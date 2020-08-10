import java.io.IOException;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Random;

class Client {
    public static int MAX_NUM_NUMS = 2000000;
    public static int MAX_NUM = 1000000000;
    public static int SPEEDUP_NUM = 100;
    int port = 0;
    int numSessions = 0;
    int numNums = 0;

    public Client(int port, int numSessions, int numNums) {
	this.port = port;
	this.numNums = numNums;
	this.numSessions = numSessions;
    }
	
    public class Producer implements Runnable {
	String newline = "\n"; //System.getProperty("line.separator");
	int port;
	int clientNum;
	int numNums;
	int numModulo;

	public Producer(int clientNum, int port, int numNums) {
	    this.port = port;
	    this.clientNum = clientNum;
	    this.numNums = numNums;
	    if (numNums > 500000) {
		this.numModulo = 50000;
	    } else {
		this.numModulo = this.numNums/10;
		if (this.numModulo < 1) {
		    this.numModulo = 1;
		}
	    }
	}

	void sendRandomData(DataOutputStream dos) {
	    try {
		Random r = new Random();
		System.out.println("Client " + clientNum + " generating " + numNums*SPEEDUP_NUM + " numbers");
		for (int i = 0; i < numNums; i++) {
		    if (i % this.numModulo == 0) {
			System.out.println("Client " + clientNum + " generating # " + i + "th set of " + numNums);
		    }
		    int number = r.nextInt(MAX_NUM);
		    StringBuffer dataString = new StringBuffer(String.format("%09d%s", number, newline));
		    // An attempt to speed up number generation; instead of generating 100 Randoms,
		    // just add 1 to 99 to the original, and write 100 numbers at once.
		    // Also maybe all this StringBuffer appending is slowing me down?
		    for (int j = 1; j < SPEEDUP_NUM; j++) {
			int numberX = number + j;
			dataString.append(String.format("%09d%s", numberX, newline));
		    }
		    dos.writeBytes(dataString.toString());
		    if (i % 50000 == 0 && clientNum % 13 == 0) {
			dos.writeBytes("terminate" + System.getProperty("line.separator"));
		    }
		}
	    } catch (IOException e) {
		e.printStackTrace();
	    }
	}
	
	void sendTerminate(DataOutputStream dos) {
	    try {
		System.out.println("Sending terminate");
		dos.writeBytes("terminate" + newline);
	    } catch (IOException e) {
		System.out.println("IOException stack trace");
		e.printStackTrace();
	    }
	}
	
	@Override
	public void run() {
	    try {
		Socket socket = new Socket("127.0.0.1", this.port);
		OutputStream os = socket.getOutputStream();
		DataOutputStream dos = new DataOutputStream(os);
		System.out.println("Running producer, clientNum=" + this.clientNum);
		if (this.clientNum == -1) {
		    sendTerminate(dos);
		} else {
		    sendRandomData(dos);
		}
		socket.close();
	    } catch (SocketException e) {
		e.printStackTrace();
	    } catch (IOException e) {
		e.printStackTrace();
	    }
	}
    }

    void start() {
	System.out.println("Starting client, numSessions=" + this.numSessions);
	if (this.numSessions == 0) {
	    Thread producer = new Thread(new Producer(-1, this.port, 0));
	    producer.start();
	} else {
	    for (int i = 1; i < this.numSessions+1; i++) {
		Thread producer = new Thread(new Producer(i, this.port, this.numNums));
		System.out.println("i=" + i);
		producer.start();
	    }
	}
    }

    public static void main(String args[]) throws Exception {
	int port = Integer.parseInt(args[0]);
	int numSessions = Integer.parseInt(args[1]);
	int numNums = Integer.parseInt(args[2]);
	Client client = new Client(port, numSessions, numNums);
	client.start();
    }
}
