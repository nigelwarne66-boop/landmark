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
package com.landmarksoftware.service.gl;

import com.landmarksoftware.model.GlCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * GLNM01 — Sub Account [code] Maintenance (glcodes). Flat company-wide
 * lookup of 4-digit sub-account codes and names — no delete guard in COBOL
 * (pure code-table delete), so none here either.
 */
@Service
public class GlCodesService {

    private final JdbcTemplate jdbc;

    public GlCodesService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<GlCode> findAll(int companyNo) {
        return jdbc.query(
            "SELECT sub_acct, desc1, audit_user_id FROM glcodes WHERE company_no=? ORDER BY sub_acct",
            (rs, i) -> {
                GlCode c = new GlCode();
                c.subAcct = rs.getInt("sub_acct");
                c.desc1 = rs.getString("desc1");
                c.auditUserId = rs.getString("audit_user_id");
                return c;
            }, companyNo);
    }

    public boolean exists(int companyNo, int subAcct) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM glcodes WHERE company_no=? AND sub_acct=?", Integer.class, companyNo, subAcct);
        return n != null && n > 0;
    }

    /** Description for a sub-account code, or "" if not on file (CPCM01 S2's F5 lookup). */
    public String descOf(int companyNo, int subAcct) {
        List<String> r = jdbc.query(
            "SELECT desc1 FROM glcodes WHERE company_no=? AND sub_acct=?",
            (rs, i) -> rs.getString("desc1"), companyNo, subAcct);
        return r.isEmpty() ? "" : r.get(0);
    }

    @Transactional
    public void save(int companyNo, GlCode c, boolean isNew, String userId) {
        LocalTime now = LocalTime.now();
        if (isNew) {
            jdbc.update(
                "INSERT INTO glcodes (company_no, sub_acct, desc1, note_no, audit_user_id, audit_date, " +
                "audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun) VALUES (?,?,?,0,?,?,?,?,?,?)",
                companyNo, c.subAcct, trunc(c.desc1, 35),
                trunc(userId, 15), Date.valueOf(LocalDate.now()), now.getHour(), now.getMinute(), now.getSecond(), 0);
        } else {
            jdbc.update(
                "UPDATE glcodes SET desc1=?, audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?, " +
                "audit_time_sec=?, audit_time_hun=? WHERE company_no=? AND sub_acct=?",
                trunc(c.desc1, 35), trunc(userId, 15), Date.valueOf(LocalDate.now()),
                now.getHour(), now.getMinute(), now.getSecond(), 0, companyNo, c.subAcct);
        }
    }

    @Transactional
    public void delete(int companyNo, int subAcct) {
        jdbc.update("DELETE FROM glcodes WHERE company_no=? AND sub_acct=?", companyNo, subAcct);
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
