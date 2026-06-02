package io.newsdata.api.exception;

/** Raised on 401 / 403 responses (missing, invalid, or unauthorized API key). */
public class NewsdataAuthException extends NewsdataApiException {
    private static final long serialVersionUID = 1L;

    public NewsdataAuthException(String message, int statusCode, String responseBody) {
        super(message, statusCode, responseBody);
    }
}
