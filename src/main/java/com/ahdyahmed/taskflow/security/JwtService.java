package com.ahdyahmed.taskflow.security;

import com.ahdyahmed.taskflow.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

/**
 * Access and refresh tokens are both plain JWTs signed with the same
 * HS256 key, distinguished only by a {@code type} claim and a different
 * expiration. That claim is what stops a leaked refresh token from being
 * usable as an access token (or vice versa) — {@link #isAccessToken}
 * is checked explicitly by the filter, not inferred from expiry length.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private static final String CLAIM_TOKEN_TYPE = "type";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";

    private final JwtProperties jwtProperties;

    public String generateAccessToken(String subjectEmail) {
        return buildToken(subjectEmail, TOKEN_TYPE_ACCESS, jwtProperties.accessTokenExpirationMs());
    }

    public String generateRefreshToken(String subjectEmail) {
        return buildToken(subjectEmail, TOKEN_TYPE_REFRESH, jwtProperties.refreshTokenExpirationMs());
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isAccessToken(String token) {
        return TOKEN_TYPE_ACCESS.equals(extractClaim(token, c -> c.get(CLAIM_TOKEN_TYPE, String.class)));
    }

    public boolean isRefreshToken(String token) {
        return TOKEN_TYPE_REFRESH.equals(extractClaim(token, c -> c.get(CLAIM_TOKEN_TYPE, String.class)));
    }

    /**
     * True only if the signature is valid and the token isn't expired.
     * Any parse failure is treated as "not valid" rather than letting the
     * exception escape — callers (the filter) shouldn't need to know
     * jjwt's exception hierarchy just to ask "is this usable?".
     */
    public boolean isValid(String token) {
        try {
            extractAllClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    private String buildToken(String subjectEmail, String tokenType, long expirationMs) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(subjectEmail)
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey())
                .compact();
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }
}
