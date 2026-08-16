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
import jakarta.annotation.PostConstruct;
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

    /**
     * {@code mepass} is a Java replacement for the ACUCOBOL MEPASS Vision
     * file (terminal-session tracking) — it has no ACU/COBOL source, so it
     * is created here rather than via the extract pipeline. Same precedent
     * as {@code tax_brackets} ({@link com.landmarksoftware.payroll.service.TaxBracketService})
     * and {@code pa_audit} ({@link com.landmarksoftware.payroll.service.BatchAuditService}).
     */
    @PostConstruct
    void ensureMepassTable() {
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS mepass (" +
            "    terminal_no            INT          NOT NULL," +
            "    current_menu_name      VARCHAR(10)  NOT NULL DEFAULT ''," +
            "    previous_menu_name     VARCHAR(10)  NOT NULL DEFAULT ''," +
            "    main_menu_name         VARCHAR(10)  NOT NULL DEFAULT ''," +
            "    company_no             INT          NOT NULL DEFAULT 0," +
            "    company_name           VARCHAR(35)  NOT NULL DEFAULT ''," +
            "    terminal_inactive      VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    user_id                VARCHAR(15)  NOT NULL DEFAULT ''," +
            "    password               VARCHAR(10)  NOT NULL DEFAULT ''," +
            "    auth_no                INT          NOT NULL DEFAULT 0," +
            "    skip_menu_flag         VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    call_prog_flag         VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    f4_key_flag            VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    log_flag               VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    company_select_flag    VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    log_off_coy_no_flag    VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    auto_log_off_flag      VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    auto_log_off_time      INT          NOT NULL DEFAULT 0," +
            "    print_only_log_on_coy  VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    delete_pass_files_flag VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    print_pa_from_pass     VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    curr_program_name      VARCHAR(10)  NOT NULL DEFAULT ''," +
            "    curr_program_switch    VARCHAR(1)   NOT NULL DEFAULT ' '," +
            "    curr_program_date      DATE         NULL," +
            "    curr_program_time_hr   INT          NOT NULL DEFAULT 0," +
            "    curr_program_time_min  INT          NOT NULL DEFAULT 0," +
            "    reserve_sessions_flag  VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    pid_no                 BIGINT       NOT NULL DEFAULT 0," +
            "    remote_session_flag    VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    remove_session_flag    VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    send_a_message         VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    menu_log_date          DATE         NULL," +
            "    menu_log_hr            INT          NOT NULL DEFAULT 0," +
            "    menu_log_min           INT          NOT NULL DEFAULT 0," +
            "    log_date               DATE         NULL," +
            "    log_hr                 INT          NOT NULL DEFAULT 0," +
            "    log_min                INT          NOT NULL DEFAULT 0," +
            "    log_hun                INT          NOT NULL DEFAULT 0," +
            "    log_thou               INT          NOT NULL DEFAULT 0," +
            "    activate_flag          VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    site_no                INT          NOT NULL DEFAULT 0," +
            "    start_date             DATE         NULL," +
            "    no_of_days             INT          NOT NULL DEFAULT 0," +
            "    expired_flag           VARCHAR(1)   NOT NULL DEFAULT 'N'," +
            "    machine_type           VARCHAR(1)   NOT NULL DEFAULT ' '," +
            "    day_month_format       VARCHAR(1)   NOT NULL DEFAULT 'D'," +
            "    rev_no                 INT          NOT NULL DEFAULT 0," +
            "    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "    updated_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "    PRIMARY KEY (terminal_no)," +
            "    INDEX idx_mepass_company (company_no)," +
            "    INDEX idx_mepass_log_flag (log_flag)," +
            "    INDEX idx_mepass_user_id (user_id)" +
            ")");
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
