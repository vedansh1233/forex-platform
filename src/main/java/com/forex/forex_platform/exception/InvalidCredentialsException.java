package com.forex.forex_platform.exception;

import org.springframework.http.HttpStatus;

public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        // Same message for unknown email and wrong password, so accounts can't be enumerated
        super(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }
}
