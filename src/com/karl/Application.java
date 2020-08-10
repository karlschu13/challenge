package com.karl;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.cli.*;

/**
  TODOs:
    2. Clean up code
 */

public class Application {

    boolean running = true;
    AtomicInteger numUnique = new AtomicInteger();
    AtomicInteger numUniqueThisPeriod = new AtomicInteger();
    AtomicInteger numDupes = new AtomicInteger();

    LinkedBlockingQueue<Integer> receivedNumbers = new LinkedBlockingQueue<Integer>();
    ServerSocket server = null;
    Timer loggingTimer = null;

    boolean DEBUG = false;

    boolean useFakeConsumer;
    int maxConnections;
    int port;
    String logFileName;

    public Application(int port, int maxConnections, String logFileName, boolean useFakeConsumer) {
	this.port = port;
	this.logFileName = logFileName;
	this.maxConnections = maxConnections;
	this.useFakeConsumer = useFakeConsumer;
    }

    /**
       A fake consumer to bypass reading data from a socket and stress test the quee
       reading, uniqueness checkng, and log writing.
     */
    class FakeConsumer implements Runnable {
	int clientNum = -1;
	Socket socket = null;
    
	public FakeConsumer(int clientNum, Socket socket) {
	    this.clientNum = clientNum;
	    this.socket = socket;
	}
	
	@Override
	public void run() {
	    int numNums = 4000000;
	    int MAX_NUM = 1000000000;
	    Random r = new Random();
	    System.out.println("Client " + clientNum + " generating " + numNums + " numbers");
	    for (int i = 0; i < numNums; i++) {
		if (i % 500000 == 0) {
		    System.out.println("Client " + clientNum + " generating # " + i + " of " + numNums);
		}
		int number = r.nextInt(MAX_NUM);
		receivedNumbers.offer(number);
	    }
	    // Simulate receipt of "terminate"
	    receivedNumbers.offer(-13);
	    shutdown();
	}
    }

    /**
       The shutdown method prints out a report and kills the server socket.
     */
    void shutdown() {
	try {
	    printStatus("AT TERMINATE: ");
	    // This will kill any blocking accept
	    this.server.close();
	} catch (IOException e) {
	    e.printStackTrace();
	}
    }

    /**
       Consumer for reading the sockets.
     */
    class Consumer implements Runnable {
	int clientNum = -1;
	Socket socket = null;
    
	public Consumer(int clientNum, Socket socket) {
	    this.clientNum = clientNum;
	    this.socket = socket;
	}
	
	@Override
	public void run() {
	    handleConnection(this.socket, this.clientNum);
	}

	/**
	   Read from a socket, 10 bytes at a time, until the socket is closed or
	   a timeout occurs, or bad data is read, or the terminate signal is sent;
	   then close the socket.

	   If the terminate occurs, put a negative number on the queue and call
	   shutdown().
	 */
	void handleConnection(Socket socket, int clientNum) {
	    String sep = System.getProperty("line.separator");
	    String termination = "terminate" + sep;
	    String line = null;
	    byte[] byteBuffer = new byte[10];
	    try {
		DataInputStream din = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
		boolean noneRead = true;
		boolean unterminated = true;
		while (unterminated) {
		    try {
			// Try to read 10 bytes, blocking; if the producer writes less than that and closes,
			// and EOFException is thrown and we're free.  If the producer writes less and then
			// staysopen but never writes more, we will hang here until someone does a terminate.
			// If all connections do this, it could lock up the system.  "Fixing" this with a
			// Socket timeout.
			din.readFully(byteBuffer);
			line = new String(byteBuffer);
			if (line == null || line.isEmpty()) {
			    System.out.println(clientNum + ": Got null!");
			    continue;
			} else {
			    if (DEBUG) {
				System.out.println(clientNum + ": line=" + line + ", length=" + line.length());
			    }
			    // Only terminate if this is the only (first) piece of data on the socket.
			    // Otherwise this will be passed on and parsed as an integer, which will
			    // then show as a "Got bad input" message.
			    if (line.equals(termination) && noneRead) {
				unterminated = true;
				receivedNumbers.offer(-13);
				shutdown();
				break;
			    } else if (line.length() != 10) {
				System.out.println("Got bad line length of " + line.length() + ": '" + line + "'");
				throw new NumberFormatException("Illegal line length");
			    } else {
				if (!sep.equals(line.substring(9, 10))) {
				    System.out.println("Got bad new line '" + line.substring(8, 10) + "'");
				    throw new NumberFormatException("Illegal line length");
				}
				// This parseInt will catch any leading non-zero spaces
				int num = Integer.parseInt(line.substring(0, 9));
				receivedNumbers.offer(num);
			    }
			}
			noneRead = false;
		    } catch(SocketTimeoutException stoe) {
			System.out.println(clientNum + ": Client timed out");
			break;
		    } catch(EOFException eof) {
			System.out.println(clientNum + ": Client done");
			break;
		    }
		}
	    } catch (NumberFormatException nfe) {
		System.out.println("Got bad input '" + line + "'");
	    } catch (IOException e) {
		e.printStackTrace();
	    }
	    try {
		socket.close();
	    } catch (IOException e) {
		e.printStackTrace();
	    }
	}
    }

