package com.cleveft.transcriptionservice.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /**
     * The request is valid and the caller is authenticated — they have simply
     * exhausted what their tier allows. 402 rather than 403 so the client can
     * tell "you may not do this" from "upgrade and you may".
     */
    public static ApiException quotaExceeded(String message) {
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, message);
    }
}
