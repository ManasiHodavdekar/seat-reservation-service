package com.paytmmoney.seatreservation.common;

import com.paytmmoney.seatreservation.auth.UnauthorizedException;
import com.paytmmoney.seatreservation.common.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ShowNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ShowNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("show_not_found", e.getMessage()));
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ReservationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("reservation_not_found", e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> forbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("forbidden", e.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of("unauthorized", e.getMessage()));
    }

    @ExceptionHandler(SeatTakenException.class)
    public ResponseEntity<ApiError> seatTaken(SeatTakenException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("seat_taken", e.getMessage(), e.getSeats()));
    }

    @ExceptionHandler(UserLimitExceededException.class)
    public ResponseEntity<ApiError> userLimit(UserLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("user_limit_exceeded", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> idempotencyConflict(IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("idempotency_conflict", e.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> badRequest(BadRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("bad_request", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("validation_error", message));
    }

    /**
     * All retry attempts (see TransientRetry) were exhausted against genuine, repeated InnoDB
     * deadlocks/lock-wait-timeouts. A client-retryable 429, not a 500: this is transient DB
     * contention, not a server defect - the correctness bar treats it as a decline, never a 5xx.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ApiError> concurrencyExhausted(ConcurrencyFailureException e) {
        log.warn("retries exhausted on transient DB contention: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiError.of("retry_required", "transient contention - please retry this request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> fallback(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("internal_error", "an unexpected error occurred"));
    }
}
