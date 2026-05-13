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

import com.landmarksoftware.payroll.model.AwardJobClass;
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
 * paawjob CRUD for award job classes (PAAW01 S2 drill).
 *
 * <p>{@code paawjob} carries ~80 columns covering hours/rates, LSL, AL +
 * loading, sick (3 tiers), RDO accrual, super (3 bands + voluntary), and
 * free-text other-data. We map every column individually rather than
 * grouping into JSONB or similar — the COBOL pay-run code expects
 * fixed columns and we'd lose that without a separate adapter.
 *
 * <p>Delete blocks if any active employee carries this {@code job_class}.
 */
@Service
public class AwardJobClassService {

    private final JdbcTemplate jdbc;

    public AwardJobClassService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<AwardJobClass> ROW = (rs, i) -> {
        AwardJobClass j = new AwardJobClass();
        j.companyNo          = rs.getInt("company_no");
        j.awardCode          = nz(rs.getString("award_code"));
        j.jobClassCode       = nz(rs.getString("job_class_code"));
        j.desc1              = nz(rs.getString("desc1"));
        j.stdHrs             = rs.getInt("std_hrs");
        j.ratePerHr          = nzDec(rs.getBigDecimal("rate_per_hr"));
        j.ratePerWeek        = nzDec(rs.getBigDecimal("rate_per_week"));
        j.annualAmt          = nzDec(rs.getBigDecimal("annual_amt"));
        j.lslStartYr         = rs.getInt("lsl_start_yr");
        j.lslHrs             = rs.getInt("lsl_hrs");
        j.lslCalcMethod      = nz(rs.getString("lsl_calc_method"));
        j.lslWeeks           = nzDec(rs.getBigDecimal("lsl_weeks"));
        j.lslIncLumpInd      = nz(rs.getString("lsl_inc_lump_ind"));
        j.lslDateInd         = nz(rs.getString("lsl_date_ind"));
        j.lslDateDay         = rs.getInt("lsl_date_day");
        j.lslDateMonth       = rs.getInt("lsl_date_month");
        j.lslCasStartYr      = rs.getInt("lsl_cas_start_yr");
        j.lslCasWksPerYr     = nzDec(rs.getBigDecimal("lsl_cas_wks_per_yr"));
        j.lslCasAveWks1      = rs.getInt("lsl_cas_ave_wks_1");
        j.lslCasAveWks2      = rs.getInt("lsl_cas_ave_wks_2");
        j.alHrs              = rs.getInt("al_hrs");
        j.alAfterMths        = rs.getInt("al_after_mths");
        j.alIncLumpInd       = nz(rs.getString("al_inc_lump_ind"));
        j.alDateInd          = nz(rs.getString("al_date_ind"));
        j.alDateDay          = rs.getInt("al_date_day");
        j.alDateMonth        = rs.getInt("al_date_month");
        j.allPerc            = nzDec(rs.getBigDecimal("all_perc"));
        j.allAccrualMax      = rs.getInt("all_accrual_max");
        j.allHrs             = rs.getInt("all_hrs");
        j.allAfterMths       = rs.getInt("all_after_mths");
        j.allIncLumpInd      = nz(rs.getString("all_inc_lump_ind"));
        j.allDateInd         = nz(rs.getString("all_date_ind"));
        j.allDateDay         = rs.getInt("all_date_day");
        j.allDateMonth       = rs.getInt("all_date_month");
        j.sickHrs1           = rs.getInt("sick_hrs_1");
        j.sickHrs2           = rs.getInt("sick_hrs_2");
        j.sickHrs3           = rs.getInt("sick_hrs_3");
        j.sickAfterMths1     = rs.getInt("sick_after_mths_1");
        j.sickAfterMths2     = rs.getInt("sick_after_mths_2");
        j.sickAfterMths3     = rs.getInt("sick_after_mths_3");
        j.sickIncLumpInd1    = nz(rs.getString("sick_inc_lump_ind_1"));
        j.sickIncLumpInd2    = nz(rs.getString("sick_inc_lump_ind_2"));
        j.sickIncLumpInd3    = nz(rs.getString("sick_inc_lump_ind_3"));
        j.sickDateInd1       = nz(rs.getString("sick_date_ind_1"));
        j.sickDateInd2       = nz(rs.getString("sick_date_ind_2"));
        j.sickDateInd3       = nz(rs.getString("sick_date_ind_3"));
        j.sickDateDay1       = rs.getInt("sick_date_day_1");
        j.sickDateDay2       = rs.getInt("sick_date_day_2");
        j.sickDateDay3       = rs.getInt("sick_date_day_3");
        j.sickDateMonth1     = rs.getInt("sick_date_month_1");
        j.sickDateMonth2     = rs.getInt("sick_date_month_2");
        j.sickDateMonth3     = rs.getInt("sick_date_month_3");
        j.sickAccrualMax     = rs.getInt("sick_accrual_max");
        j.accrualPayCode     = nz(rs.getString("accrual_pay_code"));
        j.paidHrsPerDay      = rs.getInt("paid_hrs_per_day");
        j.accrualMinsPerDay  = rs.getInt("accrual_mins_per_day");
        j.minimumAccrualMins = rs.getInt("minimum_accrual_mins");
        j.rdoAccrualMax      = rs.getInt("rdo_accrual_max");
        Date scd = rs.getDate("super_commence_date");
        j.superCommenceDate  = scd == null ? null : scd.toLocalDate();
        j.qualifyDays        = rs.getInt("qualify_days");
        j.minHrs             = rs.getInt("min_hrs");
        j.minAmt             = nzDec(rs.getBigDecimal("min_amt"));
        j.percOrAmtFlag      = nz(rs.getString("perc_or_amt_flag"));
        j.hrsOrAmtFlag       = nz(rs.getString("hrs_or_amt_flag"));
        j.band1FromAmt       = nzDec(rs.getBigDecimal("band_1_from_amt"));
        j.band1ToAmt         = nzDec(rs.getBigDecimal("band_1_to_amt"));
        j.band1Value         = nzDec(rs.getBigDecimal("band_1_value"));
        j.band2FromAmt       = nzDec(rs.getBigDecimal("band_2_from_amt"));
        j.band2ToAmt         = nzDec(rs.getBigDecimal("band_2_to_amt"));
        j.band2Value         = nzDec(rs.getBigDecimal("band_2_value"));
        j.band3FromAmt       = nzDec(rs.getBigDecimal("band_3_from_amt"));
        j.band3ToAmt         = nzDec(rs.getBigDecimal("band_3_to_amt"));
        j.band3Value         = nzDec(rs.getBigDecimal("band_3_value"));
        j.volSuperFlag       = nz(rs.getString("vol_super_flag"));
        j.totalVolValue      = nzDec(rs.getBigDecimal("total_vol_value"));
        j.addnVolValue       = nzDec(rs.getBigDecimal("addn_vol_value"));
        j.vestSuperCode      = nz(rs.getString("vest_super_code"));
        j.nonvestSuperCode   = nz(rs.getString("nonvest_super_code"));
        j.minHrsUnder18      = rs.getInt("min_hrs_under_18");
        j.maxHrs             = rs.getInt("max_hrs");
        j.otherData          = nz(rs.getString("other_data"));
        j.noteNo             = rs.getLong("note_no");
        return j;
    };

