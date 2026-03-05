# 🔐 Java Graph Auth Utility

A production-grade, thread-safe, and secure utility for authenticating with Microsoft Graph API using the OAuth 2.0 Client Credentials flow. 🚀

This utility simplifies the complexities of Azure AD authentication, token management, and secure credential handling, allowing you to focus on building your application logic.

---

## ✨ Key Features

| Feature | Description |
|---------|-------------|
| 🔒 **Secure by Design** | Client secrets are handled as `char[]` and securely wiped from memory after use |
| 🔄 **Automatic Token Management** | Handles token acquisition, caching, and automatic refreshing before expiry |
| ⚡ **Virtual Threads** | Uses Java 21 Virtual Threads for lightweight, non-blocking async operations |
| 🛡️ **Circuit Breaker** | Fails fast when Azure AD is unavailable to prevent cascade failures |
| 🧵 **Thread-Safe** | Double-checked locking prevents thundering herd on token refresh |
| 📊 **Observable** | Metrics hooks for integration with Micrometer, Prometheus, etc. |
| 🧪 **Testable** | Interface-based design with dependency injection support |
| ⚙️ **Configurable** | All timeouts, retries, and scopes are fully configurable |

---

## 🛠️ Prerequisites

Before you start, ensure you have:

1.  **Java 21+** (Required for Virtual Threads)
2.  An **Azure AD App Registration**:
    *   `Client ID` (Application ID)
    *   `Tenant ID` (Directory ID)
    *   `Client Secret` (generated in "Certificates & secrets")
3.  **Permissions**: Ensure your app has the necessary API permissions (e.g., `User.Read.All`) granted in Azure Portal

---

## 📦 Dependencies

### Gradle

```groovy
plugins {
    id 'java'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation 'com.microsoft.azure:msal4j:1.14.0'
    implementation 'com.microsoft.graph:microsoft-graph:5.80.0'
    implementation 'org.apache.logging.log4j:log4j-api:2.20.0'
    runtimeOnly 'org.apache.logging.log4j:log4j-core:2.20.0'
    
    // For testing
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
    testImplementation 'org.mockito:mockito-core:5.7.0'
}
```

### Maven

```xml
<dependencies>
    <dependency>
        <groupId>com.microsoft.azure</groupId>
        <artifactId>msal4j</artifactId>
        <version>1.14.0</version>
    </dependency>
    <dependency>
        <groupId>com.microsoft.graph</groupId>
        <artifactId>microsoft-graph</artifactId>
        <version>5.80.0</version>
    </dependency>
    <dependency>
        <groupId>org.apache.logging.log4j</groupId>
        <artifactId>log4j-api</artifactId>
        <version>2.20.0</version>
    </dependency>
    <dependency>
        <groupId>org.apache.logging.log4j</groupId>
        <artifactId>log4j-core</artifactId>
        <version>2.20.0</version>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

---

## 🚀 Quick Start Guide

### 1. Set Environment Variables

Set your credentials in your environment (never hardcode them!):

```bash
export AZURE_TENANT_ID="your-tenant-id"
export AZURE_CLIENT_ID="your-client-id"
export AZURE_CLIENT_SECRET="your-client-secret"
```

### 2. Basic Usage

```java
import org.eptura.GraphAuthProvider;
import com.microsoft.graph.requests.GraphServiceClient;
import okhttp3.Request;
import java.util.UUID;

public class MyApp {
    public static void main(String[] args) {
        UUID tenantId = UUID.fromString(System.getenv("AZURE_TENANT_ID"));
        UUID clientId = UUID.fromString(System.getenv("AZURE_CLIENT_ID"));
        char[] secret = System.getenv("AZURE_CLIENT_SECRET").toCharArray();

        // Use try-with-resources for automatic cleanup
        try (GraphAuthProvider auth = GraphAuthProvider.create()) {
            
            auth.connect(clientId, tenantId, secret);
            
            GraphServiceClient<Request> client = auth.getClient();

            // Use the client
            var users = client.users()
                .buildRequest()
                .select("displayName,id")
                .top(5)
                .get();
                
            users.getCurrentPage().forEach(user -> 
                System.out.println("User: " + user.displayName));

        } catch (GraphAuthProvider.GraphAuthProviderException e) {
            if (e.isCircuitOpen()) {
                System.err.println("Azure AD is unavailable, try again later");
            } else {
                e.printStackTrace();
            }
        }
    }
}
```

---

## ⚙️ Configuration

### Custom Configuration with Builder

```java
import org.eptura.GraphAuthConfig;
import org.eptura.GraphAuthProvider;
import java.time.Duration;

