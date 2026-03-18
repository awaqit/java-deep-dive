package com.deepdive.month06.week21;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Week 21: JWT (JSON Web Tokens)
 *
 * CONCEPT: JWT is a compact, self-contained token format for securely
 * transmitting information between parties as a JSON object.
 * The information can be verified and trusted because it is digitally signed.
 *
 * JWT Structure: header.payload.signature (Base64URL encoded)
 *
 * Header:   {"alg":"HS256","typ":"JWT"}
 * Payload:  {"sub":"user-42","iss":"auth-service","exp":1234567890,"roles":["admin"]}
 * Signature: HMAC-SHA256(base64(header) + "." + base64(payload), secret)
 *
 * JWT is NOT encryption (payload is just Base64, not encrypted).
 * JWT is AUTHENTICATION/AUTHORIZATION (verifies who made the claim).
 * Use JWE (JSON Web Encryption) if payload must be private.
 *
 * Algorithms:
 * - HS256/384/512: HMAC-SHA (symmetric key, same key to sign and verify)
 * - RS256/384/512: RSA     (asymmetric, private to sign, public to verify)
 * - ES256/384/512: ECDSA   (faster than RSA, smaller signatures)
 *
 * Standard claims (RFC 7519):
 * - iss: Issuer (who issued the token)
 * - sub: Subject (who the token is about)
 * - aud: Audience (who the token is for)
 * - exp: Expiration time (Unix timestamp)
 * - nbf: Not before (token not valid before this time)
 * - iat: Issued at (when token was created)
 * - jti: JWT ID (unique identifier for replay prevention)
 *
 * Common pitfalls:
 * - Accepting "none" algorithm (allows unsigned tokens!)
 * - Not validating exp claim
 * - Storing sensitive data in payload (it's not encrypted)
 * - Using weak secrets for HS256
 * - Not validating iss/aud claims
 */
public class JwtDemo {

    // ==================== JWT BUILDER ====================

    static class JwtBuilder {
        private String subject;
        private String issuer;
        private String audience;
        private long expirationSeconds;
        private final Map<String, Object> claims = new LinkedHashMap<>();
        private final String secret;

        JwtBuilder(String secret) {
            this.secret = secret;
        }

        JwtBuilder subject(String subject) { this.subject = subject; return this; }
        JwtBuilder issuer(String issuer) { this.issuer = issuer; return this; }
        JwtBuilder audience(String audience) { this.audience = audience; return this; }
        JwtBuilder expiresIn(long seconds) { this.expirationSeconds = seconds; return this; }
        JwtBuilder claim(String name, Object value) { claims.put(name, value); return this; }

        String build() {
            long now = Instant.now().getEpochSecond();

            // Build header
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            // Build payload
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jti", UUID.randomUUID().toString()); // Unique ID for replay prevention
            payload.put("iat", now);
            if (issuer != null) payload.put("iss", issuer);
            if (subject != null) payload.put("sub", subject);
            if (audience != null) payload.put("aud", audience);
            if (expirationSeconds > 0) payload.put("exp", now + expirationSeconds);
            payload.putAll(claims);

            String headerEncoded = base64UrlEncode(toJson(header));
            String payloadEncoded = base64UrlEncode(toJson(payload));
            String signingInput = headerEncoded + "." + payloadEncoded;
            String signature = sign(signingInput, secret);

            return signingInput + "." + signature;
        }
    }

    // ==================== JWT VALIDATOR ====================

    record JwtClaims(String subject, String issuer, String audience, long issuedAt,
                     long expiration, String jwtId, Map<String, Object> customClaims) {
        boolean isExpired() {
            return expiration > 0 && Instant.now().getEpochSecond() > expiration;
        }
    }

    static class JwtValidator {
        private final String secret;
        private String expectedIssuer;
        private String expectedAudience;

        JwtValidator(String secret) {
            this.secret = secret;
        }

        JwtValidator withIssuer(String issuer) { this.expectedIssuer = issuer; return this; }
        JwtValidator withAudience(String audience) { this.expectedAudience = audience; return this; }

        JwtClaims validate(String token) {
            String[] parts = token.split("\\.");
            if (parts.length != 3) throw new IllegalArgumentException("Invalid JWT format");

            String headerEncoded = parts[0];
            String payloadEncoded = parts[1];
            String signatureProvided = parts[2];

            // CRITICAL: Verify signature FIRST (before trusting any claims)
            String signingInput = headerEncoded + "." + payloadEncoded;
            String expectedSignature = sign(signingInput, secret);

            if (!constantTimeEquals(signatureProvided, expectedSignature)) {
                throw new SecurityException("JWT signature verification FAILED! Token may be tampered.");
            }

            // Decode and parse claims (only after signature verification)
            String payloadJson = new String(Base64.getUrlDecoder().decode(payloadEncoded));
            Map<String, Object> claims = parseJson(payloadJson);

            // Check header - CRITICAL: reject "alg": "none" attacks
            String headerJson = new String(Base64.getUrlDecoder().decode(headerEncoded));
            if (headerJson.contains("\"none\"") || headerJson.contains("\"NONE\"")) {
                throw new SecurityException("JWT algorithm 'none' is not allowed!");
            }

            // Validate standard claims
            long exp = ((Number) claims.getOrDefault("exp", 0L)).longValue();
            if (exp > 0 && Instant.now().getEpochSecond() > exp) {
                throw new SecurityException("JWT has expired (exp=" + exp + ")");
            }

            String iss = (String) claims.get("iss");
            if (expectedIssuer != null && !expectedIssuer.equals(iss)) {
                throw new SecurityException("JWT issuer mismatch: expected=" + expectedIssuer + " got=" + iss);
            }

            String aud = (String) claims.get("aud");
            if (expectedAudience != null && !expectedAudience.equals(aud)) {
                throw new SecurityException("JWT audience mismatch: expected=" + expectedAudience + " got=" + aud);
            }

            Map<String, Object> customClaims = new HashMap<>(claims);
            customClaims.remove("sub"); customClaims.remove("iss"); customClaims.remove("aud");
            customClaims.remove("exp"); customClaims.remove("iat"); customClaims.remove("jti");

            return new JwtClaims(
                    (String) claims.get("sub"),
                    (String) claims.get("iss"),
                    (String) claims.get("aud"),
                    ((Number) claims.getOrDefault("iat", 0L)).longValue(),
                    exp,
                    (String) claims.get("jti"),
                    Collections.unmodifiableMap(customClaims)
            );
        }

        // CONCEPT: Constant-time comparison to prevent timing attacks
        // Regular string comparison short-circuits on first mismatch -> reveals information
        private static boolean constantTimeEquals(String a, String b) {
            if (a == null || b == null) return false;
            byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
            byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
            if (aBytes.length != bBytes.length) return false;
            int diff = 0;
            for (int i = 0; i < aBytes.length; i++) diff |= aBytes[i] ^ bBytes[i];
            return diff == 0;
        }
    }

    // ==================== UTILITY METHODS ====================

    static String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return base64UrlEncode(Base64.getUrlEncoder().withoutPadding().encodeToString(signature).getBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign JWT", e);
        }
    }

    static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    static String base64UrlEncode(String data) {
        return base64UrlEncode(data.getBytes(StandardCharsets.UTF_8));
    }

    // Simple JSON serializer (in production: use Jackson or Gson)
    static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        map.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(",");
            sb.append("\"").append(k).append("\":");
            if (v instanceof String) sb.append("\"").append(v).append("\"");
            else if (v instanceof List) {
                sb.append("[");
                List<?> list = (List<?>) v;
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(list.get(i)).append("\"");
                }
                sb.append("]");
            }
            else sb.append(v);
        });
        return sb.append("}").toString();
    }

    // Simple JSON parser
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseJson(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        json = json.trim().replaceAll("^\\{|\\}$", "");
        // Simple parsing for demo (handles strings and numbers)
        String[] pairs = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String pair : pairs) {
            String[] kv = pair.split(":(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim().replaceAll("\"", "");
            String value = kv[1].trim().replaceAll("\"", "");
            if (value.startsWith("[")) {
                map.put(key, value); // List - simplified
            } else {
                try { map.put(key, Long.parseLong(value)); }
                catch (NumberFormatException e) { map.put(key, value); }
            }
        }
        return map;
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== JWT Demo ===");
        System.out.println("NOTE: Using Java built-in crypto. Production: use JJWT or Nimbus JOSE");

        String secret = "super-secret-key-minimum-256-bits-for-security!!"; // 48+ chars

        demonstrateJwtCreation(secret);
        demonstrateJwtValidation(secret);
        demonstrateJwtAttacks(secret);
        explainJwtBestPractices();
    }

    private static void demonstrateJwtCreation(String secret) {
        System.out.println("\n--- JWT Creation ---");

        String token = new JwtBuilder(secret)
                .subject("user-42")
                .issuer("auth.example.com")
                .audience("api.example.com")
                .expiresIn(3600) // 1 hour
                .claim("roles", List.of("admin", "editor"))
                .claim("email", "alice@example.com")
                .claim("tier", "premium")
                .build();

        System.out.println("JWT Token:");
        System.out.println(token);
        System.out.println("\nDecoded parts:");
        String[] parts = token.split("\\.");
        System.out.println("Header:    " + new String(Base64.getUrlDecoder().decode(parts[0])));
        System.out.println("Payload:   " + new String(Base64.getUrlDecoder().decode(parts[1])));
        System.out.println("Signature: " + parts[2].substring(0, 20) + "... (Base64URL)");
    }

    private static void demonstrateJwtValidation(String secret) {
        System.out.println("\n--- JWT Validation ---");

        String validToken = new JwtBuilder(secret)
                .subject("user-42")
                .issuer("auth.example.com")
                .audience("api.example.com")
                .expiresIn(3600)
                .claim("roles", "admin")
                .build();

        JwtValidator validator = new JwtValidator(secret)
                .withIssuer("auth.example.com")
                .withAudience("api.example.com");

        try {
            JwtClaims claims = validator.validate(validToken);
            System.out.println("Valid token! Claims:");
            System.out.println("  Subject:  " + claims.subject());
            System.out.println("  Issuer:   " + claims.issuer());
            System.out.println("  Audience: " + claims.audience());
            System.out.println("  JWT ID:   " + claims.jwtId());
            System.out.println("  Custom:   " + claims.customClaims());
            System.out.println("  Expired:  " + claims.isExpired());
        } catch (Exception e) {
            System.err.println("Validation failed: " + e.getMessage());
        }
    }

    private static void demonstrateJwtAttacks(String secret) {
        System.out.println("\n--- JWT Security Attacks ---");

        JwtValidator validator = new JwtValidator(secret);

        // Attack 1: Tampered payload
        System.out.println("Attack 1: Tampered payload (change roles to admin)");
        String legitimateToken = new JwtBuilder(secret)
                .subject("user-42").claim("roles", "user").expiresIn(3600).build();
        String[] parts = legitimateToken.split("\\.");
        String tamperedPayload = base64UrlEncode("{\"sub\":\"user-42\",\"roles\":\"admin\",\"exp\":9999999999}");
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];
        try {
            validator.validate(tamperedToken);
            System.out.println("  VULNERABLE: Accepted tampered token!");
        } catch (SecurityException e) {
            System.out.println("  SECURE: Rejected tampered token - " + e.getMessage());
        }

        // Attack 2: Expired token
        System.out.println("Attack 2: Expired token");
        String expiredToken = new JwtBuilder(secret)
                .subject("user-42").expiresIn(-1).build(); // Already expired
        try {
            validator.validate(expiredToken);
            System.out.println("  VULNERABLE: Accepted expired token!");
        } catch (SecurityException e) {
            System.out.println("  SECURE: Rejected expired token - " + e.getMessage());
        }

        // Attack 3: Wrong issuer
        System.out.println("Attack 3: Forged issuer");
        String forgedToken = new JwtBuilder(secret)
                .subject("user-42").issuer("evil.attacker.com").expiresIn(3600).build();
        JwtValidator strictValidator = new JwtValidator(secret).withIssuer("auth.example.com");
        try {
            strictValidator.validate(forgedToken);
            System.out.println("  VULNERABLE: Accepted wrong issuer!");
        } catch (SecurityException e) {
            System.out.println("  SECURE: Rejected wrong issuer - " + e.getMessage());
        }
    }

    private static void explainJwtBestPractices() {
        System.out.println("\n--- JWT Best Practices ---");
        System.out.println("1. Use RS256/ES256 for distributed systems (private key signs, public verifies)");
        System.out.println("   HS256 requires sharing secret between services (key distribution problem)");
        System.out.println("2. Keep tokens short-lived (15min - 1hr). Use refresh tokens for longevity");
        System.out.println("3. Always validate: signature, exp, iss, aud");
        System.out.println("4. Reject 'alg: none' (unsigned token attack)");
        System.out.println("5. Don't store sensitive data in payload (it's Base64, not encrypted)");
        System.out.println("6. Implement token revocation (blacklist in Redis) for logout");
        System.out.println("7. Use HTTPS exclusively (tokens can be stolen from HTTP connections)");
        System.out.println("8. Use PKCE for OAuth2 public clients (mobile apps)");
        System.out.println("9. Store in httpOnly cookie (not localStorage) to prevent XSS theft");
        System.out.println("10. Implement jti claim + tracking for replay prevention in high-security contexts");
    }
}
