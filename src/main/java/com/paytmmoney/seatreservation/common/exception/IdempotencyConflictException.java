package com.paytmmoney.seatreservation.common.exception;

/** Thrown when an idempotency key is replayed with a different seat set than the original request. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String idempotencyKey) {
        super("idempotency key '" + idempotencyKey + "' was already used with a different request body");
    }
}
