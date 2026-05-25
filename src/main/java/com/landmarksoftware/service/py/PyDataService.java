package com.landmarksoftware.service.py;

import com.landmarksoftware.model.AppSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

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

    private final JdbcTemplate jdbc;

    public PyDataService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── KPI tiles ───────────────────────────────────────────────────────────

    /** Active employee headcount from pastaff. */
    public int getActiveHeadcount(AppSession s) {
        try {
            Integer v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pastaff WHERE company_no=? AND (employee_status IS NULL OR employee_status = '' OR employee_status = ' ')",
                Integer.class, s.getCompanyNo());
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
            String startDate = s.getYrStartDate() != null ? s.getYrStartDate().toString() : s.getYearNo() + "-01-01";
            String endDate   = s.getYrEndDate()   != null ? s.getYrEndDate().toString()   : s.getYearNo() + "-12-31";
            BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(ext_amt), 0) FROM paehist " +
                "WHERE company_no=? AND pay_type IN (1,2) AND payrun_date BETWEEN ? AND ?",
                BigDecimal.class, s.getCompanyNo(), startDate, endDate);
            return v != null ? v : BigDecimal.ZERO;
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /**
     * Total PAYG tax withheld YTD — pay_type=4 in paehist.
     */
    public BigDecimal getTotalTaxYtd(AppSession s) {
        try {
            String startDate = s.getYrStartDate() != null ? s.getYrStartDate().toString() : s.getYearNo() + "-01-01";
            String endDate   = s.getYrEndDate()   != null ? s.getYrEndDate().toString()   : s.getYearNo() + "-12-31";
            BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(ext_amt), 0) FROM paehist " +
                "WHERE company_no=? AND pay_type=4 AND payrun_date BETWEEN ? AND ?",
                BigDecimal.class, s.getCompanyNo(), startDate, endDate);
            return v != null ? v : BigDecimal.ZERO;
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** Pay run count for the year — distinct payrun_no from paehist filtered by date. */
    public int getPayRunCount(AppSession s) {
        try {
            String startDate = s.getYrStartDate() != null ? s.getYrStartDate().toString() : s.getYearNo() + "-01-01";
            String endDate   = s.getYrEndDate()   != null ? s.getYrEndDate().toString()   : s.getYearNo() + "-12-31";
            Integer v = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT payrun_no) FROM paehist " +
                "WHERE company_no=? AND payrun_date BETWEEN ? AND ?",
                Integer.class, s.getCompanyNo(), startDate, endDate);
            return v != null ? v : 0;
        } catch (Exception e) { return 0; }
    }

    // ── ECharts data ────────────────────────────────────────────────────────

    /** Gross pay by month — bar chart directly from paehist, filtered by fiscal year dates. */
    public Map<String, Object> getGrossPayByPeriod(AppSession s) {
        List<String>     labels   = new ArrayList<>();
        List<BigDecimal> grossPay = new ArrayList<>();
        try {
            // Use payrun_date directly — no parunhd join needed
            // Filter by fiscal year start/end dates from session
            String startDate = s.getYrStartDate() != null ? s.getYrStartDate().toString() : null;
            String endDate   = s.getYrEndDate()   != null ? s.getYrEndDate().toString()   : null;
            if (startDate == null || endDate == null) {
                // Fall back to calendar year
                startDate = s.getYearNo() + "-01-01";
                endDate   = s.getYearNo() + "-12-31";
            }
            jdbc.query(
                "SELECT DATE_FORMAT(payrun_date, '%b %Y') AS month_label, " +
                "  DATE_FORMAT(payrun_date, '%Y-%m') AS month_sort, " +
                "  COALESCE(SUM(CASE WHEN pay_type IN (1,2) THEN ext_amt ELSE 0 END), 0) AS gross " +
                "FROM paehist " +
                "WHERE company_no=? AND payrun_date BETWEEN ? AND ? " +
                "GROUP BY month_sort, month_label ORDER BY month_sort",
                rs -> {
                    labels.add(rs.getString("month_label"));
                    BigDecimal g = rs.getBigDecimal("gross");
                    grossPay.add(g != null ? g : BigDecimal.ZERO);
                },
                s.getCompanyNo(), startDate, endDate);
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
            jdbc.query(
                "SELECT COALESCE(NULLIF(dept,''), 'Unassigned') AS dept_name, COUNT(*) AS cnt " +
                "FROM pastaff WHERE company_no=? AND (employee_status IS NULL OR employee_status = '' OR employee_status = ' ') " +
                "GROUP BY dept ORDER BY cnt DESC LIMIT 15",
                rs -> { depts.add(rs.getString("dept_name")); counts.add(rs.getInt("cnt")); },
                s.getCompanyNo());
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
            jdbc.query(
                "SELECT e.employee_no, e.surname, e.first_name, e.dept, e.employee_status, " +
                "  e.pay_freq, e.std_rate_per_hr, e.annual_salary, e.date_started, e.email_address, " +
                "  COALESCE(NULLIF(e.dept,''), 'Unassigned') AS dept_display " +
                "FROM pastaff e " +
                "WHERE e.company_no=? AND e.(employee_status IS NULL OR employee_status = '' OR employee_status = ' ') " +
                "ORDER BY e.dept, e.surname, e.first_name",
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("empNo",       rs.getInt("employee_no"));
                    row.put("name",        rs.getString("surname") + ", " + rs.getString("first_name"));
                    row.put("dept",        rs.getString("dept_display"));
                    row.put("payFreq",     rs.getString("pay_freq"));
                    row.put("hourlyRate",  rs.getBigDecimal("std_rate_per_hr"));
                    row.put("salary",      rs.getBigDecimal("annual_salary"));
                    row.put("startDate",   rs.getDate("date_started") != null ? rs.getDate("date_started").toString() : "");
                    row.put("email",       rs.getString("email_address"));
                    rows.add(row);
                }, s.getCompanyNo());
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
            String startDate = s.getYrStartDate() != null ? s.getYrStartDate().toString() : s.getYearNo() + "-01-01";
            String endDate   = s.getYrEndDate()   != null ? s.getYrEndDate().toString()   : s.getYearNo() + "-12-31";
            jdbc.query(
                "SELECT payrun_no, payrun_date, " +
                "  COALESCE(SUM(CASE WHEN pay_type IN (1,2) THEN ext_amt ELSE 0 END), 0) AS gross_pay, " +
                "  COALESCE(SUM(CASE WHEN pay_type=18 THEN ext_amt ELSE 0 END), 0) AS tax_withheld, " +
                "  COALESCE(SUM(CASE WHEN pay_type=20 THEN ext_amt ELSE 0 END), 0) AS super_amt, " +
                "  COUNT(DISTINCT employee_no) AS employees " +
                "FROM paehist " +
                "WHERE company_no=? AND payrun_date BETWEEN ? AND ? " +
                "GROUP BY payrun_no, payrun_date ORDER BY payrun_date",
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("payrunNo",  rs.getInt("payrun_no"));
                    row.put("payDate",   rs.getDate("payrun_date") != null ? rs.getDate("payrun_date").toString() : "");
                    row.put("pmtDate",   "");
                    row.put("employees", rs.getInt("employees"));
                    row.put("grossPay",  rs.getBigDecimal("gross_pay"));
                    row.put("tax",       rs.getBigDecimal("tax_withheld"));
                    row.put("super",     rs.getBigDecimal("super_amt"));
                    row.put("netPay",    rs.getBigDecimal("gross_pay"));
                    rows.add(row);
                },
                s.getCompanyNo(), startDate, endDate);
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
            return jdbc.queryForObject(
                "SELECT yr_no FROM gldates WHERE company_no=? AND year_no=? LIMIT 1",
                Integer.class, s.getCompanyNo(), s.getYearNo());
        } catch (Exception e) { return null; }
    }

    private Map<String, Object> col(String label, String field, String type) {
        return Map.of("label", label, "field", field, "type", type);
    }
    private Map<String, Object> errorResult(String msg) {
        return Map.of("error", msg, "columns", List.of(), "rows", List.of(), "title", "Error");
    }
}
