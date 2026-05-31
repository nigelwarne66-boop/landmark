package com.landmarksoftware.service.py;

import com.landmarksoftware.model.AppSession;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.landmarksoftware.db.tables.Gldates.GLDATES;
import static com.landmarksoftware.db.tables.Paehist.PAEHIST;
import static com.landmarksoftware.db.tables.Pastaff.PASTAFF;

/**
 * Payroll data service.
 *
 * Correct table names (confirmed from SQL DDL):
 *   pastaff  — Employee master (was PYEMPLO)
 *              PK: (company_no, employee_no)
 *              employee_status: 'A'=active, 'T'=terminated etc.
 *              dept (VARCHAR 4, not dept_code), email_address, date_started
 *
 *   parunhd  — Payrun header (was PYPAYRUN)
 *              PK: (company_no, payrun_no)
 *              yr_no = fiscal year (joins to gldates.yr_no)
 *              payrun_date, payrun_status, no_of_employees_paid
 *              NO pay amount columns — amounts are in paehist
 *
 *   paehist  — Payroll history detail (line level)
 *              PK: (company_no, employee_no, payrun_date, pay_type, pay_code, payrun_no, line_no)
 *              pay_type: 1=ordinary/allowance income, 2=allowance, 3=deduction, 4=tax, 5=super
 *              ext_amt = the line amount
 *
 *   pacosts  — Monthly cost summary (pre-aggregated by period_end_date / dept / pay_type)
 *              PK: (company_no, period_end_date, paygroup, dept, cost_type, pay_type, pay_code)
 *              Useful for dashboard charts without scanning paehist
 *
 *   pacodes  — Paycode master — type INT, super_flag
 */
@Service
public class PyDataService {
    private static final Logger log = LoggerFactory.getLogger(PyDataService.class);

    private final DSLContext dsl;

