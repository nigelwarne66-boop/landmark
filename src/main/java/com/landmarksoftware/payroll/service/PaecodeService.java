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
 * paecode service — per-employee standing pay lines.
 *
 * <p>Every write also writes a {@code papcaud} audit row in the same
 * transaction via {@link MasterFileAuditService#auditPaeCode}. This was the
 * deferred Wave 1.5 hook ("paecode CRUD doesn't exist until Wave 3"); it
 * activates here.
 */
@Service
public class PaecodeService {

    private final JdbcTemplate jdbc;
    private final MasterFileAuditService audit;

    public PaecodeService(JdbcTemplate jdbc, MasterFileAuditService audit) {
        this.jdbc  = jdbc;
        this.audit = audit;
    }

    /** All standing lines for one employee, sorted by line_no. */
    public List<Paecode> findByEmployee(int companyNo, int employeeNo) {
        return jdbc.query(
            "SELECT * FROM paecode WHERE company_no=? AND employee_no=? " +
            "ORDER BY line_no",
            (rs, i) -> map(rs),
            companyNo, employeeNo);
    }

    public Optional<Paecode> findOne(int companyNo, int employeeNo, int lineNo) {
        try {
            Paecode pc = jdbc.queryForObject(
                "SELECT * FROM paecode WHERE company_no=? AND employee_no=? AND line_no=?",
                (rs, i) -> map(rs),
                companyNo, employeeNo, lineNo);
            return Optional.ofNullable(pc);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Highest line_no for an employee + 1; starts at 1. */
    public int nextLineNo(int companyNo, int employeeNo) {
        Integer max = jdbc.queryForObject(
            "SELECT COALESCE(MAX(line_no),0) FROM paecode " +
            "WHERE company_no=? AND employee_no=?",
            Integer.class, companyNo, employeeNo);
        return (max == null ? 0 : max) + 1;
    }

    @Transactional
    public void insert(Paecode p, String userId) {
        LocalTime now = LocalTime.now();
        jdbc.update(
            "INSERT INTO paecode (" +
            " company_no, alt_2_pay_code, employee_no, line_no, alt_1_employee_no," +
            " alt_1_pay_code, alt_1_line_no, pay_freq, pays_since_last_paid," +
            " std_pay_code_flag, start_date, end_date, last_paid_date, super_member_no," +
            " pay_type, pay_code, min, qty, rate_perc, ext_amt, paygroup, dept," +
            " award, job_class, cost_type, gl_acct_no_main, gl_acct_no_sub," +
            " ledger_type, ledger_code, analysis_code, absorp_type, absorp_factor," +
            " absorp_amt, ref, leave_start_date, leave_return_date, fbt_gross_value," +
            " ba_ledger_id, ba_primary_codes, ba_desc, ba_bill_text, gst_value," +
            " ba_gl_override_flag, ba_edit_bill_data_flag, note_no," +
            " audit_user_id, audit_date, audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            p.companyNo, nz(p.alt2PayCode), p.employeeNo, p.lineNo,
            p.alt1EmployeeNo, nz(p.alt1PayCode), p.alt1LineNo,
            p.payFreq, p.paysSinceLastPaid,
            nz1(p.stdPayCodeFlag, "Y"),
            sqlDate(p.startDate), sqlDate(p.endDate), sqlDate(p.lastPaidDate),
            nz(p.superMemberNo),
            p.payType, nz(p.payCode), p.min,
            nz0(p.qty), nz0(p.ratePerc), nz0(p.extAmt),
            nz(p.paygroup), nz(p.dept), nz(p.award), nz(p.jobClass), nz(p.costType),
            p.glAcctNoMain, p.glAcctNoSub,
            nz(p.ledgerType), nz(p.ledgerCode), nz(p.analysisCode),
            nz(p.absorpType), nz0(p.absorpFactor), nz0(p.absorpAmt),
            nz(p.ref), sqlDate(p.leaveStartDate), sqlDate(p.leaveReturnDate),
            nz0(p.fbtGrossValue),
            nz(p.baLedgerId), nz(p.baPrimaryCodes), nz(p.baDesc), nz(p.baBillText),
            nz0(p.gstValue),
            nz1(p.baGlOverrideFlag, "N"), nz1(p.baEditBillDataFlag, "N"),
            p.noteNo,
            nz(userId), java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0);

        // papcaud — AFTER values for Add (COBOL convention)
        audit.auditPaeCode(snapshotOf(p), "A", userId);
    }

    @Transactional
    public void update(Paecode p, String userId) {
        // Capture BEFORE values for the audit row (COBOL convention for M).
        Optional<Paecode> before = findOne(p.companyNo, p.employeeNo, p.lineNo);

        LocalTime now = LocalTime.now();
        jdbc.update(
            "UPDATE paecode SET" +
            "  pay_type=?, pay_code=?, min=?, qty=?, rate_perc=?, ext_amt=?," +
            "  paygroup=?, dept=?, award=?, job_class=?, cost_type=?," +
            "  pay_freq=?, std_pay_code_flag=?, start_date=?, end_date=?," +
            "  super_member_no=?, ref=?, leave_start_date=?, leave_return_date=?," +
            "  gl_acct_no_main=?, gl_acct_no_sub=?, ledger_type=?, ledger_code=?," +
            "  analysis_code=?, absorp_type=?, absorp_factor=?, absorp_amt=?," +
            "  audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?," +
            "  audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND employee_no=? AND line_no=?",
            p.payType, nz(p.payCode), p.min,
            nz0(p.qty), nz0(p.ratePerc), nz0(p.extAmt),
            nz(p.paygroup), nz(p.dept), nz(p.award), nz(p.jobClass), nz(p.costType),
            p.payFreq, nz1(p.stdPayCodeFlag, "Y"),
            sqlDate(p.startDate), sqlDate(p.endDate),
            nz(p.superMemberNo), nz(p.ref),
            sqlDate(p.leaveStartDate), sqlDate(p.leaveReturnDate),
            p.glAcctNoMain, p.glAcctNoSub,
            nz(p.ledgerType), nz(p.ledgerCode), nz(p.analysisCode),
            nz(p.absorpType), nz0(p.absorpFactor), nz0(p.absorpAmt),
            nz(userId), java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0,
            p.companyNo, p.employeeNo, p.lineNo);

        audit.auditPaeCode(snapshotOf(before.orElse(p)), "M", userId);
    }

    @Transactional
    public void delete(int companyNo, int employeeNo, int lineNo, String userId) {
        Optional<Paecode> before = findOne(companyNo, employeeNo, lineNo);
        jdbc.update(
            "DELETE FROM paecode WHERE company_no=? AND employee_no=? AND line_no=?",
            companyNo, employeeNo, lineNo);
        if (before.isPresent()) {
            audit.auditPaeCode(snapshotOf(before.get()), "D", userId);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static MasterFileAuditService.PaeCodeSnapshot snapshotOf(Paecode p) {
        return new MasterFileAuditService.PaeCodeSnapshot(
            p.companyNo, p.employeeNo, p.payType, nz(p.payCode),
            p.min, nz0(p.qty), nz0(p.ratePerc), nz0(p.extAmt),
            nz(p.paygroup), nz(p.dept), nz(p.award), nz(p.jobClass));
    }

    private static Paecode map(ResultSet rs) throws SQLException {
        Paecode p = new Paecode();
        p.companyNo          = rs.getInt("company_no");
        p.alt2PayCode        = nz(rs.getString("alt_2_pay_code"));
        p.employeeNo         = rs.getInt("employee_no");
        p.lineNo             = rs.getInt("line_no");
        p.alt1EmployeeNo     = rs.getInt("alt_1_employee_no");
        p.alt1PayCode        = nz(rs.getString("alt_1_pay_code"));
        p.alt1LineNo         = rs.getInt("alt_1_line_no");
        p.payFreq            = rs.getInt("pay_freq");
        p.paysSinceLastPaid  = rs.getInt("pays_since_last_paid");
        p.stdPayCodeFlag     = nz(rs.getString("std_pay_code_flag"));
        p.startDate          = ld(rs.getDate("start_date"));
        p.endDate            = ld(rs.getDate("end_date"));
        p.lastPaidDate       = ld(rs.getDate("last_paid_date"));
        p.superMemberNo      = nz(rs.getString("super_member_no"));
        p.payType            = rs.getInt("pay_type");
        p.payCode            = nz(rs.getString("pay_code"));
        p.min                = rs.getInt("min");
        p.qty                = nzBd(rs.getBigDecimal("qty"));
        p.ratePerc           = nzBd(rs.getBigDecimal("rate_perc"));
        p.extAmt             = nzBd(rs.getBigDecimal("ext_amt"));
        p.paygroup           = nz(rs.getString("paygroup"));
        p.dept               = nz(rs.getString("dept"));
        p.award              = nz(rs.getString("award"));
        p.jobClass           = nz(rs.getString("job_class"));
        p.costType           = nz(rs.getString("cost_type"));
        p.glAcctNoMain       = rs.getInt("gl_acct_no_main");
        p.glAcctNoSub        = rs.getInt("gl_acct_no_sub");
        p.ledgerType         = nz(rs.getString("ledger_type"));
        p.ledgerCode         = nz(rs.getString("ledger_code"));
        p.analysisCode       = nz(rs.getString("analysis_code"));
        p.absorpType         = nz(rs.getString("absorp_type"));
        p.absorpFactor       = nzBd(rs.getBigDecimal("absorp_factor"));
        p.absorpAmt          = nzBd(rs.getBigDecimal("absorp_amt"));
        p.ref                = nz(rs.getString("ref"));
        p.leaveStartDate     = ld(rs.getDate("leave_start_date"));
        p.leaveReturnDate    = ld(rs.getDate("leave_return_date"));
        p.fbtGrossValue      = nzBd(rs.getBigDecimal("fbt_gross_value"));
        p.baLedgerId         = nz(rs.getString("ba_ledger_id"));
        p.baPrimaryCodes     = nz(rs.getString("ba_primary_codes"));
        p.baDesc             = nz(rs.getString("ba_desc"));
        p.baBillText         = nz(rs.getString("ba_bill_text"));
        p.gstValue           = nzBd(rs.getBigDecimal("gst_value"));
        p.baGlOverrideFlag   = nz(rs.getString("ba_gl_override_flag"));
        p.baEditBillDataFlag = nz(rs.getString("ba_edit_bill_data_flag"));
        p.noteNo             = rs.getLong("note_no");
        p.auditUserId        = nz(rs.getString("audit_user_id"));
        p.auditDate          = ld(rs.getDate("audit_date"));
        p.auditTimeHr        = rs.getInt("audit_time_hr");
        p.auditTimeMin       = rs.getInt("audit_time_min");
        p.auditTimeSec       = rs.getInt("audit_time_sec");
        p.auditTimeHun       = rs.getInt("audit_time_hun");
        return p;
    }

    private static String nz(String s)              { return s == null ? "" : s; }
    private static String nz1(String s, String d)   { return s == null || s.isBlank() ? d : s; }
    private static BigDecimal nz0(BigDecimal b)     { return b == null ? BigDecimal.ZERO : b; }
    private static BigDecimal nzBd(BigDecimal b)    { return b == null ? BigDecimal.ZERO : b; }
    private static LocalDate ld(java.sql.Date d)    { return d == null ? null : d.toLocalDate(); }
    private static java.sql.Date sqlDate(LocalDate d) {
        return d == null ? java.sql.Date.valueOf(LocalDate.of(1899, 12, 31))
                         : java.sql.Date.valueOf(d);
    }
}
