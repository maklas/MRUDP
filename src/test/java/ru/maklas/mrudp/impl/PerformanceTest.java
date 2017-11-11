package ru.maklas.mrudp.impl;

import com.sun.xml.internal.messaging.saaj.util.ByteInputStream;
import com.sun.xml.internal.messaging.saaj.util.ByteOutputStream;
import org.junit.Before;
import org.junit.Test;
import ru.maklas.mrudp.*;

import java.net.InetAddress;
import java.util.Random;

import static org.junit.Assert.assertEquals;

public class PerformanceTest {


    private InetAddress localhost;
    private final int bufferSize = 512;
    private final Random random = new Random();

    @Before
    public void before() throws Exception {
        localhost = InetAddress.getLocalHost();
    }


    @Test
    public void testSendingSpeed() throws Exception {
        final int port = 3000;
        MRUDPSocket serverSocket = new MRUDPSocketImpl(new JavaUDPSocket(port), bufferSize);
        MRUDPSocket clientSocket = new MRUDPSocketImpl(new JavaUDPSocket(), bufferSize);

        final int packetSize = 100;
        byte[] data = new byte[packetSize];
        random.nextBytes(data);


        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                response.setData(request.getData());
            }
        });

        //preparation
        for (int i = 0; i < 500000; i++) {
            clientSocket.sendRequest(localhost, port, data);
        }


        System.out.println("Used memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/((float)(1024 * 1024)) + " MB");
        System.out.println("Testing for Packet size = " + packetSize + ":");
        Thread.sleep(10000);
        //sending without need for a response
        final int noResponsePackets = 50000;
        SimpleProfiler.start();
        for (int i = 0; i < noResponsePackets; i++) {
            clientSocket.sendRequest(localhost, port, data);
        }
        final double result1 = SimpleProfiler.getMS();
        System.out.println("noResponsePackets: (" + noResponsePackets + ") " + result1);

        Thread.sleep(5000);

        final int packetsWithResponse = 50000;
        SimpleProfiler.start();
        for (int i = 0; i < packetsWithResponse; i++) {
            clientSocket.sendRequest(localhost, port, data, 1000, new ResponseAdapter());
        }
        double result2 = SimpleProfiler.getMS();
        System.out.println("packetsWithResponse: (" + packetsWithResponse + ") " + result2);


        final int sequentialPackets = 50000;
        SimpleProfiler.start();
        for (int i = 0; i < sequentialPackets; i++) {
            clientSocket.sendRequestGetFuture(localhost, port, data, 1000, 0).get();
        }
        double result3 = SimpleProfiler.getMS();
        System.out.println("sequentialPackets: (" + sequentialPackets + ") " + result3);

        System.out.println("Used memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/((float)(1024 * 1024)) + " MB");
        Thread.sleep(10000);
        //Runtime.getRuntime().gc();
        Thread.sleep(1000);
        System.out.println("Used memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/((float)(1024 * 1024)) + " MB");

    }


    @Test
    public void memoryLeakTest() throws Exception {
        final int port = 3001;
        MRUDPSocket serverSocket = new MRUDPSocketImpl(new JavaUDPSocket(port), bufferSize);
        MRUDPSocket clientSocket = new MRUDPSocketImpl(new JavaUDPSocket(), bufferSize, true, MRUDPSocketImpl.DEFAULT_WORKERS, MRUDPSocketImpl.DEFAULT_UPDATE_CD, MRUDPSocketImpl.DELETE_RESPONSES_MS);

        serverSocket.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {

            }
        });
        serverSocket.setLogger(new MrudpLogger() {
            @Override
            public void log(String msg) {
                System.err.println(msg);
            }

            @Override
            public void log(Exception e) {
                e.printStackTrace();
            }
        });

        final int discardTime = 1000;
        final int packets = 5000000;
        final int packetSize = 512;
        byte[] data = new byte[packetSize];
        random.nextBytes(data);


        float maxMemory = 0;
        SimpleProfiler.start();
        for (int i = 0; i < packets; i++) {
            clientSocket.sendRequest(localhost, port, data);
            Thread.sleep(0);
            if (i % 120 == 0){
                float memoryMB = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/((float)1024 * 1024);
                if(maxMemory < memoryMB){
                    maxMemory = memoryMB;
                }
                float timePassed = (float) SimpleProfiler.getTime();
                String timeP =   SimpleProfiler.floatFormatted(timePassed, 2);
                String memoryM = SimpleProfiler.floatFormatted(memoryMB, 2);
                System.out.println("Time: " + timeP + ", Memory: " + memoryM + " MB");
            }
        }


        System.err.println("Max memory usage: " + SimpleProfiler.floatFormatted(maxMemory, 2));

        //5 mill packets; packet size = 512; discardTime = 1000; with ResponseAdapter; Max memory usage: 726.91 MB
        //5 mill packets; packet size = 512; discardTime = 1000; NO ResponseAdapter;   Max memory usage: 680.13 MB

    }

}
