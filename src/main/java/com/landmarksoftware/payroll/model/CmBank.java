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
 * cmbanks — Cash Management bank account.
 *
 * <p>PK: {@code (company_no, bank_code VARCHAR(2))}. This model carries the
 * subset PABK02 needs to populate ABA file header / detail records. The
 * full cmbanks row has 100+ columns (cashbook clearance days, statement
 * data, currency fluctuation accounts, etc.) — they're untouched by payroll
 * and the model deliberately doesn't carry them.
 *
 * <p>Selecting the payroll bank: cmbanks rows can be flagged for use by AP,
 * AR, and PA modules independently via {@code eft_*_flag} columns. The PA
 * flag is {@link #eftPaFlag}. {@link com.landmarksoftware.payroll.service.CmBanksService}
 * picks the first non-inactive row where {@code eft_pa_flag = "Y"}.
 */
public class CmBank {

    public int     companyNo            = 0;
    public String  bankCode             = "";        // 2-char PK
    public String  name                 = "";        // friendly name
    public String  branchNo             = "";        // BSB — usually formatted NNN-NNN
    public String  bankAcctNo           = "";        // employer's account number
    public String  inactiveFlag         = "N";
    public String  eftFlag              = "N";       // EFT enabled at all?
    public String  eftBankCode          = "";        // 3-char abbreviation, e.g. ANZ / CBA / NAB
    public String  eftName              = "";        // 26-char name for ABA header
    public int     userNo               = 0;         // APCA user / direct-entry id (6 digits)
    public String  eftPaFlag            = "N";       // "Y" → usable for payroll ABA
    public String  eftPaCashbookEntry   = "N";       // "Y" → write cashbook entry on ABA gen
    public String  eftPaTfrFilename     = "";        // 10-char filename prefix override
    public String  paySrvBankInd        = "";        // pay service flag (NAB / Westpac etc.)
    public String  paySrvRemitterName   = "";        // 16-char remitter (col 97-112 on ABA detail)

    /** Pad the APCA user number to 6 digits left-zero. ABA col 57-62. */
    public String formattedUserNo() {
        return String.format("%06d", Math.max(0, userNo));
    }
}
