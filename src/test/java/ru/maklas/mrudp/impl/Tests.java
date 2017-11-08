package ru.maklas.mrudp.impl;

import org.junit.Before;
import org.junit.Test;
import ru.maklas.mrudp.MRUDPSocket;
import ru.maklas.mrudp.Request;
import ru.maklas.mrudp.RequestProcessor;
import ru.maklas.mrudp.ResponseWriter;

import java.net.InetAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class Tests {

    private InetAddress localhost;
    private final int bufferSize = 512;

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
        final int resendTimes = 1;

        MRUDPSocket serverSocket = new MRUDPSocketImpl(new JavaUDPSocket(port), bufferSize);
        MRUDPSocket clientSocket = new MRUDPSocketImpl(new JavaUDPSocket(), bufferSize);
        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                boolean reject = request.getTimesRequested() < resendTimes;
                System.out.println(request.getTimesRequested());
                if (reject){
                    Thread.sleep(discardTime * 100000);
                }
            }
        });


        long before = System.currentTimeMillis();
        ResponsePackage responsePackage = clientSocket.sendRequestGetFuture(localhost, port, discardTime, resendTimes, "123".getBytes()).get();
        System.out.println(responsePackage.getType());
        long after = System.currentTimeMillis();
        long diff = after - before;
        System.out.println(diff);
        assertEquals(discardTime * resendTimes, diff, 100);

    }





}