package ru.maklas.mrudp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;

import static ru.maklas.mrudp.MRUDPUtils.*;

public class MRUDPServerSocket {
    private static volatile int serverCounter;

    private final UDPSocket socket;
    private final ServerModel model;
    private final AddressObjectMap<MRUDPSocketImpl> connectionMap = new AddressObjectMap.Synchronized<MRUDPSocketImpl>();
    private final AddressObjectMap<Object[]> waitingForAckMap = new AddressObjectMap<Object[]>();
    private final DatagramPacket sendingPacket;
    private final int dcTimeDueToInactivity;
    private final int bufferSize;
    private Thread receivingThread;
    private volatile boolean started = false;

    public MRUDPServerSocket(int port, int bufferSize, ServerModel model) throws Exception{
        this(new JavaUDPSocket(port), bufferSize, model, 12 * 1000);
    }

    public MRUDPServerSocket(UDPSocket socket, int bufferSize, ServerModel model, int dcTimeDueToInactivity) {
        this.bufferSize = bufferSize;
        this.socket = socket;
        this.model = model;
        this.sendingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        this.dcTimeDueToInactivity = dcTimeDueToInactivity;
    }

    public void start(){
        if (started){
            log("Can't start ServerSocket twice. Forbidden");
            return;
        }
        started = true;
        receivingThread = new Thread(new Runnable() {
            @Override
            public void run() {

                final AddressObjectMap<MRUDPSocketImpl> connectionMap = MRUDPServerSocket.this.connectionMap;
                final byte[] receivingBuffer = new byte[bufferSize];
                final DatagramPacket packet = new DatagramPacket(receivingBuffer, bufferSize);

                while (!Thread.interrupted()) {

                    try {
                        socket.receive(packet);

                        int dataLength = packet.getLength();
                        if (dataLength < 5){
                            continue;
                        }

                        InetAddress remoteAddress = packet.getAddress();
                        int remotePort = packet.getPort();

                        MRUDPSocketImpl subSocket;
                        subSocket = connectionMap.get(remoteAddress, remotePort);


                        switch (receivingBuffer[0]) {
                            case connectionRequest:
                                if (subSocket != null) {
                                    subSocket.receiveConnected(remoteAddress, remotePort, receivingBuffer, dataLength);
                                } else {
                                    byte[] data = new byte[dataLength];
                                    System.arraycopy(receivingBuffer, 0, data, 0, dataLength);
                                    dealWithNewConnectionRequest(remoteAddress, remotePort, data);
                                }
                                break;

                            case connectionAcknowledgment:
                                if (subSocket != null) {
                                    sendDataOnReceivingThread(remoteAddress, remotePort, buildConnectionAckResponse(0));
                                } else {
                                    Object[] tuple = waitingForAckMap.remove(remoteAddress, remotePort);
                                    if (tuple == null) {
                                        System.err.println("Socket for ack not found!");
                                        break;
                                    }

                                    @SuppressWarnings("unchecked")
                                    ConnectionResponsePackage<byte[]> userResp = (ConnectionResponsePackage<byte[]>) tuple[0];
                                    final MRUDPSocketImpl mrudp = (MRUDPSocketImpl) tuple[1];
                                    mrudp.addListener(new MRUDPListener() {
                                        @Override
                                        public void onDisconnect(MRUDPSocket fixedBufferMRUDP2) {
                                            connectionMap.remove(mrudp.getRemoteAddress(), mrudp.getRemotePort());
                                            model.onSocketDisconnected(mrudp);
                                        }

                                        @Override
                                        public void onPingUpdated(float newPing) {

                                        }
                                    });
                                    connectionMap.put(remoteAddress, remotePort, mrudp);
                                    model.registerNewConnection(mrudp, userResp);
                                    sendDataOnReceivingThread(remoteAddress, remotePort, buildConnectionAckResponse(0));
                                }
                                break;

                            case disconnect:
                                if (subSocket != null) {
                                    subSocket.receiveConnected(remoteAddress, remotePort, receivingBuffer, dataLength);
                                } else {
                                    waitingForAckMap.remove(remoteAddress, remotePort);
                                }

                            default:
                                if (subSocket != null) {
                                    subSocket.receiveConnected(remoteAddress, remotePort, receivingBuffer, dataLength);
                                } else {
                                    Object[] tuple = waitingForAckMap.get(remoteAddress, remotePort);
                                    if (tuple != null) {
                                        MRUDPSocketImpl mrudp = (MRUDPSocketImpl) tuple[1];
                                        mrudp.receiveConnected(remoteAddress, remotePort, receivingBuffer, dataLength);
                                    } else {
                                        byte[] userData = new byte[dataLength - 5];
                                        System.arraycopy(receivingBuffer, 5, userData, 0, dataLength - 5);
                                        model.handleUnknownSourceMsg(userData);
                                    }
                                }
                                break;


                        }

                    } catch (SocketException se) {
                        log("Got SocketException in receiving thread. Quitting...");
                        break;
                    } catch (IOException e) {
                        log("IOE in receiving thread");
                    } catch (Exception ex) {
                        log(ex);
                    }
                }

                Iterable<MRUDPSocketImpl> values = connectionMap.values();
                for (MRUDPSocketImpl value : values) {
                    value.serverStopped();
                }

            }
        }, "MRUDP-ServerSocket-Rec" + serverCounter++);

        receivingThread.start();
    }

    private void dealWithNewConnectionRequest(final InetAddress address, int port, byte[] fullData) {
        Object[] tuple = waitingForAckMap.get(address, port);
        if (tuple != null) {
            MRUDPSocketImpl sock = (MRUDPSocketImpl) tuple[1];
            sock.receiveConnected(address, port, fullData, fullData.length);
            return;
        }

        int fullDataLength = fullData.length;
        byte[] userData = new byte[fullDataLength - 9];
        System.arraycopy(fullData, 9, userData, 0, userData.length);

        ConnectionResponsePackage<byte[]> connectionResponsePackage = model.validateNewConnection(address, port, userData);
        boolean isValid = connectionResponsePackage.accepted();
        byte[] response = connectionResponsePackage.getResponseData();

        if (response == null){
            throw new NullPointerException();
        }

        int socketSeq = extractInt(fullData, 1);
        int expectSeq = extractInt(fullData, 5);
        if (isValid){
            final MRUDPSocketImpl socket = new MRUDPSocketImpl(this.socket, bufferSize, address, port, socketSeq + 1, expectSeq, response, dcTimeDueToInactivity);
            waitingForAckMap.put(address, port, new Object[]{connectionResponsePackage, socket});
        }

        sendConnectionResponse(address, port, socketSeq, isValid, response);
    }

    private void sendConnectionResponse(InetAddress address, int port, int seq, boolean acceptance, byte[] responseData){
        DatagramPacket sendingPacket = this.sendingPacket;
        sendingPacket.setAddress(address);
        sendingPacket.setPort(port);
        sendingPacket.setData(buildConnectionResponse(acceptance, seq, responseData));
        try {
            socket.send(sendingPacket);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void sendDataOnReceivingThread(InetAddress address, int port, byte[] data){
        DatagramPacket sendingPacket = this.sendingPacket;
        sendingPacket.setAddress(address);
        sendingPacket.setPort(port);
        sendingPacket.setData(data);
        try {
            socket.send(sendingPacket);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void log(String msg){
        System.err.println(msg);
    }

    private void log(Throwable t){
        t.printStackTrace();
    }

    public void close(){
        if (receivingThread != null){
            receivingThread.interrupt();
        }
        socket.close();

    }

}
