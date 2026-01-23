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
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe utility for authenticating and connecting to Microsoft Graph API using OAuth 2.0 client credentials flow.
 * <p>
 * <b>Authentication Flow:</b>
 * <ol>
 *   <li>Connects to Azure AD using app registration credentials (clientId, tenantId, clientSecret)</li>
 *   <li>Acquires OAuth 2.0 access token from Microsoft identity platform</li>
 *   <li>Caches token and automatically refreshes when expired (before 2-minute expiry buffer)</li>
 *   <li>Provides authenticated GraphServiceClient for Microsoft Graph API operations</li>
 * </ol>
 * </p>
 * <p>
 * <b>Usage Example:</b>
 * <pre>
 *     <code>
 *     // Get singleton instance
 *     GraphAuthProvider provider = GraphAuthProvider.getInstance();
 *     try {
 *         // Connect with Azure AD app credentials
 *         provider.connect(clientId, tenantId, clientSecret);
 *
 *         // Get authenticated client for Graph API calls
 *         GraphServiceClient&lt;Request&gt; client = provider.getClient();
 *
 *         // Use client for Graph API operations (e.g., fetch users)
 *         UserCollectionPage users = client.users().buildRequest().get();
 *     } finally {
 *         // Disconnect when done (clears cached tokens)
 *         provider.close();
 *     }
 *     </code>
 * </pre>
 * </p>
 * <p>
 * <b>Thread Safety:</b> All public methods are synchronized. Token refresh occurs asynchronously
 * using virtual threads for lightweight concurrency without blocking.
 * </p>
 * <p>
 * <b>Security:</b> Client secrets are securely wiped from memory after use. Tokens are cached
 * in-memory only and never persisted to disk.
 * </p>
 * <p>
 * <b>Troubleshooting:</b>
 * <ul>
 *   <li>Check logs for "Failed to acquire token" - verify Azure AD credentials are correct</li>
 *   <li>If seeing timeouts, check network connectivity to login.microsoftonline.com</li>
 *   <li>Token refresh failures may indicate expired/revoked app registration</li>
 *   <li>Enable DEBUG logging to see token expiry times and refresh events</li>
 * </ul>
 * </p>
 * @see <a href="https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-client-creds-grant-flow">
 *      OAuth 2.0 Client Credentials Flow</a>
 */
public final class GraphAuthProvider implements AutoCloseable {
    /** Logger for tracking authentication events, token refresh, and debugging connection issues */
    private static final Logger LOGGER = LogManager.getLogger(GraphAuthProvider.class);

    /**
     * OAuth 2.0 scopes requested from Microsoft Graph.
     * ".default" scope grants all permissions configured in Azure AD app registration.
     * Debug: If seeing permission errors, verify app registration has required API permissions.
     */
    private static final Set<String> SCOPES = Set.of("https://graph.microsoft.com/.default");

    /**
     * Time buffer before actual token expiry when we consider token "expired" and trigger refresh.
     * Set to 2 minutes to proactively refresh tokens before they expire, preventing API call failures.
     * Debug: If seeing authentication failures, check if this buffer is sufficient for your workload.
     */
    private static final Duration TOKEN_EXPIRY_BUFFER = Duration.ofMinutes(2);

    /**
     * Azure AD authority URL template. '%s' is replaced with Azure tenant ID at runtime.
     * Example result: {@code https://login.microsoftonline.com/12345678-1234-1234-1234-123456789abc}
     * Debug: Verify tenant ID is correct if seeing "AADSTS90002: Tenant not found" errors.
     */
    private static final String AUTHORITY_URL_TEMPLATE = "https://login.microsoftonline.com/%s";

    /**
     * Thread name prefix for virtual threads used in token refresh operations.
     * Helps identify GraphAuthProvider threads in thread dumps and profiling tools.
     */
    private static final String THREAD_NAME = "GraphAuthProvider-TokenRefresher";

    /**
     * Maximum retry attempts for token acquisition when transient failures occur (network errors, timeouts).
     * Retries use exponential backoff: 250ms, 500ms, 1000ms.
     * Debug: Increase if experiencing intermittent network issues. Check logs for retry attempt messages.
     */
    private static final int TOKEN_ACQUIRE_MAX_RETRIES = 3;

