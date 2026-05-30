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
}
