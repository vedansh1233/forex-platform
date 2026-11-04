package com.forex.forex_platform.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base class for business errors that map to a specific HTTP status.
 */
@Getter
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
