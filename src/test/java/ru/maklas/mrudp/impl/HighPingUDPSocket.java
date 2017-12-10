package ru.maklas.mrudp.impl;

import ru.maklas.mrudp.UDPSocket;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class HighPingUDPSocket implements UDPSocket, Runnable{

    private final UDPSocket delegate;
    private final int sendingPing;
    private final int receivingPing;
    private final ExecutorService service;
    private final Object sendingMonitor;
    private final DatagramPacket sendingPacket;

    private final DatagramPacket receivingPacket;
    private final LinkedBlockingQueue<DataTriplet> dataQueue;

    public HighPingUDPSocket(UDPSocket delegate, int minPingMS) {
        this.delegate = delegate;
        this.sendingPing = this.receivingPing = minPingMS/2;
        service = Executors.newCachedThreadPool();
        sendingMonitor = new Object();
        sendingPacket = new DatagramPacket(new byte[0], 0);
        receivingPacket = new DatagramPacket(new byte[1500], 1500);
        dataQueue = new LinkedBlockingQueue<DataTriplet>();
        final Thread thread = new Thread(this);
        thread.start();
    }


    @Override
    public int getLocalPort() {
        return delegate.getLocalPort();
    }


    @Override
    public void send(final DatagramPacket packet) throws Exception {
        final InetAddress address = packet.getAddress();
        final int port = packet.getPort();
        final int packetLength = packet.getData().length;
        final byte[] data = new byte[packetLength];
        System.arraycopy(packet.getData(), 0, data, 0, packetLength);
        service.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(sendingPing);
                    synchronized (sendingMonitor) {
                        sendingPacket.setAddress(address);
                        sendingPacket.setPort(port);
                        sendingPacket.setData(data);
                        delegate.send(sendingPacket);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        try {
            final DataTriplet take = dataQueue.take();
            packet.setAddress(take.address);
            packet.setPort(take.port);
            packet.setData(take.data);
            long currentTime = System.currentTimeMillis();
            while (currentTime - take.creationTime < receivingPing){
                Thread.sleep(1);
                currentTime = System.currentTimeMillis();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {

        try {
            while (true) {
                delegate.receive(receivingPacket);
                final InetAddress address = receivingPacket.getAddress();
                final int port = receivingPacket.getPort();
                final byte[] data = new byte[receivingPacket.getLength()];
                System.arraycopy(receivingPacket.getData(), 0, data, 0, data.length);
                final DataTriplet triplet = new DataTriplet(address, port, data);
                dataQueue.offer(triplet);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        delegate.close();
    }

    private class DataTriplet {


        private final InetAddress address;
        private final int port;
        private final byte[] data;
        private final long creationTime;

        public DataTriplet(InetAddress address, int port, byte[] data) {
            this.creationTime = System.currentTimeMillis();
            this.address = address;
            this.port = port;
            this.data = data;
        }
    }
}
