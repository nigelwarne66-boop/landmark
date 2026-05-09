package com.example.fixedassets.payroll.model;

import java.math.BigDecimal;

/**
 * PACD01 — Pay Code master record (pacodes table).
 *
 * pay_type values (from paehist.pay_type convention):
 *   1 = Income / Earnings
 *   2 = Allowance
 *   3 = Deduction
 *   4 = Tax
 *   5 = Superannuation
 *
 * All rates/amounts stored as BigDecimal — COBOL COMP-3 packed decimal.
 * Hours values in DB are stored as minutes; this model carries minutes
 * and the UI divides by 60 for display.
 */
public class PayCode {

    public String     companyNo     = "";   // VARCHAR — matches cpcoyco key
    public String     payCode       = "";   // pay_code VARCHAR(10)
    public String     desc1         = "";   // description
    public int        payType       = 1;    // 1-5 — see above
    public String     superFlag     = "N";  // 'Y' = counts toward super
    public String     incomeTaxFlag = "N";  // 'Y' = income-taxable
    public String     activeFlag    = "Y";  // 'Y' = active, 'N' = inactive
    public BigDecimal stdRate       = BigDecimal.ZERO;  // standard rate if applicable
    public BigDecimal stdAmount     = BigDecimal.ZERO;  // fixed amount if applicable
    public String     glCode        = "";   // GL account code (optional)
    public String     notes         = "";   // free text notes

    // ── Display helpers ───────────────────────────────────────────────────

    public String payTypeLabel() {
        return switch (payType) {
            case 1 -> "Income";
            case 2 -> "Allowance";
            case 3 -> "Deduction";
            case 4 -> "Tax";
            case 5 -> "Super";
            default -> "Type " + payType;
        };
    }

    public boolean isActive() { return !"N".equals(activeFlag); }
}
