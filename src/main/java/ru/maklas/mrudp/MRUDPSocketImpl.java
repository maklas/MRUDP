package ru.maklas.mrudp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static ru.maklas.mrudp.MRUDPUtils.*;

/**
 * Default implementation of MRUDPSocket
 */
public class MRUDPSocketImpl implements MRUDPSocket, SocketIterator {

    private static volatile int threadCounter;

    private final AtomicReference<SocketState> state = new AtomicReference<SocketState>(SocketState.NOT_CONNECTED);
    private InetAddress lastConnectedAddress = null;
    private int lastConnectedPort = -1;
    private InetAddress connectingToAddress = null;
    private int connectingToPort = -1;
    private volatile byte[] connectingResponse = null;
    private volatile byte[] connectingRequest = null;
    private final AtomicInteger seq = new AtomicInteger(0);
    private final UDPSocket socket;
    private final DatagramPacket sendingPacket;
    private final DatagramPacket responsePacket;
    private final Object requestSendingMonitor = new Object();
    private final int bufferSize;

    private final AtomicQueue<byte[]> receiveQueue = new AtomicQueue<byte[]>(10000);
    private volatile int lastInsertedSeq = 0;

    private volatile boolean started = false;
    private boolean interrupted = false;
    private volatile boolean processing = false;
    private volatile MDisconnectionListener[] dcListeners = new MDisconnectionListener[0];
    private volatile MPingListener[] pingListeners = new MPingListener[0];

    private boolean createdByServer = false;
    private byte[] responseForConnect = new byte[]{0};
    private volatile boolean ackDelivered = false;
    private static final byte[] zeroLengthByte = new byte[0];

    private final int dcTimeDueToInactivity;
    private volatile long lastCommunicationTime;
    private boolean dcOnInactivity = true;

    private static final int defaultPingCD = 4000;
    private volatile int pingCD = defaultPingCD;
    private long lastPingSendTime;
    private volatile float currentPing = 0;
    private Object userData = null;


    private Thread updateThread;
    private Thread receivingThread;

    /**
     * Sumple constructor
     * @param bufferSize Size of datagram packets. Note that MRUDP has 5 bytes overhead. Use maximum size of your byte[] to be sent + 5 to set this parameter.
     * @throws Exception In case UDP socket can't be opened.
     */
    public MRUDPSocketImpl(int bufferSize) throws Exception{
        this(new JavaUDPSocket(), bufferSize, 12 * 1000);
    }

    /**
     * Complex constructor
     * @param dSocket UDPSocket implementation which is used for udp data sending
     * @param bufferSize Size of datagram packets. Note that MRUDP has 5 bytes overhead. Use maximum size of your byte[] to be sent + 5 to set this parameter.
     * @param dcTimeDueToInactivity Time in milliseconds. If socket on the other end doesn't response for specified time,
     *                              socket will be closed.
     *                              Note that{@link MRUDPSocket#setPingUpdateTime(int)} must be a smaller value to keep constant connection.
     */
    public MRUDPSocketImpl(UDPSocket dSocket, int bufferSize, int dcTimeDueToInactivity) {
        this.socket = dSocket;
        this.sendingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        this.responsePacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        this.dcTimeDueToInactivity = dcTimeDueToInactivity;
        this.createdByServer = false;
        this.lastCommunicationTime = System.currentTimeMillis();
        this.bufferSize = bufferSize;
    }

    /**
     * Private constructor which is used to create server sub-sockets
     */
    MRUDPSocketImpl(UDPSocket socket, int bufferSize, InetAddress connectedAddress, int connectedPort, int socketSeq, int expectSeq, byte[] responseForConnect, int dcTimeDueToInactivity) {
        this.socket = socket;
        this.sendingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        this.responsePacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        this.responseForConnect = responseForConnect;
        this.dcTimeDueToInactivity = dcTimeDueToInactivity;
        this.createdByServer = true;
        this.lastConnectedAddress = connectedAddress;
        this.lastConnectedPort = connectedPort;
        this.lastInsertedSeq = expectSeq;
        this.seq.set(socketSeq);
        this.state.set(SocketState.CONNECTED);
        sendingPacket.setAddress(connectedAddress);
        sendingPacket.setPort(connectedPort);
        this.lastCommunicationTime = System.currentTimeMillis();
        this.ackDelivered = true;
        this.bufferSize = bufferSize;
    }

