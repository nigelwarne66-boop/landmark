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

/**
 * pafunds row — superannuation fund master, used by PACD01 S2E (SUPER) and
 * S2H (CONTRIB) for picking a fund and auto-populating pacodes fund_* fields.
 *
 * Key: (company_no, apra_smsf_fund_ind, fund_id, smsf_abn).
 *  - apra_smsf_fund_ind = 'A'  → fund_id is the USI
 *  - apra_smsf_fund_ind = 'S'  → fund_id is the ESA alias; smsf_abn is the SMSF's ABN
 */
public class Fund {

    public String apraSmsfFundInd = "";   // 'A' = APRA fund, 'S' = SMSF
    public String fundId          = "";   // USI (APRA) or ESA alias (SMSF)
    public String smsfAbn         = "";
    public String fundAbn         = "";
    public String fundName        = "";
    public String fundName2       = "";
    public String productName     = "";
    public String fundAddr1       = "";
    public String fundAddr2       = "";
    public String fundAddr3       = "";
    public String contactName     = "";
    public String contactPhone    = "";
    public String contactEmail    = "";
    public String bankCode        = "";
    public String bankBsb         = "";
    public String bankAcctNo      = "";
    public String acctName        = "";
    public String employerNo      = "";

    /** USI for APRA funds, blank for SMSFs. */
    public String usi() { return "A".equalsIgnoreCase(apraSmsfFundInd) ? fundId : ""; }

    /** ESA alias for SMSFs, blank for APRA funds. */
    public String esa() { return "S".equalsIgnoreCase(apraSmsfFundInd) ? fundId : ""; }
}
