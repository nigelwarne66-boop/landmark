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
package com.landmarksoftware.payroll.service;

/**
 * Centralised SQL constants for the payroll module.
 *
 * Each public constant is a complete, parameterised SQL statement that
 * a service passes directly to JdbcTemplate. The point is readability,
 * single-source-of-truth column lists, and one place to audit the SQL
 * surface of the module.
 *
 * Naming: VERB_TARGET[_QUALIFIER] — e.g. FIND_ALL_PAYCODES,
 * COUNT_PAYCODE_USES_IN_HISTORY, UPDATE_PAYCODE.
 *
 * Tables currently covered: pacodes (PACD01).
 * Tables planned: pastaff, paehist, paecode, parunhd, paawhed/job/wcp.
 */
public final class PayrollSql {

    private PayrollSql() {}

    // ─── pacodes (Pay Code Master — PACD01) ──────────────────────────────

    /** Subset of pacodes columns the maintenance UI exposes. Internal. */
    private static final String PACODES_COLS =
        "pay_code, type, desc1, payslip_desc, abbrev_desc, " +
        "print_on_payslip_flag, super_flag, wcomp_flag, term_e_flag, " +
        "pay_rate, pay_factor, allow_rate, allow_amt, dedn_perc, dedn_amt";

    /** All pay codes for a company, ordered by type then code. Params: companyNo. */
    public static final String FIND_ALL_PAYCODES =
        "SELECT " + PACODES_COLS + " FROM pacodes WHERE company_no=? " +
        "ORDER BY type, pay_code";

    /** Pay codes for a company filtered by type. Params: companyNo, payType. */
    public static final String FIND_PAYCODES_BY_TYPE =
        "SELECT " + PACODES_COLS + " FROM pacodes " +
        "WHERE company_no=? AND type=? ORDER BY pay_code";

    /** Single pay code by PK. Params: companyNo, payCode. */
    public static final String FIND_PAYCODE_BY_PK =
        "SELECT " + PACODES_COLS + " FROM pacodes " +
        "WHERE company_no=? AND pay_code=?";

    /** Count rows matching the PK — for existence checks. Params: companyNo, payCode. */
    public static final String COUNT_PAYCODE_BY_PK =
        "SELECT COUNT(*) FROM pacodes WHERE company_no=? AND pay_code=?";

    /** Count uses of a pay code in pay history. Params: companyNo, payCode. */
    public static final String COUNT_PAYCODE_USES_IN_HISTORY =
        "SELECT COUNT(*) FROM paehist WHERE company_no=? AND pay_code=?";

    /** Count uses of a pay code in standing pay lines. Params: companyNo, payCode. */
    public static final String COUNT_PAYCODE_USES_IN_STANDING =
        "SELECT COUNT(*) FROM paecode WHERE company_no=? AND pay_code=?";

    /**
     * Update editable columns on a pay code. Touches only the UI-exposed
     * subset; the other ~100 pacodes columns are left untouched.
     * Params, in order:
     *   type, desc1, payslip_desc, abbrev_desc,
     *   print_on_payslip_flag, super_flag, wcomp_flag, term_e_flag,
     *   pay_rate, pay_factor, allow_rate, allow_amt, dedn_perc, dedn_amt,
     *   companyNo, payCode.
     */
    public static final String UPDATE_PAYCODE =
        "UPDATE pacodes SET " +
        "type=?, desc1=?, payslip_desc=?, abbrev_desc=?, " +
        "print_on_payslip_flag=?, super_flag=?, wcomp_flag=?, term_e_flag=?, " +
        "pay_rate=?, pay_factor=?, allow_rate=?, allow_amt=?, " +
        "dedn_perc=?, dedn_amt=? " +
        "WHERE company_no=? AND pay_code=?";

    /** Hard-delete a pay code. Caller must verify it's not in use. Params: companyNo, payCode. */
    public static final String DELETE_PAYCODE =
        "DELETE FROM pacodes WHERE company_no=? AND pay_code=?";

    // ─── pastaff (Employee Master — PAEM01) ──────────────────────────────

