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

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * CPCM01 — Chart of Accounts Maintenance (glchart: main accounts, acct_sub_no=0,
 * and sub-accounts, acct_sub_no&gt;0, in the same table/row shape).
 *
 * <p>A new sub-account inherits its classification flags (fin_acct_flag,
 * pl_bs_ind, dr_cr_ind, cash_acct_flag, stat_acct_flag, all_non_posting_flag,
 * roll_stat_open_bals, for_curr_revalue_ind, def_analysis_code_ind, acct_type)
 * from its parent main account at creation — CPCM01's S2 sub-account screen
 * never exposes those fields, so the sub-account can only take on the same
 * nature as the account it subdivides.
 */
@Service
public class GlChartService {

    private final JdbcTemplate jdbc;

    public GlChartService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ── Read ─────────────────────────────────────────────────────────────

    public List<GlChartAccount> findMainAccounts(int companyNo) {
        return jdbc.query(GlChartSql.SELECT_MAIN_ACCOUNTS, GlChartService::map, companyNo);
    }

    public List<GlChartAccount> findSubAccounts(int companyNo, int acctMainNo) {
        return jdbc.query(GlChartSql.SELECT_SUB_ACCOUNTS, GlChartService::map, companyNo, acctMainNo);
    }

    public GlChartAccount getAccount(int companyNo, int acctMainNo, int acctSubNo) {
        List<GlChartAccount> r = jdbc.query(GlChartSql.SELECT_ONE, GlChartService::map, companyNo, acctMainNo, acctSubNo);
        return r.isEmpty() ? null : r.get(0);
    }

    public boolean exists(int companyNo, int acctMainNo, int acctSubNo) {
        Integer n = jdbc.queryForObject(GlChartSql.COUNT_ONE, Integer.class, companyNo, acctMainNo, acctSubNo);
        return n != null && n > 0;
    }

    /** CHECK-FOR-JNLS-POSTED — any journal ever posted to the main account or any of its sub-accounts. */
    public boolean hasPostedJournals(int companyNo, int acctMainNo) {
        Integer n = jdbc.queryForObject(GlChartSql.COUNT_POSTED, Integer.class, companyNo, acctMainNo);
        return n != null && n > 0;
    }

    public boolean hasPostedJournals(int companyNo, int acctMainNo, int acctSubNo) {
        Integer n = jdbc.queryForObject(GlChartSql.COUNT_POSTED_ONE, Integer.class, companyNo, acctMainNo, acctSubNo);
        return n != null && n > 0;
    }

    // ── Write ────────────────────────────────────────────────────────────

    /** Add or update a main account (acct_sub_no=0). */
    @Transactional
    public void saveMainAccount(int companyNo, GlChartAccount a, boolean isNew, String userId) {
        a.acctSubNo = 0;
        if (isNew) {
            jdbc.update(GlChartSql.INSERT, insertParams(companyNo, a, userId));
        } else {
            LocalTime now = LocalTime.now();
            jdbc.update(GlChartSql.UPDATE_MAIN,
                a.alphaCode, a.desc1, a.abbrevDesc, a.acctType, a.drCrInd,
                a.finAcctFlag, a.plBsInd, a.forCurrRevalueInd, a.cashAcctFlag, a.statAcctFlag,
                a.rollStatOpenBals, a.defAnalysisCodeInd, a.allNonPostingFlag,
                trunc(userId, 15), Date.valueOf(LocalDate.now()), now.getHour(), now.getMinute(), now.getSecond(), 0,
                companyNo, a.acctMainNo);
        }
    }

