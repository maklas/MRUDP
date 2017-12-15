package ru.maklas.mrudp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;

import static ru.maklas.mrudp.MRUDPUtils.*;

public class MRUDPServerSocket {

    private final UDPSocket socket;
    private final ServerModel model;
    private final AddressObjectMap<MRUDPSocketImpl> connectionMap = new AddressObjectMap<MRUDPSocketImpl>();
    private final AddressObjectMap<Object[]> waitingForAckMap = new AddressObjectMap<Object[]>();
    private final DatagramPacket datagramPacket;
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
        this.datagramPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
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
                while (!Thread.interrupted()) {

                    try {
                        DatagramPacket packet = datagramPacket;
                        socket.receive(packet);

                        int dataLength = packet.getLength();
                        if (dataLength < 5){
                            continue;
                        }

                        InetAddress remoteAddress = packet.getAddress();
                        int remotePort = packet.getPort();
                        byte[] data = new byte[dataLength];
                        System.arraycopy(packet.getData(), 0, data, 0, dataLength);

                        MRUDPSocketImpl subSocket;
                        subSocket = connectionMap.get(remoteAddress, remotePort);


                        switch (data[0]) {
                            case connectionRequest:
                                if (subSocket != null) {
                                    subSocket.receiveConnected(remoteAddress, remotePort, data);
                                } else {
                                    dealWithNewConnectionRequest(remoteAddress, remotePort, data);
                                }
                                break;

                            case connectionAcknowledgment:
                                if (subSocket != null) {
                                    subSocket.sendData(remoteAddress, remotePort, buildConnectionAckResponse(0));
                                } else {
                                    Object[] tuple = waitingForAckMap.remove(remoteAddress, remotePort);
                                    if (tuple == null) {
                                        System.err.println("Socket for ack not found!");
                                        break;
                                    }

                                    ConnectionResponsePackage<byte[]> userResp = (ConnectionResponsePackage<byte[]>) tuple[0];
                                    MRUDPSocketImpl mrudp = (MRUDPSocketImpl) tuple[1];
                                    connectionMap.put(remoteAddress, remotePort, mrudp);
                                    model.registerNewConnection(mrudp, userResp);
                                    mrudp.sendData(remoteAddress, remotePort, buildConnectionAckResponse(0));
                                }
                                break;

                            case disconnect:
                                if (subSocket != null) {
                                    connectionMap.remove(remoteAddress, remotePort);
                                    model.onSocketDisconnected(subSocket);
                                } else {
                                    waitingForAckMap.remove(remoteAddress, remotePort);
                                }

                            default:
                                if (subSocket != null) {
                                    subSocket.receiveConnected(remoteAddress, remotePort, data);
                                } else {
                                    Object[] tuple = waitingForAckMap.get(remoteAddress, remotePort);
                                    if (tuple != null) {
                                        MRUDPSocketImpl mrudp = (MRUDPSocketImpl) tuple[1];
                                        mrudp.receiveConnected(remoteAddress, remotePort, data);
                                    } else {
                                        byte[] userData = new byte[dataLength - 5];
                                        System.arraycopy(data, 5, userData, 0, dataLength - 5);
                                        model.handleUnknownSourceMsg(userData);
                                    }
                                }
                                break;


                        }

                    } catch (SocketException se) {
                        log("Got SocketException in receiving thread. Quitting...");
                        MRUDPSocketImpl[] values = connectionMap.values(new MRUDPSocketImpl[0]);
                        for (MRUDPSocketImpl value : values) {
                            value.close();
                        }

                        break;
                    } catch (IOException e) {
                        log("IOE in receiving thread");
                    } catch (Exception ex) {
                        log(ex);
                    }
                }
            }
        });

        receivingThread.start();
    }

    private void dealWithNewConnectionRequest(final InetAddress address, int port, byte[] fullData) {
        Object[] tuple = waitingForAckMap.get(address, port);
        if (tuple != null) {
            MRUDPSocketImpl sock = (MRUDPSocketImpl) tuple[1];
            sock.receiveConnected(address, port, fullData);
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
