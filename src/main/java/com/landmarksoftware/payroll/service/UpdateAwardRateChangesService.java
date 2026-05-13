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

import com.landmarksoftware.payroll.model.Employee;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * PASU11 — Update Award Rate Changes.
 *
 * <p>Driven by {@code paawchg} — the dirty-flag table written by Wave 1.5
 * whenever an award/job-class is edited. For each pending paawchg row in
 * the user-selected range, read the new {@code paawjob} values
 * (rate_per_hr, std_hrs, all_perc) and push them down to:
 *
 * <ol>
 *   <li>Every active {@code pastaff} row carrying that award + job_class
 *       (skipping employees with {@code over_award_flag='Y'} — they keep
 *       their negotiated over-award rate).</li>
 *   <li>Every {@code paecode} default-timesheet row for those employees
 *       where the pay_type is normal/overtime/other (1/2/3) and the
 *       rate currently equals the OLD pastaff rate — recalcs the line's
 *       ext_amt off the new rate.</li>
 *   <li>The employee's {@code paecode} super line (pay_type 17 or 20) —
 *       rebuilds ext_amt from the new gross.</li>
 * </ol>
 *
 * <p>After all employees for a given (award, job_class) are processed,
 * the corresponding paawchg row is deleted — the dirty flag is cleared.
 * Mirrors pasu11.cbl MAIN-LOGIC and pasu11.pl UPDATE-EMPLOYEES /
 * UPDATE-FOR-CHANGES / UPDATE-DEFAULT-TIMESHEETS.
 *
 * <p>Audit:
 * <ul>
 *   <li>One {@code pa_audit} row per batch run.</li>
 *   <li>One {@code paemaud} row per modified employee — full before/after
 *       JSON snapshot of the {@link Employee}.</li>
 *   <li>One {@code papcaud} row per modified paecode line + one per
 *       pastaff change (pay_code_type=1, blank pay_code, matching the
 *       COBOL WRITE-PAY-CODE-AUDIT pattern).</li>
 * </ul>
 */
@Service
public class UpdateAwardRateChangesService {

    /** One preview row — one (award, job_class, employee) intersection. */
    public record AffectedRow(
        String     award,
        String     jobClass,
        int        employeeNo,
        String     employeeName,
        String     employeeType,
        BigDecimal oldRatePerHr,
        BigDecimal newRatePerHr,
        int        oldStdMins,
        int        newStdMins,
        BigDecimal oldAlLoading,
        BigDecimal newAlLoading) {}

    private final JdbcTemplate          jdbc;
    private final EmployeeService       employees;
    private final MasterFileAuditService rowAudit;
    private final BatchAuditService     batchAudit;

    public UpdateAwardRateChangesService(JdbcTemplate jdbc,
                                          EmployeeService employees,
                                          MasterFileAuditService rowAudit,
                                          BatchAuditService batchAudit) {
        this.jdbc       = jdbc;
        this.employees  = employees;
        this.rowAudit   = rowAudit;
        this.batchAudit = batchAudit;
    }

    /**
     * Build the list of employees the apply step WOULD touch. Read-only;
     * no DB writes. One row per (paawchg × pastaff) intersection where
     * at least one of rate / std_hrs / al_loading would change.
     */
    public List<AffectedRow> preview(int companyNo,
                                     String startAward, String endAward,
                                     String startJobClass, String endJobClass) {
        return jdbc.query(
            "SELECT ch.award, ch.job_class, " +
            "       j.rate_per_hr AS new_rate, j.std_hrs AS new_hrs, j.all_perc AS new_all, " +
            "       s.employee_no, s.surname, s.first_name, s.employee_type, " +
            "       s.std_rate_per_hr AS old_rate, s.std_hrs AS old_hrs, s.al_loading AS old_all " +
            "FROM paawchg ch " +
            "JOIN paawjob j ON j.company_no  = ch.company_no " +
            "             AND j.award_code   = ch.award " +
            "             AND j.job_class_code = ch.job_class " +
            "JOIN pastaff  s ON s.company_no  = ch.company_no " +
            "             AND s.award        = ch.award " +
            "             AND s.job_class    = ch.job_class " +
            "WHERE ch.company_no = ? " +
            "  AND ch.award      BETWEEN ? AND ? " +
            "  AND ch.job_class  BETWEEN ? AND ? " +
            "  AND s.over_award_flag <> 'Y' " +
            "  AND s.employee_status <> 'T' " +
            "  AND (s.std_rate_per_hr <> j.rate_per_hr " +
            "       OR (s.employee_type = 'F' AND s.std_hrs <> j.std_hrs) " +
            "       OR s.al_loading <> j.all_perc) " +
            "ORDER BY ch.award, ch.job_class, s.employee_no",
            (rs, i) -> new AffectedRow(
                rs.getString("award"),
                rs.getString("job_class"),
                rs.getInt("employee_no"),
                joinName(rs.getString("surname"), rs.getString("first_name")),
                rs.getString("employee_type"),
                rs.getBigDecimal("old_rate"),
                rs.getBigDecimal("new_rate"),
                rs.getInt("old_hrs"),
                rs.getInt("new_hrs"),
                rs.getBigDecimal("old_all"),
                rs.getBigDecimal("new_all")),
            companyNo,
            blankToLo(startAward),     blankToHi(endAward, "zzz"),
            blankToLo(startJobClass),  blankToHi(endJobClass, "zzzzzz"));
    }

