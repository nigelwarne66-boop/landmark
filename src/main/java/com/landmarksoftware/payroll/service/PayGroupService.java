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

import com.landmarksoftware.payroll.model.PayGroup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * pagroup CRUD service for PAPG01 — Pay Group Maintenance.
 *
 * <p>Audit columns are NOT NULL in the schema; every insert/update populates
 * them from the caller's user id and the server clock.
 *
 * <p>Delete blocks if {@code pastaff} carries the paygroup (employees would
 * be orphaned). The COBOL program does the same check.
 */
@Service
public class PayGroupService {

    private final JdbcTemplate jdbc;

    public PayGroupService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<PayGroup> ROW = (rs, i) -> {
        PayGroup g = new PayGroup();
        g.companyNo            = rs.getInt   ("company_no");
        g.paygroup             = nz(rs.getString("paygroup"));
        g.desc1                = nz(rs.getString("desc1"));
        g.paygroupType         = nz(rs.getString("paygroup_type"));
        g.roundPayUpDownInd    = nz(rs.getString("round_pay_up_down_ind"));
        g.roundPayFactor       = nzDec(rs.getBigDecimal("round_pay_factor"));

        g.allowPayrollTaxFlag  = nz(rs.getString("allow_payroll_tax_flag"));
        g.payrollTaxPerc       = nzDec(rs.getBigDecimal("payroll_tax_perc"));

        g.netPayAcctMain       = rs.getInt("net_pay_acct_main");
        g.netPayAcctSub        = rs.getInt("net_pay_acct_sub");
        g.incomeTaxAcctMain    = rs.getInt("income_tax_acct_main");
        g.incomeTaxAcctSub     = rs.getInt("income_tax_acct_sub");
        g.wcompClearMain       = rs.getInt("wcomp_clear_main");
        g.wcompClearSub        = rs.getInt("wcomp_clear_sub");
        g.payTaxClearMain      = rs.getInt("pay_tax_clear_main");
        g.payTaxClearSub       = rs.getInt("pay_tax_clear_sub");
        g.oncostsClearMain     = rs.getInt("oncosts_clear_main");
        g.oncostsClearSub      = rs.getInt("oncosts_clear_sub");
        g.gstClearMain         = rs.getInt("gst_clear_main");
        g.gstClearSub          = rs.getInt("gst_clear_sub");

        g.lastPayrunNoMth      = rs.getInt("last_payrun_no_mth");
        g.lastPayrunNo4Wk      = rs.getInt("last_payrun_no_4_wk");
        g.lastPayrunNoBimth    = rs.getInt("last_payrun_no_bimth");
        g.lastPayrunNoFort     = rs.getInt("last_payrun_no_fort");
        g.lastPayrunNoWeek     = rs.getInt("last_payrun_no_week");
        g.payrunDateMth        = toLd(rs.getDate("payrun_date_mth"));
        g.payrunDate4Wk        = toLd(rs.getDate("payrun_date_4_wk"));
        g.payrunDateBimth      = toLd(rs.getDate("payrun_date_bimth"));
        g.payrunDateFort       = toLd(rs.getDate("payrun_date_fort"));
        g.payrunDateWeek       = toLd(rs.getDate("payrun_date_week"));
        g.paidThruToMth        = rs.getInt("paid_thru_to_mth");
        g.paidThruTo4Wk        = rs.getInt("paid_thru_to_4_wk");
        g.paidThruToBimth      = rs.getInt("paid_thru_to_bimth");
        g.paidThruToFort       = rs.getInt("paid_thru_to_fort");
        g.paidThruToWeek       = rs.getInt("paid_thru_to_week");
        g.payrunActiveMth      = nz(rs.getString("payrun_active_mth"));
        g.payrunActive4Wk      = nz(rs.getString("payrun_active_4_wk"));
        g.payrunActiveBimth    = nz(rs.getString("payrun_active_bimth"));
        g.payrunActiveFort     = nz(rs.getString("payrun_active_fort"));
        g.payrunActiveWeek     = nz(rs.getString("payrun_active_week"));
        g.payrunNoActiveMth    = rs.getInt("payrun_no_active_mth");
        g.payrunNoActive4Wk    = rs.getInt("payrun_no_active_4_wk");
        g.payrunNoActiveBimth  = rs.getInt("payrun_no_active_bimth");
        g.payrunNoActiveFort   = rs.getInt("payrun_no_active_fort");
        g.payrunNoActiveWeek   = rs.getInt("payrun_no_active_week");

        g.printRdoOnPayslip    = nz(rs.getString("print_rdo_on_payslip"));
        g.slipFormsReqdFlag    = nz(rs.getString("slip_forms_reqd_flag"));
        g.slipFormsUserCode    = nz(rs.getString("slip_forms_user_code"));
        g.slipFormsPrintFlag   = nz(rs.getString("slip_forms_print_flag"));
        g.slipFormsEmailFlag   = nz(rs.getString("slip_forms_email_flag"));
        g.slipPrintCoyName     = nz(rs.getString("slip_print_coy_name"));
        g.slipPrintAbn         = nz(rs.getString("slip_print_abn"));
        g.slipPrintLslFlag     = nz(rs.getString("slip_print_lsl_flag"));
        g.slipPrintAlFlag      = nz(rs.getString("slip_print_al_flag"));
        g.slipPrintSlFlag      = nz(rs.getString("slip_print_sl_flag"));
        g.slipPrintAnnualSal   = nz(rs.getString("slip_print_annual_sal"));
        g.slipAbn              = nz(rs.getString("slip_abn"));
        g.slipPaygroupName     = nz(rs.getString("slip_paygroup_name"));

        g.summFormsReqdFlag    = nz(rs.getString("summ_forms_reqd_flag"));
        g.summFormsUserCode    = nz(rs.getString("summ_forms_user_code"));
        g.summFormsPrintFlag   = nz(rs.getString("summ_forms_print_flag"));
        g.summFormsEmailFlag   = nz(rs.getString("summ_forms_email_flag"));

        g.bankCode             = nz(rs.getString("bank_code"));
        g.ssContactCode        = nz(rs.getString("ss_contact_code"));
        g.stpOzediClientId     = rs.getInt("stp_ozedi_client_id");
        g.useGroupBank         = nz(rs.getString("use_paygroup_bank"));

        g.noteNo               = rs.getLong("note_no");
        return g;
    };

