package ru.maklas.mrudp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static ru.maklas.mrudp.MRUDPUtils.*;

public class MRUDPSocketImpl implements MRUDPSocket, SocketIterator {


    private final AtomicReference<SocketState> state = new AtomicReference<SocketState>(SocketState.NOT_CONNECTED);
    private InetAddress lastConnectedAddress = null;
    private int lastConnectedPort = -1;
    private InetAddress connectingToAddress = null;
    private int connectingToPort = -1;
    private volatile byte[] connectingResponse = null;
    private volatile byte[] connectingRequest = null;
    private final AtomicInteger seq = new AtomicInteger(0);
    private final UDPSocket socket;
    private final DatagramPacket receivingPacket;
    private final DatagramPacket sendingPacket;
    private final Object sendingMonitor = new Object();

    private final LinkedBlockingQueue<byte[]> receiveQueue = new LinkedBlockingQueue<byte[]>();
    private int lastInsertedSeq = 0;

    private volatile boolean started = false;
    private boolean interrupted = false;
    private volatile boolean processing = false;
    private MRUDPListener[] listeners = new MRUDPListener[0];

    private boolean createdByServer = false;
    private byte[] responseForConnect = new byte[]{0};
    private volatile boolean ackDelivered = false;

    private final int dcTimeDueToInactivity;
    private volatile long lastCommunicationTime;

    private static final int defaultPingCD = 4000;
    private volatile int pingCD = defaultPingCD;
    private long lastPingSendTime;
    private volatile float currentPing = 0;
    private Object userData = null;

    private Thread updateThread;
    private Thread receivingThread;

    public MRUDPSocketImpl(int bufferSize) throws Exception{
        this(new JavaUDPSocket(), bufferSize, 12 * 1000);
    }

    public MRUDPSocketImpl(UDPSocket dSocket, int bufferSize, int dcTimeDueToInactivity) {
        socket = dSocket;
        this.receivingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        this.sendingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        this.dcTimeDueToInactivity = dcTimeDueToInactivity;
        createdByServer = false;
        lastCommunicationTime = System.currentTimeMillis();
    }

    MRUDPSocketImpl(UDPSocket socket, int bufferSize, InetAddress connectedAddress, int connectedPort, int socketSeq, int expectSeq, byte[] responseForConnect, int dcTimeDueToInactivity) {
        this.socket = socket;
        this.receivingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        this.sendingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        this.responseForConnect = responseForConnect;
        this.dcTimeDueToInactivity = dcTimeDueToInactivity;
        this.createdByServer = true;
        this.lastConnectedAddress = connectedAddress;
        this.lastConnectedPort = connectedPort;
        this.lastInsertedSeq = expectSeq;
        this.seq.set(socketSeq);
        this.state.set(SocketState.CONNECTED);
        this.lastCommunicationTime = System.currentTimeMillis();
    }

