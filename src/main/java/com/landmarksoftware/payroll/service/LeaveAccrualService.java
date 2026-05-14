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
 * PAPA14 — Leave Processing.
 *
 * <p>Accrues annual leave (AL) and sick leave (SL) onto {@code pastaff} for
 * every employee on a posted payrun, based on hours worked in that payrun.
 *
 * <p>MVP accrual factors (matching the Australian Fair Work default):
 * <ul>
 *   <li><b>AL</b> — 4 weeks / 52 weeks → 0.0769 minutes accrued per minute worked.</li>
 *   <li><b>SL</b> — 10 days / 52 weeks (≈ 2 weeks at 38hr) → 0.0385.</li>
 *   <li><b>LSL</b> — not handled in this MVP; needs years-of-service tracking.</li>
 * </ul>
 *
 * <p>Hours worked = {@code patimhd.total_normal_min + total_otime_min_actual}
 * (PAPP01 populated these during recalc). Award-rate overrides from paawjob
 * are deferred — the simple flat factor is exposed as a tweakable default
 * so a future PAPA14 pass can pick up paawjob.al_accrual_rate per job class.
 *
 * <p>Refuses to run unless the payrun status is {@code "P"} (Posted) — we
 * accrue against confirmed timesheets, not in-progress ones.
 */
@Service
public class LeaveAccrualService {

    /** AL accrual: 4 weeks ÷ 52 weeks worked. Override per award later. */
    public static final BigDecimal AL_FACTOR =
        new BigDecimal("4").divide(new BigDecimal("52"), 8, RoundingMode.HALF_UP);
    /** SL accrual: 2 weeks (10 days at 38hr) ÷ 52 weeks worked. */
    public static final BigDecimal SL_FACTOR =
        new BigDecimal("2").divide(new BigDecimal("52"), 8, RoundingMode.HALF_UP);

    private final JdbcTemplate           jdbc;
    private final PayrunService          payruns;
    private final TimesheetHeaderService timesheetHeaders;

    public LeaveAccrualService(JdbcTemplate jdbc,
                                PayrunService payruns,
                                TimesheetHeaderService timesheetHeaders) {
        this.jdbc             = jdbc;
        this.payruns          = payruns;
        this.timesheetHeaders = timesheetHeaders;
    }

    public record Result(int employees, int alMinAccrued, int slMinAccrued) {}

    /**
     * Accrue AL + SL onto pastaff for every employee on a posted payrun.
     * Idempotency note: this MVP does <em>not</em> remember whether a
     * payrun has already been processed — re-running will double-accrue.
     * Track per-payrun accrual state in a dedicated table once the user
     * has confirmed the factors are right.
     */
    @Transactional
    public Result accruePayrun(int companyNo, int payrunNo, String userId) {
        Payrun pr = payruns.findOne(companyNo, payrunNo).orElseThrow(
            () -> new IllegalArgumentException("Payrun " + payrunNo + " not found."));
        if (!"P".equalsIgnoreCase(pr.payrunStatus)
                && !"F".equalsIgnoreCase(pr.payrunStatus)) {
            throw new IllegalStateException("Payrun " + payrunNo + " is "
                + pr.statusDisplay().toLowerCase()
                + " — only posted payruns can have leave accrued.");
        }

        List<TimesheetHeader> headers = timesheetHeaders.findForPayrun(companyNo, payrunNo, null);
        int employees    = 0;
        int totalAl      = 0;
        int totalSl      = 0;
        LocalTime now = LocalTime.now();
        java.sql.Date today = java.sql.Date.valueOf(LocalDate.now());

        for (TimesheetHeader h : headers) {
            int worked = h.totalNormalMin + h.totalOtimeMinActual;
            if (worked <= 0) continue;
            int alMin = new BigDecimal(worked).multiply(AL_FACTOR)
                .setScale(0, RoundingMode.HALF_UP).intValue();
            int slMin = new BigDecimal(worked).multiply(SL_FACTOR)
                .setScale(0, RoundingMode.HALF_UP).intValue();
            if (alMin == 0 && slMin == 0) continue;

            // pastaff.al_hrs_accrued and accrued_sick_leave are minute totals.
            jdbc.update(
                "UPDATE pastaff SET" +
                "  al_hrs_accrued = al_hrs_accrued + ?," +
                "  accrued_sick_leave = accrued_sick_leave + ?," +
                "  audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?," +
                "  audit_time_sec=?, audit_time_hun=? " +
                "WHERE company_no=? AND employee_no=?",
                alMin, slMin,
                nz(userId), today, now.getHour(), now.getMinute(), now.getSecond(), 0,
                companyNo, h.employeeNo);

            employees++;
            totalAl += alMin;
            totalSl += slMin;
        }
        return new Result(employees, totalAl, totalSl);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
