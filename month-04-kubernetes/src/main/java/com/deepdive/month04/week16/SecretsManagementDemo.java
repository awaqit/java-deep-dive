package com.deepdive.month04.week16;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Week 16: Secrets Management Patterns
 *
 * CONCEPT: Secrets (API keys, DB passwords, TLS certs) require special handling:
 * - Never store in source code or container images
 * - Encrypt at rest and in transit
 * - Rotate regularly without application downtime
 * - Audit who accessed what and when
 * - Limit access to only what's needed (least privilege)
 *
 * Options in Kubernetes:
 * 1. Kubernetes Secrets (basic, base64 encoded, NOT encrypted by default)
 *    - Enable EncryptionConfiguration for encryption at rest
 *    - Mount as files (preferred) or environment variables (leaked in ps, /proc)
 *
 * 2. External Secret Stores (production recommended):
 *    - HashiCorp Vault: Full-featured secret store with dynamic secrets
 *    - AWS Secrets Manager / Parameter Store
 *    - GCP Secret Manager
 *    - Azure Key Vault
 *    - Kubernetes External Secrets Operator (ESO): Syncs external secrets to K8s
 *
 * 3. Sealed Secrets (Bitnami): Encrypt secrets for GitOps workflows
 *    - Encrypt locally with public key -> Safe to commit to git
 *    - Decrypt by controller in cluster
 *
 * Dynamic secrets (Vault feature):
 * - Vault generates short-lived credentials on demand
 * - Database credentials: "This pod gets a unique username/password, valid 1 hour"
 * - Auto-rotated: pod renews lease before expiry
 * - No long-lived shared credentials in environment variables!
 *
 * The golden rule: Secrets should never touch disk unencrypted or appear in logs.
 */
public class SecretsManagementDemo {

    // ==================== VAULT-STYLE SECRET STORE ====================

    record SecretMetadata(String path, long version, long createdAt, long ttlMs, String accessor) {}

    record Secret(String path, Map<String, String> data, SecretMetadata metadata) {
        boolean isExpired() {
            if (metadata.ttlMs() <= 0) return false; // No TTL = static secret
            return System.currentTimeMillis() - metadata.createdAt() > metadata.ttlMs();
        }
    }

    static class VaultStyleSecretStore {
        private final Map<String, List<Secret>> secretVersions = new ConcurrentHashMap<>();
        private final List<AuditEntry> auditLog = new ArrayList<>();
        private final Map<String, String> accessPolicies = new HashMap<>();

        record AuditEntry(long timestamp, String accessor, String operation, String path) {}

        void definePolicy(String role, String path) {
            accessPolicies.put(role + ":" + path, "allow");
        }

        // Write a new version of a secret
        void writeSecret(String path, Map<String, String> data, String accessor) {
            writeSecret(path, data, accessor, -1); // No TTL by default
        }

        void writeSecret(String path, Map<String, String> data, String accessor, long ttlMs) {
            List<Secret> versions = secretVersions.computeIfAbsent(path, k -> new ArrayList<>());
            long version = versions.size() + 1;
            SecretMetadata meta = new SecretMetadata(path, version, System.currentTimeMillis(), ttlMs, accessor);
            versions.add(new Secret(path, new HashMap<>(data), meta));
            audit(accessor, "write", path);
            System.out.printf("  [VAULT] Wrote secret %s v%d%s%n",
                    path, version, ttlMs > 0 ? " (TTL: " + ttlMs + "ms)" : "");
        }

        // Read the latest version of a secret
        Optional<Secret> readSecret(String path, String accessor) {
            if (!checkPolicy(accessor, path)) {
                audit(accessor, "denied-read", path);
                System.out.printf("  [VAULT] DENIED: %s cannot read %s%n", accessor, path);
                return Optional.empty();
            }

            List<Secret> versions = secretVersions.get(path);
            if (versions == null || versions.isEmpty()) {
                audit(accessor, "miss", path);
                return Optional.empty();
            }

            Secret latest = versions.get(versions.size() - 1);
            if (latest.isExpired()) {
                audit(accessor, "expired", path);
                System.out.printf("  [VAULT] Secret %s has expired%n", path);
                return Optional.empty();
            }

            audit(accessor, "read", path);
            // CONCEPT: Mask sensitive values in logs
            System.out.printf("  [VAULT] Read %s v%d by %s (masked: %s)%n",
                    path, latest.metadata().version(), accessor,
                    maskSecret(latest.data().toString()));
            return Optional.of(latest);
        }

        // Get all versions of a secret (for rotation/audit purposes)
        List<Secret> listVersions(String path) {
            return Collections.unmodifiableList(secretVersions.getOrDefault(path, Collections.emptyList()));
        }

        private boolean checkPolicy(String accessor, String path) {
            // Check exact path or wildcard parent
            return accessPolicies.containsKey(accessor + ":" + path) ||
                    accessPolicies.containsKey(accessor + ":*") ||
                    accessPolicies.entrySet().stream().anyMatch(e ->
                            e.getKey().startsWith(accessor + ":") &&
                            path.startsWith(e.getKey().substring(accessor.length() + 1)
                                    .replace("*", "")));
        }

