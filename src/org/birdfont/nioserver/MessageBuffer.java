package org.birdfont.nioserver;

import java.io.UnsupportedEncodingException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/** A ring buffer 
* @author Johan Mattsson
*/
public class MessageBuffer {
	byte[] data;
	
	int readIndex = 0;
	int writeIndex = 0;
	
	public MessageBuffer(int capacity) {
		data = new byte[capacity];
	}
	
	/** Remove the first line from the buffer and return it as a string.
	 * @return next line or null if no line has been added to the buffer  
	 */
	public String nextLine() {
		final int FIRST_BIT = 1 << 7;
		int size = size();
		int endOfLine = -1; 
		int length = 0;
		
		for (int i = 0; i < size; i++) {
			int index = (i + readIndex) % data.length;
			if ((data[index] & FIRST_BIT) == 0) {
				if (data[index] == '\n') {
					endOfLine = index;
					length++;
					break;
				}
			}
			length++;
		}
		
		if (endOfLine == -1) {
			return null;
		}

		byte[] line = new byte[length];
		for (int i = 0; i < size; i++) {
			int index = (i + readIndex) % data.length;
			
			if (index == endOfLine) {
				break;
			}
			
			line[i] = data[index];
		}
		
		readIndex = endOfLine + 1;
		
		String nextLine = new String(line, Charset.forName("UTF-8"));
		return nextLine.trim();
	}
	
	public void clear() {
		readIndex = 0;
		writeIndex = 0;
	}
	
	public int capacity() {
		return data.length;
	}
	
	public void add(byte element) {
		if (size() + 1 >= data.length) {
			throw new BufferOverflowException();
		}
		
		data[writeIndex] = element;
		writeIndex++;
		
		if (writeIndex == data.length) {
			writeIndex = 0;
		}
	}

	public void append(MessageBuffer elements) {
		int size = elements.size();
		
		if (size() + size >= data.length) {
			throw new BufferOverflowException();
		}
		
		for (int i = 0; i < size; i++) {
			int index = (i + elements.readIndex) % elements.data.length;
			add(elements.data[index]);
		}
		
		elements.clear();
	}
	
	public void add(ByteBuffer elements) {
		if (size() + elements.remaining() >= data.length) {
			throw new BufferOverflowException();
		}
		
		while (elements.hasRemaining()) {
			add(elements.get());
		}
	}

	public void add(String text) {
		try {
			byte[] bytes;
			bytes = text.getBytes("UTF-8");
			
			for (int j = 0; j < bytes.length; j++) {
				add(bytes[j]);
			}
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
	}
	
	public void removeFirst(int bytes) {
		if (bytes > size()) {
			throw new BufferUnderflowException();
		}
		
		readIndex += bytes;
		readIndex %= data.length;
	}
	
	public ByteBuffer asByteBuffer () {
		int size = size();
		ByteBuffer buffer = ByteBuffer.allocate(size);

		for (int i = 0; i < size; i++) {
			int index = (i + readIndex) % data.length;
			buffer.put(data[index]);
		}
		
		buffer.flip();
		return buffer;
	}
	
	public int size() {
		if (writeIndex >= readIndex) {
			return writeIndex - readIndex;
		}
		
		return (writeIndex + data.length) - readIndex;
	}
}

