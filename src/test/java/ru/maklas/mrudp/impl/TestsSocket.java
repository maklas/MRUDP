package ru.maklas.mrudp.impl;

import com.sun.xml.internal.messaging.saaj.util.ByteInputStream;
import org.junit.Before;
import org.junit.Test;
import ru.maklas.mrudp.*;

import java.io.InputStream;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

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
        final int seq = 1092413412;
        ResponsePackage pack = new ResponsePackage(type, code, data, seq);
        response.put(pack);
        ResponsePackage responsePackage = response.get();
        assertEquals(responsePackage.getType(), type);
        assertEquals(responsePackage.getResponseCode(), code);
        assertEquals(responsePackage.getData(), data);
        assertEquals(responsePackage.getSequenceNumber(), seq);
    }

    @Test(timeout = 10000)
    public void testFutureLaterSuccess(){
        final int waitTime = 5000;
        final ResponsePackage.Type type = ResponsePackage.Type.Ok;
        final int code = 125323;
        final int seq = 2412343;
        byte[] data = new byte[]{8, 7, 6, 5, 4, 3, 2, 1};
        final ResponsePackage pack = new ResponsePackage(type, code, data, seq);

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
        assertEquals(responsePackage.getSequenceNumber(), seq);
    }

    @Test
    public void testSimpleRequest() throws Exception {
        final int port = 3000;
        MRUDPSocket serverSocket = new FixedBufferMRUDP(new JavaUDPSocket(port), bufferSize);
        MRUDPSocket clientSocket = new FixedBufferMRUDP(new JavaUDPSocket(), bufferSize);
        final FutureResponse future = new FutureResponse();
        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                boolean ok = request.getDataAsString().equals("1");
                future.put(new ResponsePackage(ok ? ResponsePackage.Type.Ok : ResponsePackage.Type.Error, true, 0, 0));
            }
        });
        clientSocket.sendRequest(localhost, port, "1".getBytes());

        long maxTimeToWait = 500;
        long before = System.currentTimeMillis();
        while (!future.isReady()){
            Thread.sleep(10);
            long difference = System.currentTimeMillis() - before;
            if (difference > maxTimeToWait){
                future.put(new ResponsePackage(ResponsePackage.Type.Error, true, -1, 0));
            }
        }

        ResponsePackage response = future.get();

        assertEquals(response.getType(), ResponsePackage.Type.Ok);

    }

    @Test
    public void testConnectionRecreation() throws Exception {
        final int port = 3001;
        for (int i = 0; i < 30; i++) {
            MRUDPSocket serverSocket = new FixedBufferMRUDP(new JavaUDPSocket(port), bufferSize);
            serverSocket.killConnection();
        }
    }

    @Test(timeout = 10 * 1000)
    public void testMassiveDiscard() throws Exception {
        final int times = 5000;
        final MRUDPSocket clientSocket = new FixedBufferMRUDP(new JavaUDPSocket(), bufferSize);
        final CountDownLatch latch = new CountDownLatch(times);
        for (int i = 0; i < times; i++) {
            clientSocket.sendRequest(localhost, 1000, "100".getBytes(), 1500, new ResponseHandlerAdapter(){
                @Override
                public void discard(boolean internal, Request request) {
                    latch.countDown();
                }
            });
        }

        latch.await();
    }


    @Test
    public void testResend() throws Exception {
        final int port = 3002;
        final int discardTime = 1000;
        final int firstRequestSleepTime = (int) (discardTime * 1.5f);

        MRUDPSocket serverSocket = new FixedBufferMRUDP(new JavaUDPSocket(port), bufferSize);
        MRUDPSocket clientSocket = new FixedBufferMRUDP(new JavaUDPSocket(), bufferSize, true, 5, 15, 4000);


        final AtomicInteger totalRequestsReceived = new AtomicInteger(0);
        serverSocket.setProcessor(new RequestProcessor() {
            private volatile boolean first = true;
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                totalRequestsReceived.incrementAndGet();
                if (first){
                    Thread.sleep(firstRequestSleepTime);
                    first = false;
                }
            }
        });

        long before = System.currentTimeMillis();
        clientSocket.sendRequestGetFuture(localhost, port, "123".getBytes(), discardTime, 1).get();
        long after = System.currentTimeMillis();
        System.out.println("expected: " + firstRequestSleepTime + ", got: " + (after - before));
        assertEquals(firstRequestSleepTime, after - before, 150);
        assertEquals(1, totalRequestsReceived.get());
    }


    @Test(timeout = 20 * 1000)
    public void testSimpleDataTransmission() throws Exception {
        final String testData = "{ \"name\":\"John\", \"age\":30, \"car\":null }";
        final int port = 3003;
        final int timesToSend = 15000;
        final CountDownLatch latch = new CountDownLatch(timesToSend);
        MRUDPSocket serverSocket = new FixedBufferMRUDP(new JavaUDPSocket(port), bufferSize);
        MRUDPSocket clientSocket = new FixedBufferMRUDP(new JavaUDPSocket(), bufferSize);

        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                String requestData = request.getDataAsString();
                if (testData.equals(requestData)){
                    latch.countDown();
                }
            }
        });

        for (int i = 0; i < timesToSend; i++) {
            clientSocket.sendRequest(localhost, port, testData.getBytes());
        }
        latch.await();
    }


    @Test(timeout = 20 * 1000)
    public void testSimpleDataTransmissionOnRouter() throws Exception {
        final String testData = "{ \"name\":\"John\", \"age\":30, \"car\":null }";
        final int port = 3003;
        final int timesToSend = 1000000;
        final CountDownLatch latch = new CountDownLatch(timesToSend);

        Router router = new RouterImpl();
        UDPSocket serverUDP = router.getNewConnection(port);
        InetAddress localhost = ((RouterImpl.RouterUDP) serverUDP).getAddress();
        UDPSocket clientUDP = router.getNewConnection();

        System.out.println("Server address: " + localhost);

        MRUDPSocket serverSocket = new FixedBufferMRUDP(serverUDP, bufferSize);
        MRUDPSocket clientSocket = new FixedBufferMRUDP(clientUDP, bufferSize);

        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                String requestData = request.getDataAsString();
                if (testData.equals(requestData)){
                    latch.countDown();
                }
            }
        });

        for (int i = 0; i < timesToSend; i++) {
            clientSocket.sendRequest(localhost, port, testData.getBytes());
        }
        latch.await();
    }


    @Test
    public void sizeTest() throws Exception {
        final int port = 3004;
        MRUDPSocket serverSocket = new FixedBufferMRUDP(new JavaUDPSocket(port), 4096);
        MRUDPSocket clientSocket = new FixedBufferMRUDP(new JavaUDPSocket(), 4096);

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
        clientSocket.sendRequestGetFuture(localhost, serverSocket.getLocalPort(), data, 1500, 0).get();
        assertEquals("wtf: " + size, success.get(), true);
    }

    @Test
    public void testSequentialDataTransmission() throws Exception {
        final int port = 3005;
        final int times = 100000;
        MRUDPSocket serverSocket = new FixedBufferMRUDP(new JavaUDPSocket(port), 4096);
        MRUDPSocket clientSocket = new FixedBufferMRUDP(new JavaUDPSocket(), 4096);

        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                response.setData(request.getData());
            }
        });


        long before = System.currentTimeMillis();

        for (int i = 0; i < times; i++) {
            ResponsePackage responsePackage = clientSocket.sendRequestGetFuture(localhost, port, Integer.toString(i).getBytes(), 500, 0).get();
            int parsed = Integer.parseInt(responsePackage.getDataAsString());
            if (parsed != i){
                throw new Exception("Expected " + i + ", but got " + parsed);
            }
        }

        long after = System.currentTimeMillis();
        System.out.println("Sequential data transmission for " + times + " packets took: " + (after - before) + " ms.");
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

            ResponseHandler nullRH = new ResponseHandlerAdapter(){
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

    @Test
    public void delayedRequest() throws Exception {
        final int port = 3006;
        MRUDPSocket serverSocket = new FixedBufferMRUDP(new JavaUDPSocket(port), bufferSize);
        MRUDPSocket clientSocket = new FixedBufferMRUDP(new JavaUDPSocket(), bufferSize);
        MrudpLogger logger = new DefaultMrudpLogger();
        serverSocket.setLogger(logger);
        clientSocket.setLogger(logger);


        DelayedRequestProcessor processor = new DelayedRequestProcessor(3000);
        serverSocket.setProcessor(processor);


        for (int i = 0; i < 10; i++) {
            clientSocket.sendRequest(localhost, port, ("Hello " + i).getBytes(), 2000, new ResponseHandlerAdapter(2){
                @Override
                public void handle(Request request, Response response) {

                }
            });
        }

        Thread.sleep(2300);

        for (int i = 0; i < 10; i++) {
            DelayedRequest take = processor.take();
            take.responseEmpty();
        }

        Thread.sleep(500);

    }

    @Test
    public void laggyRouterTest() throws Exception {
        final String testData = "{\"name\":\"John\", \"age\":30, \"car\":null}";
        final int port = 3003;
        final int timesToSend = 100000;
        final float loseChance = 50f;
        final int responseTimeOut = 100;
        final int retries = 50;


        final AtomicInteger success = new AtomicInteger(0);
        final AtomicInteger failure = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(timesToSend);

        Router router = new LaggyRouter(loseChance);
        System.err.println("If chance of losing a packet = " + loseChance + "% and total tries = " + (retries + 1) + ", then ");
        System.err.println("Success chance = " + ((LaggyRouter) router).getSuccessChance(retries + 1) + "%");
        UDPSocket serverUDP = router.getNewConnection(port);
        UDPSocket clientUDP = router.getNewConnection();
        InetAddress localhost = ((RouterImpl.RouterUDP) serverUDP).getAddress();

        System.out.println("Server address: " + localhost);

        MRUDPSocket serverSocket = new FixedBufferMRUDP(serverUDP, bufferSize, true, 150, 75, 30000);
        MRUDPSocket clientSocket = new FixedBufferMRUDP(clientUDP, bufferSize, true, 150, 75, 2000);

        final AtomicInteger counter = new AtomicInteger();
        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                counter.incrementAndGet();
            }
        });


        Thread.sleep(50);
        for (int i = 0; i < timesToSend; i++) {
            clientSocket.sendRequest(localhost, port, (testData + " " + i).getBytes(), responseTimeOut, new ResponseHandlerAdapter(retries){
                @Override
                public void handle(Request request, Response response) {
                    success.incrementAndGet();
                    latch.countDown();
                }

                @Override
                public void discard(boolean internal, Request request) {
                    failure.incrementAndGet();
                    latch.countDown();
                }
            });
        }

        latch.await();
        System.err.println("Handled: " + success.get() + ", Discarded: " + failure.get());
        System.err.println("Server counted " + counter.get() + " requests");
        assertEquals(timesToSend, success.get(), 5);
        assertEquals(timesToSend, counter.get(), 5);

    }

    @Test
    public void testNoResponse() throws Exception {
        final int port = 3007;
        MRUDPSocket serverSocket = new FixedBufferMRUDP(new JavaUDPSocket(port), bufferSize);
        MRUDPSocket clientSocket = new FixedBufferMRUDP(new JavaUDPSocket(), bufferSize);

        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                if (request.getAddress().equals(localhost)){
                    System.out.println("localhost detected. Denying in response");
                    response.setSendResponse(false);
                }
            }
        });

        ResponsePackage responsePackage = clientSocket.sendRequestGetFuture(localhost, port, new byte[]{0}, 1000, 1).get();
        assertEquals(ResponsePackage.Type.Discarded, responsePackage.getType());
        assertEquals(SocketUtils.DISCARDED, responsePackage.getResponseCode());
    }

    @Test(timeout = 6 * 1000)
    public void testInternalDiscard() throws Exception {
        final int port = 3008;
        MRUDPSocket clientSocket = new FixedBufferMRUDP(new JavaUDPSocket(), bufferSize);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean internalResult = new AtomicBoolean(false);

        ResponseHandlerAdapter handler = new ResponseHandlerAdapter(1) {
            @Override
            public void discard(boolean internal, Request request) {
                internalResult.set(internal);
                latch.countDown();
            }
        };
        handler.setKeepResending(false);
        clientSocket.sendRequest(localhost, port, "123".getBytes(), 2000, handler);

        latch.await();
        assertTrue(internalResult.get());

    }
    @Test(timeout = 6 * 1000)
    public void testInternalDiscardWithOk() throws Exception {
        final int port = 3008;
        MRUDPSocket clientSocket = new FixedBufferMRUDP(new JavaUDPSocket(), bufferSize);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean internalResult = new AtomicBoolean(false);

        MRUDPSocket serverSocket = new FixedBufferMRUDP(new JavaUDPSocket(port), bufferSize);
        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                response.setResponseCode(SocketUtils.OK);
            }
        });

        ResponseHandlerAdapter handler = new ResponseHandlerAdapter(1) {
            @Override
            public void discard(boolean internal, Request request) {
                internalResult.set(internal);
                latch.countDown();
            }
        };
        handler.setKeepResending(false);
        clientSocket.sendRequest(localhost, port, "123".getBytes(), 2000, handler);

        latch.await();
        assertTrue(internalResult.get());

    }

    @Test
    public void testSendFromStream() throws Exception {
        final String msg = "Hello";
        final byte[] byteMsg = msg.getBytes();
        ByteInputStream bis = new ByteInputStream(byteMsg, byteMsg.length);

        final int port = 3009;
        MRUDPSocket serverSocket = new FixedBufferMRUDP(new JavaUDPSocket(port), bufferSize);
        MRUDPSocket clientSocket = new FixedBufferMRUDP(new JavaUDPSocket(), bufferSize);
        
        clientSocket.setLogger(new DefaultMrudpLogger());

        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                assertArrayEquals(byteMsg, request.getData());
                response.setData(request.getData());
            }
        });


        ResponsePackage responsePackage = clientSocket.sendRequestGetFuture(localhost, port, bis, 1000, 0).get();
        assertArrayEquals(byteMsg, responsePackage.getData());
    }

    @Test
    public void testSendFromFile() throws Exception{

        final int port = 3010;
        MRUDPSocket serverSocket = new FixedBufferMRUDP(new JavaUDPSocket(port), bufferSize);
        MRUDPSocket clientSocket = new FixedBufferMRUDP(new JavaUDPSocket(), bufferSize);

        clientSocket.setLogger(new DefaultMrudpLogger());

        InputStream file = TestsSocket.class.getClassLoader().getResourceAsStream("test.txt");

        if (file == null){
            throw new RuntimeException();
        }

        file.mark(bufferSize);

        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                response.setData(request.getData());
            }
        });

        ResponsePackage responsePackage = clientSocket.sendRequestGetFuture(localhost, port, file, 1000, 0).get();
        file.reset();
        Scanner scanner = new Scanner(file);
        String s = scanner.nextLine().substring(0, bufferSize);
        assertEquals(s, responsePackage.getDataAsString());
        System.out.println(responsePackage);
    }
}