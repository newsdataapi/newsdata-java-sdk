package io.newsdata.api.exception;

/** Raised on 5xx responses once retries are exhausted. */
public class NewsdataServerException extends NewsdataApiException {
    private static final long serialVersionUID = 1L;

    public NewsdataServerException(String message, int statusCode, String responseBody) {
        super(message, statusCode, responseBody);
    }
}
