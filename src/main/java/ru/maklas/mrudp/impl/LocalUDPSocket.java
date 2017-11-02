package ru.maklas.mrudp.impl;

import ru.maklas.mrudp.UDPSocket;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created by amaklakov on 02.11.2017.
 */
public class LocalUDPSocket implements UDPSocket{

    private final int localPort;
    private volatile boolean isOpen = true;
    private List<LocalUDPSocket> connectedSockets;
    private LinkedBlockingQueue<DataPacket> receivingQueue = new LinkedBlockingQueue<DataPacket>();
    private InetAddress address;

    public LocalUDPSocket(int localPort, LocalUDPSocket... connectedSockets) {
        this.localPort = localPort;
        this.connectedSockets = new CopyOnWriteArrayList<LocalUDPSocket>();
        this.connectedSockets.addAll(Arrays.asList(connectedSockets));
        try {
            address = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    public void addSocket(LocalUDPSocket socket){
        connectedSockets.add(socket);
    }

    void removeSocket(LocalUDPSocket socket){
        connectedSockets.remove(socket);
    }

    final void propagate(DataPacket packet){
        receivingQueue.offer(packet);
    }

    @Override
    public int getLocalPort() {
        return localPort;
    }

    @Override
    public void send(DatagramPacket packet) throws Exception {
        DataPacket data = new DataPacket(localPort, packet.getData(), packet.getOffset(), packet.getLength());
        for (LocalUDPSocket connectedSocket : connectedSockets) {
            connectedSocket.propagate(data);
        }

    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        try {
            DataPacket take = receivingQueue.take();
            packet.setAddress(address);
            packet.setPort(take.port);
            if (!isOpen){
                throw new SocketException("Socket is closed");
            }
            packet.setData(take.data);
        } catch (InterruptedException e) {
            packet.setData(new byte[0]);
        }
    }

    @Override
    public void close() {
        DataPacket p = new DataPacket(localPort, new byte[0], 0, 0);
        isOpen = false;
        receivingQueue.clear();
        receivingQueue.offer(p);
    }



    private class DataPacket {
        private byte[] data;
        private int port;

        public DataPacket(int port, byte[] buffer, int start, int length) {
            this.port = port;
            data = new byte[length];
            try {
                System.arraycopy(buffer, start, data, 0, length);
            } catch (Exception e) {
                data = new byte[0];
            }
        }
    }



}