    // ─── Reads ───────────────────────────────────────────────────────────

    public List<PayGroup> findAll(int companyNo) {
        return jdbc.query(
            "SELECT * FROM pagroup WHERE company_no=? ORDER BY paygroup",
            ROW, companyNo);
    }

    public Optional<PayGroup> findOne(int companyNo, String paygroup) {
        List<PayGroup> rows = jdbc.query(
            "SELECT * FROM pagroup WHERE company_no=? AND paygroup=?",
            ROW, companyNo, paygroup);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean exists(int companyNo, String paygroup) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pagroup WHERE company_no=? AND paygroup=?",
            Integer.class, companyNo, paygroup);
        return n != null && n > 0;
    }

    /**
     * Count active employees referencing this paygroup — blocks delete if
     * non-zero. Matches the COBOL CHECK-STAFF-PAYGROUP guard.
     */
    public int countAttachedEmployees(int companyNo, String paygroup) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pastaff WHERE company_no=? AND paygroup=? " +
            "AND employee_status <> 'T'",
            Integer.class, companyNo, paygroup);
        return n == null ? 0 : n;
    }

    // ─── Writes ──────────────────────────────────────────────────────────

    @Transactional
    public void insert(PayGroup g, String userId) {
        Stamp s = stamp(userId);
        jdbc.update(
            "INSERT INTO pagroup (" +
            " company_no, paygroup, desc1, round_pay_up_down_ind, round_pay_factor," +
            " last_payrun_no_mth, last_payrun_no_4_wk, last_payrun_no_bimth," +
            " last_payrun_no_fort, last_payrun_no_week," +
            " payrun_date_mth, payrun_date_4_wk, payrun_date_bimth," +
            " payrun_date_fort, payrun_date_week," +
            " paid_thru_to_mth, paid_thru_to_4_wk, paid_thru_to_bimth," +
            " paid_thru_to_fort, paid_thru_to_week," +
            " payrun_active_mth, payrun_active_4_wk, payrun_active_bimth," +
            " payrun_active_fort, payrun_active_week," +
            " net_pay_acct_main, net_pay_acct_sub," +
            " income_tax_acct_main, income_tax_acct_sub," +
            " wcomp_clear_main, wcomp_clear_sub," +
            " pay_tax_clear_main, pay_tax_clear_sub," +
            " payroll_tax_perc, allow_payroll_tax_flag, paygroup_type, print_rdo_on_payslip," +
            " payrun_no_active_mth, payrun_no_active_4_wk, payrun_no_active_bimth," +
            " payrun_no_active_fort, payrun_no_active_week," +
            " oncosts_clear_main, oncosts_clear_sub," +
            " slip_forms_reqd_flag, slip_forms_user_code, slip_forms_print_flag, slip_forms_email_flag," +
            " slip_print_coy_name, slip_print_abn, slip_print_lsl_flag, slip_print_al_flag, slip_print_sl_flag," +
            " gst_clear_main, gst_clear_sub," +
            " slip_abn, slip_paygroup_name, slip_print_annual_sal," +
            " summ_forms_reqd_flag, summ_forms_user_code, summ_forms_print_flag, summ_forms_email_flag," +
            " note_no, bank_code, ss_contact_code, stp_ozedi_client_id, use_paygroup_bank," +
            " audit_user_id, audit_date, audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun" +
            ") VALUES (" +
            "  ?, ?, ?, ?, ?,  ?, ?, ?, ?, ?,  ?, ?, ?, ?, ?,  ?, ?, ?, ?, ?, " +
            "  ?, ?, ?, ?, ?,  ?, ?, ?, ?, ?, ?, ?, ?,  ?, ?, ?, ?,  ?, ?, ?, ?, ?,  ?, ?, " +
            "  ?, ?, ?, ?,  ?, ?, ?, ?, ?,  ?, ?,  ?, ?, ?,  ?, ?, ?, ?,  ?, ?, ?, ?, ?, " +
            "  ?, ?, ?, ?, ?, ?" +
            ")",
            g.companyNo, g.paygroup, g.desc1, g.roundPayUpDownInd, g.roundPayFactor,
            g.lastPayrunNoMth, g.lastPayrunNo4Wk, g.lastPayrunNoBimth,
            g.lastPayrunNoFort, g.lastPayrunNoWeek,
            sqlDate(g.payrunDateMth), sqlDate(g.payrunDate4Wk), sqlDate(g.payrunDateBimth),
            sqlDate(g.payrunDateFort), sqlDate(g.payrunDateWeek),
            g.paidThruToMth, g.paidThruTo4Wk, g.paidThruToBimth,
            g.paidThruToFort, g.paidThruToWeek,
            g.payrunActiveMth, g.payrunActive4Wk, g.payrunActiveBimth,
            g.payrunActiveFort, g.payrunActiveWeek,
            g.netPayAcctMain, g.netPayAcctSub,
            g.incomeTaxAcctMain, g.incomeTaxAcctSub,
            g.wcompClearMain, g.wcompClearSub,
            g.payTaxClearMain, g.payTaxClearSub,
            g.payrollTaxPerc, g.allowPayrollTaxFlag, g.paygroupType, g.printRdoOnPayslip,
            g.payrunNoActiveMth, g.payrunNoActive4Wk, g.payrunNoActiveBimth,
            g.payrunNoActiveFort, g.payrunNoActiveWeek,
            g.oncostsClearMain, g.oncostsClearSub,
            g.slipFormsReqdFlag, g.slipFormsUserCode, g.slipFormsPrintFlag, g.slipFormsEmailFlag,
            g.slipPrintCoyName, g.slipPrintAbn, g.slipPrintLslFlag, g.slipPrintAlFlag, g.slipPrintSlFlag,
            g.gstClearMain, g.gstClearSub,
            g.slipAbn, g.slipPaygroupName, g.slipPrintAnnualSal,
            g.summFormsReqdFlag, g.summFormsUserCode, g.summFormsPrintFlag, g.summFormsEmailFlag,
            g.noteNo, g.bankCode, g.ssContactCode, g.stpOzediClientId, g.useGroupBank,
            s.user, s.date, s.hr, s.mi, s.sec, s.hun);
    }

    /**
     * Update editable columns only — pay-run state ({@code payrun_active_*},
     * {@code last_payrun_no_*}, etc.) is owned by PAPP01 and never touched here.
     */
    @Transactional
    public void update(PayGroup g, String userId) {
        Stamp s = stamp(userId);
        jdbc.update(
            "UPDATE pagroup SET " +
            " desc1=?, paygroup_type=?, round_pay_up_down_ind=?, round_pay_factor=?, " +
            " allow_payroll_tax_flag=?, payroll_tax_perc=?, " +
            " net_pay_acct_main=?, net_pay_acct_sub=?, " +
            " income_tax_acct_main=?, income_tax_acct_sub=?, " +
            " wcomp_clear_main=?, wcomp_clear_sub=?, " +
            " pay_tax_clear_main=?, pay_tax_clear_sub=?, " +
            " oncosts_clear_main=?, oncosts_clear_sub=?, " +
            " gst_clear_main=?, gst_clear_sub=?, " +
            " print_rdo_on_payslip=?, " +
            " slip_forms_reqd_flag=?, slip_forms_user_code=?, slip_forms_print_flag=?, slip_forms_email_flag=?, " +
            " slip_print_coy_name=?, slip_print_abn=?, slip_print_lsl_flag=?, slip_print_al_flag=?, slip_print_sl_flag=?, " +
            " slip_abn=?, slip_paygroup_name=?, slip_print_annual_sal=?, " +
            " summ_forms_reqd_flag=?, summ_forms_user_code=?, summ_forms_print_flag=?, summ_forms_email_flag=?, " +
            " bank_code=?, ss_contact_code=?, stp_ozedi_client_id=?, use_paygroup_bank=?, " +
            " audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?, audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND paygroup=?",
            g.desc1, g.paygroupType, g.roundPayUpDownInd, g.roundPayFactor,
            g.allowPayrollTaxFlag, g.payrollTaxPerc,
            g.netPayAcctMain, g.netPayAcctSub,
            g.incomeTaxAcctMain, g.incomeTaxAcctSub,
            g.wcompClearMain, g.wcompClearSub,
            g.payTaxClearMain, g.payTaxClearSub,
            g.oncostsClearMain, g.oncostsClearSub,
            g.gstClearMain, g.gstClearSub,
            g.printRdoOnPayslip,
            g.slipFormsReqdFlag, g.slipFormsUserCode, g.slipFormsPrintFlag, g.slipFormsEmailFlag,
            g.slipPrintCoyName, g.slipPrintAbn, g.slipPrintLslFlag, g.slipPrintAlFlag, g.slipPrintSlFlag,
            g.slipAbn, g.slipPaygroupName, g.slipPrintAnnualSal,
            g.summFormsReqdFlag, g.summFormsUserCode, g.summFormsPrintFlag, g.summFormsEmailFlag,
            g.bankCode, g.ssContactCode, g.stpOzediClientId, g.useGroupBank,
            s.user, s.date, s.hr, s.mi, s.sec, s.hun,
            g.companyNo, g.paygroup);
    }

    @Transactional
    public void delete(int companyNo, String paygroup) {
        jdbc.update(
            "DELETE FROM pagroup WHERE company_no=? AND paygroup=?",
            companyNo, paygroup);
    }

    // ─── Internal helpers ────────────────────────────────────────────────

    private record Stamp(String user, java.sql.Date date, int hr, int mi, int sec, int hun) {}

    private static Stamp stamp(String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now   = LocalTime.now();
        String u = userId == null ? "" : (userId.length() > 15 ? userId.substring(0, 15) : userId);
        return new Stamp(u, Date.valueOf(today),
                          now.getHour(), now.getMinute(),
                          now.getSecond(), now.getNano() / 10_000_000);
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static BigDecimal nzDec(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static LocalDate toLd(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }

    /**
     * Convert nullable {@link LocalDate} to {@link java.sql.Date}. For NOT NULL
     * date columns with no real value (new pay groups before first pay run),
     * use the COBOL "date-zero" sentinel of 1899-12-31.
     */
    private static java.sql.Date sqlDate(LocalDate d) {
        if (d == null || d.getYear() < 1900) return Date.valueOf(LocalDate.of(1899, 12, 31));
        return Date.valueOf(d);
    }
}
