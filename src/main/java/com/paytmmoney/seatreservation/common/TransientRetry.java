package com.paytmmoney.seatreservation.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * MySQL/InnoDB can genuinely deadlock (or lock-wait-timeout) two transactions that both try to
 * INSERT the same not-yet-existing unique key concurrently - this is documented InnoDB behaviour,
 * not a sign of a broken schema, and MySQL's own error message says "try restarting transaction".
 * Each attempt here runs as a brand-new transaction (the retry happens at the call site, one layer
 * above the @Transactional method, so self-invocation doesn't bypass the Spring proxy).
 */
public final class TransientRetry {

    private static final Logger log = LoggerFactory.getLogger(TransientRetry.class);

    private TransientRetry() {
    }

    public static <T> T withRetry(Supplier<T> action, int maxAttempts) {
        ConcurrencyFailureException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (ConcurrencyFailureException e) {
                lastFailure = e;
                log.warn("transient DB contention on attempt {}/{}: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        long backoffMs = Math.min(5L * (1L << attempt), 200L);
                        Thread.sleep(ThreadLocalRandom.current().nextLong(5, backoffMs));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw lastFailure;
    }
}
