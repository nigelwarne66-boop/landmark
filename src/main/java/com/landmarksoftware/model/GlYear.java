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

import java.time.LocalDate;

/**
 * Domain model for a single GLDATES record (one row per fiscal year).
 *
 * COBOL FD source: gldates.fd
 *
 * Only the fields consumed by the FATL12 depreciation engine are mapped.
 * The full GLDATES record contains AP/AR/BA/CM/etc. module period pointers
 * which are irrelevant to FA projection.
 */
public class GlYear {

    // ── Keys ─────────────────────────────────────────────────────────────
    /**
     * GLDATES-YEAR-NO  PIC 9(4) — 4-digit fiscal year (primary key).
     * Also has GLDATES-YR-NO PIC 9(2) inside GLDATES-KEY (2-digit alternate).
     */
    private int yearNo;

    // ── Year boundary dates ───────────────────────────────────────────────
    /** GLDATES-YR-START-DATE  PIC 9(6) COMP-3 */
    private LocalDate yrStartDate;
    /** GLDATES-YR-END-DATE    PIC 9(6) COMP-3 */
    private LocalDate yrEndDate;

    // ── Period-end dates (13 periods max) ─────────────────────────────────
    /**
     * GLDATES-PERIOD-END OCCURS 13 TIMES  PIC 9(6) COMP-3
     *
     * Stored as a 13-element array. Unused trailing periods remain null/zero.
     * FATL12 uses these to:
     *   1. Validate the user-entered projection date (must match one entry).
     *   2. Drive the LOOP-THRU-EACH-YEAR column generation.
     */
    private LocalDate[] periodEnd   = new LocalDate[13];
    private LocalDate[] periodStart = new LocalDate[13];

    // ── Period status ────────────────────────────────────────────────────
    /**
     * GLDATES-PERIOD-STATUS OCCURS 13 TIMES  PIC X(1)
     * 'O'=open, 'C'=closed, etc.
     */
    private String[] periodStatus = new String[13];

    // ── Work-day units ────────────────────────────────────────────────────
    /**
     * GLDATES-UNIT OCCURS 13 TIMES  PIC S9(9)V99 COMP-3
     *
     * Work-day count for each period. Used when calc indicator = 'W'.
     * Equivalent to WS-TOTAL-YR-WORK-DAYS / WS-DEPN-WORK-DAYS in FATL12.
     */
    private double[] unit = new double[13];

    // ── Year-end status ───────────────────────────────────────────────────
    /** GLDATES-YR-END-STATUS  PIC 9(1) */
    private int yrEndStatus;

    // ── Convenience methods ───────────────────────────────────────────────

    /**
     * Returns the period-end date for the given 1-based period number.
     * Returns null if the period slot is unused (matches COBOL zero-value).
     */
    public LocalDate getPeriodEnd(int period1Based) {
        if (period1Based < 1 || period1Based > 13) return null;
        return periodEnd[period1Based - 1];
    }

    public void setPeriodEnd(int period1Based, LocalDate date) {
        if (period1Based < 1 || period1Based > 13)
            throw new IllegalArgumentException("Period must be 1..13");
        periodEnd[period1Based - 1] = date;
    }

    public LocalDate getPeriodStart(int period1Based) {
        if (period1Based < 1 || period1Based > 13) return null;
        return periodStart[period1Based - 1];
    }

    public void setPeriodStart(int period1Based, LocalDate date) {
        if (period1Based < 1 || period1Based > 13)
            throw new IllegalArgumentException("Period must be 1..13");
        periodStart[period1Based - 1] = date;
    }

    public String getPeriodStatus(int period1Based) {
        if (period1Based < 1 || period1Based > 13) return null;
        return periodStatus[period1Based - 1];
    }

    public void setPeriodStatus(int period1Based, String status) {
        if (period1Based < 1 || period1Based > 13)
            throw new IllegalArgumentException("Period must be 1..13");
        periodStatus[period1Based - 1] = status;
    }

    public double getUnit(int period1Based) {
        if (period1Based < 1 || period1Based > 13) return 0;
        return unit[period1Based - 1];
    }

    public void setUnit(int period1Based, double value) {
        if (period1Based < 1 || period1Based > 13)
            throw new IllegalArgumentException("Period must be 1..13");
        unit[period1Based - 1] = value;
    }

    /** Count of active (non-null) periods in this year. */
    public int activePeriodCount() {
        int count = 0;
        for (LocalDate d : periodEnd) if (d != null) count++;
        return count;
    }

    // ── Getters / setters ─────────────────────────────────────────────────

    public int        getYearNo()                    { return yearNo; }
    public void       setYearNo(int v)              { this.yearNo = v; }

    public LocalDate  getYrStartDate()               { return yrStartDate; }
    public void       setYrStartDate(LocalDate v)   { this.yrStartDate = v; }

    public LocalDate  getYrEndDate()                 { return yrEndDate; }
    public void       setYrEndDate(LocalDate v)     { this.yrEndDate = v; }

    public LocalDate[] getPeriodEndArray()           { return periodEnd; }
    public String[]    getPeriodStatusArray()        { return periodStatus; }
    public double[]    getUnitArray()                { return unit; }

    public int        getYrEndStatus()               { return yrEndStatus; }
    public void       setYrEndStatus(int v)         { this.yrEndStatus = v; }
}
