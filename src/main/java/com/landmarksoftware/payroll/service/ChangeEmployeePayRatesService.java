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
import java.util.List;
import java.util.function.IntConsumer;

/**
 * PAEM60 — Change Employee Pay Rates.
 *
 * <p>Apply a percentage change to selected employees' std rate / actual
 * paid rate plus a subset of their default-timesheet (paecode) lines.
 *
 * <p>Range filters: employee, paygroup, dept, award, job_class.
 * Behaviour switches:
 * <ul>
 *   <li>{@code updateMasterFile} — touch pastaff (std_rate_per_hr,
 *       std_gross, annual_salary, actual_paid_rate)</li>
 *   <li>{@code includeNormal}    + normal pay-code range (type 1)</li>
 *   <li>{@code includeOvertime}  + overtime pay-code range (type 2)</li>
 *   <li>{@code includeOther}     + other pay-code range (type 3)</li>
 *   <li>{@code includeLeave}     + leave pay-code range (types 4-9)</li>
 * </ul>
 *
 * <p>Mirrors paem60.pl CHANGE-EMPLOYEE-PAY-RATE / CHECK-UPDATE-TSHEET-LINE
 * / CALCULATE-THE-SUPER. After the per-line updates super lines (type 17/20)
 * are rebuilt off the new gross.
 */
@Service
public class ChangeEmployeePayRatesService {

    public record Inputs(
        int    startEmployee, int endEmployee,
        String startPaygroup, String endPaygroup,
        String startDept,     String endDept,
        String startAward,    String endAward,
        String startJobClass, String endJobClass,
        BigDecimal percChange,
        boolean updateMasterFile,
        boolean includeNormal,   String normalStart,   String normalEnd,
        boolean includeOvertime, String overtimeStart, String overtimeEnd,
        boolean includeOther,    String otherStart,    String otherEnd,
        boolean includeLeave,    String leaveStart,    String leaveEnd) {}

    public record AffectedRow(
        int        employeeNo,
        String     employeeName,
        String     paygroup,
        String     dept,
        String     award,
        String     jobClass,
        BigDecimal oldRatePerHr,
        BigDecimal newRatePerHr) {}

    private final JdbcTemplate           jdbc;
    private final EmployeeService        employees;
    private final MasterFileAuditService rowAudit;
    private final BatchAuditService      batchAudit;

    public ChangeEmployeePayRatesService(JdbcTemplate jdbc,
                                          EmployeeService employees,
                                          MasterFileAuditService rowAudit,
                                          BatchAuditService batchAudit) {
        this.jdbc       = jdbc;
        this.employees  = employees;
        this.rowAudit   = rowAudit;
        this.batchAudit = batchAudit;
    }

