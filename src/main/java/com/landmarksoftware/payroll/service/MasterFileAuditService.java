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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Wave 1.5 audit framework — one entry point per audit table.
 *
 * <p>Two flavours of audit table:
 *
 * <ul>
 *   <li><b>Heavyweight</b> ({@code paemaud}, {@code pafuaud}) — one row
 *       per change with full JSON before/after snapshots. COBOL uses
 *       1500-byte fixed before/after blobs; we use {@code LONGTEXT JSON}
 *       so the trail is queryable via {@code JSON_EXTRACT}.</li>
 *   <li><b>Lightweight</b> ({@code pacdchg}, {@code paawchg}) — single
 *       current-state row per entity (UPSERTed) carrying a
 *       {@code last_change_date} and, for pay codes, the OLD rate/amount.
 *       Read by downstream recalc jobs to know what's dirty.</li>
 * </ul>
 *
 * <p>Methods do not declare {@link org.springframework.transaction.annotation.Transactional}
 * on themselves — they're meant to be called from within a parent
 * {@code @Transactional} service method so the audit write rolls back
 * alongside the data write if either fails.
 */
@Service
public class MasterFileAuditService {

    public static final String MAINT_ADD    = "A";
    public static final String MAINT_MODIFY = "M";
    public static final String MAINT_DELETE = "D";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MasterFileAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.json = new ObjectMapper()
            .findAndRegisterModules()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ── Heavyweight: full JSON before/after ──────────────────────────────

    /**
     * Write a paemaud row capturing one change to a {@code pastaff}
     * employee record. {@code before} is null on Add; {@code after} is
     * null on Delete.
     */
    public void auditEmployee(int companyNo, int employeeNo, String maintType,
                              Object before, Object after, String userId) {
        Stamp s = stamp(userId);
        jdbc.update(
            "INSERT INTO paemaud (company_no, employee_no, date_changed, " +
            "time_changed, maint_type, before_data, after_data, " +
            "audit_user_id, audit_date, audit_time_hr, audit_time_min, " +
            "audit_time_sec, audit_time_hun) " +
            "VALUES (?,?,?,?, ?,?,?, ?,?,?,?,?,?)",
            companyNo, employeeNo, s.date, s.time6,
            safeMaint(maintType), toJson(before), toJson(after),
            s.user, s.date, s.hr, s.mi, s.sec, s.hun);
    }

    /**
     * Write a pafuaud row capturing one change to a {@code pafunds}
     * super-fund record. Fund key is composite — APRA/SMSF indicator
     * plus the USI/ESA fund_id and (for SMSFs) the SMSF ABN.
     */
    public void auditFund(int companyNo, String apraSmsfFundInd, String fundId,
                          String smsfAbn, String maintType,
                          Object before, Object after, String userId) {
        Stamp s = stamp(userId);
        jdbc.update(
            "INSERT INTO pafuaud (company_no, apra_smsf_fund_ind, fund_id, " +
            "smsf_abn, date_changed, time_changed, maint_type, before_data, " +
            "after_data, audit_user_id, audit_date, audit_time_hr, " +
            "audit_time_min, audit_time_sec, audit_time_hun) " +
            "VALUES (?,?,?,?, ?,?, ?,?,?, ?,?,?,?,?,?)",
            companyNo,
            nz(apraSmsfFundInd), nz(fundId), nz(smsfAbn),
            s.date, s.time6,
            safeMaint(maintType), toJson(before), toJson(after),
            s.user, s.date, s.hr, s.mi, s.sec, s.hun);
    }

    // ── Lightweight: UPSERT one row per entity ───────────────────────────

    /**
     * UPSERT pacdchg with the OLD rate/amount and today's date. Call
     * before applying the new values in {@code pacodes}. Downstream
     * payrun recalculation jobs poll this table to see which pay codes
     * have moved since the last run.
     */
    public void auditPayCodeRateChange(int companyNo, String payCode,
                                       BigDecimal rateBefore,
                                       BigDecimal amtBefore) {
        java.sql.Date today = java.sql.Date.valueOf(LocalDate.now());
        jdbc.update(
            "INSERT INTO pacdchg (company_no, pay_code, last_change_date, " +
            "rate_before, amt_before) VALUES (?,?,?,?,?) " +
            "ON DUPLICATE KEY UPDATE last_change_date=VALUES(last_change_date), " +
            "rate_before=VALUES(rate_before), amt_before=VALUES(amt_before)",
            companyNo, nz(payCode), today,
            rateBefore == null ? BigDecimal.ZERO : rateBefore,
            amtBefore  == null ? BigDecimal.ZERO : amtBefore);
    }

