package io.newsdata.api;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.newsdata.api.exception.NewsdataValidationException;

/**
 * Client-side parameter validation and normalization, mirroring the official
 * Python/PHP/Node/Go/Dart clients:
 *
 * <ul>
 *   <li>keys are lowercased (the API is case-insensitive);
 *   <li>{@code null} values are dropped;
 *   <li>{@link Collection}s are comma-joined; booleans become {@code "1"}/{@code "0"};
 *   <li>{@code size} must be an integer within the configured bounds;
 *   <li>{@code sentiment_score} must be numeric and requires {@code sentiment};
 *   <li>mutually-exclusive groups are rejected;
 *   <li>unknown parameters for the endpoint are rejected;
 *   <li>{@code rawQuery}, when present, must be the only parameter and is
 *       parsed and checked against the endpoint's allowed keys.
 * </ul>
 */
final class Validator {
    private Validator() {}

    /**
     * Validate + normalize endpoint parameters. Returns a string-keyed map
     * ready to be URL-encoded.
     *
     * @param endpoint  one of the keys in {@link Constants#FILTERS}
     * @param params    raw user parameters (may include {@code rawQuery})
     */
    static Map<String, String> validateAndEncode(String endpoint, Map<String, Object> params) {
        var allowed = Constants.FILTERS.get(endpoint);
        if (allowed == null) {
            throw new NewsdataValidationException("unknown endpoint: " + endpoint);
        }

        // Lowercase keys; drop nulls.
        var lowered = new LinkedHashMap<String, Object>(params.size());
        for (var entry : params.entrySet()) {
            var value = entry.getValue();
            if (value == null) continue;
            lowered.put(entry.getKey().toLowerCase(), value);
        }

        // rawQuery is mutually exclusive with every other parameter.
        var rawQuery = lowered.remove("rawquery");
        if (rawQuery != null) {
            if (!lowered.isEmpty()) {
                var keys = new ArrayList<>(lowered.keySet());
                Collections.sort(keys);
                throw new NewsdataValidationException(
                        "rawQuery cannot be combined with other parameters; got rawQuery and " + keys,
                        "rawQuery");
            }
            if (!(rawQuery instanceof String s)) {
                throw new NewsdataValidationException("rawQuery must be a string", "rawQuery");
            }
            return parseRawQuery(s, allowed);
        }

        // Count endpoints require an explicit date range.
        if (Constants.REQUIRES_DATE_RANGE.contains(endpoint)) {
            for (var required : List.of("from_date", "to_date")) {
                var v = lowered.get(required);
                if (v == null || (v instanceof String s && s.isEmpty())) {
                    throw new NewsdataValidationException(
                            required + " is required for the " + endpoint + " endpoint",
                            required);
                }
            }
        }

        // Mutually-exclusive groups.
        for (var group : Constants.MUTEX_GROUPS) {
            var present = new ArrayList<String>();
            for (var name : group) {
                if (lowered.containsKey(name)) present.add(name);
            }
            if (present.size() > 1) {
                throw new NewsdataValidationException(
                        "these parameters are mutually exclusive: " + present,
                        present.get(0));
            }
        }

        // sentiment_score requires sentiment.
        if (lowered.containsKey("sentiment_score") && !lowered.containsKey("sentiment")) {
            throw new NewsdataValidationException(
                    "sentiment_score requires sentiment to be set",
                    "sentiment_score");
        }

        // Per-param validation + coercion.
        var out = new LinkedHashMap<String, String>(lowered.size());
        for (var entry : lowered.entrySet()) {
            var name = entry.getKey();
            if (!allowed.contains(name)) {
                throw new NewsdataValidationException(
                        "unsupported parameter for the " + endpoint + " endpoint: " + name,
                        name);
            }
            out.put(name, coerce(name, entry.getValue()));
        }
        return out;
    }

    private static String coerce(String name, Object value) {
        if (Constants.BOOL_PARAMS.contains(name)) return coerceBool(name, value);
        if (Constants.INT_PARAMS.contains(name)) return coerceInt(name, value);
        if (Constants.FLOAT_PARAMS.contains(name)) return coerceFloat(name, value);
        return coerceString(name, value);
    }

