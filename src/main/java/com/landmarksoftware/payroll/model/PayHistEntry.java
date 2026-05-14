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
 * paehist — Pay run history. One row per posted timesheet line.
 *
 * <p>PK: {@code (company_no, employee_no, payrun_date, pay_type, pay_code,
 * payrun_no, line_no)}.
 *
 * <p>Populated by PAPP28 Payroll Posting — every patimes line on a payrun
 * becomes a paehist row. Read by PABK02 (ABA file), PAPS26 (payment
 * summaries), STP submission, and the PATL reports.
 *
 * <p>The model covers the fields PAPP28 actively writes; the schema's wider
 * column set (BA / billing-activity, STP, GST-extras) carries forward via
 * sensible defaults — refined when the corresponding programs land.
 */
public class PayHistEntry {

    // ── PK ────────────────────────────────────────────────────────────────
    public int        companyNo            = 0;
    public int        employeeNo           = 0;
    public LocalDate  payrunDate;
    public int        payType              = 0;
    public String     payCode              = "";
    public int        payrunNo             = 0;
    public int        lineNo               = 0;

    // ── Pay line ─────────────────────────────────────────────────────────
    public String     paygroup             = "";
    public String     dept                 = "";
    public int        hrs                  = 0;        // minutes — same convention as patimes.min
    public BigDecimal qty                  = BigDecimal.ZERO;
    public BigDecimal ratePerc             = BigDecimal.ZERO;
    public BigDecimal extAmt               = BigDecimal.ZERO;
    public String     award                = "";
    public String     jobClass             = "";
    public String     costType             = "";

    // ── GL routing ───────────────────────────────────────────────────────
    public int        glAcctNoMain         = 0;
    public int        glAcctNoSub          = 0;
    public String     ledgerType           = "";
    public String     ledgerCode           = "";
    public String     absorpType           = "";
    public BigDecimal absorpAmt            = BigDecimal.ZERO;

    public String     ref                  = "";

    // ── Tax / income classification ──────────────────────────────────────
    public String     incomeTaxableFlag    = "N";
    public String     payrollTaxableFlag   = "N";
    public BigDecimal termCTaxAmt          = BigDecimal.ZERO;
    public BigDecimal hecsTaxAmt           = BigDecimal.ZERO;
    public LocalDate  lastPayToDate;
    public LocalDate  thisPayToDate;
    public String     payrunType           = "P";
    public BigDecimal fbtGrossValue        = BigDecimal.ZERO;
    public String     paidFlag             = "N";

    // ── Tax totals (when pay_type=tax) ───────────────────────────────────
    public String     gstFlag              = "N";
    public String     taxCode              = "";
    public BigDecimal taxAmt               = BigDecimal.ZERO;
    public BigDecimal grossAmt             = BigDecimal.ZERO;

    // ── Termination flags ────────────────────────────────────────────────
    public String     termAIndRT           = "";
    public String     termCTransInd        = "";
    public String     termCDeathInd        = "";
    public BigDecimal gstValue             = BigDecimal.ZERO;
    public String     gstCode              = "";
    public String     basGroup             = "";
    public String     termCPaymtType       = "";
    public String     cdepFlag             = "N";

    // ── Backpay ──────────────────────────────────────────────────────────
    public BigDecimal backpayTaxAmt        = BigDecimal.ZERO;
    public int        backpayTaxYr         = 0;

    // ── Leave window ─────────────────────────────────────────────────────
    public LocalDate  leaveStartDate;
    public LocalDate  leaveEndDate;
    public LocalDate  thisPayStartDate;

    // ── STP ──────────────────────────────────────────────────────────────
    public String     stpCountryCode       = "";
    public String     stpDateTimeStamp     = "";
    public String     stpIncomeType        = "";

    // ── Termination C/W amounts ──────────────────────────────────────────
    public LocalDate  termCPaymtDate;
    public BigDecimal termCTaxFreeAmt      = BigDecimal.ZERO;
    public BigDecimal termCTaxableAmt      = BigDecimal.ZERO;
    public BigDecimal termWTaxAmt          = BigDecimal.ZERO;

    public long       noteNo               = 0L;

    // ── Audit ────────────────────────────────────────────────────────────
    public String     auditUserId          = "";
    public LocalDate  auditDate;
    public int        auditTimeHr          = 0;
    public int        auditTimeMin         = 0;
    public int        auditTimeSec         = 0;
    public int        auditTimeHun         = 0;

    /** Hours = hrs / 60 for display. */
    public BigDecimal hoursAsBd() {
        return new BigDecimal(hrs).divide(new BigDecimal("60"), 2,
            java.math.RoundingMode.HALF_UP);
    }
}
