package ru.maklas.mrudp.impl;

import org.junit.Test;
import ru.maklas.mrudp.*;

import java.net.InetAddress;

public class TestsSocket {


    @Test
    public void testConnection() throws Exception {

        InetAddress localHost = InetAddress.getLocalHost();
        int port = 9000;
        MRUDPServerSocket server = new MRUDPServerSocket(port, 512, new ServerModel() {
            @Override
            public ConnectionResponsePackage<byte[]> validateNewConnection(InetAddress address, int port, byte[] userData) {
                System.out.println("Validating new connection");
                return ConnectionResponsePackage.accept("123".getBytes());
            }

            @Override
            public void registerNewConnection(final MRUDPSocketImpl socket, ConnectionResponsePackage<byte[]> responsePackage) {
                System.out.println("Registering new connection");
                socket.start(50);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        SocketProcessor processor = new SocketProcessor() {
                            @Override
                            public void process(byte[] data, MRUDPSocket socket, SocketIterator iterator) {
                                System.out.println("Server received data: " + new String(data));
                            }
                        };

                        try {
                            Thread.sleep(1000);
                            for (int i = 0; i < 150; i++) {
                                Thread.sleep(2);
                                socket.send(Integer.toString(i).getBytes());
                            }


                            while (true){
                                socket.receive(processor);
                                Thread.sleep(15);
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                    }
                }).start();
            }
            @Override
            public void handleUnknownSourceMsg(byte[] userData) {

            }

            @Override
            public void onSocketDisconnected(MRUDPSocketImpl socket) {

            }
        });
        server.start();

        final MRUDPSocket client = new MRUDPSocketImpl(512);
        client.start(50);
        ConnectionResponse connect = client.connect(5000, localHost, port, new byte[]{0});
        System.out.println(connect.getType());


        Thread.sleep(500);


        new Thread(new Runnable() {
            @Override
            public void run() {
                SocketProcessor processor = new SocketProcessor() {
                    @Override
                    public void process(byte[] data, MRUDPSocket socket, SocketIterator iterator) {
                        System.out.println("Client received data: " + new String(data));
                    }
                };

                while (true) {
                    try {
                        Thread.sleep(15);
                        client.receive(processor);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();

        Thread.sleep(500);
        for (int i = 0; i < 150; i++) {
            Thread.sleep(2);
            client.send(Integer.toString(i).getBytes());
        }


        Thread.sleep(2000);
    }
}