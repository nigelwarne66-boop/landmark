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

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Subset of FAASSET fields read by FATL12.
 *
 * Only fields actually referenced in fatl12.pl are mapped here.
 * Full FAASSET has ~80+ fields; unused ones are omitted intentionally.
 *
 * COBOL FD source: faasset.fd
 */
public class AssetRow {

    // ── Keys ─────────────────────────────────────────────────────────────
    /** FAASSET-ASSET-NO  PIC X(20) */
    private String assetNo;

    // ── Classification codes ─────────────────────────────────────────────
    /** FAASSET-LOC-CODE    PIC X(6) */
    private String locCode;
    /** FAASSET-GRP-CODE    PIC X(6) */
    private String grpCode;
    /** FAASSET-SUBGRP-CODE PIC X(6) */
    private String subgrpCode;
    /** FAASSET-DEPT-CODE   PIC X(6) */
    private String deptCode;

    // ── Status & identity ────────────────────────────────────────────────
    /** FAASSET-ASSET-STATUS  PIC X(1)  — 'A'=active, 'D'=disposed, etc. */
    private String assetStatus;
    /** FAASSET-DESC-1  PIC X(35) */
    private String desc1;

    // ── Acquisition ──────────────────────────────────────────────────────
    /** FAASSET-ACQN-DATE  PIC 9(6) COMP-3 — stored as YYMMDD */
    private LocalDate acqnDate;
    /** FAASSET-ACTUAL-COST  PIC 9(9)V99 COMP-3 */
    private BigDecimal actualCost;
    /** FAASSET-TAX-DEPN-COST  PIC 9(9)V99 COMP-3 */
    private BigDecimal taxDepnCost;
    /** FAASSET-BOOK-DEPN-COST PIC 9(9)V99 COMP-3 */
    private BigDecimal bookDepnCost;

    // ── Book depreciation data ────────────────────────────────────────────
    /** FAASSET-BOOK-DEPN-METHOD  PIC X(1)  'S'=straight-line, 'D'=diminishing */
    private String bookDepnMethod;
    /** FAASSET-BOOK-DEPN-CODE    PIC X(6) */
    private String bookDepnCode;
    /** FAASSET-BOOK-DEPN-FREQ    PIC 9(2) — periods per year */
    private int    bookDepnFreq;
    /** FAASSET-BOOK-DEPN-RATE-1  PIC 9(3)V99 */
    private BigDecimal bookDepnRate1;
    /** FAASSET-BOOK-DEPN-RATE-2  PIC 9(3)V99 — pooled assets only */
    private BigDecimal bookDepnRate2;
    /** FAASSET-BOOK-DEPN-CALC-IND   PIC X(1)  'D'=days,'W'=work-days,'F'=frequency */
    private String bookDepnCalcInd;
    /** FAASSET-BOOK-DEPN-CALC-BASE  PIC X(1)  'O'=opening balance, else cost */
    private String bookDepnCalcBase;
    /** FAASSET-START-DEPN-DATE      PIC 9(6) COMP-3 */
    private LocalDate startDepnDate;
    /** FAASSET-ACCUM-BOOK-DEPN      PIC 9(9)V99 COMP-3 */
    private BigDecimal accumBookDepn;
    /** FAASSET-ACCUM-BOOK-DEPN-ADJ  PIC S9(9)V99 COMP-3 */
    private BigDecimal accumBookDepnAdj;
    /** FAASSET-LAST-BOOK-DEPN-DATE  PIC 9(6) COMP-3 */
    private LocalDate lastBookDepnDate;

