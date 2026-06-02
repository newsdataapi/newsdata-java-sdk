package io.newsdata.api;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import io.newsdata.api.exception.NewsdataApiException;
import io.newsdata.api.exception.NewsdataAuthException;
import io.newsdata.api.exception.NewsdataException;
import io.newsdata.api.exception.NewsdataNetworkException;
import io.newsdata.api.exception.NewsdataRateLimitException;
import io.newsdata.api.exception.NewsdataServerException;
import io.newsdata.api.exception.NewsdataValidationException;

/**
 * Synchronous HTTP client for the Newsdata.io REST API.
 *
 * <p>Construct via {@link #builder()}. Methods are safe for concurrent use
 * by multiple threads. All endpoint methods accept a
 * {@code Map<String, Object>} (use {@link Params} for fluent construction)
 * and return a {@link NewsdataResponse}; failures throw subclasses of
 * {@link NewsdataException}.
 *
 * <pre>{@code
 * NewsDataApiClient client = NewsDataApiClient.builder()
 *     .apiKey(System.getenv("NEWSDATA_API_KEY"))
 *     .build();
 *
 * NewsdataResponse resp = client.latest(Params.of()
 *     .with("q", "bitcoin")
 *     .with("country", List.of("us", "gb"))
 *     .with("language", List.of("en")));
 *
 * for (Article a : resp.articles(client.objectMapper())) {
 *     System.out.println(a.title());
 * }
 * }</pre>
 */
public final class NewsDataApiClient {
    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration retryBackoff;
    private final Duration retryBackoffMax;
    private final Duration paginationDelay;
    private final boolean includeHeaders;
    private final HttpClient httpClient;
    private final BiConsumer<String, String> logger;
    private final ObjectMapper objectMapper;

    private NewsDataApiClient(Builder b) {
        if (b.apiKey == null || b.apiKey.isEmpty()) {
            throw new NewsdataValidationException("apiKey must be a non-empty string", "apiKey");
        }
        this.apiKey = b.apiKey;
        this.baseUrl = b.baseUrl.endsWith("/") ? b.baseUrl : b.baseUrl + "/";
        this.timeout = b.timeout;
        this.maxRetries = Math.max(1, b.maxRetries);
        this.retryBackoff = b.retryBackoff;
        this.retryBackoffMax = b.retryBackoffMax;
        this.paginationDelay = b.paginationDelay;
        this.includeHeaders = b.includeHeaders;
        this.httpClient = b.httpClient != null
                ? b.httpClient
                : HttpClient.newBuilder().connectTimeout(b.timeout).build();
        this.logger = b.logger;
        this.objectMapper = new ObjectMapper();
    }

    /** Construct a builder. {@code apiKey} is required; everything else has sensible defaults. */
    public static Builder builder() { return new Builder(); }

    /** The Jackson ObjectMapper used to decode responses. */
    public ObjectMapper objectMapper() { return objectMapper; }

    // ---- endpoint methods ------------------------------------------------

    /** Real-time news. GET /1/latest. */
    public NewsdataResponse latest(Map<String, Object> params) {
        return request(Endpoint.LATEST.key(), params);
    }

    /** Historical news. GET /1/archive. */
    public NewsdataResponse archive(Map<String, Object> params) {
        return request(Endpoint.ARCHIVE.key(), params);
    }

    /** Cryptocurrency news. GET /1/crypto. */
    public NewsdataResponse crypto(Map<String, Object> params) {
        return request(Endpoint.CRYPTO.key(), params);
    }

    /** Available news sources. Single-page endpoint. GET /1/sources. */
    public NewsdataResponse sources(Map<String, Object> params) {
        return request(Endpoint.SOURCES.key(), params);
    }

    /** Market / financial news. GET /1/market. */
    public NewsdataResponse market(Map<String, Object> params) {
        return request(Endpoint.MARKET.key(), params);
    }

    /** Aggregate news counts. Requires {@code from_date} and {@code to_date}. GET /1/count. */
    public NewsdataResponse count(Map<String, Object> params) {
        return request(Endpoint.COUNT.key(), params);
    }

    /** Aggregate crypto counts. Requires {@code from_date} and {@code to_date}. */
    public NewsdataResponse cryptoCount(Map<String, Object> params) {
        return request(Endpoint.CRYPTO_COUNT.key(), params);
    }

    /** Aggregate market counts. Requires {@code from_date} and {@code to_date}. */
    public NewsdataResponse marketCount(Map<String, Object> params) {
        return request(Endpoint.MARKET_COUNT.key(), params);
    }

    // ---- pagination ------------------------------------------------------

