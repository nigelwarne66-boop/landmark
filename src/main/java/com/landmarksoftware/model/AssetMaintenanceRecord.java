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
 * Full FAASSET record for the FAAS01 Asset Maintenance program.
 *
 * This is the update-capable companion to AssetRow (which is read-only for reports).
 * All FAASSET fields required by S1, S1A, S1B, S2, S3 screens are represented here.
 *
 * Status values (FAASSET-ASSET-STATUS):
 *   blank = ACTIVE
 *   U     = UNPOSTED (cannot edit unless pooled & pool-acqn-posted)
 *   H     = ON HOLD
 *   N     = NOT IN USE
 *   R     = RETIRED
 *
 * Pool flag (FAASSET-ASSET-POOL-FLAG):
 *   Y = pooled asset — depn fields disabled on S1B, pool-balance fields shown
 *
 * Leased flag (FAASSET-LEASED-ASSET-FLAG):
 *   Y = leased — all depreciation fields disabled on S1B
 */
public class AssetMaintenanceRecord {

    // ── Primary key ───────────────────────────────────────────────────
    private int    companyNo;
    private String assetNo = "";

    // ── S1 — Main edit screen ─────────────────────────────────────────
    private String desc1         = "";
    private String desc2         = "";
    private String alphaCode     = "";
    private String locCode       = "";
    private String deptCode      = "";
    private String grpCode       = "";
    private String subgrpCode    = "";
    private String stakeSite     = "";
    private String attachToAssetNo = "";
    private String assetPoolFlag = "";
    private BigDecimal qty       = BigDecimal.ZERO;
    private LocalDate acqnDate;
    private String acqnType      = "";
    private String internalOrderNo = "";
    // Overseas parent fields (visible only if GLPASS-OVERSEAS-PARENT-FLAG = Y)
    private String parentRateInd = "";           // C=current, P=purchase
    private BigDecimal parentRateCurr = BigDecimal.ZERO;
    private String parentFcMathsInd = "";        // M=multiply, D=divide

    // ── S1A — Other details ───────────────────────────────────────────
    private String supplierName   = "";
    private String supplierNo     = "";
    private String invoiceNo      = "";
    private String leasedAssetFlag = "";         // Y/N
    // Lease details (enabled only if leased=Y)
    private LocalDate lseExpiryDate;
    private String lseContractNo  = "";
    private BigDecimal lsePaymentAmt = BigDecimal.ZERO;
    private String lsePaymentFreq = "";
    // Insurance details
    private BigDecimal currentInsValue = BigDecimal.ZERO;
    private BigDecimal replNewVal      = BigDecimal.ZERO;
    private LocalDate replValAsAtDate;
    private String insType        = "";          // lookup FACODIN

    // ── S1B — Depreciation details ────────────────────────────────────
    private BigDecimal actualCost       = BigDecimal.ZERO;
    private BigDecimal taxDepnCost      = BigDecimal.ZERO;
    private BigDecimal bookDepnCost     = BigDecimal.ZERO;
    private BigDecimal interestComponent = BigDecimal.ZERO;
    private LocalDate writeDownDate;
    private LocalDate startDepnDate;             // book depn start
    private LocalDate startTaxDepnDate;

    // Tax depreciation
    private String taxDepnMethod  = "";          // S=straight-line, D=diminishing
    private String taxDepnCode    = "";          // lookup FACODDN
    private BigDecimal taxRate1   = BigDecimal.ZERO;   // Yr1 rate
    private BigDecimal taxRate2   = BigDecimal.ZERO;   // Yr2+ rate
    private BigDecimal taxCalcBase = BigDecimal.ZERO;

    // Book depreciation
    private String bookDepnMethod = "";          // S=straight-line, D=diminishing
    private String bookDepnCode   = "";          // lookup FACODDN
    private BigDecimal bookRate1  = BigDecimal.ZERO;
    private BigDecimal bookRate2  = BigDecimal.ZERO;
    private BigDecimal bookCalcBase = BigDecimal.ZERO;
    private int   bookDepnFreq    = 0;           // months
    private int   taxDepnFreq     = 0;

    // Pool balance fields (visible only if pool=Y)
    private BigDecimal poolBookBal = BigDecimal.ZERO;
    private LocalDate poolBookBalDate;
    private BigDecimal poolTaxBal  = BigDecimal.ZERO;
    private LocalDate poolTaxBalDate;
    private String poolAcqnPostedFlag = "";

    // Post depreciation to: N=none, C=cost ledger, B=business analysis
    private String postDepnToClBa = "";
    // Cost ledger (if C)
    private String ledgerType     = "";
    private String ledgerCode     = "";
    // Business analysis (if B)
    private String baLedgerId     = "";
    private String baPrimaryCodes = "";