    /**
       This interface is for generalizing the way to store the unique numbers.
       My initial attempt was with BitSet, but I wanted to explore other options
       for the NumberCruncher to use and could easily swap in a new store.
       Sadly, I ran out of time.
     */
    interface NumberStore {
	public int get(int index);
	public void set(int index);
    }

    /**
       BitStore uses a BitSet for keeping track of unique numbers.  See README for
       why.
       This is not thread safe, and if the design were to change to have multiple
       consumers, then this could be tweaked to have multiple BitSets for ranged of
       numbers and have a consumer per BitSet.
     */
    class BitStore implements NumberStore {
	// If the "maxnum" command line option were allowed, it would be used here.
	int N_BITS = 1000000000;
	BitSet b1 = new BitSet(N_BITS);

	public int get(int index) {
	    int foo = b1.get(index) ? 1 : 0;
	    return foo;
	}
	
	public void set(int index) {
	    b1.set(index);
	}
    }

    /**
       The NumberCruncher creates the log file, then consumes numbers off the
       queue until a negative number is encountered (this is put onto the queue
       whenever a proper "terminate" is encountered by the socket readers.
     */
    public class NumberCruncher implements Runnable {
	FileWriter wr = createFile(logFileName);
	
	FileWriter createFile(String fileName) {
	    try {
		File fac = new File(fileName);
		if (!fac.exists()) {
		    fac.createNewFile();
		}
		return new FileWriter(fac);
	    } catch (IOException e) {
		e.printStackTrace();
		return null;
	    }
	}

	@Override
	public void run() {
	    consumeNumbers();
	    System.out.println("NumberCruncher finished");
	}

	/**
	   Here is where the queue of (possibly) non-unique numbers is
	   processed.  We read from the queue until a negative number
	   is encountered, which will be put there when a proper
	   "terminate" comes over a socket.  When reading a number,
	   it is checked to see if we already have it in the NumberStore;
	   if not we put it in the store and write it to the file.
	 */
	void consumeNumbers() {
	    String sep = System.getProperty("line.separator");
	    Integer myNum = 0;
	    NumberStore numStore = new BitStore();
	    try {
		while (myNum >= 0) {
		    try {
			myNum = receivedNumbers.take();
		    } catch (InterruptedException e) {
			// Handle this more appropriately
		    }
		    if (myNum > 0) {
			if (DEBUG) { System.out.println("Consuming " + myNum); }
			if (numStore.get(myNum) != 0) {
			    numDupes.getAndIncrement();
			} else {
			    numStore.set(myNum);
			    numUnique.getAndIncrement();
			    numUniqueThisPeriod.getAndIncrement();
			    // Hmm, would buffering help any?
			    wr.write(myNum+sep);
			}
		    }
		}
		wr.close();
	    } catch (IOException e) {
		e.printStackTrace();
		try {
		    // Try to close in case the original exception came from a write
		    wr.close();
		} catch (IOException e2) {
		} 

	    }
	    System.out.println("# dupes: " + numDupes);
	    if (loggingTimer != null) {
		loggingTimer.cancel();
		loggingTimer.purge();
	    }
	    printStatus("AT END: ");
	}
    }

