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

import com.landmarksoftware.payroll.model.Employee;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * PASU55 — Leave Accrual Reversal.
 *
 * <p>Wave 2 thin port: read paleave rows for one employee plus a
 * "bulk-reverse" that deletes every paleave row for that employee.
 * Bulk-reverse writes:
 * <ul>
 *   <li>One {@code pa_audit} batch row (program PASU55).</li>
 *   <li>One {@code pasuwk3} marker per affected employee — matches
 *       COBOL ADD-WORK-RECORD so downstream payrun recalc can find
 *       the dirty employees.</li>
 *   <li>One {@code paemaud} row per employee (full before/after).</li>
 * </ul>
 *
 * <p>Per-row Add / Edit / Delete dialogs from the COBOL screen are
 * deferred — the next iteration should add them on top of this service.
 */
@Service
public class LeaveAccrualReversalService {

    public record LeaveRow(
        String     payCode,
        int        payType,
        String     accruedTakenInd,
        String     leaveStartDate,
        String     leaveEndDate,
        int        min,
        BigDecimal rate,
        BigDecimal amt,
        int        payrunNo) {}

    private final JdbcTemplate           jdbc;
    private final EmployeeService        employees;
    private final MasterFileAuditService rowAudit;
    private final BatchAuditService      batchAudit;

    public LeaveAccrualReversalService(JdbcTemplate jdbc,
                                        EmployeeService employees,
                                        MasterFileAuditService rowAudit,
                                        BatchAuditService batchAudit) {
        this.jdbc       = jdbc;
        this.employees  = employees;
        this.rowAudit   = rowAudit;
        this.batchAudit = batchAudit;
    }

    public List<LeaveRow> findByEmployee(int companyNo, int employeeNo) {
        return jdbc.query(
            "SELECT pay_code, pay_type, accrued_taken_ind, " +
            "       leave_start_date, leave_end_date, min, rate, amt, payrun_no " +
            "FROM paleave WHERE company_no=? AND employee_no=? " +
            "ORDER BY leave_start_date DESC, pay_code",
            (rs, i) -> new LeaveRow(
                rs.getString("pay_code"),
                rs.getInt("pay_type"),
                rs.getString("accrued_taken_ind"),
                String.valueOf(rs.getDate("leave_start_date")),
                String.valueOf(rs.getDate("leave_end_date")),
                rs.getInt("min"),
                rs.getBigDecimal("rate"),
                rs.getBigDecimal("amt"),
                rs.getInt("payrun_no")),
            companyNo, employeeNo);
    }

    /**
     * Delete every paleave row for an employee. Audited as a single
     * PASU55 batch with one paemaud snapshot of the employee and one
     * pasuwk3 marker.
     *
     * @return the number of paleave rows removed
     */
    @Transactional
    public int reverseAllForEmployee(int companyNo, int employeeNo, String userId) {
        long auditId = batchAudit.start(companyNo, userId, "PASU55",
            "Leave Accrual Reversal — employee " + employeeNo,
            BatchAuditService.STATUS_RUNNING);
        try {
            Employee before = employees.findOne(companyNo, employeeNo).orElse(null);
            int n = jdbc.update(
                "DELETE FROM paleave WHERE company_no=? AND employee_no=?",
                companyNo, employeeNo);
            // pasuwk3 marker — tracks employees whose accruals were touched
            jdbc.update(
                "INSERT INTO pasuwk3 (company_no, employee_no) VALUES (?,?) " +
                "ON DUPLICATE KEY UPDATE employee_no=VALUES(employee_no)",
                companyNo, employeeNo);
            if (before != null) {
                rowAudit.auditEmployee(companyNo, employeeNo,
                    MasterFileAuditService.MAINT_MODIFY, before, before, userId);
            }
            batchAudit.complete(auditId, n);
            return n;
        } catch (RuntimeException ex) {
            batchAudit.fail(auditId, ex.toString());
            throw ex;
        }
    }
}
