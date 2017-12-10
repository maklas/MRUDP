package ru.maklas.mrudp.impl;

import org.junit.Test;
import ru.maklas.mrudp.*;

import java.net.InetAddress;

public class TestsSocket implements ServerModel {

    public static void main(String[] args) throws Exception {
        MRUDPServerSocket server = new MRUDPServerSocket(new PacketLossUDPSocket(new JavaUDPSocket(9000), 50), 512,  new TestsSocket());
        server.start();
        InetAddress localHost = InetAddress.getLocalHost();

        final MRUDPSocket2 client  = new FixedBufferMRUDP2(new PacketLossUDPSocket(new JavaUDPSocket(), 50), 512);
        client.start(true, 100);
        SimpleProfiler.start();
        ConnectionResponse response = client.connect(1000, localHost, 9000, "hello!".getBytes());
        double ms = SimpleProfiler.getMS();
        System.out.println("Got " + response.getType() + " in " + ms + " ms");

        for (int i = 0; i < 100; i++) {
            client.send(("1 hey " + i).getBytes());
        }
        Thread.sleep(100);
        new ClientThread("Client", client).start();


        final MRUDPSocket2 client2  = new FixedBufferMRUDP2(new PacketLossUDPSocket(new JavaUDPSocket(), 50), 512);
        client2.start(true, 100);
        SimpleProfiler.start();
        ConnectionResponse response2 = client2.connect(15000, localHost, 9000, "hello2!".getBytes());
        double ms2 = SimpleProfiler.getMS();
        System.out.println("Got " + response2.getType() + " in " + ms2 + " ms");

        for (int i = 0; i < 100; i++) {
            client2.send(("2 hey " + i).getBytes());
        }
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
                for (int i = 0; i < 100; i++) {
                    socket.send(("ServerToClientAfterConnection " + i).getBytes());
                }
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
                    Thread.sleep(15);
                    socket.receive(this);
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





















































    @Test
    public void testDC() throws Exception {
        final int port = 9001;
        final InetAddress localHost = InetAddress.getLocalHost();
        final MRUDPServerSocket server = new MRUDPServerSocket(new JavaUDPSocket(port), 512, new ServerModel() {
            @Override
            public byte[] validateNewConnection(InetAddress address, int port, byte[] userData) {
                return new byte[0];
            }

            @Override
            public void registerNewConnection(FixedBufferMRUDP2 socket) {
                System.out.println("Registered new Connection");
                socket.start(true, 50);
                new ClientThread("Server bean", socket);
            }

            @Override
            public void handleUnknownSourceMsg(byte[] userData) {

            }

            @Override
            public void onSocketDisconnected(FixedBufferMRUDP2 socket) {
                System.out.println("Socket has disconnected( ");
            }
        });
        server.start();

        final MRUDPSocket2 client = new FixedBufferMRUDP2(new JavaUDPSocket(), 512);
        client.start(true, 50);

        ConnectionResponse connect = client.connect(2000, localHost, port, "Hello. I'm Maklas".getBytes());
        System.out.println(connect.getType());
        new ClientThread("ClientReceive Thread" , client);

        client.addListener(new MRUDPListener() {
            @Override
            public void onDisconnect(MRUDPSocket2 fixedBufferMRUDP2) {
                System.out.println("1 Disconnected by listener");
            }
        });
        for (int i = 0; i < 10; i++) {
            client.send(Integer.toString(i).getBytes());
            Thread.sleep(500);
        }

        client.close();

        Thread.sleep(10000);



    }
}