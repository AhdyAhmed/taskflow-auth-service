package com.ahdyahmed.taskflow.security;

import com.ahdyahmed.taskflow.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 14. Covers the three things the roadmap calls out explicitly for
 * this class: token generation (right subject, right {@code type} claim),
 * expiry, and tampered-token rejection.
 * <p>
 * {@link JwtService} is plain-old-Java (no Spring context needed) — its
 * only collaborator is the {@link JwtProperties} record, so this is
 * instantiated directly rather than pulled in with {@code @SpringBootTest}.
 */
class JwtServiceTest {

    // 32+ chars so HS256's >=256-bit key requirement is satisfied, same
    // as the real app.jwt.secret default in application.yml.
    private static final String TEST_SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties(TEST_SECRET, 900_000L, 604_800_000L);
        jwtService = new JwtService(properties);
    }

    @Test
    void generateAccessToken_carriesSubjectAndAccessTypeClaim() {
        String token = jwtService.generateAccessToken("alice@taskflow.dev");

        assertThat(jwtService.extractEmail(token)).isEqualTo("alice@taskflow.dev");
        assertThat(jwtService.isAccessToken(token)).isTrue();
        assertThat(jwtService.isRefreshToken(token)).isFalse();
    }

    @Test
    void generateRefreshToken_carriesSubjectAndRefreshTypeClaim() {
        String token = jwtService.generateRefreshToken("bob@taskflow.dev");

        assertThat(jwtService.extractEmail(token)).isEqualTo("bob@taskflow.dev");
        assertThat(jwtService.isRefreshToken(token)).isTrue();
        assertThat(jwtService.isAccessToken(token)).isFalse();
    }

    @Test
    void isValid_trueForFreshlyIssuedToken() {
        String token = jwtService.generateAccessToken("alice@taskflow.dev");

        assertThat(jwtService.isValid(token)).isTrue();
    }

    @Test
    void isValid_falseForTamperedToken() {
        String token = jwtService.generateAccessToken("alice@taskflow.dev");

        // Flip the last character of the signature segment. Any change
        // there should fail signature verification without jjwt's
        // exception ever escaping isValid().
        char lastChar = token.charAt(token.length() - 1);
        char replacement = lastChar == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;

        assertThat(jwtService.isValid(tampered)).isFalse();
    }

    @Test
    void isValid_falseForTokenSignedWithADifferentKey() {
        JwtProperties otherKeyProperties = new JwtProperties(
                "a-completely-different-secret-key-also-32-bytes", 900_000L, 604_800_000L);
        JwtService otherJwtService = new JwtService(otherKeyProperties);
        String tokenFromOtherService = otherJwtService.generateAccessToken("alice@taskflow.dev");

        assertThat(jwtService.isValid(tokenFromOtherService)).isFalse();
    }

    @Test
    void isValid_falseForExpiredToken() throws InterruptedException {
        // 1ms expiry so the token is already expired by the time isValid()
        // parses it — avoids mocking the clock just to prove this branch.
        JwtProperties shortLivedProperties = new JwtProperties(TEST_SECRET, 1L, 1L);
        JwtService shortLivedJwtService = new JwtService(shortLivedProperties);
        String token = shortLivedJwtService.generateAccessToken("alice@taskflow.dev");

        Thread.sleep(20);

        assertThat(shortLivedJwtService.isValid(token)).isFalse();
    }

    @Test
    void isValid_falseForGarbageInput() {
        assertThat(jwtService.isValid("not-a-jwt-at-all")).isFalse();
    }

    @Test
    void isValid_falseForEmptyString() {
        assertThat(jwtService.isValid("")).isFalse();
    }
}
