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

/**
 * CPBA10 — preview totals shown on the Create-BAS dialog before commit:
 * how many unposted {@code cpbastx} rows (for this BAS group, not yet
 * claimed by any BAS run) fall within the requested period and GST-include
 * setting, and their combined gross/tax.
 *
 * <p>Read-only estimate — does not include rows that would additionally be
 * pulled in via GST-group consolidation (see {@code cpgstgr}), which are
 * comparatively rare and whose totals only become known once the target
 * group is actually walked at creation time.
 */
public record BasPullPreview(int rowCount, BigDecimal totalGross, BigDecimal totalTax) {
}
