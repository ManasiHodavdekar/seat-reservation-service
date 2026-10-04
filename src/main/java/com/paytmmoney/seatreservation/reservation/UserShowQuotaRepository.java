package com.paytmmoney.seatreservation.reservation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserShowQuotaRepository {

    private final JdbcTemplate jdbc;

    public UserShowQuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * INSERT IGNORE, not "ON DUPLICATE KEY UPDATE held_count = held_count": under concurrent
     * first-time inserts for the same (show_id, user_id), ON DUPLICATE KEY UPDATE's duplicate ->
     * lock-upgrade-to-update path is a documented InnoDB deadlock trigger between two sessions
     * racing the same not-yet-existing unique key. IGNORE just drops the losing insert without
     * taking that extra update lock, so the row-creation race itself no longer deadlocks (the
     * TransientRetry wrapper at the controller remains as a safety net for the rest of the
     * transaction either way).
     */
    private void ensureRow(String showId, String userId) {
        jdbc.update(
                "INSERT IGNORE INTO user_show_quota (show_id, user_id, held_count) VALUES (?, ?, 0)",
                showId, userId
        );
    }

    /**
     * The per-user-limit decision: a single conditional UPDATE that only succeeds if the new
     * total still fits under the limit. The WHERE clause is evaluated against the row as locked
     * by this UPDATE, so two concurrent requests for the same user+show serialize on this row
     * and the second sees the first's committed-or-not delta correctly (MySQL takes the row lock
     * for the duration of the UPDATE within the transaction).
     */
    public boolean tryIncrement(String showId, String userId, int delta, int limit) {
        ensureRow(showId, userId);
        int rows = jdbc.update(
                "UPDATE user_show_quota SET held_count = held_count + ? " +
                        "WHERE show_id = ? AND user_id = ? AND held_count + ? <= ?",
                delta, showId, userId, delta, limit
        );
        return rows == 1;
    }

    public void decrement(String showId, String userId, int delta) {
        jdbc.update(
                "UPDATE user_show_quota SET held_count = GREATEST(held_count - ?, 0) WHERE show_id = ? AND user_id = ?",
                delta, showId, userId
        );
    }
}
