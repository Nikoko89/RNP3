/* FileCopyClient.java
 Version 0.1 - Muss ergaenzt werden!!
 Praktikum 3 Rechnernetze BAI4 HAW Hamburg
 Autoren:
 */

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class FileCopyClient extends Thread {

    // -------- Constants
    public final static boolean TEST_OUTPUT_MODE = false;

    public final int SERVER_PORT = 23000;

    public final int UDP_PACKET_SIZE = 1008;

    // -------- Public parms
    public String servername;

    public String sourcePath;

    public String destPath;

    public int windowSize;

    public long serverErrorRate;

    // -------- Variables
    // current default timeout in nanoseconds
    private long timeoutValue = 100000000L;

    private long expRTT = 100000000L;

    private long jitter = 20;

    private FileInputStream dataFromPath;

    private InetAddress serverAdress;

    private DatagramSocket clientSocket;

    private LinkedList<FCpacket> sendBuf = new LinkedList<>();

    private final ReentrantLock lock = new ReentrantLock();

    private final Condition freeSpace = lock.newCondition();

    private int nextSeq;


    // Constructor
    public FileCopyClient(String serverArg, String sourcePathArg,
                          String destPathArg, String windowSizeArg, String errorRateArg) {
        servername = serverArg;
        sourcePath = sourcePathArg;
        destPath = destPathArg;
        windowSize = Integer.parseInt(windowSizeArg);
        serverErrorRate = Long.parseLong(errorRateArg);
        try {
            dataFromPath = new FileInputStream(sourcePath);
        } catch (FileNotFoundException e) {
            System.err.println("Could not read path of source");
        }
        try {
            serverAdress = InetAddress.getByName(servername);
        } catch (UnknownHostException e) {
            System.err.println("Could not parse server adress");
        }
    }

    public void setUpCon() {
        try {
            clientSocket = new DatagramSocket();
        } catch (SocketException e) {
            System.err.println("Could not connect Client UDP Socket");
        }
    }

    public void runFileCopyClient() {
        setUpCon();
        FCpacket firstPacket = makeControlPacket();
        fillPuffer(firstPacket);
        new ClientHelper().start();
        send(firstPacket);
        startTimer(firstPacket);
        new ClientHelper().start();
        nextSeq++;
        FCpacket packet = makePacket(nextSeq);
        while (packet != null){
            System.out.println("Nächstes Pak kommt");
            fillPuffer(packet);
            send(packet);
            startTimer(packet);
            nextSeq++;
            packet = makePacket(nextSeq);
        }
        System.out.println("Done");

    }

    public void send(FCpacket packet){
        byte[] data = packet.getSeqNumBytesAndData();
        DatagramPacket sendData = new DatagramPacket(data, data.length, serverAdress, SERVER_PORT);
        try {
            clientSocket.send(sendData);
            packet.setTimestamp(System.nanoTime());
        } catch (IOException e) {
            System.err.println("Could not send packet");
        }
    }

    public void fillPuffer(FCpacket packet) {
        lock.lock();
        while (sendBuf.size() == windowSize) {
            try {
                freeSpace.await();
            } catch (InterruptedException e) {
                System.err.println("Could not wait to get free space");
            }
        }
        if (!sendBuf.contains(packet)) {
            sendBuf.add(packet);
            Collections.sort(sendBuf);
        }
        lock.unlock();
    }

    public void removePackets() {
        lock.lock();
        while (!sendBuf.isEmpty() && sendBuf.getFirst().isValidACK()) {
            sendBuf.removeFirst();
            freeSpace.signal();
        }
        lock.unlock();
    }

    public FCpacket getPacket(long seqNr) {
        lock.lock();
        FCpacket result = sendBuf.stream().filter(s -> s.getSeqNum() == seqNr).findAny().orElse(null);
        lock.unlock();
        return result;
    }

    /**
     * Timer Operations
     */
    public void startTimer(FCpacket packet) {
    /* Create, save and start timer for the given FCpacket */
        FC_Timer timer = new FC_Timer(timeoutValue, this, packet.getSeqNum());
        packet.setTimer(timer);
        timer.start();
    }

    public void cancelTimer(FCpacket packet) {
    /* Cancel timer for the given FCpacket */
        testOut("Cancel Timer for packet" + packet.getSeqNum());

        if (packet.getTimer() != null) {
            packet.getTimer().interrupt();
        }
    }

    /**
     * Implementation specific task performed at timeout
     */
    public void timeoutTask(long seqNum) {
        FCpacket packet = getPacket(seqNum);
        if(packet != null){
            send(packet);
            //System.out.println("Packet ist nicht null");
        }
        packet.setTimestamp(System.nanoTime());
        startTimer(packet);
    }


    /**
     * Computes the current timeout value (in nanoseconds)
     */
    public void computeTimeoutValue(long sampleRTT) {
        double x = 0.25;
        double y = x / 2;
        expRTT = (long) ((1 - y) * expRTT + y * sampleRTT);
        jitter = (long) ((1 - x) * jitter + x * Math.abs(sampleRTT - expRTT));
        timeoutValue = (expRTT + 4 * jitter);
    }


    /**
     * Return value: FCPacket with (0 destPath;windowSize;errorRate)
     */
    public FCpacket makeControlPacket() {
   /* Create first packet with seq num 0. Return value: FCPacket with
     (0 destPath ; windowSize ; errorRate) */
        String sendString = destPath + ";" + windowSize + ";" + serverErrorRate;
        byte[] sendData = null;
        try {
            sendData = sendString.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return new FCpacket(0, sendData, sendData.length);
    }

    public FCpacket makePacket(long seqNr){
        byte[] sendData = new byte[UDP_PACKET_SIZE - 8];
        int packLen = 0;
        try {
            packLen = dataFromPath.read(sendData);
        } catch (IOException e) {
            System.err.println("Could not read Datalength");
        }
        FCpacket packet = null;
        if(packLen != -1){
            System.out.println("erstelle nächses paket");
            packet = new FCpacket(seqNr, sendData, packLen);
        }
        return packet;
    }

    public void testOut(String out) {
        if (TEST_OUTPUT_MODE) {
            System.err.printf("%,d %s: %s\n", System.nanoTime(), Thread
                    .currentThread().getName(), out);
        }
    }

    public static void main(String argv[]) throws Exception {
        FileCopyClient myClient = new FileCopyClient(argv[0], argv[1], argv[2],
                argv[3], argv[4]);
        myClient.runFileCopyClient();
    }

    private class ClientHelper extends Thread {
        public void run() {
            while (!sendBuf.isEmpty()) {
                byte[] data = new byte[8];
                DatagramPacket packet = new DatagramPacket(data, data.length);
                try {
                    clientSocket.receive(packet);
                } catch (IOException e) {
                    System.err.println("Could not receive Packet");
                }
                FCpacket ackPack = null;
                long seqNr = makeLong(packet.getData(), 0, data.length);
                ackPack = getPacket(seqNr);
                if (ackPack != null) {
                    cancelTimer(ackPack);
                    long dur = System.nanoTime() - ackPack.getTimestamp();
                    computeTimeoutValue(dur);
                    ackPack.setValidACK(true);
                    System.out.println("receive ack with nr: " + ackPack.getSeqNum());
                }
                removePackets();
            }
        }

        private long makeLong(byte[] buf, int i, int length) {
            long r = 0;
            length += i;

            for (int j = i; j < length; j++)
                r = (r << 8) | (buf[j] & 0xffL);

            return r;
        }
    }

}
