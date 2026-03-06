package org.eptura;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration for GraphAuthProvider.
 * <p>
 * Use the {@link Builder} to create custom configurations, or use {@link #defaults()} for standard settings.
 * </p>
 *
 * <p><b>Example:</b></p>
 * <pre>{@code
 * GraphAuthConfig config = GraphAuthConfig.builder()
 *     .tokenExpiryBuffer(Duration.ofMinutes(5))
 *     .maxRetries(5)
 *     .build();
 * }</pre>
 *
 * @param tokenExpiryBuffer Time before actual expiry to trigger refresh (default: 2 minutes)
 * @param acquireTimeout    Maximum wait time for token acquisition (default: 30 seconds)
 * @param maxRetries        Maximum retry attempts for transient failures (default: 3)
 * @param initialBackoff    Initial backoff duration for retries (default: 500ms)
 * @param maxBackoff        Maximum backoff cap for retries (default: 10 seconds)
 * @param scopes            OAuth scopes to request (default: Graph .default scope)
 * @param authorityUrlTemplate Azure AD authority URL template (default: login.microsoftonline.com)
 */
public record GraphAuthConfig(
        Duration tokenExpiryBuffer,
        Duration acquireTimeout,
        int maxRetries,
        Duration initialBackoff,
        Duration maxBackoff,
        Set<String> scopes,
        String authorityUrlTemplate
) {
    /**
     * Default OAuth 2.0 scope for Microsoft Graph.
     */
    public static final String DEFAULT_GRAPH_SCOPE = "https://graph.microsoft.com/.default";

    /**
     * Default Azure AD authority URL template.
     */
    public static final String DEFAULT_AUTHORITY_TEMPLATE = "https://login.microsoftonline.com/%s";

    /**
     * Minimum token expiry buffer to prevent race conditions where tokens expire during request processing.
     * A buffer of at least 30 seconds is recommended to account for clock skew and network latency.
     */
    public static final Duration MINIMUM_TOKEN_EXPIRY_BUFFER = Duration.ofSeconds(30);

    /**
     * Validates configuration parameters.
     */
    public GraphAuthConfig {
        Objects.requireNonNull(tokenExpiryBuffer, "tokenExpiryBuffer cannot be null");
        Objects.requireNonNull(acquireTimeout, "acquireTimeout cannot be null");
        Objects.requireNonNull(initialBackoff, "initialBackoff cannot be null");
        Objects.requireNonNull(maxBackoff, "maxBackoff cannot be null");
        Objects.requireNonNull(scopes, "scopes cannot be null");
        Objects.requireNonNull(authorityUrlTemplate, "authorityUrlTemplate cannot be null");

        if (maxRetries < 1) {
            throw new IllegalArgumentException("maxRetries must be at least 1");
        }
        if (tokenExpiryBuffer.isNegative()) {
            throw new IllegalArgumentException("tokenExpiryBuffer cannot be negative");
        }
        if (tokenExpiryBuffer.compareTo(MINIMUM_TOKEN_EXPIRY_BUFFER) < 0) {
            throw new IllegalArgumentException(
                    "tokenExpiryBuffer must be at least " + MINIMUM_TOKEN_EXPIRY_BUFFER.toSeconds() +
                    " seconds to prevent race conditions where tokens expire during request processing");
        }
        if (acquireTimeout.isNegative() || acquireTimeout.isZero()) {
            throw new IllegalArgumentException("acquireTimeout must be positive");
        }
        if (initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("initialBackoff must be positive");
        }
        if (maxBackoff.isNegative() || maxBackoff.isZero()) {
            throw new IllegalArgumentException("maxBackoff must be positive");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException(
                    "maxBackoff (" + maxBackoff.toMillis() + "ms) must be at least as long as initialBackoff (" +
                    initialBackoff.toMillis() + "ms)");
        }
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes cannot be empty");
        }
        if (!authorityUrlTemplate.contains("%s")) {
            throw new IllegalArgumentException("authorityUrlTemplate must contain %s placeholder for tenant ID");
        }

        // Defensive copy for immutability
        scopes = Set.copyOf(scopes);
    }

    /**
     * Returns default configuration suitable for most use cases.
     *
     * @return Default GraphAuthConfig instance
     */
    public static GraphAuthConfig defaults() {
        return builder().build();
    }

    /**
     * Creates a new configuration builder.
     *
     * @return New Builder instance with default values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating GraphAuthConfig instances.
     */
    public static class Builder {
        private Duration tokenExpiryBuffer = Duration.ofMinutes(2);
        private Duration acquireTimeout = Duration.ofSeconds(30);
        private int maxRetries = 3;
        private Duration initialBackoff = Duration.ofMillis(500);
        private Duration maxBackoff = Duration.ofSeconds(10);
        private Set<String> scopes = Set.of(DEFAULT_GRAPH_SCOPE);
        private String authorityUrlTemplate = DEFAULT_AUTHORITY_TEMPLATE;

        private Builder() {}

        /**
         * Sets the time buffer before actual token expiry to trigger proactive refresh.
         * Recommended: 2-5 minutes to account for clock skew and request duration.
         *
         * @param tokenExpiryBuffer Duration before expiry to refresh (must be non-negative)
         * @return this builder
         */
        public Builder tokenExpiryBuffer(Duration tokenExpiryBuffer) {
            this.tokenExpiryBuffer = tokenExpiryBuffer;
            return this;
        }

        /**
         * Sets the maximum wait time for a single token acquisition attempt.
         *
         * @param acquireTimeout Timeout duration (must be positive)
         * @return this builder
         */
        public Builder acquireTimeout(Duration acquireTimeout) {
            this.acquireTimeout = acquireTimeout;
            return this;
        }

        /**
         * Sets the maximum number of retry attempts for transient failures.
         *
         * @param maxRetries Number of retries (must be at least 1)
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Sets the initial backoff duration for the first retry.
         * Subsequent retries use exponential backoff (doubles each time).
         *
         * @param initialBackoff Initial wait duration (must be positive)
         * @return this builder
         */
        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        /**
         * Sets the maximum backoff cap regardless of exponential growth.
         *
         * @param maxBackoff Maximum wait duration between retries
         * @return this builder
         */
        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        /**
         * Sets the OAuth scopes to request from Azure AD.
         *
         * @param scopes Set of scope URIs (must not be empty)
         * @return this builder
         */
        public Builder scopes(Set<String> scopes) {
            this.scopes = scopes;
            return this;
        }

        /**
         * Sets the Azure AD authority URL template.
         * Must contain %s placeholder for tenant ID.
         *
         * @param authorityUrlTemplate URL template (e.g., "https://login.microsoftonline.com/%s")
         * @return this builder
         */
        public Builder authorityUrlTemplate(String authorityUrlTemplate) {
            this.authorityUrlTemplate = authorityUrlTemplate;
            return this;
        }

        /**
         * Builds the immutable GraphAuthConfig instance.
         *
         * @return New GraphAuthConfig with configured values
         * @throws IllegalArgumentException if configuration is invalid
         */
        public GraphAuthConfig build() {
            return new GraphAuthConfig(
                    tokenExpiryBuffer,
                    acquireTimeout,
                    maxRetries,
                    initialBackoff,
                    maxBackoff,
                    scopes,
                    authorityUrlTemplate
            );
        }
    }
}

