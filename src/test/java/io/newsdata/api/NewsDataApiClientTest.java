package io.newsdata.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.newsdata.api.exception.NewsdataApiException;
import io.newsdata.api.exception.NewsdataAuthException;
import io.newsdata.api.exception.NewsdataException;
import io.newsdata.api.exception.NewsdataRateLimitException;
import io.newsdata.api.exception.NewsdataValidationException;

/**
 * Client tests using Java's built-in {@link HttpServer} as a local mock —
 * no WireMock or external dep needed.
 */
class NewsDataApiClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/1/";
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private void handle(String path, HttpHandler handler) {
        server.createContext("/api/1/" + path, handler);
    }

    private static String successBody(String resultsJson) {
        return "{\"status\":\"success\",\"results\":" + resultsJson + "}";
    }

    private static void respond(HttpExchange exchange, int status, String body, String... headers) throws IOException {
        for (int i = 0; i + 1 < headers.length; i += 2) {
            exchange.getResponseHeaders().add(headers[i], headers[i + 1]);
        }
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
    void successfulRequestReturnsResponse() {
        AtomicReference<String> capturedQuery = new AtomicReference<>();
        handle("latest", exchange -> {
            capturedQuery.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200, successBody("[{\"article_id\":\"1\",\"title\":\"a\"}]"));
        });
        var client = defaultBuilder().build();
        var resp = client.latest(Params.of().with("q", "x"));
        var articles = resp.articles(client.objectMapper());
        assertEquals(1, articles.size());
        assertEquals("a", articles.get(0).title());
        assertTrue(capturedQuery.get().contains("apikey=key"));
        assertTrue(capturedQuery.get().contains("q=x"));
    }

    @Test
    void authError401Thrown() {
        handle("latest", exchange -> respond(exchange, 401,
                "{\"status\":\"error\",\"results\":{\"message\":\"bad key\"}}"));
        var client = defaultBuilder().build();
        var ex = assertThrows(NewsdataAuthException.class,
                () -> client.latest(Params.of().with("q", "x")));
        assertEquals(401, ex.statusCode());
    }

    @Test
    void rateLimitRetriesThenThrows() {
        AtomicInteger calls = new AtomicInteger();
        handle("latest", exchange -> {
            int n = calls.incrementAndGet();
            String retry = (n == 2) ? "7" : "0";
            respond(exchange, 429, "{\"status\":\"error\"}", "Retry-After", retry);
        });
        var client = defaultBuilder()
                .maxRetries(2)
                .retryBackoff(Duration.ofMillis(1))
                .retryBackoffMax(Duration.ofMillis(1))
                .build();
        var ex = assertThrows(NewsdataRateLimitException.class,
                () -> client.latest(Params.of().with("q", "x")));
        assertEquals(7, ex.retryAfter());
        assertEquals(2, calls.get());
    }

    @Test
    void serverErrorRetriedThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        handle("latest", exchange -> {
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 503, "{\"status\":\"error\"}");
            } else {
                respond(exchange, 200,
                        successBody("[{\"article_id\":\"1\",\"title\":\"recovered\"}]"));
            }
        });
        var client = defaultBuilder()
                .maxRetries(3)
                .retryBackoff(Duration.ofMillis(1))
                .build();
        var resp = client.latest(Params.of().with("q", "x"));
        var articles = resp.articles(client.objectMapper());
        assertEquals("recovered", articles.get(0).title());
        assertEquals(2, calls.get());
    }

    @Test
    void scrollAllMergesResultsAcrossPages() {
        String[] pages = {
                "{\"status\":\"success\",\"totalResults\":3,\"nextPage\":\"p2\",\"results\":[{\"article_id\":\"1\",\"title\":\"a\"},{\"article_id\":\"2\",\"title\":\"b\"}]}",
                "{\"status\":\"success\",\"totalResults\":3,\"results\":[{\"article_id\":\"3\",\"title\":\"c\"}]}"
        };
        AtomicInteger calls = new AtomicInteger();
        handle("latest", exchange -> respond(exchange, 200, pages[calls.getAndIncrement()]));
        var client = defaultBuilder().paginationDelay(Duration.ZERO).build();
        var merged = client.scrollAll(Endpoint.LATEST, Params.of().with("q", "x"), 0);
        assertEquals(3, merged.articles(client.objectMapper()).size());
        assertEquals(2, calls.get());
    }

    @Test
    void scrollAllHonorsMaxResults() {
        handle("latest", exchange -> respond(exchange, 200,
                "{\"status\":\"success\",\"nextPage\":\"p2\",\"results\":[{\"article_id\":\"1\",\"title\":\"a\"},{\"article_id\":\"2\",\"title\":\"b\"}]}"));
        var client = defaultBuilder().paginationDelay(Duration.ZERO).build();
        var merged = client.scrollAll(Endpoint.LATEST, Params.of().with("q", "x"), 1);
        assertEquals(1, merged.articles(client.objectMapper()).size());
    }

    @Test
    void paginateYieldsEachPage() {
        String[] pages = {
                "{\"status\":\"success\",\"nextPage\":\"p2\",\"results\":[{\"article_id\":\"1\",\"title\":\"a\"}]}",
                "{\"status\":\"success\",\"results\":[{\"article_id\":\"2\",\"title\":\"b\"}]}"
        };
        AtomicInteger calls = new AtomicInteger();
        handle("latest", exchange -> respond(exchange, 200, pages[calls.getAndIncrement()]));
        var client = defaultBuilder().paginationDelay(Duration.ZERO).build();

        List<String> seen = new ArrayList<>();
        client.paginate(Endpoint.LATEST, Params.of().with("q", "x"))
                .forEach(resp -> resp.articles(client.objectMapper())
                        .forEach(a -> seen.add(a.title())));
        assertEquals(List.of("a", "b"), seen);
    }

    @Test
    void paginateLimitStopsEarly() {
        String[] pages = {
                "{\"status\":\"success\",\"nextPage\":\"p2\",\"results\":[{\"article_id\":\"1\",\"title\":\"a\"}]}",
                "{\"status\":\"success\",\"nextPage\":\"p3\",\"results\":[{\"article_id\":\"2\",\"title\":\"b\"}]}",
                "{\"status\":\"success\",\"nextPage\":\"p4\",\"results\":[{\"article_id\":\"3\",\"title\":\"c\"}]}"
        };
        AtomicInteger calls = new AtomicInteger();
        handle("latest", exchange -> respond(exchange, 200, pages[calls.getAndIncrement()]));
        var client = defaultBuilder().paginationDelay(Duration.ZERO).build();

        long seen = client.paginate(Endpoint.LATEST, Params.of().with("q", "x"))
                .limit(2)
                .count();
        assertEquals(2, seen);
        // Stream.limit may pull 2 or 3 elements depending on impl; both
        // are acceptable, so we don't assert on calls here.
    }

    @Test
    void emptyApiKeyRejected() {
        assertThrows(NewsdataValidationException.class,
                () -> NewsDataApiClient.builder().apiKey("").build());
    }

    @Test
    void redactApiKeyHidesKey() {
        assertEquals(
                "https://newsdata.io/api/1/latest?apikey=REDACTED&q=foo",
                NewsDataApiClient.redactApiKey(
                        "https://newsdata.io/api/1/latest?apikey=SECRET&q=foo"));
    }

    @Test
    void typedErrorsFormCatchableHierarchy() {
        handle("latest", exchange -> respond(exchange, 401, "{\"status\":\"error\"}"));
        var client = defaultBuilder().build();
        try {
            client.latest(Params.of().with("q", "x"));
            fail("expected exception");
        } catch (NewsdataException e) {
            assertTrue(e instanceof NewsdataAuthException);
            assertTrue(e instanceof NewsdataApiException);
        }
    }

    @Test
    void articleFieldsDecodeFromSnakeCase() {
        handle("latest", exchange -> respond(exchange, 200,
                successBody("[{\"article_id\":\"a1\",\"title\":\"t\",\"link\":\"l\","
                        + "\"ai_tag\":[\"x\",\"y\"],\"sentiment\":\"positive\","
                        + "\"source_priority\":1}]")));
        var client = defaultBuilder().build();
        var resp = client.latest(Params.of().with("q", "x"));
        var art = resp.articles(client.objectMapper()).get(0);
        assertEquals("a1", art.articleId());
        assertEquals("t", art.title());
        assertEquals("l", art.link());
        assertEquals(List.of("x", "y"), art.aiTag());
        assertEquals("positive", art.sentiment());
        assertEquals(1, art.sourcePriority());
    }

    // Market results carry both `symbol` and `market_id`; the two are separate
    // response fields and both decode onto Article.
    @Test
    void articleDecodesSymbolAndMarketId() {
        handle("market", exchange -> respond(exchange, 200,
                successBody("[{\"article_id\":\"m1\",\"symbol\":[\"AAPL\",\"MSFT\"],"
                        + "\"market_id\":[\"NASDAQ:AAPL\",\"NASDAQ:MSFT\"]}]")));
        var client = defaultBuilder().build();
        var resp = client.market(Params.of().with("market_id", "AAPL"));
        var art = resp.articles(client.objectMapper()).get(0);
        assertEquals(List.of("AAPL", "MSFT"), art.symbol());
        assertEquals(List.of("NASDAQ:AAPL", "NASDAQ:MSFT"), art.marketId());
    }

    @Test
    void articleSymbolAndMarketIdAreNullWhenAbsent() {
        handle("latest", exchange -> respond(exchange, 200,
                successBody("[{\"article_id\":\"a1\",\"title\":\"t\"}]")));
        var client = defaultBuilder().build();
        var art = client.latest(Params.of().with("q", "x"))
                .articles(client.objectMapper()).get(0);
        assertNull(art.symbol());
        assertNull(art.marketId());
    }

    @Test
    void countReturnsAggregateMap() {
        handle("count", exchange -> respond(exchange, 200,
                "{\"status\":\"success\",\"results\":{\"total\":42,\"hour\":{\"00\":1}}}"));
        var client = defaultBuilder().build();
        var resp = client.count(Params.of()
                .with("from_date", "2024-01-01")
                .with("to_date", "2024-01-02"));
        var agg = resp.aggregate(client.objectMapper());
        assertNotNull(agg);
        assertEquals(42, agg.get("total"));
        assertEquals(0, resp.articles(client.objectMapper()).size());
    }

    @Test
    void parseRetryAfterAcceptsIntegerSeconds() {
        assertEquals(7, NewsDataApiClient.parseRetryAfter("7"));
        assertEquals(0, NewsDataApiClient.parseRetryAfter("-1"));
        assertEquals(0, NewsDataApiClient.parseRetryAfter(""));
        assertEquals(0, NewsDataApiClient.parseRetryAfter(null));
    }
}
