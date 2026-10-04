package com.paytmmoney.seatreservation.show;

import com.paytmmoney.seatreservation.common.exception.ShowNotFoundException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Plain JdbcTemplate (no ORM) on purpose: every statement here that matters for correctness is a
 * single conditional UPDATE/INSERT whose WHERE clause encodes the atomic decision. Hiding that
 * behind an ORM's dirty-checking would make the race-freedom argument harder to audit.
 */
@Repository
public class ShowRepository {

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Show> SHOW_MAPPER = (rs, rowNum) -> new Show(
            rs.getString("id"),
            rs.getString("name"),
            rs.getLong("price_paise"),
            rs.getInt("per_user_limit"),
            rs.getInt("total_seats"),
            rs.getTimestamp("created_at").toInstant()
    );

    public Show createShowWithSeats(String id, String name, long pricePaise, int perUserLimit, List<String> seatNumbers) {
        jdbc.update(
                "INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats) VALUES (?,?,?,?,?)",
                id, name, pricePaise, perUserLimit, seatNumbers.size()
        );
        jdbc.batchUpdate(
                "INSERT INTO seats (show_id, seat_number, status) VALUES (?, ?, 'AVAILABLE')",
                seatNumbers,
                200,
                (ps, seatNumber) -> {
                    ps.setString(1, id);
                    ps.setString(2, seatNumber);
                }
        );
        return findById(id).orElseThrow(() -> new ShowNotFoundException(id));
    }

    public Optional<Show> findById(String id) {
        try {
            return Optional.of(jdbc.queryForObject("SELECT * FROM shows WHERE id = ?", SHOW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Show requireById(String id) {
        return findById(id).orElseThrow(() -> new ShowNotFoundException(id));
    }

    public List<SeatView> listSeats(String showId) {
        return jdbc.query(
                "SELECT seat_number, status FROM seats WHERE show_id = ? ORDER BY seat_number",
                (rs, rowNum) -> new SeatView(rs.getString("seat_number"), rs.getString("status").toLowerCase()),
                showId
        );
    }

    public SeatCounts countSeats(String showId) {
        return jdbc.queryForObject(
                "SELECT " +
                        "  SUM(CASE WHEN status='AVAILABLE' THEN 1 ELSE 0 END) AS available, " +
                        "  SUM(CASE WHEN status='CONFIRMED' THEN 1 ELSE 0 END) AS confirmed, " +
                        "  COUNT(*) AS total " +
                        "FROM seats WHERE show_id = ?",
                (rs, rowNum) -> new SeatCounts(
                        rs.getInt("available"),
                        0,
                        rs.getInt("confirmed"),
                        rs.getInt("total")
                ),
                showId
        );
    }

    public int countAvailable(String showId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'AVAILABLE'",
                Integer.class, showId
        );
        return count == null ? 0 : count;
    }

    /** The atomic seat decision: AVAILABLE -> CONFIRMED only if it is still AVAILABLE. Returns true iff we won the race. */
    public boolean tryConfirmSeat(String showId, String seatNumber, String userId, String reservationId) {
        int rows = jdbc.update(
                "UPDATE seats SET status = 'CONFIRMED', confirmed_by = ?, reservation_id = ? " +
                        "WHERE show_id = ? AND seat_number = ? AND status = 'AVAILABLE'",
                userId, reservationId, showId, seatNumber
        );
        return rows == 1;
    }

    public boolean seatExists(String showId, String seatNumber) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE show_id = ? AND seat_number = ?",
                Integer.class, showId, seatNumber
        );
        return count != null && count > 0;
    }

    /**
     * Release only succeeds if the seat is still tied to this exact reservation id - guards
     * against resurrecting a seat that has since been confirmed under a different reservation.
     */
    public boolean releaseSeat(String showId, String seatNumber, String reservationId) {
        int rows = jdbc.update(
                "UPDATE seats SET status = 'AVAILABLE', confirmed_by = NULL, reservation_id = NULL " +
                        "WHERE show_id = ? AND seat_number = ? AND reservation_id = ?",
                showId, seatNumber, reservationId
        );
        return rows == 1;
    }
}
