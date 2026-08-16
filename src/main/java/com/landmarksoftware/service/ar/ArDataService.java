package com.landmarksoftware.service.ar;

import com.landmarksoftware.model.AppSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Accounts Receivable data service.
 *
 * artrans.trx_status: COBOL (artl01) skips only 'U' (unposted).
 * 'O' = open/posted is the standard open status for AR.
 *
 * AR balance formula (from artl01 CALC-TRX-BAL):
 *   Payments  (doc_type='P'): gross = amt + disc_taken;   net = amt + disc_taken - amt_paid
 *   Non-payments:             gross = amt - retent_amt;   net = amt - retent_amt - amt_paid - disc_taken
 *
 * Parameters (matching AP exactly):
 *   detailSummary: 'S'=supplier totals only, 'D'=each transaction
 *   dateInd:       'D'=due date (default), 'T'=transaction/doc date, 'P'=posting date
 *                  Note: payments always use doc_date when dateInd='D' (per COBOL)
 *   grossNet:      'N'=net outstanding, 'G'=gross original
 */
@Service
public class ArDataService {

    private static final Logger log = LoggerFactory.getLogger(ArDataService.class);
    private final JdbcTemplate jdbc;

    public ArDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ── Picker lookups (shared by AR selection screens) ──────────────────────

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
                rs -> { list.add(new CodeName(trimv(rs.getString("sub_ledger")),
                                              trimv(rs.getString("sub_ledger")) + " — " + trimv(rs.getString("name1")))); },
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
                    String code = byAlpha ? trimv(rs.getString("alpha_key")) : trimv(rs.getString("cust_no"));
                    list.add(new CodeName(code, code + " — " + trimv(rs.getString("name_1"))));
                }, s.getCompanyNo());
        } catch (Exception e) { log.warn("getCustomers: {}", e.getMessage()); }
        return list;
    }

    private static String trimv(String s) { return s == null ? "" : s.trim(); }

    // ── KPI methods ───────────────────────────────────────────────────────────

    public int getActiveCustomerCount(AppSession s) {
        try { return jdbc.queryForObject(
            "SELECT COUNT(*) FROM arcusts WHERE company_no=? AND acct_status='A'",
            Integer.class, s.getCompanyNo()); }
        catch (Exception e) { log.error("getActiveCustomerCount: {}", e.getMessage()); return 0; }
    }

    public BigDecimal getDebtorsBalance(AppSession s) {
        try { return jdbc.queryForObject(
            "SELECT COALESCE(SUM(amt - amt_paid), 0) FROM artrans WHERE company_no=? AND trx_status='O'",
            BigDecimal.class, s.getCompanyNo()); }
        catch (Exception e) { log.error("getDebtorsBalance: {}", e.getMessage()); return BigDecimal.ZERO; }
    }

    public BigDecimal getSalesYtd(AppSession s) {
        try { return jdbc.queryForObject(
            "SELECT COALESCE(SUM(sales_01+sales_02+sales_03+sales_04+sales_05+sales_06+" +
            "sales_07+sales_08+sales_09+sales_10+sales_11+sales_12+sales_13),0) " +
            "FROM arsales WHERE company_no=? AND year_no=?",
            BigDecimal.class, s.getCompanyNo(), s.getYearNo()); }
        catch (Exception e) { log.error("getSalesYtd: {}", e.getMessage()); return BigDecimal.ZERO; }
    }

    public BigDecimal getOverdueBalance(AppSession s) {
        try { return jdbc.queryForObject(
            "SELECT COALESCE(SUM(amt - amt_paid), 0) FROM artrans " +
            "WHERE company_no=? AND trx_status='O' AND due_date < CURDATE()",
            BigDecimal.class, s.getCompanyNo()); }
        catch (Exception e) { log.error("getOverdueBalance: {}", e.getMessage()); return BigDecimal.ZERO; }
    }

    // ── Dashboard chart methods ───────────────────────────────────────────────

    public Map<String, Object> getSalesByPeriod(AppSession s) {
        List<String> periods = new ArrayList<>();
        List<BigDecimal> sales = new ArrayList<>();
        try {
            StringBuilder sb = new StringBuilder("SELECT ");
            for (int p = 1; p <= 13; p++) { if (p > 1) sb.append(","); sb.append(String.format("COALESCE(SUM(sales_%02d),0) AS p%d", p, p)); }
            sb.append(" FROM arsales WHERE company_no=? AND year_no=?");
            jdbc.query(sb.toString(), rs -> {
                for (int p = 1; p <= 13; p++) {
                    BigDecimal v = rs.getBigDecimal("p" + p); if (v == null) v = BigDecimal.ZERO;
                    if (v.compareTo(BigDecimal.ZERO) != 0 || !periods.isEmpty()) { periods.add("P" + p); sales.add(v); }
                }
            }, s.getCompanyNo(), s.getYearNo());
        } catch (Exception e) { log.error("getSalesByPeriod: {}", e.getMessage()); }
        Map<String, Object> r = new LinkedHashMap<>(); r.put("periods", periods); r.put("sales", sales); return r;
    }

    public Map<String, Object> getDebtorsAgeing(AppSession s) {
        List<String> labels = List.of("Current", "30 days", "60 days", "90 days", "90+ days");
        List<BigDecimal> amounts = new ArrayList<>(Collections.nCopies(5, BigDecimal.ZERO));
        try {
            jdbc.query(
                "SELECT " +
                "SUM(CASE WHEN DATEDIFF(CURDATE(),due_date)<=0 THEN amt-amt_paid ELSE 0 END) c0," +
                "SUM(CASE WHEN DATEDIFF(CURDATE(),due_date) BETWEEN 1 AND 30 THEN amt-amt_paid ELSE 0 END) c30," +
                "SUM(CASE WHEN DATEDIFF(CURDATE(),due_date) BETWEEN 31 AND 60 THEN amt-amt_paid ELSE 0 END) c60," +
                "SUM(CASE WHEN DATEDIFF(CURDATE(),due_date) BETWEEN 61 AND 90 THEN amt-amt_paid ELSE 0 END) c90," +
                "SUM(CASE WHEN DATEDIFF(CURDATE(),due_date)>90 THEN amt-amt_paid ELSE 0 END) c90p " +
                "FROM artrans WHERE company_no=? AND trx_status='O'",
                rs -> { amounts.set(0, z(rs.getBigDecimal("c0"))); amounts.set(1, z(rs.getBigDecimal("c30")));
                        amounts.set(2, z(rs.getBigDecimal("c60"))); amounts.set(3, z(rs.getBigDecimal("c90")));
                        amounts.set(4, z(rs.getBigDecimal("c90p"))); }, s.getCompanyNo());
        } catch (Exception e) { log.error("getDebtorsAgeing: {}", e.getMessage()); }
        Map<String, Object> r = new LinkedHashMap<>(); r.put("labels", labels); r.put("amounts", amounts); return r;
    }

    public List<Map<String, Object>> getTopCustomers(AppSession s) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT c.name_1, COALESCE(SUM(t.amt),0) AS total FROM artrans t " +
                "JOIN arcusts c ON c.company_no=t.company_no AND c.cust_no=t.cust_no " +
                "WHERE t.company_no=? AND t.doc_type='I' AND YEAR(t.doc_date)=? " +
                "GROUP BY c.name_1 ORDER BY total DESC LIMIT 10",
                rs -> { Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", rs.getString("name_1")); row.put("value", rs.getBigDecimal("total")); rows.add(row); },
                s.getCompanyNo(), s.getYearNo());
        } catch (Exception e) { log.error("getTopCustomers: {}", e.getMessage()); }
        return rows;
    }

    // ── Debtors Ageing (Aged Trial Balance) — COBOL ARTL32 ────────────────────

    /** ARTL32 selection (one screen), minus the deferred FC block + audit latest-date balance. */
    public record DebtorsAgeingParams(
            String subLedgerStart, String subLedgerEnd,
            String printSeq,                 // "N" customer no | "A" alpha key
            String customerStart, String customerEnd,
            boolean sortDescBalance,         // sort customers by descending balance
            boolean includeZeroBalance,      // print customers whose total nets to zero
            String dateInd,                  // "T" doc | "P" posting | "D" due
            String ageUnallocCr,             // "D" by date | "O" against oldest
            String grossNet,                 // "G" gross | "N" net
            String datesType,                // "C" calendar | "P" acct periods | "W" weeks | "N" manual
            LocalDate anchorDate,            // latest/as-at date for C/P/W
            List<LocalDate> manualPeriods,   // 1–4 ascending dates for N
            String reportType) {}            // "S" summary (one row/customer) | "D" detail (one row/transaction)

    /**
     * Debtors Ageing — port of COBOL ARTL32 (Aging Summary; one row per customer).
     *
     * <p>Up to <b>4</b> ascending period-ending dates form the buckets, derived from
     * {@code datesType}: C=calendar months, P=accounting periods (GLDATES), W=weeks,
     * N=manual. Period date 4 is the as-at cut-off (later transactions suppressed).
     * Each transaction lands in the first bucket whose boundary is on/after its ageing
     * date (T=doc, P=posting, D=due; payments always age by doc date). Net vs gross per
     * doc_type (net of non-payments also subtracts {@code for_curr_fluct_amt}); net mode
     * skips fully-paid docs and zeroes fully-balanced reconciliations. Unallocated
     * credits/payments are aged by date, or rolled into the oldest buckets.
     *
     * <p><b>Deferred</b> vs COBOL: the foreign-currency block, the "as at latest aging
     * date" audit-balance mode (arrctrx reconstruction), and the arcodte due-period column.
     */
    public Map<String, Object> getDebtorsAgeingData(AppSession s, DebtorsAgeingParams p) {
        final List<LocalDate> bounds;
        try { bounds = computeAgeingBounds(s, p); }
        catch (IllegalArgumentException ex) { return warn(ex.getMessage()); }
        if ("D".equalsIgnoreCase(p.reportType())) return getDebtorsAgeingDetail(s, p, bounds);
        final int nb = bounds.size();
        final LocalDate latest = bounds.get(nb - 1);

        boolean isGross = "G".equalsIgnoreCase(p.grossNet());
        boolean alpha   = "A".equalsIgnoreCase(p.printSeq());
        boolean ageOldest = "O".equalsIgnoreCase(p.ageUnallocCr());

        StringBuilder sql = new StringBuilder(
            "SELECT c.cust_no, c.name_1, c.alpha_key, c.sub_ledger, c.city, c.state, c.contact_phone, c.credit_limit, " +
            "       COALESCE(l.name1,'') AS sub_ledger_name, " +
            "       t.doc_date, t.posting_date, t.due_date, t.doc_type, t.trx_status, t.fully_paid_flag, t.recon_no, " +
            "       t.amt, t.retent_amt, t.amt_paid, t.disc_taken, t.for_curr_fluct_amt, " +
            "       r.gross_bal, r.outstanding_bal, r.recon_no AS recon_found " +
            "FROM arcusts c " +
            "JOIN artrans t ON t.company_no=c.company_no AND t.cust_no=c.cust_no " +
            "LEFT JOIN arledgr l ON l.company_no=c.company_no AND l.sub_ledger=c.sub_ledger " +
            "LEFT JOIN arrecon r ON r.company_no=t.company_no AND r.cust_no=t.cust_no AND r.recon_no=t.recon_no " +
            "WHERE c.company_no=? AND t.trx_status<>'U' AND TRIM(t.archive_flag)='' ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.subLedgerStart())) {
            sql.append(" AND c.sub_ledger BETWEEN ? AND ? ");
            args.add(p.subLedgerStart());
            args.add(notBlank(p.subLedgerEnd()) ? p.subLedgerEnd() : "zzzz");
        }
        if (notBlank(p.customerStart())) {
            sql.append(alpha ? " AND c.alpha_key BETWEEN ? AND ? " : " AND c.cust_no BETWEEN ? AND ? ");
            args.add(p.customerStart());
            args.add(notBlank(p.customerEnd()) ? p.customerEnd() : "zzzzzzzzzz");
        }

        Map<String, Object[]> byCust = new LinkedHashMap<>();   // cust_no -> [accumulator object]
        Map<String, Map<String, Object>> meta = new LinkedHashMap<>();
        String err = null;
        try {
            jdbc.query(sql.toString(), rs -> {
                String docType = trim(rs.getString("doc_type"));
                boolean fullyPaid = "Y".equalsIgnoreCase(trim(rs.getString("fully_paid_flag")));
                if (!isGross && fullyPaid) return;            // net + as-at-today skips fully-paid

                LocalDate ageDate;
                if ("P".equalsIgnoreCase(p.dateInd()))      ageDate = ld(rs.getDate("posting_date"));
                else if ("D".equalsIgnoreCase(p.dateInd())) ageDate = "P".equals(docType) ? ld(rs.getDate("doc_date")) : ld(rs.getDate("due_date"));
                else                                         ageDate = ld(rs.getDate("doc_date"));
                if (ageDate == null || ageDate.isAfter(latest)) return;

                BigDecimal amt = z(rs.getBigDecimal("amt")), ret = z(rs.getBigDecimal("retent_amt")),
                           paid = z(rs.getBigDecimal("amt_paid")), disc = z(rs.getBigDecimal("disc_taken")),
                           fcf = z(rs.getBigDecimal("for_curr_fluct_amt"));
                BigDecimal bal;
                if ("P".equals(docType)) { bal = amt.add(disc); if (!isGross) bal = bal.subtract(paid); }
                else { bal = amt.subtract(ret).subtract(fcf); if (!isGross) bal = bal.subtract(paid).subtract(disc); }
                if (!isGross && rs.getObject("recon_found") != null
                        && z(rs.getBigDecimal("gross_bal")).signum() == 0
                        && z(rs.getBigDecimal("outstanding_bal")).signum() == 0) bal = BigDecimal.ZERO;
                if (bal.signum() == 0) return;

                String custNo = rs.getString("cust_no");
                Object[] acc = byCust.get(custNo);
                if (acc == null) {
                    acc = new Object[]{new BigDecimal[nb], BigDecimal.ZERO};   // [buckets, unallocCr]
                    BigDecimal[] bk = (BigDecimal[]) acc[0];
                    Arrays.fill(bk, BigDecimal.ZERO);
                    byCust.put(custNo, acc);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("custNo", custNo); m.put("name", rs.getString("name_1"));
                    m.put("alphaKey", rs.getString("alpha_key"));
                    m.put("subLedger", rs.getString("sub_ledger"));
                    m.put("subLedgerName", rs.getString("sub_ledger_name"));
                    m.put("location", trim(rs.getString("city")) + " " + trim(rs.getString("state")));
                    m.put("phone", rs.getString("contact_phone"));
                    m.put("creditLimit", rs.getLong("credit_limit"));
                    meta.put(custNo, m);
                }
                BigDecimal[] bk = (BigDecimal[]) acc[0];

                if (ageOldest && ("P".equals(docType) || "C".equals(docType)) && bal.signum() < 0) {
                    acc[1] = ((BigDecimal) acc[1]).add(bal);   // hold unallocated credit aside
                } else {
                    int idx = nb - 1;
                    for (int i = 0; i < nb; i++) { if (!ageDate.isAfter(bounds.get(i))) { idx = i; break; } }
                    bk[idx] = bk[idx].add(bal);
                }
            }, args.toArray());
        } catch (Exception e) {
            log.error("getDebtorsAgeingData: {}", e.getMessage(), e);
            err = e.getMessage();
        }
        if (err != null) return warn("Query failed: " + err);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (var e : byCust.entrySet()) {
            BigDecimal[] bk = (BigDecimal[]) e.getValue()[0];
            BigDecimal unalloc = (BigDecimal) e.getValue()[1];
            if (unalloc.signum() < 0) {                       // SHUFFLE-UNALLOC-CR: offset oldest first
                for (int i = 0; i < nb && unalloc.signum() < 0; i++) {
                    if (bk[i].signum() > 0) {
                        BigDecimal nv = bk[i].add(unalloc);
                        if (nv.signum() >= 0) { bk[i] = nv; unalloc = BigDecimal.ZERO; }
                        else { unalloc = nv; bk[i] = BigDecimal.ZERO; }
                    }
                }
                bk[nb - 1] = bk[nb - 1].add(unalloc);         // leftover credit into newest bucket
            }
            BigDecimal total = BigDecimal.ZERO;
            for (BigDecimal b : bk) total = total.add(b);
            if (!p.includeZeroBalance() && total.signum() == 0) continue;
            Map<String, Object> row = meta.get(e.getKey());
            for (int i = 0; i < 4; i++) row.put("p" + (i + 1), i < nb ? bk[i] : BigDecimal.ZERO);
            row.put("total", total);
            rows.add(row);
        }

        // sort: descending balance (if requested) else by alpha key / cust no
        rows.sort((a, b) -> {
            int sl = ((String) a.get("subLedger")).compareTo((String) b.get("subLedger"));
            if (sl != 0) return sl;
            if (p.sortDescBalance()) return ((BigDecimal) b.get("total")).compareTo((BigDecimal) a.get("total"));
            String ka = (String) (alpha ? a.get("alphaKey") : a.get("custNo"));
            String kb = (String) (alpha ? b.get("alphaKey") : b.get("custNo"));
            return ka.compareTo(kb);
        });

        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) params.put("PERIOD_" + (i + 1), i < nb ? bounds.get(i).format(f) : "");
        params.put("AS_AT_DATE", latest.format(f));
        params.put("DATE_BASIS", "P".equalsIgnoreCase(p.dateInd()) ? "Posting date"
                               : "D".equalsIgnoreCase(p.dateInd()) ? "Due date" : "Document date");
        params.put("AMT_BASIS", isGross ? "Gross" : "Net outstanding");
        params.put("DATES_TYPE_DESC", agesTypeDesc(p.datesType()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("params", params);
        result.put("rowCount", rows.size());
        return result;
    }

    /**
     * Detail ageing (CMPASS-AGING-TYPE = "L") — one row per transaction, with injected
     * customer header and subtotal rows, following the injected-rows Jasper pattern.
     * rowType: "header" = customer name band, "txn" = transaction line, "subtotal" = customer totals.
     */
    private Map<String, Object> getDebtorsAgeingDetail(AppSession s, DebtorsAgeingParams p, List<LocalDate> bounds) {
        final int nb = bounds.size();
        final LocalDate latest = bounds.get(nb - 1);
        boolean isGross = "G".equalsIgnoreCase(p.grossNet());
        boolean alpha   = "A".equalsIgnoreCase(p.printSeq());

        StringBuilder sql = new StringBuilder(
            "SELECT c.cust_no, c.name_1, c.alpha_key, c.sub_ledger, " +
            "       t.doc_date, t.posting_date, t.due_date, t.doc_type, t.doc_no, t.ref, " +
            "       t.trx_status, t.fully_paid_flag, t.recon_no, " +
            "       t.amt, t.retent_amt, t.amt_paid, t.disc_taken, t.for_curr_fluct_amt, " +
            "       r.gross_bal, r.outstanding_bal, r.recon_no AS recon_found " +
            "FROM arcusts c " +
            "JOIN artrans t ON t.company_no=c.company_no AND t.cust_no=c.cust_no " +
            "LEFT JOIN arrecon r ON r.company_no=t.company_no AND r.cust_no=t.cust_no AND r.recon_no=t.recon_no " +
            "WHERE c.company_no=? AND t.trx_status<>'U' AND TRIM(t.archive_flag)='' ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.subLedgerStart())) {
            sql.append(" AND c.sub_ledger BETWEEN ? AND ? ");
            args.add(p.subLedgerStart());
            args.add(notBlank(p.subLedgerEnd()) ? p.subLedgerEnd() : "zzzz");
        }
        if (notBlank(p.customerStart())) {
            sql.append(alpha ? " AND c.alpha_key BETWEEN ? AND ? " : " AND c.cust_no BETWEEN ? AND ? ");
            args.add(p.customerStart());
            args.add(notBlank(p.customerEnd()) ? p.customerEnd() : "zzzzzzzzzz");
        }
        sql.append(alpha ? " ORDER BY c.alpha_key, c.cust_no, t.doc_date, t.doc_type, t.doc_no "
                         : " ORDER BY c.cust_no, t.doc_date, t.doc_type, t.doc_no ");

        // Collect per-customer transaction lists in order.
        Map<String, String[]>              custMeta  = new LinkedHashMap<>();  // custNo → [name, alphaKey, subLedger]
        Map<String, List<Map<String,Object>>> custTxns = new LinkedHashMap<>();
        String err = null;
        try {
            jdbc.query(sql.toString(), rs -> {
                String docType = trim(rs.getString("doc_type"));
                boolean fullyPaid = "Y".equalsIgnoreCase(trim(rs.getString("fully_paid_flag")));
                if (!isGross && fullyPaid) return;

                LocalDate ageDate;
                if ("P".equalsIgnoreCase(p.dateInd()))      ageDate = ld(rs.getDate("posting_date"));
                else if ("D".equalsIgnoreCase(p.dateInd())) ageDate = "P".equals(docType) ? ld(rs.getDate("doc_date")) : ld(rs.getDate("due_date"));
                else                                         ageDate = ld(rs.getDate("doc_date"));
                if (ageDate == null || ageDate.isAfter(latest)) return;

                BigDecimal amt  = z(rs.getBigDecimal("amt")),  ret  = z(rs.getBigDecimal("retent_amt")),
                           paid = z(rs.getBigDecimal("amt_paid")), disc = z(rs.getBigDecimal("disc_taken")),
                           fcf  = z(rs.getBigDecimal("for_curr_fluct_amt"));
                BigDecimal bal;
                if ("P".equals(docType)) { bal = amt.add(disc); if (!isGross) bal = bal.subtract(paid); }
                else { bal = amt.subtract(ret).subtract(fcf); if (!isGross) bal = bal.subtract(paid).subtract(disc); }
                if (!isGross && rs.getObject("recon_found") != null
                        && z(rs.getBigDecimal("gross_bal")).signum() == 0
                        && z(rs.getBigDecimal("outstanding_bal")).signum() == 0) bal = BigDecimal.ZERO;
                if (bal.signum() == 0) return;

                String custNo = rs.getString("cust_no");
                custMeta.putIfAbsent(custNo, new String[]{
                    trim(rs.getString("name_1")), trim(rs.getString("alpha_key")), trim(rs.getString("sub_ledger"))});
                custTxns.computeIfAbsent(custNo, k -> new ArrayList<>());

                // Classify into bucket by the transaction's own ageing date. (The summary
                // "age unallocated credits against oldest" roll-up isn't meaningful at the
                // per-transaction line level, so every line ages by its own date here.)
                int idx = nb - 1;
                for (int i = 0; i < nb; i++) { if (!ageDate.isAfter(bounds.get(i))) { idx = i; break; } }
                BigDecimal[] bkAmts = new BigDecimal[4];
                Arrays.fill(bkAmts, BigDecimal.ZERO);
                if (idx < 4) bkAmts[idx] = bal;

                String docTypeDesc = switch (docType) {
                    case "I" -> "Invoice"; case "D" -> "Dr Note"; case "C" -> "Cr Note";
                    case "P" -> "Payment"; case "V" -> "Void"; default -> docType; };
                LocalDate docDate = ld(rs.getDate("doc_date"));
                Map<String, Object> txn = new LinkedHashMap<>();
                txn.put("rowType", "txn");
                txn.put("custNo", custNo);
                txn.put("name", custMeta.get(custNo)[0]);
                txn.put("docDate", docDate != null ? java.sql.Date.valueOf(docDate) : null);
                txn.put("docTypeDesc", docTypeDesc);
                txn.put("docNo", trim(rs.getString("doc_no")));
                txn.put("ref",   trim(rs.getString("ref")));
                txn.put("p1", bkAmts[0]); txn.put("p2", bkAmts[1]);
                txn.put("p3", bkAmts[2]); txn.put("p4", bkAmts[3]);
                txn.put("total", bal);
                custTxns.get(custNo).add(txn);
            }, args.toArray());
        } catch (Exception e) {
            log.error("getDebtorsAgeingDetail: {}", e.getMessage(), e);
            err = e.getMessage();
        }
        if (err != null) return warn("Query failed: " + err);
        if (custTxns.isEmpty()) return warn("No customers matched the selection.");

        // Assemble output: header + txn rows + subtotal per customer.
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] grand = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        int custCount = 0;
        for (var entry : custTxns.entrySet()) {
            String custNo = entry.getKey();
            String[] meta = custMeta.get(custNo);
            List<Map<String, Object>> txns = entry.getValue();

            BigDecimal[] sub = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
            for (Map<String, Object> txn : txns) {
                sub[0] = sub[0].add(z((BigDecimal) txn.get("p1")));
                sub[1] = sub[1].add(z((BigDecimal) txn.get("p2")));
                sub[2] = sub[2].add(z((BigDecimal) txn.get("p3")));
                sub[3] = sub[3].add(z((BigDecimal) txn.get("p4")));
            }
            BigDecimal subTotal = sub[0].add(sub[1]).add(sub[2]).add(sub[3]);
            if (!p.includeZeroBalance() && subTotal.signum() == 0) continue;
            custCount++;
            for (int i = 0; i < 4; i++) grand[i] = grand[i].add(sub[i]);
            grand[4] = grand[4].add(subTotal);

            Map<String, Object> hdr = new LinkedHashMap<>();
            hdr.put("rowType", "header"); hdr.put("custNo", custNo); hdr.put("name", meta[0]);
            hdr.put("docDate", null); hdr.put("docTypeDesc", ""); hdr.put("docNo", ""); hdr.put("ref", "");
            hdr.put("p1", BigDecimal.ZERO); hdr.put("p2", BigDecimal.ZERO);
            hdr.put("p3", BigDecimal.ZERO); hdr.put("p4", BigDecimal.ZERO); hdr.put("total", BigDecimal.ZERO);
            rows.add(hdr);
            rows.addAll(txns);

            Map<String, Object> st = new LinkedHashMap<>();
            st.put("rowType", "subtotal"); st.put("custNo", custNo); st.put("name", meta[0]);
            st.put("docDate", null); st.put("docTypeDesc", ""); st.put("docNo", ""); st.put("ref", "");
            st.put("p1", sub[0]); st.put("p2", sub[1]); st.put("p3", sub[2]); st.put("p4", sub[3]);
            st.put("total", subTotal);
            rows.add(st);
        }
        if (rows.isEmpty()) return warn("No customers matched the selection.");

        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) params.put("PERIOD_" + (i + 1), i < nb ? bounds.get(i).format(f) : "");
        params.put("AS_AT_DATE", latest.format(f));
        params.put("DATE_BASIS", "P".equalsIgnoreCase(p.dateInd()) ? "Posting date"
                               : "D".equalsIgnoreCase(p.dateInd()) ? "Due date" : "Document date");
        params.put("AMT_BASIS", isGross ? "Gross" : "Net outstanding");
        params.put("DATES_TYPE_DESC", agesTypeDesc(p.datesType()));
        params.put("CUST_COUNT", custCount);
        params.put("GRAND_P1", grand[0]); params.put("GRAND_P2", grand[1]);
        params.put("GRAND_P3", grand[2]); params.put("GRAND_P4", grand[3]);
        params.put("GRAND_TOTAL", grand[4]);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("params", params);
        result.put("rowCount", rows.size());
        return result;
    }

    /** Build the 1–4 ascending ageing boundary dates per the chosen dates-type (ARTL32 CALC-DATES). */
    private List<LocalDate> computeAgeingBounds(AppSession s, DebtorsAgeingParams p) {
        String dt = trim(p.datesType()).toUpperCase();
        List<LocalDate> b = new ArrayList<>();
        switch (dt) {
            case "C" -> {
                LocalDate a = p.anchorDate();
                if (a == null) throw new IllegalArgumentException("Enter the latest (anchor) month-end date.");
                if (!a.equals(a.withDayOfMonth(a.lengthOfMonth())))
                    throw new IllegalArgumentException("Calendar-months: the anchor date must be a month-end.");
                for (int i = 3; i >= 0; i--) { LocalDate m = a.minusMonths(i); b.add(m.withDayOfMonth(m.lengthOfMonth())); }
            }
            case "W" -> {
                LocalDate a = p.anchorDate();
                if (a == null) throw new IllegalArgumentException("Enter the latest (anchor) date.");
                for (int i = 3; i >= 0; i--) b.add(a.minusWeeks(i));
            }
            case "P" -> {
                LocalDate a = p.anchorDate();
                if (a == null) throw new IllegalArgumentException("Enter the latest (anchor) period-end date.");
                List<LocalDate> ends = glPeriodEnds(s);
                if (!ends.contains(a)) throw new IllegalArgumentException("Anchor must be a GL period-end date.");
                List<LocalDate> le = new ArrayList<>();
                for (LocalDate d : ends) if (!d.isAfter(a)) le.add(d);
                b.addAll(le.subList(Math.max(0, le.size() - 4), le.size()));
            }
            case "N" -> {
                if (p.manualPeriods() == null) throw new IllegalArgumentException("Enter at least one ageing date.");
                TreeSet<LocalDate> set = new TreeSet<>();
                for (LocalDate d : p.manualPeriods()) if (d != null) set.add(d);
                if (set.isEmpty()) throw new IllegalArgumentException("Enter at least one ageing date.");
                for (LocalDate d : set) { b.add(d); if (b.size() == 4) break; }
            }
            default -> throw new IllegalArgumentException("Choose an ageing dates type (calendar / periods / weeks / manual).");
        }
        return b;
    }

    private List<LocalDate> glPeriodEnds(AppSession s) {
        TreeSet<LocalDate> set = new TreeSet<>();
        try {
            StringBuilder cols = new StringBuilder();
            for (int i = 1; i <= 13; i++) cols.append(i > 1 ? "," : "").append(String.format("period_end_%02d", i));
            jdbc.query("SELECT " + cols + " FROM gldates WHERE company_no=?", rs -> {
                for (int i = 1; i <= 13; i++) {
                    Date d = rs.getDate(String.format("period_end_%02d", i));
                    if (d != null && d.toLocalDate().isAfter(LocalDate.of(1900, 1, 1))) set.add(d.toLocalDate());
                }
            }, s.getCompanyNo());
        } catch (Exception e) { log.warn("glPeriodEnds: {}", e.getMessage()); }
        return new ArrayList<>(set);
    }

    private static String agesTypeDesc(String t) {
        return switch (trim(t).toUpperCase()) {
            case "C" -> "Calendar months"; case "P" -> "Accounting periods";
            case "W" -> "Weeks"; case "N" -> "Manual dates"; default -> "";
        };
    }

    private static LocalDate ld(Date d) {
        if (d == null) return null;
        LocalDate v = d.toLocalDate();
        return v.isAfter(LocalDate.of(1900, 1, 1)) ? v : null;
    }
    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static BigDecimal z(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private Map<String, Object> warn(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", new ArrayList<>()); m.put("params", new LinkedHashMap<>());
        m.put("rowCount", 0); m.put("warning", msg);
        return m;
    }
}
