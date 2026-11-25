package io.newsdata.api;

import io.newsdata.api.exception.NewsdataApiKeyLimitExceededException;
import io.newsdata.api.exception.NewsdataApiLimitExceededException;
import io.newsdata.api.exception.NewsdataRateLimitException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static configuration: base URL, endpoint paths, HTTP defaults, and the
 * per-endpoint accepted-parameter sets. Mirrors the server-side filter
 * mapping and the official Python/PHP/Node/Go/Dart clients.
 *
 * <p>Parameter names are stored lowercase here; user-supplied keys are
 * lowercased before validation (the API is case-insensitive, so
 * {@code qInTitle} and {@code qintitle} are equivalent).
 */
public final class Constants {
    private Constants() {}

    /**
     * API root (ends with a slash). The paths in {@link #ENDPOINT_PATHS} carry
     * the API version, so the v1 and v2 endpoints share one base.
     */
    public static final String BASE_URL = "https://newsdata.io/api/";

    /** Per-request timeout. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Total attempts (1 = no retry). */
    public static final int DEFAULT_MAX_RETRIES = 5;

    /** Base for exponential backoff between retries. */
    public static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(2);

    /** Cap on any single backoff sleep. */
    public static final Duration DEFAULT_RETRY_BACKOFF_MAX = Duration.ofSeconds(60);

    /** Delay between pages when using {@code scrollAll} / {@code paginate}. */
    public static final Duration DEFAULT_PAGINATION_DELAY = Duration.ofSeconds(1);

    /** Allowed {@code size} bounds — the API caps a single response at 50. */
    public static final int SIZE_MIN = 1;
    public static final int SIZE_MAX = 50;

    /** Endpoint key &rarr; URL path appended to {@link #BASE_URL}. */
    public static final Map<String, String> ENDPOINT_PATHS = Map.ofEntries(
            // v1
            Map.entry("latest", "1/latest"),
            Map.entry("crypto", "1/crypto"),
            Map.entry("archive", "1/archive"),
            Map.entry("sources", "1/sources"),
            Map.entry("market", "1/market"),
            Map.entry("count", "1/count"),
            Map.entry("crypto_count", "1/crypto/count"),
            Map.entry("market_count", "1/market/count"),
            // v2
            Map.entry("news_v2", "2/news"),
            Map.entry("market_v2", "2/market"),
            Map.entry("crypto_v2", "2/crypto"),
            Map.entry("sources_v2", "2/sources"),
            Map.entry("count_news_v2", "2/count/news"),
            Map.entry("count_market_v2", "2/count/market"),
            Map.entry("count_crypto_v2", "2/count/crypto"),
            // The real-time service is v2 only.
            Map.entry("websocket_register", "2/websocket/register"),
            Map.entry("websocket_fetch", "2/websocket/fetch"),
            Map.entry("websocket_delete", "2/websocket/delete")
    );

    /** HTTP method per endpoint; anything absent is a {@code GET}. */
    public static final Map<String, String> ENDPOINT_METHODS = Map.of(
            "websocket_register", "POST",
            "websocket_delete", "DELETE"
    );

    /**
     * Endpoints whose success envelope may carry no {@code results} field, so
     * they are exempt from the results-present check applied elsewhere.
     */
    public static final Set<String> RESULTS_OPTIONAL =
            Set.of("websocket_register", "websocket_fetch", "websocket_delete");

    /** Real-time WebSocket endpoint. */
    public static final String WS_BASE_URL = "wss://ws.newsdata.io/ws/event";

    /** The feed a registered query matches against; only news so far. */
    public static final String WS_NEWS_TYPE = "news";

    /** Close code the server uses for a permanent connection rejection. */
    public static final int WS_POLICY_VIOLATION = 1008;

    /** Wait before the first reconnect; doubles after each consecutive failure. */
    public static final Duration WS_RECONNECT_DELAY = Duration.ofSeconds(1);

    /** Upper bound on the reconnect delay. */
    public static final Duration WS_RECONNECT_DELAY_MAX = Duration.ofSeconds(30);

