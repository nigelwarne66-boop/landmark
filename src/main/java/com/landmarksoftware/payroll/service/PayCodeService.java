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

import com.landmarksoftware.payroll.model.PayCode;
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
 * PACD01 — Pay Code maintenance service.
 *
 * All JDBC for pacodes CRUD. The UI controller is JDBC-free.
 *
 * Table: pacodes   PK: (company_no, pay_code)
 *
 * SQL statements live in {@link PayrollSql} — this class holds only the
 * JdbcTemplate calls and the row mapper.
 */
@Service
public class PayCodeService {

    private final JdbcTemplate jdbc;

    public PayCodeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── List ──────────────────────────────────────────────────────────────

    public List<PayCode> findAll(int companyNo) {
        return jdbc.query(
            PayrollSql.FIND_ALL_PAYCODES,
            (rs, i) -> map(String.valueOf(companyNo), rs),
            companyNo);
    }

    public List<PayCode> findByType(int companyNo, int payType) {
        return jdbc.query(
            PayrollSql.FIND_PAYCODES_BY_TYPE,
            (rs, i) -> map(String.valueOf(companyNo), rs),
            companyNo, payType);
    }

    // ── Single record ─────────────────────────────────────────────────────

    public Optional<PayCode> findOne(int companyNo, String payCode) {
        try {
            PayCode pc = jdbc.queryForObject(
                PayrollSql.FIND_PAYCODE_BY_PK,
                (rs, i) -> map(String.valueOf(companyNo), rs),
                companyNo, payCode);
            return Optional.ofNullable(pc);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ── Validation ────────────────────────────────────────────────────────

    public boolean exists(int companyNo, String payCode) {
        Integer n = jdbc.queryForObject(
            PayrollSql.COUNT_PAYCODE_BY_PK,
            Integer.class, companyNo, payCode);
        return n != null && n > 0;
    }

    /** Whether a pay code is referenced in any paehist or paecode rows. */
    public boolean isInUse(int companyNo, String payCode) {
        try {
            Integer n = jdbc.queryForObject(
                PayrollSql.COUNT_PAYCODE_USES_IN_HISTORY,
                Integer.class, companyNo, payCode);
            if (n != null && n > 0) return true;
        } catch (Exception ignored) {}
        try {
            Integer n = jdbc.queryForObject(
                PayrollSql.COUNT_PAYCODE_USES_IN_STANDING,
                Integer.class, companyNo, payCode);
            if (n != null && n > 0) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // ── Write operations ──────────────────────────────────────────────────

    /**
     * Insert a new pacodes row.
     * Binds all 121 columns (see PayrollSql.INSERT_PAYCODE) — UI-exposed
     * fields come from {@code pc}, audit fields from {@code userId} + now,
     * everything else gets a sentinel default ("", "N", 0, BigDecimal.ZERO).
     * Caller must verify no duplicate exists.
     */
    @Transactional
    public void insert(int companyNo, PayCode pc, String userId) {
        java.sql.Date today = java.sql.Date.valueOf(LocalDate.now());
        LocalTime now = LocalTime.now();
        int hr  = now.getHour();
        int min = now.getMinute();
        int sec = now.getSecond();
        BigDecimal Z = BigDecimal.ZERO;

        jdbc.update(PayrollSql.INSERT_PAYCODE,
            //  1- 6 identity / display
            companyNo, trimUp(pc.payCode, 10), pc.payType, trim(pc.desc1, 30),
            trim(pc.payslipDesc, 30), trim(pc.abbrevDesc, 10),
            //  7-10 behaviour flags
            yn(pc.printOnPayslipFlag), yn(pc.wcompFlag),
            yn(pc.superFlag), yn(pc.termEFlag),
            // 11-13 allowance rate/amt + unit description
            nz(pc.allowRate), nz(pc.allowAmt), "",
            // 14-22 allow_* boolean flags
            "N", "N", "N", "N", "N", "N", "N", "N", "N",
            // 23-29 cg/allow_* misc + gst code + cdep
            "N", "N", "N", "N", "N", "", "N",
            // 30-31 dedn rate/amt
            nz(pc.dednPerc), nz(pc.dednAmt),
            // 32-39 dedn settings
            "", "", 0, 0, "N", "N", "N", "N",
            // 40-46 super_*
            Z, "", "", 0, 0, "N", "N",
            // 47-50 fund_addr_*
            "", "", "", "",
            // 51-57 bank/contact/plan/acct
            "", "", "", "", "", "", "",
            // 58-60 super reportable / before_after / max_super_ytd
            "N", "", Z,
            // 61-63 pay_factor / pay_rate / pay_payable_flag
            nz(pc.payFactor), nz(pc.payRate), "N",
            // 64-73 pay_* flags
            "N", "N", "N", "N", "N", "N", "N", "N", "N", "N",
            // 74-80 leave_max_taken + leave_*
            0, "N", "N", "N", "N", "N", "N",
            // 81-87 leave_pay_factor + leave_*
            Z, "N", "N", 0, "N", "N", "N",
            // 88-100 contrib_*
            "N", "", "", "N", "N", 0, 0, "N", "N", "N", "N", "", "N",
            // 101-103 tax_remit_freq / tax_pay_method / eft_reference
            "", "", "",
            // 104-106 fund_abn / fund_usi / fund_esa
            "", "", "",
            // 107-115 apra/superstream/etc + note_no
            "", "N", "N", "", "", "", "N", "", 0L,
            // 116-121 audit
            userId, today, hr, min, sec, 0
        );
    }

    /**
     * Update an existing pay code — only the columns the UI exposes.
     * Other 100+ pacodes columns are left untouched.
     */
    @Transactional
    public void update(int companyNo, PayCode pc) {
        jdbc.update(
            PayrollSql.UPDATE_PAYCODE,
            pc.payType, trim(pc.desc1, 30),
            trim(pc.payslipDesc, 30), trim(pc.abbrevDesc, 10),
            yn(pc.printOnPayslipFlag), yn(pc.superFlag),
            yn(pc.wcompFlag), yn(pc.termEFlag),
            nz(pc.payRate), nz(pc.payFactor),
            nz(pc.allowRate), nz(pc.allowAmt),
            nz(pc.dednPerc), nz(pc.dednAmt),
            companyNo, pc.payCode);
    }

    @Transactional
    public void delete(int companyNo, String payCode) {
        jdbc.update(PayrollSql.DELETE_PAYCODE, companyNo, payCode);
    }

    // ── Row mapper ────────────────────────────────────────────────────────

    private static PayCode map(String companyNo, ResultSet rs) throws SQLException {
        PayCode pc = new PayCode();
        pc.companyNo          = companyNo;
        pc.payCode            = strDefault(rs, "pay_code", "").trim();
        pc.payType            = safeInt(rs, "type");
        pc.desc1              = strDefault(rs, "desc1", "");
        pc.payslipDesc        = strDefault(rs, "payslip_desc", "");
        pc.abbrevDesc         = strDefault(rs, "abbrev_desc", "");
        pc.printOnPayslipFlag = strDefault(rs, "print_on_payslip_flag", "Y");
        pc.superFlag          = strDefault(rs, "super_flag", "N");
        pc.wcompFlag          = strDefault(rs, "wcomp_flag", "N");
        pc.termEFlag          = strDefault(rs, "term_e_flag", "N");
        pc.payRate            = dec(rs, "pay_rate");
        pc.payFactor          = dec(rs, "pay_factor");
        pc.allowRate          = dec(rs, "allow_rate");
        pc.allowAmt           = dec(rs, "allow_amt");
        pc.dednPerc           = dec(rs, "dedn_perc");
        pc.dednAmt            = dec(rs, "dedn_amt");
        return pc;
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

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String trimUp(String s, int max) {
        return trim(s, max).toUpperCase();
    }

    private static String yn(String s) {
        return "Y".equalsIgnoreCase(s == null ? "" : s.trim()) ? "Y" : "N";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
