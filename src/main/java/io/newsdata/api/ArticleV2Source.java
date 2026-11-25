package io.newsdata.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * The publisher an article came from — the v2 {@code source} group.
 *
 * <p>It replaces v1's {@code source_id}, {@code source_name},
 * {@code source_url}, {@code source_icon} and {@code source_priority} fields.
 *
 * @param name     publisher name, e.g. {@code "Example News"}
 * @param url      the publisher's home page
 * @param iconUrl  the publisher's icon
 * @param priority the platform's source-priority rank (lower ranks first), or
 *                 {@code null} when the API did not send a usable number
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArticleV2Source(
        @JsonProperty("name") String name,
        @JsonProperty("url") String url,
        @JsonProperty("icon_url") String iconUrl,
        @JsonProperty("priority") @JsonDeserialize(using = LenientIntDeserializer.class) Integer priority
) {}
