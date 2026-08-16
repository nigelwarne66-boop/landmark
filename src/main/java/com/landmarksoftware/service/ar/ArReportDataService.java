package com.landmarksoftware.service.ar;

import com.landmarksoftware.model.AppSession;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Accounts Receivable <b>report</b> data service — one query method per AR report
 * card in the JavaFX Reports Hub ({@code -Preporting} build).
 *
 * <p>Kept separate from {@link ArDataService} (dashboard KPIs + debtors ageing /
 * ARTL32) so the AR report queries don't bloat that class. Same project rule:
 * all JDBC lives here, controllers stay pure JavaFX.
 *
 * <p>Every method is a faithful port of the matching COBOL/Perl in
 * {@code C:\landmark\cobol\ar2}. Column names verified against the live
 * {@code lmextract} schema ({@code C:\landmark_extract\sql\create}).
 */
@Service
public class ArReportDataService {

    private static final Logger log = LoggerFactory.getLogger(ArReportDataService.class);
    private final DSLContext dsl;

    public ArReportDataService(DSLContext dsl) { this.dsl = dsl; }

    // ── Picker lookups (shared by every AR selection screen) ─────────────────

    /** A selectable code with a display label; {@code toString()} drives ComboBox rendering. */
    public record CodeName(String code, String label) {
        @Override public String toString() { return label; }
    }

