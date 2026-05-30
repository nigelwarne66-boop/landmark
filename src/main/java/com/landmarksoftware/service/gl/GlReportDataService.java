package com.landmarksoftware.service.gl;

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
 * General Ledger <b>report</b> data service — one query method per GL report card
 * in the JavaFX Reports Hub ({@code -Preporting} build). Distinct from
 * {@link GlDataService}, which feeds the interactive GL dashboard (KPI tiles +
 * ECharts + on-screen tables); this one returns the {@code rows / params / warning}
 * shape the hub's {@code runJasperReportWithDataSource} consumes.
 *
 * <p>Ported from {@code C:\landmark\cobol\gl2}: Trial Balance {@code gltl01},
 * P&amp;L / Balance Sheet {@code gltl12} (filtered on {@code pl_bs_ind}), General
 * Journal {@code gltl06}, Account Transactions {@code gltl14/15}.
 *
 * <p><b>Balance model (verified against the live extract 2026-05-28):</b>
 * {@code glbal.bal_01..13} are period <i>movements</i>, debit-positive (dr − cr):
 * the full-year sum equals {@code SUM(gltrx.dr_amt − gltrx.cr_amt)} exactly.
 * <ul>
 *   <li>P&amp;L movement for periods [from..to] = Σ bal_from..bal_to (no opening).</li>
 *   <li>Trial-balance / balance-sheet closing balance as-at period N =
 *       {@code open_bal + Σ bal_1..bal_N} (cumulative, includes brought-forward).</li>
 * </ul>
 * Income accounts are {@code dr_cr_ind='C'}, expenses {@code 'D'}; assets {@code 'D'},
 * liabilities/equity {@code 'C'} — all under {@code pl_bs_ind} P (P&amp;L) or B (Balance
 * Sheet). {@code all_non_posting_flag='Y'} marks header/group accounts (excluded).
 *
 * <p>Transaction reports (Journal, Account Transactions) read {@code gltrx} and derive
 * the period from {@code jnl_date} against {@code gldates.period_start/end_NN}.
 */
@Service
public class GlReportDataService {

    private static final Logger log = LoggerFactory.getLogger(GlReportDataService.class);
    private final JdbcTemplate jdbc;

    public GlReportDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** A selectable code with a display label; {@code toString()} drives ComboBox rendering. */
    public record CodeName(String code, String label) {
        @Override public String toString() { return label; }
    }

    // ── Lookups (the COBOL F5 lists) ──────────────────────────────────────────

