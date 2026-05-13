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

import com.landmarksoftware.payroll.model.Department;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * padepts CRUD service for PAPG01's department drill-down.
 *
 * <p>Audit columns are NOT NULL — every insert/update populates them from
 * the caller's user id and the server clock. {@code last_wage_accr_date}
 * is owned by the wage-accrual processing; this service never overwrites
 * it on update (we re-supply the existing value to keep INSERT clean).
 *
 * <p>Delete blocks if active employees carry this department, matching the
 * COBOL guard.
 */
@Service
public class DepartmentService {

    private final JdbcTemplate jdbc;

    public DepartmentService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Department> ROW = (rs, i) -> {
        Department d = new Department();
        d.companyNo            = rs.getInt   ("company_no");
        d.paygroup             = nz(rs.getString("paygroup"));
        d.dept                 = nz(rs.getString("dept"));
        d.desc1                = nz(rs.getString("desc1"));
        d.wcompExpMain         = rs.getInt("wcomp_exp_main");
        d.wcompExpSub          = rs.getInt("wcomp_exp_sub");
        d.payTaxExpMain        = rs.getInt("pay_tax_exp_main");
        d.payTaxExpSub         = rs.getInt("pay_tax_exp_sub");
        d.interCrAcctMain      = rs.getInt("inter_cr_acct_main");
        d.interCrAcctSub       = rs.getInt("inter_cr_acct_sub");
        d.interChargeAcctMain  = rs.getInt("inter_charge_acct_main");
        d.interChargeAcctSub   = rs.getInt("inter_charge_acct_sub");
        d.accruedSalAcctMain   = rs.getInt("accrued_sal_acct_main");
        d.accruedSalAcctSub    = rs.getInt("accrued_sal_acct_sub");
        d.accruedTimeAcctMain  = rs.getInt("accrued_time_acct_main");
        d.accruedTimeAcctSub   = rs.getInt("accrued_time_acct_sub");
        d.employSupAcctMain    = rs.getInt("employ_sup_acct_main");
        d.employSupAcctSub     = rs.getInt("employ_sup_acct_sub");
        d.revAccrSalAcctMain   = rs.getInt("rev_accr_sal_acct_main");
        d.revAccrSalAcctSub    = rs.getInt("rev_accr_sal_acct_sub");
        d.accrSalVarAcctMain   = rs.getInt("accr_sal_var_acct_main");
        d.accrSalVarAcctSub    = rs.getInt("accr_sal_var_acct_sub");
        d.oncostsExpAcctMain   = rs.getInt("oncosts_exp_acct_main");
        d.oncostsExpAcctSub    = rs.getInt("oncosts_exp_acct_sub");
        d.sickProvAcctMain     = rs.getInt("sick_prov_acct_main");
        d.sickProvAcctSub      = rs.getInt("sick_prov_acct_sub");
        d.sickExpAcctMain      = rs.getInt("sick_exp_acct_main");
        d.sickExpAcctSub       = rs.getInt("sick_exp_acct_sub");
        d.alProvAcctMain       = rs.getInt("al_prov_acct_main");
        d.alProvAcctSub        = rs.getInt("al_prov_acct_sub");
        d.alExpAcctMain        = rs.getInt("al_exp_acct_main");
        d.alExpAcctSub         = rs.getInt("al_exp_acct_sub");
        d.lslProvAcctMain      = rs.getInt("lsl_prov_acct_main");
        d.lslProvAcctSub       = rs.getInt("lsl_prov_acct_sub");
        d.lslExpAcctMain       = rs.getInt("lsl_exp_acct_main");
        d.lslExpAcctSub        = rs.getInt("lsl_exp_acct_sub");
        d.wageAcctProvMain     = rs.getInt("wage_acct_prov_main");
        d.wageAccrProvSub      = rs.getInt("wage_accr_prov_sub");
        d.wageAccrAcctMain     = rs.getInt("wage_accr_acct_main");
        d.wageAccrAcctSub      = rs.getInt("wage_accr_acct_sub");
        Date lwa = rs.getDate("last_wage_accr_date");
        d.lastWageAccrDate     = lwa == null ? null : lwa.toLocalDate();
        d.noteNo               = rs.getLong("note_no");
        return d;
    };

