package ru.maklas.mrudp;

public interface MRUDP_NTP_Listener {

    /**
     * NTP resolving was successful.
     * @param socket socket which NTP was launched on
     * @param offset time difference between this device's time and connected device time
     * @param packetsSent how many packets were sent during NTP
     * @param packetsReceived how many packets came back. Can't be less than 2,
     *                        since 1 packet is not enough to determine offset.
     */
    void onSuccess(MRUDPSocket socket, long offset, int packetsSent, int packetsReceived);

    /**
     * <p>
     * Most common reasons:
     *               <li>less than 2 packets came back. Since 1 packet is not enough, NTP is failed</li>
     *               <li>NTP was already launched and is running at the moment</li>
     *               </p>
     *
     * @param socket socket which NTP resolving has failed.
     */
    void onFailure(MRUDPSocket socket);

}
