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
import com.landmarksoftware.payroll.model.Payrun;
import com.landmarksoftware.payroll.model.TimesheetHeader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * PAPP03 — Leave accrual onto pastaff.
 *
 * <p>This Java port collapses the COBOL ACCRUE-LEAVE / ACCRUE-ANNUAL-LEAVE /
 * ACCRUE-SICK-LEAVE / ACCRUE-LSL chain into one service. Per employee on
 * the payrun:
 * <ul>
 *   <li>Read employee_type, accrue_al_by_hrs_flag from pastaff.</li>
 *   <li>Read paawjob (award + job_class) for AL_HRS / SICK_HRS_1 / LSL_HRS /
 *       ALL_HRS entitlements per pay period.</li>
 *   <li>Apply COBOL-style branching:
 *     <ul>
 *       <li>Full-time + by-period: entitlement direct from paawjob, distributed
 *           across pay periods (52 weekly / 26 fortnightly / 12 monthly).</li>
 *       <li>Part-time, or full-time + by-hours: pro-rata from
 *           {@code patimhd.hrs_wrkd_for_al} (= total_normal_min + total_otime_min_actual).</li>
 *       <li>Casual: AL/SL not accrued — only LSL via ACCRUE-LSL-CASUAL.</li>
 *     </ul>
 *   </li>
 *   <li>Update pastaff balances directly: {@code al_hrs_accrued},
 *       {@code al_hrs_curr_yr}, {@code accrued_al_loading},
 *       {@code all_accrued_this_yr}, {@code accrued_sick_leave},
 *       {@code sl_accrued_this_yr}.</li>
 * </ul>
 *
 * <p><strong>Deferred from full COBOL parity:</strong>
 * <ul>
 *   <li>Anniversary-date math (CALC-AL-SL-ANNIVERSARY) — we treat every accrual
 *       as on-going; the COBOL only adds to {@code al_hrs_curr_yr} until the
 *       anniversary. To replicate exactly we'd track {@code start_al_sl_accrual}.</li>
 *   <li>Lump vs incremental ({@code al_inc_lump_ind = "L"} vs "I") — lump
 *       accruals only post on anniversary; we treat everything incremental.</li>
 *   <li>LSL hrs/weeks split — pre-78, post-78, post-aug-93. MVP defers LSL.</li>
 *   <li>{@code paid_thru_to_date} based number-of-days-paid logic — we use the
 *       patimhd minutes directly.</li>
 * </ul>
 *
 * <p>PALEAVE is NOT written by accrual. The ledger only gets 'A' rows from
 * PAEM01 manual edits + PASU18 opening-balance migration; 'T' rows from
 * PAPP28 posting (see PayrollPostingService.addTakenLeaveRow).
 */
@Service
public class PayrollLeaveService {

    /** Fallback factor when paawjob has no rate set — Fair Work AL default. */
    public static final BigDecimal AL_FACTOR_DEFAULT =
        new BigDecimal("4").divide(new BigDecimal("52"), 8, RoundingMode.HALF_UP);
    /** Fallback for SL — 2 weeks per 52 worked. */
    public static final BigDecimal SL_FACTOR_DEFAULT =
        new BigDecimal("2").divide(new BigDecimal("52"), 8, RoundingMode.HALF_UP);

    private final JdbcTemplate           jdbc;
    private final PayrunService          payruns;
    private final TimesheetHeaderService timesheetHeaders;
    private final AwardJobClassService   awardJobClasses;

    public PayrollLeaveService(JdbcTemplate jdbc,
                                PayrunService payruns,
                                TimesheetHeaderService timesheetHeaders,
                                AwardJobClassService awardJobClasses) {
        this.jdbc             = jdbc;
        this.payruns          = payruns;
        this.timesheetHeaders = timesheetHeaders;
        this.awardJobClasses  = awardJobClasses;
    }

    public record Result(int processed, int skippedCasual, int skippedNoAward,
                          int alMinAccrued, int slMinAccrued) {}

    /**
     * Process leave accrual for every employee on a payrun. Idempotency note:
     * still doesn't track per-payrun accrual state — re-running double-accrues.
     * Address via a {@code pa_accrual_log} or {@code parunhd.leave_processed_flag}
     * before live use.
     */
    @Transactional
    public Result accruePayrun(int companyNo, int payrunNo, String userId) {
        Payrun pr = payruns.findOne(companyNo, payrunNo).orElseThrow(
            () -> new IllegalArgumentException("Payrun " + payrunNo + " not found."));
        if (!pr.isOpen() && !"P".equalsIgnoreCase(pr.payrunStatus)) {
            throw new IllegalStateException("Payrun " + payrunNo + " is "
                + pr.statusDisplay().toLowerCase() + " — cannot accrue leave.");
        }

        List<TimesheetHeader> headers = timesheetHeaders.findForPayrun(companyNo, payrunNo, null);
        int processed = 0, casuals = 0, noAward = 0;
        int totalAl = 0, totalSl = 0;
        java.sql.Date today = java.sql.Date.valueOf(LocalDate.now());
        LocalTime now = LocalTime.now();

        for (TimesheetHeader h : headers) {
            Pastaff staff = readPastaff(companyNo, h.employeeNo);
            if (staff == null) continue;
            if ("C".equalsIgnoreCase(staff.employeeType)) { casuals++; continue; }

            AwardJobClass aj = staff.award.isBlank() || staff.jobClass.isBlank()
                ? null
                : awardJobClasses.findOne(companyNo, staff.award, staff.jobClass).orElse(null);
            if (aj == null) { noAward++; }

            int hoursWorkedMin = h.totalNormalMin + h.totalOtimeMinActual;
            boolean byHours = "Y".equalsIgnoreCase(staff.accrueAlByHrsFlag)
                || "P".equalsIgnoreCase(staff.employeeType);

            int alAccrue = accrueAl(aj, byHours, hoursWorkedMin, pr.payrunType);
            int slAccrue = accrueSl(aj, byHours, hoursWorkedMin);
            int allAccrue = accrueAlLoading(aj, byHours, hoursWorkedMin);

            if (alAccrue == 0 && slAccrue == 0 && allAccrue == 0) continue;
            applyAccrualToPastaff(companyNo, h.employeeNo, alAccrue, slAccrue, allAccrue,
                today, now, userId);
            processed++;
            totalAl += alAccrue;
            totalSl += slAccrue;
        }
        return new Result(processed, casuals, noAward, totalAl, totalSl);
    }

