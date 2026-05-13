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
 * PAPG01 — Pay Group master (pagroup table).
 *
 * <p>PK: {@code (company_no, paygroup)}. Field names mirror the actual
 * pagroup column names from the COBOL extract (confirmed against
 * {@code C:\landmark_extract\sql\create\pagroup_create.sql}).
 *
 * <p>Pay groups bundle:
 * <ul>
 *   <li>Frequency-specific pay-run state (weekly, fortnightly, 4-weekly,
 *       bi-monthly, monthly) — read-only on the maintenance screen,
 *       written by PAPP01 pay-run processing.</li>
 *   <li>GL posting accounts (net pay, income tax, w-comp clearing,
 *       pay-tax clearing, on-costs clearing, GST clearing).</li>
 *   <li>Payslip and payment-summary print/email preferences.</li>
 *   <li>Bank + SuperStream/STP routing (when {@code useGroupBank=Y}).</li>
 * </ul>
 */
public class PayGroup {

    // ── PK ───────────────────────────────────────────────────────────────
    public int        companyNo               = 0;
    public String     paygroup                = "";

    // ── General ──────────────────────────────────────────────────────────
    public String     desc1                   = "";
    public String     paygroupType            = "";
    /** "U" round up, "D" round down, "N" no rounding. */
    public String     roundPayUpDownInd       = "N";
    /** Rounding factor in dollars (e.g. 0.05 = 5c). */
    public BigDecimal roundPayFactor          = BigDecimal.ZERO;

    // ── Payroll tax ──────────────────────────────────────────────────────
    public String     allowPayrollTaxFlag     = "N";
    public BigDecimal payrollTaxPerc          = BigDecimal.ZERO;

    // ── GL accounts (main + sub) ─────────────────────────────────────────
    public int        netPayAcctMain          = 0;
    public int        netPayAcctSub           = 0;
    public int        incomeTaxAcctMain       = 0;
    public int        incomeTaxAcctSub        = 0;
    public int        wcompClearMain          = 0;
    public int        wcompClearSub           = 0;
    public int        payTaxClearMain         = 0;
    public int        payTaxClearSub          = 0;
    public int        oncostsClearMain        = 0;
    public int        oncostsClearSub         = 0;
    public int        gstClearMain            = 0;
    public int        gstClearSub             = 0;

    // ── Pay-run state per frequency (read-only on the maintenance screen)─
    public int        lastPayrunNoMth         = 0;
    public int        lastPayrunNo4Wk         = 0;
    public int        lastPayrunNoBimth       = 0;
    public int        lastPayrunNoFort        = 0;
    public int        lastPayrunNoWeek        = 0;
    public LocalDate  payrunDateMth;
    public LocalDate  payrunDate4Wk;
    public LocalDate  payrunDateBimth;
    public LocalDate  payrunDateFort;
    public LocalDate  payrunDateWeek;
    public int        paidThruToMth           = 0;
    public int        paidThruTo4Wk           = 0;
    public int        paidThruToBimth         = 0;
    public int        paidThruToFort          = 0;
    public int        paidThruToWeek          = 0;
    public String     payrunActiveMth         = "N";
    public String     payrunActive4Wk         = "N";
    public String     payrunActiveBimth       = "N";
    public String     payrunActiveFort        = "N";
    public String     payrunActiveWeek        = "N";
    public int        payrunNoActiveMth       = 0;
    public int        payrunNoActive4Wk       = 0;
    public int        payrunNoActiveBimth     = 0;
    public int        payrunNoActiveFort      = 0;
    public int        payrunNoActiveWeek      = 0;

    // ── Payslip print options ────────────────────────────────────────────
    public String     printRdoOnPayslip       = "N";
    public String     slipFormsReqdFlag       = "N";
    public String     slipFormsUserCode       = "";
    public String     slipFormsPrintFlag      = "N";
    public String     slipFormsEmailFlag      = "N";
    public String     slipPrintCoyName        = "N";
    public String     slipPrintAbn            = "N";
    public String     slipPrintLslFlag        = "N";
    public String     slipPrintAlFlag         = "N";
    public String     slipPrintSlFlag         = "N";
    public String     slipPrintAnnualSal      = "N";
    public String     slipAbn                 = "";
    public String     slipPaygroupName        = "";

    // ── Payment summary forms ────────────────────────────────────────────
    public String     summFormsReqdFlag       = "N";
    public String     summFormsUserCode       = "";
    public String     summFormsPrintFlag      = "N";
    public String     summFormsEmailFlag      = "N";

    // ── STP / Bank ───────────────────────────────────────────────────────
    public String     bankCode                = "";
    public String     ssContactCode           = "";
    public int        stpOzediClientId        = 0;
    public String     useGroupBank            = "N";

    // ── Misc ─────────────────────────────────────────────────────────────
    public long       noteNo                  = 0;

    // ── Display helpers ──────────────────────────────────────────────────

    public String typeLabel() {
        return switch (paygroupType == null ? "" : paygroupType.trim()) {
            case "ACTIVE" -> "Active";
            case "ARCH"   -> "Archived";
            case ""       -> "";
            default       -> paygroupType;
        };
    }

    public String roundingLabel() {
        return switch (roundPayUpDownInd == null ? "" : roundPayUpDownInd) {
            case "U" -> "Round up to " + roundPayFactor.stripTrailingZeros().toPlainString();
            case "D" -> "Round down to " + roundPayFactor.stripTrailingZeros().toPlainString();
            default  -> "No rounding";
        };
    }

    /** Combined GL account string for display: "main-sub" (zero values shown empty). */
    public static String glDisplay(int main, int sub) {
        if (main == 0 && sub == 0) return "";
        return main + "-" + sub;
    }
}
