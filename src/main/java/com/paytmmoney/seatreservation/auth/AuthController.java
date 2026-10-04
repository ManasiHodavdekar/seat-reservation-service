package com.paytmmoney.seatreservation.auth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only stand-in for an identity provider: trades a user id for a signed bearer token. A real
 * deployment would delegate this to an actual IdP; what matters for the exercise is that every
 * downstream endpoint trusts ONLY this token's subject claim, never a body field.
 */
@RestController
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    public record TokenRequest(@NotBlank String userId) {
    }

    public record TokenResponse(String token, long expiresIn) {
    }

    @PostMapping("/auth/token")
    public TokenResponse issueToken(@RequestBody TokenRequest request) {
        String token = jwtService.issue(request.userId());
        return new TokenResponse(token, jwtService.ttlSeconds());
    }
}
