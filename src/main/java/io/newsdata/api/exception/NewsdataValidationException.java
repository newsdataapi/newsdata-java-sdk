package io.newsdata.api.exception;

/**
 * A user-provided parameter failed client-side validation. No request was
 * sent.
 */
public class NewsdataValidationException extends NewsdataException {
    private static final long serialVersionUID = 1L;

    /** The offending parameter name, when known. */
    private final String param;

    public NewsdataValidationException(String message) {
        this(message, null);
    }

    public NewsdataValidationException(String message, String param) {
        super(message);
        this.param = param;
    }

    /** The offending parameter name, or {@code null}. */
    public String param() {
        return param;
    }
}
