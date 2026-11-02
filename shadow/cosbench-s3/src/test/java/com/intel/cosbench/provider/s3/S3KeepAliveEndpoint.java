package com.intel.cosbench.provider.s3;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class S3KeepAliveEndpoint implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    S3KeepAliveEndpoint() throws IOException {
        executor.submit(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket socket = listener.accept();
                    connections.add(socket);
                    executor.submit(() -> serve(socket));
                } catch (IOException error) {
                    if (!listener.isClosed()) {
                        throw new java.io.UncheckedIOException(error);
                    }
                }
            }
        });
    }

    String endpoint() {
        return "http://127.0.0.1:" + listener.getLocalPort();
    }

    boolean hasConnections() {
        return !connections.isEmpty();
    }

    boolean awaitDisconnected() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (hasConnections() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return !hasConnections();
    }

    private void serve(Socket socket) {
        try (socket; var input = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
            while (input.readLine() != null) {
                String header;
                while ((header = input.readLine()) != null && !header.isEmpty()) { }
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n"
                        + "Connection: keep-alive\r\n\r\ndata").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            }
        } catch (IOException ignored) {
            // The client and fixture may close concurrently during cleanup.
        } finally {
            connections.remove(socket);
        }
    }

    @Override
    public void close() throws IOException {
        listener.close();
        try {
            for (Socket socket : connections) {
                socket.close();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
