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
import java.util.List;

/**
 * CPCM09 — Duplicate Chart of Accounts. Copies a main/sub account range from
 * one company into a DIFFERENT company (cross-company replication, per
 * CHECK-FROM-COMPANY-NO / CHECK-INTO-COMPANY-NO + LINKAGE-DATA-FILES-DIR
 * redirection) — not a same-company clone-to-new-number.
 *
 * <p>Per account, either the full setup is overwritten in the target
 * ({@code updateAcctDetails}) or just the description fields
 * ({@code updateDescOnly}) — mirrors {@code WS-UPDATE-ACCT-DETAILS} /
 * {@code WS-UPDATE-DESC}. Only {@code glchart} master fields are touched —
 * no {@code glbal} balance history is duplicated.
 */
@Service
public class GlChartDuplicateService {

    private final JdbcTemplate jdbc;
    private final GlChartService chart;

    public GlChartDuplicateService(JdbcTemplate jdbc, GlChartService chart) {
        this.jdbc = jdbc;
        this.chart = chart;
    }

    /** One account in the source range, and whether it already exists in the target company. */
    public record Candidate(int acctMainNo, int acctSubNo, String desc1, boolean existsInTarget) {}

    private static final String RANGE_SQL =
        "WHERE company_no=? AND acct_main_no BETWEEN ? AND ? AND acct_sub_no BETWEEN ? AND ? " +
        "ORDER BY acct_main_no, acct_sub_no";

    public List<Candidate> preview(int fromCompanyNo, int toCompanyNo,
                                   int startMain, int endMain, int startSub, int endSub) {
        List<Candidate> out = new ArrayList<>();
        jdbc.query(
            "SELECT acct_main_no, acct_sub_no, desc1 FROM glchart " + RANGE_SQL,
            rs -> {
                int main = rs.getInt("acct_main_no"), sub = rs.getInt("acct_sub_no");
                out.add(new Candidate(main, sub, rs.getString("desc1"), chart.exists(toCompanyNo, main, sub)));
            },
            fromCompanyNo, startMain, endMain, startSub, endSub);
        return out;
    }

    /**
     * Copy the range into the target company. Returns the number of accounts written.
     *
     * @param updateAcctDetails full setup overwrite/insert when {@code true}
     * @param updateDescOnly    description-only overwrite when {@code true} and details is {@code false}
     * @param newConsolSubAcct  optional override for the copied row's consol_sub_no (SET-CONSOL-SUB-ACCT)
     */
    @Transactional
    public int apply(int fromCompanyNo, int toCompanyNo, int startMain, int endMain, int startSub, int endSub,
                     boolean updateAcctDetails, boolean updateDescOnly, Integer newConsolSubAcct, String userId) {
        List<GlChartAccount> src = jdbc.query(
            "SELECT * FROM glchart " + RANGE_SQL,
            (rs, i) -> readAccount(rs), fromCompanyNo, startMain, endMain, startSub, endSub);
        int n = 0;
        for (GlChartAccount a : src) {
            boolean existsInTarget = chart.exists(toCompanyNo, a.acctMainNo, a.acctSubNo);
            if (newConsolSubAcct != null) a.consolSubNo = newConsolSubAcct;
            if (!existsInTarget) {
                chart.saveRaw(toCompanyNo, a, true, userId);
                n++;
            } else if (updateAcctDetails) {
                chart.saveRaw(toCompanyNo, a, false, userId);
                n++;
            } else if (updateDescOnly) {
                jdbc.update(
                    "UPDATE glchart SET desc1=?, abbrev_desc=?, alpha_code=? " +
                    "WHERE company_no=? AND acct_main_no=? AND acct_sub_no=?",
                    a.desc1, a.abbrevDesc, a.alphaCode, toCompanyNo, a.acctMainNo, a.acctSubNo);
                n++;
            }
        }
        return n;
    }

    private static GlChartAccount readAccount(java.sql.ResultSet rs) throws java.sql.SQLException {
        GlChartAccount a = new GlChartAccount();
        a.acctMainNo = rs.getInt("acct_main_no");
        a.acctSubNo = rs.getInt("acct_sub_no");
        a.alphaCode = rs.getString("alpha_code");
        a.desc1 = rs.getString("desc1");
        a.abbrevDesc = rs.getString("abbrev_desc");
        a.acctType = rs.getString("acct_type");
        a.drCrInd = rs.getString("dr_cr_ind");
        a.finAcctFlag = rs.getString("fin_acct_flag");
        a.plBsInd = rs.getString("pl_bs_ind");
        a.forCurrRevalueInd = rs.getString("for_curr_revalue_ind");
        a.cashAcctFlag = rs.getString("cash_acct_flag");
        a.statAcctFlag = rs.getString("stat_acct_flag");
        a.rollStatOpenBals = rs.getString("roll_stat_open_bals");
        a.defAnalysisCodeInd = rs.getString("def_analysis_code_ind");
        a.allNonPostingFlag = rs.getString("all_non_posting_flag");
        a.counterBalMainNo = rs.getInt("counter_bal_main_no");
        a.counterBalSubNo = rs.getInt("counter_bal_sub_no");
        a.consolMainNo = rs.getInt("consol_main_no");
        a.consolSubNo = rs.getInt("consol_sub_no");
        a.distType = rs.getString("dist_type");
        a.distCode = rs.getString("dist_code");
        a.allocMainNo = rs.getInt("alloc_main_no");
        a.allocSubNo = rs.getInt("alloc_sub_no");
        a.costAllocAcctType = rs.getString("cost_alloc_acct_type");
        a.allocMethod = rs.getString("alloc_method");
        a.prepaymentAcctFlag = rs.getString("prepayment_acct_flag");
        a.gstPercClaimable = rs.getBigDecimal("gst_perc_claimable");
        a.compressCode = rs.getString("compress_code");
        a.useReconIdsFlag = rs.getString("use_recon_ids_flag");
        a.postingFlag = rs.getString("posting_flag");
        a.subCoyNo = rs.getInt("sub_coy_no");
        a.cashAcctBankNo = rs.getInt("cash_acct_bank_no");
        a.analysisCodeInd = rs.getString("analysis_code_ind");
        a.guardianExportFlag = rs.getString("guardian_export_flag");
        return a;
    }
}
