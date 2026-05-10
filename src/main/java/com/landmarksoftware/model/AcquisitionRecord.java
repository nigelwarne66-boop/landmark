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
 * Data transfer object carrying all acquisition fields from the
 * FAAQ01 entry dialog to AcquisitionService.
 *
 * All fields are plain Java types — no JavaFX, no SQL types.
 * BigDecimal for all monetary/rate values; LocalDate for all dates.
 * String fields use empty string, never null.
 */
public class AcquisitionRecord {

    // ── Identity ──────────────────────────────────────────────
    public String assetNo    = "";
    public String desc1      = "";
    public String desc2      = "";
    public String alpha      = "";

    // ── Location / classification ─────────────────────────────
    public String locCode    = "";
    public String deptCode   = "";
    public String grpCode    = "";
    public String subgrpCode = "";
    public String site       = "";
    public String attachTo   = "";

    // ── Asset flags ───────────────────────────────────────────
    public boolean pooled    = false;
    public int     qty       = 1;
    public LocalDate acqnDate;
    public String acqnType   = "P";
    public String intOrder   = "";

    // ── Supplier / other ─────────────────────────────────────
    public String suppName   = "";
    public String suppNo     = "";
    public String invoiceNo  = "";

    // ── Lease ────────────────────────────────────────────────
    public boolean leased     = false;
    public String contractNo  = "";
    public BigDecimal payAmt  = BigDecimal.ZERO;
    public String payFreq     = "";
    public LocalDate lseExpiry;
    public BigDecimal residual    = BigDecimal.ZERO;
    public BigDecimal contractVal = BigDecimal.ZERO;
    public BigDecimal disposalVal = BigDecimal.ZERO;

    // ── Insurance ─────────────────────────────────────────────
    public String insType      = "";
    public BigDecimal currIns  = BigDecimal.ZERO;
    public BigDecimal replNew  = BigDecimal.ZERO;
    public LocalDate replAsAt;

    // ── Cost ──────────────────────────────────────────────────
    public String ref          = "";
    public BigDecimal actualCost    = BigDecimal.ZERO;
    public BigDecimal taxDepnCost   = BigDecimal.ZERO;
    public BigDecimal bookDepnCost  = BigDecimal.ZERO;
    public LocalDate writeDown;

    // ── Tax depreciation ──────────────────────────────────────
    public String taxMethod    = "";
    public LocalDate startTax;
    public String taxCode      = "";
    public BigDecimal taxRate1 = BigDecimal.ZERO;
    public BigDecimal taxRate2 = BigDecimal.ZERO;
    public String taxCalcInd   = "";
    public String taxCalcBase  = "";
    public int    taxFreq      = 1;

    // ── Book depreciation ─────────────────────────────────────
    public String bookMethod    = "";
    public LocalDate startBook;
    public String bookCode      = "";
    public BigDecimal bookRate1 = BigDecimal.ZERO;
    public BigDecimal bookRate2 = BigDecimal.ZERO;
    public String bookCalcInd   = "";
    public String bookCalcBase  = "";
    public int    bookFreq      = 1;

    // ── GL posting ────────────────────────────────────────────
    public String postTo      = "N";   // N=none, C=cost ledger, B=BA ledger
    public String ledgerCode  = "";

    // ── Opening balances (S3 tab) — written to FAASSET on Add ─
    public LocalDate  lastTaxDepnDate;
    public BigDecimal accumTaxDepn     = BigDecimal.ZERO;
    public BigDecimal accumTaxAdj      = BigDecimal.ZERO;
    public LocalDate  lastTaxRevalDate;
    public BigDecimal lastTaxRevalVal  = BigDecimal.ZERO;

    public LocalDate  lastBookDepnDate;
    public BigDecimal accumBookDepn    = BigDecimal.ZERO;
    public BigDecimal accumBookAdj     = BigDecimal.ZERO;
    public LocalDate  lastBookRevalDate;
    public BigDecimal lastBookRevalVal = BigDecimal.ZERO;

    public BigDecimal poolTaxBal  = BigDecimal.ZERO;
    public BigDecimal poolBookBal = BigDecimal.ZERO;

    // ── Bar codes (P3 tab) ────────────────────────────────────
    public String barCodes = "";
}
