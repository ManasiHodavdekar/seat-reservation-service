package com.paytmmoney.seatreservation.reservation;

import java.time.Instant;
import java.util.List;

public record ReservationRecord(
        String id,
        String showId,
        String userId,
        String idempotencyKey,
        List<String> seats,
        long amountPaise,
        String status,
        Instant createdAt,
        Instant cancelledAt
) {
}
