package io.newsdata.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads a {@code List<String>} that the API may also send as a single string.
 *
 * <p>The v2 list fields ({@code country}, {@code keyword}, {@code ai.tag}, …)
 * are JSON arrays, but the API replaces the whole field with one "not in your
 * plan" notice string when the plan does not include it. Plain list
 * deserialization fails on that; this yields a one-element list instead, which
 * {@link ArticleV2#isPlanNotice(String)} recognises.
 *
 * <p>Package-private: it is an implementation detail of {@link ArticleV2}.
 */
final class StringListDeserializer extends JsonDeserializer<List<String>> {

    @Override
    public List<String> deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode node = parser.readValueAsTree();
        if (node == null || node.isNull()) return Collections.emptyList();

        if (node.isArray()) {
            List<String> out = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                if (item != null && !item.isNull()) out.add(item.asText());
            }
            return Collections.unmodifiableList(out);
        }

        String single = node.asText();
        return single == null || single.isEmpty()
                ? Collections.emptyList()
                : List.of(single);
    }

    /** An absent field reads as an empty list, not {@code null}. */
    @Override
    public List<String> getNullValue(DeserializationContext context) {
        return Collections.emptyList();
    }
}
