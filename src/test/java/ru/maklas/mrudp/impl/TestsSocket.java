package ru.maklas.mrudp.impl;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import ru.maklas.mrudp.*;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class TestsSocket {

    private InetAddress localhost;
    private final int bufferSize = 512;
    private final Random random = new Random();

    @Before
    public void before() throws Exception {
        localhost = InetAddress.getLocalHost();
    }

    @Test(expected = TimeoutException.class)
    public void testFutureBlock() throws Exception {
        final FutureResponse response = new FutureResponse();
        Callable<ResponsePackage> callable = new Callable<ResponsePackage>() {
            @Override
            public ResponsePackage call() throws Exception {
                return response.get();
            }
        };

        ExecutorService e = Executors.newSingleThreadExecutor();
        Future<ResponsePackage> submit = e.submit(callable);
        submit.get(5, TimeUnit.SECONDS);


    }

    @Test
    public void testFutureSuccessEarly(){
        ResponsePackage.Type type = ResponsePackage.Type.Discarded;
        int code = 1023123;
        byte[] data = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        final FutureResponse response = new FutureResponse();
        ResponsePackage pack = new ResponsePackage(type, code, data);
        response.put(pack);
        ResponsePackage responsePackage = response.get();
        assertEquals(responsePackage.getType(), type);
        assertEquals(responsePackage.getResponseCode(), code);
        assertEquals(responsePackage.getData(), data);
    }

    @Test(timeout = 10000)
    public void testFutureLaterSuccess(){
        final int waitTime = 5000;
        final ResponsePackage.Type type = ResponsePackage.Type.Ok;
        final int code = 125323;
        byte[] data = new byte[]{8, 7, 6, 5, 4, 3, 2, 1};
        final ResponsePackage pack = new ResponsePackage(type, code, data);

        final FutureResponse response = new FutureResponse();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(waitTime);
                    response.put(pack);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        thread.start();
        long before = System.currentTimeMillis();
        ResponsePackage responsePackage = response.get();
        long diff = System.currentTimeMillis() - before;
        assertEquals(waitTime, diff, 100);
        assertEquals(responsePackage.getType(), type);
        assertEquals(responsePackage.getResponseCode(), code);
        assertEquals(responsePackage.getData(), data);
    }

    @Test
    public void testSimpleRequest() throws Exception {
        final int port = 3000;
        MRUDPSocket serverSocket = new MRUDPSocketImpl(new JavaUDPSocket(port), bufferSize);
        MRUDPSocket clientSocket = new MRUDPSocketImpl(new JavaUDPSocket(), bufferSize);
        final FutureResponse future = new FutureResponse();
        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                boolean ok = request.getDataAsString().equals("1");
                future.put(new ResponsePackage(ok ? ResponsePackage.Type.Ok : ResponsePackage.Type.Error, 0));
            }
        });
        clientSocket.sendRequest(localhost, port, "1");

        long maxTimeToWait = 500;
        long before = System.currentTimeMillis();
        while (!future.isReady()){
            Thread.sleep(10);
            long difference = System.currentTimeMillis() - before;
            if (difference > maxTimeToWait){
                future.put(new ResponsePackage(ResponsePackage.Type.Error, -1));
            }
        }

        ResponsePackage response = future.get();

        assertEquals(response.getType(), ResponsePackage.Type.Ok);

    }

    @Test
    public void testConnectionRecreation() throws Exception {
        final int port = 3001;
        for (int i = 0; i < 30; i++) {
            MRUDPSocket serverSocket = new MRUDPSocketImpl(new JavaUDPSocket(port), bufferSize);
            serverSocket.killConnection();
        }
    }

    @Test
    public void testMassiveDiscard() throws Exception {
        int times = 5000;
        MRUDPSocket clientSocket = new MRUDPSocketImpl(new JavaUDPSocket(), bufferSize);
        final AtomicInteger integer = new AtomicInteger(0);
        for (int i = 0; i < times; i++) {
            clientSocket.sendRequest(localhost, 1000, "100", 1500, new ResponseAdapter(){
                @Override
                public void discard(Request request) {
                    integer.incrementAndGet();
                }
            });
        }

        Thread.sleep(5000);
        assertEquals(integer.get(), times);
    }


    @Test
    public void testResend() throws Exception {
        final int port = 3002;
        final int discardTime = 1000;

        MRUDPSocket serverSocket = new MRUDPSocketImpl(new JavaUDPSocket(port), bufferSize);
        MRUDPSocket clientSocket = new MRUDPSocketImpl(new JavaUDPSocket(), bufferSize);




        final AtomicInteger totalRequestsReceived = new AtomicInteger(0);
        serverSocket.setProcessor(new RequestProcessor() {
            private volatile boolean first = true;
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                totalRequestsReceived.incrementAndGet();
                if (first){
                    Thread.sleep((int)(discardTime * 1.5f));
                }
                if (first){
                    first = false;
                }

            }
        });

        long before = System.currentTimeMillis();
        clientSocket.sendRequestGetFuture(localhost, port, discardTime, 1, "123".getBytes()).get();
        long after = System.currentTimeMillis();
        System.out.println("Time took: " + (after - before));
        assertEquals(1, totalRequestsReceived.get());
    }


    @Test
    public void testSimpleDataTransmission() throws Exception {
        final String testData = "{ \"name\":\"John\", \"age\":30, \"car\":null }";
        final int port = 3003;
        final int timesToSend = 20000;
        final AtomicInteger successCounter = new AtomicInteger(0);
        MRUDPSocket serverSocket = new MRUDPSocketImpl(new JavaUDPSocket(port), bufferSize);
        MRUDPSocket clientSocket = new MRUDPSocketImpl(new JavaUDPSocket(), bufferSize);

        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                String requestData = request.getDataAsString();
                if (testData.equals(requestData)){
                    successCounter.incrementAndGet();
                }
            }
        });

        for (int i = 0; i < timesToSend; i++) {
            clientSocket.sendRequest(localhost, port, testData.getBytes());
        }

        Thread.sleep(3000);
        assertEquals(successCounter.get(), timesToSend);
    }


    @Test
    public void sizeTest() throws Exception {
        final int port = 3004;
        MRUDPSocket serverSocket = new MRUDPSocketImpl(new JavaUDPSocket(port), 4096);
        MRUDPSocket clientSocket = new MRUDPSocketImpl(new JavaUDPSocket(), 4096);

        int[] sizes = new int[]{1, 512, 1024, 4096};

        for (int size : sizes) {
            sizeTest(serverSocket, clientSocket, size);
        }
    }

    private void sizeTest(MRUDPSocket serverSocket, MRUDPSocket clientSocket, int size) throws Exception{
        final byte[] data = new byte[size];
        random.nextBytes(data);
        final AtomicBoolean success = new AtomicBoolean(false);
        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                boolean equals = Arrays.equals(data, request.getData());
                assertEquals("Not equals: \n" + Arrays.toString(data) + " \nand \n" + Arrays.toString(request.getData()), true, equals);
                success.set(true);
            }
        });
        clientSocket.sendRequestGetFuture(localhost, serverSocket.getLocalPort(), 1500, 0, data).get();
        assertEquals("wtf: " + size, success.get(), true);
    }

    @Test
    public void testSequentialDataTransmission() throws Exception {
        final int port = 3005;
        final int times = 10000;
        MRUDPSocket serverSocket = new MRUDPSocketImpl(new JavaUDPSocket(port), 4096);
        MRUDPSocket clientSocket = new MRUDPSocketImpl(new JavaUDPSocket(), 4096);

        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                response.setData(request.getData());
            }
        });


        long before = System.currentTimeMillis();

        for (int i = 0; i < times; i++) {
            ResponsePackage responsePackage = clientSocket.sendRequestGetFuture(localhost, port, Integer.toString(i)).get();
            int parsed = Integer.parseInt(responsePackage.getDataAsString());
            if (parsed != i){
                throw new Exception("Expected " + i + ", but got " + parsed);
            }
        }

        long after = System.currentTimeMillis();
        System.out.println("Time took: " + (after - before));
    }

    @Test
    @Ignore
    public void ddos() throws Exception {
        final int port = 3005;
        final int times = 20;
        final int threads = 100;
        MRUDPSocket serverSocket = new MRUDPSocketImpl(new JavaUDPSocket(port), bufferSize);

        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
            }
        });

        ExecutorService executorService = Executors.newFixedThreadPool(threads);
        Sender[] senders = new Sender[threads];
        for (int i = 0; i < threads; i++) {
            senders[i] = new Sender(port, new MRUDPSocketImpl(new JavaUDPSocket(), 50), bufferSize, times);
        }

        List<Future<Integer>> futures = executorService.invokeAll(Arrays.asList(senders));
        for (Future<Integer> future : futures) {
            Integer integer = future.get();
            assertEquals(integer, Integer.valueOf(times));
        }

    }


    private class Sender implements Callable<Integer> {

        final int port;
        final MRUDPSocket dosSocket;
        private int bufferSize;
        final int times;
        private final Random random = new Random();

        public Sender(int port, MRUDPSocket dosSocket, int bufferSize, int times) {
            this.port = port;
            this.dosSocket = dosSocket;
            this.bufferSize = bufferSize;
            this.times = times;
        }

        @Override
        public Integer call() throws Exception {

            final AtomicInteger counter = new AtomicInteger();

            ResponseHandler nullRH = new ResponseAdapter(){
                @Override
                public void handle(Request request, Response response) {
                    counter.incrementAndGet();
                }
            };

            for (int i = 0; i < times; i++) {
                byte[] data = new byte[bufferSize];
                random.nextBytes(data);
                dosSocket.sendRequest(localhost, port, data, 10000, nullRH);
            }

            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (counter.get() != times){
                System.err.println("DDOS FUCKED THE SERVER! Expected " + times + ", but only " + counter.get() + " made it");
            }

            return counter.get();
        }

    }
}