/*
 * Copyright (c) 2026 Landmark Software Pty Ltd.
 * All rights reserved.
 *
 * This software is proprietary and confidential.
 * Unauthorised copying, modification, distribution or use
 * of this software, via any medium, is strictly prohibited.
 * Decompilation and reverse engineering are expressly forbidden.
 *
 * Licenced under the terms of the Landmark Software Licence Agreement.
 */
package com.landmarksoftware.service;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.model.UserRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MENU00 — User account operations.
 *
 * All JDBC for LoginController, extracted so that:
 *   - LoginController contains only UI and decision logic.
 *   - This service is independently testable.
 *
 * Tables: meusers, MEPASS
 */
@Service
public class UserService {

    private final JdbcTemplate jdbc;
    private final AppSession   appSession;

    public UserService(JdbcTemplate jdbc, AppSession appSession) {
        this.jdbc       = jdbc;
        this.appSession = appSession;
    }

    // ── User lookup ───────────────────────────────────────────────────────

    /**
     * Load a meusers row by user ID.
     * Returns empty if the user does not exist.
     */
    public Optional<UserRecord> findUser(String userId) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                AuthSql.FIND_USER_BY_PK, userId);
            LocalDate expiry = null;
            Object exp = row.get("passwd_expiry_date");
            if (exp instanceof java.sql.Date d) expiry = d.toLocalDate();
            else if (exp instanceof LocalDate ld) expiry = ld;
            return Optional.of(new UserRecord(
                str(row.get("user_id")),
                str(row.get("name1")),
                str(row.get("password")),
                str(row.get("user_status")),
                str(row.get("supervisor_flag")),
                expiry,
                str(row.get("print_pa_from_pass"))));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Look up user IDs by email address, for the "Forgot User ID" dialog.
     * Excludes terminated accounts.
     *
     * @return list of matching user IDs (never null, may be empty)
     */
    public List<String> findUserIdsByEmail(String email) {
        return jdbc.queryForList(
            AuthSql.FIND_USER_IDS_BY_EMAIL, String.class, email);
    }

    // ── Account status writes ─────────────────────────────────────────────

    /**
     * Lock a user account after too many failed login attempts.
     * Sets user_status = 'L'.  Non-fatal if DB write fails.
     */
    public void lockUser(String userId) {
        try {
            jdbc.update(AuthSql.LOCK_USER, userId);
        } catch (Exception e) {
            log("lockUser failed for " + userId + ": " + e.getMessage());
        }
    }

    /**
     * Record a successful login:
     *   1. Update meusers.last_access_date to today.
     *   2. Allocate or reuse a terminal slot in MEPASS.
     *
     * Both writes are non-fatal — a failure here does not block the login.
     *
     * @param userId      the authenticated user
     * @param companyNo   current company (from AppSession, may be 0 at login time)
     * @param companyName current company name (may be blank at login time)
     */
    public void recordSuccessfulLogin(String userId, int companyNo, String companyName) {
        updateLastAccess(userId);
        writeSessionToMepass(userId, companyNo, companyName);
    }

    private void updateLastAccess(String userId) {
        try {
            jdbc.update(
                AuthSql.UPDATE_USER_LAST_ACCESS,
                java.sql.Date.valueOf(LocalDate.now()), userId);
        } catch (Exception e) {
            log("updateLastAccess failed: " + e.getMessage());
        }
    }

    /**
     * Allocate a MEPASS terminal slot for the session.
     * Uses MAX(terminal_no)+1, capped at 998 (999 is reserved).
     * Writes back the allocated terminal number to AppSession.
     */
    private void writeSessionToMepass(String userId, int companyNo, String companyName) {
        try {
            Integer n = jdbc.queryForObject(
                AuthSql.FIND_NEXT_MEPASS_TERMINAL_NO, Integer.class);
            if (n == null || n >= 999) n = 1;
            LocalTime t = LocalTime.now();
            jdbc.update(
                AuthSql.UPSERT_MEPASS_SESSION,
                n, userId, companyNo, companyName,
                java.sql.Date.valueOf(LocalDate.now()),
                t.getHour(), t.getMinute());
            appSession.setTerminalNo(n);
            log("MEPASS: terminal=" + n + " user=" + userId);
        } catch (Exception e) {
            log("MEPASS write (non-fatal): " + e.getMessage());
        }
    }

    // ── Password reset ────────────────────────────────────────────────────

    /**
     * Result of a password-reset operation.
     * On success, tempPassword is non-null and errorMessage is null.
     * On failure, tempPassword is null and errorMessage describes the problem.
     */
    public record TempPasswordResult(String tempPassword, String errorMessage) {
        public boolean success() { return tempPassword != null; }
    }

    /**
     * Generate a temporary password, write it to meusers, and expire it immediately
     * (passwd_expiry_date = today) so the user is forced to change it on next login.
     *
     * The temporary password is 6 chars: 3 uppercase letters + 3 digits,
     * excluding visually ambiguous characters (0/O, 1/I, etc.).
     *
     * @Transactional — the SELECT and UPDATE are an atomic unit.
     */
    @Transactional
    public TempPasswordResult generateTempPassword(String userId) {
        Optional<UserRecord> found = findUser(userId);
        if (found.isEmpty())
            return new TempPasswordResult(null, "User ID not found.");
        if ("T".equals(found.get().userStatus()))
            return new TempPasswordResult(null, "This account has been terminated.");

        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String digits  = "23456789";
        java.util.Random rng = new java.util.Random();
        String tmp = "" +
            letters.charAt(rng.nextInt(letters.length())) +
            letters.charAt(rng.nextInt(letters.length())) +
            letters.charAt(rng.nextInt(letters.length())) +
            digits.charAt(rng.nextInt(digits.length())) +
            digits.charAt(rng.nextInt(digits.length())) +
            digits.charAt(rng.nextInt(digits.length()));
        try {
            jdbc.update(
                AuthSql.UPDATE_USER_TEMP_PASSWORD,
                tmp, java.sql.Date.valueOf(LocalDate.now()), userId);
        } catch (Exception e) {
            return new TempPasswordResult(null, "Could not update password: " + e.getMessage());
        }
        return new TempPasswordResult(tmp, null);
    }

    /**
     * Write a BCrypt hash for the given user — called by LoginController
     * when migrating a plain-text password on first successful login.
     * Non-fatal: a failure here does not interrupt the session.
     */
    public void writePasswordHash(String userId, String bcryptHash) {
        try {
            jdbc.update(AuthSql.UPDATE_USER_PASSWORD_HASH, bcryptHash, userId);
        } catch (Exception e) {
            log("writePasswordHash failed (non-fatal): " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String str(Object v) { return v == null ? "" : v.toString().trim(); }
    private static void   log(String m) { System.out.println("UserService: " + m); }
}
