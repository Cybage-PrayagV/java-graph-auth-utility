package org.eptura;

/**
 * Metrics interface for monitoring GraphAuthProvider operations.
 * <p>
 * Implement this interface to integrate with your monitoring system (Micrometer, Prometheus, etc.).
 * </p>
 *
 * <p><b>Example Implementation with Micrometer:</b></p>
 * <pre>{@code
 * public class MicrometerTokenMetrics implements TokenMetrics {
 *     private final MeterRegistry registry;
 *     private final Timer refreshTimer;
 *     private final Counter cacheHitCounter;
 *     private final AtomicInteger circuitBreakerState;
 *
 *     public MicrometerTokenMetrics(MeterRegistry registry) {
 *         this.registry = registry;
 *         this.refreshTimer = registry.timer("graph.auth.token.refresh");
 *         this.cacheHitCounter = registry.counter("graph.auth.token.cache.hits");
 *
 *         // Register gauge with AtomicInteger - Micrometer will poll this value
 *         this.circuitBreakerState = new AtomicInteger(0);
 *         registry.gauge("graph.auth.circuit.open", circuitBreakerState);
 *     }
 *
 *     @Override
 *     public void recordTokenRefresh(long latencyMs, boolean success) {
 *         refreshTimer.record(latencyMs, TimeUnit.MILLISECONDS);
 *         registry.counter("graph.auth.token.refresh.total",
 *             "success", String.valueOf(success)).increment();
 *     }
 *
 *     @Override
 *     public void recordTokenCacheHit() {
 *         cacheHitCounter.increment();
 *     }
 *
 *     @Override
 *     public void recordRetryAttempt(int attemptNumber, long backoffMs) {
 *         registry.counter("graph.auth.token.retry",
 *             "attempt", String.valueOf(attemptNumber)).increment();
 *     }
 *
 *     @Override
 *     public void recordCircuitBreakerState(boolean open) {
 *         // Update the AtomicInteger - gauge will reflect this on next scrape
 *         circuitBreakerState.set(open ? 1 : 0);
 *     }
 *
 *     @Override
 *     public void recordConnection(boolean success) {
 *         registry.counter("graph.auth.connection",
 *             "success", String.valueOf(success)).increment();
 *     }
 * }
 * }</pre>
 */
public interface TokenMetrics {

    /**
     * Records a token refresh operation.
     *
     * @param latencyMs Time taken to acquire the token in milliseconds
     * @param success   Whether the refresh was successful
     */
    void recordTokenRefresh(long latencyMs, boolean success);

    /**
     * Records a cache hit (token was valid and no refresh needed).
     */
    void recordTokenCacheHit();

    /**
     * Records a retry attempt during token acquisition.
     *
     * @param attemptNumber Current retry attempt number (1-based)
     * @param backoffMs     Backoff duration before this retry
     */
    void recordRetryAttempt(int attemptNumber, long backoffMs);

    /**
     * Records circuit breaker state changes.
     *
     * @param open Whether the circuit is now open (failing fast)
     */
    void recordCircuitBreakerState(boolean open);

    /**
     * Records a connection event.
     *
     * @param success Whether the connection was successful
     */
    void recordConnection(boolean success);

    /**
     * No-op implementation that discards all metrics.
     * Use when metrics collection is not needed.
     */
    TokenMetrics NOOP = new TokenMetrics() {
        @Override
        public void recordTokenRefresh(long latencyMs, boolean success) {}

        @Override
        public void recordTokenCacheHit() {}

        @Override
        public void recordRetryAttempt(int attemptNumber, long backoffMs) {}

        @Override
        public void recordCircuitBreakerState(boolean open) {}

        @Override
        public void recordConnection(boolean success) {}
    };
}

