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
 * PAEM01 — Employee master record (pastaff table).
 *
 * pastaff PK: (company_no, employee_no INT)
 *
 * Field names mirror real pastaff column names (verified from Landmark
 * SQL extracts, not from CONVENTIONS.md which had several inaccuracies).
 *
 * Hours fields are stored in MINUTES in the DB; UI divides by 60 to display.
 *
 * Tax-file-number rule: never log or print in full. The UI masks all but
 * the last three digits — see {@link #maskedTfn()}.
 */
public class Employee {

    // ── Identity ──────────────────────────────────────────────────────────
    public int       companyNo        = 0;
    public int       employeeNo       = 0;       // pastaff.employee_no INT (PK)

    // ── Personal name ─────────────────────────────────────────────────────
    public String    surname          = "";      // pastaff.surname
    public String    firstName        = "";      // pastaff.first_name
    public String    secondName       = "";      // pastaff.second_name (optional middle)

    // ── Address ───────────────────────────────────────────────────────────
    public String    addr1            = "";      // pastaff.addr_1
    public String    addr2            = "";      // pastaff.addr_2
    public String    city             = "";      // pastaff.city
    public String    state            = "";      // pastaff.state
    public String    postcode         = "";      // pastaff.postcode

    // ── Contact ───────────────────────────────────────────────────────────
    public String    phoneArea        = "";      // pastaff.phone_area
    public String    phoneNo          = "";      // pastaff.phone_no
    public String    mobile           = "";      // pastaff.mobile
    public String    emailAddress     = "";      // pastaff.email_address

    // ── Employment ────────────────────────────────────────────────────────
    public String    dept             = "";      // pastaff.dept
    public String    paygroup         = "";      // pastaff.paygroup
    public String    employeeStatus   = "A";     // pastaff.employee_status — A=active, I=inactive, T=terminated
    public String    employeeType     = "";      // pastaff.employee_type — F=full-time, P=part-time, C=casual
    public LocalDate dateStarted;                // pastaff.date_started
    public LocalDate dateTerminated;             // pastaff.date_terminated
    public String    payFreq          = "W";     // pastaff.pay_freq — W=weekly, F=fortnightly, M=monthly
    public String    award            = "";      // pastaff.award
    public String    jobClass         = "";      // pastaff.job_class

    // ── Pay rates ─────────────────────────────────────────────────────────
    public BigDecimal annualSalary    = BigDecimal.ZERO;  // pastaff.annual_salary
    public BigDecimal stdRatePerHr    = BigDecimal.ZERO;  // pastaff.std_rate_per_hr
    public int        stdHrs          = 0;                // pastaff.std_hrs (minutes)

    // ── Tax ───────────────────────────────────────────────────────────────
    public String    taxFileNo        = "";      // pastaff.tax_file_no — never log
    public String    taxScaleNo       = "";      // pastaff.tax_scale_no
    public BigDecimal extraTaxAmt     = BigDecimal.ZERO;  // pastaff.extra_tax_amt
    /** {@code "Y"} when employee has a HECS / HELP debt (legacy). */
    public String    hecsDebtFlag     = "N";     // pastaff.hecs_debt_flag
    /**
     * STSL = Study and Training Support Loan (HELP + others). When {@code "Y"}
     * (or {@code hecsDebtFlag="Y"}) tax must use NAT_3539 — see PaygTaxCalculator.
     */
    public String    stslFlag         = "N";     // pastaff.stsl_flag

    /** Does this employee need STSL withholding? Either flag = "Y" triggers it. */
    public boolean hasStsl() {
        return "Y".equalsIgnoreCase(stslFlag) || "Y".equalsIgnoreCase(hecsDebtFlag);
    }

    // ── Leave balances ────────────────────────────────────────────────────
    // al_hrs_accrued is stored in MINUTES; accrued_sick_leave appears as a
    // bare numeric (treat as minutes for consistency with AL).
    public int       alHrsAccrued       = 0;     // pastaff.al_hrs_accrued
    public int       accruedSickLeave   = 0;     // pastaff.accrued_sick_leave
    // LSL on pastaff is stored as weeks_accrued + hrs_bef_78/aft_78/since_aug_93.
    // Read but display as informational only — too complex to edit on this screen.
    public BigDecimal lslWeeksAccrued = BigDecimal.ZERO;  // pastaff.lsl_weeks_accrued

    // ── Superannuation (PAEM01 S1C) ──────────────────────────────────────
    public String    superCode          = "";     // pastaff.super_code — FK to pacodes.pay_code (type 17/20)
    public String    superMemberNo      = "";     // pastaff.super_member_no
    public LocalDate superCommDate;                // pastaff.super_comm_date
    public int       qualifyDays        = 0;      // pastaff.qualify_days
    public String    forcePayFlag       = "N";    // pastaff.force_pay_flag — Y/N
    public String    useExtSuperFlag    = "N";    // pastaff.use_ext_super_flag — Y/N ("Calc super based on hrs/$ worked?")
    public int       lastSuperPayrun    = 0;      // pastaff.last_super_payrun — display only
    public int       currentSuperPayrun = 0;      // pastaff.current_super_payrun — display only
    public LocalDate lastSuperDate;                // pastaff.last_super_date — display only

    // ── Identity fields needed for MVR (member-verification) submission ──
    public String    sex                = "";     // pastaff.sex — M/F (ATO STP fund member ident)
    public LocalDate dateOfBirth;                  // pastaff.date_of_birth

    // ── Employment status & payrun history (PAEM01 S1B) ────────────────
    // Most of these are read-only — set by the pay-run processing chain.
    public LocalDate  paidThruToDate;                              // pastaff.paid_thru_to_date — last date paid
    public LocalDate  timesheetsToDate;                            // pastaff.timesheets_to_date — last costed timesheet date
    public int        lastPayrunNo            = 0;                 // pastaff.last_payrun_no
    public int        currentPayrunNo         = 0;                 // pastaff.current_payrun_no — "Currently active in payrun"
    public int        authLevel               = 0;                 // pastaff.auth_level — editable; who can edit this employee
    public String     stdRateCode             = "";                // pastaff.std_rate_code — Cost Ledger billing code
    public String     cdepEligibleInd         = "N";               // pastaff.cdep_elligible_ind
    public String     cdepCurrentFlag         = "N";               // pastaff.cdep_current_flag — "Currently on CDEP?"
    public BigDecimal retainerToDate          = BigDecimal.ZERO;   // pastaff.retainer_to_date
    public BigDecimal commissionToDate        = BigDecimal.ZERO;   // pastaff.commission_to_date
    public BigDecimal retDeductedToDate       = BigDecimal.ZERO;   // pastaff.ret_deducted_to_date

    /** Retainer outstanding = paid - deducted - commission (display only). */
    public BigDecimal retainerOutstanding() {
        return retainerToDate.subtract(retDeductedToDate).subtract(commissionToDate);
    }

    // ── Tax detail — PAEM01 S1A (rebates, zone, family tax) ─────────────
    public BigDecimal actualPaidRate          = BigDecimal.ZERO;   // pastaff.actual_paid_rate
    public int        zoneAllow               = 0;                 // pastaff.zone_allow — zone allowance amount
    public int        dependantRebateAmt      = 0;                 // pastaff.dependant_rebate_amt
    public BigDecimal familyTaxAnnualAmt      = BigDecimal.ZERO;   // pastaff.family_tax_annual_amt
    public int        noOfChildren            = 0;                 // pastaff.no_of_children
    public LocalDate  lastGrpCertDate;                             // pastaff.last_grp_cert_date — last group certificate (legacy)
    public String     paymentSummaryType      = "";                // pastaff.payment_summary_type — single char (S/B/N etc.)
    public String     paymentSummaryAbn       = "";                // pastaff.payment_summary_abn — 11-char ABN
    public String     paymentSummaryBType     = "";                // pastaff.payment_summary_b_type — Business / personal

    // ── Tax variation received from ATO (rate override window) ─────────
    public LocalDate  taxVarStartDate;                             // pastaff.tax_var_start_date
    public LocalDate  taxVarEndDate;                               // pastaff.tax_var_end_date
    public BigDecimal taxVarRatePerc          = BigDecimal.ZERO;   // pastaff.tax_var_rate_perc

    // ── STP Phase 2 — PAEM01 S1F ────────────────────────────────────────
    public String     stpTaxTreatment         = "";                // pastaff.stp_tax_treatment — 6-char code
    public String     stpEmployeeCategory     = "";                // pastaff.stp_employee_category
    public String     stpCategoryOption       = "";                // pastaff.stp_category_option
    public String     stpEmploymentBasis      = "";                // pastaff.stp_employment_basis
    public String     stpIncomeType           = "";                // pastaff.stp_income_type — 3-char
    public String     stpCountryCode          = "";                // pastaff.stp_country_code — 2-char (for IAA / WHM income types)
    public String     stpCessationType        = "";                // pastaff.stp_cessation_type — on termination

    // ── Display helpers ───────────────────────────────────────────────────

    public String fullName() {
        String s = (firstName + " " + surname).trim();
        return s.isEmpty() ? "(unnamed)" : s;
    }

    public String statusLabel() {
        return switch (employeeStatus == null ? "" : employeeStatus) {
            case "A" -> "Active";
            case "I" -> "Inactive";
            case "T" -> "Terminated";
            default  -> employeeStatus;
        };
    }

    public String payFreqLabel() {
        return switch (payFreq == null ? "" : payFreq) {
            case "W" -> "Weekly";
            case "F" -> "Fortnightly";
            case "M" -> "Monthly";
            default  -> payFreq;
        };
    }

    public String employeeTypeLabel() {
        return switch (employeeType == null ? "" : employeeType) {
            case "F" -> "Full-time";
            case "P" -> "Part-time";
            case "C" -> "Casual";
            default  -> employeeType;
        };
    }

    public boolean isActive()     { return "A".equals(employeeStatus); }
    public boolean isTerminated() { return "T".equals(employeeStatus); }

    /** Masked TFN for display — never show the full number on screen. */
    public String maskedTfn() { return maskTfn(taxFileNo); }

    /**
     * Mask any TFN string for display: keeps the last 3 digits, replaces
     * the rest with asterisks ({@code ***-***-NNN}). Static so callers
     * holding a raw, in-flight value (e.g. mid-edit in PAEM01) can mask
     * without constructing an {@link Employee}.
     */
    public static String maskTfn(String raw) {
        if (raw == null) return "";
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) return "";
        if (digits.length() <= 3) return "***-***-" + digits;
        return "***-***-" + digits.substring(digits.length() - 3);
    }

    /** Convert minutes to hours for display, e.g. 4500 → "75.0". */
    public static String minutesAsHours(int minutes) {
        if (minutes == 0) return "";
        return String.format("%.1f", minutes / 60.0);
    }

    /**
     * Landmark uses 1899-12-31 as the COBOL "date-zero" sentinel for
     * NOT NULL date columns with no value. Some installations also
     * carry corrupt dates like 7431-02-14. Anything outside this
     * window is treated as "no date".
     */
    public static boolean isValidDate(LocalDate d) {
        return d != null && d.getYear() > 1900 && d.getYear() < 7000;
    }
}
