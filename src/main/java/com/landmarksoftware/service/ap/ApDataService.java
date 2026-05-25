package com.landmarksoftware.service.ap;

import com.landmarksoftware.model.AppSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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

    /**
     * @param detailSummary 'D'=detail (one row per transaction), 'S'=summary (supplier totals)
     * @param dateInd       'D'=due date, 'T'=transaction/doc date, 'P'=posting date
     * @param grossNet      'N'=net outstanding, 'G'=gross original amount
     */
    /**
     * Creditors Ageing Listing.
     * Ages by 6 monthly period-end boundaries working back from asAtDate (default today).
     * Matching Landmark aptl07 Excel output exactly.
     *
     * @param detailSummary 'D'=per-transaction, 'S'=supplier totals
     * @param dateInd       'D'=due date, 'T'=transaction/doc date, 'P'=posting date
     * @param grossNet      'N'=net outstanding, 'G'=gross original
     * @param asAtDate      YYYY-MM-DD as-at date (null/blank = today)
     */
    public Map<String, Object> getCreditorsListingData(AppSession s,
                                                        String detailSummary,
                                                        String dateInd,
                                                        String grossNet,
                                                        String asAtDate) {
        boolean isDetail    = "D".equalsIgnoreCase(detailSummary);
        boolean isGross     = "G".equalsIgnoreCase(grossNet);
        boolean usePostDate = "P".equalsIgnoreCase(dateInd);
        boolean useDocDate  = "T".equalsIgnoreCase(dateInd);

        // As-at date — default to today
        String asAt = (asAtDate != null && !asAtDate.isBlank()) ? "'" + asAtDate + "'" : "CURDATE()";

        // 6 period-end boundaries: P1=oldest (5 months back), P6=as-at
        // LAST_DAY(DATE_SUB(asAt, INTERVAL N MONTH)) gives calendar month-ends
        String p1 = "LAST_DAY(DATE_SUB(" + asAt + ", INTERVAL 5 MONTH))";
        String p2 = "LAST_DAY(DATE_SUB(" + asAt + ", INTERVAL 4 MONTH))";
        String p3 = "LAST_DAY(DATE_SUB(" + asAt + ", INTERVAL 3 MONTH))";
        String p4 = "LAST_DAY(DATE_SUB(" + asAt + ", INTERVAL 2 MONTH))";
        String p5 = "LAST_DAY(DATE_SUB(" + asAt + ", INTERVAL 1 MONTH))";
        String p6 = asAt; // the as-at date itself

        // For due date: fall back to doc_date when due_date is NULL (common in AP)
        String dateCol = useDocDate ? "t.doc_date" : (usePostDate ? "t.posting_date" :
                          "COALESCE(t.due_date, t.doc_date)");

        // Balance: gross = amt for non-payments, amt+disc_taken for payments
        //          net   = gross - amt_paid - disc_taken (non-pmts), gross - amt_paid (pmts)
        String balExpr = isGross
            ? "CASE WHEN t.doc_type='P' THEN t.amt + t.disc_taken ELSE t.amt END"
            : "t.amt - t.amt_paid - t.disc_taken";

        // Open filter — include transactions that still have a balance
        String openFilter = "(t.amt - t.amt_paid - t.disc_taken) <> 0";

        // Period bucket CASE expression using boundary dates
        // A transaction falls in the FIRST period whose boundary it doesn't exceed
        String bucket =
            "CASE WHEN " + dateCol + " <= " + p1 + " THEN 1" +
            "     WHEN " + dateCol + " <= " + p2 + " THEN 2" +
            "     WHEN " + dateCol + " <= " + p3 + " THEN 3" +
            "     WHEN " + dateCol + " <= " + p4 + " THEN 4" +
            "     WHEN " + dateCol + " <= " + p5 + " THEN 5" +
            "     WHEN " + dateCol + " <= " + p6 + " THEN 6" +
            "     ELSE 6 END";

        List<Map<String, Object>> rows = new ArrayList<>();
        String errMsg = null;

        try {
            if (isDetail) {
                String sql =
                    "SELECT s.supplier_no, s.name_1, t.doc_date, t.posting_date, t.due_date, " +
                    "  t.doc_type, t.doc_no, t.trx_status, (" + balExpr + ") AS bal, " +
                    "  (" + bucket + ") AS period_bucket " +
                    "FROM apsupps s " +
                    "JOIN aptrans t ON t.company_no=s.company_no AND t.supplier_no=s.supplier_no " +
                    "WHERE s.company_no=? AND " + openFilter + " AND t.doc_date <= " + p6 +
                    " ORDER BY s.name_1, t.doc_date, t.doc_no";
                jdbc.query(sql, rs -> {
                    int b = rs.getInt("period_bucket");
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("suppNo",  rs.getString("supplier_no"));
                    row.put("name",    rs.getString("name_1"));
                    row.put("docDate", rs.getString("doc_date"));
                    row.put("docType", docTypeLabel(rs.getString("doc_type")));
                    row.put("docNo",   rs.getString("doc_no"));
                    row.put("status",  trxStatusLabel(rs.getString("trx_status")));
                    java.math.BigDecimal bal = rs.getBigDecimal("bal");
                    row.put("p1", b==1 ? bal : java.math.BigDecimal.ZERO);
                    row.put("p2", b==2 ? bal : java.math.BigDecimal.ZERO);
                    row.put("p3", b==3 ? bal : java.math.BigDecimal.ZERO);
                    row.put("p4", b==4 ? bal : java.math.BigDecimal.ZERO);
                    row.put("p5", b==5 ? bal : java.math.BigDecimal.ZERO);
                    row.put("p6", b==6 ? bal : java.math.BigDecimal.ZERO);
                    row.put("balance", bal);
                    rows.add(row);
                }, s.getCompanyNo());
            } else {
                String sql =
                    "SELECT s.supplier_no, s.name_1, s.city, s.state, s.contact_phone, " +
                    "  COALESCE(SUM(CASE WHEN " + dateCol + " <= " + p1 + " THEN (" + balExpr + ") ELSE 0 END),0) AS p1, " +
                    "  COALESCE(SUM(CASE WHEN " + dateCol + " > " + p1 + " AND " + dateCol + " <= " + p2 + " THEN (" + balExpr + ") ELSE 0 END),0) AS p2, " +
                    "  COALESCE(SUM(CASE WHEN " + dateCol + " > " + p2 + " AND " + dateCol + " <= " + p3 + " THEN (" + balExpr + ") ELSE 0 END),0) AS p3, " +
                    "  COALESCE(SUM(CASE WHEN " + dateCol + " > " + p3 + " AND " + dateCol + " <= " + p4 + " THEN (" + balExpr + ") ELSE 0 END),0) AS p4, " +
                    "  COALESCE(SUM(CASE WHEN " + dateCol + " > " + p4 + " AND " + dateCol + " <= " + p5 + " THEN (" + balExpr + ") ELSE 0 END),0) AS p5, " +
                    "  COALESCE(SUM(CASE WHEN " + dateCol + " > " + p5 + " AND " + dateCol + " <= " + p6 + " THEN (" + balExpr + ") ELSE 0 END),0) AS p6, " +
                    "  COALESCE(SUM(CASE WHEN " + dateCol + " <= " + p6 + " THEN (" + balExpr + ") ELSE 0 END),0) AS total " +
                    "FROM apsupps s " +
                    "JOIN aptrans t ON t.company_no=s.company_no AND t.supplier_no=s.supplier_no " +
                    "WHERE s.company_no=? AND " + openFilter + " AND t.doc_date <= " + p6 + " " +
                    "GROUP BY s.supplier_no, s.name_1, s.city, s.state, s.contact_phone " +
                    "HAVING total <> 0 ORDER BY s.name_1";
                jdbc.query(sql, rs -> {
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("suppNo",   rs.getString("supplier_no"));
                    row.put("name",     rs.getString("name_1"));
                    row.put("location", rs.getString("city") + " " + rs.getString("state"));
                    row.put("phone",    rs.getString("contact_phone"));
                    row.put("p1", rs.getBigDecimal("p1")); row.put("p2", rs.getBigDecimal("p2"));
                    row.put("p3", rs.getBigDecimal("p3")); row.put("p4", rs.getBigDecimal("p4"));
                    row.put("p5", rs.getBigDecimal("p5")); row.put("p6", rs.getBigDecimal("p6"));
                    row.put("total",    rs.getBigDecimal("total"));
                    rows.add(row);
                }, s.getCompanyNo());
            }
        } catch (Exception e) {
            log.error("getCreditorsListingData: {}", e.getMessage());
            errMsg = e.getMessage();
        }

        if (errMsg != null) return err(errMsg);

        String amtDesc  = isGross ? "Gross" : "Net Outstanding";
        String dateDesc = useDocDate ? "Doc Date" : (usePostDate ? "Posting Date" : "Due Date");
        String modeDesc = isDetail ? "Detail" : "Summary";
        String asAtDesc = (asAtDate != null && !asAtDate.isBlank()) ? asAtDate : "today";
        String title = "Creditors Ageing — " + modeDesc + " | " + dateDesc + " | " + amtDesc + " | as at " + asAtDesc;

        // Column headers: we can't compute dates here without running SQL, so use period labels
        // The frontend will show "P1 (oldest)" through "P6 (current)"
        List<Map<String,Object>> cols;
        if (isDetail) {
            cols = List.of(
                col("Supp No",   "suppNo",  "text"),  col("Supplier", "name",    "text"),
                col("Doc Date",  "docDate", "date"),  col("Type",     "docType", "text"),
                col("Doc No",    "docNo",   "text"),  col("Status",   "status",  "text"),
                col("P1 Oldest","p1","currency"),     col("P2","p2","currency"),
                col("P3","p3","currency"),            col("P4","p4","currency"),
                col("P5","p5","currency"),            col("P6 Current","p6","currency"),
                col("Balance",   "balance", "currency")
            );
        } else {
            cols = List.of(
                col("Supp No",   "suppNo",   "text"), col("Supplier", "name",    "text"),
                col("Location",  "location", "text"), col("Phone",    "phone",   "text"),
                col("P1 Oldest","p1","currency"),     col("P2","p2","currency"),
                col("P3","p3","currency"),            col("P4","p4","currency"),
                col("P5","p5","currency"),            col("P6 Current","p6","currency"),
                col("Total",     "total",    "currency")
            );
        }
        // Build period date labels for column headers via SQL
        List<String> periodDates = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT " +
                "DATE_FORMAT(" + p1 + ",'%d/%m/%Y') d1, DATE_FORMAT(" + p2 + ",'%d/%m/%Y') d2, " +
                "DATE_FORMAT(" + p3 + ",'%d/%m/%Y') d3, DATE_FORMAT(" + p4 + ",'%d/%m/%Y') d4, " +
                "DATE_FORMAT(" + p5 + ",'%d/%m/%Y') d5, DATE_FORMAT(" + p6 + ",'%d/%m/%Y') d6",
                rs -> {
                    for (int i = 1; i <= 6; i++) periodDates.add(rs.getString("d"+i));
                });
        } catch (Exception e) { log.warn("periodDates: {}", e.getMessage()); }

        Map<String,Object> r = new LinkedHashMap<>();
        r.put("columns", cols); r.put("rows", rows); r.put("title", title);
        if (!periodDates.isEmpty()) r.put("periodDates", periodDates);
        return r;
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
