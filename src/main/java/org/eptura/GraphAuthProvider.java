package org.eptura;

import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.graph.requests.GraphServiceClient;

import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.CharBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe utility for authenticating and connecting to Microsoft Graph API using OAuth 2.0 client credentials flow.
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><b>Thread Safety:</b> Double-checked locking prevents thundering herd on token refresh</li>
 *   <li><b>Circuit Breaker:</b> Fails fast when Azure AD is unreachable to prevent cascade failures</li>
 *   <li><b>Configurable:</b> All timeouts, retries, and scopes are configurable via {@link GraphAuthConfig}</li>
 *   <li><b>Observable:</b> Metrics hooks via {@link TokenMetrics} for monitoring integration</li>
 *   <li><b>Testable:</b> Implements {@link IGraphAuthProvider} interface and supports dependency injection</li>
 *   <li><b>Secure:</b> Client secrets are wiped from memory after use</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Simple Usage (Factory Method):</h3>
 * <pre>{@code
 * GraphAuthProvider provider = GraphAuthProvider.create();
 * try {
 *     provider.connect(clientId, tenantId, clientSecret);
 *     GraphServiceClient<Request> client = provider.getClient();
 *     // Use client for Graph API operations
 * } finally {
 *     provider.close();
 * }
 * }</pre>
 *
 * <h3>Custom Configuration:</h3>
 * <pre>{@code
 * GraphAuthConfig config = GraphAuthConfig.builder()
 *     .tokenExpiryBuffer(Duration.ofMinutes(5))
 *     .maxRetries(5)
 *     .initialBackoff(Duration.ofSeconds(1))
 *     .build();
 *
 * GraphAuthProvider provider = GraphAuthProvider.create(config);
 * }</pre>
 *
 * <h3>With Metrics:</h3>
 * <pre>{@code
 * TokenMetrics metrics = new MyMicrometerMetrics(registry);
 * GraphAuthProvider provider = GraphAuthProvider.create(GraphAuthConfig.defaults(), metrics);
 * }</pre>
 *
 * <h3>Dependency Injection (for testing):</h3>
 * <pre>{@code
 * ConfidentialClientFactory mockFactory = mock(ConfidentialClientFactory.class);
 * GraphAuthProvider provider = new GraphAuthProvider(
 *     GraphAuthConfig.defaults(),
 *     mockFactory,
 *     TokenMetrics.NOOP
 * );
 * }</pre>
 *
 * <h2>Circuit Breaker Behavior</h2>
 * <p>
 * When consecutive failures reach the threshold (default: 5), the circuit opens and subsequent
 * requests fail immediately without attempting Azure AD calls. The circuit automatically resets
 * after a cooldown period (default: 30 seconds).
 * </p>
 *
 * <h2>Thread Safety Details</h2>
 * <p>
 * Token refresh uses double-checked locking with a dedicated lock to ensure only one thread
 * refreshes the token while others wait and reuse the result. This prevents the "thundering herd"
 * problem where many threads simultaneously attempt expensive token refresh operations.
 * </p>
 *
 * @see IGraphAuthProvider Interface for dependency injection
 * @see GraphAuthConfig Configuration options
 * @see TokenMetrics Metrics integration
 * @see <a href="https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-client-creds-grant-flow">
 *      OAuth 2.0 Client Credentials Flow</a>
 */
public final class GraphAuthProvider implements IGraphAuthProvider {

    private static final Logger LOGGER = LogManager.getLogger(GraphAuthProvider.class);

    // ==================== Circuit Breaker Constants ====================

    /**
     * Number of consecutive failures before circuit opens.
     */
    private static final int CIRCUIT_BREAKER_FAILURE_THRESHOLD = 5;

    /**
     * Duration to keep circuit open before allowing retry.
     */
    private static final Duration CIRCUIT_BREAKER_RESET_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Maximum time to wait for token refresh lock acquisition.
     * Prevents indefinite blocking if lock holder hangs.
     */
    private static final Duration TOKEN_REFRESH_LOCK_TIMEOUT = Duration.ofSeconds(60);

    // ==================== Thread Management ====================

    /**
     * Thread name prefix for virtual threads.
     */
    private static final String THREAD_NAME_PREFIX = "GraphAuth-TokenRefresh";

