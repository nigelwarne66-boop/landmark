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
 * Centralised SQL for GL journal entry (GLGN01 — glgnhed / glgnlin).
 *
 * <p>glgnhed and glgnlin are all-NOT-NULL, so the INSERTs list every column;
 * {@link GlJournalService} defaults the ones this MVP does not yet expose
 * (GST/tax, foreign currency, counter-balancing, analysis codes).
 */
public final class GlJournalSql {

    private GlJournalSql() {}

    // ── Read ─────────────────────────────────────────────────────────────

    /**
     * Draft headers for a company/year/type, newest first. Excludes
     * committed and cancelled journals ({@code posted_flag='Y'} in both
     * cases — glgn01.pl CANCEL-THIS-JOURNAL sets posted_flag='Y' on cancel
     * the same as glgn09 does on commit).
     *
     * <p>Mirrors COBOL GLGN01P1-ENTER-DATA: it browses GLGNHED-POSTED-KEY
     * (posted_jnl_type, posted_flag, jnl_type, yr_no, jnl_no) with
     * posted_flag fixed to SPACES on <em>both</em> the start and end key —
     * since 'Y' sorts after SPACES in the collating sequence, any
     * posted_flag='Y' record falls outside the browse range and the P1
     * listbox never sees it.
     */
    public static final String SELECT_HEADERS =
        "SELECT jnl_type, yr_no, jnl_no, jnl_date, auto_reverse_flag, note_1, note_2, " +
        "       jnl_status, total_dr, total_cr, post_freq, expiry_date " +
        "FROM glgnhed WHERE company_no=? AND jnl_type=? AND yr_no=? AND posted_flag<>'Y' " +
        "ORDER BY jnl_no DESC";

    /** Lines for one journal, in line order — left-joins glchart for the display-only account description. */
    public static final String SELECT_LINES =
        "SELECT gl.line_no, gl.acct_main_no, gl.acct_sub_no, gl.dr_amt, gl.cr_amt, gl.ref, gl.zero_amt_flag, gc.desc1 " +
        "FROM glgnlin gl LEFT JOIN glchart gc " +
        "  ON gc.company_no=gl.company_no AND gc.acct_main_no=gl.acct_main_no AND gc.acct_sub_no=gl.acct_sub_no " +
        "WHERE gl.company_no=? AND gl.jnl_type=? AND gl.yr_no=? AND gl.jnl_no=? ORDER BY gl.line_no";

    public static final String COUNT_HEADER =
        "SELECT COUNT(*) FROM glgnhed WHERE company_no=? AND jnl_type=? AND yr_no=? AND jnl_no=?";

    /** Account validation (glchart). */
    public static final String SELECT_ACCOUNT =
        "SELECT desc1, fin_acct_flag, cash_acct_flag, posting_flag, all_non_posting_flag, sub_coy_no " +
        "FROM glchart WHERE company_no=? AND acct_main_no=? AND acct_sub_no=?";

    /** Postable accounts for the account picker (GLCHIND-style browse). */
    public static final String LIST_POSTABLE_ACCOUNTS =
        "SELECT acct_main_no, acct_sub_no, desc1 FROM glchart " +
        "WHERE company_no=? AND fin_acct_flag='Y' AND all_non_posting_flag<>'Y' " +
        "AND posting_flag NOT IN ('N','S') ORDER BY acct_main_no, acct_sub_no";

    /** Year/period info for date validation. */
    public static final String SELECT_GLDATES =
        "SELECT yr_end_status, yr_start_date, yr_end_date FROM gldates WHERE company_no=? AND yr_no=?";

    // ── Journal-number allocation (serialized counters, per COBOL) ───────
    // General: gldates.last_aud_no_gen_jnl (per company + year) — GET-NEXT-JNL-NO.
    // Standing: cpcoyco.gl_last_std_jnl_no (per company, cross-year) —
    //           GET-NEXT-STANDING-JNL-NO.
    public static final String BUMP_GEN_JNL_NO =
        "UPDATE gldates SET last_aud_no_gen_jnl = last_aud_no_gen_jnl + 1 WHERE company_no=? AND yr_no=?";
    public static final String READ_GEN_JNL_NO =
        "SELECT last_aud_no_gen_jnl FROM gldates WHERE company_no=? AND yr_no=?";
    public static final String BUMP_STD_JNL_NO =
        "UPDATE cpcoyco SET gl_last_std_jnl_no = gl_last_std_jnl_no + 1 WHERE company_no=?";
    public static final String READ_STD_JNL_NO =
        "SELECT gl_last_std_jnl_no FROM cpcoyco WHERE company_no=?";

    // ── Write ────────────────────────────────────────────────────────────

    /** All 40 glgnhed columns, in INSERT order (see GlJournalService.headerParams). */
    private static final String HEADER_COLS =
        "company_no, posted_jnl_type, posted_flag, jnl_type, yr_no, jnl_no, jnl_date, " +
        "auto_reverse_flag, total_dr, total_cr, jnl_status, for_curr_code, for_curr_rate, " +
        "for_curr_maths_ind, local_parent_ind, sub_coy_jnl_flag, cash_acct_jnl_flag, " +
        "import_batch_no, import_company_no, note_1, note_2, gst_flag, default_tax_code, " +
        "tax_pricing_ind, post_terminal_no, post_freq, last_aud_source, last_aud_jnl_no, " +
        "last_aud_reversal_ind, expiry_date, total_fc_dr, total_fc_cr, total_profit, " +
        "total_fc_profit, audit_user_id, audit_date, audit_time_hr, audit_time_min, " +
        "audit_time_sec, audit_time_hun";

    public static final String INSERT_HEADER =
        "INSERT INTO glgnhed (" + HEADER_COLS + ") VALUES (" + q(40) + ")";

    public static final String DELETE_HEADER =
        "DELETE FROM glgnhed WHERE company_no=? AND jnl_type=? AND yr_no=? AND jnl_no=?";

    /** All 27 glgnlin columns, in INSERT order (see GlJournalService.lineParams). */
    private static final String LINE_COLS =
        "company_no, jnl_type, yr_no, jnl_no, line_no, acct_main_no, acct_sub_no, " +
        "dr_amt, cr_amt, trx_type, ref, tax_code, tax_amt, gst_gross_amt, " +
        "tax_clear_acct_main, tax_clear_acct_sub, sales_purch_ind, gl_recon_acct_flag, " +
        "gl_recon_id, note_no, zero_amt_flag, audit_user_id, audit_date, " +
        "audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun";

    public static final String INSERT_LINE =
        "INSERT INTO glgnlin (" + LINE_COLS + ") VALUES (" + q(27) + ")";

    public static final String DELETE_LINES =
        "DELETE FROM glgnlin WHERE company_no=? AND jnl_type=? AND yr_no=? AND jnl_no=?";

    public static final String UPDATE_STATUS =
        "UPDATE glgnhed SET jnl_status=?, posted_flag=? " +
        "WHERE company_no=? AND jnl_type=? AND yr_no=? AND jnl_no=?";

    /** "?,?,...,?" with n placeholders. */
    private static String q(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) { if (i > 0) sb.append(','); sb.append('?'); }
        return sb.toString();
    }
}
