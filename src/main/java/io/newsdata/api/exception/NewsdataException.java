package io.newsdata.api.exception;

/**
 * Base class for every exception raised by the Newsdata.io SDK.
 *
 * <p>Unchecked (extends {@link RuntimeException}) so callers don't have to
 * pepper {@code throws} clauses through their code. Catch this for a
 * catch-all; catch a subclass to react to specific failure modes.
 */
public class NewsdataException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NewsdataException(String message) {
        super(message);
    }

    public NewsdataException(String message, Throwable cause) {
        super(message, cause);
    }
}
