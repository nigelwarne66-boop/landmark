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
 * patimes — one timesheet line on a {@link TimesheetHeader}.
 *
 * <p>PK: {@code (company_no, payrun_no, employee_no, line_no)}.
 *
 * <p>Hours are stored in {@code min} as <strong>minutes</strong>; UI divides
 * by 60 for display. Monetary values are {@link BigDecimal}.
 *
 * <p>Mirrors paecode (standing line) shape — patimes is what the standing
 * line becomes for a specific payrun. Many of the same columns exist on
 * both tables.
 */
public class TimesheetLine {

    // ── PK ────────────────────────────────────────────────────────────────
    public int        companyNo            = 0;
    public int        payrunNo             = 0;
    public int        employeeNo           = 0;
    public int        lineNo               = 0;

    // ── Pay line ─────────────────────────────────────────────────────────
    public int        payType              = 0;
    public String     payCode              = "";
    public int        min                  = 0;        // minutes
    public BigDecimal qty                  = BigDecimal.ZERO;
    public BigDecimal ratePerc             = BigDecimal.ZERO;
    public BigDecimal extAmt               = BigDecimal.ZERO;
    public String     paygroup             = "";
    public String     dept                 = "";
    public String     award                = "";
    public String     jobClass             = "";
    public String     employeeDept         = "";
    public String     costType             = "";
    public LocalDate  timesheetDate;
    public String     ref                  = "";

    // ── Leave details ────────────────────────────────────────────────────
    public LocalDate  leaveStartDate;
    public LocalDate  leaveReturnDate;
    public String     reasonCode           = "";

    // ── GL routing ───────────────────────────────────────────────────────
    public int        glAcctNoMain         = 0;
    public int        glAcctNoSub          = 0;
    public String     glReconAcctFlag      = "N";
    public String     glReconId            = "";
    public String     ledgerType           = "";
    public String     ledgerCode           = "";
    public String     analysisCode         = "";
    public String     absorpType           = "";
    public BigDecimal absorpAmt            = BigDecimal.ZERO;
    public int        absorpGlAcctNoMain   = 0;
    public int        absorpGlAcctNoSub    = 0;
    public String     stdRateCode          = "";

    // ── RDO ──────────────────────────────────────────────────────────────
    public String     rdoCalcdFlag         = "N";
    public String     rdoCalcOnPayCode     = "";
    public int        rdoCalcOnPayType     = 0;

    // ── Super / leave fragment ───────────────────────────────────────────
    public BigDecimal lslWeeksTaken        = BigDecimal.ZERO;
    public int        noOfPeriods          = 0;

    // ── Termination split ────────────────────────────────────────────────
    public BigDecimal termCTax             = BigDecimal.ZERO;
    public BigDecimal fbtGrossValue        = BigDecimal.ZERO;
    public int        origPayrunNo         = 0;
    public String     paehistKey           = "";
    public BigDecimal costedTimesheetValue = BigDecimal.ZERO;

    // ── GST ──────────────────────────────────────────────────────────────
    public String     gstFlag              = "N";
    public String     gstCode              = "";
    public BigDecimal gstValue             = BigDecimal.ZERO;
    public BigDecimal gstGrossExTax        = BigDecimal.ZERO;

    // ── Summary flags ────────────────────────────────────────────────────
    public String     termAIndRT           = "";
    public String     termCTransInd        = "";
    public String     termCDeathInd        = "";

    // ── Misc ─────────────────────────────────────────────────────────────
    public int        subCoyNo             = 0;
    public String     basGroup             = "";
    public String     termCPaymtType       = "";

    // ── Backpay ──────────────────────────────────────────────────────────
    public String     backpayFlag          = "N";
    public int        backpayYrNo          = 0;
    public BigDecimal backpayTaxAmt        = BigDecimal.ZERO;

    public BigDecimal termWTaxAmt          = BigDecimal.ZERO;
    public LocalDate  termCPaymtDate;
    public BigDecimal termCTaxFreeAmt      = BigDecimal.ZERO;
    public BigDecimal termCTaxableAmt      = BigDecimal.ZERO;

    public long       noteNo               = 0L;

    // ── Audit ────────────────────────────────────────────────────────────
    public String     auditUserId          = "";
    public LocalDate  auditDate;
    public int        auditTimeHr          = 0;
    public int        auditTimeMin         = 0;
    public int        auditTimeSec         = 0;
    public int        auditTimeHun         = 0;

    /** Hours = min / 60 for display. */
    public BigDecimal hours() {
        return new BigDecimal(min).divide(new BigDecimal("60"), 2,
            java.math.RoundingMode.HALF_UP);
    }
}
