package com.landmarksoftware.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Represents one row in the FATRANS table, enriched with the parent
 * asset details needed for the FATL02 report output.
 *
 * All amount fields default to ZERO (never null) to simplify Excel/PDF output.
 */
public class TransactionRow {

    // ── Asset context (from FAASSET) ──────────────────────────────────
    private String   assetNo;
    private String   desc1;
    private String   locCode;
    private String   deptCode;
    private String   grpCode;
    private String   subgrpCode;
    private LocalDate acqnDate;
    private String   assetStatus;
    private boolean  pooledAsset;

    // ── Transaction header ────────────────────────────────────────────
    private String    trxType;
    private LocalDate trxDate;
    private int       batchNo;
    private String    ref;
    private String    trxStatus;

    // ── AQ — Acquisition ─────────────────────────────────────────────
    private BigDecimal acqnActualCost    = BigDecimal.ZERO;
    private BigDecimal acqnBookDepnCost  = BigDecimal.ZERO;
    private BigDecimal acqnTaxDepnCost   = BigDecimal.ZERO;
    private String     acqnLoc;
    private String     acqnDept;
    private String     acqnGrp;
    private String     acqnSubgrp;

    // ── BD/TD — Depreciation ──────────────────────────────────────────
    private String     depnMethod;
    private String     depnCode;
    private int        depnFreq;
    private BigDecimal depnRate          = BigDecimal.ZERO;
    private BigDecimal depnAmt           = BigDecimal.ZERO;
    private LocalDate  depnThruToDate;
    private String     depnLoc;
    private String     depnDept;
    private String     depnGrp;
    private String     depnSubgrp;

    // ── BA/TA — Depreciation adjustment ──────────────────────────────
    private BigDecimal depnAdjAmt        = BigDecimal.ZERO;
    private String     depnAdjLoc;
    private String     depnAdjDept;
    private String     depnAdjGrp;
    private String     depnAdjSubgrp;

    // ── RV/TV — Revaluation ───────────────────────────────────────────
    private BigDecimal revalVal          = BigDecimal.ZERO;
    private BigDecimal revalAdjAmt       = BigDecimal.ZERO;
    private BigDecimal revalAccumDepn    = BigDecimal.ZERO;
    private String     revalLoc;
    private String     revalDept;
    private String     revalGrp;
    private String     revalSubgrp;

    // ── TR — Transfer ─────────────────────────────────────────────────
    private String     tfrFromLoc;
    private String     tfrFromDept;
    private String     tfrFromGrp;
    private String     tfrFromSubgrp;
    private String     tfrToLoc;
    private String     tfrToDept;
    private String     tfrToGrp;
    private String     tfrToSubgrp;
    private BigDecimal tfrBookDepnCost   = BigDecimal.ZERO;
    private BigDecimal tfrNetRevalAmt    = BigDecimal.ZERO;
    private BigDecimal tfrProvDepnAmt    = BigDecimal.ZERO;

    // ── RT — Retirement ───────────────────────────────────────────────
    private BigDecimal retmtBkProfitAmt  = BigDecimal.ZERO;
    private BigDecimal retmtTxProfitAmt  = BigDecimal.ZERO;
    private BigDecimal retmtProceedsAmt  = BigDecimal.ZERO;
    private String     retmtLoc;
    private String     retmtDept;
    private String     retmtGrp;
    private String     retmtSubgrp;

    // ── Audit ─────────────────────────────────────────────────────────
    private String    auditUserId;
    private LocalDate auditDate;
    private String    auditTime;

    // ── Derived helpers ───────────────────────────────────────────────

    /** Human-readable type description matching COBOL DETAIL-08 output */
    public String trxTypeDesc() {
        return switch (trxType == null ? "" : trxType) {
            case "AQ" -> "ACQUISITN";
            case "BD" -> "BOOK DEPN";
            case "TD" -> "TAX DEPN";
            case "BA" -> "BK DEP ADJ";
            case "TA" -> "TX DEP ADJ";
            case "TR" -> "TRANSFER";
            case "RV" -> "BOOK REVAL";
            case "TV" -> "TAX REVAL";
            case "RT" -> "RETIREMENT";
            default   -> trxType;
        };
    }

