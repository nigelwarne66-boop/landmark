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

import com.landmarksoftware.payroll.model.Paleave;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * paleave service — leave ledger I/O. Mirrors the COBOL READ/WRITE/REWRITE/
 * DELETE PALEAVE operations used across PASU18 (opening), PAEM01 (manual),
 * PAPP28 (taken on post) and the PAPP* reversal paths.
 */
@Service
public class PaleaveService {

    private final JdbcTemplate jdbc;

    public PaleaveService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Paleave> findByEmployee(int companyNo, int employeeNo) {
        return jdbc.query(
            "SELECT * FROM paleave WHERE company_no=? AND employee_no=? " +
            "ORDER BY leave_start_date DESC, pay_type, pay_code",
            (rs, i) -> map(rs),
            companyNo, employeeNo);
    }

    public Optional<Paleave> findOne(int companyNo, int employeeNo,
                                      String payCode, int payType,
                                      LocalDate leaveStartDate,
                                      String accruedTakenInd) {
        try {
            Paleave p = jdbc.queryForObject(
                "SELECT * FROM paleave WHERE company_no=? AND employee_no=? " +
                "AND pay_code=? AND pay_type=? AND leave_start_date=? AND accrued_taken_ind=?",
                (rs, i) -> map(rs),
                companyNo, employeeNo,
                payCode == null ? "" : payCode, payType,
                java.sql.Date.valueOf(leaveStartDate),
                accruedTakenInd == null ? "A" : accruedTakenInd);
            return Optional.ofNullable(p);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Rows for a payrun (alt-1 timeline ordered). Used by PAPP28 un-post. */
    public List<Paleave> findByEmployeeAndPayrun(int companyNo, int employeeNo, int payrunNo) {
        return jdbc.query(
            "SELECT * FROM paleave WHERE company_no=? AND employee_no=? AND payrun_no=? " +
            "ORDER BY leave_start_date DESC, pay_type, pay_code",
            (rs, i) -> map(rs),
            companyNo, employeeNo, payrunNo);
    }

    @Transactional
    public void insert(Paleave p, String userId) {
        LocalTime now = LocalTime.now();
        // Alt-key always mirrors PK by default.
        if (p.alt1EmployeeNo == 0)       p.alt1EmployeeNo   = p.employeeNo;
        if (p.alt1StartDate == null)     p.alt1StartDate    = p.leaveStartDate;
        if (p.alt1PayType == 0)          p.alt1PayType      = p.payType;
        if (p.alt1PayCode.isBlank())     p.alt1PayCode      = p.payCode;
        if (p.alt1AccrTakenInd.isBlank())p.alt1AccrTakenInd = p.accruedTakenInd;

        jdbc.update(
            "INSERT INTO paleave (" +
            " company_no, employee_no, pay_code, pay_type, leave_start_date, accrued_taken_ind," +
            " alt_1_employee_no, alt_1_start_date, alt_1_pay_type, alt_1_pay_code, alt_1_accr_taken_ind," +
            " leave_end_date, min, rate, amt, day_of_week, ref," +
            " payrun_no, payrun_date, lsl_weeks, note_no," +
            " audit_user_id, audit_date, audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            p.companyNo, p.employeeNo, nz(p.payCode), p.payType,
            sqlDate(p.leaveStartDate), nz1(p.accruedTakenInd, "A"),
            p.alt1EmployeeNo, sqlDate(p.alt1StartDate), p.alt1PayType,
            nz(p.alt1PayCode), nz1(p.alt1AccrTakenInd, "A"),
            sqlDate(p.leaveEndDate), p.min,
            nz0(p.rate), nz0(p.amt), p.dayOfWeek, nz(p.ref),
            p.payrunNo, sqlDate(p.payrunDate), nz0(p.lslWeeks), p.noteNo,
            nz(userId), java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0);
    }

    @Transactional
    public void update(Paleave p, String userId) {
        LocalTime now = LocalTime.now();
        jdbc.update(
            "UPDATE paleave SET" +
            "  leave_end_date=?, min=?, rate=?, amt=?, day_of_week=?, ref=?," +
            "  payrun_no=?, payrun_date=?, lsl_weeks=?," +
            "  audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?," +
            "  audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND employee_no=? AND pay_code=? AND pay_type=? " +
            "AND leave_start_date=? AND accrued_taken_ind=?",
            sqlDate(p.leaveEndDate), p.min,
            nz0(p.rate), nz0(p.amt), p.dayOfWeek, nz(p.ref),
            p.payrunNo, sqlDate(p.payrunDate), nz0(p.lslWeeks),
            nz(userId), java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0,
            p.companyNo, p.employeeNo, nz(p.payCode), p.payType,
            sqlDate(p.leaveStartDate), nz1(p.accruedTakenInd, "A"));
    }

    @Transactional
    public int delete(int companyNo, int employeeNo, String payCode, int payType,
                       LocalDate leaveStartDate, String accruedTakenInd) {
        return jdbc.update(
            "DELETE FROM paleave WHERE company_no=? AND employee_no=? AND pay_code=? " +
            "AND pay_type=? AND leave_start_date=? AND accrued_taken_ind=?",
            companyNo, employeeNo, nz(payCode), payType,
            java.sql.Date.valueOf(leaveStartDate),
            accruedTakenInd == null ? "A" : accruedTakenInd);
    }

    @Transactional
    public int deleteByPayrun(int companyNo, int payrunNo, String accruedTakenInd) {
        return jdbc.update(
            "DELETE FROM paleave WHERE company_no=? AND payrun_no=? AND accrued_taken_ind=?",
            companyNo, payrunNo,
            accruedTakenInd == null ? "A" : accruedTakenInd);
    }

    private static Paleave map(ResultSet rs) throws SQLException {
        Paleave p = new Paleave();
        p.companyNo          = rs.getInt("company_no");
        p.employeeNo         = rs.getInt("employee_no");
        p.payCode            = nz(rs.getString("pay_code"));
        p.payType            = rs.getInt("pay_type");
        p.leaveStartDate     = ld(rs.getDate("leave_start_date"));
        p.accruedTakenInd    = nz(rs.getString("accrued_taken_ind"));
        p.alt1EmployeeNo     = rs.getInt("alt_1_employee_no");
        p.alt1StartDate      = ld(rs.getDate("alt_1_start_date"));
        p.alt1PayType        = rs.getInt("alt_1_pay_type");
        p.alt1PayCode        = nz(rs.getString("alt_1_pay_code"));
        p.alt1AccrTakenInd   = nz(rs.getString("alt_1_accr_taken_ind"));
        p.leaveEndDate       = ld(rs.getDate("leave_end_date"));
        p.min                = rs.getInt("min");
        p.rate               = nzBd(rs.getBigDecimal("rate"));
        p.amt                = nzBd(rs.getBigDecimal("amt"));
        p.dayOfWeek          = rs.getInt("day_of_week");
        p.ref                = nz(rs.getString("ref"));
        p.payrunNo           = rs.getInt("payrun_no");
        p.payrunDate         = ld(rs.getDate("payrun_date"));
        p.lslWeeks           = nzBd(rs.getBigDecimal("lsl_weeks"));
        p.noteNo             = rs.getLong("note_no");
        p.auditUserId        = nz(rs.getString("audit_user_id"));
        p.auditDate          = ld(rs.getDate("audit_date"));
        p.auditTimeHr        = rs.getInt("audit_time_hr");
        p.auditTimeMin       = rs.getInt("audit_time_min");
        p.auditTimeSec       = rs.getInt("audit_time_sec");
        p.auditTimeHun       = rs.getInt("audit_time_hun");
        return p;
    }

    private static String nz(String s)            { return s == null ? "" : s; }
    private static String nz1(String s, String d) { return s == null || s.isBlank() ? d : s; }
    private static BigDecimal nz0(BigDecimal b)   { return b == null ? BigDecimal.ZERO : b; }
    private static BigDecimal nzBd(BigDecimal b)  { return b == null ? BigDecimal.ZERO : b; }
    private static LocalDate ld(java.sql.Date d)  { return d == null ? null : d.toLocalDate(); }
    private static java.sql.Date sqlDate(LocalDate d) {
        return d == null ? java.sql.Date.valueOf(LocalDate.of(1899, 12, 31))
                         : java.sql.Date.valueOf(d);
    }
}
