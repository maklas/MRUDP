package ru.maklas.locator;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;

public class BroadcastServlet {

    private static volatile int threadCounter;

    private final DatagramSocket socket;
    private final DatagramPacket sendingPacket;
    private final DatagramPacket receivingPacket;
    private final byte[] uuid;
    private final BroadcastProcessor processor;
    private volatile boolean enabled = false;
    private final Thread thread;
    private final HashMap<Pack, byte[]> memory = new HashMap<Pack, byte[]>();
    private final Object memoryMonitor = new Object();


    public BroadcastServlet(int port, int bufferSize, byte[] uuid, BroadcastProcessor processor) throws Exception{
        socket = new DatagramSocket(port, InetAddress.getByName("0.0.0.0"));
        sendingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        receivingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        this.uuid = Arrays.copyOf(uuid, 16);
        this.processor = processor;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                BroadcastServlet.this.run();
            }
        }, "BroadcastServlet-" + threadCounter++);
        thread.start();
    }

    public void enable(){
        enabled = true;
    }

    public void disable(){
        synchronized (memoryMonitor){
            memory.clear();
        }
        enabled = false;
    }

    public boolean isEnabled(){
        return enabled;
    }

    public void close(){
        socket.close();
    }



    private void run(){

        while (true) {

            DatagramPacket receivingPacket = this.receivingPacket;
            try {
                socket.receive(receivingPacket);
            } catch (IOException e) {
                break;
            }

            if (!enabled){
                continue;
            }


            int length = receivingPacket.getLength();
            if (length < LocatorUtils.minMsgLength){
                continue;
            }

            byte[] fullPackage = new byte[length];
            System.arraycopy(receivingPacket.getData(), 0, fullPackage, 0, length);
            boolean startsWithUUID = LocatorUtils.startsWithUUID(fullPackage, uuid);
            if (!startsWithUUID){
                continue;
            }

            if (!LocatorUtils.isRequest(fullPackage)){
                continue;
            }

            int seq = LocatorUtils.getSeq(fullPackage);
            InetAddress address = receivingPacket.getAddress();
            int port = receivingPacket.getPort();
            byte[] userData = new byte[length - 21];
            System.arraycopy(fullPackage, 21, userData, 0, length - 21);
            Pack pack = new Pack(address, port, seq);

            byte[] alreadySentResponse;
            synchronized (memoryMonitor) {
                alreadySentResponse = memory.get(pack);
                if (alreadySentResponse == null){
                    byte[] userResponse = processor.process(address, port, userData);
                    if (userResponse == null){
                        continue;
                    }
                    alreadySentResponse = LocatorUtils.createResponse(uuid, seq, userResponse);
                    memory.put(pack, alreadySentResponse);
                }
            }
            sendData(address, port, alreadySentResponse);
        }
    }

    private void sendData(InetAddress address, int port, byte[] data){
        sendingPacket.setData(data);
        sendingPacket.setAddress(address);
        sendingPacket.setPort(port);
        try {
            socket.send(sendingPacket);
        } catch (IOException e) {}
    }


    private class Pack{
        private final InetAddress address;
        private final int port;
        private final int seq;

        public Pack(InetAddress address, int port, int seq) {
            this.address = address;
            this.port = port;
            this.seq = seq;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Pack pack = (Pack) o;

            if (port != pack.port) return false;
            if (seq != pack.seq) return false;
            return address.equals(pack.address);
        }

        @Override
        public int hashCode() {
            int result = address.hashCode();
            result = 31 * result + port;
            result = 31 * result + seq;
            return result;
        }
    }
}
