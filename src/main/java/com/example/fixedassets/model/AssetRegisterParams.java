package com.example.fixedassets.model;

import java.time.LocalDate;

/**
 * Parameters for FATL10 — Fixed Asset Register.
 * Mirrors the 17 fields on the FATL10S0 parameter screen.
 */
public class AssetRegisterParams {

    // ── Asset range ────────────────────────────────────────────────────
    private String  startAssetNo    = "";          // blank = all
    private String  endAssetNo      = "zzzzzzzzzzzzzzzzzzzz";  // default all

    // ── Filters ────────────────────────────────────────────────────────
    private String  printLocn       = "";          // blank = all locations
    private String  printDept       = "";          // blank = all departments
    private String  printGroup      = "";          // blank = all groups
    private String  printSubGroup   = "";          // blank = all sub-groups

    // ── Asset status Y/N flags (default all Y) ─────────────────────────
    private boolean includeUnposted = true;        // U
    private boolean includeActive   = true;        // space
    private boolean includeOnHold   = true;        // H
    private boolean includeInactive = true;        // N
    private boolean includeRetired  = true;        // R

    // ── Other selection criteria ───────────────────────────────────────
    private String  leasedInd       = "A";         // L=leased N=non-leased A=all
    private String  bookTaxInd      = "B";         // B=book T=tax
    private String  costInd         = "A";         // A=actual R=revalued

    // ── Date range ─────────────────────────────────────────────────────
    private LocalDate asAtDate      = LocalDate.now();
    private LocalDate startDate     = LocalDate.of(1900, 1, 1);  // exclude if retired before
    private boolean   includeIfActivity = false;   // Y=only if activity in date range

    // ── Descriptions (populated from lookups, for display only) ────────
    private String  locnDesc        = "ALL LOCATIONS";
    private String  deptDesc        = "ALL DEPARTMENTS";
    private String  groupDesc       = "ALL GROUPS";
    private String  subGroupDesc    = "ALL SUB-GROUPS";

    // ── Getters / setters ──────────────────────────────────────────────

    public String  getStartAssetNo()    { return startAssetNo; }
    public void    setStartAssetNo(String v) { this.startAssetNo = v == null ? "" : v.trim(); }

    public String  getEndAssetNo()      { return endAssetNo; }
    public void    setEndAssetNo(String v) { this.endAssetNo = v == null ? "zzzzzzzzzzzzzzzzzzzz" : v.trim(); }

    public String  getPrintLocn()       { return printLocn; }
    public void    setPrintLocn(String v) { this.printLocn = v == null ? "" : v.trim(); }

    public String  getPrintDept()       { return printDept; }
    public void    setPrintDept(String v) { this.printDept = v == null ? "" : v.trim(); }

    public String  getPrintGroup()      { return printGroup; }
    public void    setPrintGroup(String v) { this.printGroup = v == null ? "" : v.trim(); }

    public String  getPrintSubGroup()   { return printSubGroup; }
    public void    setPrintSubGroup(String v) { this.printSubGroup = v == null ? "" : v.trim(); }

    public boolean isIncludeUnposted()  { return includeUnposted; }
    public void    setIncludeUnposted(boolean v) { this.includeUnposted = v; }

    public boolean isIncludeActive()    { return includeActive; }
    public void    setIncludeActive(boolean v) { this.includeActive = v; }

    public boolean isIncludeOnHold()    { return includeOnHold; }
    public void    setIncludeOnHold(boolean v) { this.includeOnHold = v; }

    public boolean isIncludeInactive()  { return includeInactive; }
    public void    setIncludeInactive(boolean v) { this.includeInactive = v; }

    public boolean isIncludeRetired()   { return includeRetired; }
    public void    setIncludeRetired(boolean v) { this.includeRetired = v; }

    public String  getLeasedInd()       { return leasedInd; }
    public void    setLeasedInd(String v) { this.leasedInd = v == null ? "A" : v.toUpperCase().trim(); }

    public String  getBookTaxInd()      { return bookTaxInd; }
    public void    setBookTaxInd(String v) { this.bookTaxInd = v == null ? "B" : v.toUpperCase().trim(); }

    public String  getCostInd()         { return costInd; }
    public void    setCostInd(String v) { this.costInd = v == null ? "A" : v.toUpperCase().trim(); }

    public LocalDate getAsAtDate()      { return asAtDate; }
    public void      setAsAtDate(LocalDate v) { this.asAtDate = v; }

    public LocalDate getStartDate()     { return startDate; }
    public void      setStartDate(LocalDate v) { this.startDate = v; }

    public boolean isIncludeIfActivity() { return includeIfActivity; }
    public void    setIncludeIfActivity(boolean v) { this.includeIfActivity = v; }

    public String  getLocnDesc()        { return locnDesc; }
    public void    setLocnDesc(String v) { this.locnDesc = v; }

    public String  getDeptDesc()        { return deptDesc; }
    public void    setDeptDesc(String v) { this.deptDesc = v; }

    public String  getGroupDesc()       { return groupDesc; }
    public void    setGroupDesc(String v) { this.groupDesc = v; }

    public String  getSubGroupDesc()    { return subGroupDesc; }
    public void    setSubGroupDesc(String v) { this.subGroupDesc = v; }

    /** Validate: returns error message or null if OK */
    public String validate() {
        if (!endAssetNo.isBlank() && !startAssetNo.isBlank()
                && endAssetNo.compareToIgnoreCase(startAssetNo) < 0)
            return "Ending asset no must be >= starting asset no";
        if (asAtDate == null)
            return "As-at date is required";
        if (startDate != null && startDate.isAfter(asAtDate))
            return "Start date cannot be after as-at date";
        if (!leasedInd.matches("[LNA]"))
            return "Leased indicator must be L, N, or A";
        if (!bookTaxInd.matches("[BT]"))
            return "Book/Tax indicator must be B or T";
        if (!costInd.matches("[AR]"))
            return "Cost indicator must be A or R";
        if (!includeUnposted && !includeActive && !includeOnHold
                && !includeInactive && !includeRetired)
            return "At least one asset status must be selected";
        return null;
    }
}
