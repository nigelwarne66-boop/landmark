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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * PASU14 — Set Super Percentage.
 *
 * <p>Mass update of paecode (per-employee pay-code) rows: for every paecode
 * row whose {@code pay_code} is in the user-selected range, whose
 * {@code pay_type} is 17 (Superannuation) or 20 (Employer Superann'n), and
 * whose {@code rate_perc} equals {@code fromPerc}, change the rate to
 * {@code toPerc}.
 *
 * <p>Mirrors pasu14.pl SET-EMPLOYEE-DEFAULT-TIMESHEETS / SET-NEXT-TIMESHEET.
 *
 * <p>Preview vs Apply:
 * <ul>
 *   <li>{@link #preview} returns the rows that would change without touching
 *       the DB. Used by {@link com.landmarksoftware.payroll.ui.BatchPreviewDialog}.</li>
 *   <li>{@link #apply} performs the actual update in a single transaction and
 *       writes the per-row {@code papcaud} audit trail plus the batch-level
 *       {@code pa_audit} row.</li>
 * </ul>
 */
@Service
public class SetSuperPercentageService {

    /** PASU14 only operates on these two pay types — mirrors the COBOL guard. */
    public static final int TYPE_SUPERANNUATION       = 17;
    public static final int TYPE_EMPLOYER_SUPERANN    = 20;

    /** One row in the preview / progress stream. */
    public record AffectedRow(
        int        employeeNo,
        String     employeeName,   // surname, first_name — for display only
        String     payCode,
        int        payType,
        int        lineNo,
        BigDecimal currentRate,
        BigDecimal newRate) {}

    private final JdbcTemplate          jdbc;
    private final MasterFileAuditService rowAudit;
    private final BatchAuditService     batchAudit;

    public SetSuperPercentageService(JdbcTemplate jdbc,
                                     MasterFileAuditService rowAudit,
                                     BatchAuditService batchAudit) {
        this.jdbc       = jdbc;
        this.rowAudit   = rowAudit;
        this.batchAudit = batchAudit;
    }

    /**
     * Build the list of rows the apply step WOULD update. Read-only; no
     * DB writes. Sorted by employee_no then pay_code for stable preview.
     */
    public List<AffectedRow> preview(int companyNo,
                                     String startPayCode, String endPayCode,
                                     int payType,
                                     BigDecimal fromPerc, BigDecimal toPerc) {
        if (!isValidType(payType)) return List.of();
        return jdbc.query(
            "SELECT pe.employee_no, pe.pay_code, pe.pay_type, pe.line_no, pe.rate_perc, " +
            "       COALESCE(s.surname,'') AS surname, COALESCE(s.first_name,'') AS first_name " +
            "FROM paecode pe " +
            "LEFT JOIN pastaff s ON s.company_no = pe.company_no " +
            "                    AND s.employee_no = pe.employee_no " +
            "WHERE pe.company_no = ? " +
            "  AND pe.pay_code BETWEEN ? AND ? " +
            "  AND pe.pay_type = ? " +
            "  AND pe.rate_perc = ? " +
            "ORDER BY pe.employee_no, pe.pay_code, pe.line_no",
            (rs, i) -> new AffectedRow(
                rs.getInt("employee_no"),
                joinName(rs.getString("surname"), rs.getString("first_name")),
                rs.getString("pay_code"),
                rs.getInt("pay_type"),
                rs.getInt("line_no"),
                rs.getBigDecimal("rate_perc"),
                toPerc),
            companyNo,
            nz(startPayCode), nz(endPayCode).isEmpty() ? "zzzzzz" : nz(endPayCode),
            payType, fromPerc);
    }

    /**
     * Perform the update and write the row + batch audit trail. Returns the
     * number of paecode rows updated. {@code progress} is invoked after every
     * row so the {@link com.landmarksoftware.payroll.ui.BatchProgressDialog}
     * can advance.
     */
    @Transactional
    public int apply(int companyNo,
                     String startPayCode, String endPayCode,
                     int payType,
                     BigDecimal fromPerc, BigDecimal toPerc,
                     String userId,
                     IntConsumer progress) {
        if (!isValidType(payType)) {
            throw new IllegalArgumentException(
                "PASU14 only handles pay_type 17 or 20; got " + payType);
        }

        long auditId = batchAudit.start(companyNo, userId, "PASU14",
            "Set Super % from " + fromPerc + " to " + toPerc +
            " on pay codes " + nz(startPayCode) + ".." + nz(endPayCode) +
            " (type " + payType + ")",
            BatchAuditService.STATUS_RUNNING);

        List<AffectedRow> rows = preview(companyNo,
            startPayCode, endPayCode, payType, fromPerc, toPerc);

        try {
            int n = 0;
            for (AffectedRow r : rows) {
                // 1. snapshot BEFORE values for papcaud
                MasterFileAuditService.PaeCodeSnapshot before =
                    snapshot(companyNo, r.employeeNo, r.lineNo);
                if (before != null) {
                    rowAudit.auditPaeCode(before, MasterFileAuditService.MAINT_MODIFY, userId);
                }
                // 2. update paecode
                jdbc.update(
                    "UPDATE paecode SET rate_perc=?, " +
                    "audit_user_id=?, audit_date=CURRENT_DATE(), " +
                    "audit_time_hr=HOUR(NOW()), audit_time_min=MINUTE(NOW()), " +
                    "audit_time_sec=SECOND(NOW()), audit_time_hun=0 " +
                    "WHERE company_no=? AND employee_no=? AND line_no=?",
                    toPerc, safe(userId, 15),
                    companyNo, r.employeeNo, r.lineNo);

                n++;
                if (progress != null) progress.accept(n);
            }
            batchAudit.complete(auditId, n);
            return n;
        } catch (RuntimeException ex) {
            batchAudit.fail(auditId, ex.toString());
            throw ex;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    public static boolean isValidType(int payType) {
        return payType == TYPE_SUPERANNUATION || payType == TYPE_EMPLOYER_SUPERANN;
    }

    /** Read a single paecode row as a papcaud snapshot. */
    private MasterFileAuditService.PaeCodeSnapshot snapshot(int companyNo,
                                                             int employeeNo,
                                                             int lineNo) {
        List<MasterFileAuditService.PaeCodeSnapshot> hits = jdbc.query(
            "SELECT employee_no, pay_type, pay_code, min, qty, rate_perc, ext_amt, " +
            "       paygroup, dept, award, job_class " +
            "FROM paecode WHERE company_no=? AND employee_no=? AND line_no=?",
            (rs, i) -> new MasterFileAuditService.PaeCodeSnapshot(
                companyNo,
                rs.getInt("employee_no"),
                rs.getInt("pay_type"),
                nz(rs.getString("pay_code")),
                rs.getInt("min"),
                rs.getBigDecimal("qty"),
                rs.getBigDecimal("rate_perc"),
                rs.getBigDecimal("ext_amt"),
                nz(rs.getString("paygroup")),
                nz(rs.getString("dept")),
                nz(rs.getString("award")),
                nz(rs.getString("job_class"))),
            companyNo, employeeNo, lineNo);
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static String joinName(String surname, String firstName) {
        String s = nz(firstName).trim();
        String f = nz(surname).trim();
        if (s.isEmpty() && f.isEmpty()) return "(unnamed)";
        if (s.isEmpty()) return f;
        if (f.isEmpty()) return s;
        return s + " " + f;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String safe(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Defensive copy without the IntConsumer ctor — kept for clarity. */
    private List<AffectedRow> copy(List<AffectedRow> in) { return new ArrayList<>(in); }
}
