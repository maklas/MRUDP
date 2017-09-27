package ru.maklas.mrudp;

/**
 * Created by maklas on 11.09.2017.
 * Response from server
 */
public interface Response extends AddressContainer {

    /**
     * @return Data inside Response
     */
    byte[] getData();

    /**
     * @return Data as string
     */
    String getDataAsString();

    /**
     * @return Code of response (OK/ERROR)
     * @see SocketUtils
     */
    int getResponseCode();

    /**
     * @return Packet sequence number
     */
    int getSequenceNumber();

    String toString();

}
