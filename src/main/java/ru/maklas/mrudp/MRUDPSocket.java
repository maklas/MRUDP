package ru.maklas.mrudp;

import java.net.InetAddress;

/**
 * Represents a socket that can be connected to remote socket. Uses UDP. Reliable, Sequential.
 */
public interface MRUDPSocket {

    /**
     * Current state of the socket.
     */
    enum SocketState {NOT_CONNECTED, CONNECTING, CONNECTED}

    String DEFAULT_DC_MSG = "DISCONNECT";
    String DEFAULT_CLOSE_MSG = "CLOSE";
    String SERVER_CLOSE_MSG = "SERVER_CLOSE";
    String CONNECTION_TIME_OUT = "CONNECTION TIME OUT";

    // ACTIONS

    /**
     * <p>Tries to establish connection to the server with custom user request.
     * Blocks thread for specified time until connected or not answered</p>
     * <p></p>
     * @param timeout Blocking time in milliseconds
     * @param address Server address
     * @param port Server port
     * @param data Custom data (request data). Can be anything. For example login + password for validation. Maximum size is bufferSize - 9
     * @return Connection response containing connection {@link ConnectionResponse.Type result} and response data as byte[]
     * @throws RuntimeException If this socket was created by server. Changing sub-server socket connections is forbidden
     */
    ConnectionResponse connect(int timeout, InetAddress address, int port, byte[] data);

    /**
     * <p>Sends data to connected socket if current state == CONNECTED</p>
     * <p>This method provides reliable, and ordered byte[] sending. Packet will be delivered in the order of sending.
     * So recommended to use this method from the same thread each time.
     * Packets will be resent over and over until socket on the other end received it or disconnection occurs
     * </p>
     *
     *
     * @param data Data to be send. Max size == bufferSize - 5. Can be changed after this method is finished, since creates copy
     * @return <b>False</b> if socket is not connected
     */
    boolean send(byte[] data);

    /**
     * <p>Sends data to connected socket in a batch if current state == CONNECTED</p>
     * <p>This method provides reliable, and ordered byte[] sending. Packet will be delivered in the order of sending.
     * So recommended to use this method from the same thread each time.
     * Packets will be resent over and over until socket on the other end received it or disconnection occurs
     * </p>
     * @param batch Data to be sent
     * @return <b>False</b> if socket is not connected
     */
    boolean sendBatch(MRUDPBatch batch);

    /**
     * <p>Sends data to connected socket in a batch if current state == CONNECTED</p>
     * <p>This method provides reliable, and ordered byte[] sending. Packet will be delivered in the order of sending.
     * So recommended to use this method from the same thread each time.
     * Packets will be resent over and over until socket on the other end received it or disconnection occurs
     * </p>
     * <p>
     *     Main difference with {@link #sendBatch(MRUDPBatch)} is that if batch won't fit, it will be devided in small batches.
     *     Make sure that all byte[] lengths are less than bufferSize - 8;
     * </p>
     * @param batch Data to be sent
     * @return <b>False</b> if socket is not connected
     */
    boolean sendBigBatch(MRUDPBatch batch);

    /**
     * <p>Sends data to connected socket in a batch if current state == CONNECTED</p>
     * <p>This method provides unreliable byte[] sending. Packets inside of batch will be delivered in the order of sending.
     * </p>
     * @param batch Data to be sent
     * @return <b>False</b> if socket is not connected
     */
    boolean sendUnreliableBatch(MRUDPBatch batch);

    /**
     * <p>Sends data to connected socket if current state == CONNECTED</p>
     * <p>This method provides reliable, and ordered byte[] sending. Packet will be delivered in the order of sending.
     * So recommended to use this method from the same thread each time.
     * Packets will be resent over and over until socket on the other end received it or disconnection occurs
     * </p>
     *
     * <p>Difference between {@link #send(byte[])} is that the first 5 bytes are going to be overwritten
     * and that this data won't be copied, so <b>please do not change byte[] after sending!</b>
     * This method is used to avoid unnecessary byte[] allocation and copying.
     * </p>
     *
     * @param dataWithOffset5 Data to be send with offset 5. Meaning that first 5 bytes must not contain data.
     *                        They are going to be overwritten. After sending this byte array must not be changed.
     * @return <b>False</b> if socket is not connected
     */
    boolean sendOff5(byte[] dataWithOffset5);

