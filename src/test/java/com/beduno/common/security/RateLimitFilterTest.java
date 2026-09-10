package com.beduno.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Two fixes are pinned here. The throttle used to key its buckets on the left-most
 * {@code X-Forwarded-For} entry -- every hop appends to that header, so its left-most value is
 * whatever the caller sent, and a brute-force script could vary it per request and never exhaust a
 * bucket. It also never removed a bucket, so the keyspace was the internet.
 */
class RateLimitFilterTest {

    private static final int LIMIT = 10;
    private static final String AUTH_PATH = "/api/v1/auth/login";

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        // ErrorResponse carries an Instant, which a bare ObjectMapper cannot serialize.
        // Spring Boot's autoconfigured mapper registers this module; the throttle's 429
        // branch writes through it, so the test mapper needs it too.
        filter = new RateLimitFilter(new ObjectMapper().registerModule(new JavaTimeModule()), LIMIT);
    }

    private MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        var request = new MockHttpServletRequest("POST", AUTH_PATH);
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private int statusAfter(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        return response.getStatus();
    }

    @Nested
    class Throttling {

        @Test
        void shouldRejectEleventhRequest_whenSameClientRepeats() throws Exception {
            for (var i = 0; i < LIMIT; i++) {
                assertThat(statusAfter(request("203.0.113.7", null))).isEqualTo(200);
            }
            assertThat(statusAfter(request("203.0.113.7", null))).isEqualTo(429);
        }

        @Test
        void shouldNotThrottle_whenPathIsNotAuth() throws Exception {
            var chain = mock(FilterChain.class);
            for (var i = 0; i < LIMIT + 5; i++) {
                var request = new MockHttpServletRequest("GET", "/api/v1/workers");
                request.setRemoteAddr("203.0.113.7");
                filter.doFilter(request, new MockHttpServletResponse(), chain);
            }
            verify(chain, org.mockito.Mockito.times(LIMIT + 5))
                    .doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested
    class ForwardedHeaderSpoofing {

        @Test
        void shouldStillThrottle_whenCallerVariesForwardedForHeader() throws Exception {
            // One attacker, a different spoofed XFF on every request. Keying on that header would
            // hand each request a fresh bucket; keying on the peer address does not.
            for (var i = 0; i < LIMIT; i++) {
                var spoofed = "198.51.100." + i;
                assertThat(statusAfter(request("203.0.113.7", spoofed))).isEqualTo(200);
            }
            assertThat(statusAfter(request("203.0.113.7", "198.51.100.250"))).isEqualTo(429);
        }

        @Test
        void shouldKeepClientsIndependent_whenPeerAddressesDiffer() throws Exception {
            for (var i = 0; i < LIMIT; i++) {
                assertThat(statusAfter(request("203.0.113.7", null))).isEqualTo(200);
            }
            assertThat(statusAfter(request("203.0.113.7", null))).isEqualTo(429);
            // A genuinely different client is unaffected by the first one's exhausted bucket.
            assertThat(statusAfter(request("203.0.113.8", null))).isEqualTo(200);
        }
    }

    @Nested
    class BucketEviction {

        @Test
        void shouldForgetBucket_whenClientHasBeenIdlePastTheTtl() throws Exception {
            statusAfter(request("203.0.113.7", null));
            assertThat(filter.trackedClients()).isEqualTo(1);

            // Nothing has aged yet, so a sweep now must not throw the live bucket away.
            filter.sweepIdleBuckets();
            assertThat(filter.trackedClients()).isEqualTo(1);

            ageAllBucketsBy(Duration.ofMinutes(11));
            filter.sweepIdleBuckets();
            assertThat(filter.trackedClients()).isZero();
        }

        @Test
        void shouldNotGrowUnbounded_whenManyDistinctClientsCallOnce() throws Exception {
            for (var i = 0; i < 500; i++) {
                statusAfter(request("198.51.100." + (i % 256) + "." + i, null));
            }
            assertThat(filter.trackedClients()).isEqualTo(500);

            ageAllBucketsBy(Duration.ofMinutes(11));
            filter.sweepIdleBuckets();
            assertThat(filter.trackedClients()).isZero();
        }

        @Test
        void shouldKeepThrottling_whenBucketSurvivesASweep() throws Exception {
            for (var i = 0; i < LIMIT; i++) {
                assertThat(statusAfter(request("203.0.113.7", null))).isEqualTo(200);
            }
            filter.sweepIdleBuckets();
            // The client is still active, so its exhausted bucket must not be reset by the sweep.
            assertThat(statusAfter(request("203.0.113.7", null))).isEqualTo(429);
        }

        /** Reaches into the entries rather than sleeping ten minutes. */
        private void ageAllBucketsBy(Duration age) throws Exception {
            var bucketsField = RateLimitFilter.class.getDeclaredField("buckets");
            bucketsField.setAccessible(true);
            var buckets = (java.util.Map<?, ?>) bucketsField.get(filter);
            for (var entry : buckets.values()) {
                var lastSeen = entry.getClass().getDeclaredField("lastSeenMillis");
                lastSeen.setAccessible(true);
                lastSeen.setLong(entry, System.currentTimeMillis() - age.toMillis());
            }
        }
    }

    @Nested
    class PathMatching {

        @Test
        void shouldThrottle_whenPathIsPercentEncoded() throws Exception {
            // Spring routes on the decoded path, so /api/v1/%61uth/login reaches the login handler.
            // A prefix check against the raw URI does not match it, which let a brute-force run
            // straight past this filter without ever consuming a token.
            for (var i = 0; i < LIMIT; i++) {
                assertThat(statusAfter(encodedRequest("203.0.113.9"))).isEqualTo(200);
            }
            assertThat(statusAfter(encodedRequest("203.0.113.9"))).isEqualTo(429);
        }

        @Test
        void shouldShareOneBucket_whenTheSamePathIsWrittenTwoWays() throws Exception {
            // Otherwise the attacker gets a fresh allowance per spelling of the same endpoint.
            for (var i = 0; i < LIMIT; i++) {
                assertThat(statusAfter(request("203.0.113.10", null))).isEqualTo(200);
            }
            assertThat(statusAfter(encodedRequest("203.0.113.10"))).isEqualTo(429);
        }

        @Test
        void shouldNotThrottle_whenPathIsOutsideAuthEvenIfEncoded() throws Exception {
            var chain = mock(FilterChain.class);
            for (var i = 0; i < LIMIT + 5; i++) {
                var request = new MockHttpServletRequest("GET", "/api/v1/%77orkers");
                request.setRemoteAddr("203.0.113.11");
                filter.doFilter(request, new MockHttpServletResponse(), chain);
            }
            verify(chain, org.mockito.Mockito.times(LIMIT + 5))
                    .doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        private MockHttpServletRequest encodedRequest(String remoteAddr) {
            var request = new MockHttpServletRequest("POST", "/api/v1/%61uth/login");
            request.setRemoteAddr(remoteAddr);
            return request;
        }
    }
}
