package com.example.fixedassets.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Full FAASSET record loaded for edit pre-fill in FAAQ01.
 * Mirrors the AcqnRow.fullLoaded fields but as a proper model object
 * returned by AcquisitionService.loadFullAsset().
 */
public class AssetFullRecord {
    public String assetNo    = "";
    public String desc1      = "";
    public String desc2      = "";
    public String alpha      = "";
    public String locCode    = "";
    public String deptCode   = "";
    public String grpCode    = "";
    public String subgrpCode = "";
    public String site       = "";
    public String attachTo   = "";
    public boolean pooled    = false;
    public int qty           = 1;
    public LocalDate acqnDate;
    public String acqnType   = "";
    public String intOrder   = "";
    public String suppName   = "";
    public String suppNo     = "";
    public String suppInv    = "";
    public boolean leased    = false;
    public String contractNo = "";
    public String payFreq    = "";
    public BigDecimal payAmt     = BigDecimal.ZERO;
    public BigDecimal residual   = BigDecimal.ZERO;
    public BigDecimal contractVal= BigDecimal.ZERO;
    public BigDecimal disposalVal= BigDecimal.ZERO;
    public String insType        = "";
    public BigDecimal currIns    = BigDecimal.ZERO;
    public BigDecimal replNew    = BigDecimal.ZERO;
    public LocalDate replAsAt;
    public LocalDate lseExpiry;
    public String ref            = "";
    public BigDecimal actualCost    = BigDecimal.ZERO;
    public BigDecimal taxDepnCost   = BigDecimal.ZERO;
    public BigDecimal bookDepnCost  = BigDecimal.ZERO;
    public LocalDate writeDown;
    public String taxMethod    = "";
    public String taxCode      = "";
    public String taxCalcInd   = "";
    public String taxCalcBase  = "";
    public String taxFreq      = "";
    public BigDecimal taxRate1 = BigDecimal.ZERO;
    public BigDecimal taxRate2 = BigDecimal.ZERO;
    public LocalDate startTax;
    public String bookMethod   = "";
    public String bookCode     = "";
    public String bookCalcInd  = "";
    public String bookCalcBase = "";
    public String bookFreq     = "";
    public BigDecimal bookRate1= BigDecimal.ZERO;
    public BigDecimal bookRate2= BigDecimal.ZERO;
    public LocalDate startBook;
    public String postTo       = "N";
    public String ledgerCode   = "";
    public BigDecimal accumTaxDepn    = BigDecimal.ZERO;
    public BigDecimal accumTaxAdj     = BigDecimal.ZERO;
    public BigDecimal lastTaxRevalVal = BigDecimal.ZERO;
    public BigDecimal accumBookDepn   = BigDecimal.ZERO;
    public BigDecimal accumBookAdj    = BigDecimal.ZERO;
    public BigDecimal lastBookRevalVal= BigDecimal.ZERO;
    public BigDecimal poolTaxBal  = BigDecimal.ZERO;
    public BigDecimal poolBookBal = BigDecimal.ZERO;
    public LocalDate lastTaxDepn;
    public LocalDate lastTaxReval;
    public LocalDate lastBookDepn;
    public LocalDate lastBookReval;
}
