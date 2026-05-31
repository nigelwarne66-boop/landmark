package com.landmarksoftware.service.ap;

import com.landmarksoftware.model.AppSession;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

import static com.landmarksoftware.db.tables.Apledgr.APLEDGR;
import static com.landmarksoftware.db.tables.Apsupps.APSUPPS;
import static com.landmarksoftware.db.tables.Aptrans.APTRANS;

/**
 * Accounts Payable <b>report</b> data service — one query method per AP report
 * card in the JavaFX Reports Hub (-Preporting build).
 *
 * <p>Kept separate from {@link ApDataService} (dashboard KPIs + creditors ageing)
 * so the 10 AP report queries don't bloat that class. Same rule applies: all
 * JDBC lives here, controllers stay pure JavaFX.
 *
 * <p>Every method is a faithful port of the matching COBOL/Perl in
 * {@code C:\landmark\cobol\ap2}. Column names verified against the live
 * {@code lmextract} schema (generated from {@code C:\landmark_extract\sql\create}).
 */
@Service
public class ApReportDataService {

    private static final Logger log = LoggerFactory.getLogger(ApReportDataService.class);
    private final DSLContext dsl;

    public ApReportDataService(DSLContext dsl) { this.dsl = dsl; }

    // ── Picker lookups (shared by every AP selection screen) ─────────────────

    /** A selectable code with a display label; {@code toString()} drives ComboBox rendering. */
    public record CodeName(String code, String label) {
        @Override public String toString() { return label; }
    }

    /** Sub-ledgers for the company, "(All)" first, then "code — name" from apledgr. */
    public List<CodeName> getSubLedgers(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All sub ledgers)"));
        try {
            dsl.select(APLEDGR.SUB_LEDGER, APLEDGR.NAME1)
               .from(APLEDGR)
               .where(APLEDGR.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(APLEDGR.SUB_LEDGER)
               .fetch()
               .forEach(r -> {
                   String code = trim(r.get(APLEDGR.SUB_LEDGER));
                   list.add(new CodeName(code, code + " — " + trim(r.get(APLEDGR.NAME1))));
               });
        } catch (Exception e) { log.warn("getSubLedgers: {}", e.getMessage()); }
        return list;
    }

