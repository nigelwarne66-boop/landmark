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

import com.landmarksoftware.payroll.model.TimesheetHeader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * patimhd service — Timesheet headers, one row per (payrun, employee).
 *
 * <p>Read methods power PATM01 P3. Write methods are stubs for now;
 * the full Add / Edit / Delete flow lands with S3 (per-employee data
 * entry) and S3B (per-line entry) which seed totals from patimes.
 */
@Service
public class TimesheetHeaderService {

    private final JdbcTemplate jdbc;

    public TimesheetHeaderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * P3 listbox feed — all timesheet headers for a payrun, optionally
     * filtered to a specific paygroup. Ordered by surname / first_name to
     * match the COBOL PATIMHD-SURNAME-KEY sort.
     *
     * @param paygroup {@code null} or blank → no paygroup filter.
     */
    public List<TimesheetHeader> findForPayrun(int companyNo, int payrunNo, String paygroup) {
        if (paygroup == null || paygroup.isBlank()) {
            return jdbc.query(
                "SELECT * FROM patimhd WHERE company_no=? AND payrun_no=? " +
                "ORDER BY surname, first_name, employee_no",
                (rs, i) -> map(rs),
                companyNo, payrunNo);
        }
        return jdbc.query(
            "SELECT * FROM patimhd WHERE company_no=? AND payrun_no=? AND alt_paygroup=? " +
            "ORDER BY surname, first_name, employee_no",
            (rs, i) -> map(rs),
            companyNo, payrunNo, paygroup);
    }

