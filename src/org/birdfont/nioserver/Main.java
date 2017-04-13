package org.birdfont.nioserver;

import java.io.IOException;

/** A small server using java nio framwork and ssl
 * @author Johan Mattsson
 */
public class Main {

	public static void main(String[] args) {
		try {
			Server server = new Server();
			server.setup();
			server.start();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
