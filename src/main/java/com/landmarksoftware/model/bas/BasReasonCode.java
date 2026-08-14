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

/**
 * CPBA02 — a single BAS variation-reason code row from {@code cpbascd}.
 *
 * <p>{@code cpbascd} is a global table (no company_no) holding rows for
 * several ATO BAS categories — this screen only ever reads/writes rows
 * where {@code basCode} is {@code "T4"} (Income Tax Instalment Variation
 * Reason Codes) or {@code "F4"} (Fringe Benefits Tax Instalment Variation
 * Reason Codes). PK: {@code (basCode, reasonCode)}.
 */
public record BasReasonCode(String basCode, String reasonCode, String desc1, String desc2) {

    /** Display description — desc_1 and desc_2 concatenated with a space. */
    public String description() {
        String d1 = desc1 == null ? "" : desc1.trim();
        String d2 = desc2 == null ? "" : desc2.trim();
        if (d1.isEmpty()) return d2;
        if (d2.isEmpty()) return d1;
        return d1 + " " + d2;
    }
}
