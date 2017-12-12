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

public class MRUDPSocketImpl implements MRUDPSocket, SocketIterator {

    static final byte reliableRequest = 1;
    static final byte reliableResponse = 2;
    static final byte unreliableRequest = 3;
    static final byte pingRequest = 4;
    static final byte pingResponse = 5;
    static final byte connectionRequest = 6;
    static final byte connectionResponseAccepted = 7;
    static final byte connectionResponseRejected = 8;
    static final byte connectionResponseAcknowledgment = 9;
    static final byte disconnect = 10;


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

    private final int dcTimeDueToInactivity;
    private volatile long lastCommunicationTime;

    private static final int defaultPingCD = 4000;
    private volatile int pingCD = defaultPingCD;
    private long lastPingSendTime;
    private volatile float currentPing = 0;
    private Object userData = null;

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
        //Если не подключен в данный момент,
        // устанавливает connectingToAddress и connectingToPort, а connectingResponse = null;
        // Ждет пока connectingResponse не станет значением. Как только станет - возвращает. Или как врем ожидания кончится.
        // Если в ответе будет ACCEPTED, сокет будет уже подключен.
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
            connectingToAddress = null;
            return new ConnectionResponse(ConnectionResponse.Type.NO_RESPONSE, new byte[0]);
        }

        boolean accepted = fullResponse[0] == connectionResponseAccepted;
        byte[] userData = new byte[fullResponse.length - 5];
        System.arraycopy(fullResponse, 5, userData, 0, userData.length);
        ConnectionResponse connectionResponse = new ConnectionResponse(accepted ? ConnectionResponse.Type.ACCEPTED : ConnectionResponse.Type.NOT_ACCEPTED, userData);
        if (accepted) {
            sendData(connectingToAddress, connectingToPort, buildConnectionAck(seq));
            state.set(SocketState.CONNECTED);
        } else {
            state.set(SocketState.NOT_CONNECTED);
            //FOR TEST ONLY
            if (fullResponse[0] != connectionResponseRejected){
                System.err.println("Got wrong package for connection response");
            }
        }
        long currentTime = System.currentTimeMillis();
        this.lastCommunicationTime = currentTime;
        this.lastPingSendTime = currentTime;
        this.lastConnectedAddress = address;
        this.lastConnectedPort = port;
        this.connectingToAddress = null;
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
            final Thread receivingThread = new Thread(new Runnable() {
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

        final Thread updateThread = new Thread(new Runnable() {
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

    @Override
    public void update() {
        SocketState socketState = state.get();

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
                long currTime = System.currentTimeMillis();
                if (currTime - lastCommunicationTime > dcTimeDueToInactivity){
                    receiveQueue.offer(new byte[0]);
                    return;
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


    }

    private void sendPing() {
        int seq = this.seq.getAndIncrement();
        long currentTimeNano = System.nanoTime();
        byte[] fullPackage = buildPingRequest(seq, currentTimeNano);
        saveRequest(seq, fullPackage);
        sendData(lastConnectedAddress, lastConnectedPort, fullPackage);
    }

    @Override
    public void close() {
        if (!isConnected()){
            System.err.println("Closing, but not connected!");
            return;
        }
        sendData(lastConnectedAddress, lastConnectedPort, buildDisconnect());
        state.set(SocketState.NOT_CONNECTED);
        flushBuffers();
        triggerDCListeners();
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
                log("IOException while trying to send via DatagramSocket");
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
            case connectionResponseAccepted:
                //ignore I guess
                break;
            case connectionResponseRejected:
                //ignore I guess
                break;
            case disconnect:
                receiveQueue.offer(new byte[0]);
                break;
            case connectionResponseAcknowledgment:
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



    private static byte[] buildReliableRequest (int seq, byte [] data){
        return build5byte(reliableRequest, seq, data);
    }

    private static byte[] buildReliableResponse (int seq){
        return build5byte(reliableResponse, seq);
    }

    private static byte[] buildUnreliableRequest (byte[] data){
        return build5byte(unreliableRequest, 0, data);
    }

    private static byte[] buildPingRequest (int seq, long startTime){
        byte[] nanos = new byte[8];
        putLong(nanos, startTime, 0);
        return build5byte(pingRequest, seq, nanos);
    }

    private static byte[] buildPingResponse (int seq, long startTime){
        byte[] timeAsBytes = new byte[8];
        putLong(timeAsBytes, startTime, 0);
        return build5byte(pingResponse, seq, timeAsBytes);
    }

    static byte[] buildConnectionRequest (int seq, int otherSeq, byte[] data){
        byte[] fullData = new byte[data.length + 9];
        fullData[0] = connectionRequest;
        putInt(fullData, seq, 1);
        putInt(fullData, otherSeq, 5);
        System.arraycopy(data, 0, fullData, 9, data.length);
        return fullData;
    }

    static byte[] buildConnectionResponse (boolean accepted, int seq, byte[] data){
        return build5byte(accepted ? connectionResponseAccepted : connectionResponseRejected, seq, data);
    }

    static byte[] buildConnectionAck (int seq){
        return build5byte(connectionResponseAcknowledgment, seq);
    }

    private static byte[] buildDisconnect (){
        return build5byte(disconnect, 0);
    }

    private static byte[] build5byte(byte settings, int seq, byte [] data){
        int dataLength = data.length;
        byte[] ret = new byte[dataLength + 5];
        ret[0] = settings;
        putInt(ret, seq, 1);
        System.arraycopy(data, 0, ret, 5, dataLength);
        return ret;
    }

    private static byte[] build5byte(byte settings, int seq){
        byte[] ret = new byte[5];
        ret[0] = settings;
        putInt(ret, seq, 1);
        return ret;
    }

    static void putInt(byte[] bytes, int value, int offset) {
        bytes[    offset] = (byte) (value >>> 24);
        bytes[1 + offset] = (byte) (value >>> 16);
        bytes[2 + offset] = (byte) (value >>> 8);
        bytes[3 + offset] = (byte)  value;
    }

    static int extractInt(byte[] bytes, int offset){
        return
                 bytes[offset] << 24             |
                (bytes[1 + offset] & 0xFF) << 16 |
                (bytes[2 + offset] & 0xFF) << 8  |
                (bytes[3 + offset] & 0xFF);
    }

    private static void putLong(byte[] bytes, long value, int offset) {
        bytes[    offset] = (byte) (value >>> 56);
        bytes[1 + offset] = (byte) (value >>> 48);
        bytes[2 + offset] = (byte) (value >>> 40);
        bytes[3 + offset] = (byte) (value >>> 32);
        bytes[4 + offset] = (byte) (value >>> 24);
        bytes[5 + offset] = (byte) (value >>> 16);
        bytes[6 + offset] = (byte) (value >>> 8);
        bytes[7 + offset] = (byte)  value;
    }

    private static long extractLong(byte[] bytes, int offset){
        long first = extractInt(bytes, offset);
        long second = extractInt(bytes, offset + 4);
        return second + (first << 32);
    }

    public static void printSettings(byte settingsByte){
        System.out.println(getSettingsAsString(settingsByte));
    }

    public static String getSettingsAsString(byte settingsBytes){
        switch (settingsBytes){
            case reliableRequest                 : return "ReliableRequest(" + reliableRequest + ")";
            case reliableResponse                : return "ReliableResponse(" + reliableResponse + ")";
            case unreliableRequest               : return "UnreliableRequest(" + unreliableRequest + ")";
            case pingRequest                     : return "PingRequest(" + pingRequest + ")";
            case pingResponse                    : return "PingResponse(" + pingResponse + ")";
            case connectionRequest               : return "ConnectionRequest(" + connectionRequest + ")";
            case connectionResponseAccepted      : return "ConnectionResponseAccepted(" + connectionResponseAccepted + ")";
            case connectionResponseRejected      : return "ConnectionResponseRejected(" + connectionResponseRejected + ")";
            case connectionResponseAcknowledgment: return "ConnectionResponseAcknowledgment(" + connectionResponseAcknowledgment + ")";
            case disconnect                      : return "Disconnect(" + disconnect + ")";
            default                              : return "UNKNOWN TYPE(" + settingsBytes + ")";
        }
    }

}