GraphAuthConfig config = GraphAuthConfig.builder()
    .tokenExpiryBuffer(Duration.ofMinutes(5))   // Refresh 5 min before expiry
    .acquireTimeout(Duration.ofSeconds(60))     // Token acquisition timeout
    .maxRetries(5)                               // Maximum retry attempts
    .initialBackoff(Duration.ofSeconds(1))      // Initial retry backoff
    .maxBackoff(Duration.ofSeconds(30))         // Maximum backoff cap
    .build();

try (GraphAuthProvider provider = GraphAuthProvider.create(config)) {
    provider.connect(clientId, tenantId, secret);
    // ...
}
```

### Configuration Options

| Option | Default | Min | Description |
|--------|---------|-----|-------------|
| `tokenExpiryBuffer` | 2 minutes | 30 seconds | Time before actual expiry to trigger proactive refresh (minimum 30s to prevent race conditions) |
| `acquireTimeout` | 30 seconds | >0 | Maximum wait time for token acquisition |
| `maxRetries` | 3 | 1 | Maximum retry attempts for transient failures |
| `initialBackoff` | 500ms | >0 | Initial backoff duration for first retry |
| `maxBackoff` | 10 seconds | N/A | Maximum backoff cap regardless of exponential growth |
| `scopes` | `https://graph.microsoft.com/.default` | N/A | OAuth scopes to request |
| `authorityUrlTemplate` | `https://login.microsoftonline.com/%s` | N/A | Azure AD authority URL | URL |

---

## 📊 Metrics Integration

Integrate with your monitoring system by implementing the `TokenMetrics` interface:

```java
import org.eptura.TokenMetrics;
import org.eptura.GraphAuthProvider;
import org.eptura.GraphAuthConfig;

// Example: Micrometer integration
public class MicrometerTokenMetrics implements TokenMetrics {
    private final MeterRegistry registry;

    public MicrometerTokenMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordTokenRefresh(long latencyMs, boolean success) {
        registry.timer("graph.auth.token.refresh").record(latencyMs, TimeUnit.MILLISECONDS);
        registry.counter("graph.auth.token.refresh.total", "success", String.valueOf(success)).increment();
    }

    @Override
    public void recordTokenCacheHit() {
        registry.counter("graph.auth.token.cache.hits").increment();
    }

    @Override
    public void recordRetryAttempt(int attemptNumber, long backoffMs) {
        registry.counter("graph.auth.retry", "attempt", String.valueOf(attemptNumber)).increment();
    }

    @Override
    public void recordCircuitBreakerState(boolean open) {
        registry.gauge("graph.auth.circuit.open", open ? 1 : 0);
    }

    @Override
    public void recordConnection(boolean success) {
        registry.counter("graph.auth.connection", "success", String.valueOf(success)).increment();
    }
}

// Usage
TokenMetrics metrics = new MicrometerTokenMetrics(registry);
GraphAuthProvider provider = GraphAuthProvider.create(GraphAuthConfig.defaults(), metrics);
```

---

## 🧪 Testing

The library is designed for testability with dependency injection:

```java
import org.eptura.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import static org.mockito.Mockito.*;

class MyServiceTest {
    
    @Mock
    private IGraphAuthProvider mockAuthProvider;
    
    @Mock
    private GraphServiceClient<Request> mockClient;
    
    @Test
    void testWithMockedAuth() {
        // Arrange
        when(mockAuthProvider.getClient()).thenReturn(mockClient);
        when(mockAuthProvider.isConnected()).thenReturn(true);
        
        // Act
        MyService service = new MyService(mockAuthProvider);
        service.doSomething();
        
        // Assert
        verify(mockAuthProvider).getClient();
    }
}
```

### Dependency Injection Pattern

```java
public class MyService {
    private final IGraphAuthProvider authProvider;

    // Constructor injection for testability
    public MyService(IGraphAuthProvider authProvider) {
        this.authProvider = authProvider;
    }
    
    // Production factory method
    public static MyService create() {
        return new MyService(GraphAuthProvider.create());
    }
}
```

