package ru.maklas.mrudp;

import org.junit.Test;
import org.junit.runners.JUnit4;
import ru.maklas.locator.*;
import ru.maklas.mrudp.*;

import java.net.InetAddress;
import java.util.Arrays;

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


    @Test
    public void locator() throws Exception {
        final byte[] uuid = "123".getBytes();
        final int port = 9000;
        final String address = "255.255.255.255";

        BroadcastServlet servlet = new BroadcastServlet(port, 512, uuid, new BroadcastProcessor() {
            @Override
            public byte[] process(InetAddress address, int port, byte[] request) {
                System.out.println("requested: " + address.getHostAddress() + ":" + port + " -- " + Arrays.toString(request));
                if (Arrays.equals(request, new byte[]{1, 2, 3}))
                    return "Approved".getBytes();
                else
                    return "Rejected".getBytes();
            }
        });

        servlet.enable();






        final Locator locator = new Locator(uuid, address, port, 512);
        SimpleProfiler.start();
        locator.startDiscovery(5000, 5, new byte[]{1, 2, 3}, new Notifier<LocatorResponse>() {
            @Override
            public void notify(LocatorResponse locatorResponse) {
                System.out.println("new response! " + locatorResponse.getAddress().getHostAddress() + ":" + locatorResponse.getPort() + " -- " + new String(locatorResponse.getResponse()));
            }
        });
        System.out.println(SimpleProfiler.getTimeAsString(2));


    }
}