package com.paytmmoney.seatreservation.reservation;

import com.paytmmoney.seatreservation.auth.AuthUser;
import com.paytmmoney.seatreservation.common.TransientRetry;
import com.paytmmoney.seatreservation.reservation.dto.ReservationResponse;
import com.paytmmoney.seatreservation.reservation.dto.ReserveRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReservationController {

    private static final int MAX_ATTEMPTS = 8;

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable String id,
            @AuthUser String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody ReserveRequest request) {

        ReserveRequest effective = request.idempotencyKey() != null && !request.idempotencyKey().isBlank()
                ? request
                : new ReserveRequest(request.seats(), idempotencyKeyHeader);

        // Each retry is a brand-new transaction via the proxy - see TransientRetry for why this
        // lives at the call site rather than inside ReservationService.reserve() itself.
        ReservationService.ReservationOutcome outcome = TransientRetry.withRetry(
                () -> reservationService.reserve(id, userId, effective), MAX_ATTEMPTS);
        HttpStatus status = outcome.newlyCreated() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome.toResponse());
    }

    @PostMapping("/reservations/{id}/cancel")
    public ResponseEntity<ReservationResponse> cancel(@PathVariable String id, @AuthUser String userId) {
        ReservationResponse response = TransientRetry.withRetry(
                () -> reservationService.cancel(id, userId), MAX_ATTEMPTS);
        return ResponseEntity.ok(response);
    }
}