    @Override
    public ConnectionResponse connect(int timeout, InetAddress address, int port, byte[] data) {
        if (address == null || port < 0) {
            throw new NullPointerException();
        }

        SocketState stateAtTheBeginning = state.get();
        if (stateAtTheBeginning != SocketState.NOT_CONNECTED){
            return new ConnectionResponse(ConnectionResponse.Type.ALREADY_CONNECTED_OR_CONNECTING, new byte[0]);
        }



        state.set(SocketState.CONNECTING);
        ExecutorService e = Executors.newSingleThreadExecutor();
        connectingToAddress = address;
        connectingToPort = port;
        connectingResponse = null;
        this.seq.set(0);
        final int serverSequenceNumber = 0;
        lastInsertedSeq = serverSequenceNumber;

        int seq = this.seq.getAndIncrement();
        byte[] fullData = buildConnectionRequest(seq, serverSequenceNumber, data);
        connectingRequest = fullData;
        sendData(address, port, fullData);
        final Future<byte[]> futureResponse = e.submit(new Callable<byte[]>() {
            @Override
            public byte[] call() throws Exception {
                byte[] ret = connectingResponse;
                while (ret == null){
                    Thread.sleep(20);
                    ret = connectingResponse;
                }
                return ret;
            }
        });

        byte[] fullResponse = null;
        try {
            fullResponse = futureResponse.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        } catch (ExecutionException e1) {
            e1.printStackTrace();
        } catch (TimeoutException e1) {
            //when time exceeds
        } catch (CancellationException e1){
            e1.printStackTrace();
        }

        if (fullResponse == null){
            state.set(SocketState.NOT_CONNECTED);
            return new ConnectionResponse(ConnectionResponse.Type.NO_RESPONSE, new byte[0]);
        }

        boolean accepted = fullResponse[0] == connectionResponseAccepted;
        byte[] userData = new byte[fullResponse.length - 5];
        System.arraycopy(fullResponse, 5, userData, 0, userData.length);
        ConnectionResponse connectionResponse = new ConnectionResponse(accepted ? ConnectionResponse.Type.ACCEPTED : ConnectionResponse.Type.NOT_ACCEPTED, userData);
        if (accepted) {
            ackDelivered = false;
            sendData(connectingToAddress, connectingToPort, buildConnectionAck(seq));
            state.set(SocketState.CONNECTED);
            long currentTime = System.currentTimeMillis();
            this.lastCommunicationTime = currentTime;
            this.lastPingSendTime = currentTime;
            this.lastConnectedAddress = address;
            this.lastConnectedPort = port;
        } else {
            state.set(SocketState.NOT_CONNECTED);
        }
        return connectionResponse;
    }

    @Override
    public boolean send(byte[] data) {
        if (isConnected()) {
            int seq = this.seq.getAndIncrement();
            byte[] fullPackage = buildReliableRequest(seq, data);
            saveRequest(seq, fullPackage);
            sendData(lastConnectedAddress, lastConnectedPort, fullPackage);
            return true;
        }
        return false;
    }

    @Override
    public boolean sendUnreliable(byte[] data) {
        if (isConnected()) {
            byte[] fullPackage = buildUnreliableRequest(data);
            sendData(lastConnectedAddress, lastConnectedPort, fullPackage);
            return true;
        }
        return false;
    }

    public void start(final int updateThreadSleepTimeMS){
        if (started){
            System.err.println("Double start registered");
            return;
        }
        started = true;

        if (!createdByServer) {
            receivingThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.interrupted()) {

                        try {
                            DatagramPacket packet = receivingPacket;
                            socket.receive(packet);

                            InetAddress remoteAddress = packet.getAddress();
                            int remotePort = packet.getPort();
                            int dataLength = packet.getLength();
                            byte[] fullData = new byte[dataLength];
                            System.arraycopy(packet.getData(), 0, fullData, 0, dataLength);

                            SocketState socketState = state.get();

                            switch (socketState){
                                case NOT_CONNECTED:
                                    break;
                                case CONNECTING:
                                    receiveWhileConnecting(remoteAddress, remotePort, fullData);
                                    break;
                                case CONNECTED:
                                    receiveConnected(remoteAddress, remotePort, fullData);
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
                }
            });

            receivingThread.start();
        }

        updateThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    try {
                        update();
                        Thread.sleep(updateThreadSleepTimeMS);
                    } catch (InterruptedException interruption) {
                        break;
                    }
                }
            }
        });
        updateThread.start();
    }

    public void update() {
        SocketState socketState = state.get();

        try {
            switch (socketState){
                case CONNECTING:
                    InetAddress connectAddress = connectingToAddress;
                    int connectPort = connectingToPort;
                    byte[] fullConnectData = connectingRequest;
                    if (connectAddress != null && fullConnectData != null){
                        sendData(connectAddress, connectPort, fullConnectData);
                    }
                    break;
                case CONNECTED:
                    if (!ackDelivered){
                        sendData(lastConnectedAddress, lastConnectedPort, buildConnectionAck(0));
                        break;
                    }
                    final long currTime = System.currentTimeMillis();
                    if (currTime - lastCommunicationTime > dcTimeDueToInactivity){
                        receiveQueue.offer(new byte[0]);
                        break;
                    }

                    if (currTime - lastPingSendTime > pingCD){
                        sendPing();
                        lastPingSendTime = currTime;
                    }
                    synchronized (requestList) {
                        Iterator<Object[]> savedRequests = requestList.iterator();
                        while (savedRequests.hasNext()) {
                            byte[] fullDataReq = (byte[]) savedRequests.next()[1];
                            sendData(lastConnectedAddress, lastConnectedPort, fullDataReq);
                        }
                    }
                    break;
            }
        } catch (Exception ignore) {}


    }

    private void sendPing() {
        int seq = this.seq.getAndIncrement();
        long currentTimeNano = System.nanoTime();
        byte[] fullPackage = buildPingRequest(seq, currentTimeNano);
        saveRequest(seq, fullPackage);
        sendData(lastConnectedAddress, lastConnectedPort, fullPackage);
    }

    @Override
    public boolean disconnect() {
        if (!isConnected()){
            System.err.println("Closing, but not connected!");
            return false;
        }
        sendData(lastConnectedAddress, lastConnectedPort, buildDisconnect());
        state.set(SocketState.NOT_CONNECTED);
        flushBuffers();
        triggerDCListeners();
        return true;
    }

    void closeByServer(){
        state.set(SocketState.NOT_CONNECTED);
        if (updateThread != null){
            updateThread.interrupt();
        }
    }

    @Override
    public void close() {
        disconnect();
        if (updateThread != null){
            updateThread.interrupt();
        }
        if (receivingThread != null){
            receivingThread.interrupt();
        }
        socket.close();
    }

    private void flushBuffers() {
        requestList.clear();
        receiveQueue.clear();
    }

    @Override
    public void addListener(MRUDPListener listener) {
        MRUDPListener[] listeners = this.listeners;
        int length = listeners.length;

        for (int i = 0; i < length; i++) {
            if (listeners[i] == listener){
                return;
            }
        }

        MRUDPListener[] newListeners = new MRUDPListener[length + 1];
        System.arraycopy(listeners, 0, newListeners, 0, length);
        newListeners[length] = listener;
        this.listeners = newListeners;
    }

    @Override
    public void removeListeners(MRUDPListener listener) {
        MRUDPListener[] listeners = this.listeners;
        int length = listeners.length;

        for (int i = 0; i < length; i++) {
            if (listeners[i] == listener){

                MRUDPListener[] newListeners = new MRUDPListener[length-1];

                int aliveCounter = 0;
                for (int j = 0; j < length; j++) {
                    if (listeners[i] != listener){
                        newListeners[aliveCounter++] = listeners[i];
                    }
                }
                this.listeners = newListeners;
                return;
            }
        }
    }

    @Override
    public void receive(SocketProcessor processor) {
        if (processing){
            throw new RuntimeException("Can't be processed by 2 threads at the same time");
        }
        processing = true;
        interrupted = false;
        try {

            byte[] poll = receiveQueue.poll();
            while (poll != null){
                if (poll.length == 0){
                    state.set(SocketState.NOT_CONNECTED);
                    flushBuffers();
                    triggerDCListeners();
                    break;
                }
                processor.process(poll, this, this);
                if (interrupted){
                    break;
                }
                poll = receiveQueue.poll();
            }

        } catch (Throwable t){
            log(t);
        }
        processing = false;
    }

    @Override
    public void setPingUpdateTime(final int ms) {
        final int newPingCD;

        if (ms < 0){
            newPingCD = defaultPingCD;
        } else if (ms == 0){
            newPingCD = Integer.MAX_VALUE - 100000;
        } else {
            newPingCD = ms;
        }
        pingCD = newPingCD;
    }

    private void triggerDCListeners(){
        MRUDPListener[] listeners = this.listeners;
        for (MRUDPListener listener : listeners) {
            listener.onDisconnect(this);
        }
    }

    private void triggerPingListeners(float ping) {
        MRUDPListener[] listeners = this.listeners;
        for (MRUDPListener listener : listeners) {
            listener.onPingUpdated(ping);
        }

    }

    void sendData(InetAddress address, int port, byte[] fullPackage){
        synchronized (sendingMonitor) {
            sendingPacket.setAddress(address);
            sendingPacket.setPort(port);
            sendingPacket.setData(fullPackage);
            try {
                socket.send(sendingPacket);
            } catch (Exception e){
                log("IOException while trying to send via DatagramSocket" + e.getMessage());
            }
        }
    }

    void receiveConnected(InetAddress address, int port, byte[] fullPackage){
        this.lastCommunicationTime = System.currentTimeMillis();
        if (fullPackage.length < 5){
            log("Received message less than 5 bytes long!");
            return;
        }

        if (!address.equals(lastConnectedAddress) || port != lastConnectedPort){
            log("Received message from not connected address");
        }

        byte settings = fullPackage[0];
        int seq = extractInt(fullPackage, 1);

        switch (settings){
            case reliableRequest:
                byte[] resp = buildReliableResponse(seq);
                sendData(address, port, resp);
                final int expectedSeq = this.lastInsertedSeq + 1;

                if (expectedSeq == seq){
                    insert(expectedSeq, fullPackage, 5);
                    checkForWaitingDatas();
                } else if (expectedSeq > seq){
                } else {
                    byte[] userData = new byte[fullPackage.length - 5];
                    System.arraycopy(fullPackage, 5, userData, 0, userData.length);
                    insertIntoWaitingDatas(seq, userData);
                }
                break;
            case reliableResponse:
                int responseLength = fullPackage.length;
                if (responseLength != 5){
                    log("Unexpected response length: " + responseLength);
                }
                removeRequest(seq);
                break;
            case unreliableRequest:
                byte[] data = new byte[fullPackage.length - 5];
                System.arraycopy(fullPackage, 5,  data, 0, data.length);
                if (data.length == 5){
                    log("Received unreliable request of 0 length!");
                    break;
                }
                receiveQueue.offer(data);
                break;
            case pingRequest:
                final long startTime = extractLong(fullPackage, 5);
                final byte[] fullResponse = buildPingResponse(seq, startTime);
                sendData(address, port, fullResponse);

                final int expectSeq = this.lastInsertedSeq + 1;

                if (expectSeq == seq){
                    lastInsertedSeq++;
                    checkForWaitingDatas();
                } else if (expectSeq > seq){
                } else {
                    insertIntoWaitingDatas(seq, new byte[0]);
                }

                break;
            case pingResponse:
                final long startingTime = extractLong(fullPackage, 5);
                boolean removed = removeRequest(seq);
                if (removed){
                    long currentTime = System.nanoTime();
                    float ping = ((float) (currentTime - startingTime))/1000000f;
                    this.currentPing = ping;
                    triggerPingListeners(ping);
                }
                break;
            case connectionRequest:
                if (createdByServer){
                    byte[] response = buildConnectionResponse(true, seq, responseForConnect);
                    sendData(address, port, response);
                }
                break;
            case connectionAcknowledgmentResponse:
                ackDelivered = true;
                break;
            case connectionResponseAccepted:
                //ignore I guess
                break;
            case connectionResponseRejected:
                //ignore I guess
                break;
            case disconnect:
                receiveQueue.offer(new byte[0]);
                break;
            case connectionAcknowledgment:
                //Ignore I guess
                break;
            default:
                log("Unknown settings received: " + settings);
                break;
        }
    }


    void receiveWhileConnecting(InetAddress address, int port, byte[] fullPackage){
        this.lastCommunicationTime = System.currentTimeMillis();
        if (fullPackage.length < 5){
            log("Received message less than 5 bytes long!");
            return;
        }

        if (!address.equals(connectingToAddress) || !(port == connectingToPort)){
            log("Received message from unknown address while connecting to another");
            return;
        }

        byte settings = fullPackage[0];
        int seq = extractInt(fullPackage, 1);

        switch (settings){
            case reliableRequest:
                byte[] resp = buildReliableResponse(seq);
                sendData(address, port, resp);

                byte[] userData = new byte[fullPackage.length - 5];
                System.arraycopy(fullPackage, 5, userData, 0, userData.length);
                insertIntoWaitingDatas(seq, userData);
                break;

            case connectionRequest:
                log("Attempt to connect to a client-socket");
                break;

            case connectionResponseAccepted:
            case connectionResponseRejected:
                this.connectingResponse = fullPackage;
                break;

            case disconnect:
                receiveQueue.offer(new byte[0]);
                break;

            default:
                break;

        }
    }

    /* SOCKET ITERATOR */

    @Override
    public void stop() {
        interrupted = true;
    }

    @Override
    public boolean isProcessing() {
        return processing;
    }





    /* DATA MANUPULATION */

    private void checkForWaitingDatas() {
        int expectedSeq;
        boolean madeIt = true;

        synchronized (waitings) {
            while (madeIt) {
                madeIt = false;
                expectedSeq = lastInsertedSeq + 1;

                for (Object[] pair : waitings) {
                    if (((Integer) pair[0]) == expectedSeq) {
                        byte[] bytes = (byte[]) pair[1];
                        if (bytes.length == 0){ // Если в очереди остался пинг, то мы ничего не делаем, а просто пропускаем seq
                            lastInsertedSeq++;
                            continue;
                        } else {
                            insert(expectedSeq, bytes);
                            madeIt = true;
                            continue;
                        }
                    }
                }

            }
        }

    }

    private void insert(int seq, byte[] fullData, int offset){
        this.lastInsertedSeq = seq;
        byte[] userData = new byte[fullData.length - offset];
        System.arraycopy(fullData, offset, userData, 0, userData.length);
        receiveQueue.offer(userData);
    }

    private void insert(int seq, byte[] userData){
        this.lastInsertedSeq = seq;
        receiveQueue.offer(userData);
    }

    private final ArrayList<Object[]> waitings = new ArrayList<Object[]>(); //<Integer.class, byte[].class>
    private void insertIntoWaitingDatas(int seq, byte[] fullPackage) {
        synchronized (waitings) {
            for (Object[] pair : waitings) {
                if (((Integer) pair[0]) == seq) {
                    return;
                }
            }
            waitings.add(new Object[]{Integer.valueOf(seq), fullPackage});
        }
    }

    private final ArrayList<Object[]> requestList = new ArrayList<Object[]>();
    private void saveRequest(int seq, byte[] fullPackage) {
        synchronized (requestList) {
            requestList.add(new Object[]{seq, fullPackage});
        }
    }

    private boolean removeRequest(int seq) {
        synchronized (requestList) {
            Iterator<Object[]> iterator = requestList.iterator();
            while (iterator.hasNext()) {
                Object[] next = iterator.next();
                if ((Integer) next[0] == seq) {
                    iterator.remove();
                    return true;
                }
            }
        }
        return false;
    }












    /* GETTERS */

    @Override
    public boolean isConnected() {
        return state.get() == SocketState.CONNECTED;
    }

    @Override
    public SocketState getState() {
        return state.get();
    }

    @Override
    public InetAddress getRemoteAddress() {
        return lastConnectedAddress;
    }

    @Override
    public int getRemotePort() {
        return lastConnectedPort;
    }

    public int getLocalPort(){
        return socket.getLocalPort();
    }

    @Override
    public float getPing() {
        return currentPing;
    }

    @Override
    public void setUserData(Object userData) {
        this.userData = userData;
    }

    @Override
    public int getCurrentSeq() {
        return seq.get();
    }

    @Override
    public Object getUserData() {
        return userData;
    }

    /* UTILS */

    private void log(String msg){
        System.err.println(msg);
    }

    private void log(Throwable e){
        e.printStackTrace();
    }


}
