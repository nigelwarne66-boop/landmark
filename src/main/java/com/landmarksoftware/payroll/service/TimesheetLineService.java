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

import com.landmarksoftware.payroll.model.Paecode;
import com.landmarksoftware.payroll.model.TimesheetLine;
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
 * patimes service — timesheet lines on a {@link com.landmarksoftware.payroll.model.TimesheetHeader}.
 */
@Service
public class TimesheetLineService {

    private final JdbcTemplate jdbc;

    public TimesheetLineService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TimesheetLine> findByHeader(int companyNo, int payrunNo, int employeeNo) {
        return jdbc.query(
            "SELECT * FROM patimes WHERE company_no=? AND payrun_no=? AND employee_no=? " +
            "ORDER BY line_no",
            (rs, i) -> map(rs),
            companyNo, payrunNo, employeeNo);
    }

    public Optional<TimesheetLine> findOne(int companyNo, int payrunNo, int employeeNo, int lineNo) {
        try {
            TimesheetLine l = jdbc.queryForObject(
                "SELECT * FROM patimes WHERE company_no=? AND payrun_no=? " +
                "AND employee_no=? AND line_no=?",
                (rs, i) -> map(rs),
                companyNo, payrunNo, employeeNo, lineNo);
            return Optional.ofNullable(l);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public int nextLineNo(int companyNo, int payrunNo, int employeeNo) {
        Integer max = jdbc.queryForObject(
            "SELECT COALESCE(MAX(line_no),0) FROM patimes " +
            "WHERE company_no=? AND payrun_no=? AND employee_no=?",
            Integer.class, companyNo, payrunNo, employeeNo);
        return (max == null ? 0 : max) + 1;
    }

    @Transactional
    public void insert(TimesheetLine l, String userId) {
        LocalTime now = LocalTime.now();
        jdbc.update(
            "INSERT INTO patimes (" +
            " company_no, payrun_no, employee_no, line_no, pay_type, pay_code," +
            " min, qty, rate_perc, ext_amt, paygroup, dept, award, job_class," +
            " employee_dept, leave_start_date, leave_return_date, reason_code, cost_type," +
            " timesheet_date, ref, gl_acct_no_main, gl_acct_no_sub, gl_recon_acct_flag," +
            " gl_recon_id, ledger_type, ledger_code, analysis_code, absorp_type," +
            " absorp_amt, absorp_gl_acct_no_main, absorp_gl_acct_no_sub, std_rate_code," +
            " rdo_calcd_flag, rdo_calc_on_pay_code, rdo_calc_on_pay_type," +
            " lsl_weeks_taken, no_of_periods, term_c_tax, fbt_gross_value, orig_payrun_no," +
            " paehist_key, costed_timesheet_value, gst_flag, gst_code, gst_value," +
            " gst_gross_ex_tax, term_a_ind_r_t, term_c_trans_ind, term_c_death_ind," +
            " sub_coy_no, bas_group, term_c_paymt_type, backpay_flag, backpay_yr_no," +
            " backpay_tax_amt, term_w_tax_amt, term_c_paymt_date, term_c_tax_free_amt," +
            " term_c_taxable_amt, note_no, audit_user_id, audit_date, audit_time_hr," +
            " audit_time_min, audit_time_sec, audit_time_hun) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            l.companyNo, l.payrunNo, l.employeeNo, l.lineNo,
            l.payType, nz(l.payCode), l.min,
            nz0(l.qty), nz0(l.ratePerc), nz0(l.extAmt),
            nz(l.paygroup), nz(l.dept), nz(l.award), nz(l.jobClass),
            nz(l.employeeDept), sqlDate(l.leaveStartDate), sqlDate(l.leaveReturnDate),
            nz(l.reasonCode), nz(l.costType), sqlDate(l.timesheetDate),
            nz(l.ref), l.glAcctNoMain, l.glAcctNoSub,
            nz1(l.glReconAcctFlag, "N"), nz(l.glReconId),
            nz(l.ledgerType), nz(l.ledgerCode), nz(l.analysisCode),
            nz(l.absorpType), nz0(l.absorpAmt),
            l.absorpGlAcctNoMain, l.absorpGlAcctNoSub,
            nz(l.stdRateCode),
            nz1(l.rdoCalcdFlag, "N"), nz(l.rdoCalcOnPayCode), l.rdoCalcOnPayType,
            nz0(l.lslWeeksTaken), l.noOfPeriods,
            nz0(l.termCTax), nz0(l.fbtGrossValue), l.origPayrunNo,
            nz(l.paehistKey), nz0(l.costedTimesheetValue),
            nz1(l.gstFlag, "N"), nz(l.gstCode), nz0(l.gstValue), nz0(l.gstGrossExTax),
            nz(l.termAIndRT), nz(l.termCTransInd), nz(l.termCDeathInd),
            l.subCoyNo, nz(l.basGroup), nz(l.termCPaymtType),
            nz1(l.backpayFlag, "N"), l.backpayYrNo, nz0(l.backpayTaxAmt),
            nz0(l.termWTaxAmt), sqlDate(l.termCPaymtDate),
            nz0(l.termCTaxFreeAmt), nz0(l.termCTaxableAmt),
            l.noteNo,
            nz(userId), java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0);
    }

    @Transactional
    public void update(TimesheetLine l, String userId) {
        LocalTime now = LocalTime.now();
        jdbc.update(
            "UPDATE patimes SET" +
            "  pay_type=?, pay_code=?, min=?, qty=?, rate_perc=?, ext_amt=?," +
            "  paygroup=?, dept=?, award=?, job_class=?, cost_type=?," +
            "  ref=?, leave_start_date=?, leave_return_date=?, reason_code=?," +
            "  timesheet_date=?, gl_acct_no_main=?, gl_acct_no_sub=?," +
            "  ledger_type=?, ledger_code=?, analysis_code=?, absorp_type=?," +
            "  absorp_amt=?, audit_user_id=?, audit_date=?, audit_time_hr=?," +
            "  audit_time_min=?, audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND payrun_no=? AND employee_no=? AND line_no=?",
            l.payType, nz(l.payCode), l.min,
            nz0(l.qty), nz0(l.ratePerc), nz0(l.extAmt),
            nz(l.paygroup), nz(l.dept), nz(l.award), nz(l.jobClass), nz(l.costType),
            nz(l.ref), sqlDate(l.leaveStartDate), sqlDate(l.leaveReturnDate),
            nz(l.reasonCode), sqlDate(l.timesheetDate),
            l.glAcctNoMain, l.glAcctNoSub,
            nz(l.ledgerType), nz(l.ledgerCode), nz(l.analysisCode),
            nz(l.absorpType), nz0(l.absorpAmt),
            nz(userId), java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0,
            l.companyNo, l.payrunNo, l.employeeNo, l.lineNo);
    }

    @Transactional
    public void delete(int companyNo, int payrunNo, int employeeNo, int lineNo) {
        jdbc.update(
            "DELETE FROM patimes WHERE company_no=? AND payrun_no=? AND employee_no=? AND line_no=?",
            companyNo, payrunNo, employeeNo, lineNo);
    }

    /**
     * Helper for S3 — copy the standing paecode rows for an employee into
     * patimes as the seed for a new timesheet. Returns the count inserted.
     */
    @Transactional
    public int seedFromPaecode(int companyNo, int payrunNo, int employeeNo,
                                List<Paecode> standing, String userId) {
        int n = 0;
        int next = nextLineNo(companyNo, payrunNo, employeeNo);
        for (Paecode p : standing) {
            TimesheetLine l = new TimesheetLine();
            l.companyNo    = companyNo;
            l.payrunNo     = payrunNo;
            l.employeeNo   = employeeNo;
            l.lineNo       = next++;
            l.payType      = p.payType;
            l.payCode      = p.payCode;
            l.min          = p.min;
            l.qty          = p.qty;
            l.ratePerc     = p.ratePerc;
            l.extAmt       = p.extAmt;
            l.paygroup     = p.paygroup;
            l.dept         = p.dept;
            l.award        = p.award;
            l.jobClass     = p.jobClass;
            l.costType     = p.costType;
            l.ref          = p.ref;
            l.glAcctNoMain = p.glAcctNoMain;
            l.glAcctNoSub  = p.glAcctNoSub;
            l.ledgerType   = p.ledgerType;
            l.ledgerCode   = p.ledgerCode;
            l.analysisCode = p.analysisCode;
            l.absorpType   = p.absorpType;
            l.absorpAmt    = p.absorpAmt;
            insert(l, userId);
            n++;
        }
        return n;
    }

    // ── Row mapper ────────────────────────────────────────────────────────

    private static TimesheetLine map(ResultSet rs) throws SQLException {
        TimesheetLine l = new TimesheetLine();
        l.companyNo            = rs.getInt("company_no");
        l.payrunNo             = rs.getInt("payrun_no");
        l.employeeNo           = rs.getInt("employee_no");
        l.lineNo               = rs.getInt("line_no");
        l.payType              = rs.getInt("pay_type");
        l.payCode              = nz(rs.getString("pay_code"));
        l.min                  = rs.getInt("min");
        l.qty                  = nzBd(rs.getBigDecimal("qty"));
        l.ratePerc             = nzBd(rs.getBigDecimal("rate_perc"));
        l.extAmt               = nzBd(rs.getBigDecimal("ext_amt"));
        l.paygroup             = nz(rs.getString("paygroup"));
        l.dept                 = nz(rs.getString("dept"));
        l.award                = nz(rs.getString("award"));
        l.jobClass             = nz(rs.getString("job_class"));
        l.employeeDept         = nz(rs.getString("employee_dept"));
        l.costType             = nz(rs.getString("cost_type"));
        l.timesheetDate        = ld(rs.getDate("timesheet_date"));
        l.ref                  = nz(rs.getString("ref"));
        l.leaveStartDate       = ld(rs.getDate("leave_start_date"));
        l.leaveReturnDate      = ld(rs.getDate("leave_return_date"));
        l.reasonCode           = nz(rs.getString("reason_code"));
        l.glAcctNoMain         = rs.getInt("gl_acct_no_main");
        l.glAcctNoSub          = rs.getInt("gl_acct_no_sub");
        l.glReconAcctFlag      = nz(rs.getString("gl_recon_acct_flag"));
        l.glReconId            = nz(rs.getString("gl_recon_id"));
        l.ledgerType           = nz(rs.getString("ledger_type"));
        l.ledgerCode           = nz(rs.getString("ledger_code"));
        l.analysisCode         = nz(rs.getString("analysis_code"));
        l.absorpType           = nz(rs.getString("absorp_type"));
        l.absorpAmt            = nzBd(rs.getBigDecimal("absorp_amt"));
        l.absorpGlAcctNoMain   = rs.getInt("absorp_gl_acct_no_main");
        l.absorpGlAcctNoSub    = rs.getInt("absorp_gl_acct_no_sub");
        l.stdRateCode          = nz(rs.getString("std_rate_code"));
        l.rdoCalcdFlag         = nz(rs.getString("rdo_calcd_flag"));
        l.rdoCalcOnPayCode     = nz(rs.getString("rdo_calc_on_pay_code"));
        l.rdoCalcOnPayType     = rs.getInt("rdo_calc_on_pay_type");
        l.lslWeeksTaken        = nzBd(rs.getBigDecimal("lsl_weeks_taken"));
        l.noOfPeriods          = rs.getInt("no_of_periods");
        l.termCTax             = nzBd(rs.getBigDecimal("term_c_tax"));
        l.fbtGrossValue        = nzBd(rs.getBigDecimal("fbt_gross_value"));
        l.origPayrunNo         = rs.getInt("orig_payrun_no");
        l.paehistKey           = nz(rs.getString("paehist_key"));
        l.costedTimesheetValue = nzBd(rs.getBigDecimal("costed_timesheet_value"));
        l.gstFlag              = nz(rs.getString("gst_flag"));
        l.gstCode              = nz(rs.getString("gst_code"));
        l.gstValue             = nzBd(rs.getBigDecimal("gst_value"));
        l.gstGrossExTax        = nzBd(rs.getBigDecimal("gst_gross_ex_tax"));
        l.termAIndRT           = nz(rs.getString("term_a_ind_r_t"));
        l.termCTransInd        = nz(rs.getString("term_c_trans_ind"));
        l.termCDeathInd        = nz(rs.getString("term_c_death_ind"));
        l.subCoyNo             = rs.getInt("sub_coy_no");
        l.basGroup             = nz(rs.getString("bas_group"));
        l.termCPaymtType       = nz(rs.getString("term_c_paymt_type"));
        l.backpayFlag          = nz(rs.getString("backpay_flag"));
        l.backpayYrNo          = rs.getInt("backpay_yr_no");
        l.backpayTaxAmt        = nzBd(rs.getBigDecimal("backpay_tax_amt"));
        l.termWTaxAmt          = nzBd(rs.getBigDecimal("term_w_tax_amt"));
        l.termCPaymtDate       = ld(rs.getDate("term_c_paymt_date"));
        l.termCTaxFreeAmt      = nzBd(rs.getBigDecimal("term_c_tax_free_amt"));
        l.termCTaxableAmt      = nzBd(rs.getBigDecimal("term_c_taxable_amt"));
        l.noteNo               = rs.getLong("note_no");
        l.auditUserId          = nz(rs.getString("audit_user_id"));
        l.auditDate            = ld(rs.getDate("audit_date"));
        l.auditTimeHr          = rs.getInt("audit_time_hr");
        l.auditTimeMin         = rs.getInt("audit_time_min");
        l.auditTimeSec         = rs.getInt("audit_time_sec");
        l.auditTimeHun         = rs.getInt("audit_time_hun");
        return l;
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
