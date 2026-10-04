package com.paytmmoney.seatreservation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Central place where every reservation outcome gets counted exactly once, so the Prometheus
 * series always reconciles with the HTTP responses actually returned (one increment per decision,
 * made at the same point the decision is made — see ReservationService / GlobalExceptionHandler).
 */
@Component
public class ReservationMetrics {

    private final Counter confirmed;
    private final Counter cancelled;
    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> declinedByReason = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder("reservations_confirmed_total")
                .description("Reservations that were newly confirmed (excludes idempotent replays)")
                .register(registry);
        this.cancelled = Counter.builder("reservations_cancelled_total")
                .description("Reservations cancelled by their owner")
                .register(registry);
    }

    public void confirmed() {
        confirmed.increment();
    }

    public void cancelled() {
        cancelled.increment();
    }

    /** reason in {seat_taken, user_limit_exceeded, idempotent_replay, idempotency_conflict} */
    public void declined(String reason) {
        declinedByReason.computeIfAbsent(reason, (Function<String, Counter>) r ->
                Counter.builder("reservations_declined_total")
                        .description("Reservations declined, labeled by reason")
                        .tag("reason", r)
                        .register(registry)
        ).increment();
    }
}
