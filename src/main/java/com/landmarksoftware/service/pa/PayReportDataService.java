package com.landmarksoftware.service.pa;

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

import static com.landmarksoftware.db.tables.Glchart.GLCHART;
import static com.landmarksoftware.db.tables.Pacodes.PACODES;
import static com.landmarksoftware.db.tables.Pacosts.PACOSTS;
import static com.landmarksoftware.db.tables.Padepts.PADEPTS;
import static com.landmarksoftware.db.tables.Paehist.PAEHIST;
import static com.landmarksoftware.db.tables.Pagroup.PAGROUP;
import static com.landmarksoftware.db.tables.Parunhd.PARUNHD;
import static com.landmarksoftware.db.tables.Pastaff.PASTAFF;
import static com.landmarksoftware.db.tables.Paytd.PAYTD;

/**
 * Data service for PA (Payroll) reporting module.
 * All SQL here; controllers contain only JavaFX.
 *
 * <p>Migrated from JdbcTemplate to jOOQ DSLContext.
 *
 * <p><b>Inline-SQL notes:</b> {@link #getHistorySummary} uses
 * {@code DSL.field(String, Class, Object...)} for the dynamic GROUP BY / ORDER BY
 * columns because the sort key is a runtime parameter and cannot be expressed with
 * jOOQ's typed API without duplicating the entire select. All other queries use the
 * fully-typed jOOQ API.
 */
@Service
public class PayReportDataService {

    private static final Logger log = LoggerFactory.getLogger(PayReportDataService.class);

    private final DSLContext dsl;

    public PayReportDataService(DSLContext dsl) { this.dsl = dsl; }

    // ── Shared lookup types ──────────────────────────────────────────────────

    public record CodeName(String code, String name) {
        @Override public String toString() { return name; }
    }

    // ── Lookup helpers for new PA reports ───────────────────────────────────

    /** Posted/completed payruns for PATL60 (status F or P), newest first. */
    public List<CodeName> getPostedPayruns(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        try {
            dsl.select(PARUNHD.PAYRUN_NO, PARUNHD.PAYRUN_DATE, PARUNHD.PAYRUN_STATUS)
               .from(PARUNHD)
               .where(PARUNHD.COMPANY_NO.eq(s.getCompanyNo())
                   .and(PARUNHD.PAYRUN_STATUS.in("F", "P")))
               .orderBy(PARUNHD.PAYRUN_NO.desc())
               .fetch()
               .forEach(r -> {
                   int no = r.get(PARUNHD.PAYRUN_NO);
                   String status = "F".equals(r.get(PARUNHD.PAYRUN_STATUS)) ? "Completed" : "Posted";
                   LocalDate d = r.get(PARUNHD.PAYRUN_DATE);
                   list.add(new CodeName(String.valueOf(no),
                       no + "  —  " + (d != null ? d.toString() : "?") + "  (" + status + ")"));
               });
        } catch (Exception e) { log.warn("getPostedPayruns: {}", e.getMessage()); }
        return list;
    }