    // ── S2 — Status change ────────────────────────────────────────────
    private String assetStatus    = "";          // blank=active, H=hold, N=not in use, R=retired
    private String assetStatusRef = "";          // reference for status change

    // ── Audit / timestamps ────────────────────────────────────────────
    private LocalDate acqnDateRaw;               // stored as day-number in COBOL
    private LocalDate retmtDate;
    private BigDecimal retmtProceedsVal = BigDecimal.ZERO;

    // ── Additional display fields (computed, not persisted) ───────────
    private transient String locDesc       = "";
    private transient String deptDesc      = "";
    private transient String grpDesc       = "";
    private transient String subgrpDesc    = "";
    private transient String stakeSiteDesc = "";
    private transient String insTypeDesc   = "";
    private transient String taxDepnMethodDesc  = "";
    private transient String bookDepnMethodDesc = "";
    private transient String taxDepnCodeDesc    = "";
    private transient String bookDepnCodeDesc   = "";
    private transient String statusDesc    = "";
    private transient boolean hasUnpostedTrx = false;

    // ── Note no ───────────────────────────────────────────────────────
    private long noteNo = 0;

    // ── Status helpers ────────────────────────────────────────────────

    public boolean isActive()    { return assetStatus == null || assetStatus.isBlank(); }
    public boolean isUnposted()  { return "U".equals(assetStatus); }
    public boolean isOnHold()    { return "H".equals(assetStatus); }
    public boolean isNotInUse()  { return "N".equals(assetStatus); }
    public boolean isRetired()   { return "R".equals(assetStatus); }
    public boolean isLeased()    { return "Y".equals(leasedAssetFlag); }
    public boolean isPooled()    { return "Y".equals(assetPoolFlag); }

    public String statusDisplayDesc() {
        if (assetStatus == null || assetStatus.isBlank()) return "**  ACTIVE  **";
        return switch (assetStatus) {
            case "U" -> "**  ASSET NOT POSTED  **";
            case "R" -> "**  RETIRED  **";
            case "H" -> "**  ON HOLD  **";
            case "N" -> "**  NOT IN USE  **";
            default  -> assetStatus;
        };
    }

    /** Can the user edit this asset? Mirrors COBOL CHECK-ASSET-NO logic. */
    public boolean isEditable() {
        if (isUnposted() && !isPooled()) return false;
        if (isUnposted() && isPooled() && !"Y".equals(poolAcqnPostedFlag)) return false;
        return true;
    }

    // ── Getters / Setters ─────────────────────────────────────────────

    public int     getCompanyNo()           { return companyNo; }
    public void    setCompanyNo(int v)      { companyNo = v; }

    public String  getAssetNo()             { return assetNo; }
    public void    setAssetNo(String v)     { assetNo = v == null ? "" : v.trim(); }

    public String  getDesc1()               { return desc1; }
    public void    setDesc1(String v)       { desc1 = nvl(v); }

    public String  getDesc2()               { return desc2; }
    public void    setDesc2(String v)       { desc2 = nvl(v); }

    public String  getAlphaCode()           { return alphaCode; }
    public void    setAlphaCode(String v)   { alphaCode = nvl(v); }

    public String  getLocCode()             { return locCode; }
    public void    setLocCode(String v)     { locCode = nvl(v); }

    public String  getDeptCode()            { return deptCode; }
    public void    setDeptCode(String v)    { deptCode = nvl(v); }

    public String  getGrpCode()             { return grpCode; }
    public void    setGrpCode(String v)     { grpCode = nvl(v); }

    public String  getSubgrpCode()          { return subgrpCode; }
    public void    setSubgrpCode(String v)  { subgrpCode = nvl(v); }

    public String  getStakeSite()           { return stakeSite; }
    public void    setStakeSite(String v)   { stakeSite = nvl(v); }

    public String  getAttachToAssetNo()           { return attachToAssetNo; }
    public void    setAttachToAssetNo(String v)   { attachToAssetNo = nvl(v); }

    public String  getAssetPoolFlag()             { return assetPoolFlag; }
    public void    setAssetPoolFlag(String v)     { assetPoolFlag = nvl(v); }

    public BigDecimal getQty()              { return qty; }
    public void       setQty(BigDecimal v)  { qty = bd(v); }

    public LocalDate getAcqnDate()          { return acqnDate; }
    public void      setAcqnDate(LocalDate v) { acqnDate = v; }

    public String  getAcqnType()            { return acqnType; }
    public void    setAcqnType(String v)    { acqnType = nvl(v); }

    public String  getInternalOrderNo()           { return internalOrderNo; }
    public void    setInternalOrderNo(String v)   { internalOrderNo = nvl(v); }

    public String  getParentRateInd()             { return parentRateInd; }
    public void    setParentRateInd(String v)     { parentRateInd = nvl(v); }

    public BigDecimal getParentRateCurr()             { return parentRateCurr; }
    public void       setParentRateCurr(BigDecimal v) { parentRateCurr = bd(v); }

