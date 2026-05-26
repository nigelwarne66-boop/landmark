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

import org.springframework.stereotype.Component;

/**
 * Application-wide session state — the Java equivalent of the COBOL GLPASS file.
 *
 * In COBOL, every program reads GLPASS at startup to get:
 *   - GLPASS-COMPANY-NO      → which company's data to process
 *   - GLPASS-YR-NO           → 2-digit current fiscal year
 *   - GLPASS-YEAR-NO         → 4-digit current fiscal year
 *   - GLPASS-BATCH-NO        → current batch number
 *   - GLPASS-FA-TAX-YR-END-MTH → FA tax year end month (from CPCOYCO)
 *   - GLPASS-USER-ID         → logged-in user
 *   - GLPASS-BATCH-DATE      → batch date (COMP-3 day number)
 *   - GLPASS-OPEN-BAL-DATE   → opening balance date
 *   - GLPASS-OVERSEAS-PARENT-FLAG
 *   - GLPASS-BA-INSTAL-FLAG  → Business Analysis installed
 *   - GLPASS-JL-INSTAL-FLAG  → Job Ledger installed
 *   - GLPASS-BATCH-CONTROL-FLAG
 *
 * This singleton is populated by MainMenuController when the user selects a
 * company and year (MENU23 dialog), and is injected into every UI controller
 * and service that needs it.
 *
 * It replaces the per-controller companyNo fields and hardcoded defaults.
 */
@Component
public class AppSession {

    // ── Identity ───────────────────────────────────────────────────────────
    // ── Session identity ─────────────────────────────────────────────────
    /** MEPASS-TERMINAL-NO  PIC 9(3) — allocated at login (1–998) */
    private int terminalNo = 0;

    public int  getTerminalNo()        { return terminalNo; }
    public void setTerminalNo(int v)   { this.terminalNo = v; }

    /** GLPASS-USER-ID — logged-in user */
    private String userId = System.getProperty("user.name", "");

    // ── Company ────────────────────────────────────────────────────────────
    /** GLPASS-COMPANY-NO  PIC 9(3) */
    private int    companyNo   = 1;

    /** CPCOYCO-NAME / MEPASS-COMPANY-NAME — display name */
    private String companyName = "";

    /** CPCOYCO-NAME-2 — secondary name line */
    private String companyName2 = "";

    // ── Fiscal year ────────────────────────────────────────────────────────
    /** GLPASS-YR-NO  PIC 9(2) — e.g. 25 */
    private int    yrNo    = 0;

    /** GLPASS-YEAR-NO  PIC 9(4) — e.g. 2025 */
    private int    yearNo  = 0;

    /** Display string — e.g. "FY 2024–25" */
    private String yearDesc = "";

    /** GLDATES-YR-START-DATE */
    private java.time.LocalDate yrStartDate;

    /** GLDATES-YR-END-DATE */
    private java.time.LocalDate yrEndDate;

    // ── FA-specific ────────────────────────────────────────────────────────
    /** CPCOYCO-FA-TAX-YR-END-MTH  PIC 9(2) — month 1–12 */
    private int    faTaxYrEndMth  = 6;

    /** MEUSERS-SUPERVISOR-FLAG — 'Y' or 'N' */
    private String supervisorFlag = "N";

    /** MEUSERS-PRINT-PA-FROM-PASS — 'Y' grants Payroll-section visibility. */
    private boolean payrollAccess = false;

    /** MEUSERS-NAME — display name of user */
    private String userName = "";

    /** GLPASS-BATCH-NO  PIC 9(6) */
    private int    batchNo        = 0;

    /** GLPASS-BATCH-DATE — current period date (day number) */
    private int    batchDate      = 0;

    /** GLPASS-OPEN-BAL-DATE — opening balance date (day number) */
    private int    openBalDate    = 0;

    /** GLPASS-BATCH-CONTROL-FLAG — 'Y' or 'N' */
    private String batchControlFlag = "Y";

    // ── Module install flags ───────────────────────────────────────────────
    /** GLPASS-BA-INSTAL-FLAG — Business Analysis installed */
    private String baInstalFlag   = "N";

    /** GLPASS-JL-INSTAL-FLAG — Job Ledger installed */
    private String jlInstalFlag   = "N";

    /** GLPASS-OVERSEAS-PARENT-FLAG */
    private String overseasParentFlag = "N";

    // ── Accessors ──────────────────────────────────────────────────────────

    public String  getUserId()              { return userId; }
    public void    setUserId(String v)      { this.userId = v; }

    public int     getCompanyNo()           { return companyNo; }
    public void    setCompanyNo(int v)      { this.companyNo = v; }

    public String  getCompanyName()         { return companyName; }
    public void    setCompanyName(String v) { this.companyName = v; }

    public String  getCompanyName2()         { return companyName2; }
    public void    setCompanyName2(String v) { this.companyName2 = v; }

    public int     getYrNo()               { return yrNo; }
    public void    setYrNo(int v)          { this.yrNo = v; }

    public int     getYearNo()             { return yearNo; }
    public void    setYearNo(int v)        { this.yearNo = v; }

