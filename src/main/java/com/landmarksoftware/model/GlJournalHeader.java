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
import java.util.ArrayList;
import java.util.List;

/**
 * A GL journal header — a row of {@code glgnhed} (GLGN01) plus its lines.
 *
 * <p>Two modes, discriminated by {@link #jnlType}: {@code "G"} = General
 * journal, {@code "S"} = Standing journal (a template that a separate posting
 * process expands into real journals per {@link #postFreq} until
 * {@link #expiryDate}). Journals are captured as drafts; a separate commit
 * (posting) step writes them to the ledger.
 */
public class GlJournalHeader {

    /** glgnhed.jnl_type — "G" general, "S" standing. */
    public String jnlType = "G";

    /** glgnhed.yr_no — 2-digit fiscal year (part of the key). */
    public int yrNo;

    /** glgnhed.jnl_no — allocated from the year counter on save. */
    public int jnlNo;

    /** glgnhed.jnl_date. */
    public LocalDate jnlDate;

    /** glgnhed.auto_reverse_flag — "Y" = auto-reverse next period. */
    public String autoReverseFlag = "N";

    /** glgnhed.note_1 / note_2 — two 55-char narration lines. */
    public String note1 = "";
    public String note2 = "";

    /** glgnhed.jnl_status — " " draft, "C" cancelled, "P" committing/committed, "U" in-use, "X" expired (standing). */
    public String jnlStatus = " ";

    /** glgnhed.audit_user_id — who last saved this journal. Populated by the Inquiry search; blank elsewhere. */
    public String auditUserId = "";

    /** glgnhed.total_dr / total_cr — maintained from the lines. */
    public BigDecimal totalDr = BigDecimal.ZERO;
    public BigDecimal totalCr = BigDecimal.ZERO;

    // ── Standing-journal only ────────────────────────────────────────────
    /** glgnhed.post_freq — M/B/Q/H/A/O. */
    public String postFreq = "";
    /** glgnhed.expiry_date — stop generating after this date. */
    public LocalDate expiryDate;

    /** The journal's lines. */
    public List<GlJournalLine> lines = new ArrayList<>();

    public boolean isStanding() { return "S".equals(jnlType); }

    /** DR − CR; zero when balanced. The posting step rejects non-zero. */
    public BigDecimal balance() {
        return totalDr.subtract(totalCr);
    }

    public boolean isBalanced() {
        return balance().compareTo(BigDecimal.ZERO) == 0;
    }

    /** Recompute totals from the current lines. */
    public void recomputeTotals() {
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (GlJournalLine l : lines) {
            if (l.drAmt != null) dr = dr.add(l.drAmt);
            if (l.crAmt != null) cr = cr.add(l.crAmt);
        }
        totalDr = dr;
        totalCr = cr;
    }
}