    /**
     * Suppliers for the company, "(All)" first. Keyed by supplier number, or by
     * alpha key when {@code byAlpha} (matches the screen's print-sequence choice).
     */
    public List<CodeName> getSuppliers(AppSession s, boolean byAlpha) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All suppliers)"));
        try {
            var orderBy = byAlpha
                ? new SortField[]{APSUPPS.ALPHA_KEY.asc(), APSUPPS.ALPHA_SUPPLIER_NO.asc()}
                : new SortField[]{APSUPPS.SUPPLIER_NO.asc()};
            dsl.select(APSUPPS.SUPPLIER_NO, APSUPPS.ALPHA_KEY, APSUPPS.NAME_1)
               .from(APSUPPS)
               .where(APSUPPS.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(orderBy)
               .fetch()
               .forEach(r -> {
                   String code = byAlpha ? trim(r.get(APSUPPS.ALPHA_KEY)) : trim(r.get(APSUPPS.SUPPLIER_NO));
                   list.add(new CodeName(code, code + " — " + trim(r.get(APSUPPS.NAME_1))));
               });
        } catch (Exception e) { log.warn("getSuppliers: {}", e.getMessage()); }
        return list;
    }

    // ════════════════════════════════════════════════════════════════════════
    // APRC05 — Transaction Listing  (cobol/ap2/aprc05.pl, aprc05s0.pl)
    // ════════════════════════════════════════════════════════════════════════

    /** Selection parameters — mirrors the APRC05S0 entry screen field-for-field. */
    public record TxnListingParams(
            String printSeq,            // "N" supplier no | "A" alpha key
            String startSupplier,
            String endSupplier,
            boolean invoices,           // doc_type I
            boolean drNotes,            // doc_type D
            boolean crNotes,            // doc_type C
            boolean crClaims,           // doc_type K
            boolean claimReversals,     // doc_type R
            boolean payments,           // doc_type P
            boolean cancelledChqs,      // doc_type V
            boolean balances,           // doc_type B
            String includePaid,         // "Y" include fully-paid | "N" balance<>0 only
            String docPostInd,          // "D" doc date | "P" posting date | "A" audit date
            LocalDate startDate,        // null = all dates
            LocalDate endDate,
            String subLedger,           // blank = all
            int batchNo,                // 0 = all
            String printLines,          // "Y" include apdistn lines
            String printPosMatched,     // "Y" include PO matches (apdocno)
            String unposted,            // "Y" trx_status='U' & not standing
            String standingInvoice,     // "Y" trx_status='U' & standing_doc_flag='Y'
            String posted,              // "Y" trx_status=' '
            String onHold,              // "Y" trx_status='H'
            String fullyDelivered,      // hold sub-filter ("Y"/"N"/" ")
            String withinTolerance,     // hold sub-filter ("Y"/"N"/" ")
            String paidButFlagsSet      // hold sub-filter ("Y"/"N")
    ) {}

    /**
     * APRC05 listing. Returns {@code rows} (transaction beans, each optionally
     * followed by its apdistn line beans when printLines='Y'), a {@code params}
     * map carrying the by-doc-type totals + legend text for the report bands,
     * and {@code warning} when the selection can produce no output.
     *
     * <p>Logic ported from {@code PRINT-TRX / CHECK-SELECTIONS / CHECK-HOLD-TRXS
     * / CALC-TRX-BAL / SET-DR-CR-AMT / GET-DOC-TYPE}.
     */
    public Map<String, Object> getTransactionListingData(AppSession s, TxnListingParams p,
                                                         boolean excelLayout) {

        // ── doc-type IN (...) from the eight checkboxes ──────────────────────
        List<String> types = new ArrayList<>();
        if (p.invoices())        types.add("I");
        if (p.drNotes())         types.add("D");
        if (p.crNotes())         types.add("C");
        if (p.crClaims())        types.add("K");
        if (p.claimReversals())  types.add("R");
        if (p.payments())        types.add("P");
        if (p.cancelledChqs())   types.add("V");
        if (p.balances())        types.add("B");
        if (types.isEmpty()) return warn("Select at least one document type to list.");

        // ── posting-status OR group from the four status flags ───────────────
        List<String> status = new ArrayList<>();
        if (yes(p.unposted()))        status.add("(t.trx_status='U' AND t.standing_doc_flag<>'Y')");
        if (yes(p.standingInvoice())) status.add("(t.trx_status='U' AND t.standing_doc_flag='Y')");
        if (yes(p.posted()))          status.add("(TRIM(t.trx_status)='')");
        if (yes(p.onHold()))          status.add("(t.trx_status='H')");
        if (status.isEmpty()) return warn("Select at least one posting status (posted / unposted / standing / on hold).");

        boolean alpha = "A".equalsIgnoreCase(p.printSeq());

        StringBuilder sql = new StringBuilder(
            "SELECT t.supplier_no, s.name_1, s.sub_ledger, s.alpha_key, " +
            "       COALESCE(l.name1,'') AS sub_ledger_name, " +
            "       t.doc_date, t.posting_date, t.audit_date, t.doc_type, t.retent_flag, t.doc_no, " +
            "       t.seq_no, t.standing_doc_flag, t.trx_status, t.po_no, " +
            "       t.amt, t.retent_amt, t.disc_taken, t.amt_paid, t.for_curr_fluct_amt, t.tax_amt, " +
            "       t.paid_flag, t.last_paid_doc_date, t.last_paid_post_date, " +
            "       t.batch_no, t.recon_no, t.archive_flag, " +
            "       t.fully_delivered_flag, t.within_tolerance_flag, " +
            "       t.audit_user_id, t.audit_time_hr, t.audit_time_min, t.audit_time_sec, " +
            "       CASE WHEN t.recon_no > 0 AND r.recon_no IS NOT NULL " +
            "                 AND r.gross_bal = 0 AND r.outstanding_bal = 0 AND r.claim_bal = 0 " +
            "            THEN 1 ELSE 0 END AS recon_zeroed " +
            "FROM apsupps s " +
            "JOIN aptrans t ON t.company_no = s.company_no AND t.supplier_no = s.supplier_no " +
            "LEFT JOIN apledgr l ON l.company_no = s.company_no AND l.sub_ledger = s.sub_ledger " +
            "LEFT JOIN aprecon r ON r.company_no = t.company_no AND r.supplier_no = t.supplier_no " +
            "                   AND r.recon_no = t.recon_no " +
            "WHERE s.company_no = ? " +
            "  AND t.archive_flag <> 'Y' " +
            "  AND t.doc_type IN (" + qMarks(types.size()) + ") ");

        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        args.addAll(types);

        // supplier range (blank start = all suppliers)
        if (notBlank(p.startSupplier())) {
            String end = notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz";
            if (alpha) { sql.append(" AND s.alpha_key BETWEEN ? AND ? "); }
            else       { sql.append(" AND s.supplier_no BETWEEN ? AND ? "); }
            args.add(p.startSupplier()); args.add(end);
        }

        // date range on the chosen basis (null start = all dates)
        if (p.startDate() != null) {
            String dateCol = "P".equalsIgnoreCase(p.docPostInd()) ? "t.posting_date"
                           : "A".equalsIgnoreCase(p.docPostInd()) ? "t.audit_date"
                           : "t.doc_date";
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND ").append(dateCol).append(" BETWEEN ? AND ? ");
            args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(end));
        }

        if (notBlank(p.subLedger())) { sql.append(" AND s.sub_ledger = ? "); args.add(p.subLedger()); }
        if (p.batchNo() > 0)         { sql.append(" AND t.batch_no = ? ");   args.add(p.batchNo()); }

        sql.append(" AND (").append(String.join(" OR ", status)).append(") ");

        if (alpha) sql.append(" ORDER BY s.alpha_key, s.alpha_supplier_no, t.doc_date, t.doc_type, t.retent_flag, t.doc_no ");
        else       sql.append(" ORDER BY s.supplier_no, t.doc_date, t.doc_type, t.retent_flag, t.doc_no ");

        boolean includePaid = yes(p.includePaid());
        boolean printLines  = yes(p.printLines());

        List<Map<String, Object>> rows = new ArrayList<>();
        Totals tot = new Totals();
        String err = null;

        try {
            // Complex multi-join with dynamic column list (doc_type IN, status OR group, supplier/date ranges)
            // — kept as DSL.resultQuery for safe parameterised execution
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                String docType = trim(r.get("doc_type", String.class));
                String trxStatus = trim(r.get("trx_status", String.class));

                BigDecimal amt        = z(r.get("amt", BigDecimal.class));
                BigDecimal retent     = z(r.get("retent_amt", BigDecimal.class));
                BigDecimal discTaken  = z(r.get("disc_taken", BigDecimal.class));
                BigDecimal amtPaid    = z(r.get("amt_paid", BigDecimal.class));
                BigDecimal fcFluct    = z(r.get("for_curr_fluct_amt", BigDecimal.class));

                // CALC-TRX-BAL
                BigDecimal bal = "P".equals(docType)
                    ? amt.add(discTaken).subtract(amtPaid)
                    : amt.subtract(retent).subtract(amtPaid).subtract(discTaken).subtract(fcFluct);
                if (Integer.valueOf(1).equals(r.get("recon_zeroed", Integer.class))) bal = BigDecimal.ZERO;

                // include-paid filter (CHECK-SELECTIONS): N => skip zero-balance
                if (!includePaid && bal.signum() == 0) return;

                // CHECK-HOLD-TRXS — only on-hold invoices are further filtered
                if ("H".equals(trxStatus) && "I".equals(docType)) {
                    if (!holdTrxAccepted(r.get("fully_delivered_flag", String.class),
                                         r.get("within_tolerance_flag", String.class),
                                         amtPaid, p)) return;
                }

                // SET-DR-CR-AMT
                BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
                if ("I".equals(docType) || "D".equals(docType) || "V".equals(docType)
                        || ("B".equals(docType) && amt.signum() > 0)) {
                    debit = amt.subtract(retent);
                } else {
                    credit = amt.subtract(retent);
                }

                tot.add(docType, amt, retent, discTaken);

                LocalDate docDateLd = r.get("doc_date", LocalDate.class);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rowType",       "T");
                row.put("suppNo",        r.get("supplier_no", String.class));
                row.put("name",          r.get("name_1", String.class));
                row.put("subLedger",     r.get("sub_ledger", String.class));
                row.put("subLedgerName", r.get("sub_ledger_name", String.class));
                row.put("docDate",       ldSqlDate(docDateLd));
                row.put("postDate",      ldSqlDate(r.get("posting_date", LocalDate.class)));
                row.put("auditDate",     ldSqlDate(r.get("audit_date", LocalDate.class)));
                row.put("docType",       docTypeLabel(docType));
                row.put("retentFlag",    r.get("retent_flag", String.class));
                row.put("docNo",         r.get("doc_no", String.class));
                row.put("seqNo",         seqDisplay(
                                             zeroIfNull(r.get("seq_no", Integer.class)),
                                             r.get("standing_doc_flag", String.class)));
                row.put("amt",           amt);
                row.put("retentAmt",     retent);
                row.put("debit",         debit);
                row.put("credit",        credit);
                row.put("amtPaid",       amtPaid);
                row.put("discTaken",     discTaken);
                row.put("balance",       bal);
                row.put("taxAmt",        z(r.get("tax_amt", BigDecimal.class)));
                row.put("paidFlag",      r.get("paid_flag", String.class));
                row.put("paidDocDate",   ldSqlDate(r.get("last_paid_doc_date", LocalDate.class)));
                row.put("paidPostDate",  ldSqlDate(r.get("last_paid_post_date", LocalDate.class)));
                row.put("batchNo",       zeroIfNull(r.get("batch_no", Integer.class)));
                row.put("reconNo",       zeroIfNull(r.get("recon_no", Integer.class)));
                row.put("archiveFlag",   r.get("archive_flag", String.class));
                row.put("auditUser",     r.get("audit_user_id", String.class));
                row.put("auditTime",     String.format("%02d:%02d:%02d",
                                            zeroIfNull(r.get("audit_time_hr", Integer.class)),
                                            zeroIfNull(r.get("audit_time_min", Integer.class)),
                                            zeroIfNull(r.get("audit_time_sec", Integer.class))));
                row.put("poNo",          trim(r.get("po_no", String.class)));
                row.put("_docDateRaw",   docDateLd != null ? Date.valueOf(docDateLd) : null);  // raw for dist-line lookup
                // line-only fields kept present (null) so the bean shape is uniform
                for (String f : LINE_FIELDS) row.put(f, null);

                if (printLines && excelLayout) {
                    // Excel: one row per line to the right of the header; amounts on first line only.
                    appendExcelCombinedRows(s, row, rows);
                } else {
                    rows.add(row);
                    if (printLines) appendDistLines(s, row, rows);   // PDF: indented sub-lines
                }
            });
        } catch (Exception e) {
            log.error("getTransactionListingData failed: {}", e.getMessage(), e);
            err = e.getMessage();
        }
        if (err != null) return warn("Query failed: " + err);

        Map<String, Object> params = tot.toParams();
        params.put("PRINT_SEQ_DESC", alpha ? "Alpha key" : "Supplier number");
        params.put("DATE_BASIS_DESC", "P".equalsIgnoreCase(p.docPostInd()) ? "Posting date"
                                    : "A".equalsIgnoreCase(p.docPostInd()) ? "Audit date" : "Document date");
        params.put("SUPP_RANGE", notBlank(p.startSupplier())
            ? p.startSupplier() + " to " + (notBlank(p.endSupplier()) ? p.endSupplier() : "end")
            : "All suppliers");
        params.put("SUB_LEDGER_DESC", notBlank(p.subLedger()) ? p.subLedger() : "All sub ledgers");
        params.put("DATE_RANGE", p.startDate() != null
            ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("params", params);
        result.put("rowCount", tot.grandNo);
        return result;
    }

    /** apdistn lines for one transaction as plain line-field maps (no header context). */
    private List<Map<String, Object>> fetchDistLines(AppSession s, Map<String, Object> txn) {
        List<Map<String, Object>> lines = new ArrayList<>();
        try {
            dsl.resultQuery(
                "SELECT line_no, line_type, gl_acct_main, gl_acct_sub, qty, unit_per, unit_cost, " +
                "       amt, tax_code, tax_amt, gst_gross_amt, for_curr_amt, desc_1, ref " +
                "FROM apdistn WHERE company_no=? AND supplier_no=? AND doc_date=? AND doc_type=? " +
                "  AND retent_flag=? AND doc_no=? ORDER BY line_no",
                s.getCompanyNo(), txn.get("suppNo"), txn.get("_docDateRaw"),
                rawDocType(txn.get("docType")), txn.get("retentFlag"), txn.get("docNo"))
            .fetch().forEach(r -> {
                Map<String, Object> ln = new LinkedHashMap<>();
                ln.put("lineNo",       zeroIfNull(r.get("line_no", Integer.class)));
                ln.put("lineType",     r.get("line_type", String.class));
                ln.put("glAcctMain",   zeroIfNull(r.get("gl_acct_main", Integer.class)));
                ln.put("glAcctSub",    zeroIfNull(r.get("gl_acct_sub", Integer.class)));
                ln.put("qty",          z(r.get("qty", BigDecimal.class)));
                ln.put("unitPer",      r.get("unit_per", String.class));
                ln.put("unitCost",     z(r.get("unit_cost", BigDecimal.class)));
                ln.put("amtExTax",     z(r.get("amt", BigDecimal.class)));
                ln.put("taxCode",      r.get("tax_code", String.class));
                ln.put("lineTaxAmt",   z(r.get("tax_amt", BigDecimal.class)));
                ln.put("amtIncTax",    z(r.get("gst_gross_amt", BigDecimal.class)));
                ln.put("fcAmt",        z(r.get("for_curr_amt", BigDecimal.class)));
                ln.put("description",  r.get("desc_1", String.class));
                ln.put("reference",    r.get("ref", String.class));
                lines.add(ln);
            });
        } catch (Exception e) {
            log.warn("fetchDistLines {} {}: {}", txn.get("suppNo"), txn.get("docNo"), e.getMessage());
        }
        return lines;
    }

    /** PDF layout: distribution lines as indented rowType="L" sub-rows under the transaction. */
    private void appendDistLines(AppSession s, Map<String, Object> txn, List<Map<String, Object>> rows) {
        for (Map<String, Object> ln : fetchDistLines(s, txn)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rowType", "L");
            row.put("suppNo",  txn.get("suppNo"));   // keeps the line in the same supplier group
            row.put("docNo",   txn.get("docNo"));
            row.putAll(ln);
            rows.add(row);
        }
    }

    /**
     * Excel layout: one row per distribution line, placed to the RIGHT of the header
     * columns. The header detail fields repeat on every line; the header <em>amounts</em>
     * appear only on the first line so column sums don't double-count. A transaction
     * with no distribution lines still emits one full header row.
     */
    private void appendExcelCombinedRows(AppSession s, Map<String, Object> txn, List<Map<String, Object>> rows) {
        List<Map<String, Object>> lines = fetchDistLines(s, txn);
        if (lines.isEmpty()) { rows.add(txn); return; }
        for (int i = 0; i < lines.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String k : HEADER_DETAIL_FIELDS) row.put(k, txn.get(k));   // repeated on every line
            if (i == 0) for (String k : HEADER_AMOUNT_FIELDS) row.put(k, txn.get(k));  // first line only
            row.putAll(lines.get(i));
            rows.add(row);
        }
    }

    // ── CHECK-HOLD-TRXS (on-hold invoices only) ──────────────────────────────
    private boolean holdTrxAccepted(String fullyDelivered, String withinTolerance,
                                    BigDecimal amtPaid, TxnListingParams p) {
        String fd = trim(fullyDelivered), wt = trim(withinTolerance);
        boolean a = blank(p.fullyDelivered())  || fd.equals(trim(p.fullyDelivered()));
        boolean b = blank(p.withinTolerance()) || wt.equals(trim(p.withinTolerance()));
        boolean c = "N".equalsIgnoreCase(p.paidButFlagsSet())
                 || ("Y".equalsIgnoreCase(p.paidButFlagsSet())
                     && amtPaid.signum() > 0
                     && ("N".equals(fd) || "N".equals(wt)));
        return a || b || c;
    }

    // ════════════════════════════════════════════════════════════════════════
    // APRC09 — Detailed Transaction Listing  (cobol/ap2/aprc09.cbl, aprc09s0.pl)
    // ════════════════════════════════════════════════════════════════════════

    /** Selection parameters — mirrors the APRC09S0 entry screen (10 fields). */
    public record DetailTxnParams(
            String startSupplier,
            String endSupplier,
            String subLedger,        // blank = all (on apsupps.sub_ledger)
            String dateType,         // "D" document date | "P" posting date
            LocalDate startDate,     // null = all dates
            LocalDate endDate,
            String startDocNo,
            String endDocNo,
            int batchNo,             // 0 = all
            String taxCode           // blank = all
    ) {}

    /**
     * APRC09 listing — driven by apdistn distribution lines joined to aptrans /
     * apsupps / glchart. Ported from {@code TEST-RECORD-SELECTION-03} (incl. the
     * hard-coded {@code doc_type <> 'P'} exclusion) and the tax-code grand-total
     * accumulators (T/E/C/N/D/J/Z).
     *
     * <p>PDF: supplier-grouped transaction header rows ("T") with indented line
     * rows ("L"). Excel ({@code excelLayout=true}): one row per line, header
     * detail repeated, header amounts on the first line of each transaction only.
     */
    public Map<String, Object> getDetailedTransactionData(AppSession s, DetailTxnParams p,
                                                          boolean excelLayout) {
        boolean post = "P".equalsIgnoreCase(p.dateType());
        String dateCol = post ? "t.posting_date" : "d.doc_date";

        StringBuilder sql = new StringBuilder(
            "SELECT d.supplier_no, s.name_1, s.sub_ledger, COALESCE(l.name1,'') AS sub_ledger_name, " +
            "       s.acct_status, d.doc_date, d.doc_type, d.retent_flag, d.doc_no, " +
            "       d.line_no, d.line_type, d.gl_acct_main, d.gl_acct_sub, " +
            "       g.abbrev_desc, g.fin_acct_flag, g.posting_flag, g.acct_main_no AS gl_found, " +
            "       d.desc_1, d.qty, d.unit_per, d.unit_cost, d.amt AS line_amt, d.tax_code, " +
            "       d.tax_amt AS line_tax, d.amt_paid, d.analysis_code, d.ledger_type, d.ledger_code, " +
            "       d.batch_no, t.amt AS trans_amt, t.retent_amt, t.posting_date, t.due_date, " +
            "       t.po_no, t.ref AS trans_ref, t.abn, t.must_pay_flag, t.prompt_pay_flag " +
            "FROM apdistn d " +
            "JOIN apsupps s ON s.company_no = d.company_no AND s.supplier_no = d.supplier_no " +
            "LEFT JOIN aptrans t ON t.company_no = d.company_no AND t.supplier_no = d.supplier_no " +
            "   AND t.doc_date = d.doc_date AND t.doc_type = d.doc_type " +
            "   AND t.retent_flag = d.retent_flag AND t.doc_no = d.doc_no " +
            "LEFT JOIN glchart g ON g.company_no = d.company_no " +
            "   AND g.acct_main_no = d.gl_acct_main AND g.acct_sub_no = d.gl_acct_sub " +
            "LEFT JOIN apledgr l ON l.company_no = s.company_no AND l.sub_ledger = s.sub_ledger " +
            "WHERE d.company_no = ? AND d.doc_type <> 'P' ");

        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());

        if (notBlank(p.startSupplier())) {
            sql.append(" AND d.supplier_no BETWEEN ? AND ? ");
            args.add(p.startSupplier());
            args.add(notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz");
        }
        if (notBlank(p.subLedger())) { sql.append(" AND s.sub_ledger = ? "); args.add(p.subLedger()); }
        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND ").append(dateCol).append(" BETWEEN ? AND ? ");
            args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(end));
        }
        if (notBlank(p.startDocNo())) {
            sql.append(" AND d.doc_no BETWEEN ? AND ? ");
            args.add(p.startDocNo());
            args.add(notBlank(p.endDocNo()) ? p.endDocNo() : "zzzzzzzzzzzzzzzzzzzz");
        }
        if (p.batchNo() > 0)        { sql.append(" AND d.batch_no = ? ");  args.add(p.batchNo()); }
        if (notBlank(p.taxCode()))  { sql.append(" AND d.tax_code = ? ");  args.add(p.taxCode()); }

        sql.append(" ORDER BY d.supplier_no, d.doc_date, d.doc_type, d.retent_flag, d.doc_no, d.line_no");

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, BigDecimal[]> taxTotals = new LinkedHashMap<>();   // code -> [amt, tax]
        for (String c : new String[]{"T", "E", "C", "N", "D", "J", "Z"})
            taxTotals.put(c, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        BigDecimal[] grand = {BigDecimal.ZERO, BigDecimal.ZERO};   // [lineAmt, lineTax]
        String[] lastTxn = {null};
        String err = null;

        try {
            // Complex multi-join with dynamic date column and optional ranges — kept as resultQuery
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                String txnKey = r.get("supplier_no", String.class) + "|" + r.get("doc_date", String.class) + "|"
                              + r.get("doc_type", String.class) + "|" + r.get("retent_flag", String.class) + "|"
                              + r.get("doc_no", String.class);
                boolean firstLine = !txnKey.equals(lastTxn[0]);
                lastTxn[0] = txnKey;

                String docType = trim(r.get("doc_type", String.class));
                BigDecimal lineAmt = z(r.get("line_amt", BigDecimal.class));
                BigDecimal lineTax = z(r.get("line_tax", BigDecimal.class));
                String taxCode = trim(r.get("tax_code", String.class));

                // tax-code grand totals (only T/E/C/N/D/J/Z accumulate)
                BigDecimal[] tt = taxTotals.get(taxCode);
                if (tt != null) { tt[0] = tt[0].add(lineAmt); tt[1] = tt[1].add(lineTax); }
                grand[0] = grand[0].add(lineAmt); grand[1] = grand[1].add(lineTax);

                String glDesc = glAcctStatus(r.get("gl_found", String.class), r.get("abbrev_desc", String.class),
                                             r.get("fin_acct_flag", String.class), r.get("posting_flag", String.class));
                String glAcct = zeroIfNull(r.get("gl_acct_main", Integer.class)) + "-" + zeroIfNull(r.get("gl_acct_sub", Integer.class));

                if (excelLayout) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    // header detail — repeated on every line
                    row.put("suppNo",        r.get("supplier_no", String.class));
                    row.put("name",          r.get("name_1", String.class));
                    row.put("subLedger",     r.get("sub_ledger", String.class));
                    row.put("subLedgerName", r.get("sub_ledger_name", String.class));
                    row.put("docType",       docTypeLabel(docType));
                    row.put("retentFlag",    r.get("retent_flag", String.class));
                    row.put("docDate",       ldSqlDate(r.get("doc_date", LocalDate.class)));
                    row.put("docNo",         r.get("doc_no", String.class));
                    row.put("postDate",      ldSqlDate(r.get("posting_date", LocalDate.class)));
                    row.put("dueDate",       ldSqlDate(r.get("due_date", LocalDate.class)));
                    row.put("poNo",          trim(r.get("po_no", String.class)));
                    row.put("transRef",      r.get("trans_ref", String.class));
                    row.put("abn",           r.get("abn", String.class));
                    row.put("batchNo",       zeroIfNull(r.get("batch_no", Integer.class)));
                    row.put("mustPay",       r.get("must_pay_flag", String.class));
                    row.put("promptPay",     r.get("prompt_pay_flag", String.class));
                    // header amounts — first line of the transaction only
                    if (firstLine) {
                        row.put("transAmt",  z(r.get("trans_amt", BigDecimal.class)));
                        row.put("retentAmt", z(r.get("retent_amt", BigDecimal.class)));
                    }
                    // line fields
                    putLineFields(row, r, glAcct, glDesc, lineAmt, lineTax);
                    rows.add(row);
                } else {
                    if (firstLine) {
                        Map<String, Object> h = new LinkedHashMap<>();
                        h.put("rowType",    "T");
                        h.put("suppNo",     r.get("supplier_no", String.class));
                        h.put("name",       r.get("name_1", String.class));
                        h.put("subLedger",  r.get("sub_ledger", String.class));
                        h.put("subLedgerName", r.get("sub_ledger_name", String.class));
                        h.put("acctStatus", acctStatusLabel(r.get("acct_status", String.class)));
                        h.put("docType",    docTypeLabel(docType));
                        h.put("retentFlag", r.get("retent_flag", String.class));
                        h.put("docDate",    ldSqlDate(r.get("doc_date", LocalDate.class)));
                        h.put("docNo",      r.get("doc_no", String.class));
                        h.put("transAmt",   z(r.get("trans_amt", BigDecimal.class)));
                        h.put("retentAmt",  z(r.get("retent_amt", BigDecimal.class)));
                        h.put("postDate",   ldSqlDate(r.get("posting_date", LocalDate.class)));
                        h.put("dueDate",    ldSqlDate(r.get("due_date", LocalDate.class)));
                        h.put("poNo",       trim(r.get("po_no", String.class)));
                        h.put("transRef",   r.get("trans_ref", String.class));
                        rows.add(h);
                    }
                    Map<String, Object> ln = new LinkedHashMap<>();
                    ln.put("rowType", "L");
                    ln.put("suppNo",  r.get("supplier_no", String.class));
                    ln.put("docNo",   r.get("doc_no", String.class));
                    putLineFields(ln, r, glAcct, glDesc, lineAmt, lineTax);
                    rows.add(ln);
                }
            });
        } catch (Exception e) {
            log.error("getDetailedTransactionData failed: {}", e.getMessage(), e);
            err = e.getMessage();
        }
        if (err != null) return warn("Query failed: " + err);

        Map<String, Object> params = new LinkedHashMap<>();
        for (var e : taxTotals.entrySet()) {
            params.put("TX_" + e.getKey() + "_AMT", e.getValue()[0]);
            params.put("TX_" + e.getKey() + "_TAX", e.getValue()[1]);
        }
        params.put("GRAND_AMT", grand[0]);
        params.put("GRAND_TAX", grand[1]);
        params.put("DATE_BASIS_DESC", post ? "Posting date" : "Document date");
        params.put("SUPP_RANGE", notBlank(p.startSupplier())
            ? p.startSupplier() + " to " + (notBlank(p.endSupplier()) ? p.endSupplier() : "end") : "All suppliers");
        params.put("SUB_LEDGER_DESC", notBlank(p.subLedger()) ? p.subLedger() : "All sub ledgers");
        params.put("DATE_RANGE", p.startDate() != null
            ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("params", params);
        result.put("rowCount", rows.size());
        return result;
    }

    private void putLineFields(Map<String, Object> row, org.jooq.Record r, String glAcct,
                               String glDesc, BigDecimal lineAmt, BigDecimal lineTax) {
        row.put("lineNo",      zeroIfNull(r.get("line_no", Integer.class)));
        row.put("lineType",    r.get("line_type", String.class));
        row.put("glAcct",      glAcct);
        row.put("glDesc",      glDesc);
        row.put("desc1",       r.get("desc_1", String.class));
        row.put("qty",         z(r.get("qty", BigDecimal.class)));
        row.put("unitPer",     r.get("unit_per", String.class));
        row.put("unitCost",    z(r.get("unit_cost", BigDecimal.class)));
        row.put("lineAmt",     lineAmt);
        row.put("taxCode",     r.get("tax_code", String.class));
        row.put("lineTaxAmt",  lineTax);
        row.put("amtPaid",     z(r.get("amt_paid", BigDecimal.class)));
        row.put("analysisCode", r.get("analysis_code", String.class));
        row.put("ledgerType",  r.get("ledger_type", String.class));
        row.put("ledgerCode",  r.get("ledger_code", String.class));
    }

    /** CHECK-GL-ACCT-STATUS — status text replaces the description when the GL account is unusable. */
    private static String glAcctStatus(String found, String desc, String finFlag, String postFlag) {
        if (found == null)                              return "* NOT ON FILE *";
        if (!"Y".equalsIgnoreCase(trim(finFlag)))       return "NOT A FINANCIAL ACCT";
        if ("N".equalsIgnoreCase(trim(postFlag)))       return "NON-POSTING ACCOUNT";
        return desc;
    }

    private static String acctStatusLabel(String st) {
        return switch (trim(st)) {
            case "H" -> "* ON HOLD *"; case "N" -> "* NO PURCHASES *"; default -> "";
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    // APTL06 — Period Summary  (cobol/ap2/aptl06.cbl, aptl06.pl, aptl06s0.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record PeriodSummaryParams(LocalDate periodEndDate, String startSubLedger, String endSubLedger) {}

    /**
     * APTL06 — per AP sub-ledger opening balance, period movements and closing.
     * Ported from {@code GET-OPEN-BAL / GET-PRIOR-PERIODS}: opening = the zero-key
     * apsumry row's {@code open_bal} + Σ prior-period net movements; closing =
     * opening + this period's net movements. The period-end date is validated by
     * locating the gldates row whose {@code period_end_01..13} contains it (= COBOL
     * "MUST BE A PERIOD ENDING DATE"); prior periods are that year's ends &lt; chosen.
     */
    public Map<String, Object> getPeriodSummaryData(AppSession s, PeriodSummaryParams p) {
        if (p.periodEndDate() == null) return warn("Enter a period end date.");
        Date chosen = Date.valueOf(p.periodEndDate());

        // 1. locate the financial-year row containing this period end
        // (dynamic 13-column IN list — kept as resultQuery)
        List<LocalDate> ends = new ArrayList<>();
        try {
            StringBuilder cols = new StringBuilder();
            for (int i = 1; i <= 13; i++) cols.append(i > 1 ? "," : "").append(String.format("period_end_%02d", i));
            dsl.resultQuery("SELECT " + cols + " FROM gldates WHERE company_no=? AND ? IN (" + cols + ")",
                s.getCompanyNo(), chosen)
               .fetch().forEach(r -> {
                   for (int i = 1; i <= 13; i++) {
                       LocalDate d = r.get(String.format("period_end_%02d", i), LocalDate.class);
                       if (d != null) ends.add(d);
                   }
               });
        } catch (Exception e) { return warn("Calendar lookup failed: " + e.getMessage()); }
        if (ends.isEmpty())
            return warn("Period end date must be a period-ending date in the financial calendar.");

        List<Object> inDates = new ArrayList<>();
        inDates.add(Date.valueOf("1899-12-31"));                       // zero-key opening row
        for (LocalDate d : ends) if (d.isBefore(p.periodEndDate())) inDates.add(Date.valueOf(d));  // priors
        inDates.add(chosen);                                           // current period

        boolean forCurr = "Y".equalsIgnoreCase(scalarString(
            "SELECT ap_for_curr_flag FROM cpcoyco WHERE company_no=? LIMIT 1", s.getCompanyNo()));

        // 2. sub-ledger range from apledgr (driver)
        String startSL = notBlank(p.startSubLedger()) ? p.startSubLedger() : "";
        String endSL   = notBlank(p.startSubLedger())
            ? (notBlank(p.endSubLedger()) ? p.endSubLedger() : "zzzz") : "zzzz";

        Map<String, Map<String, Object>> bySl = new LinkedHashMap<>();   // sub_ledger -> accumulator
        try {
            dsl.select(APLEDGR.SUB_LEDGER, APLEDGR.NAME1)
               .from(APLEDGR)
               .where(APLEDGR.COMPANY_NO.eq(s.getCompanyNo())
                   .and(APLEDGR.SUB_LEDGER.between(startSL, endSL)))
               .orderBy(APLEDGR.SUB_LEDGER)
               .fetch()
               .forEach(r -> {
                   Map<String, Object> a = newPeriodAcc(r.get(APLEDGR.SUB_LEDGER), r.get(APLEDGR.NAME1));
                   bySl.put(r.get(APLEDGR.SUB_LEDGER), a);
               });
        } catch (Exception e) { return warn("Sub-ledger lookup failed: " + e.getMessage()); }
        if (bySl.isEmpty()) return warn("No sub ledgers in the selected range.");

        // 3. apsumry rows for the opening, prior and current periods (dynamic IN list — resultQuery)
        String inMarks = qMarks(inDates.size());
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo()); args.add(startSL); args.add(endSL); args.addAll(inDates);
        final LocalDate chosenLd = p.periodEndDate();
        final boolean fc = forCurr;
        try {
            dsl.resultQuery("SELECT * FROM apsumry WHERE company_no=? AND sub_ledger BETWEEN ? AND ? " +
                            "AND period_end_date IN (" + inMarks + ")",
                args.toArray())
               .fetch().forEach(r -> {
                   Map<String, Object> a = bySl.get(r.get("sub_ledger", String.class));
                   if (a == null) return;
                   LocalDate ped = r.get("period_end_date", LocalDate.class);
                   if (ped == null) return;
                   BigDecimal inv = z(r.get("ap_inv_value", BigDecimal.class)), crn = z(r.get("ap_cr_note_value", BigDecimal.class)),
                              drn = z(r.get("ap_dr_note_value", BigDecimal.class)), chq = z(r.get("ap_chq_value", BigDecimal.class)),
                              vd  = z(r.get("ap_void_chqs_value", BigDecimal.class)), disc = z(r.get("ap_disc_taken_value", BigDecimal.class)),
                              cf  = z(r.get("ap_curr_fluctuation", BigDecimal.class)), pf = z(r.get("ap_prov_fluctuation", BigDecimal.class)),
                              rded = z(r.get("ap_retent_deducted", BigDecimal.class)), rbil = z(r.get("ap_retent_billed", BigDecimal.class));
                   if (ped.isBefore(LocalDate.of(1900, 1, 1))) {                 // opening (zero-key) row
                       acc(a, "opening", z(r.get("open_bal", BigDecimal.class)));
                       acc(a, "retentOpening", z(r.get("retent_open_bal", BigDecimal.class)));
                   } else if (ped.isBefore(chosenLd)) {                          // prior period — accumulate into opening
                       acc(a, "opening", inv.add(crn).add(drn).add(chq).add(vd).add(disc).add(cf).add(pf));
                       acc(a, "retentOpening", rded.subtract(rbil));
                   } else {                                                       // current period
                       acc(a, "invoices", inv); a.put("invNo", zeroIfNull(r.get("ap_inv_no", Integer.class)));
                       acc(a, "crNotes", crn);  a.put("crNo", zeroIfNull(r.get("ap_cr_note_no", Integer.class)));
                       acc(a, "drNotes", drn);  a.put("drNo", zeroIfNull(r.get("ap_dr_note_no", Integer.class)));
                       acc(a, "payments", chq); a.put("chqNo", zeroIfNull(r.get("ap_chq_no", Integer.class)));
                       acc(a, "voidChqs", vd);  a.put("voidNo", zeroIfNull(r.get("ap_void_chqs_no", Integer.class)));
                       acc(a, "discount", disc);
                       acc(a, "currFluct", cf); acc(a, "provFluct", pf);
                       acc(a, "retentDeducted", rded); acc(a, "retentBilled", rbil);
                   }
               });
        } catch (Exception e) { return warn("Period data query failed: " + e.getMessage()); }

        // 4. finalise per sub-ledger + grand totals
        List<Map<String, Object>> rows = new ArrayList<>(bySl.values());
        BigDecimal gOpen = BigDecimal.ZERO, gInv = BigDecimal.ZERO, gPay = BigDecimal.ZERO,
                   gClose = BigDecimal.ZERO, gRetClose = BigDecimal.ZERO, gPeriod = BigDecimal.ZERO;
        for (Map<String, Object> a : rows) {
            BigDecimal opening = (BigDecimal) a.get("opening");
            BigDecimal curNet = ((BigDecimal) a.get("invoices")).add((BigDecimal) a.get("crNotes"))
                .add((BigDecimal) a.get("drNotes")).add((BigDecimal) a.get("payments"))
                .add((BigDecimal) a.get("voidChqs")).add((BigDecimal) a.get("discount"))
                .add(fc ? ((BigDecimal) a.get("currFluct")).add((BigDecimal) a.get("provFluct")) : BigDecimal.ZERO);
            BigDecimal closing = opening.add(curNet);
            BigDecimal retOpening = (BigDecimal) a.get("retentOpening");
            BigDecimal retClosing = retOpening.add((BigDecimal) a.get("retentDeducted"))
                                              .subtract((BigDecimal) a.get("retentBilled"));
            BigDecimal periodBal = closing.add(retClosing);
            a.put("closing", closing);
            a.put("retentClosing", retClosing);
            a.put("periodBalance", periodBal);
            gOpen = gOpen.add(opening); gInv = gInv.add((BigDecimal) a.get("invoices"));
            gPay = gPay.add((BigDecimal) a.get("payments")); gClose = gClose.add(closing);
            gRetClose = gRetClose.add(retClosing); gPeriod = gPeriod.add(periodBal);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("PERIOD_END", p.periodEndDate().toString());
        params.put("FOR_CURR", forCurr ? "Y" : "N");
        params.put("GT_OPENING", gOpen); params.put("GT_INVOICES", gInv); params.put("GT_PAYMENTS", gPay);
        params.put("GT_CLOSING", gClose); params.put("GT_RETENT_CLOSING", gRetClose); params.put("GT_PERIOD_BAL", gPeriod);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("params", params);
        result.put("rowCount", rows.size());
        return result;
    }

    private static Map<String, Object> newPeriodAcc(String sl, String name) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("subLedger", sl); a.put("name", name);
        for (String k : new String[]{"opening", "invoices", "crNotes", "drNotes", "payments", "voidChqs",
                "discount", "currFluct", "provFluct", "retentOpening", "retentDeducted", "retentBilled"})
            a.put(k, BigDecimal.ZERO);
        a.put("invNo", 0); a.put("crNo", 0); a.put("drNo", 0); a.put("chqNo", 0); a.put("voidNo", 0);
        return a;
    }

    private static void acc(Map<String, Object> a, String key, BigDecimal v) {
        a.put(key, ((BigDecimal) a.get(key)).add(v));
    }

    // ════════════════════════════════════════════════════════════════════════
    // APTL05 — GL Distributions Summary  (cobol/ap2/aptl05.cbl, aptl05s0.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record GlDistParams(LocalDate periodEndDate, String subLedger) {}

    /**
     * APTL05 — AP GL distribution postings for a period-end date, grouped by GL
     * account and split into CONTROL ({@code acct_type='C'}) vs EXPENSE/DISTRIBUTION
     * (anything else) per {@code ADD-TO-TOTALS}. Driven by apdisum; glchart supplies
     * the description (with the {@code CHECK-GL-ACCT-STATUS} overrides).
     */
    public Map<String, Object> getGlDistributionsData(AppSession s, GlDistParams p) {
        if (p.periodEndDate() == null) return warn("Enter a period end date.");

        StringBuilder sql = new StringBuilder(
            "SELECT d.sub_ledger, COALESCE(l.name1,'') AS sub_ledger_name, d.acct_type, " +
            "       d.gl_acct_main_no, d.gl_acct_sub_no, d.amt, " +
            "       g.desc1, g.fin_acct_flag, g.posting_flag, g.acct_main_no AS gl_found " +
            "FROM apdisum d " +
            "LEFT JOIN glchart g ON g.company_no = d.company_no " +
            "   AND g.acct_main_no = d.gl_acct_main_no AND g.acct_sub_no = d.gl_acct_sub_no " +
            "LEFT JOIN apledgr l ON l.company_no = d.company_no AND l.sub_ledger = d.sub_ledger " +
            "WHERE d.company_no = ? AND d.period_end_date = ? ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo()); args.add(Date.valueOf(p.periodEndDate()));
        if (notBlank(p.subLedger())) { sql.append(" AND d.sub_ledger = ? "); args.add(p.subLedger()); }
        sql.append(" ORDER BY d.sub_ledger, d.acct_type, d.gl_acct_main_no, d.gl_acct_sub_no");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] totals = {BigDecimal.ZERO, BigDecimal.ZERO};   // [control, expense]
        String err = null;
        try {
            // Multi-join with optional sub-ledger filter — kept as resultQuery
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                String at = trim(r.get("acct_type", String.class));
                BigDecimal amt = z(r.get("amt", BigDecimal.class));
                if ("C".equals(at)) totals[0] = totals[0].add(amt); else totals[1] = totals[1].add(amt);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("subLedger",     r.get("sub_ledger", String.class));
                row.put("subLedgerName", r.get("sub_ledger_name", String.class));
                row.put("acctTypeRaw",   at);
                row.put("acctType",      "C".equals(at) ? "CONTROL" : "DISTRIBUTION");
                row.put("glAcctMain",    zeroIfNull(r.get("gl_acct_main_no", Integer.class)));
                row.put("glAcctSub",     zeroIfNull(r.get("gl_acct_sub_no", Integer.class)));
                row.put("glAcct",        zeroIfNull(r.get("gl_acct_main_no", Integer.class)) + "-" + zeroIfNull(r.get("gl_acct_sub_no", Integer.class)));
                row.put("glDesc",        glAcctStatus(r.get("gl_found", String.class), r.get("desc1", String.class),
                                                      r.get("fin_acct_flag", String.class), r.get("posting_flag", String.class)));
                row.put("amount",        amt);
                rows.add(row);
            });
        } catch (Exception e) {
            log.error("getGlDistributionsData failed: {}", e.getMessage(), e);
            err = e.getMessage();
        }
        if (err != null) return warn("Query failed: " + err);
        if (rows.isEmpty()) return warn("No GL distributions for the selected period end date.");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("PERIOD_END", p.periodEndDate().toString());
        params.put("SUB_LEDGER_DESC", notBlank(p.subLedger()) ? p.subLedger() : "All sub ledgers");
        params.put("GT_CONTROL", totals[0]);
        params.put("GT_EXPENSE", totals[1]);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("params", params);
        result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // APTL09 — Supplier Purchase History  (cobol/ap2/aptl09.cbl, aptl09s0.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record PurchaseHistParams(String subLedger, LocalDate periodEndDate,
                                     String startSupplier, String endSupplier, boolean suppressZero) {}

    /**
     * APTL09 — for the selected period, this-year vs last-year purchase values:
     * period (MTD) this/last + variance, and YTD (Σ periods 1..P) this/last + variance.
     * The period index P is the position of the chosen date in the year's
     * {@code gldates.period_end_01..13}; last year = that row's {@code year_no - 1}.
     *
     * <p><b>Divergence:</b> COBOL grouped/filtered by {@code apsupps.supplier_type},
     * but that column was dropped by the extract — so the supplier-type range and its
     * grouping are omitted. Grouping is by sub-ledger only.
     */
    public Map<String, Object> getPurchaseHistoryData(AppSession s, PurchaseHistParams p) {
        if (p.periodEndDate() == null) return warn("Enter a period ending date.");

        // locate year + period index P from gldates (dynamic 13-column IN list — resultQuery)
        int[] yearNo = {0}; int[] period = {0};
        try {
            StringBuilder cols = new StringBuilder();
            for (int i = 1; i <= 13; i++) cols.append(i > 1 ? "," : "").append(String.format("period_end_%02d", i));
            dsl.resultQuery("SELECT year_no," + cols + " FROM gldates WHERE company_no=? AND ? IN (" + cols + ")",
                s.getCompanyNo(), Date.valueOf(p.periodEndDate()))
               .fetch().forEach(r -> {
                   yearNo[0] = zeroIfNull(r.get("year_no", Integer.class));
                   for (int i = 1; i <= 13; i++) {
                       LocalDate d = r.get(String.format("period_end_%02d", i), LocalDate.class);
                       if (d != null && d.equals(p.periodEndDate())) period[0] = i;
                   }
               });
        } catch (Exception e) { return warn("Calendar lookup failed: " + e.getMessage()); }
        if (period[0] == 0)
            return warn("Period ending date must be a period-ending date in the financial calendar.");
        final int P = period[0];

        StringBuilder cy = new StringBuilder(), ly = new StringBuilder();
        for (int i = 1; i <= 13; i++) {
            cy.append(String.format(", cy.purch_%02d AS c%02d", i, i));
            ly.append(String.format(", ly.purch_%02d AS l%02d", i, i));
        }
        StringBuilder sql = new StringBuilder(
            "SELECT s.sub_ledger, s.supplier_no, s.name_1, COALESCE(g.name1,'') AS sub_ledger_name" +
            cy + ly +
            " FROM apsupps s " +
            " LEFT JOIN appurch cy ON cy.company_no=s.company_no AND cy.sub_ledger=s.sub_ledger AND cy.supplier_no=s.supplier_no AND cy.year_no=? " +
            " LEFT JOIN appurch ly ON ly.company_no=s.company_no AND ly.sub_ledger=s.sub_ledger AND ly.supplier_no=s.supplier_no AND ly.year_no=? " +
            " LEFT JOIN apledgr g ON g.company_no=s.company_no AND g.sub_ledger=s.sub_ledger " +
            " WHERE s.company_no=? AND s.purch_hist_flag='Y' ");
        List<Object> args = new ArrayList<>();
        args.add(yearNo[0]); args.add(yearNo[0] - 1); args.add(s.getCompanyNo());
        if (notBlank(p.subLedger())) { sql.append(" AND s.sub_ledger=? "); args.add(p.subLedger()); }
        if (notBlank(p.startSupplier())) {
            sql.append(" AND s.supplier_no BETWEEN ? AND ? ");
            args.add(p.startSupplier());
            args.add(notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz");
        }
        sql.append(" ORDER BY s.sub_ledger, s.supplier_no");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] gt = new BigDecimal[6];   // perThis, perLast, perVar, ytdThis, ytdLast, ytdVar
        Arrays.fill(gt, BigDecimal.ZERO);
        String err = null;
        try {
            // Dynamic 26-column appurch self-join (period columns) — kept as resultQuery
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                BigDecimal perThis = z(r.get("c" + String.format("%02d", P), BigDecimal.class));
                BigDecimal perLast = z(r.get("l" + String.format("%02d", P), BigDecimal.class));
                BigDecimal ytdThis = BigDecimal.ZERO, ytdLast = BigDecimal.ZERO;
                for (int i = 1; i <= P; i++) {
                    ytdThis = ytdThis.add(z(r.get("c" + String.format("%02d", i), BigDecimal.class)));
                    ytdLast = ytdLast.add(z(r.get("l" + String.format("%02d", i), BigDecimal.class)));
                }
                BigDecimal perVar = perThis.subtract(perLast);
                BigDecimal ytdVar = ytdThis.subtract(ytdLast);

                if (p.suppressZero() && perThis.signum() == 0 && perLast.signum() == 0
                        && ytdThis.signum() == 0 && ytdLast.signum() == 0) return;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("subLedger",     r.get("sub_ledger", String.class));
                row.put("subLedgerName", r.get("sub_ledger_name", String.class));
                row.put("supplierNo",    r.get("supplier_no", String.class));
                row.put("name",          r.get("name_1", String.class));
                row.put("perThis", perThis); row.put("perLast", perLast);
                row.put("perVar",  perVar);  row.put("perVarPct", pct(perVar, perThis));
                row.put("ytdThis", ytdThis); row.put("ytdLast", ytdLast);
                row.put("ytdVar",  ytdVar);  row.put("ytdVarPct", pct(ytdVar, ytdThis));
                rows.add(row);
                gt[0] = gt[0].add(perThis); gt[1] = gt[1].add(perLast); gt[2] = gt[2].add(perVar);
                gt[3] = gt[3].add(ytdThis); gt[4] = gt[4].add(ytdLast); gt[5] = gt[5].add(ytdVar);
            });
        } catch (Exception e) {
            log.error("getPurchaseHistoryData failed: {}", e.getMessage(), e);
            err = e.getMessage();
        }
        if (err != null) return warn("Query failed: " + err);
        if (rows.isEmpty()) return warn("No suppliers with purchase history matched the selection.");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("PERIOD_END", p.periodEndDate().toString());
        params.put("YEAR_NO", yearNo[0]); params.put("PERIOD_NO", P);
        params.put("SUB_LEDGER_DESC", notBlank(p.subLedger()) ? p.subLedger() : "All sub ledgers");
        params.put("GT_PER_THIS", gt[0]); params.put("GT_PER_LAST", gt[1]); params.put("GT_PER_VAR", gt[2]);
        params.put("GT_YTD_THIS", gt[3]); params.put("GT_YTD_LAST", gt[4]); params.put("GT_YTD_VAR", gt[5]);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    /** %variance = var/base*100, 0 when either is zero (COBOL CALC-VAR; denominator = this-year). */
    private static BigDecimal pct(BigDecimal var, BigDecimal base) {
        if (base.signum() == 0 || var.signum() == 0) return BigDecimal.ZERO;
        return var.multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
    }

    // ════════════════════════════════════════════════════════════════════════
    // APRC04 — Unbalanced Reconciliation  (cobol/ap2/aprc04.cbl, aprc04s0.pl)
    // ════════════════════════════════════════════════════════════════════════

    /** unbalLocal / unbalForeign are "U" (unbalanced) or "B" (balanced) — no "all". */
    public record UnbalReconParams(String startSupplier, String endSupplier,
                                   LocalDate startDate, LocalDate endDate,
                                   String unbalLocal, String unbalForeign) {}

    /**
     * APRC04 — reconciliations out of (or in) balance. Ported from
     * {@code TEST-RECORD-SELECTION-03}: local test ORs the three local balances
     * (gross/outstanding/claim), foreign test ORs the three FC balances; "U" =
     * any non-zero, "B" = all zero; the two tests combine with AND. Optional
     * last-transaction-date ({@code aprecon.last_doc_date}) range.
     */
    public Map<String, Object> getUnbalancedReconData(AppSession s, UnbalReconParams p) {
        String localTest = "B".equalsIgnoreCase(p.unbalLocal())
            ? "(r.gross_bal=0 AND r.outstanding_bal=0 AND r.claim_bal=0)"
            : "(r.gross_bal<>0 OR r.outstanding_bal<>0 OR r.claim_bal<>0)";
        String fcTest = "B".equalsIgnoreCase(p.unbalForeign())
            ? "(r.for_curr_gross_bal=0 AND r.for_curr_outst_bal=0 AND r.for_curr_claim_bal=0)"
            : "(r.for_curr_gross_bal<>0 OR r.for_curr_outst_bal<>0 OR r.for_curr_claim_bal<>0)";

        StringBuilder sql = new StringBuilder(
            "SELECT r.supplier_no, COALESCE(s.name_1,'') AS name_1, COALESCE(s.sub_ledger,'') AS sub_ledger, " +
            "       COALESCE(s.alpha_key,'') AS alpha_key, r.recon_no, " +
            "       r.gross_bal, r.outstanding_bal, r.claim_bal, r.for_curr_code, " +
            "       r.for_curr_gross_bal, r.for_curr_outst_bal, r.for_curr_claim_bal, r.last_doc_date " +
            "FROM aprecon r " +
            "LEFT JOIN apsupps s ON s.company_no = r.company_no AND s.supplier_no = r.supplier_no " +
            "WHERE r.company_no = ? AND " + localTest + " AND " + fcTest + " ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.startSupplier())) {
            sql.append(" AND r.supplier_no BETWEEN ? AND ? ");
            args.add(p.startSupplier());
            args.add(notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz");
        }
        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND r.last_doc_date BETWEEN ? AND ? ");
            args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(end));
        }
        sql.append(" ORDER BY r.supplier_no, r.recon_no");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] gt = new BigDecimal[6];
        Arrays.fill(gt, BigDecimal.ZERO);
        Set<String> suppliers = new HashSet<>();
        String err = null;
        try {
            // Dynamic balance test expressions in WHERE — kept as resultQuery
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("supplierNo", r.get("supplier_no", String.class));
                row.put("name",       r.get("name_1", String.class));
                row.put("subLedger",  r.get("sub_ledger", String.class));
                row.put("alphaKey",   r.get("alpha_key", String.class));
                row.put("reconNo",    zeroIfNull(r.get("recon_no", Integer.class)));
                BigDecimal gb = z(r.get("gross_bal", BigDecimal.class)), ob = z(r.get("outstanding_bal", BigDecimal.class)),
                           cb = z(r.get("claim_bal", BigDecimal.class)), fg = z(r.get("for_curr_gross_bal", BigDecimal.class)),
                           fo = z(r.get("for_curr_outst_bal", BigDecimal.class)), fcb = z(r.get("for_curr_claim_bal", BigDecimal.class));
                row.put("grossBal", gb); row.put("outstandingBal", ob); row.put("claimBal", cb);
                row.put("forCurrCode", r.get("for_curr_code", String.class));
                row.put("fcGrossBal", fg); row.put("fcOutstBal", fo); row.put("fcClaimBal", fcb);
                row.put("lastDocDate", ldSqlDate(r.get("last_doc_date", LocalDate.class)));
                rows.add(row);
                gt[0] = gt[0].add(gb); gt[1] = gt[1].add(ob); gt[2] = gt[2].add(cb);
                gt[3] = gt[3].add(fg); gt[4] = gt[4].add(fo); gt[5] = gt[5].add(fcb);
                suppliers.add(r.get("supplier_no", String.class));
            });
        } catch (Exception e) {
            log.error("getUnbalancedReconData failed: {}", e.getMessage(), e);
            err = e.getMessage();
        }
        if (err != null) return warn("Query failed: " + err);
        if (rows.isEmpty()) return warn("No reconciliations matched the selection.");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOCAL_DESC", "B".equalsIgnoreCase(p.unbalLocal()) ? "Balanced" : "Unbalanced");
        params.put("FC_DESC", "B".equalsIgnoreCase(p.unbalForeign()) ? "Balanced" : "Unbalanced");
        params.put("GT_GROSS", gt[0]); params.put("GT_OUTST", gt[1]); params.put("GT_CLAIM", gt[2]);
        params.put("GT_FC_GROSS", gt[3]); params.put("GT_FC_OUTST", gt[4]); params.put("GT_FC_CLAIM", gt[5]);
        params.put("SUPPLIER_COUNT", suppliers.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // APRC03 — Account Reconciliation  (cobol/ap2/aprc03.cbl, aprc03s0.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record AccountReconParams(
            String startSupplier, String endSupplier,
            int startReconNo, int endReconNo,   // 0 = all recons
            String unbalancedFlag,              // "U" / "B" / "A"
            String localFcFlag,                 // "L" local | "F" foreign
            String forCurrCode,                 // only when F
            String paymtDocNoInd                // "Y" show payment instrument detail
    ) {}

    /**
     * APRC03 — reconciliation detail: invoices matched to payments, grouped by
     * supplier then reconciliation. Ported from {@code TEST-RECORD-SELECTION-03}
     * (supplier/recon range, balanced/unbalanced via aprecon balances, local/FC),
     * {@code CALC-GROSS-NET-AMTS} (inv/cr gross + net, net zeroed when the recon is
     * fully balanced) and {@code SET-PAYMT-DOC-NO} (payment instrument doc-no).
     * The COBOL "include archived" field is intentionally omitted (it was never
     * used in any filter). cpfccod isn't in the extract, so the currency code is
     * accepted without validation.
     */
    public Map<String, Object> getAccountReconData(AppSession s, AccountReconParams p, boolean excelLayout) {
        boolean fc = "F".equalsIgnoreCase(p.localFcFlag());
        String ub = p.unbalancedFlag() == null ? "A" : p.unbalancedFlag().toUpperCase();

        StringBuilder sql = new StringBuilder(
            "SELECT t.supplier_no, COALESCE(s.name_1,'') AS name_1, COALESCE(s.sub_ledger,'') AS sub_ledger, " +
            "       t.recon_no, t.recon_seq_no, t.doc_type, t.retent_flag, t.doc_no, t.doc_date, t.posting_date, " +
            "       t.amt, t.retent_amt, t.disc_taken, t.amt_paid, t.for_curr_fluct_amt, " +
            "       t.for_curr_amt, t.for_curr_retent_amt, t.for_curr_disc_taken, t.for_curr_amt_paid, " +
            "       t.for_curr_code, t.cmtrans_bank_code, t.cmtrans_doc_type, t.cmtrans_doc_no, " +
            "       r.recon_no AS recon_found, r.gross_bal, r.outstanding_bal, r.claim_bal, " +
            "       r.for_curr_gross_bal, r.for_curr_outst_bal, r.for_curr_claim_bal " +
            "FROM aptrans t " +
            "LEFT JOIN apsupps s ON s.company_no = t.company_no AND s.supplier_no = t.supplier_no " +
            "LEFT JOIN aprecon r ON r.company_no = t.company_no AND r.supplier_no = t.supplier_no AND r.recon_no = t.recon_no " +
            "WHERE t.company_no = ? ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.startSupplier())) {
            sql.append(" AND t.supplier_no BETWEEN ? AND ? ");
            args.add(p.startSupplier());
            args.add(notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz");
        }
        if (p.startReconNo() > 0) {
            sql.append(" AND t.recon_no BETWEEN ? AND ? ");
            args.add(p.startReconNo());
            args.add(p.endReconNo() > 0 ? p.endReconNo() : 9999);
        }
        if (fc && notBlank(p.forCurrCode())) { sql.append(" AND t.for_curr_code = ? "); args.add(p.forCurrCode()); }
        sql.append(" ORDER BY t.supplier_no, t.recon_no, t.recon_seq_no");

        List<Map<String, Object>> rows = new ArrayList<>();
        int[] supplierCount = {0};
        Set<String> seenSupp = new HashSet<>();
        BigDecimal[] gt = {BigDecimal.ZERO, BigDecimal.ZERO};   // grand net, grand docAmt
        String err = null;
        try {
            // Complex recon-balance filter + optional FC/recon range — kept as resultQuery
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                int reconNo = zeroIfNull(r.get("recon_no", Integer.class));
                boolean reconFound = r.get("recon_found") != null;
                BigDecimal gb = z(r.get("gross_bal", BigDecimal.class)), ob = z(r.get("outstanding_bal", BigDecimal.class)),
                           cb = z(r.get("claim_bal", BigDecimal.class));
                boolean balanced = reconFound && gb.signum() == 0 && ob.signum() == 0 && cb.signum() == 0;

                // balanced/unbalanced selection (Java — needs recon_no=0 special case)
                if ("U".equals(ub)) { if (!(reconNo == 0 || (reconFound && !balanced))) return; }
                else if ("B".equals(ub)) { if (!(reconNo > 0 && balanced)) return; }
                // "A" → keep all

                String docType = trim(r.get("doc_type", String.class));
                BigDecimal docAmt   = z(fc ? r.get("for_curr_amt", BigDecimal.class)        : r.get("amt", BigDecimal.class));
                BigDecimal retent   = z(fc ? r.get("for_curr_retent_amt", BigDecimal.class) : r.get("retent_amt", BigDecimal.class));
                BigDecimal disc     = z(fc ? r.get("for_curr_disc_taken", BigDecimal.class) : r.get("disc_taken", BigDecimal.class));
                BigDecimal paid     = z(fc ? r.get("for_curr_amt_paid", BigDecimal.class)   : r.get("amt_paid", BigDecimal.class));
                BigDecimal fluct    = z(r.get("for_curr_fluct_amt", BigDecimal.class));

                BigDecimal invGross = BigDecimal.ZERO, crGross = BigDecimal.ZERO;
                if ("P".equals(docType)) {
                    crGross = docAmt.add(disc);
                } else if ("C".equals(docType) || "K".equals(docType)
                        || ("B".equals(docType) && docAmt.signum() < 0)) {
                    crGross = docAmt.subtract(retent);
                } else {
                    invGross = docAmt.subtract(retent);
                }
                BigDecimal net = "P".equals(docType)
                    ? docAmt.add(disc).subtract(paid)
                    : docAmt.subtract(retent).subtract(paid).subtract(disc).subtract(fc ? BigDecimal.ZERO : fluct);
                if (balanced) net = BigDecimal.ZERO;

                String displayDocNo = r.get("doc_no", String.class);
                if ("Y".equalsIgnoreCase(p.paymtDocNoInd()) && ("P".equals(docType) || "V".equals(docType))
                        && notBlank(r.get("cmtrans_bank_code", String.class))) {
                    String t = "3".equals(trim(r.get("cmtrans_doc_type", String.class))) ? "chq"
                             : "4".equals(trim(r.get("cmtrans_doc_type", String.class))) ? "eft" : "pmt";
                    displayDocNo = t + "/" + trim(r.get("cmtrans_bank_code", String.class)) + "/" + zeroIfNull(r.get("cmtrans_doc_no", Integer.class));
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("supplierNo", r.get("supplier_no", String.class));
                row.put("name",       r.get("name_1", String.class));
                row.put("subLedger",  r.get("sub_ledger", String.class));
                row.put("reconNo",    reconNo);
                row.put("reconKey",   r.get("supplier_no", String.class) + "|" + reconNo);
                row.put("reconLabel", reconNo == 0 ? "Unreconciled" : "Recon " + reconNo);
                row.put("seqNo",      zeroIfNull(r.get("recon_seq_no", Integer.class)) == 0 ? "UNPOST" : String.valueOf(r.get("recon_seq_no", Integer.class)));
                row.put("docType",    reconDocTypeLabel(docType));
                row.put("retentFlag", r.get("retent_flag", String.class));
                row.put("docNo",      displayDocNo);
                row.put("docDate",    ldSqlDate(r.get("doc_date", LocalDate.class)));
                row.put("postDate",   ldSqlDate(r.get("posting_date", LocalDate.class)));
                row.put("docAmt",     docAmt);
                row.put("retentAmt",  retent);
                row.put("invGross",   invGross);
                row.put("crGross",    crGross);
                row.put("amtPaid",    paid);
                row.put("discTaken",  disc);
                row.put("net",        net);
                row.put("forCurrCode", r.get("for_curr_code", String.class));
                row.put("grossBal", gb); row.put("outstandingBal", ob); row.put("claimBal", cb);
                row.put("fcGrossBal", z(r.get("for_curr_gross_bal", BigDecimal.class)));
                row.put("fcOutstBal", z(r.get("for_curr_outst_bal", BigDecimal.class)));
                row.put("fcClaimBal", z(r.get("for_curr_claim_bal", BigDecimal.class)));
                rows.add(row);
                gt[0] = gt[0].add(net); gt[1] = gt[1].add(docAmt);
                if (seenSupp.add(r.get("supplier_no", String.class))) supplierCount[0]++;
            });
        } catch (Exception e) {
            log.error("getAccountReconData failed: {}", e.getMessage(), e);
            err = e.getMessage();
        }
        if (err != null) return warn("Query failed: " + err);
        if (rows.isEmpty()) return warn("No reconciliations matched the selection.");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("CURRENCY_DESC", fc ? ("Foreign" + (notBlank(p.forCurrCode()) ? " (" + p.forCurrCode() + ")" : "")) : "Local");
        params.put("BALANCE_DESC", "U".equals(ub) ? "Unbalanced only" : "B".equals(ub) ? "Balanced only" : "All");
        params.put("GT_NET", gt[0]);
        params.put("GT_DOC_AMT", gt[1]);
        params.put("SUPPLIER_COUNT", supplierCount[0]);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    /** APRC03 doc-type expansion (GET-DOC-TYPE). */
    private static String reconDocTypeLabel(String t) {
        if (t == null) return "";
        return switch (t.trim()) {
            case "I" -> "INV"; case "C" -> "CR/N"; case "D" -> "DR/N"; case "V" -> "VOID";
            case "K" -> "CLAIM"; case "R" -> "CLREV"; case "B" -> "BAL"; case "P" -> "PAYMT";
            default  -> t.trim();
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    // APTL01 — Cash Requirements  (cobol/ap2/aptl01.pl → aptl02 detail / aptl03 summary)
    // ════════════════════════════════════════════════════════════════════════

    public record CashReqParams(
            String subLedger, String startSupplier, String endSupplier,
            LocalDate paymentDate, LocalDate nextPaymentDate,
            boolean onHold, boolean selected, boolean deductWithholding,
            boolean mustPay, boolean promptPay, boolean overdue, boolean due, boolean discAvail) {}

    /**
     * APTL01 — outstanding AP transactions due for payment, allocated into
     * must-pay / prompt-pay / overdue / now-due / disc-due buckets per
     * {@code TEST-RECORD-SELECTION-02} (5 allocation rules) + {@code CLASSIFY-AMT}
     * + {@code CHECK-FOR-DISCOUNT}. {@code summary=true} rolls up to one row per
     * supplier (APTL03); false = one row per transaction (APTL02).
     *
     * <p>Divergences from COBOL, both safe: the {@code appmdis} prompt-pay-date
     * OR-branches are omitted (that table's role is an edge-case refinement), and
     * supplier-type isn't available in the extract.
     */
    public Map<String, Object> getCashRequirementsData(AppSession s, CashReqParams p, boolean summary) {
        if (p.paymentDate() == null || p.nextPaymentDate() == null)
            return warn("Enter both a payment date and a next payment date.");
        if (!(p.mustPay() || p.promptPay() || p.overdue() || p.due() || p.discAvail()))
            return warn("Select at least one allocation rule (A–E) — otherwise only payments would list.");

        StringBuilder sql = new StringBuilder(
            "SELECT t.supplier_no, COALESCE(s.name_1,'') AS name_1, s.sub_ledger, s.always_take_disc_ind, " +
            "       t.doc_type, t.doc_no, t.doc_date, t.due_date, t.disc_1_date, t.disc_1_amt, " +
            "       t.disc_2_date, t.disc_2_amt, t.amt, t.retent_amt, t.amt_paid, t.disc_taken, " +
            "       t.for_curr_fluct_amt, t.hold_paymt_amt, t.must_pay_flag, t.prompt_pay_flag, t.trx_status, " +
            "       r.recon_no AS recon_found, r.gross_bal, r.outstanding_bal, r.claim_bal " +
            "FROM aptrans t " +
            "JOIN apsupps s ON s.company_no = t.company_no AND s.supplier_no = t.supplier_no " +
            "LEFT JOIN aprecon r ON r.company_no = t.company_no AND r.supplier_no = t.supplier_no AND r.recon_no = t.recon_no " +
            "WHERE t.company_no = ? AND TRIM(t.paid_flag) = '' AND TRIM(t.archive_flag) = '' " +
            "  AND t.doc_type <> 'J' AND s.acct_status NOT IN ('H','Y') ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.subLedger())) { sql.append(" AND s.sub_ledger = ? "); args.add(p.subLedger()); }
        if (notBlank(p.startSupplier())) {
            sql.append(" AND t.supplier_no BETWEEN ? AND ? ");
            args.add(p.startSupplier());
            args.add(notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz");
        }
        sql.append(p.onHold() ? " AND (t.trx_status='H' OR TRIM(t.trx_status)='') " : " AND TRIM(t.trx_status)='' ");
        if (!p.selected()) sql.append(" AND t.paymt_batch_no = 0 ");
        sql.append(" ORDER BY t.supplier_no, t.doc_date, t.doc_type, t.doc_no");

        LocalDate pay = p.paymentDate(), next = p.nextPaymentDate();
        List<Map<String, Object>> detailRows = new ArrayList<>();
        Map<String, Map<String, Object>> bySupplier = new LinkedHashMap<>();
        BigDecimal[] gt = new BigDecimal[6];   // mustPay, promptPay, overdue, nowDue, discDue, discAvail
        Arrays.fill(gt, BigDecimal.ZERO);
        String err = null;
        try {
            // Complex multi-join with dynamic status/selection filters and disc/due date logic — resultQuery
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                String docType = trim(r.get("doc_type", String.class));
                LocalDate dueDate = ldFromLocalDate(r.get("due_date", LocalDate.class));
                LocalDate disc1 = ldFromLocalDate(r.get("disc_1_date", LocalDate.class));
                LocalDate disc2 = ldFromLocalDate(r.get("disc_2_date", LocalDate.class));
                boolean mustPayTx = "Y".equalsIgnoreCase(trim(r.get("must_pay_flag", String.class)));
                boolean promptTx  = "Y".equalsIgnoreCase(trim(r.get("prompt_pay_flag", String.class)));

                BigDecimal amt = z(r.get("amt", BigDecimal.class)), retent = z(r.get("retent_amt", BigDecimal.class)),
                           amtPaid = z(r.get("amt_paid", BigDecimal.class)), discTaken = z(r.get("disc_taken", BigDecimal.class)),
                           fcFluct = z(r.get("for_curr_fluct_amt", BigDecimal.class)), holdAmt = z(r.get("hold_paymt_amt", BigDecimal.class));

                // CALC-TRX-BALANCE
                BigDecimal bal;
                if ("P".equals(docType)) {
                    bal = amt.add(discTaken).subtract(amtPaid);
                } else {
                    bal = amt.subtract(retent).subtract(amtPaid).subtract(discTaken).subtract(fcFluct);
                    if (p.deductWithholding()) bal = bal.subtract(holdAmt);
                }
                boolean reconBalanced = r.get("recon_found") != null
                        && z(r.get("gross_bal", BigDecimal.class)).signum() == 0
                        && z(r.get("outstanding_bal", BigDecimal.class)).signum() == 0
                        && z(r.get("claim_bal", BigDecimal.class)).signum() == 0;
                if (reconBalanced) bal = BigDecimal.ZERO;

                // TEST-RECORD-SELECTION-02 gate 6 (any enabled allocation rule)
                boolean inc = "P".equals(docType)
                    || (p.mustPay() && mustPayTx && before(dueDate, next))
                    || (p.promptPay() && promptTx && before(dueDate, next))
                    || (p.overdue() && before(dueDate, pay))
                    || (p.due() && !before(dueDate, pay) && before(dueDate, next))
                    || (p.discAvail() && after(dueDate, next)
                        && (inRange(disc1, pay, next) || inRange(disc2, pay, next)));
                if (!inc) return;

                // CHECK-FOR-DISCOUNT
                int alwaysTake = parseAlwaysTake(r.get("always_take_disc_ind", String.class));
                BigDecimal discAvailAmt = BigDecimal.ZERO;
                if (!"P".equals(docType)) {
                    if ((disc1 != null && !before(disc1, pay)) || alwaysTake == 1)
                        discAvailAmt = z(r.get("disc_1_amt", BigDecimal.class)).subtract(discTaken);
                    else if ((disc2 != null && !before(disc2, pay)) || alwaysTake == 2)
                        discAvailAmt = z(r.get("disc_2_amt", BigDecimal.class)).subtract(discTaken);
                }

                // CLASSIFY-AMT — assign the balance to exactly one bucket
                BigDecimal mustB = BigDecimal.ZERO, promptB = BigDecimal.ZERO, overB = BigDecimal.ZERO,
                           nowB = BigDecimal.ZERO, discB = BigDecimal.ZERO;
                if ("P".equals(docType)) {
                    overB = bal;                                // payments shown in overdue/credit
                } else if (mustPayTx && before(dueDate, next)) {
                    mustB = bal.subtract(discAvailAmt);
                } else if (promptTx && before(dueDate, next)) {
                    promptB = bal.subtract(discAvailAmt);
                } else if (before(dueDate, pay)) {
                    overB = bal.subtract(discAvailAmt);
                } else if (!before(dueDate, pay) && before(dueDate, next)) {
                    nowB = bal.subtract(discAvailAmt);
                } else if (after(dueDate, next) && (before(disc1, next) || before(disc2, next))) {
                    discB = bal.subtract(discAvailAmt);
                } else {
                    return;   // no bucket — not a cash requirement
                }

                BigDecimal total = mustB.add(promptB).add(overB).add(nowB).add(discB);
                if (total.signum() == 0 && discAvailAmt.signum() == 0) return;   // skip nil lines

                gt[0] = gt[0].add(mustB); gt[1] = gt[1].add(promptB); gt[2] = gt[2].add(overB);
                gt[3] = gt[3].add(nowB); gt[4] = gt[4].add(discB); gt[5] = gt[5].add(discAvailAmt);

                if (summary) {
                    Map<String, Object> a = bySupplier.computeIfAbsent(r.get("supplier_no", String.class), k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("supplierNo", k); m.put("name", null); m.put("subLedger", null);
                        for (String b : new String[]{"mustPay", "promptPay", "overdue", "nowDue", "discDue", "discAvail", "total"})
                            m.put(b, BigDecimal.ZERO);
                        return m;
                    });
                    a.put("name", r.get("name_1", String.class)); a.put("subLedger", r.get("sub_ledger", String.class));
                    acc(a, "mustPay", mustB); acc(a, "promptPay", promptB); acc(a, "overdue", overB);
                    acc(a, "nowDue", nowB); acc(a, "discDue", discB); acc(a, "discAvail", discAvailAmt);
                    acc(a, "total", total);
                } else {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("supplierNo", r.get("supplier_no", String.class));
                    row.put("name",       r.get("name_1", String.class));
                    row.put("subLedger",  r.get("sub_ledger", String.class));
                    row.put("docType",    docTypeLabel(docType));
                    row.put("docNo",      r.get("doc_no", String.class));
                    row.put("docDate",    ldSqlDate(r.get("doc_date", LocalDate.class)));
                    row.put("dueDate",    ldSqlDate(r.get("due_date", LocalDate.class)));
                    row.put("mustPay", mustB); row.put("promptPay", promptB); row.put("overdue", overB);
                    row.put("nowDue", nowB); row.put("discDue", discB); row.put("discAvail", discAvailAmt);
                    row.put("total", total);
                    row.put("onHold", "H".equals(trim(r.get("trx_status", String.class))) ? "HOLD" : "");
                    detailRows.add(row);
                }
            });
        } catch (Exception e) {
            log.error("getCashRequirementsData failed: {}", e.getMessage(), e);
            err = e.getMessage();
        }
        if (err != null) return warn("Query failed: " + err);

        List<Map<String, Object>> rows = summary ? new ArrayList<>(bySupplier.values()) : detailRows;
        if (rows.isEmpty()) return warn("No transactions matched the cash-requirements selection.");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("PAYMENT_DATE", pay.toString());
        params.put("NEXT_PAYMENT_DATE", next.toString());
        params.put("SUB_LEDGER_DESC", notBlank(p.subLedger()) ? p.subLedger() : "All sub ledgers");
        params.put("GT_MUST_PAY", gt[0]); params.put("GT_PROMPT_PAY", gt[1]); params.put("GT_OVERDUE", gt[2]);
        params.put("GT_NOW_DUE", gt[3]); params.put("GT_DISC_DUE", gt[4]); params.put("GT_DISC_AVAIL", gt[5]);
        params.put("GT_TOTAL", gt[0].add(gt[1]).add(gt[2]).add(gt[3]).add(gt[4]));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    private static int parseAlwaysTake(String s) {
        try { return Integer.parseInt(trim(s)); } catch (Exception e) { return 0; }
    }
    private static LocalDate ld(Date d) {
        if (d == null) return null;
        LocalDate v = d.toLocalDate();
        return v.isAfter(LocalDate.of(1900, 1, 1)) ? v : null;
    }
    private static boolean before(LocalDate a, LocalDate b) { return a != null && a.isBefore(b); }
    private static boolean after(LocalDate a, LocalDate b)  { return a != null && a.isAfter(b); }
    private static boolean inRange(LocalDate d, LocalDate lo, LocalDate hi) {
        return d != null && !d.isBefore(lo) && d.isBefore(hi);
    }

    // ════════════════════════════════════════════════════════════════════════
    // APRC11 — Foreign Currency Revaluation  (cobol/ap2/aprc11.cbl, aprc11s0.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record FcRevalParams(
            String printSeq, String startSupplier, String endSupplier, String subLedger,
            boolean invoices, boolean drNotes, boolean crNotes,
            String includeRecon,          // "R" / "U" / "A"
            String docPostInd,            // "D" / "P"
            LocalDate startDate, LocalDate endDate,
            LocalDate revalStartDate, LocalDate revalEndDate,
            String forCurrCode) {}

    /** True when the company is registered for AP foreign currency. */
    public boolean isForeignCurrencyEnabled(AppSession s) {
        return "Y".equalsIgnoreCase(scalarString(
            "SELECT ap_for_curr_flag FROM cpcoyco WHERE company_no=? LIMIT 1", s.getCompanyNo()));
    }

    /**
     * APRC11 — FC transactions with their revaluation gain/loss. The gain/loss is
     * a child row in {@code aprctrx} (one per revaluation event), joined to its
     * parent {@code aptrans} document; only base revaluations ({@code match_doc_type=''})
     * count. Ported from {@code TEST-RECORD-SELECTION-04} + {@code CHECK-FOR-REVALS}.
     *
     * <p><b>Divergences:</b> guard requires {@code cpcoyco.ap_for_curr_flag='Y'};
     * the reval-batch filter is dropped ({@code aprctrx} has no batch column in the
     * extract); FC-code validation uses {@code aptrans} values (no {@code cpfccod}).
     */
    public Map<String, Object> getFcRevaluationData(AppSession s, FcRevalParams p) {
        if (!isForeignCurrencyEnabled(s))
            return warn("Company is not registered for foreign currency.");

        List<String> types = new ArrayList<>();
        if (p.invoices()) types.add("I");
        if (p.drNotes())  types.add("D");
        if (p.crNotes())  types.add("C");
        if (types.isEmpty()) return warn("Select at least one document type (invoices / debit / credit notes).");

        boolean alpha = "A".equalsIgnoreCase(p.printSeq());
        boolean post  = "P".equalsIgnoreCase(p.docPostInd());
        String dateCol = post ? "t.posting_date" : "t.doc_date";
        String revalCol = post ? "x.match_posting_date" : "x.match_doc_date";
        String ub = p.includeRecon() == null ? "A" : p.includeRecon().toUpperCase();

        StringBuilder sql = new StringBuilder(
            "SELECT t.supplier_no, COALESCE(s.name_1,'') AS name_1, COALESCE(s.sub_ledger,'') AS sub_ledger, " +
            "       t.for_curr_code, t.doc_type, t.retent_flag, t.doc_no, t.doc_date, t.posting_date, " +
            "       t.seq_no, t.recon_no, t.amt, t.amt_orig, t.retent_amt, t.amt_paid, t.disc_taken, " +
            "       t.for_curr_amt, t.for_curr_fluct_amt, " +
            "       x.match_doc_date, x.match_posting_date, x.reval_amt, x.reval_curr_fluct_amt " +
            "FROM aptrans t " +
            "JOIN aprctrx x ON x.company_no=t.company_no AND x.supplier_no=t.supplier_no " +
            "   AND x.doc_date=t.doc_date AND x.doc_type=t.doc_type AND x.retent_flag=t.retent_flag " +
            "   AND x.doc_no=t.doc_no AND TRIM(x.match_doc_type)='' " +
            "JOIN apsupps s ON s.company_no=t.company_no AND s.supplier_no=t.supplier_no " +
            "LEFT JOIN aprecon r ON r.company_no=t.company_no AND r.supplier_no=t.supplier_no AND r.recon_no=t.recon_no " +
            "WHERE t.company_no=? AND TRIM(t.for_curr_code)<>'' AND t.trx_status<>'U' " +
            "  AND t.doc_type IN (" + qMarks(types.size()) + ") ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo()); args.addAll(types);

        if (notBlank(p.forCurrCode())) { sql.append(" AND t.for_curr_code=? "); args.add(p.forCurrCode()); }
        if (notBlank(p.subLedger()))   { sql.append(" AND s.sub_ledger=? ");    args.add(p.subLedger()); }
        if (notBlank(p.startSupplier())) {
            if (alpha) sql.append(" AND s.alpha_key BETWEEN ? AND ? ");
            else       sql.append(" AND t.supplier_no BETWEEN ? AND ? ");
            args.add(p.startSupplier());
            args.add(notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz");
        }
        if ("R".equals(ub))      sql.append(" AND t.recon_no>0 AND r.gross_bal=0 AND r.outstanding_bal=0 ");
        else if ("U".equals(ub)) sql.append(" AND (t.recon_no=0 OR r.gross_bal<>0 OR r.outstanding_bal<>0) ");
        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND ").append(dateCol).append(" BETWEEN ? AND ? ");
            args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(end));
        }
        if (p.revalStartDate() != null) {
            LocalDate end = p.revalEndDate() != null ? p.revalEndDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND ").append(revalCol).append(" BETWEEN ? AND ? ");
            args.add(Date.valueOf(p.revalStartDate())); args.add(Date.valueOf(end));
        }
        if (alpha) sql.append(" ORDER BY s.alpha_key, t.doc_date, t.doc_type, t.doc_no");
        else       sql.append(" ORDER BY t.supplier_no, t.doc_date, t.doc_type, t.doc_no");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] reval = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};  // inv,dr,cr,grand
        String err = null;
        try {
            // FC revaluation: multi-join with dynamic date column + supplier/recon/date filters — resultQuery
            dsl.resultQuery(sql.toString(), args.toArray()).fetch().forEach(r -> {
                String docType = trim(r.get("doc_type", String.class));
                BigDecimal amt = z(r.get("amt", BigDecimal.class)), retent = z(r.get("retent_amt", BigDecimal.class)),
                           amtPaid = z(r.get("amt_paid", BigDecimal.class)), disc = z(r.get("disc_taken", BigDecimal.class)),
                           fcFluct = z(r.get("for_curr_fluct_amt", BigDecimal.class)), revalAmt = z(r.get("reval_amt", BigDecimal.class));
                BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
                if ("I".equals(docType) || "D".equals(docType)) dr = amt.subtract(retent); else cr = amt.subtract(retent);
                BigDecimal trxBal = amt.subtract(retent).subtract(amtPaid).subtract(disc).subtract(fcFluct);

                switch (docType) {
                    case "I" -> reval[0] = reval[0].add(revalAmt);
                    case "D" -> reval[1] = reval[1].add(revalAmt);
                    case "C" -> reval[2] = reval[2].add(revalAmt);
                    default -> { }
                }
                reval[3] = reval[3].add(revalAmt);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("supplierNo", r.get("supplier_no", String.class));
                row.put("name",       r.get("name_1", String.class));
                row.put("subLedger",  r.get("sub_ledger", String.class));
                row.put("forCurrCode", r.get("for_curr_code", String.class));
                row.put("docType",    docTypeLabel(docType));
                row.put("retentFlag", r.get("retent_flag", String.class));
                row.put("docNo",      r.get("doc_no", String.class));
                row.put("seqNo",      zeroIfNull(r.get("seq_no", Integer.class)));
                row.put("docDate",    ldSqlDate(r.get("doc_date", LocalDate.class)));
                row.put("postDate",   ldSqlDate(r.get("posting_date", LocalDate.class)));
                row.put("reconNo",    zeroIfNull(r.get("recon_no", Integer.class)));
                row.put("docAmt",     z(r.get("amt_orig", BigDecimal.class)));
                row.put("retentAmt",  retent);
                row.put("drAmt", dr); row.put("crAmt", cr);
                row.put("amtPaid", amtPaid); row.put("discTaken", disc);
                row.put("trxBal", trxBal);
                row.put("fcAmt",      z(r.get("for_curr_amt", BigDecimal.class)));
                row.put("revalDate",  ldSqlDate(r.get(post ? "match_posting_date" : "match_doc_date", LocalDate.class)));
                row.put("revalAmt",   revalAmt);
                row.put("revalFluct", z(r.get("reval_curr_fluct_amt", BigDecimal.class)));
                rows.add(row);
            });
        } catch (Exception e) {
            log.error("getFcRevaluationData failed: {}", e.getMessage(), e);
            err = e.getMessage();
        }
        if (err != null) return warn("Query failed: " + err);
        if (rows.isEmpty()) return warn("No foreign-currency revaluations matched the selection.");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("PRINT_SEQ_DESC", alpha ? "Alpha key" : "Supplier number");
        params.put("DATE_BASIS_DESC", post ? "Posting date" : "Document date");
        params.put("FC_CODE_DESC", notBlank(p.forCurrCode()) ? p.forCurrCode() : "All currencies");
        params.put("REVAL_INV", reval[0]); params.put("REVAL_DR", reval[1]);
        params.put("REVAL_CR", reval[2]);  params.put("REVAL_GRAND", reval[3]);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", rows.size());
        return result;
    }

    /** Distinct FC codes present in aptrans — feeds the APRC11 currency picker (cpfccod isn't extracted). */
    public List<String> getForeignCurrencyCodes(AppSession s) {
        try {
            return dsl.selectDistinct(APTRANS.FOR_CURR_CODE)
                      .from(APTRANS)
                      .where(APTRANS.COMPANY_NO.eq(s.getCompanyNo())
                          .and(DSL.trim(APTRANS.FOR_CURR_CODE).ne("")))
                      .orderBy(APTRANS.FOR_CURR_CODE)
                      .fetch(APTRANS.FOR_CURR_CODE);
        } catch (Exception e) { return List.of(); }
    }

    // ════════════════════════════════════════════════════════════════════════
    // APTL10 — Supplier Analysis  (cobol/ap2/aptl10.cbl) — Excel-only
    // ════════════════════════════════════════════════════════════════════════

    public record SupplierAnalysisParams(
            String startSupplier, String endSupplier, String startSubLedger, String endSubLedger,
            int noOfMonths, LocalDate endDate, int topNumber, String basedOn) {}   // basedOn AN/AV/TN/TV

    /** One supplier's analysis row. monthNo/monthValue are indexed oldest→newest. */
    public record SupplierAnalysis(
            String supplierNo, String name, String subLedger, String city, String state,
            String postcode, String country, String forCurrCode,
            int totalNo, BigDecimal totalValue, int avgNo, BigDecimal avgValue,
            BigDecimal valuePct, BigDecimal noPct,
            BigDecimal highVal, int highNo, BigDecimal lowVal, int lowNo,
            int[] monthNo, BigDecimal[] monthValue) {}

    public record SupplierAnalysisResult(
            List<LocalDate> monthEnds, List<SupplierAnalysis> suppliers,
            int grandNo, BigDecimal grandValue, int[] grandMonthNo, BigDecimal[] grandMonthValue,
            String warning) {}

    /**
     * APTL10 — N-month transaction analysis per supplier (count + value per month,
     * totals, averages, highest/lowest month) with top-N ranking by the chosen
     * metric. Ported from {@code GET-NEXT-TRX} (doc_type I/D/C, trx_status≠U,
     * doc_date buckets) + {@code CALC-AVERAGES-ETC} + the sort-value ranking.
     * Suppliers with no transactions or total value &lt; 0.01 are excluded
     * (hard-coded in COBOL — there are no Include/Exclude toggles). Excel-only.
     */
    public SupplierAnalysisResult getSupplierAnalysis(AppSession s, SupplierAnalysisParams p) {
        int n = Math.max(1, Math.min(60, p.noOfMonths()));
        LocalDate end = p.endDate();
        List<LocalDate> monthEnds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocalDate m = end.minusMonths(n - 1 - i);
            monthEnds.add(m.withDayOfMonth(m.lengthOfMonth()));
        }
        LocalDate reportStart = end.minusMonths(n - 1).withDayOfMonth(1);

        // suppliers in range
        Map<String, SupAcc> accs = new LinkedHashMap<>();
        try {
            Condition where = APSUPPS.COMPANY_NO.eq(s.getCompanyNo());
            if (notBlank(p.startSupplier()))
                where = where.and(APSUPPS.SUPPLIER_NO.between(p.startSupplier(),
                    notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz"));
            if (notBlank(p.startSubLedger()))
                where = where.and(APSUPPS.SUB_LEDGER.between(p.startSubLedger(),
                    notBlank(p.endSubLedger()) ? p.endSubLedger() : "zzzz"));
            dsl.select(APSUPPS.SUPPLIER_NO, APSUPPS.NAME_1, APSUPPS.SUB_LEDGER,
                       APSUPPS.CITY, APSUPPS.STATE, APSUPPS.POSTCODE, APSUPPS.COUNTRY, APSUPPS.FOR_CURR_CODE)
               .from(APSUPPS)
               .where(where)
               .orderBy(APSUPPS.SUPPLIER_NO)
               .fetch()
               .forEach(r -> {
                   SupAcc a = new SupAcc(n);
                   a.supplierNo = r.get(APSUPPS.SUPPLIER_NO); a.name = r.get(APSUPPS.NAME_1);
                   a.subLedger = r.get(APSUPPS.SUB_LEDGER); a.city = r.get(APSUPPS.CITY);
                   a.state = r.get(APSUPPS.STATE); a.postcode = r.get(APSUPPS.POSTCODE);
                   a.country = r.get(APSUPPS.COUNTRY); a.forCurrCode = r.get(APSUPPS.FOR_CURR_CODE);
                   accs.put(a.supplierNo, a);
               });
        } catch (Exception e) {
            return new SupplierAnalysisResult(monthEnds, List.of(), 0, BigDecimal.ZERO,
                new int[n], zeros(n), "Supplier lookup failed: " + e.getMessage());
        }
        if (accs.isEmpty())
            return new SupplierAnalysisResult(monthEnds, List.of(), 0, BigDecimal.ZERO,
                new int[n], zeros(n), "No suppliers in the selected range.");

        // transactions, bucketed per supplier per month
        try {
            dsl.select(APTRANS.SUPPLIER_NO, APTRANS.DOC_DATE, APTRANS.AMT)
               .from(APTRANS)
               .where(APTRANS.COMPANY_NO.eq(s.getCompanyNo())
                   .and(APTRANS.DOC_TYPE.in("I", "D", "C"))
                   .and(APTRANS.TRX_STATUS.ne("U"))
                   .and(APTRANS.DOC_DATE.between(reportStart, end)))
               .fetch()
               .forEach(r -> {
                   SupAcc a = accs.get(r.get(APTRANS.SUPPLIER_NO));
                   if (a == null) return;
                   LocalDate dd = r.get(APTRANS.DOC_DATE);
                   int idx = -1;
                   for (int i = 0; i < monthEnds.size(); i++) {
                       if (!dd.isAfter(monthEnds.get(i))) { idx = i; break; }
                   }
                   if (idx < 0) return;
                   BigDecimal amt = z(r.get(APTRANS.AMT));
                   a.totalNo++; a.totalValue = a.totalValue.add(amt);
                   a.monthNo[idx]++; a.monthValue[idx] = a.monthValue[idx].add(amt);
               });
        } catch (Exception e) {
            return new SupplierAnalysisResult(monthEnds, List.of(), 0, BigDecimal.ZERO,
                new int[n], zeros(n), "Transaction query failed: " + e.getMessage());
        }

        // grand totals over kept suppliers
        int grandNo = 0; BigDecimal grandValue = BigDecimal.ZERO;
        int[] grandMonthNo = new int[n]; BigDecimal[] grandMonthValue = zeros(n);
        List<SupAcc> kept = new ArrayList<>();
        for (SupAcc a : accs.values()) {
            if (a.totalNo == 0 || a.totalValue.abs().compareTo(new BigDecimal("0.01")) < 0) continue;  // COBOL exclusion
            kept.add(a);
            grandNo += a.totalNo; grandValue = grandValue.add(a.totalValue);
            for (int i = 0; i < n; i++) { grandMonthNo[i] += a.monthNo[i]; grandMonthValue[i] = grandMonthValue[i].add(a.monthValue[i]); }
        }
        if (kept.isEmpty())
            return new SupplierAnalysisResult(monthEnds, List.of(), 0, BigDecimal.ZERO,
                new int[n], zeros(n), "No suppliers had transactions in the period.");

        String basedOn = p.basedOn() == null ? "TV" : p.basedOn().toUpperCase();
        boolean countMetric = basedOn.equals("AN") || basedOn.equals("TN");
        BigDecimal gv = grandValue.signum() == 0 ? BigDecimal.ONE : grandValue;
        int gn = grandNo == 0 ? 1 : grandNo;

        List<SupplierAnalysis> list = new ArrayList<>();
        for (SupAcc a : kept) {
            int avgNo = a.totalNo / n;
            BigDecimal avgValue = a.totalValue.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
            BigDecimal valuePct = a.totalValue.multiply(BigDecimal.valueOf(100)).divide(gv, 2, RoundingMode.HALF_UP);
            BigDecimal noPct = BigDecimal.valueOf(a.totalNo).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(gn), 2, RoundingMode.HALF_UP);
            // highest / lowest month by the chosen metric
            BigDecimal highVal = BigDecimal.ZERO, lowVal = null; int highNo = 0, lowNo = 0;
            BigDecimal bestMetric = null, worstMetric = null;
            for (int i = 0; i < n; i++) {
                BigDecimal metric = countMetric ? BigDecimal.valueOf(a.monthNo[i]) : a.monthValue[i];
                if (bestMetric == null || metric.compareTo(bestMetric) > 0) { bestMetric = metric; highVal = a.monthValue[i]; highNo = a.monthNo[i]; }
                if (worstMetric == null || metric.compareTo(worstMetric) < 0) { worstMetric = metric; lowVal = a.monthValue[i]; lowNo = a.monthNo[i]; }
            }
            list.add(new SupplierAnalysis(a.supplierNo, a.name, a.subLedger, a.city, a.state, a.postcode,
                a.country, a.forCurrCode, a.totalNo, a.totalValue, avgNo, avgValue, valuePct, noPct,
                highVal, highNo, lowVal == null ? BigDecimal.ZERO : lowVal, lowNo, a.monthNo, a.monthValue));
        }

        // rank by chosen metric desc, then supplier_no
        java.util.Comparator<SupplierAnalysis> cmp = switch (basedOn) {
            case "AN" -> java.util.Comparator.comparingInt(SupplierAnalysis::avgNo).reversed();
            case "TN" -> java.util.Comparator.comparingInt(SupplierAnalysis::totalNo).reversed();
            case "AV" -> java.util.Comparator.comparing(SupplierAnalysis::avgValue).reversed();
            default   -> java.util.Comparator.comparing(SupplierAnalysis::totalValue).reversed();
        };
        list.sort(cmp.thenComparing(SupplierAnalysis::supplierNo));
        int top = p.topNumber() > 0 ? p.topNumber() : Integer.MAX_VALUE;
        if (list.size() > top) list = new ArrayList<>(list.subList(0, top));

        return new SupplierAnalysisResult(monthEnds, list, grandNo, grandValue, grandMonthNo, grandMonthValue, null);
    }

    /** Mutable per-supplier accumulator for APTL10. */
    private static final class SupAcc {
        String supplierNo, name, subLedger, city, state, postcode, country, forCurrCode;
        int totalNo = 0; BigDecimal totalValue = BigDecimal.ZERO;
        final int[] monthNo; final BigDecimal[] monthValue;
        SupAcc(int n) { monthNo = new int[n]; monthValue = zeros(n); }
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    // ════════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Line-only bean keys, pre-seeded null on transaction rows for a uniform shape. */
    private static final String[] LINE_FIELDS = {
        "lineNo", "lineType", "glAcctMain", "glAcctSub", "qty", "unitPer", "unitCost",
        "amtExTax", "taxCode", "lineTaxAmt", "amtIncTax", "fcAmt", "description", "reference"
    };

    /** Header detail columns repeated on every Excel distribution-line row. */
    private static final String[] HEADER_DETAIL_FIELDS = {
        "rowType", "suppNo", "name", "subLedger", "subLedgerName",
        "docDate", "postDate", "auditDate", "docType", "retentFlag", "docNo", "seqNo",
        "paidFlag", "paidDocDate", "paidPostDate", "batchNo", "reconNo", "archiveFlag",
        "auditUser", "auditTime", "poNo"
    };
    /** Header amount columns shown only on the first Excel distribution-line row. */
    private static final String[] HEADER_AMOUNT_FIELDS = {
        "amt", "retentAmt", "debit", "credit", "amtPaid", "discTaken", "balance", "taxAmt"
    };

    /** GET-DOC-TYPE accumulators (per-type counts/amounts + grand totals). */
    private static final class Totals {
        int invNo, drNo, crNo, payNo, voidNo, balNo, grandNo;
        BigDecimal invAmt = BigDecimal.ZERO, drAmt = BigDecimal.ZERO, crAmt = BigDecimal.ZERO,
                   payAmt = BigDecimal.ZERO, voidAmt = BigDecimal.ZERO, balAmt = BigDecimal.ZERO,
                   retentAmt = BigDecimal.ZERO, discTaken = BigDecimal.ZERO,
                   grandAmt = BigDecimal.ZERO, grand = BigDecimal.ZERO;

        void add(String docType, BigDecimal amt, BigDecimal retent, BigDecimal disc) {
            switch (docType) {
                case "I" -> { invNo++; invAmt = invAmt.add(amt); retentAmt = retentAmt.add(retent);
                              grandAmt = grandAmt.add(amt); grand = grand.add(amt).subtract(retent); grandNo++; }
                case "D" -> { drNo++;  drAmt = drAmt.add(amt);  retentAmt = retentAmt.add(retent);
                              grandAmt = grandAmt.add(amt); grand = grand.add(amt).subtract(retent); grandNo++; }
                case "C" -> { crNo++;  crAmt = crAmt.add(amt);  retentAmt = retentAmt.add(retent);
                              grandAmt = grandAmt.add(amt); grand = grand.add(amt).subtract(retent); grandNo++; }
                case "P" -> { payNo++; payAmt = payAmt.add(amt); discTaken = discTaken.add(disc);
                              grandAmt = grandAmt.add(amt); grand = grand.add(amt).add(disc); grandNo++; }
                case "V" -> { voidNo++; voidAmt = voidAmt.add(amt);
                              grandAmt = grandAmt.add(amt); grand = grand.add(amt); grandNo++; }
                case "B" -> { balNo++; balAmt = balAmt.add(amt);
                              grandAmt = grandAmt.add(amt); grand = grand.add(amt); grandNo++; }
                default  -> { /* K (claim) and R (reversal) carry no totals — per GET-DOC-TYPE */ }
            }
        }

        Map<String, Object> toParams() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("T_INV_NO", invNo);   m.put("T_INV_AMT", invAmt);
            m.put("T_DR_NO", drNo);     m.put("T_DR_AMT", drAmt);
            m.put("T_CR_NO", crNo);     m.put("T_CR_AMT", crAmt);
            m.put("T_PAY_NO", payNo);   m.put("T_PAY_AMT", payAmt);  m.put("T_PAY_DISC", discTaken);
            m.put("T_VOID_NO", voidNo); m.put("T_VOID_AMT", voidAmt);
            m.put("T_BAL_NO", balNo);   m.put("T_BAL_AMT", balAmt);
            m.put("T_RETENT_AMT", retentAmt);
            m.put("GRAND_NO", grandNo); m.put("GRAND_AMT", grandAmt); m.put("GRAND_TOTAL", grand);
            return m;
        }
    }

    private static String docTypeLabel(String t) {
        if (t == null) return "";
        return switch (t.trim()) {
            case "I" -> "INV"; case "D" -> "DR";  case "C" -> "CR";  case "P" -> "PAY";
            case "V" -> "VOID"; case "B" -> "BAL"; case "K" -> "CLM"; case "R" -> "REV";
            default  -> t.trim();
        };
    }

    private static String rawDocType(Object label) {
        return switch (String.valueOf(label)) {
            case "INV" -> "I"; case "DR" -> "D"; case "CR" -> "C"; case "PAY" -> "P";
            case "VOID" -> "V"; case "BAL" -> "B"; case "CLM" -> "K"; case "REV" -> "R";
            default -> String.valueOf(label);
        };
    }

    /** SET-SEQ-NO: 0 → STAND (standing) / UNPOST; else the number. */
    private static String seqDisplay(int seqNo, String standingFlag) {
        if (seqNo == 0) return "Y".equalsIgnoreCase(trim(standingFlag)) ? "STAND" : "UNPOST";
        return String.valueOf(seqNo);
    }

    /** Convert sentinel "no date" (<= 1900-01-01) to null so it renders blank. */
    private static Date sqlDate(Date d) {
        if (d == null) return null;
        return d.toLocalDate().isAfter(LocalDate.of(1900, 1, 1)) ? d : null;
    }

    /** jOOQ returns LocalDate from DATE columns — convert to java.sql.Date for Jasper, suppressing sentinels. */
    private static Date ldSqlDate(LocalDate ld) {
        if (ld == null) return null;
        return ld.isAfter(LocalDate.of(1900, 1, 1)) ? Date.valueOf(ld) : null;
    }

    /** jOOQ returns null for sentinel dates; return null so Jasper renders blank. */
    private static LocalDate ldFromLocalDate(LocalDate ld) {
        if (ld == null) return null;
        return ld.isAfter(LocalDate.of(1900, 1, 1)) ? ld : null;
    }

    private static int zeroIfNull(Integer v) { return v != null ? v : 0; }

    /** Single-value query returning null on any error (used for optional config flags). */
    private String scalarString(String sql, Object... args) {
        try {
            return dsl.resultQuery(sql, args).fetchOne(0, String.class);
        } catch (Exception e) { return null; }
    }

    private Map<String, Object> warn(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", new ArrayList<>());
        m.put("params", new LinkedHashMap<>());
        m.put("rowCount", 0);
        m.put("warning", msg);
        return m;
    }

    private static boolean yes(String s)      { return "Y".equalsIgnoreCase(trim(s)); }
    private static boolean blank(String s)    { return s == null || s.trim().isEmpty(); }
    private static boolean notBlank(String s) { return !blank(s); }
    private static String  trim(String s)     { return s == null ? "" : s.trim(); }
    private static BigDecimal z(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static String qMarks(int n)       { return String.join(",", Collections.nCopies(n, "?")); }
}
