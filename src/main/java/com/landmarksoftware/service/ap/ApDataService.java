package com.landmarksoftware.service.ap;

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
 * Accounts Payable data service.
 *
 * aptrans.trx_status: ' '=Posted/Open, 'U'=Unposted, 'H'=On Hold  (NOT 'O' like artrans!)
 * Open balance (net) = amt - amt_paid - disc_taken - for_curr_fluct_amt
 * Open balance (gross) = amt (for invoices/credits); amt + disc_taken (for payments)
 *
 * Ageing date options (matching Landmark aptl07):
 *   dateInd='T' -> age by transaction/document date
 *   dateInd='P' -> age by posting date
 *   dateInd='D' -> age by due date (default)
 *
 * Amount options:
 *   grossNet='N' -> net outstanding (amt - amt_paid - disc_taken)
 *   grossNet='G' -> gross original amount
 *
 * Detail/Summary:
 *   detailSummary='D' -> show each transaction per supplier
 *   detailSummary='S' -> show supplier totals only (default)
 */
@Service
public class ApDataService {

    private static final Logger log = LoggerFactory.getLogger(ApDataService.class);
    private final JdbcTemplate jdbc;

    public ApDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ── KPI methods ───────────────────────────────────────────────────────────

    public int getActiveSupplierCount(AppSession s) {
        try { return jdbc.queryForObject("SELECT COUNT(*) FROM apsupps WHERE company_no=? AND acct_status='A'", Integer.class, s.getCompanyNo()); }
        catch (Exception e) { log.error("getActiveSupplierCount: {}", e.getMessage()); return 0; }
    }

    public BigDecimal getCreditorsBalance(AppSession s) {
        try { return jdbc.queryForObject(
            "SELECT COALESCE(SUM(amt - amt_paid - disc_taken), 0) FROM aptrans WHERE company_no=? AND (amt - amt_paid - disc_taken) > 0",
            BigDecimal.class, s.getCompanyNo()); }
        catch (Exception e) { log.error("getCreditorsBalance: {}", e.getMessage()); return BigDecimal.ZERO; }
    }

    public BigDecimal getPurchasesYtd(AppSession s) {
        try { return jdbc.queryForObject(
            "SELECT COALESCE(SUM(purch_01+purch_02+purch_03+purch_04+purch_05+purch_06+purch_07+purch_08+purch_09+purch_10+purch_11+purch_12+purch_13),0) FROM appurch WHERE company_no=? AND year_no=?",
            BigDecimal.class, s.getCompanyNo(), s.getYearNo()); }
        catch (Exception e) { log.error("getPurchasesYtd: {}", e.getMessage()); return BigDecimal.ZERO; }
    }

    public BigDecimal getOverdueBalance(AppSession s) {
        try { return jdbc.queryForObject(
            "SELECT COALESCE(SUM(amt - amt_paid - disc_taken), 0) FROM aptrans WHERE company_no=? AND (amt - amt_paid - disc_taken) > 0 AND due_date < CURDATE()",
            BigDecimal.class, s.getCompanyNo()); }
        catch (Exception e) { log.error("getOverdueBalance: {}", e.getMessage()); return BigDecimal.ZERO; }
    }

    // ── Dashboard chart methods ───────────────────────────────────────────────

    public Map<String, Object> getPurchasesByPeriod(AppSession s) {
        List<String> periods = new ArrayList<>();
        List<BigDecimal> purchases = new ArrayList<>();
        try {
            StringBuilder sb = new StringBuilder("SELECT ");
            for (int p = 1; p <= 13; p++) { if (p>1) sb.append(","); sb.append(String.format("COALESCE(SUM(purch_%02d),0) AS p%d",p,p)); }
            sb.append(" FROM appurch WHERE company_no=? AND year_no=?");
            jdbc.query(sb.toString(), rs -> {
                for (int p = 1; p <= 13; p++) {
                    BigDecimal v = rs.getBigDecimal("p"+p); if (v==null) v=BigDecimal.ZERO;
                    if (v.compareTo(BigDecimal.ZERO)!=0 || !periods.isEmpty()) { periods.add("P"+p); purchases.add(v); }
                }
            }, s.getCompanyNo(), s.getYearNo());
        } catch (Exception e) { log.error("getPurchasesByPeriod: {}", e.getMessage()); }
        Map<String,Object> r=new LinkedHashMap<>(); r.put("periods",periods); r.put("purchases",purchases); return r;
    }

