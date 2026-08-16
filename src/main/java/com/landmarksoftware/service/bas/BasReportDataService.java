package com.landmarksoftware.service.bas;

import com.landmarksoftware.model.AppSession;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

import static com.landmarksoftware.db.tables.Cpbascd.CPBASCD;
import static com.landmarksoftware.db.tables.Cpbasgr.CPBASGR;
import static com.landmarksoftware.db.tables.Cpbashd.CPBASHD;
import static com.landmarksoftware.db.tables.Cpbastx.CPBASTX;
import static com.landmarksoftware.db.tables.Cpgstcd.CPGSTCD;
import static com.landmarksoftware.db.tables.Glchart.GLCHART;

/**
 * Business Activity Statement (BAS / Australian GST) <b>report</b> data service —
 * one query method per BAS report card in the JavaFX Reports Hub.
 *
 * <p>Migrated from JdbcTemplate to jOOQ DSLContext. Dialect-neutral: renders
 * correct SQL for MySQL, MariaDB, and SQL Server without code changes.
 *
 * <p>A BAS run is identified by the composite key {@code (bas_group, bas_no)}.
 * The header summary lives in {@code cpbashd}; the transaction detail in
 * {@code cpbastx} (the line table {@code cpbasln} is empty and not used).
 */
@Service
public class BasReportDataService {

    private static final Logger log = LoggerFactory.getLogger(BasReportDataService.class);
    private final DSLContext dsl;

    public BasReportDataService(DSLContext dsl) { this.dsl = dsl; }

    /** A selectable code with a display label; {@code toString()} drives ComboBox rendering. */
    public record CodeName(String code, String label) {
        @Override public String toString() { return label; }
    }

    // ── Lookups ────────────────────────────────────────────────────────────

