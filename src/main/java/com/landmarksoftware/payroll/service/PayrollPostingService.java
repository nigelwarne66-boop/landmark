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

import com.landmarksoftware.payroll.model.Paleave;
import com.landmarksoftware.payroll.model.PayHistEntry;
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
 * PAPP28 — Payroll Posting.
 *
 * <p>Closes a payrun by moving every patimes line into a paehist row, then
 * flips {@code parunhd.payrun_status} to {@code "P"} (Posted) and each
 * patimhd to status {@code "P"}.
 *
 * <p>The posting writes a synthetic tax-line per employee so the PAYG tax
 * amount calculated by PAPP01 lands in paehist (where PABK02 / STP / the
 * report suite read from). The tax line uses pay_type = 22 (the PACD01
 * "tax" code-type by convention).
 *
 * <p>Refuses to post unless:
 * <ul>
 *   <li>the payrun status is open (O) or calc-completed,</li>
 *   <li>PAPP01 has been run ({@code calcs_completed_flag = "Y"}), and</li>
 *   <li>no paehist rows already exist for the payrun (no double-post).</li>
 * </ul>
 *
 * <p>GL journal generation is left to a later pass — once the user has
 * confirmed the patimes→paehist mapping is correct.
 */
@Service
public class PayrollPostingService {

    /** Pay_type marker for the synthetic tax line written per employee. */
    private static final int TAX_PAY_TYPE = 22;
    private static final String TAX_PAY_CODE = "TAX";

    private final JdbcTemplate           jdbc;
    private final PayrunService          payruns;
    private final TimesheetHeaderService timesheetHeaders;
    private final TimesheetLineService   timesheetLines;
    private final PayHistService         paehist;
    private final PaleaveService         paleave;

    public PayrollPostingService(JdbcTemplate jdbc,
                                  PayrunService payruns,
                                  TimesheetHeaderService timesheetHeaders,
                                  TimesheetLineService timesheetLines,
                                  PayHistService paehist,
                                  PaleaveService paleave) {
        this.jdbc             = jdbc;
        this.payruns          = payruns;
        this.timesheetHeaders = timesheetHeaders;
        this.timesheetLines   = timesheetLines;
        this.paehist          = paehist;
        this.paleave          = paleave;
    }

    public record Result(int employees, int linesPosted, String firstError) {}

    /**
     * Post every patimhd attached to {@code payrunNo}. All-or-nothing in a
     * single transaction — if any insert fails, the whole post rolls back.
     */
    @Transactional
    public Result postPayrun(int companyNo, int payrunNo, String userId) {
        Payrun pr = payruns.findOne(companyNo, payrunNo).orElseThrow(
            () -> new IllegalArgumentException("Payrun " + payrunNo + " not found."));
        if ("F".equalsIgnoreCase(pr.payrunStatus) || "D".equalsIgnoreCase(pr.payrunStatus)) {
            throw new IllegalStateException("Payrun " + payrunNo + " is "
                + pr.statusDisplay().toLowerCase() + " — cannot post.");
        }
        if (!"Y".equalsIgnoreCase(pr.calcsCompletedFlag)) {
            throw new IllegalStateException(
                "Run PAPP01 (Calculate Tax + Totals) before posting.");
        }
        if (paehist.isPosted(companyNo, payrunNo)) {
            throw new IllegalStateException(
                "Payrun " + payrunNo + " is already posted (paehist rows exist).");
        }

        List<TimesheetHeader> headers = timesheetHeaders.findForPayrun(companyNo, payrunNo, null);
        int employees   = 0;
        int linesPosted = 0;
        String firstError = null;

        LocalDate payrunDate = pr.payrunDate;
        for (TimesheetHeader h : headers) {
            try {
                List<TimesheetLine> lines = timesheetLines.findByHeader(
                    companyNo, payrunNo, h.employeeNo);
                int lineNoOut = 1;
                for (TimesheetLine l : lines) {
                    paehist.insert(toPaeHist(pr, h, l, lineNoOut++), userId);
                    linesPosted++;
                    // PAPP28 — for leave-type lines (LSL/AL/AL-load/sick),
                    // record a 'T' (taken) row on paleave and decrement the
                    // corresponding pastaff balance. Mirrors COBOL pay_type
                    // codes 04=LSL, 05=AL, 06=AL-load, 07=sick.
                    if (isLeaveTakenType(l.payType)) {
                        recordLeaveTaken(pr, h, l, userId);
                    }
                }
                // Synthetic tax line — PAPP01's computed total_tax lands in paehist.
                if (h.totalTax != null && h.totalTax.signum() > 0) {
                    paehist.insert(taxLine(pr, h, lineNoOut, h.totalTax), userId);
                    linesPosted++;
                }
                // Mark the timesheet posted.
                jdbc.update(
                    "UPDATE patimhd SET timesheet_status='P'," +
                    "  audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?," +
                    "  audit_time_sec=?, audit_time_hun=? " +
                    "WHERE company_no=? AND payrun_no=? AND employee_no=?",
                    nz(userId), java.sql.Date.valueOf(LocalDate.now()),
                    nowHr(), nowMin(), nowSec(), 0,
                    companyNo, payrunNo, h.employeeNo);
                employees++;
            } catch (Exception ex) {
                firstError = "Employee " + h.employeeNo + ": " + ex.getMessage();
                throw new IllegalStateException(firstError, ex);
            }
        }

        // Flip parunhd status to Posted.
        jdbc.update(
            "UPDATE parunhd SET payrun_status='P'," +
            "  no_of_employees=?, no_of_employees_paid=?," +
            "  audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?," +
            "  audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND payrun_no=?",
            employees, employees,
            nz(userId), java.sql.Date.valueOf(LocalDate.now()),
            nowHr(), nowMin(), nowSec(), 0,
            companyNo, payrunNo);

        return new Result(employees, linesPosted, firstError);
    }

