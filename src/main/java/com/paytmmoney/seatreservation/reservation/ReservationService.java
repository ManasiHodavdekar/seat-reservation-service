package com.paytmmoney.seatreservation.reservation;

import com.paytmmoney.seatreservation.common.exception.BadRequestException;
import com.paytmmoney.seatreservation.common.exception.ForbiddenException;
import com.paytmmoney.seatreservation.common.exception.IdempotencyConflictException;
import com.paytmmoney.seatreservation.common.exception.ReservationNotFoundException;
import com.paytmmoney.seatreservation.common.exception.SeatTakenException;
import com.paytmmoney.seatreservation.common.exception.UserLimitExceededException;
import com.paytmmoney.seatreservation.metrics.ReservationMetrics;
import com.paytmmoney.seatreservation.reservation.dto.ReservationResponse;
import com.paytmmoney.seatreservation.reservation.dto.ReserveRequest;
import com.paytmmoney.seatreservation.show.Show;
import com.paytmmoney.seatreservation.show.ShowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ShowRepository showRepository;
    private final ReservationRepository reservationRepository;
    private final UserShowQuotaRepository quotaRepository;
    private final ReservationMetrics metrics;

    public ReservationService(ShowRepository showRepository,
                               ReservationRepository reservationRepository,
                               UserShowQuotaRepository quotaRepository,
                               ReservationMetrics metrics) {
        this.showRepository = showRepository;
        this.reservationRepository = reservationRepository;
        this.quotaRepository = quotaRepository;
        this.metrics = metrics;
    }

    /**
     * All-or-nothing: either every requested seat becomes CONFIRMED for this user, or none do.
     * (Documented trade-off vs. best-effort in WRITEUP.md - a hot multi-seat request that can't be
     * fully satisfied is declined cleanly rather than leaving the user with a partial, possibly
     * useless, set of seats.)
     *
     * Lock order is always: this user's quota row, then requested seats in lexicographic order -
     * the same global order every transaction uses, which is what rules out deadlocks between two
     * concurrent multi-seat requests that overlap on seats.
     *
     * READ_COMMITTED (not the MySQL default REPEATABLE READ) is required here: when two
     * transactions race to INSERT the same (user_id, idempotency_key), the loser blocks on the
     * unique index, then fails with a duplicate-key error once the winner commits. Under
     * REPEATABLE READ the loser's subsequent plain SELECT still uses the snapshot from before the
     * winner committed and would see nothing - a real failure mode hit while load-testing this
     * service (see WRITEUP.md). READ_COMMITTED gives every statement a fresh read view, so the
     * lookup reliably finds the row the loser just lost the race to create.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationOutcome reserve(String showId, String userId, ReserveRequest request) {
        Show show = showRepository.requireById(showId);

        String idempotencyKey = request.idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("idempotency_key is required");
        }

        List<String> sortedSeats = new ArrayList<>(new TreeSet<>(new LinkedHashSet<>(request.seats())));
        if (sortedSeats.isEmpty()) {
            throw new BadRequestException("seats must not be empty");
        }
        for (String seat : sortedSeats) {
            if (!showRepository.seatExists(showId, seat)) {
                throw new BadRequestException("unknown seat for this show: " + seat);
            }
        }

        long amountPaise = show.pricePaise() * sortedSeats.size();
        String reservationId = UUID.randomUUID().toString();

        boolean inserted = reservationRepository.tryInsert(reservationId, showId, userId, idempotencyKey, sortedSeats, amountPaise);
        if (!inserted) {
            // Someone (possibly this same client, retrying) already used this (user_id, idempotency_key).
            ReservationRecord existing = reservationRepository.findByUserAndKey(userId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("duplicate key insert raced with a concurrent delete - should not happen"));
            if (new TreeSet<>(existing.seats()).equals(new TreeSet<>(sortedSeats))) {
                metrics.declined("idempotent_replay");
                return new ReservationOutcome(existing, false);
            }
            metrics.declined("idempotency_conflict");
            throw new IdempotencyConflictException(idempotencyKey);
        }

        int perUserLimit = show.perUserLimit();
        boolean withinLimit = quotaRepository.tryIncrement(showId, userId, sortedSeats.size(), perUserLimit);
        if (!withinLimit) {
            metrics.declined("user_limit_exceeded");
            throw new UserLimitExceededException(perUserLimit);
        }

        List<String> unavailable = new ArrayList<>();
        for (String seat : sortedSeats) {
            boolean won = showRepository.tryConfirmSeat(showId, seat, userId, reservationId);
            if (!won) {
                unavailable.add(seat);
            }
        }
        if (!unavailable.isEmpty()) {
            metrics.declined("seat_taken");
            throw new SeatTakenException(unavailable);
        }

        ReservationRecord created = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalStateException("just-inserted reservation vanished"));
        metrics.confirmed();
        log.info("reservation confirmed id={} show={} user={} seats={} amount={}",
                reservationId, showId, userId, sortedSeats, amountPaise);
        return new ReservationOutcome(created, true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse cancel(String reservationId, String callerUserId) {
        ReservationRecord reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (!reservation.userId().equals(callerUserId)) {
            throw new ForbiddenException("only the owning user may cancel this reservation");
        }

        if ("CANCELLED".equals(reservation.status())) {
            // Idempotent: cancelling an already-cancelled reservation is a no-op, not an error.
            return ReservationResponse.of(reservation);
        }

        boolean cancelled = reservationRepository.markCancelled(reservationId);
        if (!cancelled) {
            // Lost a race to another cancel call for the same id - re-read and return current state.
            ReservationRecord latest = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new ReservationNotFoundException(reservationId));
            return ReservationResponse.of(latest);
        }

        for (String seat : reservation.seats()) {
            // Guarded on reservation_id, so this can never resurrect a seat already confirmed
            // under a different (later) reservation.
            showRepository.releaseSeat(reservation.showId(), seat, reservationId);
        }
        quotaRepository.decrement(reservation.showId(), reservation.userId(), reservation.seats().size());

        metrics.cancelled();
        log.info("reservation cancelled id={} show={} user={} seats={}",
                reservationId, reservation.showId(), reservation.userId(), reservation.seats());

        ReservationRecord updated = reservationRepository.findById(reservationId).orElseThrow();
        return ReservationResponse.of(updated);
    }

    public record ReservationOutcome(ReservationRecord reservation, boolean newlyCreated) {
        public ReservationResponse toResponse() {
            return ReservationResponse.of(reservation);
        }
    }
}