    /** BAS groups for the company, "code — name" from cpbasgr. */
    public List<CodeName> getBasGroups(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        try {
            dsl.select(CPBASGR.BAS_GROUP, CPBASGR.BAS_GROUP_NAME)
               .from(CPBASGR)
               .where(CPBASGR.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(CPBASGR.BAS_GROUP)
               .fetch()
               .forEach(r -> {
                   String code = trim(r.get(CPBASGR.BAS_GROUP));
                   list.add(new CodeName(code, code + " — " + trim(r.get(CPBASGR.BAS_GROUP_NAME))));
               });
        } catch (Exception e) { log.warn("getBasGroups: {}", e.getMessage()); }
        return list;
    }

    /** BAS numbers for a group (newest first), labelled with the period range. */
    public List<CodeName> getBasNumbers(AppSession s, String basGroup) {
        List<CodeName> list = new ArrayList<>();
        if (notBlank(basGroup)) {
            try {
                dsl.select(CPBASHD.BAS_NO, CPBASHD.A3_FROM_DATE, CPBASHD.A4_TO_DATE)
                   .from(CPBASHD)
                   .where(CPBASHD.COMPANY_NO.eq(s.getCompanyNo()).and(CPBASHD.BAS_GROUP.eq(basGroup)))
                   .orderBy(CPBASHD.BAS_NO.desc())
                   .fetch()
                   .forEach(r -> {
                       LocalDate f = ld(r.get(CPBASHD.A3_FROM_DATE)), t = ld(r.get(CPBASHD.A4_TO_DATE));
                       String lbl = String.valueOf(r.get(CPBASHD.BAS_NO));
                       if (f != null && t != null) lbl += " — " + f + " to " + t;
                       list.add(new CodeName(String.valueOf(r.get(CPBASHD.BAS_NO)), lbl));
                   });
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
            dsl.select(CPGSTCD.GST_CODE, CPGSTCD.GST_DESC)
               .from(CPGSTCD)
               .where(CPGSTCD.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(CPGSTCD.GST_CODE)
               .fetch()
               .forEach(r -> {
                   String c = trim(r.get(CPGSTCD.GST_CODE));
                   if (!c.isEmpty()) list.add(new CodeName(c, c + " — " + trim(r.get(CPGSTCD.GST_DESC))));
               });
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

        Map<String, Object> params = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            var record = dsl.selectFrom(CPBASHD)
                .where(CPBASHD.COMPANY_NO.eq(s.getCompanyNo())
                    .and(CPBASHD.BAS_GROUP.eq(p.basGroup()))
                    .and(CPBASHD.BAS_NO.eq(basNo)))
                .fetchOne();
            if (record == null) return warn("No BAS found for " + p.basGroup() + " / " + basNo + ".");

            params.put("BAS_DESC", p.basGroup() + " / " + basNo);
            params.put("ENTITY_NAME", trim(record.get(CPBASHD.COMPANY_NAME)));
            params.put("ABN", trim(record.get(CPBASHD.A2_ABN)));
            LocalDate f = ld(record.get(CPBASHD.A3_FROM_DATE)), t = ld(record.get(CPBASHD.A4_TO_DATE));
            LocalDate due = ld(record.get(CPBASHD.A5_DUE_DATE)), pay = ld(record.get(CPBASHD.A6_PAY_DATE));
            params.put("PERIOD", dmy(f) + " to " + dmy(t));
            params.put("DUE_DATE", dmy(due));
            params.put("PAY_DATE", dmy(pay));
            for (String[] l : BAS_LABELS) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("section", l[0]); row.put("label", l[1]); row.put("description", l[2]);
                row.put("amount", z(record.get(l[3], BigDecimal.class)));
                rows.add(row);
            }
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

        Field<String> party = DSL.coalesce(
            DSL.nullif(DSL.trim(CPBASTX.SUPPLIER_NAME), ""),
            CPBASTX.CUST_NAME
        ).as("party");
        Condition where = CPBASTX.COMPANY_NO.eq(s.getCompanyNo())
            .and(CPBASTX.BAS_GROUP.eq(p.basGroup()))
            .and(CPBASTX.BAS_NO.eq(basNo));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            if (detail) {
                dsl.select(CPBASTX.BAS_CODE, DSL.coalesce(CPBASCD.DESC_1, "").as("code_desc"),
                           CPBASTX.TRX_DATE, CPBASTX.POSTING_DATE, CPBASTX.GST_CODE,
                           party, CPBASTX.REF_1, CPBASTX.TRX_GROSS_AMT, CPBASTX.TAX_AMT)
                   .from(CPBASTX)
                   .leftJoin(CPBASCD).on(CPBASCD.BAS_CODE.eq(CPBASTX.BAS_CODE))
                   .where(where)
                   .orderBy(CPBASTX.BAS_CODE, CPBASTX.TRX_DATE)
                   .fetch()
                   .forEach(r -> {
                       BigDecimal g = z(r.get(CPBASTX.TRX_GROSS_AMT)), tx = z(r.get(CPBASTX.TAX_AMT));
                       tot[0] = tot[0].add(g); tot[1] = tot[1].add(tx);
                       Map<String, Object> row = new LinkedHashMap<>();
                       row.put("basCode",     trim(r.get(CPBASTX.BAS_CODE)));
                       row.put("codeDesc",    trim(r.get("code_desc", String.class)));
                       row.put("trxDate",     toSqlDate(ld(r.get(CPBASTX.TRX_DATE))));
                       row.put("postingDate", toSqlDate(ld(r.get(CPBASTX.POSTING_DATE))));
                       row.put("gstCode",     trim(r.get(CPBASTX.GST_CODE)));
                       row.put("party",       trim(r.get("party", String.class)));
                       row.put("reference",   r.get(CPBASTX.REF_1));
                       row.put("grossAmt", g); row.put("taxAmt", tx);
                       rows.add(row);
                   });
            } else {
                dsl.select(CPBASTX.BAS_CODE, DSL.coalesce(CPBASCD.DESC_1, "").as("code_desc"),
                           DSL.sum(CPBASTX.TRX_GROSS_AMT).as("gross"),
                           DSL.sum(CPBASTX.TAX_AMT).as("tax"),
                           DSL.count().as("cnt"))
                   .from(CPBASTX)
                   .leftJoin(CPBASCD).on(CPBASCD.BAS_CODE.eq(CPBASTX.BAS_CODE))
                   .where(where)
                   .groupBy(CPBASTX.BAS_CODE, CPBASCD.DESC_1)
                   .orderBy(CPBASTX.BAS_CODE)
                   .fetch()
                   .forEach(r -> {
                       BigDecimal g = z(r.get("gross", BigDecimal.class)), tx = z(r.get("tax", BigDecimal.class));
                       tot[0] = tot[0].add(g); tot[1] = tot[1].add(tx);
                       Map<String, Object> row = new LinkedHashMap<>();
                       row.put("basCode",  trim(r.get(CPBASTX.BAS_CODE)));
                       row.put("codeDesc", trim(r.get("code_desc", String.class)));
                       row.put("count",    r.get("cnt", Integer.class));
                       row.put("grossAmt", g); row.put("taxAmt", tx);
                       rows.add(row);
                   });
            }
        } catch (Exception e) { log.error("getDetailedBas: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No BAS transactions for " + p.basGroup() + " / " + basNo + ".");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BAS_DESC",   p.basGroup() + " / " + basNo);
        params.put("MODE_DESC",  detail ? "Detail" : "Summary");
        params.put("SUM_GROSS",  tot[0]); params.put("SUM_TAX", tot[1]); params.put("ROW_COUNT", rows.size());
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
        Field<LocalDate> dateField = "P".equalsIgnoreCase(p.dateInd()) ? CPBASTX.POSTING_DATE : CPBASTX.TRX_DATE;

        Condition where = CPBASTX.COMPANY_NO.eq(s.getCompanyNo());
        if (notBlank(p.basGroup())) where = where.and(CPBASTX.BAS_GROUP.eq(p.basGroup()));
        if (notBlank(p.basNo()))    where = where.and(CPBASTX.BAS_NO.eq(Integer.parseInt(p.basNo())));
        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            where = where.and(dateField.between(p.startDate()).and(end));
        }
        if (notBlank(p.gstCode())) where = where.and(CPBASTX.GST_CODE.eq(p.gstCode()));

        Field<String> party = DSL.coalesce(
            DSL.nullif(DSL.trim(CPBASTX.SUPPLIER_NAME), ""),
            CPBASTX.CUST_NAME
        ).as("party");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(CPBASTX.BAS_GROUP, CPBASTX.BAS_NO, CPBASTX.BAS_CODE,
                       CPBASTX.TRX_DATE, CPBASTX.POSTING_DATE, CPBASTX.GST_CODE,
                       CPBASTX.TRX_GROSS_AMT, CPBASTX.TAX_AMT, CPBASTX.SOURCE, CPBASTX.BATCH_NO,
                       CPBASTX.TAX_CLEARING_MAIN, CPBASTX.TAX_CLEARING_SUB,
                       CPBASTX.CMTRANS_DOC_TYPE, CPBASTX.CMTRANS_DOC_NO, CPBASTX.REF_1, party)
               .from(CPBASTX)
               .where(where)
               .orderBy(CPBASTX.BAS_GROUP, CPBASTX.BAS_NO, CPBASTX.BAS_CODE, dateField)
               .fetch()
               .forEach(r -> {
                   BigDecimal g = z(r.get(CPBASTX.TRX_GROSS_AMT)), tx = z(r.get(CPBASTX.TAX_AMT));
                   tot[0] = tot[0].add(g); tot[1] = tot[1].add(tx);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("basGroup",    trim(r.get(CPBASTX.BAS_GROUP)));
                   row.put("basNo",       r.get(CPBASTX.BAS_NO));
                   row.put("basCode",     trim(r.get(CPBASTX.BAS_CODE)));
                   row.put("trxDate",     toSqlDate(ld(r.get(CPBASTX.TRX_DATE))));
                   row.put("postingDate", toSqlDate(ld(r.get(CPBASTX.POSTING_DATE))));
                   row.put("gstCode",     trim(r.get(CPBASTX.GST_CODE)));
                   row.put("party",       trim(r.get("party", String.class)));
                   row.put("reference",   r.get(CPBASTX.REF_1));
                   row.put("glAcct",      r.get(CPBASTX.TAX_CLEARING_MAIN) + "-" + r.get(CPBASTX.TAX_CLEARING_SUB));
                   row.put("source",      trim(r.get(CPBASTX.SOURCE)));
                   row.put("batchNo",     r.get(CPBASTX.BATCH_NO));
                   row.put("docRef",      trim(r.get(CPBASTX.CMTRANS_DOC_TYPE)) + " " + r.get(CPBASTX.CMTRANS_DOC_NO));
                   row.put("grossAmt", g); row.put("taxAmt", tx);
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getBasTransactions: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No BAS transactions matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BAS_DESC",        notBlank(p.basGroup()) ? p.basGroup() + (notBlank(p.basNo()) ? " / " + p.basNo() : "") : "All BAS groups");
        params.put("DATE_BASIS_DESC", "P".equalsIgnoreCase(p.dateInd()) ? "Posting date" : "Transaction date");
        params.put("DATE_RANGE",      p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
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
            dsl.select(CPBASTX.TAX_CLEARING_MAIN, CPBASTX.TAX_CLEARING_SUB,
                       DSL.coalesce(GLCHART.DESC1, "").as("gl_desc"),
                       CPBASTX.BAS_CODE,
                       DSL.sum(CPBASTX.TRX_GROSS_AMT).as("gross"),
                       DSL.sum(CPBASTX.TAX_AMT).as("tax"),
                       DSL.count().as("cnt"))
               .from(CPBASTX)
               .leftJoin(GLCHART).on(
                   GLCHART.COMPANY_NO.eq(CPBASTX.COMPANY_NO)
                   .and(GLCHART.ACCT_MAIN_NO.eq(CPBASTX.TAX_CLEARING_MAIN))
                   .and(GLCHART.ACCT_SUB_NO.eq(CPBASTX.TAX_CLEARING_SUB)))
               .where(CPBASTX.COMPANY_NO.eq(s.getCompanyNo())
                   .and(CPBASTX.BAS_GROUP.eq(p.basGroup()))
                   .and(CPBASTX.BAS_NO.eq(basNo)))
               .groupBy(CPBASTX.TAX_CLEARING_MAIN, CPBASTX.TAX_CLEARING_SUB, GLCHART.DESC1, CPBASTX.BAS_CODE)
               .orderBy(CPBASTX.TAX_CLEARING_MAIN, CPBASTX.TAX_CLEARING_SUB, CPBASTX.BAS_CODE)
               .fetch()
               .forEach(r -> {
                   BigDecimal g = z(r.get("gross", BigDecimal.class)), tx = z(r.get("tax", BigDecimal.class));
                   tot[0] = tot[0].add(g); tot[1] = tot[1].add(tx);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("glAcct",  r.get(CPBASTX.TAX_CLEARING_MAIN) + "-" + r.get(CPBASTX.TAX_CLEARING_SUB));
                   row.put("glDesc",  trim(r.get("gl_desc", String.class)));
                   row.put("basCode", trim(r.get(CPBASTX.BAS_CODE)));
                   row.put("count",   r.get("cnt", Integer.class));
                   row.put("grossAmt", g); row.put("taxAmt", tx);
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getBasByGl: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No BAS transactions for " + p.basGroup() + " / " + basNo + ".");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BAS_DESC",  p.basGroup() + " / " + basNo);
        params.put("SUM_GROSS", tot[0]); params.put("SUM_TAX", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> result(List<Map<String, Object>> rows, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows); m.put("params", params); m.put("rowCount", rows.size());
        return m;
    }

    /** Convert LocalDate to java.sql.Date for Jasper report parameters; null for sentinel pre-1900 dates. */
    static Date toSqlDate(LocalDate d) { return d != null ? Date.valueOf(d) : null; }

    /** Sentinel filter — jOOQ returns LocalDate directly; null or pre-1900 → null. */
    static LocalDate ld(LocalDate d) {
        return (d != null && d.isAfter(LocalDate.of(1900, 1, 1))) ? d : null;
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

    /** Format a date as dd-MM-yyyy for report display (blank when null). */
    private static String dmy(java.time.LocalDate d) {
        return d == null ? "" : d.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"));
    }
}
