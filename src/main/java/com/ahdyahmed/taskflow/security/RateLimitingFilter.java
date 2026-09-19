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
 * only to {@code /auth/login} and {@code /auth/register} — the two
 * endpoints an attacker would actually want to hammer (credential
 * stuffing / account-creation spam). Every other endpoint already
 * requires a valid JWT, which is its own throttle of sorts; these two
 * are the ones reachable with nothing but a network connection.
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

    private static final Set<String> RATE_LIMITED_PATHS = Set.of("/auth/login", "/auth/register");

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
     * Trusts {@code X-Forwarded-For} when present. That's only safe
     * because this is a single-instance deployment sitting directly
     * behind whatever reverse proxy sets that header (or nothing at
     * all, in local dev) — a header a client could set for itself isn't
     * trustworthy the moment there's a proxy in front of this that
     * *doesn't* strip client-supplied values before adding its own.
     * Worth revisiting alongside the Redis migration noted above.
     */
    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