    /**
     * Executor for token refresh operations using virtual threads.
     */
    private final ExecutorService tokenRefreshExecutor;

    /**
     * Flag indicating if this instance owns its executor (for proper shutdown).
     */
    private final boolean ownsExecutor;

    // ==================== Configuration & Dependencies ====================

    /**
     * Immutable configuration for this provider.
     */
    private final GraphAuthConfig config;

    /**
     * Factory for creating MSAL client applications (injectable for testing).
     */
    private final ConfidentialClientFactory clientFactory;

    /**
     * Metrics collector for observability.
     */
    private final TokenMetrics metrics;

    // ==================== Connection State ====================
    // Note: Connection state fields (app, graphClient, clientInfo) use volatile for memory visibility.
    // While writes occur within synchronized(connectionLock) blocks, reads in getClient(),
    // isConnected(), and acquireTokenWithRetry() are performed without synchronization for
    // performance. The volatile keyword ensures these unsynchronized reads see the most recent
    // write from other threads (happens-before relationship).

    /**
     * MSAL confidential client application.
     * Writes synchronized via {@link #connectionLock}; volatile for unsynchronized reads.
     */
    private volatile ConfidentialClientApplication app;

    /**
     * Cached authentication result (thread-safe).
     */
    private final AtomicReference<IAuthenticationResult> cachedAuthResult = new AtomicReference<>();

    /**
     * Microsoft Graph client instance.
     * Writes synchronized via {@link #connectionLock}; volatile for unsynchronized reads.
     */
    private volatile GraphServiceClient<Request> graphClient;

    /**
     * Client information for logging.
     * Writes synchronized via {@link #connectionLock}; volatile for unsynchronized reads.
     */
    private volatile ClientInfo clientInfo;

    // ==================== Thread Safety Locks ====================

    /**
     * Lock for token refresh to prevent thundering herd.
     */
    private final ReentrantLock tokenRefreshLock = new ReentrantLock();

    /**
     * Lock for connection operations.
     */
    private final Object connectionLock = new Object();

    // ==================== Circuit Breaker State ====================

    /**
     * Count of consecutive failures.
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /**
     * Time when circuit breaker should reset.
     */
    private volatile Instant circuitResetTime = Instant.MIN;

    // ==================== Inner Classes ====================

    /**
     * Custom exception for GraphAuthProvider-specific errors.
     *
     * @deprecated Use {@link GraphAuthException} instead. This class is kept for backward compatibility
     *             and will be removed in a future version.
     */
    @Deprecated(since = "1.1.0", forRemoval = true)
    public static class GraphAuthProviderException extends GraphAuthException {

        public GraphAuthProviderException(String message, Throwable cause) {
            super(message, cause, false);
        }

        public GraphAuthProviderException(String message, Throwable cause, boolean isCircuitOpen) {
            super(message, cause, isCircuitOpen);
        }
    }

    /**
     * Immutable record for client identification in logs.
     */
    private record ClientInfo(UUID clientId, UUID tenantId) {
        @Override
        public String toString() {
            return "ClientInfo[clientId=" + clientId + ", tenantId=" + tenantId + "]";
        }
    }

    // ==================== Constructors ====================

