package ru.maklas.mrudp.impl;

import org.junit.Test;
import ru.maklas.mrudp.*;

import java.net.InetAddress;

public class TestsSocket implements ServerModel {


    @Test
    public void test() throws Exception {
        MRUDPServerSocket server = new MRUDPServerSocket(new JavaUDPSocket(9000), 512,  new TestsSocket());
        server.start();

        MRUDPSocket2 client  = new FixedBufferMRUDP2(new JavaUDPSocket(), 512);
        client.start(true, 100);

        Thread.sleep(10);

        InetAddress localHost = InetAddress.getLocalHost();
        SimpleProfiler.start();
        ConnectionResponse response = client.connect(1000, localHost, 9000, "hello!".getBytes());
        double ms = SimpleProfiler.getMS();
        System.out.println("Got response in " + ms + " ms");

        ConnectionResponse.Type type = response.getType();
        System.out.println("Connection type: " + type);
        System.out.println("Server responded with: " + new String(response.getResponseData()));
    }



    @Override
    public byte[] validateNewConnection(InetAddress address, int port, byte[] userData) {
        System.out.println("New connection arrived. Validating... " + new String(userData));
        return "Welcome, Maklas".getBytes();
    }

    @Override
    public void registerNewConnection(FixedBufferMRUDP2 socket) {
        System.out.println("New connection is registered!");
    }

    @Override
    public void handleUnknownSourceMsg(byte[] userData) {

    }

    @Override
    public void onSocketDisconnected(FixedBufferMRUDP2 socket) {

    }
}