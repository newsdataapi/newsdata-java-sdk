package io.newsdata.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * A minimal RFC 6455 server for tests — enough to accept the handshake and
 * push text / close frames at the client. Server-to-client frames are unmasked,
 * which keeps the writer trivial; inbound frames are drained and ignored apart
 * from noticing the socket closing.
 *
 * <p>Not a general-purpose implementation: no fragmentation, no extensions, no
 * payloads over 65535 bytes.
 */
final class MockWebSocketServer implements AutoCloseable {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final Thread acceptor;
    private final AtomicInteger connections = new AtomicInteger();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    /** Handshake status to answer with; 101 performs the upgrade. */
    private final int handshakeStatus;

    /**
     * @param handshakeStatus 101 to accept, or an error status to reject with
     * @param onConnect       runs per accepted connection: (session, connectionNumber)
     */
    MockWebSocketServer(int handshakeStatus, BiConsumer<Session, Integer> onConnect)
            throws IOException {
        this.handshakeStatus = handshakeStatus;
        this.serverSocket = new ServerSocket();
        this.serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        this.acceptor = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    int n = connections.incrementAndGet();
                    Thread worker = new Thread(() -> serve(socket, onConnect, n));
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    return; // socket closed
                }
            }
        });
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    String url() {
        return "ws://127.0.0.1:" + serverSocket.getLocalPort() + "/ws/event";
    }

    int connectionCount() {
        return connections.get();
    }

    /** Query strings seen on each handshake, in order. */
    List<String> queries() {
        return new ArrayList<>(queries);
    }

    private void serve(Socket socket, BiConsumer<Session, Integer> onConnect, int n) {
        try (Socket s = socket) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine != null) {
                String[] parts = requestLine.split(" ");
                if (parts.length > 1) {
                    int q = parts[1].indexOf('?');
                    queries.add(q >= 0 ? parts[1].substring(q + 1) : "");
                }
            }
            String key = null;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                    key = line.substring(line.indexOf(':') + 1).trim();
                }
            }

            if (handshakeStatus != 101) {
                byte[] body = "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 " + handshakeStatus + " NO\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(body);
                out.flush();
                return;
            }

            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept(key) + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();

            onConnect.accept(new Session(out), n);
        } catch (IOException ignored) {
            // client went away
        }
    }

    private static String accept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    /** Write side of one accepted connection. */
    static final class Session {
        private final OutputStream out;

        Session(OutputStream out) {
            this.out = out;
        }

        /** Send one unmasked text frame. */
        void sendText(String payload) throws IOException {
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            synchronized (out) {
                out.write(0x81); // FIN + text
                if (data.length < 126) {
                    out.write(data.length);
                } else {
                    out.write(126);
                    out.write((data.length >>> 8) & 0xFF);
                    out.write(data.length & 0xFF);
                }
                out.write(data);
                out.flush();
            }
        }

        /** Send a close frame with the given code and reason. */
        void sendClose(int code, String reason) throws IOException {
            byte[] r = reason.getBytes(StandardCharsets.UTF_8);
            synchronized (out) {
                out.write(0x88); // FIN + close
                out.write(2 + r.length);
                out.write((code >>> 8) & 0xFF);
                out.write(code & 0xFF);
                out.write(r);
                out.flush();
            }
        }

        /** Sleep, keeping the connection open. */
        void hold(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
