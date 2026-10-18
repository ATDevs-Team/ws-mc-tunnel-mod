package org.atdevs.mctunnel;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TcpWebSocketBridge {
    private final String listenHost;
    private final int listenPort;
    private final URI websocketUri;
    private final String token;
    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile ServerSocket serverSocket;

    public TcpWebSocketBridge(
            String listenHost,
            int listenPort,
            String websocketUrl,
            String token) {

        this.listenHost = listenHost;
        this.listenPort = listenPort;
        this.websocketUri = URI.create(websocketUrl);
        this.token = token;

        String scheme = websocketUri.getScheme();

        if (!"ws".equalsIgnoreCase(scheme)
                && !"wss".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                "WebSocket URL must use ws:// or wss://"
            );
        }

        if (websocketUri.getHost() == null
                || websocketUri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                "Invalid WebSocket URL"
            );
        }

        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Empty token");
        }

        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        ServerSocket listener = new ServerSocket();

        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(listenHost, listenPort));
            serverSocket = listener;

            Thread thread = new Thread(this::acceptLoop, "mc-ws-accept");
            thread.setDaemon(true);
            thread.start();

            System.out.println(
                "[MC-WSTunnel] Listening on "
                + listenHost + ":" + listenPort
            );
        } catch (IOException | RuntimeException e) {
            running.set(false);
            try {
                listener.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);

                Thread worker = new Thread(
                    () -> handleClient(socket),
                    "mc-ws-client"
                );
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println(
                        "[MC-WSTunnel] Accept failed: " + e.getMessage()
                    );
                }
                return;
            }
        }
    }

    private void handleClient(Socket socket) {
        String peer = String.valueOf(socket.getRemoteSocketAddress());

        try (socket) {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);

            TunnelListener listener = new TunnelListener(socket);

            WebSocket websocket = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .buildAsync(websocketUri, listener)
                .join();

            listener.setWebSocket(websocket);

            if (!listener.awaitOpen(15, TimeUnit.SECONDS)) {
                throw new IOException("WebSocket opening timed out");
            }

            System.out.println("[MC-WSTunnel] Connected: " + peer);

            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[64 * 1024];

            while (running.get() && !socket.isClosed()) {
                int count = input.read(buffer);

                if (count < 0) {
                    break;
                }

                if (count == 0) {
                    continue;
                }

                byte[] copy = java.util.Arrays.copyOf(buffer, count);

                websocket.sendBinary(ByteBuffer.wrap(copy), true).join();
            }

            try {
                websocket.sendClose(
                    WebSocket.NORMAL_CLOSURE,
                    "TCP connection closed"
                ).get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                websocket.abort();
            }
        } catch (CompletionException e) {
            System.err.println(
                "[MC-WSTunnel] Connection failed for "
                + peer + ": " + rootMessage(e)
            );
        } catch (Exception e) {
            System.err.println(
                "[MC-WSTunnel] Connection ended for "
                + peer + ": " + rootMessage(e)
            );
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;

        while (current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }

        return current.getMessage() == null
            ? current.getClass().getSimpleName()
            : current.getMessage();
    }

    private static final class TunnelListener implements WebSocket.Listener {
        private final Socket socket;
        private final CountDownLatch opened = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile WebSocket websocket;

        TunnelListener(Socket socket) {
            this.socket = socket;
        }

        void setWebSocket(WebSocket websocket) {
            this.websocket = websocket;
        }

        boolean awaitOpen(long timeout, TimeUnit unit)
                throws InterruptedException {
            return opened.await(timeout, unit);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            websocket = webSocket;
            opened.countDown();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(
                WebSocket webSocket,
                ByteBuffer data,
                boolean last) {
            try {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);

                socket.getOutputStream().write(bytes);
                socket.getOutputStream().flush();

                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            } catch (IOException e) {
                fail(webSocket);
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last) {
            fail(webSocket);
            return CompletableFuture.failedFuture(
                new IOException("Unexpected text WebSocket message")
            );
        }

        @Override
        public CompletionStage<?> onPing(
                WebSocket webSocket,
                ByteBuffer message) {
            webSocket.request(1);
            return WebSocket.Listener.super.onPing(webSocket, message);
        }

        @Override
        public CompletionStage<?> onPong(
                WebSocket webSocket,
                ByteBuffer message) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket webSocket,
                int statusCode,
                String reason) {
            opened.countDown();
            closeSocket();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            opened.countDown();
            fail(webSocket);
        }

        private void fail(WebSocket webSocket) {
            closeSocket();
            webSocket.abort();
        }

        private void closeSocket() {
            if (closed.compareAndSet(false, true)) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
