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
 * paecode — per-employee standing pay line.
 *
 * <p>PK: {@code (company_no, employee_no, line_no)}. One row per default
 * timesheet line that should be created for the employee each payrun. These
 * seed the patimhd / patimes rows when a new timesheet is started.
 *
 * <p>Pay type follows the pacodes convention (24 codes; same set as PACD01).
 * Hours are stored in {@code min} as <strong>minutes</strong>; UI divides
 * by 60 for display. Monetary values are {@link BigDecimal}.
 *
 * <p>Audit: every write goes through
 * {@code MasterFileAuditService.auditPaeCode}, producing a {@code papcaud}
 * row (the audit hook deferred from Wave 1.5).
 */
public class Paecode {

    // ── PK ────────────────────────────────────────────────────────────────
    public int        companyNo            = 0;
    public int        employeeNo           = 0;
    public int        lineNo               = 0;

    // ── Alt keys / cross-refs ────────────────────────────────────────────
    public String     alt2PayCode          = "";
    public int        alt1EmployeeNo       = 0;
    public String     alt1PayCode          = "";
    public int        alt1LineNo           = 0;

    // ── Schedule ─────────────────────────────────────────────────────────
    public int        payFreq              = 0;        // 1=W 2=F 4=4W 5=BiMth 12=M (industry convention)
    public int        paysSinceLastPaid    = 0;
    public String     stdPayCodeFlag       = "Y";
    public LocalDate  startDate;
    public LocalDate  endDate;
    public LocalDate  lastPaidDate;
    public String     superMemberNo        = "";

    // ── Pay line ─────────────────────────────────────────────────────────
    public int        payType              = 0;        // matches pacodes.pay_type
    public String     payCode              = "";
    public int        min                  = 0;        // minutes; display ÷ 60
    public BigDecimal qty                  = BigDecimal.ZERO;
    public BigDecimal ratePerc             = BigDecimal.ZERO;
    public BigDecimal extAmt               = BigDecimal.ZERO;
    public String     paygroup             = "";
    public String     dept                 = "";
    public String     award                = "";
    public String     jobClass             = "";
    public String     costType             = "";

    // ── GL ───────────────────────────────────────────────────────────────
    public int        glAcctNoMain         = 0;
    public int        glAcctNoSub          = 0;
    public String     ledgerType           = "";
    public String     ledgerCode           = "";
    public String     analysisCode         = "";
    public String     absorpType           = "";
    public BigDecimal absorpFactor         = BigDecimal.ZERO;
    public BigDecimal absorpAmt            = BigDecimal.ZERO;

    // ── Misc ─────────────────────────────────────────────────────────────
    public String     ref                  = "";
    public LocalDate  leaveStartDate;
    public LocalDate  leaveReturnDate;
    public BigDecimal fbtGrossValue        = BigDecimal.ZERO;

    // ── BA (business activity / billing) — left here for round-trip ─────
    public String     baLedgerId           = "";
    public String     baPrimaryCodes       = "";
    public String     baDesc               = "";
    public String     baBillText           = "";
    public BigDecimal gstValue             = BigDecimal.ZERO;
    public String     baGlOverrideFlag     = "N";
    public String     baEditBillDataFlag   = "N";

    public long       noteNo               = 0L;

    // ── Audit (per-row) ──────────────────────────────────────────────────
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
