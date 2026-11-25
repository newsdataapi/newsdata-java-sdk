package io.newsdata.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * An article's sentiment classification: a {@link #label()}
 * ({@code "positive"}, {@code "neutral"}, {@code "negative"}) and the
 * per-class confidence {@link #score()}.
 *
 * <p>{@code score} is a map rather than three components so a class the
 * platform adds later is still decoded; the keys today are {@code "negative"},
 * {@code "neutral"} and {@code "positive"}.
 *
 * <p>When your plan does not include sentiment, the API sends its notice
 * string in place of the object. It is decoded into {@link #label()} with an
 * empty score, where {@link ArticleV2#isPlanNotice(String)} recognises it.
 *
 * @param label the winning class, or a plan notice
 * @param score confidence per class, as percentages; never {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArticleV2Sentiment(String label, Map<String, Double> score) {

    /** Normalises {@code score} to an unmodifiable, non-null map. */
    public ArticleV2Sentiment {
        score = score == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(score));
    }

    /**
     * Decodes either the sentiment object or the bare notice string the API
     * sends when the plan does not include sentiment.
     *
     * @param node the raw {@code ai.sentiment} JSON
     * @return the decoded sentiment, or {@code null} when there was none
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static ArticleV2Sentiment fromJson(JsonNode node) {
        if (node == null || node.isNull()) return null;

        if (node.isTextual()) {
            return new ArticleV2Sentiment(node.asText(), null);
        }
        if (!node.isObject()) return null;

        String label = node.hasNonNull("label") ? node.get("label").asText() : null;

        Map<String, Double> score = new LinkedHashMap<>();
        JsonNode scoreNode = node.get("score");
        if (scoreNode != null && scoreNode.isObject()) {
            scoreNode.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value != null && value.isNumber()) {
                    score.put(entry.getKey(), value.asDouble());
                }
            });
        }
        return new ArticleV2Sentiment(label, score);
    }
}