    /**
     * Follow {@code nextPage} cursors and return one merged response, capped
     * at {@code maxResults} articles (0 = no cap, follow to exhaustion).
     */
    public NewsdataResponse scrollAll(Endpoint endpoint, Map<String, Object> params, int maxResults) {
        if (endpoint == Endpoint.SOURCES) {
            throw new NewsdataValidationException(
                    "scrollAll is not supported for the sources endpoint");
        }
        Map<String, Object> req = new LinkedHashMap<>(params);
        ArrayNode merged = objectMapper.createArrayNode();
        NewsdataResponse last = null;
        int total = 0;
        for (;;) {
            NewsdataResponse resp = request(endpoint.key(), req);
            last = resp;
            total = resp.totalResults() != 0 ? resp.totalResults() : total;
            JsonNode results = resp.results();
            if (results != null && results.isArray()) {
                for (JsonNode item : results) merged.add(item);
            }
            String nextPage = resp.nextPage();
            if (maxResults > 0 && merged.size() >= maxResults) {
                while (merged.size() > maxResults) merged.remove(merged.size() - 1);
                nextPage = null;
            }
            if (nextPage == null || nextPage.isEmpty()) break;
            req.put("page", nextPage);
            sleep(paginationDelay);
        }
        JsonNode resultsOut = merged.size() > 0 ? merged : (last != null ? last.results() : null);
        return new NewsdataResponse(
                "success",
                total,
                resultsOut,
                null,
                includeHeaders && last != null ? last.headers() : null);
    }

    /**
     * Lazy {@link Stream} that yields one {@link NewsdataResponse} per page.
     * Composes with {@code .limit(n)}, {@code .takeWhile(...)}, etc.
     */
    public Stream<NewsdataResponse> paginate(Endpoint endpoint, Map<String, Object> params) {
        if (endpoint == Endpoint.SOURCES) {
            throw new NewsdataValidationException(
                    "paginate is not supported for the sources endpoint");
        }
        Iterator<NewsdataResponse> it = new Iterator<>() {
            private final Map<String, Object> req = new LinkedHashMap<>(params);
            private NewsdataResponse next;
            private boolean done = false;
            private boolean primed = false;

            @Override
            public boolean hasNext() {
                // Don't short-circuit on `done` first — advance() may have
                // staged the final element before flipping done=true.
                if (!primed && !done) {
                    advance();
                    primed = true;
                }
                return next != null;
            }

            @Override
            public NewsdataResponse next() {
                if (!hasNext()) throw new NoSuchElementException();
                NewsdataResponse out = next;
                next = null;
                primed = false;
                return out;
            }

            private void advance() {
                NewsdataResponse resp = request(endpoint.key(), req);
                next = resp;
                // Count endpoints return an object on the final page.
                JsonNode results = resp.results();
                if (results != null && results.isObject()) {
                    done = true;
                    return;
                }
                String nextPage = resp.nextPage();
                if (nextPage == null || nextPage.isEmpty()) {
                    done = true;
                    return;
                }
                req.put("page", nextPage);
                sleep(paginationDelay);
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED),
                false);
    }

    // ---- internals -------------------------------------------------------

    /** Visible for tests. */
    NewsdataResponse request(String endpoint, Map<String, Object> params) {
        Map<String, String> encoded = Validator.validateAndEncode(endpoint, params);
        encoded.put("apikey", apiKey);
        String path = Constants.ENDPOINT_PATHS.get(endpoint);
        String url = baseUrl + path + "?" + buildQuery(encoded);
        String logUrl = redactApiKey(url);

        Exception last = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log("info", "GET " + logUrl + " (attempt " + attempt + "/" + maxRetries + ")");
            HttpResponse<String> resp;
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(timeout)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                resp = httpClient.send(req, BodyHandlers.ofString());
            } catch (IOException e) {
                last = e;
                if (attempt >= maxRetries) {
                    throw new NewsdataNetworkException("network error after " + maxRetries + " attempts: " + e.getMessage(), e);
                }
                log("warn", "network error: " + e.getMessage());
                sleep(backoff(attempt));
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NewsdataNetworkException("interrupted", e);
            }

            int status = resp.statusCode();
            String body = resp.body();
            JsonNode parsed;
            try {
                parsed = body == null || body.isEmpty() ? null : objectMapper.readTree(body);
            } catch (IOException e) {
                if (status >= 500 && attempt < maxRetries) {
                    log("warn", "non-JSON response (status " + status + ")");
                    sleep(backoff(attempt));
                    continue;
                }
                throw new NewsdataApiException(
                        "non-JSON response from API (status " + status + ")", status, body);
            }

            if (status == 200 && parsed != null && parsed.path("status").asText("").equals("success")
                    && !parsed.path("results").isNull() && !parsed.path("results").isMissingNode()) {
                return new NewsdataResponse(
                        "success",
                        parsed.path("totalResults").asInt(0),
                        parsed.get("results"),
                        parsed.hasNonNull("nextPage") ? parsed.get("nextPage").asText() : null,
                        includeHeaders ? resp.headers() : null);
            }

            String message = errorMessage(parsed, status);

            if (status == 429) {
                int retryAfter = parseRetryAfter(resp.headers().firstValue("retry-after").orElse(null));
                if (attempt >= maxRetries) {
                    throw new NewsdataRateLimitException(message, 429, body, retryAfter);
                }
                Duration wait = retryAfter > 0 ? Duration.ofSeconds(retryAfter) : backoff(attempt);
                log("warn", "429 rate limit; sleeping " + wait.toMillis() + "ms");
                sleep(wait);
                continue;
            }

