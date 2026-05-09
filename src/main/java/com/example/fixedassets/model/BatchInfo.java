package com.example.fixedassets.model;

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
