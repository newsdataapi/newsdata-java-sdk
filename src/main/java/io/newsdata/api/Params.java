package io.newsdata.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for endpoint parameter maps.
 *
 * <p>Each {@link #with} call drops {@code null} values for ergonomic chaining,
 * so optional fields can be assigned without {@code if (x != null)} guards.
 * The class extends {@link LinkedHashMap} so an instance can be passed
 * anywhere a {@code Map<String, Object>} is expected.
 *
 * <pre>{@code
 * client.latest(Params.of()
 *     .with("q", "bitcoin")
 *     .with("country", List.of("us", "gb"))
 *     .with("language", List.of("en")));
 * }</pre>
 */
public class Params extends LinkedHashMap<String, Object> {
    private static final long serialVersionUID = 1L;

    /** Empty params. */
    public static Params of() {
        return new Params();
    }

    /** Params seeded from an existing map. */
    public static Params from(Map<String, Object> source) {
        Params p = new Params();
        if (source != null) p.putAll(source);
        return p;
    }

    /**
     * Add a key/value. Returns {@code this} for chaining. If {@code value} is
     * {@code null}, the key is left out (no-op).
     */
    public Params with(String key, Object value) {
        if (value != null) put(key, value);
        return this;
    }

    /** Convenience for multi-value string params: same as {@code with(key, List.of(values))}. */
    public Params withList(String key, String... values) {
        if (values != null && values.length > 0) put(key, List.of(values));
        return this;
    }
}
