# 🔐 Java Graph Auth Utility

A robust, thread-safe, and secure utility for authenticating with Microsoft Graph API using the OAuth 2.0 Client Credentials flow. 🚀

This utility simplifies the complexities of Azure AD authentication, token management, and secure credential handling, allowing you to focus on building your application logic.

---

## ✨ Key Features

*   **🔒 Secure by Design**: Client secrets are handled as `char[]` and securely wiped from memory immediately after use.
*   **🔄 Automatic Token Management**: Handles token acquisition, caching, and automatic refreshing before expiry.
*   **⚡ Lightweight Concurrency**: Uses **Java 21 Virtual Threads** for non-blocking asynchronous token refreshing.
*   **🛡️ Resilient**: Implements exponential backoff retry logic for network or auth failures.
*   **🧵 Thread-Safe**: Fully synchronized methods and thread-safe singleton pattern.

---

## 🛠️ Prerequisites

Before you start, ensure you have:

1.  **Java 21+** (Required for Virtual Threads).
2.  An **Azure AD App Registration**:
    *   `Client ID` (Application ID)
    *   `Tenant ID` (Directory ID)
    *   `Client Secret` (generated in "Certificates & secrets")
3.  **Permissions**: Ensure your app has the necessary API permissions (e.g., `User.Read.All`) granted in Azure Portal.

---

## 📦 Dependencies

Add the following libraries to your build configuration:

### Gradle
```groovy
implementation 'com.microsoft.azure:msal4j:1.14.0'
implementation 'com.microsoft.graph:microsoft-graph:5.80.0'
implementation 'org.apache.logging.log4j:log4j-api:2.20.0'
implementation 'org.apache.logging.log4j:log4j-core:2.20.0'
```

### Maven
```xml
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
</dependency>
```

---

## 🚀 Quick Start Guide

Here is a complete example of how to use the utility:

### 1. Set Environment Variables
Set your credentials in your environment (never hardcode them!):
*   `AZURE_TENANT_ID`
*   `AZURE_CLIENT_ID`
*   `AZURE_CLIENT_SECRET`

### 2. Implementation

```java
import org.eptura.GraphAuthProvider;
import com.microsoft.graph.requests.GraphServiceClient;
import com.microsoft.graph.models.User;
import okhttp3.Request;
import java.util.UUID;

public class MyApp {
    public static void main(String[] args) {
        // 1. Load Credentials safely
        UUID tenantId = UUID.fromString(System.getenv("AZURE_TENANT_ID"));
        UUID clientId = UUID.fromString(System.getenv("AZURE_CLIENT_ID"));
        char[] secret = System.getenv("AZURE_CLIENT_SECRET").toCharArray();

        try {
            // 2. Get Instance
            GraphAuthProvider auth = GraphAuthProvider.getInstance();

            // 3. Connect
            auth.connect(clientId, tenantId, secret);

            // 4. Get Authenticated Client
            GraphServiceClient<Request> graphClient = auth.getClient();

            // 5. Use the Client!
            // Example: Get top 5 users
            var users = graphClient.users()
                .buildRequest()
                .select("displayName,id")
                .top(5)
                .get();
                
            if (users != null) {
                for (User user : users.getCurrentPage()) {
                    System.out.println("User: " + user.displayName);
                }
            }

            // 6. Cleanup Session (Optional, good for resetting state)
            auth.close(); 

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
             // 7. App Shutdown (Essential for Virtual Threads!)
             // Stops the background executor so the JVM can exit
            GraphAuthProvider.getInstance().shutdown();
        }
    }
}
```

---

## 📚 Core Functionalities

### 🛡️ Security First
We take security seriously. The `connect` method accepts the client secret as a `char[]`. Once the initial token is acquired, the array is **zeroed out** (`\0`), wiping the secret from memory to prevent leaks in stack traces or memory dumps.

### ⏱️ Smart Token Refresh
No need to worry about `401 Unauthorized` errors!
*   The utility caches the access token.
*   It proactively refreshes the token **2 minutes before** it expires.
*   Refreshes happen asynchronously on a background virtual thread, so your main application flow isn't blocked.

### 🛑 Error Handling & Retries
Network blip? Azure hiccup? No problem.
The utility attempts to acquire tokens up to **3 times** with exponential backoff (250ms -> 500ms -> 1s) to handle transient failures gracefully.

---

## 📖 API Reference

| Method | Description |
| :--- | :--- |
| `getInstance()` | Returns the singleton instance. Thread-safe and lazy-loaded. |
| `connect(clientId, tenantId, secret)` | Initializes the connection. Wipes the secret after use. |
| `getClient()` | Returns the authenticated `GraphServiceClient`. Throws error if not connected. |
| `isConnected()` | Returns `true` if connected and valid. |
| `close()` / `disconnect()` | Clears tokens and session data from memory. |
| `shutdown()` | **Important**: Shuts down the internal executor. Call this when your app stops. |

---

## ❓ Troubleshooting

### Common Azure AD Errors

*   **AADSTS90002: Tenant not found** (Wrong Tenant ID)
    *   Check if you are using the correct Tenant ID (Directory ID) from the Azure Portal overview page.
    *   [Tenant 'X' not found - Microsoft Q&A](https://learn.microsoft.com/en-us/answers/questions/81180/aadsts90002-tenant-x-not-found)

*   **AADSTS7000215: Invalid client secret**
    *   The client secret may be incorrect or expired. Generate a new secret in "Certificates & secrets".
    *   [How do I mitigate "AADSTS7000215: Invalid client secret is provided"? - Microsoft Q&A](https://learn.microsoft.com/en-us/answers/questions/730366/how-do-i-mitigate-aadsts7000215-invalid-client-sec)

*   **AADSTS700016: Application not found** (Wrong Client ID)
    *   The Application ID (Client ID) does not match any app registration in the directory.
    *   [AADSTS700016: Application with identifier not found - Microsoft Q&A](https://learn.microsoft.com/en-us/answers/questions/1000858/error-aadsts700016-application-with-identifier-not)

*   **AADSTS50034: User account doesn't exist**
    *   This usually happens when trying to sign in with an account that isn't in the tenant.
    *   [AADSTS50034: The user account does not exist... - Microsoft Q&A](https://learn.microsoft.com/en-us/answers/questions/681029/error-description-aadsts50034-the-user-account-ema)

### Other Issues

*   **Connection Timeout**: If your network is slow or behind a proxy, the utility might time out after 30 seconds.
*   **JVM not exiting**: Ensure you called `GraphAuthProvider.getInstance().shutdown()` in your `finally` block or shutdown hook to stop the background threads.

---

## 🔗 Useful Links

*   **OAuth 2.0 Client Credentials**: [OAuth 2.0 client credentials flow on the Microsoft identity platform](https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-client-creds-grant-flow)
*   **Microsoft Graph Java SDK**: [Microsoft Graph SDK overview](https://learn.microsoft.com/en-us/graph/sdks/sdk-installation)

---

Happy Coding! 🎉 🚀 🦄 ☕️

![Happy Coding](https://media.giphy.com/media/JIX9t2j0ZTN9S/giphy.gif)
