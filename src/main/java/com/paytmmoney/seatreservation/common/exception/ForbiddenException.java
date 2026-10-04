package com.paytmmoney.seatreservation.common.exception;

/** Thrown when the token-derived caller is not the owner of the resource they're trying to act on. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
