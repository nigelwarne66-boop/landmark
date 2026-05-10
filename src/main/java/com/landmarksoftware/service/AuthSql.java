package com.landmarksoftware.service;

/**
 * Centralised SQL constants for authentication and user-account operations.
 *
 * Used by UserService. (PasswordService is pure crypto — no JDBC, so it
 * does not need any constants here. If a future PasswordService change
 * adds DB writes, those should land in this file.)
 *
 * Tables: meusers, MEPASS.
 */
public final class AuthSql {

    private AuthSql() {}

    // ─── meusers (User Master) ──────────────────────────────────────────

    /** Load a user row by PK. Params: userId. */
    public static final String FIND_USER_BY_PK =
        "SELECT user_id, name1, password, user_status, supervisor_flag, " +
        "passwd_expiry_date FROM meusers WHERE user_id = ?";

    /**
     * Look up user IDs by email (Forgot User ID flow).
     * Excludes terminated accounts. Params: email.
     */
    public static final String FIND_USER_IDS_BY_EMAIL =
        "SELECT user_id FROM meusers " +
        "WHERE LOWER(TRIM(email_address)) = LOWER(TRIM(?)) " +
        "AND (user_status IS NULL OR user_status <> 'T')";

    /** Lock a user (too many failed logins). Params: userId. */
    public static final String LOCK_USER =
        "UPDATE meusers SET user_status='L' WHERE user_id=?";

    /** Stamp last-access date on successful login. Params: today, userId. */
    public static final String UPDATE_USER_LAST_ACCESS =
        "UPDATE meusers SET last_access_date=?, log_flag='Y' WHERE user_id=?";

    /**
     * Write a temporary password and force expiry today.
     * Params: tempPassword, today, userId.
     */
    public static final String UPDATE_USER_TEMP_PASSWORD =
        "UPDATE meusers SET password=?, user_status='', passwd_expiry_date=? " +
        "WHERE user_id=?";

    /** Persist a BCrypt hash (plain-text → BCrypt migration). Params: bcryptHash, userId. */
    public static final String UPDATE_USER_PASSWORD_HASH =
        "UPDATE meusers SET password=? WHERE user_id=?";

    // ─── MEPASS (Terminal Sessions) ─────────────────────────────────────

    /** Allocate the next free terminal slot (1..998). No params. */
    public static final String FIND_NEXT_MEPASS_TERMINAL_NO =
        "SELECT COALESCE(MAX(terminal_no),0)+1 FROM MEPASS WHERE terminal_no<999";

    /**
     * Upsert a MEPASS session row.
     * Params: terminalNo, userId, companyNo, companyName, today, hour, minute.
     * On duplicate PK, refreshes user/company/log timestamps.
     */
    public static final String UPSERT_MEPASS_SESSION =
        "INSERT INTO MEPASS (" +
        "terminal_no, user_id, company_no, company_name," +
        "terminal_inactive, log_flag, remote_session_flag, day_month_format," +
        "log_date, log_hr, log_min, created_at" +
        ") VALUES (?,?,?,?,'N','Y','N','D',?,?,?,NOW()) " +
        "ON DUPLICATE KEY UPDATE " +
        "  user_id=VALUES(user_id)," +
        "  company_no=VALUES(company_no)," +
        "  company_name=VALUES(company_name)," +
        "  terminal_inactive='N'," +
        "  log_flag='Y'," +
        "  log_date=VALUES(log_date)," +
        "  log_hr=VALUES(log_hr)," +
        "  log_min=VALUES(log_min)," +
        "  updated_at=NOW()";
}
