package com.landmarksoftware.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One asset row for the FATL03 report, with all computed values.
 *
 * Non-pooled assets:
 *   Carries all FAASSET values plus computed WDV / profit-loss per
 *   CALC-BOOK-WRITTEN-DOWN-VAL / CALC-TAX-WRITTEN-DOWN-VAL.
 *
 * Pooled assets:
 *   Values in the WS-ASSET-DATA group (totals from FATRANS) are
 *   used instead of the raw FAASSET fields.
 *   Individual pooled transactions are held in pooledTrxs.
 */
public class AcquiredRetiredRow {

    // ── Asset identity ────────────────────────────────────────────────
    private String   assetNo;
    private String   desc1;
    private String   desc2;
    private String   locCode;
    private String   deptCode;
    private String   grpCode;
    private String   subgrpCode;
    private String   assetStatus;
    private boolean  pooledAsset;
    private LocalDate acqnDate;
    private LocalDate retmtDate;

    // ── Book values from FAASSET ──────────────────────────────────────
    private BigDecimal bookDepnCost      = BigDecimal.ZERO;
    private BigDecimal lastRevalVal      = BigDecimal.ZERO;
    private BigDecimal accumBookDepn     = BigDecimal.ZERO;
    private BigDecimal accumBookDepnAdj  = BigDecimal.ZERO;
    private LocalDate  lastBookDepnDate;
    private BigDecimal lastTaxRevalVal   = BigDecimal.ZERO;

    // ── Tax values from FAASSET ───────────────────────────────────────
    private BigDecimal taxDepnCost       = BigDecimal.ZERO;
    private BigDecimal accumTaxDepn      = BigDecimal.ZERO;
    private BigDecimal accumTaxDepnAdj   = BigDecimal.ZERO;
    private LocalDate  lastTaxDepnDate;

    // ── Retirement proceeds from FAASSET ──────────────────────────────
    private BigDecimal retmtProceedsVal  = BigDecimal.ZERO;

    // ── Computed values (CALC-BOOK/TAX-WRITTEN-DOWN-VAL) ─────────────
    /**
     * BOOK WDV = (last_reval_val if > 0, else book_depn_cost)
     *            - (accum_book_depn + accum_book_depn_adj)
     */
    private BigDecimal bookWrittenDownVal     = BigDecimal.ZERO;
    /**
     * TAX WDV = (last_tax_reval_val if > 0, else tax_depn_cost)
     *           - accum_tax_depn - accum_tax_depn_adj
     */
    private BigDecimal taxWrittenDownVal      = BigDecimal.ZERO;

    /** retmt_proceeds_val - book_WDV */
    private BigDecimal bookDisposalProfitLoss = BigDecimal.ZERO;
    /** retmt_proceeds_val - tax_WDV */
    private BigDecimal taxDisposalProfitLoss  = BigDecimal.ZERO;

    // ── Pooled asset totals (WS-ASSET-DATA, from ACCUM-NEXT-TRX) ─────
    private BigDecimal totalBookDepnCost   = BigDecimal.ZERO;
    private BigDecimal totalTaxDepnCost    = BigDecimal.ZERO;
    private BigDecimal totalAccumBookDepn  = BigDecimal.ZERO;
    private BigDecimal totalAccumTaxDepn   = BigDecimal.ZERO;
    private BigDecimal totalRetmtProceeds  = BigDecimal.ZERO;

    // ── Per-transaction lines for pooled assets (DETAIL-LINE-16/17) ──
    /** AQ or RT transactions printed individually under the pooled summary */
    private List<PooledTrxLine> pooledTrxs = new ArrayList<>();

    // ── Helpers ───────────────────────────────────────────────────────