        private void audit(String accessor, String operation, String path) {
            auditLog.add(new AuditEntry(System.currentTimeMillis(), accessor, operation, path));
        }

        void printAuditLog() {
            System.out.println("\n  Audit Log:");
            auditLog.forEach(e -> System.out.printf("    [%s] %s %s %s%n",
                    new java.util.Date(e.timestamp()), e.accessor(), e.operation(), e.path()));
        }

        // CONCEPT: Mask sensitive values - never log raw secrets
        private String maskSecret(String value) {
            return value.replaceAll("[^=,{}\\[\\] ]", "*");
        }
    }

    // ==================== DYNAMIC SECRETS (VAULT DB ENGINE) ====================

    /**
     * CONCEPT: Dynamic secrets are generated on-demand, tied to a specific requester,
     * and automatically expire. This eliminates shared, long-lived credentials.
     *
     * Example: Vault database engine:
     * 1. Vault has a privileged DB connection (rotation root credential)
     * 2. Pod requests credentials: vault write db/creds/my-role
     * 3. Vault creates a unique DB user with time-limited password
     * 4. Pod uses credentials until lease expires
     * 5. Pod renews lease OR Vault revokes credentials on pod termination
     */
    static class DynamicSecretEngine {
        private final Map<String, String> activeCredentials = new ConcurrentHashMap<>();
        private int createdCount = 0;

        record DatabaseCredential(String username, String password, long expiresAt, String leaseId) {
            boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
        }

        DatabaseCredential generateCredentials(String role, long ttlMs) {
            String username = "vault_" + role + "_" + (++createdCount) + "_" +
                    Long.toHexString(System.currentTimeMillis() % 0xFFFFF);
            // In real Vault: generates a cryptographically random password and creates DB user
            String password = UUID.randomUUID().toString().replace("-", "");
            String leaseId = "db/creds/" + role + "/" + UUID.randomUUID();

            activeCredentials.put(leaseId, username);
            DatabaseCredential cred = new DatabaseCredential(username, password,
                    System.currentTimeMillis() + ttlMs, leaseId);

            System.out.printf("  [DYNAMIC-SECRET] Generated: user=%s lease=%s TTL=%dms%n",
                    username, leaseId, ttlMs);
            return cred;
        }

        void revoke(String leaseId) {
            String username = activeCredentials.remove(leaseId);
            if (username != null) {
                System.out.printf("  [DYNAMIC-SECRET] Revoked: user=%s lease=%s (DB user dropped)%n",
                        username, leaseId);
            }
        }

        // Renew a lease before it expires
        DatabaseCredential renew(DatabaseCredential cred, long extensionMs) {
            if (cred.isExpired()) throw new IllegalStateException("Cannot renew expired credential");
            System.out.printf("  [DYNAMIC-SECRET] Renewed lease %s (+%dms)%n",
                    cred.leaseId(), extensionMs);
            return new DatabaseCredential(cred.username(), cred.password(),
                    cred.expiresAt() + extensionMs, cred.leaseId());
        }

        int getActiveCount() { return activeCredentials.size(); }
    }

    // ==================== SECRET ROTATION ====================

    /**
     * CONCEPT: Secret rotation without downtime using the "dual write" pattern:
     * 1. Write new version of secret alongside old version
     * 2. Applications reload new credential (via volume mount re-read or Vault agent)
     * 3. Verify all instances are using new credential
     * 4. Revoke/delete old credential
     */
    static class SecretRotationManager {
        private final VaultStyleSecretStore vault;

        SecretRotationManager(VaultStyleSecretStore vault) {
            this.vault = vault;
        }

        void rotateDatabasePassword(String secretPath, String accessorRole) {
            System.out.println("  Starting rotation for " + secretPath + ":");

            // Step 1: Read current credential
            Optional<Secret> current = vault.readSecret(secretPath, accessorRole);
            if (current.isEmpty()) { System.out.println("  No current secret found"); return; }

            System.out.println("  Step 1: Current credential read (v" + current.get().metadata().version() + ")");

            // Step 2: Generate new credential
            String newPassword = UUID.randomUUID().toString();
            Map<String, String> newData = new HashMap<>(current.get().data());
            newData.put("password", newPassword);
            newData.put("rotated_at", new Date().toString());

            // Step 3: Write new version
            vault.writeSecret(secretPath, newData, accessorRole);
            System.out.println("  Step 2: New credential written (new version)");

            // Step 4: Applications pick up new credential (e.g., via Vault Agent sidecar,
            // Kubernetes External Secrets Operator, or application restart)
            System.out.println("  Step 3: Applications reload secret (Vault Agent / ESO sync)");

            System.out.println("  Rotation complete! Version history:");
            vault.listVersions(secretPath).forEach(v ->
                    System.out.printf("    v%d created at %s%n",
                            v.metadata().version(), new Date(v.metadata().createdAt())));
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Secrets Management Demo ===");

        demonstrateVaultStyleSecrets();
        demonstrateDynamicSecrets();
        demonstrateSecretRotation();
        showKubernetesSecretsBestPractices();
    }

