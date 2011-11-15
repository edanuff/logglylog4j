package com.spidertracks.loggly;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.spi.LoggingEvent;

/**
 * Currently uses an asynchronous blocking queue to write messages. Messages are
 * written to files with sequential identifiers, these sequential files are then
 * read by the reader thread. When a file is fully consumed, it is removed.
 * 
 * @author Todd Nine
 */
public class LogglyAppender extends AppenderSkeleton {

	private HttpPost poster;

	// private LogglyMessageQueue messageQ;

	private String logglyUrl;

	private int batchSize = 50;

	private String proxyHost = null;

	private int proxyPort = -1;

	private BlockingQueue<Entry> queue = new ArrayBlockingQueue<Entry>(1000);

	private final Object waitLock = new Object();

	public LogglyAppender() {
		super();
	}

	public LogglyAppender(boolean isActive) {
		super(isActive);
	}

	@Override
	public void close() {
		// Stop is a blocking call, it waits for HttpPost to finish.
		poster.stop();

	}

	@Override
	public boolean requiresLayout() {
		return true;
	}

	@Override
	protected void append(LoggingEvent event) {

		/**
		 * We always only produce to the current file. So there's no need for
		 * locking
		 */

		assert layout != null : "Cannot log, there is no layout configured.";

		String output = layout.format(event);
		Entry entry = new Entry();

		entry.setId(System.nanoTime());

		entry.setMessage(output);
		entry.setTime(System.nanoTime());

		queue.offer(entry);

		if (poster.getState() == ThreadState.STOPPED) {
			LogLog.debug("Noticed thread stopped!");
		}
	}

	/**
	 * Reads the output file directory and puts all existing files into the
	 * queue.
	 */
	@Override
	public void activateOptions() {

		if (logglyUrl == null) {
			LogLog.warn("loggly url for log queue was not set.  Please set the \"logglyUrl\" property");
		}

		poster = new HttpPost();

		Thread posterThread = new Thread(poster);
		posterThread.start();

	}

	private enum ThreadState {
		START, RUNNING, STOP_REQUESTED, STOPPED
	};

	private class HttpPost implements Runnable {

		// State variables needs to be volatile, otherwise it can be cached
		// local to the thread and stop() will never work
		volatile ThreadState curState = ThreadState.START;
		volatile ThreadState requestedState = ThreadState.RUNNING;

		final Object stopLock = new Object();

		@Override
		public void run() {

			curState = ThreadState.RUNNING;
			LogLog.debug("Loggly: background thread waiting for db");

			List<Entry> messages = new ArrayList<Entry>(batchSize);
			queue.drainTo(messages, batchSize);

			// ThreadState.STOP_REQUESTED lets us keep running until our queue
			// is empty, but stop when it is
			while ((curState == ThreadState.RUNNING)
					|| ((curState == ThreadState.STOP_REQUESTED)
							&& (messages != null) && (messages.size() > 0))) {

				if (curState == ThreadState.STOP_REQUESTED) {
					LogLog.warn("Loggly: Stop requested, emptying queue of: "
							+ messages.size());
				}

				if ((messages == null) || (messages.size() == 0)) {

					// We aren't synchronized around the database, because that
					// doesn't matter
					// this synchronization block just lets us be notified
					// sooner if a new message comes it
					synchronized (waitLock) {
						try {
							// nothing to consume, sleep for 1 second
							waitLock.wait(1000);
						} catch (InterruptedException e) {
							if (curState == ThreadState.STOP_REQUESTED) {
								// no-op, we are shutting down
							} else {
								// an error
								errorHandler
										.error("Unable to sleep for 1 second in queue consumer",
												e, 1);
							}
						}
					}

				} else {

					try {
						int response = sendData(messages);
						switch (response) {
						case 200:
						case 201: {
							break;
						}
						case 400: {
							LogLog.warn("loggly: bad request dumping message");
						}
						default: {
							LogLog.error("Received error code " + response
									+ " from Loggly servers.");
						}
						}
					} catch (IOException e) {
						errorHandler.error(String.format(
								"Unable to send data to loggly at URL %s",
								logglyUrl), e, 2);
					}
				}

				// The order of these two if statements (and the else) is very
				// important
				// If the order was reversed, we would drop straight from
				// RUNNING to STOPPED without one last 'cleanup' pass.
				// If the else was missing, we would permently be stuck in the
				// STOP_REQUESTED state.
				if (curState == ThreadState.STOP_REQUESTED) {
					curState = ThreadState.STOPPED;
				} else if (requestedState == ThreadState.STOPPED) {
					curState = ThreadState.STOP_REQUESTED;
				}

				messages = new ArrayList<Entry>(batchSize);
				queue.drainTo(messages, batchSize);

			}

			LogLog.debug("Loggly background thread is stopped.");

			synchronized (stopLock) {
				stopLock.notify();
			}
		}

