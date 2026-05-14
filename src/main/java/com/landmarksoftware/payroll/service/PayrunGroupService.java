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

import com.landmarksoftware.payroll.model.PayrunGroup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * PATM01 P2 — Pay Run × Pay Group join service (parungr table).
 *
 * <p>Reads include the {@code pagroup.desc1} column (resolved via LEFT JOIN)
 * for display in the P2 listbox — that field is denormalised on the model,
 * not stored.
 */
@Service
public class PayrunGroupService {

    private final JdbcTemplate jdbc;

    public PayrunGroupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** PATM01 P2 listbox — all paygroups attached to the given payrun. */
    public List<PayrunGroup> findByPayrun(int companyNo, int payrunNo) {
        return jdbc.query(
            "SELECT g.*, COALESCE(pg.desc1,'') AS desc1 " +
            "FROM parungr g " +
            "LEFT JOIN pagroup pg " +
            "  ON pg.company_no=g.company_no AND pg.paygroup=g.paygroup " +
            "WHERE g.company_no=? AND g.payrun_no=? " +
            "ORDER BY g.paygroup",
            (rs, i) -> map(rs, true),
            companyNo, payrunNo);
    }

    public Optional<PayrunGroup> findOne(int companyNo, int payrunNo, String paygroup) {
        try {
            PayrunGroup g = jdbc.queryForObject(
                "SELECT g.*, COALESCE(pg.desc1,'') AS desc1 " +
                "FROM parungr g " +
                "LEFT JOIN pagroup pg " +
                "  ON pg.company_no=g.company_no AND pg.paygroup=g.paygroup " +
                "WHERE g.company_no=? AND g.payrun_no=? AND g.paygroup=?",
                (rs, i) -> map(rs, true),
                companyNo, payrunNo, paygroup);
            return Optional.ofNullable(g);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Transactional
    public void insert(PayrunGroup g, String userId) {
        LocalTime now = LocalTime.now();
        jdbc.update(
            "INSERT INTO parungr (" +
            " company_no, payrun_no, paygroup," +
            " pay_thru_to_mth, pay_thru_to_4_wk, pay_thru_to_bimth," +
            " pay_thru_to_fort, pay_thru_to_week, paygroup_status," +
            " note_no, audit_user_id, audit_date, audit_time_hr, audit_time_min," +
            " audit_time_sec, audit_time_hun) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            g.companyNo, g.payrunNo, g.paygroup,
            sqlDate(g.payThruToMth), sqlDate(g.payThruTo4Wk), sqlDate(g.payThruToBimth),
            sqlDate(g.payThruToFort), sqlDate(g.payThruToWeek),
            g.paygroupStatus == null || g.paygroupStatus.isBlank() ? "O" : g.paygroupStatus,
            g.noteNo,
            userId, java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0);
    }

    @Transactional
    public void update(PayrunGroup g, String userId) {
        LocalTime now = LocalTime.now();
        jdbc.update(
            "UPDATE parungr SET" +
            "  pay_thru_to_mth=?, pay_thru_to_4_wk=?, pay_thru_to_bimth=?," +
            "  pay_thru_to_fort=?, pay_thru_to_week=?, paygroup_status=?," +
            "  audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?," +
            "  audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND payrun_no=? AND paygroup=?",
            sqlDate(g.payThruToMth), sqlDate(g.payThruTo4Wk), sqlDate(g.payThruToBimth),
            sqlDate(g.payThruToFort), sqlDate(g.payThruToWeek),
            g.paygroupStatus == null || g.paygroupStatus.isBlank() ? "O" : g.paygroupStatus,
            userId, java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0,
            g.companyNo, g.payrunNo, g.paygroup);
    }

    @Transactional
    public void delete(int companyNo, int payrunNo, String paygroup) {
        jdbc.update(
            "DELETE FROM parungr WHERE company_no=? AND payrun_no=? AND paygroup=?",
            companyNo, payrunNo, paygroup);
    }

    /** True when this paygroup already has timesheet header rows in patimhd. */
    public boolean hasTimesheets(int companyNo, int payrunNo, String paygroup) {
        try {
            Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM patimhd " +
                "WHERE company_no=? AND payrun_no=? AND alt_paygroup=?",
                Integer.class, companyNo, payrunNo, paygroup);
            return n != null && n > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static PayrunGroup map(ResultSet rs, boolean joined) throws SQLException {
        PayrunGroup g = new PayrunGroup();
        g.companyNo      = rs.getInt("company_no");
        g.payrunNo       = rs.getInt("payrun_no");
        g.paygroup       = nz(rs.getString("paygroup"));
        g.payThruToMth   = ld(rs.getDate("pay_thru_to_mth"));
        g.payThruTo4Wk   = ld(rs.getDate("pay_thru_to_4_wk"));
        g.payThruToBimth = ld(rs.getDate("pay_thru_to_bimth"));
        g.payThruToFort  = ld(rs.getDate("pay_thru_to_fort"));
        g.payThruToWeek  = ld(rs.getDate("pay_thru_to_week"));
        g.paygroupStatus = nz(rs.getString("paygroup_status"));
        g.noteNo         = rs.getLong("note_no");
        g.auditUserId    = nz(rs.getString("audit_user_id"));
        g.auditDate      = ld(rs.getDate("audit_date"));
        g.auditTimeHr    = rs.getInt("audit_time_hr");
        g.auditTimeMin   = rs.getInt("audit_time_min");
        g.auditTimeSec   = rs.getInt("audit_time_sec");
        g.auditTimeHun   = rs.getInt("audit_time_hun");
        if (joined) {
            try { g.paygroupDesc = nz(rs.getString("desc1")); }
            catch (SQLException ignored) { /* column absent on non-joined query */ }
        }
        return g;
    }

    private static String nz(String s)           { return s == null ? "" : s; }
    private static LocalDate ld(java.sql.Date d) { return d == null ? null : d.toLocalDate(); }
    private static java.sql.Date sqlDate(LocalDate d) {
        return d == null ? java.sql.Date.valueOf(LocalDate.of(1899, 12, 31))
                         : java.sql.Date.valueOf(d);
    }
}
