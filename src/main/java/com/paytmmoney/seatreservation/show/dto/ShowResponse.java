package com.paytmmoney.seatreservation.show.dto;

import com.paytmmoney.seatreservation.show.SeatCounts;
import com.paytmmoney.seatreservation.show.SeatView;
import com.paytmmoney.seatreservation.show.Show;

import java.time.Instant;
import java.util.List;

public record ShowResponse(
        String id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        Instant createdAt,
        List<SeatView> seats,
        SeatCounts counts
) {
    public static ShowResponse of(Show show, List<SeatView> seats, SeatCounts counts) {
        return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
                show.totalSeats(), show.createdAt(), seats, counts);
    }
}
