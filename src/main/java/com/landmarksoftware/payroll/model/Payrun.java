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

import java.time.LocalDate;

/**
 * PATM01 — Pay Run header (parunhd table).
 *
 * <p>PK: {@code (company_no, payrun_no)}. {@code payrun_status} drives the
 * filter behaviour in PATM01 P1:
 * <ul>
 *   <li>{@code 'O'} — open / in progress (default).</li>
 *   <li>{@code 'F'} — fully posted. Hidden unless "Include Fully Posted" is on.</li>
 *   <li>{@code 'D'} — deleted / cancelled. Hidden unless "Include Cancelled" is on.</li>
 *   <li>{@code 'P'} — posted to GL (still open for STP / reversal).</li>
 * </ul>
 *
 * <p>{@code payrun_type} is the user's classification ({@code 'P'} primary
 * pay, {@code 'T'} termination, {@code 'B'} backpay, etc.). The S0 dialog
 * filters by this; blank = include all types.
 */
public class Payrun {

    public int       companyNo            = 0;
    public int       payrunNo             = 0;

    public LocalDate payrunDate;
    public String    payrunStatus         = "O";
    public int       yrNo                 = 0;
    public String    payrunType           = "P";

    public int       authLevelNo          = 0;
    public String    calcsCompletedFlag   = "N";
    public String    defaultCostType      = "";
    public String    defaultCalcTaxFlag   = "Y";
    public String    skipPaygroupOnAdd    = "N";
    public String    skipPaygroupOnEdit   = "N";
    public String    defltCalcSuperFlag   = "Y";
    public String    retainerRunFlag      = "N";
    public String    splitsRunFlag        = "N";
    public String    createRdoFlag        = "N";

    public int       noOfEmployees        = 0;
    public int       noOfEmployeesPaid    = 0;

    public LocalDate paymtDate;
    public int       paymtYrNo            = 0;

    public String    ref                  = "";
    public String    remitStatus          = "";
    public LocalDate startDate;
    public LocalDate endDate;
    public long      payerAbn             = 0L;

    public int       lastSuperSeqNo       = 0;
    public String    superstreamOnly      = "N";
    public String    superPayMethod       = "";

    public long      noteNo               = 0L;

    public String    auditUserId          = "";
    public LocalDate auditDate;
    public int       auditTimeHr          = 0;
    public int       auditTimeMin         = 0;
    public int       auditTimeSec         = 0;
    public int       auditTimeHun         = 0;

    /** True when this payrun is still editable (not fully posted, not cancelled). */
    public boolean isOpen() {
        return !"F".equalsIgnoreCase(payrunStatus)
            && !"D".equalsIgnoreCase(payrunStatus);
    }

    /** Display helper — readable status. */
    public String statusDisplay() {
        if (payrunStatus == null) return "";
        return switch (payrunStatus.toUpperCase()) {
            case "O" -> "Open";
            case "F" -> "Fully posted";
            case "D" -> "Cancelled";
            case "P" -> "Posted";
            default  -> payrunStatus;
        };
    }

    /** Display helper — readable type. */
    public String typeDisplay() {
        if (payrunType == null) return "";
        return switch (payrunType.toUpperCase()) {
            case "P" -> "Primary";
            case "T" -> "Termination";
            case "B" -> "Backpay";
            case "S" -> "Supplementary";
            default  -> payrunType;
        };
    }
}
