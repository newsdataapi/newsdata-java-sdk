package io.newsdata.api;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;

import io.newsdata.api.exception.NewsdataValidationException;
import io.newsdata.api.exception.NewsdataWebSocketAuthException;
import io.newsdata.api.exception.NewsdataWebSocketException;

/**
 * NewsData.io real-time WebSocket service.
 *
 * <p>Registers, lists, and deletes the account's real-time queries and streams
 * the responses for a registered query. The management calls go through the
 * wrapped {@link NewsDataApiClient}:
 *
 * <pre>{@code
 * NewsDataApiClient client = NewsDataApiClient.builder(apiKey).build();
 * try (NewsDataApiWebSocket ws = new NewsDataApiWebSocket(client)) {
 *     NewsdataResponse registered = ws.websocketRegister(Params.of().with("q", "bitcoin"));
 *     String id = registered.results().path("registration_id").asText();
 *
 *     ws.stream(id, response -> {
 *         for (Article a : response.articles(client.objectMapper())) {
 *             System.out.println(a.title());
 *         }
 *         return true;   // keep streaming; return false to stop
 *     });
 * }
 * }</pre>
 *
 * <p>Transient drops (network errors, server restarts, abnormal closes) are
 * reconnected automatically with a capped exponential backoff; build with
 * {@code reconnect(false)} to stop on the first disconnect. A permanent
 * rejection always throws {@link NewsdataWebSocketAuthException} and is never
 * retried.
 *
 * <p>Closing the instance (or leaving the try-with-resources block) stops an
 * in-flight {@link #stream}.
 *
 * <p>A single {@code stream} call must not be shared between threads.
 */
public final class NewsDataApiWebSocket implements AutoCloseable {

    /** Sentinel queued when the connection closes normally. */
    private static final String NORMAL_CLOSE = new String("__normal_close__");

    private final NewsDataApiClient client;
    private final String baseUrl;
    private final boolean reconnect;
    private final Duration reconnectDelay;
    private final Duration reconnectDelayMax;
    private final Duration handshakeTimeout;
    private final Map<String, String> headers;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile WebSocket active;

    /** Construct with the defaults; equivalent to {@code builder(client).build()}. */
    public NewsDataApiWebSocket(NewsDataApiClient client) {
        this(builder(client));
    }

    private NewsDataApiWebSocket(Builder b) {
        if (b.client == null) {
            throw new NewsdataValidationException("client is required", "client");
        }
        this.client = b.client;
        this.baseUrl = b.baseUrl;
        this.reconnect = b.reconnect;
        this.reconnectDelay = b.reconnectDelay;
        this.reconnectDelayMax = b.reconnectDelayMax;
        this.handshakeTimeout = b.handshakeTimeout;
        this.headers = Map.copyOf(b.headers);
    }

    /** A builder for the connection options. */
    public static Builder builder(NewsDataApiClient client) {
        return new Builder(client);
    }

    /** Fluent options for {@link NewsDataApiWebSocket}. */
    public static final class Builder {
        private final NewsDataApiClient client;
        private String baseUrl = Constants.WS_BASE_URL;
        private boolean reconnect = true;
        private Duration reconnectDelay = Constants.WS_RECONNECT_DELAY;
        private Duration reconnectDelayMax = Constants.WS_RECONNECT_DELAY_MAX;
        private Duration handshakeTimeout = Constants.WS_HANDSHAKE_TIMEOUT;
        private Map<String, String> headers = Map.of();

        private Builder(NewsDataApiClient client) {
            this.client = client;
        }

        /** WebSocket endpoint; override for staging / self-hosted / proxied. */
        public Builder baseUrl(String v) { this.baseUrl = v; return this; }

        /** Reconnect on transient drops. Default {@code true}. */
        public Builder reconnect(boolean v) { this.reconnect = v; return this; }

        /** Wait before the first reconnect; doubles after each failure. */
        public Builder reconnectDelay(Duration v) { this.reconnectDelay = v; return this; }

        /** Upper bound on the reconnect delay. */
        public Builder reconnectDelayMax(Duration v) { this.reconnectDelayMax = v; return this; }

        /** Bound on the opening handshake. */
        public Builder handshakeTimeout(Duration v) { this.handshakeTimeout = v; return this; }

