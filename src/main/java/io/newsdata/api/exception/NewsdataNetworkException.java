package io.newsdata.api.exception;

/**
 * A network-level failure (DNS, TLS, timeout, socket error, interrupted call)
 * prevented the request from completing. The underlying cause is available
 * via {@link #getCause()}.
 */
public class NewsdataNetworkException extends NewsdataException {
    private static final long serialVersionUID = 1L;

    public NewsdataNetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
