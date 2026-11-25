package io.newsdata.api;

/**
 * Endpoint identifiers used by {@link NewsDataApiClient} pagination helpers.
 *
 * <p>The string returned by {@link #path()} is the key used internally for
 * filter lookup and request validation; the URL path comes from
 * {@link Constants#ENDPOINT_PATHS}.
 */
public enum Endpoint {
    /** {@code /1/latest} — real-time news. */
    LATEST("latest"),
    /** {@code /1/archive} — historical news. */
    ARCHIVE("archive"),
    /** {@code /1/crypto} — cryptocurrency news. */
    CRYPTO("crypto"),
    /** {@code /1/sources} — available news sources (single page). */
    SOURCES("sources"),
    /** {@code /1/market} — market / financial news. */
    MARKET("market"),
    /** {@code /1/count} — aggregate counts (requires {@code from_date}/{@code to_date}). */
    COUNT("count"),
    /** {@code /1/crypto/count} — aggregate crypto counts. */
    CRYPTO_COUNT("crypto_count"),
    /** {@code /1/market/count} — aggregate market counts. */
    MARKET_COUNT("market_count"),

    /** {@code /2/news} — latest news, or historical with dates. */
    NEWS_V2("news_v2"),
    /** {@code /2/market} — market / financial news. */
    MARKET_V2("market_v2"),
    /** {@code /2/crypto} — cryptocurrency news. */
    CRYPTO_V2("crypto_v2"),
    /** {@code /2/sources} — available news sources (single page). */
    SOURCES_V2("sources_v2"),
    /** {@code /2/count/news} — aggregate news counts. */
    COUNT_NEWS_V2("count_news_v2"),
    /** {@code /2/count/market} — aggregate market counts. */
    COUNT_MARKET_V2("count_market_v2"),
    /** {@code /2/count/crypto} — aggregate crypto counts. */
    COUNT_CRYPTO_V2("count_crypto_v2"),

    /** {@code /2/websocket/register} — register a real-time query. */
    WEBSOCKET_REGISTER("websocket_register"),
    /** {@code /2/websocket/fetch} — list registered real-time queries. */
    WEBSOCKET_FETCH("websocket_fetch"),
    /** {@code /2/websocket/delete} — delete a registered real-time query. */
    WEBSOCKET_DELETE("websocket_delete");

    private final String key;

    Endpoint(String key) {
        this.key = key;
    }

    /** Internal lowercase identifier, also used as the filter map key. */
    public String key() {
        return key;
    }
}