        /** Extra HTTP headers for the opening handshake. */
        public Builder headers(Map<String, String> v) { this.headers = v; return this; }

        public NewsDataApiWebSocket build() { return new NewsDataApiWebSocket(this); }
    }

    // ---- query management -------------------------------------------------

    /** Register a real-time query. See {@link NewsDataApiClient#websocketRegister}. */
    public NewsdataResponse websocketRegister(Map<String, Object> params) {
        return client.websocketRegister(params);
    }

    /** List registered queries. See {@link NewsDataApiClient#websocketFetch}. */
    public NewsdataResponse websocketFetch() {
        return client.websocketFetch();
    }

    /** Delete a registered query. See {@link NewsDataApiClient#websocketDelete}. */
    public NewsdataResponse websocketDelete(String registrationId) {
        return client.websocketDelete(registrationId);
    }

    // ---- streaming --------------------------------------------------------

    private String url(String registrationId) {
        return baseUrl
                + "?apikey=" + URLEncoder.encode(client.apiKey(), StandardCharsets.UTF_8)
                + "&registration_id=" + URLEncoder.encode(registrationId, StandardCharsets.UTF_8);
    }

    private Duration nextDelay(Duration delay) {
        Duration doubled = delay.multipliedBy(2);
        return doubled.compareTo(reconnectDelayMax) > 0 ? reconnectDelayMax : doubled;
    }

    /**
     * Connect and hand each response to {@code handler} as it arrives.
     * Responses have the familiar {@code status} / {@code totalResults} /
     * {@code results} shape.
     *
     * <p>Blocks until the handler returns {@code false}, the instance is
     * closed, the calling thread is interrupted, or — with reconnect
     * disabled — the connection drops.
     *
     * @param registrationId the registered query to stream
     * @param handler        returns {@code true} to keep streaming,
     *                       {@code false} to stop
     */
    public void stream(String registrationId, Predicate<NewsdataResponse> handler) {
        if (registrationId == null || registrationId.isEmpty()) {
            throw new NewsdataValidationException(
                    "registrationId must be a non-empty string", "registration_id");
        }
        if (handler == null) {
            throw new NewsdataValidationException("handler must not be null", "handler");
        }

        String url = url(registrationId);
        String logUrl = NewsDataApiClient.redactApiKey(url);
        Duration delay = reconnectDelay;
        closed.set(false);

        try {
            while (!closed.get()) {
                boolean closedNormally;
                try {
                    closedNormally = runOnce(url, logUrl, handler);
                } catch (StopStream stop) {
                    return;
                }
                if (closed.get()) return;
                if (closedNormally && !reconnect) return;
                if (!reconnect) return;

                // Transient failure, or a normal close with reconnect enabled:
                // wait (capped exponential backoff) and reconnect.
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delay = nextDelay(delay);
            }
        } finally {
            closeActive();
        }
    }

