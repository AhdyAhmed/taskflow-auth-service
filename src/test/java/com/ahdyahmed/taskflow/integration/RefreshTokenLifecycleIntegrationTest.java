package com.ahdyahmed.taskflow.integration;

import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.domain.enums.Role;
import com.ahdyahmed.taskflow.dto.request.LoginRequest;
import com.ahdyahmed.taskflow.dto.request.RefreshRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 16 ("fill gaps"). Day 6's README committed to a specific promise —
 * refresh tokens are rotated on use and revoked on logout, tracked
 * server-side precisely so they *can* be revoked — and nothing before
 * today actually exercised that through the real endpoints. Day 14/15
 * covered login, lockout, and token-type/expiry rejection; this class
 * covers the one remaining piece of the token lifecycle: what happens to
 * a refresh token across {@code /auth/refresh} and {@code /auth/logout}.
 */
class RefreshTokenLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Sup3rSecretPassword";

    @Test
    void refresh_rotatesTheToken_oldRefreshTokenCanNoLongerBeUsed() throws Exception {
        User user = createUser("rotation-target", PASSWORD, Role.USER);
        String originalRefreshToken = loginAndGetRefreshToken(user.getEmail(), PASSWORD);

        RefreshRequest refreshRequest = new RefreshRequest();
        refreshRequest.setRefreshToken(originalRefreshToken);

        MvcResult firstRefresh = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(firstRefresh.getResponse().getContentAsString());
        String newRefreshToken = body.get("refreshToken").asText();
        String newAccessToken = body.get("accessToken").asText();

        // The whole point of rotation: a genuinely new pair, not the same
        // refresh token echoed back with a fresh access token.
        assertThat(newRefreshToken).isNotEqualTo(originalRefreshToken);
        assertThat(newAccessToken).isNotBlank();

        // Replaying the now-rotated original token — the scenario that
        // matters is a stolen refresh token being used after the real
        // owner has already refreshed once — must fail, not succeed a
        // second time.
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token is invalid or expired"));

        // The new token from rotation, meanwhile, is live and usable.
        RefreshRequest secondRefreshRequest = new RefreshRequest();
        secondRefreshRequest.setRefreshToken(newRefreshToken);

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRefreshRequest)))
                .andExpect(status().isOk());
    }

    @Test
    void logout_revokesTheRefreshToken_soItCanNoLongerBeUsedToRefresh() throws Exception {
        User user = createUser("logout-target", PASSWORD, Role.USER);
        String refreshToken = loginAndGetRefreshToken(user.getEmail(), PASSWORD);

        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(refreshToken);

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        // The refresh token that was just logged out with is now dead —
        // this is the actual security property "logout" is supposed to
        // buy: a revoked refresh token can't mint new access tokens
        // anymore, even though the access token issued alongside it
        // (being a stateless JWT) would otherwise keep working until its
        // own 15-minute expiry.
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token is invalid or expired"));
    }

    @Test
    void logout_isIdempotent_secondCallWithTheSameAlreadyRevokedTokenStillSucceeds() throws Exception {
        User user = createUser("idempotent-logout-target", PASSWORD, Role.USER);
        String refreshToken = loginAndGetRefreshToken(user.getEmail(), PASSWORD);

        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(refreshToken);

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        // AuthService.logout()'s own javadoc calls this out as
        // deliberate: a client retrying a logout call (network blip,
        // double-tap on a slow connection) shouldn't see an error just
        // because the token was already revoked a moment ago.
        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
    }

    @Test
    void logout_withATokenThatWasNeverIssued_stillSucceeds() throws Exception {
        // Same idempotency contract extended to a token the server has
        // never seen at all, not just an already-revoked one — a
        // malformed or long-expired refreshToken value shouldn't turn
        // "log me out" into an error response either.
        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken("this-was-never-a-real-refresh-token");

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
    }

    private String loginAndGetRefreshToken(String email, String rawPassword) throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(rawPassword);

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("refreshToken").asText();
    }
}
