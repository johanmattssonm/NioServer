package org.birdfont.nioserver;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.text.ParseException;
import java.util.Iterator;
import java.util.Set;

/** A non-blocking MyChat server.
* @author Johan Mattsson
*/
public class Server implements Runnable {
	private int port = 45489;
	private ServerSocketChannel serverSocketChannel;
	private Selector socketSelector;
	private boolean running = false; 
	private boolean local = true;
	private Thread mainServerThread;
	private SSLWorker sslWorker;
	private boolean usingSSL = true;
	private byte[] keyStore = null;
	private String keyPassphrase = "";
	private ConnectionHandler connectionHandler;
	
	public void setup() throws IOException {
		sslWorker = new SSLWorker(this);		
		createServerSocket();
	}

	public void start() {
		running = true;
		ServerLog.info("Starting server.");
		mainServerThread = new Thread(this);
		sslWorker.start();
		mainServerThread.start();
		connectionHandler = new ConnectionHandler(sslWorker, this);
	}
	
	public void stop() {
		Thread thread;
		ServerLog.info("Shutting down server.");
		
		synchronized (this) {
			if (!running) {
				ServerLog.error("Server is not running.");
				return;
			}

			running = false;
			thread = mainServerThread;
		}

		boolean done = false;
		while(!done) {
			try {
				thread.join();
				done = true;
			} catch (InterruptedException e) {
				ServerLog.info("Server thread was interrupted.");
			}
		}
		
		ServerLog.info("Server has exited.");
	}
	
	public void setUsingSSL(boolean ssl) {
		usingSSL = ssl;
	}

	public synchronized boolean isUsingSSL() {
		return usingSSL;
	}

	public void setLogLevels(int levels) {
		ServerLog.setLogLevels(levels);
	}
	
	public synchronized boolean isRunning() {
		return running;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public synchronized int getPort() {
		return port;
	}

	public void setOnlyLocalHost(boolean local) {
		this.local = local;
	}

	public synchronized String getKeyPassphrase() {
		return keyPassphrase;
	}
	
	public void setKeyStore(InputStream keyStoreStream, String passphrase) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
		
		this.keyPassphrase = passphrase;
		
		// copy key store to a byte array
		int bytes;
		try {
			do {
				bytes = keyStoreStream.read(buffer);
				if (bytes != -1) {
					out.write(buffer);
				}
			} while (bytes != -1);

			out.flush();
		} catch (IOException e) {
			ServerLog.error(e.getMessage());
		}
		
		keyStore = out.toByteArray();
	}

	synchronized InputStream getKeyStoreStream() throws FileNotFoundException, IOException {
		if (keyStore == null) {
			return null;
		}
		
		return new InputStream () {
			private byte[] keyStoreData = keyStore;
			int position = 0;
			
			@Override
			public int read() throws IOException {
				if (position >= keyStoreData.length) {
					return -1;
				}
				
				int data = keyStoreData[position] & 0xFF;
				position++;
				return data;
			}
		};
	}
	
	public void run() {
		if (serverSocketChannel == null) {
			ServerLog.error("No server socket has been created.");
			return;
		}

		while (true) {
			synchronized (this) {
				if (!running) {
					break;
				}
			}

			processConnections();
		}

		terminate();
	}

	private synchronized void terminate() {
		try {
			if (serverSocketChannel != null) {
				serverSocketChannel.close();
			}
		} catch (IOException exception) {
			ServerLog.error(exception.getMessage() + " (server socket)");
		}

		sslWorker.stop();
		ServerLog.info("Disconnecting all clients.");
		connectionHandler.disconnectAllClients(this);

		try {
			if (socketSelector.isOpen()) {
				socketSelector.close();
			}
		} catch (IOException exception) {
			ServerLog.error(exception.getMessage() + " (selector)");
		}
		
		running = false;
	}

	private void createServerSocket() throws IOException {
		serverSocketChannel = ServerSocketChannel.open();
		serverSocketChannel.configureBlocking(false);

		InetSocketAddress address;
		int port = getPort();

		if (local) {
			address = new InetSocketAddress("localhost", port);
		} else {
			address = new InetSocketAddress(port);
		}

		serverSocketChannel.bind(address);

		socketSelector = Selector.open();
		serverSocketChannel.register(socketSelector, SelectionKey.OP_ACCEPT);
	}

	private synchronized void acceptConnection() {
		try {
			SocketChannel incoming = serverSocketChannel.accept();
			Selector selector = getSocketSelector();
			incoming.configureBlocking(false);
			SelectionKey key = incoming.register(selector, SelectionKey.OP_READ);
			ClientConnection connection = new ClientConnection(incoming);

			if (usingSSL) {
				sslWorker.createSSLEngine(connection);
			}

			key.attach(connection);
			
			connectionHandler.addConnection(connection);
			
			Socket socket = incoming.socket();
			ServerLog.connection("New connection " + socket);
		} catch (IOException exception) {
			ServerLog.error(exception.getMessage() + " when accepting connection");
		}
	}

