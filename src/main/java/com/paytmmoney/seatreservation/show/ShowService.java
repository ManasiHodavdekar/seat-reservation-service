package com.paytmmoney.seatreservation.show;

import com.paytmmoney.seatreservation.show.dto.CreateShowRequest;
import com.paytmmoney.seatreservation.show.dto.ShowResponse;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ShowService {

    private final ShowRepository showRepository;
    private final MeterRegistry meterRegistry;
    private final int defaultPerUserLimit;
    private final ConcurrentHashMap<String, Boolean> gaugeRegistered = new ConcurrentHashMap<>();

    public ShowService(ShowRepository showRepository,
                        MeterRegistry meterRegistry,
                        @Value("${app.reservation.default-per-user-limit:4}") int defaultPerUserLimit) {
        this.showRepository = showRepository;
        this.meterRegistry = meterRegistry;
        this.defaultPerUserLimit = defaultPerUserLimit;
    }

    public ShowResponse createShow(CreateShowRequest req) {
        List<String> seatNumbers = req.seats().stream().map(String::trim).distinct().collect(Collectors.toList());
        int perUserLimit = req.perUserLimit() != null ? req.perUserLimit() : defaultPerUserLimit;
        String id = UUID.randomUUID().toString();
        Show show = showRepository.createShowWithSeats(id, req.name(), req.pricePaise(), perUserLimit, seatNumbers);
        registerSeatsAvailableGauge(show.id(), show.name());
        return toResponse(show);
    }

    public ShowResponse getShow(String showId) {
        Show show = showRepository.requireById(showId);
        // Also registered lazily here (idempotent) so a gauge still shows up after a process
        // restart, for a show that existed before this instance came up.
        registerSeatsAvailableGauge(show.id(), show.name());
        return toResponse(show);
    }

    private ShowResponse toResponse(Show show) {
        List<SeatView> seats = showRepository.listSeats(show.id());
        SeatCounts counts = showRepository.countSeats(show.id());
        return ShowResponse.of(show, seats, counts);
    }

    /** Registers (once) a gauge that re-queries the DB on every Prometheus scrape, so it can never drift from API state. */
    private void registerSeatsAvailableGauge(String showId, String showName) {
        gaugeRegistered.computeIfAbsent(showId, id -> {
            Gauge.builder("seats_available", showRepository, repo -> repo.countAvailable(id))
                    .description("Seats currently AVAILABLE for a show, recomputed from the DB on every scrape")
                    .tag("show_id", id)
                    .tag("show_name", showName)
                    .register(meterRegistry);
            return true;
        });
    }
}
