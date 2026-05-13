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
 * pay_type values are 1-24 (sourced from PACD01.cbl
 * WS-PAYCODE-TYPE-LIT-TABLE; CONVENTIONS.md previously said "1-5"
 * — that was wrong). See {@link #payTypeLabel()} for the full list
 * and {@link #fieldGroupFor(int)} for which column-group each type uses.
 *
 * Different rate/amount columns apply per type group:
 *   PAY    (1-3)        → pay_rate, pay_factor
 *   LEAVE  (4-9)        → leave_*           (UI deferred — Phase B)
 *   ALLOW  (10-14)      → allow_rate, allow_amt
 *   DEDN   (15-16)      → dedn_perc, dedn_amt
 *   SUPER  (17, 20)     → super_*           (UI deferred — Phase B)
 *   TAX    (18)         → tax_*             (UI deferred — Phase B)
 *   TERM_E (19)         → term_e_flag only
 *   CONTRIB(21)         → contrib_*         (UI deferred — Phase B)
 *   NONE   (22-24)      → header-only (Payroll Tax / WC / On Costs)
 */
public class PayCode {

    public String     companyNo            = "";   // varchar — matches cpcoyco key
    public String     payCode              = "";   // pacodes.pay_code
    public String     desc1                = "";   // pacodes.desc1
    public int        payType              = 1;    // pacodes.type — 1-24

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

    // ── PAY-type flags (PACD01S2 — types 1, 2, 3) ───────────────────────
    public String payPayableFlag         = "N";  // "Are hours payable?"
    public String payRdoAccrualFlag      = "N";  // "Used for rostered day off accruals?"
    public String payLslAccrualFlag      = "N";  // PT staff: "include hours in LSL accrual?"
    public String payAlAccrualFlag       = "N";  // PT staff: "include hours in AL accrual?"
    public String paySickAccrualFlag     = "N";  // PT staff: "include hours in sick accrual?"
    public String payLslCasAccrual       = "N";  // Casuals: "include in LSL accrual?"
    public String payIncludeForRdo       = "N";  // "Include in RDO accrual calculation?"
    public String payRetCommInd          = "";   // "Retainer/commission type" (single char)
    public String payLslReturnFlag       = "N";  // "Include in LSL return report?"
    public String payUsualPaidFlag       = "";   // "Use usual rate or actual paid rate?" (single char)
    public String payCdepFlag            = "N";  // "Include in CDEP?"

    // ── ALLOW-type fields (PACD01S2A / S2B — types 10-14) ───────────────
    public String     allowUnitPerDesc       = "";   // "Rate per <unit>"
    public String     allowLslReturnFlag     = "N";  // Include in LSL return report?
    public String     allowPayrollTaxFlag    = "N";  // Include in payroll tax calc?
    public String     allowLslAccrualFlag    = "N";  // PT: include hrs in LSL accrual
    public String     allowAlAccrualFlag     = "N";  // PT: include hrs in AL accrual
    public String     allowSlAccrualFlag     = "N";  // PT: include hrs in sick accrual
    public String     allowRdoAccrualFlag    = "N";  // Include hrs in RDO accrual
    public String     allowIncludeForRdo     = "N";  // Include in RDO accrual calc?
    public String     allowRetCommInd        = "";   // Retainer/commission type
    public String     allowIncludeForGc      = "N";  // Include on payment summaries?
    public String     allowLslCasAccrual     = "N";  // Casual: include in LSL accrual
    public String     allowFbtFlag           = "N";  // Includes FBT grossed up?
    public String     allowRptIncFlag        = "N";  // Reportable income?
    public String     allowGstFlag           = "N";  // GST applies?
    public String     allowGstCode           = "";   // Tax code
    public String     allowCdepFlag          = "N";  // Include in CDEP?

    // ── DEDN-type fields (PACD01S2D — types 15, 16) ─────────────────────
    public String     dednSalSacFlag         = "N";  // Salary sacrifice?
    public String     dednPayMethod          = "";   // C=cheque, D=direct, Q=cleared
    public String     dednRemittanceFreq     = "";   // P=pay run, M=monthly, Q=quarterly
    public int        dednClearAcctMain      = 0;    // GL clearing main
    public int        dednClearAcctSub       = 0;    // GL clearing sub
    public String     dednReportableFlag     = "N";  // Reportable on payment summary?
    public String     dednWplaceGiveFlag     = "N";  // Workplace giving?
    public String     dednUnionFeesFlag      = "N";  // Union/professional assoc fees?
    public String     dednUsedForSuper       = "N";  // Used for super calc?

    // ── LEAVE-type fields (PACD01S2C/S2F — types 4-9) ───────────────────
    public int        leaveMaxTaken          = 0;    // Max hours allowed (in MINUTES)
    public String     leaveLslAccrualFlag    = "N";
    public String     leaveAlAccrualFlag     = "N";
    public String     leaveSlAccrualFlag     = "N";
    public String     leaveRdoAccrualFlag    = "N";
    public String     leavePayableFlag       = "Y";  // Are hours payable?
    public String     leaveIncludeForRdo     = "N";  // Include in RDO accrual calcs?
    public BigDecimal leavePayFactor         = BigDecimal.ZERO;  // Factor of standard rate
    public String     leaveLslReturnFlag     = "N";
    public String     leaveTermPayFlag       = "N";  // Termination pay only?
    public int        leaveMaxPeriod         = 0;    // hours within a period of N (months)
    public String     leaveUsualPaidFlag     = "";   // Use usual or actual rate?
    public String     leaveLslCasAccrual     = "N";
    public String     leaveCdepFlag          = "N";

    // ── SUPER-type fields (PACD01S2E — types 17, 20) ────────────────────
    public BigDecimal superEmployeePerc      = BigDecimal.ZERO;
    public String     superPayMethod         = "";
    public String     superRemittanceFreq    = "";
    public int        superClearAcctMain     = 0;
    public int        superClearAcctSub      = 0;
    public String     superTfrFileFlag       = "N";  // Include in transfer file?
    public String     superPayrollTaxFlag    = "N";  // Payroll taxable?
    public String     superReportableFlag    = "N";  // Reportable on payment summary?
    public String     superBeforeAfterTax    = "";   // B/A
    public BigDecimal maxSuperYtd            = BigDecimal.ZERO;
    public String     planNo                 = "";   // Plan number

    // ── CONTRIB-type fields (PACD01S2H — type 21) ───────────────────────
    public String     contribPaidFlag        = "N";  // Includes paid contribution?
    public String     contribRemitFreq       = "";
    public String     contribPayMethod       = "";
    public String     contribFbtFlag         = "N";  // Includes FBT grossed up value?
    public String     contribRptIncFlag      = "N";  // Reportable income?
    public int        contribClearMain       = 0;
    public int        contribClearSub        = 0;
    public String     contribReportFlag      = "N";  // Calc payroll tax?
    public String     contribDeductTaxable   = "N";
    public String     contribPayTaxFlag      = "N";
    public String     contribGstFlag         = "N";
    public String     contribGstCode         = "";
    public String     contribUsedForSuper    = "N";

    // ── TAX-type fields (PACD01S2I — pay code = "TAX") ──────────────────
    public String     taxRemitFreq           = "";
    public String     taxPayMethod           = "";
    public String     eftReference           = "";

    // ── Fund / payee details — shared by DEDN/SUPER/CONTRIB/TAX ─────────
    public String     fundName               = "";
    public String     fundAddr1              = "";
    public String     fundAddr2              = "";
    public String     fundAddr3              = "";
    public String     contactName            = "";
    public String     contactPhone           = "";
    public String     fundAbn                = "";
    public String     fundUsi                = "";
    public String     fundEsa                = "";

    // ── EFT details (PACD01S2G — appears when pay-method = D or Q) ──────
    public String     bankCode               = "";   // Draw-from bank code
    public String     acctName               = "";   // Account name (defaults to fund_name)
    public String     bankBsb                = "";   // Transfer-to BSB
    public String     bankAcctNo             = "";   // Transfer-to acct no

    // ── SuperStream / fund classification (S2D / S2E / S2H — 17, 20, 21) ─
    public String     apraSmsfFundInd        = "";   // Fund type: A=APRA, S=SMSF
    public String     superstreamCategory    = "";   // SuperStream category 1-5

    // ── Display helpers ───────────────────────────────────────────────────

    /** 24 type labels — sourced verbatim from PACD01.cbl WS-PAYCODE-TYPE-LIT-TABLE. */
    public String payTypeLabel() {
        return switch (payType) {
            case  1 -> "Normal Pay";
            case  2 -> "Overtime";
            case  3 -> "Other Pay";
            case  4 -> "Long Service Leave";
            case  5 -> "Annual Leave";
            case  6 -> "Annual Leave Loading";
            case  7 -> "Sick Leave";
            case  8 -> "Rostered Day Off";
            case  9 -> "Other Leave";
            case 10 -> "Non Taxable Allowance";
            case 11 -> "Taxable Allowance";
            case 12 -> "Termination - A";
            case 13 -> "Termination - B";
            case 14 -> "Termination - C";
            case 15 -> "Before Tax Deduction";
            case 16 -> "After Tax Deduction";
            case 17 -> "Superannuation";
            case 18 -> "Tax";
            case 19 -> "Termination - D";
            case 20 -> "Employer Superann'n";
            case 21 -> "Employer Contribution";
            case 22 -> "Payroll Tax";
            case 23 -> "Workers' Compensation";
            case 24 -> "On Costs";
            default -> "Type " + payType;
        };
    }

    /** Logical field-group a type uses — drives which dialog section is shown. */
    public enum FieldGroup { PAY, LEAVE, ALLOW, DEDN, SUPER, TAX, TERM_E, CONTRIB, NONE }

    /** Mirrors PACD01.pl ENTER-PAY-CODE-DETAILS dispatch (24-way). */
    public static FieldGroup fieldGroupFor(int payType) {
        return switch (payType) {
            case 1, 2, 3                      -> FieldGroup.PAY;     // S2  — pay_*
            case 4, 5, 6, 7, 8, 9             -> FieldGroup.LEAVE;   // S2C / S2F — leave_*
            case 10, 11, 12, 13, 14           -> FieldGroup.ALLOW;   // S2A / S2B — allow_*
            case 15, 16                       -> FieldGroup.DEDN;    // S2D — dedn_*
            case 17, 20                       -> FieldGroup.SUPER;   // S2E — super_* + fund_* + bank_*
            case 18                           -> FieldGroup.TAX;     // S2I — tax_*
            case 19                           -> FieldGroup.TERM_E;  // S2J — term_e_flag only
            case 21                           -> FieldGroup.CONTRIB; // S2H — contrib_*
            case 22, 23, 24                   -> FieldGroup.NONE;    // header-only
            default                           -> FieldGroup.NONE;
        };
    }

    public FieldGroup fieldGroup() { return fieldGroupFor(payType); }

    /**
     * super_flag is force-locked to 'N' for types 17 and 20 — the type
     * itself implies super-ness, the flag is for *other* types that
     * also count toward super. Mirrors PACD01.pl CHECK-PAY-TYPE.
     */
    public static boolean superFlagLockedNo(int payType) {
        return payType == 17 || payType == 20;
    }

    /**
     * wcomp_flag is force-locked to 'N' for types > 14 EXCEPT 19, 20, 21.
     * Mirrors PACD01.pl CHECK-PAY-TYPE.
     */
    public static boolean wcompFlagLockedNo(int payType) {
        return payType > 14 && payType != 19 && payType != 20 && payType != 21;
    }

    /** Render the rate column appropriate to this pay_type's field group. */
    public String primaryRate() {
        return switch (fieldGroup()) {
            case PAY     -> nzStr(payRate);
            case ALLOW   -> nzStr(allowRate);
            case DEDN    -> nzStr(dednPerc);
            default      -> "";
        };
    }

    /** Render the amount column appropriate to this pay_type's field group. */
    public String primaryAmount() {
        return switch (fieldGroup()) {
            case ALLOW   -> nzStr(allowAmt);
            case DEDN    -> nzStr(dednAmt);
            default      -> "";
        };
    }

    private static String nzStr(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return v.stripTrailingZeros().toPlainString();
    }
}