    public String  getYearDesc()           { return yearDesc; }
    public void    setYearDesc(String v)   { this.yearDesc = v; }

    public java.time.LocalDate getYrStartDate()               { return yrStartDate; }
    public void                setYrStartDate(java.time.LocalDate v) { this.yrStartDate = v; }

    public java.time.LocalDate getYrEndDate()                 { return yrEndDate; }
    public void                setYrEndDate(java.time.LocalDate v)   { this.yrEndDate = v; }

    public int     getFaTaxYrEndMth()           { return faTaxYrEndMth; }
    public void    setFaTaxYrEndMth(int v)      { this.faTaxYrEndMth = v; }

    public String  getSupervisorFlag()              { return supervisorFlag; }
    public void    setSupervisorFlag(String v)      { this.supervisorFlag = v; }
    public boolean isSupervisor()                   { return "Y".equals(supervisorFlag); }

    public boolean isPayrollAccess()                { return payrollAccess; }
    public void    setPayrollAccess(boolean v)      { this.payrollAccess = v; }

    public String  getUserName()                    { return userName; }
    public void    setUserName(String v)            { this.userName = v; }

    /** GLPASS-BOOK-OR-TAX-IND — default 'B' */
    private String bookOrTaxInd   = "B";

    public String  getBookOrTaxInd()            { return bookOrTaxInd; }
    public void    setBookOrTaxInd(String v)    { this.bookOrTaxInd = v; }

    public int     getBatchNo()                 { return batchNo; }
    public void    setBatchNo(int v)            { this.batchNo = v; }

    public int     getBatchDate()               { return batchDate; }
    public void    setBatchDate(int v)          { this.batchDate = v; }

    public int     getOpenBalDate()             { return openBalDate; }
    public void    setOpenBalDate(int v)        { this.openBalDate = v; }

    public String  getBatchControlFlag()        { return batchControlFlag; }
    public void    setBatchControlFlag(String v){ this.batchControlFlag = v; }

    public String  getBaInstalFlag()            { return baInstalFlag; }
    public void    setBaInstalFlag(String v)    { this.baInstalFlag = v; }

    public String  getJlInstalFlag()            { return jlInstalFlag; }
    public void    setJlInstalFlag(String v)    { this.jlInstalFlag = v; }

    public String  getOverseasParentFlag()            { return overseasParentFlag; }
    public void    setOverseasParentFlag(String v)    { this.overseasParentFlag = v; }


    // ── Payroll session ───────────────────────────────────────────────────
    /**
     * Currently selected payrun (PARUNHD.payrun_no).
     * Set by payroll programs that operate on a specific payrun.
     * 0 = no payrun selected.
     */
    private int    selectedPayrunNo   = 0;
    private String selectedPayrunDate = "";
    private String selectedPayrunDesc = "";

    /**
     * Directory where payroll output files (ABA, reports) are written.
     * Read from CPCOYCO.payroll_files_dir; falls back to
     * landmark.payroll.output-dir in application.properties.
     */
    private String payrollFilesDir    = "";

    /** CPCOYCO.pa_instal_flag — blank or 'Y' = payroll installed. */
    private String paInstalFlag       = "Y";

    public int     getSelectedPayrunNo()                { return selectedPayrunNo; }
    public void    setSelectedPayrunNo(int v)           { this.selectedPayrunNo = v; }
    public String  getSelectedPayrunDate()              { return selectedPayrunDate; }
    public void    setSelectedPayrunDate(String v)      { this.selectedPayrunDate = v; }
    public String  getSelectedPayrunDesc()              { return selectedPayrunDesc; }
    public void    setSelectedPayrunDesc(String v)      { this.selectedPayrunDesc = v; }
    public boolean hasPayrun()                          { return selectedPayrunNo > 0; }
    public void    clearPayrun()                        { selectedPayrunNo=0; selectedPayrunDate=""; selectedPayrunDesc=""; }

    public String  getPayrollFilesDir()                 { return payrollFilesDir; }
    public void    setPayrollFilesDir(String v)         { this.payrollFilesDir = v; }
    public String  getPaInstalFlag()                    { return paInstalFlag; }
    public void    setPaInstalFlag(String v)            { this.paInstalFlag = v; }
    public boolean isPayrollInstalled()                 { return !"N".equals(paInstalFlag); }

    // ── Derived helpers ────────────────────────────────────────────────────

    /** Month that starts the FA tax year (faTaxYrEndMth + 1, wrapping Dec→Jan) */
    public int faTaxYrStartMth() {
        return (faTaxYrEndMth % 12) + 1;
    }

    /**
     * Tax year number for a given date, based on faTaxYrEndMth.
     * e.g. June year-end (mth=6): 2025-03-15 → 2025, 2025-08-01 → 2026
     */
    public int taxYearFor(java.time.LocalDate date) {
        return date.getMonthValue() > faTaxYrEndMth ? date.getYear() + 1 : date.getYear();
    }

    /** True if the session has a valid company and year selected */
    public boolean isReady() {
        return companyNo > 0 && yearNo > 0;
    }

    @Override
    public String toString() {
        return "AppSession{co=" + companyNo + " \"" + companyName + "\", yr=" + yearNo + "}";
    }
}
