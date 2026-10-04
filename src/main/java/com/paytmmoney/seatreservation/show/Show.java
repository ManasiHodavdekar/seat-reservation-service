package com.paytmmoney.seatreservation.show;

import java.time.Instant;

public record Show(
        String id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        Instant createdAt
) {
}
