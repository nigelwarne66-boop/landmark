package com.landmarksoftware.service.gl;

import com.landmarksoftware.model.AppSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * General Ledger data service.
 *
 * Tables (confirmed from DDL):
 *   glchart  PK(company_no, acct_main_no, acct_sub_no)
 *            desc1, acct_type VARCHAR(4), pl_bs_ind P=P&L B=BalSheet, dr_cr_ind C=credit-normal D=debit-normal
 *            all_non_posting_flag='Y' = header/group account, not a posting account
 *
 *   glbal    PK(company_no, year_no, acct_main_no, acct_sub_no)
 *            open_bal, bal_01..bal_13 — credit-positive (income positive, expense negative)
 *
 *   gltrx    PK(company_no, year_no, acct_main_no, acct_sub_no, trx_type, jnl_date, seq_no)
 *            dr_amt, cr_amt, source, jnl_no, jnl_date, ref
 *
 *   gldates  PK(company_no, yr_no)  — also indexed on year_no
 *            period_start_01..13, period_end_01..13
 *
 * IMPORTANT: glbal.bal_NN is credit-positive.
 *   Income accounts (pl_bs_ind='P', dr_cr_ind='C') — positive bal = income (correct)
 *   Expense accounts (pl_bs_ind='P', dr_cr_ind='D') — negative bal = expense
 *   So: revenue = SUM(bal) where dr_cr_ind='C', pl_bs_ind='P'
 *       expenses = SUM(-bal) where dr_cr_ind='D', pl_bs_ind='P'
 */
@Service
public class GlDataService {
    private static final Logger log = LoggerFactory.getLogger(GlDataService.class);

    private final JdbcTemplate jdbc;

    public GlDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ── KPI tiles ────────────────────────────────────────────────────────────

    public BigDecimal getRevenueYtd(AppSession s) {
        // Credit-normal income accounts stored as negative in glbal — negate to get positive
        return queryDecimal(
            "SELECT COALESCE(SUM(-1 * (" + allPeriodsSum() + ")), 0) " +
            "FROM glbal b JOIN glchart c " +
            "  ON c.company_no=b.company_no AND c.acct_main_no=b.acct_main_no AND c.acct_sub_no=b.acct_sub_no " +
            "WHERE b.company_no=? AND b.year_no=? AND c.pl_bs_ind='P' AND c.dr_cr_ind='C'",
            s.getCompanyNo(), s.getYearNo());
    }

    public BigDecimal getExpensesYtd(AppSession s) {
        // expenses are stored as negative in glbal (debit-normal accounts), negate to get positive
        return queryDecimal(
            "SELECT COALESCE(SUM(-1 * (" + allPeriodsSum() + ")), 0) " +
            "FROM glbal b JOIN glchart c " +
            "  ON c.company_no=b.company_no AND c.acct_main_no=b.acct_main_no AND c.acct_sub_no=b.acct_sub_no " +
            "WHERE b.company_no=? AND b.year_no=? AND c.pl_bs_ind='P' AND c.dr_cr_ind='D'",
            s.getCompanyNo(), s.getYearNo());
    }

    public BigDecimal getNetProfitYtd(AppSession s) {
        return getRevenueYtd(s).subtract(getExpensesYtd(s));
    }

