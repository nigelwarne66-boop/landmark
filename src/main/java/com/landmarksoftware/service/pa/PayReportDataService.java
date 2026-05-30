package com.landmarksoftware.service.pa;

import com.landmarksoftware.model.AppSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        try {
            jdbc.query(sql, rs -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("empNo",      rs.getInt("employee_no"));
                r.put("surname",    trim(rs.getString("surname")));
                r.put("firstName",  trim(rs.getString("first_name")));
                r.put("paygroup",   trim(rs.getString("paygroup")));
                r.put("dept",       trim(rs.getString("dept")));
                r.put("payType",    rs.getInt("pay_type"));
                r.put("payCode",    trim(rs.getString("pay_code")));
                r.put("codeDesc",   trim(rs.getString("code_desc")));
                // hrs is stored as minutes — convert to decimal hours (BigDecimal, 2dp)
                int mins = rs.getInt("hrs");
                r.put("hours",  mins > 0 ? BigDecimal.valueOf(mins).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP) : null);
                r.put("amount", z(rs.getBigDecimal("amt")));
                rows.add(r);
            }, s.getCompanyNo(), p.yearNo(), pg1, pg2, d1, d2, c1, c2, e1, e2);
        } catch (Exception e) {
            log.error("getYtdPayments: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }

        if (rows.isEmpty()) return warn("No YTD data found for the selection.");

        for (Map<String, Object> r : rows) grandTotal = grandTotal.add((BigDecimal) r.get("amount"));

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
