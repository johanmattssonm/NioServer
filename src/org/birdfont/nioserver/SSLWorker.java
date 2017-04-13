package org.birdfont.nioserver;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

/** A worker thread for handling SSL encryption.
* @author Johan Mattsson
*/
public class SSLWorker implements Runnable {
	Server server;
	Thread workerThread;
	Lock taskLock = new ReentrantLock();
	Condition hasTask = taskLock.newCondition();
	SSLContext sslContext;
	boolean running;
	Thread handshaker;
	
	static LinkedBlockingQueue<Runnable> handshakeTask = new LinkedBlockingQueue<>();
	static Queue<ClientConnection> in = new LinkedBlockingQueue<>();
	static Queue<ClientConnection> out = new LinkedBlockingQueue<>();
	static boolean useSSL;
	
	public SSLWorker(Server server) throws SSLException {
		this.server = server;

		useSSL = server.isUsingSSL();
		
		if (useSSL) {
			try {
				sslContext = SSLContext.getInstance("SSL");

				KeyStore keys = KeyStore.getInstance("JKS");
				KeyStore trustStore = KeyStore.getInstance("JKS");

				char[] passphrase = server.getKeyPassphrase().toCharArray();
				
				if (server.getKeyStoreStream() == null) {
					throw new SSLException("No keystore provided.");
				}
				
				keys.load(server.getKeyStoreStream(), passphrase);
				trustStore.load(server.getKeyStoreStream(), passphrase);
				
				KeyManagerFactory keyManager = KeyManagerFactory.getInstance("SunX509");
				keyManager.init(keys, passphrase);

				TrustManagerFactory trustManager = TrustManagerFactory.getInstance("SunX509");
				trustManager.init(trustStore);

				sslContext.init(keyManager.getKeyManagers(), trustManager.getTrustManagers(), null);
			} catch (IOException | UnrecoverableKeyException | KeyStoreException 
					| CertificateException | NoSuchAlgorithmException
					| KeyManagementException exception) {
				throw new SSLException("SSL certificate problem. " + exception.getMessage());
			}
		}
	}

	public void createSSLEngine(ClientConnection connection) {
		try {
			connection.setSSLEngine(startSSLEngine());
		} catch (CertificateException | NoSuchAlgorithmException exception) {
			ServerLog.error("Can't start SSL engine. " + exception.getMessage());
		}
	}

	private SSLEngine startSSLEngine() throws NoSuchAlgorithmException, CertificateException {
		SSLEngine sslEngine = sslContext.createSSLEngine();
		sslEngine.setUseClientMode(false);
		sslEngine.setNeedClientAuth(false);
		return sslEngine;
	}

	public void handleOutput(ClientConnection connection) {	
		taskLock.lock();
		out.add(connection);
		hasTask.signal();
		taskLock.unlock();
	}

	public void handleInput(ClientConnection connection) {
		taskLock.lock();
		in.add(connection);
		hasTask.signal();
		taskLock.unlock();
	}

	private void processOutput(ClientConnection connection) throws SSLException {
		SSLEngineResult result;

		MessageBuffer plainTextOut = connection.getOut();
		ByteBuffer plaintText = plainTextOut.asByteBuffer();
		SSLEngine sslEngine = connection.getSSLEngine();
		ByteBuffer sslData = connection.getWorkspace();

		MessageBuffer sslOutput = connection.getSSLOutput();

		result = sslEngine.wrap(plaintText, sslData);
		runHandshakeTasks(connection, result);

		sslData.flip();
		sslOutput.add(sslData);
		plainTextOut.removeFirst(result.bytesConsumed());
		
		synchronized (this) {
			server.requestWrite(connection);
		}
		
		switch (result.getStatus()) {
		case BUFFER_OVERFLOW:
			int packetSize = sslEngine.getSession().getPacketBufferSize();
			ServerLog.info("Resize buffer in SSL wrap, packet size: " + packetSize);
			connection.resizeBuffers(packetSize);
			out.add(connection);
			break;
		case BUFFER_UNDERFLOW:
			ServerLog.info("SSL wrap needs more data");
			break;
		case CLOSED:
			return;
		default:
			break;
		}

		if (plainTextOut.size() > 0) {
			out.add(connection);
		} else if (connection.isClosing()) {
			sslEngine.closeOutbound();
			processOutput(connection);
		}
	}