    public String  getParentFcMathsInd()              { return parentFcMathsInd; }
    public void    setParentFcMathsInd(String v)      { parentFcMathsInd = nvl(v); }

    public String  getSupplierName()        { return supplierName; }
    public void    setSupplierName(String v){ supplierName = nvl(v); }

    public String  getSupplierNo()          { return supplierNo; }
    public void    setSupplierNo(String v)  { supplierNo = nvl(v); }

    public String  getInvoiceNo()           { return invoiceNo; }
    public void    setInvoiceNo(String v)   { invoiceNo = nvl(v); }

    public String  getLeasedAssetFlag()           { return leasedAssetFlag; }
    public void    setLeasedAssetFlag(String v)   { leasedAssetFlag = nvl(v); }

    public LocalDate getLseExpiryDate()           { return lseExpiryDate; }
    public void      setLseExpiryDate(LocalDate v){ lseExpiryDate = v; }

    public String  getLseContractNo()             { return lseContractNo; }
    public void    setLseContractNo(String v)     { lseContractNo = nvl(v); }

    public BigDecimal getLsePaymentAmt()             { return lsePaymentAmt; }
    public void       setLsePaymentAmt(BigDecimal v) { lsePaymentAmt = bd(v); }

    public String  getLsePaymentFreq()            { return lsePaymentFreq; }
    public void    setLsePaymentFreq(String v)    { lsePaymentFreq = nvl(v); }

    public BigDecimal getCurrentInsValue()             { return currentInsValue; }
    public void       setCurrentInsValue(BigDecimal v) { currentInsValue = bd(v); }

    public BigDecimal getReplNewVal()               { return replNewVal; }
    public void       setReplNewVal(BigDecimal v)   { replNewVal = bd(v); }

    public LocalDate getReplValAsAtDate()               { return replValAsAtDate; }
    public void      setReplValAsAtDate(LocalDate v)    { replValAsAtDate = v; }

    public String  getInsType()             { return insType; }
    public void    setInsType(String v)     { insType = nvl(v); }

    public BigDecimal getActualCost()               { return actualCost; }
    public void       setActualCost(BigDecimal v)   { actualCost = bd(v); }

    public BigDecimal getTaxDepnCost()              { return taxDepnCost; }
    public void       setTaxDepnCost(BigDecimal v)  { taxDepnCost = bd(v); }

    public BigDecimal getBookDepnCost()             { return bookDepnCost; }
    public void       setBookDepnCost(BigDecimal v) { bookDepnCost = bd(v); }

    public BigDecimal getInterestComponent()             { return interestComponent; }
    public void       setInterestComponent(BigDecimal v) { interestComponent = bd(v); }

    public LocalDate getWriteDownDate()             { return writeDownDate; }
    public void      setWriteDownDate(LocalDate v)  { writeDownDate = v; }

    public LocalDate getStartDepnDate()             { return startDepnDate; }
    public void      setStartDepnDate(LocalDate v)  { startDepnDate = v; }

    public LocalDate getStartTaxDepnDate()              { return startTaxDepnDate; }
    public void      setStartTaxDepnDate(LocalDate v)   { startTaxDepnDate = v; }

    public String  getTaxDepnMethod()               { return taxDepnMethod; }
    public void    setTaxDepnMethod(String v)       { taxDepnMethod = nvl(v); }

    public String  getTaxDepnCode()                 { return taxDepnCode; }
    public void    setTaxDepnCode(String v)         { taxDepnCode = nvl(v); }

    public BigDecimal getTaxRate1()                 { return taxRate1; }
    public void       setTaxRate1(BigDecimal v)     { taxRate1 = bd(v); }

    public BigDecimal getTaxRate2()                 { return taxRate2; }
    public void       setTaxRate2(BigDecimal v)     { taxRate2 = bd(v); }

    public BigDecimal getTaxCalcBase()              { return taxCalcBase; }
    public void       setTaxCalcBase(BigDecimal v)  { taxCalcBase = bd(v); }

    public String  getBookDepnMethod()              { return bookDepnMethod; }
    public void    setBookDepnMethod(String v)      { bookDepnMethod = nvl(v); }

    public String  getBookDepnCode()                { return bookDepnCode; }
    public void    setBookDepnCode(String v)        { bookDepnCode = nvl(v); }

    public BigDecimal getBookRate1()                { return bookRate1; }
    public void       setBookRate1(BigDecimal v)    { bookRate1 = bd(v); }

    public BigDecimal getBookRate2()                { return bookRate2; }
    public void       setBookRate2(BigDecimal v)    { bookRate2 = bd(v); }

    public BigDecimal getBookCalcBase()             { return bookCalcBase; }
    public void       setBookCalcBase(BigDecimal v) { bookCalcBase = bd(v); }

