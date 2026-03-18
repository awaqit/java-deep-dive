package com.deepdive.month06.week22;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/**
 * Week 22: AES Encryption (Symmetric Encryption)
 *
 * CONCEPT: AES (Advanced Encryption Standard) is a symmetric block cipher.
 * "Symmetric" means the same key is used to encrypt and decrypt.
 * AES-256 uses a 256-bit (32-byte) key and is currently unbroken in practice.
 *
 * Block cipher modes:
 * - ECB (Electronic Codebook):    NEVER USE - same plaintext = same ciphertext (patterns visible)
 * - CBC (Cipher Block Chaining):  Needs IV, sequential (not parallelizable), padding required
 * - CTR (Counter):                Stream cipher mode, parallelizable, no padding
 * - GCM (Galois/Counter Mode):    CTR + authentication tag = authenticated encryption
 *
 * ALWAYS use GCM (or CCM/EAX):
 * - Provides confidentiality (encryption) + integrity (authentication tag)
 * - Detects tampering: modified ciphertext fails decryption with AEADBadTagException
 * - No padding needed (stream mode)
 *
 * Key facts:
 * - AES key sizes: 128, 192, or 256 bits (256 recommended for new systems)
 * - GCM IV (nonce): 12 bytes, MUST be unique per encryption with same key
 * - GCM authentication tag: 128 bits (16 bytes)
 * - IV is NOT secret - include it with the ciphertext
 *
 * Key derivation:
 * - Never use a password directly as an AES key (too short, low entropy)
 * - Use PBKDF2, bcrypt, or Argon2 to derive a key from a password
 * - For machine-to-machine: use KeyGenerator to create a random key
 */
public class AesEncryptionDemo {

    static final String AES_GCM_ALGORITHM = "AES/GCM/NoPadding";
    static final int GCM_IV_LENGTH = 12;       // 96 bits - recommended for GCM
    static final int GCM_TAG_LENGTH = 128;     // 128-bit authentication tag
    static final int AES_KEY_SIZE = 256;       // bits

    // ==================== KEY GENERATION ====================

