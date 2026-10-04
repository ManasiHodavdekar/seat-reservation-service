package com.paytmmoney.seatreservation.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Minimal token issuer/verifier standing in for a real identity provider. The point it exists to
 * demonstrate: a reservation request's user_id is read from a signed token's subject claim, never
 * from the JSON body - so a client cannot act "as" another user by editing a field.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                       @Value("${app.jwt.ttl-seconds:3600}") long ttlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public String issue(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public long ttlSeconds() {
        return ttl.getSeconds();
    }

    /** @return the verified subject (user id), or empty if the token is missing/invalid/expired. */
    public java.util.Optional<String> verifyAndGetUserId(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return java.util.Optional.ofNullable(subject);
        } catch (JwtException | IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
