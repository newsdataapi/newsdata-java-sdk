package io.newsdata.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * The platform's AI enrichment of an article — the v2 {@code ai} group.
 *
 * <p>It replaces v1's {@code sentiment}, {@code sentiment_stats},
 * {@code ai_tag}, {@code ai_region} and {@code ai_org} fields, and adds
 * {@link #summary()}.
 *
 * <p>Components are {@code null} or empty on articles the platform did not
 * enrich. When your plan does not include a field, it holds the API's notice
 * string instead of data — see {@link ArticleV2#isPlanNotice(String)}.
 *
 * @param summary      a one-or-two-sentence summary of the article
 * @param sentiment    sentiment label and per-class confidence
 * @param tag          topic tags, e.g. {@code ["banking and finance"]}
 * @param region       places the article relates to, each a comma-separated
 *                     path from the narrowest to the widest
 *                     ({@code "mumbai,maharashtra,india"}) — entries contain
 *                     commas themselves, so do not split them
 * @param organization organisations mentioned
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArticleV2Ai(
        @JsonProperty("summary") String summary,
        @JsonProperty("sentiment") ArticleV2Sentiment sentiment,
        @JsonProperty("tag") @JsonDeserialize(using = StringListDeserializer.class) List<String> tag,
        @JsonProperty("region") @JsonDeserialize(using = StringListDeserializer.class) List<String> region,
        @JsonProperty("organization") @JsonDeserialize(using = StringListDeserializer.class) List<String> organization
) {}
