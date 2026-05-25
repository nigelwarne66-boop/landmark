package com.landmarksoftware.ui.reports;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * One gldates row, formatted for display in a ComboBox.
 * toString() renders as "Period N — MMM yyyy" (e.g. "Period 7 — Jan 2026").
 */
public record PeriodOption(int periodNo, LocalDate endDate) {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MMM yyyy");

    @Override
    public String toString() {
        return "Period " + periodNo + (endDate == null ? "" : " — " + endDate.format(FMT));
    }
}