    /**
     * Initial backoff duration for first retry attempt (250 milliseconds).
     * Subsequent retries double this value: 500ms, 1000ms, etc.
     * Debug: Increase for rate-limited scenarios or slow network connections.
     */
    private static final Duration TOKEN_ACQUIRE_INITIAL_BACKOFF = Duration.ofMillis(250);

    /**
     * Maximum time to wait for token acquisition before timing out (30 seconds).
     * Prevents indefinite hangs when Azure AD is unreachable or experiencing issues.
     * Debug: If seeing TimeoutException, check network connectivity and Azure AD service status.
     */
    private static final Duration TOKEN_ACQUIRE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Executor service for asynchronous token refresh operations using virtual threads (Java 21).
     * Virtual threads are lightweight and efficient for I/O-bound operations like HTTP token requests.
     * Each token refresh gets its own virtual thread that's automatically cleaned up when complete.
     * Debug: Monitor thread dumps - virtual threads appear with "GraphAuthProvider-TokenRefresher" prefix.
     */
    private static final ExecutorService TOKEN_REFRESH_EXECUTOR =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(THREAD_NAME).factory());

    /**
     * Microsoft Authentication Library (MSAL) client for acquiring OAuth tokens from Azure AD.
     * Initialized during connect() and set to null during disconnect().
     * Debug: If null, connect() hasn't been called yet or disconnect() was called.
     */
    private ConfidentialClientApplication app;

    /**
     * Thread-safe cache for the current OAuth access token result.
     * Contains access token string, expiry time, and other token metadata.
     * AtomicReference ensures thread-safe reads/writes without explicit synchronization.
     * Debug: Check logs for "Token refreshed successfully" to track refresh events.
     */
    private final AtomicReference<IAuthenticationResult> cachedAuthResult = new AtomicReference<>();

    /**
     * Authenticated Microsoft Graph client for making API calls.
     * Automatically supplies valid access tokens for each request via authentication provider.
     * Debug: If null when getClient() called, connection hasn't been established - call connect() first.
     */
    private GraphServiceClient<Request> graphClient;

    /**
     * Stores client and tenant IDs for logging and debugging purposes.
     * Helps track which Azure AD app/tenant combination is being used.
     * Debug: Check logs for this info when troubleshooting multi-tenant scenarios.
     */
    private ClientInfo clientInfo;

    /**
     * Private constructor prevents direct instantiation - enforces singleton pattern.
     * Use {@link #getInstance()} to get the single instance of this class.
     */
    private GraphAuthProvider() {}

    /**
     * Holder class for lazy-loaded, thread-safe singleton instance (Bill Pugh Singleton Pattern).
     * The instance is created only when getInstance() is first called, not when the class loads.
     * Java class loading mechanism guarantees thread-safety without explicit synchronization.
     * Debug: This pattern ensures only one GraphAuthProvider exists per JVM.
     */
    private static class Holder {
        /**
         * Singleton instance - created lazily on first access to Holder class.
         * Thread-safe initialization guaranteed by Java class loader.
         */
        private static final GraphAuthProvider INSTANCE = new GraphAuthProvider();
    }

    /**
     * Returns the singleton instance of GraphAuthProvider.
     * Thread-safe and lazy-loaded - instance created only on first call.
     * @return The single GraphAuthProvider instance for this JVM
     */
    public static GraphAuthProvider getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Custom runtime exception for GraphAuthProvider-specific errors.
     * Wraps underlying authentication, network, or configuration failures.
     * Debug: Check the cause (getCause()) for root exception details.
     */
    public static class GraphAuthProviderException extends RuntimeException {
        /**
         * Constructs a new GraphAuthProviderException with message and underlying cause.
         * @param message Human-readable error description
         * @param cause   The underlying exception that caused this error (maybe null)
         */
        public GraphAuthProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Immutable record holding Azure AD client and tenant identifiers.
     * Used for logging and tracking which Azure AD app/tenant is being used.
     * Debug: Check logs for ClientInfo toString() output to verify correct credentials are being used.
     * @param clientId Azure AD application (client) ID from app registration
     * @param tenantId Azure AD tenant (directory) ID where app is registered
     */
    private record ClientInfo(UUID clientId, UUID tenantId) {}

    /**
     * Establishes connection to Microsoft Graph API using Azure AD app registration credentials.
     * <p>
     * This method performs the following operations:
     * <ol>
     *   <li>Validates all parameters are non-null</li>
     *   <li>Checks if already connected - returns early if connection exists</li>
     *   <li>Builds MSAL ConfidentialClientApplication with provided credentials</li>
     *   <li>Acquires initial OAuth 2.0 access token from Azure AD (with retry logic)</li>
     *   <li>Creates authenticated GraphServiceClient with automatic token refresh</li>
     *   <li>Securely wipes client secret from memory after use</li>
     * </ol>
     * </p>
     * <p>
     * <b>Security Note:</b> The client secret is passed as char[] instead of String to allow
     * secure wiping from memory. The array is zeroed out in the finally block.
     * </p>
     * <p>
     * <b>Thread Safety:</b> This method is synchronized to prevent concurrent connection attempts.
     * </p>
     * @param clientId     Azure AD application (client) ID - found in Azure Portal app registration
     * @param tenantId     Azure AD tenant (directory) ID - found in Azure Portal overview
     * @param clientSecret Azure AD application client secret - generated in Certificates & secrets
     * @throws NullPointerException       if any parameter is null
     * @throws GraphAuthProviderException if connection fails due to:
     *                                    - Invalid credentials (check clientId/tenantId/secret)
     *                                    - Network issues (check connectivity to login.microsoftonline.com)
     *                                    - Token acquisition timeout (check TOKEN_ACQUIRE_TIMEOUT)
     *                                    - Azure AD service issues (check Azure status page)
     * @see #disconnect() To clear the connection and cached tokens
     * @see #isConnected() To check connection status
     */
    public synchronized void connect(UUID clientId, UUID tenantId, char[] clientSecret) {
        Objects.requireNonNull(clientId, "clientId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(clientSecret, "clientSecret cannot be null");
        this.clientInfo = new ClientInfo(clientId, tenantId);
        // Early return if already connected with same or different credentials
        if (app != null) {
            LOGGER.info("Already connected to Microsoft Graph for {}", clientInfo);
            return;
        }
        LOGGER.info("Connecting to Microsoft Graph for clientId={} tenantId={}", clientId, tenantId);
        try {
            // Convert char[] to String for MSAL library (will be wiped in finally block)
            String secretString = CharBuffer.wrap(clientSecret).toString();
            // Build MSAL confidential client application with Azure AD authority URL
            app = ConfidentialClientApplication.builder(clientId.toString(),
                            ClientCredentialFactory.createFromSecret(secretString))
                    .authority(AUTHORITY_URL_TEMPLATE.formatted(tenantId))
                    .build();
            // Acquire initial token with retry logic (may take up to 30 seconds)
            cachedAuthResult.set(acquireTokenWithRetry());
            // Build Graph client with custom authentication provider that auto-refreshes tokens
            graphClient = GraphServiceClient.builder()
                    .authenticationProvider(requestUrl ->
                            CompletableFuture.supplyAsync(this::getValidAccessToken, TOKEN_REFRESH_EXECUTOR))
                    .buildClient();
            LOGGER.info("Connected to Microsoft Graph for tenantId={}", tenantId);
        } catch (Exception e) {
            LOGGER.error("Failed to connect to Microsoft Graph (clientId={}, tenantId={}): {}",
                    clientId, tenantId, e.getMessage(), e);
            throw new GraphAuthProviderException("Failed to connect to Microsoft Graph", e);
        } finally {
            // Security: Securely wipe client secret from memory to prevent exposure
            zeroOutCharArray(clientSecret);
        }
    }

    /**
     * Returns the authenticated Microsoft Graph client for making API calls.
     * <p>
     * The returned client automatically handles token refresh when tokens expire.
     * All Graph API operations should use this client instance.
     * </p>
     * <p>
     * <b>Precondition:</b> {@link #connect(UUID, UUID, char[])} must be called first.
     * </p>
     * <p>
     * <b>Usage Example:</b>
     * <pre><code>
     *   GraphServiceClient&lt;Request&gt; client = provider.getClient();
     *   UserCollectionPage users = client.users().buildRequest().top(10).get();
     * </code></pre>
     * </p>
     * @return Authenticated GraphServiceClient instance ready for API calls
     * @throws IllegalStateException if not connected (connect() not called or disconnect() was called)
     *                               Debug: Check if connect() was successfully called and didn't throw exception
     * @see #connect(UUID, UUID, char[]) To establish connection first
     * @see #isConnected() To check if connection is established
     */
    public synchronized GraphServiceClient<Request> getClient() {
        if (graphClient == null) {
            LOGGER.warn("Graph client not initialized (app: {}). Call connect() first.",
                    app == null ? "null" : "initialized");
            throw new IllegalStateException("Not connected. Call connect() first.");
        }
        LOGGER.info("Returning authenticated GraphServiceClient for {}", clientInfo);
        return graphClient;
    }

    /**
     * Checks if the provider is currently connected to Microsoft Graph.
     * <p>
     * Returns true only if both the MSAL app is initialized AND the Graph client is ready.
     * Use this before calling {@link #getClient()} to avoid IllegalStateException.
     * </p>
     * @return true if connected and ready for API calls, false otherwise
     * @see #connect(UUID, UUID, char[]) To establish connection
     * @see #getClient() To get the authenticated client
     */
    public synchronized boolean isConnected() {
        return app != null && graphClient != null;
    }

    /**
     * Disconnects from Microsoft Graph and clears all cached credentials and tokens.
     * <p>
     * This method performs cleanup:
     * <ul>
     *   <li>Nullifies the MSAL client application</li>
     *   <li>Clears cached OAuth tokens from memory</li>
     *   <li>Removes the Graph client instance</li>
     * </ul>
     * </p>
     * <p>
     * <b>Note:</b> After disconnect(), you must call {@link #connect(UUID, UUID, char[])} again
     * before using {@link #getClient()}.
     * </p>
     * <p>
     * <b>Thread Safety:</b> This method is synchronized and safe to call from multiple threads.
     * </p>
     * @see #connect(UUID, UUID, char[]) To reconnect after disconnecting
     * @see #close() Alternative method that calls disconnect() (for AutoCloseable)
     */
    public synchronized void disconnect() {
        LOGGER.info("Disconnecting from Microsoft Graph for {}", clientInfo);
        app = null;
        cachedAuthResult.set(null);
        graphClient = null;
        LOGGER.info("Disconnected from Microsoft Graph for tenant {}", clientInfo);
    }

    /**
     * Retrieves a valid access token, automatically refreshing if expired or near expiry.
     * <p>
     * This method implements the token lifecycle management:
     * <ol>
     *   <li>Checks if cached token exists and is still valid</li>
     *   <li>If token is null or expired (within 2-min buffer), acquires new token from Azure AD</li>
     *   <li>Updates cache with new token and returns access token string</li>
     *   <li>If token is valid, returns cached access token immediately</li>
     * </ol>
     * </p>
     * <p>
     * <b>Thread Safety:</b> Uses AtomicReference for thread-safe cache access.
     * Multiple threads may call this simultaneously - only one will refresh the token.
     * </p>
     * <p>
     * <b>Error Handling:</b>
     * <ul>
     *   <li>InterruptedException: Preserves interrupt flag and throws GraphAuthProviderException</li>
     *   <li>Other exceptions: Wraps in GraphAuthProviderException with error details in logs</li>
     * </ul>
     * </p>
     * @return Valid OAuth 2.0 access token string (not null)
     * @throws GraphAuthProviderException if token retrieval or refresh fails
     *                                    Debug: Check logs for underlying cause (network, auth failure, etc.)
     */
    private String getValidAccessToken() {
        try {
            var current = cachedAuthResult.get();
            // Check if we need to acquire a new token (no cache or expired)
            if (current == null || isTokenExpired(current)) {
                var newToken = acquireTokenWithRetry();
                cachedAuthResult.set(newToken);
                LOGGER.debug("Token refreshed successfully. New expiry: {}", newToken.expiresOnDate());
                return newToken.accessToken();
            }
            // Return cached token if still valid
            return current.accessToken();
        } catch (InterruptedException ie) {
            // Preserve interrupt status for proper thread handling
            Thread.currentThread().interrupt();
            LOGGER.error("Token retrieval interrupted: {}", ie.getMessage(), ie);
            throw new GraphAuthProviderException("Token retrieval interrupted", ie);
        } catch (Exception e) {
            LOGGER.error("Failed to refresh or retrieve access token: {}", e.getMessage(), e);
            throw new GraphAuthProviderException("Failed to retrieve access token", e);
        }
    }

    /**
     * Acquires a new OAuth 2.0 access token from Azure AD with automatic retry and exponential backoff.
     * <p>
     * <b>Retry Strategy:</b>
     * <ul>
     *   <li>Max attempts: 3 (configurable via TOKEN_ACQUIRE_MAX_RETRIES)</li>
     *   <li>Timeout per attempt: 30 seconds (configurable via TOKEN_ACQUIRE_TIMEOUT)</li>
     *   <li>Backoff pattern: 250ms → 500ms → 1000ms (exponential doubling)</li>
     *   <li>Retries on: ExecutionException (auth/network errors) and TimeoutException</li>
     * </ul>
     * </p>
     * <p>
     * <b>How It Works:</b>
     * <ol>
     *   <li>Attempts to acquire token with configured timeout</li>
     *   <li>On failure, checks if max retries reached - if yes, throws exception</li>
     *   <li>Otherwise, logs warning and sleeps for exponential backoff duration</li>
     *   <li>Doubles backoff time for next retry and increments attempt counter</li>
     *   <li>Repeats until success or max retries exhausted</li>
     * </ol>
     * </p>
     * <p>
     * <b>Debug Tips:</b>
     * <ul>
     *   <li>Check logs for "Token acquisition attempt X of Y failed" to see retry behavior</li>
     *   <li>ExecutionException usually means auth failure (bad credentials) or network error</li>
     *   <li>TimeoutException means Azure AD didn't respond within 30 seconds</li>
     *   <li>If all retries fail, check network connectivity and Azure AD status</li>
     * </ul>
     * </p>
     * @return IAuthenticationResult containing access token, expiry time, and metadata
     * @throws ExecutionException   if token acquisition fails after all retries
     *                              Debug: Check inner exception for root cause (MsalException, IOException, etc.)
     * @throws InterruptedException if thread is interrupted during sleep/wait
     *                              Debug: Another thread called interrupt() - check calling code
     * @throws TimeoutException     if token acquisition times out on final retry attempt
     *                              Debug: Azure AD is slow/unreachable - check network and service status
     */
    private IAuthenticationResult acquireTokenWithRetry() throws ExecutionException, InterruptedException, TimeoutException {
        var params = ClientCredentialParameters.builder(SCOPES).build();
        Duration backoff = TOKEN_ACQUIRE_INITIAL_BACKOFF;
        int attempt = 1;
        while (true) {
            try {
                // Attempt to acquire token with timeout to prevent indefinite blocking
                return app.acquireToken(params).get(TOKEN_ACQUIRE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                // Check if we've used all retry attempts
                if (attempt >= TOKEN_ACQUIRE_MAX_RETRIES) {
                    LOGGER.error("Failed to acquire token after {} attempts", TOKEN_ACQUIRE_MAX_RETRIES);
                    throw e;
                }
                // Log retry attempt with backoff information for debugging
                LOGGER.warn("Token acquisition attempt {} of {} failed, retrying after {}ms",
                        attempt, TOKEN_ACQUIRE_MAX_RETRIES, backoff.toMillis());
                try {
                    // Apply exponential backoff before next retry
                    TimeUnit.MILLISECONDS.sleep(backoff.toMillis());
                } catch (InterruptedException ie) {
                    // Preserve interrupt status and propagate to caller
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                // Double the backoff time for next attempt (exponential backoff)
                backoff = backoff.multipliedBy(2).truncatedTo(ChronoUnit.MILLIS);
                attempt++;
            }
        }
    }

    /**
     * Checks if the OAuth access token is expired or approaching expiry.
     * <p>
     * <b>Expiry Logic:</b>
     * <ul>
     *   <li>Tokens are considered "expired" if within 2 minutes of actual expiry time</li>
     *   <li>This buffer (TOKEN_EXPIRY_BUFFER) prevents using tokens that expire mid-request</li>
     *   <li>Example: Token expires at 10:00 AM → considered expired at 9:58 AM</li>
     * </ul>
     * </p>
     * <p>
     * <b>Conservative Approach:</b> If any error occurs reading expiry date, treats token as expired.
     * This ensures we never use a potentially invalid token.
     * </p>
     * @param result Authentication result containing token and expiry metadata
     * @return true if token is expired/near expiry or expiry cannot be determined, false if valid
     * Debug: Check DEBUG logs for expiry evaluation details. If seeing frequent refreshes,
     *        token lifetime may be too short (check Azure AD token lifetime policies).
     */
    private boolean isTokenExpired(IAuthenticationResult result) {
        try {
            var expiryDate = result.expiresOnDate();
            // If expiry date is missing, treat as expired for safety
            if (expiryDate == null) {
                return true;
            }
            // Calculate effective expiry (actual expiry - 2 minute buffer)
            var expiry = expiryDate.toInstant().minus(TOKEN_EXPIRY_BUFFER);
            // Token is expired if current time is past effective expiry
            return Instant.now().isAfter(expiry);
        } catch (Exception e) {
            // Conservative: if we can't determine expiry, assume expired and refresh
            LOGGER.warn("Unable to evaluate token expiry, treating as expired: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Securely wipes the contents of a character array from memory.
     * <p>
     * <b>Security Purpose:</b> Prevents client secrets from remaining in memory where they
     * could be exposed through memory dumps, debugging, or security vulnerabilities.
     * </p>
     * <p>
     * <b>How It Works:</b> Overwrites every character with null character ('\0'),
     * making the original secret unrecoverable from the array.
     * </p>
     * <p>
     * <b>Why char[] instead of String:</b> Strings are immutable in Java and remain in memory
     * until garbage collected. char[] can be immediately wiped after use.
     * </p>
     * @param array Character array to zero out (typically contains client secret)
     *              If null, method safely returns without action.
     * Debug: This is a security best practice - not seeing this called means secrets
     *        may remain in memory longer than necessary.
     */
    private void zeroOutCharArray(char[] array) {
        if (array != null) {
            Arrays.fill(array, '\0');
        }
    }

    /**
     * Closes the GraphAuthProvider and disconnects from Microsoft Graph.
     * <p>
     * Implements {@link AutoCloseable} interface to support try-with-resources pattern.
     * Internally calls {@link #disconnect()} to clear all cached tokens and connections.
     * </p>
     * <p>
     * <b>Try-With-Resources Example:</b>
     * <pre><code>
     *   try (var provider = GraphAuthProvider.getInstance()) {
     *       provider.connect(clientId, tenantId, secret);
     *       // Use provider...
     *   } // Automatically disconnected here
     * </code></pre>
     * </p>
     * <p>
     * <b>Note:</b> Since this is a singleton, subsequent calls to getInstance() will return
     * the same disconnected instance. You'll need to call connect() again.
     * </p>
     * @see #disconnect() For explicit disconnection without AutoCloseable
     */
    @Override
    public void close() {
        disconnect();
    }

    /**
     * Shuts down the token refresh executor service during application shutdown.
     * <p>
     * <b>When to Call:</b> Should be invoked during application shutdown/cleanup phase
     * (e.g., in a shutdown hook or application lifecycle listener).
     * </p>
     * <p>
     * <b>What It Does:</b>
     * <ul>
     *   <li>Immediately stops accepting new token refresh tasks</li>
     *   <li>Attempts to interrupt any running token refresh operations</li>
     *   <li>Prevents resource leaks from executor threads</li>
     * </ul>
     * </p>
     * <p>
     * <b>Warning:</b> After calling shutdown(), the GraphAuthProvider cannot refresh tokens.
     * Only call this when the application is terminating.
     * </p>
     * <p>
     * <b>Thread Safety:</b> Safe to call multiple times (subsequent calls have no effect).
     * </p>
     * <p>
     * Debug: If seeing "Executor already shut down" errors, this was called prematurely.
     *        If seeing thread leaks on shutdown, ensure this is being called.
     */
    public void shutdown() {
        TOKEN_REFRESH_EXECUTOR.shutdownNow();
        LOGGER.info("GraphAuthProvider executor shut down during application shutdown.");
    }
}