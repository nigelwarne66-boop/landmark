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
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * PAEM01 — Employee maintenance service.
 *
 * All JDBC for pastaff CRUD. The UI controller is JDBC-free.
 * SQL statements live in {@link PayrollSql} — this class holds only the
 * JdbcTemplate calls and the row mapper.
 *
 * Table: pastaff   PK: (company_no, employee_no INT)
 *
 * Banking, super, and LSL hour breakdowns are stored on other tables
 * (paempay, paemsup, etc.) and are not edited from this screen.
 *
 * INSERT is intentionally not implemented — pastaff has 121 columns,
 * many NOT NULL, and a successful Add requires more wiring than the
 * subset the UI currently exposes. Update/Terminate are sufficient
 * for Wave 1 maintenance.
 */
@Service
public class EmployeeService {

    /** COBOL date-zero sentinel for NOT NULL date columns with no value. */
    private static final LocalDate DATE_ZERO = LocalDate.of(1899, 12, 31);

    private final JdbcTemplate jdbc;

    public EmployeeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── List ──────────────────────────────────────────────────────────────

    /** Load all employees for a company, ordered by employee_no. */
    public List<Employee> findAll(int companyNo) {
        return jdbc.query(
            PayrollSql.FIND_ALL_EMPLOYEES,
            (rs, i) -> map(companyNo, rs),
            companyNo);
    }

    /** Search employees by partial name (surname OR first_name) or exact employee_no. */
    public List<Employee> search(int companyNo, String term) {
        if (term == null || term.isBlank()) return findAll(companyNo);
        String like = "%" + term.trim() + "%";
        Integer empNo = parseInt(term);
        return jdbc.query(
            PayrollSql.SEARCH_EMPLOYEES_BY_NAME_OR_NO,
            (rs, i) -> map(companyNo, rs),
            companyNo, like, like, empNo == null ? -1 : empNo);
    }

    // ── Single record ─────────────────────────────────────────────────────

