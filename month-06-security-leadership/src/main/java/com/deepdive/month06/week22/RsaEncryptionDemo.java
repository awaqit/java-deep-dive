package com.deepdive.month06.week22;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/**
 * Week 22: RSA Encryption & Digital Signatures (Asymmetric Cryptography)
 *
 * CONCEPT: Asymmetric (public-key) cryptography uses a mathematically linked key pair:
 * - Public key:  Share freely. Anyone can encrypt with it or verify signatures.
 * - Private key: Keep secret. Only holder can decrypt or create signatures.
 *
 * RSA operations:
 * 1. Encryption:  Encrypt with public key -> only private key can decrypt
 * 2. Signatures:  Sign with private key -> anyone with public key can verify
 *
 * RSA is SLOW - don't use it to encrypt large data directly!
 * Solution: Hybrid encryption
 *   1. Generate random AES key (fast)
 *   2. Encrypt data with AES
 *   3. Encrypt AES key with RSA public key (small - key is only 32 bytes)
 *   4. Send: [RSA-encrypted AES key] + [AES-encrypted data]
 * This is how TLS works!
 *
 * RSA key sizes (2024 recommendations):
 * - 2048-bit: Minimum acceptable (legacy)
 * - 3072-bit: NIST recommendation through 2031
 * - 4096-bit: High security / long-term
 *
 * ECDSA vs RSA:
 * - ECDSA (Elliptic Curve): Smaller keys, faster, same security as larger RSA key
 * - P-256 (ECDSA) ≈ RSA-3072 in security, but key is only 256 bits
 * - RSA still widely used for compatibility, ECDSA preferred for new systems
 *
 * Digital signatures:
 * - Prove the sender has the private key (authentication)
 * - Guarantee message hasn't been modified (integrity)
 * - Non-repudiation: sender cannot deny sending (unlike HMAC with shared key)
 *
 * Common padding schemes:
 * - RSA/ECB/PKCS1Padding:      Older, RSA-PKCS#1 v1.5 - vulnerable to Bleichenbacher attack
 * - RSA/ECB/OAEPWithSHA-256...: OAEP - modern, recommended for encryption
 * - SHA256withRSA:              PKCS#1 v1.5 signature (still secure for signing)
 * - SHA256withRSA/PSS:         PSS padding - recommended for new systems
 */
public class RsaEncryptionDemo {

    static final int RSA_KEY_SIZE = 2048;   // bits (use 3072+ for new systems)
    static final int AES_KEY_SIZE = 256;    // bits for hybrid encryption

    // ==================== KEY PAIR GENERATION ====================

