package com.ahdyahmed.taskflow.security;

import com.ahdyahmed.taskflow.config.RateLimitProperties;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Day 11: a simple in-memory token bucket per (client IP, path), applied
 * to the auth endpoints reachable without a token — {@code /auth/login}
 * and {@code /auth/register} initially; Day 12 added
 * {@code /auth/verify} and {@code /auth/resend-verification}, and
 * Day 13 added {@code /auth/forgot-password} and
 * {@code /auth/reset-password}, to the same set, since a token-guessing
 * or spam attempt against any of those is exactly the kind of thing this
 * filter already exists to slow down. Every other endpoint already
 * requires a valid JWT, which is its own throttle of sorts; these are
 * the ones reachable with nothing but a network connection.
 * <p>
 * <b>In-memory, single-instance only.</b> The bucket map lives in this
 * filter's heap, so it resets on restart and isn't shared across
 * instances — behind a load balancer with N instances, an attacker
 * effectively gets N times the configured limit, split across whichever
 * instance each request happens to land on. That's an explicit,
 * documented trade-off for now, not an oversight: fixing it means
 * moving the bucket state somewhere every instance can see (Redis is
 * the standard choice, and Bucket4j has first-class support for it via
 * its JCache/Redis backends) — the same move this project's README
 * already flags for the refresh-token blacklist once this becomes a
 * multi-instance deployment.
 * <p>
 * Keyed by {@code ip + path}, not just {@code ip}: hammering
 * {@code /auth/login} shouldn't cost someone their {@code /auth/register}
 * attempts, and vice versa — they're different abuse patterns with no
 * reason to share a budget.
 */
@Component
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Set<String> RATE_LIMITED_PATHS = Set.of(
            "/auth/login", "/auth/register", "/auth/verify", "/auth/resend-verification",
            // Day 13: forgot-password is a classic inbox-spam and
            // enumeration-probing target — same reasoning as Day 12's
            // additions, same filter.
            "/auth/forgot-password", "/auth/reset-password");

    private final RateLimitProperties rateLimitProperties;
    private final SecurityErrorResponseWriter responseWriter;

    // ConcurrentHashMap, not a scheduled-eviction cache: with a small,
    // fixed set of rate-limited paths and typical attacker/IP churn, this
    // is bounded enough in practice for a portfolio-scale deployment.
    // A long-running production instance would want an eviction policy
    // (e.g. Caffeine) so buckets for IPs that stop showing up eventually
    // get reclaimed — noted here rather than solved, since it's a real
    // gap, just not the one Day 11 is about.
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (!RATE_LIMITED_PATHS.contains(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = clientIp(request) + "|" + request.getRequestURI();
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> newBucket());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        responseWriter.write(request, response, HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Try again in %d second(s).".formatted(retryAfterSeconds));
    }

    private Bucket newBucket() {
        int capacity = rateLimitProperties.capacity();
        Duration window = Duration.ofSeconds(rateLimitProperties.refillDurationSeconds());
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, window))
                .build();
    }

    /**
     * Keys on the actual TCP peer address only. {@code X-Forwarded-For}
     * is deliberately NOT read here: it's a client-controlled header, so
     * trusting it lets any caller mint a fresh bucket per request just by
     * varying it, which defeats the rate limit entirely.
     * <p>
     * Behind a reverse proxy you control, don't parse the header by hand —
     * set {@code server.forward-headers-strategy=native} plus Tomcat's
     * {@code server.tomcat.remoteip.internal-proxies} so the container
     * only honors it when it comes from your proxy, and
     * {@code getRemoteAddr()} then returns the real client address.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