    public Map<String, Object> getCreditorsAgeing(AppSession s) {
        List<String> labels = List.of("Current","30 days","60 days","90 days","90+ days");
        List<BigDecimal> amounts = new ArrayList<>(Collections.nCopies(5, BigDecimal.ZERO));
        try {
            jdbc.query(
                "SELECT " +
                "SUM(CASE WHEN DATEDIFF(COALESCE(?,CURDATE()),due_date)<=0 THEN amt - amt_paid - disc_taken ELSE 0 END) c0," +
                "SUM(CASE WHEN DATEDIFF(COALESCE(?,CURDATE()),due_date) BETWEEN 1 AND 30 THEN amt - amt_paid - disc_taken ELSE 0 END) c30," +
                "SUM(CASE WHEN DATEDIFF(COALESCE(?,CURDATE()),due_date) BETWEEN 31 AND 60 THEN amt - amt_paid - disc_taken ELSE 0 END) c60," +
                "SUM(CASE WHEN DATEDIFF(COALESCE(?,CURDATE()),due_date) BETWEEN 61 AND 90 THEN amt - amt_paid - disc_taken ELSE 0 END) c90," +
                "SUM(CASE WHEN DATEDIFF(COALESCE(?,CURDATE()),due_date)>90 THEN amt - amt_paid - disc_taken ELSE 0 END) c90p " +
                "FROM aptrans WHERE company_no=? AND (amt - amt_paid - disc_taken) > 0",
                rs -> { amounts.set(0,z(rs.getBigDecimal("c0"))); amounts.set(1,z(rs.getBigDecimal("c30")));
                        amounts.set(2,z(rs.getBigDecimal("c60"))); amounts.set(3,z(rs.getBigDecimal("c90")));
                        amounts.set(4,z(rs.getBigDecimal("c90p"))); }, s.getCompanyNo());
        } catch (Exception e) { log.error("getCreditorsAgeing: {}", e.getMessage()); }
        Map<String,Object> r=new LinkedHashMap<>(); r.put("labels",labels); r.put("amounts",amounts); return r;
    }

