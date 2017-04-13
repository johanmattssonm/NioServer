package org.birdfont.nioserver.tests;

import org.birdfont.nioserver.*;
import org.junit.Test;

public class PlainTextServerTest {

	@Test
	public void test() {
		ServerStressTest test = new ServerStressTest();
		test.runTest(false);
	}

}
