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
 * PAEM11 — Duplicate Default Timesheets.
 *
 * <p>Copy the paecode "default timesheet" rows from a source employee to
 * every employee in the user-selected target range. Mirrors PAEM11.cbl
 * which stashes the source rows in {@code paemwk1} as a work file then
 * chains to PAEM12 to push them out — we collapse both phases into one
 * Java transaction.
 *
 * <p>Replace semantics: target employees have their existing paecode rows
 * deleted before the new template is inserted (typical batch-utility
 * semantics — caller should be sure this is what they want).
 *
 * <p>Audit:
 * <ul>
 *   <li>One pa_audit batch row.</li>
 *   <li>One papcaud per deleted target row (maint_type='D').</li>
 *   <li>One papcaud per inserted target row (maint_type='A').</li>
 *   <li>paemwk1 is populated with the source-employee snapshot so a manual
 *       audit query (or future PAEM12 rerun) can see what the template
 *       was at apply time.</li>
 * </ul>
 */
@Service
public class DuplicateTimesheetsService {

    public record Inputs(
        int    sourceEmployeeNo,
        int    startEmployee, int endEmployee,
        String startPaygroup, String endPaygroup,
        String startDept,     String endDept,
        String startAward,    String endAward,
        String startJobClass, String endJobClass) {}

    public record AffectedRow(
        int    employeeNo,
        String employeeName,
        String paygroup,
        String dept,
        String award,
        String jobClass,
        int    existingLines,
        int    newLines) {}

    private final JdbcTemplate          jdbc;
    private final MasterFileAuditService rowAudit;
    private final BatchAuditService     batchAudit;

    public DuplicateTimesheetsService(JdbcTemplate jdbc,
                                       MasterFileAuditService rowAudit,
                                       BatchAuditService batchAudit) {
        this.jdbc       = jdbc;
        this.rowAudit   = rowAudit;
        this.batchAudit = batchAudit;
    }

    public List<AffectedRow> preview(int companyNo, Inputs in) {
        // Count of paecode rows on the source employee — drives the
        // "lines that will be added" column.
        int sourceLines = countLines(companyNo, in.sourceEmployeeNo());
        if (sourceLines == 0) return List.of();

        return jdbc.query(
            "SELECT s.employee_no, s.surname, s.first_name, " +
            "       s.paygroup, s.dept, s.award, s.job_class, " +
            "       COALESCE(c.cnt, 0) AS existing_lines " +
            "FROM pastaff s " +
            "LEFT JOIN (SELECT employee_no, COUNT(*) AS cnt FROM paecode " +
            "           WHERE company_no=? GROUP BY employee_no) c " +
            "       ON c.employee_no = s.employee_no " +
            "WHERE s.company_no = ? " +
            "  AND s.employee_status <> 'T' " +
            "  AND s.employee_no <> ? " +
            "  AND s.employee_no BETWEEN ? AND ? " +
            "  AND s.paygroup    BETWEEN ? AND ? " +
            "  AND s.dept        BETWEEN ? AND ? " +
            "  AND s.award       BETWEEN ? AND ? " +
            "  AND s.job_class   BETWEEN ? AND ? " +
            "ORDER BY s.employee_no",
            (rs, i) -> new AffectedRow(
                rs.getInt("employee_no"),
                joinName(rs.getString("surname"), rs.getString("first_name")),
                rs.getString("paygroup"),
                rs.getString("dept"),
                rs.getString("award"),
                rs.getString("job_class"),
                rs.getInt("existing_lines"),
                sourceLines),
            companyNo, companyNo,
            in.sourceEmployeeNo(),
            in.startEmployee() == 0 ? 0 : in.startEmployee(),
            in.endEmployee()   == 0 ? 999999 : in.endEmployee(),
            blankLo(in.startPaygroup()), blankHi(in.endPaygroup(), "zzzz"),
            blankLo(in.startDept()),     blankHi(in.endDept(),     "zzzz"),
            blankLo(in.startAward()),    blankHi(in.endAward(),    "zzz"),
            blankLo(in.startJobClass()), blankHi(in.endJobClass(), "zzzzzz"));
    }

