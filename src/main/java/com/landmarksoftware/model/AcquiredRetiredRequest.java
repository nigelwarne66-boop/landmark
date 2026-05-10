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
 * Parameter object for FATL03 — Assets Acquired and Retired.
 *
 * Mirrors the 11 fields on the FATL03S0 parameter screen exactly.
 * Field ordering matches FATL03S0-FIELD-NAMES (01..11).
 *
 *  01  WS-START-ASSET-NO          X(20)  blank = all
 *  02  WS-END-ASSET-NO            X(20)
 *  03  WS-LOC-CODE                X(6)   blank = all locations
 *  04  WS-DEPT-CODE               X(6)   blank = all departments
 *  05  WS-GRP-CODE                X(6)   blank = all groups
 *  06  WS-SUBGRP-CODE             X(6)   blank = all sub-groups
 *  07  WS-START-DATE              9(6)   0 = all dates
 *  08  WS-END-DATE                9(6)
 *  09  WS-INCLUDE-LEASED-ASSETS   X(1)   N default
 *  10  WS-INCLUDE-POOLED-ASSETS   X(1)   blank default
 *  11  WS-ACQN-RETMT-IND          X(1)   A or R  (required)
 */
public class AcquiredRetiredRequest {

    // ── Company (from GLPASS) ──────────────────────────────────────────
    private int companyNo;

    // ── Asset range ───────────────────────────────────────────────────
    private String startAssetNo = "";
    private String endAssetNo   = "";

    // ── Code filters ──────────────────────────────────────────────────
    private String locCode    = "";
    private String deptCode   = "";
    private String grpCode    = "";
    private String subgrpCode = "";

    // ── Date range ────────────────────────────────────────────────────
    /** null = all dates */
    private LocalDate startDate = null;
    /** null = all dates */
    private LocalDate endDate   = null;

    // ── Options ───────────────────────────────────────────────────────
    /** Default N — N = exclude leased assets */
    private boolean includeLeased  = false;
    /** Default blank/false — Y = include pooled assets */
    private boolean includePooled  = false;

    /**
     * A = Acquisitions, R = Retirements.
     * This is the key discriminator for the whole report.
     */
    private String acqnRetmtInd = "A";

    // ── Helpers ───────────────────────────────────────────────────────

    public boolean isAcquisitions() { return "A".equals(acqnRetmtInd); }
    public boolean isRetirements()  { return "R".equals(acqnRetmtInd); }

    /** Effective end asset no for SQL (COBOL fills z's when start is blank) */
    public String effectiveEndAssetNo() {
        if (endAssetNo == null || endAssetNo.isBlank())
            return "~~~~~~~~~~~~~~~~~~~~";
        return endAssetNo;
    }

    /** Report title literal matching COBOL WS-ACQN-RETMT-LIT */
    public String acqnRetmtLiteral() {
        return isAcquisitions() ? "Acquisitions" : "Retirements";
    }

    // ── Getters / Setters ─────────────────────────────────────────────

    public int     getCompanyNo()           { return companyNo; }
    public void    setCompanyNo(int v)      { companyNo = v; }

    public String  getStartAssetNo()              { return startAssetNo; }
    public void    setStartAssetNo(String v)      { startAssetNo = v == null ? "" : v.trim(); }

    public String  getEndAssetNo()                { return endAssetNo; }
    public void    setEndAssetNo(String v)        { endAssetNo = v == null ? "" : v.trim(); }

    public String  getLocCode()                   { return locCode; }
    public void    setLocCode(String v)           { locCode = v == null ? "" : v.trim(); }

    public String  getDeptCode()                  { return deptCode; }
    public void    setDeptCode(String v)          { deptCode = v == null ? "" : v.trim(); }

    public String  getGrpCode()                   { return grpCode; }
    public void    setGrpCode(String v)           { grpCode = v == null ? "" : v.trim(); }

    public String  getSubgrpCode()                { return subgrpCode; }
    public void    setSubgrpCode(String v)        { subgrpCode = v == null ? "" : v.trim(); }

    public LocalDate getStartDate()               { return startDate; }
    public void      setStartDate(LocalDate v)    { startDate = v; }

    public LocalDate getEndDate()                 { return endDate; }
    public void      setEndDate(LocalDate v)      { endDate = v; }

    public boolean isIncludeLeased()              { return includeLeased; }
    public void    setIncludeLeased(boolean v)    { includeLeased = v; }

    public boolean isIncludePooled()              { return includePooled; }
    public void    setIncludePooled(boolean v)    { includePooled = v; }

    public String  getAcqnRetmtInd()              { return acqnRetmtInd; }
    public void    setAcqnRetmtInd(String v)      { acqnRetmtInd = v == null ? "A" : v.trim().toUpperCase(); }
}
