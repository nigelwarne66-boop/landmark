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

/** Centralised SQL for Chart of Accounts Maintenance (CPCM01 — glchart). */
public final class GlChartSql {

    private GlChartSql() {}

    private static final String COLUMNS =
        "acct_main_no, acct_sub_no, alpha_code, desc1, abbrev_desc, acct_type, dr_cr_ind, " +
        "fin_acct_flag, pl_bs_ind, for_curr_revalue_ind, cash_acct_flag, stat_acct_flag, " +
        "roll_stat_open_bals, def_analysis_code_ind, all_non_posting_flag, " +
        "counter_bal_main_no, counter_bal_sub_no, consol_main_no, consol_sub_no, dist_type, dist_code, " +
        "alloc_main_no, alloc_sub_no, cost_alloc_acct_type, alloc_method, prepayment_acct_flag, " +
        "gst_perc_claimable, compress_code, use_recon_ids_flag, posting_flag, sub_coy_no, " +
        "cash_acct_bank_no, analysis_code_ind, guardian_export_flag, " +
        "last_jnl_date, last_recon_no, audit_user_id, audit_date, " +
        "audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun";

    public static final String SELECT_ONE =
        "SELECT " + COLUMNS + " FROM glchart WHERE company_no=? AND acct_main_no=? AND acct_sub_no=?";

    public static final String SELECT_MAIN_ACCOUNTS =
        "SELECT " + COLUMNS + " FROM glchart WHERE company_no=? AND acct_sub_no=0 ORDER BY acct_main_no";

    public static final String SELECT_SUB_ACCOUNTS =
        "SELECT " + COLUMNS + " FROM glchart WHERE company_no=? AND acct_main_no=? AND acct_sub_no>0 ORDER BY acct_sub_no";

    public static final String COUNT_ONE =
        "SELECT COUNT(*) FROM glchart WHERE company_no=? AND acct_main_no=? AND acct_sub_no=?";

    /** Any journal ever posted to the main account or any of its sub-accounts (CHECK-FOR-JNLS-POSTED). */
    public static final String COUNT_POSTED =
        "SELECT COUNT(*) FROM glchart WHERE company_no=? AND acct_main_no=? AND last_jnl_date > '1899-12-31'";

    public static final String COUNT_POSTED_ONE =
        "SELECT COUNT(*) FROM glchart WHERE company_no=? AND acct_main_no=? AND acct_sub_no=? AND last_jnl_date > '1899-12-31'";

    /**
     * Full 52-column INSERT — new rows need valid values for the alternate-key
     * mirror columns (alpha/alt account pointers) and housekeeping columns
     * (sub_acct_no, commit_acct_flag, tr_bal_sub_total_flag, note_no) that
     * neither CPCM01 screen exposes; these self-reference / default per
     * GlChartService.insertParams.
     */
    public static final String INSERT =
        "INSERT INTO glchart (" +
        "company_no, sub_acct_no, acct_main_no, acct_sub_no, alpha_code, alpha_acct_main_no, alpha_acct_sub_no, " +
        "alt_gl_code, alt_acct_main_no, alt_acct_sub_no, fin_acct_flag, stat_acct_flag, cash_acct_flag, " +
        "commit_acct_flag, pl_bs_ind, dr_cr_ind, roll_stat_open_bals, for_curr_revalue_ind, " +
        "tr_bal_sub_total_flag, acct_type, all_non_posting_flag, def_analysis_code_ind, desc1, abbrev_desc, " +
        "consol_main_no, consol_sub_no, counter_bal_main_no, counter_bal_sub_no, dist_type, alloc_main_no, " +
        "alloc_sub_no, alloc_method, dist_code, compress_code, last_jnl_date, sub_coy_no, posting_flag, " +
        "cash_acct_bank_no, prepayment_acct_flag, last_recon_no, gst_perc_claimable, use_recon_ids_flag, " +
        "guardian_export_flag, cost_alloc_acct_type, analysis_code_ind, note_no, audit_user_id, audit_date, " +
        "audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun" +
        ") VALUES (" + q(52) + ")";

    /** Main-account editable fields (S1). */
    public static final String UPDATE_MAIN =
        "UPDATE glchart SET alpha_code=?, desc1=?, abbrev_desc=?, acct_type=?, dr_cr_ind=?, " +
        "fin_acct_flag=?, pl_bs_ind=?, for_curr_revalue_ind=?, cash_acct_flag=?, stat_acct_flag=?, " +
        "roll_stat_open_bals=?, def_analysis_code_ind=?, all_non_posting_flag=?, " +
        "audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?, audit_time_sec=?, audit_time_hun=? " +
        "WHERE company_no=? AND acct_main_no=? AND acct_sub_no=0";

    /** Sub-account editable fields (S2) — classification flags (fin/pl_bs/dr_cr/...) are set once at insert, not re-edited. */
    public static final String UPDATE_SUB =
        "UPDATE glchart SET alpha_code=?, desc1=?, abbrev_desc=?, " +
        "counter_bal_main_no=?, counter_bal_sub_no=?, consol_main_no=?, consol_sub_no=?, dist_type=?, dist_code=?, " +
        "alloc_main_no=?, alloc_sub_no=?, cost_alloc_acct_type=?, alloc_method=?, prepayment_acct_flag=?, " +
        "gst_perc_claimable=?, compress_code=?, use_recon_ids_flag=?, posting_flag=?, sub_coy_no=?, " +
        "cash_acct_bank_no=?, analysis_code_ind=?, guardian_export_flag=?, " +
        "audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?, audit_time_sec=?, audit_time_hun=? " +
        "WHERE company_no=? AND acct_main_no=? AND acct_sub_no=?";

    /**
     * Full-column update touching both main- and sub-account field sets in
     * one pass — used only by CPCM09 (cross-company duplicate's "update
     * account details" mode). The interactive S1/S2 editors deliberately
     * stay scoped to UPDATE_MAIN/UPDATE_SUB so editing a main account never
     * clobbers a sub-account's distribution setup and vice versa.
     */
    public static final String UPDATE_FULL =
        "UPDATE glchart SET alpha_code=?, desc1=?, abbrev_desc=?, acct_type=?, dr_cr_ind=?, " +
        "fin_acct_flag=?, pl_bs_ind=?, for_curr_revalue_ind=?, cash_acct_flag=?, stat_acct_flag=?, " +
        "roll_stat_open_bals=?, def_analysis_code_ind=?, all_non_posting_flag=?, " +
        "counter_bal_main_no=?, counter_bal_sub_no=?, consol_main_no=?, consol_sub_no=?, dist_type=?, dist_code=?, " +
        "alloc_main_no=?, alloc_sub_no=?, cost_alloc_acct_type=?, alloc_method=?, prepayment_acct_flag=?, " +
        "gst_perc_claimable=?, compress_code=?, use_recon_ids_flag=?, posting_flag=?, sub_coy_no=?, " +
        "cash_acct_bank_no=?, analysis_code_ind=?, guardian_export_flag=?, " +
        "audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?, audit_time_sec=?, audit_time_hun=? " +
        "WHERE company_no=? AND acct_main_no=? AND acct_sub_no=?";

    public static final String DELETE_ONE =
        "DELETE FROM glchart WHERE company_no=? AND acct_main_no=? AND acct_sub_no=?";

    public static final String DELETE_ALL_SUB =
        "DELETE FROM glchart WHERE company_no=? AND acct_main_no=?";

    private static String q(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) { if (i > 0) sb.append(','); sb.append('?'); }
        return sb.toString();
    }
}
