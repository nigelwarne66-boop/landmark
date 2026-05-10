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
    public String maskedTfn() {
        if (taxFileNo == null || taxFileNo.isBlank()) return "";
        String digits = taxFileNo.replaceAll("\\D", "");
        if (digits.length() <= 3) return "***";
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
