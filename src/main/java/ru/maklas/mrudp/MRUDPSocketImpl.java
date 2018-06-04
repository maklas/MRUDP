package ru.maklas.mrudp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
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

    /**
     * Maximal size of receiving queue. Don't put too little or might be some problems
     */
    public static int receivingQueueSize = 5000;
    private static volatile int threadCounter;

    //ADDRESSES
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

    private final AtomicQueue<Object> receiveQueue = new AtomicQueue<Object>(receivingQueueSize);
    private volatile int lastInsertedSeq = 0;

    //LISTENERS
    private volatile MDisconnectionListener[] dcListeners = new MDisconnectionListener[0];
    private volatile MPingListener[] pingListeners = new MPingListener[0];

    //STATE DATA
    private final AtomicReference<SocketState> state = new AtomicReference<SocketState>(SocketState.NOT_CONNECTED);
    private volatile boolean started = false;
    private boolean interrupted = false;
    private volatile boolean processing = false;
    private boolean createdByServer = false;
    private byte[] responseForConnect = new byte[]{0};
    private volatile boolean ackDelivered = false;
    private static final byte[] zeroLengthByte = new byte[0];

    //AUTO-DC TRACKING
    private final int dcTimeDueToInactivity;
    private volatile long lastCommunicationTime;
    private volatile boolean dcOnInactivity = true;

    //PING
    private static final int defaultPingCD = 4000;
    private volatile int pingCD = defaultPingCD;
    private long lastPingSendTime;
    private volatile float currentPing = 0;
    private Object userData = null;

    //NTP
    private volatile MRUDP_NTP_Listener ntpListener;
    private volatile NTPInterractionData[] ntpDatas;
    private volatile boolean ntpWasSuccessful = false;
    private volatile long ntpOffset;

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
    public final ConnectionResponse connect(final int timeout, InetAddress address, int port, final byte[] data) {
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
                final int awaits = 5;
                long attempts = (long) (Math.ceil(timeout / awaits) + 1);
                long currentAttempt = 0;
                byte[] ret = connectingResponse;
                while (ret == null && currentAttempt < attempts){
                    Thread.sleep(awaits);
                    ret = connectingResponse;
                    currentAttempt++;
                }
                currentPing = (float)(System.currentTimeMillis() - connectStartTime);
                return ret;
            }
        });
        e.shutdown();

        byte[] fullResponse = null;
        try {
            fullResponse = futureResponse.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e1) {
            log(e1);
        } catch (ExecutionException e1) {
            log(e1);
        } catch (TimeoutException e1) {
            //when time exceeds
        } catch (CancellationException e1){
            log(e1);
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
    public final boolean send(byte[] data) {
        if (isConnected()) {
            int seq = this.seq.getAndIncrement();
            byte[] fullPackage = buildReliableRequest(seq, data);
            saveRequest(seq, fullPackage);
            sendData(fullPackage);
            return true;
        }
        return false;
    }

    @Override
    public final boolean sendBatch(MRUDPBatch batch) {
        if (!isConnected()){
            return false;
        }
        int size = batch.size();
        switch (size){
            case 0: return true;
            case 1:
                int seq = this.seq.getAndIncrement();
                byte[] fullPackage = buildReliableRequest(seq, batch.get(0));
                saveRequest(seq, fullPackage);
                sendData(fullPackage);
                return true;
        }

        int bufferSize = this.bufferSize;

        int i = 0;
        while (i < size){
            int seq = this.seq.getAndIncrement();
            Object[] tuple = buildSafeBatch(seq, MRUDPUtils.batch, batch, i, bufferSize);
            byte[] fullPackage = (byte[]) tuple[0];
            i = ((Integer) tuple[1]);
            saveRequest(seq, fullPackage);
            sendData(fullPackage);
        }

        return true;
    }

    @Override
    public final boolean sendUnreliableBatch(MRUDPBatch batch) {
        if (!isConnected()){
            return false;
        }
        int size = batch.size();
        switch (size){
            case 0: return true;
            case 1:
                byte[] fullPackage = buildUnreliableRequest(batch.get(0));
                sendData(fullPackage);
                return true;
        }

        int bufferSize = this.bufferSize;

        int i = 0;
        while (i < size){
            Object[] tuple = buildSafeBatch(0, MRUDPUtils.unreliableBatch, batch, i, bufferSize);
            byte[] fullPackage = (byte[]) tuple[0];
            i = ((Integer) tuple[1]);
            sendData(fullPackage);
        }

        return true;
    }

    public final boolean sendOff5(byte[] dataWithOffset5){
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
    public final boolean sendUnreliable(byte[] data) {
        if (isConnected()) {
            byte[] fullPackage = buildUnreliableRequest(data);
            sendData(fullPackage);
            return true;
        }
        return false;
    }

    @Override
    public final boolean sendUnreliableOff5(byte[] data) {
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

    final void update() {
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
                        receiveQueue.put(CONNECTION_TIME_OUT);
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
        } catch (Exception e) {
            log(e);
        }
    }

    long getCurrentTimeForNTP(){
        return System.currentTimeMillis();
    }

    @Override
    public void launchNTP(int timeMS, int requests, MRUDP_NTP_Listener listener){
        if (ntpListener != null){
            listener.onFailure(this);
            return;
        }

        this.ntpListener = listener;
        this.ntpDatas = new NTPInterractionData[requests];
        new NTPThread(timeMS, requests).start();
    }

    @Override
    public final boolean timeIsKnown(){
        return ntpWasSuccessful;
    }

    @Override
    public final long getTimeOnConnectedDevice() {
        return System.currentTimeMillis() + ntpOffset;
    }

    @Override
    public final long getTimeOffset() {
        return ntpOffset;
    }

    private void sendPing() {
        int seq = this.seq.getAndIncrement();
        long currentTimeNano = System.nanoTime();
        byte[] fullPackage = getPingRequestCACHED(seq, currentTimeNano);
        saveRequest(seq, fullPackage);
        sendData(fullPackage);
    }

    @Override
    public final boolean disconnect(String msg) {
        if (createdByServer){
            return dcOrCloseByServer(msg);
        } else {
            return disconnectByClient(msg);
        }
    }

    @Override
    public final boolean disconnect() {
        return disconnect(DEFAULT_DC_MSG);
    }

    @Override
    public final void close() {
        close(DEFAULT_CLOSE_MSG);
    }

    @Override
    public final void close(String msg) {
        if (createdByServer){
            dcOrCloseByServer(msg);
        } else {
            closeByClient(msg);
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
    public final void addDCListener(MDisconnectionListener listener) {
        this.dcListeners = addObject(this.dcListeners, listener);
    }

    @Override
    public final void addPingListener(MPingListener listener) {
        this.pingListeners = addObject(this.pingListeners, listener);
    }

    @Override
    public final void removeDCListener(MDisconnectionListener listener) {
        this.dcListeners = removeObject(this.dcListeners, listener);
    }

    @Override
    public final void removePingListener(MPingListener listener) {
        this.pingListeners = removeObject(this.pingListeners, listener);
    }

    @Override
    public final void removeAllListeners() {
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

            Object poll = receiveQueue.poll();
            while (poll != null){
                if (poll instanceof byte[]){
                    processor.process((byte[]) poll, this, this);
                } else if (poll instanceof String){
                    if (createdByServer){
                        receivedDCByServerOrTimeOut((String) poll);
                    } else {
                        receivedDCByClientOrTimeOut((String) poll);
                    }
                    break;
                }
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
    public final void setPingUpdateTime(final int ms) {
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

    private void triggerDCListeners(String msg){
        MDisconnectionListener[] listeners = this.dcListeners;
        for (MDisconnectionListener listener : listeners) {
            listener.onDisconnect(this, msg);
        }
    }

    private void triggerPingListeners(float ping) {
        MPingListener[] listeners = this.pingListeners;
        for (MPingListener listener : listeners) {
            listener.onPingUpdated(this, ping);
        }

    }

    final void sendData(byte[] fullPackage){
        synchronized (requestSendingMonitor) {
            DatagramPacket sendingPacket = this.sendingPacket;
            sendingPacket.setData(fullPackage);
            try {
                socket.send(sendingPacket);
            } catch (Exception e) {
                log("IOException while trying to send via DatagramSocket" + e.getMessage());
            }
        }
        dataWasSent(fullPackage);
    }

    /**
     * A method that can be overriden for performance profiling purposes.
     * Gets called every time any data was sent via this Socket. By user or by this implementation
     * @param data your data that was thrown on UDP. Note that 8 bytes of data is also hidden in UDP header, so
     *             if you want to calculate data + header add 8 to this data.length
     */
    public void dataWasSent(byte[] data){}

    /**
     * Only used internally in receiving thread of ServerSocket or ClientSocket
     */
    final void sendResponseData(InetAddress returnAddress, int port, byte[] fullPackage){
        DatagramPacket responsePacket = this.responsePacket;
        responsePacket.setAddress(returnAddress);
        responsePacket.setPort(port);
        responsePacket.setData(fullPackage);
        try {
            socket.send(responsePacket);
        } catch (Exception e) {
            log("IOException while trying to send via DatagramSocket" + e.getMessage());
        }
        dataWasSent(fullPackage);
    }

    final void receiveConnected(InetAddress address, int port, byte[] fullPackage, int packageLength){
        long receivedTime = System.currentTimeMillis();
        this.lastCommunicationTime = receivedTime;
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
            case batch:
                byte[] respBatch = getReliableResponseCACHED(seq);
                sendResponseData(address, port, respBatch);
                final int expectedSeqBatch = this.lastInsertedSeq + 1;

                try {
                    byte[][] batchPackets = MRUDPUtils.breakBatchDown(fullPackage);

                    if (expectedSeqBatch == seq){
                        this.lastInsertedSeq = seq;

                        for (byte[] batchPacket : batchPackets) {
                            receiveQueue.put(batchPacket);
                        }

                        checkForWaitingDatas();
                    } else if (expectedSeqBatch < seq){
                        insertIntoWaitingDatas(seq, MRUDPUtils.breakBatchDown(fullPackage));
                    }

                } catch (Exception ignore) {}

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
            case unreliableBatch:
                byte[][] batchPackets;
                try {
                    batchPackets = MRUDPUtils.breakBatchDown(fullPackage);
                    for (byte[] batchPacket : batchPackets) {
                        receiveQueue.put(batchPacket);
                    }
                } catch (Exception ignore) {}
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
            case disconnect:
                int dataLength = packageLength - 5;
                if (dataLength == 0){
                    receiveQueue.put(DEFAULT_DC_MSG);
                } else {
                    receiveQueue.put(new String(fullPackage, 5, dataLength));
                }
                break;
            case ntpRequest:
                sendData(buildNTPResponse(seq, extractLong(fullPackage, 5), receivedTime));
                break;
            case ntpResponse:
                NTPInterractionData[] ntpDatas = this.ntpDatas;
                if (ntpDatas == null || seq >= ntpDatas.length || seq < 0){
                    break;
                }
                long t0 = extractLong(fullPackage, 5);
                long t1 = extractLong(fullPackage, 13);
                long t2 = extractLong(fullPackage, 21);
                ntpDatas[seq] = new NTPInterractionData(t0, t1, t2, getCurrentTimeForNTP());
                break;
            case connectionAcknowledgment:
                //Ignore I guess
                break;
            case connectionResponseAccepted:
                //ignore I guess
                break;
            case connectionResponseRejected:
                //ignore I guess
                break;
            default:
                log("Unknown settings received: " + settings);
                break;
        }
    }

    final void receiveWhileConnecting(InetAddress address, int port, byte[] fullPackage, int packageLength){
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
                int dataLength = packageLength - 5;
                if (dataLength == 0){
                    receiveQueue.put(DEFAULT_DC_MSG);
                } else {
                    receiveQueue.put(new String(fullPackage, 5, dataLength));
                }
                break;

            default:
                break;

        }
    }

    /* SOCKET ITERATOR */

    @Override
    public final void stop() {
        interrupted = true;
    }

    @Override
    public final boolean isProcessing() {
        return processing;
    }

    @Override
    public int getSendBufferSize(){
        synchronized (requestList){
            return requestList.size;
        }
    }



    /* DATA MANUPULATION */

    private void checkForWaitingDatas() {
        int expectedSeq;

        synchronized (waitings) {
            expectedSeq = lastInsertedSeq + 1;
            Object mayBeData = waitings.remove(expectedSeq);

            while (mayBeData != null) {
                if (mayBeData instanceof byte[]){
                    if (((byte[]) mayBeData).length == 0) { // Если в очереди остался пинг
                        this.lastInsertedSeq = expectedSeq;
                    } else {
                        insert(expectedSeq, (byte[]) mayBeData);
                    }
                } else {
                    this.lastInsertedSeq = expectedSeq;
                    byte[][] batch = (byte[][]) mayBeData;
                    for (byte[] bytes : batch) {
                        receiveQueue.put(bytes);
                    }
                }
                expectedSeq = lastInsertedSeq + 1;
                mayBeData = waitings.remove(expectedSeq);
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

    /**
     * Сортированные данные которые могут быть как byte[] - чистый пакет или byte[][] - batch пакет
     */
    private final SortedIntList<Object> waitings = new SortedIntList<Object>();
    private void insertIntoWaitingDatas(int seq, Object userData) {
        synchronized (waitings) {
            waitings.insert(seq, userData);
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
            } catch (InterruptedException e) {
                log(e);
            }
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
            } catch (InterruptedException e) {
                log(e);
            }
        }
    }

    private boolean disconnectByClient(String msg){
        ntpWasSuccessful = false;
        ntpOffset = 0;
        if (state.get() != SocketState.NOT_CONNECTED){
            sendData(buildDisconnect(msg));
            state.set(SocketState.NOT_CONNECTED);
            triggerDCListeners(msg);
            flushBuffers();
            return true;
        }
        return false;
    }

    private void closeByClient(String msg){
        ntpWasSuccessful = false;
        ntpOffset = 0;
        interruptUpdateThread();
        interruptReceivingThread();
        if (state.get() != SocketState.NOT_CONNECTED) {
            sendData(buildDisconnect(msg));
            state.set(SocketState.NOT_CONNECTED);
            triggerDCListeners(msg);
        }
        socket.close();
        flushBuffers();
    }

    private void receivedDCByClientOrTimeOut(String msg){
        ntpWasSuccessful = false;
        ntpOffset = 0;
        sendData(buildDisconnect(msg));
        state.set(SocketState.NOT_CONNECTED);
        triggerDCListeners(msg);
        flushBuffers();
    }

    private boolean dcOrCloseByServer(String msg){
        if (state.get() != SocketState.NOT_CONNECTED){
            ntpWasSuccessful = false;
            ntpOffset = 0;
            sendData(buildDisconnect(msg));
            state.set(SocketState.NOT_CONNECTED);
            interruptUpdateThread();
            triggerDCListeners(msg);
            flushBuffers();
            return true;
        }
        return false;
    }

    private void receivedDCByServerOrTimeOut(String msg){
        if (isConnected()) {
            ntpWasSuccessful = false;
            ntpOffset = 0;
            state.set(SocketState.NOT_CONNECTED);
            flushBuffers();
            interruptUpdateThread();
            flushBuffers();
            triggerDCListeners(msg);
        }
    }

    final void serverStopped(String msg){
        ntpWasSuccessful = false;
        ntpOffset = 0;
        sendData(buildDisconnect(msg));
        state.set(SocketState.NOT_CONNECTED);
        interruptUpdateThread();
        flushBuffers();
        triggerDCListeners(msg);
    }






    /* GETTERS */

    @Override
    public final boolean isConnected() {
        return state.get() == SocketState.CONNECTED;
    }

    @Override
    public final SocketState getState() {
        return state.get();
    }

    @Override
    public final InetAddress getRemoteAddress() {
        return lastConnectedAddress;
    }

    @Override
    public final int getRemotePort() {
        return lastConnectedPort;
    }

    public final int getLocalPort(){
        return socket.getLocalPort();
    }

    @Override
    public final float getPing() {
        return currentPing;
    }

    @Override
    public final Object setUserData(Object userData) {
        Object oldUserData = this.userData;
        this.userData = userData;
        return oldUserData;
    }

    @Override
    public final void pauseDCcheck() {
        dcOnInactivity = false;
    }

    @Override
    public final boolean dcCheckIsPaused() {
        return dcOnInactivity;
    }

    @Override
    public final void resumeDCcheck() {
        lastCommunicationTime = System.currentTimeMillis();
        dcOnInactivity = true;
    }

    @Override
    public final int getCurrentSeq() {
        return seq.get();
    }

    @Override
    public final <T> T getUserData() {
        return (T) userData;
    }

    @Override
    public final boolean isClosed() {
        if (createdByServer){
            if (updateThread == null){
                return false;
            }
            return !updateThread.isAlive();
        }
        return socket.isClosed();
    }

/* UTILS */

    public void log(String msg){
        //--System.err.println(msg);
    }

    public void log(Throwable e){

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




    private class NTPThread extends Thread{

        private final int time;
        private final int attempts;

        public NTPThread(int time, int attempts) {
            this.time = time;
            this.attempts = attempts;
        }

        @Override
        public void run() {
            int attempts = this.attempts;
            int ttw = time / attempts;

            if (ttw < 5){
                ttw = 5;
            }


            try {
                for (int i = 0; i < attempts; i++) {
                    sendNTPReq(i);
                    Thread.sleep(ttw);
                }

                long additionalSleepTime = ttw < 30 ? 50 : 25;
                Thread.sleep(additionalSleepTime); //Additional sleep time to get last response
                wrapUp();
            } catch (Exception e) {
                MRUDP_NTP_Listener l = ntpListener;
                if (l != null){
                    l.onFailure(MRUDPSocketImpl.this);
                }
                ntpListener = null;
            }
        }

        private void wrapUp() {
            MRUDP_NTP_Listener l = ntpListener;
            NTPInterractionData[] ntpDatas = MRUDPSocketImpl.this.ntpDatas;
            if (ntpDatas == null){
                if (l != null){
                    l.onFailure(MRUDPSocketImpl.this);
                }
                return;
            }
            if (l == null){
                return;
            }


            int successful = 0;
            double offsetSum = 0;
            int length = ntpDatas.length;
            for (int i = 0; i < length; i++) {
                if (ntpDatas[i] != null){
                    successful++;
                    offsetSum += ntpDatas[i].calculateOffset();
                }
            }
            if (successful < 2){
                ntpListener.onFailure(MRUDPSocketImpl.this);
            } else {
                final long offset = Math.round(offsetSum / successful);
                ntpWasSuccessful = true;
                ntpOffset = offset;
                l.onSuccess(MRUDPSocketImpl.this, offset, length, successful);
            }
            ntpListener = null;
        }

        private void sendNTPReq(int i) {
            byte[] req = new byte[13];
            req[0] = ntpRequest;
            putInt(req, i, 1);

            long curr = getCurrentTimeForNTP();
            putLong(req, curr, 5);
            sendData(req);
        }
    }

}
