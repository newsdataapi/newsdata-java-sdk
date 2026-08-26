package io.newsdata.api.exception;

/**
 * The server rejected the WebSocket connection — bad API key, missing
 * WebSocket entitlement, unknown {@code registration_id}, device limit
 * reached, or exhausted quota. Never retried, regardless of the
 * {@code reconnect} setting.
 */
public class NewsdataWebSocketAuthException extends NewsdataWebSocketException {
    private static final long serialVersionUID = 1L;

    public NewsdataWebSocketAuthException(String message) {
        super(message);
    }

    public NewsdataWebSocketAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
