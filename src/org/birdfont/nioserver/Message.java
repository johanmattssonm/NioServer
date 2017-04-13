package org.birdfont.nioserver;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Message {

	private String body;
	private Date timeStamp;
	private String sender;
	
	public Message() {
	}

	public Message(String sender, Date timeStamp, String text) {
		this.body = text;
		this.sender = sender;
		this.timeStamp = timeStamp;
	}

	public String getSender() {
		return sender;
	}

	public void setSender(String sender) {
		this.sender = sender;
	}

	public String getMessagBody() {
		return body;
	}
	
	public void getMessagBody(String body) {
		this.body = body;
	}
	
	public Date getTimeStamp() {
		return timeStamp;
	}
	
	public void setTimeStamp(Date timeStamp) {
		this.timeStamp = timeStamp;
	}

	/** Parses a message line on the form: Name YYYY-MM-dd HH:mm:ss Message
	 * @param line input line
	 * @return a new message
	 * @throws ParseException if the message is malformed
	 */
	public static Message parseMessage(String line) throws ParseException {
		int nameSeparator = line.indexOf(" ");
		
		if (nameSeparator == -1) {
			throw new ParseException("Invalid message.", nameSeparator);
		}
		
		String name = line.substring(0, nameSeparator);

		nameSeparator = line.offsetByCodePoints(nameSeparator, 1);
		
		if (nameSeparator >= line.length()) {
			throw new ParseException("Invalid message.", nameSeparator);
		}
		
		int dateSeparator = line.indexOf(" ", nameSeparator);

		if (dateSeparator == -1) {
			throw new ParseException("Invalid message.", dateSeparator);
		}
		
		dateSeparator = line.offsetByCodePoints(dateSeparator, 1);

		if (dateSeparator >= line.length()) {
			throw new ParseException("Invalid message.", dateSeparator);
		}

		dateSeparator = line.indexOf(" ", dateSeparator);

		if (dateSeparator == -1) {
			throw new ParseException("Invalid message.", dateSeparator);
		}
		
		String date = line.substring(nameSeparator, dateSeparator);
		
		dateSeparator = line.offsetByCodePoints(dateSeparator, 1);

		String messageBody;
		
		if (dateSeparator >= line.length()) { // empty message
			messageBody = "";
		} else {
			messageBody = line.substring(dateSeparator);
		}
		
		SimpleDateFormat dateFormat = getDateFormat();
		Date timeStamp = dateFormat.parse(date);
		
		return new Message(name, timeStamp, messageBody.trim());
	}
	
	public static SimpleDateFormat getDateFormat() {
		return new SimpleDateFormat("YYYY-MM-dd HH:mm:ss");
	}
	
	public String toString() {
		return sender + " " + getDateFormat().format(timeStamp) + " " + body + "\n";
	}
}

