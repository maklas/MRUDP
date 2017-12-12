package ru.maklas.mrudp;

import ru.maklas.mrudp.UDPSocket;

import java.io.IOException;
import java.net.DatagramPacket;

public class PacketLossUDPSocket implements UDPSocket{

    private UDPSocket socket;
    private double packetLossChance;

    /**
     * Creates {@link UDPSocket} out of existing {@link UDPSocket}, but this one gets a chanse of <b>not</b>
     * sending a {@link DatagramPacket} when asked to. Used for testing
     * @param socket which socket to use to send Datagrams
     * @param packetLossChance Probability of a packet loss. Must be >=0 and <=100. Measured in percents %
     */
    public PacketLossUDPSocket(UDPSocket socket, double packetLossChance) {
        if (packetLossChance < 0 || packetLossChance > 100){
            throw new RuntimeException("Packet loss chance must be from 0 to 100%");
        }
        this.socket = socket;
        this.packetLossChance = packetLossChance / 100;
    }

    @Override
    public int getLocalPort() {
        return socket.getLocalPort();
    }

    @Override
    public void send(DatagramPacket packet) throws Exception {
        double random = Math.random();
        if (random > packetLossChance)
            socket.send(packet);
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        socket.receive(packet);
    }

    @Override
    public void close() {
        socket.close();
    }
}
