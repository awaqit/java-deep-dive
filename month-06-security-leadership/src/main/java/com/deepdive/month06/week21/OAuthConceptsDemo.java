package com.deepdive.month06.week21;

import java.util.*;

/**
 * Week 21: OAuth 2.0 and OpenID Connect (OIDC)
 *
 * CONCEPT: OAuth 2.0 is an authorization framework that allows applications to
 * obtain limited access to user accounts on an HTTP service.
 * OpenID Connect (OIDC) adds authentication on top of OAuth 2.0.
 *
 * Key players:
 * - Resource Owner: The user who owns the data
 * - Client:         The application requesting access
 * - Authorization Server: Issues access tokens (Auth0, Keycloak, Okta, Google)
 * - Resource Server: API that accepts access tokens
 *
 * OAuth 2.0 Grant Types:
 *
 * 1. Authorization Code (most secure, for web apps):
 *    User -> App -> AuthServer (login) -> Auth Code -> App -> Access Token
 *    + Refresh Token (for long-lived access)
 *    Used with PKCE for public clients (SPAs, mobile apps)
 *
 * 2. Client Credentials (machine-to-machine, no user):
 *    Service -> AuthServer (client_id + client_secret) -> Access Token
 *    Used for: microservice-to-microservice calls
 *
 * 3. Authorization Code + PKCE:
 *    Like Authorization Code but prevents code injection attacks
 *    PKCE (Proof Key for Code Exchange):
 *    - Generate code_verifier (random string)
 *    - code_challenge = SHA256(code_verifier)
 *    - Send code_challenge with auth request
 *    - Send code_verifier when exchanging code for token
 *    - AuthServer verifies: SHA256(code_verifier) == code_challenge
 *
 * OpenID Connect adds:
 * - ID Token (JWT with user info: sub, email, name, picture)
 * - UserInfo endpoint: GET /userinfo (returns user profile)
 * - Standard scopes: openid, profile, email, address, phone
 *
 * Token types:
 * - Access Token:  Short-lived (15min-1hr), used to call APIs
 * - Refresh Token: Long-lived (days-weeks), used to get new access tokens
 * - ID Token:      OIDC only, JWT with user identity claims
 */
public class OAuthConceptsDemo {

    // ==================== OAUTH 2.0 AUTHORIZATION CODE FLOW ====================

    record AuthorizationRequest(
            String clientId, String redirectUri, String responseType,
            String scope, String state, String codeChallenge, String codeChallengeMethod) {}

    record TokenRequest(String grantType, String code, String redirectUri,
                        String clientId, String clientSecret, String codeVerifier) {}

    record TokenResponse(String accessToken, String tokenType, long expiresIn,
                         String refreshToken, String idToken, String scope) {}

    record UserInfo(String sub, String name, String email, String picture, List<String> roles) {}

    // Simulated Authorization Server
    static class AuthorizationServer {
        private final Map<String, String> authorizationCodes = new HashMap<>(); // code -> userId
        private final Map<String, String> codeVerifiers = new HashMap<>();      // code -> codeVerifier

        /**
         * CONCEPT: Step 1 - User authenticates and AuthServer issues auth code.
         * Code is single-use, short-lived (usually 5-10 minutes).
         */
        String authorize(AuthorizationRequest request, String userId) {
            String authCode = UUID.randomUUID().toString().replace("-", "");
            authorizationCodes.put(authCode, userId);

            if (request.codeChallenge() != null) {
                // PKCE: Store the code challenge for later verification
                codeVerifiers.put(authCode, request.codeChallenge());
            }

            System.out.printf("  [AUTH-SERVER] User '%s' authorized. Code issued: %s...%n",
                    userId, authCode.substring(0, 8));
            return authCode;
        }

        /**
         * CONCEPT: Step 2 - Client exchanges auth code for tokens.
         * Code is consumed (one-time use) to prevent replay attacks.
         */
        TokenResponse exchangeCode(TokenRequest request) {
            String userId = authorizationCodes.remove(request.code()); // Consume code
            if (userId == null) {
                throw new SecurityException("Invalid or already-used authorization code!");
            }

            // PKCE verification
            String storedChallenge = codeVerifiers.remove(request.code());
            if (storedChallenge != null && request.codeVerifier() != null) {
                String computedChallenge = computeCodeChallenge(request.codeVerifier());
                if (!computedChallenge.equals(storedChallenge)) {
                    throw new SecurityException("PKCE verification failed! Code verifier mismatch.");
                }
                System.out.println("  [AUTH-SERVER] PKCE verification passed.");
            }

            // Generate tokens
            String accessToken = "access_" + UUID.randomUUID().toString().replace("-", "");
            String refreshToken = "refresh_" + UUID.randomUUID().toString().replace("-", "");
            String idToken = buildIdToken(userId);

            System.out.printf("  [AUTH-SERVER] Tokens issued for user '%s'%n", userId);
            return new TokenResponse(accessToken, "Bearer", 3600, refreshToken, idToken,
                    "openid profile email");
        }

