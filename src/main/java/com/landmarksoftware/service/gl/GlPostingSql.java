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

/**
 * SQL for GL journal posting (glgn06/glgn07 core) — posts glgnhed/glgnlin
 * into gltrx + glbal.
 *
 * <p>MVP: General journals only, straight posting. gltrx.source='GN',
 * trx_type='F'; glbal is DEBIT-positive (bal_NN += dr − cr, confirmed from
 * glgn07 UPDATE-BALANCES). Deferred: standing/cash posting, auto-reverse,
 * tax-clearing/cash-contra/FC derived rows, GLBAC analysis balances, the P&L
 * acct-0 aggregate row, recon/doc side-files, and un-posting.
 */
public final class GlPostingSql {

    private GlPostingSql() {}

    /** Balanced, unposted general journals for a company/year. */
    public static final String SELECT_UNPOSTED =
        "SELECT jnl_no, jnl_date, note_1, total_dr, total_cr FROM glgnhed " +
        "WHERE company_no=? AND jnl_type='G' AND yr_no=? " +
        "AND (posted_flag='N' OR posted_flag=' ' OR posted_flag='') " +
        "AND (jnl_status=' ' OR jnl_status='') " +
        "ORDER BY jnl_no";

    /** Balanced, active standing journals for a company/year (candidates to "Run"). */
    public static final String SELECT_RUNNABLE_STANDING =
        "SELECT jnl_no, jnl_date, note_1, total_dr, total_cr FROM glgnhed " +
        "WHERE company_no=? AND jnl_type='S' AND yr_no=? " +
        "AND (posted_flag='N' OR posted_flag=' ' OR posted_flag='') " +
        "AND (jnl_status=' ' OR jnl_status='') " +
        "ORDER BY jnl_no";

    /**
     * Run-instance counter for standing journals (gldates.last_aud_no_std_jnl)
     * — distinct from cpcoyco.gl_last_std_jnl_no, which numbers the templates
     * themselves. glgn06 GET-NEXT-STANDING-JNL-NO bumps this once per run to
     * get the jnl_no written to gltrx for that occurrence.
     */
    public static final String BUMP_STD_RUN_NO =
        "UPDATE gldates SET last_aud_no_std_jnl = last_aud_no_std_jnl + 1 WHERE company_no=? AND yr_no=?";
    public static final String READ_STD_RUN_NO =
        "SELECT last_aud_no_std_jnl FROM gldates WHERE company_no=? AND yr_no=?";

    /** Roll a standing template forward to its next occurrence (glgn09 UPDATE-STANDING-JNL, in-year case). */
    public static final String UPDATE_STANDING_DATE =
        "UPDATE glgnhed SET jnl_date=?, jnl_status=' ' " +
        "WHERE company_no=? AND jnl_type='S' AND yr_no=? AND jnl_no=?";

    /** Expire a standing template — past expiry, one-off, or crosses fiscal years (glgn09 "X" branch). */
    public static final String EXPIRE_STANDING =
        "UPDATE glgnhed SET jnl_status='X', posted_flag='Y' " +
        "WHERE company_no=? AND jnl_type='S' AND yr_no=? AND jnl_no=?";

    /** 4-digit calendar year for gltrx/glbal, from the journal's 2-digit yr_no. */
    public static final String SELECT_YEAR_NO =
        "SELECT year_no FROM gldates WHERE company_no=? AND yr_no=?";

    /** The 13 period start/end dates for period derivation. */
    public static final String SELECT_PERIODS =
        "SELECT period_start_01, period_start_02, period_start_03, period_start_04, " +
        "period_start_05, period_start_06, period_start_07, period_start_08, period_start_09, " +
        "period_start_10, period_start_11, period_start_12, period_start_13, " +
        "period_end_01, period_end_02, period_end_03, period_end_04, period_end_05, " +
        "period_end_06, period_end_07, period_end_08, period_end_09, period_end_10, " +
        "period_end_11, period_end_12, period_end_13 " +
        "FROM gldates WHERE company_no=? AND yr_no=?";

    /** Next gltrx seq_no within the full non-seq key (always unique). */
    public static final String NEXT_SEQ_NO =
        "SELECT COALESCE(MAX(seq_no),0)+1 FROM gltrx " +
        "WHERE company_no=? AND year_no=? AND acct_main_no=? AND acct_sub_no=? " +
        "AND trx_type=? AND jnl_date=?";

    /** gltrx columns in INSERT order (see GlPostingService.gltrxParams). */
    private static final String GLTRX_COLS =
        "company_no, year_no, source, jnl_no, reversal_ind, acct_main_no, acct_sub_no, " +
        "trx_type, jnl_date, seq_no, dr_amt, cr_amt, fc_dr_amt, fc_cr_amt, ref, note_no, " +
        "supplier_cust_no, doc_date, doc_type, retent_flag, doc_no, line_no, cm_bank_code, " +
        "cm_doc_type, cm_doc_no, cashbook_line_no, cm_trx_type, cm_trx_no, loc_no, " +
        "product_type, consol_flag, export_file_no, import_file_no, import_company_no, " +
        "import_function_id, recon_id, po_loc_no, po_no, po_line_no, tax_code, analysis_code, " +
        "audit_user_id, audit_date, audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun";

    public static final String INSERT_GLTRX =
        "INSERT INTO gltrx (" + GLTRX_COLS + ") VALUES (" + q(47) + ")";

    /** Mark the journal posted (glgn06 SET-JNL-STATUS + posted-flag). */
    public static final String MARK_POSTED =
        "UPDATE glgnhed SET posted_flag='Y', jnl_status='P', post_terminal_no=?, " +
        "audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?, audit_time_sec=? " +
        "WHERE company_no=? AND jnl_type='G' AND yr_no=? AND jnl_no=?";

    /** glbal period-balance update (DEBIT-positive). %02d = period, twice. */
    public static String updateGlbalBal(int period) {
        return String.format(
            "UPDATE glbal SET bal_%02d = bal_%02d + ? " +
            "WHERE company_no=? AND year_no=? AND acct_main_no=? AND acct_sub_no=?",
            period, period);
    }

    private static String q(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) { if (i > 0) sb.append(','); sb.append('?'); }
        return sb.toString();
    }
}