    // ─── Reads ──────────────────────────────────────────────────────────

    public List<AwardJobClass> findByAward(int companyNo, String awardCode) {
        return jdbc.query(
            "SELECT * FROM paawjob WHERE company_no=? AND award_code=? " +
            "ORDER BY job_class_code",
            ROW, companyNo, awardCode);
    }

    public boolean exists(int companyNo, String awardCode, String jobClassCode) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM paawjob WHERE company_no=? AND award_code=? " +
            "AND job_class_code=?",
            Integer.class, companyNo, awardCode, jobClassCode);
        return n != null && n > 0;
    }

    /** Active employees attached to this job class — non-zero blocks delete. */
    public int countAttachedEmployees(int companyNo, String jobClassCode) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pastaff WHERE company_no=? AND job_class=? " +
            "AND employee_status <> 'T'",
            Integer.class, companyNo, jobClassCode);
        return n == null ? 0 : n;
    }

    // ─── Writes ─────────────────────────────────────────────────────────

    @Transactional
    public void insert(AwardJobClass j, String userId) {
        Stamp s = stamp(userId);
        jdbc.update(INSERT_SQL, args(j, s, true));
    }

    @Transactional
    public void update(AwardJobClass j, String userId) {
        Stamp s = stamp(userId);
        jdbc.update(UPDATE_SQL, args(j, s, false));
    }

    @Transactional
    public void delete(int companyNo, String awardCode, String jobClassCode) {
        // Cascade: remove dependent paawwcp rows first
        jdbc.update(
            "DELETE FROM paawwcp WHERE company_no=? AND award_code=? AND job_class_code=?",
            companyNo, awardCode, jobClassCode);
        jdbc.update(
            "DELETE FROM paawjob WHERE company_no=? AND award_code=? AND job_class_code=?",
            companyNo, awardCode, jobClassCode);
    }

    // ─── SQL + parameter binding ────────────────────────────────────────

    private static final String INSERT_SQL =
        "INSERT INTO paawjob (" +
        " company_no, award_code, job_class_code, desc1, std_hrs, " +
        " rate_per_hr, rate_per_week, annual_amt, " +
        " lsl_start_yr, lsl_hrs, lsl_calc_method, lsl_weeks, lsl_inc_lump_ind, " +
        " lsl_date_ind, lsl_date_day, lsl_date_month, " +
        " lsl_cas_start_yr, lsl_cas_wks_per_yr, lsl_cas_ave_wks_1, lsl_cas_ave_wks_2, " +
        " al_hrs, al_after_mths, al_inc_lump_ind, al_date_ind, al_date_day, al_date_month, " +
        " all_perc, all_accrual_max, all_hrs, all_after_mths, all_inc_lump_ind, " +
        " all_date_ind, all_date_day, all_date_month, " +
        " sick_hrs_1, sick_hrs_2, sick_hrs_3, " +
        " sick_after_mths_1, sick_after_mths_2, sick_after_mths_3, " +
        " sick_inc_lump_ind_1, sick_inc_lump_ind_2, sick_inc_lump_ind_3, " +
        " sick_date_ind_1, sick_date_ind_2, sick_date_ind_3, " +
        " sick_date_day_1, sick_date_day_2, sick_date_day_3, " +
        " sick_date_month_1, sick_date_month_2, sick_date_month_3, " +
        " sick_accrual_max, accrual_pay_code, paid_hrs_per_day, accrual_mins_per_day, " +
        " minimum_accrual_mins, rdo_accrual_max, super_commence_date, qualify_days, " +
        " min_hrs, min_amt, perc_or_amt_flag, hrs_or_amt_flag, " +
        " band_1_from_amt, band_1_to_amt, band_1_value, " +
        " band_2_from_amt, band_2_to_amt, band_2_value, " +
        " band_3_from_amt, band_3_to_amt, band_3_value, " +
        " vol_super_flag, total_vol_value, addn_vol_value, vest_super_code, nonvest_super_code, " +
        " min_hrs_under_18, max_hrs, other_data, note_no, " +
        " audit_user_id, audit_date, audit_time_hr, audit_time_min, " +
        " audit_time_sec, audit_time_hun) " +
        "VALUES (" + placeholders(87) + ")";

    private static final String UPDATE_SQL =
        "UPDATE paawjob SET " +
        " desc1=?, std_hrs=?, rate_per_hr=?, rate_per_week=?, annual_amt=?, " +
        " lsl_start_yr=?, lsl_hrs=?, lsl_calc_method=?, lsl_weeks=?, lsl_inc_lump_ind=?, " +
        " lsl_date_ind=?, lsl_date_day=?, lsl_date_month=?, " +
        " lsl_cas_start_yr=?, lsl_cas_wks_per_yr=?, lsl_cas_ave_wks_1=?, lsl_cas_ave_wks_2=?, " +
        " al_hrs=?, al_after_mths=?, al_inc_lump_ind=?, al_date_ind=?, al_date_day=?, al_date_month=?, " +
        " all_perc=?, all_accrual_max=?, all_hrs=?, all_after_mths=?, all_inc_lump_ind=?, " +
        " all_date_ind=?, all_date_day=?, all_date_month=?, " +
        " sick_hrs_1=?, sick_hrs_2=?, sick_hrs_3=?, " +
        " sick_after_mths_1=?, sick_after_mths_2=?, sick_after_mths_3=?, " +
        " sick_inc_lump_ind_1=?, sick_inc_lump_ind_2=?, sick_inc_lump_ind_3=?, " +
        " sick_date_ind_1=?, sick_date_ind_2=?, sick_date_ind_3=?, " +
        " sick_date_day_1=?, sick_date_day_2=?, sick_date_day_3=?, " +
        " sick_date_month_1=?, sick_date_month_2=?, sick_date_month_3=?, " +
        " sick_accrual_max=?, accrual_pay_code=?, paid_hrs_per_day=?, accrual_mins_per_day=?, " +
        " minimum_accrual_mins=?, rdo_accrual_max=?, super_commence_date=?, qualify_days=?, " +
        " min_hrs=?, min_amt=?, perc_or_amt_flag=?, hrs_or_amt_flag=?, " +
        " band_1_from_amt=?, band_1_to_amt=?, band_1_value=?, " +
        " band_2_from_amt=?, band_2_to_amt=?, band_2_value=?, " +
        " band_3_from_amt=?, band_3_to_amt=?, band_3_value=?, " +
        " vol_super_flag=?, total_vol_value=?, addn_vol_value=?, vest_super_code=?, nonvest_super_code=?, " +
        " min_hrs_under_18=?, max_hrs=?, other_data=?, " +
        " audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?, " +
        " audit_time_sec=?, audit_time_hun=? " +
        "WHERE company_no=? AND award_code=? AND job_class_code=?";

    /** Assemble the parameter list for INSERT (87 cols) or UPDATE (data + PK). */
    private Object[] args(AwardJobClass j, Stamp s, boolean forInsert) {
        java.util.List<Object> p = new java.util.ArrayList<>();
        if (forInsert) {
            p.add(j.companyNo); p.add(j.awardCode); p.add(j.jobClassCode);
        }
        p.add(j.desc1);              p.add(j.stdHrs);
        p.add(j.ratePerHr);          p.add(j.ratePerWeek);    p.add(j.annualAmt);
        p.add(j.lslStartYr);         p.add(j.lslHrs);
        p.add(j.lslCalcMethod);      p.add(j.lslWeeks);       p.add(j.lslIncLumpInd);
        p.add(j.lslDateInd);         p.add(j.lslDateDay);     p.add(j.lslDateMonth);
        p.add(j.lslCasStartYr);      p.add(j.lslCasWksPerYr); p.add(j.lslCasAveWks1); p.add(j.lslCasAveWks2);
        p.add(j.alHrs);              p.add(j.alAfterMths);    p.add(j.alIncLumpInd);
        p.add(j.alDateInd);          p.add(j.alDateDay);      p.add(j.alDateMonth);
        p.add(j.allPerc);            p.add(j.allAccrualMax);  p.add(j.allHrs); p.add(j.allAfterMths);
        p.add(j.allIncLumpInd);      p.add(j.allDateInd);     p.add(j.allDateDay); p.add(j.allDateMonth);
        p.add(j.sickHrs1);           p.add(j.sickHrs2);       p.add(j.sickHrs3);
        p.add(j.sickAfterMths1);     p.add(j.sickAfterMths2); p.add(j.sickAfterMths3);
        p.add(j.sickIncLumpInd1);    p.add(j.sickIncLumpInd2); p.add(j.sickIncLumpInd3);
        p.add(j.sickDateInd1);       p.add(j.sickDateInd2);   p.add(j.sickDateInd3);
        p.add(j.sickDateDay1);       p.add(j.sickDateDay2);   p.add(j.sickDateDay3);
        p.add(j.sickDateMonth1);     p.add(j.sickDateMonth2); p.add(j.sickDateMonth3);
        p.add(j.sickAccrualMax);     p.add(j.accrualPayCode); p.add(j.paidHrsPerDay);  p.add(j.accrualMinsPerDay);
        p.add(j.minimumAccrualMins); p.add(j.rdoAccrualMax);  p.add(sqlDate(j.superCommenceDate)); p.add(j.qualifyDays);
        p.add(j.minHrs);             p.add(j.minAmt);         p.add(j.percOrAmtFlag);  p.add(j.hrsOrAmtFlag);
        p.add(j.band1FromAmt);       p.add(j.band1ToAmt);     p.add(j.band1Value);
        p.add(j.band2FromAmt);       p.add(j.band2ToAmt);     p.add(j.band2Value);
        p.add(j.band3FromAmt);       p.add(j.band3ToAmt);     p.add(j.band3Value);
        p.add(j.volSuperFlag);       p.add(j.totalVolValue);  p.add(j.addnVolValue);
        p.add(j.vestSuperCode);      p.add(j.nonvestSuperCode);
        p.add(j.minHrsUnder18);      p.add(j.maxHrs);         p.add(j.otherData);
        if (forInsert) p.add(j.noteNo);
        p.add(s.user); p.add(s.date); p.add(s.hr); p.add(s.mi); p.add(s.sec); p.add(s.hun);
        if (!forInsert) {
            p.add(j.companyNo); p.add(j.awardCode); p.add(j.jobClassCode);
        }
        return p.toArray();
    }

    private static String placeholders(int n) {
        StringBuilder b = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) { if (i > 0) b.append(','); b.append('?'); }
        return b.toString();
    }

    // ─── Internal helpers ───────────────────────────────────────────────

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

    private static String     nz(String s)        { return s == null ? "" : s; }
    private static BigDecimal nzDec(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