            if (status >= 500) {
                if (attempt >= maxRetries) {
                    throw new NewsdataServerException(message, status, body);
                }
                log("warn", status + " server error");
                sleep(backoff(attempt));
                continue;
            }

            if (status == 401 || status == 403) {
                throw new NewsdataAuthException(message, status, body);
            }

            // Other 4xx — not retried.
            throw new NewsdataApiException(message, status, body);
        }
        // Defensive — loop returns or throws above.
        throw new NewsdataException("request to " + endpoint + " did not complete (maxRetries=" + maxRetries + ", lastError=" + last + ")");
    }

    private Duration backoff(int attempt) {
        long ms = retryBackoff.toMillis() * (long) Math.pow(2, attempt - 1);
        long capped = ms > retryBackoffMax.toMillis() || ms <= 0 ? retryBackoffMax.toMillis() : ms;
        return Duration.ofMillis(capped);
    }

    private void sleep(Duration d) {
        long ms = d.toMillis();
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NewsdataNetworkException("interrupted", e);
        }
    }

    private void log(String level, String message) {
        if (logger != null) logger.accept(level, "[newsdataapi] " + message);
    }

    private String errorMessage(JsonNode body, int status) {
        if (body != null) {
            JsonNode results = body.get("results");
            if (results != null && results.isObject() && results.hasNonNull("message")) {
                return results.get("message").asText();
            }
            if (body.hasNonNull("message")) return body.get("message").asText();
        }
        return "API request failed with HTTP " + status;
    }

    private static String buildQuery(Map<String, String> params) {
        var parts = new ArrayList<String>(params.size());
        for (var entry : params.entrySet()) {
            parts.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return String.join("&", parts);
    }

    private static final Pattern APIKEY_RE =
            Pattern.compile("(apikey=)[^&]*", Pattern.CASE_INSENSITIVE);

    /** Replace the {@code apikey} parameter's value with {@code REDACTED}. */
    public static String redactApiKey(String url) {
        Matcher m = APIKEY_RE.matcher(url);
        return m.replaceAll("$1REDACTED");
    }

    /** Parse a {@code Retry-After} header (integer seconds or HTTP-date) into seconds. */
    static int parseRetryAfter(String value) {
        if (value == null) return 0;
        value = value.trim();
        if (value.isEmpty()) return 0;
        try {
            int seconds = Integer.parseInt(value);
            return Math.max(0, seconds);
        } catch (NumberFormatException ignored) {
            // fall through to date parse
        }
        try {
            var when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            long diff = Duration.between(ZonedDateTime.now(when.getZone()), when).getSeconds();
            return diff < 0 ? 0 : (int) diff;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- builder ---------------------------------------------------------

    /** Fluent builder for {@link NewsDataApiClient}. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl = Constants.BASE_URL;
        private Duration timeout = Constants.DEFAULT_REQUEST_TIMEOUT;
        private int maxRetries = Constants.DEFAULT_MAX_RETRIES;
        private Duration retryBackoff = Constants.DEFAULT_RETRY_BACKOFF;
        private Duration retryBackoffMax = Constants.DEFAULT_RETRY_BACKOFF_MAX;
        private Duration paginationDelay = Constants.DEFAULT_PAGINATION_DELAY;
        private boolean includeHeaders = false;
        private HttpClient httpClient;
        private BiConsumer<String, String> logger;

        /** Your Newsdata.io API key (required). */
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }

        /** Override the base URL (e.g. for a staging environment). */
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }

        /** Per-request timeout. Default: 30 seconds. */
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }

        /** Total attempts (1 = no retry). Default: 5. */
        public Builder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }

        /** Base for exponential backoff. Default: 2s. */
        public Builder retryBackoff(Duration d) { this.retryBackoff = d; return this; }

        /** Cap on any single backoff sleep. Default: 60s. */
        public Builder retryBackoffMax(Duration d) { this.retryBackoffMax = d; return this; }

        /** Delay between pages in {@code scrollAll} / {@code paginate}. Default: 1s. */
        public Builder paginationDelay(Duration d) { this.paginationDelay = d; return this; }

        /** Attach the response headers to each {@link NewsdataResponse}. */
        public Builder includeHeaders(boolean b) { this.includeHeaders = b; return this; }

        /** Inject a custom {@link HttpClient} (proxies, mTLS, etc.). */
        public Builder httpClient(HttpClient client) { this.httpClient = client; return this; }

        /**
         * Receive log lines as {@code (level, message)} pairs. Levels are
         * {@code "info"} / {@code "warn"}. The API key is redacted before
         * logging.
         */
        public Builder logger(BiConsumer<String, String> logger) { this.logger = logger; return this; }

        public NewsDataApiClient build() { return new NewsDataApiClient(this); }
    }
}
