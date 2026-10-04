-- Schema for the seat reservation service.
-- The correctness of the whole system rests on two constraints enforced here, not in application code:
--   1. seats(show_id, seat_number) is the primary key -> exactly one row per physical seat, ever.
--   2. reservations has a UNIQUE(user_id, idempotency_key) -> exactly one reservation per (user, key), ever.
-- Every "atomic decision" in the service is a single UPDATE/INSERT guarded by these constraints plus a
-- WHERE clause on current state, relying on InnoDB row locks taken during that statement.

CREATE TABLE IF NOT EXISTS shows (
    id              VARCHAR(36)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    price_paise     BIGINT       NOT NULL,
    per_user_limit  INT          NOT NULL DEFAULT 4,
    total_seats     INT          NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS seats (
    show_id         VARCHAR(36)  NOT NULL,
    seat_number     VARCHAR(20)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'AVAILABLE',
    confirmed_by    VARCHAR(100) DEFAULT NULL,
    reservation_id  VARCHAR(36)  DEFAULT NULL,
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (show_id, seat_number),
    CONSTRAINT fk_seats_show FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT chk_seat_status CHECK (status IN ('AVAILABLE', 'CONFIRMED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_seats_show_status ON seats (show_id, status);

CREATE TABLE IF NOT EXISTS reservations (
    id               VARCHAR(36)  NOT NULL,
    show_id          VARCHAR(36)  NOT NULL,
    user_id          VARCHAR(100) NOT NULL,
    idempotency_key  VARCHAR(200) NOT NULL,
    seats            TEXT         NOT NULL,
    amount_paise     BIGINT       NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    cancelled_at     TIMESTAMP(6) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_res_show FOREIGN KEY (show_id) REFERENCES shows (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_reservations_show ON reservations (show_id);

CREATE TABLE IF NOT EXISTS user_show_quota (
    show_id      VARCHAR(36)  NOT NULL,
    user_id      VARCHAR(100) NOT NULL,
    held_count   INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (show_id, user_id),
    CONSTRAINT fk_quota_show FOREIGN KEY (show_id) REFERENCES shows (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