    /**
     * CONCEPT: Generate RSA key pair.
     * Private key: kept secret, used to decrypt and sign.
     * Public key:  distributed freely, used to encrypt and verify.
     *
     * In production: store private key in a keystore (PKCS12 file) or HSM.
     * Never store private keys unencrypted.
     */
    static KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("RSA");
        keyPairGen.initialize(RSA_KEY_SIZE, new SecureRandom());
        return keyPairGen.generateKeyPair();
    }

    /**
     * CONCEPT: Generate EC key pair for ECDSA signatures.
     * P-256 (secp256r1) = NIST curve, widely supported.
     * Smaller and faster than RSA for equivalent security.
     */
    static KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("EC");
        keyPairGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return keyPairGen.generateKeyPair();
    }

    // ==================== RSA ENCRYPTION / DECRYPTION ====================

    /**
     * CONCEPT: RSA-OAEP encryption - encrypt with PUBLIC key.
     * OAEP (Optimal Asymmetric Encryption Padding) is the modern, secure padding.
     * Only the holder of the PRIVATE key can decrypt.
     *
     * WHY OAEP over PKCS1v1.5?
     * Bleichenbacher attack: PKCS1v1.5 padding errors can leak information
     * OAEP is provably secure against chosen-ciphertext attacks (CCA2)
     *
     * NOTE: RSA can only encrypt data up to (keySize/8 - padding overhead) bytes.
     * For 2048-bit key with OAEP-SHA256: max ~190 bytes.
     * Use hybrid encryption for larger data.
     */
    static byte[] rsaEncrypt(byte[] plaintext, PublicKey publicKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return cipher.doFinal(plaintext);
    }

    /**
     * CONCEPT: RSA decryption with PRIVATE key.
     */
    static byte[] rsaDecrypt(byte[] ciphertext, PrivateKey privateKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(ciphertext);
    }

    // ==================== HYBRID ENCRYPTION ====================

    /**
     * CONCEPT: Hybrid encryption = RSA + AES.
     * This is how TLS handshake works (simplified).
     *
     * Output format:
     * [4 bytes: wrapped key length] [RSA-encrypted AES key] [AES-GCM encrypted data]
     *
     * To decrypt:
     * 1. Read first 4 bytes -> wrapped key length N
     * 2. RSA-decrypt first N bytes -> AES key
     * 3. AES-GCM decrypt remaining bytes -> plaintext
     */
    static byte[] hybridEncrypt(byte[] plaintext, PublicKey recipientPublicKey) throws Exception {
        // Step 1: Generate ephemeral AES session key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_SIZE, new SecureRandom());
        SecretKey aesKey = keyGen.generateKey();

        // Step 2: Encrypt data with AES-GCM
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] encryptedData = aesCipher.doFinal(plaintext);

        // Prepend IV to encrypted data
        byte[] dataWithIv = new byte[iv.length + encryptedData.length];
        System.arraycopy(iv, 0, dataWithIv, 0, iv.length);
        System.arraycopy(encryptedData, 0, dataWithIv, iv.length, encryptedData.length);

        // Step 3: Encrypt AES key with recipient's RSA public key
        byte[] encryptedKey = rsaEncrypt(aesKey.getEncoded(), recipientPublicKey);

        // Step 4: Combine: [4 bytes key length][encrypted key][encrypted data]
        byte[] result = new byte[4 + encryptedKey.length + dataWithIv.length];
        result[0] = (byte) (encryptedKey.length >> 24);
        result[1] = (byte) (encryptedKey.length >> 16);
        result[2] = (byte) (encryptedKey.length >> 8);
        result[3] = (byte) encryptedKey.length;
        System.arraycopy(encryptedKey, 0, result, 4, encryptedKey.length);
        System.arraycopy(dataWithIv, 0, result, 4 + encryptedKey.length, dataWithIv.length);
        return result;
    }

    static byte[] hybridDecrypt(byte[] encryptedPayload, PrivateKey recipientPrivateKey) throws Exception {
        // Read encrypted key length
        int keyLength = ((encryptedPayload[0] & 0xFF) << 24) |
                ((encryptedPayload[1] & 0xFF) << 16) |
                ((encryptedPayload[2] & 0xFF) << 8) |
                (encryptedPayload[3] & 0xFF);

        // Decrypt AES key using RSA private key
        byte[] encryptedKey = Arrays.copyOfRange(encryptedPayload, 4, 4 + keyLength);
        byte[] aesKeyBytes = rsaDecrypt(encryptedKey, recipientPrivateKey);
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        // Decrypt data using AES-GCM
        byte[] dataWithIv = Arrays.copyOfRange(encryptedPayload, 4 + keyLength, encryptedPayload.length);
        byte[] iv = Arrays.copyOfRange(dataWithIv, 0, 12);
        byte[] ciphertext = Arrays.copyOfRange(dataWithIv, 12, dataWithIv.length);

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        return aesCipher.doFinal(ciphertext);
    }

    // ==================== DIGITAL SIGNATURES ====================

    /**
     * CONCEPT: Digital signature with RSA-PSS.
     * PSS (Probabilistic Signature Scheme) = modern, recommended over PKCS1v1.5.
     *
     * Sign with PRIVATE key -> proves you have the private key.
     * Verify with PUBLIC key -> anyone can verify without the private key.
     *
     * This enables:
     * - Code signing (JAR, APK signing)
     * - Certificate signing (X.509 certificates)
     * - Document signing (PDF signatures)
     * - JWT RS256 signatures
     * - TLS server certificate authentication
     */
    static byte[] signWithRsa(byte[] data, PrivateKey privateKey) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(data);
        return signer.sign();
    }

    static boolean verifyRsaSignature(byte[] data, byte[] signature, PublicKey publicKey)
            throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(data);
        return verifier.verify(signature);
    }

    /**
     * CONCEPT: ECDSA (Elliptic Curve Digital Signature Algorithm).
     * Produces smaller signatures than RSA with equivalent security.
     * ES256 (used in JWTs) = ECDSA with P-256 curve and SHA-256.
     */
    static byte[] signWithEcdsa(byte[] data, PrivateKey privateKey) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey, new SecureRandom());
        signer.update(data);
        return signer.sign();
    }

    static boolean verifyEcdsaSignature(byte[] data, byte[] signature, PublicKey publicKey)
            throws Exception {
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(data);
        return verifier.verify(signature);
    }

    // ==================== KEY SERIALIZATION ====================

    /**
     * CONCEPT: Serialize keys to bytes for storage/transmission.
     * Public keys: encode as X.509 SubjectPublicKeyInfo (DER format)
     * Private keys: encode as PKCS#8 PrivateKeyInfo (DER format)
     *
     * In production: use PKCS12 keystore for private keys:
     *   KeyStore ks = KeyStore.getInstance("PKCS12");
     *   ks.load(null, password);
     *   ks.setKeyEntry("alias", privateKey, password, certChain);
     *   ks.store(outputStream, password);
     */
    static byte[] encodePublicKey(PublicKey key) {
        return key.getEncoded(); // X.509 format
    }

    static byte[] encodePrivateKey(PrivateKey key) {
        return key.getEncoded(); // PKCS#8 format
    }

    static PublicKey decodeRsaPublicKey(byte[] encoded) throws Exception {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    }

    static PrivateKey decodeRsaPrivateKey(byte[] encoded) throws Exception {
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    // ==================== DEMOS ====================

    private static void demonstrateRsaEncryption() throws Exception {
        System.out.println("\n--- RSA Encryption/Decryption ---");

        KeyPair keyPair = generateRsaKeyPair();
        System.out.println("Generated RSA-" + RSA_KEY_SIZE + " key pair");
        System.out.println("Public key size:  " + keyPair.getPublic().getEncoded().length + " bytes");
        System.out.println("Private key size: " + keyPair.getPrivate().getEncoded().length + " bytes");

        // RSA can only encrypt small amounts of data
        String message = "AES session key: secret-32-bytes!"; // Small message
        byte[] encrypted = rsaEncrypt(message.getBytes(StandardCharsets.UTF_8),
                keyPair.getPublic());
        System.out.printf("Plaintext (%d bytes): %s%n", message.length(), message);
        System.out.printf("RSA encrypted: %d bytes (Base64: %s...)%n",
                encrypted.length,
                Base64.getEncoder().encodeToString(encrypted).substring(0, 30));

        byte[] decrypted = rsaDecrypt(encrypted, keyPair.getPrivate());
        System.out.println("Decrypted: " + new String(decrypted, StandardCharsets.UTF_8));
        System.out.println("Round-trip: " + Arrays.equals(message.getBytes(), decrypted));
    }

    private static void demonstrateHybridEncryption() throws Exception {
        System.out.println("\n--- Hybrid Encryption (RSA + AES) ---");
        System.out.println("This is essentially how TLS key exchange works");

        KeyPair recipientKeyPair = generateRsaKeyPair();

        // Large message (RSA alone couldn't encrypt this directly)
        String largeMessage = "Medical record for patient John Doe: " +
                "DOB: 1985-03-15, Blood type: O+, Allergies: penicillin, " +
                "Diagnoses: hypertension (2020), " +
                "Medications: lisinopril 10mg daily, " +
                "Last visit: 2024-01-15, Next appointment: 2024-07-15. " +
                "CONFIDENTIAL - HIPAA protected information.";

        System.out.println("Message length: " + largeMessage.length() + " bytes");

        // Encrypt with recipient's public key (sender's side)
        byte[] encrypted = hybridEncrypt(
                largeMessage.getBytes(StandardCharsets.UTF_8),
                recipientKeyPair.getPublic());
        System.out.println("Encrypted payload: " + encrypted.length + " bytes");
        System.out.println("  (RSA-encrypted AES key + AES-GCM encrypted data)");

        // Decrypt with recipient's private key (recipient's side)
        byte[] decrypted = hybridDecrypt(encrypted, recipientKeyPair.getPrivate());
        System.out.println("Decrypted: " + new String(decrypted, StandardCharsets.UTF_8).substring(0, 50) + "...");
        System.out.println("Round-trip successful: " +
                Arrays.equals(largeMessage.getBytes(), decrypted));
    }

    private static void demonstrateDigitalSignatures() throws Exception {
        System.out.println("\n--- Digital Signatures (RSA) ---");

        KeyPair signerKeyPair = generateRsaKeyPair();
        System.out.println("Signer has RSA key pair (private key kept secret)");

        String document = "Contract: I, Alice, agree to pay Bob $10,000 by 2024-12-31. " +
                "Signed: Alice. Timestamp: 2024-01-15T10:30:00Z";

        // Alice signs the document with her private key
        byte[] signature = signWithRsa(document.getBytes(StandardCharsets.UTF_8),
                signerKeyPair.getPrivate());
        System.out.println("Document signed. Signature length: " + signature.length + " bytes");
        System.out.println("Signature: " +
                Base64.getEncoder().encodeToString(signature).substring(0, 40) + "...");

        // Bob verifies the signature with Alice's public key
        boolean valid = verifyRsaSignature(
                document.getBytes(StandardCharsets.UTF_8),
                signature,
                signerKeyPair.getPublic());
        System.out.println("Signature valid (original document): " + valid);

        // Tampered document - signature fails
        String tampered = document.replace("$10,000", "$1,000,000");
        boolean tamperedValid = verifyRsaSignature(
                tampered.getBytes(StandardCharsets.UTF_8),
                signature,
                signerKeyPair.getPublic());
        System.out.println("Signature valid (tampered document):  " + tamperedValid +
                " <- SECURE: modification detected");
    }

    private static void demonstrateEcdsaSignatures() throws Exception {
        System.out.println("\n--- ECDSA Signatures (Elliptic Curve) ---");

        KeyPair ecKeyPair = generateEcKeyPair();
        System.out.println("Generated EC P-256 key pair");
        System.out.println("EC public key size:  " + ecKeyPair.getPublic().getEncoded().length + " bytes");
        System.out.println("EC private key size: " + ecKeyPair.getPrivate().getEncoded().length + " bytes");
        System.out.println("(Compare: RSA-2048 public key = ~294 bytes, private key = ~1218 bytes)");

        String jwtPayload = "{\"sub\":\"user-42\",\"iss\":\"auth.example.com\",\"exp\":9999999999}";
        byte[] signature = signWithEcdsa(jwtPayload.getBytes(StandardCharsets.UTF_8),
                ecKeyPair.getPrivate());
        System.out.println("JWT payload signed with ECDSA (ES256)");
        System.out.println("Signature: " + Base64.getEncoder().encodeToString(signature));

        boolean valid = verifyEcdsaSignature(
                jwtPayload.getBytes(StandardCharsets.UTF_8),
                signature,
                ecKeyPair.getPublic());
        System.out.println("Signature verified: " + valid);
    }

    private static void demonstrateKeySerializationRoundtrip() throws Exception {
        System.out.println("\n--- Key Serialization ---");

        KeyPair original = generateRsaKeyPair();

        // Serialize
        byte[] pubKeyBytes = encodePublicKey(original.getPublic());
        byte[] privKeyBytes = encodePrivateKey(original.getPrivate());
        System.out.println("Serialized public key:  " + pubKeyBytes.length + " bytes (X.509/DER)");
        System.out.println("Serialized private key: " + privKeyBytes.length + " bytes (PKCS8/DER)");
        System.out.println("PEM format (Base64 of DER):");
        System.out.println("-----BEGIN PUBLIC KEY-----");
        System.out.println(Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(pubKeyBytes).substring(0, 128) + "...");
        System.out.println("-----END PUBLIC KEY-----");

        // Deserialize and verify they work
        PublicKey restoredPublic = decodeRsaPublicKey(pubKeyBytes);
        PrivateKey restoredPrivate = decodeRsaPrivateKey(privKeyBytes);

        String testMessage = "serialization test";
        byte[] sig = signWithRsa(testMessage.getBytes(), restoredPrivate);
        boolean valid = verifyRsaSignature(testMessage.getBytes(), sig, restoredPublic);
        System.out.println("Serialization round-trip works: " + valid);
    }

    private static void explainRsaBestPractices() {
        System.out.println("\n--- RSA & Asymmetric Crypto Best Practices ---");
        System.out.println("1. Use RSA-2048 minimum. Prefer 3072/4096 for long-term security.");
        System.out.println("   Or switch to ECDSA/ECDH (smaller, faster, equivalent security)");
        System.out.println();
        System.out.println("2. NEVER encrypt large data directly with RSA - use hybrid encryption");
        System.out.println("   RSA max plaintext: ~190 bytes (2048-bit, OAEP-SHA256)");
        System.out.println();
        System.out.println("3. Use OAEP padding (not PKCS1v1.5) for RSA encryption");
        System.out.println("   PKCS1v1.5 padding is vulnerable to Bleichenbacher attacks");
        System.out.println();
        System.out.println("4. Private key storage:");
        System.out.println("   - PKCS12 keystore with strong password");
        System.out.println("   - Hardware Security Module (HSM) for production");
        System.out.println("   - Cloud KMS: AWS KMS, GCP Cloud KMS, Azure Key Vault");
        System.out.println();
        System.out.println("5. Certificate management:");
        System.out.println("   - Public keys in X.509 certificates (signed by CA)");
        System.out.println("   - Verify certificate chain before trusting a public key");
        System.out.println("   - Check certificate revocation (OCSP/CRL)");
        System.out.println();
        System.out.println("6. Post-quantum considerations (2024+):");
        System.out.println("   RSA and ECDSA are vulnerable to quantum computers (Shor's algorithm)");
        System.out.println("   NIST PQC standards: CRYSTALS-Kyber (encryption), CRYSTALS-Dilithium (signatures)");
        System.out.println("   Java 21+ has experimental support via BouncyCastle");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== RSA Asymmetric Encryption & Digital Signatures Demo ===");
        System.out.println("Using Java built-in java.security (production: consider BouncyCastle or Tink)");

        demonstrateRsaEncryption();
        demonstrateHybridEncryption();
        demonstrateDigitalSignatures();
        demonstrateEcdsaSignatures();
        demonstrateKeySerializationRoundtrip();
        explainRsaBestPractices();
    }
}
