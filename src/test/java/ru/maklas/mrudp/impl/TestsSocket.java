package ru.maklas.mrudp.impl;

import org.junit.Test;
import ru.maklas.mrudp.*;

import java.net.InetAddress;

public class TestsSocket implements ServerModel {

    @Test
    public void testFirst() throws Exception {
        MRUDPServerSocket server = new MRUDPServerSocket(new PacketLossUDPSocket(new JavaUDPSocket(9000), 50), 512,  new TestsSocket(), 12000);
        server.start();
        InetAddress localHost = InetAddress.getLocalHost();

        final MRUDPSocket2 client  = new FixedBufferMRUDP2(new PacketLossUDPSocket(new JavaUDPSocket(), 50), 512, 12000);
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


        final MRUDPSocket2 client2  = new FixedBufferMRUDP2(new PacketLossUDPSocket(new JavaUDPSocket(), 50), 512, 12000);
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
            String s = new String(data);
            System.out.println(name + ": " + s);
            if (s.equals("ping0")){
                socket.send("ping1".getBytes());
            } else if (s.equals("ping1")){
                System.out.println("Ping: " + SimpleProfiler.getMS());
            }
        }
    }





















    @Test
    public void testPing() throws Exception {
        final int port = 9001;
        final InetAddress localHost = InetAddress.getLocalHost();
        final MRUDPServerSocket server = new MRUDPServerSocket(port, 512, new ServerModel() {
            @Override
            public byte[] validateNewConnection(InetAddress address, int port, byte[] userData) {
                return new byte[]{0, 0, 0, 0};
            }

            @Override
            public void registerNewConnection(final FixedBufferMRUDP2 socket) {
                System.out.println("Registered new Connection");
                socket.start(true, 50);
                new ClientThread("Server bean", socket).start();

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        socket.send("12345".getBytes());
                    }
                }).start();
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

        final MRUDPSocket2 client = new FixedBufferMRUDP2(new PacketLossUDPSocket(new HighPingUDPSocket(new JavaUDPSocket(), 250), 15), 512, 12000);
        client.start(true, 50);

        ConnectionResponse connect = client.connect(5000, localHost, port, "Hello. I'm Maklas".getBytes());
        System.out.println(connect.getType());
        new ClientThread("ClientReceive Thread" , client).start();

        client.addListener(new MRUDPListener() {
            @Override
            public void onDisconnect(MRUDPSocket2 fixedBufferMRUDP2) {
                System.out.println("1 Disconnected by listener");
            }
        });
        for (int i = 0; i < 10; i++) {
            client.send(Integer.toString(i).getBytes());
            Thread.sleep(15);
        }

        Thread.sleep(5000);

        SimpleProfiler.start();
        client.send("ping0".getBytes());

        Thread.sleep(5000);

    }

    @Test
    public void testAutoPing() throws Exception {
        InetAddress localHost = InetAddress.getLocalHost();
        int port = 9002;

        MRUDPServerSocket server = new MRUDPServerSocket(new HighPingUDPSocket(new JavaUDPSocket(port), 25), 512, new ServerModel() {
            @Override
            public byte[] validateNewConnection(InetAddress address, int port, byte[] userData) {
                System.out.println("Client validated");
                return new byte[0];
            }

            @Override
            public void registerNewConnection(FixedBufferMRUDP2 socket) {
                socket.start(true, 50);
                new ClientThread("server bean", socket).start();
            }

            @Override
            public void handleUnknownSourceMsg(byte[] userData) {

            }

            @Override
            public void onSocketDisconnected(FixedBufferMRUDP2 socket) {
                System.out.println("Server DC");
            }
        }, 12000);
        server.start();

        MRUDPSocket2 client = new FixedBufferMRUDP2(512);
        client.start(true, 50);
        client.addListener(new MRUDPListener() {
            @Override
            public void onDisconnect(MRUDPSocket2 fixedBufferMRUDP2) {
                System.out.println("DC");
            }
        });
        new ClientThread("client bean", client).start();
        ConnectionResponse connect = client.connect(5000, localHost, port, new byte[0]);
        System.out.println(connect.getType());


        for (int i = 0; i < 5; i++) {
            Thread.sleep(5000);
            System.out.println(client.getPing());
        }

        //Thread.sleep(20000);

    }
}