    /**
     * Snapshot of a paecode row at the moment of change. Used to populate
     * papcaud. COBOL convention: write BEFORE-change values for {@code M}/{@code D}
     * and AFTER-change values for {@code A}.
     */
    public record PaeCodeSnapshot(
        int companyNo, int employeeNo, int payCodeType, String payCode,
        int min, BigDecimal qty, BigDecimal ratePerc, BigDecimal extAmt,
        String paygroup, String dept, String award, String jobClass) {}

    /**
     * Write one papcaud row capturing a paecode (per-employee pay-code)
     * change. Per-row tracker — one papcaud row per modification. Read by
     * downstream payrun recalc / posting routines.
     */
    public void auditPaeCode(PaeCodeSnapshot s, String maintType, String userId) {
        Stamp st = stamp(userId);
        jdbc.update(
            "INSERT INTO papcaud (company_no, employee_no, pay_code_type, " +
            "pay_code, date_changed, time_changed, maint_type, " +
            "min, qty, rate_perc, ext_amt, paygroup, dept, award, job_class, " +
            "audit_user_id, audit_date, audit_time_hr, audit_time_min, " +
            "audit_time_sec, audit_time_hun) " +
            "VALUES (?,?,?,?, ?,?, ?, ?,?,?,?, ?,?,?,?, ?,?,?,?,?,?)",
            s.companyNo, s.employeeNo, s.payCodeType, nz(s.payCode),
            st.date, st.time6,
            safeMaint(maintType),
            s.min, s.qty == null ? BigDecimal.ZERO : s.qty,
            s.ratePerc == null ? BigDecimal.ZERO : s.ratePerc,
            s.extAmt == null ? BigDecimal.ZERO : s.extAmt,
            nz(s.paygroup), nz(s.dept), nz(s.award), nz(s.jobClass),
            st.user, st.date, st.hr, st.mi, st.sec, st.hun);
    }

    /**
     * UPSERT paawchg with today's date. Call after any change to
     * paawhed / paawjob. For award-header changes pass {@code jobClass=""}.
     */
    public void auditAwardChange(int companyNo, String award, String jobClass) {
        java.sql.Date today = java.sql.Date.valueOf(LocalDate.now());
        jdbc.update(
            "INSERT INTO paawchg (company_no, award, job_class, last_change_date) " +
            "VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE last_change_date=VALUES(last_change_date)",
            companyNo, nz(award), nz(jobClass), today);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return maskSensitive(json.writeValueAsString(o));
        } catch (Exception e) {
            return "{\"_serializeError\":\"" + e.getClass().getSimpleName() +
                   ": " + (e.getMessage() == null ? "" : e.getMessage().replace("\"", "'")) + "\"}";
        }
    }

    /**
     * Replace TFN values in the JSON with the masked form. CLAUDE.md rule:
     * TFN is never printed or logged in full — the audit trail counts as
     * a log. Keep the last three digits so changes are still trackable.
     */
    private static String maskSensitive(String j) {
        return j.replaceAll(
            "\"taxFileNo\"\\s*:\\s*\"\\d*(\\d{3})\"",
            "\"taxFileNo\":\"***-***-$1\"");
    }

    private record Stamp(String user, java.sql.Date date, int time6,
                          int hr, int mi, int sec, int hun) {}

    private static Stamp stamp(String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now   = LocalTime.now();
        int hr  = now.getHour();
        int mi  = now.getMinute();
        int sec = now.getSecond();
        int hun = now.getNano() / 10_000_000;
        int time6 = hr * 10_000 + mi * 100 + sec;
        String u = userId == null ? "" : userId;
        if (u.length() > 15) u = u.substring(0, 15);
        return new Stamp(u, java.sql.Date.valueOf(today), time6, hr, mi, sec, hun);
    }

    private static String nz(String s)   { return s == null ? "" : s; }

    private static String safeMaint(String t) {
        if (t == null || t.isEmpty()) return MAINT_MODIFY;
        return t.substring(0, 1).toUpperCase();
    }
}
