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

                        MRUDPSocketImpl MRUDPSocketImpl;
                        synchronized (connectionMap) {
                            MRUDPSocketImpl = connectionMap.get(addressHash(remoteAddress, remotePort));
                        }
                        {
                            String s = new String(data, 5, dataLength - 5);
                            if (s.length() != 0) {
                                //System.out.println("Data: " + s + ", from " + remoteAddress.getHostAddress() + ":" + remotePort + ". " + (MRUDPSocketImpl == null ? "ERRRRRRRRRRRRRRRRR" : "OK"));
                            }
                        }

                        if (MRUDPSocketImpl != null){
                            MRUDPSocketImpl.receive(remoteAddress, remotePort, data);
                            continue;
                        } else {
                            if (dataLength < 5){
                                log("Got message from unknown address less than 5 bytes long");
                                continue;
                            }
                            boolean[] settings = getSettings(data[0]);
                            boolean isConnection = settings[MRUDPSocketImpl.IS_CONNECTION_POS];
                            boolean isRequest = settings[MRUDPSocketImpl.IS_REQUEST_POS];

                            if (isConnection && isRequest){
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
        byte[] validationResponse = model.validateNewConnection(address, port, userData);

        boolean isValid = validationResponse != null;
        int socketSeq = exctractInt(fullData, 1);
        int expectSeq = exctractInt(fullData, 5);
        if (isValid){
            if (validationResponse.length == 0){
                validationResponse = new byte[]{0};
            }
            sendConnectionResponse(address, port, socketSeq, true, validationResponse);
            final MRUDPSocketImpl socket = new MRUDPSocketImpl(this.socket, bufferSize, address, port, socketSeq + 1, expectSeq, validationResponse, dcTimeDueToInactivity);
            connectionMap.put(addressHash(address, port), socket);
            socket.addListener(new MRUDPListener() {
                @Override
                public void onDisconnect(MRUDPSocket fixedBufferMRUDP2) {
                    connectionMap.remove(addressHash(fixedBufferMRUDP2.getRemoteAddress(), fixedBufferMRUDP2.getRemotePort()));
                    model.onSocketDisconnected(socket);
                }
            });
            model.registerNewConnection(socket);
        } else {
            sendConnectionResponse(address, port, socketSeq, false, new byte[]{0, 0, 0, 0});
        }
    }

    private void sendConnectionResponse(InetAddress address, int port, int seq, boolean acceptance, byte[] responseData){
        DatagramPacket sendingPacket = this.sendingPacket;
        sendingPacket.setAddress(address);
        sendingPacket.setPort(port);
        sendingPacket.setData(MRUDPSocketImpl.buildConnectionResponse(seq, acceptance, responseData));
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

    private static boolean[] getSettings(byte setByte){
        return new boolean[]{
                ((setByte &  1)     == 1),
                ((setByte >> 1 & 1) == 1),
                ((setByte >> 2 & 1) == 1),
                ((setByte >> 3 & 1) == 1),
                ((setByte >> 4 & 1) == 1),
                ((setByte >> 5 & 1) == 1),
                ((setByte >> 6 & 1) == 1),
                ((setByte >> 7 & 1) == 1)
        };
    }

    private static int exctractInt(byte[] bytes, int offset){
        return
                bytes[offset] << 24             |
                        (bytes[1 + offset] & 0xFF) << 16 |
                        (bytes[2 + offset] & 0xFF) << 8  |
                        (bytes[3 + offset] & 0xFF);
    }
}
