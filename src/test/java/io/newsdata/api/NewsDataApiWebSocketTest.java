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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.newsdata.api.exception.NewsdataRateLimitException;
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
        // baseUrl is the API root; the endpoint paths carry the version.
        baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/api/";
    }

    @AfterEach
    void stopHttp() {
        if (httpServer != null) httpServer.stop(0);
    }

    private void handle(String path, HttpHandler handler) {
        httpServer.createContext("/api/1/" + path, handler);
    }

    /** The real-time management endpoints live under API version 2. */
    private void handleV2(String path, HttpHandler handler) {
        httpServer.createContext("/api/2/" + path, handler);
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
        return "{\"status\":\"success\",\"total_results\":1,\"results\":"
                + "[{\"id\":\"" + id + "\",\"title\":\"" + title + "\"}]}";
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
        handleV2("websocket/register", exchange -> {
            method.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200,
                    "{\"status\":\"success\",\"results\":{\"registration_id\":\"reg-9\"}}");
        });

        var response = client().websocketRegister(Params.of().with("q", "bitcoin"));

        assertEquals("POST", method.get());
        assertTrue(query.get().contains("news_type=news"), query.get());
        assertTrue(query.get().contains("q=bitcoin"), query.get());
        assertEquals("reg-9", response.results().path("registration_id").asText());
    }

    @Test
    void websocketRegisterDoesNotMutateCallerParams() {
        handleV2("websocket/register", exchange ->
                respond(exchange, 200, "{\"status\":\"success\",\"results\":{}}"));

        var params = Params.of().with("q", "bitcoin");
        client().websocketRegister(params);

        assertTrue(!params.containsKey("news_type"),
                "websocketRegister leaked news_type into the caller's params");
    }

    @Test
    void websocketFetchUsesGet() {
        AtomicReference<String> method = new AtomicReference<>();
        handleV2("websocket/fetch", exchange -> {
            method.set(exchange.getRequestMethod());
            respond(exchange, 200,
                    "{\"status\":\"success\",\"total_results\":0,\"results\":[]}");
        });

        client().websocketFetch();
        assertEquals("GET", method.get());
    }

    @Test
    void websocketFetchReturnsOneFlatEntryPerQuery() {
        handleV2("websocket/fetch", exchange -> respond(exchange, 200,
                "{\"status\":\"success\",\"total_results\":1,\"results\":[{"
                        + "\"registration_id\":\"reg-9\",\"active_clients\":2,"
                        + "\"last_used\":\"2026-11-20T10:15:00Z\","
                        + "\"news_type\":\"news\",\"query\":{\"q\":\"pizza\"}}]}"));

        var response = client().websocketFetch();

        assertEquals(1, response.totalResults());
        assertTrue(response.results().isArray(), "results should be a flat array");
        var entry = response.results().get(0);
        assertEquals("reg-9", entry.path("registration_id").asText());
        assertEquals(2, entry.path("active_clients").asInt());
        assertEquals("2026-11-20T10:15:00Z", entry.path("last_used").asText());
        assertEquals("pizza", entry.path("query").path("q").asText());
    }

    @Test
    void websocketDeleteUsesDelete() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        handleV2("websocket/delete", exchange -> {
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
        handleV2("websocket/delete", exchange ->
                respond(exchange, 200, "{\"status\":\"success\"}"));

        var response = client().websocketDelete("reg-9");
        assertEquals("success", response.status());
    }

    // ---- API version 2 ----------------------------------------------------
    // The real-time service moved to API version 2: the management endpoints
    // sit under /2/, news_type names the v2 news feed, and a registration
    // takes the v2 filter vocabulary only.

    @Test
    void websocketEndpointsAreUnderApiVersion2() {
        assertEquals("2/websocket/register",
                Constants.ENDPOINT_PATHS.get("websocket_register"));
        assertEquals("2/websocket/fetch", Constants.ENDPOINT_PATHS.get("websocket_fetch"));
        assertEquals("2/websocket/delete", Constants.ENDPOINT_PATHS.get("websocket_delete"));
    }

    @Test
    void newsTypeNamesTheV2NewsFeed() {
        assertEquals("news", Constants.WS_NEWS_TYPE);
    }

    @Test
    void registerTakesTheV2FilterNames() {
        var filters = Constants.FILTERS.get("websocket_register");
        for (String name : List.of(
                "q_in_title", "q_in_meta", "exclude_country", "exclude_category",
                "exclude_language", "source_url", "exclude_source_url",
                "source_priority", "content", "remove_duplicate", "exclude_field")) {
            assertTrue(filters.contains(name), name + " should be a register filter");
        }
        for (String name : List.of(
                "qintitle", "qinmeta", "excludecountry", "excludecategory",
                "excludelanguage", "domain", "domainurl", "excludedomain",
                "excludedomainurl", "prioritydomain", "full_content",
                "removeduplicate", "excludefield")) {
            assertTrue(!filters.contains(name), name + " should no longer be a register filter");
        }
        // Still no date or paging filters on a standing query.
        for (String name : List.of("from_date", "to_date", "page", "size", "sort")) {
            assertTrue(!filters.contains(name), name + " does not apply to a standing query");
        }
    }

    @Test
    void registerRejectsAV1FilterName() {
        assertThrows(NewsdataValidationException.class,
                () -> client().websocketRegister(Params.of().with("qInTitle", "pizza")));
    }

    @Test
    void registerKeepsTheV2SourceUrlMutexGroup() {
        assertThrows(NewsdataValidationException.class,
                () -> client().websocketRegister(Params.of()
                        .with("source_url", "a.com")
                        .with("exclude_source_url", "b.com")));
    }

    // RegistrationLimitExceeded means every registration slot the plan allows
    // is in use. It is never retried, but keeps the base exception: it is about
    // slots, not credits, so there is no dedicated subclass.
    @Test
    void registrationLimitExceededKeepsTheBaseException() {
        AtomicInteger calls = new AtomicInteger();
        handleV2("websocket/register", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 429, "{\"status\":\"error\",\"results\":{"
                    + "\"message\":\"limit\",\"code\":\"RegistrationLimitExceeded\"}}");
        });

        NewsDataApiClient client = NewsDataApiClient.builder()
                .apiKey("key").baseUrl(baseUrl)
                .maxRetries(5).retryBackoff(Duration.ofMillis(1))
                .retryBackoffMax(Duration.ofMillis(1))
                .build();

        var err = assertThrows(NewsdataRateLimitException.class,
                () -> client.websocketRegister(Params.of().with("q", "x")));
        assertEquals(NewsdataRateLimitException.class, err.getClass(),
                "registration slots are not credits, so no subclass applies");
        assertEquals(1, calls.get(), "a full registration set must not retry");
    }
}