        private String computeCodeChallenge(String verifier) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private String buildIdToken(String userId) {
            // Simplified ID token (real one would be a signed JWT)
            Map<String, String> claims = Map.of(
                    "sub", userId, "name", "User " + userId,
                    "email", userId + "@example.com", "iss", "https://auth.example.com"
            );
            return "ID_TOKEN_FOR_" + userId;
        }
    }

    // ==================== RESOURCE SERVER (API) ====================

    static class ResourceServer {
        private final Set<String> validTokens = new HashSet<>();
        private final Map<String, UserInfo> tokenToUser = new HashMap<>();

        void registerToken(String accessToken, UserInfo userInfo) {
            validTokens.add(accessToken);
            tokenToUser.put(accessToken, userInfo);
        }

        /**
         * CONCEPT: Token introspection (RFC 7662) or JWT verification.
         * Real implementations validate JWT signature and claims.
         */
        Optional<UserInfo> validateToken(String accessToken) {
            // In real implementation: validate JWT signature + expiry
            if (!validTokens.contains(accessToken)) {
                System.out.println("  [API] Token validation FAILED: invalid token");
                return Optional.empty();
            }
            UserInfo user = tokenToUser.get(accessToken);
            System.out.printf("  [API] Token valid for user: %s%n", user.sub());
            return Optional.of(user);
        }

        String handleRequest(String accessToken, String endpoint) {
            return validateToken(accessToken)
                    .map(user -> "200 OK: Data for " + user.name() + " from " + endpoint)
                    .orElse("401 Unauthorized: Invalid token");
        }
    }

    // ==================== CLIENT APPLICATION ====================

    static class OAuthClient {
        private final String clientId;
        private final String clientSecret;
        private final AuthorizationServer authServer;
        private final ResourceServer resourceServer;
        private TokenResponse currentTokens;

        OAuthClient(String clientId, String clientSecret,
                    AuthorizationServer authServer, ResourceServer resourceServer) {
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.authServer = authServer;
            this.resourceServer = resourceServer;
        }

        void initiateAuthorizationCodeFlow(String userId) {
            System.out.println("  [CLIENT] Step 1: Redirect user to authorization server...");

            // CONCEPT: PKCE - generate code verifier and challenge
            String codeVerifier = UUID.randomUUID().toString() + UUID.randomUUID().toString();
            String codeChallenge = computeCodeChallenge(codeVerifier);

            AuthorizationRequest authRequest = new AuthorizationRequest(
                    clientId,
                    "https://app.example.com/callback",
                    "code",
                    "openid profile email",
                    UUID.randomUUID().toString(), // state: CSRF protection
                    codeChallenge,
                    "S256"
            );

            // User logs in at AuthServer
            System.out.println("  [USER] Authenticating at authorization server...");
            String authCode = authServer.authorize(authRequest, userId);

            System.out.println("  [CLIENT] Step 2: Exchange auth code for tokens...");
            TokenRequest tokenRequest = new TokenRequest(
                    "authorization_code",
                    authCode,
                    "https://app.example.com/callback",
                    clientId,
                    clientSecret,
                    codeVerifier // PKCE: send the original verifier
            );

            currentTokens = authServer.exchangeCode(tokenRequest);
            System.out.println("  [CLIENT] Got access token and refresh token");

            // Register token with resource server (in real: API validates JWT independently)
            UserInfo userInfo = new UserInfo(userId, "User " + userId,
                    userId + "@example.com", null, List.of("user", "editor"));
            resourceServer.registerToken(currentTokens.accessToken(), userInfo);
        }

        String callApi(String endpoint) {
            if (currentTokens == null) throw new IllegalStateException("Not authenticated");
            System.out.println("  [CLIENT] Calling API with Bearer token...");
            return resourceServer.handleRequest(currentTokens.accessToken(), endpoint);
        }

