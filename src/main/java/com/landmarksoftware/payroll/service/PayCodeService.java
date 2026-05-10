package com.landmarksoftware.payroll.service;

import com.landmarksoftware.payroll.model.PayCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 *
 * INSERT is intentionally not implemented — pacodes has 122 columns,
 * many NOT NULL with no defaults. Maintenance via Edit only for now.
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

    private static String yn(String s) {
        return "Y".equalsIgnoreCase(s == null ? "" : s.trim()) ? "Y" : "N";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
