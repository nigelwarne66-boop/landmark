package com.landmarksoftware.service.bas;

import com.landmarksoftware.model.AppSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * Business Activity Statement (BAS / Australian GST) <b>report</b> data service —
 * one query method per BAS report card in the JavaFX Reports Hub.
 *
 * <p>All JDBC lives here. Ported from {@code C:\landmark\cobol\cp2\cpba*}, columns
 * verified against the live {@code lmextract} schema. A BAS run is identified by
 * the composite key {@code (bas_group, bas_no)}. The header summary lives in
 * {@code cpbashd}; the transaction detail in {@code cpbastx} (the line table
 * {@code cpbasln} is empty in the extract and is not used).
 */
@Service
public class BasReportDataService {

    private static final Logger log = LoggerFactory.getLogger(BasReportDataService.class);
    private final JdbcTemplate jdbc;

    public BasReportDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** A selectable code with a display label; {@code toString()} drives ComboBox rendering. */
    public record CodeName(String code, String label) {
        @Override public String toString() { return label; }
    }

    // ── Lookups ────────────────────────────────────────────────────────────

    /** BAS groups for the company, "code — name" from cpbasgr. */
    public List<CodeName> getBasGroups(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        try {
            jdbc.query("SELECT bas_group, bas_group_name FROM cpbasgr WHERE company_no=? ORDER BY bas_group",
                rs -> { list.add(new CodeName(trim(rs.getString("bas_group")),
                                              trim(rs.getString("bas_group")) + " — " + trim(rs.getString("bas_group_name")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getBasGroups: {}", e.getMessage()); }
        return list;
    }

    /** BAS numbers for a group (newest first), labelled with the period range. */
    public List<CodeName> getBasNumbers(AppSession s, String basGroup) {
        List<CodeName> list = new ArrayList<>();
        if (notBlank(basGroup)) {
            try {
                jdbc.query("SELECT bas_no, a3_from_date, a4_to_date FROM cpbashd WHERE company_no=? AND bas_group=? ORDER BY bas_no DESC",
                    rs -> { LocalDate f = ld(rs.getDate("a3_from_date")), t = ld(rs.getDate("a4_to_date"));
                            String lbl = String.valueOf(rs.getInt("bas_no"));
                            if (f != null && t != null) lbl += " — " + f + " to " + t;
                            list.add(new CodeName(String.valueOf(rs.getInt("bas_no")), lbl)); },
                    s.getCompanyNo(), basGroup);
            } catch (Exception e) { log.warn("getBasNumbers: {}", e.getMessage()); }
        }
        return list;
    }

    /** All BAS groups, "(All)" first (for the transactions report range). */
    public List<CodeName> getBasGroupsAll(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All BAS groups)"));
        list.addAll(getBasGroups(s));
        return list;
    }

    /** GST/tax codes, "(All)" first, "code — desc" from cpgstcd. */
    public List<CodeName> getGstCodes(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All tax codes)"));
        try {
            jdbc.query("SELECT gst_code, gst_desc FROM cpgstcd WHERE company_no=? ORDER BY gst_code",
                rs -> { String c = trim(rs.getString("gst_code")); if (!c.isEmpty())
                            list.add(new CodeName(c, c + " — " + trim(rs.getString("gst_desc")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getGstCodes: {}", e.getMessage()); }
        return list;
    }

    // ════════════════════════════════════════════════════════════════════════
    // CPBA12 — Business Activity Statement  (cobol/cp2/cpba12.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record BasStatementParams(String basGroup, String basNo) {}

    /** The BAS form labels rendered, in order: {section, label, description, header column}. */
    private static final String[][] BAS_LABELS = {
        {"Sales",            "G1",  "Total sales (incl. GST)",            "g1_total_sales"},
        {"Sales",            "G2",  "Export sales",                       "g2_export_sales"},
        {"Sales",            "G3",  "Other GST-free sales",               "g3_tax_free_supplies"},
        {"Sales",            "G7",  "Adjustments",                        "g7_sales_adjustments"},
        {"Purchases",        "G10", "Capital purchases",                  "g10_capital_purch"},
        {"Purchases",        "G11", "Non-capital purchases",              "g11_non_capital_purch"},
        {"GST",              "1A",  "GST on sales",                       "bas_1a_gst_payable"},
        {"GST",              "1B",  "GST on purchases",                   "bas_1b_gst_credits"},
        {"GST",              "2A",  "GST payable",                        "bas_2a_gst_payable"},
        {"GST",              "2B",  "GST credits",                        "bas_2b_gst_credits"},
        {"GST",              "3",   "Net GST",                            "bas_3_net_gst_amt"},
        {"PAYG & other",     "4",   "PAYG tax withheld",                  "bas_4_withhold_tax"},
        {"PAYG & other",     "5A",  "PAYG income tax instalment",         "bas_5a_income_tax_payable"},
        {"PAYG & other",     "5B",  "PAYG instalment credit",             "bas_5b_income_tax_credits"},
        {"PAYG & other",     "6A",  "FBT instalment",                     "bas_6a_fbt_payable"},
        {"PAYG & other",     "6B",  "FBT credit",                         "bas_6b_fbt_credits"},
        {"PAYG & other",     "7",   "Deferred company instalment",        "bas_7_deferred_tax"},
        {"Summary",          "8A",  "Total you owe the ATO",              "bas_8a_tax_payable"},
        {"Summary",          "8B",  "Total the ATO owes you",             "bas_8b_tax_credits"},
        {"Summary",          "9",   "Net amount for this BAS",            "bas_9_net_tax_amt"},
    };

    /** CPBA12 — the formatted Business Activity Statement (one row per BAS label). */
    public Map<String, Object> getBasStatement(AppSession s, BasStatementParams p) {
        if (!notBlank(p.basGroup()) || !notBlank(p.basNo())) return warn("Choose a BAS group and number.");
        int basNo = Integer.parseInt(p.basNo());

        StringBuilder cols = new StringBuilder(
            "company_name, a2_abn, a3_from_date, a4_to_date, a5_due_date, a6_pay_date");
        for (String[] l : BAS_LABELS) cols.append(", ").append(l[3]);

        Map<String, Object> params = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            boolean found = Boolean.TRUE.equals(jdbc.query(
                "SELECT " + cols + " FROM cpbashd WHERE company_no=? AND bas_group=? AND bas_no=?",
                rs -> {
                    if (!rs.next()) return Boolean.FALSE;
                    params.put("BAS_DESC", p.basGroup() + " / " + basNo);
                    params.put("ENTITY_NAME", trim(rs.getString("company_name")));
                    params.put("ABN", trim(rs.getString("a2_abn")));
                    LocalDate f = ld(rs.getDate("a3_from_date")), t = ld(rs.getDate("a4_to_date")),
                              due = ld(rs.getDate("a5_due_date")), pay = ld(rs.getDate("a6_pay_date"));
                    params.put("PERIOD", (f != null ? f.toString() : "") + " to " + (t != null ? t.toString() : ""));
                    params.put("DUE_DATE", due != null ? due.toString() : "");
                    params.put("PAY_DATE", pay != null ? pay.toString() : "");
                    for (String[] l : BAS_LABELS) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("section", l[0]); row.put("label", l[1]); row.put("description", l[2]);
                        row.put("amount", z(rs.getBigDecimal(l[3])));
                        rows.add(row);
                    }
                    return Boolean.TRUE;
                }, s.getCompanyNo(), p.basGroup(), basNo));
            if (!found) return warn("No BAS found for " + p.basGroup() + " / " + basNo + ".");
        } catch (Exception e) {
            log.error("getBasStatement: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CPBA13 — Detailed BAS  (cobol/cp2/cpba13.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record DetailedBasParams(String basGroup, String basNo, String detailSummary) {}

    /** CPBA13 — BAS by bas_code: summary totals, or full transaction detail per code. */
    public Map<String, Object> getDetailedBas(AppSession s, DetailedBasParams p) {
        if (!notBlank(p.basGroup()) || !notBlank(p.basNo())) return warn("Choose a BAS group and number.");
        int basNo = Integer.parseInt(p.basNo());
        boolean detail = "D".equalsIgnoreCase(p.detailSummary());

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            if (detail) {
                jdbc.query(
                    "SELECT x.bas_code, COALESCE(c.desc_1,'') AS code_desc, x.trx_date, x.posting_date, " +
                    "       x.gst_code, x.trx_gross_amt, x.tax_amt, x.ref_1, " +
                    "       COALESCE(NULLIF(TRIM(x.supplier_name),''), x.cust_name) AS party " +
                    "FROM cpbastx x LEFT JOIN cpbascd c ON c.bas_code=x.bas_code " +
                    "WHERE x.company_no=? AND x.bas_group=? AND x.bas_no=? " +
                    "ORDER BY x.bas_code, x.trx_date",
                    rs -> {
                        BigDecimal g = z(rs.getBigDecimal("trx_gross_amt")), t = z(rs.getBigDecimal("tax_amt"));
                        tot[0] = tot[0].add(g); tot[1] = tot[1].add(t);
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("basCode", trim(rs.getString("bas_code")));
                        r.put("codeDesc", trim(rs.getString("code_desc")));
                        r.put("trxDate", sqlDate(rs.getDate("trx_date")));
                        r.put("postingDate", sqlDate(rs.getDate("posting_date")));
                        r.put("gstCode", trim(rs.getString("gst_code")));
                        r.put("party", trim(rs.getString("party")));
                        r.put("reference", rs.getString("ref_1"));
                        r.put("grossAmt", g); r.put("taxAmt", t);
                        rows.add(r);
                    }, s.getCompanyNo(), p.basGroup(), basNo);
            } else {
                jdbc.query(
                    "SELECT x.bas_code, COALESCE(c.desc_1,'') AS code_desc, " +
                    "       SUM(x.trx_gross_amt) AS gross, SUM(x.tax_amt) AS tax, COUNT(*) AS cnt " +
                    "FROM cpbastx x LEFT JOIN cpbascd c ON c.bas_code=x.bas_code " +
                    "WHERE x.company_no=? AND x.bas_group=? AND x.bas_no=? " +
                    "GROUP BY x.bas_code, c.desc_1 ORDER BY x.bas_code",
                    rs -> {
                        BigDecimal g = z(rs.getBigDecimal("gross")), t = z(rs.getBigDecimal("tax"));
                        tot[0] = tot[0].add(g); tot[1] = tot[1].add(t);
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("basCode", trim(rs.getString("bas_code")));
                        r.put("codeDesc", trim(rs.getString("code_desc")));
                        r.put("count", rs.getInt("cnt"));
                        r.put("grossAmt", g); r.put("taxAmt", t);
                        rows.add(r);
                    }, s.getCompanyNo(), p.basGroup(), basNo);
            }
        } catch (Exception e) { log.error("getDetailedBas: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No BAS transactions for " + p.basGroup() + " / " + basNo + ".");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BAS_DESC", p.basGroup() + " / " + basNo);
        params.put("MODE_DESC", detail ? "Detail" : "Summary");
        params.put("SUM_GROSS", tot[0]); params.put("SUM_TAX", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CPBA06 — BAS Transactions  (cobol/cp2/cpba06.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record BasTxnParams(
            String basGroup, String basNo,
            LocalDate startDate, LocalDate endDate, String dateInd,   // "T" trx | "P" posting
            String gstCode) {}

    /** CPBA06 — BAS transaction listing from cpbastx, with date / group / tax-code filters. */
    public Map<String, Object> getBasTransactions(AppSession s, BasTxnParams p) {
        String dateCol = "P".equalsIgnoreCase(p.dateInd()) ? "x.posting_date" : "x.trx_date";
        StringBuilder sql = new StringBuilder(
            "SELECT x.bas_group, x.bas_no, x.bas_code, x.trx_date, x.posting_date, x.gst_code, " +
            "       x.trx_gross_amt, x.tax_amt, x.source, x.batch_no, x.company_no, x.ref_1, " +
            "       x.tax_clearing_main, x.tax_clearing_sub, x.cmtrans_doc_type, x.cmtrans_doc_no, " +
            "       COALESCE(NULLIF(TRIM(x.supplier_name),''), x.cust_name) AS party " +
            "FROM cpbastx x WHERE x.company_no=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        if (notBlank(p.basGroup())) { sql.append(" AND x.bas_group=? "); args.add(p.basGroup()); }
        if (notBlank(p.basNo()))    { sql.append(" AND x.bas_no=? ");    args.add(Integer.parseInt(p.basNo())); }
        if (p.startDate() != null) {
            LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND ").append(dateCol).append(" BETWEEN ? AND ? ");
            args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(e));
        }
        if (notBlank(p.gstCode())) { sql.append(" AND x.gst_code=? "); args.add(p.gstCode()); }
        sql.append(" ORDER BY x.bas_group, x.bas_no, x.bas_code, ").append(dateCol);

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal g = z(rs.getBigDecimal("trx_gross_amt")), t = z(rs.getBigDecimal("tax_amt"));
                tot[0] = tot[0].add(g); tot[1] = tot[1].add(t);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("basGroup", trim(rs.getString("bas_group")));
                r.put("basNo", rs.getInt("bas_no"));
                r.put("basCode", trim(rs.getString("bas_code")));
                r.put("trxDate", sqlDate(rs.getDate("trx_date")));
                r.put("postingDate", sqlDate(rs.getDate("posting_date")));
                r.put("gstCode", trim(rs.getString("gst_code")));
                r.put("party", trim(rs.getString("party")));
                r.put("reference", rs.getString("ref_1"));
                r.put("glAcct", rs.getInt("tax_clearing_main") + "-" + rs.getInt("tax_clearing_sub"));
                r.put("source", trim(rs.getString("source")));
                r.put("batchNo", rs.getInt("batch_no"));
                r.put("docRef", trim(rs.getString("cmtrans_doc_type")) + " " + trim(rs.getString("cmtrans_doc_no")));
                r.put("grossAmt", g); r.put("taxAmt", t);
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getBasTransactions: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No BAS transactions matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BAS_DESC", notBlank(p.basGroup()) ? p.basGroup() + (notBlank(p.basNo()) ? " / " + p.basNo() : "") : "All BAS groups");
        params.put("DATE_BASIS_DESC", "P".equalsIgnoreCase(p.dateInd()) ? "Posting date" : "Transaction date");
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_GROSS", tot[0]); params.put("SUM_TAX", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CPBA16 — BAS by GL  (cobol/cp2/cpba16.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record BasByGlParams(String basGroup, String basNo) {}

    /** CPBA16 — BAS amounts aggregated by GL clearing account + BAS code. */
    public Map<String, Object> getBasByGl(AppSession s, BasByGlParams p) {
        if (!notBlank(p.basGroup()) || !notBlank(p.basNo())) return warn("Choose a BAS group and number.");
        int basNo = Integer.parseInt(p.basNo());

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(
                "SELECT x.tax_clearing_main, x.tax_clearing_sub, COALESCE(g.desc1,'') AS gl_desc, " +
                "       x.bas_code, SUM(x.trx_gross_amt) AS gross, SUM(x.tax_amt) AS tax, COUNT(*) AS cnt " +
                "FROM cpbastx x LEFT JOIN glchart g ON g.company_no=x.company_no " +
                "             AND g.acct_main_no=x.tax_clearing_main AND g.acct_sub_no=x.tax_clearing_sub " +
                "WHERE x.company_no=? AND x.bas_group=? AND x.bas_no=? " +
                "GROUP BY x.tax_clearing_main, x.tax_clearing_sub, g.desc1, x.bas_code " +
                "ORDER BY x.tax_clearing_main, x.tax_clearing_sub, x.bas_code",
                rs -> {
                    BigDecimal g = z(rs.getBigDecimal("gross")), t = z(rs.getBigDecimal("tax"));
                    tot[0] = tot[0].add(g); tot[1] = tot[1].add(t);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("glAcct", rs.getInt("tax_clearing_main") + "-" + rs.getInt("tax_clearing_sub"));
                    r.put("glDesc", trim(rs.getString("gl_desc")));
                    r.put("basCode", trim(rs.getString("bas_code")));
                    r.put("count", rs.getInt("cnt"));
                    r.put("grossAmt", g); r.put("taxAmt", t);
                    rows.add(r);
                }, s.getCompanyNo(), p.basGroup(), basNo);
        } catch (Exception e) { log.error("getBasByGl: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No BAS transactions for " + p.basGroup() + " / " + basNo + ".");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BAS_DESC", p.basGroup() + " / " + basNo);
        params.put("SUM_GROSS", tot[0]); params.put("SUM_TAX", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> result(List<Map<String, Object>> rows, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows); m.put("params", params); m.put("rowCount", rows.size());
        return m;
    }
    static java.sql.Date sqlDate(java.sql.Date d) {
        if (d == null) return null;
        return d.toLocalDate().isAfter(LocalDate.of(1900, 1, 1)) ? d : null;
    }
    static LocalDate ld(Date d) {
        if (d == null) return null;
        LocalDate v = d.toLocalDate();
        return v.isAfter(LocalDate.of(1900, 1, 1)) ? v : null;
    }
    static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    static String trim(String s) { return s == null ? "" : s.trim(); }
    static BigDecimal z(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    Map<String, Object> warn(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", new ArrayList<>()); m.put("params", new LinkedHashMap<>());
        m.put("rowCount", 0); m.put("warning", msg);
        return m;
    }
}