    public PyDataService(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ── KPI tiles ───────────────────────────────────────────────────────────

    /** Active employee headcount from pastaff. */
    public int getActiveHeadcount(AppSession s) {
        try {
            Integer v = dsl.select(DSL.count())
                           .from(PASTAFF)
                           .where(PASTAFF.COMPANY_NO.eq(s.getCompanyNo())
                               .and(activeEmployeeCondition()))
                           .fetchOne(DSL.count());
            return v != null ? v : 0;
        } catch (Exception e) { return 0; }
    }

    /**
     * Total gross pay YTD — sum of income paycodes from paehist.
     * pay_type 1 = ordinary income / allowances (income types).
     * Join to parunhd to filter by fiscal yr_no.
     */
    public BigDecimal getTotalGrossYtd(AppSession s) {
        try {
            LocalDate startDate = yrStart(s);
            LocalDate endDate   = yrEnd(s);
            BigDecimal v = dsl.select(DSL.coalesce(DSL.sum(PAEHIST.EXT_AMT), DSL.inline(BigDecimal.ZERO)))
                              .from(PAEHIST)
                              .where(PAEHIST.COMPANY_NO.eq(s.getCompanyNo())
                                  .and(PAEHIST.PAY_TYPE.in(1, 2))
                                  .and(PAEHIST.PAYRUN_DATE.between(startDate).and(endDate)))
                              .fetchOne(0, BigDecimal.class);
            return v != null ? v : BigDecimal.ZERO;
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /**
     * Total PAYG tax withheld YTD — pay_type=4 in paehist.
     */
    public BigDecimal getTotalTaxYtd(AppSession s) {
        try {
            LocalDate startDate = yrStart(s);
            LocalDate endDate   = yrEnd(s);
            BigDecimal v = dsl.select(DSL.coalesce(DSL.sum(PAEHIST.EXT_AMT), DSL.inline(BigDecimal.ZERO)))
                              .from(PAEHIST)
                              .where(PAEHIST.COMPANY_NO.eq(s.getCompanyNo())
                                  .and(PAEHIST.PAY_TYPE.eq(4))
                                  .and(PAEHIST.PAYRUN_DATE.between(startDate).and(endDate)))
                              .fetchOne(0, BigDecimal.class);
            return v != null ? v : BigDecimal.ZERO;
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** Pay run count for the year — distinct payrun_no from paehist filtered by date. */
    public int getPayRunCount(AppSession s) {
        try {
            LocalDate startDate = yrStart(s);
            LocalDate endDate   = yrEnd(s);
            Integer v = dsl.select(DSL.countDistinct(PAEHIST.PAYRUN_NO))
                           .from(PAEHIST)
                           .where(PAEHIST.COMPANY_NO.eq(s.getCompanyNo())
                               .and(PAEHIST.PAYRUN_DATE.between(startDate).and(endDate)))
                           .fetchOne(0, Integer.class);
            return v != null ? v : 0;
        } catch (Exception e) { return 0; }
    }

    // ── ECharts data ────────────────────────────────────────────────────────

    /** Gross pay by month — bar chart directly from paehist, filtered by fiscal year dates. */
    public Map<String, Object> getGrossPayByPeriod(AppSession s) {
        List<String>     labels   = new ArrayList<>();
        List<BigDecimal> grossPay = new ArrayList<>();
        try {
            LocalDate startDate = yrStart(s);
            LocalDate endDate   = yrEnd(s);

            // MySQL-specific DATE_FORMAT — use DSL.field for dialect-specific functions
            Field<String> monthLabel = DSL.field("DATE_FORMAT({0}, {1})", String.class,
                    PAEHIST.PAYRUN_DATE, DSL.inline("%b %Y")).as("month_label");
            Field<String> monthSort  = DSL.field("DATE_FORMAT({0}, {1})", String.class,
                    PAEHIST.PAYRUN_DATE, DSL.inline("%Y-%m")).as("month_sort");
            Field<BigDecimal> gross = DSL.coalesce(
                    DSL.sum(DSL.when(PAEHIST.PAY_TYPE.in(1, 2), PAEHIST.EXT_AMT).otherwise(BigDecimal.ZERO)),
                    DSL.inline(BigDecimal.ZERO)).as("gross");

            dsl.select(monthLabel, monthSort, gross)
               .from(PAEHIST)
               .where(PAEHIST.COMPANY_NO.eq(s.getCompanyNo())
                   .and(PAEHIST.PAYRUN_DATE.between(startDate).and(endDate)))
               .groupBy(monthSort, monthLabel)
               .orderBy(monthSort)
               .fetch()
               .forEach(r -> {
                   labels.add(r.get("month_label", String.class));
                   BigDecimal g = r.get("gross", BigDecimal.class);
                   grossPay.add(g != null ? g : BigDecimal.ZERO);
               });
        } catch (Exception e) { log.error("getGrossPayByPeriod: {}", e.getMessage()); }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("periods", labels); r.put("grossPay", grossPay);
        return r;
    }

    /** Headcount by department — horizontal bar chart from pastaff. */
    public Map<String, Object> getHeadcountByDept(AppSession s) {
        List<String>  depts  = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        try {
            Field<String> deptName = DSL.coalesce(
                    DSL.nullif(PASTAFF.DEPT, ""), DSL.inline("Unassigned")).as("dept_name");

            dsl.select(deptName, DSL.count().as("cnt"))
               .from(PASTAFF)
               .where(PASTAFF.COMPANY_NO.eq(s.getCompanyNo())
                   .and(activeEmployeeCondition()))
               .groupBy(PASTAFF.DEPT)
               .orderBy(DSL.count().desc())
               .limit(15)
               .fetch()
               .forEach(r -> {
                   depts.add(r.get("dept_name", String.class));
                   counts.add(r.get("cnt", Integer.class));
               });
        } catch (Exception e) { log.error("Query failed in {}: {}", getClass().getSimpleName(), e.getMessage()); }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("depts", depts); r.put("counts", counts);
        return r;
    }

    // ── Interactive report data ─────────────────────────────────────────────

    /** Employee list for interactive preview. */
    public Map<String, Object> getEmployeeListData(AppSession s) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            Field<String> deptDisplay = DSL.coalesce(
                    DSL.nullif(PASTAFF.DEPT, ""), DSL.inline("Unassigned")).as("dept_display");

            dsl.select(PASTAFF.EMPLOYEE_NO, PASTAFF.SURNAME, PASTAFF.FIRST_NAME,
                       PASTAFF.DEPT, PASTAFF.EMPLOYEE_STATUS, PASTAFF.PAY_FREQ,
                       PASTAFF.STD_RATE_PER_HR, PASTAFF.ANNUAL_SALARY,
                       PASTAFF.DATE_STARTED, PASTAFF.EMAIL_ADDRESS, deptDisplay)
               .from(PASTAFF)
               .where(PASTAFF.COMPANY_NO.eq(s.getCompanyNo())
                   .and(activeEmployeeCondition()))
               .orderBy(PASTAFF.DEPT, PASTAFF.SURNAME, PASTAFF.FIRST_NAME)
               .fetch()
               .forEach(r -> {
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("empNo",       r.get(PASTAFF.EMPLOYEE_NO));
                   row.put("name",        r.get(PASTAFF.SURNAME) + ", " + r.get(PASTAFF.FIRST_NAME));
                   row.put("dept",        r.get("dept_display", String.class));
                   row.put("payFreq",     r.get(PASTAFF.PAY_FREQ));
                   row.put("hourlyRate",  r.get(PASTAFF.STD_RATE_PER_HR));
                   row.put("salary",      r.get(PASTAFF.ANNUAL_SALARY));
                   LocalDate ds = r.get(PASTAFF.DATE_STARTED);
                   row.put("startDate",   ds != null ? ds.toString() : "");
                   row.put("email",       r.get(PASTAFF.EMAIL_ADDRESS));
                   rows.add(row);
               });
        } catch (Exception e) { return errorResult(e.getMessage()); }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", List.of(
            col("Emp No",      "empNo",      "number"),
            col("Name",        "name",       "text"),
            col("Department",  "dept",       "text"),
            col("Pay Freq",    "payFreq",    "text"),
            col("Hourly Rate", "hourlyRate", "currency"),
            col("Annual Salary","salary",    "currency"),
            col("Start Date",  "startDate",  "date"),
            col("Email",       "email",      "text")
        ));
        result.put("rows", rows);
        result.put("title", "Employee List — Active Employees");
        return result;
    }

    /** Payroll summary by payrun for interactive preview — driven by paehist.payrun_date. */
    public Map<String, Object> getPayrollSummaryData(AppSession s) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            LocalDate startDate = yrStart(s);
            LocalDate endDate   = yrEnd(s);

            Field<BigDecimal> grossPay = DSL.coalesce(
                    DSL.sum(DSL.when(PAEHIST.PAY_TYPE.in(1, 2), PAEHIST.EXT_AMT).otherwise(BigDecimal.ZERO)),
                    DSL.inline(BigDecimal.ZERO)).as("gross_pay");
            Field<BigDecimal> taxWithheld = DSL.coalesce(
                    DSL.sum(DSL.when(PAEHIST.PAY_TYPE.eq(18), PAEHIST.EXT_AMT).otherwise(BigDecimal.ZERO)),
                    DSL.inline(BigDecimal.ZERO)).as("tax_withheld");
            Field<BigDecimal> superAmt = DSL.coalesce(
                    DSL.sum(DSL.when(PAEHIST.PAY_TYPE.eq(20), PAEHIST.EXT_AMT).otherwise(BigDecimal.ZERO)),
                    DSL.inline(BigDecimal.ZERO)).as("super_amt");
            Field<Integer> employees = DSL.countDistinct(PAEHIST.EMPLOYEE_NO).as("employees");

            dsl.select(PAEHIST.PAYRUN_NO, PAEHIST.PAYRUN_DATE, grossPay, taxWithheld, superAmt, employees)
               .from(PAEHIST)
               .where(PAEHIST.COMPANY_NO.eq(s.getCompanyNo())
                   .and(PAEHIST.PAYRUN_DATE.between(startDate).and(endDate)))
               .groupBy(PAEHIST.PAYRUN_NO, PAEHIST.PAYRUN_DATE)
               .orderBy(PAEHIST.PAYRUN_DATE)
               .fetch()
               .forEach(r -> {
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("payrunNo",  r.get(PAEHIST.PAYRUN_NO));
                   LocalDate pd = r.get(PAEHIST.PAYRUN_DATE);
                   row.put("payDate",   pd != null ? pd.toString() : "");
                   row.put("pmtDate",   "");
                   row.put("employees", r.get("employees", Integer.class));
                   BigDecimal gp = r.get("gross_pay", BigDecimal.class);
                   row.put("grossPay",  gp);
                   row.put("tax",       r.get("tax_withheld", BigDecimal.class));
                   row.put("super",     r.get("super_amt", BigDecimal.class));
                   row.put("netPay",    gp);
                   rows.add(row);
               });
        } catch (Exception e) { return errorResult(e.getMessage()); }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", List.of(
            col("Pay Run",    "payrunNo",  "number"),
            col("Pay Date",   "payDate",   "date"),
            col("Pmt Date",   "pmtDate",   "date"),
            col("Employees",  "employees", "number"),
            col("Gross Pay",  "grossPay",  "currency"),
            col("Tax",        "tax",       "currency"),
            col("Super",      "super",     "currency"),
            col("Net Pay",    "netPay",    "currency")
        ));
        result.put("rows", rows);
        result.put("title", "Payroll Summary — " + s.getYearDesc());
        return result;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Get the gldates.yr_no for the current session year.
     * gldates has TWO year keys: year_no (calendar) and yr_no (sequence).
     * The session stores year_no; we need yr_no for parunhd joins.
     */
    private Integer getYrNo(AppSession s) {
        try {
            return dsl.select(GLDATES.YR_NO)
                      .from(GLDATES)
                      .where(GLDATES.COMPANY_NO.eq(s.getCompanyNo())
                          .and(GLDATES.YEAR_NO.eq(s.getYearNo())))
                      .limit(1)
                      .fetchOne(GLDATES.YR_NO);
        } catch (Exception e) { return null; }
    }

    /** Active employee condition — mirrors COBOL: blank or null status = active. */
    private static Condition activeEmployeeCondition() {
        return PASTAFF.EMPLOYEE_STATUS.isNull()
            .or(PASTAFF.EMPLOYEE_STATUS.eq(""))
            .or(PASTAFF.EMPLOYEE_STATUS.eq(" "));
    }

    /** Fiscal year start date, falling back to Jan 1 of the session year. */
    private static LocalDate yrStart(AppSession s) {
        return s.getYrStartDate() != null ? s.getYrStartDate() : LocalDate.of(s.getYearNo(), 1, 1);
    }

    /** Fiscal year end date, falling back to Dec 31 of the session year. */
    private static LocalDate yrEnd(AppSession s) {
        return s.getYrEndDate() != null ? s.getYrEndDate() : LocalDate.of(s.getYearNo(), 12, 31);
    }

    private Map<String, Object> col(String label, String field, String type) {
        return Map.of("label", label, "field", field, "type", type);
    }
    private Map<String, Object> errorResult(String msg) {
        return Map.of("error", msg, "columns", List.of(), "rows", List.of(), "title", "Error");
    }
}