    /**
     * Reverse a post — wipes paehist rows, reverses pastaff balances for
     * any 'T' paleave rows tied to this payrun, then flips parunhd back to O.
     * Mirrors the COBOL PAPP28 reversal block.
     */
    @Transactional
    public int unpostPayrun(int companyNo, int payrunNo, String userId) {
        // Reverse pastaff balance updates for every 'T' paleave row this
        // payrun added, then drop the rows themselves.
        List<Paleave> taken = jdbc.query(
            "SELECT * FROM paleave WHERE company_no=? AND payrun_no=? AND accrued_taken_ind='T'",
            (rs, i) -> {
                Paleave p = new Paleave();
                p.companyNo       = rs.getInt("company_no");
                p.employeeNo      = rs.getInt("employee_no");
                p.payType         = rs.getInt("pay_type");
                p.min             = rs.getInt("min");
                return p;
            },
            companyNo, payrunNo);
        for (Paleave t : taken) {
            adjustPastaffForLeaveTaken(t.companyNo, t.employeeNo, t.payType, t.min, +1);
        }
        paleave.deleteByPayrun(companyNo, payrunNo, "T");

        int removed = paehist.deleteByPayrun(companyNo, payrunNo);
        jdbc.update(
            "UPDATE patimhd SET timesheet_status='O' " +
            "WHERE company_no=? AND payrun_no=?",
            companyNo, payrunNo);
        jdbc.update(
            "UPDATE parunhd SET payrun_status='O'," +
            "  audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?," +
            "  audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND payrun_no=?",
            nz(userId), java.sql.Date.valueOf(LocalDate.now()),
            nowHr(), nowMin(), nowSec(), 0,
            companyNo, payrunNo);
        return removed;
    }

    // ── Leave-taken recording (PAPP28 'T' rows + pastaff decrement) ─────

    /**
     * COBOL pay_type codes that mean "leave taken on this timesheet". When
     * one of these lines is posted, PAPP28 writes a paleave 'T' row and
     * decrements the matching pastaff balance.
     */
    private static boolean isLeaveTakenType(int payType) {
        return payType == 4    // LSL
            || payType == 5    // AL
            || payType == 6    // AL loading
            || payType == 7;   // Sick
    }