    /** Distinct period_end_date values from pacosts, newest first. */
    public List<CodeName> getDistinctCostPeriods(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        try {
            dsl.selectDistinct(PACOSTS.PERIOD_END_DATE)
               .from(PACOSTS)
               .where(PACOSTS.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(PACOSTS.PERIOD_END_DATE.desc())
               .fetch()
               .forEach(r -> {
                   LocalDate d = r.get(PACOSTS.PERIOD_END_DATE);
                   if (d != null) {
                       String ds = d.toString();
                       list.add(new CodeName(ds, ds));
                   }
               });
        } catch (Exception e) { log.warn("getDistinctCostPeriods: {}", e.getMessage()); }
        return list;
    }

    // ── PATL02 — Employee YTD Payments ──────────────────────────────────────

    public record YtdPaymentsParams(
            Integer startEmployee, Integer endEmployee,
            String  startPaygroup, String  endPaygroup,
            String  startDept,     String  endDept,
            String  startCode,     String  endCode,
            int     yearNo) {}

    /** "(All)" sentinel used as the first item in every lookup combo. */
    public static final CodeName ALL = new CodeName("", "(All)");

    /** Distinct tax years present in paytd for this company, descending. */
    public List<CodeName> getYtdYears(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        try {
            dsl.selectDistinct(PAYTD.YEAR_NO)
               .from(PAYTD)
               .where(PAYTD.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(PAYTD.YEAR_NO.desc())
               .fetch()
               .forEach(r -> {
                   int y = r.get(PAYTD.YEAR_NO);
                   list.add(new CodeName(String.valueOf(y), "FY " + (y - 1) + "-" + String.valueOf(y).substring(2)));
               });
        } catch (Exception e) { log.warn("getYtdYears: {}", e.getMessage()); }
        return list;
    }

    /** Paygroups for this company: "(All)" + each paygroup. */
    public List<CodeName> getPaygroups(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(ALL);
        try {
            dsl.select(PAGROUP.PAYGROUP, PAGROUP.DESC1)
               .from(PAGROUP)
               .where(PAGROUP.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(PAGROUP.PAYGROUP)
               .fetch()
               .forEach(r -> list.add(new CodeName(trim(r.get(PAGROUP.PAYGROUP)),
                   trim(r.get(PAGROUP.PAYGROUP)) + "  —  " + trim(r.get(PAGROUP.DESC1)))));
        } catch (Exception e) { log.warn("getPaygroups: {}", e.getMessage()); }
        return list;
    }

    /** Distinct departments for this company: "(All)" + each dept. */
    public List<CodeName> getDepts(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(ALL);
        try {
            dsl.select(PADEPTS.DEPT, DSL.min(PADEPTS.DESC1).as("d"))
               .from(PADEPTS)
               .where(PADEPTS.COMPANY_NO.eq(s.getCompanyNo()))
               .groupBy(PADEPTS.DEPT)
               .orderBy(PADEPTS.DEPT)
               .fetch()
               .forEach(r -> list.add(new CodeName(trim(r.get(PADEPTS.DEPT)),
                   trim(r.get(PADEPTS.DEPT)) + "  —  " + trim(r.get("d", String.class)))));
        } catch (Exception e) { log.warn("getDepts: {}", e.getMessage()); }
        return list;
    }

    /** Active employees for this company: "(All)" + each employee. */
    public List<CodeName> getEmployees(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(ALL);
        try {
            dsl.select(PASTAFF.EMPLOYEE_NO, PASTAFF.SURNAME, PASTAFF.FIRST_NAME)
               .from(PASTAFF)
               .where(PASTAFF.COMPANY_NO.eq(s.getCompanyNo())
                   .and(PASTAFF.EMPLOYEE_STATUS.ne("T")))
               .orderBy(PASTAFF.EMPLOYEE_NO)
               .fetch()
               .forEach(r -> {
                   int no = r.get(PASTAFF.EMPLOYEE_NO);
                   list.add(new CodeName(String.valueOf(no),
                       no + "  —  " + trim(r.get(PASTAFF.SURNAME)) + ", " + trim(r.get(PASTAFF.FIRST_NAME))));
               });
        } catch (Exception e) { log.warn("getEmployees: {}", e.getMessage()); }
        return list;
    }

    /** Pay codes for this company: "(All)" + each code. */
    public List<CodeName> getPayCodes(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(ALL);
        try {
            dsl.select(PACODES.PAY_CODE, PACODES.DESC1)
               .from(PACODES)
               .where(PACODES.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(PACODES.PAY_CODE)
               .fetch()
               .forEach(r -> list.add(new CodeName(trim(r.get(PACODES.PAY_CODE)),
                   trim(r.get(PACODES.PAY_CODE)) + "  —  " + trim(r.get(PACODES.DESC1)))));
        } catch (Exception e) { log.warn("getPayCodes: {}", e.getMessage()); }
        return list;
    }

    public Map<String, Object> getYtdPayments(AppSession s, YtdPaymentsParams p) {
        String pg1 = blank(p.startPaygroup()) ? "    " : p.startPaygroup();
        String pg2 = blank(p.endPaygroup())   ? "zzzz" : p.endPaygroup();
        String d1  = blank(p.startDept())     ? "    " : p.startDept();
        String d2  = blank(p.endDept())       ? "zzzz" : p.endDept();
        String c1  = blank(p.startCode())     ? "      " : p.startCode();
        String c2  = blank(p.endCode())       ? "zzzzzz" : p.endCode();
        int    e1  = p.startEmployee() == null || p.startEmployee() <= 0 ? 0      : p.startEmployee();
        int    e2  = p.endEmployee()   == null || p.endEmployee()   <= 0 ? 999999 : p.endEmployee();

        // Table aliases for the multi-table join
        var t = PAYTD.as("t");
        var s2 = PASTAFF.as("s");
        var c = PACODES.as("c");

        Field<String> codeDesc = DSL.coalesce(c.field(PACODES.DESC1), t.field(PAYTD.PAY_CODE)).as("code_desc");

        // Collect raw query rows first so we can compute per-employee totals.
        List<Map<String, Object>> rawRows = new ArrayList<>();
        try {
            dsl.select(
                   t.field(PAYTD.EMPLOYEE_NO),
                   s2.field(PASTAFF.SURNAME),
                   s2.field(PASTAFF.FIRST_NAME),
                   s2.field(PASTAFF.PAYGROUP),
                   s2.field(PASTAFF.DEPT),
                   t.field(PAYTD.PAY_TYPE),
                   t.field(PAYTD.PAY_CODE),
                   codeDesc,
                   t.field(PAYTD.AMT),
                   t.field(PAYTD.HRS))
               .from(t)
               .join(s2).on(s2.field(PASTAFF.COMPANY_NO).eq(t.field(PAYTD.COMPANY_NO))
                   .and(s2.field(PASTAFF.EMPLOYEE_NO).eq(t.field(PAYTD.EMPLOYEE_NO))))
               .leftJoin(c).on(c.field(PACODES.COMPANY_NO).eq(t.field(PAYTD.COMPANY_NO))
                   .and(c.field(PACODES.PAY_CODE).eq(t.field(PAYTD.PAY_CODE))))
               .where(t.field(PAYTD.COMPANY_NO).eq(s.getCompanyNo())
                   .and(t.field(PAYTD.YEAR_NO).eq(p.yearNo()))
                   .and(s2.field(PASTAFF.PAYGROUP).between(pg1, pg2))
                   .and(s2.field(PASTAFF.DEPT).between(d1, d2))
                   .and(t.field(PAYTD.PAY_CODE).between(c1, c2))
                   .and(t.field(PAYTD.EMPLOYEE_NO).between(e1, e2)))
               .orderBy(s2.field(PASTAFF.SURNAME), s2.field(PASTAFF.FIRST_NAME),
                        t.field(PAYTD.EMPLOYEE_NO), t.field(PAYTD.PAY_TYPE), t.field(PAYTD.PAY_CODE))
               .fetch()
               .forEach(r -> {
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("empNo",     r.get(t.field(PAYTD.EMPLOYEE_NO)));
                   row.put("surname",   trim(r.get(s2.field(PASTAFF.SURNAME))));
                   row.put("firstName", trim(r.get(s2.field(PASTAFF.FIRST_NAME))));
                   row.put("paygroup",  trim(r.get(s2.field(PASTAFF.PAYGROUP))));
                   row.put("dept",      trim(r.get(s2.field(PASTAFF.DEPT))));
                   row.put("payType",   r.get(t.field(PAYTD.PAY_TYPE)));
                   row.put("payCode",   trim(r.get(t.field(PAYTD.PAY_CODE))));
                   row.put("codeDesc",  trim(r.get("code_desc", String.class)));
                   int mins = r.get(t.field(PAYTD.HRS));
                   row.put("hours", mins > 0 ? BigDecimal.valueOf(mins).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP) : null);
                   BigDecimal amt0 = z(r.get(t.field(PAYTD.AMT)));
                   row.put("payAmt", amt0);
                   row.put("amtStr", fmtAmt(amt0));
                   rawRows.add(row);
               });
        } catch (Exception e) {
            log.error("getYtdPayments: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rawRows.isEmpty()) return warn("No YTD data found for the selection.");

        // Per-employee totals.
        Map<Integer, BigDecimal> empTotals = new LinkedHashMap<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (Map<String, Object> r : rawRows) {
            int emp = (Integer) r.get("empNo");
            BigDecimal amt = (BigDecimal) r.get("payAmt");
            empTotals.merge(emp, amt, BigDecimal::add);
            grandTotal = grandTotal.add(amt);
        }

        // Build output rows: header → detail lines → total, per employee.
        // rowKind drives conditional styles in the PDF jrxml (GLRP40 pattern).
        // Excel jrxml filters to "detail" rows only via printWhenExpression.
        List<Map<String, Object>> rows = new ArrayList<>();
        Integer prevEmp = null;
        for (Map<String, Object> raw : rawRows) {
            int emp = (Integer) raw.get("empNo");
            if (!Integer.valueOf(emp).equals(prevEmp)) {
                // Close previous employee with a total row.
                if (prevEmp != null) {
                    rows.add(totalRow(prevEmp, empTotals.get(prevEmp)));
                }
                // Open new employee with a header row.
                rows.add(headerRow(raw));
                prevEmp = emp;
            }
            raw.put("rowKind", "detail");
            rows.add(raw);
        }
        if (prevEmp != null) {
            rows.add(totalRow(prevEmp, empTotals.get(prevEmp)));
        }

        int y = p.yearNo();
        String yearDesc = "FY " + (y - 1) + "-" + String.valueOf(y).substring(2);
        String acctRange = rangeDesc("Paygroup", p.startPaygroup(), p.endPaygroup())
                         + rangeDesc("  Dept", p.startDept(), p.endDept())
                         + rangeDesc("  Emp", empStr(e1), empStr(e2));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("YEAR_DESC",   yearDesc);
        params.put("ACCT_RANGE",  acctRange.trim());
        params.put("GRAND_TOTAL", grandTotal);
        params.put("ROW_COUNT",   rows.size());
        return result(rows, params);
    }

    // ── Row builders ─────────────────────────────────────────────────────────

    private static Map<String, Object> headerRow(Map<String, Object> raw) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rowKind",   "header");
        r.put("empNo",     raw.get("empNo"));
        r.put("surname",   raw.get("surname"));
        r.put("firstName", raw.get("firstName"));
        r.put("paygroup",  raw.get("paygroup"));
        r.put("dept",      raw.get("dept"));
        r.put("payType",   0);
        r.put("payCode",   "");
        r.put("codeDesc",  String.format("%d  —  %s, %s   Dept: %s   Paygroup: %s",
            raw.get("empNo"), raw.get("surname"), raw.get("firstName"),
            raw.get("dept"), raw.get("paygroup")));
        r.put("hours",  null);
        r.put("payAmt", BigDecimal.ZERO);
        r.put("amtStr", "");
        return r;
    }

    private static Map<String, Object> totalRow(int empNo, BigDecimal total) {
        BigDecimal t = total == null ? BigDecimal.ZERO : total;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rowKind",   "total");
        r.put("empNo",     empNo);
        r.put("surname",   "");
        r.put("firstName", "");
        r.put("paygroup",  "");
        r.put("dept",      "");
        r.put("payType",   0);
        r.put("payCode",   "");
        r.put("codeDesc",  "Employee Total");
        r.put("hours",  null);
        r.put("payAmt", t);
        r.put("amtStr", fmtAmt(t));
        return r;
    }

    private static String fmtAmt(BigDecimal b) {
        if (b == null || b.signum() == 0) return "";
        if (b.signum() < 0) return "(" + String.format("%,.2f", b.negate()) + ")";
        return String.format("%,.2f", b);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean blank(String s)      { return s == null || s.isBlank(); }
    private static String  trim(String s)       { return s == null ? "" : s.trim(); }
    private static BigDecimal z(BigDecimal b)   { return b == null ? BigDecimal.ZERO : b; }

    private static String empStr(int e) {
        return e <= 0 ? "" : e >= 999999 ? "" : String.valueOf(e);
    }

    private static String rangeDesc(String label, String start, String end) {
        boolean s = blank(start), e = blank(end);
        if (s && e) return "";
        if (s) return "  " + label + " to " + end.trim();
        if (e) return "  " + label + " from " + start.trim();
        String st = start.trim(), en = end.trim();
        return st.equals(en) ? "  " + label + " " + st : "  " + label + " " + st + "–" + en;
    }

    private static Map<String, Object> result(List<Map<String, Object>> rows, Map<String, Object> params) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rows", rows); r.put("params", params);
        return r;
    }

    private static Map<String, Object> warn(String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("warning", msg);
        return r;
    }

    // ── PATL14 — Employee History Detail ────────────────────────────────────

    public record HistoryDetailParams(
            Integer startEmp, Integer endEmp,
            String startPg, String endPg,
            LocalDate startDate, LocalDate endDate) {}

    public Map<String, Object> getHistoryDetail(AppSession s, HistoryDetailParams p) {
        int e1 = p.startEmp() == null || p.startEmp() <= 0 ? 0      : p.startEmp();
        int e2 = p.endEmp()   == null || p.endEmp()   <= 0 ? 999999 : p.endEmp();
        String pg1 = blank(p.startPg()) ? "    " : p.startPg();
        String pg2 = blank(p.endPg())   ? "zzzz" : p.endPg();
        LocalDate d1 = p.startDate() != null ? p.startDate() : LocalDate.of(1900, 1, 1);
        LocalDate d2 = p.endDate()   != null ? p.endDate()   : LocalDate.of(2999, 12, 31);

        var h = PAEHIST.as("h");
        var s2 = PASTAFF.as("s");
        var c = PACODES.as("c");
        var r2 = PARUNHD.as("r");

        Field<String> codeDesc = DSL.coalesce(c.field(PACODES.DESC1), h.field(PAEHIST.PAY_CODE)).as("code_desc");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            dsl.select(
                   h.field(PAEHIST.EMPLOYEE_NO),
                   s2.field(PASTAFF.SURNAME),
                   s2.field(PASTAFF.FIRST_NAME),
                   s2.field(PASTAFF.PAYGROUP),
                   s2.field(PASTAFF.DEPT),
                   h.field(PAEHIST.PAYRUN_NO),
                   h.field(PAEHIST.PAYRUN_DATE),
                   r2.field(PARUNHD.START_DATE),
                   r2.field(PARUNHD.END_DATE),
                   h.field(PAEHIST.PAY_TYPE),
                   h.field(PAEHIST.PAY_CODE),
                   codeDesc,
                   h.field(PAEHIST.HRS),
                   h.field(PAEHIST.EXT_AMT),
                   h.field(PAEHIST.REF))
               .from(h)
               .join(s2).on(s2.field(PASTAFF.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(s2.field(PASTAFF.EMPLOYEE_NO).eq(h.field(PAEHIST.EMPLOYEE_NO))))
               .leftJoin(c).on(c.field(PACODES.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(c.field(PACODES.PAY_CODE).eq(h.field(PAEHIST.PAY_CODE))))
               .join(r2).on(r2.field(PARUNHD.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(r2.field(PARUNHD.PAYRUN_NO).eq(h.field(PAEHIST.PAYRUN_NO))))
               .where(h.field(PAEHIST.COMPANY_NO).eq(s.getCompanyNo())
                   .and(h.field(PAEHIST.EMPLOYEE_NO).between(e1, e2))
                   .and(s2.field(PASTAFF.PAYGROUP).between(pg1, pg2))
                   .and(h.field(PAEHIST.PAYRUN_DATE).between(d1, d2)))
               .orderBy(s2.field(PASTAFF.SURNAME), s2.field(PASTAFF.FIRST_NAME),
                        h.field(PAEHIST.EMPLOYEE_NO), h.field(PAEHIST.PAYRUN_DATE),
                        h.field(PAEHIST.PAYRUN_NO), h.field(PAEHIST.PAY_TYPE), h.field(PAEHIST.LINE_NO))
               .fetch()
               .forEach(row -> {
                   Map<String, Object> rowMap = new LinkedHashMap<>();
                   rowMap.put("empNo",      row.get(h.field(PAEHIST.EMPLOYEE_NO)));
                   rowMap.put("surname",    trim(row.get(s2.field(PASTAFF.SURNAME))));
                   rowMap.put("firstName",  trim(row.get(s2.field(PASTAFF.FIRST_NAME))));
                   rowMap.put("paygroup",   trim(row.get(s2.field(PASTAFF.PAYGROUP))));
                   rowMap.put("dept",       trim(row.get(s2.field(PASTAFF.DEPT))));
                   rowMap.put("payrunNo",   row.get(h.field(PAEHIST.PAYRUN_NO)));
                   rowMap.put("payrunDate", row.get(h.field(PAEHIST.PAYRUN_DATE)));
                   rowMap.put("payCode",    trim(row.get(h.field(PAEHIST.PAY_CODE))));
                   rowMap.put("codeDesc",   trim(row.get("code_desc", String.class)));
                   int mins = row.get(h.field(PAEHIST.HRS));
                   rowMap.put("hours", mins > 0 ? BigDecimal.valueOf(mins).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP) : null);
                   rowMap.put("amount",     z(row.get(h.field(PAEHIST.EXT_AMT))));
                   rowMap.put("ref",        trim(row.get(h.field(PAEHIST.REF))));
                   rows.add(rowMap);
               });
        } catch (Exception e) {
            log.error("getHistoryDetail: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No history data matched the selection.");

        for (Map<String, Object> row : rows) grandTotal = grandTotal.add(z((BigDecimal) row.get("amount")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("COMPANY_NAME", s.getCompanyName());
        params.put("DATE_RANGE",   datRangeDesc(p.startDate(), p.endDate()));
        params.put("GRAND_TOTAL",  grandTotal);
        params.put("ROW_COUNT",    rows.size());
        return result(rows, params);
    }

    // ── PATL17/30/55 — Employee History Summary ──────────────────────────────

    public record HistorySummaryParams(
            Integer startEmp, Integer endEmp,
            String startPg, String endPg,
            String startDept, String endDept,
            LocalDate startDate, LocalDate endDate,
            String sortBy) {} // "Employee", "Paygroup", "Payrun date"

    public Map<String, Object> getHistorySummary(AppSession s, HistorySummaryParams p) {
        int e1 = p.startEmp() == null || p.startEmp() <= 0 ? 0      : p.startEmp();
        int e2 = p.endEmp()   == null || p.endEmp()   <= 0 ? 999999 : p.endEmp();
        String pg1 = blank(p.startPg())   ? "    " : p.startPg();
        String pg2 = blank(p.endPg())     ? "zzzz" : p.endPg();
        String d1s = blank(p.startDept()) ? "    " : p.startDept();
        String d2s = blank(p.endDept())   ? "zzzz" : p.endDept();
        LocalDate dt1 = p.startDate() != null ? p.startDate() : LocalDate.of(1900, 1, 1);
        LocalDate dt2 = p.endDate()   != null ? p.endDate()   : LocalDate.of(2999, 12, 31);

        boolean byPayrun = "Payrun date".equals(p.sortBy());
        boolean byPaygroup = "Paygroup".equals(p.sortBy());

        var h = PAEHIST.as("h");
        var s2 = PASTAFF.as("s");
        var c = PACODES.as("c");

        Field<String> codeDesc = DSL.coalesce(c.field(PACODES.DESC1), h.field(PAEHIST.PAY_CODE)).as("code_desc");

        // Dynamic GROUP BY and ORDER BY — use inline DSL.field() because the column
        // set changes at runtime based on sortBy and cannot be typed-API expressed
        // without duplicating the entire select tree. See class Javadoc.
        var groupByFields = new ArrayList<Field<?>>();
        groupByFields.add(h.field(PAEHIST.EMPLOYEE_NO));
        groupByFields.add(s2.field(PASTAFF.SURNAME));
        groupByFields.add(s2.field(PASTAFF.FIRST_NAME));
        groupByFields.add(s2.field(PASTAFF.PAYGROUP));
        groupByFields.add(s2.field(PASTAFF.DEPT));
        groupByFields.add(h.field(PAEHIST.PAY_TYPE));
        groupByFields.add(h.field(PAEHIST.PAY_CODE));
        groupByFields.add(DSL.field("code_desc", String.class));
        if (byPayrun) {
            groupByFields.add(h.field(PAEHIST.PAYRUN_NO));
            groupByFields.add(h.field(PAEHIST.PAYRUN_DATE));
        }

        var orderByFields = new ArrayList<Field<?>>();
        if (byPayrun) {
            orderByFields.add(h.field(PAEHIST.PAYRUN_DATE));
            orderByFields.add(h.field(PAEHIST.PAYRUN_NO));
            orderByFields.add(s2.field(PASTAFF.SURNAME));
            orderByFields.add(s2.field(PASTAFF.FIRST_NAME));
            orderByFields.add(h.field(PAEHIST.EMPLOYEE_NO));
            orderByFields.add(h.field(PAEHIST.PAY_TYPE));
        } else if (byPaygroup) {
            orderByFields.add(s2.field(PASTAFF.PAYGROUP));
            orderByFields.add(s2.field(PASTAFF.SURNAME));
            orderByFields.add(s2.field(PASTAFF.FIRST_NAME));
            orderByFields.add(h.field(PAEHIST.EMPLOYEE_NO));
            orderByFields.add(h.field(PAEHIST.PAY_TYPE));
        } else {
            orderByFields.add(s2.field(PASTAFF.SURNAME));
            orderByFields.add(s2.field(PASTAFF.FIRST_NAME));
            orderByFields.add(h.field(PAEHIST.EMPLOYEE_NO));
            orderByFields.add(h.field(PAEHIST.PAY_TYPE));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            var selectStep = dsl.select(
                   h.field(PAEHIST.EMPLOYEE_NO),
                   s2.field(PASTAFF.SURNAME),
                   s2.field(PASTAFF.FIRST_NAME),
                   s2.field(PASTAFF.PAYGROUP),
                   s2.field(PASTAFF.DEPT),
                   h.field(PAEHIST.PAY_TYPE),
                   h.field(PAEHIST.PAY_CODE),
                   codeDesc,
                   DSL.sum(h.field(PAEHIST.HRS)).as("total_hrs"),
                   DSL.sum(h.field(PAEHIST.EXT_AMT)).as("total_amt"),
                   byPayrun ? h.field(PAEHIST.PAYRUN_NO) : DSL.val((Integer) null).as("payrun_no"),
                   byPayrun ? h.field(PAEHIST.PAYRUN_DATE) : DSL.val((LocalDate) null).as("payrun_date"))
               .from(h)
               .join(s2).on(s2.field(PASTAFF.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(s2.field(PASTAFF.EMPLOYEE_NO).eq(h.field(PAEHIST.EMPLOYEE_NO))))
               .leftJoin(c).on(c.field(PACODES.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(c.field(PACODES.PAY_CODE).eq(h.field(PAEHIST.PAY_CODE))))
               .where(h.field(PAEHIST.COMPANY_NO).eq(s.getCompanyNo())
                   .and(h.field(PAEHIST.EMPLOYEE_NO).between(e1, e2))
                   .and(s2.field(PASTAFF.PAYGROUP).between(pg1, pg2))
                   .and(s2.field(PASTAFF.DEPT).between(d1s, d2s))
                   .and(h.field(PAEHIST.PAYRUN_DATE).between(dt1, dt2)))
               .groupBy(groupByFields)
               .orderBy(orderByFields);

            selectStep.fetch().forEach(row -> {
                Map<String, Object> rowMap = new LinkedHashMap<>();
                rowMap.put("empNo",     row.get(h.field(PAEHIST.EMPLOYEE_NO)));
                rowMap.put("surname",   trim(row.get(s2.field(PASTAFF.SURNAME))));
                rowMap.put("firstName", trim(row.get(s2.field(PASTAFF.FIRST_NAME))));
                rowMap.put("paygroup",  trim(row.get(s2.field(PASTAFF.PAYGROUP))));
                rowMap.put("dept",      trim(row.get(s2.field(PASTAFF.DEPT))));
                rowMap.put("payType",   row.get(h.field(PAEHIST.PAY_TYPE)));
                rowMap.put("payCode",   trim(row.get(h.field(PAEHIST.PAY_CODE))));
                rowMap.put("codeDesc",  trim(row.get("code_desc", String.class)));
                int mins = row.get("total_hrs", Integer.class) != null ? row.get("total_hrs", Integer.class) : 0;
                rowMap.put("hours", mins > 0 ? BigDecimal.valueOf(mins).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP) : null);
                rowMap.put("amount", z(row.get("total_amt", BigDecimal.class)));
                rowMap.put("payrunNo",   byPayrun ? row.get("payrun_no", Integer.class)   : null);
                rowMap.put("payrunDate", byPayrun ? row.get("payrun_date", LocalDate.class) : null);
                rows.add(rowMap);
            });
        } catch (Exception e) {
            log.error("getHistorySummary: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No history data matched the selection.");
        for (Map<String, Object> row : rows) grandTotal = grandTotal.add(z((BigDecimal) row.get("amount")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("COMPANY_NAME", s.getCompanyName());
        params.put("DATE_RANGE",   datRangeDesc(p.startDate(), p.endDate()));
        params.put("GRAND_TOTAL",  grandTotal);
        params.put("ROW_COUNT",    rows.size());
        params.put("SORT_BY",      p.sortBy() == null ? "Employee" : p.sortBy());
        return result(rows, params);
    }

    // ── PATL05/09 — Deductions & Superannuation ──────────────────────────────

    public record DednSuperParams(
            String startCode, String endCode,
            Integer startEmp, Integer endEmp,
            int yearNo, String sortBy) {} // "Pay Code" or "Fund Name"

    public Map<String, Object> getDednSuper(AppSession s, DednSuperParams p) {
        String c1 = blank(p.startCode()) ? "      " : p.startCode();
        String c2 = blank(p.endCode())   ? "zzzzzz" : p.endCode();
        int e1 = p.startEmp() == null || p.startEmp() <= 0 ? 0      : p.startEmp();
        int e2 = p.endEmp()   == null || p.endEmp()   <= 0 ? 999999 : p.endEmp();
        boolean byFund = "Fund Name".equals(p.sortBy());

        var t = PAYTD.as("t");
        var s2 = PASTAFF.as("s");
        var c = PACODES.as("c");

        Field<String> codeDesc = DSL.coalesce(c.field(PACODES.DESC1), t.field(PAYTD.PAY_CODE)).as("code_desc");
        Field<String> fundName = DSL.coalesce(c.field(PACODES.FUND_NAME), DSL.val("")).as("fund_name");

        var orderByFields = new ArrayList<Field<?>>();
        if (byFund) orderByFields.add(DSL.coalesce(c.field(PACODES.FUND_NAME), DSL.val("")));
        orderByFields.add(t.field(PAYTD.PAY_CODE));
        orderByFields.add(s2.field(PASTAFF.SURNAME));
        orderByFields.add(t.field(PAYTD.EMPLOYEE_NO));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            dsl.select(
                   t.field(PAYTD.PAY_CODE),
                   t.field(PAYTD.PAY_TYPE),
                   codeDesc,
                   fundName,
                   t.field(PAYTD.EMPLOYEE_NO),
                   s2.field(PASTAFF.SURNAME),
                   s2.field(PASTAFF.FIRST_NAME),
                   t.field(PAYTD.AMT))
               .from(t)
               .join(s2).on(s2.field(PASTAFF.COMPANY_NO).eq(t.field(PAYTD.COMPANY_NO))
                   .and(s2.field(PASTAFF.EMPLOYEE_NO).eq(t.field(PAYTD.EMPLOYEE_NO))))
               .leftJoin(c).on(c.field(PACODES.COMPANY_NO).eq(t.field(PAYTD.COMPANY_NO))
                   .and(c.field(PACODES.PAY_CODE).eq(t.field(PAYTD.PAY_CODE))))
               .where(t.field(PAYTD.COMPANY_NO).eq(s.getCompanyNo())
                   .and(t.field(PAYTD.YEAR_NO).eq(p.yearNo()))
                   .and(t.field(PAYTD.PAY_TYPE).in(19, 20, 21))
                   .and(t.field(PAYTD.PAY_CODE).between(c1, c2))
                   .and(t.field(PAYTD.EMPLOYEE_NO).between(e1, e2)))
               .orderBy(orderByFields)
               .fetch()
               .forEach(row -> {
                   Map<String, Object> rowMap = new LinkedHashMap<>();
                   rowMap.put("payCode",   trim(row.get(t.field(PAYTD.PAY_CODE))));
                   rowMap.put("payType",   row.get(t.field(PAYTD.PAY_TYPE)));
                   rowMap.put("codeDesc",  trim(row.get("code_desc", String.class)));
                   rowMap.put("fundName",  trim(row.get("fund_name", String.class)));
                   rowMap.put("empNo",     row.get(t.field(PAYTD.EMPLOYEE_NO)));
                   rowMap.put("surname",   trim(row.get(s2.field(PASTAFF.SURNAME))));
                   rowMap.put("firstName", trim(row.get(s2.field(PASTAFF.FIRST_NAME))));
                   rowMap.put("amount",    z(row.get(t.field(PAYTD.AMT))));
                   rows.add(rowMap);
               });
        } catch (Exception e) {
            log.error("getDednSuper: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No deduction/super data matched the selection.");
        for (Map<String, Object> row : rows) grandTotal = grandTotal.add(z((BigDecimal) row.get("amount")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("COMPANY_NAME", s.getCompanyName());
        params.put("YEAR_NO",      p.yearNo());
        params.put("GRAND_TOTAL",  grandTotal);
        params.put("ROW_COUNT",    rows.size());
        params.put("SORT_BY",      p.sortBy() == null ? "Pay Code" : p.sortBy());
        return result(rows, params);
    }

    // ── PATL16 — Department Expenses ────────────────────────────────────────

    public record DeptExpensesParams(
            LocalDate periodDate,
            String startPg, String endPg,
            String startDept, String endDept) {}

    public Map<String, Object> getDeptExpenses(AppSession s, DeptExpensesParams p) {
        if (p.periodDate() == null) return warn("Select a period end date.");
        String pg1 = blank(p.startPg())   ? "    " : p.startPg();
        String pg2 = blank(p.endPg())     ? "zzzz" : p.endPg();
        String d1  = blank(p.startDept()) ? "    " : p.startDept();
        String d2  = blank(p.endDept())   ? "zzzz" : p.endDept();

        var k = PACOSTS.as("k");
        var g = PAGROUP.as("g");
        var d = PADEPTS.as("d");
        var c = PACODES.as("c");

        Field<String> codeDesc = DSL.coalesce(c.field(PACODES.DESC1), k.field(PACOSTS.PAY_CODE)).as("code_desc");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            dsl.select(
                   k.field(PACOSTS.PAYGROUP),
                   g.field(PAGROUP.DESC1).as("pg_desc"),
                   k.field(PACOSTS.DEPT),
                   d.field(PADEPTS.DESC1).as("dept_desc"),
                   k.field(PACOSTS.PAY_TYPE),
                   k.field(PACOSTS.PAY_CODE),
                   codeDesc,
                   k.field(PACOSTS.AMT),
                   k.field(PACOSTS.HRS))
               .from(k)
               .leftJoin(g).on(g.field(PAGROUP.COMPANY_NO).eq(k.field(PACOSTS.COMPANY_NO))
                   .and(g.field(PAGROUP.PAYGROUP).eq(k.field(PACOSTS.PAYGROUP))))
               .leftJoin(d).on(d.field(PADEPTS.COMPANY_NO).eq(k.field(PACOSTS.COMPANY_NO))
                   .and(d.field(PADEPTS.DEPT).eq(k.field(PACOSTS.DEPT)))
                   .and(d.field(PADEPTS.PAYGROUP).eq(k.field(PACOSTS.PAYGROUP))))
               .leftJoin(c).on(c.field(PACODES.COMPANY_NO).eq(k.field(PACOSTS.COMPANY_NO))
                   .and(c.field(PACODES.PAY_CODE).eq(k.field(PACOSTS.PAY_CODE))))
               .where(k.field(PACOSTS.COMPANY_NO).eq(s.getCompanyNo())
                   .and(k.field(PACOSTS.PERIOD_END_DATE).eq(p.periodDate()))
                   .and(k.field(PACOSTS.PAYGROUP).between(pg1, pg2))
                   .and(k.field(PACOSTS.DEPT).between(d1, d2)))
               .orderBy(k.field(PACOSTS.PAYGROUP), k.field(PACOSTS.DEPT),
                        k.field(PACOSTS.PAY_TYPE), k.field(PACOSTS.PAY_CODE))
               .fetch()
               .forEach(row -> {
                   Map<String, Object> rowMap = new LinkedHashMap<>();
                   rowMap.put("paygroup",  trim(row.get(k.field(PACOSTS.PAYGROUP))));
                   rowMap.put("pgDesc",    trim(row.get("pg_desc", String.class)));
                   rowMap.put("dept",      trim(row.get(k.field(PACOSTS.DEPT))));
                   rowMap.put("deptDesc",  trim(row.get("dept_desc", String.class)));
                   rowMap.put("payType",   row.get(k.field(PACOSTS.PAY_TYPE)));
                   rowMap.put("payCode",   trim(row.get(k.field(PACOSTS.PAY_CODE))));
                   rowMap.put("codeDesc",  trim(row.get("code_desc", String.class)));
                   int mins = row.get(k.field(PACOSTS.HRS));
                   rowMap.put("hours", mins > 0 ? BigDecimal.valueOf(mins).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP) : null);
                   rowMap.put("amount",    z(row.get(k.field(PACOSTS.AMT))));
                   rows.add(rowMap);
               });
        } catch (Exception e) {
            log.error("getDeptExpenses: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No cost data for period " + p.periodDate() + ".");
        for (Map<String, Object> row : rows) grandTotal = grandTotal.add(z((BigDecimal) row.get("amount")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("COMPANY_NAME", s.getCompanyName());
        params.put("PERIOD_DATE",  p.periodDate().toString());
        params.put("GRAND_TOTAL",  grandTotal);
        params.put("ROW_COUNT",    rows.size());
        return result(rows, params);
    }

    // ── PATL07 — Period Summary ──────────────────────────────────────────────

    public record PeriodSummaryParams(
            String startPg, String endPg,
            LocalDate startDate, LocalDate endDate) {}

    public Map<String, Object> getPeriodSummary(AppSession s, PeriodSummaryParams p) {
        String pg1 = blank(p.startPg()) ? "    " : p.startPg();
        String pg2 = blank(p.endPg())   ? "zzzz" : p.endPg();
        LocalDate d1 = p.startDate() != null ? p.startDate() : LocalDate.of(1900, 1, 1);
        LocalDate d2 = p.endDate()   != null ? p.endDate()   : LocalDate.of(2999, 12, 31);

        var h = PAEHIST.as("h");
        var r2 = PARUNHD.as("r");

        // CASE WHEN aggregates — typed jOOQ DSL.when()/DSL.sum()
        Field<BigDecimal> normalPay  = DSL.sum(DSL.when(h.field(PAEHIST.PAY_TYPE).eq(1),  h.field(PAEHIST.EXT_AMT)).otherwise(BigDecimal.ZERO)).as("normal_pay");
        Field<BigDecimal> overtime   = DSL.sum(DSL.when(h.field(PAEHIST.PAY_TYPE).eq(2),  h.field(PAEHIST.EXT_AMT)).otherwise(BigDecimal.ZERO)).as("overtime");
        Field<BigDecimal> leavePay   = DSL.sum(DSL.when(h.field(PAEHIST.PAY_TYPE).in(5,7,4,8), h.field(PAEHIST.EXT_AMT)).otherwise(BigDecimal.ZERO)).as("leave_pay");
        Field<BigDecimal> super_     = DSL.sum(DSL.when(h.field(PAEHIST.PAY_TYPE).in(20,21), h.field(PAEHIST.EXT_AMT)).otherwise(BigDecimal.ZERO)).as("super_");
        Field<BigDecimal> tax        = DSL.sum(DSL.when(h.field(PAEHIST.PAY_TYPE).eq(18), h.field(PAEHIST.EXT_AMT)).otherwise(BigDecimal.ZERO)).as("tax");
        Field<BigDecimal> deductions = DSL.sum(DSL.when(h.field(PAEHIST.PAY_TYPE).eq(19), h.field(PAEHIST.EXT_AMT)).otherwise(BigDecimal.ZERO)).as("deductions");
        Field<BigDecimal> payrollTax = DSL.sum(DSL.when(h.field(PAEHIST.PAY_TYPE).eq(22), h.field(PAEHIST.EXT_AMT)).otherwise(BigDecimal.ZERO)).as("payroll_tax");
        Field<BigDecimal> total      = DSL.sum(h.field(PAEHIST.EXT_AMT)).as("total");
        Field<Integer>    empCount   = DSL.countDistinct(h.field(PAEHIST.EMPLOYEE_NO)).as("emp_count");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            dsl.select(
                   h.field(PAEHIST.PAYGROUP),
                   h.field(PAEHIST.PAYRUN_NO),
                   r2.field(PARUNHD.PAYRUN_DATE),
                   normalPay, overtime, leavePay, super_, tax, deductions, payrollTax, total, empCount)
               .from(h)
               .join(r2).on(r2.field(PARUNHD.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(r2.field(PARUNHD.PAYRUN_NO).eq(h.field(PAEHIST.PAYRUN_NO))))
               .where(h.field(PAEHIST.COMPANY_NO).eq(s.getCompanyNo())
                   .and(h.field(PAEHIST.PAYGROUP).between(pg1, pg2))
                   .and(h.field(PAEHIST.PAYRUN_DATE).between(d1, d2)))
               .groupBy(h.field(PAEHIST.PAYGROUP), h.field(PAEHIST.PAYRUN_NO), r2.field(PARUNHD.PAYRUN_DATE))
               .orderBy(h.field(PAEHIST.PAYGROUP), r2.field(PARUNHD.PAYRUN_DATE), h.field(PAEHIST.PAYRUN_NO))
               .fetch()
               .forEach(row -> {
                   Map<String, Object> rowMap = new LinkedHashMap<>();
                   rowMap.put("paygroup",   trim(row.get(h.field(PAEHIST.PAYGROUP))));
                   rowMap.put("payrunNo",   row.get(h.field(PAEHIST.PAYRUN_NO)));
                   rowMap.put("payrunDate", row.get(r2.field(PARUNHD.PAYRUN_DATE)));
                   rowMap.put("normalPay",  z(row.get("normal_pay",  BigDecimal.class)));
                   rowMap.put("overtime",   z(row.get("overtime",    BigDecimal.class)));
                   rowMap.put("leavePay",   z(row.get("leave_pay",   BigDecimal.class)));
                   rowMap.put("super_",     z(row.get("super_",      BigDecimal.class)));
                   rowMap.put("tax",        z(row.get("tax",         BigDecimal.class)));
                   rowMap.put("deductions", z(row.get("deductions",  BigDecimal.class)));
                   rowMap.put("payrollTax", z(row.get("payroll_tax", BigDecimal.class)));
                   rowMap.put("total",      z(row.get("total",       BigDecimal.class)));
                   rowMap.put("empCount",   row.get("emp_count", Integer.class));
                   rows.add(rowMap);
               });
        } catch (Exception e) {
            log.error("getPeriodSummary: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No payrun data matched the selection.");
        for (Map<String, Object> row : rows) grandTotal = grandTotal.add(z((BigDecimal) row.get("total")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("COMPANY_NAME", s.getCompanyName());
        params.put("DATE_RANGE",   datRangeDesc(p.startDate(), p.endDate()));
        params.put("GRAND_TOTAL",  grandTotal);
        params.put("ROW_COUNT",    rows.size());
        return result(rows, params);
    }

    // ── PATL60 — Payrun GL Detail ────────────────────────────────────────────

    public Map<String, Object> getPayrunGlDetail(AppSession s, int payrunNo) {
        var h = PAEHIST.as("h");
        var s2 = PASTAFF.as("s");
        var c = PACODES.as("c");
        var g = GLCHART.as("g");

        Field<String> codeDesc = DSL.coalesce(c.field(PACODES.DESC1), h.field(PAEHIST.PAY_CODE)).as("code_desc");
        Field<String> glDesc   = DSL.coalesce(g.field(GLCHART.DESC1), DSL.val("")).as("gl_desc");

        // Load payrun header
        String[] payrunInfo = new String[]{""};
        try {
            dsl.select(PARUNHD.PAYRUN_DATE, PARUNHD.PAYRUN_STATUS)
               .from(PARUNHD)
               .where(PARUNHD.COMPANY_NO.eq(s.getCompanyNo())
                   .and(PARUNHD.PAYRUN_NO.eq(payrunNo)))
               .fetch()
               .forEach(row -> {
                   String st = "F".equals(row.get(PARUNHD.PAYRUN_STATUS)) ? "Completed" : "Posted";
                   LocalDate pd = row.get(PARUNHD.PAYRUN_DATE);
                   payrunInfo[0] = "Payrun " + payrunNo + "  —  " + (pd != null ? pd : "?") + "  (" + st + ")";
               });
        } catch (Exception e) { log.warn("getPayrunGlDetail header: {}", e.getMessage()); }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            dsl.select(
                   h.field(PAEHIST.EMPLOYEE_NO),
                   s2.field(PASTAFF.SURNAME),
                   s2.field(PASTAFF.FIRST_NAME),
                   h.field(PAEHIST.PAY_TYPE),
                   h.field(PAEHIST.PAY_CODE),
                   codeDesc,
                   h.field(PAEHIST.GL_ACCT_NO_MAIN),
                   h.field(PAEHIST.GL_ACCT_NO_SUB),
                   glDesc,
                   h.field(PAEHIST.EXT_AMT))
               .from(h)
               .join(s2).on(s2.field(PASTAFF.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(s2.field(PASTAFF.EMPLOYEE_NO).eq(h.field(PAEHIST.EMPLOYEE_NO))))
               .leftJoin(c).on(c.field(PACODES.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(c.field(PACODES.PAY_CODE).eq(h.field(PAEHIST.PAY_CODE))))
               .leftJoin(g).on(g.field(GLCHART.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(g.field(GLCHART.ACCT_MAIN_NO).eq(h.field(PAEHIST.GL_ACCT_NO_MAIN)))
                   .and(g.field(GLCHART.ACCT_SUB_NO).eq(h.field(PAEHIST.GL_ACCT_NO_SUB))))
               .where(h.field(PAEHIST.COMPANY_NO).eq(s.getCompanyNo())
                   .and(h.field(PAEHIST.PAYRUN_NO).eq(payrunNo)))
               .orderBy(h.field(PAEHIST.EMPLOYEE_NO), h.field(PAEHIST.PAY_TYPE), h.field(PAEHIST.LINE_NO))
               .fetch()
               .forEach(row -> {
                   Map<String, Object> rowMap = new LinkedHashMap<>();
                   rowMap.put("empNo",      row.get(h.field(PAEHIST.EMPLOYEE_NO)));
                   rowMap.put("surname",    trim(row.get(s2.field(PASTAFF.SURNAME))));
                   rowMap.put("firstName",  trim(row.get(s2.field(PASTAFF.FIRST_NAME))));
                   rowMap.put("payType",    row.get(h.field(PAEHIST.PAY_TYPE)));
                   rowMap.put("payCode",    trim(row.get(h.field(PAEHIST.PAY_CODE))));
                   rowMap.put("codeDesc",   trim(row.get("code_desc", String.class)));
                   rowMap.put("glAcctMain", row.get(h.field(PAEHIST.GL_ACCT_NO_MAIN)));
                   rowMap.put("glAcctSub",  row.get(h.field(PAEHIST.GL_ACCT_NO_SUB)));
                   rowMap.put("glDesc",     trim(row.get("gl_desc", String.class)));
                   rowMap.put("amount",     z(row.get(h.field(PAEHIST.EXT_AMT))));
                   rows.add(rowMap);
               });
        } catch (Exception e) {
            log.error("getPayrunGlDetail: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No GL detail found for payrun " + payrunNo + ".");
        for (Map<String, Object> row : rows) grandTotal = grandTotal.add(z((BigDecimal) row.get("amount")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("COMPANY_NAME", s.getCompanyName());
        params.put("PAYRUN_INFO",  payrunInfo[0]);
        params.put("GRAND_TOTAL",  grandTotal);
        params.put("ROW_COUNT",    rows.size());
        return result(rows, params);
    }

    // ── PATL28 — Timesheet History ───────────────────────────────────────────

    public record TimesheetHistoryParams(
            String startPg, String endPg,
            Integer startEmp, Integer endEmp,
            LocalDate startDate, LocalDate endDate) {}

    public Map<String, Object> getTimesheetHistory(AppSession s, TimesheetHistoryParams p) {
        String pg1 = blank(p.startPg()) ? "    " : p.startPg();
        String pg2 = blank(p.endPg())   ? "zzzz" : p.endPg();
        int e1 = p.startEmp() == null || p.startEmp() <= 0 ? 0      : p.startEmp();
        int e2 = p.endEmp()   == null || p.endEmp()   <= 0 ? 999999 : p.endEmp();
        LocalDate d1 = p.startDate() != null ? p.startDate() : LocalDate.of(1900, 1, 1);
        LocalDate d2 = p.endDate()   != null ? p.endDate()   : LocalDate.of(2999, 12, 31);

        var h = PAEHIST.as("h");
        var s2 = PASTAFF.as("s");
        var r2 = PARUNHD.as("r");
        var c = PACODES.as("c");

        Field<String> codeDesc = DSL.coalesce(c.field(PACODES.DESC1), h.field(PAEHIST.PAY_CODE)).as("code_desc");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            dsl.select(
                   h.field(PAEHIST.PAYGROUP),
                   h.field(PAEHIST.EMPLOYEE_NO),
                   s2.field(PASTAFF.SURNAME),
                   s2.field(PASTAFF.FIRST_NAME),
                   s2.field(PASTAFF.DEPT),
                   h.field(PAEHIST.PAYRUN_NO),
                   h.field(PAEHIST.PAYRUN_DATE),
                   r2.field(PARUNHD.START_DATE),
                   r2.field(PARUNHD.END_DATE),
                   h.field(PAEHIST.PAY_TYPE),
                   h.field(PAEHIST.PAY_CODE),
                   codeDesc,
                   h.field(PAEHIST.HRS),
                   h.field(PAEHIST.QTY),
                   h.field(PAEHIST.RATE_PERC),
                   h.field(PAEHIST.EXT_AMT),
                   h.field(PAEHIST.REF))
               .from(h)
               .join(s2).on(s2.field(PASTAFF.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(s2.field(PASTAFF.EMPLOYEE_NO).eq(h.field(PAEHIST.EMPLOYEE_NO))))
               .join(r2).on(r2.field(PARUNHD.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(r2.field(PARUNHD.PAYRUN_NO).eq(h.field(PAEHIST.PAYRUN_NO))))
               .leftJoin(c).on(c.field(PACODES.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(c.field(PACODES.PAY_CODE).eq(h.field(PAEHIST.PAY_CODE))))
               .where(h.field(PAEHIST.COMPANY_NO).eq(s.getCompanyNo())
                   .and(s2.field(PASTAFF.PAYGROUP).between(pg1, pg2))
                   .and(h.field(PAEHIST.EMPLOYEE_NO).between(e1, e2))
                   .and(h.field(PAEHIST.PAYRUN_DATE).between(d1, d2)))
               .orderBy(s2.field(PASTAFF.PAYGROUP), s2.field(PASTAFF.SURNAME),
                        h.field(PAEHIST.EMPLOYEE_NO), h.field(PAEHIST.PAYRUN_DATE),
                        h.field(PAEHIST.PAYRUN_NO), h.field(PAEHIST.PAY_TYPE), h.field(PAEHIST.LINE_NO))
               .fetch()
               .forEach(row -> {
                   Map<String, Object> rowMap = new LinkedHashMap<>();
                   rowMap.put("paygroup",   trim(row.get(h.field(PAEHIST.PAYGROUP))));
                   rowMap.put("empNo",      row.get(h.field(PAEHIST.EMPLOYEE_NO)));
                   rowMap.put("surname",    trim(row.get(s2.field(PASTAFF.SURNAME))));
                   rowMap.put("firstName",  trim(row.get(s2.field(PASTAFF.FIRST_NAME))));
                   rowMap.put("dept",       trim(row.get(s2.field(PASTAFF.DEPT))));
                   rowMap.put("payrunNo",   row.get(h.field(PAEHIST.PAYRUN_NO)));
                   rowMap.put("payrunDate", row.get(h.field(PAEHIST.PAYRUN_DATE)));
                   rowMap.put("startDate",  row.get(r2.field(PARUNHD.START_DATE)));
                   rowMap.put("endDate",    row.get(r2.field(PARUNHD.END_DATE)));
                   rowMap.put("payCode",    trim(row.get(h.field(PAEHIST.PAY_CODE))));
                   rowMap.put("codeDesc",   trim(row.get("code_desc", String.class)));
                   int mins = row.get(h.field(PAEHIST.HRS));
                   rowMap.put("hours",    mins > 0 ? BigDecimal.valueOf(mins).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP) : null);
                   rowMap.put("qty",      row.get(h.field(PAEHIST.QTY)));
                   rowMap.put("ratePerc", row.get(h.field(PAEHIST.RATE_PERC)));
                   rowMap.put("amount",   z(row.get(h.field(PAEHIST.EXT_AMT))));
                   rowMap.put("ref",      trim(row.get(h.field(PAEHIST.REF))));
                   rows.add(rowMap);
               });
        } catch (Exception e) {
            log.error("getTimesheetHistory: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No timesheet history matched the selection.");
        for (Map<String, Object> row : rows) grandTotal = grandTotal.add(z((BigDecimal) row.get("amount")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("COMPANY_NAME", s.getCompanyName());
        params.put("DATE_RANGE",   datRangeDesc(p.startDate(), p.endDate()));
        params.put("GRAND_TOTAL",  grandTotal);
        params.put("ROW_COUNT",    rows.size());
        return result(rows, params);
    }

    // ── PATL40 — Super/Deductions Paid Status ────────────────────────────────

    public record DednStatusParams(
            String startCode, String endCode,
            Integer startEmp, Integer endEmp,
            LocalDate startDate, LocalDate endDate) {}

    public Map<String, Object> getDednStatus(AppSession s, DednStatusParams p) {
        String c1 = blank(p.startCode()) ? "      " : p.startCode();
        String c2 = blank(p.endCode())   ? "zzzzzz" : p.endCode();
        int e1 = p.startEmp() == null || p.startEmp() <= 0 ? 0      : p.startEmp();
        int e2 = p.endEmp()   == null || p.endEmp()   <= 0 ? 999999 : p.endEmp();
        LocalDate d1 = p.startDate() != null ? p.startDate() : LocalDate.of(1900, 1, 1);
        LocalDate d2 = p.endDate()   != null ? p.endDate()   : LocalDate.of(2999, 12, 31);

        var h = PAEHIST.as("h");
        var s2 = PASTAFF.as("s");
        var c = PACODES.as("c");

        Field<String> codeDesc = DSL.coalesce(c.field(PACODES.DESC1), h.field(PAEHIST.PAY_CODE)).as("code_desc");
        Field<String> fundName = DSL.coalesce(c.field(PACODES.FUND_NAME), DSL.val("")).as("fund_name");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            dsl.select(
                   h.field(PAEHIST.PAY_CODE),
                   codeDesc,
                   fundName,
                   h.field(PAEHIST.EMPLOYEE_NO),
                   s2.field(PASTAFF.SURNAME),
                   s2.field(PASTAFF.FIRST_NAME),
                   h.field(PAEHIST.PAYRUN_DATE),
                   h.field(PAEHIST.EXT_AMT),
                   h.field(PAEHIST.PAID_FLAG))
               .from(h)
               .join(s2).on(s2.field(PASTAFF.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(s2.field(PASTAFF.EMPLOYEE_NO).eq(h.field(PAEHIST.EMPLOYEE_NO))))
               .leftJoin(c).on(c.field(PACODES.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(c.field(PACODES.PAY_CODE).eq(h.field(PAEHIST.PAY_CODE))))
               .where(h.field(PAEHIST.COMPANY_NO).eq(s.getCompanyNo())
                   .and(h.field(PAEHIST.PAY_TYPE).in(19, 20, 21))
                   .and(h.field(PAEHIST.PAY_CODE).between(c1, c2))
                   .and(h.field(PAEHIST.EMPLOYEE_NO).between(e1, e2))
                   .and(h.field(PAEHIST.PAYRUN_DATE).between(d1, d2)))
               .orderBy(h.field(PAEHIST.PAY_CODE), s2.field(PASTAFF.SURNAME),
                        h.field(PAEHIST.EMPLOYEE_NO), h.field(PAEHIST.PAYRUN_DATE))
               .fetch()
               .forEach(row -> {
                   Map<String, Object> rowMap = new LinkedHashMap<>();
                   rowMap.put("payCode",    trim(row.get(h.field(PAEHIST.PAY_CODE))));
                   rowMap.put("codeDesc",   trim(row.get("code_desc", String.class)));
                   rowMap.put("fundName",   trim(row.get("fund_name", String.class)));
                   rowMap.put("empNo",      row.get(h.field(PAEHIST.EMPLOYEE_NO)));
                   rowMap.put("surname",    trim(row.get(s2.field(PASTAFF.SURNAME))));
                   rowMap.put("firstName",  trim(row.get(s2.field(PASTAFF.FIRST_NAME))));
                   rowMap.put("payrunDate", row.get(h.field(PAEHIST.PAYRUN_DATE)));
                   rowMap.put("amount",     z(row.get(h.field(PAEHIST.EXT_AMT))));
                   String pf = row.get(h.field(PAEHIST.PAID_FLAG));
                   rowMap.put("paidFlag",   pf != null ? pf.trim() : "");
                   rows.add(rowMap);
               });
        } catch (Exception e) {
            log.error("getDednStatus: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No deduction/super data matched the selection.");
        for (Map<String, Object> row : rows) grandTotal = grandTotal.add(z((BigDecimal) row.get("amount")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("COMPANY_NAME", s.getCompanyName());
        params.put("DATE_RANGE",   datRangeDesc(p.startDate(), p.endDate()));
        params.put("GRAND_TOTAL",  grandTotal);
        params.put("ROW_COUNT",    rows.size());
        return result(rows, params);
    }

    // ── PASP10 — Superannuation by Fund ─────────────────────────────────────

    public record SuperByFundParams(
            String startCode, String endCode,
            Integer startEmp, Integer endEmp,
            int yearNo) {}

    public Map<String, Object> getSuperByFund(AppSession s, SuperByFundParams p) {
        String c1 = blank(p.startCode()) ? "      " : p.startCode();
        String c2 = blank(p.endCode())   ? "zzzzzz" : p.endCode();
        int e1 = p.startEmp() == null || p.startEmp() <= 0 ? 0      : p.startEmp();
        int e2 = p.endEmp()   == null || p.endEmp()   <= 0 ? 999999 : p.endEmp();

        var t = PAYTD.as("t");
        var s2 = PASTAFF.as("s");
        var c = PACODES.as("c");

        Field<String> fundName = DSL.coalesce(c.field(PACODES.FUND_NAME), DSL.val("Unknown Fund")).as("fund_name");
        Field<String> fundAbn  = DSL.coalesce(c.field(PACODES.FUND_ABN),  DSL.val("")).as("fund_abn");
        Field<String> codeDesc = DSL.coalesce(c.field(PACODES.DESC1), t.field(PAYTD.PAY_CODE)).as("code_desc");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            dsl.select(
                   fundName,
                   fundAbn,
                   t.field(PAYTD.PAY_CODE),
                   codeDesc,
                   t.field(PAYTD.EMPLOYEE_NO),
                   s2.field(PASTAFF.SURNAME),
                   s2.field(PASTAFF.FIRST_NAME),
                   t.field(PAYTD.AMT))
               .from(t)
               .join(s2).on(s2.field(PASTAFF.COMPANY_NO).eq(t.field(PAYTD.COMPANY_NO))
                   .and(s2.field(PASTAFF.EMPLOYEE_NO).eq(t.field(PAYTD.EMPLOYEE_NO))))
               .leftJoin(c).on(c.field(PACODES.COMPANY_NO).eq(t.field(PAYTD.COMPANY_NO))
                   .and(c.field(PACODES.PAY_CODE).eq(t.field(PAYTD.PAY_CODE))))
               .where(t.field(PAYTD.COMPANY_NO).eq(s.getCompanyNo())
                   .and(t.field(PAYTD.YEAR_NO).eq(p.yearNo()))
                   .and(t.field(PAYTD.PAY_TYPE).in(20, 21))
                   .and(t.field(PAYTD.PAY_CODE).between(c1, c2))
                   .and(t.field(PAYTD.EMPLOYEE_NO).between(e1, e2)))
               .orderBy(DSL.coalesce(c.field(PACODES.FUND_NAME), DSL.val("Unknown Fund")),
                        t.field(PAYTD.PAY_CODE), s2.field(PASTAFF.SURNAME), t.field(PAYTD.EMPLOYEE_NO))
               .fetch()
               .forEach(row -> {
                   Map<String, Object> rowMap = new LinkedHashMap<>();
                   rowMap.put("fundName",  trim(row.get("fund_name", String.class)));
                   rowMap.put("fundAbn",   trim(row.get("fund_abn",  String.class)));
                   rowMap.put("payCode",   trim(row.get(t.field(PAYTD.PAY_CODE))));
                   rowMap.put("codeDesc",  trim(row.get("code_desc", String.class)));
                   rowMap.put("empNo",     row.get(t.field(PAYTD.EMPLOYEE_NO)));
                   rowMap.put("surname",   trim(row.get(s2.field(PASTAFF.SURNAME))));
                   rowMap.put("firstName", trim(row.get(s2.field(PASTAFF.FIRST_NAME))));
                   rowMap.put("amount",    z(row.get(t.field(PAYTD.AMT))));
                   rows.add(rowMap);
               });
        } catch (Exception e) {
            log.error("getSuperByFund: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No super data matched the selection.");
        for (Map<String, Object> row : rows) grandTotal = grandTotal.add(z((BigDecimal) row.get("amount")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("COMPANY_NAME", s.getCompanyName());
        params.put("YEAR_NO",      p.yearNo());
        params.put("GRAND_TOTAL",  grandTotal);
        params.put("ROW_COUNT",    rows.size());
        return result(rows, params);
    }

    // ── PATL26 — Extended Superannuation ────────────────────────────────────

    public record ExtendedSuperParams(
            String startCode, String endCode,
            Integer startEmp, Integer endEmp,
            LocalDate startDate, LocalDate endDate) {}

    public Map<String, Object> getExtendedSuper(AppSession s, ExtendedSuperParams p) {
        String c1 = blank(p.startCode()) ? "      " : p.startCode();
        String c2 = blank(p.endCode())   ? "zzzzzz" : p.endCode();
        int e1 = p.startEmp() == null || p.startEmp() <= 0 ? 0      : p.startEmp();
        int e2 = p.endEmp()   == null || p.endEmp()   <= 0 ? 999999 : p.endEmp();
        LocalDate d1 = p.startDate() != null ? p.startDate() : LocalDate.of(1900, 1, 1);
        LocalDate d2 = p.endDate()   != null ? p.endDate()   : LocalDate.of(2999, 12, 31);

        var h = PAEHIST.as("h");
        var s2 = PASTAFF.as("s");
        var c = PACODES.as("c");

        Field<String> codeDesc      = DSL.coalesce(c.field(PACODES.DESC1), h.field(PAEHIST.PAY_CODE)).as("code_desc");
        Field<String> fundName      = DSL.coalesce(c.field(PACODES.FUND_NAME), DSL.val("")).as("fund_name");
        Field<String> fundAbn       = DSL.coalesce(c.field(PACODES.FUND_ABN), DSL.val("")).as("fund_abn");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            dsl.select(
                   h.field(PAEHIST.PAY_CODE),
                   codeDesc,
                   fundName,
                   fundAbn,
                   h.field(PAEHIST.EMPLOYEE_NO),
                   s2.field(PASTAFF.SURNAME),
                   s2.field(PASTAFF.FIRST_NAME),
                   s2.field(PASTAFF.TAX_FILE_NO),
                   h.field(PAEHIST.PAYRUN_DATE),
                   h.field(PAEHIST.EXT_AMT),
                   c.field(PACODES.SUPER_BEFORE_AFTER_TAX))
               .from(h)
               .join(s2).on(s2.field(PASTAFF.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(s2.field(PASTAFF.EMPLOYEE_NO).eq(h.field(PAEHIST.EMPLOYEE_NO))))
               .leftJoin(c).on(c.field(PACODES.COMPANY_NO).eq(h.field(PAEHIST.COMPANY_NO))
                   .and(c.field(PACODES.PAY_CODE).eq(h.field(PAEHIST.PAY_CODE))))
               .where(h.field(PAEHIST.COMPANY_NO).eq(s.getCompanyNo())
                   .and(h.field(PAEHIST.PAY_TYPE).in(20, 21))
                   .and(h.field(PAEHIST.PAY_CODE).between(c1, c2))
                   .and(h.field(PAEHIST.EMPLOYEE_NO).between(e1, e2))
                   .and(h.field(PAEHIST.PAYRUN_DATE).between(d1, d2)))
               .orderBy(h.field(PAEHIST.PAY_CODE), s2.field(PASTAFF.SURNAME),
                        h.field(PAEHIST.EMPLOYEE_NO), h.field(PAEHIST.PAYRUN_DATE))
               .fetch()
               .forEach(row -> {
                   Map<String, Object> rowMap = new LinkedHashMap<>();
                   rowMap.put("payCode",      trim(row.get(h.field(PAEHIST.PAY_CODE))));
                   rowMap.put("codeDesc",     trim(row.get("code_desc", String.class)));
                   rowMap.put("fundName",     trim(row.get("fund_name", String.class)));
                   rowMap.put("fundAbn",      trim(row.get("fund_abn",  String.class)));
                   rowMap.put("empNo",        row.get(h.field(PAEHIST.EMPLOYEE_NO)));
                   rowMap.put("surname",      trim(row.get(s2.field(PASTAFF.SURNAME))));
                   rowMap.put("firstName",    trim(row.get(s2.field(PASTAFF.FIRST_NAME))));
                   long tfnLong = row.get(s2.field(PASTAFF.TAX_FILE_NO)) != null
                       ? row.get(s2.field(PASTAFF.TAX_FILE_NO)) : 0L;
                   rowMap.put("maskedTfn",    com.landmarksoftware.payroll.model.Employee.maskTfn(String.valueOf(tfnLong)));
                   rowMap.put("payrunDate",   row.get(h.field(PAEHIST.PAYRUN_DATE)));
                   rowMap.put("amount",       z(row.get(h.field(PAEHIST.EXT_AMT))));
                   String bat = row.get(c.field(PACODES.SUPER_BEFORE_AFTER_TAX));
                   rowMap.put("beforeAfterTax", bat != null ? bat.trim() : "");
                   rows.add(rowMap);
               });
        } catch (Exception e) {
            log.error("getExtendedSuper: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No extended super data matched the selection.");
        for (Map<String, Object> row : rows) grandTotal = grandTotal.add(z((BigDecimal) row.get("amount")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("COMPANY_NAME", s.getCompanyName());
        params.put("DATE_RANGE",   datRangeDesc(p.startDate(), p.endDate()));
        params.put("GRAND_TOTAL",  grandTotal);
        params.put("ROW_COUNT",    rows.size());
        return result(rows, params);
    }

    // ── Date range helper ────────────────────────────────────────────────────

    private static String datRangeDesc(LocalDate from, LocalDate to) {
        String f = from != null ? from.toString() : "";
        String t = to   != null ? to.toString()   : "";
        if (f.isEmpty() && t.isEmpty()) return "";
        if (f.isEmpty()) return "to " + t;
        if (t.isEmpty()) return "from " + f;
        return f + " to " + t;
    }
}
