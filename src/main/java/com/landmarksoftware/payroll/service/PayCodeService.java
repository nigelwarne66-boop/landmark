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

import com.landmarksoftware.payroll.model.PayCode;
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
 * PACD01 — Pay Code maintenance service.
 *
 * All JDBC for pacodes CRUD. The UI controller is JDBC-free.
 *
 * Table: pacodes   PK: (company_no, pay_code)
 *
 * SQL statements live in {@link PayrollSql} — this class holds only the
 * JdbcTemplate calls and the row mapper.
 */
@Service
public class PayCodeService {

    private final JdbcTemplate jdbc;

    public PayCodeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── List ──────────────────────────────────────────────────────────────

    public List<PayCode> findAll(int companyNo) {
        return jdbc.query(
            PayrollSql.FIND_ALL_PAYCODES,
            (rs, i) -> map(String.valueOf(companyNo), rs),
            companyNo);
    }

    public List<PayCode> findByType(int companyNo, int payType) {
        return jdbc.query(
            PayrollSql.FIND_PAYCODES_BY_TYPE,
            (rs, i) -> map(String.valueOf(companyNo), rs),
            companyNo, payType);
    }

    // ── Single record ─────────────────────────────────────────────────────

    public Optional<PayCode> findOne(int companyNo, String payCode) {
        try {
            PayCode pc = jdbc.queryForObject(
                PayrollSql.FIND_PAYCODE_BY_PK,
                (rs, i) -> map(String.valueOf(companyNo), rs),
                companyNo, payCode);
            return Optional.ofNullable(pc);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ── Validation ────────────────────────────────────────────────────────

    public boolean exists(int companyNo, String payCode) {
        Integer n = jdbc.queryForObject(
            PayrollSql.COUNT_PAYCODE_BY_PK,
            Integer.class, companyNo, payCode);
        return n != null && n > 0;
    }

    /** Whether a pay code is referenced in any paehist or paecode rows. */
    public boolean isInUse(int companyNo, String payCode) {
        try {
            Integer n = jdbc.queryForObject(
                PayrollSql.COUNT_PAYCODE_USES_IN_HISTORY,
                Integer.class, companyNo, payCode);
            if (n != null && n > 0) return true;
        } catch (Exception ignored) {}
        try {
            Integer n = jdbc.queryForObject(
                PayrollSql.COUNT_PAYCODE_USES_IN_STANDING,
                Integer.class, companyNo, payCode);
            if (n != null && n > 0) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // ── Write operations ──────────────────────────────────────────────────

    /**
     * Insert a new pacodes row.
     * Binds all 121 columns (see PayrollSql.INSERT_PAYCODE) — UI-exposed
     * fields come from {@code pc}, audit fields from {@code userId} + now,
     * everything else gets a sentinel default ("", "N", 0, BigDecimal.ZERO).
     * Caller must verify no duplicate exists.
     */
    @Transactional
    public void insert(int companyNo, PayCode pc, String userId) {
        java.sql.Date today = java.sql.Date.valueOf(LocalDate.now());
        LocalTime now = LocalTime.now();
        int hr  = now.getHour();
        int min = now.getMinute();
        int sec = now.getSecond();
        BigDecimal Z = BigDecimal.ZERO;

        jdbc.update(PayrollSql.INSERT_PAYCODE,
            //  1- 6 identity / display
            companyNo, trimUp(pc.payCode, 10), pc.payType, trim(pc.desc1, 30),
            trim(pc.payslipDesc, 30), trim(pc.abbrevDesc, 10),
            //  7-10 behaviour flags
            yn(pc.printOnPayslipFlag), yn(pc.wcompFlag),
            yn(pc.superFlag), yn(pc.termEFlag),
            // 11-13 allowance rate/amt + unit description
            nz(pc.allowRate), nz(pc.allowAmt), trim(pc.allowUnitPerDesc, 20),
            // 14-22 allow_* boolean flags
            yn(pc.allowLslReturnFlag), yn(pc.allowPayrollTaxFlag),
            yn(pc.allowLslAccrualFlag), yn(pc.allowAlAccrualFlag),
            yn(pc.allowSlAccrualFlag), yn(pc.allowRdoAccrualFlag),
            yn(pc.allowIncludeForRdo),
            trim(pc.allowRetCommInd, 1).toUpperCase(),
            yn(pc.allowIncludeForGc),
            // 23-29 cg/allow_* misc + gst code + cdep
            "N", yn(pc.allowLslCasAccrual), yn(pc.allowFbtFlag),
            yn(pc.allowRptIncFlag), yn(pc.allowGstFlag),
            trim(pc.allowGstCode, 4).toUpperCase(), yn(pc.allowCdepFlag),
            // 30-31 dedn rate/amt
            nz(pc.dednPerc), nz(pc.dednAmt),
            // 32-40 dedn settings (32 = dedn_sal_sac_flag — new)
            yn(pc.dednSalSacFlag),
            trim(pc.dednPayMethod, 1).toUpperCase(),
            trim(pc.dednRemittanceFreq, 1).toUpperCase(),
            pc.dednClearAcctMain, pc.dednClearAcctSub,
            yn(pc.dednReportableFlag), yn(pc.dednWplaceGiveFlag),
            yn(pc.dednUnionFeesFlag), yn(pc.dednUsedForSuper),
            // 40-46 super_*
            nz(pc.superEmployeePerc),
            trim(pc.superPayMethod, 1).toUpperCase(),
            trim(pc.superRemittanceFreq, 1).toUpperCase(),
            pc.superClearAcctMain, pc.superClearAcctSub,
            yn(pc.superTfrFileFlag), yn(pc.superPayrollTaxFlag),
            // 47-50 fund_name + fund_addr_*
            trim(pc.fundName, 30), trim(pc.fundAddr1, 30),
            trim(pc.fundAddr2, 30), trim(pc.fundAddr3, 30),
            // 51-57 bank/contact/plan/acct
            trim(pc.bankBsb, 7), trim(pc.bankAcctNo, 12),
            trim(pc.contactName, 30), trim(pc.contactPhone, 20),
            trim(pc.planNo, 20), trim(pc.bankCode, 8), trim(pc.acctName, 30),
            // 58-60 super reportable / before_after / max_super_ytd
            yn(pc.superReportableFlag),
            trim(pc.superBeforeAfterTax, 1).toUpperCase(),
            nz(pc.maxSuperYtd),
            // 61-63 pay_factor / pay_rate / pay_payable_flag
            nz(pc.payFactor), nz(pc.payRate), yn(pc.payPayableFlag),
            // 64-73 pay_* flags (matches PACD01S2 screen)
            yn(pc.payLslAccrualFlag), yn(pc.payAlAccrualFlag), yn(pc.paySickAccrualFlag),
            yn(pc.payRdoAccrualFlag), yn(pc.payIncludeForRdo),
            trim(pc.payRetCommInd, 1).toUpperCase(),
            yn(pc.payLslReturnFlag),
            trim(pc.payUsualPaidFlag, 1).toUpperCase(),
            yn(pc.payLslCasAccrual), yn(pc.payCdepFlag),
            // 74-80 leave_max_taken + leave_*
            pc.leaveMaxTaken, yn(pc.leaveLslAccrualFlag), yn(pc.leaveAlAccrualFlag),
            yn(pc.leaveSlAccrualFlag), yn(pc.leaveRdoAccrualFlag),
            yn(pc.leavePayableFlag), yn(pc.leaveIncludeForRdo),
            // 81-87 leave_pay_factor + leave_*
            nz(pc.leavePayFactor), yn(pc.leaveLslReturnFlag),
            yn(pc.leaveTermPayFlag), pc.leaveMaxPeriod,
            trim(pc.leaveUsualPaidFlag, 1).toUpperCase(),
            yn(pc.leaveLslCasAccrual), yn(pc.leaveCdepFlag),
            // 88-100 contrib_*
            yn(pc.contribPaidFlag),
            trim(pc.contribRemitFreq, 1).toUpperCase(),
            trim(pc.contribPayMethod, 1).toUpperCase(),
            yn(pc.contribFbtFlag), yn(pc.contribRptIncFlag),
            pc.contribClearMain, pc.contribClearSub,
            yn(pc.contribReportFlag), yn(pc.contribDeductTaxable),
            yn(pc.contribPayTaxFlag), yn(pc.contribGstFlag),
            trim(pc.contribGstCode, 4).toUpperCase(),
            yn(pc.contribUsedForSuper),
            // 101-103 tax_remit_freq / tax_pay_method / eft_reference
            trim(pc.taxRemitFreq, 1).toUpperCase(),
            trim(pc.taxPayMethod, 1).toUpperCase(),
            trim(pc.eftReference, 30),
            // 104-106 fund_abn / fund_usi / fund_esa
            trim(pc.fundAbn, 14), trim(pc.fundUsi, 30), trim(pc.fundEsa, 30),
            // 107-115 apra/superstream/etc + note_no
            //   107 apra_smsf_fund_ind   (Fund type — S2D/S2E/S2H)
            //   110 superstream_category (S2D/S2E/S2H)
            trim(pc.apraSmsfFundInd, 1).toUpperCase(), "N", "N",
            trim(pc.superstreamCategory, 1),
            "", "", "N", "", 0L,
            // 116-121 audit
            userId, today, hr, min, sec, 0
        );
    }

    /**
     * Update an existing pay code — only the columns the UI exposes.
     * Other 100+ pacodes columns are left untouched.
     */
    @Transactional
    public void update(int companyNo, PayCode pc) {
        jdbc.update(
            PayrollSql.UPDATE_PAYCODE,
            pc.payType, trim(pc.desc1, 30),
            trim(pc.payslipDesc, 30), trim(pc.abbrevDesc, 10),
            yn(pc.printOnPayslipFlag), yn(pc.superFlag),
            yn(pc.wcompFlag), yn(pc.termEFlag),
            nz(pc.payRate), nz(pc.payFactor),
            nz(pc.allowRate), nz(pc.allowAmt),
            nz(pc.dednPerc), nz(pc.dednAmt),
            // PAY-type flags
            yn(pc.payPayableFlag), yn(pc.payRdoAccrualFlag), yn(pc.payLslAccrualFlag),
            yn(pc.payAlAccrualFlag), yn(pc.paySickAccrualFlag), yn(pc.payLslCasAccrual),
            yn(pc.payIncludeForRdo), trim(pc.payRetCommInd, 1).toUpperCase(),
            yn(pc.payLslReturnFlag), trim(pc.payUsualPaidFlag, 1).toUpperCase(),
            yn(pc.payCdepFlag),
            // ALLOW-type
            trim(pc.allowUnitPerDesc, 20), yn(pc.allowLslReturnFlag), yn(pc.allowPayrollTaxFlag),
            yn(pc.allowLslAccrualFlag), yn(pc.allowAlAccrualFlag), yn(pc.allowSlAccrualFlag),
            yn(pc.allowRdoAccrualFlag), yn(pc.allowIncludeForRdo),
            trim(pc.allowRetCommInd, 1).toUpperCase(), yn(pc.allowIncludeForGc),
            yn(pc.allowLslCasAccrual), yn(pc.allowFbtFlag), yn(pc.allowRptIncFlag),
            yn(pc.allowGstFlag), trim(pc.allowGstCode, 4).toUpperCase(), yn(pc.allowCdepFlag),
            // DEDN-type
            yn(pc.dednSalSacFlag),
            trim(pc.dednPayMethod, 1).toUpperCase(), trim(pc.dednRemittanceFreq, 1).toUpperCase(),
            pc.dednClearAcctMain, pc.dednClearAcctSub,
            yn(pc.dednReportableFlag), yn(pc.dednWplaceGiveFlag),
            yn(pc.dednUnionFeesFlag), yn(pc.dednUsedForSuper),
            // LEAVE-type
            pc.leaveMaxTaken, yn(pc.leaveLslAccrualFlag), yn(pc.leaveAlAccrualFlag),
            yn(pc.leaveSlAccrualFlag), yn(pc.leaveRdoAccrualFlag), yn(pc.leavePayableFlag),
            yn(pc.leaveIncludeForRdo), nz(pc.leavePayFactor), yn(pc.leaveLslReturnFlag),
            yn(pc.leaveTermPayFlag), pc.leaveMaxPeriod,
            trim(pc.leaveUsualPaidFlag, 1).toUpperCase(),
            yn(pc.leaveLslCasAccrual), yn(pc.leaveCdepFlag),
            // SUPER-type
            nz(pc.superEmployeePerc), trim(pc.superPayMethod, 1).toUpperCase(),
            trim(pc.superRemittanceFreq, 1).toUpperCase(),
            pc.superClearAcctMain, pc.superClearAcctSub,
            yn(pc.superTfrFileFlag), yn(pc.superPayrollTaxFlag), yn(pc.superReportableFlag),
            trim(pc.superBeforeAfterTax, 1).toUpperCase(), nz(pc.maxSuperYtd),
            trim(pc.planNo, 20),
            // CONTRIB-type
            yn(pc.contribPaidFlag), trim(pc.contribRemitFreq, 1).toUpperCase(),
            trim(pc.contribPayMethod, 1).toUpperCase(),
            yn(pc.contribFbtFlag), yn(pc.contribRptIncFlag),
            pc.contribClearMain, pc.contribClearSub,
            yn(pc.contribReportFlag), yn(pc.contribDeductTaxable),
            yn(pc.contribPayTaxFlag), yn(pc.contribGstFlag),
            trim(pc.contribGstCode, 4).toUpperCase(), yn(pc.contribUsedForSuper),
            // TAX-type
            trim(pc.taxRemitFreq, 1).toUpperCase(), trim(pc.taxPayMethod, 1).toUpperCase(),
            trim(pc.eftReference, 30),
            // Fund / payee
            trim(pc.fundName, 30), trim(pc.fundAddr1, 30), trim(pc.fundAddr2, 30),
            trim(pc.fundAddr3, 30), trim(pc.contactName, 30), trim(pc.contactPhone, 20),
            trim(pc.fundAbn, 14), trim(pc.fundUsi, 30), trim(pc.fundEsa, 30),
            // EFT
            trim(pc.bankCode, 8), trim(pc.acctName, 30),
            trim(pc.bankBsb, 7), trim(pc.bankAcctNo, 12),
            // SuperStream / fund classification
            trim(pc.apraSmsfFundInd, 1).toUpperCase(),
            trim(pc.superstreamCategory, 1),
            companyNo, pc.payCode);
    }

    @Transactional
    public void delete(int companyNo, String payCode) {
        jdbc.update(PayrollSql.DELETE_PAYCODE, companyNo, payCode);
    }

    // ── Row mapper ────────────────────────────────────────────────────────

    private static PayCode map(String companyNo, ResultSet rs) throws SQLException {
        PayCode pc = new PayCode();
        pc.companyNo          = companyNo;
        pc.payCode            = strDefault(rs, "pay_code", "").trim();
        pc.payType            = safeInt(rs, "type");
        pc.desc1              = strDefault(rs, "desc1", "");
        pc.payslipDesc        = strDefault(rs, "payslip_desc", "");
        pc.abbrevDesc         = strDefault(rs, "abbrev_desc", "");
        pc.printOnPayslipFlag = strDefault(rs, "print_on_payslip_flag", "Y");
        pc.superFlag          = strDefault(rs, "super_flag", "N");
        pc.wcompFlag          = strDefault(rs, "wcomp_flag", "N");
        pc.termEFlag          = strDefault(rs, "term_e_flag", "N");
        pc.payRate            = dec(rs, "pay_rate");
        pc.payFactor          = dec(rs, "pay_factor");
        pc.allowRate          = dec(rs, "allow_rate");
        pc.allowAmt           = dec(rs, "allow_amt");
        pc.dednPerc           = dec(rs, "dedn_perc");
        pc.dednAmt            = dec(rs, "dedn_amt");
        // PAY-type flags (PACD01S2)
        pc.payPayableFlag     = strDefault(rs, "pay_payable_flag", "N");
        pc.payRdoAccrualFlag  = strDefault(rs, "pay_rdo_accrual_flag", "N");
        pc.payLslAccrualFlag  = strDefault(rs, "pay_lsl_accrual_flag", "N");
        pc.payAlAccrualFlag   = strDefault(rs, "pay_al_accrual_flag", "N");
        pc.paySickAccrualFlag = strDefault(rs, "pay_sick_accrual_flag", "N");
        pc.payLslCasAccrual   = strDefault(rs, "pay_lsl_cas_accrual", "N");
        pc.payIncludeForRdo   = strDefault(rs, "pay_include_for_rdo", "N");
        pc.payRetCommInd      = strDefault(rs, "pay_ret_comm_ind", "");
        pc.payLslReturnFlag   = strDefault(rs, "pay_lsl_return_flag", "N");
        pc.payUsualPaidFlag   = strDefault(rs, "pay_usual_paid_flag", "");
        pc.payCdepFlag        = strDefault(rs, "pay_cdep_flag", "N");
        // ALLOW-type
        pc.allowUnitPerDesc    = strDefault(rs, "allow_unit_per_desc",   "");
        pc.allowLslReturnFlag  = strDefault(rs, "allow_lsl_return_flag", "N");
        pc.allowPayrollTaxFlag = strDefault(rs, "allow_payroll_tax_flag","N");
        pc.allowLslAccrualFlag = strDefault(rs, "allow_lsl_accrual_flag","N");
        pc.allowAlAccrualFlag  = strDefault(rs, "allow_al_accrual_flag", "N");
        pc.allowSlAccrualFlag  = strDefault(rs, "allow_sl_accrual_flag", "N");
        pc.allowRdoAccrualFlag = strDefault(rs, "allow_rdo_accrual_flag","N");
        pc.allowIncludeForRdo  = strDefault(rs, "allow_include_for_rdo", "N");
        pc.allowRetCommInd     = strDefault(rs, "allow_ret_comm_ind",    "");
        pc.allowIncludeForGc   = strDefault(rs, "allow_include_for_gc",  "N");
        pc.allowLslCasAccrual  = strDefault(rs, "allow_lsl_cas_accrual", "N");
        pc.allowFbtFlag        = strDefault(rs, "allow_fbt_flag",        "N");
        pc.allowRptIncFlag     = strDefault(rs, "allow_rpt_inc_flag",    "N");
        pc.allowGstFlag        = strDefault(rs, "allow_gst_flag",        "N");
        pc.allowGstCode        = strDefault(rs, "allow_gst_code",        "");
        pc.allowCdepFlag       = strDefault(rs, "allow_cdep_flag",       "N");
        // DEDN-type
        pc.dednSalSacFlag      = strDefault(rs, "dedn_sal_sac_flag",      "N");
        pc.dednPayMethod       = strDefault(rs, "dedn_pay_method",        "");
        pc.dednRemittanceFreq  = strDefault(rs, "dedn_remittance_freq",   "");
        pc.dednClearAcctMain   = safeInt   (rs, "dedn_clear_acct_main");
        pc.dednClearAcctSub    = safeInt   (rs, "dedn_clear_acct_sub");
        pc.dednReportableFlag  = strDefault(rs, "dedn_reportable_flag",   "N");
        pc.dednWplaceGiveFlag  = strDefault(rs, "dedn_wplace_give_flag",  "N");
        pc.dednUnionFeesFlag   = strDefault(rs, "dedn_union_fees_flag",   "N");
        pc.dednUsedForSuper    = strDefault(rs, "dedn_used_for_super",    "N");
        // LEAVE-type
        pc.leaveMaxTaken       = safeInt   (rs, "leave_max_taken");
        pc.leaveLslAccrualFlag = strDefault(rs, "leave_lsl_accrual_flag", "N");
        pc.leaveAlAccrualFlag  = strDefault(rs, "leave_al_accrual_flag",  "N");
        pc.leaveSlAccrualFlag  = strDefault(rs, "leave_sl_accrual_flag",  "N");
        pc.leaveRdoAccrualFlag = strDefault(rs, "leave_rdo_accrual_flag", "N");
        pc.leavePayableFlag    = strDefault(rs, "leave_payable_flag",     "Y");
        pc.leaveIncludeForRdo  = strDefault(rs, "leave_include_for_rdo",  "N");
        pc.leavePayFactor      = dec       (rs, "leave_pay_factor");
        pc.leaveLslReturnFlag  = strDefault(rs, "leave_lsl_return_flag",  "N");
        pc.leaveTermPayFlag    = strDefault(rs, "leave_term_pay_flag",    "N");
        pc.leaveMaxPeriod      = safeInt   (rs, "leave_max_period");
        pc.leaveUsualPaidFlag  = strDefault(rs, "leave_usual_paid_flag",  "");
        pc.leaveLslCasAccrual  = strDefault(rs, "leave_lsl_cas_accrual",  "N");
        pc.leaveCdepFlag       = strDefault(rs, "leave_cdep_flag",        "N");
        // SUPER-type
        pc.superEmployeePerc   = dec       (rs, "super_employee_perc");
        pc.superPayMethod      = strDefault(rs, "super_pay_method",       "");
        pc.superRemittanceFreq = strDefault(rs, "super_remittance_freq",  "");
        pc.superClearAcctMain  = safeInt   (rs, "super_clear_acct_main");
        pc.superClearAcctSub   = safeInt   (rs, "super_clear_acct_sub");
        pc.superTfrFileFlag    = strDefault(rs, "super_tfr_file_flag",    "N");
        pc.superPayrollTaxFlag = strDefault(rs, "super_payroll_tax_flag", "N");
        pc.superReportableFlag = strDefault(rs, "super_reportable_flag",  "N");
        pc.superBeforeAfterTax = strDefault(rs, "super_before_after_tax", "");
        pc.maxSuperYtd         = dec       (rs, "max_super_ytd");
        pc.planNo              = strDefault(rs, "plan_no",                "");
        // CONTRIB-type
        pc.contribPaidFlag     = strDefault(rs, "contrib_paid_flag",      "N");
        pc.contribRemitFreq    = strDefault(rs, "contrib_remit_freq",     "");
        pc.contribPayMethod    = strDefault(rs, "contrib_pay_method",     "");
        pc.contribFbtFlag      = strDefault(rs, "contrib_fbt_flag",       "N");
        pc.contribRptIncFlag   = strDefault(rs, "contrib_rpt_inc_flag",   "N");
        pc.contribClearMain    = safeInt   (rs, "contrib_clear_main");
        pc.contribClearSub     = safeInt   (rs, "contrib_clear_sub");
        pc.contribReportFlag   = strDefault(rs, "contrib_report_flag",    "N");
        pc.contribDeductTaxable= strDefault(rs, "contrib_deduct_taxable", "N");
        pc.contribPayTaxFlag   = strDefault(rs, "contrib_pay_tax_flag",   "N");
        pc.contribGstFlag      = strDefault(rs, "contrib_gst_flag",       "N");
        pc.contribGstCode      = strDefault(rs, "contrib_gst_code",       "");
        pc.contribUsedForSuper = strDefault(rs, "contrib_used_for_super", "N");
        // TAX-type
        pc.taxRemitFreq        = strDefault(rs, "tax_remit_freq",         "");
        pc.taxPayMethod        = strDefault(rs, "tax_pay_method",         "");
        pc.eftReference        = strDefault(rs, "eft_reference",          "");
        // Fund / payee
        pc.fundName            = strDefault(rs, "fund_name",      "");
        pc.fundAddr1           = strDefault(rs, "fund_addr_1",    "");
        pc.fundAddr2           = strDefault(rs, "fund_addr_2",    "");
        pc.fundAddr3           = strDefault(rs, "fund_addr_3",    "");
        pc.contactName         = strDefault(rs, "contact_name",   "");
        pc.contactPhone        = strDefault(rs, "contact_phone",  "");
        pc.fundAbn             = strDefault(rs, "fund_abn",       "");
        pc.fundUsi             = strDefault(rs, "fund_usi",       "");
        pc.fundEsa             = strDefault(rs, "fund_esa",       "");
        // EFT
        pc.bankCode            = strDefault(rs, "bank_code",      "");
        pc.acctName            = strDefault(rs, "acct_name",      "");
        pc.bankBsb             = strDefault(rs, "bank_bsb",       "");
        pc.bankAcctNo          = strDefault(rs, "bank_acct_no",   "");
        // SuperStream / fund classification
        pc.apraSmsfFundInd     = strDefault(rs, "apra_smsf_fund_ind",   "");
        pc.superstreamCategory = strDefault(rs, "superstream_category", "");
        return pc;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String strDefault(ResultSet rs, String col, String def) {
        try {
            String v = rs.getString(col);
            return v == null ? def : v.trim();
        } catch (Exception e) { return def; }
    }

    private static int safeInt(ResultSet rs, String col) {
        try { return rs.getInt(col); } catch (Exception e) { return 0; }
    }

    private static BigDecimal dec(ResultSet rs, String col) {
        try {
            BigDecimal v = rs.getBigDecimal(col);
            return v == null ? BigDecimal.ZERO : v;
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String trimUp(String s, int max) {
        return trim(s, max).toUpperCase();
    }

    private static String yn(String s) {
        return "Y".equalsIgnoreCase(s == null ? "" : s.trim()) ? "Y" : "N";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
