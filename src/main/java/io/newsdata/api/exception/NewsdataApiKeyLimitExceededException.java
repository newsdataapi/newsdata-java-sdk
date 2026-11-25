package io.newsdata.api.exception;

/**
 * Raised on 429 when this specific API key's own credit cap is
 * exhausted, even though the account's overall pool may still have credits
 * left.
 *
 * <p>Never retried — waiting out the backoff cannot conjure more credits.
 * Extends {@link NewsdataRateLimitException}, so an existing {@code catch}
 * on that type keeps working.
 */
public class NewsdataApiKeyLimitExceededException extends NewsdataRateLimitException {
    private static final long serialVersionUID = 1L;

    public NewsdataApiKeyLimitExceededException(String message, int statusCode, String responseBody, int retryAfter) {
        super(message, statusCode, responseBody, retryAfter);
    }
}
