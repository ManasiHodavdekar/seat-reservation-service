package com.paytmmoney.seatreservation.reservation.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReserveRequest(
        @NotEmpty List<@jakarta.validation.constraints.NotBlank String> seats,
        String idempotencyKey
) {
}
