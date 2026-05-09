package com.example.fixedassets.ui;

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