    // ── Tax depreciation data ─────────────────────────────────────────────
    /** FAASSET-TAX-DEPN-METHOD  PIC X(1) */
    private String taxDepnMethod;
    /** FAASSET-TAX-DEPN-CODE    PIC X(6) */
    private String taxDepnCode;
    /** FAASSET-TAX-DEPN-FREQ    PIC 9(2) */
    private int    taxDepnFreq;
    /** FAASSET-TAX-DEPN-RATE-1  PIC 9(3)V99 */
    private BigDecimal taxDepnRate1;
    /** FAASSET-TAX-DEPN-RATE-2  PIC 9(3)V99 */
    private BigDecimal taxDepnRate2;
    /** FAASSET-TAX-DEPN-CALC-IND   PIC X(1) */
    private String taxDepnCalcInd;
    /** FAASSET-TAX-DEPN-CALC-BASE  PIC X(1) */
    private String taxDepnCalcBase;
    /** FAASSET-START-TAX-DEPN-DATE PIC 9(6) COMP-3 */
    private LocalDate startTaxDepnDate;
    /** FAASSET-ACCUM-TAX-DEPN      PIC 9(9)V99 COMP-3 */
    private BigDecimal accumTaxDepn;
    /** FAASSET-ACCUM-TAX-DEPN-ADJ  PIC S9(9)V99 COMP-3 */
    private BigDecimal accumTaxDepnAdj;
    /** FAASSET-LAST-TAX-DEPN-DATE  PIC 9(6) COMP-3 */
    private LocalDate lastTaxDepnDate;

    // ── Revaluation ──────────────────────────────────────────────────────
    /** FAASSET-LAST-REVAL-DATE  PIC 9(6) COMP-3 */
    private LocalDate lastRevalDate;
    /** FAASSET-LAST-REVAL-VAL   PIC 9(9)V99 COMP-3 */
    private BigDecimal lastRevalVal;
    /** FAASSET-LAST-TAX-REVAL-DATE */
    private LocalDate lastTaxRevalDate;

    // ── Write-down ────────────────────────────────────────────────────────
    /** FAASSET-WRITE-DOWN-DATE  PIC 9(6) COMP-3 */
    private LocalDate writeDownDate;

    // ── Pooled asset ─────────────────────────────────────────────────────
    /** FAASSET-LEASED-ASSET-FLAG  PIC X(1)  'Y' = leased */
    private String leasedAssetFlag;

    /** FAASSET-ASSET-POOL-FLAG  PIC X(1)  'Y' = pooled */
    private String assetPoolFlag;
    /** FAASSET-POOL-BOOK-BAL    PIC S9(9)V99 COMP-3 */
    private BigDecimal poolBookBal;
    /** FAASSET-POOL-BOOK-BAL-DATE PIC 9(6) COMP-3 */
    private LocalDate poolBookBalDate;
    /** FAASSET-POOL-TAX-BAL     PIC S9(9)V99 COMP-3 */
    private BigDecimal poolTaxBal;
    /** FAASSET-POOL-TAX-BAL-DATE  PIC 9(6) COMP-3 */
    private LocalDate poolTaxBalDate;

    // ── Generated getters / setters ───────────────────────────────────────
    // (abbreviated — generate with IDE or Lombok @Data in production)

    public String getAssetNo()                        { return assetNo; }
    public void   setAssetNo(String v)               { this.assetNo = v; }

    public String getLocCode()                        { return locCode; }
    public void   setLocCode(String v)               { this.locCode = v; }

    public String getGrpCode()                        { return grpCode; }
    public void   setGrpCode(String v)               { this.grpCode = v; }

    public String getSubgrpCode()                     { return subgrpCode; }
    public void   setSubgrpCode(String v)            { this.subgrpCode = v; }

    public String getDeptCode()                       { return deptCode; }
    public void   setDeptCode(String v)              { this.deptCode = v; }

    public String getAssetStatus()                    { return assetStatus; }
    public void   setAssetStatus(String v)           { this.assetStatus = v; }

    public String getDesc1()                          { return desc1; }
    public void   setDesc1(String v)                 { this.desc1 = v; }

    public LocalDate getAcqnDate()                    { return acqnDate; }
    public void      setAcqnDate(LocalDate v)        { this.acqnDate = v; }

