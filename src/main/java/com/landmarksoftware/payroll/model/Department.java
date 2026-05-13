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
 * PAPG01 P2/S2 — Department record (padepts table).
 *
 * <p>PK: {@code (company_no, paygroup, dept)}. Each pay group can own many
 * departments. The department carries ~14 GL account pairs (main/sub) used
 * for posting payroll costs and accruals.
 *
 * <p>{@link #lastWageAccrDate} is owned by the wage-accrual processing and
 * shown read-only on the maintenance screen.
 */
public class Department {

    // ── PK ────────────────────────────────────────────────────────────────
    public int        companyNo                  = 0;
    public String     paygroup                   = "";
    public String     dept                       = "";

    // ── Identity ──────────────────────────────────────────────────────────
    public String     desc1                      = "";

    // ── Expense accounts (DR — costs hit P&L) ─────────────────────────────
    public int        wcompExpMain               = 0;
    public int        wcompExpSub                = 0;
    public int        payTaxExpMain              = 0;
    public int        payTaxExpSub               = 0;
    public int        oncostsExpAcctMain         = 0;
    public int        oncostsExpAcctSub          = 0;
    public int        sickExpAcctMain            = 0;
    public int        sickExpAcctSub             = 0;
    public int        alExpAcctMain              = 0;
    public int        alExpAcctSub               = 0;
    public int        lslExpAcctMain             = 0;
    public int        lslExpAcctSub              = 0;

    // ── Provision accounts (CR — liabilities accumulating) ────────────────
    public int        sickProvAcctMain           = 0;
    public int        sickProvAcctSub            = 0;
    public int        alProvAcctMain             = 0;
    public int        alProvAcctSub              = 0;
    public int        lslProvAcctMain            = 0;
    public int        lslProvAcctSub             = 0;
    public int        employSupAcctMain          = 0;
    public int        employSupAcctSub           = 0;
    public int        wageAcctProvMain           = 0;
    public int        wageAccrProvSub            = 0;

    // ── Accrual accounts ─────────────────────────────────────────────────
    public int        accruedSalAcctMain         = 0;
    public int        accruedSalAcctSub          = 0;
    public int        revAccrSalAcctMain         = 0;
    public int        revAccrSalAcctSub          = 0;
    public int        accrSalVarAcctMain         = 0;
    public int        accrSalVarAcctSub          = 0;
    public int        accruedTimeAcctMain        = 0;
    public int        accruedTimeAcctSub         = 0;
    public int        wageAccrAcctMain           = 0;
    public int        wageAccrAcctSub            = 0;

    // ── Inter-company ────────────────────────────────────────────────────
    public int        interCrAcctMain            = 0;
    public int        interCrAcctSub             = 0;
    public int        interChargeAcctMain        = 0;
    public int        interChargeAcctSub         = 0;

    // ── Processing state (read-only on maintenance) ──────────────────────
    public LocalDate  lastWageAccrDate;
    public long       noteNo                     = 0;

    // ── Display helpers ──────────────────────────────────────────────────

    /** Combined "main-sub" string for table columns; empty when both are 0. */
    public static String glDisplay(int main, int sub) {
        if (main == 0 && sub == 0) return "";
        return main + "-" + sub;
    }
}
