package com.paytmmoney.seatreservation.show.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record CreateShowRequest(
        @NotBlank String name,
        @NotEmpty List<@NotBlank String> seats,
        @Positive long pricePaise,
        @Min(1) Integer perUserLimit
) {
}
