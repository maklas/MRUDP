package ru.maklas.locator;

import ru.maklas.mrudp.AddressObjectMap;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static ru.maklas.locator.LocatorUtils.createRequest;

public class Locator {

    private final ExecutorService executor;
    private final DatagramPacket sendingPacket;
    private final DatagramPacket receivingPacket;
    private final DatagramSocket socket;
    private final byte[] uuid;
    private final InetAddress address;
    private final Receiver receiver;
    private volatile int port;
    private final AtomicInteger seqCounter = new AtomicInteger(0);
    private final AtomicBoolean discovering = new AtomicBoolean(false);
    private volatile boolean isSleeping = false;
    private volatile Thread sleepingThread;
    private final Object sleepingMonitor = new Object();

    public Locator(byte[] uuid, String address, int port, int bufferSize) throws Exception{
        this.uuid = Arrays.copyOf(uuid, 16);
        this.address = InetAddress.getByName(address);
        this.port = port;
        executor = Executors.newFixedThreadPool(2);
        sendingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        receivingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        socket = new DatagramSocket();
        socket.setBroadcast(true);
        receiver = new Receiver(receivingPacket, socket, this.uuid);
        executor.submit(receiver);
    }

    public boolean startDiscovery(final int discoveryTimeMS, final int resends, final byte[] requestData, Notifier<LocatorResponse> responseNotifier) {

        boolean succeeded = discovering.compareAndSet(false, true);
        if (!succeeded){
            return false;
        }

        final int seq = seqCounter.getAndIncrement();
        receiver.activate(seq, responseNotifier);
        executor.submit(new Runnable() {
            @Override
            public void run() {

                byte[] fullPackage = createRequest(uuid, seq, requestData);

                try {
                    final int msToWaitEachIteration = discoveryTimeMS/resends;
                    for (int i = 0; i < resends; i++) {
                        sendData(fullPackage);
                        Thread.sleep(msToWaitEachIteration);
                    }
                } catch (InterruptedException e) {}

            }
        });

        try {
            synchronized (sleepingMonitor) {
                sleepingThread = Thread.currentThread();
                isSleeping = true;
            }
            Thread.sleep(discoveryTimeMS);
            synchronized (sleepingMonitor) {
                isSleeping = false;
            }
        } catch (InterruptedException e) {}
        receiver.stop();
        discovering.set(false);
        return true;

    }


    public boolean isDiscovering(){
        return discovering.get();
    }

    public void interruptDiscovering(){
        synchronized (sleepingMonitor){
            Thread sleepingThread = this.sleepingThread;
            if (isSleeping && sleepingThread != null){
                sleepingThread.interrupt();
            }
        }


    }

    private void sendData(byte[] data){
        sendingPacket.setData(data);
        sendingPacket.setAddress(address);
        sendingPacket.setPort(port);
        try {
            socket.send(sendingPacket);
        } catch (IOException e) {}
    }


    public void dispose(){
        socket.close();
        executor.shutdown();
    }

    private static class Receiver implements Runnable{

        private final DatagramPacket receivingPacket;
        private final DatagramSocket socket;
        private final byte[] uuid;
        private int seq;
        private Notifier<LocatorResponse> responseNotifier;
        private final AddressObjectMap<Boolean> addressObjectMap;
        private volatile boolean stop = false;

        public Receiver(DatagramPacket receivingPacket, DatagramSocket socket, byte[] uuid) {
            this.receivingPacket = receivingPacket;
            this.socket = socket;
            this.uuid = uuid;
            this.addressObjectMap = new AddressObjectMap<Boolean>();
        }

        void activate(int seq, Notifier<LocatorResponse> responseNotifier){
            this.seq = seq;
            this.responseNotifier = responseNotifier;
            this.stop = false;
        }

        @Override
        public void run() {

            while (!Thread.interrupted()){

                DatagramPacket receivingPacket = this.receivingPacket;

                try {
                    socket.receive(receivingPacket);
                } catch (IOException e) {
                   break;
                }
                if (stop){
                    continue;
                }


                int length = receivingPacket.getLength();
                if (length < LocatorUtils.minMsgLength){
                    continue;
                }

                byte[] fullPackage = new byte[length];
                System.arraycopy(receivingPacket.getData(), 0, fullPackage, 0, length);
                boolean startsWithUUID = LocatorUtils.startsWithUUID(fullPackage, uuid);
                if (!startsWithUUID){
                    continue;
                }


                if (!LocatorUtils.isResponse(fullPackage)){
                    continue;
                }

                if (LocatorUtils.getSeq(fullPackage) != seq){
                    continue;
                }


                InetAddress address = receivingPacket.getAddress();
                int port = receivingPacket.getPort();

                Boolean alreadyReceived = addressObjectMap.get(address, port);
                if (alreadyReceived == null){
                    alreadyReceived = false;
                }


                if (alreadyReceived){
                    continue;
                } else {
                    addressObjectMap.put(address, port, new Boolean(true));
                    byte[] userData = new byte[length - 21];
                    System.arraycopy(fullPackage, 21, userData, 0, length - 21);
                    responseNotifier.notify(new LocatorResponse(address, port, userData));
                }


            }
        }


        public void stop() {
            stop = true;
        }
    }

}
