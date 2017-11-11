package ru.maklas.mrudp.impl;

import ru.maklas.mrudp.Response;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ResponseMap {

    private final int deleteDelay;
    private final Object mutex;
    private final HashMap<ResponseIdentifier, AnsweredResponse> map;

    public ResponseMap(int deleteDelay) {
        this.map = new HashMap<ResponseIdentifier, AnsweredResponse>();
        this.deleteDelay = deleteDelay;
        mutex = this;
    }





    public void put(ResponseWriterImpl response){
        AnsweredResponse answeredResponse = new AnsweredResponse(response);
        synchronized (mutex){
            map.put(new ResponseIdentifier(response), answeredResponse);
        }
    }

    public ResponseWriterImpl get(InetAddress address, int port, int seq) {
        ResponseIdentifier id = new ResponseIdentifier(address, port, seq);
        AnsweredResponse r;
        synchronized (mutex){
            r = map.get(id);
        }
        return r.response;
    }


    private ArrayList<ResponseIdentifier> keysToDelete = new ArrayList<ResponseIdentifier>();
    public void cleanup(int deletionDelay){
        long currentTime = System.currentTimeMillis();
        synchronized (mutex){
            Set<Map.Entry<ResponseIdentifier, AnsweredResponse>> entries = map.entrySet();
            for (Map.Entry<ResponseIdentifier, AnsweredResponse> entry : entries) {
                if (currentTime - entry.getValue().creationTime > deletionDelay){
                    keysToDelete.add(entry.getKey());
                }
            }
        }

        if (keysToDelete.size() == 0){
            return;
        }

        synchronized (mutex){
            for (ResponseIdentifier responseIdentifier : keysToDelete) {
                map.remove(responseIdentifier);
            }
        }

        keysToDelete.clear();
    }

    public void update(){
        cleanup(deleteDelay);
    }

    /**
     * Удаляет все ответы которые пробыли в памяти дольше чем minTime
     */
    public void deleteOld(int minTime){
        cleanup(minTime);
    }


    private static class AnsweredResponse{

        private final long creationTime;
        private final ResponseWriterImpl response;

        public AnsweredResponse(ResponseWriterImpl response) {
            this.response = response;
            this.creationTime = System.currentTimeMillis();
        }
    }

    private static class ResponseIdentifier {

        private final byte[] address;
        private final int port;
        private final int seq;

        public ResponseIdentifier(InetAddress address, int port, int seq) {
            this.address = address.getAddress();
            this.port = port;
            this.seq = seq;
        }

        public ResponseIdentifier(Response response) {
            this.address = response.getAddress().getAddress();
            this.port = response.getPort();
            this.seq = response.getSequenceNumber();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ResponseIdentifier that = (ResponseIdentifier) o;

            if (port != that.port) return false;
            if (seq != that.seq) return false;
            return Arrays.equals(address, that.address);
        }

        @Override
        public int hashCode() {
            int result = 31 + port;
            for (byte element : address)
                result = 31 * result + element;
            result = 31 * result + seq;
            return result;
        }
    }
}