	public void processOutput() {
		ClientConnection connection;
		do {
			connection = null;

			if (!out.isEmpty()) {
				connection = out.poll();
			}

			if (connection != null) {
				try {
					synchronized (connection) {
						processOutput(connection);
					}
				} catch (SSLException sslException) {
					ServerLog.error(sslException.getMessage());
					connection.close();
				} catch (BufferOverflowException exception) {
					ServerLog.error("SSL output buffer is full for " + connection);
					connection.close();
				}
			}
		} while (connection != null);
	}
	
	private void processInput(ClientConnection connection) throws SSLException {
		SSLEngineResult result;
		MessageBuffer sslInput;

		sslInput = connection.getSSLInput();

		if (sslInput.size() == 0) {
			return;
		}

		ByteBuffer plaintText = connection.getWorkspace();
		sslInput = connection.getSSLInput();
		ByteBuffer sslData = sslInput.asByteBuffer();
		SSLEngine sslEngine = connection.getSSLEngine();

		result = sslEngine.unwrap(sslData, plaintText);
		plaintText.flip();

		connection.getIn().add(plaintText);
		sslInput.removeFirst(result.bytesConsumed());

		runHandshakeTasks(connection, result);

		switch (result.getStatus()) {
		case BUFFER_OVERFLOW:
			ServerLog.info("Resize buffer in SSL unwrap.");
			int packetSize = sslEngine.getSession().getPacketBufferSize();
			connection.resizeBuffers(packetSize);
			out.add(connection);
			break;
		case BUFFER_UNDERFLOW:
			ServerLog.info("SSL unwrap needs more data.");
			break;
		case CLOSED:
			ServerLog.error("SSL connection is already closed.");
			return;
		default:
			break;
		}
		
		synchronized (this) {
			server.requestRead(connection);
		}
		
		if (sslInput.size() > 0) {
			in.add(connection);
		} else if (connection.isClosing() && sslEngine.isInboundDone()) {
			sslEngine.closeInbound();
		}
	}

	public void processInput() {
		ClientConnection connection;
		do {
			connection = null;

			if (!in.isEmpty()) {
				connection = in.poll();
			}

			if (connection != null) {
				try {
					synchronized (connection) {
						processInput(connection);
					}
				} catch (SSLException sslException) {
					ServerLog.error(sslException.getMessage());
					connection.close();
				} catch (BufferOverflowException exception) {
					ServerLog.error("SSL input buffer is full for connection " + connection);
					connection.close();
				}
			}
		} while (connection != null);
	}

	public void start() {
		if (!server.isUsingSSL()) {
			ServerLog.info("SSL is turned off.");
			return;
		}

		running = true;

		handshaker = new Thread(() -> {
			boolean done = false;
			while (!done) {
				Runnable task = null;
				
				try {
					task = handshakeTask.take();
				} catch (InterruptedException e) {
					ServerLog.info("Handshaker was interrupted.");
				}

				taskLock.lock();
				if (!running)  {
					done = true;
				}
				taskLock.unlock();

				if (task != null) {
					task.run();
				}
			}
			
			ServerLog.info("SSL handshaker has exited.");
		});
		handshaker.start();

		workerThread = new Thread(this);
		workerThread.start();
	}

	public void stop() {
		if (!running) {
			return;
		}

		boolean done;

		done = false;
		while(!done) {
			try {
				taskLock.lock();
				running = false;
				hasTask.signal();
				taskLock.unlock();
				workerThread.join();
				done = true;
			} catch (InterruptedException e) {
				ServerLog.info("SSL worker was interrupted.");
			}
		}
		done = false;
		handshakeTask.add(() -> {}); // force handshaker to wake up 
		while (!done) {
			try {
				handshaker.join();
				done = true;
			} catch (InterruptedException e) {
				ServerLog.info("SSL worker was interrupted.");
			}
		}
		
		ServerLog.info("SSL worker is done.");
	}

	@Override
	public void run() {		
		while (true) {
			taskLock.lock();
			try {
				if (in.isEmpty() && out.isEmpty()) {
					hasTask.await();
				}

				if (!running) {
					taskLock.unlock();
					break;
				}
			} catch (InterruptedException e) {
				ServerLog.info("Wakeup SSL worker.");
			}
			taskLock.unlock();
			
			processOutput();
			processInput();
		}
	}

	private void runHandshakeTasks(ClientConnection connection, SSLEngineResult result) {
		SSLEngine engine = connection.getSSLEngine();

		if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
			handshakeTask.offer(() -> {
				while (true) {
					synchronized (connection) {
						Runnable task = engine.getDelegatedTask();

						if (task == null) {
							out.offer(connection);
							in.offer(connection);

							taskLock.lock();
							hasTask.signal();
							taskLock.unlock();
							break;
						}

						task.run();
					}
				}
			});
		}
	}
	
}
