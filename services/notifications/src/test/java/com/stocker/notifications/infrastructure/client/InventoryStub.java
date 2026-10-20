package com.stocker.notifications.infrastructure.client;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Stands in for the inventory service over real HTTP (JDK built-in server, no dependency): the handler gets
 * the request's raw query string and returns {status, body}.
 */
public class InventoryStub implements AutoCloseable {

    private final HttpServer server;
    private volatile String lastPath;
    private volatile String lastQuery;

    public InventoryStub(Function<String, Reply> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            lastQuery = exchange.getRequestURI().getRawQuery();
            Reply reply = handler.apply(lastQuery == null ? "" : lastQuery);
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String lastPath() {
        return lastPath;
    }

    public String lastQuery() {
        return lastQuery;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public record Reply(int status, String body) {
        public static Reply ok(String body) {
            return new Reply(200, body);
        }
    }
}