    public int getJournalCount(AppSession s) {
        try {
            Integer v = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT jnl_no) FROM gltrx WHERE company_no=? AND year_no=?",
                Integer.class, s.getCompanyNo(), s.getYearNo());
            return v != null ? v : 0;
        } catch (Exception e) { return 0; }
    }

    // ── ECharts data ─────────────────────────────────────────────────────────

    /** Revenue vs expenses per period — single query across all 13 period columns. */
    public Map<String, Object> getRevenueVsExpensesByPeriod(AppSession s) {
        List<String> periods = new ArrayList<>();
        List<BigDecimal> revenue = new ArrayList<>();
        List<BigDecimal> expenses = new ArrayList<>();
        try {
            // Use gltrx grouped by period (glbal only populated after period close)
            // Revenue (dr_cr_ind='C'): cr_amt - dr_amt (positive = income)
            // Expense (dr_cr_ind='D'): dr_amt - cr_amt (positive = cost)
            String periodExpr = buildPeriodCaseJoin();
            String sql =
                "SELECT " + periodExpr + " AS period_no, " +
                "  COALESCE(SUM(CASE WHEN c.dr_cr_ind='C' THEN t.cr_amt - t.dr_amt ELSE 0 END), 0) AS rev, " +
                "  COALESCE(SUM(CASE WHEN c.dr_cr_ind='D' THEN t.dr_amt - t.cr_amt ELSE 0 END), 0) AS exp " +
                "FROM gltrx t " +
                "JOIN glchart c ON c.company_no=t.company_no " +
                "  AND c.acct_main_no=t.acct_main_no AND c.acct_sub_no=t.acct_sub_no " +
                "JOIN gldates d ON d.company_no=t.company_no AND d.year_no=t.year_no " +
                "WHERE t.company_no=? AND t.year_no=? AND c.pl_bs_ind='P' " +
                "GROUP BY period_no HAVING period_no IS NOT NULL ORDER BY period_no";

            jdbc.query(sql, rs -> {
                BigDecimal rev = z(rs.getBigDecimal("rev"));
                BigDecimal exp = z(rs.getBigDecimal("exp"));
                if (rev.compareTo(BigDecimal.ZERO) != 0 || exp.compareTo(BigDecimal.ZERO) != 0) {
                    periods.add("P" + rs.getInt("period_no"));
                    revenue.add(rev);
                    expenses.add(exp);
                }
            }, s.getCompanyNo(), s.getYearNo());
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getMessage()); err.put("periods", periods); err.put("revenue", revenue); err.put("expenses", expenses);
            return err;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("periods", periods); r.put("revenue", revenue); r.put("expenses", expenses);
        return r;
    }

    /** Top 10 expense accounts by YTD spend — for donut chart. Uses gltrx (glbal empty until period close). */
    public List<Map<String, Object>> getExpenseBreakdown(AppSession s) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT c.desc1, " +
                "  COALESCE(SUM(t.dr_amt), 0) - COALESCE(SUM(t.cr_amt), 0) AS total " +
                "FROM glchart c " +
                "JOIN gltrx t ON t.company_no=c.company_no " +
                "  AND t.acct_main_no=c.acct_main_no AND t.acct_sub_no=c.acct_sub_no " +
                "  AND t.year_no=? " +
                "WHERE c.company_no=? AND c.pl_bs_ind='P' AND c.dr_cr_ind='D' " +
                "GROUP BY c.acct_main_no, c.acct_sub_no, c.desc1 " +
                "HAVING total > 0 ORDER BY total DESC LIMIT 10",
                rs -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name",  rs.getString("desc1"));
                    item.put("value", rs.getBigDecimal("total"));
                    result.add(item);
                }, s.getYearNo(), s.getCompanyNo());
        } catch (Exception e) { log.error("getExpenseBreakdown: {}", e.getMessage()); }
        return result;
    }

    /** Cumulative P&L by period — area chart. Uses gltrx grouped by period via gldates. */
    public Map<String, Object> getCumulativePnl(AppSession s) {
        List<String> periods = new ArrayList<>();
        List<BigDecimal> cumPnl = new ArrayList<>();
        try {
            // Build a per-period P&L from gltrx using the same period CASE expression as Trial Balance
            // Revenue (dr_cr_ind='C'): profit = cr_amt - dr_amt (credit increases profit)
            // Expense (dr_cr_ind='D'): profit = -(dr_amt - cr_amt) = cr_amt - dr_amt also
            // So net P&L = SUM(cr_amt - dr_amt) across all P&L accounts, per period
            String periodExpr = buildPeriodCaseJoin();
            StringBuilder sb = new StringBuilder(
                "SELECT " + periodExpr + " AS period_no, " +
                "  COALESCE(SUM(CASE WHEN c.dr_cr_ind='C' THEN t.cr_amt - t.dr_amt " +
                "                    WHEN c.dr_cr_ind='D' THEN t.cr_amt - t.dr_amt " +
                "                    ELSE 0 END), 0) AS pnl " +
                "FROM gltrx t " +
                "JOIN glchart c ON c.company_no=t.company_no " +
                "  AND c.acct_main_no=t.acct_main_no AND c.acct_sub_no=t.acct_sub_no " +
                "JOIN gldates d ON d.company_no=t.company_no AND d.year_no=t.year_no " +
                "WHERE t.company_no=? AND t.year_no=? AND c.pl_bs_ind='P' " +
                "GROUP BY period_no HAVING period_no IS NOT NULL ORDER BY period_no");

            BigDecimal[] running = {BigDecimal.ZERO};
            jdbc.query(sb.toString(), rs -> {
                int p = rs.getInt("period_no");
                BigDecimal pnl = z(rs.getBigDecimal("pnl"));
                running[0] = running[0].add(pnl);
                periods.add("P" + p);
                cumPnl.add(running[0]);
            }, s.getCompanyNo(), s.getYearNo());
        } catch (Exception e) { log.error("getCumulativePnl: {}", e.getMessage()); }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("periods", periods); r.put("cumPnl", cumPnl);
        return r;
    }

    // ── Interactive report data ───────────────────────────────────────────────

    /**
     * Trial Balance — glchart + glbal.
     * Uses a subquery approach: select all posting accounts from glchart,
     * LEFT JOIN glbal, filter where there is any balance.
     * No HAVING without GROUP BY — use WHERE on a subquery instead.
     */
    public Map<String, Object> getTrialBalanceData(AppSession s, int fromPeriod, int toPeriod) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String errMsg = null;
        // ── DIAGNOSTIC: log what each table contains ──────────────────────────
        try {
            Integer glchartCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM glchart WHERE company_no=?", Integer.class, s.getCompanyNo());
            Integer glbalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM glbal WHERE company_no=? AND year_no=?", Integer.class, s.getCompanyNo(), s.getYearNo());
            Integer joinCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM glbal b JOIN glchart c ON c.company_no=b.company_no " +
                "AND c.acct_main_no=b.acct_main_no AND c.acct_sub_no=b.acct_sub_no " +
                "WHERE b.company_no=? AND b.year_no=?", Integer.class, s.getCompanyNo(), s.getYearNo());
            Integer leftJoinCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM glchart c LEFT JOIN glbal b ON b.company_no=c.company_no " +
                "AND b.acct_main_no=c.acct_main_no AND b.acct_sub_no=c.acct_sub_no AND b.year_no=? " +
                "WHERE c.company_no=?", Integer.class, s.getYearNo(), s.getCompanyNo());
            log.info("TB DIAG: glchart[co={}]={} rows, glbal[co={},yr={}]={} rows, INNER JOIN={} rows, LEFT JOIN={} rows",
                s.getCompanyNo(), glchartCount, s.getCompanyNo(), s.getYearNo(), glbalCount, joinCount, leftJoinCount);
        } catch (Exception e) { log.error("TB DIAG error: {}", e.getMessage()); }
        // ── END DIAGNOSTIC ─────────────────────────────────────────────────────
        try {
            // Use gltrx for live data (glbal only populated after period close)
            // Show raw debit/credit totals matching Landmark's own TB format
            jdbc.query(
                "SELECT c.acct_main_no, c.acct_sub_no, c.desc1, c.acct_type, c.pl_bs_ind, " +
                "  COALESCE(SUM(t.dr_amt), 0) AS total_debit, " +
                "  COALESCE(SUM(t.cr_amt), 0) AS total_credit " +
                "FROM glchart c " +
                "LEFT JOIN gltrx t ON t.company_no=c.company_no " +
                "  AND t.acct_main_no=c.acct_main_no AND t.acct_sub_no=c.acct_sub_no " +
                "  AND t.year_no=? " +
                "WHERE c.company_no=? " +
                "GROUP BY c.acct_main_no, c.acct_sub_no, c.desc1, c.acct_type, c.pl_bs_ind " +
                "HAVING (COALESCE(SUM(t.dr_amt),0)<>0 OR COALESCE(SUM(t.cr_amt),0)<>0) " +
                "ORDER BY c.acct_main_no, c.acct_sub_no",
                rs -> {
                    int main = rs.getInt("acct_main_no");
                    int sub  = rs.getInt("acct_sub_no");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("account",     sub == 0 ? String.valueOf(main) : main + "." + sub);
                    row.put("description", rs.getString("desc1"));
                    row.put("section",     "P".equals(rs.getString("pl_bs_ind")) ? "P&L" : "Bal Sheet");
                    row.put("type",        rs.getString("acct_type"));
                    row.put("debit",       rs.getBigDecimal("total_debit"));
                    row.put("credit",      rs.getBigDecimal("total_credit"));
                    rows.add(row);
                },
                s.getYearNo(), s.getCompanyNo());
        } catch (Exception e) {
            errMsg = e.getMessage();
        }

        if (errMsg != null) return err(errMsg);
        log.info("Trial balance: {} rows returned for company={} year={} periods={}-{}",
            rows.size(), s.getCompanyNo(), s.getYearNo(), fromPeriod, toPeriod);
        if (rows.isEmpty()) {
            log.warn("Trial balance returned 0 rows. Check: 1) all_non_posting_flag values in glchart, 2) glbal has data for year_no={}", s.getYearNo());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", List.of(
            col("Account",     "account",     "text"),
            col("Description", "description", "text"),
            col("Section",     "section",     "text"),
            col("Type",        "type",        "text"),
            col("Debit",       "debit",       "currency"),
            col("Credit",      "credit",      "currency")
        ));
        result.put("rows", rows);
        result.put("title", "Trial Balance — Periods " + fromPeriod + " to " + toPeriod);
        return result;
    }

    /** Profit & Loss report — glchart + glbal, P&L accounts only. */
    public Map<String, Object> getProfitLossData(AppSession s, int fromPeriod, int toPeriod) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String errMsg = null;
        try {
            String periodBal = rangeSum(fromPeriod, toPeriod, "b");
            jdbc.query(
                "SELECT c.acct_main_no, c.acct_sub_no, c.desc1, c.dr_cr_ind, " +
                "  CASE WHEN c.dr_cr_ind='C' THEN 'Income' ELSE 'Expense' END AS section, " +
                "  COALESCE(" + periodBal + ", 0) AS period_bal " +
                "FROM glchart c " +
                "LEFT JOIN glbal b ON b.company_no=c.company_no " +
                "  AND b.acct_main_no=c.acct_main_no AND b.acct_sub_no=c.acct_sub_no " +
                "  AND b.year_no=? " +
                "WHERE c.company_no=? AND c.pl_bs_ind='P' " +
                "  AND COALESCE(" + periodBal + ", 0) <> 0 " +
                "ORDER BY c.dr_cr_ind DESC, c.acct_main_no, c.acct_sub_no",
                rs -> {
                    BigDecimal bal = rs.getBigDecimal("period_bal");
                    if (bal == null) bal = BigDecimal.ZERO;
                    boolean isExpense = "D".equals(rs.getString("dr_cr_ind"));
                    int main = rs.getInt("acct_main_no");
                    int sub  = rs.getInt("acct_sub_no");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("section",     rs.getString("section"));
                    row.put("account",     sub == 0 ? String.valueOf(main) : main + "." + sub);
                    row.put("description", rs.getString("desc1"));
                    row.put("amount",      isExpense ? bal.negate() : bal);
                    rows.add(row);
                },
                s.getYearNo(), s.getCompanyNo());
        } catch (Exception e) { errMsg = e.getMessage(); }

        if (errMsg != null) return err(errMsg);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", List.of(
            col("Section",     "section",     "text"),
            col("Account",     "account",     "text"),
            col("Description", "description", "text"),
            col("Amount",      "amount",      "currency")
        ));
        result.put("rows", rows);
        result.put("title", "Profit & Loss — Periods " + fromPeriod + " to " + toPeriod);
        return result;
    }

    /** General Journal — from gltrx. Derives period from jnl_date vs gldates. */
    public Map<String, Object> getGeneralJournalData(AppSession s, int fromPeriod, int toPeriod) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String errMsg = null;
        try {
            // Build period start/end dates from gldates for the given range
            // Use a JOIN to gldates and filter on jnl_date BETWEEN period_start_NN AND period_end_NN
            // Simpler: derive period number in a subquery
            String periodExpr = buildPeriodCaseJoin();

            jdbc.query(
                "SELECT t.source, t.jnl_no, t.jnl_date, t.acct_main_no, t.acct_sub_no, " +
                "  COALESCE(c.desc1, '') AS acct_desc, t.ref, t.dr_amt, t.cr_amt, t.audit_user_id, " +
                "  " + periodExpr + " AS period_no " +
                "FROM gltrx t " +
                "LEFT JOIN glchart c ON c.company_no=t.company_no " +
                "  AND c.acct_main_no=t.acct_main_no AND c.acct_sub_no=t.acct_sub_no " +
                "JOIN gldates d ON d.company_no=t.company_no AND d.year_no=t.year_no " +
                "WHERE t.company_no=? AND t.year_no=? " +
                "HAVING period_no BETWEEN ? AND ? " +
                "ORDER BY period_no, t.source, t.jnl_no, t.jnl_date, t.acct_main_no",
                rs -> {
                    int main = rs.getInt("acct_main_no");
                    int sub  = rs.getInt("acct_sub_no");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("period",      rs.getInt("period_no"));
                    row.put("journal",     rs.getString("source") + "-" + rs.getInt("jnl_no"));
                    row.put("date",        rs.getDate("jnl_date") != null ? rs.getDate("jnl_date").toString() : "");
                    row.put("account",     sub == 0 ? String.valueOf(main) : main + "." + sub);
                    row.put("accountDesc", rs.getString("acct_desc"));
                    row.put("reference",   rs.getString("ref"));
                    row.put("debit",       rs.getBigDecimal("dr_amt"));
                    row.put("credit",      rs.getBigDecimal("cr_amt"));
                    row.put("user",        rs.getString("audit_user_id"));
                    rows.add(row);
                },
                s.getCompanyNo(), s.getYearNo(), fromPeriod, toPeriod);
        } catch (Exception e) { errMsg = e.getMessage(); }

        if (errMsg != null) return err(errMsg);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", List.of(
            col("Period",      "period",      "number"),
            col("Journal",     "journal",     "text"),
            col("Date",        "date",        "date"),
            col("Account",     "account",     "text"),
            col("Description", "accountDesc", "text"),
            col("Reference",   "reference",   "text"),
            col("Debit",       "debit",       "currency"),
            col("Credit",      "credit",      "currency"),
            col("User",        "user",        "text")
        ));
        result.put("rows", rows);
        result.put("title", "General Journal — Periods " + fromPeriod + " to " + toPeriod);
        return result;
    }

    // ── SQL helpers ──────────────────────────────────────────────────────────

    /** Sum of ALL 13 period balance columns — for YTD. */
    private String allPeriodsSum() { return rangeSum(1, 13, "b"); }

    /** Sum bal_NN columns in range [from..to] with given table alias. */
    private String rangeSum(int from, int to, String alias) {
        StringBuilder sb = new StringBuilder("(");
        for (int p = from; p <= Math.min(to, 13); p++) {
            if (p > from) sb.append("+");
            sb.append(String.format("COALESCE(%s.bal_%02d, 0)", alias, p));
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * CASE expression to derive period number (1-13) from t.jnl_date,
     * using period_start/end columns from gldates joined as alias 'd'.
     */
    private String buildPeriodCaseJoin() {
        StringBuilder sb = new StringBuilder("CASE ");
        for (int p = 1; p <= 13; p++) {
            sb.append(String.format(
                "WHEN t.jnl_date BETWEEN d.period_start_%02d AND d.period_end_%02d THEN %d ",
                p, p, p));
        }
        sb.append("ELSE 0 END");
        return sb.toString();
    }

    private BigDecimal queryDecimal(String sql, Object... args) {
        try {
            BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, args);
            return v != null ? v : BigDecimal.ZERO;
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private BigDecimal z(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private Map<String,Object> col(String l,String f,String t){return Map.of("label",l,"field",f,"type",t);}
    private Map<String,Object> err(String m){return Map.of("error",m,"columns",List.of(),"rows",List.of(),"title","Error: "+m);}

    /** Account Transactions — gltrx filtered by account, with running balance. */
    public Map<String, Object> getAccountTransactionsData(AppSession s, int fromPeriod, int toPeriod, String acctNo) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String errMsg = null;
        try {
            // Parse acctNo — may be "1000" or "1000.10"
            int mainNo = 0; int subNo = -1;
            if (acctNo != null && !acctNo.isBlank() && !"%".equals(acctNo)) {
                String[] parts = acctNo.split("\\.");
                try { mainNo = Integer.parseInt(parts[0].trim()); } catch (NumberFormatException ignored) {}
                if (parts.length > 1) { try { subNo = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {} }
            }
            String acctFilter = mainNo > 0
                ? (subNo >= 0 ? " AND t.acct_main_no=? AND t.acct_sub_no=? " : " AND t.acct_main_no=? ")
                : "";

            String periodExpr = buildPeriodCaseJoin();
            String sql =
                "SELECT t.source, t.jnl_no, t.jnl_date, t.acct_main_no, t.acct_sub_no, " +
                "  COALESCE(c.desc1,'') AS acct_desc, t.ref, t.dr_amt, t.cr_amt, t.audit_user_id, " +
                "  COALESCE(c.dr_cr_ind,'D') AS dr_cr_ind, " +
                "  " + periodExpr + " AS period_no " +
                "FROM gltrx t " +
                "LEFT JOIN glchart c ON c.company_no=t.company_no " +
                "  AND c.acct_main_no=t.acct_main_no AND c.acct_sub_no=t.acct_sub_no " +
                "JOIN gldates d ON d.company_no=t.company_no AND d.year_no=t.year_no " +
                "WHERE t.company_no=? AND t.year_no=?" + acctFilter +
                "HAVING period_no BETWEEN ? AND ? " +
                "ORDER BY t.acct_main_no, t.acct_sub_no, period_no, t.jnl_date, t.jnl_no";

            // Build params list
            java.util.List<Object> params = new java.util.ArrayList<>();
            params.add(s.getCompanyNo()); params.add(s.getYearNo());
            if (mainNo > 0) { params.add(mainNo); if (subNo >= 0) params.add(subNo); }
            params.add(fromPeriod); params.add(toPeriod);

            // Running balance per account
            final int[] lastMain = {-1}; final int[] lastSub = {-1};
            final java.math.BigDecimal[] runBal = {java.math.BigDecimal.ZERO};
            final String[] lastDrCr = {"D"};

            jdbc.query(sql, rs -> {
                int main = rs.getInt("acct_main_no");
                int sub  = rs.getInt("acct_sub_no");
                if (main != lastMain[0] || sub != lastSub[0]) {
                    runBal[0] = java.math.BigDecimal.ZERO;
                    lastMain[0] = main; lastSub[0] = sub;
                    lastDrCr[0] = rs.getString("dr_cr_ind");
                }
                java.math.BigDecimal dr = rs.getBigDecimal("dr_amt");
                java.math.BigDecimal cr = rs.getBigDecimal("cr_amt");
                if (dr == null) dr = java.math.BigDecimal.ZERO;
                if (cr == null) cr = java.math.BigDecimal.ZERO;
                // For DR-normal accounts balance increases with debits; for CR-normal with credits
                if ("C".equals(lastDrCr[0])) runBal[0] = runBal[0].add(cr).subtract(dr);
                else                          runBal[0] = runBal[0].add(dr).subtract(cr);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("period",      rs.getInt("period_no"));
                row.put("journal",     rs.getString("source") + "-" + rs.getInt("jnl_no"));
                row.put("date",        rs.getDate("jnl_date") != null ? rs.getDate("jnl_date").toString() : "");
                row.put("account",     sub == 0 ? String.valueOf(main) : main + "." + sub);
                row.put("accountDesc", rs.getString("acct_desc"));
                row.put("reference",   rs.getString("ref"));
                row.put("debit",       dr);
                row.put("credit",      cr);
                row.put("balance",     runBal[0]);
                row.put("user",        rs.getString("audit_user_id"));
                rows.add(row);
            }, params.toArray());
        } catch (Exception e) { errMsg = e.getMessage(); log.error("getAccountTransactionsData: {}", e.getMessage()); }

        if (errMsg != null) return err(errMsg);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", List.of(
            col("Period",      "period",      "number"),
            col("Journal",     "journal",     "text"),
            col("Date",        "date",        "date"),
            col("Account",     "account",     "text"),
            col("Description", "accountDesc", "text"),
            col("Reference",   "reference",   "text"),
            col("Debit",       "debit",       "currency"),
            col("Credit",      "credit",      "currency"),
            col("Balance",     "balance",     "currency"),
            col("User",        "user",        "text")
        ));
        result.put("rows",  rows);
        result.put("title", "Account Transactions — Periods " + fromPeriod + " to " + toPeriod +
                            (acctNo != null && !acctNo.isBlank() && !"%".equals(acctNo) ? " — Acct " + acctNo : ""));
        return result;
    }
}
