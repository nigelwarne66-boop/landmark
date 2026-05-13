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

import com.landmarksoftware.payroll.model.EmployeePay;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * paempay CRUD for the PAEM01 Bank tab.
 *
 * <p>Audit columns ({@code audit_user_id, audit_date, audit_time_*}) are
 * NOT NULL in the schema — every insert/update populates them from the
 * caller's user id and the server clock. {@code note_no} defaults to 0.
 */
@Service
public class EmployeePayService {

    private final JdbcTemplate jdbc;

    public EmployeePayService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<EmployeePay> ROW = (rs, i) -> {
        EmployeePay p = new EmployeePay();
        p.companyNo       = rs.getInt("company_no");
        p.employeeNo      = rs.getInt("employee_no");
        p.paymentNo       = rs.getInt("payment_no");
        p.payMethod       = nullToEmpty(rs.getString("pay_method"));
        p.bankCode        = nullToEmpty(rs.getString("bank_code"));
        p.tfrToBankNo     = nullToEmpty(rs.getString("tfr_to_bank_no"));
        p.tfrToBankAcctNo = nullToEmpty(rs.getString("tfr_to_bank_acct_no"));
        p.payCalcMethod   = nullToEmpty(rs.getString("pay_calc_method"));
        BigDecimal amt    = rs.getBigDecimal("pay_amt_perc");
        p.payAmtPerc      = amt == null ? BigDecimal.ZERO : amt;
        p.payeeName       = nullToEmpty(rs.getString("payee_name"));
        p.noteNo          = rs.getLong("note_no");
        return p;
    };

    public List<EmployeePay> findByEmployee(int companyNo, int employeeNo) {
        return jdbc.query(
            "SELECT * FROM paempay WHERE company_no=? AND employee_no=? " +
            "ORDER BY payment_no",
            ROW, companyNo, employeeNo);
    }

    /** Lowest unused payment_no for a given employee (1-based). */
    public int nextPaymentNo(int companyNo, int employeeNo) {
        Integer maxNo = jdbc.queryForObject(
            "SELECT COALESCE(MAX(payment_no), 0) FROM paempay " +
            "WHERE company_no=? AND employee_no=?",
            Integer.class, companyNo, employeeNo);
        return (maxNo == null ? 0 : maxNo) + 1;
    }

    @Transactional
    public void insert(EmployeePay p, String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now   = LocalTime.now();
        jdbc.update(
            "INSERT INTO paempay (company_no, employee_no, payment_no, " +
            "pay_method, bank_code, tfr_to_bank_no, tfr_to_bank_acct_no, " +
            "pay_calc_method, pay_amt_perc, payee_name, note_no, " +
            "audit_user_id, audit_date, audit_time_hr, audit_time_min, " +
            "audit_time_sec, audit_time_hun) " +
            "VALUES (?,?,?, ?,?,?,?, ?,?,?,?, ?,?,?,?,?,?)",
            p.companyNo, p.employeeNo, p.paymentNo,
            p.payMethod, p.bankCode, p.tfrToBankNo, p.tfrToBankAcctNo,
            p.payCalcMethod, p.payAmtPerc, p.payeeName, p.noteNo,
            safeUser(userId), java.sql.Date.valueOf(today),
            now.getHour(), now.getMinute(), now.getSecond(), now.getNano() / 10_000_000);
    }

    @Transactional
    public void update(EmployeePay p, String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now   = LocalTime.now();
        jdbc.update(
            "UPDATE paempay SET " +
            "pay_method=?, bank_code=?, tfr_to_bank_no=?, tfr_to_bank_acct_no=?, " +
            "pay_calc_method=?, pay_amt_perc=?, payee_name=?, note_no=?, " +
            "audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?, " +
            "audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND employee_no=? AND payment_no=?",
            p.payMethod, p.bankCode, p.tfrToBankNo, p.tfrToBankAcctNo,
            p.payCalcMethod, p.payAmtPerc, p.payeeName, p.noteNo,
            safeUser(userId), java.sql.Date.valueOf(today),
            now.getHour(), now.getMinute(), now.getSecond(), now.getNano() / 10_000_000,
            p.companyNo, p.employeeNo, p.paymentNo);
    }

    @Transactional
    public void delete(int companyNo, int employeeNo, int paymentNo) {
        jdbc.update(
            "DELETE FROM paempay WHERE company_no=? AND employee_no=? AND payment_no=?",
            companyNo, employeeNo, paymentNo);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String safeUser(String s) {
        if (s == null) return "";
        return s.length() > 15 ? s.substring(0, 15) : s;
    }
}
