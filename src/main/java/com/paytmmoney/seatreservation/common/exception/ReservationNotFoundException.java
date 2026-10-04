package com.paytmmoney.seatreservation.common.exception;

public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException(String reservationId) {
        super("reservation not found: " + reservationId);
    }
}
