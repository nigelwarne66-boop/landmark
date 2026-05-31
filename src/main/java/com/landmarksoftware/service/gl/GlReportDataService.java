package com.landmarksoftware.service.gl;

import com.landmarksoftware.model.AppSession;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.landmarksoftware.db.tables.Glbal.GLBAL;
import static com.landmarksoftware.db.tables.Glchart.GLCHART;
import static com.landmarksoftware.db.tables.Glcodeg.GLCODEG;
import static com.landmarksoftware.db.tables.Gldates.GLDATES;
import static com.landmarksoftware.db.tables.Gltrx.GLTRX;

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
 * <p>Migrated from JdbcTemplate to jOOQ DSLContext.
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
 *
 * <p><b>Inline-SQL notes:</b> The period-movement balance expressions
 * ({@code COALESCE(b.bal_01,0)+…}) are built by {@link #rangeSum} as plain SQL strings
 * and injected via {@code DSL.field(expr, BigDecimal.class)}. This is intentional:
 * the number of summed periods (1–13) is a runtime parameter and cannot be expressed
 * with jOOQ's typed API without generating 13 separate conditional field references.
 * The dynamic period-end column in {@link #periodEndDate} likewise uses
 * {@code DSL.field(name, LocalDate.class)}.
 */
@Service
public class GlReportDataService {

    private static final Logger log = LoggerFactory.getLogger(GlReportDataService.class);
    private final DSLContext dsl;

    public GlReportDataService(DSLContext dsl) { this.dsl = dsl; }

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
            dsl.select(GLCHART.ACCT_MAIN_NO, DSL.min(GLCHART.DESC1).as("desc1"))
               .from(GLCHART)
               .where(GLCHART.COMPANY_NO.eq(s.getCompanyNo()))
               .groupBy(GLCHART.ACCT_MAIN_NO)
               .orderBy(GLCHART.ACCT_MAIN_NO)
               .fetch()
               .forEach(r -> {
                   String c = String.valueOf(r.get(GLCHART.ACCT_MAIN_NO));
                   list.add(new CodeName(c, c + " — " + trim(r.get("desc1", String.class))));
               });
        } catch (Exception e) { log.warn("getAccounts: {}", e.getMessage()); }
        return list;
    }

    /** Journal source codes, "(All)" first, distinct from gltrx. */
    public List<CodeName> getSources(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All sources)"));
        try {
            dsl.selectDistinct(GLTRX.SOURCE)
               .from(GLTRX)
               .where(GLTRX.COMPANY_NO.eq(s.getCompanyNo())
                   .and(GLTRX.SOURCE.isNotNull())
                   .and(GLTRX.SOURCE.ne("")))
               .orderBy(GLTRX.SOURCE)
               .fetch()
               .forEach(r -> { String c = trim(r.get(GLTRX.SOURCE)); list.add(new CodeName(c, c)); });
        } catch (Exception e) { log.warn("getSources: {}", e.getMessage()); }
        return list;
    }

    /** Report-group codes from glcodeg ("(All)" first). Empty in the current extract. */
    public List<CodeName> getReportGroups(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All groups)"));
        try {
            dsl.select(GLCODEG.RPT_GROUP_CODE, GLCODEG.DESC1)
               .from(GLCODEG)
               .where(GLCODEG.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(GLCODEG.RPT_GROUP_CODE)
               .fetch()
               .forEach(r -> {
                   String c = trim(r.get(GLCODEG.RPT_GROUP_CODE));
                   list.add(new CodeName(c, c + " — " + trim(r.get(GLCODEG.DESC1))));
               });
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
        // Inline SQL: dynamic period sum — see class Javadoc for rationale
        String balExpr = "(COALESCE(glbal.open_bal,0) + " + rangeSum(1, n, "glbal") + ")";
        Field<BigDecimal> balField = DSL.field(balExpr, BigDecimal.class).as("bal");

        // Build conditions. Non-zero filter applied to WHERE (no GROUP BY, so WHERE ≡ HAVING here).
        Condition where = GLCHART.COMPANY_NO.eq(s.getCompanyNo())
            .and(DSL.coalesce(GLCHART.ALL_NON_POSTING_FLAG, "N").ne("Y"));
        where = appendAcctRange(where, GLCHART.ACCT_MAIN_NO, p.startAcct(), p.endAcct());
        if (!p.includeZero()) where = where.and(DSL.field(balExpr, BigDecimal.class).ne(BigDecimal.ZERO));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            var results = dsl
                .select(GLCHART.ACCT_MAIN_NO, GLCHART.ACCT_SUB_NO, GLCHART.DESC1,
                        GLCHART.PL_BS_IND, balField)
                .from(GLCHART)
                .leftJoin(GLBAL)
                    .on(GLBAL.COMPANY_NO.eq(GLCHART.COMPANY_NO)
                        .and(GLBAL.ACCT_MAIN_NO.eq(GLCHART.ACCT_MAIN_NO))
                        .and(GLBAL.ACCT_SUB_NO.eq(GLCHART.ACCT_SUB_NO))
                        .and(GLBAL.YEAR_NO.eq(s.getYearNo())))
                .where(where)
                .orderBy(GLCHART.ACCT_MAIN_NO, GLCHART.ACCT_SUB_NO)
                .fetch();

            results.forEach(r -> {
                BigDecimal bal = z(r.get("bal", BigDecimal.class));
                BigDecimal debit = bal.signum() > 0 ? bal : BigDecimal.ZERO;
                BigDecimal credit = bal.signum() < 0 ? bal.negate() : BigDecimal.ZERO;
                tot[0] = tot[0].add(debit); tot[1] = tot[1].add(credit);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("acctMain", acctMain(r.get(GLCHART.ACCT_MAIN_NO)));
                row.put("acctSub",  acctSub(r.get(GLCHART.ACCT_SUB_NO)));
                row.put("description", r.get(GLCHART.DESC1));
                row.put("section", "P".equals(trim(r.get(GLCHART.PL_BS_IND))) ? "P&L" : "Balance Sheet");
                row.put("debit", debit);
                row.put("credit", credit);
                rows.add(row);
            });
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
        // Inline SQL: dynamic period sum — see class Javadoc for rationale
        String movExpr = "COALESCE(" + rangeSum(from, to, "glbal") + ",0)";
        Field<BigDecimal> movField = DSL.field(movExpr, BigDecimal.class).as("mov");

        // Non-zero filter applied to WHERE (no GROUP BY, so WHERE ≡ HAVING here).
        Condition where = GLCHART.COMPANY_NO.eq(s.getCompanyNo())
            .and(GLCHART.PL_BS_IND.eq("P"))
            .and(DSL.coalesce(GLCHART.ALL_NON_POSTING_FLAG, "N").ne("Y"));
        where = appendAcctRange(where, GLCHART.ACCT_MAIN_NO, p.startAcct(), p.endAcct());
        if (!p.includeZero()) where = where.and(DSL.field(movExpr, BigDecimal.class).ne(BigDecimal.ZERO));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };  // income, expense
        try {
            var results = dsl
                .select(GLCHART.ACCT_MAIN_NO, GLCHART.ACCT_SUB_NO, GLCHART.DESC1,
                        GLCHART.DR_CR_IND, movField)
                .from(GLCHART)
                .leftJoin(GLBAL)
                    .on(GLBAL.COMPANY_NO.eq(GLCHART.COMPANY_NO)
                        .and(GLBAL.ACCT_MAIN_NO.eq(GLCHART.ACCT_MAIN_NO))
                        .and(GLBAL.ACCT_SUB_NO.eq(GLCHART.ACCT_SUB_NO))
                        .and(GLBAL.YEAR_NO.eq(s.getYearNo())))
                .where(where)
                .orderBy(GLCHART.DR_CR_IND.desc(), GLCHART.ACCT_MAIN_NO, GLCHART.ACCT_SUB_NO)
                .fetch();

            results.forEach(r -> {
                BigDecimal mov = z(r.get("mov", BigDecimal.class));   // debit-positive (dr − cr)
                boolean income = "C".equals(trim(r.get(GLCHART.DR_CR_IND)));
                BigDecimal amt = income ? mov.negate() : mov;         // present both as positive
                if (income) tot[0] = tot[0].add(amt); else tot[1] = tot[1].add(amt);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("section", income ? "Income" : "Expense");
                row.put("acctMain", acctMain(r.get(GLCHART.ACCT_MAIN_NO)));
                row.put("acctSub",  acctSub(r.get(GLCHART.ACCT_SUB_NO)));
                row.put("description", r.get(GLCHART.DESC1));
                row.put("amount", amt);
                rows.add(row);
            });
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
        // Inline SQL: dynamic period sum — see class Javadoc for rationale
        String balExpr = "(COALESCE(glbal.open_bal,0) + " + rangeSum(1, n, "glbal") + ")";
        Field<BigDecimal> balField = DSL.field(balExpr, BigDecimal.class).as("bal");

        // Non-zero filter applied to WHERE (no GROUP BY, so WHERE ≡ HAVING here).
        Condition where = GLCHART.COMPANY_NO.eq(s.getCompanyNo())
            .and(GLCHART.PL_BS_IND.eq("B"))
            .and(DSL.coalesce(GLCHART.ALL_NON_POSTING_FLAG, "N").ne("Y"));
        where = appendAcctRange(where, GLCHART.ACCT_MAIN_NO, p.startAcct(), p.endAcct());
        if (!p.includeZero()) where = where.and(DSL.field(balExpr, BigDecimal.class).ne(BigDecimal.ZERO));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };  // assets, liab+equity
        try {
            var results = dsl
                .select(GLCHART.ACCT_MAIN_NO, GLCHART.ACCT_SUB_NO, GLCHART.DESC1,
                        GLCHART.DR_CR_IND, balField)
                .from(GLCHART)
                .leftJoin(GLBAL)
                    .on(GLBAL.COMPANY_NO.eq(GLCHART.COMPANY_NO)
                        .and(GLBAL.ACCT_MAIN_NO.eq(GLCHART.ACCT_MAIN_NO))
                        .and(GLBAL.ACCT_SUB_NO.eq(GLCHART.ACCT_SUB_NO))
                        .and(GLBAL.YEAR_NO.eq(s.getYearNo())))
                .where(where)
                .orderBy(GLCHART.DR_CR_IND, GLCHART.ACCT_MAIN_NO, GLCHART.ACCT_SUB_NO)
                .fetch();

            results.forEach(r -> {
                BigDecimal bal = z(r.get("bal", BigDecimal.class));
                boolean asset = "D".equals(trim(r.get(GLCHART.DR_CR_IND)));
                BigDecimal shown = asset ? bal : bal.negate();
                if (asset) tot[0] = tot[0].add(shown); else tot[1] = tot[1].add(shown);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("section", asset ? "1-Assets" : "2-Liabilities & Equity");
                row.put("acctMain", acctMain(r.get(GLCHART.ACCT_MAIN_NO)));
                row.put("acctSub",  acctSub(r.get(GLCHART.ACCT_SUB_NO)));
                row.put("description", r.get(GLCHART.DESC1));
                row.put("balance", shown);
                rows.add(row);
            });
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

        Condition where = GLTRX.COMPANY_NO.eq(s.getCompanyNo()).and(GLTRX.YEAR_NO.eq(s.getYearNo()));
        where = appendJournalFilters(where, p);

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(GLTRX.SOURCE, GLTRX.JNL_NO, GLTRX.JNL_DATE,
                       GLTRX.ACCT_MAIN_NO, GLTRX.ACCT_SUB_NO,
                       DSL.coalesce(GLCHART.DESC1, "").as("acct_desc"),
                       GLTRX.REF, GLTRX.DR_AMT, GLTRX.CR_AMT, GLTRX.AUDIT_USER_ID)
               .from(GLTRX)
               .leftJoin(GLCHART)
                   .on(GLCHART.COMPANY_NO.eq(GLTRX.COMPANY_NO)
                       .and(GLCHART.ACCT_MAIN_NO.eq(GLTRX.ACCT_MAIN_NO))
                       .and(GLCHART.ACCT_SUB_NO.eq(GLTRX.ACCT_SUB_NO)))
               .where(where)
               .orderBy(GLTRX.SOURCE, GLTRX.JNL_NO, GLTRX.JNL_DATE, GLTRX.ACCT_MAIN_NO, GLTRX.ACCT_SUB_NO)
               .fetch()
               .forEach(r -> {
                   BigDecimal dr = z(r.get(GLTRX.DR_AMT)), cr = z(r.get(GLTRX.CR_AMT));
                   tot[0] = tot[0].add(dr); tot[1] = tot[1].add(cr);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("source", trim(r.get(GLTRX.SOURCE)));
                   row.put("journal", trim(r.get(GLTRX.SOURCE)) + "-" + r.get(GLTRX.JNL_NO));
                   row.put("jnlDate", localDateToSqlDate(r.get(GLTRX.JNL_DATE)));
                   row.put("acctMain", acctMain(r.get(GLTRX.ACCT_MAIN_NO)));
                   row.put("acctSub",  acctSub(r.get(GLTRX.ACCT_SUB_NO)));
                   row.put("description", r.get("acct_desc", String.class));
                   row.put("reference", trim(r.get(GLTRX.REF)));
                   row.put("debit", dr); row.put("credit", cr);
                   row.put("user", trim(r.get(GLTRX.AUDIT_USER_ID)));
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getGeneralJournal: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No journal transactions matched the selection.");
        Map<String, Object> params = journalParams(p);
        params.put("SUM_DEBIT", tot[0]); params.put("SUM_CREDIT", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    private Map<String, Object> generalJournalSummary(AppSession s, JournalParams p) {
        Condition where = GLTRX.COMPANY_NO.eq(s.getCompanyNo()).and(GLTRX.YEAR_NO.eq(s.getYearNo()));
        where = appendJournalFilters(where, p);

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(GLTRX.SOURCE, GLTRX.JNL_NO,
                       DSL.min(GLTRX.JNL_DATE).as("jnl_date"),
                       DSL.count().as("line_count"),
                       DSL.sum(GLTRX.DR_AMT).as("dr"),
                       DSL.sum(GLTRX.CR_AMT).as("cr"),
                       DSL.max(GLTRX.REF).as("ref"))
               .from(GLTRX)
               .where(where)
               .groupBy(GLTRX.SOURCE, GLTRX.JNL_NO)
               .orderBy(GLTRX.SOURCE, GLTRX.JNL_NO)
               .fetch()
               .forEach(r -> {
                   BigDecimal dr = z(r.get("dr", BigDecimal.class)), cr = z(r.get("cr", BigDecimal.class));
                   tot[0] = tot[0].add(dr); tot[1] = tot[1].add(cr);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("source", trim(r.get(GLTRX.SOURCE)));
                   row.put("journal", trim(r.get(GLTRX.SOURCE)) + "-" + r.get(GLTRX.JNL_NO));
                   row.put("jnlDate", localDateToSqlDate(r.get("jnl_date", LocalDate.class)));
                   row.put("description", trim(r.get("ref", String.class)));
                   row.put("reference", "");
                   row.put("lineCount", r.get("line_count", Integer.class));
                   row.put("debit", dr); row.put("credit", cr);
                   row.put("acctMain", ""); row.put("acctSub", ""); row.put("user", "");
                   rows.add(row);
               });
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
        Condition where = GLTRX.COMPANY_NO.eq(s.getCompanyNo()).and(GLTRX.YEAR_NO.eq(s.getYearNo()));
        where = appendAcctRange(where, GLTRX.ACCT_MAIN_NO, p.startAcct(), p.endAcct());
        if (notBlank(p.source())) where = where.and(GLTRX.SOURCE.eq(p.source()));
        where = appendStringRange(where, GLTRX.ANALYSIS_CODE, p.startAnalysis(), p.endAnalysis());
        where = appendDateRange(where, GLTRX.JNL_DATE, p.startDate(), p.endDate());

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        int[] lastMain = { Integer.MIN_VALUE }, lastSub = { Integer.MIN_VALUE };
        BigDecimal[] run = { BigDecimal.ZERO }; String[] drcr = { "D" };
        try {
            dsl.select(GLTRX.ACCT_MAIN_NO, GLTRX.ACCT_SUB_NO,
                       DSL.coalesce(GLCHART.DESC1, "").as("acct_desc"),
                       DSL.coalesce(GLCHART.DR_CR_IND, "D").as("dr_cr_ind"),
                       GLTRX.SOURCE, GLTRX.JNL_NO, GLTRX.JNL_DATE,
                       GLTRX.REF, GLTRX.ANALYSIS_CODE, GLTRX.DR_AMT, GLTRX.CR_AMT, GLTRX.AUDIT_USER_ID)
               .from(GLTRX)
               .leftJoin(GLCHART)
                   .on(GLCHART.COMPANY_NO.eq(GLTRX.COMPANY_NO)
                       .and(GLCHART.ACCT_MAIN_NO.eq(GLTRX.ACCT_MAIN_NO))
                       .and(GLCHART.ACCT_SUB_NO.eq(GLTRX.ACCT_SUB_NO)))
               .where(where)
               .orderBy(GLTRX.ACCT_MAIN_NO, GLTRX.ACCT_SUB_NO, GLTRX.JNL_DATE, GLTRX.SOURCE, GLTRX.JNL_NO)
               .fetch()
               .forEach(r -> {
                   int main = r.get(GLTRX.ACCT_MAIN_NO), sub = r.get(GLTRX.ACCT_SUB_NO);
                   if (main != lastMain[0] || sub != lastSub[0]) {
                       run[0] = BigDecimal.ZERO; lastMain[0] = main; lastSub[0] = sub;
                       drcr[0] = trim(r.get("dr_cr_ind", String.class));
                   }
                   BigDecimal dr = z(r.get(GLTRX.DR_AMT)), cr = z(r.get(GLTRX.CR_AMT));
                   run[0] = "C".equals(drcr[0]) ? run[0].add(cr).subtract(dr) : run[0].add(dr).subtract(cr);
                   tot[0] = tot[0].add(dr); tot[1] = tot[1].add(cr);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("account", acct(main, sub));
                   row.put("acctMain", acctMain(main));
                   row.put("acctSub",  acctSub(sub));
                   row.put("description", r.get("acct_desc", String.class));
                   row.put("jnlDate", localDateToSqlDate(r.get(GLTRX.JNL_DATE)));
                   row.put("source", trim(r.get(GLTRX.SOURCE)));
                   row.put("journal", trim(r.get(GLTRX.SOURCE)) + "-" + r.get(GLTRX.JNL_NO));
                   row.put("reference", trim(r.get(GLTRX.REF)));
                   row.put("analysisCode", trim(r.get(GLTRX.ANALYSIS_CODE)));
                   row.put("debit", dr); row.put("credit", cr); row.put("balance", run[0]);
                   row.put("user", trim(r.get(GLTRX.AUDIT_USER_ID)));
                   rows.add(row);
               });
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

    private Condition appendJournalFilters(Condition where, JournalParams p) {
        if (notBlank(p.source())) where = where.and(GLTRX.SOURCE.eq(p.source()));
        if (p.startJnl() != null && p.startJnl() > 0) {
            int e = (p.endJnl() != null && p.endJnl() > 0) ? p.endJnl() : 999999999;
            where = where.and(GLTRX.JNL_NO.between(p.startJnl(), e));
        }
        where = appendDateRange(where, GLTRX.JNL_DATE, p.startDate(), p.endDate());
        return where;
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

    private Condition appendAcctRange(Condition where, Field<Integer> col, Integer start, Integer end) {
        if (start != null && start > 0) {
            int e = (end != null && end > 0) ? end : 999999999;
            return where.and(col.between(start, e));
        } else if (end != null && end > 0) {
            return where.and(col.le(end));
        }
        return where;
    }

    private Condition appendStringRange(Condition where, Field<String> col, String start, String end) {
        if (notBlank(start)) {
            String e = notBlank(end) ? end : "zzzzzzzzzzzzzzzzzzzz";
            return where.and(col.between(start, e));
        } else if (notBlank(end)) {
            return where.and(col.le(end));
        }
        return where;
    }

    private Condition appendDateRange(Condition where, Field<LocalDate> col, LocalDate start, LocalDate end) {
        if (start != null) {
            LocalDate e = end != null ? end : LocalDate.of(9999, 12, 31);
            return where.and(col.between(start, e));
        } else if (end != null) {
            return where.and(col.le(end));
        }
        return where;
    }

    /**
     * Σ bal_from..bal_to (debit-positive period movements).
     * Returns an inline SQL string fragment, e.g. for alias "b":
     * {@code (COALESCE(b.bal_01,0)+COALESCE(b.bal_02,0)+...)}.
     * Used with {@code DSL.field(expr, BigDecimal.class)} — see class Javadoc.
     */
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

    /**
     * Looks up {@code gldates.period_end_NN} for the given period.
     * Uses {@code DSL.field(name, LocalDate.class)} because the column name is dynamic
     * (period 1–13 → period_end_01..period_end_13) and cannot be typed at compile time.
     */
    private LocalDate periodEndDate(int companyNo, int yearNo, int periodNo) {
        String col = "period_end_" + String.format("%02d", periodNo);
        try {
            return dsl.select(DSL.field(col, LocalDate.class))
                      .from(GLDATES)
                      .where(GLDATES.COMPANY_NO.eq(companyNo).and(GLDATES.YEAR_NO.eq(yearNo)))
                      .limit(1)
                      .fetchOne(DSL.field(col, LocalDate.class));
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

    /**
     * Converts a {@code LocalDate} returned by jOOQ to a {@code java.sql.Date} for Jasper
     * compatibility, returning {@code null} for pre-1900 sentinel dates.
     */
    static java.sql.Date localDateToSqlDate(LocalDate d) {
        if (d == null) return null;
        return d.isAfter(LocalDate.of(1900, 1, 1)) ? java.sql.Date.valueOf(d) : null;
    }

    /** Back-compat alias used by callers that previously received a {@code java.sql.Date}. */
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