    public List<AffectedRow> preview(int companyNo, Inputs in) {
        BigDecimal factor = BigDecimal.ONE.add(
            in.percChange().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        return jdbc.query(
            "SELECT employee_no, surname, first_name, paygroup, dept, award, job_class, " +
            "       std_rate_per_hr AS old_rate, " +
            "       ROUND(std_rate_per_hr * ?, 4) AS new_rate " +
            "FROM pastaff " +
            "WHERE company_no = ? " +
            "  AND employee_status <> 'T' " +
            "  AND employee_no BETWEEN ? AND ? " +
            "  AND paygroup    BETWEEN ? AND ? " +
            "  AND dept        BETWEEN ? AND ? " +
            "  AND award       BETWEEN ? AND ? " +
            "  AND job_class   BETWEEN ? AND ? " +
            "ORDER BY employee_no",
            (rs, i) -> new AffectedRow(
                rs.getInt("employee_no"),
                joinName(rs.getString("surname"), rs.getString("first_name")),
                rs.getString("paygroup"),
                rs.getString("dept"),
                rs.getString("award"),
                rs.getString("job_class"),
                rs.getBigDecimal("old_rate"),
                rs.getBigDecimal("new_rate")),
            factor, companyNo,
            in.startEmployee() == 0 ? 0 : in.startEmployee(),
            in.endEmployee()   == 0 ? 999999 : in.endEmployee(),
            blankLo(in.startPaygroup()), blankHi(in.endPaygroup(), "zzzz"),
            blankLo(in.startDept()),     blankHi(in.endDept(),     "zzzz"),
            blankLo(in.startAward()),    blankHi(in.endAward(),    "zzz"),
            blankLo(in.startJobClass()), blankHi(in.endJobClass(), "zzzzzz"));
    }

    @Transactional
    public int apply(int companyNo, Inputs in, String userId, IntConsumer progress) {
        long auditId = batchAudit.start(companyNo, userId, "PAEM60",
            "Change Employee Pay Rates by " + in.percChange() + "% emp " +
            in.startEmployee() + ".." + in.endEmployee() +
            "  (master=" + yn(in.updateMasterFile()) +
            ",N=" + yn(in.includeNormal()) +
            ",OT=" + yn(in.includeOvertime()) +
            ",O=" + yn(in.includeOther()) +
            ",L=" + yn(in.includeLeave()) + ")",
            BatchAuditService.STATUS_RUNNING);
        try {
            List<AffectedRow> rows = preview(companyNo, in);
            int n = 0;
            for (AffectedRow r : rows) {
                applyOne(companyNo, r, in, userId);
                n++;
                if (progress != null) progress.accept(n);
            }
            batchAudit.complete(auditId, n);
            return n;
        } catch (RuntimeException ex) {
            batchAudit.fail(auditId, ex.toString());
            throw ex;
        }
    }

    // ── per-employee apply ───────────────────────────────────────────────

    private void applyOne(int companyNo, AffectedRow r, Inputs in, String userId) {
        Employee before = employees.findOne(companyNo, r.employeeNo()).orElse(null);
        if (before == null) return;
        Employee after  = shallowCopy(before);

        BigDecimal factor = BigDecimal.ONE.add(
            in.percChange().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));

        BigDecimal newRate    = before.stdRatePerHr.multiply(factor).setScale(4, RoundingMode.HALF_UP);
        BigDecimal newGross   = BigDecimal.valueOf(before.stdHrs).multiply(newRate)
            .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        BigDecimal newSalary  = newGross.multiply(BigDecimal.valueOf(52)).setScale(2, RoundingMode.HALF_UP);

        BigDecimal actualPaid = readActualPaidRate(companyNo, r.employeeNo());
        BigDecimal newActual  = actualPaid;
        if (actualPaid.signum() > 0) {
            newActual = actualPaid.multiply(factor).setScale(4, RoundingMode.HALF_UP);
        }

        if (in.updateMasterFile()) {
            jdbc.update(
                "UPDATE pastaff SET std_rate_per_hr=?, std_gross=?, annual_salary=?, " +
                "actual_paid_rate=?, " +
                "audit_user_id=?, audit_date=CURRENT_DATE(), " +
                "audit_time_hr=HOUR(NOW()), audit_time_min=MINUTE(NOW()), " +
                "audit_time_sec=SECOND(NOW()), audit_time_hun=0 " +
                "WHERE company_no=? AND employee_no=?",
                newRate, newGross, newSalary, newActual,
                safeUser(userId), companyNo, r.employeeNo());

            after.stdRatePerHr = newRate;
            after.annualSalary = newSalary;

            rowAudit.auditPaeCode(
                new MasterFileAuditService.PaeCodeSnapshot(
                    companyNo, r.employeeNo(), 1, "",
                    before.stdHrs, BigDecimal.ZERO, newRate, newGross,
                    nz(before.paygroup), nz(before.dept),
                    nz(before.award), nz(before.jobClass)),
                MasterFileAuditService.MAINT_MODIFY, userId);
            rowAudit.auditEmployee(companyNo, r.employeeNo(),
                MasterFileAuditService.MAINT_MODIFY, before, after, userId);
        }

        if (in.includeNormal() || in.includeOvertime()
            || in.includeOther() || in.includeLeave()) {
            updateTimesheetLines(companyNo, r.employeeNo(), in, factor,
                newRate, newActual, userId);
            recalcSuperLines(companyNo, r.employeeNo(), userId);
        }
    }