    /**
     * Apply all rate / hrs / loading changes for the selected paawchg
     * range. Returns the number of employees touched (a single employee
     * may have multiple paecode rows updated but is counted once).
     *
     * @param progress invoked after each employee is processed with the
     *                 running count — drives the
     *                 {@link com.landmarksoftware.payroll.ui.BatchProgressDialog}
     */
    @Transactional
    public int apply(int companyNo,
                     String startAward, String endAward,
                     String startJobClass, String endJobClass,
                     String userId, IntConsumer progress) {

        long auditId = batchAudit.start(companyNo, userId, "PASU11",
            "Update Award Rate Changes for awards " +
            nz(startAward) + ".." + nz(endAward) + " job " +
            nz(startJobClass) + ".." + nz(endJobClass),
            BatchAuditService.STATUS_RUNNING);
        try {
            List<AffectedRow> rows = preview(companyNo, startAward, endAward,
                startJobClass, endJobClass);
            int n = 0;
            for (AffectedRow r : rows) {
                applyOneEmployee(companyNo, r, userId);
                n++;
                if (progress != null) progress.accept(n);
            }
            // Clear processed paawchg rows in the same range — only those
            // we actually consumed. (Rows with no affected employees are
            // also cleared to match COBOL DELETE-PAAWCHG semantics.)
            jdbc.update(
                "DELETE FROM paawchg WHERE company_no=? " +
                "  AND award BETWEEN ? AND ? " +
                "  AND job_class BETWEEN ? AND ?",
                companyNo,
                blankToLo(startAward),     blankToHi(endAward, "zzz"),
                blankToLo(startJobClass),  blankToHi(endJobClass, "zzzzzz"));
            batchAudit.complete(auditId, n);
            return n;
        } catch (RuntimeException ex) {
            batchAudit.fail(auditId, ex.toString());
            throw ex;
        }
    }

    // ── Per-employee update (mirrors UPDATE-FOR-CHANGES) ─────────────────

