package ru.maklas.mrudp;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runners.JUnit4;
import ru.maklas.locator.*;

import java.net.InetAddress;
import java.util.Arrays;

@SuppressWarnings("all")
public class TestsSocket {


    @Test
    public void testBatchEncodingDecoding() throws Exception {
        byte[] one = new byte[]{1, 2, 3, 4, 5, 6};
        byte[] two = new byte[]{11, 22, 33, 44, 55, 66};
        byte[] three = new byte[]{101, 102, 103, 104, 105, 106, 107, 108};


        MRUDPBatch batch = new MRUDPBatch();
        batch.add(one);
        batch.add(two);
        batch.add(three);
        byte[] batchPacket = MRUDPUtils.buildBatch(0, batch);
        System.out.println(Arrays.toString(batchPacket));
        byte[][] bytes = MRUDPUtils.breakBatchDown(batchPacket);
        System.out.println("One:   " + Arrays.toString(bytes[0]));
        System.out.println("Two:   " + Arrays.toString(bytes[1]));
        System.out.println("Three: " + Arrays.toString(bytes[2]));
        Assert.assertArrayEquals(one, bytes[0]);
        Assert.assertArrayEquals(two, bytes[1]);
        Assert.assertArrayEquals(three, bytes[2]);
    }

    @Test
    public void testBatchInSocket() throws Exception {
        InetAddress localHost = InetAddress.getLocalHost();
        int port = 9090;
        MRUDPServerSocket server = new MRUDPServerSocket(new PacketLossUDPSocket(new HighPingUDPSocket(new JavaUDPSocket(port), 500) , 0), 512, new ServerModel() {
            @Override
            public ConnectionResponsePackage<byte[]> validateNewConnection(InetAddress address, int port, byte[] userData) {
                System.out.println("Validating new connection");
                return ConnectionResponsePackage.accept("123".getBytes());
            }

            @Override
            public void registerNewConnection(final MRUDPSocketImpl socket, ConnectionResponsePackage<byte[]> responsePackage, byte[] userData) {
                System.out.println("Registering new connection");
                socket.start(50);
                socket.addDCListener(new MDisconnectionListener() {
                    @Override
                    public void onDisconnect(MRUDPSocket socket, String msg) {
                        System.out.println("Server-Sub dced: " + msg);
                    }
                });

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
                            for (int i = 0; i < 30; i++) {
                                Thread.sleep(5);
                                MRUDPBatch batch = new MRUDPBatch();
                                batch.add(Integer.toString(i * 5).getBytes());
                                batch.add(Integer.toString(i * 5 + 1).getBytes());
                                batch.add(Integer.toString(i * 5 + 2).getBytes());
                                batch.add(Integer.toString(i * 5 + 3).getBytes());
                                batch.add(Integer.toString(i * 5 + 4).getBytes());
                                socket.sendBatch(batch);
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
            public void onSocketDisconnected(MRUDPSocketImpl socket, String msg) {

            }
        }, 10000);
        server.start();

        final MRUDPSocket client = new MRUDPSocketImpl(512);
        client.start(50);
        client.addPingListener(new MPingListener() {
            @Override
            public void onPingUpdated(MRUDPSocket socket, float newPing) {
                System.out.println("Ping: " + newPing);
            }
        });
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


        Thread.sleep(7000);

        client.disconnect();

        Thread.sleep(1000);
    }

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
            public void registerNewConnection(final MRUDPSocketImpl socket, ConnectionResponsePackage<byte[]> responsePackage, byte[] userData) {
                System.out.println("Registering new connection");
                socket.start(50);
                socket.addDCListener(new MDisconnectionListener() {
                    @Override
                    public void onDisconnect(MRUDPSocket socket, String msg) {
                        System.out.println("Server-Sub dced: " + msg);
                    }
                });

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
            public void onSocketDisconnected(MRUDPSocketImpl socket, String msg) {

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

        client.disconnect();

        Thread.sleep(10000);
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

            @Override
            public void finish() {

            }
        });
        System.out.println(SimpleProfiler.getTimeAsString(2));


    }



    @Test
    public void packetLoss() throws Exception{

        InetAddress localHost = InetAddress.getLocalHost();
        int port = 9000;
        MRUDPServerSocket server = new MRUDPServerSocket(new PacketLossUDPSocket(new JavaUDPSocket(port), 50), 512, new ServerModel() {
            @Override
            public ConnectionResponsePackage<byte[]> validateNewConnection(InetAddress address, int port, byte[] userData) {
                return ConnectionResponsePackage.accept(new byte[0]);
            }

            @Override
            public void registerNewConnection(final MRUDPSocketImpl socket, ConnectionResponsePackage<byte[]> responsePackage, byte[] userData) {
                System.out.println("Server registered.");
                socket.start(75);
                new Thread(new Tester("ServerC", socket, new SocketProcessor() {
                    @Override
                    public void process(byte[] data, MRUDPSocket socket, SocketIterator iterator) {
                        int received = MRUDPUtils.extractInt(data, 0);
                        int toSend = received + 1048576;
                        System.out.println("Server received " + MRUDPUtils.extractInt(data, 0) + " and responding with " + toSend);
                        byte[] sendingBytes = new byte[4];
                        MRUDPUtils.putInt(sendingBytes, toSend, 0);
                        socket.send(sendingBytes);
                    }
                })).start();
            }

            @Override
            public void onSocketDisconnected(MRUDPSocketImpl socket, String msg) {
                System.out.println("Server dc");
            }
        }, 7000);

        server.start();

        MRUDPSocketImpl client = new MRUDPSocketImpl(new PacketLossUDPSocket(new JavaUDPSocket(), 50), 512, 7000);
        client.start(75);

        client.addDCListener(new MDisconnectionListener() {
            @Override
            public void onDisconnect(MRUDPSocket socket, String msg) {
                System.out.println("client dc");
            }
        });

        ConnectionResponse connect = client.connect(5000, localHost, port, new byte[]{1, 2, 3});
        System.out.println(connect);
        System.out.println("Ping to server: " + client.getPing());

        new Thread(new Tester("Client", client, new SocketProcessor() {
            @Override
            public void process(byte[] data, MRUDPSocket socket, SocketIterator iterator) {
                System.out.println("Client received: " + (MRUDPUtils.extractInt(data, 0) - 1048576));
            }
        })).start();


        Thread.sleep(500);
        for (int i = 0; i < 10000; i++) {
            byte[] in = new byte[4];
            MRUDPUtils.putInt(in, i, 0);
            client.send(in);
            Thread.sleep(10);
        }


        Thread.sleep(20000);


    }



    private class Tester implements Runnable {

        private final String tag;
        final MRUDPSocket socket;
        private final SocketProcessor processor;

        public Tester(String tag, MRUDPSocket socket, SocketProcessor processor) {
            this.tag = tag;
            this.socket = socket;
            this.processor = processor;
        }

        @Override
        public void run() {


            while (true){
                try {
                    Thread.sleep(50);

                    socket.receive(processor);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

    }
}