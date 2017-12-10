package ru.maklas.mrudp.impl;

import ru.maklas.mrudp.*;

import java.net.InetAddress;

public class TestsSocket implements ServerModel {

    public static void main(String[] args) throws Exception {
        MRUDPServerSocket server = new MRUDPServerSocket(new PacketLossUDPSocket(new JavaUDPSocket(9000), 0), 512,  new TestsSocket());
        server.start();
        InetAddress localHost = InetAddress.getLocalHost();

        final MRUDPSocket2 client  = new FixedBufferMRUDP2(new JavaUDPSocket(), 512);
        client.start(true, 100);
        SimpleProfiler.start();
        ConnectionResponse response = client.connect(1000, localHost, 9000, "hello!".getBytes());
        double ms = SimpleProfiler.getMS();
        System.out.println("Got " + response.getType() + " in " + ms + " ms");

        client.send(("1 hey ").getBytes());
        Thread.sleep(100);
        new ClientThread("Client", client).start();


        final MRUDPSocket2 client2  = new FixedBufferMRUDP2(new JavaUDPSocket(), 512);
        client2.start(true, 100);
        SimpleProfiler.start();
        ConnectionResponse response2 = client2.connect(15000, localHost, 9000, "hello2!".getBytes());
        double ms2 = SimpleProfiler.getMS();
        System.out.println("Got " + response2.getType() + " in " + ms2 + " ms");

        client2.send(("2 hey ").getBytes());
        Thread.sleep(100);
        new ClientThread("Client2", client2).start();

        Thread.sleep(20000);
    }


    @Override
    public byte[] validateNewConnection(InetAddress address, int port, byte[] userData) {
        return "Welcome, Maklas".getBytes();
    }

    @Override
    public void registerNewConnection(final FixedBufferMRUDP2 socket) {
        socket.start(true, 50);
        new ClientThread("Server", socket).start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean send = socket.send(("ServerToClientAfterConnection ").getBytes());
            }
        }).start();
    }

    @Override
    public void handleUnknownSourceMsg(byte[] userData) {

    }

    @Override
    public void onSocketDisconnected(FixedBufferMRUDP2 socket) {

    }



    private static class ClientThread extends Thread implements SocketProcessor {

        private final String name;
        private final MRUDPSocket2 socket;

        public ClientThread(String name, MRUDPSocket2 socket) {
            this.name = name;
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                while (true){
                    socket.receive(this);
                    Thread.sleep(15);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void process(byte[] data, MRUDPSocket2 socket, SocketIterator iterator) {
            System.out.println(name + ": " + new String(data));
            /*
            String s = new String(data);
            int val = Integer.parseInt(s);
            if (val == 0){
                SimpleProfiler.start();
            } else if (val == 10000 - 1){
                System.out.println("Time took: " + SimpleProfiler.getMS());
            }*/
        }
    }
}