package com.paytmmoney.seatreservation.reservation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class ReservationRepository {

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ReservationRecord> MAPPER = (rs, rowNum) -> new ReservationRecord(
            rs.getString("id"),
            rs.getString("show_id"),
            rs.getString("user_id"),
            rs.getString("idempotency_key"),
            Arrays.asList(rs.getString("seats").split(",")),
            rs.getLong("amount_paise"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("cancelled_at") != null ? rs.getTimestamp("cancelled_at").toInstant() : null
    );

    /**
     * The idempotency decision lives here: UNIQUE(user_id, idempotency_key) makes a second
     * concurrent insert for the same key fail fast with a DuplicateKeyException instead of a
     * race window between "check" and "write".
     *
     * @return true if this call created the row (we own this attempt); false if a row for this
     *         (user_id, idempotency_key) already exists (caller must look it up and decide
     *         replay vs conflict).
     */
    public boolean tryInsert(String id, String showId, String userId, String idempotencyKey,
                              List<String> sortedSeats, long amountPaise) {
        try {
            jdbc.update(
                    "INSERT INTO reservations (id, show_id, user_id, idempotency_key, seats, amount_paise, status) " +
                            "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED')",
                    id, showId, userId, idempotencyKey, String.join(",", sortedSeats), amountPaise
            );
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<ReservationRecord> findByUserAndKey(String userId, String idempotencyKey) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM reservations WHERE user_id = ? AND idempotency_key = ?",
                    MAPPER, userId, idempotencyKey
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ReservationRecord> findById(String id) {
        try {
            return Optional.of(jdbc.queryForObject("SELECT * FROM reservations WHERE id = ?", MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Locks the row for the duration of the enclosing transaction so concurrent cancels on the same id serialize. */
    public Optional<ReservationRecord> findByIdForUpdate(String id) {
        try {
            return Optional.of(jdbc.queryForObject("SELECT * FROM reservations WHERE id = ? FOR UPDATE", MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean markCancelled(String id) {
        int rows = jdbc.update(
                "UPDATE reservations SET status = 'CANCELLED', cancelled_at = NOW(6) WHERE id = ? AND status = 'CONFIRMED'",
                id
        );
        return rows == 1;
    }
}
