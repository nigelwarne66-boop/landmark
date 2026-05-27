package com.landmarksoftware.service.ar;

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
    private final JdbcTemplate jdbc;

    public ArReportDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
            jdbc.query("SELECT sub_ledger, name1 FROM arledgr WHERE company_no=? ORDER BY sub_ledger",
                rs -> { list.add(new CodeName(trim(rs.getString("sub_ledger")),
                                              trim(rs.getString("sub_ledger")) + " — " + trim(rs.getString("name1")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getSubLedgers: {}", e.getMessage()); }
        return list;
    }

    /** Customers for the company, "(All)" first; keyed by cust no or alpha key per the screen sequence. */
    public List<CodeName> getCustomers(AppSession s, boolean byAlpha) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All customers)"));
        try {
            String order = byAlpha ? "alpha_key, alpha_cust_no" : "cust_no";
            jdbc.query("SELECT cust_no, alpha_key, name_1 FROM arcusts WHERE company_no=? ORDER BY " + order,
                rs -> {
                    String code = byAlpha ? trim(rs.getString("alpha_key")) : trim(rs.getString("cust_no"));
                    list.add(new CodeName(code, code + " — " + trim(rs.getString("name_1"))));
                }, s.getCompanyNo());
        } catch (Exception e) { log.warn("getCustomers: {}", e.getMessage()); }
        return list;
    }

    /** Salesmen for the company, "(All)" first, then "code — name" from arcodsm. */
    public List<CodeName> getSalesmen(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All salespeople)"));
        try {
            jdbc.query("SELECT salesman_code, name1 FROM arcodsm WHERE company_no=? ORDER BY salesman_code",
                rs -> { list.add(new CodeName(trim(rs.getString("salesman_code")),
                                              trim(rs.getString("salesman_code")) + " — " + trim(rs.getString("name1")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getSalesmen: {}", e.getMessage()); }
        return list;
    }

    /** Customer-type codes for the company, "(All)" first, then "code — desc" from arcodct. */
    public List<CodeName> getCustomerTypes(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All customer types)"));
        try {
            jdbc.query("SELECT cust_type_code, desc1 FROM arcodct WHERE company_no=? ORDER BY cust_type_code",
                rs -> { list.add(new CodeName(trim(rs.getString("cust_type_code")),
                                              trim(rs.getString("cust_type_code")) + " — " + trim(rs.getString("desc1")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getCustomerTypes: {}", e.getMessage()); }
        return list;
    }

    /** Product-type codes for the company, "(All)" first, then "code — desc" from smcodpt. */
    public List<CodeName> getProductTypes(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All product types)"));
        try {
            jdbc.query("SELECT product_type_code, desc1 FROM smcodpt WHERE company_no=? ORDER BY product_type_code",
                rs -> { list.add(new CodeName(trim(rs.getString("product_type_code")),
                                              trim(rs.getString("product_type_code")) + " — " + trim(rs.getString("desc1")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getProductTypes: {}", e.getMessage()); }
        return list;
    }

    /** Distinct foreign-currency codes present on AR transactions, "(All)" first. */
    public List<CodeName> getCurrencyCodes(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All currencies)"));
        try {
            jdbc.query("SELECT DISTINCT for_curr_code FROM artrans WHERE company_no=? AND TRIM(for_curr_code)<>'' ORDER BY for_curr_code",
                rs -> { String c = trim(rs.getString("for_curr_code")); if (!c.isEmpty()) list.add(new CodeName(c, c)); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getCurrencyCodes: {}", e.getMessage()); }
        return list;
    }

    /** Financial GL accounts, "(All)" first, rendered "main-sub — desc" from glchart. */
    public List<CodeName> getGlAccounts(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All accounts)"));
        try {
            jdbc.query("SELECT acct_main_no, acct_sub_no, desc1 FROM glchart WHERE company_no=? ORDER BY acct_main_no, acct_sub_no",
                rs -> { String code = rs.getInt("acct_main_no") + "-" + rs.getInt("acct_sub_no");
                        list.add(new CodeName(code, code + " — " + trim(rs.getString("desc1")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getGlAccounts: {}", e.getMessage()); }
        return list;
    }

    /** Period-ending dates for the company's current year (from gldates), latest first. */
    public List<LocalDate> getPeriodEndDates(AppSession s) {
        TreeSet<LocalDate> set = new TreeSet<>(Comparator.reverseOrder());
        try {
            StringBuilder cols = new StringBuilder();
            for (int i = 1; i <= 13; i++) cols.append(i > 1 ? "," : "").append(String.format("period_end_%02d", i));
            jdbc.query("SELECT " + cols + " FROM gldates WHERE company_no=? AND year_no=?", rs -> {
                for (int i = 1; i <= 13; i++) {
                    Date d = rs.getDate(String.format("period_end_%02d", i));
                    if (d != null && d.toLocalDate().isAfter(LocalDate.of(1900, 1, 1))) set.add(d.toLocalDate());
                }
            }, s.getCompanyNo(), s.getYearNo());
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
            args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(end));
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
            jdbc.query(sql.toString(), rs -> {
                String docType = trim(rs.getString("doc_type"));

                BigDecimal amt       = z(rs.getBigDecimal("amt"));
                BigDecimal retent    = z(rs.getBigDecimal("retent_amt"));
                BigDecimal discTaken = z(rs.getBigDecimal("disc_taken"));
                BigDecimal amtPaid   = z(rs.getBigDecimal("amt_paid"));

                // CALC-TRX-BAL (ARRC05 does not subtract for_curr_fluct_amt)
                BigDecimal bal = "P".equals(docType)
                    ? amt.add(discTaken).subtract(amtPaid)
                    : amt.subtract(retent).subtract(amtPaid).subtract(discTaken);
                if (rs.getInt("recon_zeroed") == 1) bal = BigDecimal.ZERO;

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

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rowType",       "T");
                row.put("custNo",        rs.getString("cust_no"));
                row.put("name",          rs.getString("name_1"));
                row.put("subLedger",     rs.getString("sub_ledger"));
                row.put("subLedgerName", rs.getString("sub_ledger_name"));
                row.put("docDate",       sqlDate(rs.getDate("doc_date")));
                row.put("postDate",      sqlDate(rs.getDate("posting_date")));
                row.put("dueDate",       sqlDate(rs.getDate("due_date")));
                row.put("docType",       docTypeLabel(docType));
                row.put("docNo",         rs.getString("doc_no"));
                row.put("seqNo",         seqDisplay(rs.getInt("seq_no"), rs.getString("standing_doc_flag")));
                row.put("amt",           amt);
                row.put("retentAmt",     retent);
                row.put("debit",         debit);
                row.put("credit",        credit);
                row.put("amtPaid",       amtPaid);
                row.put("discTaken",     discTaken);
                row.put("balance",       bal);
                row.put("fullyPaid",     rs.getString("fully_paid_flag"));
                row.put("batchNo",       rs.getInt("batch_no"));
                row.put("reconNo",       rs.getInt("recon_no"));
                row.put("archiveFlag",   rs.getString("archive_flag"));
                row.put("auditUser",     rs.getString("audit_user_id"));
                row.put("auditTime",     String.format("%02d:%02d:%02d",
                                            rs.getInt("audit_time_hr"), rs.getInt("audit_time_min"), rs.getInt("audit_time_sec")));
                row.put("_docDateRaw",   rs.getDate("doc_date"));
                row.put("_retentFlag",   rs.getString("retent_flag"));
                for (String f : LINE_FIELDS) row.put(f, null);

                if (printLines && excelLayout) {
                    appendExcelCombinedRows(s, row, rows);
                } else {
                    rows.add(row);
                    if (printLines) appendDistLines(s, row, rows);
                }
            }, args.toArray());
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
            jdbc.query(
                "SELECT line_no, line_type, gl_acct_main, gl_acct_sub, qty, unit_per, unit_cost, " +
                "       amt, tax_code, tax_amt, tax_gross_amt, for_curr_amt, desc_1, ref " +
                "FROM ardistn WHERE company_no=? AND cust_no=? AND doc_date=? AND doc_type=? " +
                "  AND retent_flag=? AND doc_no=? ORDER BY line_no",
                rs -> {
                    Map<String, Object> ln = new LinkedHashMap<>();
                    ln.put("lineNo",      rs.getInt("line_no"));
                    ln.put("lineType",    rs.getString("line_type"));
                    ln.put("glAcctMain",  rs.getInt("gl_acct_main"));
                    ln.put("glAcctSub",   rs.getInt("gl_acct_sub"));
                    ln.put("qty",         z(rs.getBigDecimal("qty")));
                    ln.put("unitCost",    z(rs.getBigDecimal("unit_cost")));
                    ln.put("amtExTax",    z(rs.getBigDecimal("amt")));
                    ln.put("taxCode",     rs.getString("tax_code"));
                    ln.put("lineTaxAmt",  z(rs.getBigDecimal("tax_amt")));
                    ln.put("description", rs.getString("desc_1"));
                    ln.put("reference",   rs.getString("ref"));
                    lines.add(ln);
                },
                s.getCompanyNo(), txn.get("custNo"), txn.get("_docDateRaw"),
                rawDocType(txn.get("docType")), txn.get("_retentFlag"), txn.get("docNo"));
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
            jdbc.query(sql.toString(), rs -> {
                String docType = trim(rs.getString("doc_type"));
                BigDecimal amt = z(rs.getBigDecimal("amt")), ret = z(rs.getBigDecimal("retent_amt")),
                           paid = z(rs.getBigDecimal("amt_paid")), disc = z(rs.getBigDecimal("disc_taken")),
                           fluct = z(rs.getBigDecimal("fluct_amt"));
                BigDecimal gross = "P".equals(docType) ? amt.add(disc) : amt.subtract(ret);
                BigDecimal net;
                if ("P".equals(docType)) net = amt.add(disc).subtract(paid);
                else if ("C".equals(docType)) net = amt.subtract(ret).subtract(paid).subtract(disc);
                else net = amt.subtract(ret).subtract(paid).subtract(disc).subtract(fluct);
                grand[0] = grand[0].add(net);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("custNo",      rs.getString("recon_cust_no"));
                row.put("name",        rs.getString("name_1"));
                row.put("reconNo",     rs.getInt("recon_no"));
                row.put("seqNo",       seqDisplay(rs.getInt("seq_no"), rs.getString("standing_doc_flag")));
                row.put("docType",     docTypeLabel(docType));
                row.put("docNo",       rs.getString("doc_no"));
                row.put("docDate",     sqlDate(rs.getDate("doc_date")));
                row.put("forCurrCode", trim(rs.getString("for_curr_code")));
                row.put("gross",       gross);
                row.put("retentAmt",   ret);
                row.put("amtPaid",     paid);
                row.put("discTaken",   disc);
                row.put("net",         net);
                row.put("reconOutstanding", z(rs.getBigDecimal(fc ? "for_curr_outst_bal" : "outstanding_bal")));
                row.put("reconGross",       z(rs.getBigDecimal(fc ? "for_curr_gross_bal" : "gross_bal")));
                row.put("reconClaim",       z(rs.getBigDecimal(fc ? "for_curr_claim_bal" : "claim_bal")));
                rows.add(row);
            }, args.toArray());
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
            jdbc.query(sql.toString(), rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("custNo",      rs.getString("cust_no"));
                row.put("name",        rs.getString("name_1"));
                row.put("reconNo",     rs.getInt("recon_no"));
                row.put("outstanding", z(rs.getBigDecimal("outstanding_bal")));
                row.put("gross",       z(rs.getBigDecimal("gross_bal")));
                row.put("claim",       z(rs.getBigDecimal("claim_bal")));
                row.put("lastDocDate", sqlDate(rs.getDate("last_doc_date")));
                row.put("forCurrCode", trim(rs.getString("for_curr_code")));
                tot[0] = tot[0].add((BigDecimal) row.get("outstanding"));
                tot[1] = tot[1].add((BigDecimal) row.get("gross"));
                tot[2] = tot[2].add((BigDecimal) row.get("claim"));
                rows.add(row);
            }, args.toArray());
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
            sql.append(" AND d.doc_date BETWEEN ? AND ? "); args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(end));
        }
        if (notBlank(p.startDocNo())) {
            String end = notBlank(p.endDocNo()) ? p.endDocNo() : "zzzzzzzzzz";
            sql.append(" AND d.doc_no BETWEEN ? AND ? "); args.add(p.startDocNo()); args.add(end);
        }
        sql.append(" ORDER BY d.cust_no, d.doc_date, d.doc_type, d.retent_flag, d.doc_no, d.line_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };  // amt, tax
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal qty = z(rs.getBigDecimal("qty")), unitCost = z(rs.getBigDecimal("unit_cost")),
                           discPerc = z(rs.getBigDecimal("disc_perc")), amt = z(rs.getBigDecimal("amt")),
                           taxAmt = z(rs.getBigDecimal("tax_amt"));
                BigDecimal discAmt = qty.multiply(unitCost).multiply(discPerc)
                        .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                BigDecimal lineGross = amt.add(discAmt);
                String code = trim(rs.getString("stock_code"));
                if (code.isEmpty()) {
                    String lt = trim(rs.getString("line_type"));
                    if ("S".equals(lt)) code = trim(rs.getString("sales_code"));
                    else code = trim(rs.getString("ledger_type")) + trim(rs.getString("ledger_code"));
                }
                tot[0] = tot[0].add(amt); tot[1] = tot[1].add(taxAmt);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("custNo",       rs.getString("cust_no"));
                row.put("name",         rs.getString("name_1"));
                row.put("status",       acctStatusDesc(rs.getString("acct_status")));
                row.put("docDate",      sqlDate(rs.getDate("doc_date")));
                row.put("docType",      docTypeLabel(trim(rs.getString("doc_type"))));
                row.put("docNo",        rs.getString("doc_no"));
                row.put("termsDesc",    trim(rs.getString("terms_desc")));
                row.put("salesmanName", trim(rs.getString("salesman_name")));
                row.put("lineNo",       rs.getInt("line_no"));
                row.put("lineType",     rs.getString("line_type"));
                row.put("code",         code);
                row.put("analysis",     notBlank(rs.getString("analysis_code")) ? "Analysis" : "");
                row.put("description",  rs.getString("desc_1"));
                row.put("reference",    rs.getString("ref"));
                row.put("qty",          qty);
                row.put("unitPer",      rs.getString("unit_per"));
                row.put("unitCost",     unitCost);
                row.put("amt",          amt);
                row.put("discPerc",     discPerc);
                row.put("discAmt",      discAmt);
                row.put("lineGross",    lineGross);
                row.put("taxCode",      rs.getString("tax_code"));
                row.put("taxAmt",       taxAmt);
                row.put("glAcctMain",   rs.getInt("gl_acct_main"));
                row.put("glAcctSub",    rs.getInt("gl_acct_sub"));
                rows.add(row);
            }, args.toArray());
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
            args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(end));
        }
        sql.append(alpha ? " ORDER BY c.alpha_key, t.cust_no, t.doc_date, t.doc_type, t.doc_no "
                         : " ORDER BY t.cust_no, t.doc_date, t.doc_type, t.doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] grand = { BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                String docType = trim(rs.getString("doc_type"));
                BigDecimal amt = z(rs.getBigDecimal("amt")), ret = z(rs.getBigDecimal("retent_amt")),
                           paid = z(rs.getBigDecimal("amt_paid")), disc = z(rs.getBigDecimal("disc_taken")),
                           fluct = z(rs.getBigDecimal("for_curr_fluct_amt"));
                BigDecimal bal = "C".equals(docType) || "V".equals(docType) || "B".equals(docType)
                    ? amt.subtract(ret).subtract(paid).subtract(disc)
                    : amt.subtract(ret).subtract(paid).subtract(disc).subtract(fluct);
                grand[0] = grand[0].add(bal);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rowType",     "T");
                row.put("custNo",      rs.getString("cust_no"));
                row.put("name",        rs.getString("name_1"));
                row.put("forCurrCode", trim(rs.getString("for_curr_code")));
                row.put("docNo",       rs.getString("doc_no"));
                row.put("seqNo",       seqDisplay(rs.getInt("seq_no"), rs.getString("standing_doc_flag")));
                row.put("docType",     docTypeLabel(docType));
                row.put("docDate",     sqlDate(rs.getDate("doc_date")));
                row.put("postDate",    sqlDate(rs.getDate("posting_date")));
                row.put("amtOrig",     z(rs.getBigDecimal("amt_orig")));
                row.put("amtPaid",     paid);
                row.put("discTaken",   disc);
                row.put("fluctAmt",    fluct);
                row.put("balance",     bal);
                row.put("revalDate",   null);
                row.put("revalAmt",    null);
                rows.add(row);

                // attach arrctrx revaluation lines for this transaction
                try {
                    jdbc.query(
                        "SELECT match_doc_date, match_posting_date, reval_amt FROM arrctrx " +
                        "WHERE company_no=? AND cust_no=? AND doc_date=? AND doc_type=? AND retent_flag=? AND doc_no=? " +
                        "  AND reval_amt<>0 ORDER BY match_doc_date",
                        rr -> {
                            Map<String, Object> rv = new LinkedHashMap<>();
                            rv.put("rowType",   "R");
                            rv.put("custNo",    row.get("custNo"));
                            rv.put("revalDate", sqlDate(rr.getDate("P".equalsIgnoreCase(p.docPostInd()) ? "match_posting_date" : "match_doc_date")));
                            rv.put("revalAmt",  z(rr.getBigDecimal("reval_amt")));
                            rows.add(rv);
                        },
                        s.getCompanyNo(), rs.getString("cust_no"), rs.getDate("doc_date"),
                        docType, rs.getString("retent_flag"), rs.getString("doc_no"));
                } catch (Exception ignore) { /* no reval lines */ }
            }, args.toArray());
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
        args.add(s.getCompanyNo()); args.add(Date.valueOf(p.periodEndDate()));
        if (notBlank(p.subLedger())) { sql.append(" AND a.sub_ledger=? "); args.add(p.subLedger()); }
        sql.append(" ORDER BY a.gl_acct_main_no, a.gl_acct_sub_no, a.acct_type ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };   // control, sales
        try {
            jdbc.query(sql.toString(), rs -> {
                String acctType = trim(rs.getString("acct_type"));
                BigDecimal amt = z(rs.getBigDecimal("amt"));
                if ("C".equals(acctType)) tot[0] = tot[0].add(amt); else tot[1] = tot[1].add(amt);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("glAcctMain", rs.getInt("gl_acct_main_no"));
                row.put("glAcctSub",  rs.getInt("gl_acct_sub_no"));
                row.put("glDesc",     rs.getString("gl_desc"));
                row.put("acctType",   "C".equals(acctType) ? "Control" : "D".equals(acctType) ? "Sales" : acctType);
                row.put("subLedger",  rs.getString("sub_ledger"));
                row.put("amt",        amt);
                rows.add(row);
            }, args.toArray());
        } catch (Exception e) {
            log.error("getGlDistributionsData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No distributions for this period-ending date.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("PERIOD_END", p.periodEndDate().toString());
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
        args.add(s.getCompanyNo()); args.add(Date.valueOf(sel));
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
            jdbc.query(sql.toString(), rs -> {
                String sl = trim(rs.getString("sub_ledger"));
                LocalDate pe = rs.getDate("period_end_date").toLocalDate();
                BigDecimal[] a = acc.computeIfAbsent(sl, k -> new BigDecimal[]{
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
                names.putIfAbsent(sl, trim(rs.getString("name1")));
                BigDecimal inv = z(rs.getBigDecimal("ar_inv_value")), cr = z(rs.getBigDecimal("ar_cr_note_value")),
                           dr = z(rs.getBigDecimal("ar_dr_note_value")), rec = z(rs.getBigDecimal("ar_recpt_value")),
                           vd = z(rs.getBigDecimal("ar_void_chqs_value")), ds = z(rs.getBigDecimal("ar_disc_allow_value")),
                           cf = z(rs.getBigDecimal("ar_curr_fluctuation")).add(z(rs.getBigDecimal("ar_prov_fluctuation")));
                BigDecimal mvt = inv.add(cr).add(dr).add(rec).add(vd).add(ds).add(cf);
                if (!pe.isAfter(sentinel)) {                       // period-0 opening row
                    a[0] = a[0].add(z(rs.getBigDecimal("open_bal")));
                } else if (pe.isBefore(sel)) {                     // prior periods → opening movement
                    a[1] = a[1].add(mvt);
                } else {                                           // selected period → current movement
                    a[2] = a[2].add(inv); a[3] = a[3].add(cr); a[4] = a[4].add(dr);
                    a[5] = a[5].add(rec); a[6] = a[6].add(vd); a[7] = a[7].add(ds); a[8] = a[8].add(cf);
                }
            }, args.toArray());
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
            sql.append(" AND d.doc_date BETWEEN ? AND ? "); args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(end));
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
            jdbc.query(sql.toString(), rs -> {
                boolean cancelled = "C".equalsIgnoreCase(trim(rs.getString("doc_status")));
                BigDecimal amt = cancelled ? BigDecimal.ZERO : z(rs.getBigDecimal("amt"));
                grand[0] = grand[0].add(amt);
                String dt = trim(rs.getString("doc_type"));
                String orderNo = trim(rs.getString("order_loc_no")).isEmpty() ? ""
                        : rs.getInt("order_no") + " / " + trim(rs.getString("order_loc_no"));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("docNo",    rs.getString("doc_no"));
                row.put("docDate",  sqlDate(rs.getDate("doc_date")));
                row.put("docType",  "I".equals(dt) ? "INV" : "C".equals(dt) ? "C/N" : "D".equals(dt) ? "D/N" : dt);
                row.put("custNo",   rs.getString("cust_no"));
                row.put("name",     cancelled ? "CANCELLED" : rs.getString("name_1"));
                row.put("amt",      amt);
                row.put("status",   cancelled ? "CANCELLED" : "");
                row.put("poNo",     trim(rs.getString("po_no")));
                row.put("orderInfo", orderNo);
                row.put("source",   trim(rs.getString("system_id")));
                row.put("batchNo",  rs.getInt("batch_no"));
                rows.add(row);
            }, args.toArray());
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
            args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(end));
        }
        sql.append(" ORDER BY t.sub_ledger, ").append(dateCol).append(", t.doc_type, t.doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };   // CR, DR
        try {
            jdbc.query(sql.toString(), rs -> {
                String dt = trim(rs.getString("doc_type"));
                BigDecimal amt = z(rs.getBigDecimal("amt"));
                if ("C".equals(dt)) tot[0] = tot[0].add(amt); else tot[1] = tot[1].add(amt);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("subLedger",   rs.getString("sub_ledger"));
                row.put("docNo",       rs.getString("doc_no"));
                row.put("docType",     "C".equals(dt) ? "C/N" : "D/N");
                row.put("custNo",      rs.getString("cust_no"));
                row.put("name",        rs.getString("name_1"));
                row.put("docDate",     sqlDate(rs.getDate("doc_date")));
                row.put("postingDate", sqlDate(rs.getDate("posting_date")));
                row.put("amt",         amt);
                row.put("reference",   rs.getString("ref"));
                rows.add(row);
            }, args.toArray());
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
            jdbc.query("SELECT " + cols + " FROM gldates WHERE company_no=? AND year_no=?", rs -> {
                for (int i = 1; i <= 13; i++) {
                    Date d = rs.getDate(String.format("period_end_%02d", i));
                    if (d != null && d.toLocalDate().equals(pe)) r[0] = i;
                }
            }, s.getCompanyNo(), s.getYearNo());
        } catch (Exception e) { log.warn("resolvePeriodNo: {}", e.getMessage()); }
        return r[0];
    }

    private static BigDecimal[] read13(java.sql.ResultSet rs, String prefix) throws java.sql.SQLException {
        BigDecimal[] a = new BigDecimal[14];
        for (int i = 1; i <= 13; i++) a[i] = z(rs.getBigDecimal(String.format(prefix + "_%02d", i)));
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
            jdbc.query(sql.toString(), rs -> {
                String sl = trim(rs.getString("sub_ledger")), sc = trim(rs.getString("sales_code"));
                String key = sl + "" + sc;
                BigDecimal[] sales = read13(rs, "sales");
                BigDecimal[] a = acc.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                keys.putIfAbsent(key, new String[]{sl, sc});
                if (rs.getInt("year_no") == thisYr) { a[0] = sales[period]; a[1] = ytd(sales, period); }
                else                                { a[2] = sales[period]; a[3] = ytd(sales, period); }
            }, args.toArray());
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
        params.put("PERIOD_END", p.periodEndDate().toString());
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
            jdbc.query(sql, rs -> {
                int main = rs.getInt("gl_acct_main"), sub = rs.getInt("gl_acct_sub");
                String key = main + "-" + sub;
                BigDecimal[] sales = read13(rs, "sales");
                BigDecimal[] a = acc.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                keys.putIfAbsent(key, new Object[]{main, sub, trim(rs.getString("gl_desc"))});
                if (rs.getInt("year_no") == thisYr) { a[0] = sales[period]; a[1] = ytd(sales, period); }
                else                                { a[2] = sales[period]; a[3] = ytd(sales, period); }
            }, s.getCompanyNo(), thisYr, lastYr);
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
        params.put("PERIOD_END", p.periodEndDate().toString());
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
            jdbc.query(sql.toString(), rs -> {
                BigDecimal acctBal = z(rs.getBigDecimal("acct_bal"));
                BigDecimal sales = BigDecimal.ZERO;
                String csv = rs.getString("sales_csv");
                if (csv != null && !csv.isEmpty() && period >= 1) {
                    String[] parts = csv.split("\\|", -1);
                    if (ytd) { for (int i = 0; i < period && i < parts.length; i++) sales = sales.add(new BigDecimal(parts[i])); }
                    else if (period - 1 < parts.length) sales = new BigDecimal(parts[period - 1]);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("subLedger",   rs.getString("sub_ledger"));
                row.put("custNo",      rs.getString("cust_no"));
                row.put("name",        rs.getString("name_1"));
                row.put("acctBal",     acctBal);
                row.put("sales",       sales);
                row.put("creditLimit", rs.getLong("credit_limit"));
                row.put("status",      acctStatusDesc(rs.getString("acct_status")));
                rows.add(row);
            }, args.toArray());
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
            jdbc.query(sql.toString(), rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("custNo",     rs.getString("cust_no"));
                row.put("name",       rs.getString("name_1"));
                row.put("subLedger",  rs.getString("sub_ledger"));
                row.put("status",     statusFull(rs.getString("acct_status")));
                row.put("acctBal",    z(rs.getBigDecimal("acct_bal")));
                row.put("addDate",    sqlDate(rs.getDate("acct_add_date")));
                row.put("closeDate",  sqlDate(rs.getDate("acct_close_date")));
                tot[0] = tot[0].add((BigDecimal) row.get("acctBal"));
                rows.add(row);
            }, args.toArray());
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
            jdbc.query(sql.toString(), rs -> {
                String cust = trim(rs.getString("cust_no"));
                String grp = p.byType() ? trim(rs.getString("type")) : trim(rs.getString("sub_ledger"));
                String key = grp + "" + cust;
                BigDecimal[] a = acc.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                meta.putIfAbsent(key, new String[]{grp, cust, trim(rs.getString("name_1")), trim(rs.getString("sub_ledger"))});
                if (rs.getInt("year_no") == thisYr) {
                    a[0] = ytd(read13(rs, "sales"), period);
                    a[1] = ytd(read13(rs, "cost"), period);
                } else {
                    a[2] = ytd(read13(rs, "sales"), period);
                }
            }, args.toArray());
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
        params.put("PERIOD_END", p.periodEndDate().toString());
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
            jdbc.query(sql.toString(), rs -> {
                BigDecimal[] sales = read13(rs, "sales");
                BigDecimal mtd = sales[period], y = ytd(sales, period);
                if (mtd.signum() == 0 && y.signum() == 0) return;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("salesman",     trim(rs.getString("salesman")));
                row.put("salesmanName", trim(rs.getString("salesman_name")));
                row.put("custNo",       rs.getString("cust_no"));
                row.put("alphaKey",     rs.getString("alpha_key"));
                row.put("name",         rs.getString("name_1"));
                row.put("location",     trim(rs.getString("city")) + " " + trim(rs.getString("state")));
                row.put("phone",        rs.getString("contact_phone"));
                row.put("mtdSales",     mtd);
                row.put("ytdSales",     y);
                rows.add(row);
                g[0] = g[0].add(mtd); g[1] = g[1].add(y);
            }, args.toArray());
        } catch (Exception e) {
            log.error("getSalesBySalespersonData: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No customer sales for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("PERIOD_END", p.periodEndDate().toString());
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
            sql.append(" AND t.doc_date BETWEEN ? AND ? "); args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(end));
        }
        sql.append(" ORDER BY t.salesman, t.doc_date, t.doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] g = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal sale = z(rs.getBigDecimal("sale_amt")), cost = z(rs.getBigDecimal("cost_of_sale_amt"));
                BigDecimal profit = sale.subtract(cost);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("salesman",     trim(rs.getString("salesman")));
                row.put("salesmanName", trim(rs.getString("salesman_name")));
                row.put("custNo",       rs.getString("cust_no"));
                row.put("name",         rs.getString("name_1"));
                row.put("docType",      docTypeLabel(trim(rs.getString("doc_type"))));
                row.put("docNo",        rs.getString("doc_no"));
                row.put("docDate",      sqlDate(rs.getDate("doc_date")));
                row.put("saleAmt",      sale);
                row.put("costAmt",      cost);
                row.put("grossProfit",  profit);
                row.put("profitPct",    varPct(profit, sale));
                rows.add(row);
                g[0] = g[0].add(sale); g[1] = g[1].add(cost);
            }, args.toArray());
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
            args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(end));
        }
        sql.append("N".equalsIgnoreCase(p.seq())
            ? " ORDER BY t.sub_ledger, t.doc_no " : " ORDER BY t.sub_ledger, " + dateCol + ", t.doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] g = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };  // sale, tax, amt
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal sale = z(rs.getBigDecimal("sale_amt")), tax = z(rs.getBigDecimal("sales_tax_amt")), amt = z(rs.getBigDecimal("amt"));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("subLedger",   rs.getString("sub_ledger"));
                row.put("docDate",     sqlDate(rs.getDate("doc_date")));
                row.put("postingDate", sqlDate(rs.getDate("posting_date")));
                row.put("docType",     docTypeLabel(trim(rs.getString("doc_type"))));
                row.put("docNo",       rs.getString("doc_no"));
                row.put("custNo",      rs.getString("cust_no"));
                row.put("name",        rs.getString("name_1"));
                row.put("saleAmt",     sale);
                row.put("taxAmt",      tax);
                row.put("amt",         amt);
                row.put("salesman",    trim(rs.getString("salesman")));
                rows.add(row);
                g[0]=g[0].add(sale); g[1]=g[1].add(tax); g[2]=g[2].add(amt);
            }, args.toArray());
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
            sql.append(" AND t.doc_date BETWEEN ? AND ? "); args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(end));
        }
        sql.append(" ORDER BY t.salesman, t.doc_date, t.doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] g = { BigDecimal.ZERO, BigDecimal.ZERO };   // sale, commission
        try {
            jdbc.query(sql.toString(), rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("salesman",     trim(rs.getString("salesman")));
                row.put("salesmanName", trim(rs.getString("salesman_name")));
                row.put("docDate",      sqlDate(rs.getDate("doc_date")));
                row.put("docType",      docTypeLabel(trim(rs.getString("doc_type"))));
                row.put("retentFlag",   rs.getString("retent_flag"));
                row.put("docNo",        rs.getString("doc_no"));
                row.put("custNo",       rs.getString("cust_no"));
                row.put("name",         rs.getString("name_1"));
                row.put("docAmt",       z(rs.getBigDecimal("amt")));
                row.put("saleAmt",      z(rs.getBigDecimal("sale_amt")));
                row.put("commPct",      z(rs.getBigDecimal("comm_rate")));
                row.put("commission",   z(rs.getBigDecimal("comm_amt")));
                rows.add(row);
                g[0] = g[0].add((BigDecimal) row.get("saleAmt"));
                g[1] = g[1].add((BigDecimal) row.get("commission"));
            }, args.toArray());
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
        args.add(s.getCompanyNo()); args.add(Date.valueOf(broadStart)); args.add(Date.valueOf(broadEnd));
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
            jdbc.query(sql.toString(), rs -> {
                LocalDate md = ld(rs.getDate("move_date"));
                if (md == null) return;
                int win = 0;
                for (int n = 1; n <= 5; n++) if (!md.isBefore(winStart[n]) && !md.isAfter(winEnd[n])) { win = n; break; }
                if (win == 0) return;
                BigDecimal val = z(rs.getBigDecimal("sales_or_recpt_value")).negate();
                String cust = trim(rs.getString("cust_supplier_no"));
                BigDecimal[] a = acc.computeIfAbsent(cust, k -> new BigDecimal[]{
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
                names.putIfAbsent(cust, trim(rs.getString("name_1")));
                a[win] = a[win].add(val);
                a[0] = a[0].add(val);
            }, args.toArray());
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

    static java.sql.Date sqlDate(java.sql.Date d) {
        if (d == null) return null;
        return d.toLocalDate().isAfter(LocalDate.of(1900, 1, 1)) ? d : null;
    }

    static LocalDate ld(Date d) {
        if (d == null) return null;
        LocalDate v = d.toLocalDate();
        return v.isAfter(LocalDate.of(1900, 1, 1)) ? v : null;
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
}