    /** Bound on the opening handshake. */
    public static final Duration WS_HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Error codes on a 429 that waiting cannot fix, so they are never retried.
     * {@code ApiLimitExceeded} and {@code ApiKeyLimitExceeded} mean the API
     * credits are exhausted rather than a transient rate limit — backoff cannot
     * conjure more credits. {@code RegistrationLimitExceeded} means every
     * real-time registration the plan allows is in use; only deleting one frees
     * a slot.
     *
     * <p>{@code ApiLimitExceeded} is the documented code (see the ErrorCode
     * enum in https://newsdata.io/openapi.json); {@code ApiKeyLimitExceeded} is
     * accepted too because the API has been observed to send it and the spec is
     * not exhaustive.
     */
    public static final Set<String> QUOTA_EXHAUSTED_CODES =
            Set.of("ApiLimitExceeded", "ApiKeyLimitExceeded", "RegistrationLimitExceeded");

    /**
     * Error code on a 429 &rarr; the exception to throw. None of them is worth
     * retrying, but the remedy differs: {@code ApiLimitExceeded} is the
     * account's whole credit pool, {@code ApiKeyLimitExceeded} is this one
     * key's own cap, and {@code RegistrationLimitExceeded} is the real-time
     * registration slots — it keeps the base exception, since it is not about
     * credits at all.
     */
    public static final Map<String, QuotaExceptionFactory> QUOTA_EXHAUSTED_ERRORS = Map.of(
            "ApiLimitExceeded", NewsdataApiLimitExceededException::new,
            "ApiKeyLimitExceeded", NewsdataApiKeyLimitExceededException::new,
            "RegistrationLimitExceeded", NewsdataRateLimitException::new
    );

    /** Constructor shape shared by the quota-exhausted exceptions. */
    @FunctionalInterface
    public interface QuotaExceptionFactory {
        /**
         * @param message      the error message from the API
         * @param statusCode   always 429
         * @param responseBody the raw error body, when available
         * @param retryAfter   seconds from {@code Retry-After}, or 0
         * @return the exception to throw
         */
        NewsdataRateLimitException create(String message, int statusCode, String responseBody,
                int retryAfter);
    }

    /** Endpoints that require both {@code from_date} and {@code to_date}. */
    public static final Set<String> REQUIRES_DATE_RANGE =
            Set.of("count", "crypto_count", "market_count");

    /** Parameters sent as boolean flags (coerced to {@code "1"} / {@code "0"}). */
    public static final Set<String> BOOL_PARAMS = Set.of(
            "full_content", "image", "video", "removeduplicate",
            // v2 spellings
            "content", "remove_duplicate");

    /** Parameters that must be integers. */
    public static final Set<String> INT_PARAMS = Set.of("size");

    /** Parameters that must be numeric (int or float). */
    public static final Set<String> FLOAT_PARAMS = Set.of("sentiment_score");

    /**
     * Mutually-exclusive parameter groups. Setting more than one member of a
     * group is rejected before the request is sent.
     */
    public static final List<List<String>> MUTEX_GROUPS = List.of(
            List.of("q", "qintitle", "qinmeta"),
            List.of("country", "excludecountry"),
            List.of("category", "excludecategory"),
            List.of("language", "excludelanguage"),
            List.of("domain", "domainurl", "excludedomain", "excludedomainurl"),
            // v2 names
            List.of("q", "q_in_title", "q_in_meta"),
            List.of("country", "exclude_country"),
            List.of("category", "exclude_category"),
            List.of("language", "exclude_language"),
            List.of("source_url", "exclude_source_url")
    );

    /** Per-endpoint accepted parameters (lowercase API names). */
    public static final Map<String, Set<String>> FILTERS = Map.ofEntries(
            Map.entry("latest", Set.of(
                    "q", "qintitle", "qinmeta", "country", "excludecountry", "category",
                    "excludecategory", "language", "excludelanguage", "domain", "domainurl",
                    "excludedomain", "excludedomainurl", "prioritydomain", "timeframe",
                    "timezone", "size", "full_content", "image", "video", "page", "tag",
                    "sentiment", "region", "excludefield", "removeduplicate", "id",
                    "organization", "url", "sort", "creator", "datatype", "sentiment_score"
            )),
            Map.entry("archive", Set.of(
                    "q", "qintitle", "qinmeta", "country", "excludecountry", "category",
                    "excludecategory", "language", "excludelanguage", "domain", "domainurl",
                    "excludedomain", "excludedomainurl", "prioritydomain", "timezone", "size",
                    "full_content", "image", "video", "page", "from_date", "to_date",
                    "excludefield", "id", "url", "sort", "tag", "sentiment", "sentiment_score",
                    "region", "organization", "creator", "datatype", "removeduplicate"
            )),
            Map.entry("crypto", Set.of(
                    "q", "qintitle", "qinmeta", "language", "excludelanguage", "domain",
                    "domainurl", "excludedomain", "excludedomainurl", "prioritydomain",
                    "timeframe", "timezone", "size", "full_content", "image", "video", "page",
                    "tag", "sentiment", "coin", "excludefield", "from_date", "to_date",
                    "removeduplicate", "id", "url", "sort"
            )),
            Map.entry("sources",
                    Set.of("country", "category", "language", "prioritydomain", "domainurl")),
            Map.entry("market", Set.of(
                    "q", "qintitle", "qinmeta", "from_date", "to_date", "country",
                    "excludecountry", "domain", "domainurl", "excludedomain",
                    "excludedomainurl", "language", "excludelanguage", "prioritydomain",
                    "timezone", "timeframe", "size", "full_content", "image", "video", "page",
                    "tag", "sentiment", "excludefield", "removeduplicate", "organization",
                    "market_id", "id", "url", "sort", "creator", "datatype", "sentiment_score"
            )),
            Map.entry("count", Set.of(
                    "from_date", "to_date", "q", "qintitle", "qinmeta", "country",
                    "excludecountry", "category", "excludecategory", "language",
                    "excludelanguage", "domain", "domainurl", "excludedomain",
                    "excludedomainurl", "full_content", "image", "video", "prioritydomain",
                    "page", "size", "sort", "interval", "tag", "sentiment", "sentiment_score",
                    "region", "organization", "creator", "datatype", "removeduplicate"
            )),
            Map.entry("crypto_count", Set.of(
                    "from_date", "to_date", "q", "qintitle", "qinmeta", "language",
                    "excludelanguage", "coin", "domain", "domainurl", "excludedomain",
                    "excludedomainurl", "full_content", "image", "video", "prioritydomain",
                    "page", "sentiment", "size", "sort", "tag", "interval", "removeduplicate"
            )),
            Map.entry("market_count", Set.of(
                    "from_date", "to_date", "q", "qintitle", "qinmeta", "country",
                    "excludecountry", "domain", "domainurl", "excludedomain",
                    "excludedomainurl", "language", "excludelanguage", "full_content", "image",
                    "video", "organization", "market_id", "prioritydomain", "page",
                    "sentiment", "removeduplicate", "size", "sort", "tag", "interval",
                    "creator", "datatype", "sentiment_score"
            )),

            // ---- v2 ------------------------------------------------------
            // v2 renames the filter vocabulary to snake_case and folds
            // domain/domainurl/excludedomain/excludedomainurl into
            // source_url/exclude_source_url.
            Map.entry("news_v2", Set.of(
                    "q", "q_in_title", "q_in_meta", "country", "exclude_country", "category",
                    "exclude_category", "language", "exclude_language", "source_url",
                    "exclude_source_url", "source_priority", "from_date", "to_date",
                    "timeframe", "timezone", "size", "content", "image", "video", "page",
                    "tag", "sentiment", "sentiment_score", "region", "organization", "creator",
                    "datatype", "exclude_field", "remove_duplicate", "id", "url", "sort"
            )),
            Map.entry("market_v2", Set.of(
                    "q", "q_in_title", "q_in_meta", "country", "exclude_country", "language",
                    "exclude_language", "source_url", "exclude_source_url", "source_priority",
                    "from_date", "to_date", "timeframe", "timezone", "size", "content",
                    "image", "video", "page", "tag", "sentiment", "sentiment_score",
                    "organization", "market_id", "creator", "datatype", "exclude_field",
                    "remove_duplicate", "id", "url", "sort"
            )),
            Map.entry("crypto_v2", Set.of(
                    "q", "q_in_title", "q_in_meta", "coin", "language", "exclude_language",
                    "source_url", "exclude_source_url", "source_priority", "from_date",
                    "to_date", "timeframe", "timezone", "size", "content", "image", "video",
                    "page", "tag", "sentiment", "exclude_field", "remove_duplicate", "id",
                    "url", "sort"
            )),
            Map.entry("sources_v2", Set.of(
                    "country", "category", "language", "source_priority", "source_url"
            )),
            Map.entry("count_news_v2", Set.of(
                    "q", "q_in_title", "q_in_meta", "country", "exclude_country", "category",
                    "exclude_category", "language", "exclude_language", "source_url",
                    "exclude_source_url", "source_priority", "from_date", "to_date",
                    "timeframe", "interval", "size", "content", "image", "video", "page",
                    "tag", "sentiment", "sentiment_score", "region", "organization", "creator",
                    "datatype", "remove_duplicate", "sort"
            )),
            Map.entry("count_market_v2", Set.of(
                    "q", "q_in_title", "q_in_meta", "country", "exclude_country", "language",
                    "exclude_language", "source_url", "exclude_source_url", "source_priority",
                    "from_date", "to_date", "timeframe", "interval", "size", "content",
                    "image", "video", "page", "tag", "sentiment", "sentiment_score",
                    "organization", "market_id", "creator", "datatype", "remove_duplicate",
                    "sort"
            )),
            Map.entry("count_crypto_v2", Set.of(
                    "q", "q_in_title", "q_in_meta", "coin", "language", "exclude_language",
                    "source_url", "exclude_source_url", "source_priority", "from_date",
                    "to_date", "timeframe", "interval", "size", "content", "image", "video",
                    "page", "tag", "sentiment", "remove_duplicate", "sort"
            )),

            // Real-time query registration. The real-time service is v2 only,
            // so these are the v2 filter names. No date or paging filters apply
            // — a registered query matches news as it is published.
            // `news_type` is set by websocketRegister, not by the caller.
            Map.entry("websocket_register", Set.of(
                    "q", "q_in_title", "q_in_meta", "country", "exclude_country",
                    "category", "exclude_category", "language", "exclude_language",
                    "source_url", "exclude_source_url", "source_priority", "timezone",
                    "content", "image", "video", "remove_duplicate", "tag", "sentiment",
                    "sentiment_score", "region", "organization", "creator", "datatype",
                    "exclude_field", "news_type"
            )),
            Map.entry("websocket_fetch", Set.<String>of()),
            Map.entry("websocket_delete", Set.of("registration_id"))
    );
}
