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
 * PASU15 — Global Employee Award Update.
 *
 * <p>Walks every pastaff row in the user-selected employee range and, for
 * each one, conditionally pushes paawjob values down to the employee + the
 * employee's default timesheet (paecode). Differs from PASU11 in that it
 * is <em>not</em> driven by paawchg — the user picks an employee range
 * directly and chooses which of {rate, salary, timesheets, super} to
 * refresh.
 *
 * <p>Mirrors pasu15.pl UPDATE-EMPLOYEE / CHECK-UPDATE-TSHEET-LINE / etc.
 *
 * <p>Filter inputs (each accepts blank/zero = full range):
 * <ul>
 *   <li>employee_no range</li>
 *   <li>pacodes.type range (e.g. 1..21)</li>
 *   <li>paecode.pay_code range</li>
 *   <li>award + job_class range</li>
 * </ul>
 *
 * <p>Behaviour switches (Y/N):
 * <ul>
 *   <li>{@code includeOverAward} — touch employees whose over_award_flag='Y'</li>
 *   <li>{@code updateStdRate}    — push paawjob.std_hrs / rate_per_hr / rate_per_week
 *       into pastaff</li>
 *   <li>{@code updateAnnualSalary} — push paawjob.annual_amt into pastaff</li>
 *   <li>{@code updateTimesheets} — refresh default timesheet rows in paecode</li>
 *   <li>{@code recalcSuper}      — rebuild super ext_amt off the new gross
 *       (only applies when updateTimesheets=Y)</li>
 * </ul>
 */
@Service
public class GlobalEmployeeAwardUpdateService {

    /** Caller bundles all 10 PASU15 inputs in one record. */
    public record Inputs(
        int    startEmployee, int endEmployee,
        int    startPayCodeType, int endPayCodeType,
        String startPayCode, String endPayCode,
        String startAward, String endAward,
        String startJobClass, String endJobClass,
        boolean includeOverAward,
        boolean updateStdRate,
        boolean updateAnnualSalary,
        boolean updateTimesheets,
        boolean recalcSuper) {}

    /** Preview row — one employee that would be touched. */
    public record AffectedRow(
        int        employeeNo,
        String     employeeName,
        String     award,
        String     jobClass,
        String     employeeType,
        boolean    overAwardCarried,
        BigDecimal oldRatePerHr,
        BigDecimal newRatePerHr,
        BigDecimal oldAnnualSalary,
        BigDecimal newAnnualSalary) {}

    private final JdbcTemplate           jdbc;
    private final EmployeeService        employees;
    private final MasterFileAuditService rowAudit;
    private final BatchAuditService      batchAudit;

    public GlobalEmployeeAwardUpdateService(JdbcTemplate jdbc,
                                             EmployeeService employees,
                                             MasterFileAuditService rowAudit,
                                             BatchAuditService batchAudit) {
        this.jdbc       = jdbc;
        this.employees  = employees;
        this.rowAudit   = rowAudit;
        this.batchAudit = batchAudit;
    }

    /**
     * Return all employees the apply step would touch. Read-only.
     * Filters by employee range + award/job-class range + over-award flag.
     * (pay-code type / pay-code ranges apply to paecode lines and only
     * influence the per-row update step.)
     */
    public List<AffectedRow> preview(int companyNo, Inputs in) {
        return jdbc.query(
            "SELECT s.employee_no, s.surname, s.first_name, s.employee_type, " +
            "       s.award, s.job_class, s.over_award_flag, " +
            "       s.std_rate_per_hr AS old_rate, s.annual_salary AS old_salary, " +
            "       COALESCE(j.rate_per_hr, s.std_rate_per_hr) AS new_rate, " +
            "       COALESCE(j.annual_amt,  s.annual_salary)   AS new_salary " +
            "FROM pastaff s " +
            "LEFT JOIN paawjob j ON j.company_no = s.company_no " +
            "                  AND j.award_code = s.award " +
            "                  AND j.job_class_code = s.job_class " +
            "WHERE s.company_no = ? " +
            "  AND s.employee_no BETWEEN ? AND ? " +
            "  AND s.award      BETWEEN ? AND ? " +
            "  AND s.job_class  BETWEEN ? AND ? " +
            "  AND s.employee_status <> 'T' " +
            "  AND (s.over_award_flag <> 'Y' OR ?) " +
            "ORDER BY s.employee_no",
            (rs, i) -> new AffectedRow(
                rs.getInt("employee_no"),
                joinName(rs.getString("surname"), rs.getString("first_name")),
                rs.getString("award"),
                rs.getString("job_class"),
                rs.getString("employee_type"),
                "Y".equals(rs.getString("over_award_flag")),
                rs.getBigDecimal("old_rate"),
                rs.getBigDecimal("new_rate"),
                rs.getBigDecimal("old_salary"),
                rs.getBigDecimal("new_salary")),
            companyNo,
            in.startEmployee() == 0 ? 0 : in.startEmployee(),
            in.endEmployee()   == 0 ? 999999 : in.endEmployee(),
            blankToLo(in.startAward()),     blankToHi(in.endAward(), "zzz"),
            blankToLo(in.startJobClass()),  blankToHi(in.endJobClass(), "zzzzzz"),
            in.includeOverAward());
    }