    /**
     * CONCEPT: Generate a cryptographically random AES-256 key.
     * Uses SecureRandom (CSPRNG) internally - never use Random for crypto!
     */
    static SecretKey generateAesKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_SIZE, new SecureRandom());
        return keyGen.generateKey();
    }

    /**
     * CONCEPT: Derive an AES key from a password using PBKDF2.
     * Password-Based Key Derivation Function 2:
     * - Applies HMAC-SHA256 many times (iterations) to slow down brute force
     * - Salt prevents rainbow table attacks
     * - NIST recommends 600,000+ iterations for PBKDF2-HMAC-SHA256 (2023)
     */
    static SecretKey deriveKeyFromPassword(char[] password, byte[] salt)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        int ITERATIONS = 600_000;
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, AES_KEY_SIZE);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword(); // CONCEPT: Clear password from memory ASAP
        }
    }

    // ==================== AES-GCM ENCRYPTION ====================

    /**
     * CONCEPT: Encrypt plaintext using AES-256-GCM.
     *
     * Output format: [IV (12 bytes)] + [ciphertext + auth tag]
     * The IV is prepended to ciphertext so the receiver can extract it.
     * IV is NOT secret - it just needs to be unique per encryption.
     *
     * WHY GCM over CBC?
     * - GCM = encryption + MAC in one pass (authenticated encryption)
     * - CBC requires separate HMAC for integrity (easy to get wrong)
     * - Padding oracle attacks don't apply to GCM (no padding)
     */
    static byte[] encrypt(byte[] plaintext, SecretKey key) throws Exception {
        // Generate a random 12-byte IV (nonce) - MUST be unique per encryption
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

        byte[] ciphertext = cipher.doFinal(plaintext);

        // Prepend IV to ciphertext: IV | ciphertext+tag
        byte[] result = new byte[GCM_IV_LENGTH + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
        System.arraycopy(ciphertext, 0, result, GCM_IV_LENGTH, ciphertext.length);
        return result;
    }

    /**
     * CONCEPT: Decrypt AES-256-GCM ciphertext.
     * Extracts IV from the first 12 bytes, then decrypts.
     * If ciphertext was tampered, throws AEADBadTagException (authentication fails).
     */
    static byte[] decrypt(byte[] encryptedData, SecretKey key) throws Exception {
        // Extract IV from first 12 bytes
        byte[] iv = Arrays.copyOfRange(encryptedData, 0, GCM_IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(encryptedData, GCM_IV_LENGTH, encryptedData.length);

        Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

        // AEADBadTagException if ciphertext was modified
        return cipher.doFinal(ciphertext);
    }

    // ==================== ADDITIONAL AUTHENTICATED DATA (AAD) ====================

    /**
     * CONCEPT: AAD (Additional Authenticated Data) in GCM.
     * Data that is AUTHENTICATED but NOT encrypted.
     * Use for metadata (user ID, record ID, timestamp) that should be:
     * - Readable without decryption
     * - Protected against tampering (if AAD changes, decryption fails)
     *
     * Example: Encrypt message body, AAD = "userId=123,timestamp=1234567890"
     * If attacker changes the AAD, decryption fails -> prevents context confusion attacks
     */
    static byte[] encryptWithAad(byte[] plaintext, byte[] aad, SecretKey key) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        cipher.updateAAD(aad); // AAD must be set BEFORE calling doFinal

        byte[] ciphertext = cipher.doFinal(plaintext);
        byte[] result = new byte[GCM_IV_LENGTH + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
        System.arraycopy(ciphertext, 0, result, GCM_IV_LENGTH, ciphertext.length);
        return result;
    }

    static byte[] decryptWithAad(byte[] encryptedData, byte[] aad, SecretKey key) throws Exception {
        byte[] iv = Arrays.copyOfRange(encryptedData, 0, GCM_IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(encryptedData, GCM_IV_LENGTH, encryptedData.length);

        Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        cipher.updateAAD(aad); // Must provide same AAD used during encryption

        return cipher.doFinal(ciphertext);
    }

    // ==================== KEY WRAPPING ====================

    /**
     * CONCEPT: Key wrapping - encrypting one key with another key.
     * Used to store or transmit encryption keys securely.
     *
     * Key hierarchy:
     * - Data Encryption Key (DEK): encrypts actual data
     * - Key Encryption Key (KEK): encrypts the DEK
     * - Master Key: in HSM or KMS (AWS KMS, HashiCorp Vault)
     *
     * WHY? If you encrypt data directly with the master key:
     * - Rotating the master key requires re-encrypting ALL data
     * If you use DEK + KEK:
     * - Rotate master key = just re-wrap the DEKs (no data re-encryption)
     */
    static byte[] wrapKey(SecretKey keyToWrap, SecretKey wrappingKey) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        cipher.init(Cipher.WRAP_MODE, wrappingKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

        byte[] wrappedKey = cipher.wrap(keyToWrap);
        byte[] result = new byte[GCM_IV_LENGTH + wrappedKey.length];
        System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
        System.arraycopy(wrappedKey, 0, result, GCM_IV_LENGTH, wrappedKey.length);
        return result;
    }

    static SecretKey unwrapKey(byte[] wrappedKeyData, SecretKey wrappingKey) throws Exception {
        byte[] iv = Arrays.copyOfRange(wrappedKeyData, 0, GCM_IV_LENGTH);
        byte[] wrappedKey = Arrays.copyOfRange(wrappedKeyData, GCM_IV_LENGTH, wrappedKeyData.length);

        Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
        cipher.init(Cipher.UNWRAP_MODE, wrappingKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return (SecretKey) cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY);
    }

    // ==================== DEMOS ====================

    private static void demonstrateBasicEncryption() throws Exception {
        System.out.println("\n--- Basic AES-256-GCM Encryption ---");

        SecretKey key = generateAesKey();
        System.out.println("Generated AES-256 key: " + key.getEncoded().length * 8 + " bits");

        String message = "Sensitive data: credit card 4111-1111-1111-1111, CVV: 123";
        byte[] plaintext = message.getBytes(StandardCharsets.UTF_8);

        // Encrypt
        byte[] encrypted = encrypt(plaintext, key);
        System.out.println("Plaintext:  " + message);
        System.out.println("Encrypted:  " + Base64.getEncoder().encodeToString(encrypted));
        System.out.println("  IV (first 12 bytes):  " +
                Base64.getEncoder().encodeToString(Arrays.copyOfRange(encrypted, 0, 12)));
        System.out.println("  Ciphertext + tag:     " + (encrypted.length - 12) + " bytes");

        // Decrypt
        byte[] decrypted = decrypt(encrypted, key);
        System.out.println("Decrypted:  " + new String(decrypted, StandardCharsets.UTF_8));
        System.out.println("Round-trip successful: " + Arrays.equals(plaintext, decrypted));
    }

    private static void demonstrateTamperDetection() throws Exception {
        System.out.println("\n--- Tamper Detection (GCM Authentication) ---");

        SecretKey key = generateAesKey();
        byte[] encrypted = encrypt("Original message".getBytes(StandardCharsets.UTF_8), key);

        // Try to tamper with the ciphertext
        byte[] tampered = Arrays.copyOf(encrypted, encrypted.length);
        tampered[20] ^= 0xFF; // Flip bits in ciphertext

        System.out.println("Attempting to decrypt tampered ciphertext...");
        try {
            decrypt(tampered, key);
            System.out.println("  VULNERABLE: Tampered ciphertext accepted! (should never happen with GCM)");
        } catch (AEADBadTagException e) {
            System.out.println("  SECURE: AEADBadTagException - GCM detected tampering!");
            System.out.println("  Message: " + e.getMessage());
        }
    }

    private static void demonstratePasswordBasedEncryption() throws Exception {
        System.out.println("\n--- Password-Based Encryption (PBKDF2 + AES-GCM) ---");

        char[] password = "MySecureP@ssw0rd!".toCharArray();
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt); // Random salt, store alongside ciphertext
        System.out.println("Salt (store with ciphertext): " +
                Base64.getEncoder().encodeToString(salt));

        // Derive key from password
        SecretKey key = deriveKeyFromPassword(password, salt);
        System.out.println("Derived AES-256 key via PBKDF2 (600k iterations)");

        String secret = "User's private note: meeting with lawyer at 3pm";
        byte[] encrypted = encrypt(secret.getBytes(StandardCharsets.UTF_8), key);
        System.out.println("Encrypted: " + Base64.getEncoder().encodeToString(encrypted));

        // Decrypt with same password + salt
        SecretKey sameKey = deriveKeyFromPassword(password, salt);
        byte[] decrypted = decrypt(encrypted, sameKey);
        System.out.println("Decrypted: " + new String(decrypted, StandardCharsets.UTF_8));

        // Wrong password fails
        System.out.println("Trying wrong password...");
        try {
            SecretKey wrongKey = deriveKeyFromPassword("wrongpassword".toCharArray(), salt);
            decrypt(encrypted, wrongKey);
            System.out.println("  VULNERABLE: Wrong password accepted!");
        } catch (AEADBadTagException e) {
            System.out.println("  SECURE: Wrong password rejected (auth tag mismatch)");
        }

        Arrays.fill(password, '\0'); // Clear password from memory
    }

    private static void demonstrateAad() throws Exception {
        System.out.println("\n--- Additional Authenticated Data (AAD) ---");

        SecretKey key = generateAesKey();
        String userId = "user-42";
        String payload = "Account balance: $10,000";

        byte[] aad = ("userId=" + userId + ",version=1").getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = encryptWithAad(payload.getBytes(StandardCharsets.UTF_8), aad, key);

        System.out.println("Payload: " + payload);
        System.out.println("AAD (not encrypted, but authenticated): " + new String(aad));

        // Correct AAD: decryption succeeds
        byte[] decrypted = decryptWithAad(encrypted, aad, key);
        System.out.println("Correct AAD: decryption successful -> " +
                new String(decrypted, StandardCharsets.UTF_8));

        // Wrong AAD (different userId): decryption fails
        byte[] wrongAad = "userId=user-99,version=1".getBytes(StandardCharsets.UTF_8);
        try {
            decryptWithAad(encrypted, wrongAad, key);
            System.out.println("VULNERABLE: Wrong AAD accepted!");
        } catch (AEADBadTagException e) {
            System.out.println("Tampered AAD (userId changed): decryption FAILED -> context confusion prevented");
        }
    }

    private static void demonstrateKeyWrapping() throws Exception {
        System.out.println("\n--- Key Wrapping (DEK + KEK Hierarchy) ---");

        // Key Encryption Key (KEK) - would come from KMS in production
        SecretKey kek = generateAesKey();
        System.out.println("Generated KEK (would be in HSM/KMS in production)");

        // Data Encryption Key (DEK) - used to encrypt actual data
        SecretKey dek = generateAesKey();
        System.out.println("Generated DEK for encrypting data");

        // Encrypt actual data with DEK
        String sensitiveData = "Patient record: DOB 1990-01-15, Diagnosis: confidential";
        byte[] encryptedData = encrypt(sensitiveData.getBytes(StandardCharsets.UTF_8), dek);
        System.out.println("Data encrypted with DEK");

        // Wrap DEK with KEK (store wrapped DEK alongside data)
        byte[] wrappedDek = wrapKey(dek, kek);
        System.out.println("DEK wrapped with KEK: " +
                Base64.getEncoder().encodeToString(wrappedDek).substring(0, 30) + "...");

        // To decrypt: unwrap DEK with KEK, then decrypt data
        SecretKey unwrappedDek = unwrapKey(wrappedDek, kek);
        byte[] decryptedData = decrypt(encryptedData, unwrappedDek);
        System.out.println("Unwrapped DEK, decrypted data: " +
                new String(decryptedData, StandardCharsets.UTF_8));
        System.out.println("Key rotation: change KEK = just re-wrap DEK, no data re-encryption!");
    }

    private static void explainAesBestPractices() {
        System.out.println("\n--- AES Encryption Best Practices ---");
        System.out.println("1. ALWAYS use AES-GCM (or AES-CCM/EAX) - authenticated encryption");
        System.out.println("   Never use AES-ECB (patterns visible) or AES-CBC alone (no integrity)");
        System.out.println();
        System.out.println("2. IV/Nonce MUST be unique per encryption with the same key");
        System.out.println("   Nonce reuse with GCM = catastrophic (key recovery possible)");
        System.out.println("   Use SecureRandom for IV generation (never counter without care)");
        System.out.println();
        System.out.println("3. Key sizes:");
        System.out.println("   AES-128: Fine for most applications");
        System.out.println("   AES-256: Required for top-secret / future quantum resistance");
        System.out.println();
        System.out.println("4. Password -> Key: Use PBKDF2/Argon2/bcrypt, NEVER hash directly");
        System.out.println("   SHA-256(password) as AES key is weak (password has low entropy)");
        System.out.println();
        System.out.println("5. Use AAD for metadata that should be integrity-protected");
        System.out.println("   Prevents context confusion: ciphertext for user A can't be used for user B");
        System.out.println();
        System.out.println("6. Key management:");
        System.out.println("   Development: environment variables or secrets manager");
        System.out.println("   Production:  AWS KMS / HashiCorp Vault / Azure Key Vault / HSM");
        System.out.println("   Never hardcode keys in source code!");
        System.out.println();
        System.out.println("7. Libraries to use in production:");
        System.out.println("   - Google Tink: High-level, safe-by-default crypto library");
        System.out.println("   - BouncyCastle: Full-featured, but more footguns");
        System.out.println("   - Java javax.crypto: Fine for AES-GCM, used here");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== AES-256 Encryption Demo ===");
        System.out.println("Using Java built-in javax.crypto (production: consider Google Tink)");

        demonstrateBasicEncryption();
        demonstrateTamperDetection();
        demonstratePasswordBasedEncryption();
        demonstrateAad();
        demonstrateKeyWrapping();
        explainAesBestPractices();
    }
}
