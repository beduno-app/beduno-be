package com.beduno.common.security;

import com.beduno.common.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String RATE_LIMITED_PATH_PREFIX = "/api/v1/auth/";

    /** A bucket nobody has touched for this long is indistinguishable from a fresh one. */
    private static final Duration IDLE_TTL = Duration.ofMinutes(10);

    /**
     * Backstop for the window between sweeps. Reaching this many distinct client addresses in ten
     * minutes on a single-instance deployment is already an incident; the point is only that the
     * map cannot grow until the heap does.
     */
    private static final int MAX_TRACKED_CLIENTS = 50_000;

    private final ObjectMapper objectMapper;
    private final int requestsPerMinute;
    private final ConcurrentHashMap<String, Entry> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(ObjectMapper objectMapper,
                           @Value("${beduno.rate-limit.requests-per-minute:10}") int requestsPerMinute) {
        this.objectMapper = objectMapper;
        this.requestsPerMinute = requestsPerMinute;
    }

    private static final class Entry {
        private final Bucket bucket;
        private volatile long lastSeenMillis;

        private Entry(Bucket bucket) {
            this.bucket = bucket;
            this.lastSeenMillis = System.currentTimeMillis();
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!resolvePath(request).startsWith(RATE_LIMITED_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        var ip = resolveClientIp(request);
        var entry = buckets.get(ip);
        if (entry == null) {
            evictIfOverCapacity();
            entry = buckets.computeIfAbsent(ip, key -> new Entry(newBucket()));
        }
        entry.lastSeenMillis = System.currentTimeMillis();

        if (entry.bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            // Explicit: the servlet default is ISO-8859-1 and getWriter() encodes with whatever the
            // response declares, so without this the header lies and any localized string mojibakes.
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(
                    response.getWriter(),
                    ErrorResponse.of("RATE_LIMIT_EXCEEDED", "error.rate_limit_exceeded"));
        }
    }

    /**
     * Decoded, and relative to the context path — deliberately not {@code getRequestURI()}, which
     * is the raw URI as sent. Spring routes on the decoded path, so a caller who writes
     * {@code /api/v1/%61uth/login} reaches the login handler while a raw prefix check sees a path
     * that does not match and waves the request through unthrottled. That turned this filter into
     * a formality for anyone who read it.
     */
    private String resolvePath(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }

    private Bucket newBucket() {
        var limit = Bandwidth.builder()
                .capacity(requestsPerMinute)
                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * The map is keyed by client address and was previously never emptied, so a run of requests
     * from many addresses grew it until the heap ran out — a slow denial of service that needed
     * no authentication and left no trace beyond memory pressure. Buckets refill in a minute, so
     * anything idle for ten carries no state worth keeping.
     */
    @Scheduled(fixedDelayString = "${beduno.rate-limit.sweep-interval-ms:300000}")
    void sweepIdleBuckets() {
        var cutoff = System.currentTimeMillis() - IDLE_TTL.toMillis();
        buckets.values().removeIf(entry -> entry.lastSeenMillis < cutoff);
    }

    /**
     * Deliberately fail-open. Dropping the table lets a flood of distinct addresses reset the
     * throttle, which is the lesser harm: holding the entries instead trades a bounded, one-minute
     * loss of throttle state for an unbounded and permanent loss of the heap.
     */
    private void evictIfOverCapacity() {
        if (buckets.size() < MAX_TRACKED_CLIENTS) {
            return;
        }
        sweepIdleBuckets();
        if (buckets.size() >= MAX_TRACKED_CLIENTS) {
            log.warn("rate-limit table still holds {} active clients after a sweep; clearing it",
                    buckets.size());
            buckets.clear();
        }
    }

    int trackedClients() {
        return buckets.size();
    }

    /**
     * Deliberately does not read {@code X-Forwarded-For}. That header is appended to by every hop,
     * so its left-most entry is supplied by the caller: trusting it let anyone bypass this throttle
     * by sending a different value on each request. Behind a reverse proxy the trustworthy client
     * address comes from {@code server.forward-headers-strategy: native} (Tomcat's RemoteIpValve),
     * which resolves the first untrusted hop into {@code getRemoteAddr()}. Without a proxy this is
     * simply the peer address.
     */
    private String resolveClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
