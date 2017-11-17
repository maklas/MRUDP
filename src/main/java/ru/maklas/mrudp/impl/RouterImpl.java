package ru.maklas.mrudp.impl;

import ru.maklas.mrudp.Router;
import ru.maklas.mrudp.UDPSocket;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class RouterImpl implements Router{

    private final List<RouterUDP> sockets = new CopyOnWriteArrayList<RouterUDP>();
    private final AtomicInteger addressCounter = new AtomicInteger(2);
    private final Random rand = new Random();

    @Override
    public UDPSocket getNewConnection() {
        return getNewConnection(getNewPort());
    }

    @Override
    public UDPSocket getNewConnection(int port) {
        InetAddress address = getNewAddress();
        RouterUDP udp = new RouterUDP(address, port);
        sockets.add(udp);
        return udp;
    }

    private InetAddress getNewAddress() {
        try {
            return InetAddress.getByAddress(new byte[]{(byte)192, (byte)168, 1, (byte)addressCounter.getAndIncrement()});
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private int getNewPort() {
        synchronized (rand) {
            return rand.nextInt(50000) + 10000;
        }
    }


    void transfer(RouterUDP udp, DatagramPacket packet) {
        final InetAddress targetAddress = packet.getAddress();
        final int targetPort = packet.getPort();
        final Data data = new Data(udp.localPort, udp.address, packet.getData());

        for (RouterUDP socket : sockets) {
            if (socket.is(targetAddress, targetPort)){
                boolean toSend = filter(udp, socket, data);
                if (toSend){
                    socket.queue.offer(data);
                }
                return;
            }
        }
    }

    /**
     * You can actually change data in those
     * @param source who sent package
     * @param destination target
     * @param data data
     * @return <b>True</b> to send package, <b>False to discard</b>
     */
    protected boolean filter(RouterUDP source, RouterUDP destination, Data data){
        return true;
    }


    @Override
    public List<? extends UDPSocket> getAllSockets() {
        return sockets;
    }

    public class RouterUDP implements UDPSocket{

        private final InetAddress address;
        private final int localPort;
        private LinkedBlockingQueue<Data> queue = new LinkedBlockingQueue<Data>();

        public RouterUDP(InetAddress address, int localPort) {
            this.address = address;
            this.localPort = localPort;
        }

        @Override
        public int getLocalPort() {
            return localPort;
        }

        @Override
        public void send(DatagramPacket packet) throws Exception {
            transfer(RouterUDP.this, packet);
        }

        @Override
        public void receive(DatagramPacket packet) throws IOException {
            final Data take;
            try {
                take = queue.take();
            } catch (InterruptedException e) {
                throw new SocketException("interrupted");
            }

            packet.setAddress(take.address);
            packet.setPort(take.port);
            packet.setData(take.data);
        }

        @Override
        public void close() {
            gotClosed(this);
        }

        public InetAddress getAddress() {
            return address;
        }

        boolean is(InetAddress address, int port){
            return this.localPort == port && Arrays.equals(this.address.getAddress(), address.getAddress());
        }
    }

    private void gotClosed(RouterUDP udp) {
        sockets.remove(udp);
    }


    protected class Data{
        private final int port;
        private final InetAddress address;
        private byte[] data;

        public Data(int port, InetAddress address, byte[] data) {
            this.port = port;
            this.address = address;
            this.data = data;
        }

        public byte[] getData() {
            return data;
        }

        public void setData(byte[] data) {
            this.data = data;
        }

        @Override
        public String toString() {
            return "Data{" +
                    "Address of sender =" + address + ":" + port +
                    ", data=" + dataToString(data) +
                    '}';
        }
    }

    private static String dataToString(byte[] data){

        byte setByte = data[4];
        final boolean[] settings = new boolean[]{ //8 values from byte as boolean array
                ((setByte >> 7 & 1) == 1),
                ((setByte >> 6 & 1) == 1),
                ((setByte >> 5 & 1) == 1),
                ((setByte >> 4 & 1) == 1),
                ((setByte >> 3 & 1) == 1),
                ((setByte >> 2 & 1) == 1),
                ((setByte >> 1 & 1) == 1),
                ((setByte & 1)      == 1)};


        StringBuilder builder = new StringBuilder();


        final boolean isRequest = settings[4];
        final boolean alreadyBeenSend = settings[6];
        builder.append(isRequest ? "REQ":"RESP");
        builder.append(" | ");
        builder.append(alreadyBeenSend ? "SECOND" : "FIRST");
        builder.append(" | ");
        builder.append(new String(data, 6, data.length - 6));
        return builder.toString();
    }

}
