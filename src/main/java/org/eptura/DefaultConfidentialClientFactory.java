package org.eptura;

import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IClientSecret;

import java.net.MalformedURLException;

/**
 * Default implementation of ConfidentialClientFactory.
 * <p>
 * Creates standard MSAL ConfidentialClientApplication instances using the builder pattern.
 * </p>
 */
public final class DefaultConfidentialClientFactory implements ConfidentialClientFactory {

    /**
     * Singleton instance for convenience.
     */
    public static final DefaultConfidentialClientFactory INSTANCE = new DefaultConfidentialClientFactory();

    private DefaultConfidentialClientFactory() {}

    @Override
    public ConfidentialClientApplication create(String clientId, IClientSecret clientSecret, String authority)
            throws MalformedURLException {
        return ConfidentialClientApplication.builder(clientId, clientSecret)
                .authority(authority)
                .build();
    }
}

