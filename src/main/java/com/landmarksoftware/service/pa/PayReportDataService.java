package com.landmarksoftware.service.pa;

import com.landmarksoftware.model.AppSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Data service for PA (Payroll) reporting module.
 * All SQL here; controllers contain only JavaFX.
 */
@Service
public class PayReportDataService {

    private static final Logger log = LoggerFactory.getLogger(PayReportDataService.class);

    @Autowired private JdbcTemplate jdbc;

    // ── Shared lookup types ──────────────────────────────────────────────────

    public record CodeName(String code, String name) {
        @Override public String toString() { return name; }
    }

    // ── Lookup helpers for new PA reports ───────────────────────────────────

    /** Posted/completed payruns for PATL60 (status F or P), newest first. */
    public List<CodeName> getPostedPayruns(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT payrun_no, payrun_date, payrun_status FROM parunhd " +
                "WHERE company_no=? AND payrun_status IN('F','P') ORDER BY payrun_no DESC",
                (RowCallbackHandler) rs -> {
                    int no = rs.getInt("payrun_no");
                    String status = "F".equals(rs.getString("payrun_status")) ? "Completed" : "Posted";
                    java.sql.Date d = rs.getDate("payrun_date");
                    list.add(new CodeName(String.valueOf(no),
                        no + "  —  " + (d != null ? d.toString() : "?") + "  (" + status + ")"));
                }, s.getCompanyNo());
        } catch (Exception e) { log.warn("getPostedPayruns: {}", e.getMessage()); }
        return list;
    }

    /** Distinct period_end_date values from pacosts, newest first. */
    public List<CodeName> getDistinctCostPeriods(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT DISTINCT period_end_date FROM pacosts WHERE company_no=? ORDER BY period_end_date DESC",
                (RowCallbackHandler) rs -> {
                    java.sql.Date d = rs.getDate("period_end_date");
                    if (d != null) {
                        String ds = d.toString();
                        list.add(new CodeName(ds, ds));
                    }
                }, s.getCompanyNo());
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
            jdbc.query(
                "SELECT DISTINCT year_no FROM paytd WHERE company_no=? ORDER BY year_no DESC",
                rs -> {
                    int y = rs.getInt("year_no");
                    list.add(new CodeName(String.valueOf(y), "FY " + (y - 1) + "-" + String.valueOf(y).substring(2)));
                },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getYtdYears: {}", e.getMessage()); }
        return list;
    }

    /** Paygroups for this company: "(All)" + each paygroup. */
    public List<CodeName> getPaygroups(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(ALL);
        try {
            jdbc.query("SELECT paygroup, desc1 FROM pagroup WHERE company_no=? ORDER BY paygroup",
                (RowCallbackHandler) rs -> list.add(new CodeName(trim(rs.getString("paygroup")),
                    trim(rs.getString("paygroup")) + "  —  " + trim(rs.getString("desc1")))),
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getPaygroups: {}", e.getMessage()); }
        return list;
    }

    /** Distinct departments for this company: "(All)" + each dept. */
    public List<CodeName> getDepts(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(ALL);
        try {
            jdbc.query("SELECT dept, MIN(desc1) d FROM padepts WHERE company_no=? GROUP BY dept ORDER BY dept",
                (RowCallbackHandler) rs -> list.add(new CodeName(trim(rs.getString("dept")),
                    trim(rs.getString("dept")) + "  —  " + trim(rs.getString("d")))),
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getDepts: {}", e.getMessage()); }
        return list;
    }

    /** Active employees for this company: "(All)" + each employee. */
    public List<CodeName> getEmployees(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(ALL);
        try {
            jdbc.query(
                "SELECT employee_no, surname, first_name FROM pastaff " +
                "WHERE company_no=? AND employee_status <> 'T' ORDER BY employee_no",
                rs -> {
                    int no = rs.getInt("employee_no");
                    list.add(new CodeName(String.valueOf(no),
                        no + "  —  " + trim(rs.getString("surname")) + ", " + trim(rs.getString("first_name"))));
                },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getEmployees: {}", e.getMessage()); }
        return list;
    }

    /** Pay codes for this company: "(All)" + each code. */
    public List<CodeName> getPayCodes(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(ALL);
        try {
            jdbc.query("SELECT pay_code, desc1 FROM pacodes WHERE company_no=? ORDER BY pay_code",
                (RowCallbackHandler) rs -> list.add(new CodeName(trim(rs.getString("pay_code")),
                    trim(rs.getString("pay_code")) + "  —  " + trim(rs.getString("desc1")))),
                s.getCompanyNo());
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

        String sql =
            "SELECT t.employee_no, s.surname, s.first_name, s.paygroup, s.dept, " +
            "       t.pay_type, t.pay_code, COALESCE(c.desc1, t.pay_code) code_desc, " +
            "       t.amt, t.hrs " +
            "FROM paytd t " +
            "JOIN pastaff s ON s.company_no=t.company_no AND s.employee_no=t.employee_no " +
            "LEFT JOIN pacodes c ON c.company_no=t.company_no AND c.pay_code=t.pay_code " +
            "WHERE t.company_no=? AND t.year_no=? " +
            "  AND s.paygroup BETWEEN ? AND ? " +
            "  AND s.dept BETWEEN ? AND ? " +
            "  AND t.pay_code BETWEEN ? AND ? " +
            "  AND t.employee_no BETWEEN ? AND ? " +
            "ORDER BY s.surname, s.first_name, t.employee_no, t.pay_type, t.pay_code";

        // Collect raw query rows first so we can compute per-employee totals.
        List<Map<String, Object>> rawRows = new ArrayList<>();
        try {
            jdbc.query(sql, rs -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("empNo",     rs.getInt("employee_no"));
                r.put("surname",   trim(rs.getString("surname")));
                r.put("firstName", trim(rs.getString("first_name")));
                r.put("paygroup",  trim(rs.getString("paygroup")));
                r.put("dept",      trim(rs.getString("dept")));
                r.put("payType",   rs.getInt("pay_type"));
                r.put("payCode",   trim(rs.getString("pay_code")));
                r.put("codeDesc",  trim(rs.getString("code_desc")));
                int mins = rs.getInt("hrs");
                r.put("hours",  mins > 0 ? BigDecimal.valueOf(mins).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP) : null);
                BigDecimal amt0 = z(rs.getBigDecimal("amt"));
                r.put("payAmt", amt0);
                r.put("amtStr", fmtAmt(amt0));
                rawRows.add(r);
            }, s.getCompanyNo(), p.yearNo(), pg1, pg2, d1, d2, c1, c2, e1, e2);
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
            java.time.LocalDate startDate, java.time.LocalDate endDate) {}

    public Map<String, Object> getHistoryDetail(AppSession s, HistoryDetailParams p) {
        int e1 = p.startEmp() == null || p.startEmp() <= 0 ? 0      : p.startEmp();
        int e2 = p.endEmp()   == null || p.endEmp()   <= 0 ? 999999 : p.endEmp();
        String pg1 = blank(p.startPg()) ? "    " : p.startPg();
        String pg2 = blank(p.endPg())   ? "zzzz" : p.endPg();
        java.sql.Date d1 = p.startDate() != null ? java.sql.Date.valueOf(p.startDate()) : java.sql.Date.valueOf("1900-01-01");
        java.sql.Date d2 = p.endDate()   != null ? java.sql.Date.valueOf(p.endDate())   : java.sql.Date.valueOf("2999-12-31");

        String sql =
            "SELECT h.employee_no, s.surname, s.first_name, s.paygroup, s.dept, " +
            "       h.payrun_no, h.payrun_date, r.start_date, r.end_date, " +
            "       h.pay_type, h.pay_code, COALESCE(c.desc1, h.pay_code) code_desc, " +
            "       h.hrs, h.ext_amt, h.ref " +
            "FROM paehist h " +
            "JOIN pastaff s ON s.company_no=h.company_no AND s.employee_no=h.employee_no " +
            "LEFT JOIN pacodes c ON c.company_no=h.company_no AND c.pay_code=h.pay_code " +
            "JOIN parunhd r ON r.company_no=h.company_no AND r.payrun_no=h.payrun_no " +
            "WHERE h.company_no=? AND h.employee_no BETWEEN ? AND ? " +
            "  AND s.paygroup BETWEEN ? AND ? AND h.payrun_date BETWEEN ? AND ? " +
            "ORDER BY s.surname, s.first_name, h.employee_no, h.payrun_date, h.payrun_no, h.pay_type, h.line_no";

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            jdbc.query(sql, rs -> {
                Map<String, Object> r2 = new LinkedHashMap<>();
                r2.put("empNo",      rs.getInt("employee_no"));
                r2.put("surname",    trim(rs.getString("surname")));
                r2.put("firstName",  trim(rs.getString("first_name")));
                r2.put("paygroup",   trim(rs.getString("paygroup")));
                r2.put("dept",       trim(rs.getString("dept")));
                r2.put("payrunNo",   rs.getInt("payrun_no"));
                r2.put("payrunDate", rs.getDate("payrun_date"));
                r2.put("payCode",    trim(rs.getString("pay_code")));
                r2.put("codeDesc",   trim(rs.getString("code_desc")));
                int mins = rs.getInt("hrs");
                r2.put("hours", mins > 0 ? BigDecimal.valueOf(mins).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP) : null);
                r2.put("amount",     z(rs.getBigDecimal("ext_amt")));
                r2.put("ref",        trim(rs.getString("ref")));
                rows.add(r2);
            }, s.getCompanyNo(), e1, e2, pg1, pg2, d1, d2);
        } catch (Exception e) {
            log.error("getHistoryDetail: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No history data matched the selection.");

        for (Map<String, Object> r2 : rows) grandTotal = grandTotal.add(z((BigDecimal) r2.get("amount")));

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
            java.time.LocalDate startDate, java.time.LocalDate endDate,
            String sortBy) {} // "Employee", "Paygroup", "Payrun date"

    public Map<String, Object> getHistorySummary(AppSession s, HistorySummaryParams p) {
        int e1 = p.startEmp() == null || p.startEmp() <= 0 ? 0      : p.startEmp();
        int e2 = p.endEmp()   == null || p.endEmp()   <= 0 ? 999999 : p.endEmp();
        String pg1 = blank(p.startPg())   ? "    " : p.startPg();
        String pg2 = blank(p.endPg())     ? "zzzz" : p.endPg();
        String d1s = blank(p.startDept()) ? "    " : p.startDept();
        String d2s = blank(p.endDept())   ? "zzzz" : p.endDept();
        java.sql.Date dt1 = p.startDate() != null ? java.sql.Date.valueOf(p.startDate()) : java.sql.Date.valueOf("1900-01-01");
        java.sql.Date dt2 = p.endDate()   != null ? java.sql.Date.valueOf(p.endDate())   : java.sql.Date.valueOf("2999-12-31");

        boolean byPayrun = "Payrun date".equals(p.sortBy());
        boolean byPaygroup = "Paygroup".equals(p.sortBy());

        String groupBy = byPayrun
            ? "h.employee_no, s.surname, s.first_name, s.paygroup, s.dept, h.pay_type, h.pay_code, code_desc, h.payrun_no, h.payrun_date"
            : "h.employee_no, s.surname, s.first_name, s.paygroup, s.dept, h.pay_type, h.pay_code, code_desc";
        String orderBy = byPayrun
            ? "h.payrun_date, h.payrun_no, s.surname, s.first_name, h.employee_no, h.pay_type"
            : byPaygroup
                ? "s.paygroup, s.surname, s.first_name, h.employee_no, h.pay_type"
                : "s.surname, s.first_name, h.employee_no, h.pay_type";

        String selectExtra = byPayrun ? ", h.payrun_no, h.payrun_date" : "";
        String sql =
            "SELECT h.employee_no, s.surname, s.first_name, s.paygroup, s.dept, " +
            "       h.pay_type, h.pay_code, COALESCE(c.desc1,h.pay_code) code_desc, " +
            "       SUM(h.hrs) total_hrs, SUM(h.ext_amt) total_amt" + selectExtra + " " +
            "FROM paehist h " +
            "JOIN pastaff s ON s.company_no=h.company_no AND s.employee_no=h.employee_no " +
            "LEFT JOIN pacodes c ON c.company_no=h.company_no AND c.pay_code=h.pay_code " +
            "WHERE h.company_no=? AND h.employee_no BETWEEN ? AND ? " +
            "  AND s.paygroup BETWEEN ? AND ? AND s.dept BETWEEN ? AND ? " +
            "  AND h.payrun_date BETWEEN ? AND ? " +
            "GROUP BY " + groupBy + " ORDER BY " + orderBy;

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            jdbc.query(sql, rs -> {
                Map<String, Object> r2 = new LinkedHashMap<>();
                r2.put("empNo",     rs.getInt("employee_no"));
                r2.put("surname",   trim(rs.getString("surname")));
                r2.put("firstName", trim(rs.getString("first_name")));
                r2.put("paygroup",  trim(rs.getString("paygroup")));
                r2.put("dept",      trim(rs.getString("dept")));
                r2.put("payType",   rs.getInt("pay_type"));
                r2.put("payCode",   trim(rs.getString("pay_code")));
                r2.put("codeDesc",  trim(rs.getString("code_desc")));
                int mins = rs.getInt("total_hrs");
                r2.put("hours", mins > 0 ? BigDecimal.valueOf(mins).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP) : null);
                r2.put("amount",    z(rs.getBigDecimal("total_amt")));
                if (byPayrun) {
                    r2.put("payrunNo",   rs.getInt("payrun_no"));
                    r2.put("payrunDate", rs.getDate("payrun_date"));
                } else {
                    r2.put("payrunNo",   null);
                    r2.put("payrunDate", null);
                }
                rows.add(r2);
            }, s.getCompanyNo(), e1, e2, pg1, pg2, d1s, d2s, dt1, dt2);
        } catch (Exception e) {
            log.error("getHistorySummary: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No history data matched the selection.");
        for (Map<String, Object> r2 : rows) grandTotal = grandTotal.add(z((BigDecimal) r2.get("amount")));

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
        String orderBy = byFund
            ? "COALESCE(c.fund_name,''), t.pay_code, s.surname, t.employee_no"
            : "t.pay_code, s.surname, t.employee_no";

        String sql =
            "SELECT t.pay_code, t.pay_type, COALESCE(c.desc1, t.pay_code) code_desc, " +
            "       COALESCE(c.fund_name,'') fund_name, " +
            "       t.employee_no, s.surname, s.first_name, t.amt " +
            "FROM paytd t " +
            "JOIN pastaff s ON s.company_no=t.company_no AND s.employee_no=t.employee_no " +
            "LEFT JOIN pacodes c ON c.company_no=t.company_no AND c.pay_code=t.pay_code " +
            "WHERE t.company_no=? AND t.year_no=? AND t.pay_type IN(19,20,21) " +
            "  AND t.pay_code BETWEEN ? AND ? AND t.employee_no BETWEEN ? AND ? " +
            "ORDER BY " + orderBy;

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            jdbc.query(sql, rs -> {
                Map<String, Object> r2 = new LinkedHashMap<>();
                r2.put("payCode",   trim(rs.getString("pay_code")));
                r2.put("payType",   rs.getInt("pay_type"));
                r2.put("codeDesc",  trim(rs.getString("code_desc")));
                r2.put("fundName",  trim(rs.getString("fund_name")));
                r2.put("empNo",     rs.getInt("employee_no"));
                r2.put("surname",   trim(rs.getString("surname")));
                r2.put("firstName", trim(rs.getString("first_name")));
                r2.put("amount",    z(rs.getBigDecimal("amt")));
                rows.add(r2);
            }, s.getCompanyNo(), p.yearNo(), c1, c2, e1, e2);
        } catch (Exception e) {
            log.error("getDednSuper: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No deduction/super data matched the selection.");
        for (Map<String, Object> r2 : rows) grandTotal = grandTotal.add(z((BigDecimal) r2.get("amount")));

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
            java.time.LocalDate periodDate,
            String startPg, String endPg,
            String startDept, String endDept) {}

    public Map<String, Object> getDeptExpenses(AppSession s, DeptExpensesParams p) {
        if (p.periodDate() == null) return warn("Select a period end date.");
        String pg1 = blank(p.startPg())   ? "    " : p.startPg();
        String pg2 = blank(p.endPg())     ? "zzzz" : p.endPg();
        String d1  = blank(p.startDept()) ? "    " : p.startDept();
        String d2  = blank(p.endDept())   ? "zzzz" : p.endDept();
        java.sql.Date pd = java.sql.Date.valueOf(p.periodDate());

        String sql =
            "SELECT k.paygroup, g.desc1 pg_desc, k.dept, d.desc1 dept_desc, " +
            "       k.pay_type, k.pay_code, COALESCE(c.desc1, k.pay_code) code_desc, " +
            "       k.amt, k.hrs " +
            "FROM pacosts k " +
            "LEFT JOIN pagroup g ON g.company_no=k.company_no AND g.paygroup=k.paygroup " +
            "LEFT JOIN padepts d ON d.company_no=k.company_no AND d.dept=k.dept AND d.paygroup=k.paygroup " +
            "LEFT JOIN pacodes c ON c.company_no=k.company_no AND c.pay_code=k.pay_code " +
            "WHERE k.company_no=? AND k.period_end_date=? " +
            "  AND k.paygroup BETWEEN ? AND ? AND k.dept BETWEEN ? AND ? " +
            "ORDER BY k.paygroup, k.dept, k.pay_type, k.pay_code";

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            jdbc.query(sql, rs -> {
                Map<String, Object> r2 = new LinkedHashMap<>();
                r2.put("paygroup",  trim(rs.getString("paygroup")));
                r2.put("pgDesc",    trim(rs.getString("pg_desc")));
                r2.put("dept",      trim(rs.getString("dept")));
                r2.put("deptDesc",  trim(rs.getString("dept_desc")));
                r2.put("payType",   rs.getInt("pay_type"));
                r2.put("payCode",   trim(rs.getString("pay_code")));
                r2.put("codeDesc",  trim(rs.getString("code_desc")));
                int mins = rs.getInt("hrs");
                r2.put("hours", mins > 0 ? BigDecimal.valueOf(mins).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP) : null);
                r2.put("amount",    z(rs.getBigDecimal("amt")));
                rows.add(r2);
            }, s.getCompanyNo(), pd, pg1, pg2, d1, d2);
        } catch (Exception e) {
            log.error("getDeptExpenses: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No cost data for period " + p.periodDate() + ".");
        for (Map<String, Object> r2 : rows) grandTotal = grandTotal.add(z((BigDecimal) r2.get("amount")));

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
            java.time.LocalDate startDate, java.time.LocalDate endDate) {}

    public Map<String, Object> getPeriodSummary(AppSession s, PeriodSummaryParams p) {
        String pg1 = blank(p.startPg()) ? "    " : p.startPg();
        String pg2 = blank(p.endPg())   ? "zzzz" : p.endPg();
        java.sql.Date d1 = p.startDate() != null ? java.sql.Date.valueOf(p.startDate()) : java.sql.Date.valueOf("1900-01-01");
        java.sql.Date d2 = p.endDate()   != null ? java.sql.Date.valueOf(p.endDate())   : java.sql.Date.valueOf("2999-12-31");

        String sql =
            "SELECT h.paygroup, h.payrun_no, r.payrun_date, " +
            "       SUM(CASE WHEN h.pay_type=1 THEN h.ext_amt ELSE 0 END) normal_pay, " +
            "       SUM(CASE WHEN h.pay_type=2 THEN h.ext_amt ELSE 0 END) overtime, " +
            "       SUM(CASE WHEN h.pay_type IN(5,7,4,8) THEN h.ext_amt ELSE 0 END) leave_pay, " +
            "       SUM(CASE WHEN h.pay_type IN(20,21) THEN h.ext_amt ELSE 0 END) super_, " +
            "       SUM(CASE WHEN h.pay_type=18 THEN h.ext_amt ELSE 0 END) tax, " +
            "       SUM(CASE WHEN h.pay_type=19 THEN h.ext_amt ELSE 0 END) deductions, " +
            "       SUM(CASE WHEN h.pay_type=22 THEN h.ext_amt ELSE 0 END) payroll_tax, " +
            "       SUM(h.ext_amt) total, COUNT(DISTINCT h.employee_no) emp_count " +
            "FROM paehist h " +
            "JOIN parunhd r ON r.company_no=h.company_no AND r.payrun_no=h.payrun_no " +
            "WHERE h.company_no=? AND h.paygroup BETWEEN ? AND ? " +
            "  AND h.payrun_date BETWEEN ? AND ? " +
            "GROUP BY h.paygroup, h.payrun_no, r.payrun_date " +
            "ORDER BY h.paygroup, r.payrun_date, h.payrun_no";

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            jdbc.query(sql, rs -> {
                Map<String, Object> r2 = new LinkedHashMap<>();
                r2.put("paygroup",   trim(rs.getString("paygroup")));
                r2.put("payrunNo",   rs.getInt("payrun_no"));
                r2.put("payrunDate", rs.getDate("payrun_date"));
                r2.put("normalPay",  z(rs.getBigDecimal("normal_pay")));
                r2.put("overtime",   z(rs.getBigDecimal("overtime")));
                r2.put("leavePay",   z(rs.getBigDecimal("leave_pay")));
                r2.put("super_",     z(rs.getBigDecimal("super_")));
                r2.put("tax",        z(rs.getBigDecimal("tax")));
                r2.put("deductions", z(rs.getBigDecimal("deductions")));
                r2.put("payrollTax", z(rs.getBigDecimal("payroll_tax")));
                r2.put("total",      z(rs.getBigDecimal("total")));
                r2.put("empCount",   rs.getInt("emp_count"));
                rows.add(r2);
            }, s.getCompanyNo(), pg1, pg2, d1, d2);
        } catch (Exception e) {
            log.error("getPeriodSummary: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No payrun data matched the selection.");
        for (Map<String, Object> r2 : rows) grandTotal = grandTotal.add(z((BigDecimal) r2.get("total")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("COMPANY_NAME", s.getCompanyName());
        params.put("DATE_RANGE",   datRangeDesc(p.startDate(), p.endDate()));
        params.put("GRAND_TOTAL",  grandTotal);
        params.put("ROW_COUNT",    rows.size());
        return result(rows, params);
    }

    // ── PATL60 — Payrun GL Detail ────────────────────────────────────────────

    public Map<String, Object> getPayrunGlDetail(AppSession s, int payrunNo) {
        String sql =
            "SELECT h.employee_no, s.surname, s.first_name, " +
            "       h.pay_type, h.pay_code, COALESCE(c.desc1, h.pay_code) code_desc, " +
            "       h.gl_acct_no_main, h.gl_acct_no_sub, " +
            "       COALESCE(g.desc1,'') gl_desc, h.ext_amt " +
            "FROM paehist h " +
            "JOIN pastaff s ON s.company_no=h.company_no AND s.employee_no=h.employee_no " +
            "LEFT JOIN pacodes c ON c.company_no=h.company_no AND c.pay_code=h.pay_code " +
            "LEFT JOIN glchart g ON g.company_no=h.company_no " +
            "     AND g.acct_main_no=h.gl_acct_no_main AND g.acct_sub_no=h.gl_acct_no_sub " +
            "WHERE h.company_no=? AND h.payrun_no=? " +
            "ORDER BY h.employee_no, h.pay_type, h.line_no";

        // Load payrun header
        String[] payrunInfo = new String[]{""};
        try {
            jdbc.query("SELECT payrun_date, payrun_status FROM parunhd WHERE company_no=? AND payrun_no=?",
                (RowCallbackHandler) rs -> {
                    String st = "F".equals(rs.getString("payrun_status")) ? "Completed" : "Posted";
                    payrunInfo[0] = "Payrun " + payrunNo + "  —  " + rs.getDate("payrun_date") + "  (" + st + ")";
                }, s.getCompanyNo(), payrunNo);
        } catch (Exception e) { log.warn("getPayrunGlDetail header: {}", e.getMessage()); }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            jdbc.query(sql, rs -> {
                Map<String, Object> r2 = new LinkedHashMap<>();
                r2.put("empNo",      rs.getInt("employee_no"));
                r2.put("surname",    trim(rs.getString("surname")));
                r2.put("firstName",  trim(rs.getString("first_name")));
                r2.put("payType",    rs.getInt("pay_type"));
                r2.put("payCode",    trim(rs.getString("pay_code")));
                r2.put("codeDesc",   trim(rs.getString("code_desc")));
                r2.put("glAcctMain", rs.getInt("gl_acct_no_main"));
                r2.put("glAcctSub",  rs.getInt("gl_acct_no_sub"));
                r2.put("glDesc",     trim(rs.getString("gl_desc")));
                r2.put("amount",     z(rs.getBigDecimal("ext_amt")));
                rows.add(r2);
            }, s.getCompanyNo(), payrunNo);
        } catch (Exception e) {
            log.error("getPayrunGlDetail: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No GL detail found for payrun " + payrunNo + ".");
        for (Map<String, Object> r2 : rows) grandTotal = grandTotal.add(z((BigDecimal) r2.get("amount")));

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
            java.time.LocalDate startDate, java.time.LocalDate endDate) {}

    public Map<String, Object> getTimesheetHistory(AppSession s, TimesheetHistoryParams p) {
        String pg1 = blank(p.startPg()) ? "    " : p.startPg();
        String pg2 = blank(p.endPg())   ? "zzzz" : p.endPg();
        int e1 = p.startEmp() == null || p.startEmp() <= 0 ? 0      : p.startEmp();
        int e2 = p.endEmp()   == null || p.endEmp()   <= 0 ? 999999 : p.endEmp();
        java.sql.Date d1 = p.startDate() != null ? java.sql.Date.valueOf(p.startDate()) : java.sql.Date.valueOf("1900-01-01");
        java.sql.Date d2 = p.endDate()   != null ? java.sql.Date.valueOf(p.endDate())   : java.sql.Date.valueOf("2999-12-31");

        String sql =
            "SELECT h.paygroup, h.employee_no, s.surname, s.first_name, s.dept, " +
            "       h.payrun_no, h.payrun_date, r.start_date, r.end_date, " +
            "       h.pay_type, h.pay_code, COALESCE(c.desc1,h.pay_code) code_desc, " +
            "       h.hrs, h.qty, h.rate_perc, h.ext_amt, h.ref " +
            "FROM paehist h " +
            "JOIN pastaff s ON s.company_no=h.company_no AND s.employee_no=h.employee_no " +
            "JOIN parunhd r ON r.company_no=h.company_no AND r.payrun_no=h.payrun_no " +
            "LEFT JOIN pacodes c ON c.company_no=h.company_no AND c.pay_code=h.pay_code " +
            "WHERE h.company_no=? AND s.paygroup BETWEEN ? AND ? " +
            "  AND h.employee_no BETWEEN ? AND ? AND h.payrun_date BETWEEN ? AND ? " +
            "ORDER BY s.paygroup, s.surname, h.employee_no, h.payrun_date, h.payrun_no, h.pay_type, h.line_no";

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            jdbc.query(sql, rs -> {
                Map<String, Object> r2 = new LinkedHashMap<>();
                r2.put("paygroup",   trim(rs.getString("paygroup")));
                r2.put("empNo",      rs.getInt("employee_no"));
                r2.put("surname",    trim(rs.getString("surname")));
                r2.put("firstName",  trim(rs.getString("first_name")));
                r2.put("dept",       trim(rs.getString("dept")));
                r2.put("payrunNo",   rs.getInt("payrun_no"));
                r2.put("payrunDate", rs.getDate("payrun_date"));
                r2.put("startDate",  rs.getDate("start_date"));
                r2.put("endDate",    rs.getDate("end_date"));
                r2.put("payCode",    trim(rs.getString("pay_code")));
                r2.put("codeDesc",   trim(rs.getString("code_desc")));
                int mins = rs.getInt("hrs");
                r2.put("hours",    mins > 0 ? BigDecimal.valueOf(mins).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP) : null);
                r2.put("qty",      rs.getBigDecimal("qty"));
                r2.put("ratePerc", rs.getBigDecimal("rate_perc"));
                r2.put("amount",   z(rs.getBigDecimal("ext_amt")));
                r2.put("ref",      trim(rs.getString("ref")));
                rows.add(r2);
            }, s.getCompanyNo(), pg1, pg2, e1, e2, d1, d2);
        } catch (Exception e) {
            log.error("getTimesheetHistory: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No timesheet history matched the selection.");
        for (Map<String, Object> r2 : rows) grandTotal = grandTotal.add(z((BigDecimal) r2.get("amount")));

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
            java.time.LocalDate startDate, java.time.LocalDate endDate) {}

    public Map<String, Object> getDednStatus(AppSession s, DednStatusParams p) {
        String c1 = blank(p.startCode()) ? "      " : p.startCode();
        String c2 = blank(p.endCode())   ? "zzzzzz" : p.endCode();
        int e1 = p.startEmp() == null || p.startEmp() <= 0 ? 0      : p.startEmp();
        int e2 = p.endEmp()   == null || p.endEmp()   <= 0 ? 999999 : p.endEmp();
        java.sql.Date d1 = p.startDate() != null ? java.sql.Date.valueOf(p.startDate()) : java.sql.Date.valueOf("1900-01-01");
        java.sql.Date d2 = p.endDate()   != null ? java.sql.Date.valueOf(p.endDate())   : java.sql.Date.valueOf("2999-12-31");

        String sql =
            "SELECT h.pay_code, COALESCE(c.desc1,h.pay_code) code_desc, " +
            "       COALESCE(c.fund_name,'') fund_name, " +
            "       h.employee_no, s.surname, s.first_name, " +
            "       h.payrun_date, h.ext_amt, h.paid_flag " +
            "FROM paehist h " +
            "JOIN pastaff s ON s.company_no=h.company_no AND s.employee_no=h.employee_no " +
            "LEFT JOIN pacodes c ON c.company_no=h.company_no AND c.pay_code=h.pay_code " +
            "WHERE h.company_no=? AND h.pay_type IN(19,20,21) " +
            "  AND h.pay_code BETWEEN ? AND ? AND h.employee_no BETWEEN ? AND ? " +
            "  AND h.payrun_date BETWEEN ? AND ? " +
            "ORDER BY h.pay_code, s.surname, h.employee_no, h.payrun_date";

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            jdbc.query(sql, rs -> {
                Map<String, Object> r2 = new LinkedHashMap<>();
                r2.put("payCode",    trim(rs.getString("pay_code")));
                r2.put("codeDesc",   trim(rs.getString("code_desc")));
                r2.put("fundName",   trim(rs.getString("fund_name")));
                r2.put("empNo",      rs.getInt("employee_no"));
                r2.put("surname",    trim(rs.getString("surname")));
                r2.put("firstName",  trim(rs.getString("first_name")));
                r2.put("payrunDate", rs.getDate("payrun_date"));
                r2.put("amount",     z(rs.getBigDecimal("ext_amt")));
                String pf = rs.getString("paid_flag");
                r2.put("paidFlag",   pf != null ? pf.trim() : "");
                rows.add(r2);
            }, s.getCompanyNo(), c1, c2, e1, e2, d1, d2);
        } catch (Exception e) {
            log.error("getDednStatus: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No deduction/super data matched the selection.");
        for (Map<String, Object> r2 : rows) grandTotal = grandTotal.add(z((BigDecimal) r2.get("amount")));

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

        String sql =
            "SELECT COALESCE(c.fund_name,'Unknown Fund') fund_name, " +
            "       COALESCE(c.fund_abn,'') fund_abn, " +
            "       t.pay_code, COALESCE(c.desc1,t.pay_code) code_desc, " +
            "       t.employee_no, s.surname, s.first_name, t.amt " +
            "FROM paytd t " +
            "JOIN pastaff s ON s.company_no=t.company_no AND s.employee_no=t.employee_no " +
            "LEFT JOIN pacodes c ON c.company_no=t.company_no AND c.pay_code=t.pay_code " +
            "WHERE t.company_no=? AND t.year_no=? AND t.pay_type IN(20,21) " +
            "  AND t.pay_code BETWEEN ? AND ? AND t.employee_no BETWEEN ? AND ? " +
            "ORDER BY COALESCE(c.fund_name,'Unknown Fund'), t.pay_code, s.surname, t.employee_no";

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            jdbc.query(sql, rs -> {
                Map<String, Object> r2 = new LinkedHashMap<>();
                r2.put("fundName",  trim(rs.getString("fund_name")));
                r2.put("fundAbn",   trim(rs.getString("fund_abn")));
                r2.put("payCode",   trim(rs.getString("pay_code")));
                r2.put("codeDesc",  trim(rs.getString("code_desc")));
                r2.put("empNo",     rs.getInt("employee_no"));
                r2.put("surname",   trim(rs.getString("surname")));
                r2.put("firstName", trim(rs.getString("first_name")));
                r2.put("amount",    z(rs.getBigDecimal("amt")));
                rows.add(r2);
            }, s.getCompanyNo(), p.yearNo(), c1, c2, e1, e2);
        } catch (Exception e) {
            log.error("getSuperByFund: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No super data matched the selection.");
        for (Map<String, Object> r2 : rows) grandTotal = grandTotal.add(z((BigDecimal) r2.get("amount")));

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
            java.time.LocalDate startDate, java.time.LocalDate endDate) {}

    public Map<String, Object> getExtendedSuper(AppSession s, ExtendedSuperParams p) {
        String c1 = blank(p.startCode()) ? "      " : p.startCode();
        String c2 = blank(p.endCode())   ? "zzzzzz" : p.endCode();
        int e1 = p.startEmp() == null || p.startEmp() <= 0 ? 0      : p.startEmp();
        int e2 = p.endEmp()   == null || p.endEmp()   <= 0 ? 999999 : p.endEmp();
        java.sql.Date d1 = p.startDate() != null ? java.sql.Date.valueOf(p.startDate()) : java.sql.Date.valueOf("1900-01-01");
        java.sql.Date d2 = p.endDate()   != null ? java.sql.Date.valueOf(p.endDate())   : java.sql.Date.valueOf("2999-12-31");

        String sql =
            "SELECT h.pay_code, COALESCE(c.desc1,h.pay_code) code_desc, " +
            "       COALESCE(c.fund_name,'') fund_name, COALESCE(c.fund_abn,'') fund_abn, " +
            "       h.employee_no, s.surname, s.first_name, s.tax_file_no, " +
            "       h.payrun_date, h.ext_amt, c.super_before_after_tax " +
            "FROM paehist h " +
            "JOIN pastaff s ON s.company_no=h.company_no AND s.employee_no=h.employee_no " +
            "LEFT JOIN pacodes c ON c.company_no=h.company_no AND c.pay_code=h.pay_code " +
            "WHERE h.company_no=? AND h.pay_type IN(20,21) " +
            "  AND h.pay_code BETWEEN ? AND ? AND h.employee_no BETWEEN ? AND ? " +
            "  AND h.payrun_date BETWEEN ? AND ? " +
            "ORDER BY h.pay_code, s.surname, h.employee_no, h.payrun_date";

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            jdbc.query(sql, rs -> {
                Map<String, Object> r2 = new LinkedHashMap<>();
                r2.put("payCode",      trim(rs.getString("pay_code")));
                r2.put("codeDesc",     trim(rs.getString("code_desc")));
                r2.put("fundName",     trim(rs.getString("fund_name")));
                r2.put("fundAbn",      trim(rs.getString("fund_abn")));
                r2.put("empNo",        rs.getInt("employee_no"));
                r2.put("surname",      trim(rs.getString("surname")));
                r2.put("firstName",    trim(rs.getString("first_name")));
                long tfnLong = rs.getLong("tax_file_no");
                r2.put("maskedTfn",    com.landmarksoftware.payroll.model.Employee.maskTfn(String.valueOf(tfnLong)));
                r2.put("payrunDate",   rs.getDate("payrun_date"));
                r2.put("amount",       z(rs.getBigDecimal("ext_amt")));
                String bat = rs.getString("super_before_after_tax");
                r2.put("beforeAfterTax", bat != null ? bat.trim() : "");
                rows.add(r2);
            }, s.getCompanyNo(), c1, c2, e1, e2, d1, d2);
        } catch (Exception e) {
            log.error("getExtendedSuper: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No extended super data matched the selection.");
        for (Map<String, Object> r2 : rows) grandTotal = grandTotal.add(z((BigDecimal) r2.get("amount")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("COMPANY_NAME", s.getCompanyName());
        params.put("DATE_RANGE",   datRangeDesc(p.startDate(), p.endDate()));
        params.put("GRAND_TOTAL",  grandTotal);
        params.put("ROW_COUNT",    rows.size());
        return result(rows, params);
    }

    // ── Date range helper ────────────────────────────────────────────────────

    private static String datRangeDesc(java.time.LocalDate from, java.time.LocalDate to) {
        String f = from != null ? from.toString() : "";
        String t = to   != null ? to.toString()   : "";
        if (f.isEmpty() && t.isEmpty()) return "";
        if (f.isEmpty()) return "to " + t;
        if (t.isEmpty()) return "from " + f;
        return f + " to " + t;
    }
}