    public int  getBookDepnFreq()                   { return bookDepnFreq; }
    public void setBookDepnFreq(int v)              { bookDepnFreq = v; }

    public int  getTaxDepnFreq()                    { return taxDepnFreq; }
    public void setTaxDepnFreq(int v)               { taxDepnFreq = v; }

    public BigDecimal getPoolBookBal()              { return poolBookBal; }
    public void       setPoolBookBal(BigDecimal v)  { poolBookBal = bd(v); }

    public LocalDate getPoolBookBalDate()               { return poolBookBalDate; }
    public void      setPoolBookBalDate(LocalDate v)    { poolBookBalDate = v; }

    public BigDecimal getPoolTaxBal()               { return poolTaxBal; }
    public void       setPoolTaxBal(BigDecimal v)   { poolTaxBal = bd(v); }

    public LocalDate getPoolTaxBalDate()                { return poolTaxBalDate; }
    public void      setPoolTaxBalDate(LocalDate v)     { poolTaxBalDate = v; }

    public String  getPoolAcqnPostedFlag()              { return poolAcqnPostedFlag; }
    public void    setPoolAcqnPostedFlag(String v)      { poolAcqnPostedFlag = nvl(v); }

    public String  getPostDepnToClBa()              { return postDepnToClBa; }
    public void    setPostDepnToClBa(String v)      { postDepnToClBa = nvl(v); }

    public String  getLedgerType()                  { return ledgerType; }
    public void    setLedgerType(String v)          { ledgerType = nvl(v); }

    public String  getLedgerCode()                  { return ledgerCode; }
    public void    setLedgerCode(String v)          { ledgerCode = nvl(v); }

    public String  getBaLedgerId()                  { return baLedgerId; }
    public void    setBaLedgerId(String v)          { baLedgerId = nvl(v); }

    public String  getBaPrimaryCodes()              { return baPrimaryCodes; }
    public void    setBaPrimaryCodes(String v)      { baPrimaryCodes = nvl(v); }

    public String  getAssetStatus()                 { return assetStatus; }
    public void    setAssetStatus(String v)         { assetStatus = nvl(v); }

    public String  getAssetStatusRef()              { return assetStatusRef; }
    public void    setAssetStatusRef(String v)      { assetStatusRef = nvl(v); }

    public LocalDate getRetmtDate()                 { return retmtDate; }
    public void      setRetmtDate(LocalDate v)      { retmtDate = v; }

    public BigDecimal getRetmtProceedsVal()             { return retmtProceedsVal; }
    public void       setRetmtProceedsVal(BigDecimal v) { retmtProceedsVal = bd(v); }

    public long getNoteNo()                         { return noteNo; }
    public void setNoteNo(long v)                   { noteNo = v; }

    // Transient display helpers
    public String  getLocDesc()             { return locDesc; }
    public void    setLocDesc(String v)     { locDesc = nvl(v); }
    public String  getDeptDesc()            { return deptDesc; }
    public void    setDeptDesc(String v)    { deptDesc = nvl(v); }
    public String  getGrpDesc()             { return grpDesc; }
    public void    setGrpDesc(String v)     { grpDesc = nvl(v); }
    public String  getSubgrpDesc()          { return subgrpDesc; }
    public void    setSubgrpDesc(String v)  { subgrpDesc = nvl(v); }
    public String  getStakeSiteDesc()             { return stakeSiteDesc; }
    public void    setStakeSiteDesc(String v)     { stakeSiteDesc = nvl(v); }
    public String  getInsTypeDesc()               { return insTypeDesc; }
    public void    setInsTypeDesc(String v)       { insTypeDesc = nvl(v); }
    public String  getTaxDepnMethodDesc()         { return taxDepnMethodDesc; }
    public void    setTaxDepnMethodDesc(String v) { taxDepnMethodDesc = nvl(v); }
    public String  getBookDepnMethodDesc()         { return bookDepnMethodDesc; }
    public void    setBookDepnMethodDesc(String v) { bookDepnMethodDesc = nvl(v); }
    public String  getTaxDepnCodeDesc()           { return taxDepnCodeDesc; }
    public void    setTaxDepnCodeDesc(String v)   { taxDepnCodeDesc = nvl(v); }
    public String  getBookDepnCodeDesc()          { return bookDepnCodeDesc; }
    public void    setBookDepnCodeDesc(String v)  { bookDepnCodeDesc = nvl(v); }
    public String  getStatusDesc()                { return statusDesc; }
    public void    setStatusDesc(String v)        { statusDesc = nvl(v); }
    public boolean isHasUnpostedTrx()             { return hasUnpostedTrx; }
    public void    setHasUnpostedTrx(boolean v)   { hasUnpostedTrx = v; }

    private static String nvl(String s) { return s == null ? "" : s; }
    private static BigDecimal bd(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
