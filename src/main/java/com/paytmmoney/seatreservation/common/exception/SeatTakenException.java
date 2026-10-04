package com.paytmmoney.seatreservation.common.exception;

import java.util.List;

/** Thrown when any requested seat loses the conditional UPDATE race (already CONFIRMED). */
public class SeatTakenException extends RuntimeException {
    private final List<String> seats;

    public SeatTakenException(List<String> seats) {
        super("seat(s) already taken: " + seats);
        this.seats = seats;
    }

    public List<String> getSeats() {
        return seats;
    }
}
