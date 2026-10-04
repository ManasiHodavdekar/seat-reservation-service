package com.paytmmoney.seatreservation.common.exception;

public class ShowNotFoundException extends RuntimeException {
    public ShowNotFoundException(String showId) {
        super("show not found: " + showId);
    }
}
