package ru.maklas.locator;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Locator {

    private final ExecutorService executor;
    private final DatagramPacket sendingPacket;
    private final DatagramPacket receivingPacket;
    private final DatagramSocket socket;

    public Locator(int port, int bufferSize, int maxThreads) throws Exception{
        executor = Executors.newFixedThreadPool(maxThreads);
        sendingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        receivingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        socket = new DatagramSocket();
        socket.setBroadcast(true);
    }

    public List<LocatorResponse> startDiscovery(int discoveryTimeMS, int resends, byte[] requestData) {


        return null;
    }

}