    @Transactional
    public int apply(int companyNo, Inputs in, String userId, IntConsumer progress) {
        long auditId = batchAudit.start(companyNo, userId, "PASU15",
            "Global Employee Award Update emp " +
            in.startEmployee() + ".." + in.endEmployee() +
            " awards " + nz(in.startAward()) + ".." + nz(in.endAward()) +
            " (rate=" + yn(in.updateStdRate()) +
            ",salary=" + yn(in.updateAnnualSalary()) +
            ",tsheet=" + yn(in.updateTimesheets()) +
            ",super=" + yn(in.recalcSuper()) + ")",
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

    // ── Per-employee apply (UPDATE-EMPLOYEE in COBOL) ────────────────────

    private void applyOne(int companyNo, AffectedRow r, Inputs in, String userId) {
        Employee before = employees.findOne(companyNo, r.employeeNo()).orElse(null);
        if (before == null) return;
        Employee after  = shallowCopy(before);

        // Read the matching paawjob row + the pastaff fields we'll need
        PaawJobRefresh job = readPaawJob(companyNo, r.award(), r.jobClass());

        // 1. Update pastaff (rate/hrs/gross + annual salary)
        boolean pastaffChanged = false;
        BigDecimal newRate   = before.stdRatePerHr;
        int        newHrs    = before.stdHrs;
        BigDecimal newGross  = before.stdRatePerHr.multiply(BigDecimal.valueOf(before.stdHrs))
            .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        BigDecimal newSalary = before.annualSalary;

        if (in.updateStdRate() && job != null) {
            newRate  = job.ratePerHr();
            newHrs   = job.stdHrs();
            newGross = job.ratePerWeek();
            pastaffChanged = true;
        }
        if (in.updateAnnualSalary() && job != null) {
            newSalary = job.annualAmt();
            pastaffChanged = true;
        }

        if (pastaffChanged) {
            jdbc.update(
                "UPDATE pastaff SET std_hrs=?, std_rate_per_hr=?, std_gross=?, " +
                "annual_salary=?, " +
                "audit_user_id=?, audit_date=CURRENT_DATE(), " +
                "audit_time_hr=HOUR(NOW()), audit_time_min=MINUTE(NOW()), " +
                "audit_time_sec=SECOND(NOW()), audit_time_hun=0 " +
                "WHERE company_no=? AND employee_no=?",
                newHrs, newRate, newGross, newSalary,
                safeUser(userId), companyNo, r.employeeNo());

            after.stdHrs       = newHrs;
            after.stdRatePerHr = newRate;
            after.annualSalary = newSalary;

            // papcaud "pastaff-changed" marker mirroring COBOL AUDIT-EMPLOYEE
            rowAudit.auditPaeCode(
                new MasterFileAuditService.PaeCodeSnapshot(
                    companyNo, r.employeeNo(), 1, "",
                    newHrs, BigDecimal.ZERO, newRate, newGross,
                    nz(before.paygroup), nz(before.dept),
                    nz(before.award), nz(before.jobClass)),
                MasterFileAuditService.MAINT_MODIFY, userId);
            rowAudit.auditEmployee(companyNo, r.employeeNo(),
                MasterFileAuditService.MAINT_MODIFY, before, after, userId);
        }

        // 2. Update default timesheets — paecode lines for this employee
        if (in.updateTimesheets()) {
            BigDecimal superGross = updateTimesheetLines(
                companyNo, r.employeeNo(), in, job, userId);

            // 3. Recalc super off the new gross if requested
            if (in.recalcSuper()) {
                recalcSuperLines(companyNo, r.employeeNo(), superGross, userId);
            }
        }
    }

    // ── Default timesheet update (UPDATE-TSHEET-LINE) ───────────────────

    /**
     * Walk the employee's paecode rows. For each row whose pay_code matches
     * the user's pay_code range AND whose pacodes.type matches the type
     * range AND (type<15 or =19 → award match required, else any award),
     * push the new rate/amount per the COBOL rules:
     * <ul>
     *   <li>type 1-3: pacodes.pay_rate &gt; 0 wins; else pay_factor &lt;=&gt; rate</li>
     *   <li>type 10-11: pacodes.allow_amt &gt; 0 wins; else allow_rate × qty</li>
     *   <li>type 15-16: pacodes.dedn_amt overrides</li>
     *   <li>type 17/20: super_employee_perc overrides rate_perc</li>
     * </ul>
     * Returns the running gross for super recalc.
     */
    private BigDecimal updateTimesheetLines(int companyNo, int employeeNo,
                                             Inputs in, PaawJobRefresh job,
                                             String userId) {
        List<PaeLine> lines = jdbc.query(
            "SELECT pe.line_no, pe.pay_type, pe.pay_code, pe.min, pe.qty, pe.rate_perc, " +
            "       pe.ext_amt, pe.paygroup, pe.dept, pe.award, pe.job_class, " +
            "       COALESCE(pc.type, pe.pay_type)        AS pc_type, " +
            "       COALESCE(pc.pay_rate, 0)              AS pc_pay_rate, " +
            "       COALESCE(pc.pay_factor, 0)            AS pc_pay_factor, " +
            "       COALESCE(pc.allow_rate, 0)            AS pc_allow_rate, " +
            "       COALESCE(pc.allow_amt, 0)             AS pc_allow_amt, " +
            "       COALESCE(pc.allow_unit_per_desc,'')   AS pc_allow_unit, " +
            "       COALESCE(pc.dedn_amt, 0)              AS pc_dedn_amt, " +
            "       COALESCE(pc.super_employee_perc, 0)   AS pc_super_perc, " +
            "       COALESCE(pc.super_flag, 'N')          AS pc_super_flag " +
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
                rs.getInt("pc_type"), rs.getBigDecimal("pc_pay_rate"),
                rs.getBigDecimal("pc_pay_factor"), rs.getBigDecimal("pc_allow_rate"),
                rs.getBigDecimal("pc_allow_amt"), rs.getString("pc_allow_unit"),
                rs.getBigDecimal("pc_dedn_amt"), rs.getBigDecimal("pc_super_perc"),
                rs.getString("pc_super_flag")),
            companyNo, employeeNo);

        BigDecimal superGross = BigDecimal.ZERO;
        int startType = in.startPayCodeType() == 0 ? 0  : in.startPayCodeType();
        int endType   = in.endPayCodeType()   == 0 ? 99 : in.endPayCodeType();
        String startPc = blankToLo(in.startPayCode());
        String endPc   = blankToHi(in.endPayCode(), "zzzzzz");
        String startAw = blankToLo(in.startAward());
        String endAw   = blankToHi(in.endAward(), "zzz");
        String startJc = blankToLo(in.startJobClass());
        String endJc   = blankToHi(in.endJobClass(), "zzzzzz");

        for (PaeLine l : lines) {
            // Type-range filter
            if (l.pcType < startType || l.pcType > endType) continue;
            // Pay-code range
            if (cmp(nz(l.payCode), startPc) < 0 || cmp(nz(l.payCode), endPc) > 0) continue;
            // For most pay types we require the line's award/job_class to be
            // in range; types 15/16/17/20 always pass (deductions + super).
            boolean awardFiltered = !(l.pcType == 15 || l.pcType == 16 ||
                                       l.pcType == 17 || l.pcType == 20);
            if (awardFiltered) {
                if (cmp(nz(l.award), startAw) < 0 || cmp(nz(l.award), endAw) > 0) continue;
                if (cmp(nz(l.jobClass), startJc) < 0 || cmp(nz(l.jobClass), endJc) > 0) continue;
            }
            // For types <15 or =19 we need a matching paawjob to compute the
            // new rate. Without paawjob, fall through and only update the
            // tax/super/deduction types.
            if ((l.pcType < 15 || l.pcType == 19) && job == null) continue;

            // Snapshot OLD values for papcaud
            MasterFileAuditService.PaeCodeSnapshot before =
                new MasterFileAuditService.PaeCodeSnapshot(
                    companyNo, employeeNo, l.pcType, nz(l.payCode),
                    l.min, l.qty, l.ratePerc, l.extAmt,
                    nz(l.paygroup), nz(l.dept), nz(l.award), nz(l.jobClass));

            // Compute new rate + ext_amt based on pcType
            BigDecimal newRate = l.ratePerc;
            BigDecimal newExt  = l.extAmt;
            int        newMin  = l.min;

            if (l.pcType >= 1 && l.pcType <= 3) {
                if (l.pcPayRate.signum() > 0) {
                    newRate = l.pcPayRate;
                } else if (l.pcPayFactor.compareTo(BigDecimal.ONE) == 0) {
                    newRate = job.ratePerHr();
                } else if (l.pcPayFactor.signum() > 0) {
                    newRate = job.ratePerHr().multiply(l.pcPayFactor);
                }
                if (l.min != 0) {
                    newExt = BigDecimal.valueOf(l.min).multiply(newRate)
                        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
                }
            } else if (l.pcType == 10 || l.pcType == 11) {
                if (l.pcAllowAmt.signum() > 0) {
                    newExt  = l.pcAllowAmt;
                    newMin  = 0;
                    newRate = BigDecimal.ZERO;
                } else if (l.pcAllowRate.signum() > 0) {
                    newRate = l.pcAllowRate;
                    if (l.qty != null && l.qty.signum() != 0) {
                        if (!"HR".equalsIgnoreCase(l.pcAllowUnit)) {
                            newExt = l.qty.multiply(newRate);
                        } else if (l.qty.signum() > 0) {
                            newExt = l.qty.multiply(newRate);
                        } else {
                            newExt = l.qty.multiply(newRate)
                                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
                        }
                    }
                }
            } else if (l.pcType == 15 || l.pcType == 16) {
                if (l.pcDednAmt.signum() > 0) {
                    newExt  = l.pcDednAmt;
                    newMin  = 0;
                    newRate = BigDecimal.ZERO;
                }
            } else if (l.pcType == 17 || l.pcType == 20) {
                if (l.pcSuperPerc.signum() != 0
                    && l.pcSuperPerc.compareTo(l.ratePerc == null ? BigDecimal.ZERO : l.ratePerc) != 0
                    && l.ratePerc != null && l.ratePerc.signum() != 0) {
                    newRate = l.pcSuperPerc;
                }
            }

            // Apply if anything moved
            if (cmpBd(newRate, l.ratePerc) != 0 || cmpBd(newExt, l.extAmt) != 0 || newMin != l.min) {
                rowAudit.auditPaeCode(before, MasterFileAuditService.MAINT_MODIFY, userId);
                jdbc.update(
                    "UPDATE paecode SET rate_perc=?, ext_amt=?, min=?, " +
                    "audit_user_id=?, audit_date=CURRENT_DATE(), " +
                    "audit_time_hr=HOUR(NOW()), audit_time_min=MINUTE(NOW()), " +
                    "audit_time_sec=SECOND(NOW()), audit_time_hun=0 " +
                    "WHERE company_no=? AND employee_no=? AND line_no=?",
                    newRate, newExt, newMin, safeUser(userId),
                    companyNo, employeeNo, l.lineNo);
                l.ratePerc = newRate;
                l.extAmt   = newExt;
                l.min      = newMin;
            }

            // Accumulate for super recalc: super_flag=Y + type <15/19/21
            if ("Y".equalsIgnoreCase(l.pcSuperFlag)
                && (l.pcType < 15 || l.pcType == 19 || l.pcType == 21)) {
                superGross = superGross.add(nz(l.extAmt));
            }
        }
        return superGross;
    }

    /** Apply rate × gross / 100 to every super (17/20) paecode line. */
    private void recalcSuperLines(int companyNo, int employeeNo,
                                   BigDecimal grossAmt, String userId) {
        List<int[]> lines = jdbc.query(
            "SELECT line_no FROM paecode WHERE company_no=? AND employee_no=? " +
            "AND pay_type IN (17, 20)",
            (rs, i) -> new int[] { rs.getInt("line_no") },
            companyNo, employeeNo);
        for (int[] holder : lines) {
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

    private record PaawJobRefresh(int stdHrs, BigDecimal ratePerHr,
                                   BigDecimal ratePerWeek, BigDecimal annualAmt) {}

    private PaawJobRefresh readPaawJob(int companyNo, String award, String jobClass) {
        List<PaawJobRefresh> hits = jdbc.query(
            "SELECT std_hrs, rate_per_hr, rate_per_week, annual_amt " +
            "FROM paawjob WHERE company_no=? AND award_code=? AND job_class_code=?",
            (rs, i) -> new PaawJobRefresh(
                rs.getInt("std_hrs"),
                rs.getBigDecimal("rate_per_hr"),
                rs.getBigDecimal("rate_per_week"),
                rs.getBigDecimal("annual_amt")),
            companyNo, award, jobClass);
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static final class PaeLine {
        final int lineNo;
        final int payType;
        final String payCode;
        int min;
        final BigDecimal qty;
        BigDecimal ratePerc;
        BigDecimal extAmt;
        final String paygroup, dept, award, jobClass;
        final int pcType;
        final BigDecimal pcPayRate, pcPayFactor, pcAllowRate, pcAllowAmt;
        final String pcAllowUnit;
        final BigDecimal pcDednAmt, pcSuperPerc;
        final String pcSuperFlag;
        PaeLine(int lineNo, int payType, String payCode, int min,
                BigDecimal qty, BigDecimal ratePerc, BigDecimal extAmt,
                String paygroup, String dept, String award, String jobClass,
                int pcType, BigDecimal pcPayRate, BigDecimal pcPayFactor,
                BigDecimal pcAllowRate, BigDecimal pcAllowAmt, String pcAllowUnit,
                BigDecimal pcDednAmt, BigDecimal pcSuperPerc, String pcSuperFlag) {
            this.lineNo=lineNo; this.payType=payType; this.payCode=payCode;
            this.min=min; this.qty=qty; this.ratePerc=ratePerc; this.extAmt=extAmt;
            this.paygroup=paygroup; this.dept=dept; this.award=award; this.jobClass=jobClass;
            this.pcType=pcType; this.pcPayRate=pcPayRate; this.pcPayFactor=pcPayFactor;
            this.pcAllowRate=pcAllowRate; this.pcAllowAmt=pcAllowAmt; this.pcAllowUnit=pcAllowUnit;
            this.pcDednAmt=pcDednAmt; this.pcSuperPerc=pcSuperPerc; this.pcSuperFlag=pcSuperFlag;
        }
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
    private static String nz(String s)            { return s == null ? "" : s; }
    private static BigDecimal nz(BigDecimal v)    { return v == null ? BigDecimal.ZERO : v; }
    private static int cmp(String a, String b)    { return nz(a).compareTo(nz(b)); }
    private static int cmpBd(BigDecimal a, BigDecimal b) { return nz(a).compareTo(nz(b)); }
    private static String safeUser(String s) {
        if (s == null) return "";
        return s.length() > 15 ? s.substring(0, 15) : s;
    }
    private static String blankToLo(String s) { return (s == null || s.trim().isEmpty()) ? "" : s.trim().toUpperCase(); }
    private static String blankToHi(String s, String hi) {
        return (s == null || s.trim().isEmpty()) ? hi : s.trim().toUpperCase();
    }
    private static String yn(boolean b) { return b ? "Y" : "N"; }
}
