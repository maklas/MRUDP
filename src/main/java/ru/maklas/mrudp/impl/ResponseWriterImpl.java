package ru.maklas.mrudp.impl;


import ru.maklas.mrudp.ResponseWriter;
import ru.maklas.mrudp.SocketUtils;

import java.net.InetAddress;

/**
 * Created by maklas on 12.09.2017.
 * <p>Имплиментация пакета для ответа на запрос</p>
 */

public class ResponseWriterImpl extends ResponseImpl implements ResponseWriter {

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
}
