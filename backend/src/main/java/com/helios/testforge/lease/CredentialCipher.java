package com.helios.testforge.lease;

import com.helios.testforge.config.TestForgeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts lease passwords at rest in the control plane.
 *
 * <p>AES-256-GCM, with the key derived from the platform key that lives in the
 * secret manager rather than in the database. That separation is the point: a
 * dump of the control plane contains ciphertext and no key, so it cannot be
 * turned into a working connection to any ephemeral database.
 *
 * <p>GCM rather than CBC because it authenticates as well as encrypts — a
 * tampered ciphertext fails to decrypt rather than yielding a wrong password
 * and an unexplainable connection error. The lease id is bound in as associated
 * data, so a ciphertext moved to a different lease row will not decrypt either.
 */
@Component
public class CredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    // Explicit: the package-private constructor below is a test seam.
    @Autowired
    public CredentialCipher(TestForgeProperties properties) {
        this(properties.masking().key());
    }

    CredentialCipher(String platformKey) {
        if (platformKey == null || platformKey.isBlank()) {
            throw new IllegalStateException(
                    "no platform key is configured, so lease credentials cannot be encrypted at rest");
        }
        this.key = deriveKey(platformKey);
    }

    /**
     * Encrypts a password.
     *
     * @param leaseId bound in as associated data, so the ciphertext is only
     *                valid for this lease
     * @return base64 of nonce followed by ciphertext
     */
    public String encrypt(String plaintext, String leaseId) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            SECURE_RANDOM.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(leaseId.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + ciphertext.length)
                            .put(nonce).put(ciphertext).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to encrypt a lease credential", e);
        }
    }

    /**
     * Decrypts a password.
     *
     * @throws IllegalStateException when the ciphertext has been tampered with,
     *                               belongs to a different lease, or was
     *                               encrypted under a different key
     */
    public String decrypt(String encoded, String leaseId) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length <= NONCE_BYTES) {
                throw new IllegalStateException("lease credential ciphertext is truncated");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(combined, 0, nonce, 0, NONCE_BYTES);
            byte[] ciphertext = new byte[combined.length - NONCE_BYTES];
            System.arraycopy(combined, NONCE_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(leaseId.getBytes(StandardCharsets.UTF_8));

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "a lease credential could not be decrypted; it may have been tampered with, "
                            + "or the platform key has changed since it was written", e);
        }
    }

    /**
     * Derives a 256-bit key from the configured platform key.
     *
     * <p>SHA-256 rather than a password-based KDF because the input is already a
     * high-entropy secret from the secret manager, not a human-chosen password —
     * there is no brute-force margin for iteration count to buy here.
     */
    private static SecretKeySpec deriveKey(String platformKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update("testforge:lease-credential:v1".getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(sha256.digest(platformKey.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
