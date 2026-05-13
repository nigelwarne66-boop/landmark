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

import com.landmarksoftware.payroll.model.AwardWcomp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * paawwcp CRUD — workers comp & on-costs % per
 * (award, job_class, paygroup). PAAW01 S3.
 */
@Service
public class AwardWcompService {

    private final JdbcTemplate jdbc;

    public AwardWcompService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<AwardWcomp> ROW = (rs, i) -> {
        AwardWcomp w = new AwardWcomp();
        w.companyNo    = rs.getInt("company_no");
        w.awardCode    = nz(rs.getString("award_code"));
        w.jobClassCode = nz(rs.getString("job_class_code"));
        w.paygroup     = nz(rs.getString("paygroup"));
        w.wcompPerc    = nzDec(rs.getBigDecimal("wcomp_perc"));
        w.onCostsPerc  = nzDec(rs.getBigDecimal("on_costs_perc"));
        w.noteNo       = rs.getLong("note_no");
        return w;
    };

    public List<AwardWcomp> findByAward(int companyNo, String awardCode) {
        return jdbc.query(
            "SELECT * FROM paawwcp WHERE company_no=? AND award_code=? " +
            "ORDER BY job_class_code, paygroup",
            ROW, companyNo, awardCode);
    }

    public boolean exists(int companyNo, String awardCode,
                           String jobClassCode, String paygroup) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM paawwcp WHERE company_no=? AND award_code=? " +
            "AND job_class_code=? AND paygroup=?",
            Integer.class, companyNo, awardCode, jobClassCode, paygroup);
        return n != null && n > 0;
    }

    @Transactional
    public void insert(AwardWcomp w, String userId) {
        Stamp s = stamp(userId);
        jdbc.update(
            "INSERT INTO paawwcp (" +
            " company_no, award_code, job_class_code, paygroup, " +
            " wcomp_perc, on_costs_perc, note_no, " +
            " audit_user_id, audit_date, audit_time_hr, audit_time_min, " +
            " audit_time_sec, audit_time_hun" +
            ") VALUES (?,?,?,?, ?,?,?, ?,?,?,?,?,?)",
            w.companyNo, w.awardCode, w.jobClassCode, w.paygroup,
            w.wcompPerc, w.onCostsPerc, w.noteNo,
            s.user, s.date, s.hr, s.mi, s.sec, s.hun);
    }

    @Transactional
    public void update(AwardWcomp w, String userId) {
        Stamp s = stamp(userId);
        jdbc.update(
            "UPDATE paawwcp SET wcomp_perc=?, on_costs_perc=?, " +
            " audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?, " +
            " audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND award_code=? AND job_class_code=? AND paygroup=?",
            w.wcompPerc, w.onCostsPerc,
            s.user, s.date, s.hr, s.mi, s.sec, s.hun,
            w.companyNo, w.awardCode, w.jobClassCode, w.paygroup);
    }

    @Transactional
    public void delete(int companyNo, String awardCode,
                        String jobClassCode, String paygroup) {
        jdbc.update(
            "DELETE FROM paawwcp WHERE company_no=? AND award_code=? " +
            "AND job_class_code=? AND paygroup=?",
            companyNo, awardCode, jobClassCode, paygroup);
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
