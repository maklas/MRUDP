package ru.maklas.mrudp.impl;


import ru.maklas.mrudp.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
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
public class MRUDPSocketImpl implements Runnable, MRUDPSocket {

    public static final int DEFAULT_UPDATE_CD = 100;
    public static final int DEFAULT_WORKERS = 50;
    public static int DELETE_RESPONSES_MS = 10000;

    private MrudpLogger logger;
    private final HashMap<Integer, RequestHandleWrap> requestHashMap;
    private final UDPSocket socket;
    private final Thread receiverThread;
    private final Thread updateThread;
    private final ExecutorService service;
    private final DatagramPacket receivingPacket;
    private final DatagramPacket sendingPacket;
    private final Object sendingMonitor = new Object();
    private final Object processorMonitor = new Object();

    private int seq = (int) (Math.random() * Long.MAX_VALUE);
    private RequestProcessor processor;
    private ResponseMap responseMap; // ctrl+F the "--1" to se usage. Can be safely deleted

    public MRUDPSocketImpl(UDPSocket dSocket, int bufferSize, final boolean daemon, int workers, final int updateThreadCD, final int deleteResponseCD) throws Exception {
        bufferSize += 6;
        this.socket = dSocket;
        this.processor = new NullProcessor();
        requestHashMap = new HashMap<Integer, RequestHandleWrap>();
        responseMap = new ResponseMap(deleteResponseCD);
        receivingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        sendingPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
        final ThreadFactory threadFactory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(daemon);
                return thread;
            }
        };
        service = Executors.newFixedThreadPool(workers, threadFactory);

        receiverThread = new Thread(this);
        receiverThread.setDaemon(daemon);
        receiverThread.start();
        updateThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!Thread.interrupted()) {
                        Thread.sleep(updateThreadCD);
                        update();
                        responseMap.update(); /// --1
                    }
                } catch (InterruptedException e){
                    log("UpdateThread interrupted. Quitting");
                }
            }
        });

        updateThread.setDaemon(daemon);
        updateThread.start();
    }

    public MRUDPSocketImpl(UDPSocket socket, int bufferSize) throws Exception {
        this(socket, bufferSize, true, DEFAULT_WORKERS, DEFAULT_UPDATE_CD, DELETE_RESPONSES_MS);
    }

    @Override
    public void setProcessor(RequestProcessor processor) {
        synchronized (processorMonitor) {
            this.processor = processor;
        }
    }

    @Override
    public void setLogger(MrudpLogger logger) {
        this.logger = logger;
    }

    @Override
    public void killConnection() {
        receiverThread.interrupt();
        service.shutdown();
        socket.close();
        updateThread.interrupt();
    }

    private final ArrayList<Integer> requiresRemoval = new ArrayList<Integer>();
    private void update(){

        synchronized (requestHashMap){
            Set<Map.Entry<Integer, MRUDPSocketImpl.RequestHandleWrap>> entries = requestHashMap.entrySet();
            for (Map.Entry<Integer, MRUDPSocketImpl.RequestHandleWrap> entry : entries) {
                MRUDPSocketImpl.RequestHandleWrap wrap = entry.getValue();
                if (wrap.msSinceCreation() > wrap.request.getDiscardTime()){
                    requiresRemoval.add(entry.getKey());
                }
            }
        }

        if (requiresRemoval.size() > 0){
            for (Integer i:requiresRemoval) {
                RequestHandleWrap triple;
                synchronized (requestHashMap) {
                    triple = requestHashMap.remove(i);
                }
                if (triple == null){
                    continue;
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
                final boolean alreadyBeenSend = settings[6];
                // for later usage // final boolean isSplit = settings[7];
                final byte[] data = getDataFrom(fullData,6, fullLength - 6);

                if (isRequest) {

                    //HANDLING REQUEST

                    /// --1 (The whole if statement)
                    if (alreadyBeenSend && needsResponse) {
                        ResponseWriterImpl alreadyAnsweredResponse = responseMap.get(address, port, seq);
                        if (alreadyAnsweredResponse != null){
                            if (!alreadyAnsweredResponse.isProcessing())
                                sendResponse(alreadyAnsweredResponse);
                            return;
                        }
                    }

                    final Request request = new RequestImpl(seq, address, port, data, needsResponse, alreadyBeenSend, -1);
                    final ResponseWriterImpl response = new ResponseWriterImpl(seq, address, port, SocketUtils.OK);
                    if (needsResponse)
                        responseMap.put(response);  /// --1

                    final RequestProcessor processor;
                    synchronized (processorMonitor){
                        processor = this.processor;
                    }
                    service.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                boolean sendResponse = processor.process(request, response, needsResponse);

                                if (needsResponse && sendResponse){
                                    sendResponse(response);
                                }
                                response.setProcessing(false);

                            } catch (Exception e) {
                                log("Exception while processing request: " + e.getClass().getSimpleName() +
                                ".\n" + "Request=" + request.getDataAsString() + "\n" +
                                "Response=" + response);
                                log(e);
                            }
                        }
                    });

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
                        Runnable action;

                        if (!SocketUtils.isAnErrorCode(msgCode)){
                            action = new Runnable() {
                                @Override
                                public void run() {
                                    handler.handle(oldRequest, response);
                                }
                            };
                        } else {
                            action = new Runnable() {
                                @Override
                                public void run() {
                                    handler.handleError(oldRequest, response, msgCode);
                                }
                            };
                        }
                        service.execute(action);

                    } else {
                        log("No saved request for packet '" + new String(fullData) + "'");
                    }
                }


            } catch (SocketException se) {
                log("Got SocketException in receiving thread. Quitting...");
                break;
            } catch (IOException e){
                log("IOE in receiving thread");
            }

        }

        log("Receiving thread has stopped!");
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
    public void sendRequest(InetAddress address, int port, String data, int responseTimeOut, ResponseHandler handler) {
        sendRequest(address, port, data.getBytes(), responseTimeOut, handler);
    }

    @Override
    public void sendRequest(InetAddress address, int port, String data) {
        sendRequest(address, port, data, 0, null);
    }

    @Override
    public void sendRequest(InetAddress address, int port, byte[] data, int responseTimeOut, ResponseHandler handler){
        boolean handlerExists = handler != null;
        RequestWriter request = new RequestImpl(seq, address, port, data, handlerExists, false, responseTimeOut);

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
        sendRequest(address, port, data, 0, null);
    }

    @Override
    public FutureResponse sendRequestGetFuture(InetAddress address, int port, byte[] data, int discardTime, final int resendTries) {
        final FutureResponse ret = new FutureResponse();

        sendRequest(address, port, data, discardTime, new ResponseHandler() {
            @Override
            public void handle(Request request, Response response) {
                ret.put(new ResponsePackage(ResponsePackage.Type.Ok, response.getResponseCode(), response.getData(), response.getSequenceNumber()));
            }

            @Override
            public void handleError(Request request, Response response, int errorCode) {
                ret.put(new ResponsePackage(ResponsePackage.Type.Error, response.getResponseCode(), response.getData(), response.getSequenceNumber()));
            }

            @Override
            public void discard(Request request) {
                ret.put(new ResponsePackage(ResponsePackage.Type.Discarded, 0, request.getSequenceNumber()));
            }

            @Override
            public int getTimesToResend() {
                return resendTries;
            }
        });


        return ret;
    }

    @Override
    public void resendRequest(final Request request, final ResponseHandler handler){
        RequestWriter newRequest = new RequestImpl(request.getSequenceNumber(), request.getAddress(), request.getPort(), request.getData(), request.responseRequired(), true, request.getDiscardTime());
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

    private void log(Exception e){
        if (logger != null){
            logger.log(e);
        }
    }

    @Override
    public int getLocalPort() {
        return socket.getLocalPort();
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

    private class NullProcessor implements RequestProcessor {
        @Override
        public boolean process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
            response.setResponseCode(SocketUtils.INTERNAL_SERVER_ERROR);
            return true;
        }
    }

}
