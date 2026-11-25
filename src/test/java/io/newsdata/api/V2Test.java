package io.newsdata.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.newsdata.api.exception.NewsdataApiKeyLimitExceededException;
import io.newsdata.api.exception.NewsdataApiLimitExceededException;
import io.newsdata.api.exception.NewsdataRateLimitException;
import io.newsdata.api.exception.NewsdataValidationException;

/**
 * The v2 endpoints: versioned paths, the snake_case filter vocabulary, the
 * snake_case envelope keys, and the quota-exhausted exception split.
 */
class V2Test {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Mount a handler at an exact path under the API root, version included. */
    private void handle(String path, HttpHandler handler) {
        server.createContext("/api/" + path, handler);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private NewsDataApiClient.Builder defaultBuilder() {
        return NewsDataApiClient.builder().apiKey("key").baseUrl(baseUrl);
    }

    @Test
    void eachV2MethodHitsItsVersionedPath() {
        AtomicReference<String> captured = new AtomicReference<>();
        for (String path : List.of(
                "2/news", "2/market", "2/crypto", "2/sources",
                "2/count/news", "2/count/market", "2/count/crypto")) {
            handle(path, exchange -> {
                captured.set(exchange.getRequestURI().getPath());
                respond(exchange, 200, "{\"status\":\"success\",\"results\":[]}");
            });
        }
        NewsDataApiClient client = defaultBuilder().build();

        Map<String, Function<Map<String, Object>, NewsdataResponse>> calls = Map.of(
                "2/news", client::newsV2,
                "2/market", client::marketV2,
                "2/crypto", client::cryptoV2,
                "2/sources", client::sourcesV2,
                "2/count/news", client::countNewsV2,
                "2/count/market", client::countMarketV2,
                "2/count/crypto", client::countCryptoV2);

        calls.forEach((path, call) -> {
            // sources takes no `q`, so send a filter every v2 endpoint accepts.
            call.apply(Map.of("language", "en"));
            assertEquals("/api/" + path, captured.get(),
                    path + " should be the requested path");
        });
    }

    @Test
    void v1MethodsKeepTheirVersionedPath() {
        AtomicReference<String> captured = new AtomicReference<>();
        handle("1/latest", exchange -> {
            captured.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"status\":\"success\",\"results\":[]}");
        });
        defaultBuilder().build().latest(Map.of("q", "x"));
        assertEquals("/api/1/latest", captured.get());
    }

