package com.example.fixedassets.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Domain model for a FATRANS record.
 *
 * COBOL FD source: fatrans.fd
 *
 * FATL12 reads FATRANS for three transaction types:
 *
 *   AQ  — Acquisition      (FATRANS-ACQN-ACTUAL-COST etc.)
 *   BD  — Book Depreciation (FATRANS-DEPN-AMT, used for O=opening-balance calc)
 *   TD  — Tax Depreciation  (same fields, tax stream)
 *   BA  — Book Depn Adjustment
 *   TA  — Tax Depn Adjustment
 *   RV  — Revaluation       (FATRANS-REVAL-ADJ-AMT, post-period revaluation)
 *
 * Only fields accessed in fatl12.pl are mapped here. The transfer, retirement,
 * and other sub-records are omitted from this skeleton.
 */
public class FaTransactionRow {

    // ── Key fields ────────────────────────────────────────────────────────
    /** FATRANS-ASSET-NO    PIC X(20) */
    private String assetNo;
    /** FATRANS-TRX-TYPE    PIC X(2)  e.g. "AQ","BD","TD","BA","TA","RV" */
    private String trxType;
    /** FATRANS-TRX-DATE    PIC 9(6) COMP-3 */
    private LocalDate trxDate;
    /** FATRANS-BATCH-NO    PIC 9(6) */
    private int batchNo;

    // ── Status ────────────────────────────────────────────────────────────
    /** FATRANS-TRX-STATUS  PIC X(1) */
    private String trxStatus;
    /** FATRANS-OP-BAL-FLAG PIC X(1)  'Y' = opening balance record */
    private String opBalFlag;

    // ── Acquisition sub-record (TRX-TYPE = 'AQ') ─────────────────────────
    /** FATRANS-ACQN-ACTUAL-COST     PIC S9(9)V99 COMP-3 */
    private BigDecimal acqnActualCost;
    /** FATRANS-ACQN-BOOK-DEPN-COST  PIC S9(9)V99 COMP-3 */
    private BigDecimal acqnBookDepnCost;
    /** FATRANS-ACQN-TAX-DEPN-COST   PIC S9(9)V99 COMP-3 */
    private BigDecimal acqnTaxDepnCost;

    // ── Depreciation sub-record (TRX-TYPE = 'BD','TD','BA','TA') ─────────
    /** FATRANS-DEPN-METHOD   PIC X(1) */
    private String depnMethod;
    /** FATRANS-DEPN-CODE     PIC X(6) */
    private String depnCode;
    /** FATRANS-DEPN-FREQ     PIC 9(2) */
    private int depnFreq;
    /** FATRANS-DEPN-RATE     PIC 9(3)V99 COMP-3 */
    private BigDecimal depnRate;
    /**
     * FATRANS-DEPN-AMT  PIC 9(9)V99 COMP-3
     *
     * Used by FATL12 to reconstruct the opening WDV when CALC-BASE = 'O'.
     * The engine sums BA/BD (or TA/TD) transactions up to the projection start.
     */
    private BigDecimal depnAmt;
    /** FATRANS-DEPN-THRU-TO-DATE  PIC 9(6) COMP-3 */
    private LocalDate depnThruToDate;
    /** FATRANS-DEPN-CALC-IND   PIC X(1) */
    private String depnCalcInd;
    /** FATRANS-DEPN-CALC-BASE  PIC X(1) */
    private String depnCalcBase;

    // ── Revaluation sub-record (TRX-TYPE = 'RV') ─────────────────────────
    /** FATRANS-REVAL-VAL      PIC 9(9)V99 COMP-3 */
    private BigDecimal revalVal;
    /**
     * FATRANS-REVAL-ADJ-AMT  PIC S9(9)V99 COMP-3
     *
     * Post-period revaluation adjustment. FATL12 subtracts RV records dated
     * after the current projection period when computing the depreciable base.
     */
    private BigDecimal revalAdjAmt;
    /** FATRANS-REVAL-ACCUM-DEPN  PIC S9(9)V99 COMP-3 */
    private BigDecimal revalAccumDepn;

