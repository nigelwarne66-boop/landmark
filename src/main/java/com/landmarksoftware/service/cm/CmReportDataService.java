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
