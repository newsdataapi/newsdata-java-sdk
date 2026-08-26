package io.newsdata.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.newsdata.api.exception.NewsdataValidationException;
import io.newsdata.api.exception.NewsdataWebSocketAuthException;
import io.newsdata.api.exception.NewsdataWebSocketException;

/** Real-time WebSocket tests, against a local RFC 6455 mock. */
class NewsDataApiWebSocketTest {

    private HttpServer httpServer;
    private String baseUrl;

    @BeforeEach
    void startHttp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.start();
        baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/api/1/";
    }

    @AfterEach
    void stopHttp() {
        if (httpServer != null) httpServer.stop(0);
    }

    private void handle(String path, HttpHandler handler) {
        httpServer.createContext("/api/1/" + path, handler);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private NewsDataApiClient client() {
        return NewsDataApiClient.builder().apiKey("key").baseUrl(baseUrl).build();
    }

    private static String articleFrame(String id, String title) {
        return "{\"status\":\"success\",\"totalResults\":1,\"results\":"
                + "[{\"article_id\":\"" + id + "\",\"title\":\"" + title + "\"}]}";
    }

    @Test
    @Timeout(20)
    void streamsResponsesAsTheyArrive() throws Exception {
        try (MockWebSocketServer server = new MockWebSocketServer(101, (session, n) -> {
            try {
                session.sendText(articleFrame("a1", "one"));
                session.sendText(articleFrame("a2", "two"));
                session.hold(500);
            } catch (IOException ignored) {
                // client closed
            }
        })) {
            var ws = NewsDataApiWebSocket.builder(client())
                    .baseUrl(server.url()).reconnect(false).build();

            List<String> titles = new CopyOnWriteArrayList<>();
            ws.stream("reg-1", response -> {
                titles.add(response.results().get(0).path("title").asText());
                return titles.size() < 2; // stop after the second
            });

            assertEquals(List.of("one", "two"), titles);
        }
    }

    @Test
    @Timeout(20)
    void sendsApiKeyAndRegistrationIdInQuery() throws Exception {
        try (MockWebSocketServer server = new MockWebSocketServer(101, (session, n) -> {
            try {
                session.sendText(articleFrame("a1", "one"));
                session.hold(300);
            } catch (IOException ignored) {
                // client closed
            }
        })) {
            var ws = NewsDataApiWebSocket.builder(client())
                    .baseUrl(server.url()).reconnect(false).build();
            ws.stream("reg-42", response -> false);

            String query = server.queries().get(0);
            assertTrue(query.contains("apikey=key"), "query missing apikey: " + query);
            assertTrue(query.contains("registration_id=reg-42"), "query missing id: " + query);
        }
    }

    @Test
    @Timeout(20)
    void skipsMalformedFrames() throws Exception {
        try (MockWebSocketServer server = new MockWebSocketServer(101, (session, n) -> {
            try {
                session.sendText("not json at all");
                session.sendText(articleFrame("a1", "one"));
                session.hold(300);
            } catch (IOException ignored) {
                // client closed
            }
        })) {
            var ws = NewsDataApiWebSocket.builder(client())
                    .baseUrl(server.url()).reconnect(false).build();

            List<String> seen = new CopyOnWriteArrayList<>();
            ws.stream("reg-1", response -> {
                seen.add(response.results().get(0).path("title").asText());
                return false;
            });

            assertEquals(List.of("one"), seen, "the malformed frame should be skipped");
        }
    }

    @Test
    @Timeout(20)
    void handshake401IsPermanentAndNotRetried() throws Exception {
        try (MockWebSocketServer server = new MockWebSocketServer(401, (session, n) -> { })) {
            // reconnect stays ON to prove a permanent rejection is not retried.
            var ws = NewsDataApiWebSocket.builder(client())
                    .baseUrl(server.url())
                    .reconnectDelay(Duration.ofMillis(5))
                    .build();

            assertThrows(NewsdataWebSocketAuthException.class,
                    () -> ws.stream("reg-1", response -> true));
            assertEquals(1, server.connectionCount(),
                    "a permanent rejection must not retry");
        }
    }

    @Test
    @Timeout(20)
    void policyViolationCloseIsPermanent() throws Exception {
        try (MockWebSocketServer server = new MockWebSocketServer(101, (session, n) -> {
            try {
                session.sendClose(1008, "quota exhausted");
                session.hold(200);
            } catch (IOException ignored) {
                // client closed
            }
        })) {
            var ws = NewsDataApiWebSocket.builder(client())
                    .baseUrl(server.url())
                    .reconnectDelay(Duration.ofMillis(5))
                    .build();

            var err = assertThrows(NewsdataWebSocketAuthException.class,
                    () -> ws.stream("reg-1", response -> true));
            assertTrue(err.getMessage().contains("quota exhausted"),
                    "should carry the close reason, got: " + err.getMessage());
            assertEquals(1, server.connectionCount());
        }
    }

    @Test
    @Timeout(20)
    void transientHandshakeStopsWhenReconnectDisabled() throws Exception {
        try (MockWebSocketServer server = new MockWebSocketServer(500, (session, n) -> { })) {
            var ws = NewsDataApiWebSocket.builder(client())
                    .baseUrl(server.url()).reconnect(false).build();

            var err = assertThrows(NewsdataWebSocketException.class,
                    () -> ws.stream("reg-1", response -> true));
            assertTrue(!(err instanceof NewsdataWebSocketAuthException),
                    "a 500 handshake is transient, not an auth error");
        }
    }

    @Test
    @Timeout(30)
    void reconnectsAfterTransientDrop() throws Exception {
        try (MockWebSocketServer server = new MockWebSocketServer(101, (session, n) -> {
            try {
                if (n == 1) {
                    session.sendClose(1011, "server restart"); // transient
                    return;
                }
                session.sendText(articleFrame("a1", "after-reconnect"));
                session.hold(300);
            } catch (IOException ignored) {
                // client closed
            }
        })) {
            var ws = NewsDataApiWebSocket.builder(client())
                    .baseUrl(server.url())
                    .reconnectDelay(Duration.ofMillis(10))
                    .reconnectDelayMax(Duration.ofMillis(50))
                    .build();

            AtomicReference<String> got = new AtomicReference<>();
            ws.stream("reg-1", response -> {
                got.set(response.results().get(0).path("title").asText());
                return false;
            });

            assertEquals("after-reconnect", got.get());
            assertTrue(server.connectionCount() >= 2,
                    "should have reconnected, connections=" + server.connectionCount());
        }
    }

    @Test
    void rejectsEmptyRegistrationId() {
        var ws = new NewsDataApiWebSocket(client());
        assertThrows(NewsdataValidationException.class,
                () -> ws.stream("", response -> true));
    }

    @Test
    void rejectsNullHandler() {
        var ws = new NewsDataApiWebSocket(client());
        assertThrows(NewsdataValidationException.class, () -> ws.stream("reg-1", null));
    }

    // ---- query management -------------------------------------------------

    @Test
    void websocketRegisterPostsWithNewsType() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        handle("websocket/register", exchange -> {
            method.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200,
                    "{\"status\":\"success\",\"results\":{\"registration_id\":\"reg-9\"}}");
        });

        var response = client().websocketRegister(Params.of().with("q", "bitcoin"));

        assertEquals("POST", method.get());
        assertTrue(query.get().contains("news_type=latest"), query.get());
        assertTrue(query.get().contains("q=bitcoin"), query.get());
        assertEquals("reg-9", response.results().path("registration_id").asText());
    }

    @Test
    void websocketRegisterDoesNotMutateCallerParams() {
        handle("websocket/register", exchange ->
                respond(exchange, 200, "{\"status\":\"success\",\"results\":{}}"));

        var params = Params.of().with("q", "bitcoin");
        client().websocketRegister(params);

        assertTrue(!params.containsKey("news_type"),
                "websocketRegister leaked news_type into the caller's params");
    }

    @Test
    void websocketFetchUsesGet() {
        AtomicReference<String> method = new AtomicReference<>();
        handle("websocket/fetch", exchange -> {
            method.set(exchange.getRequestMethod());
            respond(exchange, 200, "{\"status\":\"success\",\"results\":{\"queries\":[]}}");
        });

        client().websocketFetch();
        assertEquals("GET", method.get());
    }

    @Test
    void websocketDeleteUsesDelete() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        handle("websocket/delete", exchange -> {
            method.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200, "{\"status\":\"success\",\"results\":{\"deleted\":true}}");
        });

        client().websocketDelete("reg-9");

        assertEquals("DELETE", method.get());
        assertTrue(query.get().contains("registration_id=reg-9"), query.get());
    }

    @Test
    void websocketDeleteRejectsEmptyId() {
        assertThrows(NewsdataValidationException.class, () -> client().websocketDelete(""));
    }

    @Test
    void resultlessSuccessStillSucceedsOnWebsocketEndpoints() {
        handle("websocket/delete", exchange ->
                respond(exchange, 200, "{\"status\":\"success\"}"));

        var response = client().websocketDelete("reg-9");
        assertEquals("success", response.status());
    }
}
