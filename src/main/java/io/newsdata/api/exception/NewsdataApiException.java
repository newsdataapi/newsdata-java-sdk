package io.newsdata.api.exception;

/**
 * The API returned a structured error response.
 */
public class NewsdataApiException extends NewsdataException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String responseBody;

    public NewsdataApiException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /** HTTP status returned by the API. */
    public int statusCode() {
        return statusCode;
    }

    /** Raw JSON response body, when available. */
    public String responseBody() {
        return responseBody;
    }
}
