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
