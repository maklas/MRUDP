package ru.maklas.mrudp.impl;


import ru.maklas.mrudp.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Created by maklas on 11.09.2017.
 * <p>Basic MRUDP implementation</p>
 * <p>Features:
 * <li>Connection filtering;</li>
 * <li>Multithreaded request processing and response handling;</li>
 * <li>Sends lost packet multiple times if it's lost;</li>
 * <li>Watching over packets using sequence number.</li></p>
 */
public class MRUDPSocketImplv1 implements Runnable, MRUDPSocket {

    private MrudpLogger logger;
    private final HashMap<Integer, RequestHandleWrap> requestHashMap;
    private final DatagramSocket socket;
    private final Thread receiverThread;
    private final Thread updateThread;
    private final ExecutorService service;
    private final DatagramPacket receivingPacket;
    private final DatagramPacket sendingPacket;
    private final Object sendingMonitor = new Object();
    private final Object processorMonitor = new Object();
    private static final int UPDATE_CD = 150;
    private int DISCARD_TIME_MS = 3000;


    private int seq = (int) (Math.random() * Long.MAX_VALUE);
    private SocketFilter filter;
    private RequestProcessor processor;

    public MRUDPSocketImplv1(DatagramSocket dSocket, int bufferSize, final boolean daemon) throws Exception {
        bufferSize+= 6;
        this.socket = dSocket;
        requestHashMap = new HashMap<Integer, RequestHandleWrap>();
        receivingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        sendingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        //service = Executors.newFixedThreadPool(numberOfThreads);
        final ThreadFactory threadFactory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(daemon);
                return thread;
            }
        };
        service = Executors.newCachedThreadPool(threadFactory);

        filter = new NoFilter();
        receiverThread = new Thread(this);
        receiverThread.setDaemon(true);
        receiverThread.start();
        updateThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!Thread.interrupted()) {
                        Thread.sleep(UPDATE_CD);
                        update();
                    }
                } catch (InterruptedException e){
                    log("UpdateThread interrupted. Quitting");
                }
            }
        });

        updateThread.setDaemon(true);
        updateThread.start();
    }

    public MRUDPSocketImplv1(DatagramSocket socket, int bufferSize) throws Exception {
        this(socket, bufferSize, true);
    }

    @Override
    public void setProcessor(RequestProcessor listener) {
        synchronized (processorMonitor) {
            this.processor = listener;
        }
    }

    @Override
    public void setLogger(MrudpLogger logger) {
        this.logger = logger;
    }

    @Override
    public void setFilter(SocketFilter filter) {
        this.filter = filter;
    }

    @Override
    public void killConnection() {
        receiverThread.interrupt();
        service.shutdown();
        socket.close();
        updateThread.interrupt();
    }

    @Override
    public void setResponseTimeout(int ms) {
        DISCARD_TIME_MS = ms;
    }

    private final ArrayList<Integer> requiresRemoval = new ArrayList<Integer>();
    private void update(){

        synchronized (requestHashMap) {
            Set<Integer> keys = requestHashMap.keySet();

            for (Integer key : keys) {
                RequestHandleWrap requestHandleWrap = requestHashMap.get(key);
                if (requestHandleWrap.msSinceCreation() > DISCARD_TIME_MS) {
                    requiresRemoval.add(key);
                }
            }
        }

        if (requiresRemoval.size() > 0){
            for (Integer i:requiresRemoval) {
                RequestHandleWrap triple;
                synchronized (requestHashMap) {
                    triple = requestHashMap.remove(i);
                }
                final RequestWriter request = triple.request;
                final ResponseHandler handler = triple.handler;
                final int timesToResend = handler.getTimesToResend();

                if (timesToResend > request.getTimesRequested()){
                    request.incTimesRequested();
                    resendRequest(request, handler);
                    log("Retry for: " + request + " #" + request.getTimesRequested());
                } else {
                    service.execute(new Runnable() {
                        @Override
                        public void run() {
                            handler.discard(request);
                        }
                    });

                }
            }
            requiresRemoval.clear();
        }

    }


    @Override
    public void run() {

        while (!Thread.interrupted()){

            try {
                socket.receive(receivingPacket);

                final byte[] fullData = receivingPacket.getData();
                final int fullLength = receivingPacket.getLength();
                final InetAddress address = receivingPacket.getAddress();
                final int port = receivingPacket.getPort();

                if (fullLength < 5){
                    log("Got message less than 5 byte long");
                    continue;
                }

                final int seq = fromByteArray(fullData);
                final byte setByte = fullData[4];
                final int msgCode = fullData[5];
                final boolean[] settings = new boolean[]{ //8 values from byte as boolean array
                        ((setByte >> 7 & 1) == 1),
                        ((setByte >> 6 & 1) == 1),
                        ((setByte >> 5 & 1) == 1),
                        ((setByte >> 4 & 1) == 1),
                        ((setByte >> 3 & 1) == 1),
                        ((setByte >> 2 & 1) == 1),
                        ((setByte >> 1 & 1) == 1),
                        ((setByte & 1)      == 1)};
                final boolean isRequest = settings[4];
                final boolean needsResponse = settings[5];
                final boolean alreadyBeenSent = settings[6];
                final boolean isSplit = settings[7];
                final byte[] data = getDataFrom(fullData,6, fullLength - 6);

                if (isRequest) {

                    //HANDLING REQUEST

                    final Request request = new RequestImpl(seq, address, port, data, needsResponse);
                    final ResponseWriter response = new ResponseWriterImpl(seq, address, port, SocketUtils.OK);

                    final RequestProcessor processor;
                    synchronized (processorMonitor){
                        processor = this.processor;
                    }
                    boolean acceptable = this.filter.filter(address, port, request) && (processor != null);
                    if (acceptable) {
                        service.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    processor.process(request, response, needsResponse);

                                    if (needsResponse) {
                                        sendResponse(response);
                                    }
                                } catch (Exception e) {
                                    log("exception while processing request" + e.getClass().getSimpleName());
                                }
                            }
                        });
                    } else {
                        log(request + " was filtered");
                        response.setResponseCode(filter.errorCodeToReturn());
                        try {
                            sendResponse(response);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                } else {

                    //HANDLING RESPONSE
                    RequestHandleWrap requestMemory;
                    synchronized (requestHashMap){
                        requestMemory = requestHashMap.remove(seq);
                    }

                    if (requestMemory != null) {
                        final Request oldRequest = requestMemory.request;
                        final ResponseHandler handler = requestMemory.handler;
                        final Response response = new ResponseImpl(seq, address, port, msgCode, data);
                        if (!SocketUtils.isAnErrorCode(msgCode))
                            service.execute(new Runnable() {
                                @Override
                                public void run() {
                                    handler.handle(oldRequest, response);
                                }
                            });
                        else
                            service.execute(new Runnable() {
                                @Override
                                public void run() {
                                    handler.handleError(oldRequest, response, msgCode);
                                }
                            });
                    } else {
                        log("No saved request for packet '" + new String(fullData) + "'");
                    }
                }


            } catch (SocketException se){
                //logger.log(Level.INFO, "Got bad msg. Quitting");
                break;
            } catch (IOException e){
                log("IOE in receiving thread");
            }

        }
    }

    private void incSeq(){
        seq ++;
    }

    private void sendResponse(Response response) {
        byte[] data = response.getData();
        if (data == null){
            data = new byte[0];
        }
        sendData(response.getAddress(), response.getPort(), data, response.getResponseCode(), response.getSequenceNumber(), false, false);
    }


    @Override
    public void sendRequest(InetAddress address, int port, String data, ResponseHandler handler) {
        sendRequest(address, port, data.getBytes(), handler);
    }

    @Override
    public void sendRequest(InetAddress address, int port, String data) {
        sendRequest(address, port, data, null);
    }

    @Override
    public void sendRequest(InetAddress address, int port, byte[] data, ResponseHandler handler){
        boolean handlerExists = handler != null;
        RequestWriter request = new RequestImpl(seq, address, port, data, handlerExists);

        if (handlerExists) {
            synchronized (requestHashMap) {
                requestHashMap.put(seq, new RequestHandleWrap(request, handler));
            }
        }
        int seq = this.seq;
        incSeq();
        sendData(address, port, data, SocketUtils.REQUEST_TYPE, seq, handlerExists, false);
    }

    @Override
    public void sendRequest(InetAddress address, int port, byte[] data) {
        sendRequest(address, port, data, null);
    }

    @Override
    public void resendRequest(final Request request, final ResponseHandler handler){
        RequestWriter newRequest = new RequestImpl(request.getSequenceNumber(), request.getAddress(), request.getPort(), request.getData(), request.responseRequired());
        newRequest.setTimesRequested(request.getTimesRequested());

        if (handler != null) {
            synchronized (requestHashMap) {
                requestHashMap.put(newRequest.getSequenceNumber(), new RequestHandleWrap(newRequest, handler));
            }
        }
        sendData(request.getAddress(), request.getPort(), request.getData(), SocketUtils.REQUEST_TYPE, request.getSequenceNumber(), handler != null, true);
    }

    private void sendData(InetAddress address, int port, byte[] data, int type, int seq, boolean needsResponse, boolean hasAlreadyBeenSent) {

        if (address == null) {
            throw new NullPointerException("Address wasn't specified");
        }

        synchronized (sendingMonitor) {
            sendingPacket.setAddress(address);
            sendingPacket.setPort(port);

            //Определяем. Это запрос или ответ
            boolean isRequest = isRequest(type);

            byte[] sendingData = createPacket(seq, isRequest, needsResponse, hasAlreadyBeenSent, false, type, data);

            sendingPacket.setData(sendingData);
            try {
                socket.send(sendingPacket);
            } catch (Exception e){
                log("IOException while trying to send via DatagramSocket");
            }
        }
    }

    private void log(String msg){
        if (logger != null){
            logger.log(msg);
        }
    }

    //*********//
    // Helpers //
    //*********//

    private static byte[] getDataFrom(byte[] bytes, int from, int length){
        byte[] out = new byte[length];
        System.arraycopy(bytes, from, out, 0, length);
        return out;
    }

    private static boolean isRequest(int type){
        if (SocketUtils.isRequestType(type)) {
            return true;
        } else if (SocketUtils.isResponseType(type)) {
            return false;
        } else throw new RuntimeException("Wrong code: " + type);
    }

    private static byte[] createPacket(int seqNumber, boolean isRequest, boolean needsResponse, boolean hasAlreadyBeenSent, boolean isSplit, int type, byte[] data){

        byte[] output = new byte[data.length + 6];

        //Putting sequence number in datagram
        output[0] = (byte) (seqNumber >>> 24);
        output[1] = (byte) (seqNumber >>> 16);
        output[2] = (byte) (seqNumber >>> 8);
        output[3] = (byte)  seqNumber;

        output[4] = (byte)((isRequest? 1<<3:0) + (needsResponse? 1<<2 : 0) + (hasAlreadyBeenSent? 1<<1 : 0) + (isSplit? 1 : 0)); //settings

        //msg code
        output[5] = (byte) type;

        //копируем данные в пакет
        System.arraycopy(data, 0, output, 6, output.length - 6);

        return output;
    }

    private static int fromByteArray(byte[] bytes) {
        return bytes[0] << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
    }

    private static class RequestHandleWrap{

        final long timeCreated;
        final RequestWriter request;
        final ResponseHandler handler;

        RequestHandleWrap(RequestWriter request, ResponseHandler handler) {
            this.timeCreated = System.currentTimeMillis();
            this.request = request;
            this.handler = handler;
        }

        long msSinceCreation(){
            return System.currentTimeMillis() - timeCreated;
        }
    }
}