    /**
     * Add or update a sub-account. On add, classification flags are copied
     * from the parent main account (must already exist).
     */
    @Transactional
    public void saveSubAccount(int companyNo, GlChartAccount a, boolean isNew, String userId) {
        if (isNew) {
            GlChartAccount main = getAccount(companyNo, a.acctMainNo, 0);
            if (main == null) throw new IllegalStateException("Main account " + a.acctMainNo + " does not exist.");
            a.acctType = main.acctType; a.drCrInd = main.drCrInd; a.finAcctFlag = main.finAcctFlag;
            a.plBsInd = main.plBsInd; a.forCurrRevalueInd = main.forCurrRevalueInd; a.cashAcctFlag = main.cashAcctFlag;
            a.statAcctFlag = main.statAcctFlag; a.rollStatOpenBals = main.rollStatOpenBals;
            a.defAnalysisCodeInd = main.defAnalysisCodeInd; a.allNonPostingFlag = main.allNonPostingFlag;
            jdbc.update(GlChartSql.INSERT, insertParams(companyNo, a, userId));
        } else {
            LocalTime now = LocalTime.now();
            jdbc.update(GlChartSql.UPDATE_SUB,
                a.alphaCode, a.desc1, a.abbrevDesc,
                a.counterBalMainNo, a.counterBalSubNo, a.consolMainNo, a.consolSubNo, a.distType, a.distCode,
                a.allocMainNo, a.allocSubNo, a.costAllocAcctType, a.allocMethod, a.prepaymentAcctFlag,
                nz(a.gstPercClaimable), a.compressCode, a.useReconIdsFlag, a.postingFlag, a.subCoyNo,
                a.cashAcctBankNo, a.analysisCodeInd, a.guardianExportFlag,
                trunc(userId, 15), Date.valueOf(LocalDate.now()), now.getHour(), now.getMinute(), now.getSecond(), 0,
                companyNo, a.acctMainNo, a.acctSubNo);
        }
    }

    /**
     * Raw insert/update using every field on {@code a} exactly as given —
     * no field-scoping (unlike saveMainAccount/saveSubAccount) and no
     * parent-inheritance on insert. For CPCM09 cross-company duplication,
     * where the source row is already fully populated.
     */
    @Transactional
    public void saveRaw(int companyNo, GlChartAccount a, boolean isNew, String userId) {
        if (isNew) {
            jdbc.update(GlChartSql.INSERT, insertParams(companyNo, a, userId));
        } else {
            LocalTime now = LocalTime.now();
            jdbc.update(GlChartSql.UPDATE_FULL,
                a.alphaCode, a.desc1, a.abbrevDesc, a.acctType, a.drCrInd,
                a.finAcctFlag, a.plBsInd, a.forCurrRevalueInd, a.cashAcctFlag, a.statAcctFlag,
                a.rollStatOpenBals, a.defAnalysisCodeInd, a.allNonPostingFlag,
                a.counterBalMainNo, a.counterBalSubNo, a.consolMainNo, a.consolSubNo, a.distType, a.distCode,
                a.allocMainNo, a.allocSubNo, a.costAllocAcctType, a.allocMethod, a.prepaymentAcctFlag,
                nz(a.gstPercClaimable), a.compressCode, a.useReconIdsFlag, a.postingFlag, a.subCoyNo,
                a.cashAcctBankNo, a.analysisCodeInd, a.guardianExportFlag,
                trunc(userId, 15), Date.valueOf(LocalDate.now()), now.getHour(), now.getMinute(), now.getSecond(), 0,
                companyNo, a.acctMainNo, a.acctSubNo);
        }
    }

    /** Delete one sub-account. Blocked if journals have posted to it. */
    @Transactional
    public void deleteSubAccount(int companyNo, int acctMainNo, int acctSubNo) {
        if (acctSubNo == 0) throw new IllegalStateException("Use deleteMainAccount for the main account.");
        if (hasPostedJournals(companyNo, acctMainNo, acctSubNo))
            throw new IllegalStateException("Journals have posted to this account — cannot delete.");
        jdbc.update(GlChartSql.DELETE_ONE, companyNo, acctMainNo, acctSubNo);
    }

