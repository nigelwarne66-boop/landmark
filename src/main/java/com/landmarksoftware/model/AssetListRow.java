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

/**
 * One row in the FAAS01-P1 asset listbox.
 * Maps directly to FAAS01P1 display columns:
 *   Asset No | Description | Loc | Dept | Grp | SubGrp | Status
 *
 * COBOL WS-P1-DISPLAY-STATUS is computed by SET-P1-DISPLAYS:
 *   blank → (empty — active)
 *   U     → "unposted"
 *   H     → "on hold"
 *   N     → "not in use"
 *   R     → "retired"
 */
public record AssetListRow(
    String assetNo,
    String desc1,
    String locCode,
    String deptCode,
    String grpCode,
    String subgrpCode,
    String assetStatus,
    boolean isPooled
) {
    /** Display string for the Status column, matching COBOL SET-P1-DISPLAYS */
    public String statusDisplay() {
        if (assetStatus == null || assetStatus.isBlank()) return "";
        return switch (assetStatus.trim()) {
            case "U" -> "unposted";
            case "H" -> "on hold";
            case "N" -> "not in use";
            case "R" -> "retired";
            default  -> assetStatus;
        };
    }
}