    // ── Per-bucket accrual ───────────────────────────────────────────────

    /**
     * AL accrual in minutes for the period. Mirrors COBOL ACCRUE-ANNUAL-LEAVE
     * with the deferred bits flagged in the class Javadoc.
     */
    private int accrueAl(AwardJobClass aj, boolean byHours, int hoursWorkedMin, String payrunType) {
        if ("T".equalsIgnoreCase(payrunType)) return 0;  // termination payrun — no accrual
        int entitlementMin = aj == null ? 0 : aj.alHrs;
        if (entitlementMin <= 0) {
            // Fallback to flat factor on hours worked.
            return scale(hoursWorkedMin, AL_FACTOR_DEFAULT);
        }
        if (byHours) {
            // Pro-rata: alHrs is per-pay-period entitlement at full-time hrs;
            // award typically encodes the FT period (e.g. 38 × 60 = 2280 min/week).
            // Without an FT-hrs column on paawjob, scale against 38h × 60 = 2280.
            BigDecimal factor = new BigDecimal(entitlementMin)
                .divide(new BigDecimal(2280), 8, RoundingMode.HALF_UP);
            return scale(hoursWorkedMin, factor);
        }
        // Full-time entitlement model — credit the period's allocation.
        return entitlementMin;
    }

    private int accrueSl(AwardJobClass aj, boolean byHours, int hoursWorkedMin) {
        int entitlementMin = aj == null ? 0 : aj.sickHrs1;
        if (entitlementMin <= 0) return scale(hoursWorkedMin, SL_FACTOR_DEFAULT);
        if (byHours) {
            BigDecimal factor = new BigDecimal(entitlementMin)
                .divide(new BigDecimal(2280), 8, RoundingMode.HALF_UP);
            return scale(hoursWorkedMin, factor);
        }
        return entitlementMin;
    }

    private int accrueAlLoading(AwardJobClass aj, boolean byHours, int hoursWorkedMin) {
        int entitlementMin = aj == null ? 0 : aj.allHrs;
        if (entitlementMin <= 0) return 0;
        if (byHours) {
            BigDecimal factor = new BigDecimal(entitlementMin)
                .divide(new BigDecimal(2280), 8, RoundingMode.HALF_UP);
            return scale(hoursWorkedMin, factor);
        }
        return entitlementMin;
    }

    private static int scale(int hoursWorkedMin, BigDecimal factor) {
        return new BigDecimal(hoursWorkedMin).multiply(factor)
            .setScale(0, RoundingMode.HALF_UP).intValue();
    }

    // ── pastaff I/O ──────────────────────────────────────────────────────

    private Pastaff readPastaff(int companyNo, int employeeNo) {
        try {
            return jdbc.queryForObject(
                "SELECT employee_type, accrue_al_by_hrs_flag, award, job_class " +
                "FROM pastaff WHERE company_no=? AND employee_no=?",
                (rs, i) -> {
                    Pastaff s = new Pastaff();
                    s.employeeType        = rs.getString("employee_type");
                    s.accrueAlByHrsFlag   = rs.getString("accrue_al_by_hrs_flag");
                    s.award               = rs.getString("award");
                    s.jobClass            = rs.getString("job_class");
                    if (s.employeeType == null) s.employeeType = "";
                    if (s.accrueAlByHrsFlag == null) s.accrueAlByHrsFlag = "N";
                    if (s.award == null) s.award = "";
                    if (s.jobClass == null) s.jobClass = "";
                    return s;
                },
                companyNo, employeeNo);
        } catch (Exception e) {
            return null;
        }
    }

    private void applyAccrualToPastaff(int companyNo, int employeeNo,
                                        int alAccrue, int slAccrue, int allAccrue,
                                        java.sql.Date today, LocalTime now, String userId) {
        jdbc.update(
            "UPDATE pastaff SET " +
            "  al_hrs_accrued        = al_hrs_accrued + ?," +
            "  al_hrs_curr_yr        = al_hrs_curr_yr + ?," +
            "  accrued_sick_leave    = accrued_sick_leave + ?," +
            "  sl_accrued_this_yr    = sl_accrued_this_yr + ?," +
            "  accrued_al_loading    = accrued_al_loading + ?," +
            "  all_accrued_this_yr   = all_accrued_this_yr + ?," +
            "  audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?," +
            "  audit_time_sec=?, audit_time_hun=? " +
            "WHERE company_no=? AND employee_no=?",
            alAccrue, alAccrue,
            slAccrue, slAccrue,
            allAccrue, allAccrue,
            nz(userId), today,
            now.getHour(), now.getMinute(), now.getSecond(), 0,
            companyNo, employeeNo);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Pastaff-row subset needed for accrual logic. */
    private static class Pastaff {
        String employeeType      = "";
        String accrueAlByHrsFlag = "N";
        String award             = "";
        String jobClass          = "";
    }
}
