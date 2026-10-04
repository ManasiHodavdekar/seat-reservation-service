package com.paytmmoney.seatreservation.common;

import java.time.Instant;
import java.util.List;

public record ApiError(
        String error,
        String message,
        List<String> seats,
        Instant timestamp
) {
    public static ApiError of(String error, String message) {
        return new ApiError(error, message, null, Instant.now());
    }

    public static ApiError of(String error, String message, List<String> seats) {
        return new ApiError(error, message, seats, Instant.now());
    }
}
