package ru.maklas.mrudp;

public interface MPingListener {



    /**
     * This method is going to be triggered on internal receiving Thread of the socket.
     * So do not synchronize, do not block, use Queues to update ping in your application.
     * Notifies that ping-response has just arrived from connected socket
     * @param socket socket that has it ping updated
     * @param newPing new ping value. Milliseconds in float.
     */
    void onPingUpdated(MRUDPSocket socket, float newPing);


}
