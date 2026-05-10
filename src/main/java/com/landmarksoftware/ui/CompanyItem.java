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
package com.landmarksoftware.ui;

/**
 * Simple display item for the company combo-box.
 * Shared across all parameter screens (FATL12, FATL02, FATL03 etc.)
 */
public record CompanyItem(int companyNo, String displayName) {
    @Override
    public String toString() {
        return companyNo + "  " + (displayName != null ? displayName : "");
    }
}
