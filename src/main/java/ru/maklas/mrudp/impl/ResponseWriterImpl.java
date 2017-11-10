package ru.maklas.mrudp.impl;


import ru.maklas.mrudp.ResponseWriter;
import ru.maklas.mrudp.SocketUtils;

import java.net.InetAddress;
import java.util.Arrays;

/**
 * Created by maklas on 12.09.2017.
 * <p>Implementation for ResponseWriter</p>
 */

public class ResponseWriterImpl extends ResponseImpl implements ResponseWriter {

    private volatile boolean processing = true;

    ResponseWriterImpl(int sequenceNumber, InetAddress address, int port, int responseCode) {
        super(sequenceNumber, address, port, responseCode, new byte[0]);
    }

    @Override
    public void setData(byte[] data) {
        this.data = data;
    }

    @Override
    public void setResponseCode(int responseCode) {
        if (!SocketUtils.isResponseType(responseCode)){
            throw new RuntimeException("Wrong response code: "  + responseCode);
        }
        this.responseCode = responseCode;
    }

    @Override
    public void setData(String data) {
        if (data != null) {
            setData(data.getBytes());
        }
    }

    @Override
    public String toString() {
        return "ResponseWriterImpl{" +
                "address=" + address +
                ", port=" + port +
                ", seq=" + seq +
                ", data=" + Arrays.toString(data) +
                ", responseCode=" + responseCode +
                '}';
    }


    boolean isProcessing(){
        return processing;
    }

    void setProcessing(boolean processing){
        this.processing = processing;
    }
}
