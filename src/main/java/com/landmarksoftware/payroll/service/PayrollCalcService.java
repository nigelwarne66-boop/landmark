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

import com.landmarksoftware.payroll.model.Employee;
import com.landmarksoftware.payroll.model.Payrun;
import com.landmarksoftware.payroll.model.TimesheetHeader;
import com.landmarksoftware.payroll.model.TimesheetLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * PAPP01 — Pay Run Processing.
 *
 * <p>Recomputes patimhd running totals from the underlying patimes lines,
 * then drives the tax calculation via {@link PaygTaxCalculator}. Once all
 * employees on a payrun have been calc'd, the parent parunhd
 * {@code calcs_completed_flag} is flipped to {@code "Y"}.
 *
 * <p>Pay-type categorisation matches the pacodes convention used by PACD01
 * (24 codes). The mapping below is the working assumption — refine as the
 * user surfaces gaps. Any pay_type not listed lands in {@code total_other_pay}.
 *
 * <h2>Pay-type → patimhd column</h2>
 * <table>
 *   <tr><th>Pay type</th><th>Bucket</th></tr>
 *   <tr><td>1</td><td>Normal pay (+ minutes)</td></tr>
 *   <tr><td>2</td><td>Overtime (+ minutes)</td></tr>
 *   <tr><td>3</td><td>Annual leave (+ minutes)</td></tr>
 *   <tr><td>4</td><td>AL loading (no minutes)</td></tr>
 *   <tr><td>5</td><td>Sick leave (+ minutes)</td></tr>
 *   <tr><td>6</td><td>LSL (+ minutes)</td></tr>
 *   <tr><td>7</td><td>Other leave (+ minutes)</td></tr>
 *   <tr><td>8</td><td>Other pay (+ minutes)</td></tr>
 *   <tr><td>11</td><td>Taxable allowance</td></tr>
 *   <tr><td>12</td><td>Non-taxable allowance</td></tr>
 *   <tr><td>13–18</td><td>Termination A / B / C / D / E / W</td></tr>
 *   <tr><td>19</td><td>Before-tax deduction</td></tr>
 *   <tr><td>20</td><td>After-tax deduction</td></tr>
 *   <tr><td>21</td><td>Super (employee contribution)</td></tr>
 *   <tr><td>23</td><td>Backpay</td></tr>
 *   <tr><td>others</td><td>Other pay</td></tr>
 * </table>
 *
 * <p>Tax is calculated on the sum of taxable income components minus
 * before-tax deductions, scaled to the employee's pay frequency, plus the
 * employee's {@code extra_tax_amt}.
 */
@Service
public class PayrollCalcService {

    private final JdbcTemplate           jdbc;
    private final PayrunService          payruns;
    private final TimesheetHeaderService timesheetHeaders;
    private final TimesheetLineService   timesheetLines;
    private final EmployeeService        employees;
    private final PaygTaxCalculator      taxCalc;

    public PayrollCalcService(JdbcTemplate jdbc,
                               PayrunService payruns,
                               TimesheetHeaderService timesheetHeaders,
                               TimesheetLineService timesheetLines,
                               EmployeeService employees,
                               PaygTaxCalculator taxCalc) {
        this.jdbc             = jdbc;
        this.payruns          = payruns;
        this.timesheetHeaders = timesheetHeaders;
        this.timesheetLines   = timesheetLines;
        this.employees        = employees;
        this.taxCalc          = taxCalc;
    }

    /** Outcome of a payrun calc — counts of timesheets processed / failed. */
    public record Result(int processed, int failed, String firstError) {}

