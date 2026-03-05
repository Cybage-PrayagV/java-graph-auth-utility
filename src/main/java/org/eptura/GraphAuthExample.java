package org.eptura;

import com.microsoft.graph.requests.GraphServiceClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.UUID;

/**
 * Example demonstrating various ways to use GraphAuthProvider.
 * <p>
 * Set the following environment variables before running:
 * <ul>
 *   <li>AZURE_CLIENT_ID - Your Azure AD application (client) ID</li>
 *   <li>AZURE_TENANT_ID - Your Azure AD tenant (directory) ID</li>
 *   <li>AZURE_CLIENT_SECRET - Your Azure AD application client secret</li>
 * </ul>
 * </p>
 */
public class GraphAuthExample {

    private static final Logger LOGGER = LogManager.getLogger(GraphAuthExample.class);

    // Placeholder UUIDs - replace with actual credentials or use environment variables
    private static final String DEFAULT_CLIENT_ID = "00000000-0000-0000-0000-000000000000";
    private static final String DEFAULT_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    public static void main(String[] args) {
        // Load credentials from environment variables with fallback to placeholders
        UUID clientId;
        UUID tenantId;
        char[] clientSecret;

        try {
            String clientIdEnv = System.getenv("AZURE_CLIENT_ID");
            String tenantIdEnv = System.getenv("AZURE_TENANT_ID");
            String secretEnv = System.getenv("AZURE_CLIENT_SECRET");

            if (clientIdEnv == null || tenantIdEnv == null || secretEnv == null) {
                LOGGER.warn("Environment variables not set. Using placeholder values.");
                LOGGER.warn("Set AZURE_CLIENT_ID, AZURE_TENANT_ID, and AZURE_CLIENT_SECRET to run with real credentials.");
                clientId = UUID.fromString(DEFAULT_CLIENT_ID);
                tenantId = UUID.fromString(DEFAULT_TENANT_ID);
                clientSecret = "placeholder-secret".toCharArray();
            } else {
                clientId = UUID.fromString(clientIdEnv);
                tenantId = UUID.fromString(tenantIdEnv);
                clientSecret = secretEnv.toCharArray();
            }
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid UUID format in environment variables: {}", e.getMessage());
            LOGGER.error("Ensure AZURE_CLIENT_ID and AZURE_TENANT_ID are valid UUIDs");
            return;
        }

        // Example 1: Simple usage with defaults
        simpleUsageExample(clientId, tenantId, clientSecret.clone());

        // Example 2: Custom configuration
        customConfigurationExample(clientId, tenantId, clientSecret.clone());

        // Example 3: With metrics
        withMetricsExample(clientId, tenantId, clientSecret.clone());
    }

    /**
     * Simplest usage pattern with default configuration.
    /**
     * Simplest usage pattern with default configuration.
     */
    private static void simpleUsageExample(UUID clientId, UUID tenantId, char[] clientSecret) {
        LOGGER.info("=== Simple Usage Example ===");

        try (GraphAuthProvider provider = GraphAuthProvider.create()) {
            provider.connect(clientId, tenantId, clientSecret);

            GraphServiceClient<Request> client = provider.getClient();

            // Example: Get organization info
            var org = client.organization().buildRequest().get();
            if (org != null) {
                org.getCurrentPage().forEach(o ->
                        LOGGER.info("Organization: {}", o.displayName));
            }

        } catch (GraphAuthException e) {
            if (e.isCircuitOpen()) {
                LOGGER.error("Circuit breaker is open - Azure AD unavailable", e);
            } else {
                LOGGER.error("Authentication failed", e);
            }
        }
    }

    /**
     * Custom configuration with adjusted timeouts and retries.
     */
    private static void customConfigurationExample(UUID clientId, UUID tenantId, char[] clientSecret) {
        LOGGER.info("=== Custom Configuration Example ===");

        GraphAuthConfig config = GraphAuthConfig.builder()
                .tokenExpiryBuffer(Duration.ofMinutes(5))  // Refresh 5 min before expiry
                .acquireTimeout(Duration.ofSeconds(60))    // Longer timeout
                .maxRetries(5)                             // More retries
                .initialBackoff(Duration.ofSeconds(1))     // Start with 1s backoff
                .maxBackoff(Duration.ofSeconds(30))        // Cap at 30s
                .build();

        try (GraphAuthProvider provider = GraphAuthProvider.create(config)) {
            provider.connect(clientId, tenantId, clientSecret);

            LOGGER.info("Connected with custom config: {}", provider.getConfig());
            LOGGER.info("Is connected: {}", provider.isConnected());

        } catch (GraphAuthException e) {
            LOGGER.error("Authentication failed", e);
        }
    }

    /**
     * Usage with metrics integration.
     */
    private static void withMetricsExample(UUID clientId, UUID tenantId, char[] clientSecret) {
        LOGGER.info("=== Metrics Example ===");

        // Custom metrics implementation (logging-based for demo)
        TokenMetrics loggingMetrics = new TokenMetrics() {
            @Override
            public void recordTokenRefresh(long latencyMs, boolean success) {
                LOGGER.info("[METRIC] Token refresh: latency={}ms, success={}", latencyMs, success);
            }

            @Override
            public void recordTokenCacheHit() {
                LOGGER.info("[METRIC] Token cache hit");
            }

            @Override
            public void recordRetryAttempt(int attemptNumber, long backoffMs) {
                LOGGER.info("[METRIC] Retry attempt: #{}, backoff={}ms", attemptNumber, backoffMs);
            }

            @Override
            public void recordCircuitBreakerState(boolean open) {
                LOGGER.info("[METRIC] Circuit breaker state: open={}", open);
            }

            @Override
            public void recordConnection(boolean success) {
                LOGGER.info("[METRIC] Connection: success={}", success);
            }
        };

        try (GraphAuthProvider provider = GraphAuthProvider.create(GraphAuthConfig.defaults(), loggingMetrics)) {
            provider.connect(clientId, tenantId, clientSecret);

            // Make multiple calls to see cache hits
            for (int i = 0; i < 3; i++) {
                provider.getClient();
                // Each getClient() call uses cached token
                LOGGER.info("Call #{}: Client ready", i + 1);
            }

        } catch (GraphAuthException e) {
            LOGGER.error("Authentication failed", e);
        }
    }

    /**
     * Example showing dependency injection for testing.
     */
    public GraphServiceClient<Request> createTestableClient(
            IGraphAuthProvider authProvider,
            UUID clientId,
            UUID tenantId,
            char[] clientSecret) {

        authProvider.connect(clientId, tenantId, clientSecret);
        return authProvider.getClient();
    }
}

