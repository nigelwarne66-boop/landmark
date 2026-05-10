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
 * Batch header data returned from AcquisitionService.
 */
public class BatchInfo {
    public int    batchNo;
    public LocalDate batchDate;
    public String enteredBy = "";
    public String ref       = "";
    public String status    = "";  // ""=open, U=unposted, C=completed, P=posted

    /** True if batch is open and can be resumed (status blank or U). */
    public boolean isOpen() {
        return status == null || status.isBlank() || "U".equals(status.trim());
    }

    @Override public String toString() {
        return String.format("%-6d  %-12s  %-4s  %s",
            batchNo, batchDate != null ? batchDate : "", enteredBy, ref);
    }
}
