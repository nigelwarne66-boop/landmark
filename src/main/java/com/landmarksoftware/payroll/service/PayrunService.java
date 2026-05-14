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

import com.landmarksoftware.payroll.model.Payrun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * PATM01 — Pay Run header service (parunhd table).
 *
 * <p>Scope is what PATM01 P1 (payrun selection) needs: a filtered list, a
 * single record lookup, plus Add / Edit / Cancel writes.
 */
@Service
public class PayrunService {

    private final JdbcTemplate jdbc;

    public PayrunService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Filtered list for PATM01 P1.
     *
     * @param companyNo            session company.
     * @param startDate / endDate  payrun_date range (inclusive).
     * @param payrunType           one-char filter; blank = all types.
     * @param includeFullyPosted   include rows where {@code payrun_status='F'}.
     * @param includeCancelled     include rows where {@code payrun_status='D'}.
     */
    public List<Payrun> findFiltered(int companyNo,
                                      LocalDate startDate, LocalDate endDate,
                                      String payrunType,
                                      boolean includeFullyPosted,
                                      boolean includeCancelled) {
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM parunhd WHERE company_no=? " +
            "AND payrun_date BETWEEN ? AND ? ");
        if (!includeFullyPosted) sql.append("AND payrun_status<>'F' ");
        if (!includeCancelled)   sql.append("AND payrun_status<>'D' ");
        if (payrunType != null && !payrunType.isBlank()) {
            sql.append("AND payrun_type=? ");
        }
        sql.append("ORDER BY payrun_date DESC, payrun_no DESC");

