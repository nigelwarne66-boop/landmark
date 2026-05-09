package com.example.fixedassets.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * In-memory replacement for the FATLWK1 Vision work file.
 *
 * COBOL FD source: fatlwk1.fd
 *
 * One instance per selected asset. Holds the full depreciation array
 * across all projected periods, plus the opening/closing WDV values.
 *
 * FATLWK1-DEPN-AMT occurs 50 times — array is sized to match.
 * The active slot count is driven by ProjectionHeader.columnCount.
 */
public class ProjectionResult {

    // ── Identity (from FATLWK1-KEY + descriptor fields) ─────────────────
    /** FATLWK1-ASSET-NO    PIC X(20) */
    private String assetNo;
    /** FATLWK1-DESC        PIC X(35) */
    private String description;
    /** FATLWK1-LOC-CODE    PIC X(6) */
    private String locCode;
    /** FATLWK1-GRP-CODE    PIC X(6) */
    private String grpCode;
    /** FATLWK1-SUBGRP-CODE PIC X(6) */
    private String subgrpCode;
    /** FATLWK1-DEPT-CODE   PIC X(6) */
    private String deptCode;

    // ── Depreciation parameters carried forward for the report ────────────
    /** FATLWK1-DEPN-FREQ       PIC 9(2) */
    private int    depnFreq;
    /** FATLWK1-DEPN-RATE       PIC 9(3)V99 */
    private BigDecimal depnRate;
    /** FATLWK1-ACQN-DATE       PIC 9(6) */
    private LocalDate acqnDate;
    /** FATLWK1-ACTUAL-COST     PIC S9(9)V99 */
    private BigDecimal actualCost;
    /** FATLWK1-WRITE-DOWN-DATE PIC 9(6) */
    private LocalDate writeDownDate;
    /** FATLWK1-LAST-DEPN-DATE  PIC 9(6) */
    private LocalDate lastDepnDate;
    /** FATLWK1-PROJECTED-RATE  PIC 9(2)V99 */
    private BigDecimal projectedRate;

    // ── Calculated values ─────────────────────────────────────────────────
    /**
     * FATLWK1-OPENING-WDV  PIC S9(9)V99
     *
     * Written-down value at the start of the projection.
     * = cost - accumulated depreciation (adjusted for opening-balance calc base).
     */
    private BigDecimal openingWdv;

    /**
     * FATLWK1-DEPN-AMT OCCURS 50 TIMES  PIC S9(10)V99
     *
     * Depreciation amount for each projected period column.
     * Index 0 corresponds to column 1 (first period after projection start).
     * Populated by DepreciationProjectionService during LOOP-THRU-YEARS.
     */
    private BigDecimal[] depnAmt = new BigDecimal[250];

    /**
     * FATLWK1-TOTAL-DEPN  PIC S9(10)V99
     *
     * Sum of all depnAmt entries.
     */
    private BigDecimal totalDepn = BigDecimal.ZERO;

    /**
     * FATLWK1-PROJ-CLOSING-WDV  PIC S9(10)V99
     *
     * openingWdv - totalDepn
     */
    private BigDecimal projClosingWdv = BigDecimal.ZERO;

    // ── Constructor ───────────────────────────────────────────────────────

    public ProjectionResult() {
        // Pre-fill array with zeros (mirrors COBOL INITIALIZE)
        for (int i = 0; i < depnAmt.length; i++) {
            depnAmt[i] = BigDecimal.ZERO;
        }
    }

    // ── Convenience mutators ──────────────────────────────────────────────

    /**
     * Accumulates an amount into the specified period slot (1-based, matching COBOL subscript).
     * Also updates totalDepn and recomputes projClosingWdv.
     */
    public void addDepnForPeriod(int periodIndex1Based, BigDecimal amount) {
        if (periodIndex1Based < 1 || periodIndex1Based > depnAmt.length) {
            throw new IllegalArgumentException(
                "Period index out of range [1..250]: " + periodIndex1Based);
        }
        depnAmt[periodIndex1Based - 1] = depnAmt[periodIndex1Based - 1].add(amount);
        totalDepn = totalDepn.add(amount);
        projClosingWdv = openingWdv == null ? totalDepn.negate()
                                            : openingWdv.subtract(totalDepn);
    }

    /** Called after openingWdv is set, before period amounts are accumulated. */
    public void initialiseTotals() {
        totalDepn     = BigDecimal.ZERO;
        projClosingWdv = openingWdv != null ? openingWdv : BigDecimal.ZERO;
        for (int i = 0; i < depnAmt.length; i++) depnAmt[i] = BigDecimal.ZERO;
    }

    // ── Getters / setters ─────────────────────────────────────────────────

    public String getAssetNo()                             { return assetNo; }
    public void   setAssetNo(String v)                    { this.assetNo = v; }

    public String getDescription()                         { return description; }
    public void   setDescription(String v)                { this.description = v; }

    public String getLocCode()                             { return locCode; }
    public void   setLocCode(String v)                    { this.locCode = v; }

    public String getGrpCode()                             { return grpCode; }
    public void   setGrpCode(String v)                    { this.grpCode = v; }

    public String getSubgrpCode()                          { return subgrpCode; }
    public void   setSubgrpCode(String v)                 { this.subgrpCode = v; }

    public String getDeptCode()                            { return deptCode; }
    public void   setDeptCode(String v)                   { this.deptCode = v; }

    public int    getDepnFreq()                            { return depnFreq; }
    public void   setDepnFreq(int v)                      { this.depnFreq = v; }

    public BigDecimal getDepnRate()                        { return depnRate; }
    public void       setDepnRate(BigDecimal v)           { this.depnRate = v; }

    public LocalDate getAcqnDate()                         { return acqnDate; }
    public void      setAcqnDate(LocalDate v)             { this.acqnDate = v; }

    public BigDecimal getActualCost()                      { return actualCost; }
    public void       setActualCost(BigDecimal v)         { this.actualCost = v; }

    public LocalDate getWriteDownDate()                    { return writeDownDate; }
    public void      setWriteDownDate(LocalDate v)        { this.writeDownDate = v; }

    public LocalDate getLastDepnDate()                     { return lastDepnDate; }
    public void      setLastDepnDate(LocalDate v)         { this.lastDepnDate = v; }

    public BigDecimal getProjectedRate()                   { return projectedRate; }
    public void       setProjectedRate(BigDecimal v)      { this.projectedRate = v; }

    public BigDecimal getOpeningWdv()                      { return openingWdv; }
    public void       setOpeningWdv(BigDecimal v)         { this.openingWdv = v; }

    public BigDecimal[] getDepnAmt()                       { return depnAmt; }

    public BigDecimal getTotalDepn()                       { return totalDepn; }
    public void       setTotalDepn(BigDecimal v)          { this.totalDepn = v; }

    public BigDecimal getProjClosingWdv()                  { return projClosingWdv; }
    public void       setProjClosingWdv(BigDecimal v)     { this.projClosingWdv = v; }
}
