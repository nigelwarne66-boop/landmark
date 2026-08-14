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

import java.time.LocalDate;

/**
 * CPBA01 — BAS Report Group header ({@code cpbasgr} table).
 *
 * <p>PK: {@code bas_group} (3 chars, globally unique — not scoped by
 * company_no in the DB, though the P1 list screen filters to the current
 * session company for display).
 *
 * <p>A BAS group is "owned" by one company/sub-company
 * ({@link #companyNo}/{@link #subCoyNo}); the owner also gets an entry in
 * {@code cpbasco} (mirrored alt keys) and, if the owner doesn't already have
 * a {@code cpsubcy} row, one is created purely to carry
 * {@link #taxPaidAcctMain}/{@link #varianceAcctMain} (physically stored on
 * {@code cpsubcy} even though conceptually part of the group header).
 *
 * <p>{@link #lastBasNoUsed}, {@link #lastBasNo}, {@link #currentBasNo},
 * {@link #lastStartDate}, {@link #lastEndDate} are system-managed — display
 * only, never editable from this screen.
 */
public class BasGroup {

    // ── PK / owner ──────────────────────────────────────────────────────
    public String basGroup = "";
    public int    companyNo = 0;
    public int    subCoyNo  = 0;

    // ── Editable config ─────────────────────────────────────────────────
    public String basGroupName = "";
    /** "M" monthly, "Q" quarterly. */
    public String basFreq = "M";
    /** "G" this group reports its own GST, "C" consolidated into another group. */
    public String gstGroupConsolInd = "G";
    /** "M" monthly, "Q" quarterly — only relevant when gstGroupConsolInd = 'G'. */
    public String gstFreq = "M";
    /** Plain code field — "G" gross entered / "T" tax x 11 (label only, no further meaning enforced here). */
    public String gstMethod = "G";
    /** "P" posting date, "T" transaction date. Defaults to "P" on Add. */
    public String trxPostingDateInd = "P";

    // ── GL accounts — physically on the owner's cpsubcy row ─────────────
    public int taxPaidAcctMain  = 0;
    public int taxPaidAcctSub   = 0;
    public int varianceAcctMain = 0;
    public int varianceAcctSub  = 0;

    // ── System-managed — display only ───────────────────────────────────
    public int       lastBasNoUsed = 0;
    public int       lastBasNo     = 0;
    public int       currentBasNo  = 0;
    public LocalDate lastStartDate;
    public LocalDate lastEndDate;

    public long noteNo = 0L;
}