    private static String coerceBool(String name, Object value) {
        if (value instanceof Boolean b) return b ? "1" : "0";
        if (value instanceof Integer i) {
            if (i == 0) return "0";
            if (i == 1) return "1";
        }
        if (value instanceof String s) {
            var v = s.trim().toLowerCase();
            if (v.equals("1") || v.equals("true") || v.equals("yes")) return "1";
            if (v.equals("0") || v.equals("false") || v.equals("no")) return "0";
        }
        throw new NewsdataValidationException(name + " must be a boolean", name);
    }

    private static String coerceInt(String name, Object value) {
        int n;
        if (value instanceof Integer i) {
            n = i;
        } else if (value instanceof Long l) {
            n = Math.toIntExact(l);
        } else if (value instanceof String s) {
            try {
                n = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw new NewsdataValidationException(name + " must be an integer", name);
            }
        } else {
            throw new NewsdataValidationException(name + " must be an integer", name);
        }
        if ("size".equals(name) && (n < Constants.SIZE_MIN || n > Constants.SIZE_MAX)) {
            throw new NewsdataValidationException(
                    "size must be between " + Constants.SIZE_MIN + " and " + Constants.SIZE_MAX
                            + " (got " + n + ")",
                    "size");
        }
        return Integer.toString(n);
    }

    private static String coerceFloat(String name, Object value) {
        if (value instanceof Number n) return n.toString();
        if (value instanceof String s) {
            try {
                Double.parseDouble(s);
                return s;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new NewsdataValidationException(name + " must be a number", name);
    }

    private static String coerceString(String name, Object value) {
        if (value instanceof String s) return s;
        if (value instanceof Number n) return n.toString();
        if (value instanceof Collection<?> col) {
            var parts = new ArrayList<String>(col.size());
            for (var item : col) {
                if (item instanceof String s) parts.add(s);
                else if (item instanceof Number n) parts.add(n.toString());
                else throw new NewsdataValidationException(
                            "all items in " + name + " must be strings",
                            name);
            }
            return String.join(",", parts);
        }
        if (value.getClass().isArray()) {
            var parts = new ArrayList<String>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                var item = java.lang.reflect.Array.get(value, i);
                if (item == null) continue;
                parts.add(item.toString());
            }
            return String.join(",", parts);
        }
        throw new NewsdataValidationException(
                name + " must be a String or Collection<String>",
                name);
    }

    /** Parse a rawQuery (query string or full URL) into validated params. */
    private static Map<String, String> parseRawQuery(String rawQuery, java.util.Set<String> allowed) {
        if (rawQuery.isEmpty()) {
            throw new NewsdataValidationException("rawQuery must be a non-empty string", "rawQuery");
        }
        String queryString = rawQuery;
        try {
            var uri = URI.create(rawQuery);
            if (uri.getScheme() != null && uri.getHost() != null && uri.getRawQuery() != null) {
                queryString = uri.getRawQuery();
            }
        } catch (IllegalArgumentException ignored) {
            // Treat the whole string as the query.
        }
        if (queryString.startsWith("?")) queryString = queryString.substring(1);

        var out = new LinkedHashMap<String, String>();
        if (queryString.isEmpty()) return out;
        for (var pair : queryString.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key, value;
            if (eq < 0) {
                key = pair;
                value = "";
            } else {
                key = pair.substring(0, eq);
                value = pair.substring(eq + 1);
            }
            key = URLDecoder.decode(key, StandardCharsets.UTF_8).trim().toLowerCase();
            value = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (key.isEmpty()) continue;
            if (key.equals("apikey")) continue; // supplied by the client
            if (!allowed.contains(key)) {
                throw new NewsdataValidationException(
                        "unknown parameter in rawQuery: " + key, key);
            }
            if (value.isEmpty()) {
                throw new NewsdataValidationException(
                        "parameter " + key + " in rawQuery must have a value", key);
            }
            out.put(key, value);
        }
        return out;
    }
}
