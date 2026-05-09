package com.example.fixedassets.model;

import java.time.LocalDate;

/**
 * Parameter object for FATL02 — Fixed Assets Transaction List.
 *
 * Mirrors the 19 fields on the FATL02S0 parameter screen.
 * All defaults match the COBOL screen initial values.
 */
public class TransactionListRequest {

    // ── Company ───────────────────────────────────────────────────────
    private int companyNo;

    // ── Asset range ───────────────────────────────────────────────────
    /** Start asset no — blank = all assets from beginning */
    private String startAssetNo = "";
    /** End asset no — blank = all assets to end (COBOL fills z's) */
    private String endAssetNo   = "";

    // ── Code filters (blank = all) ────────────────────────────────────
    private String locCode     = "";
    private String deptCode    = "";
    private String grpCode     = "";
    private String subgrpCode  = "";

    // ── Transaction type flags (Y/N, all default Y) ───────────────────
    private boolean inclAcqn       = true;   // AQ Acquisitions
    private boolean inclBookReval   = true;   // RV Book revaluations
    private boolean inclTaxReval    = true;   // TV Tax revaluations
    private boolean inclTransfer    = true;   // TR Transfers
    private boolean inclRetirement  = true;   // RT Retirements
    private boolean inclBookDepn    = true;   // BD Book depreciation
    private boolean inclBookDepnAdj = true;   // BA Book depn adjustments
    private boolean inclTaxDepn     = true;   // TD Tax depreciation
    private boolean inclTaxDepnAdj  = true;   // TA Tax depn adjustments

    // ── Date range ────────────────────────────────────────────────────
    /** null = all dates */
    private LocalDate startDate = null;
    /** null = all dates */
    private LocalDate endDate   = null;

    // ── Other filters ─────────────────────────────────────────────────
    /** N = exclude leased assets (default); Y = include leased */
    private boolean includeLeased = false;
    /** N = all assets (default); Y = pooled assets only */
    private boolean pooledOnly    = false;

    // ── Helpers ───────────────────────────────────────────────────────

    /** Returns true if a transaction type should be included */
    public boolean includeType(String trxType) {
        return switch (trxType) {
            case "AQ" -> inclAcqn;
            case "RV" -> inclBookReval;
            case "TV" -> inclTaxReval;
            case "TR" -> inclTransfer;
            case "RT" -> inclRetirement;
            case "BD" -> inclBookDepn;
            case "BA" -> inclBookDepnAdj;
            case "TD" -> inclTaxDepn;
            case "TA" -> inclTaxDepnAdj;
            default   -> false;
        };
    }

    /** Returns true if the transaction date falls within the requested range */
    public boolean inDateRange(LocalDate trxDate) {
        if (trxDate == null || trxDate.getYear() < 1900) return false;
        if (startDate != null && trxDate.isBefore(startDate)) return false;
        if (endDate   != null && trxDate.isAfter(endDate))    return false;
        return true;
    }

    /** Effective end asset no — COBOL fills tildes when start is blank */
    public String effectiveEndAssetNo() {
        if (endAssetNo == null || endAssetNo.isBlank())
            return "~~~~~~~~~~~~~~~~~~~~";
        return endAssetNo;
    }

    // ── Getters / Setters ─────────────────────────────────────────────

    public int     getCompanyNo()     { return companyNo; }
    public void    setCompanyNo(int v){ companyNo = v; }

    public String  getStartAssetNo()         { return startAssetNo; }
    public void    setStartAssetNo(String v) { startAssetNo = v == null ? "" : v.trim(); }

    public String  getEndAssetNo()           { return endAssetNo; }
    public void    setEndAssetNo(String v)   { endAssetNo = v == null ? "" : v.trim(); }

    public String  getLocCode()              { return locCode; }
    public void    setLocCode(String v)      { locCode = v == null ? "" : v.trim(); }

    public String  getDeptCode()             { return deptCode; }
    public void    setDeptCode(String v)     { deptCode = v == null ? "" : v.trim(); }

    public String  getGrpCode()              { return grpCode; }
    public void    setGrpCode(String v)      { grpCode = v == null ? "" : v.trim(); }

    public String  getSubgrpCode()           { return subgrpCode; }
    public void    setSubgrpCode(String v)   { subgrpCode = v == null ? "" : v.trim(); }

    public boolean isInclAcqn()              { return inclAcqn; }
    public void    setInclAcqn(boolean v)   { inclAcqn = v; }

    public boolean isInclBookReval()         { return inclBookReval; }
    public void    setInclBookReval(boolean v){ inclBookReval = v; }

    public boolean isInclTaxReval()          { return inclTaxReval; }
    public void    setInclTaxReval(boolean v){ inclTaxReval = v; }

    public boolean isInclTransfer()          { return inclTransfer; }
    public void    setInclTransfer(boolean v){ inclTransfer = v; }

    public boolean isInclRetirement()        { return inclRetirement; }
    public void    setInclRetirement(boolean v){ inclRetirement = v; }

    public boolean isInclBookDepn()          { return inclBookDepn; }
    public void    setInclBookDepn(boolean v){ inclBookDepn = v; }

    public boolean isInclBookDepnAdj()       { return inclBookDepnAdj; }
    public void    setInclBookDepnAdj(boolean v){ inclBookDepnAdj = v; }

    public boolean isInclTaxDepn()           { return inclTaxDepn; }
    public void    setInclTaxDepn(boolean v) { inclTaxDepn = v; }

    public boolean isInclTaxDepnAdj()        { return inclTaxDepnAdj; }
    public void    setInclTaxDepnAdj(boolean v){ inclTaxDepnAdj = v; }

    public LocalDate getStartDate()          { return startDate; }
    public void      setStartDate(LocalDate v){ startDate = v; }

    public LocalDate getEndDate()            { return endDate; }
    public void      setEndDate(LocalDate v) { endDate = v; }

    public boolean isIncludeLeased()         { return includeLeased; }
    public void    setIncludeLeased(boolean v){ includeLeased = v; }

    public boolean isPooledOnly()            { return pooledOnly; }
    public void    setPooledOnly(boolean v)  { pooledOnly = v; }
}
