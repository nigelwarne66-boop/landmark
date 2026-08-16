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
package com.landmarksoftware.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of {@code glchart} (CPCM01 — Chart of Accounts Maintenance).
 *
 * <p>{@code acctSubNo == 0} is the main account (CPCM01 S1 "MAIN-ACCOUNT"
 * section); {@code acctSubNo > 0} is a sub-account under that main account
 * (CPCM01 S2 "SUB-ACCOUNT" section). Both live in the same table/row shape —
 * only the field set exposed on each screen differs, mirrored here as two
 * logical groups of fields on one class rather than a subtype per COBOL's
 * own S1/S2 split.
 *
 * <p>Fields are the screen-exposed subset confirmed against cpcm01s1/s2 —
 * glchart has further columns (alt_gl_code, alpha/alt account pointers, etc.)
 * that are alternate-index housekeeping, not maintained fields; writes carry
 * them forward unchanged (mirrors the PAPG01 S2 convention — see CLAUDE.md).
 */
public class GlChartAccount {

    /** COBOL date-zero sentinel for NOT NULL DATE columns with no value (last_jnl_date, audit_date). */
    public static final LocalDate DATE_ZERO = LocalDate.of(1899, 12, 31);

    public int acctMainNo;
    public int acctSubNo;

    // ── Common to both main and sub accounts ────────────────────────────
    public String desc1 = "";
    public String abbrevDesc = "";
    public String alphaCode = "";

    // ── Main account (S1) ────────────────────────────────────────────────
    public String acctType = "";
    public String drCrInd = "D";              // D debit-normal, C credit-normal
    public String finAcctFlag = "N";
    public String plBsInd = "B";               // P profit&loss, B balance sheet
    public String forCurrRevalueInd = "N";
    public String cashAcctFlag = "N";
    public String statAcctFlag = "N";
    public String rollStatOpenBals = "N";
    public String defAnalysisCodeInd = "N";
    public String allNonPostingFlag = "N";     // Y = header/group account, not postable

    // ── Sub account (S2) ─────────────────────────────────────────────────
    public int counterBalMainNo;
    public int counterBalSubNo;
    public int consolMainNo;
    public int consolSubNo;
    public String distType = "";
    public String distCode = "";
    public int allocMainNo;
    public int allocSubNo;
    public String costAllocAcctType = "";
    public String allocMethod = "";
    public String prepaymentAcctFlag = "N";
    public BigDecimal gstPercClaimable = BigDecimal.ZERO;
    public String compressCode = "";
    public String useReconIdsFlag = "N";
    public String postingFlag = "";            // blank = normal posting, N/S per glchart convention
    public int subCoyNo;
    public int cashAcctBankNo;
    public String analysisCodeInd = "N";
    public String guardianExportFlag = "N";

    // ── System-owned (round-trip only — never edited on screen) ─────────
    public LocalDate lastJnlDate;
    public int lastReconNo;
    public String auditUserId = "";
    public LocalDate auditDate;

    public boolean isMainAccount() { return acctSubNo == 0; }

    public String accountDisplay() {
        return acctSubNo == 0 ? String.valueOf(acctMainNo) : acctMainNo + "." + acctSubNo;
    }

    /** Has any journal ever posted to this account (CHECK-FOR-JNLS-POSTED)? */
    public boolean hasPostedJournals() {
        return lastJnlDate != null && lastJnlDate.isAfter(DATE_ZERO);
    }
}
