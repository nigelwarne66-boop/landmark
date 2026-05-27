package com.landmarksoftware.service.cm;

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
 * Cash Management (cashbook) <b>report</b> data service — one query method per CM
 * report card in the JavaFX Reports Hub ({@code -Preporting} build).
 *
 * <p>All JDBC lives here; controllers stay pure JavaFX. Every method is a port of
 * the matching COBOL/Perl in {@code C:\landmark\cobol\cm2}, with column names
 * verified against the live {@code lmextract} schema.
 *
 * <p>CM doc_type convention: 1 = deposit, 2 = bank credit, 3 = cheque, 4 = bank
 * debit. Receipts (1/2) are positive to the cashbook; payments (3/4) negative.
 */
@Service
public class CmReportDataService {

    private static final Logger log = LoggerFactory.getLogger(CmReportDataService.class);
    private final JdbcTemplate jdbc;

    public CmReportDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ── Picker lookups ────────────────────────────────────────────────────────

    /** A selectable code with a display label; {@code toString()} drives ComboBox rendering. */
    public record CodeName(String code, String label) {
        @Override public String toString() { return label; }
    }

    /** Banks for the company (bank code is mandatory on every CM report). */
    public List<CodeName> getBanks(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        try {
            jdbc.query("SELECT bank_code, name1 FROM cmbanks WHERE company_no=? ORDER BY bank_code",
                rs -> { list.add(new CodeName(trim(rs.getString("bank_code")),
                                              trim(rs.getString("bank_code")) + " — " + trim(rs.getString("name1")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getBanks: {}", e.getMessage()); }
        return list;
    }

    /** Reconciliation numbers for a bank, "(All)" first, newest first. */
    public List<CodeName> getReconNumbers(AppSession s, String bankCode) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All reconciliations)"));
        if (notBlank(bankCode)) {
            try {
                jdbc.query("SELECT recon_no, stmnt_close_date FROM cmrched WHERE company_no=? AND bank_code=? ORDER BY recon_no DESC",
                    rs -> { LocalDate d = ld(rs.getDate("stmnt_close_date"));
                            list.add(new CodeName(String.valueOf(rs.getInt("recon_no")),
                                    rs.getInt("recon_no") + (d != null ? " — " + d : ""))); },
                    s.getCompanyNo(), bankCode);
            } catch (Exception e) { log.warn("getReconNumbers: {}", e.getMessage()); }
        }
        return list;
    }

    /** Financial GL accounts, "(All)" first, "main-sub — desc" from glchart. */
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

    /** Distinct GL main account numbers, "(All)" first (for the CMTL14 main-account range). */
    public List<CodeName> getGlMainAccounts(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All GL mains)"));
        try {
            jdbc.query("SELECT DISTINCT acct_main_no FROM glchart WHERE company_no=? ORDER BY acct_main_no",
                rs -> { int m = rs.getInt("acct_main_no"); list.add(new CodeName(String.valueOf(m), String.valueOf(m))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getGlMainAccounts: {}", e.getMessage()); }
        return list;
    }

    /** GST/tax codes, "(All)" first, "code — desc" from cpgstcd. */
    public List<CodeName> getTaxCodes(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All tax codes)"));
        try {
            jdbc.query("SELECT gst_code, gst_desc FROM cpgstcd WHERE company_no=? ORDER BY gst_code",
                rs -> { String c = trim(rs.getString("gst_code")); if (!c.isEmpty())
                            list.add(new CodeName(c, c + " — " + trim(rs.getString("gst_desc")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getTaxCodes: {}", e.getMessage()); }
        return list;
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL10 — Cashbook Transactions  (cobol/cm2/cmtl10.pl)
    // ════════════════════════════════════════════════════════════════════════

    /** Selection — CMTL10S0. */
    public record CashbookTxnParams(
            String bankCode,            // mandatory
            LocalDate startDate, LocalDate endDate,
            boolean inclDeposits,       // doc_type 1
            boolean inclBankCredits,    // doc_type 2
            boolean inclCheques,        // doc_type 3
            boolean inclBankDebits,     // doc_type 4
            int startDocNo, int endDocNo,
            boolean unreconciledOnly,   // recon_no = 0 only
            String reconNo,             // blank = all; else exact recon_no
            String printSeq,            // "D" date | "N" doc number
            String detailSummary        // "D" detail | "S" summary
    ) {}

    /**
     * CMTL10 — cashbook transaction listing for a bank. doc_type drives both the
     * include filters and the sign (1/2 receipts +, 3/4 payments −). Optional
     * reconciliation filter and date/doc-no ranges; print-by date or doc number.
     */
    public Map<String, Object> getCashbookTransactions(AppSession s, CashbookTxnParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");

        List<String> types = new ArrayList<>();
        if (p.inclDeposits())    types.add("1");
        if (p.inclBankCredits()) types.add("2");
        if (p.inclCheques())     types.add("3");
        if (p.inclBankDebits())  types.add("4");
        if (types.isEmpty()) return warn("Select at least one transaction type (deposits / credits / cheques / debits).");

        StringBuilder sql = new StringBuilder(
            "SELECT t.doc_date, t.doc_type, t.doc_no, t.amt, t.trx_status, t.recon_no, " +
            "       t.system_id, t.ref, t.payee_1, t.payee_2 " +
            "FROM cmtrans t WHERE t.company_no=? AND t.bank_code=? " +
            "  AND t.doc_type IN (" + qMarks(types.size()) + ") ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo()); args.add(p.bankCode()); args.addAll(types);

        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            sql.append(" AND t.doc_date BETWEEN ? AND ? "); args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(end));
        }
        if (p.startDocNo() > 0) {
            int end = p.endDocNo() > 0 ? p.endDocNo() : 99999999;
            sql.append(" AND t.doc_no BETWEEN ? AND ? "); args.add(p.startDocNo()); args.add(end);
        }
        if (p.unreconciledOnly())          { sql.append(" AND t.recon_no = 0 "); }
        else if (notBlank(p.reconNo()))    { sql.append(" AND t.recon_no = ? "); args.add(Integer.parseInt(p.reconNo())); }

        sql.append("N".equalsIgnoreCase(p.printSeq())
            ? " ORDER BY t.doc_type, t.doc_no, t.doc_date " : " ORDER BY t.doc_date, t.doc_type, t.doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };   // receipts, payments
        int[] count = { 0 };
        try {
            jdbc.query(sql.toString(), rs -> {
                String dt = trim(rs.getString("doc_type"));
                BigDecimal amt = z(rs.getBigDecimal("amt"));
                boolean receipt = "1".equals(dt) || "2".equals(dt);
                BigDecimal signed = receipt ? amt : amt.negate();
                if (receipt) tot[0] = tot[0].add(amt); else tot[1] = tot[1].add(amt);
                count[0]++;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("docDate",  sqlDate(rs.getDate("doc_date")));
                row.put("docType",  cmDocType(dt));
                row.put("docNo",    rs.getInt("doc_no"));
                row.put("payee",    payee(rs.getString("payee_1"), rs.getString("payee_2")));
                row.put("reference", rs.getString("ref"));
                row.put("systemId", trim(rs.getString("system_id")));
                row.put("amount",   signed);
                row.put("reconNo",  rs.getInt("recon_no"));
                row.put("status",   trxStatus(rs.getString("trx_status")));
                rows.add(row);
            }, args.toArray());
        } catch (Exception e) {
            log.error("getCashbookTransactions: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", p.startDate() != null
            ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SEQ_DESC", "N".equalsIgnoreCase(p.printSeq()) ? "Document number" : "Date");
        params.put("SUMMARY_MODE", "S".equalsIgnoreCase(p.detailSummary()));
        params.put("SUM_RECEIPTS", tot[0]);
        params.put("SUM_PAYMENTS", tot[1]);
        params.put("SUM_NET", tot[0].subtract(tot[1]));
        params.put("ROW_COUNT", count[0]);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", count[0]);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL08 — Receipt Listing  (cobol/cm2/cmtl08.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record ReceiptListingParams(
            String bankCode, int startDocNo, int endDocNo,
            LocalDate startDate, LocalDate endDate, boolean includeCancelled) {}

    /** CMTL08 — receipts (cmcbtrx trx_type='R') for a bank, with receipt-type and status. */
    public Map<String, Object> getReceiptListing(AppSession s, ReceiptListingParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");
        StringBuilder sql = new StringBuilder(
            "SELECT doc_no, doc_date, recpt_type, amt, trx_status, ref_1, recvd_from " +
            "FROM cmcbtrx WHERE company_no=? AND bank_code=? AND trx_type='R' ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo()); args.add(p.bankCode());
        if (p.startDocNo() > 0) { int e = p.endDocNo() > 0 ? p.endDocNo() : 99999999;
            sql.append(" AND doc_no BETWEEN ? AND ? "); args.add(p.startDocNo()); args.add(e); }
        if (p.startDate() != null) { LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999,12,31);
            sql.append(" AND doc_date BETWEEN ? AND ? "); args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(e)); }
        if (!p.includeCancelled()) sql.append(" AND trx_status <> 'C' ");
        sql.append(" ORDER BY doc_date, doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal amt = z(rs.getBigDecimal("amt")); tot[0] = tot[0].add(amt);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("docNo", rs.getInt("doc_no"));
                r.put("docDate", sqlDate(rs.getDate("doc_date")));
                r.put("recptType", recptType(rs.getString("recpt_type")));
                r.put("receivedFrom", trim(rs.getString("recvd_from")));
                r.put("reference", rs.getString("ref_1"));
                r.put("amount", amt);
                r.put("status", trxStatus(rs.getString("trx_status")));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getReceiptListing: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_AMT", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL35 — Cashbook by Type  (cobol/cm2/cmtl35.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record CashbookByTypeParams(String bankCode, LocalDate startDate, LocalDate endDate) {}

    /** CMTL35 — cashbook transactions grouped by document type, with per-type subtotals. */
    public Map<String, Object> getCashbookByType(AppSession s, CashbookByTypeParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");
        StringBuilder sql = new StringBuilder(
            "SELECT doc_type, doc_date, doc_no, payee_1, payee_2, ref, amt FROM cmtrans " +
            "WHERE company_no=? AND bank_code=? AND doc_type IN ('1','2','3','4') ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo()); args.add(p.bankCode());
        if (p.startDate() != null) { LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999,12,31);
            sql.append(" AND doc_date BETWEEN ? AND ? "); args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(e)); }
        sql.append(" ORDER BY doc_type, doc_date, doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                String dt = trim(rs.getString("doc_type"));
                BigDecimal amt = z(rs.getBigDecimal("amt"));
                BigDecimal signed = ("1".equals(dt) || "2".equals(dt)) ? amt : amt.negate();
                tot[0] = tot[0].add(signed);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("docTypeCode", dt);
                r.put("docType", cmDocType(dt));
                r.put("docDate", sqlDate(rs.getDate("doc_date")));
                r.put("docNo", rs.getInt("doc_no"));
                r.put("payee", payee(rs.getString("payee_1"), rs.getString("payee_2")));
                r.put("reference", rs.getString("ref"));
                r.put("amount", signed);
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getCashbookByType: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_NET", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMCB02 — Cashbook Listing  (cobol/cm2/cmcb02.pl) — document-level
    // ════════════════════════════════════════════════════════════════════════

    public record CashbookListingParams(String bankCode, LocalDate startDate, LocalDate endDate, String trxType) {}

    /** CMCB02 — cashbook documents (cmcbdis aggregated by document), receipts/payments/both. */
    public Map<String, Object> getCashbookListing(AppSession s, CashbookListingParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");
        StringBuilder sql = new StringBuilder(
            "SELECT doc_type, doc_no, doc_date, MAX(payee_name_1) AS payee, SUM(amt) AS doc_amt, COUNT(*) AS lines " +
            "FROM cmcbdis WHERE company_no=? AND bank_code=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo()); args.add(p.bankCode());
        if (p.startDate() != null) { LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999,12,31);
            sql.append(" AND doc_date BETWEEN ? AND ? "); args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(e)); }
        if ("R".equalsIgnoreCase(p.trxType()))      sql.append(" AND doc_type < '3' ");
        else if ("P".equalsIgnoreCase(p.trxType())) sql.append(" AND doc_type >= '3' ");
        sql.append(" GROUP BY doc_type, doc_no, doc_date ORDER BY doc_type, doc_date, doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                String dt = trim(rs.getString("doc_type"));
                BigDecimal amt = z(rs.getBigDecimal("doc_amt"));
                BigDecimal signed = ("1".equals(dt) || "2".equals(dt)) ? amt : amt.negate();
                tot[0] = tot[0].add(signed);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("docType", cmDocType(dt));
                r.put("docNo", rs.getInt("doc_no"));
                r.put("docDate", sqlDate(rs.getDate("doc_date")));
                r.put("payee", trim(rs.getString("payee")));
                r.put("lines", rs.getInt("lines"));
                r.put("amount", signed);
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getCashbookListing: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("TRX_TYPE_DESC", "R".equalsIgnoreCase(p.trxType()) ? "Receipts" : "P".equalsIgnoreCase(p.trxType()) ? "Payments" : "Receipts & payments");
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_NET", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL14 — Cashbook Distributions  (cobol/cm2/cmtl14.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record CashbookDistParams(
            String bankCode, LocalDate startDate, LocalDate endDate,
            boolean inclDeposits, boolean inclBankCredits, boolean inclCheques, boolean inclBankDebits,
            String startGlMain, String endGlMain, String taxCode, String printSeq) {}

    /** CMTL14 — cashbook distribution lines with GL account + tax, GL-main and tax-code filters. */
    public Map<String, Object> getCashbookDistributions(AppSession s, CashbookDistParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");
        List<String> types = new ArrayList<>();
        if (p.inclDeposits()) types.add("1");
        if (p.inclBankCredits()) types.add("2");
        if (p.inclCheques()) types.add("3");
        if (p.inclBankDebits()) types.add("4");
        if (types.isEmpty()) return warn("Select at least one transaction type.");

        StringBuilder sql = new StringBuilder(
            "SELECT d.doc_date, d.doc_type, d.doc_no, d.seq_no, d.gl_acct_main, d.gl_acct_sub, " +
            "       COALESCE(g.desc1,'') AS gl_desc, d.payee_name_1, d.ref_1, d.amt, d.tax_code, d.tax_amt, " +
            "       d.system_id, d.batch_no, d.bas_group " +
            "FROM cmcbdis d LEFT JOIN glchart g ON g.company_no=d.company_no AND g.acct_main_no=d.gl_acct_main AND g.acct_sub_no=d.gl_acct_sub " +
            "WHERE d.company_no=? AND d.bank_code=? AND d.doc_type IN (" + qMarks(types.size()) + ") ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo()); args.add(p.bankCode()); args.addAll(types);
        if (p.startDate() != null) { LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999,12,31);
            sql.append(" AND d.doc_date BETWEEN ? AND ? "); args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(e)); }
        if (notBlank(p.startGlMain())) { String e = notBlank(p.endGlMain()) ? p.endGlMain() : "999999";
            sql.append(" AND d.gl_acct_main BETWEEN ? AND ? "); args.add(Integer.parseInt(p.startGlMain())); args.add(Integer.parseInt(e)); }
        if (notBlank(p.taxCode())) { sql.append(" AND d.tax_code = ? "); args.add(p.taxCode()); }
        sql.append("N".equalsIgnoreCase(p.printSeq()) ? " ORDER BY d.doc_type, d.doc_no, d.seq_no " : " ORDER BY d.doc_date, d.doc_type, d.doc_no, d.seq_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal amt = z(rs.getBigDecimal("amt"));
                BigDecimal tax = z(rs.getBigDecimal("tax_amt"));
                if ("T".equalsIgnoreCase(trim(rs.getString("tax_code"))) && tax.signum() == 0)
                    tax = amt.divide(BigDecimal.valueOf(10), 2, java.math.RoundingMode.HALF_UP);
                tot[0] = tot[0].add(amt); tot[1] = tot[1].add(tax);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("docDate", sqlDate(rs.getDate("doc_date")));
                r.put("docType", cmDocType(trim(rs.getString("doc_type"))));
                r.put("docNo", rs.getInt("doc_no"));
                r.put("seqNo", rs.getInt("seq_no"));
                r.put("glAcct", rs.getInt("gl_acct_main") + "-" + rs.getInt("gl_acct_sub"));
                r.put("glDesc", rs.getString("gl_desc"));
                r.put("payee", trim(rs.getString("payee_name_1")));
                r.put("reference", rs.getString("ref_1"));
                r.put("amount", amt);
                r.put("taxCode", trim(rs.getString("tax_code")));
                r.put("taxAmt", tax);
                r.put("systemId", trim(rs.getString("system_id")));
                r.put("batchNo", rs.getInt("batch_no"));
                r.put("basGroup", trim(rs.getString("bas_group")));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getCashbookDistributions: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_AMT", tot[0]); params.put("SUM_TAX", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL05 — Cashbook Ledger  (cobol/cm2/cmtl05.pl) — running balance
    // ════════════════════════════════════════════════════════════════════════

    public record CashbookLedgerParams(String bankCode, LocalDate startDate, LocalDate endDate) {}

    /** CMTL05 — cashbook ledger with opening balance + running balance per distribution. */
    public Map<String, Object> getCashbookLedger(AppSession s, CashbookLedgerParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");
        BigDecimal openBal;
        try {
            openBal = jdbc.query("SELECT open_stmnt_bal FROM cmbanks WHERE company_no=? AND bank_code=?",
                rs -> rs.next() ? z(rs.getBigDecimal("open_stmnt_bal")) : BigDecimal.ZERO,
                s.getCompanyNo(), p.bankCode());
        } catch (Exception e) { openBal = BigDecimal.ZERO; }

        LocalDate endDate = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] running = { openBal };
        LocalDate startDate = p.startDate();
        try {
            jdbc.query(
                "SELECT doc_date, doc_type, doc_no, seq_no, amt FROM cmcbdis " +
                "WHERE company_no=? AND bank_code=? AND doc_date <= ? ORDER BY doc_date, doc_type, doc_no, seq_no",
                rs -> {
                    String dt = trim(rs.getString("doc_type"));
                    BigDecimal amt = z(rs.getBigDecimal("amt"));
                    BigDecimal signed = ("1".equals(dt) || "2".equals(dt)) ? amt : amt.negate();
                    running[0] = running[0].add(signed);
                    LocalDate d = rs.getDate("doc_date") != null ? rs.getDate("doc_date").toLocalDate() : null;
                    if (startDate != null && d != null && d.isBefore(startDate)) return;  // pre-range: balance only
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("docDate", sqlDate(rs.getDate("doc_date")));
                    r.put("docType", cmDocType(dt));
                    r.put("docNo", rs.getInt("doc_no"));
                    r.put("seqNo", rs.getInt("seq_no"));
                    r.put("amount", signed);
                    r.put("balance", running[0]);
                    rows.add(r);
                }, s.getCompanyNo(), p.bankCode(), Date.valueOf(endDate));
        } catch (Exception e) { log.error("getCashbookLedger: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", startDate != null ? startDate + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("OPENING_BAL", openBal); params.put("CLOSING_BAL", running[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL18 — Document Listing  (cobol/cm2/cmtl18.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record DocumentListingParams(
            String bankCode, LocalDate startDate, LocalDate endDate,
            boolean inclDeposits, boolean inclBankCredits, boolean inclCheques, boolean inclBankDebits,
            int startDocNo, int endDocNo) {}

    /** CMTL18 — cashbook document listing (cmtrans) with amount paid + outstanding. */
    public Map<String, Object> getDocumentListing(AppSession s, DocumentListingParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");
        List<String> types = new ArrayList<>();
        if (p.inclDeposits()) types.add("1");
        if (p.inclBankCredits()) types.add("2");
        if (p.inclCheques()) types.add("3");
        if (p.inclBankDebits()) types.add("4");
        if (types.isEmpty()) return warn("Select at least one transaction type.");

        StringBuilder sql = new StringBuilder(
            "SELECT doc_date, doc_type, doc_no, payee_1, payee_2, ref, amt, amt_paid, trx_status, system_id " +
            "FROM cmtrans WHERE company_no=? AND bank_code=? AND doc_type IN (" + qMarks(types.size()) + ") ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo()); args.add(p.bankCode()); args.addAll(types);
        if (p.startDate() != null) { LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999,12,31);
            sql.append(" AND doc_date BETWEEN ? AND ? "); args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(e)); }
        if (p.startDocNo() > 0) { int e = p.endDocNo() > 0 ? p.endDocNo() : 99999999;
            sql.append(" AND doc_no BETWEEN ? AND ? "); args.add(p.startDocNo()); args.add(e); }
        sql.append(" ORDER BY doc_date, doc_type, doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                String dt = trim(rs.getString("doc_type"));
                BigDecimal amt = z(rs.getBigDecimal("amt")), paid = z(rs.getBigDecimal("amt_paid"));
                BigDecimal outstanding = amt.subtract(paid);
                tot[0] = tot[0].add(amt); tot[1] = tot[1].add(paid); tot[2] = tot[2].add(outstanding);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("docDate", sqlDate(rs.getDate("doc_date")));
                r.put("docType", cmDocType(dt));
                r.put("docNo", rs.getInt("doc_no"));
                r.put("payee", payee(rs.getString("payee_1"), rs.getString("payee_2")));
                r.put("reference", rs.getString("ref"));
                r.put("systemId", trim(rs.getString("system_id")));
                r.put("amount", amt);
                r.put("amtPaid", paid);
                r.put("outstanding", outstanding);
                r.put("status", trxStatus(rs.getString("trx_status")));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getDocumentListing: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_AMT", tot[0]); params.put("SUM_PAID", tot[1]); params.put("SUM_OUTSTANDING", tot[2]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL02 — Bank Reconciliation Statement  (cobol/cm2/cmtl02.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record BankReconParams(String bankCode, String reconNo) {}

    /**
     * CMTL02 — reconciliation statement: cmrched header balances + the transactions
     * reconciled under that recon number. The recon detail tables (cmrcdis/cmrecln)
     * are empty in the extract, so the detail is reconstructed from cmtrans.
     */
    public Map<String, Object> getBankReconStatement(AppSession s, BankReconParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");
        if (!notBlank(p.reconNo())) return warn("Choose a reconciliation number.");
        int recon = Integer.parseInt(p.reconNo());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("RECON_NO", recon);
        try {
            jdbc.query("SELECT stmnt_open_date, stmnt_close_date, cshbk_open_bal, stmnt_open_bal, stmnt_close_bal, drs_entered, crs_entered " +
                       "FROM cmrched WHERE company_no=? AND bank_code=? AND recon_no=?",
                rs -> { if (rs.next()) {
                    LocalDate od = ld(rs.getDate("stmnt_open_date")), cd = ld(rs.getDate("stmnt_close_date"));
                    params.put("STMNT_OPEN_DATE", od != null ? od.toString() : "");
                    params.put("STMNT_CLOSE_DATE", cd != null ? cd.toString() : "");
                    params.put("CSHBK_OPEN_BAL", z(rs.getBigDecimal("cshbk_open_bal")));
                    params.put("STMNT_OPEN_BAL", z(rs.getBigDecimal("stmnt_open_bal")));
                    params.put("STMNT_CLOSE_BAL", z(rs.getBigDecimal("stmnt_close_bal")));
                    params.put("DRS_ENTERED", z(rs.getBigDecimal("drs_entered")));
                    params.put("CRS_ENTERED", z(rs.getBigDecimal("crs_entered")));
                } }, s.getCompanyNo(), p.bankCode(), recon);
        } catch (Exception e) { log.warn("CMTL02 header: {}", e.getMessage()); }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            jdbc.query(
                "SELECT doc_date, doc_type, doc_no, payee_1, payee_2, amt, trx_status FROM cmtrans " +
                "WHERE company_no=? AND bank_code=? AND recon_no=? ORDER BY doc_type, doc_date, doc_no",
                rs -> {
                    String dt = trim(rs.getString("doc_type"));
                    BigDecimal amt = z(rs.getBigDecimal("amt"));
                    BigDecimal signed = ("1".equals(dt) || "2".equals(dt)) ? amt : amt.negate();
                    tot[0] = tot[0].add(signed);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("docDate", sqlDate(rs.getDate("doc_date")));
                    r.put("docType", cmDocType(dt));
                    r.put("docNo", rs.getInt("doc_no"));
                    r.put("payee", payee(rs.getString("payee_1"), rs.getString("payee_2")));
                    r.put("amount", signed);
                    r.put("status", trxStatus(rs.getString("trx_status")));
                    rows.add(r);
                }, s.getCompanyNo(), p.bankCode(), recon);
        } catch (Exception e) { log.error("getBankReconStatement: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        params.put("SUM_RECONCILED", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL30 — Cashbook Foreign Currency Match  (cobol/cm2/cmtl30.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record FcMatchParams(String bankCode, LocalDate startDate, LocalDate endDate, int startDocNo, int endDocNo) {}

    /** CMTL30 — foreign-currency cashbook transactions (cmtrans with an FC rate) and amounts. */
    public Map<String, Object> getForeignCurrencyMatch(AppSession s, FcMatchParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");
        StringBuilder sql = new StringBuilder(
            "SELECT doc_date, doc_type, doc_no, amt, orig_local_amt, orig_parent_amt, curr_local_amt, " +
            "       orig_exchange_rate, last_exchange_rate, last_reval_date, trx_status " +
            "FROM cmtrans WHERE company_no=? AND bank_code=? AND orig_exchange_rate <> 0 ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo()); args.add(p.bankCode());
        if (p.startDate() != null) { LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999,12,31);
            sql.append(" AND doc_date BETWEEN ? AND ? "); args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(e)); }
        if (p.startDocNo() > 0) { int e = p.endDocNo() > 0 ? p.endDocNo() : 99999999;
            sql.append(" AND doc_no BETWEEN ? AND ? "); args.add(p.startDocNo()); args.add(e); }
        sql.append(" ORDER BY doc_date, doc_type, doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                String dt = trim(rs.getString("doc_type"));
                BigDecimal local = z(rs.getBigDecimal("amt"));
                tot[0] = tot[0].add(local);
                LocalDate rv = ld(rs.getDate("last_reval_date"));
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("docDate", sqlDate(rs.getDate("doc_date")));
                r.put("docType", cmDocType(dt));
                r.put("docNo", rs.getInt("doc_no"));
                r.put("localAmt", local);
                r.put("origLocalAmt", z(rs.getBigDecimal("orig_local_amt")));
                r.put("parentAmt", z(rs.getBigDecimal("orig_parent_amt")));
                r.put("exchangeRate", z(rs.getBigDecimal("orig_exchange_rate")));
                r.put("lastRate", z(rs.getBigDecimal("last_exchange_rate")));
                r.put("revalDate", rv != null ? java.sql.Date.valueOf(rv) : null);
                r.put("status", trxStatus(rs.getString("trx_status")));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getForeignCurrencyMatch: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_LOCAL", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    private Map<String, Object> result(List<Map<String, Object>> rows, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows); m.put("params", params); m.put("rowCount", rows.size());
        return m;
    }

    static String recptType(String t) {
        return switch (trim(t)) {
            case "C" -> "Cash"; case "Q" -> "Cheque"; case "R" -> "Credit Card"; case "E" -> "EFT";
            default -> trim(t);
        };
    }

    // ── shared helpers ─────────────────────────────────────────────────────────

    String bankLabel(AppSession s, String bankCode) {
        try {
            return jdbc.query("SELECT name1 FROM cmbanks WHERE company_no=? AND bank_code=?",
                rs -> rs.next() ? bankCode + " — " + trim(rs.getString("name1")) : bankCode,
                s.getCompanyNo(), bankCode);
        } catch (Exception e) { return bankCode; }
    }

    static String cmDocType(String t) {
        return switch (trim(t)) {
            case "1" -> "Deposit"; case "2" -> "Bank Cr"; case "3" -> "Cheque"; case "4" -> "Bank Dr";
            default -> trim(t);
        };
    }

    static String trxStatus(String s) {
        return switch (trim(s)) {
            case "C" -> "Cancelled"; case "A" -> "Adjustment"; case "D" -> "Deleted";
            case "S" -> "Selected"; default -> "";
        };
    }

    static String payee(String p1, String p2) {
        String a = trim(p1), b = trim(p2);
        return b.isEmpty() ? a : (a + " " + b).trim();
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
    static String qMarks(int n) { return String.join(",", Collections.nCopies(n, "?")); }
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