        private String computeCodeChallenge(String verifier) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    // ==================== CLIENT CREDENTIALS FLOW ====================

    static class ServiceToServiceAuth {
        private final AuthorizationServer authServer;
        private final Map<String, String> clientCredentials = new HashMap<>();

        ServiceToServiceAuth(AuthorizationServer authServer) {
            this.authServer = authServer;
        }

        void registerClient(String clientId, String clientSecret) {
            clientCredentials.put(clientId, clientSecret);
        }

        TokenResponse getServiceToken(String clientId, String clientSecret) {
            // CONCEPT: No user interaction - service authenticates with its own credentials
            if (!clientSecret.equals(clientCredentials.get(clientId))) {
                throw new SecurityException("Invalid client credentials");
            }
            String accessToken = "service_token_" + UUID.randomUUID().toString().replace("-", "");
            System.out.printf("  [AUTH-SERVER] Service token issued for client: %s%n", clientId);
            return new TokenResponse(accessToken, "Bearer", 3600, null, null,
                    "service.read service.write");
        }
    }

    public static void main(String[] args) {
        System.out.println("=== OAuth 2.0 & OpenID Connect Demo ===");

        demonstrateAuthorizationCodeFlow();
        demonstrateClientCredentialsFlow();
        explainOAuthSecurity();
    }

    private static void demonstrateAuthorizationCodeFlow() {
        System.out.println("\n--- Authorization Code Flow with PKCE ---");
        System.out.println("(Used by web apps and SPAs on behalf of a user)");

        AuthorizationServer authServer = new AuthorizationServer();
        ResourceServer resourceServer = new ResourceServer();
        OAuthClient client = new OAuthClient("my-app-id", "app-secret-xyz",
                authServer, resourceServer);

        System.out.println("\n[Flow Start]");
        client.initiateAuthorizationCodeFlow("user-alice");

        System.out.println("\n[API Calls with token]");
        String response = client.callApi("/api/orders");
        System.out.println("  API Response: " + response);
    }

    private static void demonstrateClientCredentialsFlow() {
        System.out.println("\n--- Client Credentials Flow ---");
        System.out.println("(Used for service-to-service authentication, no user involved)");

        AuthorizationServer authServer = new AuthorizationServer();
        ServiceToServiceAuth serviceAuth = new ServiceToServiceAuth(authServer);

        serviceAuth.registerClient("order-service", "order-secret-abc123");
        serviceAuth.registerClient("payment-service", "payment-secret-xyz789");

        // Order service needs to call Payment service
        TokenResponse token = serviceAuth.getServiceToken("order-service", "order-secret-abc123");
        System.out.println("  Order service got token: " + token.accessToken().substring(0, 20) + "...");
        System.out.println("  Token type: " + token.tokenType());
        System.out.println("  Scopes: " + token.scope());

        // Now order-service can call payment-service APIs with this token
        System.out.println("  [ORDER-SERVICE] Calling payment-service with Bearer token...");
        System.out.println("  GET /api/payments/process Authorization: Bearer " +
                token.accessToken().substring(0, 15) + "...");
    }

    private static void explainOAuthSecurity() {
        System.out.println("\n--- OAuth Security Considerations ---");
        System.out.println("PKCE prevents authorization code injection:");
        System.out.println("  Attacker intercepts auth code but doesn't have code_verifier");
        System.out.println("  Cannot exchange code for tokens without verifier -> attack fails");
        System.out.println();
        System.out.println("State parameter prevents CSRF:");
        System.out.println("  Client generates random 'state', sends in auth request");
        System.out.println("  AuthServer returns state in callback -> verify it matches");
        System.out.println("  Prevents attacker from linking their auth code to victim's session");
        System.out.println();
        System.out.println("Token storage for SPAs:");
        System.out.println("  httpOnly cookie: Better (JS can't access, prevents XSS theft)");
        System.out.println("  localStorage: Risky (XSS can steal tokens)");
        System.out.println("  sessionStorage: OK for access tokens, cleared on tab close");
        System.out.println();
        System.out.println("Token revocation challenges:");
        System.out.println("  Access tokens are stateless JWTs -> can't revoke without blocklist");
        System.out.println("  Keep access tokens short-lived (15 min)");
        System.out.println("  Refresh tokens can be revoked (server-side storage)");
    }
}
