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

    static final int IS_RELIABLE_POS = 0;
    static final int IS_CONNECTION_POS = 1;
    static final int IS_REQUEST_POS = 2;
    static final int ALREADY_SENT_POS = 3;
    static final int CONNECTION_RESP_POS = 4;
    static final int DC_POS = 5;
    static final int PING_POS = 6;


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

    private boolean interrupted = false;
    private volatile boolean processing = false;
    private MRUDPListener[] listeners = new MRUDPListener[0];

    private boolean createdByServer = false;
    private byte[] responseForConnect = new byte[]{000};

    private final int dcTimeDueToInactivity;
    private volatile long lastCommunicationTime;

    private static final int defaultPingCD = 4000;
    private volatile int pingCD = defaultPingCD;
    private long lastPingSendTime;
    private volatile int lastPingSeq;
    private volatile int currentPing = 0;
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

        if (state.get() != SocketState.NOT_CONNECTED){
            return new ConnectionResponse(ConnectionResponse.Type.ALREADY_CONNECTED_OR_CONNECTING, new byte[0]);
        }
        state.set(SocketState.CONNECTING);
        ExecutorService e = Executors.newSingleThreadExecutor();
        connectingToAddress = address;
        connectingToPort = port;
        connectingResponse = null;
        seq.set(0);
        final int serverSequenceNumber = 0;
        lastInsertedSeq = serverSequenceNumber;

        byte[] fullData = buildConnectionRequest(seq.getAndIncrement(), serverSequenceNumber, data);
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

        boolean[] settings = getSettings(fullResponse[0]);
        boolean accepted = settings[CONNECTION_RESP_POS];
        byte[] userData = new byte[fullResponse.length - 5];
        System.arraycopy(fullResponse, 5, userData, 0, userData.length);
        ConnectionResponse connectionResponse = new ConnectionResponse(accepted ? ConnectionResponse.Type.ACCEPTED : ConnectionResponse.Type.NOT_ACCEPTED, userData);
        if (accepted) {
            state.set(SocketState.CONNECTED);
        }
        lastPingSendTime = System.currentTimeMillis();
        lastConnectedAddress = address;
        lastConnectedPort = port;
        connectingToAddress = null;
        return connectionResponse;
    }

    @Override
    public boolean send(byte[] data) {
        if (isConnected()) {
            int seq = this.seq.getAndIncrement();
            byte[] fullPackage = buildPackage(seq, true, false, data);
            saveRequest(seq, fullPackage);
            sendData(lastConnectedAddress, lastConnectedPort, fullPackage);
            return true;
        }
        return false;
    }

    @Override
    public boolean sendUnreliable(byte[] data) {
        if (isConnected()) {
            byte[] fullPackage = buildPackage(0, false, false, data);
            sendData(lastConnectedAddress, lastConnectedPort, fullPackage);
            return true;
        }
        return false;
    }

    public void start(boolean startUpdateThread, final int updateThreadSleepTimeMS){

        if (!createdByServer) {
            final Thread recevingThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.interrupted()) {

                        try {
                            DatagramPacket packet = receivingPacket;
                            socket.receive(packet);

                            InetAddress remoteAddress = packet.getAddress();
                            int remotePort = packet.getPort();
                            int dataLength = packet.getLength();
                            byte[] data = new byte[dataLength];
                            System.arraycopy(packet.getData(), 0, data, 0, dataLength);
                            if (remoteAddress.equals(lastConnectedAddress) && remotePort == lastConnectedPort && isConnected()) {
                                receive(remoteAddress, remotePort, data);
                            } else {
                                if (remoteAddress.equals(connectingToAddress) && remotePort == connectingToPort && (state.get() == SocketState.CONNECTING)) {
                                    boolean[] settings = getSettings(data[0]);
                                    if (settings[IS_CONNECTION_POS] && !settings[IS_REQUEST_POS]) {
                                        receive(remoteAddress, remotePort, data);
                                    }
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

            recevingThread.start();
        }

        if (startUpdateThread) {
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
                    dealWithDC();
                    return;
                }

                if (currTime - lastPingSendTime > pingCD){
                    sendPing();
                    lastPingSendTime = currTime;
                }
                synchronized (requestList) {
                    //TODO check on size to dc
                    Iterator<Object[]> savedRequests = requestList.iterator();
                    while (savedRequests.hasNext()) {
                        byte[] fullDataReq = (byte[]) savedRequests.next()[1];
                        markAsSent(fullDataReq);
                        sendData(lastConnectedAddress, lastConnectedPort, fullDataReq);
                    }
                }
                break;
        }


    }

    private void sendPing() {
        int seq = this.seq.getAndIncrement();
        byte[] fullPackage = buildPing(seq);
        lastPingSeq = seq;
        saveRequest(seq, fullPackage);
        sendData(lastConnectedAddress, lastConnectedPort, fullPackage);
    }

    @Override
    public void close() {
        if (!isConnected()){
            System.out.println("Closing, but not connected!");
            return;
        }
        sendData(lastConnectedAddress, lastConnectedPort, buildDC());
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

    void receive(InetAddress address, int port, byte[] fullPackage){
        this.lastCommunicationTime = System.currentTimeMillis();
        if (fullPackage.length < 5){
            log("Received message less than 5 bytes long!");
            return;
        }
        boolean[] settings = getSettings(fullPackage[0]);
        int seq = exctractInt(fullPackage, 1);

        if (settings[IS_CONNECTION_POS]){
            dealWithNewConnectionResponse(address, port, seq, settings, fullPackage);
            return;
        }

        if (settings[IS_REQUEST_POS]){
            if (settings[IS_RELIABLE_POS]){
                dealWithRequest(address, port, seq, settings[PING_POS], fullPackage);
            } else {
                dealWithUnreliableRequest(address, port, settings, fullPackage);
            }
        } else {
            dealWithResponse(seq, fullPackage);
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

    /* PACKET DEALS */

    private void dealWithNewConnectionResponse(InetAddress address, int port, int seq, boolean[] settings, byte[] fullPackage) {
        boolean isRequest = settings[IS_REQUEST_POS];
        if (isRequest){
            log("Attempt to login on MRUDP socket!");
            if (createdByServer){
                byte[] response = buildConnectionResponse(seq, true, responseForConnect);
                sendData(address, port, response);
            }
            return;
        }

        if (address.equals(connectingToAddress) && port == connectingToPort){
            if (state.get() == SocketState.CONNECTING) {
                this.connectingResponse = fullPackage;
            }
        }
    }

    private void dealWithRequest(InetAddress address, int port, int seq, boolean isPing, byte[] fullPackage) {
        byte[] responseData = buildResponse(seq);
        sendData(address, port, responseData);
        final int expectedSeq = this.lastInsertedSeq + 1;
        if (expectedSeq > seq){
        } else
        if (expectedSeq == seq){
            if (isPing){
                lastInsertedSeq++;
            } else {
                insert(expectedSeq, fullPackage, 5);
            }
            checkForQueuedDatas();
        } else {
            byte[] userData = new byte[fullPackage.length - 5];
            System.arraycopy(fullPackage, 5, userData, 0, userData.length);
            insertIntoWaitingDatas(seq, userData);
        }
    }

    private void dealWithUnreliableRequest(InetAddress address, int port, boolean[] settings, byte[] fullPackage) {
        if (settings[DC_POS]){
            dealWithDC();
            return;
        }
        byte[] data = new byte[fullPackage.length - 5];
        System.arraycopy(fullPackage, 5,  data, 0, data.length);
        receiveQueue.offer(data);
    }

    private void dealWithResponse(int seq, byte[] fullPackage) {
        int responseLength = fullPackage.length;
        if (responseLength != 5){
            log("Uexpected response length: " + responseLength);
        }
        boolean removed = removeRequest(seq);
        if (removed && lastPingSeq == seq){
            int newPing = (int) (System.currentTimeMillis() - lastPingSendTime);
            this.currentPing = newPing;
            MRUDPListener[] listeners = this.listeners;
            for (int i = 0; i < listeners.length; i++) {
                listeners[i].onPingUpdated(newPing);
            }
        }
    }

    private void dealWithDC(){
        receiveQueue.offer(new byte[0]);
    }





    /* DATA MANUPULATION */

    private void checkForQueuedDatas() {
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
    public int getPing() {
        return currentPing;
    }

    @Override
    public void setUserData(Object userData) {
        this.userData = userData;
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

    private static byte[] buildConnectionRequest(int mySeq, int serverSeq, byte[] userData){
        int userDataLength = userData.length;
        byte[] ret = new byte[userDataLength + 9];

        ret[0] = buildSettings(true,true, true, false);
        putInt(ret, mySeq, 1);
        putInt(ret, serverSeq, 5);
        System.arraycopy(userData, 0, ret, 9, userDataLength);
        return ret;
    }

    static byte[] buildConnectionResponse(int seq, boolean accepted, byte[] userData){
        int userDataLength = userData.length;
        byte[] ret = new byte[userDataLength + 5];

        ret[0] = buildSettings(true, true, false, false, accepted);
        putInt(ret, seq, 1);
        System.arraycopy(userData, 0, ret, 5, userDataLength);
        return ret;
    }

    private static byte[] buildPackage(int seq, boolean reliable, boolean alreadySent, byte[] userData){
        int userDataLength = userData.length;
        byte[] ret = new byte[userDataLength + 5];

        ret[0] = buildSettings(reliable, false, true, alreadySent);
        putInt(ret, seq, 1);
        System.arraycopy(userData, 0, ret, 5, userDataLength);
        return ret;
    }

    private static byte[] buildResponse(int seq){
        byte[] ret = new byte[5];

        ret[0] = buildSettings(true, false, false, false);
        putInt(ret, seq, 1);
        return ret;
    }

    private static byte buildSettings(boolean reliable, boolean isConnReq, boolean isRequest, boolean alreadySent){
        return buildSettings(reliable, isConnReq, isRequest, alreadySent, false);
    }

    private static byte buildSettings(boolean reliable, boolean isConnReq, boolean isRequest, boolean alreadySent, boolean connectionResponse){
        return (byte) (
                        (reliable ? 1<< IS_RELIABLE_POS : 0) +
                        (isConnReq ? 1<< IS_CONNECTION_POS : 0) +
                        (isRequest ? 1<<IS_REQUEST_POS : 0) +
                        (alreadySent ? 1<<ALREADY_SENT_POS : 0) +
                        (connectionResponse ? 1<< CONNECTION_RESP_POS : 0));
    }

    private static byte[] buildDC(){
        byte settings = (byte) ((1 << DC_POS) + (1<< IS_REQUEST_POS));
        return new byte[]{settings, 0, 0, 0, 0};
    }

    private static byte[] buildPing(int seq) {
        byte[] ret = new byte[5];
        ret[0] = (byte) ((1 << IS_RELIABLE_POS) + (1<< IS_REQUEST_POS) + (1 << PING_POS));
        putInt(ret, seq, 1);
        return ret;
    }

    private static void markAsSent(byte[] packageWithSettings){
        byte settings = packageWithSettings[0];
        if ((settings >> ALREADY_SENT_POS & 1) != 1){
            packageWithSettings[0] = (byte)(settings + (1<< ALREADY_SENT_POS));
        }
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

    private static void putInt(byte[] bytes, int value, int offset) {
        bytes[    offset] = (byte) (value >>> 24);
        bytes[1 + offset] = (byte) (value >>> 16);
        bytes[2 + offset] = (byte) (value >>> 8);
        bytes[3 + offset] = (byte)  value;
    }

    private static int exctractInt(byte[] bytes, int offset){
        return
                 bytes[offset] << 24             |
                (bytes[1 + offset] & 0xFF) << 16 |
                (bytes[2 + offset] & 0xFF) << 8  |
                (bytes[3 + offset] & 0xFF);
    }

    public static void printSettings(byte settingsByte){
        System.out.println(getSettingsAsString(settingsByte));
    }

    public static String getSettingsAsString(byte settingsBytes){
        boolean[] settings = getSettings(settingsBytes);
        StringBuilder builder = new StringBuilder("Settings: ");
        builder.append(settings[IS_RELIABLE_POS] ? "+" : "-").append("reliable ");
        builder.append(settings[IS_CONNECTION_POS] ? "+" : "-").append("isConnection ");
        builder.append(settings[IS_REQUEST_POS] ? "+" : "-").append("isRequest ");
        builder.append(settings[ALREADY_SENT_POS] ? "+" : "-").append("alreadySent ");
        builder.append(settings[CONNECTION_RESP_POS] ? "+" : "-").append("isConResp ");
        builder.append(settings[DC_POS] ? "+" : "-").append("DC ");
        builder.append(settings[PING_POS] ? "+" : "-").append("PING ");
        return builder.toString();
    }

}
