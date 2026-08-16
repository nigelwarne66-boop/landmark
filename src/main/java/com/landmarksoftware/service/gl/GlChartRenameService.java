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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * CPCM05 — Set Account Names. Bulk-renames every sub-account under a range
 * of main accounts (SET-DESC / WS-NAME-CHANGE-OPTION), then optionally
 * cascades the new description into any report-writer line definition
 * ({@code glrpvel}) that points at exactly that one account
 * (start acct = end acct = the renamed account — GET-NEXT-LINE).
 *
 * <p>Three modes:
 * <ul>
 *   <li>{@code "M"} — every sub-account gets the same user-typed description/alpha/abbrev.</li>
 *   <li>{@code "S"} — each sub-account's description comes from its own {@code glcodes} entry
 *       (glcodes has no separate alpha/abbrev source, so those are derived from the
 *       resulting description when toggled — a documented simplification, not a COBOL field).</li>
 *   <li>{@code "C"} — the user-typed main description, concatenated with the glcodes description.</li>
 * </ul>
 * Each of desc1/alpha_code/abbrev_desc is independently toggled.
 */
@Service
public class GlChartRenameService {

    private final JdbcTemplate jdbc;

    public GlChartRenameService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** One sub-account this run would rename: current vs. new values. */
    public record Candidate(int acctMainNo, int acctSubNo, String oldDesc, String newDesc) {}

    public List<Candidate> preview(int companyNo, int startMain, int endMain, String option,
                                   String mainDesc, String mainAlpha, String mainAbbrev,
                                   boolean changeDesc, boolean changeAlpha, boolean changeAbbrev) {
        List<Candidate> out = new ArrayList<>();
        jdbc.query(
            "SELECT c.acct_main_no, c.acct_sub_no, c.desc1, COALESCE(g.desc1,'') AS code_desc " +
            "FROM glchart c LEFT JOIN glcodes g ON g.company_no=c.company_no AND g.sub_acct=c.acct_sub_no " +
            "WHERE c.company_no=? AND c.acct_sub_no>0 AND c.acct_main_no BETWEEN ? AND ? " +
            "ORDER BY c.acct_main_no, c.acct_sub_no",
            rs -> {
                int main = rs.getInt("acct_main_no"), sub = rs.getInt("acct_sub_no");
                String oldDesc = rs.getString("desc1");
                String codeDesc = rs.getString("code_desc");
                if (!changeDesc) return;   // desc drives the preview column; skip rows where nothing would change
                String newDesc = newDescFor(option, mainDesc, codeDesc);
                out.add(new Candidate(main, sub, oldDesc, newDesc));
            }, companyNo, startMain, endMain);
        return out;
    }

    /** Apply the rename to every sub-account under the range. Returns the number of rows changed. */
    @Transactional
    public int apply(int companyNo, int startMain, int endMain, String option,
                     String mainDesc, String mainAlpha, String mainAbbrev,
                     boolean changeDesc, boolean changeAlpha, boolean changeAbbrev,
                     boolean cascadeReportWriter, String userId) {
        List<int[]> accts = new ArrayList<>();
        List<String> codeDescs = new ArrayList<>();
        jdbc.query(
            "SELECT c.acct_main_no, c.acct_sub_no, COALESCE(g.desc1,'') AS code_desc " +
            "FROM glchart c LEFT JOIN glcodes g ON g.company_no=c.company_no AND g.sub_acct=c.acct_sub_no " +
            "WHERE c.company_no=? AND c.acct_sub_no>0 AND c.acct_main_no BETWEEN ? AND ?",
            rs -> { accts.add(new int[]{rs.getInt("acct_main_no"), rs.getInt("acct_sub_no")});
                    codeDescs.add(rs.getString("code_desc")); },
            companyNo, startMain, endMain);

        LocalTime now = LocalTime.now();
        int n = 0;
        for (int i = 0; i < accts.size(); i++) {
            int main = accts.get(i)[0], sub = accts.get(i)[1];
            String codeDesc = codeDescs.get(i);
            String newDesc = changeDesc ? newDescFor(option, mainDesc, codeDesc) : null;
            String base = newDesc != null ? newDesc : codeDesc;
            String newAlpha = changeAlpha ? ("M".equalsIgnoreCase(option) ? mainAlpha : base) : null;
            String newAbbrev = changeAbbrev ? trunc("M".equalsIgnoreCase(option) ? mainAbbrev : base, 20) : null;

            StringBuilder set = new StringBuilder();
            List<Object> params = new ArrayList<>();
            if (newDesc != null)   { set.append("desc1=?,");       params.add(trunc(newDesc, 35)); }
            if (newAlpha != null)  { set.append("alpha_code=?,");  params.add(trunc(newAlpha, 35)); }
            if (newAbbrev != null) { set.append("abbrev_desc=?,"); params.add(newAbbrev); }
            if (set.length() == 0) continue;
            set.append("audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?, audit_time_sec=?, audit_time_hun=?");
            params.add(trunc(userId, 15)); params.add(Date.valueOf(LocalDate.now()));
            params.add(now.getHour()); params.add(now.getMinute()); params.add(now.getSecond()); params.add(0);
            params.add(companyNo); params.add(main); params.add(sub);
            jdbc.update("UPDATE glchart SET " + set + " WHERE company_no=? AND acct_main_no=? AND acct_sub_no=?",
                params.toArray());
            n++;

            if (cascadeReportWriter && newDesc != null) {
                jdbc.update(
                    "UPDATE glrpvel SET line_desc=? WHERE company_no=? " +
                    "AND start_main_no=? AND start_sub_no=? AND end_main_no=? AND end_sub_no=?",
                    trunc(newDesc, 35), companyNo, main, sub, main, sub);
            }
        }
        return n;
    }

    private static String newDescFor(String option, String mainDesc, String codeDesc) {
        return switch (option == null ? "" : option.toUpperCase()) {
            case "M" -> mainDesc;
            case "C" -> (mainDesc == null ? "" : mainDesc.trim()) + " " + (codeDesc == null ? "" : codeDesc.trim());
            default  -> codeDesc;   // "S"
        };
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
