package com.example.fixedassets.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password hashing and verification for Landmark user accounts.
 *
 * Migration strategy — "hash on first successful use":
 *   Legacy passwords are stored as plain text in meusers.password.
 *   On first successful login, the plain-text value is replaced with
 *   a BCrypt hash (strength 12).  Subsequent logins use BCrypt.
 *
 *   Detection: a stored value is plain text when it does NOT start with
 *   the BCrypt prefix "$2a$", "$2b$", or "$2y$".
 *
 * No JDBC in this class — the caller (LoginController / UserService)
 * passes in the stored hash and handles DB writes separately.
 */
@Service
public class PasswordService {

    private static final int BCRYPT_STRENGTH = 12;

    private final PasswordEncoder encoder =
        new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    // ── Verification ───────────────────────────────────────────────────────

    /**
     * Verify a plaintext attempt against whatever is stored.
     * Works for both BCrypt hashes and legacy plain-text passwords.
     *
     * @param storedValue  the value currently in meusers.password
     * @param plainText    the password the user just typed
     * @return true if the password is correct
     */
    public boolean verify(String storedValue, String plainText) {
        if (storedValue == null || storedValue.isBlank()) return false;
        if (plainText  == null || plainText.isBlank())  return false;
        if (isBcrypt(storedValue)) {
            return encoder.matches(plainText, storedValue);
        }
        // Legacy plain-text comparison
        return storedValue.equals(plainText);
    }

    /**
     * Returns true if the stored value looks like a BCrypt hash.
     * Plain-text passwords from COBOL are never longer than 20 chars
     * and never start with "$2".
     */
    public boolean isBcrypt(String stored) {
        return stored != null && stored.startsWith("$2");
    }

    /**
     * Encode a plaintext password using BCrypt (strength 12).
     * Call this when a user successfully authenticates with a plain-text
     * password so the value can be migrated in meusers.password.
     */
    public String encode(String plainText) {
        return encoder.encode(plainText);
    }

    /**
     * Convenience: verify AND return the BCrypt hash to write back
     * if migration is needed.  Returns null if password is wrong.
     * Returns an empty string if password is correct and already BCrypt.
     * Returns the new hash if password is correct and was plain text.
     *
     * Usage:
     *   String hashToWrite = passwordService.verifyAndMigrate(stored, plaintext);
     *   if (hashToWrite == null) { // wrong password }
     *   else if (!hashToWrite.isEmpty()) { userService.writePassword(userId, hashToWrite); }
     */
    public String verifyAndMigrate(String storedValue, String plainText) {
        if (!verify(storedValue, plainText)) return null;      // wrong password
        if (isBcrypt(storedValue))           return "";        // already hashed, no write needed
        return encode(plainText);                              // migrate: return hash to write
    }
}
