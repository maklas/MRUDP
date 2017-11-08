package ru.maklas.mrudp.impl;

import ru.maklas.mrudp.UDPSocket;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

/**
 * Created by amaklakov on 02.11.2017.
 */
public class JavaUDPSocket implements UDPSocket {

    private final DatagramSocket socket;

    public JavaUDPSocket() throws SocketException {
        this(new DatagramSocket());
    }

    public JavaUDPSocket(int port) throws SocketException {
        this(new DatagramSocket(port));
    }

    public JavaUDPSocket(DatagramSocket dSocket) {
        this.socket = dSocket;
    }



    @Override
    public int getLocalPort() {
        return socket.getLocalPort();
    }

    @Override
    public void send(DatagramPacket packet) throws Exception {
        socket.send(packet);
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        socket.receive(packet);
    }

    @Override
    public void close() {
        socket.close();
    }
}
