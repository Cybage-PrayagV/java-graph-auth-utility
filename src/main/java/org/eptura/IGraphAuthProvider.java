package org.eptura;

import com.microsoft.graph.requests.GraphServiceClient;
import okhttp3.Request;

import java.util.UUID;

/**
 * Interface for Microsoft Graph authentication providers.
 * <p>
 * This interface abstracts the authentication mechanism, allowing for:
 * <ul>
 *   <li>Dependency injection in application code</li>
 *   <li>Easy mocking in unit tests</li>
 *   <li>Multiple implementation strategies (singleton, per-tenant, pooled)</li>
 * </ul>
 * </p>
 *
 * @see GraphAuthProvider Default implementation using MSAL4J
 * @see GraphAuthException Exception type for authentication errors
 */
public interface IGraphAuthProvider extends AutoCloseable {

    /**
     * Establishes connection to Microsoft Graph API using Azure AD credentials.
     *
     * @param clientId     Azure AD application (client) ID
     * @param tenantId     Azure AD tenant (directory) ID
     * @param clientSecret Azure AD application client secret (will be wiped after use)
     * @throws NullPointerException if any parameter is null
     * @throws GraphAuthException   if connection fails
     */
    void connect(UUID clientId, UUID tenantId, char[] clientSecret);

    /**
     * Returns the authenticated Microsoft Graph client.
     *
     * @return Authenticated GraphServiceClient instance
     * @throws IllegalStateException if not connected
     */
    GraphServiceClient<Request> getClient();

    /**
     * Checks if the provider is currently connected.
     *
     * @return true if connected and ready for API calls
     */
    boolean isConnected();

    /**
     * Disconnects and clears all cached credentials and tokens.
     * <p>
     * After calling this method, {@link #connect} can be called again to re-establish a connection.
     * </p>
     */
    void disconnect();

    /**
     * Closes the provider and releases all resources.
     * <p>
     * This method performs the following:
     * <ul>
     *   <li>Calls {@link #disconnect()} to clear credentials and tokens</li>
     *   <li>Shuts down any internal executor services (if owned by this instance)</li>
     * </ul>
     * </p>
     * <p>
     * <b>Important:</b> Unlike {@link #disconnect()}, calling {@code close()} may make the provider
     * unusable for reconnection if it owns its executor service. Create a new instance if you need
     * to reconnect after calling {@code close()}.
     * </p>
     */
    @Override
    void close();
}
