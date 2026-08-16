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
 * One row of {@code glcodes} (GLNM01 — Sub Account [code] Maintenance).
 *
 * <p>Not a child of {@code glchart} — this is a small, company-wide
 * reference list of valid 4-digit sub-account codes and their names, used
 * elsewhere (CPCM01 S2's description lookup, CPCM10's "S" bulk-create mode)
 * as a pick-list when building a full main+sub account combination. Deleting
 * a code here does not touch any {@code glchart} rows that already use it.
 */
public class GlCode {
    public int subAcct;
    public String desc1 = "";
    public String auditUserId = "";
}
