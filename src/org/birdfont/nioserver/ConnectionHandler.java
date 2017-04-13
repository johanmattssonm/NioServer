package org.birdfont.nioserver;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.util.ArrayList;
import java.util.Iterator;

/** A worker thread that manages client connections in the server.
 * @author Johan Mattsson
 */
public class ConnectionHandler {
	private ArrayList<ClientConnection> connections;
	private ArrayList<ClientConnection> pendingClose;
	private SSLWorker sslWorker;
	private Server server;
	
	public ConnectionHandler(SSLWorker sslWorker, Server server) {
		this.sslWorker = sslWorker;
		this.server = server;
		connections = new ArrayList<ClientConnection>();
		pendingClose = new ArrayList<ClientConnection>();
	}

	public synchronized void addConnection(ClientConnection connection) {
		connections.add(connection); 
	}

	public synchronized void disconnectAllClients(Server server) {
		// ensure that all pending close messages are sent
		processCloseMessages(server); 

		if (connections.size() > 0) {
			ServerLog.info("Sending close message to " + connections.size() + " clients.");
		}

		sendMessage("CLOSE");

		for (ClientConnection connection : connections) {
			pendingClose.add(connection);
		}

		processCloseMessages(server);
	}

	public synchronized void removeConnection(ClientConnection connection) {
		connections.remove(connection);
	}

	public synchronized void closeDisconnectedSockets() {
		Iterator<ClientConnection> iterator = pendingClose.iterator();

		while (iterator.hasNext()) {
			ClientConnection connection = iterator.next();

			synchronized (connection) {
				if (connection.hasEmptyOutput()) {
					removeConnection(connection);
					connection.close();
					iterator.remove();
				}
			}
		}
	}

	public synchronized void sendMessage(String message) {
		for (ClientConnection connection : connections) {
			try {
				synchronized (connection) {
					if (!connection.isClosing()) {
						sendMessage(connection, message.trim());
					}
				}
			} catch (IOException exception) {
				ServerLog.error(exception.getMessage() + " (sendMessage) " + connection);
			}
		}
	}

	private void sendMessage(ClientConnection connection, String message) throws IOException {
		MessageBuffer buffer = connection.getOut();

		try {
			buffer.add(message + "\n");
			
			if (server.isUsingSSL()) {
				sslWorker.handleOutput(connection);
			} else {
				server.requestWrite(connection);
			}
		} catch (BufferOverflowException bufferOverflow) {
			ServerLog.error("Output buffer is full for " + connection);
			connection.close();
		}
	}

	/** Process all remaining close messages in shutdown procedure. */ 
	private synchronized void processCloseMessages(Server server) {
		// wait until close message is sent
		long time = System.currentTimeMillis();
		while (pendingClose.size() > 0) { 
			sslWorker.processInput();
			sslWorker.processOutput();

			server.processConnections();

			if (System.currentTimeMillis() - time > 10 * 1000) {
				ServerLog.error("Timeout in terminate server.");
				break;
			}
		}
	}

	public synchronized void sendClosedMessage(ClientConnection connection) {
		try {
			connection.setClosing(true);
			sendMessage(connection, "CLOSED");
			pendingClose.add(connection);
		} catch (IOException e) {
			ServerLog.error(e.getMessage() + " " + connection);
			connection.close();
		}
	}
}