    public Optional<Employee> findOne(int companyNo, int employeeNo) {
        try {
            Employee e = jdbc.queryForObject(
                PayrollSql.FIND_EMPLOYEE_BY_PK,
                (rs, i) -> map(companyNo, rs),
                companyNo, employeeNo);
            return Optional.ofNullable(e);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    // ── Validation ────────────────────────────────────────────────────────

    public boolean exists(int companyNo, int employeeNo) {
        Integer n = jdbc.queryForObject(
            PayrollSql.COUNT_EMPLOYEE_BY_PK,
            Integer.class, companyNo, employeeNo);
        return n != null && n > 0;
    }

    /** Whether the employee has any pay history — blocks hard delete. */
    public boolean hasPayHistory(int companyNo, int employeeNo) {
        try {
            Integer n = jdbc.queryForObject(
                PayrollSql.COUNT_EMPLOYEE_PAY_HISTORY,
                Integer.class, companyNo, employeeNo);
            return n != null && n > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ── Leave balances (display-only refresh) ─────────────────────────────

    /**
     * Re-read the leave-balance columns for a single employee.
     * Returns: [al_hrs_accrued (minutes), accrued_sick_leave (minutes),
     *           lsl_weeks_accrued (×100 for 2dp display, 0 if unavailable)].
     */
    public int[] loadLeaveBalances(int companyNo, int employeeNo) {
        try {
            return jdbc.queryForObject(
                PayrollSql.FIND_EMPLOYEE_LEAVE_BALANCES,
                (rs, i) -> new int[] {
                    safeInt(rs, "al_hrs_accrued"),
                    safeInt(rs, "accrued_sick_leave"),
                    dec(rs, "lsl_weeks_accrued").movePointRight(2).intValue()
                },
                companyNo, employeeNo);
        } catch (Exception ignored) {
            return new int[] {0, 0, 0};
        }
    }

    // ── Write operations ──────────────────────────────────────────────────

    /**
     * Update an existing employee row.
     * employee_no itself is not updatable (it's the PK).
     * Only the columns exposed on the maintenance screen are touched —
     * other 90+ pastaff columns are left untouched.
     */
    @Transactional
    public void update(int companyNo, Employee e) {
        jdbc.update(
            PayrollSql.UPDATE_EMPLOYEE,
            trimUp(e.surname, 30), trim(e.firstName, 30), trim(e.secondName, 30),
            trim(e.addr1, 30), trim(e.addr2, 30), trim(e.city, 30),
            trimUp(e.state, 4), trim(e.postcode, 10),
            digits(e.phoneArea, 4), trim(e.phoneNo, 20), trim(e.mobile, 20),
            trim(e.emailAddress, 80),
            trimUp(e.dept, 10), trimUp(e.paygroup, 10),
            statusOrA(e.employeeStatus), trimUp(e.employeeType, 1),
            sqlDate(e.dateStarted), sqlDate(e.dateTerminated),
            payFreqOrW(e.payFreq),
            trimUp(e.award, 10), trimUp(e.jobClass, 10),
            nz(e.annualSalary), e.stdHrs, nz(e.stdRatePerHr),
            digits(e.taxFileNo, 11), trimUp(e.taxScaleNo, 4), nz(e.extraTaxAmt),
            companyNo, e.employeeNo);
    }

    /**
     * Terminate an employee (soft delete).
     * Sets employee_status='T' and date_terminated=date.
     */
    @Transactional
    public void terminate(int companyNo, int employeeNo, LocalDate date) {
        jdbc.update(
            PayrollSql.TERMINATE_EMPLOYEE,
            sqlDate(date), companyNo, employeeNo);
    }

    // ── Row mapper ────────────────────────────────────────────────────────

    private static Employee map(int companyNo, ResultSet rs) throws SQLException {
        Employee e = new Employee();
        e.companyNo        = companyNo;
        e.employeeNo       = rs.getInt("employee_no");
        e.surname          = strDefault(rs, "surname", "");
        e.firstName        = strDefault(rs, "first_name", "");
        e.secondName       = strDefault(rs, "second_name", "");
        e.addr1            = strDefault(rs, "addr_1", "");
        e.addr2            = strDefault(rs, "addr_2", "");
        e.city             = strDefault(rs, "city", "");
        e.state            = strDefault(rs, "state", "");
        e.postcode         = strDefault(rs, "postcode", "");
        e.phoneArea        = strDefault(rs, "phone_area", "");
        e.phoneNo          = strDefault(rs, "phone_no", "");
        e.mobile           = strDefault(rs, "mobile", "");
        e.emailAddress     = strDefault(rs, "email_address", "");
        e.dept             = strDefault(rs, "dept", "");
        e.paygroup         = strDefault(rs, "paygroup", "");
        e.employeeStatus   = strDefault(rs, "employee_status", "A");
        e.employeeType     = strDefault(rs, "employee_type", "");
        e.dateStarted      = dateDef(rs, "date_started");
        e.dateTerminated   = dateDef(rs, "date_terminated");
        e.payFreq          = strDefault(rs, "pay_freq", "W");
        e.award            = strDefault(rs, "award", "");
        e.jobClass         = strDefault(rs, "job_class", "");
        e.annualSalary     = dec(rs, "annual_salary");
        e.stdHrs           = safeInt(rs, "std_hrs");
        e.stdRatePerHr     = dec(rs, "std_rate_per_hr");
        e.taxFileNo        = strDefault(rs, "tax_file_no", "");
        e.taxScaleNo       = strDefault(rs, "tax_scale_no", "");
        e.extraTaxAmt      = dec(rs, "extra_tax_amt");
        e.alHrsAccrued     = safeInt(rs, "al_hrs_accrued");
        e.accruedSickLeave = safeInt(rs, "accrued_sick_leave");
        e.lslWeeksAccrued  = dec(rs, "lsl_weeks_accrued");
        return e;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String strDefault(ResultSet rs, String col, String def) {
        try {
            String v = rs.getString(col);
            return v == null ? def : v.trim();
        } catch (Exception e) { return def; }
    }

    private static int safeInt(ResultSet rs, String col) {
        try { return rs.getInt(col); } catch (Exception e) { return 0; }
    }

    private static BigDecimal dec(ResultSet rs, String col) {
        try {
            BigDecimal v = rs.getBigDecimal(col);
            return v == null ? BigDecimal.ZERO : v;
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** SQL date → LocalDate; date-zero or out-of-range → null. */
    private static LocalDate dateDef(ResultSet rs, String col) {
        try {
            Date d = rs.getDate(col);
            if (d == null) return null;
            LocalDate ld = d.toLocalDate();
            if (ld.equals(DATE_ZERO) || ld.getYear() < 1900 || ld.getYear() > 7000)
                return null;
            return ld;
        } catch (Exception e) { return null; }
    }

    /** LocalDate → java.sql.Date, null becomes the date-zero sentinel. */
    private static Date sqlDate(LocalDate ld) {
        return Date.valueOf(ld == null ? DATE_ZERO : ld);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String trimUp(String s, int max) {
        return trim(s, max).toUpperCase();
    }

    /** Strip non-digits and truncate — for TFN and phone-area. */
    private static String digits(String s, int max) {
        if (s == null) return "";
        String d = s.replaceAll("\\D", "");
        return d.length() > max ? d.substring(0, max) : d;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String statusOrA(String s) {
        if (s == null || s.isBlank()) return "A";
        return s.trim().toUpperCase();
    }

    private static String payFreqOrW(String s) {
        if (s == null || s.isBlank()) return "W";
        return s.trim().toUpperCase();
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }
}
