package io.newsdata.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An article's media addresses — the v2 {@code media} group. It replaces v1's
 * {@code image_url} and {@code video_url} fields.
 *
 * <p>Either component is {@code null} on an article without that medium.
 *
 * @param imageUrl lead image
 * @param videoUrl lead video
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArticleV2Media(
        @JsonProperty("image_url") String imageUrl,
        @JsonProperty("video_url") String videoUrl
) {}