        Object[] args;
        if (payrunType != null && !payrunType.isBlank()) {
            args = new Object[] { companyNo,
                java.sql.Date.valueOf(startDate), java.sql.Date.valueOf(endDate),
                payrunType };
        } else {
            args = new Object[] { companyNo,
                java.sql.Date.valueOf(startDate), java.sql.Date.valueOf(endDate) };
        }
        return jdbc.query(sql.toString(), (rs, i) -> map(rs), args);
    }

    public Optional<Payrun> findOne(int companyNo, int payrunNo) {
        try {
            Payrun p = jdbc.queryForObject(
                "SELECT * FROM parunhd WHERE company_no=? AND payrun_no=?",
                (rs, i) -> map(rs), companyNo, payrunNo);
            return Optional.ofNullable(p);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Next available payrun_no for this company — used by Create Payrun. */
    public int nextPayrunNo(int companyNo) {
        Integer max = jdbc.queryForObject(
            "SELECT COALESCE(MAX(payrun_no),0) FROM parunhd WHERE company_no=?",
            Integer.class, companyNo);
        return (max == null ? 0 : max) + 1;
    }

    @Transactional
    public void insert(Payrun p, String userId) {
        LocalTime now = LocalTime.now();
        jdbc.update(
            "INSERT INTO parunhd (" +
            " company_no, payrun_no, auth_level_no, payrun_date, payrun_status," +
            " yr_no, payrun_type, calcs_completed_flag, default_cost_type," +
            " default_calc_tax_flag, skip_paygroup_on_add, skip_paygroup_on_edit," +
            " deflt_calc_super_flag, retainer_run_flag, splits_run_flag," +
            " create_rdo_flag, no_of_employees, no_of_employees_paid," +
            " paymt_date, paymt_yr_no, ref, remit_status, start_date, end_date," +
            " payer_abn, last_super_seq_no, superstream_only, super_pay_method," +
            " note_no, audit_user_id, audit_date, audit_time_hr, audit_time_min," +
            " audit_time_sec, audit_time_hun) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            p.companyNo, p.payrunNo, p.authLevelNo,
            sqlDate(p.payrunDate), nz1(p.payrunStatus, "O"),
            p.yrNo, nz1(p.payrunType, "P"),
            nz1(p.calcsCompletedFlag, "N"), nz0(p.defaultCostType),
            nz1(p.defaultCalcTaxFlag, "Y"),
            nz1(p.skipPaygroupOnAdd, "N"), nz1(p.skipPaygroupOnEdit, "N"),
            nz1(p.defltCalcSuperFlag, "Y"), nz1(p.retainerRunFlag, "N"),
            nz1(p.splitsRunFlag, "N"), nz1(p.createRdoFlag, "N"),
            p.noOfEmployees, p.noOfEmployeesPaid,
            sqlDate(p.paymtDate), p.paymtYrNo,
            nz0(p.ref), nz0(p.remitStatus),
            sqlDate(p.startDate), sqlDate(p.endDate),
            p.payerAbn, p.lastSuperSeqNo,
            nz1(p.superstreamOnly, "N"), nz0(p.superPayMethod),
            p.noteNo,
            userId, java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0);
    }

    /** PATM01 P1 Edit — updates the user-facing fields, leaves running totals alone. */
    @Transactional
    public void update(Payrun p, String userId) {
        LocalTime now = LocalTime.now();
        jdbc.update(
            "UPDATE parunhd SET" +
            "  payrun_date=?, yr_no=?, payrun_type=?, ref=?," +
            "  paymt_date=?, paymt_yr_no=?, start_date=?, end_date=?," +
            "  default_cost_type=?, default_calc_tax_flag=?, deflt_calc_super_flag=?," +
            "  skip_paygroup_on_add=?, skip_paygroup_on_edit=?," +
            "  retainer_run_flag=?, splits_run_flag=?, create_rdo_flag=?," +
            "  audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?," +
            "  audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND payrun_no=?",
            sqlDate(p.payrunDate), p.yrNo, nz1(p.payrunType, "P"), nz0(p.ref),
            sqlDate(p.paymtDate), p.paymtYrNo,
            sqlDate(p.startDate), sqlDate(p.endDate),
            nz0(p.defaultCostType), nz1(p.defaultCalcTaxFlag, "Y"),
            nz1(p.defltCalcSuperFlag, "Y"),
            nz1(p.skipPaygroupOnAdd, "N"), nz1(p.skipPaygroupOnEdit, "N"),
            nz1(p.retainerRunFlag, "N"), nz1(p.splitsRunFlag, "N"),
            nz1(p.createRdoFlag, "N"),
            userId, java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0,
            p.companyNo, p.payrunNo);
    }

    /** PATM01 P1 Cancel — flips payrun_status to 'D' (cancelled / deleted). */
    @Transactional
    public void cancel(int companyNo, int payrunNo, String userId) {
        LocalTime now = LocalTime.now();
        jdbc.update(
            "UPDATE parunhd SET payrun_status='D'," +
            "  audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?," +
            "  audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND payrun_no=?",
            userId, java.sql.Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), 0,
            companyNo, payrunNo);
    }

    // ── Row mapping ───────────────────────────────────────────────────────

    private static Payrun map(ResultSet rs) throws SQLException {
        Payrun p = new Payrun();
        p.companyNo          = rs.getInt("company_no");
        p.payrunNo           = rs.getInt("payrun_no");
        p.authLevelNo        = rs.getInt("auth_level_no");
        p.payrunDate         = ld(rs.getDate("payrun_date"));
        p.payrunStatus       = nz(rs.getString("payrun_status"));
        p.yrNo               = rs.getInt("yr_no");
        p.payrunType         = nz(rs.getString("payrun_type"));
        p.calcsCompletedFlag = nz(rs.getString("calcs_completed_flag"));
        p.defaultCostType    = nz(rs.getString("default_cost_type"));
        p.defaultCalcTaxFlag = nz(rs.getString("default_calc_tax_flag"));
        p.skipPaygroupOnAdd  = nz(rs.getString("skip_paygroup_on_add"));
        p.skipPaygroupOnEdit = nz(rs.getString("skip_paygroup_on_edit"));
        p.defltCalcSuperFlag = nz(rs.getString("deflt_calc_super_flag"));
        p.retainerRunFlag    = nz(rs.getString("retainer_run_flag"));
        p.splitsRunFlag      = nz(rs.getString("splits_run_flag"));
        p.createRdoFlag      = nz(rs.getString("create_rdo_flag"));
        p.noOfEmployees      = rs.getInt("no_of_employees");
        p.noOfEmployeesPaid  = rs.getInt("no_of_employees_paid");
        p.paymtDate          = ld(rs.getDate("paymt_date"));
        p.paymtYrNo          = rs.getInt("paymt_yr_no");
        p.ref                = nz(rs.getString("ref"));
        p.remitStatus        = nz(rs.getString("remit_status"));
        p.startDate          = ld(rs.getDate("start_date"));
        p.endDate            = ld(rs.getDate("end_date"));
        p.payerAbn           = rs.getLong("payer_abn");
        p.lastSuperSeqNo     = rs.getInt("last_super_seq_no");
        p.superstreamOnly    = nz(rs.getString("superstream_only"));
        p.superPayMethod     = nz(rs.getString("super_pay_method"));
        p.noteNo             = rs.getLong("note_no");
        p.auditUserId        = nz(rs.getString("audit_user_id"));
        p.auditDate          = ld(rs.getDate("audit_date"));
        p.auditTimeHr        = rs.getInt("audit_time_hr");
        p.auditTimeMin       = rs.getInt("audit_time_min");
        p.auditTimeSec       = rs.getInt("audit_time_sec");
        p.auditTimeHun       = rs.getInt("audit_time_hun");
        return p;
    }

    private static String nz(String s)            { return s == null ? "" : s; }
    private static String nz0(String s)           { return s == null ? "" : s; }
    private static String nz1(String s, String d) { return s == null || s.isBlank() ? d : s; }
    private static LocalDate ld(java.sql.Date d)  { return d == null ? null : d.toLocalDate(); }
    private static java.sql.Date sqlDate(LocalDate d) {
        return d == null ? java.sql.Date.valueOf(LocalDate.of(1899, 12, 31))
                         : java.sql.Date.valueOf(d);
    }
}