    @Transactional
    public int apply(int companyNo, Inputs in, String userId, IntConsumer progress) {
        long auditId = batchAudit.start(companyNo, userId, "PAEM11",
            "Duplicate Default Timesheets from employee " + in.sourceEmployeeNo() +
            " to " + in.startEmployee() + ".." + in.endEmployee(),
            BatchAuditService.STATUS_RUNNING);
        try {
            // 1. Snapshot the source rows once
            List<PaeRow> template = loadPaecode(companyNo, in.sourceEmployeeNo());
            if (template.isEmpty()) {
                batchAudit.complete(auditId, 0);
                batchAudit.appendNote(auditId,
                    "; source employee " + in.sourceEmployeeNo() + " has no paecode rows.");
                return 0;
            }

            // 2. Stash the snapshot in paemwk1 (work file) so a future
            //    audit/forensic query can see the template at apply time.
            stashWorkFile(companyNo, in, template);

            // 3. Apply to every target employee
            List<AffectedRow> targets = preview(companyNo, in);
            int n = 0;
            for (AffectedRow t : targets) {
                replaceTargetPaecode(companyNo, t.employeeNo(), template, userId);
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

    // ── Internals ────────────────────────────────────────────────────────

    /** Snapshot row used both to seed paemwk1 and to write target paecode. */
    private record PaeRow(
        int lineNo, int payType, String payCode, int min, BigDecimal qty,
        BigDecimal ratePerc, BigDecimal extAmt,
        String paygroup, String dept, String award, String jobClass) {}

    private List<PaeRow> loadPaecode(int companyNo, int employeeNo) {
        return jdbc.query(
            "SELECT line_no, pay_type, pay_code, min, qty, rate_perc, ext_amt, " +
            "       paygroup, dept, award, job_class " +
            "FROM paecode WHERE company_no=? AND employee_no=? ORDER BY line_no",
            (rs, i) -> new PaeRow(
                rs.getInt("line_no"), rs.getInt("pay_type"),
                rs.getString("pay_code"), rs.getInt("min"),
                rs.getBigDecimal("qty"), rs.getBigDecimal("rate_perc"),
                rs.getBigDecimal("ext_amt"),
                rs.getString("paygroup"), rs.getString("dept"),
                rs.getString("award"), rs.getString("job_class")),
            companyNo, employeeNo);
    }

    private int countLines(int companyNo, int employeeNo) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM paecode WHERE company_no=? AND employee_no=?",
            Integer.class, companyNo, employeeNo);
        return n == null ? 0 : n;
    }

    /** Clear and repopulate paemwk1 for this company with the template snapshot. */
    private void stashWorkFile(int companyNo, Inputs in, List<PaeRow> template) {
        jdbc.update("DELETE FROM paemwk1 WHERE company_no=?", companyNo);
        // Line 1: header (selection criteria); empty detail_line
        jdbc.update(
            "INSERT INTO paemwk1 (company_no, line_no, start_employee, end_employee, " +
            "start_paygroup, end_paygroup, start_dept, end_dept, start_award, end_award, " +
            "start_job_class, end_job_class, detail_line) " +
            "VALUES (?,?,?,?, ?,?, ?,?, ?,?, ?,?, '')",
            companyNo, 1,
            in.startEmployee(), in.endEmployee() == 0 ? 999999 : in.endEmployee(),
            blankLo(in.startPaygroup()), blankHi(in.endPaygroup(), "zzzz"),
            blankLo(in.startDept()),     blankHi(in.endDept(),     "zzzz"),
            blankLo(in.startAward()),    blankHi(in.endAward(),    "zzz"),
            blankLo(in.startJobClass()), blankHi(in.endJobClass(), "zzzzzz"));

        int line = 2;
        for (PaeRow r : template) {
            String detail = r.lineNo + "|" + r.payType + "|" + nz(r.payCode) + "|" +
                r.min + "|" + str(r.qty) + "|" + str(r.ratePerc) + "|" + str(r.extAmt) + "|" +
                nz(r.paygroup) + "|" + nz(r.dept) + "|" + nz(r.award) + "|" + nz(r.jobClass);
            if (detail.length() > 500) detail = detail.substring(0, 500);
            jdbc.update(
                "INSERT INTO paemwk1 (company_no, line_no, start_employee, end_employee, " +
                "start_paygroup, end_paygroup, start_dept, end_dept, start_award, end_award, " +
                "start_job_class, end_job_class, detail_line) " +
                "VALUES (?,?, 0,0, '','', '','', '','', '','', ?)",
                companyNo, line++, detail);
        }
    }

    private void replaceTargetPaecode(int companyNo, int targetEmpNo,
                                       List<PaeRow> template, String userId) {
        // Audit-log each existing row as 'D' before deletion
        List<PaeRow> existing = loadPaecode(companyNo, targetEmpNo);
        for (PaeRow r : existing) {
            rowAudit.auditPaeCode(
                new MasterFileAuditService.PaeCodeSnapshot(
                    companyNo, targetEmpNo, r.payType, nz(r.payCode),
                    r.min, r.qty, r.ratePerc, r.extAmt,
                    nz(r.paygroup), nz(r.dept), nz(r.award), nz(r.jobClass)),
                MasterFileAuditService.MAINT_DELETE, userId);
        }
        jdbc.update("DELETE FROM paecode WHERE company_no=? AND employee_no=?",
            companyNo, targetEmpNo);

        // Insert the template into the target — line_no follows the source.
        // paecode has many required columns; we copy only what the template
        // captures and default the rest with a wider SELECT-from-source pattern.
        for (PaeRow r : template) {
            jdbc.update(
                "INSERT INTO paecode (" +
                " company_no, alt_2_pay_code, employee_no, line_no, " +
                " alt_1_employee_no, alt_1_pay_code, alt_1_line_no, " +
                " pay_freq, pays_since_last_paid, std_pay_code_flag, " +
                " start_date, end_date, last_paid_date, super_member_no, " +
                " pay_type, pay_code, min, qty, rate_perc, ext_amt, " +
                " paygroup, dept, award, job_class, " +
                " cost_type, gl_acct_no_main, gl_acct_no_sub, " +
                " ledger_type, ledger_code, analysis_code, " +
                " absorp_type, absorp_factor, absorp_amt, ref, " +
                " leave_start_date, leave_return_date, fbt_gross_value, " +
                " ba_ledger_id, ba_primary_codes, ba_desc, ba_bill_text, " +
                " gst_value, ba_gl_override_flag, ba_edit_bill_data_flag, note_no, " +
                " audit_user_id, audit_date, audit_time_hr, audit_time_min, " +
                " audit_time_sec, audit_time_hun) " +
                "VALUES (?,?,?,?, ?,?,?, 0,0,'', " +
                " '1899-12-31','1899-12-31','1899-12-31','', " +
                " ?,?,?,?,?,?, " +
                " ?,?,?,?, " +
                " '',0,0, '','','', " +
                " '',0,0,'', '1899-12-31','1899-12-31',0, " +
                " '','','','', 0,'N','N',0, " +
                " ?,CURRENT_DATE(),HOUR(NOW()),MINUTE(NOW()),SECOND(NOW()),0)",
                companyNo, nz(r.payCode), targetEmpNo, r.lineNo,
                targetEmpNo, nz(r.payCode), r.lineNo,
                r.payType, nz(r.payCode), r.min, r.qty, r.ratePerc, r.extAmt,
                nz(r.paygroup), nz(r.dept), nz(r.award), nz(r.jobClass),
                safeUser(userId));

            rowAudit.auditPaeCode(
                new MasterFileAuditService.PaeCodeSnapshot(
                    companyNo, targetEmpNo, r.payType, nz(r.payCode),
                    r.min, r.qty, r.ratePerc, r.extAmt,
                    nz(r.paygroup), nz(r.dept), nz(r.award), nz(r.jobClass)),
                MasterFileAuditService.MAINT_ADD, userId);
        }
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
    private static String str(BigDecimal v) { return v == null ? "0" : v.toPlainString(); }
    private static String safeUser(String s) {
        if (s == null) return "";
        return s.length() > 15 ? s.substring(0, 15) : s;
    }
    private static String blankLo(String s) { return (s == null || s.trim().isEmpty()) ? "" : s.trim().toUpperCase(); }
    private static String blankHi(String s, String hi) {
        return (s == null || s.trim().isEmpty()) ? hi : s.trim().toUpperCase();
    }
}