    /**
     * Hold a single connection open until it drops or the handler stops it.
     *
     * @return {@code true} when the connection closed normally
     * @throws StopStream when the handler asked to stop
     */
    private boolean runOnce(String url, String logUrl, Predicate<NewsdataResponse> handler) {
        LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        WebSocket socket;
        try {
            HttpClient http = client.httpClient();
            WebSocket.Builder wsBuilder = http.newWebSocketBuilder()
                    .connectTimeout(handshakeTimeout);
            headers.forEach(wsBuilder::header);
            socket = wsBuilder.buildAsync(URI.create(url), new Listener(queue))
                    .get(handshakeTimeout.toMillis() + 1_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StopStream();
        } catch (Exception e) {
            handleFailure(unwrap(e), logUrl);
            return false;
        }

        active = socket;
        client.logFromWebSocket("info", "connected to " + logUrl);
        socket.request(1);

        while (true) {
            Object item;
            try {
                item = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StopStream();
            }
            if (closed.get()) throw new StopStream();

            if (item == NORMAL_CLOSE) {
                return true;
            }
            if (item instanceof Throwable) {
                handleFailure((Throwable) item, logUrl);
                return false;
            }

            NewsdataResponse response = parse((String) item);
            if (response == null) continue; // skip malformed frames
            if (!handler.test(response)) {
                throw new StopStream();
            }
        }
    }

    /** Parse one frame, returning null when it isn't a JSON object. */
    private NewsdataResponse parse(String message) {
        try {
            JsonNode node = client.objectMapper().readTree(message);
            if (node == null || !node.isObject()) return null;
            return new NewsdataResponse(
                    node.path("status").asText(null),
                    node.path("totalResults").asInt(0),
                    node.get("results"),
                    node.hasNonNull("nextPage") ? node.get("nextPage").asText() : null,
                    null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Classify a connection failure.
     *
     * <p>The server always accepts the handshake and then closes with code
     * 1008 on a permanent failure — {@code invalid credentials or registration
     * not found}, {@code api limit reached}, or {@code device limit reached}.
     * Those always throw. Every other close code, including 1013
     * ({@code send timeout} — this client read too slowly), is transient: it
     * throws only when reconnect is disabled, otherwise it is logged so the
     * caller backs off and retries. The handshake-status check is defensive,
     * for proxies in front of the documented server.
     */
    private void handleFailure(Throwable err, String logUrl) {
        NewsdataWebSocketAuthException auth = permanentAuthError(err);
        if (auth != null) throw auth;
        if (!reconnect) throw transientError(err);
        client.logFromWebSocket("warn",
                "connection to " + logUrl + " failed (" + err + "); reconnecting");
    }

    /**
     * The auth error to throw if {@code err} is permanent, else null. Close
     * code 1008 is the documented permanent signal; the handshake check is
     * defensive.
     */
    private static NewsdataWebSocketAuthException permanentAuthError(Throwable err) {
        if (err instanceof WebSocketHandshakeException) {
            int status = ((WebSocketHandshakeException) err).getResponse().statusCode();
            if (status == 401 || status == 403) {
                return new NewsdataWebSocketAuthException("connection rejected", err);
            }
            return null;
        }
        if (err instanceof PolicyViolation) {
            return new NewsdataWebSocketAuthException(err.getMessage(), err);
        }
        return null;
    }

    /** Wrap a transient failure; used only when reconnect is disabled. */
    private static NewsdataWebSocketException transientError(Throwable err) {
        if (err instanceof WebSocketHandshakeException) {
            int status = ((WebSocketHandshakeException) err).getResponse().statusCode();
            return new NewsdataWebSocketException("handshake failed (HTTP " + status + ")", err);
        }
        if (err instanceof AbnormalClose) {
            return new NewsdataWebSocketException("connection closed", err);
        }
        return new NewsdataWebSocketException("connection error: " + err, err);
    }

    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private void closeActive() {
        WebSocket socket = active;
        active = null;
        if (socket != null && !socket.isOutputClosed()) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "");
            } catch (Exception ignored) {
                socket.abort();
            }
        }
    }

    /** Close the active connection, ending any in-flight {@link #stream}. */
    @Override
    public void close() {
        closed.set(true);
        WebSocket socket = active;
        if (socket != null) socket.abort();
        active = null;
    }

    /** Bridges the callback listener onto the blocking queue the stream reads. */
    private final class Listener implements WebSocket.Listener {
        private final LinkedBlockingQueue<Object> queue;
        private final StringBuilder buffer = new StringBuilder();

        Listener(LinkedBlockingQueue<Object> queue) {
            this.queue = queue;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                queue.offer(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (statusCode == WebSocket.NORMAL_CLOSURE) {
                queue.offer(NORMAL_CLOSE);
            } else if (statusCode == Constants.WS_POLICY_VIOLATION) {
                queue.offer(new PolicyViolation(
                        reason == null || reason.isEmpty() ? "connection rejected" : reason));
            } else {
                queue.offer(new AbnormalClose("connection closed (" + statusCode + ")"));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            queue.offer(error);
        }
    }

    /** Close code 1008 — a permanent rejection. */
    private static final class PolicyViolation extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PolicyViolation(String message) { super(message); }
    }

    /** Any other non-normal close — transient. */
    private static final class AbnormalClose extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AbnormalClose(String message) { super(message); }
    }

    /** Internal control-flow marker: the handler (or close()) stopped the stream. */
    private static final class StopStream extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StopStream() { super(null, null, false, false); }
    }
}
