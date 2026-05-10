package com.landmarksoftware.model;

/**
 * Minimal projection of CPCOYCO needed by the FA depreciation engine.
 *
 * COBOL FD source: cpcoyco.fd
 *
 * CPCOYCO is the company master record — a very large record covering
 * AR, AP, GL, CM, SM, PO, OP, PY, JL, FA and other module config.
 * FATL12 reads exactly ONE field from it:
 *
 *   CPCOYCO-FA-TAX-YR-END-MTH  PIC 9(2)
 *     Located in: CPCOYCO-FA-DATA (03 CPCOYCO-MODULE-DATA → 05 CPCOYCO-FA-DATA)
 *     Purpose: determines the tax year boundary when projecting tax-stream
 *              depreciation (stream = 'T'). Month number 1-12.
 *              e.g. 6 = tax year ends 30 June; 12 = ends 31 December.
 *
 * Key: CPCOYCO-COMPANY-NO  PIC 9(3)
 */
public class CompanyRow {

    /** CPCOYCO-COMPANY-NO  PIC 9(3) — primary key */
    private int companyNo;

    /**
     * CPCOYCO-FA-TAX-YR-END-MTH  PIC 9(2)
     *
     * Month number (1-12) of the last month of the tax year.
     * Used by DepreciationProjectionService to calculate the tax year
     * start and end dates when projecting tax-stream depreciation.
     *
     * Examples:
     *   6  → tax year runs 1 July – 30 June  (Australian standard)
     *   12 → tax year runs 1 January – 31 December
     */
    private int    faTaxYrEndMth;
    /** name1 column — company trading name */
    private String name;

    // ── Getters / setters ─────────────────────────────────────────────────

    public int getCompanyNo()                  { return companyNo; }
    public void setCompanyNo(int v)           { this.companyNo = v; }

    public int    getFaTaxYrEndMth()             { return faTaxYrEndMth; }
    public String getName()                      { return name; }
    public void   setName(String v)             { this.name = v; }
    public void setFaTaxYrEndMth(int v)       { this.faTaxYrEndMth = v; }

    // ── Derived helpers ───────────────────────────────────────────────────

    /**
     * Returns the 1-based month number that starts the tax year.
     * e.g. if tax year ends month 6 (June), it starts month 7 (July).
     */
    public int faTaxYrStartMth() {
        return (faTaxYrEndMth % 12) + 1;
    }
}