    /**
     * Recalc every patimhd attached to the payrun. Stops the loop on no
     * error — failures are counted and the first error is captured so the
     * UI can surface it.
     */
    @Transactional
    public Result recalcPayrun(int companyNo, int payrunNo, String userId) {
        Payrun pr = payruns.findOne(companyNo, payrunNo).orElseThrow(
            () -> new IllegalArgumentException("Payrun " + payrunNo + " not found."));
        if (!pr.isOpen()) {
            throw new IllegalStateException("Payrun " + payrunNo + " is "
                + pr.statusDisplay().toLowerCase() + " — calc not allowed.");
        }
        LocalDate asOf = pr.payrunDate != null ? pr.payrunDate : LocalDate.now();

        List<TimesheetHeader> headers = timesheetHeaders.findForPayrun(companyNo, payrunNo, null);
        int processed = 0;
        int failed    = 0;
        String firstError = null;
        for (TimesheetHeader h : headers) {
            try {
                recalcTimesheet(h, asOf, userId);
                processed++;
            } catch (Exception ex) {
                failed++;
                if (firstError == null) {
                    firstError = "Employee " + h.employeeNo + ": " + ex.getMessage();
                }
            }
        }
        if (failed == 0 && processed > 0) {
            jdbc.update(
                "UPDATE parunhd SET calcs_completed_flag='Y' " +
                "WHERE company_no=? AND payrun_no=?",
                companyNo, payrunNo);
        }
        return new Result(processed, failed, firstError);
    }

    /**
     * Recalc one patimhd from its patimes lines. Idempotent — safe to run
     * repeatedly. Tax is derived from a fresh PAYG calculation each time.
     */
    @Transactional
    public void recalcTimesheet(TimesheetHeader h, LocalDate asOf, String userId) {
        Employee emp = employees.findOne(h.companyNo, h.employeeNo).orElse(null);
        List<TimesheetLine> lines = timesheetLines.findByHeader(
            h.companyNo, h.payrunNo, h.employeeNo);

        // ── Zero ──
        Totals t = new Totals();
        // ── Sum ──
        for (TimesheetLine l : lines) {
            BigDecimal amt = l.extAmt == null ? BigDecimal.ZERO : l.extAmt;
            switch (l.payType) {
                case 1  -> { t.normalPay = t.normalPay.add(amt); t.normalMin += l.min; }
                case 2  -> { t.otimePay  = t.otimePay.add(amt);  t.otimeMin  += l.min; }
                case 3  -> { t.alPay     = t.alPay.add(amt);     t.alMin     += l.min; }
                case 4  -> { t.alLoad    = t.alLoad.add(amt); }
                case 5  -> { t.sickPay   = t.sickPay.add(amt);   t.sickMin   += l.min; }
                case 6  -> { t.lslPay    = t.lslPay.add(amt);    t.lslMin    += l.min; }
                case 7  -> { t.otherLeavePay = t.otherLeavePay.add(amt); t.otherLeaveMin += l.min; }
                case 8  -> { t.otherPay  = t.otherPay.add(amt);  t.otherMin  += l.min; }
                case 11 -> t.taxableAllow      = t.taxableAllow.add(amt);
                case 12 -> t.nontaxAllow       = t.nontaxAllow.add(amt);
                case 13 -> t.termA             = t.termA.add(amt);
                case 14 -> t.termB             = t.termB.add(amt);
                case 15 -> t.termC             = t.termC.add(amt);
                case 16 -> t.termD             = t.termD.add(amt);
                case 17 -> t.termE             = t.termE.add(amt);
                case 18 -> t.termW             = t.termW.add(amt);
                case 19 -> t.beforeTaxDedns    = t.beforeTaxDedns.add(amt);
                case 20 -> t.afterTaxDedns     = t.afterTaxDedns.add(amt);
                case 21 -> t.superAmt          = t.superAmt.add(amt);
                case 23 -> t.backpay           = t.backpay.add(amt);
                default -> { t.otherPay = t.otherPay.add(amt); t.otherMin += l.min; }
            }
        }

        // ── Tax — taxable income before tax minus before-tax deductions ──
        BigDecimal taxableIncome = t.normalPay
            .add(t.otimePay).add(t.otherPay).add(t.lslPay).add(t.alPay)
            .add(t.alLoad).add(t.sickPay).add(t.otherLeavePay)
            .add(t.taxableAllow).add(t.termA).add(t.termB).add(t.backpay)
            .subtract(t.beforeTaxDedns);
        if (taxableIncome.signum() < 0) taxableIncome = BigDecimal.ZERO;

        BigDecimal tax = BigDecimal.ZERO;
        if (emp != null && !emp.taxScaleNo.isBlank() && taxableIncome.signum() > 0) {
            try {
                tax = taxCalc.calculate(h.companyNo, emp.taxScaleNo, emp.hasStsl(),
                    taxableIncome, emp.payFreq, asOf);
                if (emp.extraTaxAmt != null) tax = tax.add(emp.extraTaxAmt);
            } catch (Exception ex) {
                // Bracket lookup failed (e.g. NAT_1004 not loaded) — bubble up so
                // the caller can decide whether to abort the run.
                throw new IllegalStateException(
                    "Tax calc failed: " + ex.getMessage(), ex);
            }
        }
        t.tax = tax;

        writeTotals(h.companyNo, h.payrunNo, h.employeeNo, t, userId);
    }

