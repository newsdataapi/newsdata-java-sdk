package io.newsdata.api.exception;

/**
 * Raised on 429 responses once retries are exhausted.
 *
 * <p>{@link #retryAfter()} is the number of seconds the {@code Retry-After}
 * header asked for, or {@code 0} when the header was missing or unparseable.
 */
public class NewsdataRateLimitException extends NewsdataApiException {
    private static final long serialVersionUID = 1L;

    private final int retryAfter;

    public NewsdataRateLimitException(String message, int statusCode, String responseBody, int retryAfter) {
        super(message, statusCode, responseBody);
        this.retryAfter = retryAfter;
    }

    /** Seconds to wait before retrying, or {@code 0} when not provided. */
    public int retryAfter() {
        return retryAfter;
    }
}