    /** Asset status description matching COBOL GET-ASSET-STATUS-DESC */
    public String assetStatusDesc() {
        if (assetStatus == null || assetStatus.isBlank()) return "ACTIVE";
        return switch (assetStatus.trim()) {
            case "H" -> "ON HOLD";
            case "N" -> "NOT IN USE";
            case "R" -> "RETIRED";
            case "U" -> "UNPOSTED";
            default  -> assetStatus;
        };
    }

    /**
     * Returns the transaction-type-specific location code.
     * Mirrors COBOL EXPORT-TRANSACTION trx-loc logic.
     */
    public String trxLoc() {
        if (trxType == null) return "";
        return switch (trxType) {
            case "AQ"       -> nvl(acqnLoc);
            case "BD","TD"  -> nvl(depnLoc);
            case "BA","TA"  -> nvl(depnAdjLoc);
            case "RV"       -> nvl(revalLoc);
            case "RT"       -> nvl(retmtLoc);
            case "TR"       -> nvl(tfrFromLoc);
            default         -> "";
        };
    }

    public String trxDept() {
        if (trxType == null) return "";
        return switch (trxType) {
            case "AQ"       -> nvl(acqnDept);
            case "BD","TD"  -> nvl(depnDept);
            case "BA","TA"  -> nvl(depnAdjDept);
            case "RV"       -> nvl(revalDept);
            case "RT"       -> nvl(retmtDept);
            case "TR"       -> nvl(tfrFromDept);
            default         -> "";
        };
    }

    public String trxGrp() {
        if (trxType == null) return "";
        return switch (trxType) {
            case "AQ"       -> nvl(acqnGrp);
            case "BD","TD"  -> nvl(depnGrp);
            case "BA","TA"  -> nvl(depnAdjGrp);
            case "RV"       -> nvl(revalGrp);
            case "RT"       -> nvl(retmtGrp);
            case "TR"       -> nvl(tfrFromGrp);
            default         -> "";
        };
    }

    public String trxSubgrp() {
        if (trxType == null) return "";
        return switch (trxType) {
            case "AQ"       -> nvl(acqnSubgrp);
            case "BD","TD"  -> nvl(depnSubgrp);
            case "BA","TA"  -> nvl(depnAdjSubgrp);
            case "RV"       -> nvl(revalSubgrp);
            case "RT"       -> nvl(retmtSubgrp);
            case "TR"       -> nvl(tfrFromSubgrp);
            default         -> "";
        };
    }

    /** Book depn amount for Excel export (BD/BA only) */
    public BigDecimal bookDepnForExport() {
        return switch (trxType == null ? "" : trxType) {
            case "BD" -> depnAmt;
            case "BA" -> depnAdjAmt;
            default   -> BigDecimal.ZERO;
        };
    }

    /** Tax depn amount for Excel export (TD/TA only) */
    public BigDecimal taxDepnForExport() {
        return switch (trxType == null ? "" : trxType) {
            case "TD" -> depnAmt;
            case "TA" -> depnAdjAmt;
            default   -> BigDecimal.ZERO;
        };
    }

    private String nvl(String s) { return s == null ? "" : s; }

    // ── Getters / Setters ─────────────────────────────────────────────

    public String    getAssetNo()          { return assetNo; }
    public void      setAssetNo(String v)  { assetNo = v; }

    public String    getDesc1()            { return desc1; }
    public void      setDesc1(String v)    { desc1 = v; }

    public String    getLocCode()          { return locCode; }
    public void      setLocCode(String v)  { locCode = v; }

    public String    getDeptCode()         { return deptCode; }
    public void      setDeptCode(String v) { deptCode = v; }

    public String    getGrpCode()          { return grpCode; }
    public void      setGrpCode(String v)  { grpCode = v; }

    public String    getSubgrpCode()            { return subgrpCode; }
    public void      setSubgrpCode(String v)    { subgrpCode = v; }

    public LocalDate getAcqnDate()              { return acqnDate; }
    public void      setAcqnDate(LocalDate v)   { acqnDate = v; }

    public String    getAssetStatus()           { return assetStatus; }
    public void      setAssetStatus(String v)   { assetStatus = v; }

