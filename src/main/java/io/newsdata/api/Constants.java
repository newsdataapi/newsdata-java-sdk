package io.newsdata.api;

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

    /** API base URL (ends with a slash). */
    public static final String BASE_URL = "https://newsdata.io/api/1/";

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
    public static final Map<String, String> ENDPOINT_PATHS = Map.of(
            "latest", "latest",
            "crypto", "crypto",
            "archive", "archive",
            "sources", "sources",
            "market", "market",
            "count", "count",
            "crypto_count", "crypto/count",
            "market_count", "market/count"
    );

    /** Endpoints that require both {@code from_date} and {@code to_date}. */
    public static final Set<String> REQUIRES_DATE_RANGE =
            Set.of("count", "crypto_count", "market_count");

    /** Parameters sent as boolean flags (coerced to {@code "1"} / {@code "0"}). */
    public static final Set<String> BOOL_PARAMS =
            Set.of("full_content", "image", "video", "removeduplicate");

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
            List.of("domain", "domainurl", "excludedomain")
    );

    /** Per-endpoint accepted parameters (lowercase API names). */
    public static final Map<String, Set<String>> FILTERS = Map.ofEntries(
            Map.entry("latest", Set.of(
                    "q", "qintitle", "qinmeta", "country", "excludecountry", "category",
                    "excludecategory", "language", "excludelanguage", "domain", "domainurl",
                    "excludedomain", "prioritydomain", "timeframe", "timezone", "size",
                    "full_content", "image", "video", "page", "tag", "sentiment", "region",
                    "excludefield", "removeduplicate", "id", "organization", "url", "sort",
                    "creator", "datatype", "sentiment_score"
            )),
            Map.entry("archive", Set.of(
                    "q", "qintitle", "qinmeta", "country", "excludecountry", "category",
                    "excludecategory", "language", "excludelanguage", "domain", "domainurl",
                    "excludedomain", "prioritydomain", "timezone", "size", "full_content",
                    "image", "video", "page", "from_date", "to_date", "excludefield", "id",
                    "url", "sort", "tag", "sentiment", "sentiment_score", "region",
                    "organization", "creator", "datatype", "removeduplicate"
            )),
            Map.entry("crypto", Set.of(
                    "q", "qintitle", "qinmeta", "language", "excludelanguage", "domain",
                    "domainurl", "excludedomain", "prioritydomain", "timeframe", "timezone",
                    "size", "full_content", "image", "video", "page", "tag", "sentiment",
                    "coin", "excludefield", "from_date", "to_date", "removeduplicate", "id",
                    "url", "sort"
            )),
            Map.entry("sources",
                    Set.of("country", "category", "language", "prioritydomain", "domainurl")),
            Map.entry("market", Set.of(
                    "q", "qintitle", "qinmeta", "from_date", "to_date", "country",
                    "excludecountry", "domain", "domainurl", "excludedomain", "language",
                    "excludelanguage", "prioritydomain", "timezone", "timeframe", "size",
                    "full_content", "image", "video", "page", "tag", "sentiment",
                    "excludefield", "removeduplicate", "organization", "market_id", "id", "url",
                    "sort", "creator", "datatype", "sentiment_score"
            )),
            Map.entry("count", Set.of(
                    "from_date", "to_date", "q", "qintitle", "qinmeta", "country",
                    "excludecountry", "category", "excludecategory", "language",
                    "excludelanguage", "domain", "domainurl", "excludedomain", "full_content",
                    "image", "video", "prioritydomain", "page", "size", "sort", "interval",
                    "tag", "sentiment", "sentiment_score", "region", "organization", "creator",
                    "datatype", "removeduplicate"
            )),
            Map.entry("crypto_count", Set.of(
                    "from_date", "to_date", "q", "qintitle", "qinmeta", "language",
                    "excludelanguage", "coin", "domain", "domainurl", "excludedomain",
                    "full_content", "image", "video", "prioritydomain", "page", "sentiment",
                    "size", "sort", "tag", "interval", "removeduplicate"
            )),
            Map.entry("market_count", Set.of(
                    "from_date", "to_date", "q", "qintitle", "qinmeta", "country",
                    "excludecountry", "domain", "domainurl", "excludedomain", "language",
                    "excludelanguage", "full_content", "image", "video", "organization",
                    "market_id", "prioritydomain", "page", "sentiment", "removeduplicate", "size",
                    "sort", "tag", "interval", "creator", "datatype", "sentiment_score"
            ))
    );
}