	private void processInput(SelectionKey socketKey) {
		SocketChannel socketChannel = (SocketChannel) socketKey.channel();
		ClientConnection connection;
		connection = (ClientConnection) socketKey.attachment();

		synchronized (connection) {
			try {
				if (connection.isClosing()) {
					return;
				}
				
				if (!socketChannel.isOpen() || !socketChannel.isConnected()) {
					ServerLog.info("Connection is not open.");
					connection.close();
					return;
				}

				ByteBuffer data = connection.getWorkspace();
				int size = socketChannel.read(data);

				if (size < 0) {
					ServerLog.info("End of stream on " + socketChannel);
					connection.close();
					return;
				}

				MessageBuffer buffer;
				
				if (isUsingSSL()) {
					buffer = connection.getSSLInput();
				} else {
					buffer = connection.getIn();
				}
				
				if (buffer.size () + size > buffer.capacity()) {
					ServerLog.error("SSL input buffer is full. Closing connection.");
					connection.close();
					return;
				}

				data.flip();
				buffer.add(data);

				if (!isUsingSSL()) {	
					requestRead(connection);
				} else {
					synchronized (this) {
						sslWorker.handleInput(connection);
					}
				}
			} catch (IOException exception) {
				ServerLog.error(exception.getMessage() + " for connection " + socketChannel);
				connection.close();
			}
		}	
	}

	public void requestWrite(ClientConnection connection) {
		try {
			
			if (!isRunning()) {
				return;
			}
			
			boolean hasData;
			
			if (isUsingSSL()) {
				hasData = connection.getSSLOutput().size() > 0;
			} else {
				hasData = connection.getOut().size() > 0;
			}
			
			if (hasData) {
				SocketChannel channel = connection.getSocketChannel();
				SelectionKey key = channel.keyFor(getSocketSelector());
	
				if (key == null) {
					ServerLog.error("No key for " + channel);
					connection.clearBuffers();
					getConnectionHandler().removeConnection(connection);
					return;
				}
	
				key.interestOps(SelectionKey.OP_WRITE);
			}
		} catch (CancelledKeyException exception) {
			ServerLog.info("Cancelled key for " + connection);
		}
	}

	private synchronized Selector getSocketSelector() {
		return socketSelector;
	}

	void requestRead(ClientConnection connection) {
		MessageBuffer buffer = connection.getIn();
		SocketChannel channel = connection.getSocketChannel();

		try {
			String nextLine = "";

			while(nextLine != null) {
				nextLine = buffer.nextLine();

				if (nextLine != null && !nextLine.equals("")) {
					if (nextLine.equals("CLOSE")) {
						ServerLog.info("Client has quit " + channel);
						getConnectionHandler().sendClosedMessage(connection);
						return; 
					}

					Message message = Message.parseMessage(nextLine);

					if (!message.getMessagBody().equals("")) { // ignore empty messages
						getConnectionHandler().sendMessage(nextLine);
					}
				}
			}
		} catch (ParseException parserException) {
			ServerLog.error("Invalid message from " + channel);
			connection.close();
		}
	}

	private void processOutput(SelectionKey key) {
		SocketChannel channel = (SocketChannel) key.channel();
		ClientConnection connection;
		connection = (ClientConnection) key.attachment();

		synchronized (connection) {
			MessageBuffer buffer;
			
			if (isUsingSSL()) {
				buffer = connection.getSSLOutput();
			} else {
				buffer = connection.getOut();
			}
			
			ByteBuffer data = buffer.asByteBuffer();

			if (!channel.isOpen()) {
				ServerLog.info("Channel is closed in write to " + connection);
				return;
			}
			
			if (buffer.size() == 0) {
				ServerLog.error("No data in message buffer.");
				return;
			}

			try {
				int written = channel.write(data);

				if (written <= 0) {
					ServerLog.error("Nothing written.");
					stop();
				}

				if (data.hasRemaining()) {
					requestWrite(connection);
				} else {
					key.interestOps(SelectionKey.OP_READ);
				}

				buffer.removeFirst(written);
			} catch (IOException e) {
				ServerLog.error(e.getMessage() + " in wite to " + channel.socket());
				connection.close();
			}
		}
	}
	
	void processConnections() {
		int selectedKeys = 0;

		try {
			selectedKeys = getSocketSelector().select(100);
		} catch (IOException exception) {
			ServerLog.error(exception.getMessage());
			return;
		}
		getConnectionHandler().closeDisconnectedSockets();
		
		if (selectedKeys <= 0) {
			return;
		}

		Set<SelectionKey> keys;
		
		try {
			Selector selector = getSocketSelector();
			keys = selector.selectedKeys();
		} catch (ClosedSelectorException selectorException) {
			ServerLog.error("Selector is closed.");
			return;
		}

		Iterator<SelectionKey> iterator = keys.iterator();

		while (iterator.hasNext()) {
			try {
				SelectionKey key = iterator.next();
				
				if (key.isValid()) {
					int readyOperations = key.readyOps();

					if ((readyOperations & SelectionKey.OP_ACCEPT) != 0) {
						acceptConnection();
					} else if ((readyOperations & SelectionKey.OP_READ) != 0) {
						processInput(key);
					} else if ((readyOperations & SelectionKey.OP_WRITE) != 0) {
						processOutput(key);
					}	
				}
			} catch (CancelledKeyException exception) {
				ServerLog.info("Socket operation cancelled.");
			}

			iterator.remove();
		}
	}

	synchronized ConnectionHandler getConnectionHandler() {
		return connectionHandler;
	}
}
