package com.ahdyahmed.taskflow.integration;

import com.ahdyahmed.taskflow.dto.request.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 16 ("fill gaps"). {@code RateLimitingFilter} has been live since
 * Day 11, but nothing before today ever pushed a request past its
 * threshold — every other test in this project makes a handful of auth
 * calls, comfortably under the real {@code capacity: 20} from
 * {@code application.yml} (deliberately generous, per that file's own
 * comment, specifically so exploratory testing doesn't trip it by
 * accident).
 * <p>
 * Provable capacity of 3, only for this test class: {@code capacity: 20}
 * would mean sending 21 requests just to prove the filter fires, which
 * is slow and not any more convincing than 3. Overriding it here rather
 * than in {@code application-test.yml} keeps the change scoped to this
 * one class's own {@code ApplicationContext} — the additional dynamic
 * property means this class gets its own Spring context distinct from
 * {@code SecurityIntegrationTest}'s, so nothing here can affect that
 * class's ~16 real login calls, and vice versa.
 */
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void rateLimitProperties(DynamicPropertyRegistry registry) {
        registry.add("app.security.rate-limit.capacity", () -> 3);
        registry.add("app.security.rate-limit.refill-duration-seconds", () -> 60);
    }

    @Test
    void loginEndpoint_returns429_afterExceedingTheConfiguredCapacity() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail("does-not-matter@taskflow.test");
        request.setPassword("whatever-this-is-wrong-anyway");

        // The filter runs before the controller, so it doesn't matter
        // that this account doesn't exist — each of these three calls
        // still consumes one token from the bucket and gets a normal
        // 401, exactly as an attacker's failed guesses would.
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/auth/login")
                            .with(remoteAddr("203.0.113.10"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        // The 4th call from the same (simulated) IP, same path, within
        // the same window, is the one that crosses the capacity of 3.
        // Retry-After isn't asserted to an exact value: with capacity 3
        // spread greedily over 60s, the next token arrives in ~20s, but
        // exactly how many nanoseconds are left depends on when in that
        // window this call happens to land — asserting the header is
        // present and the message has the right shape is the stable,
        // non-flaky version of this check.
        mockMvc.perform(post("/auth/login")
                        .with(remoteAddr("203.0.113.10"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value(startsWith("Too many requests. Try again in")));
    }

    @Test
    void bucketsAreScopedPerClientIp_oneIpBeingRateLimitedDoesNotAffectAnother() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail("does-not-matter@taskflow.test");
        request.setPassword("whatever-this-is-wrong-anyway");

        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/auth/login")
                            .with(remoteAddr("203.0.113.20"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
        // This IP is now exhausted...
        mockMvc.perform(post("/auth/login")
                        .with(remoteAddr("203.0.113.20"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests());

        // ...but a different client IP hitting the very same endpoint has
        // its own, untouched bucket. Confirms the key is (ip, path), not
        // just path — a busy shared endpoint doesn't become collateral
        // damage for every client because of one noisy one.
        mockMvc.perform(post("/auth/login")
                        .with(remoteAddr("203.0.113.21"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bucketsAreScopedPerPath_exhaustingLoginDoesNotAffectRegister() throws Exception {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("does-not-matter@taskflow.test");
        loginRequest.setPassword("whatever-this-is-wrong-anyway");

        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/auth/login")
                            .with(remoteAddr("203.0.113.30"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/auth/login")
                        .with(remoteAddr("203.0.113.30"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isTooManyRequests());

        // Same IP, different rate-limited path — RateLimitingFilter's own
        // javadoc calls this out explicitly: hammering /auth/login
        // shouldn't cost this client their /auth/register attempts too.
        // (This register call will itself fail validation — no request
        // body fields are set — but a 400 from Bean Validation proves the
        // request got *past* the rate limiter, which is all this test
        // needs; a 429 here would mean the two paths were wrongly
        // sharing one budget.)
        mockMvc.perform(post("/auth/register")
                        .with(remoteAddr("203.0.113.30"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Simulates a distinct client by setting the request's remote address,
     * which is what {@code RateLimitingFilter} keys on. (An earlier version
     * used an {@code X-Forwarded-For} header for this, but that header is
     * client-controlled and the filter no longer trusts it.)
     */
    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    void spoofedXForwardedForHeader_doesNotGrantAFreshBucket() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail("does-not-matter@taskflow.test");
        request.setPassword("whatever-this-is-wrong-anyway");

        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/auth/login")
                            .with(remoteAddr("203.0.113.40"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        // Same real address, but the client claims to be someone else via
        // a header it fully controls. Must still be rate limited.
        mockMvc.perform(post("/auth/login")
                        .with(remoteAddr("203.0.113.40"))
                        .header("X-Forwarded-For", "198.51.100.77")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests());
    }
}
