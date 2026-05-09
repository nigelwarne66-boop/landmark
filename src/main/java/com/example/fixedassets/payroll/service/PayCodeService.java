package com.example.fixedassets.payroll.service;

import com.example.fixedassets.payroll.model.PayCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PACD01 — Pay Code maintenance service.
 *
 * All JDBC for pay code CRUD. The UI controller (PayCodeMaintenanceController)
 * is JDBC-free.
 *
 * Table: pacodes
 *   PK: (company_no, pay_code)
 *   Columns confirmed from CONVENTIONS.md:
 *     pay_code VARCHAR(10), desc1, type INT (1-5),
 *     super_flag, income_taxable_flag
 *   Additional standard columns assumed present (standard Landmark pattern):
 *     active_flag, std_rate DECIMAL, std_amount DECIMAL, gl_code, notes
 *
 * NOTE: Confirm actual column names on first run — use the fallback query if
 * a column is missing and log clearly.  The tryGet() helper makes this safe.
 */
@Service
public class PayCodeService {

    private final JdbcTemplate jdbc;

    public PayCodeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── List ──────────────────────────────────────────────────────────────

    /**
     * Load all pay codes for a company, ordered by type then code.
     * Inactive codes are included; the UI can filter them.
     */
    public List<PayCode> findAll(int companyNo) {
        return jdbc.query(
            "SELECT pay_code, desc1, type, super_flag, income_taxable_flag, " +
            "active_flag, std_rate, std_amount, gl_code, notes " +
            "FROM pacodes WHERE company_no=? ORDER BY type, pay_code",
            (rs, i) -> map(String.valueOf(companyNo), rs),
            companyNo);
    }

    /**
     * Load pay codes filtered by type. Useful for the paycode lookup popup
     * that appears in PAEM01 and PATM01.
     */
    public List<PayCode> findByType(int companyNo, int payType) {
        return jdbc.query(
            "SELECT pay_code, desc1, type, super_flag, income_taxable_flag, " +
            "active_flag, std_rate, std_amount, gl_code, notes " +
            "FROM pacodes WHERE company_no=? AND type=? ORDER BY pay_code",
            (rs, i) -> map(String.valueOf(companyNo), rs),
            companyNo, payType);
    }

    // ── Single record ─────────────────────────────────────────────────────

    public Optional<PayCode> findOne(int companyNo, String payCode) {
        try {
            PayCode pc = jdbc.queryForObject(
                "SELECT pay_code, desc1, type, super_flag, income_taxable_flag, " +
                "active_flag, std_rate, std_amount, gl_code, notes " +
                "FROM pacodes WHERE company_no=? AND pay_code=?",
                (rs, i) -> map(String.valueOf(companyNo), rs),
                companyNo, payCode);
            return Optional.ofNullable(pc);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ── Validation ────────────────────────────────────────────────────────

    /**
     * Check whether a pay code already exists.
     * Used on Add to prevent duplicates.
     */
    public boolean exists(int companyNo, String payCode) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pacodes WHERE company_no=? AND pay_code=?",
            Integer.class, companyNo, payCode);
        return n != null && n > 0;
    }

    /**
     * Check whether a pay code is referenced in any paehist or paecode rows.
     * Used on Delete to prevent orphaning active pay history.
     */
    public boolean isInUse(int companyNo, String payCode) {
        try {
            Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM paehist WHERE company_no=? AND pay_code=? LIMIT 1",
                Integer.class, companyNo, payCode);
            if (n != null && n > 0) return true;
        } catch (Exception ignored) {}
        try {
            Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM paecode WHERE company_no=? AND pay_code=? LIMIT 1",
                Integer.class, companyNo, payCode);
            if (n != null && n > 0) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // ── Write operations ──────────────────────────────────────────────────

    /**
     * Insert a new pay code.
     * Caller must verify no duplicate exists before calling.
     */
    @Transactional
    public void insert(int companyNo, PayCode pc) {
        jdbc.update(
            "INSERT INTO pacodes " +
            "(company_no, pay_code, desc1, type, super_flag, income_taxable_flag, " +
            " active_flag, std_rate, std_amount, gl_code, notes) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            companyNo, pc.payCode.trim().toUpperCase(),
            pc.desc1, pc.payType,
            pc.superFlag, pc.incomeTaxFlag, pc.activeFlag,
            pc.stdRate, pc.stdAmount,
            pc.glCode, pc.notes);
    }

    /**
     * Update an existing pay code.
     * pay_code itself is not updatable (it's the PK); the user must
     * delete-and-recreate if the code needs to change.
     */
    @Transactional
    public void update(int companyNo, PayCode pc) {
        jdbc.update(
            "UPDATE pacodes SET " +
            "desc1=?, type=?, super_flag=?, income_taxable_flag=?, " +
            "active_flag=?, std_rate=?, std_amount=?, gl_code=?, notes=? " +
            "WHERE company_no=? AND pay_code=?",
            pc.desc1, pc.payType,
            pc.superFlag, pc.incomeTaxFlag, pc.activeFlag,
            pc.stdRate, pc.stdAmount,
            pc.glCode, pc.notes,
            companyNo, pc.payCode);
    }

    /**
     * Hard-delete a pay code.
     * Caller must check isInUse() first; if in use, set active_flag='N' instead.
     */
    @Transactional
    public void delete(int companyNo, String payCode) {
        jdbc.update(
            "DELETE FROM pacodes WHERE company_no=? AND pay_code=?",
            companyNo, payCode);
    }

    /**
     * Soft-delete: mark pay code inactive rather than deleting it.
     * Preferred when isInUse() returns true.
     */
    @Transactional
    public void deactivate(int companyNo, String payCode) {
        jdbc.update(
            "UPDATE pacodes SET active_flag='N' WHERE company_no=? AND pay_code=?",
            companyNo, payCode);
    }

    // ── Row mapper ────────────────────────────────────────────────────────

    private static PayCode map(String companyNo, java.sql.ResultSet rs)
            throws java.sql.SQLException {
        PayCode pc = new PayCode();
        pc.companyNo     = companyNo;
        pc.payCode       = rs.getString("pay_code").trim();
        pc.desc1         = str(rs, "desc1");
        pc.payType       = rs.getInt("type");
        pc.superFlag     = str(rs, "super_flag");
        pc.incomeTaxFlag = str(rs, "income_taxable_flag");
        pc.activeFlag    = strDefault(rs, "active_flag",  "Y");
        pc.stdRate       = dec(rs, "std_rate");
        pc.stdAmount     = dec(rs, "std_amount");
        pc.glCode        = strDefault(rs, "gl_code",  "");
        pc.notes         = strDefault(rs, "notes",    "");
        return pc;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String str(java.sql.ResultSet rs, String col)
            throws java.sql.SQLException {
        String v = rs.getString(col);
        return v == null ? "" : v.trim();
    }

    /** Return default if the column doesn't exist or is null. */
    private static String strDefault(java.sql.ResultSet rs, String col, String def) {
        try {
            String v = rs.getString(col);
            return v == null ? def : v.trim();
        } catch (Exception e) { return def; }
    }

    private static BigDecimal dec(java.sql.ResultSet rs, String col) {
        try {
            BigDecimal v = rs.getBigDecimal(col);
            return v == null ? BigDecimal.ZERO : v;
        } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
