package com.paytmmoney.seatreservation.common.exception;

/** Thrown when granting this request would push the user's held+confirmed seats past per_user_limit. */
public class UserLimitExceededException extends RuntimeException {
    public UserLimitExceededException(int limit) {
        super("per-user limit of " + limit + " seats for this show would be exceeded");
    }
}
