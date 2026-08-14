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
package com.landmarksoftware.model.bas;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CPBA07 — BAS Transaction (Tax Transaction) Maintenance record ({@code cpbastx}).
 *
 * <p>PK is the composite {@code (trxDate, dateAdded, timeAdded)} — all three
 * are system-assigned at insert time (never user-editable) and must be
 * unique; {@code timeAdded} packs the clock as {@code HHMMSShh} (hundredths)
 * to keep same-second inserts from colliding.
 *
 * <p>{@code trxDate} itself IS user-editable after insert — it is the
 * transaction's business date, distinct from the {@code dateAdded} audit
 * timestamp.
 *
 * <p>Most rows are written automatically by other modules (AR/AP/CM/Payroll
 * posting) — those carry a non-blank {@code source}. This screen also lets a
 * user hand-key/correct rows, which are distinguished by a blank
 * {@code source}. See {@link #isSystemSourced()} and the delete-guard notes
 * on {@code BasTransactionService}.
 */
public class BasTransaction {

    // ── Primary key — system-assigned, immutable after insert ──────────────
    public LocalDate trxDate   = LocalDate.now();   // user-editable business date
    public LocalDate dateAdded;                     // null until saved
    public Integer   timeAdded;                     // null until saved — packed HHMMSShh

    // ── Editable fields ──────────────────────────────────────────────────
    public LocalDate  postingDate      = LocalDate.now();
    public BigDecimal taxGrossAmt      = BigDecimal.ZERO;
    public BigDecimal taxAmt           = BigDecimal.ZERO;
    public String     ref1             = "";
    public String     ref2             = "";
    public String     basGroup         = "";
    public String     basCode          = "";
    /** 0 = no company attached. */
    public int         companyNo        = 0;
    public Integer     taxClearingMain  = 0;
    public Integer     taxClearingSub   = 0;

    // ── Read-only — shown for context when editing an existing row ────────
    public int         subCoyNo         = 0;
    /** Non-blank ⇒ row was written by an automated posting (AR/AP/CM/Payroll), not hand-keyed. */
    public String       source           = "";
    public int          batchNo          = 0;
    public BigDecimal   trxGrossAmt      = BigDecimal.ZERO;

    public String custNo         = "";
    public String custDocType    = "";
    public String custDocNo      = "";

    public String supplierNo       = "";
    public String supplierDocType  = "";
    public String supplierDocNo    = "";

    public String cmtransBankCode = "";
    public String cmtransDocType  = "";
    public int    cmtransDocNo    = 0;

    /** True when this row was created by another module's posting, not hand-keyed via CPBA07. */
    public boolean isSystemSourced() {
        return companyNo > 0 && source != null && !source.trim().isEmpty();
    }

    /** "SOURCE-BATCH_NO" display, or "" when the row has no journal link. */
    public String journalId() {
        if (companyNo <= 0 || source == null || source.trim().isEmpty()) return "";
        return source.trim() + "-" + batchNo;
    }

    /** Whichever party is populated — customer takes precedence per COBOL. */
    public boolean hasCustomer()  { return custNo != null && !custNo.trim().isEmpty(); }
    public boolean hasSupplier()  { return !hasCustomer() && supplierNo != null && !supplierNo.trim().isEmpty(); }
}
