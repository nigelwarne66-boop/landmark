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
 * One pay-method split for an employee (PAEM01 S2 — paempay table).
 *
 * <p>PK: {@code (company_no, employee_no, payment_no)}. Each employee can
 * have multiple splits — e.g. payment_no=1 sends 70% to a main account,
 * payment_no=2 sends 30% to a savings account.
 *
 * <p>Split semantics:
 * <ul>
 *   <li>{@link #payCalcMethod} = {@code "P"} → {@link #payAmtPerc} is a
 *       percentage (0–100).</li>
 *   <li>{@link #payCalcMethod} = {@code "A"} → {@link #payAmtPerc} is a
 *       fixed dollar amount.</li>
 *   <li>{@link #payCalcMethod} = {@code "B"} → balance — receives whatever
 *       is left after the other splits.</li>
 * </ul>
 */
public class EmployeePay {

    public int        companyNo        = 0;
    public int        employeeNo       = 0;
    public int        paymentNo        = 0;

    /** Pay method: "E" EFT, "C" cheque, "X" cash, etc. */
    public String     payMethod        = "";
    public String     bankCode         = "";
    /** BSB — 6 digits, may carry the hyphen depending on legacy data. */
    public String     tfrToBankNo      = "";
    public String     tfrToBankAcctNo  = "";
    /** Calculation method: "P" percent / "A" amount / "B" balance. */
    public String     payCalcMethod    = "";
    /** Percentage (0–100) or fixed amount — meaning depends on payCalcMethod. */
    public BigDecimal payAmtPerc       = BigDecimal.ZERO;
    public String     payeeName        = "";
    public long       noteNo           = 0;
}