    public boolean   isPooledAsset()            { return pooledAsset; }
    public void      setPooledAsset(boolean v)  { pooledAsset = v; }

    public String    getTrxType()               { return trxType; }
    public void      setTrxType(String v)       { trxType = v; }

    public LocalDate getTrxDate()               { return trxDate; }
    public void      setTrxDate(LocalDate v)    { trxDate = v; }

    public int       getBatchNo()               { return batchNo; }
    public void      setBatchNo(int v)          { batchNo = v; }

    public String    getRef()                   { return ref; }
    public void      setRef(String v)           { ref = v; }

    public String    getTrxStatus()             { return trxStatus; }
    public void      setTrxStatus(String v)     { trxStatus = v; }

    public BigDecimal getAcqnActualCost()            { return acqnActualCost; }
    public void       setAcqnActualCost(BigDecimal v){ acqnActualCost = bd(v); }

    public BigDecimal getAcqnBookDepnCost()           { return acqnBookDepnCost; }
    public void       setAcqnBookDepnCost(BigDecimal v){ acqnBookDepnCost = bd(v); }

    public BigDecimal getAcqnTaxDepnCost()            { return acqnTaxDepnCost; }
    public void       setAcqnTaxDepnCost(BigDecimal v){ acqnTaxDepnCost = bd(v); }

    public String    getAcqnLoc()               { return acqnLoc; }
    public void      setAcqnLoc(String v)       { acqnLoc = v; }

    public String    getAcqnDept()              { return acqnDept; }
    public void      setAcqnDept(String v)      { acqnDept = v; }

    public String    getAcqnGrp()               { return acqnGrp; }
    public void      setAcqnGrp(String v)       { acqnGrp = v; }

    public String    getAcqnSubgrp()            { return acqnSubgrp; }
    public void      setAcqnSubgrp(String v)    { acqnSubgrp = v; }

    public String    getDepnMethod()            { return depnMethod; }
    public void      setDepnMethod(String v)    { depnMethod = v; }

    public String    getDepnCode()              { return depnCode; }
    public void      setDepnCode(String v)      { depnCode = v; }

    public int       getDepnFreq()              { return depnFreq; }
    public void      setDepnFreq(int v)         { depnFreq = v; }

    public BigDecimal getDepnRate()             { return depnRate; }
    public void       setDepnRate(BigDecimal v) { depnRate = bd(v); }

    public BigDecimal getDepnAmt()              { return depnAmt; }
    public void       setDepnAmt(BigDecimal v)  { depnAmt = bd(v); }

    public LocalDate getDepnThruToDate()              { return depnThruToDate; }
    public void      setDepnThruToDate(LocalDate v)   { depnThruToDate = v; }

    public String    getDepnLoc()               { return depnLoc; }
    public void      setDepnLoc(String v)       { depnLoc = v; }

    public String    getDepnDept()              { return depnDept; }
    public void      setDepnDept(String v)      { depnDept = v; }

    public String    getDepnGrp()               { return depnGrp; }
    public void      setDepnGrp(String v)       { depnGrp = v; }

    public String    getDepnSubgrp()            { return depnSubgrp; }
    public void      setDepnSubgrp(String v)    { depnSubgrp = v; }

    public BigDecimal getDepnAdjAmt()           { return depnAdjAmt; }
    public void       setDepnAdjAmt(BigDecimal v){ depnAdjAmt = bd(v); }

    public String    getDepnAdjLoc()            { return depnAdjLoc; }
    public void      setDepnAdjLoc(String v)    { depnAdjLoc = v; }

    public String    getDepnAdjDept()           { return depnAdjDept; }
    public void      setDepnAdjDept(String v)   { depnAdjDept = v; }

    public String    getDepnAdjGrp()            { return depnAdjGrp; }
    public void      setDepnAdjGrp(String v)    { depnAdjGrp = v; }

    public String    getDepnAdjSubgrp()         { return depnAdjSubgrp; }
    public void      setDepnAdjSubgrp(String v) { depnAdjSubgrp = v; }

    public BigDecimal getRevalVal()             { return revalVal; }
    public void       setRevalVal(BigDecimal v) { revalVal = bd(v); }

