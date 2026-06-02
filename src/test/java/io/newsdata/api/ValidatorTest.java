package io.newsdata.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.newsdata.api.exception.NewsdataValidationException;

class ValidatorTest {

    private static Map<String, Object> m(String k1, Object v1) {
        var m = new HashMap<String, Object>();
        m.put(k1, v1);
        return m;
    }

    private static Map<String, Object> m(String k1, Object v1, String k2, Object v2) {
        var m = new HashMap<String, Object>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    @Test
    void arraysAreCommaJoined() {
        var out = Validator.validateAndEncode("latest", m("country", List.of("us", "gb")));
        assertEquals("us,gb", out.get("country"));
    }

    @Test
    void booleansAreCoercedToFlag() {
        var out = Validator.validateAndEncode("latest",
                m("full_content", true, "image", false));
        assertEquals("1", out.get("full_content"));
        assertEquals("0", out.get("image"));
    }

    @Test
    void keysAreLowercased() {
        var out = Validator.validateAndEncode("latest", m("qInTitle", "hi"));
        assertEquals("hi", out.get("qintitle"));
    }

    @Test
    void nullValuesAreDropped() {
        var out = Validator.validateAndEncode("latest", m("q", "x", "country", null));
        assertEquals("x", out.get("q"));
        assertFalse(out.containsKey("country"));
    }

    @Test
    void sizeUpperBoundRejected() {
        var ex = assertThrows(NewsdataValidationException.class,
                () -> Validator.validateAndEncode("latest", m("size", Constants.SIZE_MAX + 1)));
        assertEquals("size", ex.param());
    }

    @Test
    void sizeWithinBoundsAccepted() {
        var out = Validator.validateAndEncode("latest", m("size", 50));
        assertEquals("50", out.get("size"));
    }

    @Test
    void mutuallyExclusiveParamsRejected() {
        assertThrows(NewsdataValidationException.class,
                () -> Validator.validateAndEncode("latest", m("q", "a", "qInTitle", "b")));
    }

    @Test
    void unknownParameterRejected() {
        var ex = assertThrows(NewsdataValidationException.class,
                () -> Validator.validateAndEncode("latest", m("nope", "x")));
        assertEquals("nope", ex.param());
    }

    @Test
    void cryptoRejectsCountry() {
        assertThrows(NewsdataValidationException.class,
                () -> Validator.validateAndEncode("crypto", m("country", "us")));
    }

    @Test
    void sentimentScoreRequiresSentiment() {
        var ex = assertThrows(NewsdataValidationException.class,
                () -> Validator.validateAndEncode("latest", m("sentiment_score", 0.5)));
        assertEquals("sentiment_score", ex.param());
    }

    @Test
    void sentimentScoreWithSentimentAccepted() {
        var out = Validator.validateAndEncode("latest",
                m("sentiment", "positive", "sentiment_score", 0.5));
        assertEquals("positive", out.get("sentiment"));
        assertEquals("0.5", out.get("sentiment_score"));
    }

    @Test
    void countRequiresDateRange() {
        assertThrows(NewsdataValidationException.class,
                () -> Validator.validateAndEncode("count", m("q", "x")));
    }

    @Test
    void countWithDatesAccepted() {
        var out = Validator.validateAndEncode("count",
                m("from_date", "2024-01-01", "to_date", "2024-01-02"));
        assertEquals("2024-01-01", out.get("from_date"));
        assertEquals("2024-01-02", out.get("to_date"));
    }

    @Test
    void rawQueryParsedAndValidated() {
        var out = Validator.validateAndEncode("latest", m("rawQuery", "q=foo&country=us"));
        assertEquals("foo", out.get("q"));
        assertEquals("us", out.get("country"));
    }

    @Test
    void rawQueryRejectsOtherParams() {
        assertThrows(NewsdataValidationException.class,
                () -> Validator.validateAndEncode("latest",
                        m("rawQuery", "q=foo", "country", "us")));
    }

    @Test
    void rawQueryRejectsUnknownKey() {
        assertThrows(NewsdataValidationException.class,
                () -> Validator.validateAndEncode("latest", m("rawQuery", "bogus=1")));
    }

    @Test
    void rawQueryIgnoresEmbeddedApiKey() {
        var out = Validator.validateAndEncode("latest", m("rawQuery", "apikey=secret&q=foo"));
        assertEquals("foo", out.get("q"));
        assertFalse(out.containsKey("apikey"));
    }

    @Test
    void rawQueryAcceptsFullUrl() {
        var out = Validator.validateAndEncode("latest",
                m("rawQuery", "https://newsdata.io/api/1/latest?q=foo&language=en"));
        assertEquals("foo", out.get("q"));
        assertEquals("en", out.get("language"));
    }

    @Test
    void validationErrorExposesParamName() {
        var ex = assertThrows(NewsdataValidationException.class,
                () -> Validator.validateAndEncode("latest", m("size", 999)));
        assertEquals("size", ex.param());
    }
}
