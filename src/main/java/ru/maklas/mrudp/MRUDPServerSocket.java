package ru.maklas.mrudp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.HashMap;

public class MRUDPServerSocket {

    private int socketIdCounter = 0;
    private final UDPSocket socket;
    private final ServerModel model;
    private final HashMap<Long, MRUDPSocketImpl> connectionMap = new HashMap<Long, MRUDPSocketImpl>();
    private final DatagramPacket datagramPacket;
    private final DatagramPacket sendingPacket;
    private final int dcTimeDueToInactivity;
    private final int bufferSize;

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
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {

                    try {
                        DatagramPacket packet = datagramPacket;
                        socket.receive(packet);

                        InetAddress remoteAddress = packet.getAddress();
                        int remotePort = packet.getPort();
                        int dataLength = packet.getLength();
                        byte[] data = new byte[dataLength];
                        System.arraycopy(packet.getData(), 0, data, 0, dataLength);

                        MRUDPSocketImpl subSocket;
                        synchronized (connectionMap) {
                            subSocket = connectionMap.get(addressHash(remoteAddress, remotePort));
                        }

                        if (subSocket != null){
                            subSocket.receiveConnected(remoteAddress, remotePort, data);
                            continue;
                        } else {
                            if (dataLength < 5){
                                log("Got message from unknown address less than 5 bytes long");
                                continue;
                            }
                            if (data[0] == MRUDPSocketImpl.connectionRequest){
                                dealWithNewConnectionRequest(remoteAddress, remotePort, data);
                            } else {
                                byte[] userData = new byte[dataLength - 5];
                                System.arraycopy(data, 5, userData, 0, dataLength - 5);
                                model.handleUnknownSourceMsg(userData);
                            }
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
            }
        });

        thread.start();
    }

    private void dealWithNewConnectionRequest(InetAddress address, int port, byte[] fullData) {
        int fullDataLength = fullData.length;
        byte[] userData = new byte[fullDataLength - 9];
        System.arraycopy(fullData, 9, userData, 0, userData.length);

        ConnectionResponsePackage<byte[]> connectionResponsePackage = model.validateNewConnection(address, port, userData);
        boolean isValid = connectionResponsePackage.accepted();
        byte[] response = connectionResponsePackage.getResponseData();

        if (response == null){
            throw new NullPointerException();
        }

        int socketSeq = MRUDPSocketImpl.extractInt(fullData, 1);
        int expectSeq = MRUDPSocketImpl.extractInt(fullData, 5);
        if (isValid){
            sendConnectionResponse(address, port, socketSeq, true, response);
            final MRUDPSocketImpl socket = new MRUDPSocketImpl(this.socket, bufferSize, address, port, socketSeq + 1, expectSeq, response, dcTimeDueToInactivity);
            connectionMap.put(addressHash(address, port), socket);
            socket.addListener(new MRUDPListener() {
                @Override
                public void onDisconnect(MRUDPSocket fixedBufferMRUDP2) {
                    connectionMap.remove(addressHash(fixedBufferMRUDP2.getRemoteAddress(), fixedBufferMRUDP2.getRemotePort()));
                    model.onSocketDisconnected(socket);
                }

                @Override
                public void onPingUpdated(float newPing) {

                }
            });
            model.registerNewConnection(socket, connectionResponsePackage);
        } else {
            sendConnectionResponse(address, port, socketSeq, false, response);
        }
    }

    private void sendConnectionResponse(InetAddress address, int port, int seq, boolean acceptance, byte[] responseData){
        DatagramPacket sendingPacket = this.sendingPacket;
        sendingPacket.setAddress(address);
        sendingPacket.setPort(port);
        sendingPacket.setData(MRUDPSocketImpl.buildConnectionResponse(acceptance, seq, responseData));
        try {
            socket.send(sendingPacket);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }


    private static long addressHash(InetAddress address, int port){
        byte[] addressBytes = address.getAddress();
        long ret =    addressBytes[0] << 24         |
                (addressBytes[1] & 0xFF) << 16 |
                (addressBytes[2] & 0xFF) << 8  |
                (addressBytes[3] & 0xFF);
        ret += ((long) port) << 32;
        return ret;
    }

    private void log(String msg){
        System.err.println(msg);
    }

    private void log(Throwable t){
        t.printStackTrace();
    }

}