    public BigDecimal getRevalAdjAmt()          { return revalAdjAmt; }
    public void       setRevalAdjAmt(BigDecimal v){ revalAdjAmt = bd(v); }

    public BigDecimal getRevalAccumDepn()       { return revalAccumDepn; }
    public void       setRevalAccumDepn(BigDecimal v){ revalAccumDepn = bd(v); }

    public String    getRevalLoc()              { return revalLoc; }
    public void      setRevalLoc(String v)      { revalLoc = v; }

    public String    getRevalDept()             { return revalDept; }
    public void      setRevalDept(String v)     { revalDept = v; }

    public String    getRevalGrp()              { return revalGrp; }
    public void      setRevalGrp(String v)      { revalGrp = v; }

    public String    getRevalSubgrp()           { return revalSubgrp; }
    public void      setRevalSubgrp(String v)   { revalSubgrp = v; }

    public String    getTfrFromLoc()            { return tfrFromLoc; }
    public void      setTfrFromLoc(String v)    { tfrFromLoc = v; }

    public String    getTfrFromDept()           { return tfrFromDept; }
    public void      setTfrFromDept(String v)   { tfrFromDept = v; }

    public String    getTfrFromGrp()            { return tfrFromGrp; }
    public void      setTfrFromGrp(String v)    { tfrFromGrp = v; }

    public String    getTfrFromSubgrp()         { return tfrFromSubgrp; }
    public void      setTfrFromSubgrp(String v) { tfrFromSubgrp = v; }

    public String    getTfrToLoc()              { return tfrToLoc; }
    public void      setTfrToLoc(String v)      { tfrToLoc = v; }

    public String    getTfrToDept()             { return tfrToDept; }
    public void      setTfrToDept(String v)     { tfrToDept = v; }

    public String    getTfrToGrp()              { return tfrToGrp; }
    public void      setTfrToGrp(String v)      { tfrToGrp = v; }

    public String    getTfrToSubgrp()           { return tfrToSubgrp; }
    public void      setTfrToSubgrp(String v)   { tfrToSubgrp = v; }

    public BigDecimal getTfrBookDepnCost()       { return tfrBookDepnCost; }
    public void       setTfrBookDepnCost(BigDecimal v){ tfrBookDepnCost = bd(v); }

    public BigDecimal getTfrNetRevalAmt()        { return tfrNetRevalAmt; }
    public void       setTfrNetRevalAmt(BigDecimal v){ tfrNetRevalAmt = bd(v); }

    public BigDecimal getTfrProvDepnAmt()        { return tfrProvDepnAmt; }
    public void       setTfrProvDepnAmt(BigDecimal v){ tfrProvDepnAmt = bd(v); }

    public BigDecimal getRetmtBkProfitAmt()      { return retmtBkProfitAmt; }
    public void       setRetmtBkProfitAmt(BigDecimal v){ retmtBkProfitAmt = bd(v); }

    public BigDecimal getRetmtTxProfitAmt()      { return retmtTxProfitAmt; }
    public void       setRetmtTxProfitAmt(BigDecimal v){ retmtTxProfitAmt = bd(v); }

    public BigDecimal getRetmtProceedsAmt()      { return retmtProceedsAmt; }
    public void       setRetmtProceedsAmt(BigDecimal v){ retmtProceedsAmt = bd(v); }

    public String    getRetmtLoc()              { return retmtLoc; }
    public void      setRetmtLoc(String v)      { retmtLoc = v; }

    public String    getRetmtDept()             { return retmtDept; }
    public void      setRetmtDept(String v)     { retmtDept = v; }

    public String    getRetmtGrp()              { return retmtGrp; }
    public void      setRetmtGrp(String v)      { retmtGrp = v; }

    public String    getRetmtSubgrp()           { return retmtSubgrp; }
    public void      setRetmtSubgrp(String v)   { retmtSubgrp = v; }

    public String    getAuditUserId()           { return auditUserId; }
    public void      setAuditUserId(String v)   { auditUserId = v; }

    public LocalDate getAuditDate()             { return auditDate; }
    public void      setAuditDate(LocalDate v)  { auditDate = v; }

    public String    getAuditTime()             { return auditTime; }
    public void      setAuditTime(String v)     { auditTime = v; }

    private BigDecimal bd(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
