package io.newsdata.api.exception;

/**
 * A real-time WebSocket stream failure
 * (see {@code io.newsdata.api.NewsDataApiWebSocket}).
 */
public class NewsdataWebSocketException extends NewsdataException {
    private static final long serialVersionUID = 1L;

    public NewsdataWebSocketException(String message) {
        super(message);
    }

    public NewsdataWebSocketException(String message, Throwable cause) {
        super(message, cause);
    }
}
