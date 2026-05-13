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
package com.landmarksoftware.payroll.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Wave 2 batch-audit framework — option B (batch metadata only).
 *
 * <p>Every Wave 2 batch program (PASU14, PASU11, PASU15, PAEM60, PAEM11,
 * PASU55, PAPC01) writes ONE {@code pa_audit} row per run capturing
 * who/when/what/how-many. Per-employee before/after detail lives in the
 * Wave 1.5 audit tables ({@code paemaud}, {@code papcaud}, etc.) so the
 * two together give: <em>which batch did this</em> (pa_audit) plus
 * <em>what changed for each row</em> (per-row tables).
 *
 * <p>Lifecycle: {@link #start(int, String, String, String, String)} returns
 * an {@code audit_id}; call {@link #complete(long, int)} on success or
 * {@link #fail(long, String)} if the batch aborts. The row is written in a
 * brand-new short transaction (REQUIRES_NEW would be ideal, but Spring's
 * default jdbc.update creates its own transaction here — keep it simple).
 *
 * <p>The {@code pa_audit} table is Java-managed and auto-created on
 * startup — same pattern as {@code tax_brackets} (see
 * {@link TaxBracketService}).
 */
@Service
public class BatchAuditService {

    /** Status: still running (between start and complete). */
    public static final String STATUS_RUNNING  = "R";
    /** Status: ran to completion. */
    public static final String STATUS_COMPLETE = "C";
    /** Status: aborted with an error. */
    public static final String STATUS_FAILED   = "F";
    /** Status: preview only, no writes performed. */
    public static final String STATUS_PREVIEW  = "P";

    private final JdbcTemplate jdbc;

    public BatchAuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    void ensureTable() {
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS pa_audit (" +
            "    audit_id      BIGINT       NOT NULL AUTO_INCREMENT," +
            "    company_no    INT          NOT NULL," +
            "    run_timestamp DATETIME     NOT NULL," +
            "    user_id       VARCHAR(15)  NOT NULL," +
            "    program_code  VARCHAR(10)  NOT NULL," +
            "    description   VARCHAR(255) NOT NULL," +
            "    rows_affected INT          NOT NULL DEFAULT 0," +
            "    status        VARCHAR(1)   NOT NULL," +
            "    notes         TEXT," +
            "    PRIMARY KEY (audit_id)," +
            "    INDEX pa_audit_company_idx (company_no, run_timestamp)," +
            "    INDEX pa_audit_program_idx (program_code, run_timestamp)" +
            ")");
    }

    /**
     * Open a batch run. Returns the generated {@code audit_id}. Pass it
     * to {@link #complete} / {@link #fail} when the batch finishes.
     *
     * @param companyNo   from {@code AppSession.getCompanyNo()}
     * @param userId      from {@code AppSession.getUserId()}
     * @param programCode short COBOL-style program code, e.g. "PASU14"
     * @param description human-readable summary line for the log
     * @param status      {@link #STATUS_RUNNING} for live runs,
     *                    {@link #STATUS_PREVIEW} for preview-only runs
     */
    public long start(int companyNo, String userId, String programCode,
                      String description, String status) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO pa_audit (company_no, run_timestamp, user_id, " +
                "program_code, description, rows_affected, status) " +
                "VALUES (?,?,?,?,?,0,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setInt   (1, companyNo);
            ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(3, safeUser(userId));
            ps.setString(4, safe(programCode, 10));
            ps.setString(5, safe(description, 255));
            ps.setString(6, status == null ? STATUS_RUNNING : status);
            return ps;
        }, kh);
        Number id = kh.getKey();
        return id == null ? 0L : id.longValue();
    }

    /** Mark a previously-started batch as completed with the final row count. */
    public void complete(long auditId, int rowsAffected) {
        if (auditId == 0L) return;
        jdbc.update(
            "UPDATE pa_audit SET rows_affected=?, status=? WHERE audit_id=?",
            rowsAffected, STATUS_COMPLETE, auditId);
    }

    /** Mark a previously-started batch as failed and capture the reason. */
    public void fail(long auditId, String reason) {
        if (auditId == 0L) return;
        jdbc.update(
            "UPDATE pa_audit SET status=?, notes=? WHERE audit_id=?",
            STATUS_FAILED, safe(reason, 60_000), auditId);
    }

    /** Update the rows_affected counter on a running batch. */
    public void updateRowsAffected(long auditId, int rowsAffected) {
        if (auditId == 0L) return;
        jdbc.update(
            "UPDATE pa_audit SET rows_affected=? WHERE audit_id=?",
            rowsAffected, auditId);
    }

    /** Append a free-text note to the batch row. */
    public void appendNote(long auditId, String note) {
        if (auditId == 0L || note == null || note.isEmpty()) return;
        jdbc.update(
            "UPDATE pa_audit SET notes = CONCAT(COALESCE(notes,''), ?) WHERE audit_id=?",
            note, auditId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String safe(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String safeUser(String s) { return safe(s, 15); }
}
