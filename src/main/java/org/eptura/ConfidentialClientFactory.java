package org.eptura;

import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IClientSecret;

import java.net.MalformedURLException;

/**
 * Factory interface for creating MSAL ConfidentialClientApplication instances.
 * <p>
 * This abstraction allows for:
 * <ul>
 *   <li>Dependency injection in application code</li>
 *   <li>Mocking in unit tests without actual Azure AD calls</li>
 *   <li>Custom MSAL configurations (proxy, timeouts, etc.)</li>
 * </ul>
 * </p>
 */
@FunctionalInterface
public interface ConfidentialClientFactory {

    /**
     * Creates a new ConfidentialClientApplication instance.
     *
     * @param clientId     Azure AD application (client) ID
     * @param clientSecret MSAL client secret credential
     * @param authority    Azure AD authority URL (e.g., https://login.microsoftonline.com/{tenant})
     * @return Configured ConfidentialClientApplication
     * @throws MalformedURLException if the authority URL is invalid
     */
    ConfidentialClientApplication create(String clientId, IClientSecret clientSecret, String authority)
            throws MalformedURLException;
}

