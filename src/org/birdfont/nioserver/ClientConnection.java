package org.birdfont.nioserver;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

/** A client connection on the server.
* @author Johan Mattsson
*/
class ClientConnection {
	public final static int DEFAULT_BUFFER_SIZE = 2048;
	private SocketChannel socketChannel;
	private MessageBuffer in = new MessageBuffer(DEFAULT_BUFFER_SIZE);
	private MessageBuffer out = new MessageBuffer(DEFAULT_BUFFER_SIZE);
	private MessageBuffer sslOutput = new MessageBuffer(DEFAULT_BUFFER_SIZE);
	private MessageBuffer sslInput = new MessageBuffer(DEFAULT_BUFFER_SIZE);
	private boolean closing = false;
	private SSLEngine sslEngine;
	private ByteBuffer sslWorkspace = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
	
	public ClientConnection(SocketChannel socketChannel) {
		this.socketChannel = socketChannel;
	}

	public void setClosing(boolean closing) {
		this.closing = closing;
	}

	public boolean isClosing() {
		return closing;
	}

	public SocketChannel getSocketChannel() {
		return socketChannel;
	}

	public MessageBuffer getIn() {
		return in;
	}

	public MessageBuffer getOut() {
		return out;
	}

	public SSLEngine getSSLEngine() {
		return sslEngine;
	}

	public void setSSLEngine(SSLEngine engine) {
		sslEngine = engine;
	}
	
	public MessageBuffer getSSLOutput() {
		return sslOutput;
	}

	public MessageBuffer getSSLInput() {
		return sslInput;
	}
	
	public void clearBuffers() {
		sslInput.clear();
		sslOutput.clear();
		in.clear();
		out.clear();
	}
	
	@Override
	public String toString() {
		return socketChannel.toString();
	}

	public boolean hasEmptyOutput() {
		return getSSLOutput().size() == 0 && getOut().size() == 0;
	}

	public ByteBuffer getWorkspace() {
		sslWorkspace.clear();
		return sslWorkspace;
	}

	public void resizeBuffers(int packetSize) {
		MessageBuffer old;
		
		old = in;
		in = new MessageBuffer(4 * packetSize);
		in.append(old);

		old = out;
		out = new MessageBuffer(4 * packetSize);
		in.append(out);

		old = sslInput;
		sslInput = new MessageBuffer(4 * packetSize);
		in.append(out);

		old = sslOutput;
		sslOutput = new MessageBuffer(4 * packetSize);
		in.append(out);
		
		sslWorkspace = ByteBuffer.allocate(packetSize);
	}

	public void close() {
		Socket socket = socketChannel.socket();

		try {	
			socketChannel.close();

			if (socket != null) {
				ServerLog.connection("Closing connection " + socket);
				socket.close();
			}
		} catch (IOException exception) {
			ServerLog.error("Can't close socket " + socket);
		}
	}

}
