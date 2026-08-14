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
 * CPBA10 — one row of the P1 BAS-runs list, summarised from {@code cpbashd}.
 */
public record BasRun(String basGroup, int basNo, String statusLabel, String statusCode,
                      LocalDate fromDate, LocalDate toDate, BigDecimal netAmount, LocalDate dueDate) {

    public boolean isDraft() { return statusCode == null || statusCode.isBlank(); }
    public boolean isCommitted() { return "C".equals(statusCode); }
    public boolean isCancelled() { return "D".equals(statusCode); }
}
