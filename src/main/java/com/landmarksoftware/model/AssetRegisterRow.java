package com.landmarksoftware.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One detail row in the FATL10 Fixed Asset Register.
 * Holds the data for both detail lines 04 and 05, plus computed WDV.
 */
public class AssetRegisterRow {

    // ── Line 04 fields ─────────────────────────────────────────────────
    private String     assetNo;
    private String     desc1;
    private String     locCode;
    private String     grpCode;
    private LocalDate  acqnDate;
    private BigDecimal cost          = BigDecimal.ZERO;
    private BigDecimal accumDepn     = BigDecimal.ZERO;
    private String     depnMethod    = "";   // S=straight-line D=diminishing
    private String     depnCode      = "";
    private BigDecimal depnRate      = BigDecimal.ZERO;
    private int        depnFreq      = 0;
    private LocalDate  startDepnDate;
    private LocalDate  lastDepnDate;

    // ── Line 05 fields ─────────────────────────────────────────────────
    private String     desc2         = "";
    private String     deptCode      = "";
    private String     subgrpCode    = "";

    // ── Computed ───────────────────────────────────────────────────────
    private BigDecimal wdv           = BigDecimal.ZERO;
    private String     statusDisplay = "";   // for Excel output

    // ── Control break keys ─────────────────────────────────────────────
    private String     ak1Loc        = "";   // for group break detection
    private String     ak1Grp        = "";

    // ── Getters / setters ──────────────────────────────────────────────

    public String     getAssetNo()      { return assetNo; }
    public void       setAssetNo(String v) { this.assetNo = v; }

    public String     getDesc1()        { return desc1; }
    public void       setDesc1(String v) { this.desc1 = v; }

    public String     getDesc2()        { return desc2; }
    public void       setDesc2(String v) { this.desc2 = v; }

    public String     getLocCode()      { return locCode; }
    public void       setLocCode(String v) { this.locCode = v; }

    public String     getDeptCode()     { return deptCode; }
    public void       setDeptCode(String v) { this.deptCode = v; }

    public String     getGrpCode()      { return grpCode; }
    public void       setGrpCode(String v) { this.grpCode = v; }

    public String     getSubgrpCode()   { return subgrpCode; }
    public void       setSubgrpCode(String v) { this.subgrpCode = v; }

    public LocalDate  getAcqnDate()     { return acqnDate; }
    public void       setAcqnDate(LocalDate v) { this.acqnDate = v; }

    public BigDecimal getCost()         { return cost; }
    public void       setCost(BigDecimal v) { this.cost = v != null ? v : BigDecimal.ZERO; }

    public BigDecimal getAccumDepn()    { return accumDepn; }
    public void       setAccumDepn(BigDecimal v) { this.accumDepn = v != null ? v : BigDecimal.ZERO; }

    public BigDecimal getWdv()          { return wdv; }
    public void       setWdv(BigDecimal v) { this.wdv = v != null ? v : BigDecimal.ZERO; }

    public String     getDepnMethod()   { return depnMethod; }
    public void       setDepnMethod(String v) { this.depnMethod = v != null ? v : ""; }

    public String     getDepnCode()     { return depnCode; }
    public void       setDepnCode(String v) { this.depnCode = v != null ? v : ""; }

    public BigDecimal getDepnRate()     { return depnRate; }
    public void       setDepnRate(BigDecimal v) { this.depnRate = v != null ? v : BigDecimal.ZERO; }

    public int        getDepnFreq()     { return depnFreq; }
    public void       setDepnFreq(int v) { this.depnFreq = v; }

    public LocalDate  getStartDepnDate() { return startDepnDate; }
    public void       setStartDepnDate(LocalDate v) { this.startDepnDate = v; }

    public LocalDate  getLastDepnDate()  { return lastDepnDate; }
    public void       setLastDepnDate(LocalDate v) { this.lastDepnDate = v; }

    public String     getStatusDisplay() { return statusDisplay; }
    public void       setStatusDisplay(String v) { this.statusDisplay = v != null ? v : ""; }

    public String     getAk1Loc()       { return ak1Loc; }
    public void       setAk1Loc(String v) { this.ak1Loc = v != null ? v : ""; }

    public String     getAk1Grp()       { return ak1Grp; }
    public void       setAk1Grp(String v) { this.ak1Grp = v != null ? v : ""; }
}
