package ru.maklas.mrudp;

import ru.maklas.mrudp.impl.LocalUDPSocket;
import ru.maklas.mrudp.impl.MRUDPSocketImpl;

import java.net.InetAddress;

/**
 * Created by amaklakov on 02.11.2017.
 */
public class Test {


    public static void main(String[] args) throws Exception {
        UDPSocket serverUDP = new LocalUDPSocket(200);
        UDPSocket clientUDP = new LocalUDPSocket(500);

        MRUDPSocket server = new MRUDPSocketImpl(serverUDP, 512);
        MRUDPSocket client = new MRUDPSocketImpl(clientUDP, 512);
        server.setLogger(new MrudpLogger() {
            @Override
            public void log(String msg) {
                System.err.println(msg);
            }
        });


        ((LocalUDPSocket)(serverUDP)).addSocket((LocalUDPSocket) clientUDP);
        ((LocalUDPSocket)(clientUDP)).addSocket((LocalUDPSocket) serverUDP);

        server.setProcessor(new RequestProcessor() {
            @Override
            public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
                System.out.println("Server got msg: " + request.getDataAsString());
            }
        });


        for (int i = 0; i < 1000; i++) {
            client.sendRequest(InetAddress.getLocalHost(), 200, "Packet #" + i);
        }


        Thread.sleep(111000);

    }

}
