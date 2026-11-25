package io.newsdata.api;

import java.net.http.HttpHeaders;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Top-level envelope returned by every endpoint.
 *
 * <p>{@code results} is held as a raw {@link JsonNode} because its shape
 * varies by endpoint:
 * <ul>
 *   <li>news endpoints (latest, archive, crypto, market) return an array of articles;
 *   <li>count endpoints return an aggregate object on the final page.
 * </ul>
 * Use {@link #articles(ObjectMapper)} / {@link #articlesV2(ObjectMapper)} /
 * {@link #aggregate(ObjectMapper)} to decode it into a typed shape.
 * {@code articles} decodes the v1 article layout, {@code articlesV2} the v2
 * one; the real-time stream carries v2 articles.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class NewsdataResponse {
    private final String status;
    private final int totalResults;
    private final JsonNode results;
    private final String nextPage;
    private final HttpHeaders headers;

    public NewsdataResponse(String status, int totalResults, JsonNode results, String nextPage,
                            HttpHeaders headers) {
        this.status = status;
        this.totalResults = totalResults;
        this.results = results;
        this.nextPage = nextPage;
        this.headers = headers;
    }

    /** {@code "success"} on a normal response. */
    public String status() { return status; }

    /** Total result count claimed by the API. */
    public int totalResults() { return totalResults; }

    /** Raw {@code results} JSON. Use {@link #articles} or {@link #aggregate}. */
    public JsonNode results() { return results; }

    /** Cursor for the next page, or {@code null} when none. */
    public String nextPage() { return nextPage; }

    /**
     * HTTP response headers, when the client was built with
     * {@code includeHeaders=true}; otherwise {@code null}.
     */
    public HttpHeaders headers() { return headers; }

    /**
     * Decode {@code results} as a list of articles. Returns an empty list
     * when {@code results} is empty or not an array (e.g. a count endpoint's
     * aggregate page).
     */
    public List<Article> articles(ObjectMapper mapper) {
        if (results == null || !results.isArray()) return Collections.emptyList();
        try {
            return mapper.treeToValue(results, mapper.getTypeFactory().constructCollectionType(List.class, Article.class));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Decode {@code results} as a list of v2 articles — the layout of the v2
     * endpoints and of the real-time stream. Returns an empty list when
     * {@code results} is empty or not an array (e.g. a count endpoint's
     * aggregate page).
     *
     * <p>Use {@link #articles(ObjectMapper)} instead for the v1 endpoints,
     * whose articles spell the same data differently ({@code link},
     * {@code pubDate}, {@code article_id}).
     *
     * @param mapper the mapper to decode with
     * @return the articles, never {@code null}
     */
    public List<ArticleV2> articlesV2(ObjectMapper mapper) {
        if (results == null || !results.isArray()) return Collections.emptyList();
        try {
            return mapper.treeToValue(results, mapper.getTypeFactory().constructCollectionType(List.class, ArticleV2.class));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Decode {@code results} as a map (the shape count endpoints return on
     * the final page). Returns {@code null} when {@code results} is not an
     * object.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> aggregate(ObjectMapper mapper) {
        if (results == null || !results.isObject()) return null;
        try {
            return mapper.treeToValue(results, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
