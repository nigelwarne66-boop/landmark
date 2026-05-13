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
 * PASU04 — Tax-scale header (pataxfl table).
 *
 * <p>PK: {@code (company_no, scale_no)}. {@code scale_no} uses the COBOL
 * convention:
 * <ul>
 *   <li>{@code "1".."6"} — standard PAYG tax scales (NAT_1004)</li>
 *   <li>{@code "H"} — HECS marker (legacy; deletion is blocked in COBOL)</li>
 *   <li>{@code "1S".."6S"} — STSL companion scales auto-created when an STSL
 *       rate table is entered against the matching base scale</li>
 * </ul>
 *
 * <p>The bracket coefficients themselves are <strong>not</strong> stored on
 * this row. Our MySQL extract drops the COBOL OCCURS columns
 * ({@code PATAXFL-TABLE} and {@code PATAXFL-21-26-TABLE}) — the engine can't
 * extract them within its OCCURS cap. Brackets live entirely in the
 * {@code tax_brackets} table, populated by PATX01 from the ATO Excel files.
 * PASU04's "Brackets" tab shows them read-only.
 */
public class TaxScale {

    public int        companyNo          = 0;
    public String     scaleNo            = "";

    public String     desc1              = "";
    public String     desc2              = "";
    /** Leave-loading earnings limit in cents (or dollars — confirm at calc-time). */
    public int        leaveLoadingLimit  = 0;
    /** Termination-payment tax %. */
    public BigDecimal termTaxPerc        = BigDecimal.ZERO;
    /** "Y" if this scale already includes the HECS/HELP component. */
    public String     includesHecsFlag   = "N";
    public BigDecimal fbtPercRate        = BigDecimal.ZERO;
    public BigDecimal fbtTaxableAmt      = BigDecimal.ZERO;
    /** "N" none, "U" round up, "D" round down. */
    public String     roundingInd        = "N";
    public long       noteNo             = 0;

    // ── Display helpers ──────────────────────────────────────────────────

    public String roundingLabel() {
        return switch (roundingInd == null ? "" : roundingInd) {
            case "U" -> "Round up";
            case "D" -> "Round down";
            default  -> "No rounding";
        };
    }

    public boolean isHecsMarker() { return "H".equalsIgnoreCase(scaleNo); }
}
