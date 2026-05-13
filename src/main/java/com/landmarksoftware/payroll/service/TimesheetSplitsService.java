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
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * PAPC01 — Timesheet Splits.
 *
 * <p>Maintenance for the pay-phase split tables introduced in Wave 2:
 * <ul>
 *   <li>{@code pasphde} — header row per employee with the total %.</li>
 *   <li>{@code paspgre} — per-employee detail rows: paygroup + dept + %.</li>
 *   <li>{@code pasphdg} — header row per general (paygroup/dept) with total %.</li>
 *   <li>{@code paspgrg} — per-general detail rows: target paygroup/dept + %.</li>
 * </ul>
 *
 * <p>Wave 2 thin port: list + delete on the per-employee variant
 * (pasphde / paspgre). Add/Edit dialogs and the by-general variant
 * (pasphdg / paspgrg) are deferred to the next iteration — the schemas
 * are in place via the extract pipeline.
 */
@Service
public class TimesheetSplitsService {

    /** One pasphde header row joined with its child paspgre details. */
    public record EmployeeHeaderRow(
        int        employeeNo,
        String     employeeName,
        BigDecimal totalPerc,
        int        detailCount) {}

    /** One paspgre detail row. */
    public record EmployeeDetailRow(
        int        employeeNo,
        String     paygroup,
        String     dept,
        BigDecimal perc) {}

    private final JdbcTemplate          jdbc;
    private final BatchAuditService     batchAudit;

    public TimesheetSplitsService(JdbcTemplate jdbc, BatchAuditService batchAudit) {
        this.jdbc       = jdbc;
        this.batchAudit = batchAudit;
    }

    public List<EmployeeHeaderRow> findAllHeaders(int companyNo) {
        return jdbc.query(
            "SELECT h.employee_no, h.total_perc, " +
            "       COALESCE(s.surname,'') AS surname, COALESCE(s.first_name,'') AS first_name, " +
            "       COALESCE(c.cnt, 0) AS cnt " +
            "FROM pasphde h " +
            "LEFT JOIN pastaff s ON s.company_no = h.company_no AND s.employee_no = h.employee_no " +
            "LEFT JOIN (SELECT employee_no, COUNT(*) AS cnt FROM paspgre " +
            "           WHERE company_no=? GROUP BY employee_no) c " +
            "       ON c.employee_no = h.employee_no " +
            "WHERE h.company_no=? " +
            "ORDER BY h.employee_no",
            (rs, i) -> new EmployeeHeaderRow(
                rs.getInt("employee_no"),
                joinName(rs.getString("surname"), rs.getString("first_name")),
                rs.getBigDecimal("total_perc"),
                rs.getInt("cnt")),
            companyNo, companyNo);
    }

    public List<EmployeeDetailRow> findDetailsForEmployee(int companyNo, int employeeNo) {
        return jdbc.query(
            "SELECT employee_no, paygroup, dept, perc " +
            "FROM paspgre WHERE company_no=? AND employee_no=? " +
            "ORDER BY paygroup, dept",
            (rs, i) -> new EmployeeDetailRow(
                rs.getInt("employee_no"),
                rs.getString("paygroup"),
                rs.getString("dept"),
                rs.getBigDecimal("perc")),
            companyNo, employeeNo);
    }

    /**
     * Insert (or replace) an employee's full pay-split: header + details.
     * Details must sum to {@code totalPerc} — caller validates.
     */
    @Transactional
    public void saveEmployeeSplit(int companyNo, int employeeNo,
                                   BigDecimal totalPerc,
                                   List<EmployeeDetailRow> details,
                                   String userId) {
        long auditId = batchAudit.start(companyNo, userId, "PAPC01",
            "Save Timesheet Split for employee " + employeeNo +
            " (" + details.size() + " detail rows, total " + totalPerc + "%)",
            BatchAuditService.STATUS_RUNNING);
        try {
            // Replace semantics — purge then insert
            jdbc.update("DELETE FROM paspgre WHERE company_no=? AND employee_no=?",
                companyNo, employeeNo);
            jdbc.update("DELETE FROM pasphde WHERE company_no=? AND employee_no=?",
                companyNo, employeeNo);

            Stamp s = stamp(userId);
            jdbc.update(
                "INSERT INTO pasphde (company_no, employee_no, total_perc, note_no, " +
                "audit_user_id, audit_date, audit_time_hr, audit_time_min, " +
                "audit_time_sec, audit_time_hun) " +
                "VALUES (?,?,?, 0, ?,?,?,?,?,?)",
                companyNo, employeeNo, totalPerc,
                s.user, s.date, s.hr, s.mi, s.sec, s.hun);

            for (EmployeeDetailRow d : details) {
                jdbc.update(
                    "INSERT INTO paspgre (company_no, employee_no, paygroup, dept, " +
                    "perc, note_no, audit_user_id, audit_date, audit_time_hr, " +
                    "audit_time_min, audit_time_sec, audit_time_hun) " +
                    "VALUES (?,?,?,?, ?, 0, ?,?,?,?,?,?)",
                    companyNo, employeeNo, nz(d.paygroup()), nz(d.dept()),
                    d.perc(),
                    s.user, s.date, s.hr, s.mi, s.sec, s.hun);
            }
            batchAudit.complete(auditId, 1 + details.size());
        } catch (RuntimeException ex) {
            batchAudit.fail(auditId, ex.toString());
            throw ex;
        }
    }

    @Transactional
    public int deleteEmployeeSplit(int companyNo, int employeeNo, String userId) {
        long auditId = batchAudit.start(companyNo, userId, "PAPC01",
            "Delete Timesheet Split for employee " + employeeNo,
            BatchAuditService.STATUS_RUNNING);
        try {
            int d = jdbc.update(
                "DELETE FROM paspgre WHERE company_no=? AND employee_no=?",
                companyNo, employeeNo);
            int h = jdbc.update(
                "DELETE FROM pasphde WHERE company_no=? AND employee_no=?",
                companyNo, employeeNo);
            batchAudit.complete(auditId, d + h);
            return d + h;
        } catch (RuntimeException ex) {
            batchAudit.fail(auditId, ex.toString());
            throw ex;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private record Stamp(String user, Date date, int hr, int mi, int sec, int hun) {}

    private static Stamp stamp(String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now   = LocalTime.now();
        String u = userId == null ? "" : (userId.length() > 15 ? userId.substring(0, 15) : userId);
        return new Stamp(u, Date.valueOf(today),
            now.getHour(), now.getMinute(),
            now.getSecond(), now.getNano() / 10_000_000);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String joinName(String surname, String firstName) {
        String s = nz(firstName).trim();
        String f = nz(surname).trim();
        if (s.isEmpty() && f.isEmpty()) return "(unnamed)";
        if (s.isEmpty()) return f;
        if (f.isEmpty()) return s;
        return s + " " + f;
    }
}