    private void updateTimesheetLines(int companyNo, int employeeNo,
                                       Inputs in, BigDecimal factor,
                                       BigDecimal newStdRate, BigDecimal newActualPaidRate,
                                       String userId) {
        List<PaeLine> lines = jdbc.query(
            "SELECT pe.line_no, pe.pay_type, pe.pay_code, pe.min, pe.qty, pe.rate_perc, " +
            "       pe.ext_amt, pe.paygroup, pe.dept, pe.award, pe.job_class, " +
            "       COALESCE(pc.pay_factor, 0)              AS pc_factor, " +
            "       COALESCE(pc.pay_usual_paid_flag, '')    AS pc_usual " +
            "FROM paecode pe " +
            "LEFT JOIN pacodes pc ON pc.company_no=pe.company_no AND pc.pay_code=pe.pay_code " +
            "WHERE pe.company_no=? AND pe.employee_no=?",
            (rs, i) -> new PaeLine(
                rs.getInt("line_no"), rs.getInt("pay_type"),
                rs.getString("pay_code"), rs.getInt("min"),
                rs.getBigDecimal("qty"), rs.getBigDecimal("rate_perc"),
                rs.getBigDecimal("ext_amt"),
                rs.getString("paygroup"), rs.getString("dept"),
                rs.getString("award"), rs.getString("job_class"),
                rs.getBigDecimal("pc_factor"), rs.getString("pc_usual")),
            companyNo, employeeNo);

        for (PaeLine l : lines) {
            if (!lineEligible(l, in)) continue;

            BigDecimal newLineRate;
            if ((l.payType == 1 || l.payType == 2 || l.payType == 3)
                && "P".equalsIgnoreCase(l.pcUsual)) {
                // Special: use actual_paid_rate × pay_factor (or just it if =0)
                newLineRate = newActualPaidRate;
                if (l.pcFactor != null && l.pcFactor.signum() > 0) {
                    newLineRate = newLineRate.multiply(l.pcFactor)
                        .setScale(4, RoundingMode.HALF_UP);
                }
            } else {
                newLineRate = l.ratePerc.multiply(factor).setScale(4, RoundingMode.HALF_UP);
            }
            BigDecimal newExt = l.min == 0
                ? l.extAmt
                : BigDecimal.valueOf(l.min).multiply(newLineRate)
                    .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

            rowAudit.auditPaeCode(
                new MasterFileAuditService.PaeCodeSnapshot(
                    companyNo, employeeNo, l.payType, nz(l.payCode),
                    l.min, l.qty, l.ratePerc, l.extAmt,
                    nz(l.paygroup), nz(l.dept), nz(l.award), nz(l.jobClass)),
                MasterFileAuditService.MAINT_MODIFY, userId);

            jdbc.update(
                "UPDATE paecode SET rate_perc=?, ext_amt=?, " +
                "audit_user_id=?, audit_date=CURRENT_DATE(), " +
                "audit_time_hr=HOUR(NOW()), audit_time_min=MINUTE(NOW()), " +
                "audit_time_sec=SECOND(NOW()), audit_time_hun=0 " +
                "WHERE company_no=? AND employee_no=? AND line_no=?",
                newLineRate, newExt, safeUser(userId),
                companyNo, employeeNo, l.lineNo);
        }
    }

    private boolean lineEligible(PaeLine l, Inputs in) {
        String code = nz(l.payCode);
        if (l.payType == 1 && in.includeNormal())
            return inRange(code, in.normalStart(),   in.normalEnd(),   "zzzzzz");
        if (l.payType == 2 && in.includeOvertime())
            return inRange(code, in.overtimeStart(), in.overtimeEnd(), "zzzzzz");
        if (l.payType == 3 && in.includeOther())
            return inRange(code, in.otherStart(),    in.otherEnd(),    "zzzzzz");
        if (l.payType >= 4 && l.payType <= 9 && in.includeLeave())
            return inRange(code, in.leaveStart(),    in.leaveEnd(),    "zzzzzz");
        return false;
    }