    /** Subset of pastaff columns the maintenance UI exposes. Internal. */
    private static final String PASTAFF_COLS =
        "employee_no, surname, first_name, second_name, " +
        "addr_1, addr_2, city, state, postcode, " +
        "phone_area, phone_no, mobile, email_address, " +
        "dept, paygroup, employee_status, employee_type, " +
        "date_started, date_terminated, pay_freq, award, job_class, " +
        "annual_salary, std_hrs, std_rate_per_hr, " +
        "tax_file_no, tax_scale_no, extra_tax_amt, " +
        "al_hrs_accrued, accrued_sick_leave, lsl_weeks_accrued";

    /** All employees for a company, ordered by employee_no. Params: companyNo. */
    public static final String FIND_ALL_EMPLOYEES =
        "SELECT " + PASTAFF_COLS + " FROM pastaff WHERE company_no=? " +
        "ORDER BY employee_no";

    /**
     * Search employees by partial surname or first_name (LIKE) or exact employee_no.
     * Params: companyNo, surnameLike, firstNameLike, employeeNo.
     */
    public static final String SEARCH_EMPLOYEES_BY_NAME_OR_NO =
        "SELECT " + PASTAFF_COLS + " FROM pastaff " +
        "WHERE company_no=? AND (surname LIKE ? OR first_name LIKE ? OR employee_no=?) " +
        "ORDER BY employee_no";

    /** Single employee by PK. Params: companyNo, employeeNo. */
    public static final String FIND_EMPLOYEE_BY_PK =
        "SELECT " + PASTAFF_COLS + " FROM pastaff " +
        "WHERE company_no=? AND employee_no=?";

    /** Count rows matching the PK — for existence checks. Params: companyNo, employeeNo. */
    public static final String COUNT_EMPLOYEE_BY_PK =
        "SELECT COUNT(*) FROM pastaff WHERE company_no=? AND employee_no=?";

    /** Count an employee's paehist rows — blocks hard delete. Params: companyNo, employeeNo. */
    public static final String COUNT_EMPLOYEE_PAY_HISTORY =
        "SELECT COUNT(*) FROM paehist WHERE company_no=? AND employee_no=?";

    /**
     * Read just the leave-balance columns for a single employee (display refresh).
     * Params: companyNo, employeeNo.
     */
    public static final String FIND_EMPLOYEE_LEAVE_BALANCES =
        "SELECT al_hrs_accrued, accrued_sick_leave, lsl_weeks_accrued " +
        "FROM pastaff WHERE company_no=? AND employee_no=?";

    /**
     * Update editable columns on an employee. Touches only the UI-exposed
     * subset; the other ~90 pastaff columns are left untouched.
     * Params, in order:
     *   surname, first_name, second_name,
     *   addr_1, addr_2, city, state, postcode,
     *   phone_area, phone_no, mobile, email_address,
     *   dept, paygroup, employee_status, employee_type,
     *   date_started, date_terminated, pay_freq, award, job_class,
     *   annual_salary, std_hrs, std_rate_per_hr,
     *   tax_file_no, tax_scale_no, extra_tax_amt,
     *   companyNo, employeeNo.
     */
    public static final String UPDATE_EMPLOYEE =
        "UPDATE pastaff SET " +
        "surname=?, first_name=?, second_name=?, " +
        "addr_1=?, addr_2=?, city=?, state=?, postcode=?, " +
        "phone_area=?, phone_no=?, mobile=?, email_address=?, " +
        "dept=?, paygroup=?, employee_status=?, employee_type=?, " +
        "date_started=?, date_terminated=?, pay_freq=?, " +
        "award=?, job_class=?, " +
        "annual_salary=?, std_hrs=?, std_rate_per_hr=?, " +
        "tax_file_no=?, tax_scale_no=?, extra_tax_amt=? " +
        "WHERE company_no=? AND employee_no=?";

    /**
     * Soft-delete an employee — sets employee_status='T' and date_terminated.
     * Params: terminationDate, companyNo, employeeNo.
     */
    public static final String TERMINATE_EMPLOYEE =
        "UPDATE pastaff SET employee_status='T', date_terminated=? " +
        "WHERE company_no=? AND employee_no=?";
}
