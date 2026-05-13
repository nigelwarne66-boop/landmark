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
package com.landmarksoftware.payroll.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * PAAW01 — Job class within an award (paawjob table).
 *
 * <p>PK: {@code (company_no, award_code, job_class_code)}. Fields are
 * grouped here in the same logical sections the maintenance dialog uses:
 *
 * <ol>
 *   <li><b>Identity / rates</b> — code, desc, hours, $/hr, $/wk, annual</li>
 *   <li><b>LSL</b> — long service leave: start year, entitlement, calc
 *       method, casual variant, anniversary day/month</li>
 *   <li><b>Annual leave + AL Loading</b> — accrual hrs, eligibility,
 *       loading %, max</li>
 *   <li><b>Sick leave (3 tiers)</b> — hrs / after_mths / inc_lump / date
 *       for tiers 1–3</li>
 *   <li><b>Accrual</b> — RDO accrual, paid hrs/day, min accrual mins</li>
 *   <li><b>Super</b> — commence date, qualify days, min hrs/amt, bands
 *       1–3 with from/to/value, voluntary super, vest/non-vest fund codes</li>
 *   <li><b>Misc</b> — under-18 minimum, max hours, free-text other_data</li>
 * </ol>
 *
 * <p>Hours fields are stored as <strong>minutes</strong> in the DB
 * (project convention) — divide by 60 for display.
 */
public class AwardJobClass {

    // ── PK ───────────────────────────────────────────────────────────────
    public int        companyNo            = 0;
    public String     awardCode            = "";
    public String     jobClassCode         = "";

    // ── Identity / rates ─────────────────────────────────────────────────
    public String     desc1                = "";
    public int        stdHrs               = 0;          // minutes
    public BigDecimal ratePerHr            = BigDecimal.ZERO;
    public BigDecimal ratePerWeek          = BigDecimal.ZERO;
    public BigDecimal annualAmt            = BigDecimal.ZERO;

    // ── LSL (Long Service Leave) ─────────────────────────────────────────
    public int        lslStartYr           = 0;
    public int        lslHrs               = 0;          // minutes
    /** Calc method: "H" hours, "W" weeks. */
    public String     lslCalcMethod        = "H";
    public BigDecimal lslWeeks             = BigDecimal.ZERO;
    /** "Y" if lump sums included in LSL accrual base. */
    public String     lslIncLumpInd        = "N";
    /** Anniversary date: "Y" use day/month, "N" use start year. */
    public String     lslDateInd           = "N";
    public int        lslDateDay           = 0;
    public int        lslDateMonth         = 0;
    public int        lslCasStartYr        = 0;
    public BigDecimal lslCasWksPerYr       = BigDecimal.ZERO;
    public int        lslCasAveWks1        = 0;
    public int        lslCasAveWks2        = 0;

    // ── Annual leave ─────────────────────────────────────────────────────
    public int        alHrs                = 0;          // minutes
    public int        alAfterMths          = 0;
    public String     alIncLumpInd         = "N";
    public String     alDateInd            = "N";
    public int        alDateDay            = 0;
    public int        alDateMonth          = 0;

    // ── AL Loading ───────────────────────────────────────────────────────
    public BigDecimal allPerc              = BigDecimal.ZERO;
    public int        allAccrualMax        = 0;          // minutes
    public int        allHrs               = 0;          // minutes
    public int        allAfterMths         = 0;
    public String     allIncLumpInd        = "N";
    public String     allDateInd           = "N";
    public int        allDateDay           = 0;
    public int        allDateMonth         = 0;

    // ── Sick leave (3 tiers) ─────────────────────────────────────────────
    public int        sickHrs1             = 0;
    public int        sickHrs2             = 0;
    public int        sickHrs3             = 0;
    public int        sickAfterMths1       = 0;
    public int        sickAfterMths2       = 0;
    public int        sickAfterMths3       = 0;
    public String     sickIncLumpInd1      = "N";
    public String     sickIncLumpInd2      = "N";
    public String     sickIncLumpInd3      = "N";
    public String     sickDateInd1         = "N";
    public String     sickDateInd2         = "N";
    public String     sickDateInd3         = "N";
    public int        sickDateDay1         = 0;
    public int        sickDateDay2         = 0;
    public int        sickDateDay3         = 0;
    public int        sickDateMonth1       = 0;
    public int        sickDateMonth2       = 0;
    public int        sickDateMonth3       = 0;
    public int        sickAccrualMax       = 0;

    // ── Accrual ──────────────────────────────────────────────────────────
    public String     accrualPayCode       = "";
    public int        paidHrsPerDay        = 0;
    public int        accrualMinsPerDay    = 0;
    public int        minimumAccrualMins   = 0;
    public int        rdoAccrualMax        = 0;

    // ── Super ────────────────────────────────────────────────────────────
    public LocalDate  superCommenceDate;
    public int        qualifyDays          = 0;
    public int        minHrs               = 0;
    public BigDecimal minAmt               = BigDecimal.ZERO;
    /** "%" percent or "$" amount. */
    public String     percOrAmtFlag        = "%";
    /** "H" hours or "$" amount basis. */
    public String     hrsOrAmtFlag         = "H";
    public BigDecimal band1FromAmt         = BigDecimal.ZERO;
    public BigDecimal band1ToAmt           = BigDecimal.ZERO;
    public BigDecimal band1Value           = BigDecimal.ZERO;
    public BigDecimal band2FromAmt         = BigDecimal.ZERO;
    public BigDecimal band2ToAmt           = BigDecimal.ZERO;
    public BigDecimal band2Value           = BigDecimal.ZERO;
    public BigDecimal band3FromAmt         = BigDecimal.ZERO;
    public BigDecimal band3ToAmt           = BigDecimal.ZERO;
    public BigDecimal band3Value           = BigDecimal.ZERO;
    public String     volSuperFlag         = "N";
    public BigDecimal totalVolValue        = BigDecimal.ZERO;
    public BigDecimal addnVolValue         = BigDecimal.ZERO;
    public String     vestSuperCode        = "";
    public String     nonvestSuperCode     = "";

    // ── Misc ─────────────────────────────────────────────────────────────
    public int        minHrsUnder18        = 0;
    public int        maxHrs               = 0;
    public String     otherData            = "";
    public long       noteNo               = 0;
}
