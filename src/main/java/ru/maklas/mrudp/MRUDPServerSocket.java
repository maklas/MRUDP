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

                        InetAddress remoteAddress = packet.getAddress();
                        int remotePort = packet.getPort();
                        int dataLength = packet.getLength();
                        byte[] data = new byte[dataLength];
                        System.arraycopy(packet.getData(), 0, data, 0, dataLength);

                        MRUDPSocketImpl subSocket;
                        subSocket = connectionMap.get(remoteAddress, remotePort);

                        if (subSocket != null){
                            if (data[0] == connectionResponseAcknowledgment){
                                dealWithAck(remoteAddress, remotePort);
                            }
                            subSocket.receiveConnected(remoteAddress, remotePort, data);
                            continue;
                        } else {
                            if (dataLength < 5){
                                log("Got message from unknown address less than 5 bytes long");
                                continue;
                            }
                            if (data[0] == connectionRequest){
                                dealWithNewConnectionRequest(remoteAddress, remotePort, data);
                            } else if (data[0] == connectionResponseAcknowledgment) {
                                dealWithAck(remoteAddress, remotePort);
                            } else {
                                byte[] userData = new byte[dataLength - 5];
                                System.arraycopy(data, 5, userData, 0, dataLength - 5);
                                model.handleUnknownSourceMsg(userData);
                            }
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

    private void dealWithAck(InetAddress remoteAddress, int remotePort) {
        Object[] tuple = waitingForAckMap.remove(remoteAddress, remotePort);
        if (tuple == null){
            System.err.println("Socket for ack not found!");
            return;
        }

        ConnectionResponsePackage<byte[]> userResp = (ConnectionResponsePackage<byte[]>) tuple[0];
        MRUDPSocketImpl mrudp = (MRUDPSocketImpl) tuple[1];

        model.registerNewConnection(mrudp, userResp);
    }

    private void dealWithNewConnectionRequest(final InetAddress address, int port, byte[] fullData) {
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

            sendConnectionResponse(address, port, socketSeq, true, response);
            final MRUDPSocketImpl socket = new MRUDPSocketImpl(this.socket, bufferSize, address, port, socketSeq + 1, expectSeq, response, dcTimeDueToInactivity);

            waitingForAckMap.put(address, port, new Object[]{connectionResponsePackage, socket});
            connectionMap.put(address, port, socket);

            socket.addListener(new MRUDPListener() {
                @Override
                public void onDisconnect(MRUDPSocket fixedBufferMRUDP2) {
                    InetAddress addr = fixedBufferMRUDP2.getRemoteAddress();
                    int p = fixedBufferMRUDP2.getRemotePort();
                    waitingForAckMap.remove(addr, p);
                    connectionMap.remove(addr, p);
                    model.onSocketDisconnected(socket);
                }

                @Override
                public void onPingUpdated(float newPing) {

                }
            });

        } else {
            sendConnectionResponse(address, port, socketSeq, false, response);
        }
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