    private void applyOneEmployee(int companyNo, AffectedRow r, String userId) {
        Employee before = employees.findOne(companyNo, r.employeeNo()).orElse(null);
        if (before == null) return;

        Employee after = cloneShallow(before);

        BigDecimal oldAl    = r.oldAlLoading() == null ? BigDecimal.ZERO : r.oldAlLoading();
        BigDecimal newAl    = r.newAlLoading() == null ? BigDecimal.ZERO : r.newAlLoading();
        boolean rateChanged = before.stdRatePerHr.compareTo(r.newRatePerHr()) != 0;
        boolean hrsChanged  = "F".equals(before.employeeType) && before.stdHrs != r.newStdMins();
        boolean alChanged   = oldAl.compareTo(newAl) != 0;

        if (!rateChanged && !hrsChanged && !alChanged) return;

        BigDecimal oldRate = before.stdRatePerHr == null ? BigDecimal.ZERO : before.stdRatePerHr;
        BigDecimal newRate = r.newRatePerHr() == null ? BigDecimal.ZERO : r.newRatePerHr();
        int        newHrs  = "F".equals(before.employeeType) ? r.newStdMins() : before.stdHrs;

        // Recalc std_gross = std_hrs * std_rate_per_hr / 60
        BigDecimal stdGross = BigDecimal.valueOf(newHrs)
            .multiply(newRate)
            .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        BigDecimal annualSalary = stdGross.multiply(BigDecimal.valueOf(52))
            .setScale(2, RoundingMode.HALF_UP);

        // Re-derive actual_paid_rate only if it was non-zero (COBOL guard)
        // actual = (annual_salary - employer_amt) / (52 * paawjob.std_hrs)
        BigDecimal[] actualPaidRateBox = readActualPaidRateAndEmployerAmt(companyNo, r.employeeNo());
        BigDecimal actualPaidRate = actualPaidRateBox[0];
        BigDecimal employerAmt   = actualPaidRateBox[1];
        BigDecimal newActualPaidRate = actualPaidRate;
        if (actualPaidRate.signum() > 0 && r.newStdMins() > 0) {
            newActualPaidRate = annualSalary.subtract(employerAmt)
                .divide(BigDecimal.valueOf(52L * r.newStdMins()), 4, RoundingMode.HALF_UP);
        }

        // Apply the pastaff updates
        jdbc.update(
            "UPDATE pastaff SET std_rate_per_hr=?, std_hrs=?, std_gross=?, " +
            "annual_salary=?, actual_paid_rate=?, al_loading=?, " +
            "audit_user_id=?, audit_date=CURRENT_DATE(), " +
            "audit_time_hr=HOUR(NOW()), audit_time_min=MINUTE(NOW()), " +
            "audit_time_sec=SECOND(NOW()), audit_time_hun=0 " +
            "WHERE company_no=? AND employee_no=?",
            newRate, newHrs, stdGross, annualSalary, newActualPaidRate,
            r.newAlLoading(),
            safeUser(userId), companyNo, r.employeeNo());

        // Refresh after-snapshot for paemaud
        after.stdRatePerHr   = newRate;
        after.stdHrs         = newHrs;
        after.annualSalary   = annualSalary;

        // papcaud "pastaff-changed" marker (matches COBOL WRITE-PAY-CODE-AUDIT)
        rowAudit.auditPaeCode(
            new MasterFileAuditService.PaeCodeSnapshot(
                companyNo, r.employeeNo(),
                1, "",
                newHrs,
                BigDecimal.ZERO,
                newRate,
                stdGross,
                nz(before.paygroup),
                nz(before.dept),
                nz(before.award),
                nz(before.jobClass)),
            MasterFileAuditService.MAINT_MODIFY,
            userId);

        // Push the new rate into the employee's default timesheet (paecode)
        // rows for pay types 1/2/3 where the line currently matches the OLD
        // pastaff rate. Mirrors UPDATE-TSHEET-LINE.
        if (rateChanged || hrsChanged) {
            updateDefaultTimesheets(companyNo, r.employeeNo(), oldRate, newRate,
                before.payFreq, stdGross, userId);
        }

        // paemaud full before/after snapshot of the employee
        rowAudit.auditEmployee(companyNo, r.employeeNo(),
            MasterFileAuditService.MAINT_MODIFY, before, after, userId);
    }

    // ── Default-timesheet recalc (mirrors UPDATE-DEFAULT-TIMESHEETS) ─────

    /** Running pay-type totals used to rebuild super ext_amt. */
    private record SuperTotals(BigDecimal grossSum, Long superLineNo, Long employerSuperLineNo) {}

