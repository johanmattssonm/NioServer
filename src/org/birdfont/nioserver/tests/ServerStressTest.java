package org.birdfont.nioserver.tests;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.Test;

import org.birdfont.nioserver.*;

// The SSL test case needs a keystore. There is one in the resource folder,
// you need to add it to the project as a source folder.

/** Tests for the server.
* @author Johan Mattsson
*/
public class ServerStressTest {
	private Server server;
	private boolean failed = false;
	private static Lock logLock = new ReentrantLock();
	private static ConcurrentLinkedQueue<Runnable> loggerTasks;
	private boolean loggerIsRunning;
	
	@Test
	public void run() {
		runTest(true);
	}
	
	public void runTest(boolean useSSL) {
		loggerTasks = new ConcurrentLinkedQueue<Runnable>();

		server = new Server();
		server.setOnlyLocalHost(true);
		ServerLog.setLogger(new ServerLogger() {
			
			@Override
			public void log(String message) {
				System.out.println("Server: " + message);	
			}
		});
		server.setUsingSSL(useSSL);
		
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		InputStream keyStore = classLoader.getResourceAsStream("testkeys");
		
		if (keyStore == null) {
			System.err.println("No keystore has been added to the project.");
			return;
		}
		
		server.setKeyStore(keyStore, "testkeys");
		
		try {
			server.setup();
		} catch (IOException e) {
			System.err.println(e.getMessage());
			System.err.println("Can't start server.");
			return;
		}

		//server.setLogLevels(ServerLogger.ERRORS);
		server.setLogLevels(ServerLog.ALL);
		
		server.start();

		loggerIsRunning = true;
		Thread logger = new Thread(() -> {
			while (loggerIsRunning) {
				Runnable task = loggerTasks.poll();

				if (task != null) {
					task.run();
				}
			}
		});
		logger.start();

		Timer watchDog = new Timer();
		watchDog.schedule(new TimerTask() {

			@Override
			public void run() {
				if (server.isRunning()) {
					System.err.println("Timeout, terminating server.");
					server.stop();
				}
			}
			
		}, 60 * 1000);

		ArrayList<Thread> testThreads = new ArrayList<Thread>();

		for (int i = 0; i < 20; i++) {
			TestConnection sender = new TestConnection(i, 100);
			Thread senderThread = new Thread(sender);
			senderThread.start();
			testThreads.add(senderThread);
		}
		
		for (Thread sender : testThreads) {
			boolean done = false;
			while (!done) {
				try {
					sender.join();
					done = true;
				} catch (InterruptedException e) {
					log("Join interrupted");
				}
			}
		}

		if (failed) {
			fail();
		}

		server.stop();

		boolean done = false;
		
		done = false;
		while (!done) {
			try {
				loggerIsRunning = false;
				logger.join();
				done = true;
			} catch (InterruptedException e) {
				log("Join interrupted");
			}
		}
	}

	public final SSLSocketFactory getSocketFactory() throws SSLException {
		try {
			X509TrustManager acceptingTrustManager = new X509TrustManager() {
				public void checkClientTrusted(X509Certificate[] certificate,
						String authType) throws CertificateException {
				}
				
				public void checkServerTrusted(X509Certificate[] certificate,
						String authType) throws CertificateException {
				}
				
				public X509Certificate[] getAcceptedIssuers() {
					return null;
				}
			};

			TrustManager[] trustManager = new TrustManager[] { acceptingTrustManager };

			SSLContext context = SSLContext.getInstance("SSL");
			context.init(null, trustManager, null);

			return (SSLSocketFactory) context.getSocketFactory();
		} catch (KeyManagementException | NoSuchAlgorithmException e) {
			senderFailure(e.getMessage());
			throw new SSLException("Can't create ssl socket.");
		}
	}

	class TestConnection implements Runnable {
		private Socket clientSocket = null;
		private String senderName;
		private PrintWriter out;
		private BufferedReader in;
		int messages;
		
		public TestConnection(int id, int messages) {
			this.messages = messages;
			senderName = "TEST" + id;
		}

		public void stop() {
			try {
				out.println("CLOSE");

				while (true) {
					String line = in.readLine();

					if (line == null || line.equals("CLOSED")) {
						break;
					}
				}

				clientSocket.close();
			} catch (IOException e) {
				senderFailure(e.getMessage());
			}
		}

		public void run() {
			try {
				if (server.isUsingSSL()) {
					SSLSocketFactory socketfactory;
					socketfactory = getSocketFactory();
					clientSocket = socketfactory.createSocket("localhost", server.getPort());
				} else {
					clientSocket = new Socket("localhost", server.getPort());
				}
			} catch (IOException exception) {
				senderFailure(exception.getMessage());
				return;
			}

			try {
				OutputStreamWriter writer;
				OutputStream outputStrem = clientSocket.getOutputStream();
				writer = new OutputStreamWriter(outputStrem, "UTF-8");
				out = new PrintWriter(new BufferedWriter(writer), true);

				InputStreamReader reader;
				InputStream inputStream = clientSocket.getInputStream();
				reader = new InputStreamReader(inputStream, "UTF-8");
				in = new BufferedReader(reader);

				for (int i = 0; i < messages && !hasFailed(); i++) {
					sendMessage(i);
				}

				stop();
			} catch (IOException e) {
				senderFailure(e.getMessage());
			} catch (ParseException e) {
				senderFailure(e.getMessage());
			}
		}

		private void sendMessage(int i) throws ParseException, IOException {
			String body = "the message " + i;
			Message message = new Message(senderName, new Date(), body);
			
			out.println(message.toString().trim());

			// wait for the message
			while (true) {
				String line;

				line = in.readLine();

				if (line == null) {
					senderFailure("Unexpected end of stream. " + clientSocket);
					return;
				}

				if (line.equals("CLOSE")) {
					senderFailure("Connection was closed by the server.");
					return;
				}
				
				Message response;

				try {
					response = Message.parseMessage(line);
				} catch (ParseException e) {
					senderFailure("Invalid message: \"" + line + "\"");
					return;
				}
				
				if (response.getSender().equals(message.getSender())) {
					if (!response.getMessagBody().equals(message.getMessagBody())) {
						senderFailure("Wrong message.");
						out.println("CLOSE");
						clientSocket.close();
					}

					break;
				}
			}
		}
	}

	private boolean hasFailed() {
		synchronized (server) {
			return failed;
		}
	}

	private void senderFailure(String message) {
		synchronized (server) {
			log("FAIL: " + message);
			failed = true;
			fail(message);
			server.stop();
		}
	}

	private static void log(String message) {
		String date = new Date().toString();
		logLock.lock();
		loggerTasks.add(() -> System.out.println(date + " " + message));
		logLock.unlock();
	}

}

