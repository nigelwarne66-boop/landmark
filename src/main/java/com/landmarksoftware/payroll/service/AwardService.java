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

import com.landmarksoftware.payroll.model.Award;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * paawhed CRUD for the award header (PAAW01 P1/S1).
 *
 * <p>Delete blocks if employees still reference this award
 * ({@code pastaff.award = X}) — mirrors the COBOL CHECK-STAFF-AWARD guard.
 */
@Service
public class AwardService {

    private final JdbcTemplate jdbc;

    public AwardService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Award> ROW = (rs, i) -> {
        Award a = new Award();
        a.companyNo = rs.getInt("company_no");
        a.awardCode = nz(rs.getString("award_code"));
        a.desc1     = nz(rs.getString("desc1"));
        a.noteNo    = rs.getLong("note_no");
        return a;
    };

    public List<Award> findAll(int companyNo) {
        return jdbc.query(
            "SELECT * FROM paawhed WHERE company_no=? ORDER BY award_code",
            ROW, companyNo);
    }

    public Optional<Award> findOne(int companyNo, String awardCode) {
        List<Award> rows = jdbc.query(
            "SELECT * FROM paawhed WHERE company_no=? AND award_code=?",
            ROW, companyNo, awardCode);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean exists(int companyNo, String awardCode) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM paawhed WHERE company_no=? AND award_code=?",
            Integer.class, companyNo, awardCode);
        return n != null && n > 0;
    }

    /** Active employees referencing this award. Non-zero blocks delete. */
    public int countAttachedEmployees(int companyNo, String awardCode) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pastaff WHERE company_no=? AND award=? " +
            "AND employee_status <> 'T'",
            Integer.class, companyNo, awardCode);
        return n == null ? 0 : n;
    }

    /** Job-class rows attached to this award (informational on the P1 list). */
    public int countJobClasses(int companyNo, String awardCode) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM paawjob WHERE company_no=? AND award_code=?",
            Integer.class, companyNo, awardCode);
        return n == null ? 0 : n;
    }

    @Transactional
    public void insert(Award a, String userId) {
        Stamp s = stamp(userId);
        jdbc.update(
            "INSERT INTO paawhed (" +
            "  company_no, award_code, desc1, note_no, " +
            "  audit_user_id, audit_date, audit_time_hr, audit_time_min, " +
            "  audit_time_sec, audit_time_hun" +
            ") VALUES (?,?,?,?, ?,?,?,?,?,?)",
            a.companyNo, a.awardCode, a.desc1, a.noteNo,
            s.user, s.date, s.hr, s.mi, s.sec, s.hun);
    }

    @Transactional
    public void update(Award a, String userId) {
        Stamp s = stamp(userId);
        jdbc.update(
            "UPDATE paawhed SET desc1=?, " +
            "  audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?, " +
            "  audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND award_code=?",
            a.desc1,
            s.user, s.date, s.hr, s.mi, s.sec, s.hun,
            a.companyNo, a.awardCode);
    }

    /**
     * Cascade-delete the award and all its child rows in one transaction —
     * mirrors the COBOL pattern where deleting an award also wipes its
     * paawjob and paawwcp children.
     */
    @Transactional
    public void delete(int companyNo, String awardCode) {
        jdbc.update("DELETE FROM paawwcp WHERE company_no=? AND award_code=?",
                    companyNo, awardCode);
        jdbc.update("DELETE FROM paawjob WHERE company_no=? AND award_code=?",
                    companyNo, awardCode);
        jdbc.update("DELETE FROM paawhed WHERE company_no=? AND award_code=?",
                    companyNo, awardCode);
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

    private static String nz(String s) { return s == null ? "" : s; }
}