    // ─── Reads ──────────────────────────────────────────────────────────

    public List<Department> findByPaygroup(int companyNo, String paygroup) {
        return jdbc.query(
            "SELECT * FROM padepts WHERE company_no=? AND paygroup=? ORDER BY dept",
            ROW, companyNo, paygroup);
    }

    public Optional<Department> findOne(int companyNo, String paygroup, String dept) {
        List<Department> rows = jdbc.query(
            "SELECT * FROM padepts WHERE company_no=? AND paygroup=? AND dept=?",
            ROW, companyNo, paygroup, dept);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean exists(int companyNo, String paygroup, String dept) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM padepts WHERE company_no=? AND paygroup=? AND dept=?",
            Integer.class, companyNo, paygroup, dept);
        return n != null && n > 0;
    }

    /** Active employees carrying this department — non-zero blocks delete. */
    public int countAttachedEmployees(int companyNo, String dept) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pastaff WHERE company_no=? AND dept=? " +
            "AND employee_status <> 'T'",
            Integer.class, companyNo, dept);
        return n == null ? 0 : n;
    }

    // ─── Writes ─────────────────────────────────────────────────────────

    @Transactional
    public void insert(Department d, String userId) {
        Stamp s = stamp(userId);
        jdbc.update(
            "INSERT INTO padepts (" +
            " company_no, paygroup, dept, desc1," +
            " wcomp_exp_main, wcomp_exp_sub," +
            " pay_tax_exp_main, pay_tax_exp_sub," +
            " inter_cr_acct_main, inter_cr_acct_sub," +
            " inter_charge_acct_main, inter_charge_acct_sub," +
            " accrued_sal_acct_main, accrued_sal_acct_sub," +
            " accrued_time_acct_main, accrued_time_acct_sub," +
            " employ_sup_acct_main, employ_sup_acct_sub," +
            " rev_accr_sal_acct_main, rev_accr_sal_acct_sub," +
            " accr_sal_var_acct_main, accr_sal_var_acct_sub," +
            " oncosts_exp_acct_main, oncosts_exp_acct_sub," +
            " sick_prov_acct_main, sick_prov_acct_sub," +
            " sick_exp_acct_main, sick_exp_acct_sub," +
            " al_prov_acct_main, al_prov_acct_sub," +
            " al_exp_acct_main, al_exp_acct_sub," +
            " lsl_prov_acct_main, lsl_prov_acct_sub," +
            " lsl_exp_acct_main, lsl_exp_acct_sub," +
            " wage_acct_prov_main, wage_accr_prov_sub," +
            " wage_accr_acct_main, wage_accr_acct_sub," +
            " last_wage_accr_date, note_no," +
            " audit_user_id, audit_date, audit_time_hr, audit_time_min," +
            " audit_time_sec, audit_time_hun" +
            ") VALUES (" +
            "  ?, ?, ?, ?, " +
            "  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
            "  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?" +
            ")",
            d.companyNo, d.paygroup, d.dept, d.desc1,
            d.wcompExpMain, d.wcompExpSub,
            d.payTaxExpMain, d.payTaxExpSub,
            d.interCrAcctMain, d.interCrAcctSub,
            d.interChargeAcctMain, d.interChargeAcctSub,
            d.accruedSalAcctMain, d.accruedSalAcctSub,
            d.accruedTimeAcctMain, d.accruedTimeAcctSub,
            d.employSupAcctMain, d.employSupAcctSub,
            d.revAccrSalAcctMain, d.revAccrSalAcctSub,
            d.accrSalVarAcctMain, d.accrSalVarAcctSub,
            d.oncostsExpAcctMain, d.oncostsExpAcctSub,
            d.sickProvAcctMain, d.sickProvAcctSub,
            d.sickExpAcctMain, d.sickExpAcctSub,
            d.alProvAcctMain, d.alProvAcctSub,
            d.alExpAcctMain, d.alExpAcctSub,
            d.lslProvAcctMain, d.lslProvAcctSub,
            d.lslExpAcctMain, d.lslExpAcctSub,
            d.wageAcctProvMain, d.wageAccrProvSub,
            d.wageAccrAcctMain, d.wageAccrAcctSub,
            sqlDate(d.lastWageAccrDate), d.noteNo,
            s.user, s.date, s.hr, s.mi, s.sec, s.hun);
    }

    /**
     * Update all editable columns. {@code last_wage_accr_date} is owned by
     * the wage-accrual process — it's preserved by re-supplying the value
     * from the row we just loaded (the controller carries it forward).
     */
    @Transactional
    public void update(Department d, String userId) {
        Stamp s = stamp(userId);
        jdbc.update(
            "UPDATE padepts SET " +
            " desc1=?," +
            " wcomp_exp_main=?, wcomp_exp_sub=?," +
            " pay_tax_exp_main=?, pay_tax_exp_sub=?," +
            " inter_cr_acct_main=?, inter_cr_acct_sub=?," +
            " inter_charge_acct_main=?, inter_charge_acct_sub=?," +
            " accrued_sal_acct_main=?, accrued_sal_acct_sub=?," +
            " accrued_time_acct_main=?, accrued_time_acct_sub=?," +
            " employ_sup_acct_main=?, employ_sup_acct_sub=?," +
            " rev_accr_sal_acct_main=?, rev_accr_sal_acct_sub=?," +
            " accr_sal_var_acct_main=?, accr_sal_var_acct_sub=?," +
            " oncosts_exp_acct_main=?, oncosts_exp_acct_sub=?," +
            " sick_prov_acct_main=?, sick_prov_acct_sub=?," +
            " sick_exp_acct_main=?, sick_exp_acct_sub=?," +
            " al_prov_acct_main=?, al_prov_acct_sub=?," +
            " al_exp_acct_main=?, al_exp_acct_sub=?," +
            " lsl_prov_acct_main=?, lsl_prov_acct_sub=?," +
            " lsl_exp_acct_main=?, lsl_exp_acct_sub=?," +
            " wage_acct_prov_main=?, wage_accr_prov_sub=?," +
            " wage_accr_acct_main=?, wage_accr_acct_sub=?," +
            " audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?," +
            " audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND paygroup=? AND dept=?",
            d.desc1,
            d.wcompExpMain, d.wcompExpSub,
            d.payTaxExpMain, d.payTaxExpSub,
            d.interCrAcctMain, d.interCrAcctSub,
            d.interChargeAcctMain, d.interChargeAcctSub,
            d.accruedSalAcctMain, d.accruedSalAcctSub,
            d.accruedTimeAcctMain, d.accruedTimeAcctSub,
            d.employSupAcctMain, d.employSupAcctSub,
            d.revAccrSalAcctMain, d.revAccrSalAcctSub,
            d.accrSalVarAcctMain, d.accrSalVarAcctSub,
            d.oncostsExpAcctMain, d.oncostsExpAcctSub,
            d.sickProvAcctMain, d.sickProvAcctSub,
            d.sickExpAcctMain, d.sickExpAcctSub,
            d.alProvAcctMain, d.alProvAcctSub,
            d.alExpAcctMain, d.alExpAcctSub,
            d.lslProvAcctMain, d.lslProvAcctSub,
            d.lslExpAcctMain, d.lslExpAcctSub,
            d.wageAcctProvMain, d.wageAccrProvSub,
            d.wageAccrAcctMain, d.wageAccrAcctSub,
            s.user, s.date, s.hr, s.mi, s.sec, s.hun,
            d.companyNo, d.paygroup, d.dept);
    }

    @Transactional
    public void delete(int companyNo, String paygroup, String dept) {
        jdbc.update(
            "DELETE FROM padepts WHERE company_no=? AND paygroup=? AND dept=?",
            companyNo, paygroup, dept);
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private record Stamp(String user, Date date, int hr, int mi, int sec, int hun) {}

    private static Stamp stamp(String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now   = LocalTime.now();
        String u = userId == null ? "" : (userId.length() > 15 ? userId.substring(0, 15) : userId);
        return new Stamp(u, Date.valueOf(today),
                          now.getHour(), now.getMinute(),
                          now.getSecond(), now.getNano() / 10_000_000);
    }

    private static Date sqlDate(LocalDate d) {
        if (d == null || d.getYear() < 1900) return Date.valueOf(LocalDate.of(1899, 12, 31));
        return Date.valueOf(d);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