    @Test
    void v2AcceptsTheSnakeCaseVocabulary() {
        AtomicReference<String> query = new AtomicReference<>();
        handle("2/news", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200, "{\"status\":\"success\",\"results\":[]}");
        });

        defaultBuilder().build().newsV2(Map.of(
                "q_in_title", "tesla",
                "exclude_country", "cn",
                "source_url", "bbc.com",
                "source_priority", "top",
                "content", true,
                "remove_duplicate", true,
                "exclude_field", "description"));

        for (String expected : List.of(
                "q_in_title=tesla", "exclude_country=cn", "source_url=bbc.com",
                "source_priority=top", "content=1", "remove_duplicate=1",
                "exclude_field=description")) {
            assertTrue(query.get().contains(expected),
                    "query " + query.get() + " should contain " + expected);
        }
    }

    @Test
    void v2RejectsTheV1SpellingsItReplaced() {
        NewsDataApiClient client = defaultBuilder().build();
        for (Map<String, Object> params : List.<Map<String, Object>>of(
                Map.of("qInTitle", "x"),
                Map.of("domain", "bbc.com"),
                Map.of("domainurl", "bbc.com"),
                Map.of("excludedomain", "bbc.com"),
                Map.of("excludedomainurl", "bbc.com"),
                Map.of("prioritydomain", "top"),
                Map.of("full_content", true),
                Map.of("removeduplicate", true),
                Map.of("excludefield", "description"))) {
            assertThrows(NewsdataValidationException.class, () -> client.newsV2(params),
                    params.keySet() + " should not be a v2 filter");
        }
    }

    @Test
    void v1StillAcceptsTheV1Spellings() {
        handle("1/latest", exchange ->
                respond(exchange, 200, "{\"status\":\"success\",\"results\":[]}"));
        NewsdataResponse resp = defaultBuilder().build().latest(Map.of(
                "qInTitle", "x", "domain", "bbc.com", "full_content", true));
        assertEquals("success", resp.status());
    }

    @Test
    void v2MutexGroupsAreEnforced() {
        NewsDataApiClient client = defaultBuilder().build();
        for (Map<String, Object> params : List.<Map<String, Object>>of(
                Map.of("source_url", "a.com", "exclude_source_url", "b.com"),
                Map.of("q", "a", "q_in_title", "b"),
                Map.of("country", "us", "exclude_country", "cn"),
                Map.of("category", "top", "exclude_category", "sports"),
                Map.of("language", "en", "exclude_language", "de"))) {
            assertThrows(NewsdataValidationException.class, () -> client.newsV2(params),
                    params.keySet() + " should be mutually exclusive");
        }
    }

    /**
     * The v2 envelope spells the total and cursor differently; the response
     * must expose either through totalResults() / nextPage().
     */
    @Test
    void responseReadsBothEnvelopeSpellings() {
        handle("2/news", exchange -> respond(exchange, 200,
                "{\"status\":\"success\",\"total_results\":42,\"results\":[],"
                        + "\"next_page\":\"abc\"}"));
        handle("1/latest", exchange -> respond(exchange, 200,
                "{\"status\":\"success\",\"totalResults\":42,\"results\":[],"
                        + "\"nextPage\":\"abc\"}"));
        NewsDataApiClient client = defaultBuilder().build();

        NewsdataResponse v2 = client.newsV2(Map.of("q", "x"));
        assertEquals(42, v2.totalResults());
        assertEquals("abc", v2.nextPage());

        NewsdataResponse v1 = client.latest(Map.of("q", "x"));
        assertEquals(42, v1.totalResults());
        assertEquals("abc", v1.nextPage());
    }

    @Test
    void scrollAllFollowsTheV2Cursor() {
        AtomicInteger calls = new AtomicInteger();
        handle("2/news", exchange -> {
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 200,
                        "{\"status\":\"success\",\"total_results\":2,"
                                + "\"results\":[{\"article_id\":\"1\"}],\"next_page\":\"p2\"}");
            } else {
                respond(exchange, 200,
                        "{\"status\":\"success\",\"total_results\":2,"
                                + "\"results\":[{\"article_id\":\"2\"}]}");
            }
        });

        NewsDataApiClient client = defaultBuilder()
                .paginationDelay(Duration.ZERO).build();
        NewsdataResponse merged = client.scrollAll(Endpoint.NEWS_V2, Map.of("q", "x"), 0);

        assertEquals(2, merged.articles(client.objectMapper()).size());
        assertEquals(2, calls.get());
    }

    // ---- the quota split (one exception per 429 credit code) -------------

    @Test
    void apiLimitExceededRaisesTheAccountPoolException() {
        AtomicInteger calls = new AtomicInteger();
        handle("2/news", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 429,
                    "{\"status\":\"error\",\"results\":{\"message\":\"limit\","
                            + "\"code\":\"ApiLimitExceeded\"}}");
        });

        NewsDataApiClient client = defaultBuilder()
                .maxRetries(5).retryBackoff(Duration.ofMillis(1))
                .retryBackoffMax(Duration.ofMillis(1)).build();

        NewsdataRateLimitException err = assertThrows(NewsdataRateLimitException.class,
                () -> client.newsV2(Map.of("q", "x")));
        assertInstanceOf(NewsdataApiLimitExceededException.class, err);
        assertEquals(1, calls.get(), "an exhausted quota must not retry");
    }

    @Test
    void apiKeyLimitExceededRaisesThePerKeyException() {
        handle("2/news", exchange -> respond(exchange, 429,
                "{\"status\":\"error\",\"results\":{\"message\":\"limit\","
                        + "\"code\":\"ApiKeyLimitExceeded\"}}"));

        NewsDataApiClient client = defaultBuilder()
                .maxRetries(5).retryBackoff(Duration.ofMillis(1))
                .retryBackoffMax(Duration.ofMillis(1)).build();

        NewsdataRateLimitException err = assertThrows(NewsdataRateLimitException.class,
                () -> client.newsV2(Map.of("q", "x")));
        assertInstanceOf(NewsdataApiKeyLimitExceededException.class, err);
    }

    @Test
    void transient429StillRetriesAndRaisesTheBaseException() {
        AtomicInteger calls = new AtomicInteger();
        handle("2/news", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 429,
                    "{\"status\":\"error\",\"results\":{\"message\":\"slow down\","
                            + "\"code\":\"RateLimitExceeded\"}}");
        });

        NewsDataApiClient client = defaultBuilder()
                .maxRetries(2).retryBackoff(Duration.ofMillis(1))
                .retryBackoffMax(Duration.ofMillis(1)).build();

        NewsdataRateLimitException err = assertThrows(NewsdataRateLimitException.class,
                () -> client.newsV2(Map.of("q", "x")));
        assertFalse(err instanceof NewsdataApiLimitExceededException);
        assertFalse(err instanceof NewsdataApiKeyLimitExceededException);
        assertEquals(2, calls.get());
    }

    @Test
    void everyQuotaCodeMapsToAnException() {
        for (String code : Constants.QUOTA_EXHAUSTED_CODES) {
            assertTrue(Constants.QUOTA_EXHAUSTED_ERRORS.containsKey(code),
                    code + " should map to an exception");
        }
    }

    // ---- excludedomainurl (v1) ------------------------------------------

    @Test
    void excludeDomainUrlIsAcceptedOnTheV1Endpoints() {
        for (String endpoint : List.of(
                "latest", "archive", "crypto", "market",
                "count", "crypto_count", "market_count")) {
            assertTrue(Constants.FILTERS.get(endpoint).contains("excludedomainurl"),
                    endpoint + " should accept excludedomainurl");
        }
        assertFalse(Constants.FILTERS.get("sources").contains("excludedomainurl"),
                "sources should not accept excludedomainurl");
    }

    @Test
    void excludeDomainUrlJoinsTheV1DomainMutexGroup() {
        NewsDataApiClient client = defaultBuilder().build();
        assertThrows(NewsdataValidationException.class,
                () -> client.latest(Map.of("domain", "a.com", "excludedomainurl", "b.com")));
    }
}