    /** Sub-ledgers for the company, "(All)" first, then "code — name" from arledgr. */
    public List<CodeName> getSubLedgers(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All sub ledgers)"));
        try {
            dsl.resultQuery("SELECT sub_ledger, name1 FROM arledgr WHERE company_no=? ORDER BY sub_ledger",
                s.getCompanyNo())
               .fetch()
               .forEach(r -> list.add(new CodeName(trim(r.get("sub_ledger", String.class)),
                                                   trim(r.get("sub_ledger", String.class)) + " — " + trim(r.get("name1", String.class)))));
        } catch (Exception e) { log.warn("getSubLedgers: {}", e.getMessage()); }
        return list;
    }

    /** Customers for the company, "(All)" first; keyed by cust no or alpha key per the screen sequence. */
    public List<CodeName> getCustomers(AppSession s, boolean byAlpha) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All customers)"));
        try {
            String order = byAlpha ? "alpha_key, alpha_cust_no" : "cust_no";
            dsl.resultQuery("SELECT cust_no, alpha_key, name_1 FROM arcusts WHERE company_no=? ORDER BY " + order,
                s.getCompanyNo())
               .fetch()
               .forEach(r -> {
                   String code = byAlpha ? trim(r.get("alpha_key", String.class)) : trim(r.get("cust_no", String.class));
                   list.add(new CodeName(code, code + " — " + trim(r.get("name_1", String.class))));
               });
        } catch (Exception e) { log.warn("getCustomers: {}", e.getMessage()); }
        return list;
    }

    /** All distinct alpha keys for the company, sorted — used to populate the typeahead. */
    public List<String> getAlphaKeys(AppSession s) {
        List<String> keys = new ArrayList<>();
        try {
            dsl.resultQuery(
                    "SELECT DISTINCT alpha_key FROM arcusts WHERE company_no=? AND alpha_key <> '' ORDER BY alpha_key",
                    s.getCompanyNo())
               .fetch()
               .forEach(r -> {
                   String k = trim(r.get("alpha_key", String.class));
                   if (!k.isEmpty()) keys.add(k);
               });
        } catch (Exception e) { log.warn("getAlphaKeys: {}", e.getMessage()); }
        return keys;
    }

    /**
     * ARTI01 alpha-key search — looks up arcusts by exact alpha_key match.
     * Returns the matching customer(s) as CodeName(cust_no, "cust_no — name_1").
     * Empty = not found; size > 1 = duplicate alpha key (COBOL: "DUPLICATE ALPHA KEY").
     */
    public List<CodeName> findCustomersByAlphaKey(AppSession s, String alphaKey) {
        List<CodeName> result = new ArrayList<>();
        if (alphaKey == null || alphaKey.isBlank()) return result;
        try {
            dsl.resultQuery(
                    "SELECT cust_no, name_1 FROM arcusts WHERE company_no=? AND alpha_key=? ORDER BY cust_no",
                    s.getCompanyNo(), alphaKey.trim())
               .fetch()
               .forEach(r -> result.add(new CodeName(
                   trim(r.get("cust_no", String.class)),
                   trim(r.get("cust_no", String.class)) + " — " + trim(r.get("name_1", String.class)))));
        } catch (Exception e) { log.warn("findCustomersByAlphaKey: {}", e.getMessage()); }
        return result;
    }

    /** Salesmen for the company, "(All)" first, then "code — name" from arcodsm. */
    public List<CodeName> getSalesmen(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All salespeople)"));
        try {
            dsl.resultQuery("SELECT salesman_code, name1 FROM arcodsm WHERE company_no=? ORDER BY salesman_code",
                s.getCompanyNo())
               .fetch()
               .forEach(r -> list.add(new CodeName(trim(r.get("salesman_code", String.class)),
                                                   trim(r.get("salesman_code", String.class)) + " — " + trim(r.get("name1", String.class)))));
        } catch (Exception e) { log.warn("getSalesmen: {}", e.getMessage()); }
        return list;
    }

    /** Customer-type codes for the company, "(All)" first, then "code — desc" from arcodct. */
    public List<CodeName> getCustomerTypes(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All customer types)"));
        try {
            dsl.resultQuery("SELECT cust_type_code, desc1 FROM arcodct WHERE company_no=? ORDER BY cust_type_code",
                s.getCompanyNo())
               .fetch()
               .forEach(r -> list.add(new CodeName(trim(r.get("cust_type_code", String.class)),
                                                   trim(r.get("cust_type_code", String.class)) + " — " + trim(r.get("desc1", String.class)))));
        } catch (Exception e) { log.warn("getCustomerTypes: {}", e.getMessage()); }
        return list;
    }

    /** Product-type codes for the company, "(All)" first, then "code — desc" from smcodpt. */
    public List<CodeName> getProductTypes(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All product types)"));
        try {
            dsl.resultQuery("SELECT product_type_code, desc1 FROM smcodpt WHERE company_no=? ORDER BY product_type_code",
                s.getCompanyNo())
               .fetch()
               .forEach(r -> list.add(new CodeName(trim(r.get("product_type_code", String.class)),
                                                   trim(r.get("product_type_code", String.class)) + " — " + trim(r.get("desc1", String.class)))));
        } catch (Exception e) { log.warn("getProductTypes: {}", e.getMessage()); }
        return list;
    }

    /** Distinct foreign-currency codes present on AR transactions, "(All)" first. */
    public List<CodeName> getCurrencyCodes(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All currencies)"));
        try {
            dsl.resultQuery("SELECT DISTINCT for_curr_code FROM artrans WHERE company_no=? AND TRIM(for_curr_code)<>'' ORDER BY for_curr_code",
                s.getCompanyNo())
               .fetch()
               .forEach(r -> { String c = trim(r.get("for_curr_code", String.class)); if (!c.isEmpty()) list.add(new CodeName(c, c)); });
        } catch (Exception e) { log.warn("getCurrencyCodes: {}", e.getMessage()); }
        return list;
    }

    /** Financial GL accounts, "(All)" first, rendered "main-sub — desc" from glchart. */
    public List<CodeName> getGlAccounts(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All accounts)"));
        try {
            dsl.resultQuery("SELECT acct_main_no, acct_sub_no, desc1 FROM glchart WHERE company_no=? ORDER BY acct_main_no, acct_sub_no",
                s.getCompanyNo())
               .fetch()
               .forEach(r -> { String code = r.get("acct_main_no", Integer.class) + "-" + r.get("acct_sub_no", Integer.class);
                               list.add(new CodeName(code, code + " — " + trim(r.get("desc1", String.class)))); });
        } catch (Exception e) { log.warn("getGlAccounts: {}", e.getMessage()); }
        return list;
    }

    /** Period-ending dates for the company's current year (from gldates), latest first. */
    public List<LocalDate> getPeriodEndDates(AppSession s) {
        TreeSet<LocalDate> set = new TreeSet<>(Comparator.reverseOrder());
        try {
            StringBuilder cols = new StringBuilder();
            for (int i = 1; i <= 13; i++) cols.append(i > 1 ? "," : "").append(String.format("period_end_%02d", i));
            dsl.resultQuery("SELECT " + cols + " FROM gldates WHERE company_no=? AND year_no=?",
                s.getCompanyNo(), s.getYearNo())
               .fetch()
               .forEach(r -> {
                   for (int i = 1; i <= 13; i++) {
                       LocalDate d = r.get(String.format("period_end_%02d", i), LocalDate.class);
                       if (d != null && d.isAfter(LocalDate.of(1900, 1, 1))) set.add(d);
                   }
               });
        } catch (Exception e) { log.warn("getPeriodEndDates: {}", e.getMessage()); }
        return new ArrayList<>(set);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARRC05 — Transaction Listing  (cobol/ar2/arrc05.pl, arrc05s0.pl)
    // ════════════════════════════════════════════════════════════════════════

    /** Selection — mirrors the ARRC05S0 entry screen field-for-field. */
    public record TxnListingParams(
            String printSeq,            // "N" customer no | "A" alpha key
            String startCustomer,
            String endCustomer,
            String subLedger,           // blank = all
            boolean invoices,           // doc_type I
            boolean drNotes,            // doc_type D
            boolean crNotes,            // doc_type C
            boolean payments,           // doc_type P
            boolean dishonourChqs,      // doc_type V
            boolean balances,           // doc_type B
            String includePaid,         // "Y" include fully-paid | "N" balance<>0 only
            String docPostInd,          // "D" doc | "P" posting | "U" due | "A" audit
            LocalDate startDate,        // null = all
            LocalDate endDate,
            int batchNo,                // 0 = all
            String holdOnly,            // "Y" only trx_status='H'
            String detailSummary,       // "D" detail | "S" customer summary
            String excludeUnconfirmed,  // "Y" drop trx_status='U'
            boolean activeAccts,        // acct_status blank
            boolean noSalesAccts,       // acct_status 'N'
            boolean onHoldAccts,        // acct_status 'H'
            boolean inactiveAccts,      // acct_status 'I'
            String printLines,          // "Y" include ardistn lines
            String includeArchived      // "Y" include archived transactions
    ) {}

    private static final String[] LINE_FIELDS = {
        "lineNo", "lineType", "glAcctMain", "glAcctSub", "qty", "unitCost",
        "amtExTax", "taxCode", "lineTaxAmt", "description", "reference"
    };
    private static final String[] HEADER_DETAIL_FIELDS = {
        "custNo", "name", "subLedger", "subLedgerName", "docDate", "postDate",
        "dueDate", "docType", "docNo", "seqNo"
    };
    private static final String[] HEADER_AMOUNT_FIELDS = {
        "amt", "retentAmt", "debit", "credit", "amtPaid", "discTaken", "balance"
    };

    /**
     * ARRC05 listing. Returns {@code rows} (transaction beans, each optionally
     * followed by its ardistn line beans when printLines='Y'), a {@code params}
     * map carrying by-doc-type totals + legend text, and {@code warning} when
     * the selection can produce no output. Ported from PRINT-TRX / CHECK-SELECTIONS
     * / CALC-TRX-BAL / SET-DR-CR-AMT / GET-DOC-TYPE.
     */
    public Map<String, Object> getTransactionListingData(AppSession s, TxnListingParams p, boolean excelLayout) {

        List<String> types = new ArrayList<>();
        if (p.invoices())      types.add("I");
        if (p.drNotes())       types.add("D");
        if (p.crNotes())       types.add("C");
        if (p.payments())      types.add("P");
        if (p.dishonourChqs()) types.add("V");
        if (p.balances())      types.add("B");
        if (types.isEmpty()) return warn("Select at least one document type to list.");

        // account-status OR group from the four flags (active = blank/space)
        List<String> statusOr = new ArrayList<>();
        if (p.activeAccts())   statusOr.add("TRIM(c.acct_status)=''");
        if (p.noSalesAccts())  statusOr.add("c.acct_status='N'");
        if (p.onHoldAccts())   statusOr.add("c.acct_status='H'");
        if (p.inactiveAccts()) statusOr.add("c.acct_status='I'");
        if (statusOr.isEmpty()) return warn("Select at least one account status (active / no sales / on hold / inactive).");

        boolean alpha = "A".equalsIgnoreCase(p.printSeq());

        StringBuilder sql = new StringBuilder(
            "SELECT t.cust_no, c.name_1, c.sub_ledger, c.alpha_key, " +
            "       COALESCE(l.name1,'') AS sub_ledger_name, " +
            "       t.doc_date, t.posting_date, t.due_date, t.audit_date, t.doc_type, t.retent_flag, t.doc_no, " +
            "       t.seq_no, t.standing_doc_flag, t.trx_status, " +
            "       t.amt, t.retent_amt, t.disc_taken, t.amt_paid, t.for_curr_fluct_amt, " +
            "       t.fully_paid_flag, t.last_paid_doc_date, t.last_paid_post_date, " +
            "       t.batch_no, t.recon_no, t.archive_flag, " +
            "       t.audit_user_id, t.audit_time_hr, t.audit_time_min, t.audit_time_sec, " +
            "       CASE WHEN t.recon_no > 0 AND r.recon_no IS NOT NULL " +
            "                 AND r.gross_bal = 0 AND r.outstanding_bal = 0 AND r.claim_bal = 0 " +
            "            THEN 1 ELSE 0 END AS recon_zeroed " +
            "FROM arcusts c " +
            "JOIN artrans t ON t.company_no = c.company_no AND t.cust_no = c.cust_no " +
            "LEFT JOIN arledgr l ON l.company_no = c.company_no AND l.sub_ledger = c.sub_ledger " +
            "LEFT JOIN arrecon r ON r.company_no = t.company_no AND r.cust_no = t.recon_cust_no " +
            "                   AND r.recon_no = t.recon_no " +
            "WHERE c.company_no = ? " +
            "  AND t.doc_type IN (" + qMarks(types.size()) + ") ");

        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        args.addAll(types);

        if (!yes(p.includeArchived())) sql.append(" AND t.archive_flag <> 'Y' ");
        if (yes(p.excludeUnconfirmed()))          sql.append(" AND t.trx_status <> 'U' ");
        if (yes(p.holdOnly()))                    sql.append(" AND t.trx_status = 'H' ");

        if (notBlank(p.startCustomer())) {
            String end = notBlank(p.endCustomer()) ? p.endCustomer() : "zzzzzzzzzz";
            sql.append(alpha ? " AND c.alpha_key BETWEEN ? AND ? " : " AND c.cust_no BETWEEN ? AND ? ");
            args.add(p.startCustomer()); args.add(end);
        }
        if (notBlank(p.subLedger())) { sql.append(" AND c.sub_ledger = ? "); args.add(p.subLedger()); }
        if (p.batchNo() > 0)         { sql.append(" AND t.batch_no = ? ");   args.add(p.batchNo()); }

        if (p.startDate() != null) {
            String dateCol = "P".equalsIgnoreCase(p.docPostInd()) ? "t.posting_date"
                           : "U".equalsIgnoreCase(p.docPostInd()) ? "t.due_date"
                           : "A".equalsIgnoreCase(p.docPostInd()) ? "t.audit_date"
                           : "t.doc_date";
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND ").append(dateCol).append(" BETWEEN ? AND ? ");
            args.add(p.startDate()); args.add(end);
        }

        sql.append(" AND (").append(String.join(" OR ", statusOr)).append(") ");

        if (alpha) sql.append(" ORDER BY c.alpha_key, c.alpha_cust_no, t.doc_date, t.doc_type, t.retent_flag, t.doc_no ");
        else       sql.append(" ORDER BY t.cust_no, t.doc_date, t.doc_type, t.retent_flag, t.doc_no ");

        boolean includePaid = yes(p.includePaid());
        boolean printLines  = yes(p.printLines());

        List<Map<String, Object>> rows = new ArrayList<>();
        Totals tot = new Totals();
        String err = null;

        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                String docType = trim(r.get("doc_type", String.class));

                BigDecimal amt       = z(r.get("amt", BigDecimal.class));
                BigDecimal retent    = z(r.get("retent_amt", BigDecimal.class));
                BigDecimal discTaken = z(r.get("disc_taken", BigDecimal.class));
                BigDecimal amtPaid   = z(r.get("amt_paid", BigDecimal.class));

                // CALC-TRX-BAL (ARRC05 does not subtract for_curr_fluct_amt)
                BigDecimal bal = "P".equals(docType)
                    ? amt.add(discTaken).subtract(amtPaid)
                    : amt.subtract(retent).subtract(amtPaid).subtract(discTaken);
                if (Integer.valueOf(1).equals(r.get("recon_zeroed", Integer.class))) bal = BigDecimal.ZERO;

                if (!includePaid && bal.signum() == 0) return;   // outstanding-only

                // SET-DR-CR-AMT
                BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
                if ("I".equals(docType) || "D".equals(docType) || "V".equals(docType)
                        || ("B".equals(docType) && amt.signum() > 0)) {
                    debit = amt.subtract(retent);
                } else if ("C".equals(docType) || ("B".equals(docType) && amt.signum() < 0)) {
                    credit = amt.subtract(retent);
                } else {                                          // P, or B with amt=0
                    credit = amt.add(discTaken);
                }

                tot.add(docType, amt);

                LocalDate docDateRaw = r.get("doc_date", LocalDate.class);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rowType",       "T");
                row.put("custNo",        r.get("cust_no", String.class));
                row.put("name",          r.get("name_1", String.class));
                row.put("subLedger",     r.get("sub_ledger", String.class));
                row.put("subLedgerName", r.get("sub_ledger_name", String.class));
                row.put("docDate",       sqlDate(docDateRaw));
                row.put("postDate",      sqlDate(r.get("posting_date", LocalDate.class)));
                row.put("dueDate",       sqlDate(r.get("due_date", LocalDate.class)));
                row.put("docType",       docTypeLabel(docType));
                row.put("docNo",         r.get("doc_no", String.class));
                row.put("seqNo",         seqDisplay(r.get("seq_no", Integer.class), r.get("standing_doc_flag", String.class)));
                row.put("amt",           amt);
                row.put("retentAmt",     retent);
                row.put("debit",         debit);
                row.put("credit",        credit);
                row.put("amtPaid",       amtPaid);
                row.put("discTaken",     discTaken);
                row.put("balance",       bal);
                row.put("fullyPaid",     r.get("fully_paid_flag", String.class));
                row.put("batchNo",       r.get("batch_no", Integer.class));
                row.put("reconNo",       r.get("recon_no", Integer.class));
                row.put("archiveFlag",   r.get("archive_flag", String.class));
                row.put("auditUser",     r.get("audit_user_id", String.class));
                row.put("auditTime",     String.format("%02d:%02d:%02d",
                                            r.get("audit_time_hr", Integer.class),
                                            r.get("audit_time_min", Integer.class),
                                            r.get("audit_time_sec", Integer.class)));
                row.put("_docDateRaw",   docDateRaw);
                row.put("_retentFlag",   r.get("retent_flag", String.class));
                for (String f : LINE_FIELDS) row.put(f, null);

                if (printLines && excelLayout) {
                    appendExcelCombinedRows(s, row, rows);
                } else {
                    rows.add(row);
                    if (printLines) appendDistLines(s, row, rows);
                }
            });
        } catch (Exception e) {
            log.error("getTransactionListingData failed: {}", e.getMessage(), e);
            err = e.getMessage();
        }
        if (err != null) return warn("Query failed: " + err);

        Map<String, Object> params = tot.toParams();
        params.put("PRINT_SEQ_DESC", alpha ? "Alpha key" : "Customer number");
        params.put("DATE_BASIS_DESC", "P".equalsIgnoreCase(p.docPostInd()) ? "Posting date"
                                    : "U".equalsIgnoreCase(p.docPostInd()) ? "Due date"
                                    : "A".equalsIgnoreCase(p.docPostInd()) ? "Audit date" : "Document date");
        params.put("CUST_RANGE", notBlank(p.startCustomer())
            ? p.startCustomer() + " to " + (notBlank(p.endCustomer()) ? p.endCustomer() : "end") : "All customers");
        params.put("SUB_LEDGER_DESC", notBlank(p.subLedger()) ? p.subLedger() : "All sub ledgers");
        params.put("DATE_RANGE", p.startDate() != null
            ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUMMARY_MODE", "S".equalsIgnoreCase(p.detailSummary()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("params", params);
        result.put("rowCount", tot.grandNo);
        return result;
    }

    /** ardistn lines for one transaction as plain line-field maps. */
    private List<Map<String, Object>> fetchDistLines(AppSession s, Map<String, Object> txn) {
        List<Map<String, Object>> lines = new ArrayList<>();
        try {
            dsl.resultQuery(
                "SELECT line_no, line_type, gl_acct_main, gl_acct_sub, qty, unit_per, unit_cost, " +
                "       amt, tax_code, tax_amt, tax_gross_amt, for_curr_amt, desc_1, ref " +
                "FROM ardistn WHERE company_no=? AND cust_no=? AND doc_date=? AND doc_type=? " +
                "  AND retent_flag=? AND doc_no=? ORDER BY line_no",
                s.getCompanyNo(), txn.get("custNo"), txn.get("_docDateRaw"),
                rawDocType(txn.get("docType")), txn.get("_retentFlag"), txn.get("docNo"))
               .fetch()
               .forEach(r -> {
                    Map<String, Object> ln = new LinkedHashMap<>();
                    ln.put("lineNo",      r.get("line_no", Integer.class));
                    ln.put("lineType",    r.get("line_type", String.class));
                    ln.put("glAcctMain",  r.get("gl_acct_main", Integer.class));
                    ln.put("glAcctSub",   r.get("gl_acct_sub", Integer.class));
                    ln.put("qty",         z(r.get("qty", BigDecimal.class)));
                    ln.put("unitCost",    z(r.get("unit_cost", BigDecimal.class)));
                    ln.put("amtExTax",    z(r.get("amt", BigDecimal.class)));
                    ln.put("taxCode",     r.get("tax_code", String.class));
                    ln.put("lineTaxAmt",  z(r.get("tax_amt", BigDecimal.class)));
                    ln.put("description", r.get("desc_1", String.class));
                    ln.put("reference",   r.get("ref", String.class));
                    lines.add(ln);
               });
        } catch (Exception e) {
            log.warn("fetchDistLines {} {}: {}", txn.get("custNo"), txn.get("docNo"), e.getMessage());
        }
        return lines;
    }

    /** PDF layout: distribution lines as rowType="L" sub-rows under the transaction. */
    private void appendDistLines(AppSession s, Map<String, Object> txn, List<Map<String, Object>> rows) {
        for (Map<String, Object> ln : fetchDistLines(s, txn)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rowType", "L");
            row.put("custNo",  txn.get("custNo"));
            row.put("docNo",   txn.get("docNo"));
            row.putAll(ln);
            rows.add(row);
        }
    }

    /** Excel layout: one row per distribution line to the right; header amounts on first line only. */
    private void appendExcelCombinedRows(AppSession s, Map<String, Object> txn, List<Map<String, Object>> rows) {
        List<Map<String, Object>> lines = fetchDistLines(s, txn);
        if (lines.isEmpty()) { rows.add(txn); return; }
        for (int i = 0; i < lines.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String k : HEADER_DETAIL_FIELDS) row.put(k, txn.get(k));
            if (i == 0) for (String k : HEADER_AMOUNT_FIELDS) row.put(k, txn.get(k));
            row.putAll(lines.get(i));
            rows.add(row);
        }
    }

    // ── by-doc-type accumulator for the report-totals band ───────────────────
    private static final class Totals {
        int invNo, drNo, crNo, payNo, voidNo, balNo, grandNo;
        BigDecimal invAmt = BigDecimal.ZERO, drAmt = BigDecimal.ZERO, crAmt = BigDecimal.ZERO,
                   payAmt = BigDecimal.ZERO, voidAmt = BigDecimal.ZERO, balAmt = BigDecimal.ZERO,
                   grand = BigDecimal.ZERO;

        void add(String docType, BigDecimal amt) {
            grandNo++; grand = grand.add(amt);
            switch (docType) {
                case "I" -> { invNo++;  invAmt  = invAmt.add(amt); }
                case "D" -> { drNo++;   drAmt   = drAmt.add(amt); }
                case "C" -> { crNo++;   crAmt   = crAmt.add(amt); }
                case "P" -> { payNo++;  payAmt  = payAmt.add(amt); }
                case "V" -> { voidNo++; voidAmt = voidAmt.add(amt); }
                case "B" -> { balNo++;  balAmt  = balAmt.add(amt); }
                default  -> { }
            }
        }

        Map<String, Object> toParams() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("T_INV_NO", invNo);   m.put("T_INV_AMT", invAmt);
            m.put("T_DR_NO", drNo);     m.put("T_DR_AMT", drAmt);
            m.put("T_CR_NO", crNo);     m.put("T_CR_AMT", crAmt);
            m.put("T_PAY_NO", payNo);   m.put("T_PAY_AMT", payAmt);
            m.put("T_VOID_NO", voidNo); m.put("T_VOID_AMT", voidAmt);
            m.put("T_BAL_NO", balNo);   m.put("T_BAL_AMT", balAmt);
            m.put("GRAND_NO", grandNo); m.put("GRAND_TOTAL", grand);
            return m;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARRC03 — Account Reconciliation  (cobol/ar2/arrc03.pl)
    // ════════════════════════════════════════════════════════════════════════

    /** Selection — ARRC03S0. */
    public record AccountReconParams(
            String customer,            // blank = all
            int startReconNo, int endReconNo,
            String unbalancedFlag,      // "B" balanced | "U" unbalanced | "A"/blank all
            String localFc,             // "L" local | "F" foreign
            String includeArchived      // "Y" include archived
    ) {}

    /**
     * ARRC03 — reconciliation detail by transaction, grouped by customer + recon.
     * Rows carry the per-recon arrecon balances for the group header; gross/net
     * per CALC-TRX-BAL. Foreign-currency mode swaps in the {@code for_curr_*} amounts.
     */
    public Map<String, Object> getAccountReconData(AppSession s, AccountReconParams p) {
        boolean fc = "F".equalsIgnoreCase(p.localFc());
        String amtCol  = fc ? "t.for_curr_amt"        : "t.amt";
        String retCol  = fc ? "t.for_curr_retent_amt" : "t.retent_amt";
        String paidCol = fc ? "t.for_curr_amt_paid"   : "t.amt_paid";
        String discCol = fc ? "t.for_curr_disc_taken" : "t.disc_taken";
        String fluctCol= "t.for_curr_fluct_amt";

        StringBuilder sql = new StringBuilder(
            "SELECT t.recon_cust_no, c.name_1, t.recon_no, t.recon_seq_no, t.seq_no, t.standing_doc_flag, " +
            "       t.doc_type, t.doc_no, t.doc_date, t.for_curr_code, " +
            "       " + amtCol + " AS amt, " + retCol + " AS retent_amt, " + paidCol + " AS amt_paid, " +
            "       " + discCol + " AS disc_taken, " + fluctCol + " AS fluct_amt, " +
            "       r.outstanding_bal, r.gross_bal, r.claim_bal, " +
            "       r.for_curr_outst_bal, r.for_curr_gross_bal, r.for_curr_claim_bal " +
            "FROM artrans t " +
            "JOIN arrecon r ON r.company_no=t.company_no AND r.cust_no=t.recon_cust_no AND r.recon_no=t.recon_no " +
            "LEFT JOIN arcusts c ON c.company_no=t.company_no AND c.cust_no=t.recon_cust_no " +
            "WHERE t.company_no=? AND t.recon_no>0 ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.customer())) { sql.append(" AND t.recon_cust_no=? "); args.add(p.customer()); }
        if (p.startReconNo() > 0 || p.endReconNo() > 0) {
            int end = p.endReconNo() > 0 ? p.endReconNo() : 999999;
            sql.append(" AND t.recon_no BETWEEN ? AND ? "); args.add(p.startReconNo()); args.add(end);
        }
        if (!yes(p.includeArchived())) sql.append(" AND t.archive_flag <> 'Y' ");
        if ("B".equalsIgnoreCase(p.unbalancedFlag())) sql.append(fc ? " AND r.for_curr_outst_bal = 0 " : " AND r.outstanding_bal = 0 ");
        if ("U".equalsIgnoreCase(p.unbalancedFlag())) sql.append(fc ? " AND r.for_curr_outst_bal <> 0 " : " AND r.outstanding_bal <> 0 ");
        sql.append(" ORDER BY t.recon_cust_no, t.recon_no, t.recon_seq_no, t.seq_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] grand = { BigDecimal.ZERO };
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                String docType = trim(r.get("doc_type", String.class));
                BigDecimal amt = z(r.get("amt", BigDecimal.class)), ret = z(r.get("retent_amt", BigDecimal.class)),
                           paid = z(r.get("amt_paid", BigDecimal.class)), disc = z(r.get("disc_taken", BigDecimal.class)),
                           fluct = z(r.get("fluct_amt", BigDecimal.class));
                BigDecimal gross = "P".equals(docType) ? amt.add(disc) : amt.subtract(ret);
                BigDecimal net;
                if ("P".equals(docType)) net = amt.add(disc).subtract(paid);
                else if ("C".equals(docType)) net = amt.subtract(ret).subtract(paid).subtract(disc);
                else net = amt.subtract(ret).subtract(paid).subtract(disc).subtract(fluct);
                grand[0] = grand[0].add(net);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("custNo",      r.get("recon_cust_no", String.class));
                row.put("name",        r.get("name_1", String.class));
                row.put("reconNo",     r.get("recon_no", Integer.class));
                row.put("seqNo",       seqDisplay(r.get("seq_no", Integer.class), r.get("standing_doc_flag", String.class)));
                row.put("docType",     docTypeLabel(docType));
                row.put("docNo",       r.get("doc_no", String.class));
                row.put("docDate",     sqlDate(r.get("doc_date", LocalDate.class)));
                row.put("forCurrCode", trim(r.get("for_curr_code", String.class)));
                row.put("gross",       gross);
                row.put("retentAmt",   ret);
                row.put("amtPaid",     paid);
                row.put("discTaken",   disc);
                row.put("net",         net);
                row.put("reconOutstanding", z(r.get(fc ? "for_curr_outst_bal" : "outstanding_bal", BigDecimal.class)));
                row.put("reconGross",       z(r.get(fc ? "for_curr_gross_bal" : "gross_bal", BigDecimal.class)));
                row.put("reconClaim",       z(r.get(fc ? "for_curr_claim_bal" : "claim_bal", BigDecimal.class)));
                rows.add(row);
            });
        } catch (Exception e) {
            log.error("getAccountReconData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("CUST_DESC", notBlank(p.customer()) ? p.customer() : "All customers");
        params.put("AMT_BASIS", fc ? "Foreign currency" : "Local currency");
        params.put("GRAND_TOTAL", grand[0]);
        params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARRC04 — Unbalanced Reconciliation  (cobol/ar2/arrc04.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record UnbalReconParams(String customer) {}

    /** ARRC04 — arrecon rows whose outstanding/gross balance is non-zero. */
    public Map<String, Object> getUnbalancedReconData(AppSession s, UnbalReconParams p) {
        StringBuilder sql = new StringBuilder(
            "SELECT r.cust_no, c.name_1, r.recon_no, r.outstanding_bal, r.gross_bal, r.claim_bal, " +
            "       r.last_doc_date, r.for_curr_code, r.for_curr_outst_bal, r.for_curr_gross_bal, r.for_curr_claim_bal " +
            "FROM arrecon r LEFT JOIN arcusts c ON c.company_no=r.company_no AND c.cust_no=r.cust_no " +
            "WHERE r.company_no=? AND (r.outstanding_bal<>0 OR r.gross_bal<>0) ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.customer())) { sql.append(" AND r.cust_no=? "); args.add(p.customer()); }
        sql.append(" ORDER BY r.cust_no, r.recon_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("custNo",      r.get("cust_no", String.class));
                row.put("name",        r.get("name_1", String.class));
                row.put("reconNo",     r.get("recon_no", Integer.class));
                row.put("outstanding", z(r.get("outstanding_bal", BigDecimal.class)));
                row.put("gross",       z(r.get("gross_bal", BigDecimal.class)));
                row.put("claim",       z(r.get("claim_bal", BigDecimal.class)));
                row.put("lastDocDate", sqlDate(r.get("last_doc_date", LocalDate.class)));
                row.put("forCurrCode", trim(r.get("for_curr_code", String.class)));
                tot[0] = tot[0].add((BigDecimal) row.get("outstanding"));
                tot[1] = tot[1].add((BigDecimal) row.get("gross"));
                tot[2] = tot[2].add((BigDecimal) row.get("claim"));
                rows.add(row);
            });
        } catch (Exception e) {
            log.error("getUnbalancedReconData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("CUST_DESC", notBlank(p.customer()) ? p.customer() : "All customers");
        params.put("SUM_OUTSTANDING", tot[0]); params.put("SUM_GROSS", tot[1]); params.put("SUM_CLAIM", tot[2]);
        params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARRC09 — Detailed Transaction Listing  (cobol/ar2/arrc09.pl)
    // ════════════════════════════════════════════════════════════════════════

    /** Selection — ARRC09S0. */
    public record DetailTxnParams(
            String startCustomer, String endCustomer,
            String subLedger,
            LocalDate startDate, LocalDate endDate,    // document date
            String startDocNo, String endDocNo
    ) {}

    /**
     * ARRC09 — ardistn distribution lines (I/D/C only) grouped by customer + doc,
     * with the SET-CODE source-code resolution and terms / salesman lookups.
     */
    public Map<String, Object> getDetailedTransactionData(AppSession s, DetailTxnParams p) {
        StringBuilder sql = new StringBuilder(
            "SELECT d.cust_no, c.name_1, c.acct_status, d.doc_date, d.doc_type, d.doc_no, d.line_no, d.line_type, " +
            "       d.sales_code, d.stock_code, d.ledger_type, d.ledger_code, d.analysis_code, d.ar_analysis_code, " +
            "       d.desc_1, d.ref, d.qty, d.unit_per, d.unit_cost, d.disc_perc, d.amt, d.tax_code, d.tax_amt, d.tax_gross_amt, " +
            "       d.gl_acct_main, d.gl_acct_sub, t.terms_code, t.salesman, te.desc1 AS terms_desc, sm.name1 AS salesman_name " +
            "FROM ardistn d " +
            "LEFT JOIN arcusts c ON c.company_no=d.company_no AND c.cust_no=d.cust_no " +
            "LEFT JOIN artrans t ON t.company_no=d.company_no AND t.cust_no=d.cust_no AND t.doc_date=d.doc_date " +
            "                   AND t.doc_type=d.doc_type AND t.retent_flag=d.retent_flag AND t.doc_no=d.doc_no " +
            "LEFT JOIN arcodte te ON te.company_no=d.company_no AND te.terms_code=t.terms_code " +
            "LEFT JOIN arcodsm sm ON sm.company_no=d.company_no AND sm.salesman_code=t.salesman " +
            "WHERE d.company_no=? AND d.doc_type IN ('I','D','C') ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.startCustomer())) {
            String end = notBlank(p.endCustomer()) ? p.endCustomer() : "zzzzzzzzzz";
            sql.append(" AND d.cust_no BETWEEN ? AND ? "); args.add(p.startCustomer()); args.add(end);
        }
        if (notBlank(p.subLedger())) { sql.append(" AND c.sub_ledger=? "); args.add(p.subLedger()); }
        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND d.doc_date BETWEEN ? AND ? "); args.add(p.startDate()); args.add(end);
        }
        if (notBlank(p.startDocNo())) {
            String end = notBlank(p.endDocNo()) ? p.endDocNo() : "zzzzzzzzzz";
            sql.append(" AND d.doc_no BETWEEN ? AND ? "); args.add(p.startDocNo()); args.add(end);
        }
        sql.append(" ORDER BY d.cust_no, d.doc_date, d.doc_type, d.retent_flag, d.doc_no, d.line_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };  // amt, tax
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                BigDecimal qty = z(r.get("qty", BigDecimal.class)), unitCost = z(r.get("unit_cost", BigDecimal.class)),
                           discPerc = z(r.get("disc_perc", BigDecimal.class)), amt = z(r.get("amt", BigDecimal.class)),
                           taxAmt = z(r.get("tax_amt", BigDecimal.class));
                BigDecimal discAmt = qty.multiply(unitCost).multiply(discPerc)
                        .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                BigDecimal lineGross = amt.add(discAmt);
                String code = trim(r.get("stock_code", String.class));
                if (code.isEmpty()) {
                    String lt = trim(r.get("line_type", String.class));
                    if ("S".equals(lt)) code = trim(r.get("sales_code", String.class));
                    else code = trim(r.get("ledger_type", String.class)) + trim(r.get("ledger_code", String.class));
                }
                tot[0] = tot[0].add(amt); tot[1] = tot[1].add(taxAmt);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("custNo",       r.get("cust_no", String.class));
                row.put("name",         r.get("name_1", String.class));
                row.put("status",       acctStatusDesc(r.get("acct_status", String.class)));
                row.put("docDate",      sqlDate(r.get("doc_date", LocalDate.class)));
                row.put("docType",      docTypeLabel(trim(r.get("doc_type", String.class))));
                row.put("docNo",        r.get("doc_no", String.class));
                row.put("termsDesc",    trim(r.get("terms_desc", String.class)));
                row.put("salesmanName", trim(r.get("salesman_name", String.class)));
                row.put("lineNo",       r.get("line_no", Integer.class));
                row.put("lineType",     r.get("line_type", String.class));
                row.put("code",         code);
                row.put("analysis",     notBlank(r.get("analysis_code", String.class)) ? "Analysis" : "");
                row.put("description",  r.get("desc_1", String.class));
                row.put("reference",    r.get("ref", String.class));
                row.put("qty",          qty);
                row.put("unitPer",      r.get("unit_per", String.class));
                row.put("unitCost",     unitCost);
                row.put("amt",          amt);
                row.put("discPerc",     discPerc);
                row.put("discAmt",      discAmt);
                row.put("lineGross",    lineGross);
                row.put("taxCode",      r.get("tax_code", String.class));
                row.put("taxAmt",       taxAmt);
                row.put("glAcctMain",   r.get("gl_acct_main", Integer.class));
                row.put("glAcctSub",    r.get("gl_acct_sub", Integer.class));
                rows.add(row);
            });
        } catch (Exception e) {
            log.error("getDetailedTransactionData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("CUST_RANGE", notBlank(p.startCustomer())
            ? p.startCustomer() + " to " + (notBlank(p.endCustomer()) ? p.endCustomer() : "end") : "All customers");
        params.put("SUB_LEDGER_DESC", notBlank(p.subLedger()) ? p.subLedger() : "All sub ledgers");
        params.put("DATE_RANGE", p.startDate() != null
            ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_AMT", tot[0]); params.put("SUM_TAX", tot[1]); params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARRC11 — Foreign Currency Revaluation  (cobol/ar2/arrc11.pl)
    // ════════════════════════════════════════════════════════════════════════

    /** Selection — ARRC11S0. */
    public record FcRevalParams(
            String printSeq, String startCustomer, String endCustomer,
            String subLedger, String docPostInd,       // "D" doc | "P" posting
            LocalDate startDate, LocalDate endDate,
            String includeArchived
    ) {}

    /**
     * ARRC11 — foreign-currency transactions (for_curr_code set, trx_status&lt;&gt;'U'),
     * each followed by its arrctrx revaluation adjustment line(s). Grouped by customer.
     */
    public Map<String, Object> getFcRevaluationData(AppSession s, FcRevalParams p) {
        boolean alpha = "A".equalsIgnoreCase(p.printSeq());
        StringBuilder sql = new StringBuilder(
            "SELECT t.cust_no, c.name_1, c.alpha_key, t.for_curr_code, t.doc_no, t.seq_no, t.standing_doc_flag, " +
            "       t.doc_type, t.doc_date, t.posting_date, t.amt_orig, t.amt_paid, t.disc_taken, " +
            "       t.retent_amt, t.amt, t.for_curr_fluct_amt, t.retent_flag " +
            "FROM artrans t LEFT JOIN arcusts c ON c.company_no=t.company_no AND c.cust_no=t.cust_no " +
            "WHERE t.company_no=? AND TRIM(t.for_curr_code)<>'' AND t.trx_status<>'U' ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.startCustomer())) {
            String end = notBlank(p.endCustomer()) ? p.endCustomer() : "zzzzzzzzzz";
            sql.append(alpha ? " AND c.alpha_key BETWEEN ? AND ? " : " AND t.cust_no BETWEEN ? AND ? ");
            args.add(p.startCustomer()); args.add(end);
        }
        if (notBlank(p.subLedger())) { sql.append(" AND c.sub_ledger=? "); args.add(p.subLedger()); }
        if (!yes(p.includeArchived())) sql.append(" AND t.archive_flag <> 'Y' ");
        if (p.startDate() != null) {
            String col = "P".equalsIgnoreCase(p.docPostInd()) ? "t.posting_date" : "t.doc_date";
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND ").append(col).append(" BETWEEN ? AND ? ");
            args.add(p.startDate()); args.add(end);
        }
        sql.append(alpha ? " ORDER BY c.alpha_key, t.cust_no, t.doc_date, t.doc_type, t.doc_no "
                         : " ORDER BY t.cust_no, t.doc_date, t.doc_type, t.doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] grand = { BigDecimal.ZERO };
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                String docType = trim(r.get("doc_type", String.class));
                BigDecimal amt = z(r.get("amt", BigDecimal.class)), ret = z(r.get("retent_amt", BigDecimal.class)),
                           paid = z(r.get("amt_paid", BigDecimal.class)), disc = z(r.get("disc_taken", BigDecimal.class)),
                           fluct = z(r.get("for_curr_fluct_amt", BigDecimal.class));
                BigDecimal bal = "C".equals(docType) || "V".equals(docType) || "B".equals(docType)
                    ? amt.subtract(ret).subtract(paid).subtract(disc)
                    : amt.subtract(ret).subtract(paid).subtract(disc).subtract(fluct);
                grand[0] = grand[0].add(bal);

                LocalDate docDateRaw = r.get("doc_date", LocalDate.class);
                String custNo = r.get("cust_no", String.class);
                String retentFlag = r.get("retent_flag", String.class);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rowType",     "T");
                row.put("custNo",      custNo);
                row.put("name",        r.get("name_1", String.class));
                row.put("forCurrCode", trim(r.get("for_curr_code", String.class)));
                row.put("docNo",       r.get("doc_no", String.class));
                row.put("seqNo",       seqDisplay(r.get("seq_no", Integer.class), r.get("standing_doc_flag", String.class)));
                row.put("docType",     docTypeLabel(docType));
                row.put("docDate",     sqlDate(docDateRaw));
                row.put("postDate",    sqlDate(r.get("posting_date", LocalDate.class)));
                row.put("amtOrig",     z(r.get("amt_orig", BigDecimal.class)));
                row.put("amtPaid",     paid);
                row.put("discTaken",   disc);
                row.put("fluctAmt",    fluct);
                row.put("balance",     bal);
                row.put("revalDate",   null);
                row.put("revalAmt",    null);
                rows.add(row);

                // attach arrctrx revaluation lines for this transaction
                try {
                    String revalDateCol = "P".equalsIgnoreCase(p.docPostInd()) ? "match_posting_date" : "match_doc_date";
                    dsl.resultQuery(
                        "SELECT match_doc_date, match_posting_date, reval_amt FROM arrctrx " +
                        "WHERE company_no=? AND cust_no=? AND doc_date=? AND doc_type=? AND retent_flag=? AND doc_no=? " +
                        "  AND reval_amt<>0 ORDER BY match_doc_date",
                        s.getCompanyNo(), custNo, docDateRaw, docType, retentFlag, row.get("docNo"))
                       .fetch()
                       .forEach(rr -> {
                           Map<String, Object> rv = new LinkedHashMap<>();
                           rv.put("rowType",   "R");
                           rv.put("custNo",    row.get("custNo"));
                           rv.put("revalDate", sqlDate(rr.get(revalDateCol, LocalDate.class)));
                           rv.put("revalAmt",  z(rr.get("reval_amt", BigDecimal.class)));
                           rows.add(rv);
                       });
                } catch (Exception ignore) { /* no reval lines */ }
            });
        } catch (Exception e) {
            log.error("getFcRevaluationData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("PRINT_SEQ_DESC", alpha ? "Alpha key" : "Customer number");
        params.put("CUST_RANGE", notBlank(p.startCustomer())
            ? p.startCustomer() + " to " + (notBlank(p.endCustomer()) ? p.endCustomer() : "end") : "All customers");
        params.put("DATE_BASIS_DESC", "P".equalsIgnoreCase(p.docPostInd()) ? "Posting date" : "Document date");
        params.put("DATE_RANGE", p.startDate() != null
            ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("GRAND_TOTAL", grand[0]); params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARTL02 — GL Distribution  (cobol/ar2/artl02.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record GlDistParams(LocalDate periodEndDate, String subLedger) {}

    /** ARTL02 — ardisum amounts for a period-end, split Control (C) vs Sales (D); GL desc from glchart. */
    public Map<String, Object> getGlDistributionsData(AppSession s, GlDistParams p) {
        if (p.periodEndDate() == null) return warn("Choose a period-ending date.");
        StringBuilder sql = new StringBuilder(
            "SELECT a.gl_acct_main_no, a.gl_acct_sub_no, a.acct_type, a.sub_ledger, a.amt, " +
            "       COALESCE(g.desc1,'') AS gl_desc " +
            "FROM ardisum a LEFT JOIN glchart g ON g.company_no=a.company_no " +
            "             AND g.acct_main_no=a.gl_acct_main_no AND g.acct_sub_no=a.gl_acct_sub_no " +
            "WHERE a.company_no=? AND a.period_end_date=? ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo()); args.add(p.periodEndDate());
        if (notBlank(p.subLedger())) { sql.append(" AND a.sub_ledger=? "); args.add(p.subLedger()); }
        sql.append(" ORDER BY a.gl_acct_main_no, a.gl_acct_sub_no, a.acct_type ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };   // control, sales
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                String acctType = trim(r.get("acct_type", String.class));
                BigDecimal amt = z(r.get("amt", BigDecimal.class));
                if ("C".equals(acctType)) tot[0] = tot[0].add(amt); else tot[1] = tot[1].add(amt);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("glAcctMain", r.get("gl_acct_main_no", Integer.class));
                row.put("glAcctSub",  r.get("gl_acct_sub_no", Integer.class));
                row.put("glDesc",     r.get("gl_desc", String.class));
                row.put("acctType",   "C".equals(acctType) ? "Control" : "D".equals(acctType) ? "Sales" : acctType);
                row.put("subLedger",  r.get("sub_ledger", String.class));
                row.put("amt",        amt);
                rows.add(row);
            });
        } catch (Exception e) {
            log.error("getGlDistributionsData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No distributions for this period-ending date.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("PERIOD_END", dmy(p.periodEndDate()));
        params.put("SUB_LEDGER_DESC", notBlank(p.subLedger()) ? p.subLedger() : "All sub ledgers");
        params.put("TOTAL_CONTROL", tot[0]); params.put("TOTAL_SALES", tot[1]); params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARTL03 — Period Summary  (cobol/ar2/artl03.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record PeriodSummaryParams(LocalDate periodEndDate, String startSubLedger, String endSubLedger) {}

    /**
     * ARTL03 — per sub-ledger opening balance + period movement + closing balance.
     * Opening = period-0 open_bal + movements of periods before the selected one;
     * the selected period's component values are the movement columns; closing =
     * opening + selected movement. arsumry component values carry their natural sign.
     */
    public Map<String, Object> getPeriodSummaryData(AppSession s, PeriodSummaryParams p) {
        if (p.periodEndDate() == null) return warn("Choose a period-ending date.");
        LocalDate sel = p.periodEndDate();
        StringBuilder sql = new StringBuilder(
            "SELECT a.sub_ledger, COALESCE(l.name1,'') AS name1, a.period_end_date, a.open_bal, " +
            "       a.ar_inv_value, a.ar_cr_note_value, a.ar_dr_note_value, a.ar_recpt_value, " +
            "       a.ar_void_chqs_value, a.ar_disc_allow_value, a.ar_curr_fluctuation, a.ar_prov_fluctuation " +
            "FROM arsumry a LEFT JOIN arledgr l ON l.company_no=a.company_no AND l.sub_ledger=a.sub_ledger " +
            "WHERE a.company_no=? AND a.period_end_date<=? ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo()); args.add(sel);
        if (notBlank(p.startSubLedger())) {
            String end = notBlank(p.endSubLedger()) ? p.endSubLedger() : "zzzz";
            sql.append(" AND a.sub_ledger BETWEEN ? AND ? "); args.add(p.startSubLedger()); args.add(end);
        }
        sql.append(" ORDER BY a.sub_ledger, a.period_end_date ");

        // accumulate per sub-ledger
        Map<String, BigDecimal[]> acc = new LinkedHashMap<>();   // [open, prior, inv, cr, dr, recpt, void, disc, fluct]
        Map<String, String> names = new LinkedHashMap<>();
        LocalDate sentinel = LocalDate.of(1900, 1, 1);
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                String sl = trim(r.get("sub_ledger", String.class));
                LocalDate pe = r.get("period_end_date", LocalDate.class);
                BigDecimal[] a = acc.computeIfAbsent(sl, k -> new BigDecimal[]{
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
                names.putIfAbsent(sl, trim(r.get("name1", String.class)));
                BigDecimal inv = z(r.get("ar_inv_value", BigDecimal.class)), cr = z(r.get("ar_cr_note_value", BigDecimal.class)),
                           dr = z(r.get("ar_dr_note_value", BigDecimal.class)), rec = z(r.get("ar_recpt_value", BigDecimal.class)),
                           vd = z(r.get("ar_void_chqs_value", BigDecimal.class)), ds = z(r.get("ar_disc_allow_value", BigDecimal.class)),
                           cf = z(r.get("ar_curr_fluctuation", BigDecimal.class)).add(z(r.get("ar_prov_fluctuation", BigDecimal.class)));
                BigDecimal mvt = inv.add(cr).add(dr).add(rec).add(vd).add(ds).add(cf);
                if (!pe.isAfter(sentinel)) {                       // period-0 opening row
                    a[0] = a[0].add(z(r.get("open_bal", BigDecimal.class)));
                } else if (pe.isBefore(sel)) {                     // prior periods → opening movement
                    a[1] = a[1].add(mvt);
                } else {                                           // selected period → current movement
                    a[2] = a[2].add(inv); a[3] = a[3].add(cr); a[4] = a[4].add(dr);
                    a[5] = a[5].add(rec); a[6] = a[6].add(vd); a[7] = a[7].add(ds); a[8] = a[8].add(cf);
                }
            });
        } catch (Exception e) {
            log.error("getPeriodSummaryData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] g = new BigDecimal[10];
        Arrays.fill(g, BigDecimal.ZERO);
        for (var e : acc.entrySet()) {
            BigDecimal[] a = e.getValue();
            BigDecimal opening = a[0].add(a[1]);
            BigDecimal current = a[2].add(a[3]).add(a[4]).add(a[5]).add(a[6]).add(a[7]).add(a[8]);
            BigDecimal closing = opening.add(current);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subLedger", e.getKey());     row.put("name", names.get(e.getKey()));
            row.put("opening", opening);  row.put("invoices", a[2]); row.put("crNotes", a[3]);
            row.put("drNotes", a[4]);     row.put("receipts", a[5]); row.put("voidChqs", a[6]);
            row.put("discounts", a[7]);   row.put("fluctuation", a[8]); row.put("closing", closing);
            rows.add(row);
            g[0]=g[0].add(opening); g[1]=g[1].add(a[2]); g[2]=g[2].add(a[3]); g[3]=g[3].add(a[4]);
            g[4]=g[4].add(a[5]); g[5]=g[5].add(a[6]); g[6]=g[6].add(a[7]); g[7]=g[7].add(a[8]); g[8]=g[8].add(closing);
        }
        if (rows.isEmpty()) return warn("No period-summary data for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("PERIOD_END", sel.toString());
        params.put("SUB_LEDGER_DESC", notBlank(p.startSubLedger())
            ? p.startSubLedger() + " to " + (notBlank(p.endSubLedger()) ? p.endSubLedger() : "end") : "All sub ledgers");
        params.put("SUM_OPENING", g[0]); params.put("SUM_INV", g[1]); params.put("SUM_CR", g[2]);
        params.put("SUM_DR", g[3]); params.put("SUM_RECPT", g[4]); params.put("SUM_VOID", g[5]);
        params.put("SUM_DISC", g[6]); params.put("SUM_FLUCT", g[7]); params.put("SUM_CLOSING", g[8]);
        params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARTL20 — Document Number  (cobol/ar2/artl20.pl)
    // ════════════════════════════════════════════════════════════════════════

    /** Selection — ARTL20S0 (common fields; PO/location/order ranges omitted). */
    public record DocNumberParams(
            String startDocNo, String endDocNo,
            LocalDate startDate, LocalDate endDate,
            String startCustomer, String endCustomer,
            String source,                 // blank | "AR" | "OP" | "CL"
            int batchNo,
            String reportSeq               // "D" doc | "C" customer | "O" order
    ) {}

    /** ARTL20 — ardocno register joined to arcusts (name / CANCELLED) and artrans (amt). */
    public Map<String, Object> getDocumentNumberData(AppSession s, DocNumberParams p) {
        StringBuilder sql = new StringBuilder(
            "SELECT d.doc_no, d.doc_date, d.doc_type, d.cust_no, d.po_no, d.order_loc_no, d.order_no, " +
            "       d.doc_status, d.system_id, d.batch_no, COALESCE(c.name_1,'') AS name_1, " +
            "       COALESCE(t.amt,0) AS amt " +
            "FROM ardocno d " +
            "LEFT JOIN arcusts c ON c.company_no=d.company_no AND c.cust_no=d.cust_no " +
            "LEFT JOIN artrans t ON t.company_no=d.company_no AND t.cust_no=d.cust_no AND t.doc_date=d.doc_date " +
            "                   AND t.doc_type=d.doc_type AND t.retent_flag=d.retent_flag AND t.doc_no=d.doc_no " +
            "WHERE d.company_no=? ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.startDocNo())) {
            String end = notBlank(p.endDocNo()) ? p.endDocNo() : "zzzzzzzzzz";
            sql.append(" AND d.doc_no BETWEEN ? AND ? "); args.add(p.startDocNo()); args.add(end);
        }
        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND d.doc_date BETWEEN ? AND ? "); args.add(p.startDate()); args.add(end);
        }
        if (notBlank(p.startCustomer())) {
            String end = notBlank(p.endCustomer()) ? p.endCustomer() : "zzzzzzzzzz";
            sql.append(" AND d.cust_no BETWEEN ? AND ? "); args.add(p.startCustomer()); args.add(end);
        }
        if (notBlank(p.source())) { sql.append(" AND d.system_id=? "); args.add(p.source()); }
        if (p.batchNo() > 0)      { sql.append(" AND d.batch_no=? ");  args.add(p.batchNo()); }
        String order = "C".equalsIgnoreCase(p.reportSeq()) ? " ORDER BY d.cust_no, d.doc_no "
                     : "O".equalsIgnoreCase(p.reportSeq()) ? " ORDER BY d.order_loc_no, d.order_no "
                     : " ORDER BY d.doc_no, d.doc_date, d.cust_no ";
        sql.append(order);

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] grand = { BigDecimal.ZERO };
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                boolean cancelled = "C".equalsIgnoreCase(trim(r.get("doc_status", String.class)));
                BigDecimal amt = cancelled ? BigDecimal.ZERO : z(r.get("amt", BigDecimal.class));
                grand[0] = grand[0].add(amt);
                String dt = trim(r.get("doc_type", String.class));
                String orderLoc = trim(r.get("order_loc_no", String.class));
                String orderNo = orderLoc.isEmpty() ? ""
                        : r.get("order_no", Integer.class) + " / " + orderLoc;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("docNo",    r.get("doc_no", String.class));
                row.put("docDate",  sqlDate(r.get("doc_date", LocalDate.class)));
                row.put("docType",  "I".equals(dt) ? "INV" : "C".equals(dt) ? "C/N" : "D".equals(dt) ? "D/N" : dt);
                row.put("custNo",   r.get("cust_no", String.class));
                row.put("name",     cancelled ? "CANCELLED" : r.get("name_1", String.class));
                row.put("amt",      amt);
                row.put("status",   cancelled ? "CANCELLED" : "");
                row.put("poNo",     trim(r.get("po_no", String.class)));
                row.put("orderInfo", orderNo);
                row.put("source",   trim(r.get("system_id", String.class)));
                row.put("batchNo",  r.get("batch_no", Integer.class));
                rows.add(row);
            });
        } catch (Exception e) {
            log.error("getDocumentNumberData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("REPORT_SEQ_DESC", "C".equalsIgnoreCase(p.reportSeq()) ? "Customer" : "O".equalsIgnoreCase(p.reportSeq()) ? "Order number" : "Document number");
        params.put("DOC_RANGE", notBlank(p.startDocNo()) ? p.startDocNo() + " to " + (notBlank(p.endDocNo()) ? p.endDocNo() : "end") : "All documents");
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("GRAND_TOTAL", grand[0]); params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARTL22 — Adjustment Note Analysis  (cobol/ar2/artl22.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record AdjNoteParams(String subLedger, LocalDate startDate, LocalDate endDate, String dateInd) {}

    /** ARTL22 — credit/debit notes (doc_type C/D) over a date range, by sub-ledger. */
    public Map<String, Object> getAdjustmentNoteData(AppSession s, AdjNoteParams p) {
        String dateCol = "D".equalsIgnoreCase(p.dateInd()) ? "t.doc_date" : "t.posting_date";
        StringBuilder sql = new StringBuilder(
            "SELECT t.sub_ledger, t.doc_no, t.doc_type, t.cust_no, COALESCE(c.name_1,'') AS name_1, " +
            "       t.doc_date, t.posting_date, t.amt, t.ref " +
            "FROM artrans t LEFT JOIN arcusts c ON c.company_no=t.company_no AND c.cust_no=t.cust_no " +
            "WHERE t.company_no=? AND t.doc_type IN ('C','D') ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.subLedger())) { sql.append(" AND t.sub_ledger=? "); args.add(p.subLedger()); }
        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND ").append(dateCol).append(" BETWEEN ? AND ? ");
            args.add(p.startDate()); args.add(end);
        }
        sql.append(" ORDER BY t.sub_ledger, ").append(dateCol).append(", t.doc_type, t.doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };   // CR, DR
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                String dt = trim(r.get("doc_type", String.class));
                BigDecimal amt = z(r.get("amt", BigDecimal.class));
                if ("C".equals(dt)) tot[0] = tot[0].add(amt); else tot[1] = tot[1].add(amt);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("subLedger",   r.get("sub_ledger", String.class));
                row.put("docNo",       r.get("doc_no", String.class));
                row.put("docType",     "C".equals(dt) ? "C/N" : "D/N");
                row.put("custNo",      r.get("cust_no", String.class));
                row.put("name",        r.get("name_1", String.class));
                row.put("docDate",     sqlDate(r.get("doc_date", LocalDate.class)));
                row.put("postingDate", sqlDate(r.get("posting_date", LocalDate.class)));
                row.put("amt",         amt);
                row.put("reference",   r.get("ref", String.class));
                rows.add(row);
            });
        } catch (Exception e) {
            log.error("getAdjustmentNoteData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SUB_LEDGER_DESC", notBlank(p.subLedger()) ? p.subLedger() : "All sub ledgers");
        params.put("DATE_BASIS_DESC", "D".equalsIgnoreCase(p.dateInd()) ? "Document date" : "Posting date");
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_CR", tot[0]); params.put("SUM_DR", tot[1]);
        params.put("SUM_NET", tot[0].add(tot[1])); params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ── period helpers for the sales-history (13-column) reports ─────────────

    /** 1..13 period number for a period-ending date in the company's current year, or -1. */
    private int resolvePeriodNo(AppSession s, LocalDate pe) {
        if (pe == null) return -1;
        int[] r = { -1 };
        try {
            StringBuilder cols = new StringBuilder();
            for (int i = 1; i <= 13; i++) cols.append(i > 1 ? "," : "").append(String.format("period_end_%02d", i));
            dsl.resultQuery("SELECT " + cols + " FROM gldates WHERE company_no=? AND year_no=?",
                s.getCompanyNo(), s.getYearNo())
               .fetch()
               .forEach(rec -> {
                   for (int i = 1; i <= 13; i++) {
                       LocalDate d = rec.get(String.format("period_end_%02d", i), LocalDate.class);
                       if (d != null && d.equals(pe)) r[0] = i;
                   }
               });
        } catch (Exception e) { log.warn("resolvePeriodNo: {}", e.getMessage()); }
        return r[0];
    }

    private static BigDecimal[] read13(org.jooq.Record r, String prefix) {
        BigDecimal[] a = new BigDecimal[14];
        for (int i = 1; i <= 13; i++) a[i] = z(r.get(String.format(prefix + "_%02d", i), BigDecimal.class));
        return a;
    }

    private static BigDecimal ytd(BigDecimal[] a, int n) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 1; i <= n && i <= 13; i++) sum = sum.add(a[i]);
        return sum;
    }

    /** Variance percentage = base ÷ ref × 100 (0 when ref is zero). */
    private static BigDecimal varPct(BigDecimal base, BigDecimal ref) {
        if (ref == null || ref.signum() == 0) return BigDecimal.ZERO;
        return base.multiply(BigDecimal.valueOf(100)).divide(ref, 2, java.math.RoundingMode.HALF_UP);
    }

    private static final String SALES_13 =
        "sales_01,sales_02,sales_03,sales_04,sales_05,sales_06,sales_07,sales_08,sales_09,sales_10,sales_11,sales_12,sales_13";
    private static final String COST_13 =
        "cost_01,cost_02,cost_03,cost_04,cost_05,cost_06,cost_07,cost_08,cost_09,cost_10,cost_11,cost_12,cost_13";

    // ════════════════════════════════════════════════════════════════════════
    // ARTL10 — Sales Distribution  (cobol/ar2/artl10.pl)   MTD/YTD vs prior year
    // ════════════════════════════════════════════════════════════════════════

    public record SalesDistParams(String subLedger, LocalDate periodEndDate) {}

    /** ARTL10 — arsales by sub-ledger + sales code, this vs last year MTD/YTD with variances. */
    public Map<String, Object> getSalesDistributionData(AppSession s, SalesDistParams p) {
        int period = resolvePeriodNo(s, p.periodEndDate());
        if (period < 1) return warn("Choose a valid period-ending date.");
        int thisYr = s.getYearNo(), lastYr = thisYr - 1;

        StringBuilder sql = new StringBuilder(
            "SELECT sub_ledger, sales_code, year_no, " + SALES_13 + " FROM arsales " +
            "WHERE company_no=? AND year_no IN (?,?) ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo()); args.add(thisYr); args.add(lastYr);
        if (notBlank(p.subLedger())) { sql.append(" AND sub_ledger=? "); args.add(p.subLedger()); }
        sql.append(" ORDER BY sub_ledger, sales_code ");

        // key -> [mtdThis, ytdThis, mtdLast, ytdLast]
        Map<String, BigDecimal[]> acc = new LinkedHashMap<>();
        Map<String, String[]> keys = new LinkedHashMap<>();
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                String sl = trim(r.get("sub_ledger", String.class)), sc = trim(r.get("sales_code", String.class));
                String key = sl + "" + sc;
                BigDecimal[] sales = read13(r, "sales");
                BigDecimal[] a = acc.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                keys.putIfAbsent(key, new String[]{sl, sc});
                if (Integer.valueOf(thisYr).equals(r.get("year_no", Integer.class))) { a[0] = sales[period]; a[1] = ytd(sales, period); }
                else                                { a[2] = sales[period]; a[3] = ytd(sales, period); }
            });
        } catch (Exception e) {
            log.error("getSalesDistributionData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] g = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        for (var e : acc.entrySet()) {
            BigDecimal[] a = e.getValue(); String[] k = keys.get(e.getKey());
            if (a[0].signum()==0 && a[1].signum()==0 && a[2].signum()==0 && a[3].signum()==0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subLedger", k[0]); row.put("salesCode", k[1]);
            row.put("mtdThis", a[0]); row.put("mtdLast", a[2]);
            row.put("mtdVar", a[0].subtract(a[2])); row.put("mtdVarPct", varPct(a[0].subtract(a[2]), a[0]));
            row.put("ytdThis", a[1]); row.put("ytdLast", a[3]);
            row.put("ytdVar", a[1].subtract(a[3])); row.put("ytdVarPct", varPct(a[1].subtract(a[3]), a[1]));
            rows.add(row);
            g[0]=g[0].add(a[0]); g[1]=g[1].add(a[2]); g[2]=g[2].add(a[1]); g[3]=g[3].add(a[3]);
        }
        if (rows.isEmpty()) return warn("No sales for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("PERIOD_END", dmy(p.periodEndDate()));
        params.put("SUB_LEDGER_DESC", notBlank(p.subLedger()) ? p.subLedger() : "All sub ledgers");
        params.put("THIS_YR", thisYr); params.put("LAST_YR", lastYr);
        params.put("SUM_MTD_THIS", g[0]); params.put("SUM_MTD_LAST", g[1]);
        params.put("SUM_YTD_THIS", g[2]); params.put("SUM_YTD_LAST", g[3]);
        params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARTL18 — Sales by GL  (cobol/ar2/artl18.pl)   MTD/YTD vs prior year
    // ════════════════════════════════════════════════════════════════════════

    public record SalesByGlParams(LocalDate periodEndDate) {}

    /** ARTL18 — arsaleg by GL account, this vs last year MTD/YTD with variances. */
    public Map<String, Object> getSalesByGlData(AppSession s, SalesByGlParams p) {
        int period = resolvePeriodNo(s, p.periodEndDate());
        if (period < 1) return warn("Choose a valid period-ending date.");
        int thisYr = s.getYearNo(), lastYr = thisYr - 1;

        String sql = "SELECT a.gl_acct_main, a.gl_acct_sub, a.year_no, " + SALES_13 + ", " +
            "COALESCE(g.desc1,'') AS gl_desc FROM arsaleg a " +
            "LEFT JOIN glchart g ON g.company_no=a.company_no AND g.acct_main_no=a.gl_acct_main AND g.acct_sub_no=a.gl_acct_sub " +
            "WHERE a.company_no=? AND a.year_no IN (?,?) ORDER BY a.gl_acct_main, a.gl_acct_sub ";

        Map<String, BigDecimal[]> acc = new LinkedHashMap<>();
        Map<String, Object[]> keys = new LinkedHashMap<>();
        try {
            dsl.resultQuery(sql, s.getCompanyNo(), thisYr, lastYr).fetch().forEach(r -> {
                int main = r.get("gl_acct_main", Integer.class), sub = r.get("gl_acct_sub", Integer.class);
                String key = main + "-" + sub;
                BigDecimal[] sales = read13(r, "sales");
                BigDecimal[] a = acc.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                keys.putIfAbsent(key, new Object[]{main, sub, trim(r.get("gl_desc", String.class))});
                if (Integer.valueOf(thisYr).equals(r.get("year_no", Integer.class))) { a[0] = sales[period]; a[1] = ytd(sales, period); }
                else                                { a[2] = sales[period]; a[3] = ytd(sales, period); }
            });
        } catch (Exception e) {
            log.error("getSalesByGlData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] g = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        for (var e : acc.entrySet()) {
            BigDecimal[] a = e.getValue(); Object[] k = keys.get(e.getKey());
            if (a[0].signum()==0 && a[1].signum()==0 && a[2].signum()==0 && a[3].signum()==0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("glAcctMain", k[0]); row.put("glAcctSub", k[1]); row.put("glDesc", k[2]);
            row.put("mtdThis", a[0]); row.put("mtdLast", a[2]);
            row.put("mtdVar", a[0].subtract(a[2])); row.put("mtdVarPct", varPct(a[0].subtract(a[2]), a[0]));
            row.put("ytdThis", a[1]); row.put("ytdLast", a[3]);
            row.put("ytdVar", a[1].subtract(a[3])); row.put("ytdVarPct", varPct(a[1].subtract(a[3]), a[1]));
            rows.add(row);
            g[0]=g[0].add(a[0]); g[1]=g[1].add(a[2]); g[2]=g[2].add(a[1]); g[3]=g[3].add(a[3]);
        }
        if (rows.isEmpty()) return warn("No GL sales for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("PERIOD_END", dmy(p.periodEndDate()));
        params.put("THIS_YR", thisYr); params.put("LAST_YR", lastYr);
        params.put("SUM_MTD_THIS", g[0]); params.put("SUM_MTD_LAST", g[1]);
        params.put("SUM_YTD_THIS", g[2]); params.put("SUM_YTD_LAST", g[3]);
        params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARTL11 — Debtors Control  (cobol/ar2/artl11.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record DebtorsControlParams(
            String subLedger, String startCustomer, String endCustomer,
            String sortSeq,          // "A" account balance | "S" sales value
            String mtdYtd,           // "M" | "Y"
            LocalDate periodEndDate,
            int topN,
            boolean includeCreditLimit
    ) {}

    /** ARTL11 — customer account balances + period sales, sortable, optional top-N. */
    public Map<String, Object> getDebtorsControlData(AppSession s, DebtorsControlParams p) {
        int period = resolvePeriodNo(s, p.periodEndDate());
        boolean ytd = !"M".equalsIgnoreCase(p.mtdYtd());
        StringBuilder sql = new StringBuilder(
            "SELECT c.sub_ledger, c.cust_no, c.name_1, c.acct_bal, c.credit_limit, c.acct_status, " +
            "       COALESCE((SELECT CONCAT_WS('|', " + SALES_13 + ") FROM arsalec a " +
            "                 WHERE a.company_no=c.company_no AND a.sub_ledger=c.sub_ledger " +
            "                   AND a.cust_no=c.cust_no AND a.year_no=?),'') AS sales_csv " +
            "FROM arcusts c WHERE c.company_no=? ");
        List<Object> args = new ArrayList<>();
        args.add(s.getYearNo()); args.add(s.getCompanyNo());
        if (notBlank(p.subLedger())) { sql.append(" AND c.sub_ledger=? "); args.add(p.subLedger()); }
        if (notBlank(p.startCustomer())) {
            String end = notBlank(p.endCustomer()) ? p.endCustomer() : "zzzzzzzzzz";
            sql.append(" AND c.cust_no BETWEEN ? AND ? "); args.add(p.startCustomer()); args.add(end);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                BigDecimal acctBal = z(r.get("acct_bal", BigDecimal.class));
                BigDecimal sales = BigDecimal.ZERO;
                String csv = r.get("sales_csv", String.class);
                if (csv != null && !csv.isEmpty() && period >= 1) {
                    String[] parts = csv.split("\\|", -1);
                    if (ytd) { for (int i = 0; i < period && i < parts.length; i++) sales = sales.add(new BigDecimal(parts[i])); }
                    else if (period - 1 < parts.length) sales = new BigDecimal(parts[period - 1]);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("subLedger",   r.get("sub_ledger", String.class));
                row.put("custNo",      r.get("cust_no", String.class));
                row.put("name",        r.get("name_1", String.class));
                row.put("acctBal",     acctBal);
                row.put("sales",       sales);
                row.put("creditLimit", r.get("credit_limit", Long.class));
                row.put("status",      acctStatusDesc(r.get("acct_status", String.class)));
                rows.add(row);
            });
        } catch (Exception e) {
            log.error("getDebtorsControlData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }

        boolean bySales = "S".equalsIgnoreCase(p.sortSeq());
        rows.sort((a, b) -> {
            BigDecimal va = (BigDecimal) a.get(bySales ? "sales" : "acctBal");
            BigDecimal vb = (BigDecimal) b.get(bySales ? "sales" : "acctBal");
            return vb.compareTo(va);
        });
        List<Map<String, Object>> outRows = (p.topN() > 0 && rows.size() > p.topN())
            ? new ArrayList<>(rows.subList(0, p.topN())) : rows;

        BigDecimal sumBal = BigDecimal.ZERO, sumSales = BigDecimal.ZERO;
        for (Map<String, Object> r : outRows) { sumBal = sumBal.add((BigDecimal) r.get("acctBal")); sumSales = sumSales.add((BigDecimal) r.get("sales")); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SUB_LEDGER_DESC", notBlank(p.subLedger()) ? p.subLedger() : "All sub ledgers");
        params.put("SORT_DESC", bySales ? "Sales value" : "Account balance");
        params.put("SALES_BASIS", ytd ? "YTD" : "MTD");
        params.put("SHOW_CR_LIMIT", p.includeCreditLimit());
        params.put("SUM_BAL", sumBal); params.put("SUM_SALES", sumSales); params.put("ROW_COUNT", outRows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", outRows); result.put("params", params); result.put("rowCount", outRows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARTL21 — Customer Account Status  (cobol/ar2/artl21.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record AcctStatusParams(
            String startCustomer, String endCustomer,
            String startSubLedger, String endSubLedger,
            boolean active, boolean noSales, boolean onHold, boolean inactive
    ) {}

    /** ARTL21 — customers filtered by account status, by sub-ledger then customer. */
    public Map<String, Object> getCustomerAccountStatusData(AppSession s, AcctStatusParams p) {
        List<String> statusOr = new ArrayList<>();
        if (p.active())   statusOr.add("TRIM(acct_status)=''");
        if (p.noSales())  statusOr.add("acct_status='N'");
        if (p.onHold())   statusOr.add("acct_status='H'");
        if (p.inactive()) statusOr.add("acct_status='I'");
        if (statusOr.isEmpty()) return warn("Select at least one account status.");

        StringBuilder sql = new StringBuilder(
            "SELECT cust_no, name_1, sub_ledger, acct_status, acct_bal, acct_add_date, acct_close_date " +
            "FROM arcusts WHERE company_no=? ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.startCustomer())) {
            String end = notBlank(p.endCustomer()) ? p.endCustomer() : "zzzzzzzzzz";
            sql.append(" AND cust_no BETWEEN ? AND ? "); args.add(p.startCustomer()); args.add(end);
        }
        if (notBlank(p.startSubLedger())) {
            String end = notBlank(p.endSubLedger()) ? p.endSubLedger() : "zzzz";
            sql.append(" AND sub_ledger BETWEEN ? AND ? "); args.add(p.startSubLedger()); args.add(end);
        }
        sql.append(" AND (").append(String.join(" OR ", statusOr)).append(") ");
        sql.append(" ORDER BY sub_ledger, cust_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("custNo",     r.get("cust_no", String.class));
                row.put("name",       r.get("name_1", String.class));
                row.put("subLedger",  r.get("sub_ledger", String.class));
                row.put("status",     statusFull(r.get("acct_status", String.class)));
                row.put("acctBal",    z(r.get("acct_bal", BigDecimal.class)));
                row.put("addDate",    sqlDate(r.get("acct_add_date", LocalDate.class)));
                row.put("closeDate",  sqlDate(r.get("acct_close_date", LocalDate.class)));
                tot[0] = tot[0].add((BigDecimal) row.get("acctBal"));
                rows.add(row);
            });
        } catch (Exception e) {
            log.error("getCustomerAccountStatusData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SUM_BAL", tot[0]); params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    private static String statusFull(String code) {
        return switch (trim(code)) {
            case "H" -> "ON HOLD"; case "N" -> "NO SALES"; case "I" -> "INACTIVE"; default -> "ACTIVE";
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARTL06 / ARTL27 — Customer Sales by Customer Type / by Sub Ledger
    // ════════════════════════════════════════════════════════════════════════

    public record CustomerSalesParams(
            boolean byType,                 // true = ARTL06 (group by type), false = ARTL27 (group by sub-ledger)
            String startSubLedger, String endSubLedger,
            LocalDate periodEndDate,
            boolean includeLastYear
    ) {}

    /** ARTL06 / ARTL27 — customer YTD sales / cost / profit %, this vs last year, grouped. */
    public Map<String, Object> getCustomerSalesData(AppSession s, CustomerSalesParams p) {
        int period = resolvePeriodNo(s, p.periodEndDate());
        if (period < 1) return warn("Choose a valid period-ending date.");
        int thisYr = s.getYearNo(), lastYr = thisYr - 1;

        StringBuilder sql = new StringBuilder(
            "SELECT c.sub_ledger, c.cust_no, c.name_1, c.type, a.year_no, " + SALES_13 + ", " + COST_13 + " " +
            "FROM arcusts c JOIN arsalec a ON a.company_no=c.company_no AND a.sub_ledger=c.sub_ledger " +
            "             AND a.cust_no=c.cust_no AND a.year_no IN (?,?) " +
            "WHERE c.company_no=? ");
        List<Object> args = new ArrayList<>();
        args.add(thisYr); args.add(lastYr); args.add(s.getCompanyNo());
        if (notBlank(p.startSubLedger())) {
            String end = notBlank(p.endSubLedger()) ? p.endSubLedger() : "zzzz";
            sql.append(" AND c.sub_ledger BETWEEN ? AND ? "); args.add(p.startSubLedger()); args.add(end);
        }
        String groupCol = p.byType() ? "c.type" : "c.sub_ledger";
        sql.append(" ORDER BY ").append(groupCol).append(", c.cust_no ");

        // key cust -> [salesThis, costThis, salesLast]
        Map<String, BigDecimal[]> acc = new LinkedHashMap<>();
        Map<String, String[]> meta = new LinkedHashMap<>();
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                String cust = trim(r.get("cust_no", String.class));
                String grp = p.byType() ? trim(r.get("type", String.class)) : trim(r.get("sub_ledger", String.class));
                String key = grp + "" + cust;
                BigDecimal[] a = acc.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                meta.putIfAbsent(key, new String[]{grp, cust, trim(r.get("name_1", String.class)), trim(r.get("sub_ledger", String.class))});
                if (Integer.valueOf(thisYr).equals(r.get("year_no", Integer.class))) {
                    a[0] = ytd(read13(r, "sales"), period);
                    a[1] = ytd(read13(r, "cost"), period);
                } else {
                    a[2] = ytd(read13(r, "sales"), period);
                }
            });
        } catch (Exception e) {
            log.error("getCustomerSalesData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] g = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        for (var e : acc.entrySet()) {
            BigDecimal[] a = e.getValue(); String[] m = meta.get(e.getKey());
            if (a[0].signum()==0 && a[1].signum()==0 && a[2].signum()==0) continue;
            BigDecimal profit = a[0].subtract(a[1]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupKey",  m[0]); row.put("custNo", m[1]); row.put("name", m[2]); row.put("subLedger", m[3]);
            row.put("salesYtd",  a[0]); row.put("costYtd", a[1]); row.put("profit", profit);
            row.put("profitPct", varPct(profit, a[0]));
            row.put("salesLastYtd", a[2]);
            row.put("salesVar", a[0].subtract(a[2]));
            rows.add(row);
            g[0]=g[0].add(a[0]); g[1]=g[1].add(a[1]); g[2]=g[2].add(a[2]);
        }
        if (rows.isEmpty()) return warn("No customer sales for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("GROUP_BY", p.byType() ? "Customer type" : "Sub ledger");
        params.put("PERIOD_END", dmy(p.periodEndDate()));
        params.put("THIS_YR", thisYr); params.put("LAST_YR", lastYr);
        params.put("SHOW_LAST_YR", p.includeLastYear());
        params.put("SUM_SALES", g[0]); params.put("SUM_COST", g[1]); params.put("SUM_SALES_LAST", g[2]);
        params.put("SUM_PROFIT", g[0].subtract(g[1])); params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARTL15 — Customer Sales by Salesperson  (cobol/ar2/artl15.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record SalesBySalespersonParams(String startSalesman, String endSalesman, LocalDate periodEndDate) {}

    /** ARTL15 — customers grouped by salesman, MTD + YTD sales for the chosen period. */
    public Map<String, Object> getSalesBySalespersonData(AppSession s, SalesBySalespersonParams p) {
        int period = resolvePeriodNo(s, p.periodEndDate());
        if (period < 1) return warn("Choose a valid period-ending date.");

        StringBuilder sql = new StringBuilder(
            "SELECT c.salesman, COALESCE(sm.name1,'') AS salesman_name, c.cust_no, c.alpha_key, c.name_1, " +
            "       c.city, c.state, c.contact_phone, " + SALES_13 + " " +
            "FROM arcusts c " +
            "LEFT JOIN arcodsm sm ON sm.company_no=c.company_no AND sm.salesman_code=c.salesman " +
            "LEFT JOIN arsalec a ON a.company_no=c.company_no AND a.sub_ledger=c.sub_ledger " +
            "                   AND a.cust_no=c.cust_no AND a.year_no=? " +
            "WHERE c.company_no=? ");
        List<Object> args = new ArrayList<>();
        args.add(s.getYearNo()); args.add(s.getCompanyNo());
        if (notBlank(p.startSalesman())) {
            String end = notBlank(p.endSalesman()) ? p.endSalesman() : "zzzzzz";
            sql.append(" AND c.salesman BETWEEN ? AND ? "); args.add(p.startSalesman()); args.add(end);
        }
        sql.append(" ORDER BY c.salesman, c.cust_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] g = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                BigDecimal[] sales = read13(r, "sales");
                BigDecimal mtd = sales[period], y = ytd(sales, period);
                if (mtd.signum() == 0 && y.signum() == 0) return;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("salesman",     trim(r.get("salesman", String.class)));
                row.put("salesmanName", trim(r.get("salesman_name", String.class)));
                row.put("custNo",       r.get("cust_no", String.class));
                row.put("alphaKey",     r.get("alpha_key", String.class));
                row.put("name",         r.get("name_1", String.class));
                row.put("location",     trim(r.get("city", String.class)) + " " + trim(r.get("state", String.class)));
                row.put("phone",        r.get("contact_phone", String.class));
                row.put("mtdSales",     mtd);
                row.put("ytdSales",     y);
                rows.add(row);
                g[0] = g[0].add(mtd); g[1] = g[1].add(y);
            });
        } catch (Exception e) {
            log.error("getSalesBySalespersonData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No customer sales for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("PERIOD_END", dmy(p.periodEndDate()));
        params.put("SALESMAN_RANGE", notBlank(p.startSalesman())
            ? p.startSalesman() + " to " + (notBlank(p.endSalesman()) ? p.endSalesman() : "end") : "All salespeople");
        params.put("SUM_MTD", g[0]); params.put("SUM_YTD", g[1]); params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARTL16 — Salesperson Profitability  (cobol/ar2/artl16.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record SalespersonProfitParams(
            String startSalesman, String endSalesman,
            LocalDate startDate, LocalDate endDate
    ) {}

    /** ARTL16 — per-transaction sales, cost, gross profit and margin %, grouped by salesman. */
    public Map<String, Object> getSalespersonProfitData(AppSession s, SalespersonProfitParams p) {
        StringBuilder sql = new StringBuilder(
            "SELECT t.salesman, COALESCE(sm.name1,'') AS salesman_name, t.cust_no, COALESCE(c.name_1,'') AS name_1, " +
            "       t.doc_type, t.doc_no, t.doc_date, t.sale_amt, t.cost_of_sale_amt " +
            "FROM artrans t " +
            "LEFT JOIN arcusts c ON c.company_no=t.company_no AND c.cust_no=t.cust_no " +
            "LEFT JOIN arcodsm sm ON sm.company_no=t.company_no AND sm.salesman_code=t.salesman " +
            "WHERE t.company_no=? AND t.doc_type IN ('I','D','C','V') AND t.trx_status<>'U' ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.startSalesman())) {
            String end = notBlank(p.endSalesman()) ? p.endSalesman() : "zzzzzz";
            sql.append(" AND t.salesman BETWEEN ? AND ? "); args.add(p.startSalesman()); args.add(end);
        }
        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND t.doc_date BETWEEN ? AND ? "); args.add(p.startDate()); args.add(end);
        }
        sql.append(" ORDER BY t.salesman, t.doc_date, t.doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] g = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                BigDecimal sale = z(r.get("sale_amt", BigDecimal.class)), cost = z(r.get("cost_of_sale_amt", BigDecimal.class));
                BigDecimal profit = sale.subtract(cost);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("salesman",     trim(r.get("salesman", String.class)));
                row.put("salesmanName", trim(r.get("salesman_name", String.class)));
                row.put("custNo",       r.get("cust_no", String.class));
                row.put("name",         r.get("name_1", String.class));
                row.put("docType",      docTypeLabel(trim(r.get("doc_type", String.class))));
                row.put("docNo",        r.get("doc_no", String.class));
                row.put("docDate",      sqlDate(r.get("doc_date", LocalDate.class)));
                row.put("saleAmt",      sale);
                row.put("costAmt",      cost);
                row.put("grossProfit",  profit);
                row.put("profitPct",    varPct(profit, sale));
                rows.add(row);
                g[0] = g[0].add(sale); g[1] = g[1].add(cost);
            });
        } catch (Exception e) {
            log.error("getSalespersonProfitData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No transactions for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SALESMAN_RANGE", notBlank(p.startSalesman())
            ? p.startSalesman() + " to " + (notBlank(p.endSalesman()) ? p.endSalesman() : "end") : "All salespeople");
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_SALES", g[0]); params.put("SUM_COST", g[1]);
        params.put("SUM_PROFIT", g[0].subtract(g[1])); params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARTL05 — Sales Journal  (cobol/ar2/artl05.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record SalesJournalParams(
            String subLedger, String dateInd,   // "D" doc | "P" posting
            LocalDate startDate, LocalDate endDate, String seq   // "N" doc-no | else date
    ) {}

    /** ARTL05 — sales journal: invoices / debit / credit notes by sub-ledger over a date range. */
    public Map<String, Object> getSalesJournalData(AppSession s, SalesJournalParams p) {
        String dateCol = "P".equalsIgnoreCase(p.dateInd()) ? "t.posting_date" : "t.doc_date";
        StringBuilder sql = new StringBuilder(
            "SELECT t.sub_ledger, t.doc_date, t.posting_date, t.doc_type, t.doc_no, t.cust_no, " +
            "       COALESCE(c.name_1,'') AS name_1, t.sale_amt, t.sales_tax_amt, t.amt, t.salesman " +
            "FROM artrans t LEFT JOIN arcusts c ON c.company_no=t.company_no AND c.cust_no=t.cust_no " +
            "WHERE t.company_no=? AND t.doc_type IN ('I','D','C') AND t.trx_status<>'U' ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.subLedger())) { sql.append(" AND t.sub_ledger=? "); args.add(p.subLedger()); }
        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND ").append(dateCol).append(" BETWEEN ? AND ? ");
            args.add(p.startDate()); args.add(end);
        }
        sql.append("N".equalsIgnoreCase(p.seq())
            ? " ORDER BY t.sub_ledger, t.doc_no " : " ORDER BY t.sub_ledger, " + dateCol + ", t.doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] g = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };  // sale, tax, amt
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                BigDecimal sale = z(r.get("sale_amt", BigDecimal.class)), tax = z(r.get("sales_tax_amt", BigDecimal.class)), amt = z(r.get("amt", BigDecimal.class));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("subLedger",   r.get("sub_ledger", String.class));
                row.put("docDate",     sqlDate(r.get("doc_date", LocalDate.class)));
                row.put("postingDate", sqlDate(r.get("posting_date", LocalDate.class)));
                row.put("docType",     docTypeLabel(trim(r.get("doc_type", String.class))));
                row.put("docNo",       r.get("doc_no", String.class));
                row.put("custNo",      r.get("cust_no", String.class));
                row.put("name",        r.get("name_1", String.class));
                row.put("saleAmt",     sale);
                row.put("taxAmt",      tax);
                row.put("amt",         amt);
                row.put("salesman",    trim(r.get("salesman", String.class)));
                rows.add(row);
                g[0]=g[0].add(sale); g[1]=g[1].add(tax); g[2]=g[2].add(amt);
            });
        } catch (Exception e) {
            log.error("getSalesJournalData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No journal entries for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SUB_LEDGER_DESC", notBlank(p.subLedger()) ? p.subLedger() : "All sub ledgers");
        params.put("DATE_BASIS_DESC", "P".equalsIgnoreCase(p.dateInd()) ? "Posting date" : "Document date");
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_SALE", g[0]); params.put("SUM_TAX", g[1]); params.put("SUM_AMT", g[2]); params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARTL04 — Commission  (cobol/ar2/artl04.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record CommissionParams(
            String startSalesman, String endSalesman,
            LocalDate startDate, LocalDate endDate
    ) {}

    /** ARTL04 — per-transaction commission (rate + amount), grouped by salesman. */
    public Map<String, Object> getCommissionData(AppSession s, CommissionParams p) {
        StringBuilder sql = new StringBuilder(
            "SELECT t.salesman, COALESCE(sm.name1,'') AS salesman_name, t.doc_date, t.doc_type, t.retent_flag, " +
            "       t.doc_no, t.cust_no, COALESCE(c.name_1,'') AS name_1, t.amt, t.sale_amt, t.comm_rate, t.comm_amt " +
            "FROM artrans t " +
            "LEFT JOIN arcusts c ON c.company_no=t.company_no AND c.cust_no=t.cust_no " +
            "LEFT JOIN arcodsm sm ON sm.company_no=t.company_no AND sm.salesman_code=t.salesman " +
            "WHERE t.company_no=? AND t.doc_type IN ('I','D','C','V') AND t.trx_status<>'U' ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.startSalesman())) {
            String end = notBlank(p.endSalesman()) ? p.endSalesman() : "zzzzzz";
            sql.append(" AND t.salesman BETWEEN ? AND ? "); args.add(p.startSalesman()); args.add(end);
        }
        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND t.doc_date BETWEEN ? AND ? "); args.add(p.startDate()); args.add(end);
        }
        sql.append(" ORDER BY t.salesman, t.doc_date, t.doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] g = { BigDecimal.ZERO, BigDecimal.ZERO };   // sale, commission
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("salesman",     trim(r.get("salesman", String.class)));
                row.put("salesmanName", trim(r.get("salesman_name", String.class)));
                row.put("docDate",      sqlDate(r.get("doc_date", LocalDate.class)));
                row.put("docType",      docTypeLabel(trim(r.get("doc_type", String.class))));
                row.put("retentFlag",   r.get("retent_flag", String.class));
                row.put("docNo",        r.get("doc_no", String.class));
                row.put("custNo",       r.get("cust_no", String.class));
                row.put("name",         r.get("name_1", String.class));
                row.put("docAmt",       z(r.get("amt", BigDecimal.class)));
                row.put("saleAmt",      z(r.get("sale_amt", BigDecimal.class)));
                row.put("commPct",      z(r.get("comm_rate", BigDecimal.class)));
                row.put("commission",   z(r.get("comm_amt", BigDecimal.class)));
                rows.add(row);
                g[0] = g[0].add((BigDecimal) row.get("saleAmt"));
                g[1] = g[1].add((BigDecimal) row.get("commission"));
            });
        } catch (Exception e) {
            log.error("getCommissionData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No commission transactions for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SALESMAN_RANGE", notBlank(p.startSalesman())
            ? p.startSalesman() + " to " + (notBlank(p.endSalesman()) ? p.endSalesman() : "end") : "All salespeople");
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_SALE", g[0]); params.put("SUM_COMMISSION", g[1]); params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL38 — Customer Sales by Year  (cobol/sm2/smtl38.pl)
    // ════════════════════════════════════════════════════════════════════════

    /** Selection — SMTL38S0. Year 1 = [startDate..endDate]; years 2–5 trail by 1–4 years. */
    public record CustomerSalesYearParams(
            String startCustomer, String endCustomer,
            String startSubLedger, String endSubLedger,
            String startCustType, String endCustType,
            String startProductType, String endProductType,
            String startSalesman, String endSalesman,
            LocalDate startDate, LocalDate endDate
    ) {}

    /**
     * SMTL38 — five trailing-year sales totals per customer from smtrans
     * (sales_or_recpt_value negated to show sales positive). Year N covers
     * {@code [startDate−(N−1)yr, endDate−(N−1)yr]}. Filtered by customer / salesman
     * ranges (smtrans) and product-type (smsthed) / sub-ledger + customer-type
     * (arcusts) ranges — all inner joins, matching CHECK-TRX / CREATE-WORKFILE-RECORD.
     */
    public Map<String, Object> getCustomerSalesByYearData(AppSession s, CustomerSalesYearParams p) {
        if (p.startDate() == null || p.endDate() == null) return warn("Enter the year-1 date range.");
        LocalDate[] winStart = new LocalDate[6], winEnd = new LocalDate[6];
        for (int n = 1; n <= 5; n++) { winStart[n] = p.startDate().minusYears(n - 1); winEnd[n] = p.endDate().minusYears(n - 1); }
        LocalDate broadStart = winStart[5], broadEnd = winEnd[1];

        // smsthed (product-type) and arcusts (name / sub-ledger / type) are enrichment
        // lookups — LEFT-joined so the report still lists smtrans sales when those
        // masters are sparse. The range filters below still require a match when used.
        StringBuilder sql = new StringBuilder(
            "SELECT t.cust_supplier_no, c.name_1, t.move_date, t.sales_or_recpt_value " +
            "FROM smtrans t " +
            "LEFT JOIN smsthed h ON h.company_no=t.company_no AND h.stock_code=t.stock_code " +
            "LEFT JOIN arcusts c ON c.company_no=t.company_no AND c.cust_no=t.cust_supplier_no " +
            "WHERE t.company_no=? AND t.move_date BETWEEN ? AND ? ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo()); args.add(broadStart); args.add(broadEnd);
        if (notBlank(p.startCustomer())) {
            String end = notBlank(p.endCustomer()) ? p.endCustomer() : "zzzzzzzzzz";
            sql.append(" AND t.cust_supplier_no BETWEEN ? AND ? "); args.add(p.startCustomer()); args.add(end);
        }
        if (notBlank(p.startSalesman())) {
            String end = notBlank(p.endSalesman()) ? p.endSalesman() : "zzzzzz";
            sql.append(" AND t.salesman BETWEEN ? AND ? "); args.add(p.startSalesman()); args.add(end);
        }
        if (notBlank(p.startProductType())) {
            String end = notBlank(p.endProductType()) ? p.endProductType() : "zzzzzz";
            sql.append(" AND h.product_type BETWEEN ? AND ? "); args.add(p.startProductType()); args.add(end);
        }
        if (notBlank(p.startSubLedger())) {
            String end = notBlank(p.endSubLedger()) ? p.endSubLedger() : "zzzz";
            sql.append(" AND c.sub_ledger BETWEEN ? AND ? "); args.add(p.startSubLedger()); args.add(end);
        }
        if (notBlank(p.startCustType())) {
            String end = notBlank(p.endCustType()) ? p.endCustType() : "zzzzzz";
            sql.append(" AND c.type BETWEEN ? AND ? "); args.add(p.startCustType()); args.add(end);
        }
        sql.append(" ORDER BY t.cust_supplier_no ");

        Map<String, BigDecimal[]> acc = new LinkedHashMap<>();   // [total, yr1, yr2, yr3, yr4, yr5]
        Map<String, String> names = new LinkedHashMap<>();
        try {
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                LocalDate md = ld(r.get("move_date", LocalDate.class));
                if (md == null) return;
                int win = 0;
                for (int n = 1; n <= 5; n++) if (!md.isBefore(winStart[n]) && !md.isAfter(winEnd[n])) { win = n; break; }
                if (win == 0) return;
                BigDecimal val = z(r.get("sales_or_recpt_value", BigDecimal.class)).negate();
                String cust = trim(r.get("cust_supplier_no", String.class));
                BigDecimal[] a = acc.computeIfAbsent(cust, k -> new BigDecimal[]{
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
                names.putIfAbsent(cust, trim(r.get("name_1", String.class)));
                a[win] = a[win].add(val);
                a[0] = a[0].add(val);
            });
        } catch (Exception e) {
            log.error("getCustomerSalesByYearData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] g = new BigDecimal[6];
        Arrays.fill(g, BigDecimal.ZERO);
        for (var e : acc.entrySet()) {
            BigDecimal[] a = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("custNo", e.getKey()); row.put("name", names.get(e.getKey()));
            row.put("yr1", a[1]); row.put("yr2", a[2]); row.put("yr3", a[3]);
            row.put("yr4", a[4]); row.put("yr5", a[5]); row.put("total", a[0]);
            rows.add(row);
            for (int i = 0; i <= 5; i++) g[i] = g[i].add(a[i]);
        }
        if (rows.isEmpty()) return warn("No sales for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        for (int n = 1; n <= 5; n++) params.put("YR" + n + "_LABEL", String.valueOf(winEnd[n].getYear()));
        params.put("CUST_RANGE", notBlank(p.startCustomer())
            ? p.startCustomer() + " to " + (notBlank(p.endCustomer()) ? p.endCustomer() : "end") : "All customers");
        params.put("DATE_RANGE", p.startDate() + " to " + p.endDate() + " (and 4 prior years)");
        params.put("SUM_YR1", g[1]); params.put("SUM_YR2", g[2]); params.put("SUM_YR3", g[3]);
        params.put("SUM_YR4", g[4]); params.put("SUM_YR5", g[5]); params.put("SUM_TOTAL", g[0]);
        params.put("ROW_COUNT", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    /** AR account-status code → label (blank = active). */
    static String acctStatusDesc(String code) {
        return switch (trim(code)) {
            case "H" -> "ON HOLD"; case "N" -> "NO SALES"; case "I" -> "INACTIVE"; default -> "";
        };
    }

    // ── shared helpers ───────────────────────────────────────────────────────

    static String docTypeLabel(String t) {
        if (t == null) return "";
        return switch (t.trim()) {
            case "I" -> "INV"; case "C" -> "CR";  case "D" -> "DR";
            case "V" -> "VOI"; case "B" -> "BAL"; case "P" -> "PAY";
            default  -> t.trim();
        };
    }

    private static String rawDocType(Object label) {
        String l = label == null ? "" : label.toString();
        return switch (l) {
            case "INV" -> "I"; case "CR" -> "C"; case "DR" -> "D";
            case "VOI" -> "V"; case "BAL" -> "B"; case "PAY" -> "P";
            default -> l;
        };
    }

    private static String seqDisplay(int seqNo, String standingFlag) {
        if ("Y".equalsIgnoreCase(trim(standingFlag))) return "STD";
        return seqNo == 0 ? "UNC" : String.valueOf(seqNo);
    }

    static LocalDate sqlDate(LocalDate d) {
        if (d == null) return null;
        return d.isAfter(LocalDate.of(1900, 1, 1)) ? d : null;
    }

    static LocalDate ld(LocalDate d) {
        if (d == null) return null;
        return d.isAfter(LocalDate.of(1900, 1, 1)) ? d : null;
    }

    static String qMarks(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    static boolean yes(String s) { return "Y".equalsIgnoreCase(trim(s)); }
    static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    static String trim(String s) { return s == null ? "" : s.trim(); }
    static BigDecimal z(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    Map<String, Object> warn(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", new ArrayList<>()); m.put("params", new LinkedHashMap<>());
        m.put("rowCount", 0); m.put("warning", msg);
        return m;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARTI01 — AR Transaction Inquiry  (cobol/ar2/arti01.pl)
    // ════════════════════════════════════════════════════════════════════════

    /** Selection parameters — mirrors the ARTI01S1 entry screen. */
    public record TxnInquiryParams(
            String custNo,
            LocalDate startDate,
            LocalDate endDate,
            String docTypeFilter,     // "" = ALL
            boolean includeFullyPaid,
            boolean includeUnposted
    ) {}

    /** One P1 list row plus raw PK fields for drill-down. */
    public record TxnInquiryRow(
            LocalDate docDate,
            String docTypeCode,
            String docTypeDesc,
            String docNo,
            BigDecimal origAmt,
            BigDecimal outstandingAmt,
            BigDecimal grossAmt,
            String forCurrCode,
            String reconNoDisplay,
            String status,
            // raw PK fields for drill-down
            String custNoRaw,
            LocalDate docDateRaw,
            String docTypeRaw,
            String retentFlagRaw,
            String docNoRaw,
            // extra fields — Excel export
            String custName,
            String ref,
            int batchNo,
            LocalDate postingDate,
            LocalDate dueDate,
            BigDecimal taxAmt,
            String poNo
    ) {}

    /** Aggregated ARTI01 result — customer info + rows + totals. */
    public record TxnInquiryResult(
            String custName,
            BigDecimal acctBal,
            String acctStatus,
            List<TxnInquiryRow> rows,
            BigDecimal totalGross,
            BigDecimal totalNet,
            BigDecimal totalDr,
            BigDecimal totalCr
    ) {}

    /** One ardistn distribution line for the ARTI01 Details dialog. */
    public record DistributionRow(
            int lineNo,
            String glAcctNo,   // "main" or "main-sub"
            String desc,       // desc_1 or glchart.desc1 / cost dissection
            BigDecimal amt,
            BigDecimal taxAmt,
            String taxCode,
            String costedTo    // ref (G) | type/code (L) | sales/stock code (S/I)
    ) {}

    /**
     * ARTI01 — transaction inquiry for a single customer. Ported from
     * {@code CALC-TRX-DISPLAYS}, {@code SET-P1-DISPLAYS} and {@code CALC-BALANCES}.
     */
    public TxnInquiryResult getTransactionInquiry(AppSession s, TxnInquiryParams p) {
        LocalDate start = p.startDate() != null ? p.startDate() : LocalDate.of(1900, 1, 1);
        LocalDate end   = p.endDate()   != null ? p.endDate()   : LocalDate.now();

        String sql =
            "SELECT t.cust_no, t.doc_date, t.doc_type, t.retent_flag, t.doc_no, " +
            "       t.amt, t.retent_amt, t.disc_taken, t.amt_paid, t.for_curr_fluct_amt, " +
            "       t.trx_status, t.for_curr_code, t.recon_no, t.archive_flag, " +
            "       t.ref, t.batch_no, t.posting_date, t.due_date, t.sales_tax_amt, t.po_no, " +
            "       c.name_1, c.acct_bal, c.acct_status AS cust_acct_status, " +
            "       r.gross_bal, r.outstanding_bal " +
            "FROM artrans t " +
            "JOIN arcusts c ON c.company_no = t.company_no AND c.cust_no = t.cust_no " +
            "LEFT JOIN arrecon r ON r.company_no = t.company_no " +
            "                    AND r.cust_no = t.cust_no AND r.recon_no = t.recon_no " +
            "WHERE t.company_no = ? AND t.cust_no = ? " +
            "  AND t.doc_date BETWEEN ? AND ? AND t.archive_flag != 'Y' " +
            "ORDER BY t.doc_date, t.doc_type, t.retent_flag, t.doc_no";

        String[] custName  = {""};
        BigDecimal[] acctBal = {BigDecimal.ZERO};
        String[] acctStatus = {""};
        List<TxnInquiryRow> rows = new ArrayList<>();
        BigDecimal totalGross = BigDecimal.ZERO, totalNet = BigDecimal.ZERO;
        BigDecimal totalDr = BigDecimal.ZERO, totalCr = BigDecimal.ZERO;

        try {
            var records = dsl.resultQuery(sql, s.getCompanyNo(), p.custNo(),
                java.sql.Date.valueOf(start), java.sql.Date.valueOf(end)).fetch();

            for (var r : records) {
                if (custName[0].isEmpty()) {
                    custName[0]   = trim(r.get("name_1", String.class));
                    acctBal[0]    = z(r.get("acct_bal", BigDecimal.class));
                    acctStatus[0] = trim(r.get("cust_acct_status", String.class));
                }

                String docType   = trim(r.get("doc_type", String.class));
                String trxStatus = trim(r.get("trx_status", String.class));

                BigDecimal amt     = z(r.get("amt", BigDecimal.class));
                BigDecimal retent  = z(r.get("retent_amt", BigDecimal.class));
                BigDecimal discTkn = z(r.get("disc_taken", BigDecimal.class));
                BigDecimal amtPaid = z(r.get("amt_paid", BigDecimal.class));
                BigDecimal fcFluct = z(r.get("for_curr_fluct_amt", BigDecimal.class));

                // CALC-TRX-DISPLAYS (local currency)
                BigDecimal grossAmt, origAmt, outstandingAmt;
                if ("P".equals(docType)) {
                    grossAmt       = amt.add(discTkn);
                    outstandingAmt = amt.add(discTkn).subtract(amtPaid);
                    origAmt        = amt;
                } else {
                    grossAmt       = amt.subtract(retent);
                    outstandingAmt = amt.subtract(retent).subtract(amtPaid).subtract(discTkn).subtract(fcFluct);
                    origAmt        = amt.subtract(retent);
                }

                // arrecon zero-out (AR checks gross_bal + outstanding_bal only)
                int reconNo = r.get("recon_no") != null ? r.get("recon_no", Integer.class) : 0;
                if (outstandingAmt.compareTo(BigDecimal.ZERO) != 0 && reconNo > 0
                        && isZero(r.get("gross_bal", BigDecimal.class))
                        && isZero(r.get("outstanding_bal", BigDecimal.class))) {
                    outstandingAmt = BigDecimal.ZERO;
                }

                if (!p.includeFullyPaid() && outstandingAmt.compareTo(BigDecimal.ZERO) == 0) continue;
                if (!p.includeUnposted() && "U".equals(trxStatus)) continue;
                if (notBlank(p.docTypeFilter()) && !p.docTypeFilter().equals(docType)) continue;

                String docTypeDesc = switch (docType) {
                    case "I" -> "Invoice";
                    case "D" -> "Dr Note";
                    case "C" -> "Cr Note";
                    case "P" -> "Payment";
                    case "V" -> "Void";
                    default  -> docType;
                };

                String status = "U".equals(trxStatus) ? "unposted"
                              : "H".equals(trxStatus) ? "on hold" : "";

                int reconNoVal = reconNo;
                String reconNoDisplay = reconNoVal > 0 ? String.valueOf(reconNoVal) : "";

                LocalDate docDate   = r.get("doc_date", LocalDate.class);
                String retentFlag  = trim(r.get("retent_flag", String.class));
                String forCurrCode = trim(r.get("for_curr_code", String.class));
                String docNo       = trim(r.get("doc_no", String.class));
                String ref         = trim(r.get("ref", String.class));
                int batchNo        = r.get("batch_no")     != null ? r.get("batch_no",    Integer.class) : 0;
                LocalDate postDate = r.get("posting_date") != null ? r.get("posting_date", LocalDate.class) : null;
                LocalDate dueDate  = r.get("due_date")     != null ? r.get("due_date",     LocalDate.class) : null;
                BigDecimal taxAmt  = z(r.get("sales_tax_amt", BigDecimal.class));
                String poNo        = trim(r.get("po_no", String.class));

                rows.add(new TxnInquiryRow(
                    docDate, docType, docTypeDesc, docNo,
                    origAmt, outstandingAmt, grossAmt,
                    forCurrCode, reconNoDisplay, status,
                    p.custNo(), docDate, docType, retentFlag, docNo,
                    custName[0], ref, batchNo, postDate, dueDate, taxAmt, poNo));

                // DR/CR split: credit-side types (P, C) use grossAmt so fully-matched
                // rows still contribute to the CR total (outstandingAmt would be 0).
                // Debit-side types (I, D, V) contribute their outstanding to DR.
                BigDecimal amtDr = BigDecimal.ZERO, amtCr = BigDecimal.ZERO;
                if ("P".equals(docType) || "C".equals(docType)) {
                    amtCr = grossAmt.abs();
                } else {
                    if (outstandingAmt.compareTo(BigDecimal.ZERO) > 0) amtDr = outstandingAmt;
                }
                totalDr = totalDr.add(amtDr);
                totalCr = totalCr.add(amtCr);
                totalNet = totalNet.add(outstandingAmt);
                totalGross = totalGross.add(grossAmt);
            }
        } catch (Exception e) {
            log.error("getTransactionInquiry failed: {}", e.getMessage(), e);
        }

        return new TxnInquiryResult(custName[0], acctBal[0], acctStatus[0],
            rows, totalGross, totalNet, totalDr, totalCr);
    }

    /**
     * ARTI01 Details dialog — ardistn lines for one transaction.
     * Description falls back to glchart.desc1 (treating "0"/blank as empty);
     * "costed to" follows COBOL SET-P2-DISPLAYS (ref for GL, type/code for cost
     * ledger, sales/stock code for S/I lines).
     */
    public List<DistributionRow> getDistributions(AppSession s, String custNo,
            LocalDate docDate, String docType, String retentFlag, String docNo) {
        List<DistributionRow> list = new ArrayList<>();
        try {
            dsl.resultQuery(
                "SELECT d.line_no, d.line_type, d.gl_acct_main, d.gl_acct_sub, " +
                "       d.desc_1, g.desc1 AS gl_desc, d.amt, d.tax_amt, d.tax_code, " +
                "       d.ref, d.ledger_type, d.ledger_code, d.sales_code, d.stock_code " +
                "FROM ardistn d " +
                "LEFT JOIN glchart g ON g.company_no = d.company_no " +
                "       AND g.acct_main_no = d.gl_acct_main AND g.acct_sub_no = d.gl_acct_sub " +
                "WHERE d.company_no=? AND d.cust_no=? AND d.doc_date=? " +
                "  AND d.doc_type=? AND d.retent_flag=? AND d.doc_no=? " +
                "ORDER BY d.line_no",
                s.getCompanyNo(), custNo,
                java.sql.Date.valueOf(docDate), docType, retentFlag, docNo)
            .fetch().forEach(r -> {
                int main = r.get("gl_acct_main") != null ? r.get("gl_acct_main", Integer.class) : 0;
                int sub  = r.get("gl_acct_sub")  != null ? r.get("gl_acct_sub",  Integer.class) : 0;
                String glAcct = sub > 0 ? main + "-" + sub : String.valueOf(main);

                String rawDesc = trim(r.get("desc_1", String.class));
                String glDesc  = trim(r.get("gl_desc", String.class));
                String desc    = (rawDesc.isEmpty() || "0".equals(rawDesc)) ? glDesc : rawDesc;

                String lineType   = trim(r.get("line_type", String.class));
                String ref        = trim(r.get("ref", String.class));
                String ledgerType = trim(r.get("ledger_type", String.class));
                String ledgerCode = trim(r.get("ledger_code", String.class));
                String salesCode  = trim(r.get("sales_code", String.class));
                String stockCode  = trim(r.get("stock_code", String.class));
                String costedTo;
                if ("L".equals(lineType))      costedTo = ledgerType + "/" + ledgerCode;
                else if ("S".equals(lineType)) costedTo = salesCode;
                else if ("I".equals(lineType)) costedTo = stockCode;
                else                           costedTo = ref;

                list.add(new DistributionRow(
                    r.get("line_no") != null ? r.get("line_no", Integer.class) : 0,
                    glAcct, desc,
                    z(r.get("amt", BigDecimal.class)),
                    z(r.get("tax_amt", BigDecimal.class)),
                    trim(r.get("tax_code", String.class)),
                    costedTo));
            });
        } catch (Exception e) {
            log.warn("getDistributions {}/{}: {}", custNo, docNo, e.getMessage());
        }
        return list;
    }

    // ── Document Tracking (DT) — dtdocix / dtpaths / cpcoyco ─────────────────

    /** True when DT is licensed for this company (cpcoyco.dt_instal_flag = 'Y'). */
    public boolean isDtInstalled(AppSession s) {
        try {
            String flag = dsl.resultQuery(
                "SELECT dt_instal_flag FROM cpcoyco WHERE company_no=? LIMIT 1", s.getCompanyNo())
                .fetchOne(0, String.class);
            return "Y".equals(flag);
        } catch (Exception e) {
            log.warn("isDtInstalled: {}", e.getMessage());
            return false;
        }
    }

    /** dtpaths directory for an AR function ('CU' customer | 'TX' transaction); company row then company 0. */
    public String getDocumentDirectory(AppSession s, String functionId) {
        for (int co : new int[]{s.getCompanyNo(), 0}) {
            try {
                String dir = dsl.resultQuery(
                    "SELECT directory FROM dtpaths WHERE company_no=? AND system_id='AR' AND function_id=?",
                    co, functionId).fetchOne(0, String.class);
                if (dir != null && !dir.isBlank()) return dir.trim();
            } catch (Exception e) {
                log.warn("getDocumentDirectory {}/{}: {}", co, functionId, e.getMessage());
            }
        }
        return "";
    }

    /** One document index entry. */
    public record DtDocument(String docNo, int seqNo, String addedBy, LocalDate addedDate) {}

    /** Documents attached to an entity, by DTDOCIX search key. searchCode is 60 chars. */
    public List<DtDocument> getDocuments(AppSession s, int searchKeyNo, String searchCode) {
        List<DtDocument> list = new ArrayList<>();
        try {
            dsl.resultQuery(
                "SELECT doc_no, seq_no, audit_user_id, audit_date " +
                "FROM dtdocix WHERE search_key_no=? AND search_company_no=? AND search_code=? " +
                "ORDER BY seq_no",
                searchKeyNo, s.getCompanyNo(), searchCode)
                .fetch()
                .forEach(r -> list.add(new DtDocument(
                    trim(r.get("doc_no", String.class)),
                    r.get("seq_no") != null ? r.get("seq_no", Integer.class) : 0,
                    trim(r.get("audit_user_id", String.class)),
                    landmarkSerialToDate(r.get("audit_date") != null ? r.get("audit_date", Integer.class) : 0))));
        } catch (Exception e) {
            log.warn("getDocuments key={}: {}", searchKeyNo, e.getMessage());
        }
        return list;
    }

    /**
     * Landmark Julian serial epoch (1899-12-30; 1899-12-31 = 1) — same as AP /
     * Excel's 1900 system. See ApReportDataService for the verification note.
     */
    private static final LocalDate LANDMARK_EPOCH = LocalDate.of(1899, 12, 30);

    private static LocalDate landmarkSerialToDate(int serial) {
        return serial > 0 ? LANDMARK_EPOCH.plusDays(serial) : null;
    }

    /** 60-char DTDOCIX customer search code (search_key_no = 1): cust_no(10) + 50 spaces. */
    public static String buildDtSearchCodeCustomer(String custNo) {
        return String.format("%-10s", custNo == null ? "" : custNo).substring(0, 10) + " ".repeat(50);
    }

    /**
     * 60-char DTDOCIX AR transaction search code (search_key_no = 7):
     * cust_no(10) + doc_date(6 Landmark serial) + doc_type(1) + retent_flag(1) + doc_no(10) + spaces(32).
     * Note: AR doc_no is 10 chars in this key (AP key 8 uses 20).
     */
    public static String buildDtSearchCodeTransaction(
            String custNo, LocalDate docDate, String docType, String retentFlag, String docNo) {
        String date6 = docDate != null
            ? String.format("%06d", java.time.temporal.ChronoUnit.DAYS.between(LANDMARK_EPOCH, docDate))
            : "000000";
        return String.format("%-10s", custNo == null ? "" : custNo).substring(0, 10)
             + date6
             + (docType    == null ? " " : docType.substring(0, 1))
             + (retentFlag == null ? " " : retentFlag.substring(0, 1))
             + String.format("%-10s", docNo == null ? "" : docNo).substring(0, 10)
             + " ".repeat(32);
    }

    private static boolean isZero(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) == 0;
    }

    /** Format a date as dd-MM-yyyy for report display (blank when null). */
    private static String dmy(java.time.LocalDate d) {
        return d == null ? "" : d.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"));
    }
}