    // ── Getters / setters ─────────────────────────────────────────────────

    public String     getAssetNo()                          { return assetNo; }
    public void       setAssetNo(String v)                 { this.assetNo = v; }

    public String     getTrxType()                          { return trxType; }
    public void       setTrxType(String v)                 { this.trxType = v; }

    public LocalDate  getTrxDate()                          { return trxDate; }
    public void       setTrxDate(LocalDate v)              { this.trxDate = v; }

    public int        getBatchNo()                          { return batchNo; }
    public void       setBatchNo(int v)                    { this.batchNo = v; }

    public String     getTrxStatus()                        { return trxStatus; }
    public void       setTrxStatus(String v)               { this.trxStatus = v; }

    public String     getOpBalFlag()                        { return opBalFlag; }
    public void       setOpBalFlag(String v)               { this.opBalFlag = v; }

    public BigDecimal getAcqnActualCost()                   { return acqnActualCost; }
    public void       setAcqnActualCost(BigDecimal v)      { this.acqnActualCost = v; }

    public BigDecimal getAcqnBookDepnCost()                 { return acqnBookDepnCost; }
    public void       setAcqnBookDepnCost(BigDecimal v)    { this.acqnBookDepnCost = v; }

    public BigDecimal getAcqnTaxDepnCost()                  { return acqnTaxDepnCost; }
    public void       setAcqnTaxDepnCost(BigDecimal v)     { this.acqnTaxDepnCost = v; }

    public String     getDepnMethod()                       { return depnMethod; }
    public void       setDepnMethod(String v)              { this.depnMethod = v; }

    public String     getDepnCode()                         { return depnCode; }
    public void       setDepnCode(String v)                { this.depnCode = v; }

    public int        getDepnFreq()                         { return depnFreq; }
    public void       setDepnFreq(int v)                   { this.depnFreq = v; }

    public BigDecimal getDepnRate()                         { return depnRate; }
    public void       setDepnRate(BigDecimal v)            { this.depnRate = v; }

    public BigDecimal getDepnAmt()                          { return depnAmt; }
    public void       setDepnAmt(BigDecimal v)             { this.depnAmt = v; }

    public LocalDate  getDepnThruToDate()                   { return depnThruToDate; }
    public void       setDepnThruToDate(LocalDate v)       { this.depnThruToDate = v; }

    public String     getDepnCalcInd()                      { return depnCalcInd; }
    public void       setDepnCalcInd(String v)             { this.depnCalcInd = v; }

    public String     getDepnCalcBase()                     { return depnCalcBase; }
    public void       setDepnCalcBase(String v)            { this.depnCalcBase = v; }

    public BigDecimal getRevalVal()                         { return revalVal; }
    public void       setRevalVal(BigDecimal v)            { this.revalVal = v; }

    public BigDecimal getRevalAdjAmt()                      { return revalAdjAmt; }
    public void       setRevalAdjAmt(BigDecimal v)         { this.revalAdjAmt = v; }

    public BigDecimal getRevalAccumDepn()                   { return revalAccumDepn; }
    public void       setRevalAccumDepn(BigDecimal v)      { this.revalAccumDepn = v; }

    // ── Type helpers ──────────────────────────────────────────────────────

    public boolean isAcquisition()         { return "AQ".equals(trxType); }
    public boolean isBookDepn()            { return "BD".equals(trxType); }
    public boolean isTaxDepn()             { return "TD".equals(trxType); }
    public boolean isBookDepnAdj()         { return "BA".equals(trxType); }
    public boolean isTaxDepnAdj()          { return "TA".equals(trxType); }
    public boolean isRevaluation()         { return "RV".equals(trxType); }
    public boolean isDepnOrAdj(char stream){
        return stream == 'T' ? (isTaxDepn() || isTaxDepnAdj())
                             : (isBookDepn() || isBookDepnAdj());
    }
}