    private void recalcSuperLines(int companyNo, int employeeNo, String userId) {
        // Sum new ext_amt of all "super-flagged" lines that ARE NOT super/dedn
        BigDecimal grossAmt = jdbc.queryForObject(
            "SELECT COALESCE(SUM(pe.ext_amt), 0) FROM paecode pe " +
            "LEFT JOIN pacodes pc ON pc.company_no=pe.company_no AND pc.pay_code=pe.pay_code " +
            "WHERE pe.company_no=? AND pe.employee_no=? " +
            "  AND pc.super_flag='Y' " +
            "  AND (pe.pay_type < 15 OR pe.pay_type = 19 OR pe.pay_type = 21)",
            BigDecimal.class, companyNo, employeeNo);
        if (grossAmt == null) grossAmt = BigDecimal.ZERO;

        List<int[]> superLines = jdbc.query(
            "SELECT line_no FROM paecode WHERE company_no=? AND employee_no=? " +
            "AND pay_type IN (17, 20)",
            (rs, i) -> new int[] { rs.getInt("line_no") },
            companyNo, employeeNo);
        for (int[] holder : superLines) {
            int lineNo = holder[0];
            List<BigDecimal> rateList = jdbc.query(
                "SELECT rate_perc FROM paecode WHERE company_no=? AND employee_no=? AND line_no=?",
                (rs, i) -> rs.getBigDecimal("rate_perc"),
                companyNo, employeeNo, lineNo);
            if (rateList.isEmpty()) continue;
            BigDecimal rate = rateList.get(0);
            if (rate.signum() == 0) continue;
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
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static final class PaeLine {
        final int lineNo, payType, min;
        final String payCode;
        final BigDecimal qty;
        BigDecimal ratePerc;
        BigDecimal extAmt;
        final String paygroup, dept, award, jobClass;
        final BigDecimal pcFactor;
        final String pcUsual;
        PaeLine(int lineNo, int payType, String payCode, int min,
                BigDecimal qty, BigDecimal ratePerc, BigDecimal extAmt,
                String paygroup, String dept, String award, String jobClass,
                BigDecimal pcFactor, String pcUsual) {
            this.lineNo=lineNo; this.payType=payType; this.payCode=payCode;
            this.min=min; this.qty=qty; this.ratePerc=ratePerc; this.extAmt=extAmt;
            this.paygroup=paygroup; this.dept=dept; this.award=award; this.jobClass=jobClass;
            this.pcFactor=pcFactor; this.pcUsual=pcUsual;
        }
    }

    private BigDecimal readActualPaidRate(int companyNo, int employeeNo) {
        List<BigDecimal> hits = jdbc.query(
            "SELECT actual_paid_rate FROM pastaff WHERE company_no=? AND employee_no=?",
            (rs, i) -> rs.getBigDecimal("actual_paid_rate"),
            companyNo, employeeNo);
        return hits.isEmpty() || hits.get(0) == null ? BigDecimal.ZERO : hits.get(0);
    }

    private static boolean inRange(String value, String lo, String hi, String defaultHi) {
        String a = blankLo(lo);
        String b = (hi == null || hi.trim().isEmpty()) ? defaultHi : hi.trim().toUpperCase();
        String v = value == null ? "" : value;
        return v.compareTo(a) >= 0 && v.compareTo(b) <= 0;
    }

    private static Employee shallowCopy(Employee e) {
        Employee c = new Employee();
        c.companyNo=e.companyNo; c.employeeNo=e.employeeNo;
        c.surname=e.surname; c.firstName=e.firstName; c.secondName=e.secondName;
        c.addr1=e.addr1; c.addr2=e.addr2; c.city=e.city; c.state=e.state; c.postcode=e.postcode;
        c.phoneArea=e.phoneArea; c.phoneNo=e.phoneNo; c.mobile=e.mobile; c.emailAddress=e.emailAddress;
        c.dept=e.dept; c.paygroup=e.paygroup; c.employeeStatus=e.employeeStatus;
        c.employeeType=e.employeeType; c.dateStarted=e.dateStarted; c.dateTerminated=e.dateTerminated;
        c.payFreq=e.payFreq; c.award=e.award; c.jobClass=e.jobClass;
        c.annualSalary=e.annualSalary; c.stdHrs=e.stdHrs; c.stdRatePerHr=e.stdRatePerHr;
        c.taxFileNo=e.taxFileNo; c.taxScaleNo=e.taxScaleNo; c.extraTaxAmt=e.extraTaxAmt;
        c.superCode=e.superCode; c.superMemberNo=e.superMemberNo;
        c.superCommDate=e.superCommDate; c.qualifyDays=e.qualifyDays;
        c.forcePayFlag=e.forcePayFlag; c.useExtSuperFlag=e.useExtSuperFlag;
        c.sex=e.sex; c.dateOfBirth=e.dateOfBirth;
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
    private static String safeUser(String s) {
        if (s == null) return "";
        return s.length() > 15 ? s.substring(0, 15) : s;
    }
    private static String blankLo(String s) { return (s == null || s.trim().isEmpty()) ? "" : s.trim().toUpperCase(); }
    private static String blankHi(String s, String hi) {
        return (s == null || s.trim().isEmpty()) ? hi : s.trim().toUpperCase();
    }
    private static String yn(boolean b) { return b ? "Y" : "N"; }
}