		/**
		 * @return
		 */
		public ThreadState getState() {
			return curState;
		}

		/**
		 * Send the data via http post
		 * 
		 * @param message
		 * @throws IOException
		 */

		private int sendData(List<Entry> messages) throws IOException {
			URL url = new URL(logglyUrl);
			Proxy proxy = Proxy.NO_PROXY;
			if (proxyHost != null) {
				SocketAddress addr = new InetSocketAddress(proxyHost, proxyPort);
				proxy = new Proxy(Proxy.Type.HTTP, addr);
			}

			URLConnection conn = url.openConnection(proxy);
			conn.setDoOutput(true);
			conn.setDoInput(true);
			conn.setUseCaches(false);
			// conn.setRequestProperty("Content-Type",
			// "application/x-www-form-urlencoded");
			conn.setRequestProperty("Content-Type", "text/plain");
			OutputStream os = conn.getOutputStream();

			for (Entry message : messages) {
				final byte[] msgBytes = message.getMessage().getBytes();
				if (msgBytes.length < 5200) {
					conn.getOutputStream().write(msgBytes);
				} else {
					LogLog.warn("message to large for loggly - dropping msg:\n"
							+ msgBytes);
				}
			}

			os.flush();
			os.close();
			HttpURLConnection huc = ((HttpURLConnection) conn);
			int respCode = huc.getResponseCode();
			// grabbed from
			// http://download.oracle.com/javase/1.5.0/docs/guide/net/http-keepalive.html
			BufferedReader in = null;
			StringBuffer response = null;
			try {
				in = new BufferedReader(new InputStreamReader(
						conn.getInputStream()));
				response = new StringBuffer();
				int value = -1;
				while ((value = in.read()) != -1) {
					response.append((char) value);
				}
				in.close();
			} catch (IOException e) {
				try {
					response = new StringBuffer();
					response.append("Status: ").append(respCode)
							.append(" body: ");
					in = new BufferedReader(new InputStreamReader(
							huc.getErrorStream()));
					int value = -1;
					while ((value = in.read()) != -1) {
						response.append((char) value);
					}
					in.close();
					errorHandler
							.error(String
									.format("Unable to send data to loggly at URL %s Response %s",
											logglyUrl, response));
				} catch (IOException ee) {
					errorHandler.error(String.format(
							"Unable to send data to loggly at URL %s",
							logglyUrl), e, 2);

				}
			}
			return respCode;
		}

		/**
		 * Stop this thread sending data and write the last read position
		 */

		public void stop() {
			LogLog.debug("Stopping background thread");
			requestedState = ThreadState.STOPPED;

			// Poke the thread to shut it down.
			synchronized (waitLock) {
				LogLog.debug("Loggly: Waking background thread up");
				waitLock.notify();
			}

			synchronized (poster.stopLock) {
				LogLog.debug("Loggly: Waiting for background thread to stop");
				while (poster.curState != ThreadState.STOPPED) {
					try {
						poster.stopLock.wait(100);
					} catch (InterruptedException e) {
						LogLog.error("Interrupted while waiting for Http thread to stop, bailing out.");
					}
				}
			}
		}

	}

	/**
	 * ProxyHost a valid dns name or ip adresse for a proxy.
	 * 
	 * @param proxyHost
	 */
	public void setProxyHost(String proxyHost) {
		this.proxyHost = proxyHost;
	}

	/**
	 * The proxy port for a proxy
	 * 
	 * @param proxyPort
	 */
	public void setProxyPort(int proxyPort) {
		this.proxyPort = proxyPort;
	}

	/**
	 * @param logglyUrl
	 *            the logglyUrl to set
	 */
	public void setLogglyUrl(String logglyUrl) {
		this.logglyUrl = logglyUrl;
	}

	/**
	 * Set the maximum batch size for uploads. Defaults to 50.
	 * 
	 * @param batchSize
	 */
	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	/**
	 * Set the maximum queue size for uploads. Defaults to 1000.
	 * 
	 * @param batchSize
	 */
	public void setQueueSize(int queueSize) {
		queue = new ArrayBlockingQueue<Entry>(queueSize);
	}

}
