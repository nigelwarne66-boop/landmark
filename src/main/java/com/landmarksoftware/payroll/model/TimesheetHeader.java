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
 * patimhd — Timesheet header (one row per employee per payrun).
 *
 * <p>PK: {@code (company_no, payrun_no, employee_no)}. Cached
 * {@code surname} / {@code firstName} are denormalised from pastaff for
 * P3's sort-by-name listbox.
 *
 * <p>Stores running totals built up by the pay-line entry process (patimes).
 * Net pay is {@code gross − before-tax − after-tax − super − tax}.
 *
 * <p>For Wave 3 P3 we only need a subset of the FD columns. The full
 * column set lives in the schema; this model carries everything PATM01
 * S3 / S3B / S3C / S3D reference plus the running totals shown in P3.
 */
public class TimesheetHeader {

    // ── PK ────────────────────────────────────────────────────────────────
    public int        companyNo               = 0;
    public int        payrunNo                = 0;
    public int        employeeNo              = 0;

    // ── Denormalised for P3 sort/display ─────────────────────────────────
    public String     surname                 = "";
    public String     firstName               = "";

    // ── Alt key (paygroup/dept routing) ──────────────────────────────────
    public int        altPayrunNo             = 0;
    public String     altPaygroup             = "";
    public String     altDept                 = "";
    public int        altEmployeeNo           = 0;

    // ── Totals — dollar values ───────────────────────────────────────────
    public BigDecimal totalNormalPay          = BigDecimal.ZERO;
    public BigDecimal totalOtimePay           = BigDecimal.ZERO;
    public BigDecimal totalOtherPay           = BigDecimal.ZERO;
    public BigDecimal totalLslPay             = BigDecimal.ZERO;
    public BigDecimal totalAlPay              = BigDecimal.ZERO;
    public BigDecimal totalAlLoad             = BigDecimal.ZERO;
    public BigDecimal totalSickPay            = BigDecimal.ZERO;
    public BigDecimal totalOtherLeavePay      = BigDecimal.ZERO;
    public BigDecimal totalNontaxAllow        = BigDecimal.ZERO;
    public BigDecimal totalTaxableAllow       = BigDecimal.ZERO;
    public BigDecimal totalTermA              = BigDecimal.ZERO;
    public BigDecimal totalTermB              = BigDecimal.ZERO;
    public BigDecimal totalTermC              = BigDecimal.ZERO;
    public BigDecimal totalBeforeTaxDedns     = BigDecimal.ZERO;
    public BigDecimal totalAfterTaxDedns      = BigDecimal.ZERO;
    public BigDecimal totalSuper              = BigDecimal.ZERO;
    public BigDecimal totalTax                = BigDecimal.ZERO;
    public BigDecimal totalTermD              = BigDecimal.ZERO;
    public BigDecimal totalTermCTax           = BigDecimal.ZERO;
    public BigDecimal totalFbtRptIncome       = BigDecimal.ZERO;
    public BigDecimal totalHecsTax            = BigDecimal.ZERO;

    public BigDecimal alLoadYtd               = BigDecimal.ZERO;

    // ── Totals — minutes ─────────────────────────────────────────────────
    public int        totalNormalMin          = 0;
    public int        totalOtimeMinActual     = 0;
    public int        totalOtherMin           = 0;
    public int        totalLslMin             = 0;
    public int        totalAlMin              = 0;
    public int        totalSickMin            = 0;
    public int        totalOtherLeaveMin      = 0;
    public int        totalOtimeMinPaid       = 0;
    public int        hrsWrkdForAl            = 0;
    public int        hrsWrkdForSl            = 0;
    public int        hrsWrkdForLsl           = 0;

    // ── Dates / flags ────────────────────────────────────────────────────
    public LocalDate  prevPaidThruDate;
    public LocalDate  payThruStartDate;
    public LocalDate  payThruToDate;
    public String     defaultTimesheetFlag    = "N";
    public String     costedTimesheetFlag     = "N";
    public String     calcTaxUsingPayDates    = "N";
    public String     roundPayUpDownInd       = "";
    public BigDecimal roundPayFactor          = BigDecimal.ZERO;
    public int        lastPayrunNo            = 0;
    public String     timesheetStatus         = "";
    public String     timesheetInUse          = "N";
    public BigDecimal timesheetRatePerHr      = BigDecimal.ZERO;
    public String     payslipPrintedFlag      = "N";

    public BigDecimal totalLslTermPay         = BigDecimal.ZERO;
    public BigDecimal totalAlTermPay          = BigDecimal.ZERO;
    public BigDecimal totalAllTermPay         = BigDecimal.ZERO;
    public BigDecimal totalContribDedns       = BigDecimal.ZERO;
    public int        lslMinTakenToDate       = 0;
    public BigDecimal timesheetFreq           = BigDecimal.ZERO;
    public String     timesheetSplitsRun      = "N";

    public BigDecimal totalBackpay            = BigDecimal.ZERO;
    public BigDecimal totalBackpayTax         = BigDecimal.ZERO;
    public BigDecimal totalTermE              = BigDecimal.ZERO;
    public BigDecimal totalTermETax           = BigDecimal.ZERO;
    public BigDecimal totalTermW              = BigDecimal.ZERO;
    public BigDecimal totalTermWTax           = BigDecimal.ZERO;
    public BigDecimal totalTermBTax           = BigDecimal.ZERO;

    public long       noteNo                  = 0L;

    // ── Audit ────────────────────────────────────────────────────────────
    public String     auditUserId             = "";
    public LocalDate  auditDate;
    public int        auditTimeHr             = 0;
    public int        auditTimeMin            = 0;
    public int        auditTimeSec            = 0;
    public int        auditTimeHun            = 0;

    /** Display helper for P3: surname + first name as "SURNAME, First". */
    public String displayName() {
        if (surname.isBlank() && firstName.isBlank()) return "";
        if (surname.isBlank()) return firstName;
        if (firstName.isBlank()) return surname;
        return surname + ", " + firstName;
    }

    /** Total hours = sum of every minute total ÷ 60. */
    public BigDecimal totalHours() {
        long mins = (long) totalNormalMin + totalOtimeMinActual + totalOtherMin
            + totalLslMin + totalAlMin + totalSickMin + totalOtherLeaveMin;
        return new BigDecimal(mins).divide(new BigDecimal("60"), 2,
            java.math.RoundingMode.HALF_UP);
    }

    /** Gross pay = sum of every pay-component total. Mirrors P3 calc. */
    public BigDecimal grossPay() {
        return totalNormalPay
            .add(totalOtimePay).add(totalOtherPay).add(totalLslPay).add(totalAlPay)
            .add(totalAlLoad).add(totalSickPay).add(totalOtherLeavePay)
            .add(totalNontaxAllow).add(totalTaxableAllow)
            .add(totalTermA).add(totalTermB).add(totalTermC).add(totalTermD)
            .add(totalTermE).add(totalTermW).add(totalBackpay);
    }

    /** Net pay = gross − deductions − super − tax. Mirrors P3 calc. */
    public BigDecimal netPay() {
        return grossPay()
            .subtract(totalBeforeTaxDedns)
            .subtract(totalAfterTaxDedns)
            .subtract(totalSuper)
            .subtract(totalTax);
    }
}
