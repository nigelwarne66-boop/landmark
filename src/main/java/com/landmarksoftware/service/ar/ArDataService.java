package com.landmarksoftware.service.ar;

import com.landmarksoftware.model.AppSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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

    // ── Debtors Ageing Listing (with parameter options) ───────────────────────

    /**
     * @param detailSummary 'D'=detail (one row per transaction), 'S'=summary (customer totals)
     * @param dateInd       'D'=due date (payments use doc_date), 'T'=transaction/doc date, 'P'=posting date
     * @param grossNet      'N'=net outstanding, 'G'=gross original amount
     */
    public Map<String, Object> getDebtorsListingData(AppSession s,
                                                      String detailSummary,
                                                      String dateInd,
                                                      String grossNet,
                                                      String asAtDate) {
        boolean isDetail    = "D".equalsIgnoreCase(detailSummary);
        boolean isGross     = "G".equalsIgnoreCase(grossNet);
        boolean useDocDate  = "T".equalsIgnoreCase(dateInd);
        boolean usePostDate = "P".equalsIgnoreCase(dateInd);

        String asAt = (asAtDate != null && !asAtDate.isBlank()) ? "'" + asAtDate + "'" : "CURDATE()";

        // 6 monthly period-end boundaries working back from as-at date
        String p1 = "LAST_DAY(DATE_SUB(" + asAt + ", INTERVAL 5 MONTH))";
        String p2 = "LAST_DAY(DATE_SUB(" + asAt + ", INTERVAL 4 MONTH))";
        String p3 = "LAST_DAY(DATE_SUB(" + asAt + ", INTERVAL 3 MONTH))";
        String p4 = "LAST_DAY(DATE_SUB(" + asAt + ", INTERVAL 2 MONTH))";
        String p5 = "LAST_DAY(DATE_SUB(" + asAt + ", INTERVAL 1 MONTH))";
        String p6 = asAt;

        // AR: payments use doc_date even when ageing by due date (per artl01 COBOL)
        String dateExpr = useDocDate  ? "t.doc_date" :
                          usePostDate ? "t.posting_date" :
                          "CASE WHEN t.doc_type='P' THEN t.doc_date ELSE t.due_date END";

        String balExpr = isGross
            ? "CASE WHEN t.doc_type='P' THEN t.amt + t.disc_taken ELSE t.amt - t.retent_amt END"
            : "CASE WHEN t.doc_type='P' THEN t.amt + t.disc_taken - t.amt_paid " +
              "ELSE t.amt - t.retent_amt - t.amt_paid - t.disc_taken END";

        String openFilter = "t.trx_status <> 'U' AND (t.amt - t.retent_amt - t.amt_paid - t.disc_taken) <> 0";

        String bucket =
            "CASE WHEN " + dateExpr + " <= " + p1 + " THEN 1" +
            "     WHEN " + dateExpr + " <= " + p2 + " THEN 2" +
            "     WHEN " + dateExpr + " <= " + p3 + " THEN 3" +
            "     WHEN " + dateExpr + " <= " + p4 + " THEN 4" +
            "     WHEN " + dateExpr + " <= " + p5 + " THEN 5" +
            "     WHEN " + dateExpr + " <= " + p6 + " THEN 6" +
            "     ELSE 6 END";

        List<Map<String, Object>> rows = new ArrayList<>();
        String errMsg = null;

        try {
            if (isDetail) {
                String sql =
                    "SELECT c.cust_no, c.name_1, t.doc_date, t.posting_date, t.due_date, " +
                    "  t.doc_type, t.doc_no, t.trx_status, (" + balExpr + ") AS bal, " +
                    "  (" + bucket + ") AS period_bucket " +
                    "FROM arcusts c " +
                    "JOIN artrans t ON t.company_no=c.company_no AND t.cust_no=c.cust_no " +
                    "WHERE c.company_no=? AND " + openFilter +
                    " AND t.doc_date <= " + p6 +
                    " ORDER BY c.name_1, t.doc_date, t.doc_no";
                jdbc.query(sql, rs -> {
                    int b = rs.getInt("period_bucket");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("custNo",  rs.getString("cust_no"));
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
                    "SELECT c.cust_no, c.name_1, c.city, c.state, c.contact_phone, " +
                    "  COALESCE(SUM(CASE WHEN " + dateExpr + " <= " + p1 + " THEN (" + balExpr + ") ELSE 0 END),0) AS p1, " +
                    "  COALESCE(SUM(CASE WHEN " + dateExpr + " > " + p1 + " AND " + dateExpr + " <= " + p2 + " THEN (" + balExpr + ") ELSE 0 END),0) AS p2, " +
                    "  COALESCE(SUM(CASE WHEN " + dateExpr + " > " + p2 + " AND " + dateExpr + " <= " + p3 + " THEN (" + balExpr + ") ELSE 0 END),0) AS p3, " +
                    "  COALESCE(SUM(CASE WHEN " + dateExpr + " > " + p3 + " AND " + dateExpr + " <= " + p4 + " THEN (" + balExpr + ") ELSE 0 END),0) AS p4, " +
                    "  COALESCE(SUM(CASE WHEN " + dateExpr + " > " + p4 + " AND " + dateExpr + " <= " + p5 + " THEN (" + balExpr + ") ELSE 0 END),0) AS p5, " +
                    "  COALESCE(SUM(CASE WHEN " + dateExpr + " > " + p5 + " AND " + dateExpr + " <= " + p6 + " THEN (" + balExpr + ") ELSE 0 END),0) AS p6, " +
                    "  COALESCE(SUM(CASE WHEN " + dateExpr + " <= " + p6 + " THEN (" + balExpr + ") ELSE 0 END),0) AS total " +
                    "FROM arcusts c " +
                    "JOIN artrans t ON t.company_no=c.company_no AND t.cust_no=c.cust_no " +
                    "WHERE c.company_no=? AND " + openFilter + " AND t.doc_date <= " + p6 + " " +
                    "GROUP BY c.cust_no, c.name_1, c.city, c.state, c.contact_phone " +
                    "HAVING total <> 0 ORDER BY c.name_1";
                jdbc.query(sql, rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("custNo",   rs.getString("cust_no"));
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
            log.error("getDebtorsListingData: {}", e.getMessage());
            errMsg = e.getMessage();
        }

        if (errMsg != null) return err(errMsg);

        String amtDesc  = isGross ? "Gross" : "Net Outstanding";
        String dateDesc = useDocDate ? "Doc Date" : (usePostDate ? "Posting Date" : "Due Date");
        String modeDesc = isDetail ? "Detail" : "Summary";
        String asAtDesc = (asAtDate != null && !asAtDate.isBlank()) ? asAtDate : "today";
        String title = "Debtors Ageing — " + modeDesc + " | " + dateDesc + " | " + amtDesc + " | as at " + asAtDesc;

        List<Map<String, Object>> cols;
        if (isDetail) {
            cols = List.of(
                col("Cust No",  "custNo",  "text"),  col("Customer","name",    "text"),
                col("Doc Date", "docDate", "date"),  col("Type",    "docType", "text"),
                col("Doc No",   "docNo",   "text"),  col("Status",  "status",  "text"),
                col("P1 Oldest","p1","currency"),    col("P2","p2","currency"),
                col("P3","p3","currency"),           col("P4","p4","currency"),
                col("P5","p5","currency"),           col("P6 Current","p6","currency"),
                col("Balance",  "balance", "currency")
            );
        } else {
            cols = List.of(
                col("Cust No",  "custNo",   "text"), col("Customer","name",    "text"),
                col("Location", "location", "text"), col("Phone",   "phone",   "text"),
                col("P1 Oldest","p1","currency"),    col("P2","p2","currency"),
                col("P3","p3","currency"),           col("P4","p4","currency"),
                col("P5","p5","currency"),           col("P6 Current","p6","currency"),
                col("Total",    "total",    "currency")
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

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("columns", cols); r.put("rows", rows); r.put("title", title);
        if (!periodDates.isEmpty()) r.put("periodDates", periodDates);
        return r;
    }


    // ── Helpers ───────────────────────────────────────────────────────────────

    private String docTypeLabel(String t) {
        if (t == null) return "";
        return switch (t.trim()) {
            case "I" -> "INV"; case "C" -> "CR";  case "D" -> "DR";
            case "V" -> "VOI"; case "B" -> "BAL"; case "P" -> "PAY";
            default  -> t.trim();
        };
    }

    private String trxStatusLabel(String s) {
        if (s == null || s.isBlank()) return "";
        return "H".equals(s.trim()) ? "HOLD" : "";
    }

    private BigDecimal z(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private Map<String, Object> col(String l, String f, String t) { return Map.of("label", l, "field", f, "type", t); }
    private Map<String, Object> err(String m) { return Map.of("error", m, "columns", List.of(), "rows", List.of(), "title", "Error"); }
}
