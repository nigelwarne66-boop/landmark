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
 * CPBA10 — user input for {@code BasProcessingService.createBas}.
 *
 * <p>{@code fbtAmt}/{@code deferredImportTax}/{@code fuelTaxCreditOverclaim}/
 * {@code fuelTaxCredit} are the four optional manually-entered figures
 * (F1, 7A, 7C, 7D) offered on the Create dialog — zero means "not supplied",
 * matching COBOL's behaviour of only writing a synthetic {@code cpbastx} row
 * when the user actually keys a non-zero figure.
 */
public record CreateBasParams(String basGroup, LocalDate fromDate, LocalDate toDate,
                               String basIdNo, String abn, LocalDate dueDate, LocalDate payDate,
                               String includeGstFlag,
                               BigDecimal fbtAmt, BigDecimal deferredImportTax,
                               BigDecimal fuelTaxCreditOverclaim, BigDecimal fuelTaxCredit) {
}