    private void updateDefaultTimesheets(int companyNo, int employeeNo,
                                          BigDecimal oldRate, BigDecimal newRate,
                                          String payFreq, BigDecimal stdGross,
                                          String userId) {
        // Load every paecode line for this employee joined with pacodes for
        // the per-type flags (pay_factor + pay_usual_paid_flag).
        List<PaeLine> lines = jdbc.query(
            "SELECT pe.line_no, pe.pay_type, pe.pay_code, pe.min, pe.qty, pe.rate_perc, " +
            "       pe.ext_amt, pe.paygroup, pe.dept, pe.award, pe.job_class, " +
            "       COALESCE(pc.pay_factor, 0) AS pay_factor, " +
            "       COALESCE(pc.pay_usual_paid_flag, '') AS usual_flag " +
            "FROM paecode pe " +
            "LEFT JOIN pacodes pc ON pc.company_no = pe.company_no " +
            "                    AND pc.pay_code   = pe.pay_code " +
            "WHERE pe.company_no=? AND pe.employee_no=?",
            (rs, i) -> new PaeLine(
                rs.getInt("line_no"), rs.getInt("pay_type"),
                rs.getString("pay_code"), rs.getInt("min"),
                rs.getBigDecimal("qty"), rs.getBigDecimal("rate_perc"),
                rs.getBigDecimal("ext_amt"),
                rs.getString("paygroup"), rs.getString("dept"),
                rs.getString("award"), rs.getString("job_class"),
                rs.getBigDecimal("pay_factor"), rs.getString("usual_flag")),
            companyNo, employeeNo);

        BigDecimal totalNormal = BigDecimal.ZERO;
        BigDecimal totalOther  = BigDecimal.ZERO;
        BigDecimal totalLsl    = BigDecimal.ZERO;
        BigDecimal totalAl     = BigDecimal.ZERO;
        BigDecimal totalSick   = BigDecimal.ZERO;
        Integer superLine     = null;
        Integer employerSuper = null;

        for (PaeLine l : lines) {
            // Pay types 1/2/3 with current rate equal to OLD pastaff rate get
            // rewritten (with or without pay_factor).
            if ((l.payType == 1 || l.payType == 2 || l.payType == 3)
                && l.ratePerc != null && l.ratePerc.compareTo(oldRate) == 0) {
                BigDecimal newLineRate = newRate;
                if (l.payFactor != null && l.payFactor.signum() > 0) {
                    newLineRate = newLineRate.multiply(l.payFactor)
                        .setScale(4, RoundingMode.HALF_UP);
                }
                BigDecimal newExt = l.min == 0
                    ? l.extAmt
                    : BigDecimal.valueOf(l.min).multiply(newLineRate)
                        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

                // papcaud snapshot of OLD values, then update
                rowAudit.auditPaeCode(
                    new MasterFileAuditService.PaeCodeSnapshot(
                        companyNo, employeeNo, l.payType, nz(l.payCode),
                        l.min, l.qty, l.ratePerc, l.extAmt,
                        nz(l.paygroup), nz(l.dept),
                        nz(l.award), nz(l.jobClass)),
                    MasterFileAuditService.MAINT_MODIFY, userId);

                jdbc.update(
                    "UPDATE paecode SET rate_perc=?, ext_amt=?, " +
                    "audit_user_id=?, audit_date=CURRENT_DATE(), " +
                    "audit_time_hr=HOUR(NOW()), audit_time_min=MINUTE(NOW()), " +
                    "audit_time_sec=SECOND(NOW()), audit_time_hun=0 " +
                    "WHERE company_no=? AND employee_no=? AND line_no=?",
                    newLineRate, newExt,
                    safeUser(userId), companyNo, employeeNo, l.lineNo);

                l.ratePerc = newLineRate;
                l.extAmt   = newExt;
            }

            // Accumulate type-bucket totals for super recalc (mirrors COBOL)
            switch (l.payType) {
                case 1  -> totalNormal = totalNormal.add(nz(l.extAmt));
                case 3  -> totalOther  = totalOther.add(nz(l.extAmt));
                case 4  -> totalLsl    = totalLsl.add(nz(l.extAmt));
                case 5  -> totalAl     = totalAl.add(nz(l.extAmt));
                case 7  -> totalSick   = totalSick.add(nz(l.extAmt));
                case 17 -> superLine     = l.lineNo;
                case 20 -> employerSuper = l.lineNo;
                default -> {}
            }
        }

        BigDecimal superGross = totalNormal.add(totalOther).add(totalLsl).add(totalAl).add(totalSick);
        recalcSuperLine(companyNo, employeeNo, superLine, superGross, userId);
        recalcSuperLine(companyNo, employeeNo, employerSuper, superGross, userId);
    }

    private void recalcSuperLine(int companyNo, int employeeNo, Integer lineNo,
                                  BigDecimal grossAmt, String userId) {
        if (lineNo == null) return;
        // Fetch the rate, recalc ext = rate * gross / 100
        List<BigDecimal> ratePercList = jdbc.query(
            "SELECT rate_perc FROM paecode WHERE company_no=? AND employee_no=? AND line_no=?",
            (rs, i) -> rs.getBigDecimal("rate_perc"),
            companyNo, employeeNo, lineNo);
        if (ratePercList.isEmpty()) return;
        BigDecimal rate = ratePercList.get(0);
        BigDecimal newExt = rate.multiply(grossAmt)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        jdbc.update(
            "UPDATE paecode SET ext_amt=?, " +
            "audit_user_id=?, audit_date=CURRENT_DATE(), " +
            "audit_time_hr=HOUR(NOW()), audit_time_min=MINUTE(NOW()), " +
            "audit_time_sec=SECOND(NOW()), audit_time_hun=0 " +
            "WHERE company_no=? AND employee_no=? AND line_no=?",
            newExt, safeUser(userId), companyNo, employeeNo, lineNo);
    }

