package org.birdfont.nioserver.tests;

import org.birdfont.nioserver.*;
import static org.junit.Assert.*;
import org.junit.Test;


/** Tests for the ring buffer 
* @author Johan Mattsson
*/
public class MessageBufferTest {

	@Test
	public void test() {
		MessageBuffer buffer1 = new MessageBuffer(255);
		MessageBuffer buffer2 = new MessageBuffer(255);
		
		for (int i = 0; i < 1000; i++) {
			String test = "TEST " + i + "\n"; 
			
			buffer1.add(test);
			
			if (!test.trim().equals(buffer1.nextLine())) {
				fail("Buffer error");
			}
		}
		
		String test1 = "test 1\n";
		buffer1.add(test1);
		buffer2.append(buffer1);
		
		if (!test1.trim().equals(buffer2.nextLine())) {
			fail("Buffer error, append does not work.");
		}
	}

}
