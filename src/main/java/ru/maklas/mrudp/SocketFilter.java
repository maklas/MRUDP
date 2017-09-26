package ru.maklas.mrudp;

import java.net.InetAddress;

/**
 * Created by maklas on 11.09.2017.
 * Фильтрует запросы от клиентов на основании IP, порта и запроса
 */
public interface SocketFilter {

    /**
     *  <p>Фильтрует запрос</p>
     *  <p><b>True</b> чтобы принять запрос </p>
     *  <p><b>False</b> чтобы отклонить</p>
     */
    boolean filter(InetAddress address, int port, Request request);

    /**
     * Определяет какой тип ошибки отправить клиенту если он был отфильтрован
     */
    int errorCodeToReturn();

}