    @Override
    public ConnectionResponse connect(int timeout, InetAddress address, int port, final byte[] data) {
        if (address == null || port < 0) {
            throw new NullPointerException();
        }
        if (createdByServer){
            throw new RuntimeException("Can't change connection of socket that was created by server");
        }

        flushBuffers();

        SocketState stateAtTheBeginning = state.get();
        if (stateAtTheBeginning != SocketState.NOT_CONNECTED){
            return new ConnectionResponse(ConnectionResponse.Type.ALREADY_CONNECTED_OR_CONNECTING, zeroLengthByte);
        }

        state.set(SocketState.CONNECTING);
        ExecutorService e = Executors.newSingleThreadExecutor();
        connectingToAddress = address;
        connectingToPort = port;
        sendingPacket.setAddress(address);
        sendingPacket.setPort(port);
        connectingResponse = null;
        final long connectStartTime = System.currentTimeMillis();
        this.lastPingSendTime = connectStartTime;
        this.seq.set(0);
        final int serverSequenceNumber = 0;
        lastInsertedSeq = serverSequenceNumber;

        int seq = this.seq.getAndIncrement();
        byte[] fullData = buildConnectionRequest(seq, serverSequenceNumber, data);
        connectingRequest = fullData;
        sendData(fullData);
        final Future<byte[]> futureResponse = e.submit(new Callable<byte[]>() {
            @Override
            public byte[] call() throws Exception {
                byte[] ret = connectingResponse;
                while (ret == null){
                    Thread.sleep(5);
                    ret = connectingResponse;
                }
                currentPing = (float)(System.currentTimeMillis() - connectStartTime);
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
            return new ConnectionResponse(ConnectionResponse.Type.NO_RESPONSE, zeroLengthByte);
        }

        boolean accepted = fullResponse[0] == connectionResponseAccepted;
        byte[] userData = new byte[fullResponse.length - 5];
        System.arraycopy(fullResponse, 5, userData, 0, userData.length);
        ConnectionResponse connectionResponse = new ConnectionResponse(accepted ? ConnectionResponse.Type.ACCEPTED : ConnectionResponse.Type.NOT_ACCEPTED, userData);
        if (accepted) {
            ackDelivered = false;
            sendData(buildConnectionAck(seq));
            state.set(SocketState.CONNECTED);
            long currentTimeAfterConnect = System.currentTimeMillis();
            this.lastCommunicationTime = currentTimeAfterConnect;
            this.lastPingSendTime = currentTimeAfterConnect;
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
            sendData(fullPackage);
            return true;
        }
        return false;
    }

    public boolean sendOff5(byte[] dataWithOffset5){
        if (isConnected()) {
            int seq = this.seq.getAndIncrement();
            appendReliableRequest(seq, dataWithOffset5);
            saveRequest(seq, dataWithOffset5);
            sendData(dataWithOffset5);
            return true;
        }
        return false;
    }

    @Override
    public boolean sendUnreliable(byte[] data) {
        if (isConnected()) {
            byte[] fullPackage = buildUnreliableRequest(data);
            sendData(fullPackage);
            return true;
        }
        return false;
    }

    @Override
    public boolean sendUnreliableOff5(byte[] data) {
        if (isConnected()) {
            byte[] fullPackage = appendUnreliableRequest(data);
            sendData(fullPackage);
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
                    UDPSocket socket = MRUDPSocketImpl.this.socket;
                    byte[] receivingBuffer = new byte[bufferSize];
                    DatagramPacket packet = new DatagramPacket(receivingBuffer, bufferSize);
                    AtomicReference<SocketState> atomicState = MRUDPSocketImpl.this.state;

                    while (!Thread.interrupted()) {

                        try {
                            socket.receive(packet);

                            InetAddress remoteAddress = packet.getAddress();
                            int remotePort = packet.getPort();
                            int dataLength = packet.getLength();

                            SocketState socketState = atomicState.get();

                            switch (socketState){
                                case NOT_CONNECTED:
                                    break;
                                case CONNECTING:
                                    receiveWhileConnecting(remoteAddress, remotePort, receivingBuffer, dataLength);
                                    break;
                                case CONNECTED:
                                    receiveConnected(remoteAddress, remotePort, receivingBuffer, dataLength);
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
            }, "MRUDP-ClientSocket-Rec-" + threadCounter++);

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
        }, "MRUDP-ClientSocket-Upd-" + threadCounter++);
        updateThread.start();
    }

    void update() {
        SocketState socketState = state.get();

        try {
            switch (socketState){
                case CONNECTING:
                    byte[] fullConnectData = connectingRequest;
                    if (connectingToAddress != null && fullConnectData != null){
                        sendData(fullConnectData);
                    }
                    break;
                case CONNECTED:
                    if (!ackDelivered){
                        sendData(buildConnectionAck(0));
                        break;
                    }
                    final long currTime = System.currentTimeMillis();
                    if (dcOnInactivity && currTime - lastCommunicationTime > dcTimeDueToInactivity){
                        receiveQueue.put(zeroLengthByte);
                        break;
                    }

                    if (currTime - lastPingSendTime > pingCD){
                        sendPing();
                        lastPingSendTime = currTime;
                    }
                    synchronized (requestList) {
                        for (SortedIntList.Node<byte[]> aRequestList : requestList) {
                            byte[] fullDataReq = aRequestList.value;
                            sendData(fullDataReq);
                        }
                    }
                    break;
            }
        } catch (Exception ignore) {}


    }

    private void sendPing() {
        int seq = this.seq.getAndIncrement();
        long currentTimeNano = System.nanoTime();
        byte[] fullPackage = getPingRequestCACHED(seq, currentTimeNano);
        saveRequest(seq, fullPackage);
        sendData(fullPackage);
    }

    @Override
    public boolean disconnect() {
        if (createdByServer){
            return dcOrCloseByServer();
        } else {
            return disconnectByClient();
        }
    }

    @Override
    public void close() {
        if (createdByServer){
            dcOrCloseByServer();
        } else {
            closeByClient();
        }
    }

    private void flushBuffers() {
        synchronized (waitings) {
            waitings.clear();
        }
        synchronized (requestList) {
            requestList.clear();
        }
        if (!isProcessing())
            receiveQueue.clear();
    }

    @Override
    public void addDCListener(MDisconnectionListener listener) {
        this.dcListeners = addObject(this.dcListeners, listener);
    }

    @Override
    public void addPingListener(MPingListener listener) {
        this.pingListeners = addObject(this.pingListeners, listener);
    }

    @Override
    public void removeDCListener(MDisconnectionListener listener) {
        this.dcListeners = removeObject(this.dcListeners, listener);
    }

    @Override
    public void removePingListener(MPingListener listener) {
        this.pingListeners = removeObject(this.pingListeners, listener);
    }

    @Override
    public void removeAllListeners() {
        this.pingListeners = new MPingListener[0];
        this.dcListeners = new MDisconnectionListener[0];
    }

    /**
     * @return True if got interrupted in the middle of the process
     */
    @Override
    public boolean receive(SocketProcessor processor) {
        if (processing){
            throw new ConcurrentModificationException("Can't be processed by 2 threads at the same time");
        }
        processing = true;
        interrupted = false;
        try {

            byte[] poll = receiveQueue.poll();
            while (poll != null){
                if (poll.length == 0){
                    if (createdByServer){
                        receivedDCByServerOrTimeOut();
                    } else {
                        receivedDCByClientOrTimeOut();
                    }
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
        return interrupted;
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
        MDisconnectionListener[] listeners = this.dcListeners;
        for (MDisconnectionListener listener : listeners) {
            listener.onDisconnect(this);
        }
    }

    private void triggerPingListeners(float ping) {
        MPingListener[] listeners = this.pingListeners;
        for (MPingListener listener : listeners) {
            listener.onPingUpdated(this, ping);
        }

    }

    void sendData(byte[] fullPackage){
        synchronized (requestSendingMonitor) {
            DatagramPacket sendingPacket = this.sendingPacket;
            sendingPacket.setData(fullPackage);
            try {
                socket.send(sendingPacket);
            } catch (Exception e) {
                log("IOException while trying to send via DatagramSocket" + e.getMessage());
            }
        }
    }

    /**
     * Only used internally in receiving thread of ServerSocket or ClientSocket
     */
    void sendResponseData(InetAddress returnAddress, int port, byte[] fullPackage){
        DatagramPacket responsePacket = this.responsePacket;
        responsePacket.setAddress(returnAddress);
        responsePacket.setPort(port);
        responsePacket.setData(fullPackage);
        try {
            socket.send(responsePacket);
        } catch (Exception e) {
            log("IOException while trying to send via DatagramSocket" + e.getMessage());
        }
    }

    void receiveConnected(InetAddress address, int port, byte[] fullPackage, int packageLength){
        this.lastCommunicationTime = System.currentTimeMillis();
        if (packageLength < 5){
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
                byte[] resp = getReliableResponseCACHED(seq);
                sendResponseData(address, port, resp);
                final int expectedSeq = this.lastInsertedSeq + 1;

                if (expectedSeq == seq){
                    insert(expectedSeq, fullPackage, 5, packageLength - 5);
                    checkForWaitingDatas();
                } else if (expectedSeq < seq){
                    byte[] userData = new byte[packageLength - 5];
                    System.arraycopy(fullPackage, 5, userData, 0, userData.length);
                    insertIntoWaitingDatas(seq, userData);
                }
                break;
            case reliableResponse:
                if (packageLength != 5){
                    log("Unexpected response length: " + packageLength);
                }
                removeRequest(seq);
                break;
            case unreliableRequest:
                int userDataLength = packageLength - 5;
                byte[] data = new byte[userDataLength];
                System.arraycopy(fullPackage, 5,  data, 0, userDataLength);
                if (userDataLength == 5){
                    log("Received unreliable request of 0 length! That will lead to dc");
                }
                receiveQueue.put(data);
                break;
            case pingRequest:
                final long startTime = extractLong(fullPackage, 5);
                final byte[] fullResponse = getPingResponseCACHED(seq, startTime);
                sendResponseData(address, port, fullResponse);

                final int expectSeq = this.lastInsertedSeq + 1;

                if (expectSeq == seq){
                    lastInsertedSeq = seq;
                    checkForWaitingDatas();
                } else if (expectSeq < seq){
                    insertIntoWaitingDatas(seq, zeroLengthByte);
                }

                break;
            case pingResponse:
                final long startingTime = extractLong(fullPackage, 5);
                boolean removed = removeRequest(seq);
                if (removed){
                    float ping = ((float) (System.nanoTime() - startingTime))/1000000f;
                    this.currentPing = ping;
                    triggerPingListeners(ping);
                }
                break;
            case connectionRequest:
                if (createdByServer){
                    byte[] response = buildConnectionResponse(true, seq, responseForConnect);
                    sendResponseData(address, port, response);
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
                receiveQueue.put(zeroLengthByte);
                break;
            case connectionAcknowledgment:
                //Ignore I guess
                break;
            default:
                log("Unknown settings received: " + settings);
                break;
        }
    }

    void receiveWhileConnecting(InetAddress address, int port, byte[] fullPackage, int packageLength){
        this.lastCommunicationTime = System.currentTimeMillis();
        if (packageLength < 5){
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
                log("Got Reliable request while connecting. SHOULD NEVER HAPPEN!");

                byte[] userData = new byte[packageLength - 5];
                System.arraycopy(fullPackage, 5, userData, 0, userData.length);
                insertIntoWaitingDatas(seq, userData);
                break;

            case connectionRequest:
                log("Attempt to connect to a client-socket");
                break;

            case connectionResponseAccepted:
            case connectionResponseRejected:
                byte[] userPackageWithSettings = new byte[packageLength];
                System.arraycopy(fullPackage, 0, userPackageWithSettings, 0, packageLength);
                this.connectingResponse = userPackageWithSettings;
                break;

            case disconnect:
                receiveQueue.put(zeroLengthByte);
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

        synchronized (waitings) {
            expectedSeq = lastInsertedSeq + 1;
            byte[] mayBeFullData = waitings.remove(expectedSeq);

            while (mayBeFullData != null) {
                if (mayBeFullData.length == 0) { // Если в очереди остался пинг
                    this.lastInsertedSeq = expectedSeq;
                } else {
                    insert(expectedSeq, mayBeFullData);
                }
                expectedSeq = lastInsertedSeq + 1;
                mayBeFullData = waitings.remove(expectedSeq);
            }
        }

    }

    private void insert(int seq, byte[] fullData, int offset, int length){
        this.lastInsertedSeq = seq;
        byte[] userData = new byte[length];
        System.arraycopy(fullData, offset, userData, 0, length);
        receiveQueue.put(userData);
    }

    private void insert(int seq, byte[] userData){
        this.lastInsertedSeq = seq;
        receiveQueue.put(userData);
    }

    private final SortedIntList<byte[]> waitings = new SortedIntList<byte[]>();
    private void insertIntoWaitingDatas(int seq, byte[] fullPackage) {
        synchronized (waitings) {
            waitings.insert(seq, fullPackage);
        }
    }

    private final SortedIntList<byte[]> requestList = new SortedIntList<byte[]>();
    private void saveRequest(int seq, byte[] fullPackage) {
        synchronized (requestList) {
            requestList.insert(seq, fullPackage);
        }
    }

    private boolean removeRequest(int seq) {
        synchronized (requestList) {
            return requestList.remove(seq) != null;
        }
    }




    /* CLOSING OPERATIONS */

    private void interruptUpdateThread(){
        if (updateThread != null){
            updateThread.interrupt();
        }
    }

    private void interruptUpdateThreadAndJoin(int wait){
        if (updateThread != null){
            try {
                updateThread.interrupt();
                updateThread.join(wait);
            } catch (InterruptedException e) {}
        }
    }

    private void interruptReceivingThread(){
        if (receivingThread != null){
            receivingThread.interrupt();
        }
    }

    private void interruptReceivingThreadAndJoin(int wait){
        if (updateThread != null){
            try {
                updateThread.interrupt();
                updateThread.join(wait);
            } catch (InterruptedException e) {}
        }
    }

    private boolean disconnectByClient(){
        if (state.get() != SocketState.NOT_CONNECTED){
            sendData(buildDisconnect());
            state.set(SocketState.NOT_CONNECTED);
            triggerDCListeners();
            flushBuffers();
            return true;
        }
        return false;
    }

    private void closeByClient(){
        interruptUpdateThread();
        interruptReceivingThread();
        if (state.get() != SocketState.NOT_CONNECTED) {
            sendData(buildDisconnect());
            state.set(SocketState.NOT_CONNECTED);
            triggerDCListeners();
        }
        socket.close();
        flushBuffers();
    }

    private void receivedDCByClientOrTimeOut(){
        sendData(buildDisconnect());
        state.set(SocketState.NOT_CONNECTED);
        triggerDCListeners();
        flushBuffers();
    }


    private boolean dcOrCloseByServer(){
        if (state.get() != SocketState.NOT_CONNECTED){
            sendData(buildDisconnect());
            state.set(SocketState.NOT_CONNECTED);
            interruptUpdateThread();
            triggerDCListeners();
            flushBuffers();
            return true;
        }
        return false;
    }

    private void receivedDCByServerOrTimeOut(){
        if (isConnected()) {
            state.set(SocketState.NOT_CONNECTED);
            flushBuffers();
            interruptUpdateThread();
            flushBuffers();
            triggerDCListeners();
        }
    }

    void serverStopped(){
        sendData(buildDisconnect());
        state.set(SocketState.NOT_CONNECTED);
        interruptUpdateThread();
        flushBuffers();
        triggerDCListeners();
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
    public Object setUserData(Object userData) {
        Object oldUserData = this.userData;
        this.userData = userData;
        return oldUserData;
    }

    @Override
    public void pauseDCcheck() {
        dcOnInactivity = false;
    }

    @Override
    public boolean dcCheckIsPaused() {
        return dcOnInactivity;
    }

    @Override
    public void resumeDCcheck() {
        lastCommunicationTime = System.currentTimeMillis();
        dcOnInactivity = true;
    }

    @Override
    public int getCurrentSeq() {
        return seq.get();
    }

    @Override
    public Object getUserData() {
        return userData;
    }

    @Override
    public boolean isClosed() {
        return socket.isClosed();
    }

/* UTILS */

    private void log(String msg){
        //-- System.err.println(msg);
    }

    private void log(Throwable e){
        e.printStackTrace();
    }


    private byte[] cachedReliableResponse = MRUDPUtils.buildReliableResponse(0);
    private byte[] getReliableResponseCACHED(int seq){
        byte[] reliableResponse = this.cachedReliableResponse;
        MRUDPUtils.putInt(reliableResponse, seq, 1);
        return reliableResponse;
    }

    private byte[] cachedPingRequest = MRUDPUtils.buildPingRequest(0, 0);
    private byte[] getPingRequestCACHED(int seq, long startTime){
        byte[] pingRequest = this.cachedPingRequest;
        MRUDPUtils.putInt(pingRequest, seq, 1);
        putLong(pingRequest, startTime, 5);
        return pingRequest;
    }

    private byte[] cachedPingResponse = MRUDPUtils.buildPingResponse(0, 0);
    private byte[] getPingResponseCACHED(int seq, long startTime){
        byte[] pingResponse = this.cachedPingResponse;
        MRUDPUtils.putInt(pingResponse, seq, 1);
        putLong(pingResponse, startTime, 5);
        return pingResponse;
    }

    private <T> T[] addObject(T[] array, T object) {
        int length = array.length;

        for (int i = 0; i < length; i++) {
            if (array[i] == object){
                return array;
            }
        }
        T[] newArray = Arrays.copyOf(array, length + 1);
        System.arraycopy(array, 0, newArray, 0, length);
        newArray[length] = object;
        return newArray;
    }

    private <T> T[] removeObject(T[] array, T object){
        int length = array.length;

        for (int i = 0; i < length; i++) {
            if (array[i] == object){
                T[] newArray =  Arrays.copyOf(array, length - 1);

                int aliveCounter = 0;
                for (int j = 0; j < length; j++) {
                    if (array[i] != object){
                        newArray[aliveCounter++] = array[i];
                    }
                }
                return newArray;
            }
        }
        return array;
    }

}
