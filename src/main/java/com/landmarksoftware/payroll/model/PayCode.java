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

/**
 * PACD01 — Pay Code master record (pacodes table).
 *
 * pacodes PK: (company_no, pay_code)
 *
 * Field names mirror real pacodes column names (verified from
 * Landmark SQL extracts — pacodes_select.sql).
 *
 * pay_type values (from paehist.pay_type convention):
 *   1 = Income / Earnings
 *   2 = Allowance
 *   3 = Deduction
 *   4 = Tax
 *   5 = Superannuation
 *
 * Different rate/amount columns apply per pay_type:
 *   type 1 income     → pay_rate, pay_factor
 *   type 2 allowance  → allow_rate, allow_amt
 *   type 3 deduction  → dedn_perc, dedn_amt
 *
 * The UI exposes them all on Edit; the user fills in what's relevant.
 */
public class PayCode {

    public String     companyNo            = "";   // varchar — matches cpcoyco key
    public String     payCode              = "";   // pacodes.pay_code
    public String     desc1                = "";   // pacodes.desc1
    public int        payType              = 1;    // pacodes.type — 1-5

    // ── Display / payslip ────────────────────────────────────────────────
    public String     payslipDesc          = "";   // pacodes.payslip_desc
    public String     abbrevDesc           = "";   // pacodes.abbrev_desc
    public String     printOnPayslipFlag   = "Y";  // pacodes.print_on_payslip_flag

    // ── Behaviour flags ──────────────────────────────────────────────────
    public String     superFlag            = "N";  // pacodes.super_flag — counts toward super
    public String     wcompFlag            = "N";  // pacodes.wcomp_flag — workers comp
    public String     termEFlag            = "N";  // pacodes.term_e_flag — termination earning

    // ── Rates / amounts (per type) ───────────────────────────────────────
    public BigDecimal payRate              = BigDecimal.ZERO;  // type 1
    public BigDecimal payFactor            = BigDecimal.ZERO;  // type 1 — pay multiplier
    public BigDecimal allowRate            = BigDecimal.ZERO;  // type 2
    public BigDecimal allowAmt             = BigDecimal.ZERO;  // type 2
    public BigDecimal dednPerc             = BigDecimal.ZERO;  // type 3
    public BigDecimal dednAmt              = BigDecimal.ZERO;  // type 3

    // ── Display helpers ───────────────────────────────────────────────────

    public String payTypeLabel() {
        return switch (payType) {
            case 1 -> "Income";
            case 2 -> "Allowance";
            case 3 -> "Deduction";
            case 4 -> "Tax";
            case 5 -> "Super";
            default -> "Type " + payType;
        };
    }

    /** Render the rate column appropriate to this pay_type. */
    public String primaryRate() {
        return switch (payType) {
            case 1 -> nzStr(payRate);
            case 2 -> nzStr(allowRate);
            case 3 -> nzStr(dednPerc);
            default -> "";
        };
    }

    /** Render the amount column appropriate to this pay_type. */
    public String primaryAmount() {
        return switch (payType) {
            case 2 -> nzStr(allowAmt);
            case 3 -> nzStr(dednAmt);
            default -> "";
        };
    }

    private static String nzStr(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return v.stripTrailingZeros().toPlainString();
    }
}
