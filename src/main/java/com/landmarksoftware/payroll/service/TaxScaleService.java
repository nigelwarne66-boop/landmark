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

import com.landmarksoftware.payroll.model.TaxBracket;
import com.landmarksoftware.payroll.model.TaxScale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * pataxfl CRUD service for PASU04 — Tax Scale Maintenance.
 *
 * <p>Brackets are read-only here — the bulk loader (PATX01) owns
 * {@code tax_brackets}. {@link #findBracketsForScale} returns the latest
 * publication's brackets for use in the read-only Brackets tab.
 *
 * <p>Delete blocks if any active employee carries this scale, mirroring the
 * COBOL CHECK-STAFF-TAX-SCALES guard. Scale "H" (legacy HECS marker) is
 * also blocked.
 */
@Service
public class TaxScaleService {

    private final JdbcTemplate jdbc;

    public TaxScaleService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<TaxScale> ROW = (rs, i) -> {
        TaxScale s = new TaxScale();
        s.companyNo          = rs.getInt   ("company_no");
        s.scaleNo            = nz(rs.getString("scale_no"));
        s.desc1              = nz(rs.getString("desc_1"));
        s.desc2              = nz(rs.getString("desc_2"));
        s.leaveLoadingLimit  = rs.getInt   ("leave_loading_limit");
        s.termTaxPerc        = nzDec(rs.getBigDecimal("term_tax_perc"));
        s.includesHecsFlag   = nz(rs.getString("includes_hecs_flag"));
        s.fbtPercRate        = nzDec(rs.getBigDecimal("fbt_perc_rate"));
        s.fbtTaxableAmt      = nzDec(rs.getBigDecimal("fbt_taxable_amt"));
        s.roundingInd        = nz(rs.getString("rounding_ind"));
        s.noteNo             = rs.getLong  ("note_no");
        return s;
    };

    // ─── Reads ──────────────────────────────────────────────────────────

    public List<TaxScale> findAll(int companyNo) {
        return jdbc.query(
            "SELECT * FROM pataxfl WHERE company_no=? ORDER BY scale_no",
            ROW, companyNo);
    }

    public Optional<TaxScale> findOne(int companyNo, String scaleNo) {
        List<TaxScale> rows = jdbc.query(
            "SELECT * FROM pataxfl WHERE company_no=? AND scale_no=?",
            ROW, companyNo, scaleNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean exists(int companyNo, String scaleNo) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pataxfl WHERE company_no=? AND scale_no=?",
            Integer.class, companyNo, scaleNo);
        return n != null && n > 0;
    }

    /** Active employees referencing this scale — non-zero blocks delete. */
    public int countAttachedEmployees(int companyNo, String scaleNo) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pastaff WHERE company_no=? AND tax_scale_no=? " +
            "AND employee_status <> 'T'",
            Integer.class, companyNo, scaleNo);
        return n == null ? 0 : n;
    }

    /**
     * Brackets to display on the Brackets tab — latest publication per
     * source file ({@code NAT_1004} and {@code NAT_3539}). Returns rows
     * already ordered for display: source_file, bracket_no.
     */
    public List<TaxBracket> findBracketsForScale(int companyNo, String scaleNo) {
        return jdbc.query(
            "SELECT tb.* FROM tax_brackets tb " +
            "JOIN ( " +
            "  SELECT source_file, MAX(effective_from) AS eff " +
            "  FROM tax_brackets WHERE company_no=? AND scale_no=? " +
            "  GROUP BY source_file " +
            ") latest ON latest.source_file = tb.source_file " +
            "         AND latest.eff       = tb.effective_from " +
            "WHERE tb.company_no=? AND tb.scale_no=? " +
            "ORDER BY tb.source_file, tb.bracket_no",
            (rs, i) -> {
                TaxBracket b = new TaxBracket();
                b.companyNo      = rs.getInt("company_no");
                b.sourceFile     = nz(rs.getString("source_file"));
                Date d = rs.getDate("effective_from");
                b.effectiveFrom  = d == null ? null : d.toLocalDate();
                b.scaleNo        = nz(rs.getString("scale_no"));
                b.bracketNo      = rs.getInt("bracket_no");
                b.upperEarnings  = nzDec(rs.getBigDecimal("upper_earnings"));
                b.coeffA         = nzDec(rs.getBigDecimal("coeff_a"));
                b.coeffB         = nzDec(rs.getBigDecimal("coeff_b"));
                return b;
            },
            companyNo, scaleNo, companyNo, scaleNo);
    }

    // ─── Writes ─────────────────────────────────────────────────────────

    @Transactional
    public void insert(TaxScale s, String userId) {
        Stamp st = stamp(userId);
        jdbc.update(
            "INSERT INTO pataxfl (" +
            "  company_no, scale_no, desc_1, desc_2, leave_loading_limit, " +
            "  term_tax_perc, includes_hecs_flag, fbt_perc_rate, fbt_taxable_amt, " +
            "  rounding_ind, note_no, " +
            "  audit_user_id, audit_date, audit_time_hr, audit_time_min, " +
            "  audit_time_sec, audit_time_hun" +
            ") VALUES (?,?,?,?,?, ?,?,?,?, ?,?, ?,?,?,?,?,?)",
            s.companyNo, s.scaleNo, s.desc1, s.desc2, s.leaveLoadingLimit,
            s.termTaxPerc, s.includesHecsFlag, s.fbtPercRate, s.fbtTaxableAmt,
            s.roundingInd, s.noteNo,
            st.user, st.date, st.hr, st.mi, st.sec, st.hun);
    }

    @Transactional
    public void update(TaxScale s, String userId) {
        Stamp st = stamp(userId);
        jdbc.update(
            "UPDATE pataxfl SET " +
            "  desc_1=?, desc_2=?, leave_loading_limit=?, " +
            "  term_tax_perc=?, includes_hecs_flag=?, fbt_perc_rate=?, fbt_taxable_amt=?, " +
            "  rounding_ind=?, " +
            "  audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?, " +
            "  audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND scale_no=?",
            s.desc1, s.desc2, s.leaveLoadingLimit,
            s.termTaxPerc, s.includesHecsFlag, s.fbtPercRate, s.fbtTaxableAmt,
            s.roundingInd,
            st.user, st.date, st.hr, st.mi, st.sec, st.hun,
            s.companyNo, s.scaleNo);
    }

    @Transactional
    public void delete(int companyNo, String scaleNo) {
        jdbc.update(
            "DELETE FROM pataxfl WHERE company_no=? AND scale_no=?",
            companyNo, scaleNo);
    }

    // ─── Internal helpers ───────────────────────────────────────────────

    private record Stamp(String user, Date date, int hr, int mi, int sec, int hun) {}

    private static Stamp stamp(String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now   = LocalTime.now();
        String u = userId == null ? "" : (userId.length() > 15 ? userId.substring(0, 15) : userId);
        return new Stamp(u, Date.valueOf(today),
                          now.getHour(), now.getMinute(),
                          now.getSecond(), now.getNano() / 10_000_000);
    }

    private static String     nz(String s)        { return s == null ? "" : s; }
    private static BigDecimal nzDec(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