    /**
     * <p>Sends data to connected socket if current state == CONNECTED</p>
     * <p>This method sends data as fast as possible to a socket on the other end and <b>does not provide reliability nor ordering</b>.
     * this method uses plain UDP, so packet might not be delivered, or delivered not in the order of sending.
     * </p>
     * @param data Data to be send. Max size == bufferSize - 5. Data will be copied, so can be changed after method returns
     * @return <b>False</b> if socket is not connected.
     */
    boolean sendUnreliable(byte[] data);

    /**
     * <p>Sends data to connected socket if current state == CONNECTED</p>
     * <p>This method sends data as fast as possible to a socket on the other end and <b>does not provide reliability nor ordering</b>.
     * this method uses plain UDP, so packet might not be delivered, or delivered not in the order of sending.
     * </p>
     *
     * <p>Difference between {@link #sendUnreliable(byte[])} is that the first 5 bytes are going to be overwritten
     * and that this data won't be copied, so <b>please do not change byte[] after sending!</b>
     * This method is used to avoid unnecessary byte[] allocation and copying.
     * </p>
     *
     * @param data Data to be send with offset 5. Meaning that first 5 bytes must not contain data.
     *             They are going to be overwritten. After sending this byte array must not be changed.
     * @return <b>False</b> if socket is not connected.
     */
    boolean sendUnreliableOff5(byte[] data);

    /**
     * Starts update thread (and receiving thread in case this socket wasn't created by a Server).
     * Param 'updateThreadSleepTimeMS' is used to setup how often not delivered packets should be resend.
     * This parameter can't be changed after starting. Also, starting can't be launched twice.
     * @param updateThreadSleepTimeMS Sleep time of update thread between packets resending
     */
    void start(final int updateThreadSleepTimeMS);

    /**
     * <p>Receives any pending data onto the {@link SocketProcessor}.
     * This process <b>must not be used by different threads at the same time</b> as it will throw an Exception.
     * Works better if you implement this method by one of your classes and pass the same instance every time, rather than instantiating
     * </p>
     * <p>If socket on the other end disconnects, this method might receive this Disconnection event and will trigger listeners.
     * </p>
     *
     * @param processor Instance that is going to receive all pending packets in the order which they were sent.
     *                  Unreliable packets will also be consumed by this SocketProcessor. Receiving of the packets
     *                  can be temporarily stopped with {@link SocketIterator#stop()}
     * @return <b>True</b> if receiving was stopped by user with {@link SocketIterator#stop()}.
     */
    boolean receive(SocketProcessor processor);

    void addDCListener(MDisconnectionListener listener);

    void addPingListener(MPingListener listener);

    void removeDCListener(MDisconnectionListener listener);

    void removePingListener(MPingListener listener);

    void removeAllListeners();

    /**
     * When socket's dc check is paused, socket can't be disconnected due to no response. Use {@link #resumeDCcheck()} to re-enable this function
     */
    void pauseDCcheck();

    /**
     * When socket's dc check is paused, socket can't be disconnected due to no response
     */
    boolean dcCheckIsPaused();

    /**
     * When socket's dc check is paused, socket can't be disconnected due to no response
     */
    void resumeDCcheck();

    /**
     * Sets how often ping should be updated in milliseconds. When ping updates, listeners will be notified
     * from internal thread. So please, synchronize on queues
     * @param ms Update sleep time in milliseconds.
     */
    void setPingUpdateTime(int ms);

    /**
     * <p>
     * Launches NTP (Network Time Protocol) which tries to find out what time is it on connected device.
     * After success, {@link #timeIsKnown()} will return true and you can find out current time on other
     * PC by calling {@link #getTimeOnConnectedDevice()}. By calling {@link #getTimeOffset()}
     * you can know difference in values between your System.currentTimeMillis() and connected socket's
     * System.currentTimeMillis().
     * </p>
     * <p>
     *     For successful NTP resolving timeMs divided by requests should be <b>></b> 100
     *     And amount of requests to be at least 7-10. Since NTP packets can be lost and won't be recovered
     *     Although, you can figure out the best formula for yourself.
     * </p>
     * <p>
     *     Note.
     *     1. If there will be only one successful request,
     *     {@link MRUDP_NTP_Listener#onFailure(MRUDPSocket)} will be called
     *     2. Creates new that on which Listener's methods will be called.
     *     please synchronize!
     * </p>
     *
     * @param timeMS for how long should NTP try to determine time difference.
     * @param requests how many requests to send during NTP time.
     *                 So <b>time between requests = timeMS / requests</b>
     * @param listener listener which get notified when NTP is finished. Will be called from a new thread.
     */
    void launchNTP(int timeMS, int requests, MRUDP_NTP_Listener listener);