    private void recordLeaveTaken(Payrun pr, TimesheetHeader h, TimesheetLine l, String userId) {
        // Look for an existing 'T' row for this leave-start-date and add to it,
        // matching the COBOL REWRITE-PALEAVE pattern; otherwise INSERT.
        LocalDate startDate = l.leaveStartDate != null && l.leaveStartDate.getYear() > 1900
            ? l.leaveStartDate
            : (l.timesheetDate != null ? l.timesheetDate : pr.payrunDate);
        var existing = paleave.findOne(h.companyNo, h.employeeNo, l.payCode,
            l.payType, startDate, "T");
        if (existing.isPresent()) {
            Paleave p = existing.get();
            p.min  += l.min;
            p.amt  = nz(p.amt).add(nz(l.extAmt));
            p.payrunNo   = pr.payrunNo;
            p.payrunDate = pr.payrunDate;
            paleave.update(p, userId);
        } else {
            Paleave p = new Paleave();
            p.companyNo        = h.companyNo;
            p.employeeNo       = h.employeeNo;
            p.payCode          = l.payCode;
            p.payType          = l.payType;
            p.leaveStartDate   = startDate;
            p.accruedTakenInd  = "T";
            p.leaveEndDate     = l.leaveReturnDate != null && l.leaveReturnDate.getYear() > 1900
                ? l.leaveReturnDate : startDate;
            p.min              = l.min;
            p.rate             = l.ratePerc;
            p.amt              = l.extAmt;
            p.ref              = l.ref;
            p.payrunNo         = pr.payrunNo;
            p.payrunDate       = pr.payrunDate;
            paleave.insert(p, userId);
        }
        // Decrement the matching pastaff balance — matches COBOL behaviour.
        adjustPastaffForLeaveTaken(h.companyNo, h.employeeNo, l.payType, l.min, -1);
    }

    /**
     * Add (sign=+1) or subtract (sign=-1) {@code min} from the pastaff
     * balance for {@code payType}. Used by post (sign=-1 to take) and
     * un-post (sign=+1 to restore).
     */
    private void adjustPastaffForLeaveTaken(int companyNo, int employeeNo,
                                              int payType, int min, int sign) {
        if (min == 0) return;
        int delta = min * sign;
        String column = switch (payType) {
            case 4 -> "lsl_hrs_aft_78";       // LSL
            case 5 -> "al_hrs_accrued";        // AL
            case 6 -> "accrued_al_loading";    // AL loading
            case 7 -> "accrued_sick_leave";    // Sick
            default -> null;
        };
        if (column == null) return;
        // SQL identifier interpolation is safe — `column` comes from the
        // switch above, never from user input.
        jdbc.update(
            "UPDATE pastaff SET " + column + " = " + column + " + ? " +
            "WHERE company_no=? AND employee_no=?",
            delta, companyNo, employeeNo);
    }

    private static BigDecimal nz(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }

    // ── Mapping ──────────────────────────────────────────────────────────