    /** Main GL accounts, "(All)" first, "main — desc" from glchart. */
    public List<CodeName> getAccounts(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All accounts)"));
        try {
            jdbc.query("SELECT acct_main_no, MIN(desc1) AS desc1 FROM glchart WHERE company_no=? " +
                       "GROUP BY acct_main_no ORDER BY acct_main_no",
                rs -> { String c = String.valueOf(rs.getInt("acct_main_no"));
                        list.add(new CodeName(c, c + " — " + trim(rs.getString("desc1")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getAccounts: {}", e.getMessage()); }
        return list;
    }

    /** Journal source codes, "(All)" first, distinct from gltrx. */
    public List<CodeName> getSources(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All sources)"));
        try {
            jdbc.query("SELECT DISTINCT source FROM gltrx WHERE company_no=? AND source IS NOT NULL AND source<>'' ORDER BY source",
                rs -> { String c = trim(rs.getString("source")); list.add(new CodeName(c, c)); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getSources: {}", e.getMessage()); }
        return list;
    }

    /** Report-group codes from glcodeg ("(All)" first). Empty in the current extract. */
    public List<CodeName> getReportGroups(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All groups)"));
        try {
            jdbc.query("SELECT rpt_group_code, desc1 FROM glcodeg WHERE company_no=? ORDER BY rpt_group_code",
                rs -> { String c = trim(rs.getString("rpt_group_code"));
                        list.add(new CodeName(c, c + " — " + trim(rs.getString("desc1")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getReportGroups (glcodeg likely empty): {}", e.getMessage()); }
        return list;
    }

    // ════════════════════════════════════════════════════════════════════════
    // GLTL01 — Trial Balance   (glbal: open_bal + Σ bal_1..N, debit/credit columns)
    // ════════════════════════════════════════════════════════════════════════

    public record TrialBalanceParams(
            int asAtPeriod, Integer startAcct, Integer endAcct, boolean includeZero) {}

    public Map<String, Object> getTrialBalance(AppSession s, TrialBalanceParams p) {
        int n = clampPeriod(p.asAtPeriod());
        String balExpr = "(COALESCE(b.open_bal,0) + " + rangeSum(1, n, "b") + ")";
        StringBuilder sql = new StringBuilder(
            "SELECT c.acct_main_no, c.acct_sub_no, c.desc1, c.pl_bs_ind, " +
            "       " + balExpr + " AS bal " +
            "FROM glchart c LEFT JOIN glbal b ON b.company_no=c.company_no " +
            "  AND b.acct_main_no=c.acct_main_no AND b.acct_sub_no=c.acct_sub_no AND b.year_no=? " +
            "WHERE c.company_no=? AND COALESCE(c.all_non_posting_flag,'N')<>'Y' ");
        List<Object> args = new ArrayList<>(); args.add(s.getYearNo()); args.add(s.getCompanyNo());
        appendAcctRange(sql, args, "c.acct_main_no", p.startAcct(), p.endAcct());
        if (!p.includeZero()) sql.append(" HAVING bal <> 0 ");
        sql.append(" ORDER BY c.acct_main_no, c.acct_sub_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal bal = z(rs.getBigDecimal("bal"));
                BigDecimal debit = bal.signum() > 0 ? bal : BigDecimal.ZERO;
                BigDecimal credit = bal.signum() < 0 ? bal.negate() : BigDecimal.ZERO;
                tot[0] = tot[0].add(debit); tot[1] = tot[1].add(credit);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("acctMain", acctMain(rs.getInt("acct_main_no")));
                r.put("acctSub",  acctSub(rs.getInt("acct_sub_no")));
                r.put("description", rs.getString("desc1"));
                r.put("section", "P".equals(trim(rs.getString("pl_bs_ind"))) ? "P&L" : "Balance Sheet");
                r.put("debit", debit);
                r.put("credit", credit);
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getTrialBalance: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No account balances matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        LocalDate ped = periodEndDate(s.getCompanyNo(), s.getYearNo(), n);
        String pedStr = ped != null ? ped.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) : ("Period " + n);
        params.put("AS_AT_DESC", "As at " + pedStr);
        params.put("ACCT_RANGE", acctRangeDesc(p.startAcct(), p.endAcct()));
        params.put("ZERO_DESC", p.includeZero() ? "Including zero balances" : "Non-zero balances only");
        params.put("SUM_DEBIT", tot[0]); params.put("SUM_CREDIT", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // GLTL12 (pl_bs_ind='P') — Profit & Loss   (glbal: Σ bal_from..to, no opening)
    // ════════════════════════════════════════════════════════════════════════

    public record ProfitLossParams(
            int fromPeriod, int toPeriod, Integer startAcct, Integer endAcct, boolean includeZero) {}

    public Map<String, Object> getProfitLoss(AppSession s, ProfitLossParams p) {
        int from = clampPeriod(p.fromPeriod()), to = clampPeriod(p.toPeriod());
        if (to < from) { int t = from; from = to; to = t; }
        String movExpr = rangeSum(from, to, "b");
        StringBuilder sql = new StringBuilder(
            "SELECT c.acct_main_no, c.acct_sub_no, c.desc1, c.dr_cr_ind, " +
            "       COALESCE(" + movExpr + ",0) AS mov " +
            "FROM glchart c LEFT JOIN glbal b ON b.company_no=c.company_no " +
            "  AND b.acct_main_no=c.acct_main_no AND b.acct_sub_no=c.acct_sub_no AND b.year_no=? " +
            "WHERE c.company_no=? AND c.pl_bs_ind='P' AND COALESCE(c.all_non_posting_flag,'N')<>'Y' ");
        List<Object> args = new ArrayList<>(); args.add(s.getYearNo()); args.add(s.getCompanyNo());
        appendAcctRange(sql, args, "c.acct_main_no", p.startAcct(), p.endAcct());
        if (!p.includeZero()) sql.append(" HAVING mov <> 0 ");
        sql.append(" ORDER BY c.dr_cr_ind DESC, c.acct_main_no, c.acct_sub_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };  // income, expense
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal mov = z(rs.getBigDecimal("mov"));          // debit-positive (dr − cr)
                boolean income = "C".equals(trim(rs.getString("dr_cr_ind")));
                BigDecimal amt = income ? mov.negate() : mov;        // present both as positive
                if (income) tot[0] = tot[0].add(amt); else tot[1] = tot[1].add(amt);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("section", income ? "Income" : "Expense");
                r.put("acctMain", acctMain(rs.getInt("acct_main_no")));
                r.put("acctSub",  acctSub(rs.getInt("acct_sub_no")));
                r.put("description", rs.getString("desc1"));
                r.put("amount", amt);
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getProfitLoss: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No P&L account movements matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        LocalDate d1 = periodEndDate(s.getCompanyNo(), s.getYearNo(), from);
        LocalDate d2 = periodEndDate(s.getCompanyNo(), s.getYearNo(), to);
        java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String p1 = d1 != null ? d1.format(df) : ("period " + from);
        String p2 = d2 != null ? d2.format(df) : ("period " + to);
        params.put("PERIOD_RANGE", p1 + " to " + p2);
        params.put("ACCT_RANGE", acctRangeDesc(p.startAcct(), p.endAcct()));
        params.put("SUM_INCOME", tot[0]); params.put("SUM_EXPENSE", tot[1]);
        params.put("NET_PROFIT", tot[0].subtract(tot[1])); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // GLTL12 (pl_bs_ind='B') — Balance Sheet   (glbal: open_bal + Σ bal_1..N)
    // ════════════════════════════════════════════════════════════════════════

    public record BalanceSheetParams(
            int asAtPeriod, Integer startAcct, Integer endAcct, boolean includeZero) {}

    public Map<String, Object> getBalanceSheet(AppSession s, BalanceSheetParams p) {
        int n = clampPeriod(p.asAtPeriod());
        String balExpr = "(COALESCE(b.open_bal,0) + " + rangeSum(1, n, "b") + ")";
        StringBuilder sql = new StringBuilder(
            "SELECT c.acct_main_no, c.acct_sub_no, c.desc1, c.dr_cr_ind, " +
            "       " + balExpr + " AS bal " +
            "FROM glchart c LEFT JOIN glbal b ON b.company_no=c.company_no " +
            "  AND b.acct_main_no=c.acct_main_no AND b.acct_sub_no=c.acct_sub_no AND b.year_no=? " +
            "WHERE c.company_no=? AND c.pl_bs_ind='B' AND COALESCE(c.all_non_posting_flag,'N')<>'Y' ");
        List<Object> args = new ArrayList<>(); args.add(s.getYearNo()); args.add(s.getCompanyNo());
        appendAcctRange(sql, args, "c.acct_main_no", p.startAcct(), p.endAcct());
        if (!p.includeZero()) sql.append(" HAVING bal <> 0 ");
        sql.append(" ORDER BY c.dr_cr_ind, c.acct_main_no, c.acct_sub_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };  // assets, liab+equity
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal bal = z(rs.getBigDecimal("bal"));
                boolean asset = "D".equals(trim(rs.getString("dr_cr_ind")));
                BigDecimal shown = asset ? bal : bal.negate();
                if (asset) tot[0] = tot[0].add(shown); else tot[1] = tot[1].add(shown);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("section", asset ? "1-Assets" : "2-Liabilities & Equity");
                r.put("acctMain", acctMain(rs.getInt("acct_main_no")));
                r.put("acctSub",  acctSub(rs.getInt("acct_sub_no")));
                r.put("description", rs.getString("desc1"));
                r.put("balance", shown);
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getBalanceSheet: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No balance-sheet account balances matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        LocalDate ped2 = periodEndDate(s.getCompanyNo(), s.getYearNo(), n);
        String pedStr2 = ped2 != null ? ped2.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) : ("Period " + n);
        params.put("AS_AT_DESC", "As at " + pedStr2);
        params.put("ACCT_RANGE", acctRangeDesc(p.startAcct(), p.endAcct()));
        params.put("SUM_ASSETS", tot[0]); params.put("SUM_LIAB_EQUITY", tot[1]);
        params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // GLTL06 — General Journal   (gltrx, by source / journal / date)
    // ════════════════════════════════════════════════════════════════════════

    public record JournalParams(
            String source, Integer startJnl, Integer endJnl, LocalDate startDate, LocalDate endDate,
            boolean summary) {}

    public Map<String, Object> getGeneralJournal(AppSession s, JournalParams p) {
        if (p.summary()) return generalJournalSummary(s, p);
        StringBuilder sql = new StringBuilder(
            "SELECT t.source, t.jnl_no, t.jnl_date, t.acct_main_no, t.acct_sub_no, " +
            "       COALESCE(c.desc1,'') AS acct_desc, t.ref, t.dr_amt, t.cr_amt, t.audit_user_id " +
            "FROM gltrx t LEFT JOIN glchart c ON c.company_no=t.company_no " +
            "  AND c.acct_main_no=t.acct_main_no AND c.acct_sub_no=t.acct_sub_no " +
            "WHERE t.company_no=? AND t.year_no=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo()); args.add(s.getYearNo());
        appendJournalFilters(sql, args, p);
        sql.append(" ORDER BY t.source, t.jnl_no, t.jnl_date, t.acct_main_no, t.acct_sub_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal dr = z(rs.getBigDecimal("dr_amt")), cr = z(rs.getBigDecimal("cr_amt"));
                tot[0] = tot[0].add(dr); tot[1] = tot[1].add(cr);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("source", trim(rs.getString("source")));
                r.put("journal", trim(rs.getString("source")) + "-" + rs.getInt("jnl_no"));
                r.put("jnlDate", sqlDate(rs.getDate("jnl_date")));
                r.put("acctMain", acctMain(rs.getInt("acct_main_no")));
                r.put("acctSub",  acctSub(rs.getInt("acct_sub_no")));
                r.put("description", rs.getString("acct_desc"));
                r.put("reference", trim(rs.getString("ref")));
                r.put("debit", dr); r.put("credit", cr);
                r.put("user", trim(rs.getString("audit_user_id")));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getGeneralJournal: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No journal transactions matched the selection.");
        Map<String, Object> params = journalParams(p);
        params.put("SUM_DEBIT", tot[0]); params.put("SUM_CREDIT", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    private Map<String, Object> generalJournalSummary(AppSession s, JournalParams p) {
        StringBuilder sql = new StringBuilder(
            "SELECT t.source, t.jnl_no, MIN(t.jnl_date) AS jnl_date, COUNT(*) AS line_count, " +
            "       SUM(t.dr_amt) AS dr, SUM(t.cr_amt) AS cr, MAX(t.ref) AS ref " +
            "FROM gltrx t WHERE t.company_no=? AND t.year_no=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo()); args.add(s.getYearNo());
        appendJournalFilters(sql, args, p);
        sql.append(" GROUP BY t.source, t.jnl_no ORDER BY t.source, t.jnl_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal dr = z(rs.getBigDecimal("dr")), cr = z(rs.getBigDecimal("cr"));
                tot[0] = tot[0].add(dr); tot[1] = tot[1].add(cr);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("source", trim(rs.getString("source")));
                r.put("journal", trim(rs.getString("source")) + "-" + rs.getInt("jnl_no"));
                r.put("jnlDate", sqlDate(rs.getDate("jnl_date")));
                r.put("description", trim(rs.getString("ref")));
                r.put("reference", "");
                r.put("lineCount", rs.getInt("line_count"));
                r.put("debit", dr); r.put("credit", cr);
                r.put("acctMain", ""); r.put("acctSub", ""); r.put("user", "");
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("generalJournalSummary: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No journal transactions matched the selection.");
        Map<String, Object> params = journalParams(p);
        params.put("SUM_DEBIT", tot[0]); params.put("SUM_CREDIT", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // GLTL14/15 — Account Transactions   (gltrx, by account, with running balance)
    // ════════════════════════════════════════════════════════════════════════

    public record AccountTxnParams(
            Integer startAcct, Integer endAcct, String source, String startAnalysis, String endAnalysis,
            LocalDate startDate, LocalDate endDate) {}

    public Map<String, Object> getAccountTransactions(AppSession s, AccountTxnParams p) {
        StringBuilder sql = new StringBuilder(
            "SELECT t.acct_main_no, t.acct_sub_no, COALESCE(c.desc1,'') AS acct_desc, COALESCE(c.dr_cr_ind,'D') AS dr_cr_ind, " +
            "       t.source, t.jnl_no, t.jnl_date, t.ref, t.analysis_code, t.dr_amt, t.cr_amt, t.audit_user_id " +
            "FROM gltrx t LEFT JOIN glchart c ON c.company_no=t.company_no " +
            "  AND c.acct_main_no=t.acct_main_no AND c.acct_sub_no=t.acct_sub_no " +
            "WHERE t.company_no=? AND t.year_no=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo()); args.add(s.getYearNo());
        appendAcctRange(sql, args, "t.acct_main_no", p.startAcct(), p.endAcct());
        if (notBlank(p.source())) { sql.append(" AND t.source=? "); args.add(p.source()); }
        appendRange(sql, args, "t.analysis_code", p.startAnalysis(), p.endAnalysis());
        appendDate(sql, args, "t.jnl_date", p.startDate(), p.endDate());
        sql.append(" ORDER BY t.acct_main_no, t.acct_sub_no, t.jnl_date, t.source, t.jnl_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        int[] lastMain = { Integer.MIN_VALUE }, lastSub = { Integer.MIN_VALUE };
        BigDecimal[] run = { BigDecimal.ZERO }; String[] drcr = { "D" };
        try {
            jdbc.query(sql.toString(), rs -> {
                int main = rs.getInt("acct_main_no"), sub = rs.getInt("acct_sub_no");
                if (main != lastMain[0] || sub != lastSub[0]) {
                    run[0] = BigDecimal.ZERO; lastMain[0] = main; lastSub[0] = sub;
                    drcr[0] = trim(rs.getString("dr_cr_ind"));
                }
                BigDecimal dr = z(rs.getBigDecimal("dr_amt")), cr = z(rs.getBigDecimal("cr_amt"));
                run[0] = "C".equals(drcr[0]) ? run[0].add(cr).subtract(dr) : run[0].add(dr).subtract(cr);
                tot[0] = tot[0].add(dr); tot[1] = tot[1].add(cr);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("account", acct(main, sub));
                r.put("acctMain", acctMain(main));
                r.put("acctSub",  acctSub(sub));
                r.put("description", rs.getString("acct_desc"));
                r.put("jnlDate", sqlDate(rs.getDate("jnl_date")));
                r.put("source", trim(rs.getString("source")));
                r.put("journal", trim(rs.getString("source")) + "-" + rs.getInt("jnl_no"));
                r.put("reference", trim(rs.getString("ref")));
                r.put("analysisCode", trim(rs.getString("analysis_code")));
                r.put("debit", dr); r.put("credit", cr); r.put("balance", run[0]);
                r.put("user", trim(rs.getString("audit_user_id")));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getAccountTransactions: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No account transactions matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ACCT_RANGE", acctRangeDesc(p.startAcct(), p.endAcct()));
        params.put("SOURCE_DESC", notBlank(p.source()) ? p.source() : "All sources");
        params.put("ANALYSIS_RANGE", rangeDesc(p.startAnalysis(), p.endAnalysis(), "analysis codes"));
        params.put("DATE_RANGE", dateDesc(p.startDate(), p.endDate()));
        params.put("SUM_DEBIT", tot[0]); params.put("SUM_CREDIT", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ── shared filter builders ────────────────────────────────────────────────

    private void appendJournalFilters(StringBuilder sql, List<Object> args, JournalParams p) {
        if (notBlank(p.source())) { sql.append(" AND t.source=? "); args.add(p.source()); }
        if (p.startJnl() != null && p.startJnl() > 0) { int e = (p.endJnl() != null && p.endJnl() > 0) ? p.endJnl() : 999999999;
            sql.append(" AND t.jnl_no BETWEEN ? AND ? "); args.add(p.startJnl()); args.add(e); }
        appendDate(sql, args, "t.jnl_date", p.startDate(), p.endDate());
    }

    private Map<String, Object> journalParams(JournalParams p) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SOURCE_DESC", notBlank(p.source()) ? p.source() : "All sources");
        params.put("JNL_RANGE", (p.startJnl() != null && p.startJnl() > 0)
            ? p.startJnl() + " to " + (p.endJnl() != null && p.endJnl() > 0 ? p.endJnl() : "end") : "All journals");
        params.put("DATE_RANGE", dateDesc(p.startDate(), p.endDate()));
        params.put("MODE_DESC", p.summary() ? "Summary" : "Detail");
        return params;
    }

    private void appendAcctRange(StringBuilder sql, List<Object> args, String col, Integer start, Integer end) {
        if (start != null && start > 0) { int e = (end != null && end > 0) ? end : 999999999;
            sql.append(" AND ").append(col).append(" BETWEEN ? AND ? "); args.add(start); args.add(e); }
        else if (end != null && end > 0) { sql.append(" AND ").append(col).append(" <= ? "); args.add(end); }
    }

    private void appendRange(StringBuilder sql, List<Object> args, String col, String start, String end) {
        if (notBlank(start)) { String e = notBlank(end) ? end : "zzzzzzzzzzzzzzzzzzzz";
            sql.append(" AND ").append(col).append(" BETWEEN ? AND ? "); args.add(start); args.add(e); }
        else if (notBlank(end)) { sql.append(" AND ").append(col).append(" <= ? "); args.add(end); }
    }

    private void appendDate(StringBuilder sql, List<Object> args, String col, LocalDate start, LocalDate end) {
        if (start != null) { LocalDate e = end != null ? end : LocalDate.of(9999,12,31);
            sql.append(" AND ").append(col).append(" BETWEEN ? AND ? "); args.add(Date.valueOf(start)); args.add(Date.valueOf(e)); }
        else if (end != null) { sql.append(" AND ").append(col).append(" <= ? "); args.add(Date.valueOf(end)); }
    }

    /** Σ bal_from..bal_to (debit-positive period movements), alias e.g. "b". */
    private String rangeSum(int from, int to, String alias) {
        StringBuilder sb = new StringBuilder("(");
        for (int pp = Math.max(1, from); pp <= Math.min(to, 13); pp++) {
            if (sb.length() > 1) sb.append("+");
            sb.append(String.format("COALESCE(%s.bal_%02d,0)", alias, pp));
        }
        if (sb.length() == 1) sb.append("0");
        sb.append(")");
        return sb.toString();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static int clampPeriod(int p) { return p < 1 ? 1 : Math.min(p, 13); }

    private static String acct(int main, int sub) { return sub == 0 ? String.valueOf(main) : main + "." + sub; }
    private static String acctMain(int main) { return String.valueOf(main); }
    private static String acctSub(int sub)   { return sub > 0 ? String.valueOf(sub) : "0"; }

    private LocalDate periodEndDate(int companyNo, int yearNo, int periodNo) {
        String col = "period_end_" + String.format("%02d", periodNo);
        try {
            java.sql.Date d = jdbc.queryForObject(
                "SELECT " + col + " FROM gldates WHERE company_no=? AND year_no=? LIMIT 1",
                java.sql.Date.class, companyNo, yearNo);
            return d != null ? d.toLocalDate() : null;
        } catch (Exception e) { return null; }
    }

    private static String acctRangeDesc(Integer start, Integer end) {
        if (start != null && start > 0) return start + " to " + (end != null && end > 0 ? end : "end");
        if (end != null && end > 0) return "up to " + end;
        return "All accounts";
    }

    private static String rangeDesc(String start, String end, String noun) {
        if (notBlank(start)) return start + " to " + (notBlank(end) ? end : "end");
        if (notBlank(end)) return "up to " + end;
        return "All " + noun;
    }

    private static String dateDesc(LocalDate start, LocalDate end) {
        if (start != null) return start + " to " + (end != null ? end : "…");
        if (end != null) return "up to " + end;
        return "All dates";
    }

    private Map<String, Object> result(List<Map<String, Object>> rows, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows); m.put("params", params); m.put("rowCount", rows.size());
        return m;
    }

    static java.sql.Date sqlDate(java.sql.Date d) {
        if (d == null) return null;
        return d.toLocalDate().isAfter(LocalDate.of(1900, 1, 1)) ? d : null;
    }
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