    public BigDecimal getActualCost()                 { return actualCost; }
    public void       setActualCost(BigDecimal v)    { this.actualCost = v; }

    public BigDecimal getTaxDepnCost()                { return taxDepnCost; }
    public void       setTaxDepnCost(BigDecimal v)   { this.taxDepnCost = v; }

    public BigDecimal getBookDepnCost()               { return bookDepnCost; }
    public void       setBookDepnCost(BigDecimal v)  { this.bookDepnCost = v; }

    public String getBookDepnMethod()                 { return bookDepnMethod; }
    public void   setBookDepnMethod(String v)        { this.bookDepnMethod = v; }

    public String getBookDepnCode()                   { return bookDepnCode; }
    public void   setBookDepnCode(String v)          { this.bookDepnCode = v; }

    public int    getBookDepnFreq()                   { return bookDepnFreq; }
    public void   setBookDepnFreq(int v)             { this.bookDepnFreq = v; }

    public BigDecimal getBookDepnRate1()              { return bookDepnRate1; }
    public void       setBookDepnRate1(BigDecimal v) { this.bookDepnRate1 = v; }

    public BigDecimal getBookDepnRate2()              { return bookDepnRate2; }
    public void       setBookDepnRate2(BigDecimal v) { this.bookDepnRate2 = v; }

    public String getBookDepnCalcInd()                { return bookDepnCalcInd; }
    public void   setBookDepnCalcInd(String v)       { this.bookDepnCalcInd = v; }

    public String getBookDepnCalcBase()               { return bookDepnCalcBase; }
    public void   setBookDepnCalcBase(String v)      { this.bookDepnCalcBase = v; }

    public LocalDate getStartDepnDate()               { return startDepnDate; }
    public void      setStartDepnDate(LocalDate v)   { this.startDepnDate = v; }

    public BigDecimal getAccumBookDepn()              { return accumBookDepn; }
    public void       setAccumBookDepn(BigDecimal v) { this.accumBookDepn = v; }

    public BigDecimal getAccumBookDepnAdj()                   { return accumBookDepnAdj; }
    public void       setAccumBookDepnAdj(BigDecimal v)       { this.accumBookDepnAdj = v; }

    public LocalDate getLastBookDepnDate()                    { return lastBookDepnDate; }
    public void      setLastBookDepnDate(LocalDate v)        { this.lastBookDepnDate = v; }

    public String getTaxDepnMethod()                          { return taxDepnMethod; }
    public void   setTaxDepnMethod(String v)                 { this.taxDepnMethod = v; }

    public String getTaxDepnCode()                            { return taxDepnCode; }
    public void   setTaxDepnCode(String v)                   { this.taxDepnCode = v; }

    public int    getTaxDepnFreq()                            { return taxDepnFreq; }
    public void   setTaxDepnFreq(int v)                      { this.taxDepnFreq = v; }

    public BigDecimal getTaxDepnRate1()                       { return taxDepnRate1; }
    public void       setTaxDepnRate1(BigDecimal v)          { this.taxDepnRate1 = v; }

    public BigDecimal getTaxDepnRate2()                       { return taxDepnRate2; }
    public void       setTaxDepnRate2(BigDecimal v)          { this.taxDepnRate2 = v; }

    public String getTaxDepnCalcInd()                         { return taxDepnCalcInd; }
    public void   setTaxDepnCalcInd(String v)                { this.taxDepnCalcInd = v; }

    public String getTaxDepnCalcBase()                        { return taxDepnCalcBase; }
    public void   setTaxDepnCalcBase(String v)               { this.taxDepnCalcBase = v; }

    public LocalDate getStartTaxDepnDate()                    { return startTaxDepnDate; }
    public void      setStartTaxDepnDate(LocalDate v)        { this.startTaxDepnDate = v; }

