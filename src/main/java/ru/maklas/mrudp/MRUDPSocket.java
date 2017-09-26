package ru.maklas.mrudp;

import java.net.InetAddress;

/**
 * Created by maklas on 11.09.2017.
 * <p>Сокет через который возможна отправка пакетов на другой MRUDP сокет.
 * Используется UDP протокол для отправки сех данных.
 * Всегда закрывайте сокет с помощью killConnection() при завершении работы с сокетом</p>
 * <p>Поддерживается:
 * <li>Фильтрация пакетов;</li>
 * <li>Многопоточная обработка запросов и ответов;</li>
 * <li>Многоразовый посыл пакетов в случае утраты;</li>
 * <li>Отслеживание запросов при помощи sequenceNumber.</li></p>
 */
public interface MRUDPSocket {

    void setProcessor(RequestProcessor listener);

    void setFilter(SocketFilter filter);

    void sendRequest(InetAddress address, int port, byte[] data, ResponseHandler handler);

    void sendRequest(InetAddress address, int port, byte[] data);

    void sendRequest(InetAddress address, int port, String data, ResponseHandler handler);

    void sendRequest(InetAddress address, int port, String data);

    void resendRequest(Request request, ResponseHandler handler);

    void killConnection();

    void setResponseTimeout(int ms);

    void setLogger(MrudpLogger logger);

}
