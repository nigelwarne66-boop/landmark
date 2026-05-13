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
package com.landmarksoftware.payroll.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of an ATO tax-scale bracket formula.
 *
 * <p>Loaded from the ATO Excel files:
 * <ul>
 *   <li><b>NAT_1004</b> — "Statement of Formulas for Calculating Amounts to be
 *       Withheld" (the standard PAYG weekly tax tables).</li>
 *   <li><b>NAT_3539</b> — "Study and Training Support Loans (STSL) Weekly Tax
 *       Tables" (adds HELP/SFSS withholding components).</li>
 * </ul>
 *
 * <p>The ATO ships these as Excel sheets with a "Statement of Formula - CSV"
 * tab containing four columns: <i>scale_no, x-less-than, coefficient a,
 * coefficient b</i>. Each scale has multiple bracket rows ordered by upper
 * earnings; the last bracket has upper_earnings = 999999 (no cap).
 *
 * <p>Tax withheld = ROUND( a × earnings − b , 0 ) for the first bracket
 * whose upper_earnings ≥ employee's weekly earnings.
 */
public class TaxBracket {

    public int        companyNo       = 0;
    /** "NAT_1004" or "NAT_3539" — keep these segregated by source file. */
    public String     sourceFile      = "";
    /** Effective-from date of this ATO publication (e.g. 2025-07-01). */
    public LocalDate  effectiveFrom;
    /**
     * PAYG scale identifier — same convention for both source files:
     * <ul>
     *   <li><b>NAT_1004</b> — scale number 1..6 from the ATO Statement of
     *       Formula (occasionally letters for special cases). PAYG only.</li>
     *   <li><b>NAT_3539</b> — same scale numbers, but rows here carry the
     *       <strong>combined PAYG + STSL</strong> coefficients. Use when
     *       the employee has an STSL debt. (We don't store the STSL
     *       component separately — that didn't reconcile with the ATO
     *       calculator; the combined formula does.)</li>
     * </ul>
     */
    public String     scaleNo         = "";
    /** Sequence within (scale, source, eff_from) — 1-based. */
    public int        bracketNo       = 0;
    public BigDecimal upperEarnings   = BigDecimal.ZERO;
    public BigDecimal coeffA          = BigDecimal.ZERO;
    public BigDecimal coeffB          = BigDecimal.ZERO;
}
