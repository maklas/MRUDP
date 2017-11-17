package ru.maklas.mrudp.impl;

import ru.maklas.mrudp.Request;
import ru.maklas.mrudp.ResponseWriter;
import ru.maklas.mrudp.SocketUtils;

import java.net.InetAddress;

public class DelayedRequest implements Request {

    private final Request request;
    private final ResponseWriter response;
    private volatile boolean sendResponse;

    private boolean responseRequired;
    private volatile boolean inserted = false;


    public DelayedRequest(Request request, ResponseWriter response, boolean responseRequired) {
        this.request = request;
        this.response = response;
        this.responseRequired = responseRequired;
        this.sendResponse = responseRequired;

    }

    public synchronized void responseWithOk(byte[] data){
        responseWithCode(SocketUtils.OK, data);
    }

    public synchronized void responseWithCode(int code, byte[] data){
        if (inserted){
            throw new RuntimeException("Can't put data twice");
        }
        response.setData(data);
        response.setResponseCode(code);
        inserted = true;
        notify();
    }

    public synchronized void responseEmpty(){
        if (inserted){
            throw new RuntimeException("Can't put data twice");
        }
        inserted = true;
        notify();
    }

    synchronized void assertResponse(int timeOut) throws InterruptedException {
        while (!inserted){
            wait(timeOut);
            if (!inserted){
                throw new InterruptedException();
            }
        }
    }

    @Override
    public int getSequenceNumber() {
        return request.getSequenceNumber();
    }

    @Override
    public byte[] getData() {
        return request.getData();
    }

    @Override
    public String getDataAsString() {
        return request.getDataAsString();
    }

    @Override
    public int getTimesRequested() {
        return request.getTimesRequested();
    }

    @Override
    public boolean responseRequired() {
        return request.responseRequired();
    }

    @Override
    public int getDiscardTime() {
        return request.getDiscardTime();
    }

    @Override
    public boolean hasAlreadyBeenSend() {
        return request.hasAlreadyBeenSend();
    }

    @Override
    public InetAddress getAddress() {
        return request.getAddress();
    }

    @Override
    public int getPort() {
        return request.getPort();
    }

    public boolean isResponseRequired() {
        return responseRequired;
    }

    public boolean willSendResponse() {
        return sendResponse;
    }

    public void setSendResponse(boolean sendResponse) {
        this.sendResponse = sendResponse;
    }

    @Override
    public String toString() {
        return "DelayedRequest{" +
                "inserted=" + inserted +
                ", request=" + request +
                ", response=" + response +
                '}';
    }
}