    // ── Internals ────────────────────────────────────────────────────────

    /** Lightweight POJO for paecode rows we process in memory. */
    private static final class PaeLine {
        final int        lineNo;
        final int        payType;
        final String     payCode;
        final int        min;
        final BigDecimal qty;
        BigDecimal       ratePerc;
        BigDecimal       extAmt;
        final String     paygroup;
        final String     dept;
        final String     award;
        final String     jobClass;
        final BigDecimal payFactor;
        final String     usualFlag;
        PaeLine(int lineNo, int payType, String payCode, int min,
                BigDecimal qty, BigDecimal ratePerc, BigDecimal extAmt,
                String paygroup, String dept, String award, String jobClass,
                BigDecimal payFactor, String usualFlag) {
            this.lineNo = lineNo; this.payType = payType; this.payCode = payCode;
            this.min = min; this.qty = qty; this.ratePerc = ratePerc;
            this.extAmt = extAmt; this.paygroup = paygroup; this.dept = dept;
            this.award = award; this.jobClass = jobClass;
            this.payFactor = payFactor; this.usualFlag = usualFlag;
        }
    }

    private BigDecimal[] readActualPaidRateAndEmployerAmt(int companyNo, int employeeNo) {
        List<BigDecimal[]> hits = jdbc.query(
            "SELECT actual_paid_rate, employer_amt FROM pastaff " +
            "WHERE company_no=? AND employee_no=?",
            (rs, i) -> new BigDecimal[] {
                rs.getBigDecimal("actual_paid_rate") == null ? BigDecimal.ZERO : rs.getBigDecimal("actual_paid_rate"),
                rs.getBigDecimal("employer_amt")     == null ? BigDecimal.ZERO : rs.getBigDecimal("employer_amt")
            },
            companyNo, employeeNo);
        return hits.isEmpty() ? new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO} : hits.get(0);
    }

    private static Employee cloneShallow(Employee e) {
        Employee c = new Employee();
        c.companyNo        = e.companyNo;
        c.employeeNo       = e.employeeNo;
        c.surname          = e.surname;
        c.firstName        = e.firstName;
        c.secondName       = e.secondName;
        c.addr1            = e.addr1;
        c.addr2            = e.addr2;
        c.city             = e.city;
        c.state            = e.state;
        c.postcode         = e.postcode;
        c.phoneArea        = e.phoneArea;
        c.phoneNo          = e.phoneNo;
        c.mobile           = e.mobile;
        c.emailAddress     = e.emailAddress;
        c.dept             = e.dept;
        c.paygroup         = e.paygroup;
        c.employeeStatus   = e.employeeStatus;
        c.employeeType     = e.employeeType;
        c.dateStarted      = e.dateStarted;
        c.dateTerminated   = e.dateTerminated;
        c.payFreq          = e.payFreq;
        c.award            = e.award;
        c.jobClass         = e.jobClass;
        c.annualSalary     = e.annualSalary;
        c.stdHrs           = e.stdHrs;
        c.stdRatePerHr     = e.stdRatePerHr;
        c.taxFileNo        = e.taxFileNo;
        c.taxScaleNo       = e.taxScaleNo;
        c.extraTaxAmt      = e.extraTaxAmt;
        c.superCode        = e.superCode;
        c.superMemberNo    = e.superMemberNo;
        c.superCommDate    = e.superCommDate;
        c.qualifyDays      = e.qualifyDays;
        c.forcePayFlag     = e.forcePayFlag;
        c.useExtSuperFlag  = e.useExtSuperFlag;
        c.sex              = e.sex;
        c.dateOfBirth      = e.dateOfBirth;
        return c;
    }

    private static String joinName(String surname, String firstName) {
        String s = nz(firstName).trim();
        String f = nz(surname).trim();
        if (s.isEmpty() && f.isEmpty()) return "(unnamed)";
        if (s.isEmpty()) return f;
        if (f.isEmpty()) return s;
        return s + " " + f;
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String safeUser(String s) {
        if (s == null) return "";
        return s.length() > 15 ? s.substring(0, 15) : s;
    }

    private static String blankToLo(String s) { return (s == null || s.trim().isEmpty()) ? "" : s.trim().toUpperCase(); }
    private static String blankToHi(String s, String hi) {
        return (s == null || s.trim().isEmpty()) ? hi : s.trim().toUpperCase();
    }
}
