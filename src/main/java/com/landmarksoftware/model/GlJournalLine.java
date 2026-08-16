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
package com.landmarksoftware.model;

import java.math.BigDecimal;

/**
 * One line of a GL journal — a row of {@code glgnlin} (GLGN01).
 *
 * <p>MVP subset: account, debit/credit, reference, and the standing-journal
 * {@code zero_amt_flag}. GST/tax, foreign currency, counter-balancing,
 * analysis codes and notes are deferred to later waves (defaulted on write).
 */
public class GlJournalLine {

    /** glgnlin.line_no — 1-based sequence within the journal (renumbered on insert/delete). */
    public int lineNo;

    /** glgnlin.acct_main_no / acct_sub_no — the posting account (validated against glchart). */
    public int acctMainNo;
    public int acctSubNo;

    /** glgnlin.dr_amt / cr_amt — one is entered, the other is zero (COBOL CHECK-DR/CR-AMT). */
    public BigDecimal drAmt = BigDecimal.ZERO;
    public BigDecimal crAmt = BigDecimal.ZERO;

    /** glgnlin.ref — line narrative. */
    public String ref = "";

    /** glgnlin.zero_amt_flag — standing journals only: "Y" = clear amount after each post. */
    public String zeroAmtFlag = "N";

    /** Display-only: account description from glchart (not persisted on the line). */
    public String acctDesc = "";

    /** "1000" or "1000.10" for display. */
    public String accountDisplay() {
        return acctSubNo == 0 ? String.valueOf(acctMainNo) : acctMainNo + "." + acctSubNo;
    }
}
