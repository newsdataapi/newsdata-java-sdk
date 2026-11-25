package io.newsdata.api;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads an {@code Integer} the API may send as a number or as a quoted number.
 *
 * <p>A value that is neither (a plan notice, say) reads as {@code null} rather
 * than failing the whole article.
 *
 * <p>Package-private: it is an implementation detail of
 * {@link ArticleV2Source}.
 */
final class LenientIntDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode node = parser.readValueAsTree();
        if (node == null || node.isNull()) return null;

        if (node.isNumber()) return node.asInt();

        if (node.isTextual()) {
            try {
                return Integer.valueOf(node.asText().strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