    public BigDecimal getAccumTaxDepn()                       { return accumTaxDepn; }
    public void       setAccumTaxDepn(BigDecimal v)          { this.accumTaxDepn = v; }

    public BigDecimal getAccumTaxDepnAdj()                    { return accumTaxDepnAdj; }
    public void       setAccumTaxDepnAdj(BigDecimal v)       { this.accumTaxDepnAdj = v; }

    public LocalDate getLastTaxDepnDate()                     { return lastTaxDepnDate; }
    public void      setLastTaxDepnDate(LocalDate v)         { this.lastTaxDepnDate = v; }

    public LocalDate getLastRevalDate()                       { return lastRevalDate; }
    public void      setLastRevalDate(LocalDate v)           { this.lastRevalDate = v; }

    public BigDecimal getLastRevalVal()                       { return lastRevalVal; }
    public void       setLastRevalVal(BigDecimal v)          { this.lastRevalVal = v; }

    public LocalDate getLastTaxRevalDate()                    { return lastTaxRevalDate; }
    public void      setLastTaxRevalDate(LocalDate v)        { this.lastTaxRevalDate = v; }

    public LocalDate getWriteDownDate()                       { return writeDownDate; }
    public void      setWriteDownDate(LocalDate v)           { this.writeDownDate = v; }

    public String getLeasedAssetFlag()                        { return leasedAssetFlag; }
    public void   setLeasedAssetFlag(String v)               { this.leasedAssetFlag = v; }

    public String getAssetPoolFlag()                          { return assetPoolFlag; }
    public void   setAssetPoolFlag(String v)                 { this.assetPoolFlag = v; }

    public BigDecimal getPoolBookBal()                        { return poolBookBal; }
    public void       setPoolBookBal(BigDecimal v)           { this.poolBookBal = v; }

    public LocalDate getPoolBookBalDate()                     { return poolBookBalDate; }
    public void      setPoolBookBalDate(LocalDate v)         { this.poolBookBalDate = v; }

    public BigDecimal getPoolTaxBal()                         { return poolTaxBal; }
    public void       setPoolTaxBal(BigDecimal v)            { this.poolTaxBal = v; }

    public LocalDate getPoolTaxBalDate()                      { return poolTaxBalDate; }
    public void      setPoolTaxBalDate(LocalDate v)          { this.poolTaxBalDate = v; }

    // ── Derived helpers ───────────────────────────────────────────────────

    /** Returns true when this is a pooled asset (FAASSET-ASSET-POOL-FLAG = 'Y') */
    public boolean isPooled() { return "Y".equals(assetPoolFlag); }

    /** Selects book or tax fields depending on stream indicator */
    public String depnMethod(char stream)  { return stream == 'T' ? taxDepnMethod  : bookDepnMethod;  }
    public String depnCalcInd(char stream) { return stream == 'T' ? taxDepnCalcInd : bookDepnCalcInd; }
    public String depnCalcBase(char stream){ return stream == 'T' ? taxDepnCalcBase: bookDepnCalcBase; }
    public int    depnFreq(char stream)    { return stream == 'T' ? taxDepnFreq    : bookDepnFreq;    }
    public BigDecimal depnRate1(char stream){ return stream == 'T' ? taxDepnRate1  : bookDepnRate1;   }
    public BigDecimal depnRate2(char stream){ return stream == 'T' ? taxDepnRate2  : bookDepnRate2;   }
    public LocalDate startDepnDate(char stream){ return stream=='T'? startTaxDepnDate : startDepnDate;}
    public BigDecimal accumDepn(char stream){ return stream == 'T' ? accumTaxDepn  : accumBookDepn;   }
    public BigDecimal depnCost(char stream) { return stream == 'T' ? taxDepnCost   : bookDepnCost;    }
    public LocalDate lastDepnDate(char stream){ return stream=='T'? lastTaxDepnDate: lastBookDepnDate;}
}
