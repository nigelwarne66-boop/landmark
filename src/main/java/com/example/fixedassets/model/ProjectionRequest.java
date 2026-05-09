package com.example.fixedassets.model;

import java.time.LocalDate;

/**
 * Encapsulates all parameters entered on the FATL12S0 parameter screen.
 *
 * COBOL source: fatl12s0.sd / fatl12s0.pl
 *
 * Screen field mapping:
 *   WS-START-ASSET-NO        → startAssetNo
 *   WS-END-ASSET-NO          → endAssetNo
 *   WS-START-LOC             → startLocation
 *   WS-END-LOC               → endLocation
 *   WS-START-GRP             → startGroup
 *   WS-END-GRP               → endGroup
 *   WS-START-SUBGRP          → startSubGroup
 *   WS-END-SUBGRP            → endSubGroup
 *   WS-START-DEPT            → startDept
 *   WS-END-DEPT              → endDept
 *   WS-TAX-BOOK-IND          → taxOrBook  ('B' or 'T')
 *   WS-DEPRECIATION-DATE     → projectedToDate  (must match a GL period-end date)
 *   WS-PROJ-RATE             → projectedRate  (0 = use master file rate)
 */
public class ProjectionRequest {

    // ── Asset number range ──────────────────────────────────────────────
    private String startAssetNo = " ";                 // PIC X(20) LOW-VALUES default
    private String endAssetNo   = "~~~~~~~~~~~~~~~~~~~~"; // PIC X(20) HIGH-VALUES default

    // ── Location range ──────────────────────────────────────────────────
    private String startLocation = " ";
    private String endLocation   = "~~~~~~";

    // ── Group range ─────────────────────────────────────────────────────
    private String startGroup = " ";
    private String endGroup   = "~~~~~~";

    // ── Sub-group range ─────────────────────────────────────────────────
    private String startSubGroup = " ";
    private String endSubGroup   = "~~~~~~";

    // ── Department range ────────────────────────────────────────────────
    private String startDept = " ";
    private String endDept   = "~~~~~~";

    // ── Depreciation stream ─────────────────────────────────────────────
    /** 'B' = Book depreciation (default), 'T' = Tax depreciation */
    private char taxOrBook = 'B';

    // ── Projection horizon ──────────────────────────────────────────────
    /**
     * Must correspond to a GLDATES period-end date.
     * COBOL validates against GLDATES-PERIOD-END array.
     */
    private LocalDate projectedToDate;

    // ── Override rate ───────────────────────────────────────────────────
    /**
     * Override depreciation rate (%). Zero means use the master-file rate.
     * COBOL: WS-PROJ-RATE  PIC 9(2)V99
     */
    private java.math.BigDecimal projectedRate = java.math.BigDecimal.ZERO;

    // ── Session context (populated from GLPASS) ─────────────────────────
    private int companyNo;
    private int yearNo;
    private int batchNo;

    // ── Getters / setters ───────────────────────────────────────────────

    public String getStartAssetNo()              { return startAssetNo; }
    public void   setStartAssetNo(String v)      { this.startAssetNo = v; }

    public String getEndAssetNo()                { return endAssetNo; }
    public void   setEndAssetNo(String v)        { this.endAssetNo = v; }

    public String getStartLocation()             { return startLocation; }
    public void   setStartLocation(String v)     { this.startLocation = v; }

    public String getEndLocation()               { return endLocation; }
    public void   setEndLocation(String v)       { this.endLocation = v; }

    public String getStartGroup()                { return startGroup; }
    public void   setStartGroup(String v)        { this.startGroup = v; }

    public String getEndGroup()                  { return endGroup; }
    public void   setEndGroup(String v)          { this.endGroup = v; }

    public String getStartSubGroup()             { return startSubGroup; }
    public void   setStartSubGroup(String v)     { this.startSubGroup = v; }

    public String getEndSubGroup()               { return endSubGroup; }
    public void   setEndSubGroup(String v)       { this.endSubGroup = v; }

    public String getStartDept()                 { return startDept; }
    public void   setStartDept(String v)         { this.startDept = v; }

    public String getEndDept()                   { return endDept; }
    public void   setEndDept(String v)           { this.endDept = v; }

    public char   getTaxOrBook()                 { return taxOrBook; }
    public void   setTaxOrBook(char v)           { this.taxOrBook = v; }

    public LocalDate getProjectedToDate()        { return projectedToDate; }
    public void      setProjectedToDate(LocalDate v) { this.projectedToDate = v; }

    public java.math.BigDecimal getProjectedRate()        { return projectedRate; }
    public void                 setProjectedRate(java.math.BigDecimal v) { this.projectedRate = v; }

    public int  getCompanyNo()                   { return companyNo; }
    public void setCompanyNo(int v)              { this.companyNo = v; }

    public int  getYearNo()                      { return yearNo; }
    public void setYearNo(int v)                 { this.yearNo = v; }

    public int  getBatchNo()                     { return batchNo; }
    public void setBatchNo(int v)                { this.batchNo = v; }
}