    /**
       Actually print out the statistics.

       prefix - Extra string to place before the report, useful for
                out of band reports at termination and shutdown.
     */
    void printStatus(String prefix) {
	DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
	Date date = new Date();
	System.out.print(dateFormat.format(date) + " ");
	System.out.println(prefix + "Received " + numUniqueThisPeriod.get() +
			   " unique numbers, " + numDupes.get() + " duplicates. Unique total: " +
			   numUnique.get());
    }
 
    /**
       The recurring task to print out the statistics and reset the per-report variables.
     */
    public class ScheduledTask extends TimerTask {

	public void run() {
	    printStatus("");
	    numUniqueThisPeriod.set(0);
	    numDupes.set(0);
	}
    }

    /**
       The heart of Application:
         Launch the number cruncher (queue consumer)
	 Launch the logging task
	 Accept socket connections until terminated
     */
    void runService() {
	System.out.println("Starting service with max connections: " + maxConnections);
	Thread numberCruncher = new Thread(new NumberCruncher());
	numberCruncher.start();
	loggingTimer = new Timer(); // Instantiate Timer Object
	ScheduledTask st = new ScheduledTask(); // Instantiate SheduledTask class
	loggingTimer.schedule(st, 0, 10000); // Create Repetitively task for every 1 secs
	Thread consumer1;
	try {
	    int numClients = 0;
	    this.server = new ServerSocket(port, maxConnections);
	    // running gets set to false when a "terminate" is properly received
	    while (running) {
		System.out.println("Waiting to accept...");
	    	Socket socket = server.accept();
		// TODO: This should be configurable
		socket.setSoTimeout(1000);
		System.out.println("Handling connection " + ++numClients);
		if (useFakeConsumer) {
		    consumer1 = new Thread(new FakeConsumer(numClients, socket));
		} else {
		    consumer1 = new Thread(new Consumer(numClients, socket));
		}
		consumer1.start();
	    }
	    System.out.println("# clients: " + numClients);
	} catch (SocketException e) {
	    System.out.println("Stopping service...");
	} catch (IOException e) {
	    e.printStackTrace();
	}
    }

    /**************************************************************************
     * Command Line option handling
     **************************************************************************/
    static Options createOptions() {
	Options options = new Options();
        //options.addOption("m", "maxnum", true, "maximum number size; i.e. 1000000000 for 9-digit numbers");
        options.addOption("p", "port", true, "server socket port");
        options.addOption("c", "connections", true, "maxumum # of simultaneous connections");
        options.addOption("l", "log", true, "log file name");
        options.addOption("f", "fakeconsumer", false,
			  "run with a fake (local) consumer for stress testing algorithm");
	return options;
    }

    static boolean getBoolOption(CommandLine cmd, String name) {
	return cmd.hasOption(name);
    }

    static int getIntOption(CommandLine cmd, String name, int defaultValue, Options options) {
	String optionStr = cmd.getOptionValue(name);
	System.out.println("optionStr=" + optionStr + "default=" + defaultValue);
	if (optionStr != null) {
	    try {
		return Integer.parseInt(optionStr);
	    } catch (NumberFormatException e) {
		HelpFormatter formatter = new HelpFormatter();
		System.out.println("Bad value '" + optionStr + "' for arg " + name);
		formatter.printHelp("Application", options);
		System.exit(1);
		return 0;
	    }
	} else {
	    return defaultValue;
	}
    }
    
    /**
       Instantiate an Application with parameters and start it.
    */
    public static void main(String[] args) {
	int PORT = 4000;
	int MAX_CONNECTIONS = 5;
	String FILE_NAME = "numbers.log";
	Options options = createOptions();

	CommandLineParser parser = new DefaultParser();
	CommandLine cmd = null;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
	    HelpFormatter formatter = new HelpFormatter();
            System.out.println(e.getMessage());
            formatter.printHelp("Application", options);
            System.exit(1);
        }

	Application myApp = new Application(getIntOption(cmd, "port", PORT, options),
					    getIntOption(cmd, "connections", MAX_CONNECTIONS, options),
					    cmd.getOptionValue("log", FILE_NAME),
					    getBoolOption(cmd, "fakeconsumer")
					    );
	myApp.runService();
    }
}