    /**
     * Creates a GraphAuthProvider with full dependency injection support.
     * <p>
     * Use this constructor for maximum testability and control.
     * </p>
     *
     * @param config        Configuration settings
     * @param clientFactory Factory for creating MSAL clients
     * @param metrics       Metrics collector
     * @param executor      Executor for async operations (provider will NOT shut this down)
     */
    public GraphAuthProvider(
            GraphAuthConfig config,
            ConfidentialClientFactory clientFactory,
            TokenMetrics metrics,
            ExecutorService executor) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
        this.tokenRefreshExecutor = Objects.requireNonNull(executor, "executor cannot be null");
        this.ownsExecutor = false;
    }

    /**
     * Creates a GraphAuthProvider with dependency injection (creates its own executor).
     *
     * @param config        Configuration settings
     * @param clientFactory Factory for creating MSAL clients
     * @param metrics       Metrics collector
     */
    public GraphAuthProvider(
            GraphAuthConfig config,
            ConfidentialClientFactory clientFactory,
            TokenMetrics metrics) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
        this.tokenRefreshExecutor = createVirtualThreadExecutor();
        this.ownsExecutor = true;
    }

    /**
     * Creates a GraphAuthProvider with custom configuration.
     *
     * @param config Configuration settings
     */
    public GraphAuthProvider(GraphAuthConfig config) {
        this(config, DefaultConfidentialClientFactory.INSTANCE, TokenMetrics.NOOP);
    }

    /**
     * Creates a GraphAuthProvider with default configuration.
     */
    public GraphAuthProvider() {
        this(GraphAuthConfig.defaults());
    }

    // ==================== Factory Methods ====================

    /**
     * Creates a new GraphAuthProvider with default settings.
     *
     * @return New GraphAuthProvider instance
     */
    public static GraphAuthProvider create() {
        return new GraphAuthProvider();
    }

    /**
     * Creates a new GraphAuthProvider with custom configuration.
     *
     * @param config Configuration settings
     * @return New GraphAuthProvider instance
     */
    public static GraphAuthProvider create(GraphAuthConfig config) {
        return new GraphAuthProvider(config);
    }

    /**
     * Creates a new GraphAuthProvider with custom configuration and metrics.
     *
     * @param config  Configuration settings
     * @param metrics Metrics collector
     * @return New GraphAuthProvider instance
     */
    public static GraphAuthProvider create(GraphAuthConfig config, TokenMetrics metrics) {
        return new GraphAuthProvider(config, DefaultConfidentialClientFactory.INSTANCE, metrics);
    }

    // ==================== IGraphAuthProvider Implementation ====================

    /**
     * {@inheritDoc}
     *
     * <p><b>Thread Safety:</b> Uses synchronized block to prevent concurrent connection attempts.</p>
     *
     * <p><b>Security:</b> The clientSecret array is zeroed out after use, regardless of success or failure.</p>
     */
    @Override
    public void connect(UUID clientId, UUID tenantId, char[] clientSecret) {
        Objects.requireNonNull(clientId, "clientId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(clientSecret, "clientSecret cannot be null");

        synchronized (connectionLock) {
            if (app != null) {
                LOGGER.info("Already connected to Microsoft Graph for clientId={}", clientId);
                return;
            }

            this.clientInfo = new ClientInfo(clientId, tenantId);
            LOGGER.info("Connecting to Microsoft Graph for {}", clientInfo);

            long startTime = System.currentTimeMillis();
            try {
                // Build MSAL client - NOTE: String secret remains in memory until GC
                // This is a known limitation of MSAL4J library
                String secretString = CharBuffer.wrap(clientSecret).toString();
                String authority = config.authorityUrlTemplate().formatted(tenantId);

                app = clientFactory.create(
                        clientId.toString(),
                        ClientCredentialFactory.createFromSecret(secretString),
                        authority
                );

                // Acquire initial token
                cachedAuthResult.set(acquireTokenWithRetry());

                // Build Graph client with auto-refresh authentication provider
                graphClient = GraphServiceClient.builder()
                        .authenticationProvider(requestUrl ->
                                CompletableFuture.supplyAsync(this::getValidAccessToken, tokenRefreshExecutor))
                        .buildClient();

                // Reset circuit breaker on successful connection
                resetCircuitBreaker();

                metrics.recordConnection(true);
                LOGGER.info("Successfully connected to Microsoft Graph for {} in {}ms",
                        clientInfo, System.currentTimeMillis() - startTime);

            } catch (Exception e) {
                // Reset state on failure to allow retry
                app = null;
                graphClient = null;
                cachedAuthResult.set(null);

                metrics.recordConnection(false);
                LOGGER.error("Failed to connect to Microsoft Graph for {}: {}",
                        clientInfo, e.getMessage(), e);
                throw new GraphAuthException("Failed to connect to Microsoft Graph", e);
            } finally {
                zeroOutCharArray(clientSecret);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GraphServiceClient<Request> getClient() {
        GraphServiceClient<Request> client = graphClient;
        if (client == null) {
            throw new IllegalStateException("Not connected. Call connect() first.");
        }
        return client;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isConnected() {
        return app != null && graphClient != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void disconnect() {
        synchronized (connectionLock) {
            if (app == null) {
                LOGGER.debug("Already disconnected");
                return;
            }

            LOGGER.info("Disconnecting from Microsoft Graph for {}", clientInfo);
            app = null;
            cachedAuthResult.set(null);
            graphClient = null;
            clientInfo = null;
            resetCircuitBreaker();
            LOGGER.info("Disconnected from Microsoft Graph");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        disconnect();
        if (ownsExecutor) {
            shutdownExecutor();
        }
    }

    // ==================== Token Management ====================

    /**
     * Retrieves a valid access token, automatically refreshing if expired.
     *
     * <p><b>Thread Safety:</b> Uses double-checked locking with timeout to prevent thundering herd
     * and indefinite blocking.</p>
     *
     * <p><b>Circuit Breaker:</b> Fails fast if circuit is open.</p>
     *
     * @return Valid access token string
     * @throws GraphAuthException if token cannot be obtained or lock acquisition times out
     */
    private String getValidAccessToken() {
        // Fast path: check circuit breaker
        checkCircuitBreaker();

        // Fast path: return cached token if valid
        IAuthenticationResult current = cachedAuthResult.get();
        if (current != null && !isTokenExpired(current)) {
            metrics.recordTokenCacheHit();
            return current.accessToken();
        }

        // Slow path: acquire lock with timeout and refresh token
        boolean lockAcquired;
        try {
            lockAcquired = tokenRefreshLock.tryLock(
                    TOKEN_REFRESH_LOCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new GraphAuthException("Token retrieval interrupted while waiting for lock", ie);
        }

        if (!lockAcquired) {
            throw new GraphAuthException(
                    "Failed to acquire token refresh lock within " + TOKEN_REFRESH_LOCK_TIMEOUT.toSeconds() +
                    " seconds. This may indicate a deadlock or hung thread.", null);
        }

        try {
            // Double-check after acquiring lock (another thread may have refreshed)
            current = cachedAuthResult.get();
            if (current != null && !isTokenExpired(current)) {
                metrics.recordTokenCacheHit();
                return current.accessToken();
            }

            // Actually refresh the token
            long startTime = System.currentTimeMillis();
            try {
                IAuthenticationResult newToken = acquireTokenWithRetry();
                cachedAuthResult.set(newToken);

                // Reset circuit breaker on success
                resetCircuitBreaker();

                long latency = System.currentTimeMillis() - startTime;
                metrics.recordTokenRefresh(latency, true);
                LOGGER.debug("Token refreshed in {}ms. Expires: {}", latency, newToken.expiresOnDate());

                return newToken.accessToken();
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - startTime;
                metrics.recordTokenRefresh(latency, false);
                recordFailure();
                throw e;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new GraphAuthException("Token retrieval interrupted", ie);
        } catch (GraphAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphAuthException("Failed to retrieve access token", e);
        } finally {
            tokenRefreshLock.unlock();
        }
    }

    /**
     * Acquires token with retry and exponential backoff.
     */
    private IAuthenticationResult acquireTokenWithRetry()
            throws ExecutionException, InterruptedException, TimeoutException {

        ClientCredentialParameters params = ClientCredentialParameters
                .builder(config.scopes())
                .build();

        Duration backoff = config.initialBackoff();
        int attempt = 1;

        while (true) {
            try {
                return app.acquireToken(params)
                        .get(config.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS);

            } catch (ExecutionException | TimeoutException e) {
                if (attempt >= config.maxRetries()) {
                    LOGGER.error("Token acquisition failed after {} attempts for {}",
                            config.maxRetries(), clientInfo);
                    throw e;
                }

                metrics.recordRetryAttempt(attempt, backoff.toMillis());
                LOGGER.warn("Token acquisition attempt {} of {} failed, retrying after {}ms: {}",
                        attempt, config.maxRetries(), backoff.toMillis(), e.getMessage());

                try {
                    TimeUnit.MILLISECONDS.sleep(backoff.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }

                // Exponential backoff with max cap
                backoff = backoff.multipliedBy(2);
                if (backoff.compareTo(config.maxBackoff()) > 0) {
                    backoff = config.maxBackoff();
                }
                attempt++;
            }
        }
    }

    /**
     * Checks if token is expired or within the expiry buffer.
     */
    private boolean isTokenExpired(IAuthenticationResult result) {
        try {
            if (result.expiresOnDate() == null) {
                return true;
            }
            Instant effectiveExpiry = result.expiresOnDate().toInstant()
                    .minus(config.tokenExpiryBuffer());
            return Instant.now().isAfter(effectiveExpiry);
        } catch (Exception e) {
            LOGGER.warn("Unable to evaluate token expiry, treating as expired: {}", e.getMessage());
            return true;
        }
    }

    // ==================== Circuit Breaker ====================

    /**
     * Checks circuit breaker state and throws if open.
     */
    private void checkCircuitBreaker() {
        if (isCircuitOpen()) {
            metrics.recordCircuitBreakerState(true);
            throw new GraphAuthException(
                    "Circuit breaker is open - Azure AD appears unavailable. Retry after: " + circuitResetTime,
                    null,
                    true
            );
        }
    }

    /**
     * Checks if the circuit breaker is currently open.
     * <p>
     * Uses atomic compare-and-set for the half-open probe to ensure only one thread
     * is allowed through when transitioning from open to half-open state.
     * </p>
     */
    private boolean isCircuitOpen() {
        int currentFailures = consecutiveFailures.get();
        if (currentFailures >= CIRCUIT_BREAKER_FAILURE_THRESHOLD) {
            // Check if reset timeout has passed (half-open opportunity)
            if (Instant.now().isAfter(circuitResetTime)) {
                // Atomically try to claim the single half-open probe slot
                // Only one thread will succeed; others will see circuit as still open
                if (consecutiveFailures.compareAndSet(
                        CIRCUIT_BREAKER_FAILURE_THRESHOLD,
                        CIRCUIT_BREAKER_FAILURE_THRESHOLD - 1)) {
                    LOGGER.debug("Circuit breaker entering half-open state, allowing probe request");
                    return false;
                }
                // Another thread claimed the probe slot, circuit still effectively open for us
                return true;
            }
            return true;
        }
        return false;
    }

    /**
     * Records a failure and potentially opens the circuit.
     */
    private void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= CIRCUIT_BREAKER_FAILURE_THRESHOLD) {
            circuitResetTime = Instant.now().plus(CIRCUIT_BREAKER_RESET_TIMEOUT);
            metrics.recordCircuitBreakerState(true);
            LOGGER.warn("Circuit breaker opened after {} consecutive failures. Reset at: {}",
                    failures, circuitResetTime);
        }
    }

    /**
     * Resets the circuit breaker to closed state.
     */
    private void resetCircuitBreaker() {
        if (consecutiveFailures.getAndSet(0) > 0) {
            circuitResetTime = Instant.MIN;
            metrics.recordCircuitBreakerState(false);
            LOGGER.debug("Circuit breaker reset to closed state");
        }
    }

    // ==================== Utility Methods ====================

    /**
     * Securely wipes a character array.
     */
    private void zeroOutCharArray(char[] array) {
        if (array != null) {
            Arrays.fill(array, '\0');
        }
    }

    /**
     * Creates virtual thread executor for token refresh operations.
     */
    private static ExecutorService createVirtualThreadExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(THREAD_NAME_PREFIX, 0).factory()
        );
    }

    /**
     * Shuts down the executor service gracefully.
     */
    private void shutdownExecutor() {
        LOGGER.debug("Shutting down token refresh executor");
        tokenRefreshExecutor.shutdown();
        try {
            if (tokenRefreshExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.info("Token refresh executor shut down gracefully");
            } else {
                LOGGER.warn("Token refresh executor did not terminate gracefully within 5 seconds, forcing shutdown");
                tokenRefreshExecutor.shutdownNow();
                LOGGER.info("Token refresh executor forced shutdown completed");
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for executor shutdown, forcing immediate termination");
            tokenRefreshExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== Accessors for Testing ====================

    /**
     * Returns the current configuration (for testing/debugging).
     *
     * @return Current GraphAuthConfig
     */
    public GraphAuthConfig getConfig() {
        return config;
    }

    /**
     * Returns circuit breaker failure count (for testing/monitoring).
     *
     * @return Current consecutive failure count
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}

