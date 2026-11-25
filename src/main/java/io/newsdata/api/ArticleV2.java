package io.newsdata.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * One news article from API v2.
 *
 * <p>v2 renamed and regrouped the v1 fields: {@code article_id} became
 * {@code id}, {@code link} became {@code url}, {@code pubDate} became
 * {@code published_at}, {@code description} became {@code meta_description},
 * and the source, media and AI fields moved into the {@link #source()},
 * {@link #media()} and {@link #ai()} groups. Use {@code ArticleV2} for the v2
 * endpoints and the real-time stream; use {@link Article} for the v1 endpoints.
 *
 * <p>This is a Java 17 record. Field accessors are component names without the
 * {@code get} prefix — e.g. {@code article.url()}, not
 * {@code article.getUrl()}.
 *
 * <p>JSON keys are the API's own field paths — the same names
 * {@code exclude_field} accepts and the same names the NewsData documentation
 * uses, so one vocabulary covers parameters, results and docs.
 *
 * <p>A field your plan does not include holds the API's notice string instead
 * of data; see {@link #isPlanNotice(String)}.
 *
 * @param id              the article's stable identifier (v1: {@code article_id})
 * @param title           headline
 * @param url             address of the article (v1: {@code link})
 * @param metaDescription the publisher's summary (v1: {@code description})
 * @param content         full text, when the plan and the request include it
 * @param publishedAt     publication time, UTC ISO-8601 (v1: {@code pubDate})
 * @param fetchedAt       when the platform fetched the article, UTC ISO-8601
 * @param language        language name, e.g. {@code "english"}
 * @param dataType        feed the article came from, e.g. {@code "news"}
 * @param duplicate       whether the platform considers it a duplicate
 * @param country         countries the article relates to
 * @param category        categories the article was classified into
 * @param keyword         publisher keywords (v1: {@code keywords})
 * @param creator         bylines
 * @param coin            cryptocurrencies mentioned (crypto endpoints)
 * @param ticker          stock tickers mentioned (v1: {@code symbol})
 * @param marketId        market identifiers (market endpoints)
 * @param source          the publisher the article came from
 * @param media           image and video addresses
 * @param ai              the platform's AI enrichment
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArticleV2(
        @JsonProperty("id") String id,
        @JsonProperty("title") String title,
        @JsonProperty("url") String url,
        @JsonProperty("meta_description") String metaDescription,
        @JsonProperty("content") String content,
        @JsonProperty("published_at") String publishedAt,
        @JsonProperty("fetched_at") String fetchedAt,
        @JsonProperty("language") String language,
        @JsonProperty("datatype") String dataType,
        @JsonProperty("duplicate") Boolean duplicate,
        @JsonProperty("country") @JsonDeserialize(using = StringListDeserializer.class) List<String> country,
        @JsonProperty("category") @JsonDeserialize(using = StringListDeserializer.class) List<String> category,
        @JsonProperty("keyword") @JsonDeserialize(using = StringListDeserializer.class) List<String> keyword,
        @JsonProperty("creator") @JsonDeserialize(using = StringListDeserializer.class) List<String> creator,
        @JsonProperty("coin") @JsonDeserialize(using = StringListDeserializer.class) List<String> coin,
        @JsonProperty("ticker") @JsonDeserialize(using = StringListDeserializer.class) List<String> ticker,
        @JsonProperty("market_id") @JsonDeserialize(using = StringListDeserializer.class) List<String> marketId,
        @JsonProperty("source") ArticleV2Source source,
        @JsonProperty("media") ArticleV2Media media,
        @JsonProperty("ai") ArticleV2Ai ai
) {
    /**
     * Prefix of the string the API sends in place of a field your plan does
     * not include ({@code "ONLY AVAILABLE IN PAID PLANS"},
     * {@code "ONLY AVAILABLE IN PROFESSIONAL AND CORPORATE PLANS"},
     * {@code "ONLY AVAILABLE IN CORPORATE PLANS"}).
     */
    static final String PLAN_NOTICE_PREFIX = "ONLY AVAILABLE IN";

    /**
     * Whether a value is the API's "not in your plan" notice rather than real
     * data. The notice arrives in the field itself, so a withheld list field
     * holds the single notice string and a withheld sentiment carries it in
     * {@link ArticleV2Sentiment#label()}.
     *
     * @param value a field value, possibly {@code null}
     * @return {@code true} when the value is a plan notice
     */
    public static boolean isPlanNotice(String value) {
        return value != null && value.strip().toUpperCase().startsWith(PLAN_NOTICE_PREFIX);
    }
}
