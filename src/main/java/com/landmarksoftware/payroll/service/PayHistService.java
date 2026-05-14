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

import com.landmarksoftware.payroll.model.PayHistEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * paehist service — pay run history. Used by PAPP28 (posting), PABK02
 * (ABA file), STP, payment summaries, and the PATL reports.
 */
@Service
public class PayHistService {

    private final JdbcTemplate jdbc;

    public PayHistService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PayHistEntry> findByPayrun(int companyNo, int payrunNo) {
        return jdbc.query(
            "SELECT * FROM paehist WHERE company_no=? AND payrun_no=? " +
            "ORDER BY employee_no, line_no",
            (rs, i) -> map(rs),
            companyNo, payrunNo);
    }

    public List<PayHistEntry> findByEmployeeAndPayrun(int companyNo,
                                                       int employeeNo, int payrunNo) {
        return jdbc.query(
            "SELECT * FROM paehist WHERE company_no=? AND employee_no=? AND payrun_no=? " +
            "ORDER BY line_no",
            (rs, i) -> map(rs),
            companyNo, employeeNo, payrunNo);
    }

    /** Whether a payrun has any posted history rows. Used by PAPP28 to refuse re-posting. */
    public boolean isPosted(int companyNo, int payrunNo) {
        try {
            Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM paehist WHERE company_no=? AND payrun_no=?",
                Integer.class, companyNo, payrunNo);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional
    public void insert(PayHistEntry e, String userId) {
        LocalTime now = LocalTime.now();
        jdbc.update(
            "INSERT INTO paehist (" +
            " company_no, employee_no, payrun_date, pay_type, pay_code, payrun_no, line_no," +
            " paygroup, dept, alt_1_payrun_date, alt_1_employee_no, alt_1_payrun_no, alt_1_line_no," +
            " alt_2_pay_code, alt_2_employee_no, alt_2_payrun_date, alt_2_payrun_no, alt_2_line_no," +
            " hrs, qty, rate_perc, ext_amt, award, job_class, cost_type," +
            " gl_acct_no_main, gl_acct_no_sub, ledger_type, ledger_code," +
            " absorp_type, absorp_amt, ref, income_taxable_flag, payroll_taxable_flag," +
            " term_c_tax_amt, hecs_tax_amt, last_pay_to_date, this_pay_to_date, payrun_type," +
            " fbt_gross_value, paid_flag, bill_desc_1, bill_desc_2, bill_desc_3," +
            " ba_ledger_id, ba_primary_codes, ba_trans_seq_no, ba_ref, ba_cr_reversal_ind," +
            " ba_billable_flag, batrans_unbilled_key, gst_flag, tax_code, tax_amt, gross_amt," +
            " term_a_ind_r_t, term_c_trans_ind, term_c_death_ind, gst_value, gst_code, bas_group," +
            " term_c_paymt_type, cdep_flag, note_no, backpay_tax_amt, backpay_tax_yr," +
            " leave_start_date, leave_end_date, this_pay_start_date," +
            " stp_country_code, stp_date_time_stamp, stp_income_type," +
            " term_c_paymt_date, term_c_tax_free_amt, term_c_taxable_amt, term_w_tax_amt," +
            " audit_user_id, audit_date, audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            e.companyNo, e.employeeNo, sqlDate(e.payrunDate),
            e.payType, nz(e.payCode), e.payrunNo, e.lineNo,
            nz(e.paygroup), nz(e.dept),
            // alt_1 keys (paygroup/dept ordered timeline)
            sqlDate(e.payrunDate), e.employeeNo, e.payrunNo, e.lineNo,
            // alt_2 keys (pay code ordered)
            nz(e.payCode), e.employeeNo, sqlDate(e.payrunDate), e.payrunNo, e.lineNo,
            e.hrs, nz0(e.qty), nz0(e.ratePerc), nz0(e.extAmt),
            nz(e.award), nz(e.jobClass), nz(e.costType),
            e.glAcctNoMain, e.glAcctNoSub,
            nz(e.ledgerType), nz(e.ledgerCode),
            nz(e.absorpType), nz0(e.absorpAmt), nz(e.ref),
            nz1(e.incomeTaxableFlag, "N"), nz1(e.payrollTaxableFlag, "N"),
            nz0(e.termCTaxAmt), nz0(e.hecsTaxAmt),
            sqlDate(e.lastPayToDate), sqlDate(e.thisPayToDate),
            nz1(e.payrunType, "P"), nz0(e.fbtGrossValue),
            nz1(e.paidFlag, "N"),
            // bill_desc_1/2/3 — unused for payroll lines, blank
            "", "", "",
            // ba_* — unused, blank/zero
            "", "", 0, "", "", "N", "",
            nz1(e.gstFlag, "N"), nz(e.taxCode), nz0(e.taxAmt), nz0(e.grossAmt),
            nz(e.termAIndRT), nz(e.termCTransInd), nz(e.termCDeathInd),
            nz0(e.gstValue), nz(e.gstCode), nz(e.basGroup),
            nz(e.termCPaymtType), nz1(e.cdepFlag, "N"), e.noteNo,
            nz0(e.backpayTaxAmt), e.backpayTaxYr,
            sqlDate(e.leaveStartDate), sqlDate(e.leaveEndDate),
            sqlDate(e.thisPayStartDate),
            nz(e.stpCountryCode), nz(e.stpDateTimeStamp), nz(e.stpIncomeType),
            sqlDate(e.termCPaymtDate),
            nz0(e.termCTaxFreeAmt), nz0(e.termCTaxableAmt), nz0(e.termWTaxAmt),
            nz(userId), java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0);
    }

    /** Wipe every paehist row for a payrun. Used to support PAPP28 re-post. */
    @Transactional
    public int deleteByPayrun(int companyNo, int payrunNo) {
        return jdbc.update(
            "DELETE FROM paehist WHERE company_no=? AND payrun_no=?",
            companyNo, payrunNo);
    }

    private static PayHistEntry map(ResultSet rs) throws SQLException {
        PayHistEntry e = new PayHistEntry();
        e.companyNo           = rs.getInt("company_no");
        e.employeeNo          = rs.getInt("employee_no");
        e.payrunDate          = ld(rs.getDate("payrun_date"));
        e.payType             = rs.getInt("pay_type");
        e.payCode             = nz(rs.getString("pay_code"));
        e.payrunNo            = rs.getInt("payrun_no");
        e.lineNo              = rs.getInt("line_no");
        e.paygroup            = nz(rs.getString("paygroup"));
        e.dept                = nz(rs.getString("dept"));
        e.hrs                 = rs.getInt("hrs");
        e.qty                 = nzBd(rs.getBigDecimal("qty"));
        e.ratePerc            = nzBd(rs.getBigDecimal("rate_perc"));
        e.extAmt              = nzBd(rs.getBigDecimal("ext_amt"));
        e.award               = nz(rs.getString("award"));
        e.jobClass            = nz(rs.getString("job_class"));
        e.costType            = nz(rs.getString("cost_type"));
        e.glAcctNoMain        = rs.getInt("gl_acct_no_main");
        e.glAcctNoSub         = rs.getInt("gl_acct_no_sub");
        e.ledgerType          = nz(rs.getString("ledger_type"));
        e.ledgerCode          = nz(rs.getString("ledger_code"));
        e.absorpType          = nz(rs.getString("absorp_type"));
        e.absorpAmt           = nzBd(rs.getBigDecimal("absorp_amt"));
        e.ref                 = nz(rs.getString("ref"));
        e.incomeTaxableFlag   = nz(rs.getString("income_taxable_flag"));
        e.payrollTaxableFlag  = nz(rs.getString("payroll_taxable_flag"));
        e.termCTaxAmt         = nzBd(rs.getBigDecimal("term_c_tax_amt"));
        e.hecsTaxAmt          = nzBd(rs.getBigDecimal("hecs_tax_amt"));
        e.lastPayToDate       = ld(rs.getDate("last_pay_to_date"));
        e.thisPayToDate       = ld(rs.getDate("this_pay_to_date"));
        e.payrunType          = nz(rs.getString("payrun_type"));
        e.fbtGrossValue       = nzBd(rs.getBigDecimal("fbt_gross_value"));
        e.paidFlag            = nz(rs.getString("paid_flag"));
        e.gstFlag             = nz(rs.getString("gst_flag"));
        e.taxCode             = nz(rs.getString("tax_code"));
        e.taxAmt              = nzBd(rs.getBigDecimal("tax_amt"));
        e.grossAmt            = nzBd(rs.getBigDecimal("gross_amt"));
        e.termAIndRT          = nz(rs.getString("term_a_ind_r_t"));
        e.termCTransInd       = nz(rs.getString("term_c_trans_ind"));
        e.termCDeathInd       = nz(rs.getString("term_c_death_ind"));
        e.gstValue            = nzBd(rs.getBigDecimal("gst_value"));
        e.gstCode             = nz(rs.getString("gst_code"));
        e.basGroup            = nz(rs.getString("bas_group"));
        e.termCPaymtType      = nz(rs.getString("term_c_paymt_type"));
        e.cdepFlag            = nz(rs.getString("cdep_flag"));
        e.backpayTaxAmt       = nzBd(rs.getBigDecimal("backpay_tax_amt"));
        e.backpayTaxYr        = rs.getInt("backpay_tax_yr");
        e.leaveStartDate      = ld(rs.getDate("leave_start_date"));
        e.leaveEndDate        = ld(rs.getDate("leave_end_date"));
        e.thisPayStartDate    = ld(rs.getDate("this_pay_start_date"));
        e.stpCountryCode      = nz(rs.getString("stp_country_code"));
        e.stpDateTimeStamp    = nz(rs.getString("stp_date_time_stamp"));
        e.stpIncomeType       = nz(rs.getString("stp_income_type"));
        e.termCPaymtDate      = ld(rs.getDate("term_c_paymt_date"));
        e.termCTaxFreeAmt     = nzBd(rs.getBigDecimal("term_c_tax_free_amt"));
        e.termCTaxableAmt     = nzBd(rs.getBigDecimal("term_c_taxable_amt"));
        e.termWTaxAmt         = nzBd(rs.getBigDecimal("term_w_tax_amt"));
        e.noteNo              = rs.getLong("note_no");
        e.auditUserId         = nz(rs.getString("audit_user_id"));
        e.auditDate           = ld(rs.getDate("audit_date"));
        e.auditTimeHr         = rs.getInt("audit_time_hr");
        e.auditTimeMin        = rs.getInt("audit_time_min");
        e.auditTimeSec        = rs.getInt("audit_time_sec");
        e.auditTimeHun        = rs.getInt("audit_time_hun");
        return e;
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