    /**
     * @return whther or not NTP was launched and finished successfully on current connection or not
     */
    boolean timeIsKnown();

    /**
     * <b> your System.currentTimeMillis() + socket.getTimeOffset == connected device's System.currentTimeMillis()</b>
     * Returns 0 if NTP was not launched or if was unsuccessful. Check it by calling {@link #timeIsKnown()}
     * @return Device time offset between your Socket and connected Socket.
     */
    long getTimeOffset();

    /**
     * @return Current time on connected device. Return your time if NTP was not launched or if was unsuccessful.
     * Check it by calling {@link #timeIsKnown()}
     */
    long getTimeOnConnectedDevice();

    /**
     * Sets userData for this socket.
     * Useful when you use single instance of SocketProcessor for processing multiple sockets.
     * Use {@link #getUserData()} to retrieve userData
     * @param userData Any object
     * @return userData object that was replaced (null for the 1st time)
     */
    Object setUserData(Object userData);

    /**
     * @return sequence number of the last packet which was sent from this socket.
     * Shows how many reliable datasending (including pings) was performed since this socket was connected.
     */
    int getCurrentSeq();

    /**
     * <p>Sends disconnection event to the socket on the other end, changes state do 'NOT_CONNECTED' and triggers listeners</p>
     * <p><b>Important:</b> Socket can be used again to connect to server (unless this socket was created by server).
     * Doesn't close UDP socket. Use {@link #close() close} method if you want to dispose of this socket and/or close UDP port
     * </p>
     * @return <b>True</b> if socket was connected before calling this method and that this method performed all of the described actions.
     */
    boolean disconnect();

    /**
     * <p>Sends disconnection event to the socket on the other end, changes state do 'NOT_CONNECTED' and triggers listeners</p>
     * <p><b>Important:</b> Socket can be used again to connect to server (unless this socket was created by server).
     * Doesn't close UDP socket. Use {@link #close() close} method if you want to dispose of this socket and/or close UDP port
     * </p>
     * @return <b>True</b> if socket was connected before calling this method and that this method performed all of the described actions.
     */
    boolean disconnect(String msg);

    /**
     * Performs disconnect if at that time socket was connected, stops any internal threads associated with this socket and then closes the UDPSocket. After this method,
     * socket becomes unusable and you have to create new instance to establish connection.
     */
    void close();

    /**
     * Performs disconnect if at that time socket was connected, stops any internal threads associated with this socket and then closes the UDPSocket. After this method,
     * socket becomes unusable and you have to create new instance to establish connection.
     */
    void close(String msg);

    // GETTERS

    /**
     * @return Current state of the socket
     */
    SocketState getState();

    /**
     * Don't call this method a lot, since it's synchronized very tightly with internals. Might block for a period of time/
     * @return how many packets are in resend queue, waiting to be received by connectedSocket.
     */
    int getSendBufferSize();

    /**
     * @return <b>True</b> if this socket is connected to any remote Socket. Same as getState() == CONNECTED
     */
    boolean isConnected();

    /**
     * @return InetAddress of the connected (or prevoiusly connected) remote socket. Value might be null if socket was never connected.
     */
    InetAddress getRemoteAddress();

    /**
     * @return port this socket is connected (was connected) to. Value might be -1 if socket was never connected.
     */
    int getRemotePort();

    /**
     * @return Local port of UDP socket
     */
    int getLocalPort();

    /**
     * @return <b>True</b> if currently {@link #receive(SocketProcessor)} method is running.
     */
    boolean isProcessing();

    /**
     * @return Last ping after ping update
     */
    float getPing();

    /**
     * @return UserData of this socket.
     *          Useful when you use single instance of SocketProcessor for processing multiple sockets.
     */
    <T> T getUserData();

    /**
     * @return Whether this socket is closed and can't be reused
     */
    boolean isClosed();

}
