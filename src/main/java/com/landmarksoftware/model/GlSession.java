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

import java.time.LocalDate;

/**
 * Session context populated from the GLPASS Vision file.
 *
 * COBOL FD source: glpass.fd
 *
 * GLPASS is a per-terminal session record written at login and read by
 * every program in the suite to get the current company, year, and
 * various session parameters. It is keyed by GLPASS-TERMINAL-NO (PIC 9(3)).
 *
 * FATL12 reads the following fields (confirmed from fatl12.cbl and fatl13.cbl):
 *
 *   GLPASS-COMPANY-NO        PIC 9(3)    — determines which company's data to process
 *   GLPASS-YR-NO             PIC 9(2)    — 2-digit current fiscal year
 *   GLPASS-YEAR-NO           PIC 9(4)    — 4-digit current fiscal year
 *   GLPASS-BATCH-NO          PIC 9(6)    — current batch (written to CMBATCH)
 *   GLPASS-OPEN-BAL-DATE     PIC 9(6)    — opening balance date for cost reconstruction
 *   GLPASS-FA-TAX-YR-END-MTH PIC 9(2)   — FA tax year end month (propagated from CPCOYCO)
 *
 * In Java, GLPASS is replaced by the application's security/session context.
 * This class is the domain model; GlSessionRepository reads it from the database
 * (or it can be populated from Spring Security + application config).
 *
 * Key: terminal_no  PIC 9(3)
 *   In a web/service context this maps to a user session identifier.
 */
public class GlSession {

    // ── Session identity ─────────────────────────────────────────────────
    /** GLPASS-TERMINAL-NO  PIC 9(3) — Vision file key (session/terminal ID) */
    private int terminalNo;

    // ── Company & year context ────────────────────────────────────────────
    /** GLPASS-COMPANY-NO  PIC 9(3) */
    private int companyNo;

    /** GLPASS-YR-NO  PIC 9(2) — 2-digit fiscal year (e.g. 25 for FY2025) */
    private int yrNo;

    /** GLPASS-YEAR-NO  PIC 9(4) — 4-digit fiscal year (e.g. 2025) */
    private int yearNo;

    /** GLPASS-BATCH-NO  PIC 9(6) — current processing batch number */
    private int batchNo;

    // ── FA-specific session values ────────────────────────────────────────
    /**
     * GLPASS-OPEN-BAL-DATE  PIC 9(6)
     *
     * Opening balance date — used by FATL12 when calc-base = 'O' to
     * determine the cut-off for transaction accumulation.
     * Stored as YYMMDD in COBOL; mapped to LocalDate here.
     */
    private LocalDate openBalDate;

    /**
     * GLPASS-FA-TAX-YR-END-MTH  PIC 9(2)
     *
     * FA tax year end month — copied from CPCOYCO-FA-TAX-YR-END-MTH at login.
     * Determines the tax year boundary for tax-stream depreciation projection.
     * Month 1-12; e.g. 6 = year ends June, 12 = year ends December.
     */
    private int faTaxYrEndMth;

    // ── Additional session flags read by FATL13 ───────────────────────────
    /** GLPASS-FA-DET-SUMM-DEPN-IND  PIC X(1) */
    private String faDetSummDepnInd;

    /** GLPASS-BOOK-OR-TAX-IND  PIC X(1) — default 'B' or 'T' for FA programs */
    private String bookOrTaxInd;

    // ── Getters / setters ─────────────────────────────────────────────────

    public int       getTerminalNo()                   { return terminalNo; }
    public void      setTerminalNo(int v)             { this.terminalNo = v; }

    public int       getCompanyNo()                    { return companyNo; }
    public void      setCompanyNo(int v)              { this.companyNo = v; }

    public int       getYrNo()                         { return yrNo; }
    public void      setYrNo(int v)                   { this.yrNo = v; }

    public int       getYearNo()                       { return yearNo; }
    public void      setYearNo(int v)                 { this.yearNo = v; }

    public int       getBatchNo()                      { return batchNo; }
    public void      setBatchNo(int v)                { this.batchNo = v; }

    public LocalDate getOpenBalDate()                  { return openBalDate; }
    public void      setOpenBalDate(LocalDate v)      { this.openBalDate = v; }

    public int       getFaTaxYrEndMth()                { return faTaxYrEndMth; }
    public void      setFaTaxYrEndMth(int v)          { this.faTaxYrEndMth = v; }

    public String    getFaDetSummDepnInd()             { return faDetSummDepnInd; }
    public void      setFaDetSummDepnInd(String v)    { this.faDetSummDepnInd = v; }

    public String    getBookOrTaxInd()                 { return bookOrTaxInd; }
    public void      setBookOrTaxInd(String v)        { this.bookOrTaxInd = v; }

    // ── Derived helpers ───────────────────────────────────────────────────

    /**
     * Returns the month number that starts the FA tax year.
     * e.g. faTaxYrEndMth=6 (June year-end) → returns 7 (July start).
     */
    public int faTaxYrStartMth() {
        return (faTaxYrEndMth % 12) + 1;
    }

    /**
     * Returns the 4-digit tax year number for a given projection date,
     * given this session's tax year end month.
     *
     * e.g. for a June year-end (mth=6):
     *   date 2025-03-31 → tax year 2025 (Jan-Jun 2025 is within FY2025)
     *   date 2025-08-31 → tax year 2026 (Jul 2025 – Jun 2026 = FY2026)
     */
    public int taxYearFor(java.time.LocalDate date) {
        if (date.getMonthValue() > faTaxYrEndMth) {
            return date.getYear() + 1;
        }
        return date.getYear();
    }
}
