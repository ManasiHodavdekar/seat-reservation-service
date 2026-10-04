package com.paytmmoney.seatreservation.reservation.dto;

import com.paytmmoney.seatreservation.reservation.ReservationRecord;

import java.util.List;

public record ReservationResponse(
        String reservationId,
        String showId,
        String userId,
        List<String> seats,
        long amountPaise,
        String status
) {
    public static ReservationResponse of(ReservationRecord r) {
        return new ReservationResponse(r.id(), r.showId(), r.userId(), r.seats(), r.amountPaise(),
                r.status().toLowerCase());
    }
}