    private static void demonstrateVaultStyleSecrets() {
        System.out.println("\n--- Vault-Style Secret Store ---");

        VaultStyleSecretStore vault = new VaultStyleSecretStore();

        // Define access policies
        vault.definePolicy("order-service", "secret/production/database");
        vault.definePolicy("order-service", "secret/production/kafka");
        vault.definePolicy("payment-service", "secret/production/payment-gateway");
        vault.definePolicy("admin", "*"); // Admin has access to all

        // Write secrets
        vault.writeSecret("secret/production/database", Map.of(
                "url", "jdbc:postgresql://prod-db:5432/orders",
                "username", "order_service",
                "password", "p@ssw0rd-DO-NOT-LOG"
        ), "admin");

        vault.writeSecret("secret/production/payment-gateway", Map.of(
                "apiKey", "sk_live_xxxxxxxxxxxx",
                "webhookSecret", "whsec_xxxxxxxxxxxx"
        ), "admin");

        // Read secrets - authorized
        System.out.println("\nAuthorized reads:");
        vault.readSecret("secret/production/database", "order-service");
        vault.readSecret("secret/production/payment-gateway", "payment-service");

        // Read secrets - unauthorized (cross-service access attempt)
        System.out.println("\nUnauthorized reads:");
        vault.readSecret("secret/production/payment-gateway", "order-service");
        vault.readSecret("secret/production/database", "payment-service");

        vault.printAuditLog();
    }

    private static void demonstrateDynamicSecrets() throws InterruptedException {
        System.out.println("\n--- Dynamic Secrets (Short-lived credentials) ---");

        DynamicSecretEngine engine = new DynamicSecretEngine();

        // Different services get different credentials
        DynamicSecretEngine.DatabaseCredential orderCred = engine.generateCredentials("order-service", 1000);
        DynamicSecretEngine.DatabaseCredential paymentCred = engine.generateCredentials("payment-service", 2000);

        System.out.printf("  Active credentials: %d (each service has unique creds)%n", engine.getActiveCount());

        // Renew before expiry
        orderCred = engine.renew(orderCred, 1000);

        Thread.sleep(600);
        System.out.println("  After 600ms: credentials still valid");

        // On pod termination: revoke immediately
        System.out.println("  Pod 'order-service' terminating - revoking credentials...");
        engine.revoke(orderCred.leaseId());
        System.out.printf("  Active credentials after revocation: %d%n", engine.getActiveCount());

        System.out.println("\nDynamic secrets advantage:");
        System.out.println("  - No shared long-lived passwords in environment variables");
        System.out.println("  - Credentials automatically expire (blast radius limited)");
        System.out.println("  - Unique per-pod credentials enable fine-grained audit");
    }

    private static void demonstrateSecretRotation() {
        System.out.println("\n--- Secret Rotation Without Downtime ---");

        VaultStyleSecretStore vault = new VaultStyleSecretStore();
        vault.definePolicy("admin", "*");
        vault.definePolicy("order-service", "secret/production/database");

        vault.writeSecret("secret/production/database", Map.of(
                "password", "old-password-123"
        ), "admin");

        SecretRotationManager rotationManager = new SecretRotationManager(vault);
        rotationManager.rotateDatabasePassword("secret/production/database", "admin");
    }

    private static void showKubernetesSecretsBestPractices() {
        System.out.println("\n--- Kubernetes Secrets Best Practices ---");
        System.out.println("1. Enable EncryptionConfiguration (encrypt at rest in etcd):");
        System.out.println("   --encryption-provider-config=/etc/kubernetes/encryption.yaml");
        System.out.println();
        System.out.println("2. Mount as files, not env vars (env vars leak into process list):");
        System.out.println("   volumeMounts:");
        System.out.println("   - name: db-secret");
        System.out.println("     mountPath: /secrets/db");
        System.out.println("     readOnly: true");
        System.out.println("   volumes:");
        System.out.println("   - name: db-secret");
        System.out.println("     secret:");
        System.out.println("       secretName: database-credentials");
        System.out.println();
        System.out.println("3. External Secrets Operator (recommended for production):");
        System.out.println("   apiVersion: external-secrets.io/v1beta1");
        System.out.println("   kind: ExternalSecret");
        System.out.println("   spec:");
        System.out.println("     secretStoreRef:");
        System.out.println("       name: aws-secretsmanager");
        System.out.println("     target:");
        System.out.println("       name: database-credentials");
        System.out.println("     data:");
        System.out.println("     - secretKey: password");
        System.out.println("       remoteRef:");
        System.out.println("         key: prod/order-service/db-password");
        System.out.println();
        System.out.println("4. Never commit secrets to git (use Sealed Secrets or SOPS for GitOps)");
        System.out.println("5. Audit with RBAC: who accessed which secrets, when");
    }
}