    private static PayHistEntry toPaeHist(Payrun pr, TimesheetHeader h,
                                           TimesheetLine l, int lineNoOut) {
        PayHistEntry e = new PayHistEntry();
        e.companyNo            = h.companyNo;
        e.employeeNo           = h.employeeNo;
        e.payrunDate           = pr.payrunDate;
        e.payType              = l.payType;
        e.payCode              = l.payCode;
        e.payrunNo             = h.payrunNo;
        e.lineNo               = lineNoOut;
        e.paygroup             = l.paygroup.isBlank() ? h.altPaygroup : l.paygroup;
        e.dept                 = l.dept.isBlank() ? h.altDept : l.dept;
        e.hrs                  = l.min;
        e.qty                  = l.qty;
        e.ratePerc             = l.ratePerc;
        e.extAmt               = l.extAmt;
        e.award                = l.award;
        e.jobClass             = l.jobClass;
        e.costType             = l.costType;
        e.glAcctNoMain         = l.glAcctNoMain;
        e.glAcctNoSub          = l.glAcctNoSub;
        e.ledgerType           = l.ledgerType;
        e.ledgerCode           = l.ledgerCode;
        e.absorpType           = l.absorpType;
        e.absorpAmt            = l.absorpAmt;
        e.ref                  = l.ref;
        // Taxable categorisation mirrors PayrollCalcService — anything that
        // contributed to taxable income is flagged here too.
        e.incomeTaxableFlag    = isTaxableType(l.payType) ? "Y" : "N";
        e.payrollTaxableFlag   = isPayrollTaxableType(l.payType) ? "Y" : "N";
        e.lastPayToDate        = h.prevPaidThruDate;
        e.thisPayToDate        = h.payThruToDate;
        e.thisPayStartDate     = h.payThruStartDate;
        e.payrunType           = pr.payrunType;
        e.fbtGrossValue        = l.fbtGrossValue;
        e.paidFlag             = "N";
        e.gstFlag              = l.gstFlag;
        e.taxCode              = "";
        e.taxAmt               = BigDecimal.ZERO;
        e.grossAmt             = l.extAmt;
        e.termAIndRT           = l.termAIndRT;
        e.termCTransInd        = l.termCTransInd;
        e.termCDeathInd        = l.termCDeathInd;
        e.gstValue             = l.gstValue;
        e.gstCode              = l.gstCode;
        e.basGroup             = l.basGroup;
        e.termCPaymtType       = l.termCPaymtType;
        e.cdepFlag             = "N";
        e.backpayTaxAmt        = l.backpayTaxAmt;
        e.backpayTaxYr         = l.backpayYrNo;
        e.leaveStartDate       = l.leaveStartDate;
        e.leaveEndDate         = l.leaveReturnDate;
        e.termCPaymtDate       = l.termCPaymtDate;
        e.termCTaxFreeAmt      = l.termCTaxFreeAmt;
        e.termCTaxableAmt      = l.termCTaxableAmt;
        e.termWTaxAmt          = l.termWTaxAmt;
        e.noteNo               = l.noteNo;
        return e;
    }

    private static PayHistEntry taxLine(Payrun pr, TimesheetHeader h,
                                         int lineNoOut, BigDecimal tax) {
        PayHistEntry e = new PayHistEntry();
        e.companyNo          = h.companyNo;
        e.employeeNo         = h.employeeNo;
        e.payrunDate         = pr.payrunDate;
        e.payType            = TAX_PAY_TYPE;
        e.payCode            = TAX_PAY_CODE;
        e.payrunNo           = h.payrunNo;
        e.lineNo             = lineNoOut;
        e.paygroup           = h.altPaygroup;
        e.dept               = h.altDept;
        e.hrs                = 0;
        e.qty                = BigDecimal.ZERO;
        e.ratePerc           = BigDecimal.ZERO;
        e.extAmt             = tax;
        e.payrunType         = pr.payrunType;
        e.incomeTaxableFlag  = "N";
        e.payrollTaxableFlag = "N";
        e.lastPayToDate      = h.prevPaidThruDate;
        e.thisPayToDate      = h.payThruToDate;
        e.thisPayStartDate   = h.payThruStartDate;
        e.taxAmt             = tax;
        e.grossAmt           = BigDecimal.ZERO;
        e.paidFlag           = "N";
        e.ref                = "PAYG withholding";
        e.hecsTaxAmt         = h.totalHecsTax == null ? BigDecimal.ZERO : h.totalHecsTax;
        return e;
    }

    /**
     * Tax-treatment hint for the pay_type. Matches the buckets in
     * PayrollCalcService — these contribute to taxable income.
     */
    private static boolean isTaxableType(int payType) {
        return payType == 1 || payType == 2 || payType == 3 || payType == 4
            || payType == 5 || payType == 6 || payType == 7 || payType == 8
            || payType == 11 || payType == 13 || payType == 14 || payType == 18 || payType == 23;
    }

    /** Payroll-tax (state) liability — most earned components, not allowances. */
    private static boolean isPayrollTaxableType(int payType) {
        return payType == 1 || payType == 2 || payType == 3 || payType == 4
            || payType == 5 || payType == 6 || payType == 7 || payType == 8;
    }

    // ── Time helpers ─────────────────────────────────────────────────────

    private static int nowHr()  { return LocalTime.now().getHour(); }
    private static int nowMin() { return LocalTime.now().getMinute(); }
    private static int nowSec() { return LocalTime.now().getSecond(); }

    private static String nz(String s) { return s == null ? "" : s; }
}
