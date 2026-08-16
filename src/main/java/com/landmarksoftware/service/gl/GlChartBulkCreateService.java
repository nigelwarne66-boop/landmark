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

import com.landmarksoftware.model.GlChartAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CPCM10 — Create Sub Accounts. Fans out one main-account range into many
 * new sub-account rows, driven by either:
 * <ul>
 *   <li>{@code "G"} — every sub-account code that's a member of a
 *       report-group in the given group-code range (glgroup, CREATE-RPT-GROUP-RECORD)</li>
 *   <li>{@code "S"} — every sub-account code in the given code range (glcodes, CREATE-SUB-ACCT-RECORD)</li>
 * </ul>
 * For each (main account × resolved sub-account code) combination not
 * already on file, a new glchart sub-account row is created — description
 * from glcodes when requested (SET-DESC / WS-MAIN-SUB-DESC-IND = "S"),
 * classification flags inherited from the parent main account (same as a
 * normal single Add — see GlChartService.saveSubAccount).
 */
@Service
public class GlChartBulkCreateService {

    private final JdbcTemplate jdbc;
    private final GlChartService chart;

    public GlChartBulkCreateService(JdbcTemplate jdbc, GlChartService chart) {
        this.jdbc = jdbc;
        this.chart = chart;
    }

    /** One (main account, sub-account code) combination this run would create. */
    public record Candidate(int acctMainNo, int acctSubNo, String desc1, boolean alreadyExists) {}

    /** Resolve the sub-account codes for mode "G" (report-group range) or "S" (code range). */
    public List<int[]> resolveSubCodes(int companyNo, String mode, String startCode, String endCode) {
        Set<Integer> codes = new LinkedHashSet<>();
        if ("G".equalsIgnoreCase(mode)) {
            codes.addAll(jdbc.query(
                "SELECT DISTINCT sub_acct FROM glgroup WHERE company_no=? AND group_code BETWEEN ? AND ? ORDER BY sub_acct",
                (rs, i) -> rs.getInt("sub_acct"), companyNo, startCode, endCode));
        } else {
            Integer sc = parseIntOrNull(startCode), ec = parseIntOrNull(endCode);
            codes.addAll(jdbc.query(
                "SELECT sub_acct FROM glcodes WHERE company_no=? AND sub_acct BETWEEN ? AND ? ORDER BY sub_acct",
                (rs, i) -> rs.getInt("sub_acct"), companyNo, sc == null ? 0 : sc, ec == null ? 9999 : ec));
        }
        List<int[]> out = new ArrayList<>();
        for (int c : codes) out.add(new int[]{c});
        return out;
    }

    public List<Candidate> preview(int companyNo, int startMain, int endMain,
                                   String mode, String startCode, String endCode, boolean useSubCodeDesc) {
        List<Integer> mains = jdbc.query(
            "SELECT acct_main_no FROM glchart WHERE company_no=? AND acct_sub_no=0 AND acct_main_no BETWEEN ? AND ? ORDER BY acct_main_no",
            (rs, i) -> rs.getInt("acct_main_no"), companyNo, startMain, endMain);
        List<int[]> subCodes = resolveSubCodes(companyNo, mode, startCode, endCode);
        List<Candidate> out = new ArrayList<>();
        for (int main : mains) {
            for (int[] sc : subCodes) {
                int sub = sc[0];
                if (sub == 0) continue;
                boolean exists = chart.exists(companyNo, main, sub);
                String desc = useSubCodeDesc ? jdbc.query(
                    "SELECT desc1 FROM glcodes WHERE company_no=? AND sub_acct=?",
                    (rs, i) -> rs.getString("desc1"), companyNo, sub).stream().findFirst().orElse("") : "";
                out.add(new Candidate(main, sub, desc, exists));
            }
        }
        return out;
    }

    /** Create every not-already-existing candidate. Returns the number of accounts created. */
    @Transactional
    public int apply(int companyNo, List<Candidate> candidates, String postingFlag, String userId) {
        int n = 0;
        for (Candidate c : candidates) {
            if (c.alreadyExists()) continue;
            GlChartAccount a = new GlChartAccount();
            a.acctMainNo = c.acctMainNo();
            a.acctSubNo = c.acctSubNo();
            a.desc1 = c.desc1();
            a.postingFlag = postingFlag == null ? "" : postingFlag;
            chart.saveSubAccount(companyNo, a, true, userId);
            n++;
        }
        return n;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
