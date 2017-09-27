package ru.maklas.mrudp;

/**
 * Created by maklas on 12.09.2017.
 * Packet for response
 */

public interface ResponseWriter extends AddressContainer, Response {

    /**
     * Setting data that's going to be sent back
     */
    void setData(byte[] data);

    /**
     * SetData as String
     */
    void setData(String data);

    /**
     *  Type of response (OK/ERROR)
     *  @see SocketUtils
     */
    void setResponseCode(int responseCode);



}
