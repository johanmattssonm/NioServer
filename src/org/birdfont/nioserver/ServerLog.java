package org.birdfont.nioserver;

public abstract class ServerLog {
	/** Log level for monitoring connections. */
	public final static int CONNECTIONS = 1;

	/** Log level for debugging messages. */
	public final static int INFO = 1 << 1;

	/** Log level for network errors. */
	public final static int ERRORS = 1 << 2;

	/** All logging flags */
	public final static int ALL = CONNECTIONS | INFO | ERRORS;

	private static int logLevels = CONNECTIONS | ERRORS;

	public static ServerLogger logger = new ServerLogger() {
		
		@Override
		public void log(String message) {
			System.out.println(message);
		}
	};

	public static void setLogger(ServerLogger loggingCallback) {
		logger = loggingCallback;
	}
	
	public static void setLogLevels(int levels) {
		logLevels = levels;
	}

	public static void error(String message) {
		synchronized (logger) {
			if ((logLevels & ERRORS) != 0) {
				logger.log(message);
			}			
		}
	}

	public static void info(String message) {
		synchronized (logger) {
			if ((logLevels & INFO) != 0) {
				logger.log(message);
			}
		}
	}

	public static void connection(String message) {
		synchronized (logger) {
			if ((logLevels & CONNECTIONS) != 0) {
				logger.log(message);
			}
		}
	}
}