---

## 🛡️ Circuit Breaker

The utility implements a circuit breaker pattern to prevent cascade failures:

| State | Behavior |
|-------|----------|
| **Closed** | Normal operation, requests go through |
| **Open** | After 5 consecutive failures, requests fail immediately with `GraphAuthProviderException` |
| **Half-Open** | After 30 seconds, one request is allowed through to test recovery |

### Handling Circuit Breaker Exceptions

```java
try {
    var client = provider.getClient();
} catch (GraphAuthProvider.GraphAuthProviderException e) {
    if (e.isCircuitOpen()) {
        // Circuit is open - Azure AD appears unavailable
        // Implement fallback logic or inform user to retry later
        log.warn("Circuit breaker open: {}", e.getMessage());
    } else {
        // Other authentication error
        throw e;
    }
}
```

---

## 📚 Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Your Application                          │
├─────────────────────────────────────────────────────────────────┤
│                     IGraphAuthProvider                           │
│                        (Interface)                               │
├─────────────────────────────────────────────────────────────────┤
│                     GraphAuthProvider                            │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────────┐  │
│  │ Token Cache │  │ Circuit      │  │ Double-Checked Locking │  │
│  │ (Atomic)    │  │ Breaker      │  │ (No Thundering Herd)   │  │
│  └─────────────┘  └──────────────┘  └────────────────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│  GraphAuthConfig │ TokenMetrics │ ConfidentialClientFactory     │
│   (Immutable)    │ (Observable) │ (Testable)                    │
├─────────────────────────────────────────────────────────────────┤
│                    MSAL4J / Microsoft Graph SDK                  │
├─────────────────────────────────────────────────────────────────┤
│                         Azure AD                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Key Components

| Component | Purpose |
|-----------|---------|
| `IGraphAuthProvider` | Interface for dependency injection and mocking |
| `GraphAuthProvider` | Main implementation with all features |
| `GraphAuthConfig` | Immutable configuration with builder pattern |
| `TokenMetrics` | Metrics hooks for observability |
| `ConfidentialClientFactory` | Factory for MSAL clients (testable) |

---

## 📖 API Reference

### GraphAuthProvider Methods

| Method | Description |
|--------|-------------|
| `create()` | Factory method with default configuration |
| `create(config)` | Factory method with custom configuration |
| `create(config, metrics)` | Factory method with configuration and metrics |
| `connect(clientId, tenantId, secret)` | Establishes connection (wipes secret after use) |
| `getClient()` | Returns authenticated `GraphServiceClient` |
| `isConnected()` | Returns `true` if connected and ready |
| `disconnect()` | Clears tokens and session data |
| `close()` | Disconnects and shuts down executor (AutoCloseable) |
| `getConfig()` | Returns current configuration (for debugging) |
| `getConsecutiveFailures()` | Returns circuit breaker failure count |

---

## ❓ Troubleshooting

### Common Azure AD Errors

| Error Code | Cause | Solution |
|------------|-------|----------|
| `AADSTS90002` | Tenant not found | Verify Tenant ID (Directory ID) from Azure Portal |
| `AADSTS7000215` | Invalid client secret | Generate new secret in "Certificates & secrets" |
| `AADSTS700016` | Application not found | Verify Client ID (Application ID) |
| `AADSTS50034` | User account doesn't exist | Account not in tenant |

### Other Issues

| Issue | Solution |
|-------|----------|
| Connection timeout | Increase `acquireTimeout` in configuration |
| JVM not exiting | Use try-with-resources or call `close()` |
| Too many retries | Reduce `maxRetries` or check network |
| Circuit keeps opening | Check Azure AD status, verify credentials |

---

## 🔗 Useful Links

*   **OAuth 2.0 Client Credentials**: [Microsoft identity platform documentation](https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-client-creds-grant-flow)
*   **Microsoft Graph Java SDK**: [SDK overview](https://learn.microsoft.com/en-us/graph/sdks/sdk-installation)
*   **Azure AD Troubleshooting**: [Common error codes](https://learn.microsoft.com/en-us/azure/active-directory/develop/reference-aadsts-error-codes)

---

## 📄 License

MIT License - Feel free to use in your projects!

---

Happy Coding! 🎉 🚀
