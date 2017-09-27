package ru.maklas.mrudp;

/**
 * Created by maklas on 11.09.2017.
 * Request to be processed
 */
public interface Request extends AddressContainer{

    int getSequenceNumber();

    byte[] getData();

    String toString();

    String getDataAsString();

    int getTimesRequested();

    boolean responseRequired();

}
