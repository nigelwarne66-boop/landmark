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

    /** MAX(employee_no)+1 for the company (suggests next employee_no on Add). Params: companyNo. */
    public static final String FIND_NEXT_EMPLOYEE_NO =
        "SELECT COALESCE(MAX(employee_no),0)+1 FROM pastaff WHERE company_no=?";

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

    // ─── pacodes — full INSERT (122 columns) ────────────────────────────

    /**
     * Insert a new pacodes row. ALL 122 columns supplied — pacodes has no
     * DB-level defaults so a partial INSERT fails on NOT NULL columns.
     * Service.insert() fills the ~16 UI-exposed columns from the user's
     * PayCode and uses sentinel defaults for the remaining ~106:
     *   varchar→""   Y/N flag→"N"   int/decimal→0   date→1899-12-31.
     * Param order matches column order — see PayCodeService.insert().
     */
    public static final String INSERT_PAYCODE =
        "INSERT INTO pacodes (" +
        "company_no, pay_code, type, desc1, payslip_desc, abbrev_desc, " +
        "print_on_payslip_flag, wcomp_flag, super_flag, term_e_flag, " +
        "allow_rate, allow_amt, allow_unit_per_desc, " +
        "allow_lsl_return_flag, allow_payroll_tax_flag, allow_lsl_accrual_flag, " +
        "allow_al_accrual_flag, allow_sl_accrual_flag, allow_rdo_accrual_flag, " +
        "allow_include_for_rdo, allow_ret_comm_ind, allow_include_for_gc, " +
        "cg_costing_split_flag, allow_lsl_cas_accrual, allow_fbt_flag, " +
        "allow_rpt_inc_flag, allow_gst_flag, allow_gst_code, allow_cdep_flag, " +
        "dedn_perc, dedn_amt, dedn_pay_method, dedn_remittance_freq, " +
        "dedn_clear_acct_main, dedn_clear_acct_sub, dedn_reportable_flag, " +
        "dedn_wplace_give_flag, dedn_union_fees_flag, dedn_used_for_super, " +
        "super_employee_perc, super_pay_method, super_remittance_freq, " +
        "super_clear_acct_main, super_clear_acct_sub, super_tfr_file_flag, " +
        "super_payroll_tax_flag, " +
        "fund_name, fund_addr_1, fund_addr_2, fund_addr_3, " +
        "bank_bsb, bank_acct_no, contact_name, contact_phone, " +
        "plan_no, bank_code, acct_name, " +
        "super_reportable_flag, super_before_after_tax, max_super_ytd, " +
        "pay_factor, pay_rate, pay_payable_flag, " +
        "pay_lsl_accrual_flag, pay_al_accrual_flag, pay_sick_accrual_flag, " +
        "pay_rdo_accrual_flag, pay_include_for_rdo, pay_ret_comm_ind, " +
        "pay_lsl_return_flag, pay_usual_paid_flag, pay_lsl_cas_accrual, " +
        "pay_cdep_flag, " +
        "leave_max_taken, leave_lsl_accrual_flag, leave_al_accrual_flag, " +
        "leave_sl_accrual_flag, leave_rdo_accrual_flag, leave_payable_flag, " +
        "leave_include_for_rdo, leave_pay_factor, leave_lsl_return_flag, " +
        "leave_term_pay_flag, leave_max_period, leave_usual_paid_flag, " +
        "leave_lsl_cas_accrual, leave_cdep_flag, " +
        "contrib_paid_flag, contrib_remit_freq, contrib_pay_method, " +
        "contrib_fbt_flag, contrib_rpt_inc_flag, contrib_clear_main, " +
        "contrib_clear_sub, contrib_report_flag, contrib_deduct_taxable, " +
        "contrib_pay_tax_flag, contrib_gst_flag, contrib_gst_code, " +
        "contrib_used_for_super, " +
        "tax_remit_freq, tax_pay_method, eft_reference, " +
        "fund_abn, fund_usi, fund_esa, " +
        "apra_smsf_fund_ind, superstream_enabled, super_guarantee_flag, " +
        "superstream_category, other_data, ku_calc_method, " +
        "su_jl_post_qty_flag, vs_payslip_desc, note_no, " +
        "audit_user_id, audit_date, audit_time_hr, audit_time_min, " +
        "audit_time_sec, audit_time_hun" +
        ") VALUES (" +
        "?,?,?,?,?,?,?,?,?,?," +  //   1- 10
        "?,?,?,?,?,?,?,?,?,?," +  //  11- 20
        "?,?,?,?,?,?,?,?,?,?," +  //  21- 30
        "?,?,?,?,?,?,?,?,?,?," +  //  31- 40
        "?,?,?,?,?,?,?,?,?,?," +  //  41- 50
        "?,?,?,?,?,?,?,?,?,?," +  //  51- 60
        "?,?,?,?,?,?,?,?,?,?," +  //  61- 70
        "?,?,?,?,?,?,?,?,?,?," +  //  71- 80
        "?,?,?,?,?,?,?,?,?,?," +  //  81- 90
        "?,?,?,?,?,?,?,?,?,?," +  //  91-100
        "?,?,?,?,?,?,?,?,?,?," +  // 101-110
        "?,?,?,?,?,?,?,?,?,?," +  // 111-120
        "?" +                      // 121
        ")";

    // ─── pastaff — full INSERT (120 columns) ────────────────────────────

    /**
     * Insert a new pastaff row. ALL 121 columns supplied — same rationale
     * as INSERT_PAYCODE. Service.insert() fills the ~28 UI-exposed columns
     * and uses sentinel defaults for the remaining ~93.
     * Param order matches column order — see EmployeeService.insert().
     */
    public static final String INSERT_EMPLOYEE =
        "INSERT INTO pastaff (" +
        "company_no, surname, first_name, employee_no, paygroup, dept, " +
        "paygroup_employee_no, award, job_class, award_employee_no, " +
        "second_name, addr_1, addr_2, city, state, postcode, auth_level, " +
        "employee_status, employee_type, date_started, date_terminated, " +
        "termination_code, detail_pay_hist_flag, over_award_flag, " +
        "use_award_rates, pay_freq, annual_salary, std_hrs, std_rate_per_hr, " +
        "std_gross, employer_amt, actual_paid_rate, " +
        "tax_scale_no, hecs_debt_flag, dependant_rebate_amt, extra_tax_amt, " +
        "zone_allow, family_tax_annual_amt, medicare_levy_adjust, " +
        "no_of_children, tax_file_no, payment_summary_type, group_cert_no, " +
        "last_grp_cert_date, paid_thru_to_date, last_payrun_no, " +
        "current_payrun_no, timesheets_to_date, " +
        "start_al_sl_accrual, al_loading, al_loading_pay_code, " +
        "accrued_al_loading, all_accrued_this_yr, al_hrs_since_aug_93, " +
        "al_hrs_accrued, al_hrs_curr_yr, " +
        "start_lsl_accrual, lsl_hrs_bef_78, lsl_hrs_aft_78, " +
        "lsl_hrs_since_aug_93, lsl_weeks_accrued, ave_weekly_hrs, " +
        "lsl_weeks_taken, " +
        "accrued_sick_leave, sl_accrued_this_yr, accrued_rdo, " +
        "rdo_accrued_this_yr, accrual_pay_code, paid_hrs_per_day, " +
        "accrual_mins_per_day, minimum_accrual_mins, leave_note_no, " +
        "sex, date_of_birth, title1, std_rate_code, " +
        "ret_comm_staff_flag, retainer_to_date, commission_to_date, " +
        "ret_deducted_to_date, " +
        "last_super_date, super_code, super_member_no, last_super_payrun, " +
        "current_super_payrun, super_comm_date, qualify_days, force_pay_flag, " +
        "doc_dir, slip_forms_reqd_flag, slip_forms_user_code, " +
        "slip_forms_print_flag, slip_forms_email_flag, " +
        "email_address, payment_summary_abn, use_ext_super_flag, kiosk_flag, " +
        "summ_forms_reqd_flag, summ_forms_user_code, summ_forms_print_flag, " +
        "summ_forms_email_flag, disabilities_flag, al_loading_pay_code_ex, " +
        "al_use_actual_rate, sl_use_actual_rate, lsl_use_actual_rate, " +
        "payment_summary_b_type, accrue_al_by_hrs_flag, " +
        "cdep_elligible_ind, cdep_current_flag, " +
        "mobile, phone_area, phone_no, note_no, " +
        "audit_user_id, audit_date, audit_time_hr, audit_time_min, " +
        "audit_time_sec, audit_time_hun" +
        ") VALUES (" +
        "?,?,?,?,?,?,?,?,?,?," +  //   1- 10
        "?,?,?,?,?,?,?,?,?,?," +  //  11- 20
        "?,?,?,?,?,?,?,?,?,?," +  //  21- 30
        "?,?,?,?,?,?,?,?,?,?," +  //  31- 40
        "?,?,?,?,?,?,?,?,?,?," +  //  41- 50
        "?,?,?,?,?,?,?,?,?,?," +  //  51- 60
        "?,?,?,?,?,?,?,?,?,?," +  //  61- 70
        "?,?,?,?,?,?,?,?,?,?," +  //  71- 80
        "?,?,?,?,?,?,?,?,?,?," +  //  81- 90
        "?,?,?,?,?,?,?,?,?,?," +  //  91-100
        "?,?,?,?,?,?,?,?,?,?," +  // 101-110
        "?,?,?,?,?,?,?,?,?,?"  +  // 111-120
        ")";
}
