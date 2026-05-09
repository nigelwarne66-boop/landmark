package com.example.fixedassets.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory replacement for the FATLWK2 Vision work file.
 *
 * COBOL FD source: fatlwk2.fd
 *
 * One instance per run. Holds:
 *   - The dynamic column headers (up to 200 in COBOL; practical limit ~50
 *     given FATLWK1-DEPN-AMT is 50 slots).
 *   - A copy of the selection parameters, used by FATL13 to populate the
 *     Excel legend section ("Assets in the range ... to ...").
 *
 * COBOL FATLWK2 had COL-HEAD (PIC 9(6) — day-number) and COL-LIT (PIC X(8)
 * — formatted date label) as separate parallel arrays of 200. Here they are
 * combined into a single PeriodColumn record for clarity.
 */
public class ProjectionHeader {

    /**
     * One entry per projected period column.
     *
     * COBOL parallel arrays:
     *   FATLWK2-COL-HEAD(n)  PIC 9(6)  — Julian day number (used as > ZERO sentinel)
     *   FATLWK2-COL-LIT(n)   PIC X(8)  — formatted label, e.g. "01/06/25"
     */
    public static class PeriodColumn {
        /** Day number (YYMMDD or ordinal) — non-zero = active column. */
        private final int    dayNumber;
        /** Formatted date label shown as Excel column heading. */
        private final String label;
        /** Full period-end date — used internally for calculations. */
        private final LocalDate periodEndDate;

        public PeriodColumn(int dayNumber, String label, LocalDate periodEndDate) {
            this.dayNumber     = dayNumber;
            this.label         = label;
            this.periodEndDate = periodEndDate;
        }

        public int       getDayNumber()    { return dayNumber; }
        public String    getLabel()        { return label; }
        public LocalDate getPeriodEndDate(){ return periodEndDate; }
    }

    // ── Dynamic column list ───────────────────────────────────────────────
    /** Active period columns, in chronological order. Max 50 (FATLWK1 array size). */
    private final List<PeriodColumn> columns = new ArrayList<>();

    // ── Selection parameters (mirrors FATLWK2-SELECTIONS) ────────────────
    private String    startAssetNo;
    private String    endAssetNo;
    private String    startLoc;
    private String    endLoc;
    private String    startGrp;
    private String    endGrp;
    private String    startSubgrp;
    private String    endSubgrp;
    private String    startDept;
    private String    endDept;
    /** FATLWK2-DEPN-THRU-TO-DATE  PIC 9(6) — projection horizon date */
    private LocalDate depnThruToDate;
    /** FATLWK2-PROJECTED-RATE     PIC 9(2)V99 */
    private java.math.BigDecimal projectedRate;
    /** FATLWK2-END-DATE           PIC 9(6) */
    private LocalDate endDate;
    /** FATLWK2-TAX-BOOK-IND       PIC X(1)  'B' or 'T' */
    private char      taxBookInd;

    // ── Column management ─────────────────────────────────────────────────

    /** Appends a new period column. Called during FATL12 LOOP-THRU-YEARS. */
    public void addColumn(int dayNumber, String label, LocalDate periodEndDate) {
        if (columns.size() >= 250) {
            throw new IllegalStateException("Maximum 250 period columns exceeded.");
        }
        columns.add(new PeriodColumn(dayNumber, label, periodEndDate));
    }

    /** Number of active period columns (equivalent to WS-NUMBER-OF-COLS - 14 in FATL13). */
    public int columnCount() { return columns.size(); }

    public List<PeriodColumn> getColumns() { return columns; }

    // ── Getters / setters ─────────────────────────────────────────────────

    public String getStartAssetNo()                            { return startAssetNo; }
    public void   setStartAssetNo(String v)                   { this.startAssetNo = v; }

    public String getEndAssetNo()                              { return endAssetNo; }
    public void   setEndAssetNo(String v)                     { this.endAssetNo = v; }

    public String getStartLoc()                                { return startLoc; }
    public void   setStartLoc(String v)                       { this.startLoc = v; }

    public String getEndLoc()                                  { return endLoc; }
    public void   setEndLoc(String v)                         { this.endLoc = v; }

    public String getStartGrp()                                { return startGrp; }
    public void   setStartGrp(String v)                       { this.startGrp = v; }

    public String getEndGrp()                                  { return endGrp; }
    public void   setEndGrp(String v)                         { this.endGrp = v; }

    public String getStartSubgrp()                             { return startSubgrp; }
    public void   setStartSubgrp(String v)                    { this.startSubgrp = v; }

    public String getEndSubgrp()                               { return endSubgrp; }
    public void   setEndSubgrp(String v)                      { this.endSubgrp = v; }

    public String getStartDept()                               { return startDept; }
    public void   setStartDept(String v)                      { this.startDept = v; }

    public String getEndDept()                                  { return endDept; }
    public void   setEndDept(String v)                         { this.endDept = v; }

    public LocalDate getDepnThruToDate()                        { return depnThruToDate; }
    public void      setDepnThruToDate(LocalDate v)            { this.depnThruToDate = v; }

    public java.math.BigDecimal getProjectedRate()              { return projectedRate; }
    public void                 setProjectedRate(java.math.BigDecimal v) { this.projectedRate = v; }

    public LocalDate getEndDate()                               { return endDate; }
    public void      setEndDate(LocalDate v)                   { this.endDate = v; }

    public char getTaxBookInd()                                 { return taxBookInd; }
    public void setTaxBookInd(char v)                          { this.taxBookInd = v; }
}
