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
 * PATM01 P2 — Payrun ↔ Pay Group join (parungr table).
 *
 * <p>PK: {@code (company_no, payrun_no, paygroup)}. Captures the pay-thru-to
 * date for each pay frequency window (monthly / 4-weekly / bimonthly /
 * fortnightly / weekly) and the per-paygroup status within the payrun.
 *
 * <p>{@code paygroupStatus} mirrors COBOL — {@code 'O'} open, {@code 'C'}
 * closed for this paygroup, {@code 'F'} fully processed.
 *
 * <p>{@link #paygroupDesc} is denormalised at read time from {@code pagroup}
 * for display in the P2 listbox. Not part of the table.
 */
public class PayrunGroup {

    public int       companyNo        = 0;
    public int       payrunNo         = 0;
    public String    paygroup         = "";

    public LocalDate payThruToMth;
    public LocalDate payThruTo4Wk;
    public LocalDate payThruToBimth;
    public LocalDate payThruToFort;
    public LocalDate payThruToWeek;

    public String    paygroupStatus   = "O";

    public long      noteNo           = 0L;

    public String    auditUserId      = "";
    public LocalDate auditDate;
    public int       auditTimeHr      = 0;
    public int       auditTimeMin     = 0;
    public int       auditTimeSec     = 0;
    public int       auditTimeHun     = 0;

    /** Display only — joined from {@code pagroup.desc} at read time. */
    public String    paygroupDesc     = "";

    /** Display helper — readable status. */
    public String statusDisplay() {
        if (paygroupStatus == null) return "";
        return switch (paygroupStatus.toUpperCase()) {
            case "O" -> "Open";
            case "C" -> "Closed";
            case "F" -> "Full";
            default  -> paygroupStatus;
        };
    }
}