    public Optional<TimesheetHeader> findOne(int companyNo, int payrunNo, int employeeNo) {
        try {
            TimesheetHeader h = jdbc.queryForObject(
                "SELECT * FROM patimhd WHERE company_no=? AND payrun_no=? AND employee_no=?",
                (rs, i) -> map(rs),
                companyNo, payrunNo, employeeNo);
            return Optional.ofNullable(h);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Used by P3 status bar — total head-count and gross/net rollups. */
    public Totals rollupForPayrun(int companyNo, int payrunNo, String paygroup) {
        List<TimesheetHeader> all = findForPayrun(companyNo, payrunNo, paygroup);
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net   = BigDecimal.ZERO;
        for (TimesheetHeader h : all) {
            gross = gross.add(h.grossPay());
            net   = net.add(h.netPay());
        }
        return new Totals(all.size(), gross, net);
    }

    public record Totals(int count, BigDecimal gross, BigDecimal net) {}

    /**
     * Insert a fresh patimhd row (zero totals). Caller is responsible for
     * setting the cached surname / first_name from pastaff.
     */
    @Transactional
    public void insert(TimesheetHeader h, String userId) {
        LocalTime now = LocalTime.now();
        jdbc.update(
            "INSERT INTO patimhd (" +
            " company_no, payrun_no, employee_no, surname, first_name," +
            " alt_payrun_no, alt_paygroup, alt_dept, alt_employee_no," +
            " total_normal_pay, total_otime_pay, total_other_pay, total_lsl_pay," +
            " total_al_pay, total_al_load, total_sick_pay, total_other_leave_pay," +
            " total_nontax_allow, total_taxable_allow," +
            " total_term_a, total_term_b, total_term_c, total_before_tax_dedns," +
            " total_after_tax_dedns, total_super, total_tax, total_term_d," +
            " total_term_c_tax, total_fbt_rpt_income, total_hecs_tax, al_load_ytd," +
            " total_normal_min, total_otime_min_actual, total_other_min, total_lsl_min," +
            " total_al_min, total_sick_min, total_other_leave_min, total_otime_min_paid," +
            " hrs_wrkd_for_al, hrs_wrkd_for_sl, hrs_wrkd_for_lsl," +
            " prev_paid_thru_date, pay_thru_start_date, default_timesheet_flag," +
            " costed_timesheet_flag, calc_tax_using_pay_dates, round_pay_up_down_ind," +
            " round_pay_factor, pay_thru_to_date, last_payrun_no, timesheet_status," +
            " total_lsl_term_pay, total_al_term_pay, total_all_term_pay," +
            " timesheet_in_use, timesheet_rate_per_hr, payslip_printed_flag," +
            " total_contrib_dedns, lsl_min_taken_to_date, timesheet_freq, timesheet_splits_run," +
            " total_backpay, total_backpay_tax, total_term_e, total_term_e_tax," +
            " total_term_w, total_term_w_tax, total_term_b_tax, note_no," +
            " audit_user_id, audit_date, audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            h.companyNo, h.payrunNo, h.employeeNo, nz(h.surname), nz(h.firstName),
            h.payrunNo, nz(h.altPaygroup), nz(h.altDept), h.employeeNo,
            nz0(h.totalNormalPay), nz0(h.totalOtimePay), nz0(h.totalOtherPay), nz0(h.totalLslPay),
            nz0(h.totalAlPay), nz0(h.totalAlLoad), nz0(h.totalSickPay), nz0(h.totalOtherLeavePay),
            nz0(h.totalNontaxAllow), nz0(h.totalTaxableAllow),
            nz0(h.totalTermA), nz0(h.totalTermB), nz0(h.totalTermC), nz0(h.totalBeforeTaxDedns),
            nz0(h.totalAfterTaxDedns), nz0(h.totalSuper), nz0(h.totalTax), nz0(h.totalTermD),
            nz0(h.totalTermCTax), nz0(h.totalFbtRptIncome), nz0(h.totalHecsTax), nz0(h.alLoadYtd),
            h.totalNormalMin, h.totalOtimeMinActual, h.totalOtherMin, h.totalLslMin,
            h.totalAlMin, h.totalSickMin, h.totalOtherLeaveMin, h.totalOtimeMinPaid,
            h.hrsWrkdForAl, h.hrsWrkdForSl, h.hrsWrkdForLsl,
            sqlDate(h.prevPaidThruDate), sqlDate(h.payThruStartDate),
            nz1(h.defaultTimesheetFlag, "N"), nz1(h.costedTimesheetFlag, "N"),
            nz1(h.calcTaxUsingPayDates, "N"), nz(h.roundPayUpDownInd),
            nz0(h.roundPayFactor),
            sqlDate(h.payThruToDate), h.lastPayrunNo, nz(h.timesheetStatus),
            nz0(h.totalLslTermPay), nz0(h.totalAlTermPay), nz0(h.totalAllTermPay),
            nz1(h.timesheetInUse, "N"), nz0(h.timesheetRatePerHr),
            nz1(h.payslipPrintedFlag, "N"),
            nz0(h.totalContribDedns), h.lslMinTakenToDate,
            nz0(h.timesheetFreq), nz1(h.timesheetSplitsRun, "N"),
            nz0(h.totalBackpay), nz0(h.totalBackpayTax),
            nz0(h.totalTermE), nz0(h.totalTermETax),
            nz0(h.totalTermW), nz0(h.totalTermWTax), nz0(h.totalTermBTax),
            h.noteNo,
            nz(userId), java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0);
    }

    @Transactional
    public void delete(int companyNo, int payrunNo, int employeeNo) {
        // patimes lines for this header are cascade-deleted in PATM01's COBOL
        // path; mirror by deleting children first.
        jdbc.update(
            "DELETE FROM patimes WHERE company_no=? AND payrun_no=? AND employee_no=?",
            companyNo, payrunNo, employeeNo);
        jdbc.update(
            "DELETE FROM patimhd WHERE company_no=? AND payrun_no=? AND employee_no=?",
            companyNo, payrunNo, employeeNo);
    }

    // ── Row mapper ────────────────────────────────────────────────────────

    private static TimesheetHeader map(ResultSet rs) throws SQLException {
        TimesheetHeader h = new TimesheetHeader();
        h.companyNo            = rs.getInt("company_no");
        h.payrunNo             = rs.getInt("payrun_no");
        h.employeeNo           = rs.getInt("employee_no");
        h.surname              = nz(rs.getString("surname"));
        h.firstName            = nz(rs.getString("first_name"));
        h.altPayrunNo          = rs.getInt("alt_payrun_no");
        h.altPaygroup          = nz(rs.getString("alt_paygroup"));
        h.altDept              = nz(rs.getString("alt_dept"));
        h.altEmployeeNo        = rs.getInt("alt_employee_no");
        h.totalNormalPay       = nzBd(rs.getBigDecimal("total_normal_pay"));
        h.totalOtimePay        = nzBd(rs.getBigDecimal("total_otime_pay"));
        h.totalOtherPay        = nzBd(rs.getBigDecimal("total_other_pay"));
        h.totalLslPay          = nzBd(rs.getBigDecimal("total_lsl_pay"));
        h.totalAlPay           = nzBd(rs.getBigDecimal("total_al_pay"));
        h.totalAlLoad          = nzBd(rs.getBigDecimal("total_al_load"));
        h.totalSickPay         = nzBd(rs.getBigDecimal("total_sick_pay"));
        h.totalOtherLeavePay   = nzBd(rs.getBigDecimal("total_other_leave_pay"));
        h.totalNontaxAllow     = nzBd(rs.getBigDecimal("total_nontax_allow"));
        h.totalTaxableAllow    = nzBd(rs.getBigDecimal("total_taxable_allow"));
        h.totalTermA           = nzBd(rs.getBigDecimal("total_term_a"));
        h.totalTermB           = nzBd(rs.getBigDecimal("total_term_b"));
        h.totalTermC           = nzBd(rs.getBigDecimal("total_term_c"));
        h.totalBeforeTaxDedns  = nzBd(rs.getBigDecimal("total_before_tax_dedns"));
        h.totalAfterTaxDedns   = nzBd(rs.getBigDecimal("total_after_tax_dedns"));
        h.totalSuper           = nzBd(rs.getBigDecimal("total_super"));
        h.totalTax             = nzBd(rs.getBigDecimal("total_tax"));
        h.totalTermD           = nzBd(rs.getBigDecimal("total_term_d"));
        h.totalTermCTax        = nzBd(rs.getBigDecimal("total_term_c_tax"));
        h.totalFbtRptIncome    = nzBd(rs.getBigDecimal("total_fbt_rpt_income"));
        h.totalHecsTax         = nzBd(rs.getBigDecimal("total_hecs_tax"));
        h.alLoadYtd            = nzBd(rs.getBigDecimal("al_load_ytd"));
        h.totalNormalMin       = rs.getInt("total_normal_min");
        h.totalOtimeMinActual  = rs.getInt("total_otime_min_actual");
        h.totalOtherMin        = rs.getInt("total_other_min");
        h.totalLslMin          = rs.getInt("total_lsl_min");
        h.totalAlMin           = rs.getInt("total_al_min");
        h.totalSickMin         = rs.getInt("total_sick_min");
        h.totalOtherLeaveMin   = rs.getInt("total_other_leave_min");
        h.totalOtimeMinPaid    = rs.getInt("total_otime_min_paid");
        h.hrsWrkdForAl         = rs.getInt("hrs_wrkd_for_al");
        h.hrsWrkdForSl         = rs.getInt("hrs_wrkd_for_sl");
        h.hrsWrkdForLsl        = rs.getInt("hrs_wrkd_for_lsl");
        h.prevPaidThruDate     = ld(rs.getDate("prev_paid_thru_date"));
        h.payThruStartDate     = ld(rs.getDate("pay_thru_start_date"));
        h.payThruToDate        = ld(rs.getDate("pay_thru_to_date"));
        h.defaultTimesheetFlag = nz(rs.getString("default_timesheet_flag"));
        h.costedTimesheetFlag  = nz(rs.getString("costed_timesheet_flag"));
        h.calcTaxUsingPayDates = nz(rs.getString("calc_tax_using_pay_dates"));
        h.roundPayUpDownInd    = nz(rs.getString("round_pay_up_down_ind"));
        h.roundPayFactor       = nzBd(rs.getBigDecimal("round_pay_factor"));
        h.lastPayrunNo         = rs.getInt("last_payrun_no");
        h.timesheetStatus      = nz(rs.getString("timesheet_status"));
        h.timesheetInUse       = nz(rs.getString("timesheet_in_use"));
        h.timesheetRatePerHr   = nzBd(rs.getBigDecimal("timesheet_rate_per_hr"));
        h.payslipPrintedFlag   = nz(rs.getString("payslip_printed_flag"));
        h.totalLslTermPay      = nzBd(rs.getBigDecimal("total_lsl_term_pay"));
        h.totalAlTermPay       = nzBd(rs.getBigDecimal("total_al_term_pay"));
        h.totalAllTermPay      = nzBd(rs.getBigDecimal("total_all_term_pay"));
        h.totalContribDedns    = nzBd(rs.getBigDecimal("total_contrib_dedns"));
        h.lslMinTakenToDate    = rs.getInt("lsl_min_taken_to_date");
        h.timesheetFreq        = nzBd(rs.getBigDecimal("timesheet_freq"));
        h.timesheetSplitsRun   = nz(rs.getString("timesheet_splits_run"));
        h.totalBackpay         = nzBd(rs.getBigDecimal("total_backpay"));
        h.totalBackpayTax      = nzBd(rs.getBigDecimal("total_backpay_tax"));
        h.totalTermE           = nzBd(rs.getBigDecimal("total_term_e"));
        h.totalTermETax        = nzBd(rs.getBigDecimal("total_term_e_tax"));
        h.totalTermW           = nzBd(rs.getBigDecimal("total_term_w"));
        h.totalTermWTax        = nzBd(rs.getBigDecimal("total_term_w_tax"));
        h.totalTermBTax        = nzBd(rs.getBigDecimal("total_term_b_tax"));
        h.noteNo               = rs.getLong("note_no");
        h.auditUserId          = nz(rs.getString("audit_user_id"));
        h.auditDate            = ld(rs.getDate("audit_date"));
        h.auditTimeHr          = rs.getInt("audit_time_hr");
        h.auditTimeMin         = rs.getInt("audit_time_min");
        h.auditTimeSec         = rs.getInt("audit_time_sec");
        h.auditTimeHun         = rs.getInt("audit_time_hun");
        return h;
    }

    private static String nz(String s)            { return s == null ? "" : s; }
    private static String nz1(String s, String d) { return s == null || s.isBlank() ? d : s; }
    private static BigDecimal nz0(BigDecimal b)   { return b == null ? BigDecimal.ZERO : b; }
    private static BigDecimal nzBd(BigDecimal b)  { return b == null ? BigDecimal.ZERO : b; }
    private static LocalDate ld(java.sql.Date d)  { return d == null ? null : d.toLocalDate(); }
    private static java.sql.Date sqlDate(LocalDate d) {
        return d == null ? java.sql.Date.valueOf(LocalDate.of(1899, 12, 31))
                         : java.sql.Date.valueOf(d);
    }
}
