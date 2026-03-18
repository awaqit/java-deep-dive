# Month 6 — Security & Leadership: Setup Guide

## Dependencies

Uncomment in `month-06-security-leadership/build.gradle` based on what you want to run:

```gradle
dependencies {
    // BouncyCastle — advanced cryptography beyond JDK defaults (RSA, AES-GCM, ECDSA)
    implementation 'org.bouncycastle:bcprov-jdk18on:1.77'
    implementation 'org.bouncycastle:bcpkix-jdk18on:1.77'

    // JJWT — JWT creation, signing, and validation
    implementation 'io.jsonwebtoken:jjwt-api:0.12.3'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.3'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.3'

    // Spring Security — OAuth2 and authentication
    implementation 'org.springframework.boot:spring-boot-starter-security:3.2.1'
    implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server:3.2.1'
}
```

> **Note:** `JwtDemo` and `AesEncryptionDemo` use the JDK's built-in `javax.crypto` — no libraries needed. BouncyCastle is only needed for `RsaEncryptionDemo` (ECDSA/OAEP) and advanced key management.

---

## External Tools

### 1. HashiCorp Vault
> Used with: `SecretsManagementDemo` concepts — dynamic secrets, key rotation, audit log

**Docker (dev mode):**
```bash
docker run -d \
  --name vault \
  -p 8200:8200 \
  --cap-add=IPC_LOCK \
  -e VAULT_DEV_ROOT_TOKEN_ID=root \
  hashicorp/vault:latest

export VAULT_ADDR='http://localhost:8200'
export VAULT_TOKEN='root'

vault status
```

**Key management with Vault Transit (encryption as a service):**
```bash
# Enable transit secrets engine
vault secrets enable transit

# Create an encryption key (never leaves Vault)
vault write -f transit/keys/app-key

# Encrypt data (plaintext must be base64)
vault write transit/encrypt/app-key \
  plaintext=$(echo -n "my secret data" | base64)

# Decrypt
vault write transit/decrypt/app-key \
  ciphertext="vault:v1:..."

# Rotate the key (old versions can still decrypt)
vault write -f transit/keys/app-key/rotate

# Rewrap old ciphertext to new key version
vault write transit/rewrap/app-key ciphertext="vault:v1:..."
```

---

### 2. Keycloak (OAuth2 / OIDC Identity Provider)
> Used with: `OAuthConceptsDemo` — run a real Authorization Code + PKCE flow

**Docker:**
```bash
docker run -d \
  --name keycloak \
  -p 8080:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:23.0 \
  start-dev

# Open admin console: http://localhost:8080/admin
# Login: admin / admin
```

**Setup a realm and client:**
1. Create a new **Realm**: `demo`
2. Create a **Client**: `java-app`
   - Client type: `OpenID Connect`
   - Valid redirect URIs: `http://localhost:8081/callback`
   - Enable **PKCE**: `S256`
3. Create a **User**: set username + password

**Test the Authorization Code flow:**
```bash
# 1. Get authorization URL (open in browser)
http://localhost:8080/realms/demo/protocol/openid-connect/auth\
?client_id=java-app\
&response_type=code\
&redirect_uri=http://localhost:8081/callback\
&code_challenge=<PKCE_CHALLENGE>\
&code_challenge_method=S256

# 2. Exchange code for token
curl -X POST http://localhost:8080/realms/demo/protocol/openid-connect/token \
  -d "grant_type=authorization_code" \
  -d "client_id=java-app" \
  -d "code=<AUTH_CODE>" \
  -d "redirect_uri=http://localhost:8081/callback" \
  -d "code_verifier=<PKCE_VERIFIER>"
```

---

### 3. JWT Debugging Tools

**jwt.io** — paste any JWT to inspect header, payload, and verify signature:
> https://jwt.io

**jwt-cli (terminal):**
```bash
brew install mike-engel/jwt-cli/jwt-cli

# Decode a JWT (no verification)
jwt decode eyJhbGciOiJIUzI1NiJ9...

# Create a JWT
jwt encode --secret mysecret --exp=+1h '{"sub":"user1","role":"admin"}'
```

---

### 4. OpenSSL (key generation and certificate management)
> Used with: `RsaEncryptionDemo`, `AesEncryptionDemo` — generate real RSA/EC keys

```bash
# Generate RSA 2048-bit key pair
openssl genrsa -out private.pem 2048
openssl rsa -in private.pem -pubout -out public.pem

# Generate EC P-256 key pair (used in ECDSA)
openssl ecparam -name prime256v1 -genkey -noout -out ec-private.pem
openssl ec -in ec-private.pem -pubout -out ec-public.pem

# Generate a self-signed certificate (for mTLS demos)
openssl req -x509 -new -key private.pem -out cert.pem -days 365 \
  -subj "/CN=localhost/O=Demo"

# Inspect a certificate
openssl x509 -in cert.pem -text -noout

# Encrypt a file with AES-256-CBC
openssl enc -aes-256-cbc -pbkdf2 -in plaintext.txt -out encrypted.bin
openssl enc -d -aes-256-cbc -pbkdf2 -in encrypted.bin -out decrypted.txt
```

---

### 5. OWASP ZAP (security scanning — Week 21–22)
> Automated vulnerability scanner to test your JWT and OAuth endpoints

```bash
# Docker
docker run -d \
  --name zap \
  -p 8090:8090 \
  ghcr.io/zaproxy/zaproxy:stable \
  zap-webswing.sh

# Open ZAP UI: http://localhost:8090/zap
```

---

### 6. Trivy (container image vulnerability scanning — DevSecOps)
> From Month 6 DevSecOps supplement course — scan Docker images for CVEs

```bash
brew install trivy

# Scan an image
trivy image openjdk:21-jdk-slim

# Scan your project's filesystem for vulnerabilities
trivy fs .

# Scan Gradle dependencies
trivy fs --security-checks vuln build.gradle
```

---

## JDK Built-in Crypto Reference

The JDK `javax.crypto` package covers most of what the demos use — no extra deps needed:

```java
// AES-256-GCM (used in AesEncryptionDemo)
KeyGenerator kg = KeyGenerator.getInstance("AES");
kg.init(256);
SecretKey key = kg.generateKey();
Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

// RSA-2048 (used in RsaEncryptionDemo)
KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
kpg.initialize(2048);
KeyPair kp = kpg.generateKeyPair();

// PBKDF2 key derivation (used in AesEncryptionDemo)
SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
PBEKeySpec spec = new PBEKeySpec(password, salt, 600_000, 256);
```

---

## Quick Start

```bash
# 1. Start Vault (optional — for SecretsManagementDemo concepts)
docker run -d --name vault -p 8200:8200 \
  -e VAULT_DEV_ROOT_TOKEN_ID=root hashicorp/vault:latest

# 2. Compile the module
./gradlew :month-06-security-leadership:compileJava

# 3. Run JWT demo (no external deps)
./gradlew :month-06-security-leadership:run \
  -PmainClass=com.deepdive.month06.week21.JwtDemo

# 4. Run AES encryption demo (no external deps)
./gradlew :month-06-security-leadership:run \
  -PmainClass=com.deepdive.month06.week22.AesEncryptionDemo

# 5. Run RSA encryption demo (no external deps)
./gradlew :month-06-security-leadership:run \
  -PmainClass=com.deepdive.month06.week22.RsaEncryptionDemo

# 6. Run tech leadership demo
./gradlew :month-06-security-leadership:run \
  -PmainClass=com.deepdive.month06.week24.TechLeadershipPatterns
```