    /** Compute accumBookDepn + accumBookDepnAdj (COBOL A20) */
    public BigDecimal totalAccumBookDepnForDisplay() {
        return accumBookDepn.add(accumBookDepnAdj);
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

    // ── Pooled transaction line ───────────────────────────────────────

    /**
     * One individual AQ or RT transaction under a pooled asset summary.
     * Maps to DETAIL-LINE-16 (BOOK) and DETAIL-LINE-17 (TAX).
     */
    public record PooledTrxLine(
        String    trxType,
        LocalDate trxDate,
        BigDecimal bookDepnCost,
        BigDecimal taxDepnCost,
        BigDecimal retmtProceeds,
        BigDecimal bookDisposalProfit,
        BigDecimal taxDisposalProfit
    ) {}

    // ── Getters / Setters ─────────────────────────────────────────────

    public String   getAssetNo()                    { return assetNo; }
    public void     setAssetNo(String v)            { assetNo = v; }

    public String   getDesc1()                      { return desc1; }
    public void     setDesc1(String v)              { desc1 = v; }

    public String   getDesc2()                      { return desc2; }
    public void     setDesc2(String v)              { desc2 = v; }

    public String   getLocCode()                    { return locCode; }
    public void     setLocCode(String v)            { locCode = v; }

    public String   getDeptCode()                   { return deptCode; }
    public void     setDeptCode(String v)           { deptCode = v; }

    public String   getGrpCode()                    { return grpCode; }
    public void     setGrpCode(String v)            { grpCode = v; }

    public String   getSubgrpCode()                 { return subgrpCode; }
    public void     setSubgrpCode(String v)         { subgrpCode = v; }

    public String   getAssetStatus()                { return assetStatus; }
    public void     setAssetStatus(String v)        { assetStatus = v; }

    public boolean  isPooledAsset()                 { return pooledAsset; }
    public void     setPooledAsset(boolean v)       { pooledAsset = v; }

    public LocalDate getAcqnDate()                  { return acqnDate; }
    public void      setAcqnDate(LocalDate v)       { acqnDate = v; }

    public LocalDate getRetmtDate()                 { return retmtDate; }
    public void      setRetmtDate(LocalDate v)      { retmtDate = v; }

    public BigDecimal getBookDepnCost()             { return bookDepnCost; }
    public void       setBookDepnCost(BigDecimal v) { bookDepnCost = bd(v); }

    public BigDecimal getLastRevalVal()             { return lastRevalVal; }
    public void       setLastRevalVal(BigDecimal v) { lastRevalVal = bd(v); }

    public BigDecimal getAccumBookDepn()            { return accumBookDepn; }
    public void       setAccumBookDepn(BigDecimal v){ accumBookDepn = bd(v); }

    public BigDecimal getAccumBookDepnAdj()             { return accumBookDepnAdj; }
    public void       setAccumBookDepnAdj(BigDecimal v) { accumBookDepnAdj = bd(v); }

    public LocalDate getLastBookDepnDate()              { return lastBookDepnDate; }
    public void      setLastBookDepnDate(LocalDate v)   { lastBookDepnDate = v; }

    public BigDecimal getLastTaxRevalVal()              { return lastTaxRevalVal; }
    public void       setLastTaxRevalVal(BigDecimal v)  { lastTaxRevalVal = bd(v); }

    public BigDecimal getTaxDepnCost()              { return taxDepnCost; }
    public void       setTaxDepnCost(BigDecimal v)  { taxDepnCost = bd(v); }

    public BigDecimal getAccumTaxDepn()             { return accumTaxDepn; }
    public void       setAccumTaxDepn(BigDecimal v) { accumTaxDepn = bd(v); }

    public BigDecimal getAccumTaxDepnAdj()              { return accumTaxDepnAdj; }
    public void       setAccumTaxDepnAdj(BigDecimal v)  { accumTaxDepnAdj = bd(v); }

    public LocalDate getLastTaxDepnDate()               { return lastTaxDepnDate; }
    public void      setLastTaxDepnDate(LocalDate v)    { lastTaxDepnDate = v; }

    public BigDecimal getRetmtProceedsVal()             { return retmtProceedsVal; }
    public void       setRetmtProceedsVal(BigDecimal v) { retmtProceedsVal = bd(v); }

    public BigDecimal getBookWrittenDownVal()            { return bookWrittenDownVal; }
    public void       setBookWrittenDownVal(BigDecimal v){ bookWrittenDownVal = bd(v); }

    public BigDecimal getTaxWrittenDownVal()             { return taxWrittenDownVal; }
    public void       setTaxWrittenDownVal(BigDecimal v) { taxWrittenDownVal = bd(v); }

    public BigDecimal getBookDisposalProfitLoss()            { return bookDisposalProfitLoss; }
    public void       setBookDisposalProfitLoss(BigDecimal v){ bookDisposalProfitLoss = bd(v); }

    public BigDecimal getTaxDisposalProfitLoss()             { return taxDisposalProfitLoss; }
    public void       setTaxDisposalProfitLoss(BigDecimal v) { taxDisposalProfitLoss = bd(v); }

    public BigDecimal getTotalBookDepnCost()             { return totalBookDepnCost; }
    public void       setTotalBookDepnCost(BigDecimal v) { totalBookDepnCost = bd(v); }

    public BigDecimal getTotalTaxDepnCost()              { return totalTaxDepnCost; }
    public void       setTotalTaxDepnCost(BigDecimal v)  { totalTaxDepnCost = bd(v); }

    public BigDecimal getTotalAccumBookDepn()            { return totalAccumBookDepn; }
    public void       setTotalAccumBookDepn(BigDecimal v){ totalAccumBookDepn = bd(v); }

    public BigDecimal getTotalAccumTaxDepn()             { return totalAccumTaxDepn; }
    public void       setTotalAccumTaxDepn(BigDecimal v) { totalAccumTaxDepn = bd(v); }

    public BigDecimal getTotalRetmtProceeds()            { return totalRetmtProceeds; }
    public void       setTotalRetmtProceeds(BigDecimal v){ totalRetmtProceeds = bd(v); }

    public List<PooledTrxLine> getPooledTrxs()           { return pooledTrxs; }
    public void                setPooledTrxs(List<PooledTrxLine> v){ pooledTrxs = v; }

    private BigDecimal bd(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
