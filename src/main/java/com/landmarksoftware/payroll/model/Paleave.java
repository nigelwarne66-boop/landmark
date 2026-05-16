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
 * paleave — Leave ledger. One row per exceptional leave event.
 *
 * <p>PK: {@code (company_no, employee_no, pay_code, pay_type, leave_start_date, accrued_taken_ind)}.
 *
 * <p>Per COBOL: PALEAVE is NOT a per-pay-period transaction log. The bulk of
 * leave accrual updates {@code pastaff} balances directly (via PAPP03). Rows
 * land in paleave only for:
 * <ul>
 *   <li><b>Opening balance migration</b> (PASU18, {@code accrued_taken_ind='A'}).</li>
 *   <li><b>Manual employee maintenance edits</b> (PAEM01, {@code 'A'}).</li>
 *   <li><b>Leave taken on a payrun</b> (PAPP28 posting, {@code 'T'}) — one row per
 *       employee × pay_type × leave_start_date that was paid out.</li>
 * </ul>
 *
 * <p>PAPP28 also REVERSES on un-post: deletes 'A' rows tied to the payrun
 * and walks 'T' rows backing out their {@code pastaff} updates.
 *
 * <p>COBOL pay_type codes that appear on this table:
 * <ul>
 *   <li>04 — Long service leave (LSL)</li>
 *   <li>05 — Annual leave (AL)</li>
 *   <li>06 — Annual leave loading</li>
 *   <li>(plus sick / other-leave variants depending on installation)</li>
 * </ul>
 */
public class Paleave {

    public int        companyNo            = 0;
    public int        employeeNo           = 0;
    public String     payCode              = "";
    public int        payType              = 0;
    public LocalDate  leaveStartDate;
    /** {@code 'A'} accrued · {@code 'T'} taken. */
    public String     accruedTakenInd      = "A";

    // ── Alt-key (denormalised — written by every INSERT) ─────────────────
    public int        alt1EmployeeNo       = 0;
    public LocalDate  alt1StartDate;
    public int        alt1PayType          = 0;
    public String     alt1PayCode          = "";
    public String     alt1AccrTakenInd     = "A";

    // ── Event detail ─────────────────────────────────────────────────────
    public LocalDate  leaveEndDate;
    public int        min                  = 0;        // hours × 60
    public BigDecimal rate                 = BigDecimal.ZERO;
    public BigDecimal amt                  = BigDecimal.ZERO;
    public int        dayOfWeek            = 0;
    public String     ref                  = "";
    public int        payrunNo             = 0;
    public LocalDate  payrunDate;
    public BigDecimal lslWeeks             = BigDecimal.ZERO;
    public long       noteNo               = 0L;

    // ── Audit ────────────────────────────────────────────────────────────
    public String     auditUserId          = "";
    public LocalDate  auditDate;
    public int        auditTimeHr          = 0;
    public int        auditTimeMin         = 0;
    public int        auditTimeSec         = 0;
    public int        auditTimeHun         = 0;

    /** True for accrued rows (opening balance / manual / accrual). */
    public boolean isAccrued() { return "A".equalsIgnoreCase(accruedTakenInd); }
    /** True for taken rows (paid-out leave from a timesheet). */
    public boolean isTaken()   { return "T".equalsIgnoreCase(accruedTakenInd); }
}
