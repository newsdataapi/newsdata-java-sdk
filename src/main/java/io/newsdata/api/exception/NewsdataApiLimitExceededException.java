package io.newsdata.api.exception;

/**
 * Raised on 429 when the account's pool of API credits is exhausted,
 * rather than a transient rate limit having been hit.
 *
 * <p>Never retried — waiting out the backoff cannot conjure more credits.
 * Extends {@link NewsdataRateLimitException}, so an existing {@code catch}
 * on that type keeps working.
 */
public class NewsdataApiLimitExceededException extends NewsdataRateLimitException {
    private static final long serialVersionUID = 1L;

    public NewsdataApiLimitExceededException(String message, int statusCode, String responseBody, int retryAfter) {
        super(message, statusCode, responseBody, retryAfter);
    }
}