    /**
     * Delete a main account and cascade-delete every sub-account under it
     * (DELETE-ALL-SUB-ACCTS). Blocked if journals have posted anywhere in
     * the account (main or any sub) — mirrors COBOL's whole-account guard.
     */
    @Transactional
    public void deleteMainAccount(int companyNo, int acctMainNo) {
        if (hasPostedJournals(companyNo, acctMainNo))
            throw new IllegalStateException("Journals have posted to this account or its sub-accounts — cannot delete.");
        jdbc.update(GlChartSql.DELETE_ALL_SUB, companyNo, acctMainNo);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Object[] insertParams(int companyNo, GlChartAccount a, String userId) {
        LocalTime now = LocalTime.now();
        return new Object[] {
            companyNo, a.acctSubNo, a.acctMainNo, a.acctSubNo,          // company_no, sub_acct_no, acct_main_no, acct_sub_no
            trunc(a.alphaCode, 35), a.acctMainNo, a.acctSubNo,          // alpha_code, alpha_acct_main_no, alpha_acct_sub_no (self-ref)
            "", 0, 0,                                                  // alt_gl_code, alt_acct_main_no, alt_acct_sub_no (unused)
            nvl(a.finAcctFlag), nvl(a.statAcctFlag), nvl(a.cashAcctFlag),
            "N",                                                       // commit_acct_flag (not exposed)
            nvl(a.plBsInd, "B"), nvl(a.drCrInd, "D"), nvl(a.rollStatOpenBals), nvl(a.forCurrRevalueInd),
            "N",                                                       // tr_bal_sub_total_flag (not exposed)
            trunc(nvl(a.acctType), 4), nvl(a.allNonPostingFlag), nvl(a.defAnalysisCodeInd),
            trunc(a.desc1, 35), trunc(a.abbrevDesc, 20),
            a.consolMainNo, a.consolSubNo, a.counterBalMainNo, a.counterBalSubNo,
            nvl(a.distType), a.allocMainNo, a.allocSubNo, nvl(a.allocMethod),
            trunc(nvl(a.distCode), 6), nvl(a.compressCode),
            Date.valueOf(GlChartAccount.DATE_ZERO),                    // last_jnl_date
            a.subCoyNo, nvl(a.postingFlag, ""), a.cashAcctBankNo, nvl(a.prepaymentAcctFlag),
            0,                                                         // last_recon_no
            nz(a.gstPercClaimable), nvl(a.useReconIdsFlag), nvl(a.guardianExportFlag),
            nvl(a.costAllocAcctType), nvl(a.analysisCodeInd),
            0L,                                                        // note_no
            trunc(userId, 15), Date.valueOf(LocalDate.now()), now.getHour(), now.getMinute(), now.getSecond(), 0
        };
    }

    private static GlChartAccount map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
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
        a.gstPercClaimable = nz(rs.getBigDecimal("gst_perc_claimable"));
        a.compressCode = rs.getString("compress_code");
        a.useReconIdsFlag = rs.getString("use_recon_ids_flag");
        a.postingFlag = rs.getString("posting_flag");
        a.subCoyNo = rs.getInt("sub_coy_no");
        a.cashAcctBankNo = rs.getInt("cash_acct_bank_no");
        a.analysisCodeInd = rs.getString("analysis_code_ind");
        a.guardianExportFlag = rs.getString("guardian_export_flag");
        a.lastJnlDate = toLocal(rs.getDate("last_jnl_date"));
        a.lastReconNo = rs.getInt("last_recon_no");
        a.auditUserId = rs.getString("audit_user_id");
        a.auditDate = toLocal(rs.getDate("audit_date"));
        return a;
    }

    private static LocalDate toLocal(Date d) { return d == null ? null : d.toLocalDate(); }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String nvl(String s) { return nvl(s, "N"); }
    private static String nvl(String s, String dflt) { return (s == null || s.isEmpty()) ? dflt : s; }
    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
