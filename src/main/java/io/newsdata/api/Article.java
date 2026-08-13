package io.newsdata.api;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One news article from the Newsdata.io API.
 *
 * <p>This is a Java 17 record. Field accessors are component names without
 * the {@code get} prefix — e.g. {@code article.title()}, not
 * {@code article.getTitle()}.
 *
 * <p>JSON keys use the API's snake_case; component names are camelCase.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Article(
        @JsonProperty("article_id") String articleId,
        @JsonProperty("title") String title,
        @JsonProperty("link") String link,
        @JsonProperty("description") String description,
        @JsonProperty("content") String content,
        @JsonProperty("keywords") List<String> keywords,
        @JsonProperty("creator") List<String> creator,
        @JsonProperty("video_url") String videoUrl,
        @JsonProperty("image_url") String imageUrl,
        @JsonProperty("pubDate") String pubDate,
        @JsonProperty("pubDateTZ") String pubDateTZ,
        @JsonProperty("source_id") String sourceId,
        @JsonProperty("source_priority") Integer sourcePriority,
        @JsonProperty("source_url") String sourceUrl,
        @JsonProperty("source_icon") String sourceIcon,
        @JsonProperty("source_name") String sourceName,
        @JsonProperty("language") String language,
        @JsonProperty("country") List<String> country,
        @JsonProperty("category") List<String> category,
        @JsonProperty("ai_tag") List<String> aiTag,
        @JsonProperty("ai_region") List<String> aiRegion,
        @JsonProperty("ai_org") List<String> aiOrg,
        @JsonProperty("sentiment") String sentiment,
        @JsonProperty("sentiment_stats") Map<String, Object> sentimentStats,
        @JsonProperty("datatype") String dataType,
        @JsonProperty("symbol") List<String> symbol,
        @JsonProperty("market_id") List<String> marketId
) {}