    private void writeTotals(int companyNo, int payrunNo, int employeeNo,
                              Totals t, String userId) {
        LocalTime now = LocalTime.now();
        jdbc.update(
            "UPDATE patimhd SET " +
            "  total_normal_pay=?, total_otime_pay=?, total_other_pay=?, total_lsl_pay=?," +
            "  total_al_pay=?, total_al_load=?, total_sick_pay=?, total_other_leave_pay=?," +
            "  total_nontax_allow=?, total_taxable_allow=?," +
            "  total_term_a=?, total_term_b=?, total_term_c=?, total_term_d=?," +
            "  total_term_e=?, total_term_w=?," +
            "  total_before_tax_dedns=?, total_after_tax_dedns=?, total_super=?, total_tax=?," +
            "  total_backpay=?," +
            "  total_normal_min=?, total_otime_min_actual=?, total_other_min=?," +
            "  total_lsl_min=?, total_al_min=?, total_sick_min=?, total_other_leave_min=?," +
            "  audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?," +
            "  audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND payrun_no=? AND employee_no=?",
            t.normalPay, t.otimePay, t.otherPay, t.lslPay,
            t.alPay, t.alLoad, t.sickPay, t.otherLeavePay,
            t.nontaxAllow, t.taxableAllow,
            t.termA, t.termB, t.termC, t.termD,
            t.termE, t.termW,
            t.beforeTaxDedns, t.afterTaxDedns, t.superAmt, t.tax,
            t.backpay,
            t.normalMin, t.otimeMin, t.otherMin,
            t.lslMin, t.alMin, t.sickMin, t.otherLeaveMin,
            nz(userId), java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0,
            companyNo, payrunNo, employeeNo);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Working buffer — keeps {@link #recalcTimesheet} readable. */
    private static class Totals {
        BigDecimal normalPay = BigDecimal.ZERO, otimePay = BigDecimal.ZERO,
                   otherPay  = BigDecimal.ZERO, lslPay   = BigDecimal.ZERO,
                   alPay     = BigDecimal.ZERO, alLoad   = BigDecimal.ZERO,
                   sickPay   = BigDecimal.ZERO, otherLeavePay = BigDecimal.ZERO,
                   nontaxAllow = BigDecimal.ZERO, taxableAllow = BigDecimal.ZERO,
                   termA = BigDecimal.ZERO, termB = BigDecimal.ZERO,
                   termC = BigDecimal.ZERO, termD = BigDecimal.ZERO,
                   termE = BigDecimal.ZERO, termW = BigDecimal.ZERO,
                   beforeTaxDedns = BigDecimal.ZERO, afterTaxDedns = BigDecimal.ZERO,
                   superAmt = BigDecimal.ZERO, tax = BigDecimal.ZERO,
                   backpay = BigDecimal.ZERO;
        int normalMin, otimeMin, otherMin, lslMin, alMin, sickMin, otherLeaveMin;
    }
}
