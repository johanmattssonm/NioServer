package org.birdfont.nioserver;

import java.io.IOException;
import java.io.InputStream;

/** A small server using java nio framwork and ssl
 * @author Johan Mattsson
 */
public class Main {

	public static void main(String[] args) {
		try {
			Server server = new Server();
			
			// TODO: load a keystore to the ssl service, you can use Java Keytool to generate the key
			InputStream keyStore = null;
			
			if (keyStore == null) {
				System.err.println("No keystore has been added to the project.");
				return;
			}
			
			server.setKeyStore(keyStore, "testkeys");
			server.setup();
			
			server.start();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