    public List<Map<String,Object>> getTopSuppliers(AppSession s) {
        List<Map<String,Object>> rows = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT s.name_1, COALESCE(SUM(t.amt),0) AS total FROM aptrans t " +
                "JOIN apsupps s ON s.company_no=t.company_no AND s.supplier_no=t.supplier_no " +
                "WHERE t.company_no=? AND t.doc_type='I' AND YEAR(t.doc_date)=? " +
                "GROUP BY s.name_1 ORDER BY total DESC LIMIT 10",
                rs -> { Map<String,Object> row=new LinkedHashMap<>();
                        row.put("name",rs.getString("name_1")); row.put("value",rs.getBigDecimal("total")); rows.add(row); },
                s.getCompanyNo(), s.getYearNo());
        } catch (Exception e) { log.error("getTopSuppliers: {}", e.getMessage()); }
        return rows;
    }

    // ── Creditors Ageing Listing (with parameter options) ────────────────────

    /** APTL07 selection (screens s0/s1/s2), minus the deferred FC / audit-balance / DR fields. */
    public record CreditorsAgeingParams(
            String subLedgerStart, String subLedgerEnd,
            String printSeq,                 // "N" supplier no | "A" alpha key
            String supplierStart, String supplierEnd,
            String detailSummary,            // "D" detail | "S" summary
            boolean zeroSupplier,            // summary: print suppliers whose total nets to zero
            boolean includeUnposted, boolean includePosted, boolean includeOnHold,
            String fullyDelivered, String withinTolerance,   // on-hold sub-filter: "Y"/"N"/"" (all)
            String dateInd,                  // "T" doc | "P" posting | "D" due
            String ageUnallocCr,             // "D" by date | "O" against oldest
            String grossNet,                 // "G" gross | "N" net
            boolean includeClaims, boolean includeArchived,
            String datesType,                // "C" calendar | "P" acct periods | "W" weeks | "N" manual
            LocalDate anchorDate,            // latest/as-at date for C/P/W
            List<LocalDate> manualPeriods) {} // 1–6 ascending dates for N

    /**
     * Creditors Ageing (Aged Trial Balance) — port of COBOL APTL07.
     *
     * <p>Up to 6 ascending period-ending dates form the buckets, derived from
     * {@code datesType}: C=calendar months back from the anchor month-end,
     * P=accounting periods (GLDATES period-ends), W=weeks, N=manual dates. The
     * latest boundary is the as-at cut-off (anything later is suppressed). A
     * transaction lands in the first bucket whose boundary is on/after its ageing
     * date (T=doc, P=posting, D=due). Net vs gross per doc_type; unallocated
     * credits/payments optionally forced to the oldest bucket. Posting-status,
     * sub-ledger/supplier range, claims and archived filters mirror screens s0/s1.
     *
     * <p><b>Deferred</b> vs COBOL (clearly, by design): the foreign-currency block,
     * the "age as-at latest date" audit-balance mode (needs aprctrx reconstruction),
     * and the DR-client due-period filter.
     */
    public Map<String, Object> getCreditorsAgeingData(AppSession s, CreditorsAgeingParams p) {
        final List<LocalDate> bounds;
        try { bounds = computeAgeingBounds(s, p); }
        catch (IllegalArgumentException ex) { return warn(ex.getMessage()); }
        final int nb = bounds.size();
        final LocalDate latest = bounds.get(nb - 1);

        boolean isDetail = "D".equalsIgnoreCase(p.detailSummary());
        boolean isGross  = "G".equalsIgnoreCase(p.grossNet());

        List<String> status = new ArrayList<>();
        if (p.includeUnposted()) status.add("t.trx_status='U'");
        if (p.includePosted())   status.add("TRIM(t.trx_status)=''");
        if (p.includeOnHold())   status.add("t.trx_status='H'");
        if (status.isEmpty()) return warn("Select at least one posting status (posted / unposted / on hold).");

        boolean alpha = "A".equalsIgnoreCase(p.printSeq());

        StringBuilder sql = new StringBuilder(
            "SELECT s.supplier_no, s.name_1, s.sub_ledger, s.alpha_key, s.city, s.state, s.contact_phone, " +
            "       COALESCE(l.name1,'') AS sub_ledger_name, " +
            "       t.doc_date, t.posting_date, t.due_date, t.doc_type, t.doc_no, t.trx_status, " +
            "       t.amt, t.retent_amt, t.amt_paid, t.disc_taken, t.for_curr_fluct_amt, " +
            "       t.fully_delivered_flag, t.within_tolerance_flag " +
            "FROM apsupps s " +
            "JOIN aptrans t ON t.company_no=s.company_no AND t.supplier_no=s.supplier_no " +
            "LEFT JOIN apledgr l ON l.company_no=s.company_no AND l.sub_ledger=s.sub_ledger " +
            "WHERE s.company_no=? ");
        List<Object> args = new ArrayList<>();
        args.add(s.getCompanyNo());
        if (notBlank(p.subLedgerStart())) {
            sql.append(" AND s.sub_ledger BETWEEN ? AND ? ");
            args.add(p.subLedgerStart());
            args.add(notBlank(p.subLedgerEnd()) ? p.subLedgerEnd() : "zzzz");
        }
        if (notBlank(p.supplierStart())) {
            sql.append(alpha ? " AND s.alpha_key BETWEEN ? AND ? " : " AND s.supplier_no BETWEEN ? AND ? ");
            args.add(p.supplierStart());
            args.add(notBlank(p.supplierEnd()) ? p.supplierEnd() : "zzzzzzzzzz");
        }
        sql.append(" AND (").append(String.join(" OR ", status)).append(") ");
        if (!p.includeClaims())   sql.append(" AND t.doc_type NOT IN ('K','R') ");
        if (!p.includeArchived()) sql.append(" AND TRIM(t.archive_flag)='' ");
        sql.append(alpha ? " ORDER BY s.alpha_key, s.alpha_supplier_no, t.doc_date, t.doc_no"
                         : " ORDER BY s.supplier_no, t.doc_date, t.doc_no");

        List<Map<String, Object>> detail = new ArrayList<>();
        Map<String, Map<String, Object>> summary = new LinkedHashMap<>();
        String fd = trim(p.fullyDelivered()), wt = trim(p.withinTolerance());
        boolean ageOldest = "O".equalsIgnoreCase(p.ageUnallocCr());
        String err = null;
        try {
            jdbc.query(sql.toString(), rs -> {
                String docType = trim(rs.getString("doc_type"));
                String trxStatus = trim(rs.getString("trx_status"));
                // on-hold sub-filter (only on-hold invoices)
                if ("H".equals(trxStatus) && "I".equals(docType)) {
                    String rfd = trim(rs.getString("fully_delivered_flag")), rwt = trim(rs.getString("within_tolerance_flag"));
                    if (!((fd.isEmpty() || fd.equals(rfd)) && (wt.isEmpty() || wt.equals(rwt)))) return;
                }
                // ageing date per basis
                LocalDate ageDate;
                if ("P".equalsIgnoreCase(p.dateInd()))      ageDate = ld(rs.getDate("posting_date"));
                else if ("D".equalsIgnoreCase(p.dateInd())) { LocalDate d = ld(rs.getDate("due_date")); ageDate = d != null ? d : ld(rs.getDate("doc_date")); }
                else                                         ageDate = ld(rs.getDate("doc_date"));
                if (ageDate == null || ageDate.isAfter(latest)) return;   // future suppression

                BigDecimal amt = z(rs.getBigDecimal("amt")), ret = z(rs.getBigDecimal("retent_amt")),
                           paid = z(rs.getBigDecimal("amt_paid")), disc = z(rs.getBigDecimal("disc_taken")),
                           fcf = z(rs.getBigDecimal("for_curr_fluct_amt"));
                BigDecimal bal;
                if ("P".equals(docType)) { bal = amt.add(disc); if (!isGross) bal = bal.subtract(paid); }
                else { bal = amt.subtract(ret); if (!isGross) bal = bal.subtract(paid).subtract(disc).subtract(fcf); }
                if (bal.signum() == 0) return;   // zero-balance suppression

                int bIdx;
                if (ageOldest && ("P".equals(docType) || "C".equals(docType)) && bal.signum() < 0) {
                    bIdx = 0;                                              // unallocated credit → oldest
                } else {
                    bIdx = nb - 1;
                    for (int i = 0; i < nb; i++) { if (!ageDate.isAfter(bounds.get(i))) { bIdx = i; break; } }
                }

                if (isDetail) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("rowType", "txn");
                    row.put("suppNo", rs.getString("supplier_no"));
                    row.put("name", rs.getString("name_1"));
                    row.put("subLedger", rs.getString("sub_ledger"));
                    row.put("subLedgerName", rs.getString("sub_ledger_name"));
                    row.put("docDate", fmt(rs.getDate("doc_date")));
                    row.put("docType", docTypeLabel(docType));
                    row.put("docNo", rs.getString("doc_no"));
                    row.put("status", trxStatusLabel(trxStatus));
                    for (int i = 0; i < 6; i++) row.put("p" + (i + 1), i == bIdx ? bal : BigDecimal.ZERO);
                    row.put("balance", bal);
                    detail.add(row);
                } else {
                    Map<String, Object> a = summary.get(rs.getString("supplier_no"));
                    if (a == null) {
                        a = new LinkedHashMap<>();
                        a.put("suppNo", rs.getString("supplier_no"));
                        a.put("name", rs.getString("name_1"));
                        a.put("subLedger", rs.getString("sub_ledger"));
                        a.put("subLedgerName", rs.getString("sub_ledger_name"));
                        a.put("location", trim(rs.getString("city")) + " " + trim(rs.getString("state")));
                        a.put("phone", rs.getString("contact_phone"));
                        for (int i = 1; i <= 6; i++) a.put("p" + i, BigDecimal.ZERO);
                        a.put("total", BigDecimal.ZERO);
                        summary.put(rs.getString("supplier_no"), a);
                    }
                    String key = "p" + (bIdx + 1);
                    a.put(key, ((BigDecimal) a.get(key)).add(bal));
                    a.put("total", ((BigDecimal) a.get("total")).add(bal));
                }
            }, args.toArray());
        } catch (Exception e) {
            log.error("getCreditorsAgeingData: {}", e.getMessage(), e);
            err = e.getMessage();
        }
        if (err != null) return warn("Query failed: " + err);

        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) params.put("PERIOD_" + (i + 1), i < nb ? bounds.get(i).format(f) : "");
        params.put("AS_AT_DATE", latest.format(f));
        params.put("DATE_BASIS", "P".equalsIgnoreCase(p.dateInd()) ? "Posting date"
                               : "D".equalsIgnoreCase(p.dateInd()) ? "Due date" : "Document date");
        params.put("AMT_BASIS", isGross ? "Gross" : "Net outstanding");
        params.put("DATES_TYPE_DESC", agesTypeDesc(p.datesType()));

        List<Map<String, Object>> rows;
        if (isDetail) {
            // Group the flat transaction list (already ordered by supplier) into
            // header + txn + subtotal rows, mirroring the AR detail injected-rows layout.
            rows = new ArrayList<>();
            BigDecimal[] grand = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                                  BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
            int suppCount = 0;
            String curSupp = null;
            String curName = null;
            BigDecimal[] sub = null;
            for (Map<String, Object> txn : detail) {
                String suppNo = (String) txn.get("suppNo");
                if (!suppNo.equals(curSupp)) {
                    if (curSupp != null) rows.add(ageingSubtotalRow(curSupp, curName, sub));
                    curSupp = suppNo;
                    curName = (String) txn.get("name");
                    sub = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                                           BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
                    suppCount++;
                    rows.add(ageingHeaderRow(suppNo, curName));
                }
                rows.add(txn);
                for (int i = 0; i < 6; i++) {
                    BigDecimal v = z((BigDecimal) txn.get("p" + (i + 1)));
                    sub[i] = sub[i].add(v); grand[i] = grand[i].add(v);
                }
                BigDecimal bal = z((BigDecimal) txn.get("balance"));
                sub[6] = sub[6].add(bal); grand[6] = grand[6].add(bal);
            }
            if (curSupp != null) rows.add(ageingSubtotalRow(curSupp, curName, sub));

            params.put("SUPP_COUNT", suppCount);
            for (int i = 0; i < 6; i++) params.put("GRAND_P" + (i + 1), grand[i]);
            params.put("GRAND_BALANCE", grand[6]);
        } else {
            rows = new ArrayList<>();
            for (Map<String, Object> a : summary.values())
                if (p.zeroSupplier() || ((BigDecimal) a.get("total")).signum() != 0) rows.add(a);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("params", params);
        result.put("rowCount", rows.size());
        return result;
    }

    /** Detail-mode supplier header row (blue band): "1234 — Supplier Name". */
    private static Map<String, Object> ageingHeaderRow(String suppNo, String name) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rowType", "header");
        r.put("suppNo", suppNo); r.put("name", name);
        r.put("docDate", ""); r.put("docType", ""); r.put("docNo", ""); r.put("status", "");
        for (int i = 1; i <= 6; i++) r.put("p" + i, BigDecimal.ZERO);
        r.put("balance", BigDecimal.ZERO);
        return r;
    }

    /** Detail-mode per-supplier subtotal row (light band). */
    private static Map<String, Object> ageingSubtotalRow(String suppNo, String name, BigDecimal[] sub) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rowType", "subtotal");
        r.put("suppNo", suppNo); r.put("name", name);
        r.put("docDate", ""); r.put("docType", ""); r.put("docNo", ""); r.put("status", "");
        for (int i = 0; i < 6; i++) r.put("p" + (i + 1), sub[i]);
        r.put("balance", sub[6]);
        return r;
    }

    /** Build the 1–6 ascending ageing boundary dates per the chosen dates-type (COBOL screen s2). */
    private List<LocalDate> computeAgeingBounds(AppSession s, CreditorsAgeingParams p) {
        String dt = trim(p.datesType()).toUpperCase();
        List<LocalDate> b = new ArrayList<>();
        switch (dt) {
            case "C" -> {
                LocalDate a = p.anchorDate();
                if (a == null) throw new IllegalArgumentException("Enter the latest (anchor) month-end date.");
                if (!a.equals(a.withDayOfMonth(a.lengthOfMonth())))
                    throw new IllegalArgumentException("Calendar-months: the anchor date must be a month-end.");
                for (int i = 5; i >= 0; i--) { LocalDate m = a.minusMonths(i); b.add(m.withDayOfMonth(m.lengthOfMonth())); }
            }
            case "W" -> {
                LocalDate a = p.anchorDate();
                if (a == null) throw new IllegalArgumentException("Enter the latest (anchor) date.");
                for (int i = 5; i >= 0; i--) b.add(a.minusWeeks(i));
            }
            case "P" -> {
                LocalDate a = p.anchorDate();
                if (a == null) throw new IllegalArgumentException("Enter the latest (anchor) period-end date.");
                List<LocalDate> ends = glPeriodEnds(s);
                if (!ends.contains(a)) throw new IllegalArgumentException("Anchor must be a GL period-end date.");
                List<LocalDate> le = new ArrayList<>();
                for (LocalDate d : ends) if (!d.isAfter(a)) le.add(d);
                b.addAll(le.subList(Math.max(0, le.size() - 6), le.size()));
            }
            case "N" -> {
                if (p.manualPeriods() == null) throw new IllegalArgumentException("Enter at least one ageing date.");
                TreeSet<LocalDate> set = new TreeSet<>();
                for (LocalDate d : p.manualPeriods()) if (d != null) set.add(d);
                if (set.isEmpty()) throw new IllegalArgumentException("Enter at least one ageing date.");
                for (LocalDate d : set) { b.add(d); if (b.size() == 6) break; }
            }
            default -> throw new IllegalArgumentException("Choose an ageing dates type (calendar / periods / weeks / manual).");
        }
        return b;
    }

    /** Distinct GL period-end dates for the company (from gldates period_end_01..13), ascending. */
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
    private static String fmt(Date d) {
        LocalDate v = ld(d);
        return v == null ? "" : v.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
    }
    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private Map<String, Object> warn(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", new ArrayList<>());
        m.put("params", new LinkedHashMap<>());
        m.put("rowCount", 0);
        m.put("warning", msg);
        return m;
    }


    // ── Helpers ───────────────────────────────────────────────────────────────

    private String docTypeLabel(String t) {
        if (t == null) return "";
        return switch (t.trim()) {
            case "I" -> "INV"; case "C" -> "CRN"; case "D" -> "DRN";
            case "V" -> "VOI"; case "K" -> "CLM"; case "R" -> "REV";
            case "B" -> "BAL"; case "P" -> "PAY"; default -> t.trim();
        };
    }

    private String trxStatusLabel(String s) {
        if (s == null || s.isBlank()) return "";
        return switch (s.trim()) {
            case "U" -> "NEW"; case "H" -> "HOLD"; default -> "";
        };
    }

    private BigDecimal z(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private Map<String,Object> col(String l, String f, String t) { return Map.of("label",l,"field",f,"type",t); }
    private Map<String,Object> err(String m) { return Map.of("error",m,"columns",List.of(),"rows",List.of(),"title","Error"); }
}
