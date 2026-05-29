package com.landmarksoftware.service.gl;

import com.landmarksoftware.model.AppSession;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * GL Report Writer engine — Java port of COBOL {@code glrp60}, the matrix
 * evaluator that drives <b>GLRP40</b> (Report Writer Output, Reports Hub card).
 *
 * <p>A report-writer report is a <b>rows × columns</b> matrix:
 * <ul>
 *   <li><b>Rows</b> come from a "vertical format" — {@code glrpveh} (header) +
 *       {@code glrpvel} (one row per output line). Each line declares an
 *       account-range, a dr/cr sign indicator, optional sub-total reference,
 *       optional constant, group / type filters and a target column index.</li>
 *   <li><b>Columns</b> come from a "horizontal table" — {@code glrptah} (header)
 *       + {@code glrptab} (one body row per fiscal year). The body row carries
 *       up to 366 {@code report_date_NNN} slots that bracket the column
 *       periods (odd slot = period start, even slot = period end).</li>
 *   <li>A <b>"selection"</b> ({@code glrpsel}) ties a vertical format to a
 *       horizontal table plus print options (zero-balance suppression,
 *       rounding, year, before / after year-end indicator, account mask).</li>
 * </ul>
 *
 * <p><b>Phase 1 scope</b> — load definitions, return the matrix shape (one
 * row per {@code glrpvel} line, up to 13 columns derived from {@code glrptab})
 * with <b>empty cells</b>. Phase 2 fills the cells by aggregating
 * {@code glbal} (period movements) and {@code gltrx} for each row's
 * account range over each column's date range, applying the {@code dr_cr_ind}
 * sign and {@code total_type} sub-total operators.
 */
@Service
public class GlReportWriterService {

    private static final Logger log = LoggerFactory.getLogger(GlReportWriterService.class);
    private static final LocalDate SENTINEL = LocalDate.of(1899, 12, 31);
    /** Hard column cap — matches the standard Landmark wide reports (monthly + YTD). */
    public static final int MAX_COLUMNS = 13;

    private final JdbcTemplate jdbc;

    public GlReportWriterService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Self-creates the {@code glrpwkc} runtime work table — the engine's
     * equivalent of COBOL's {@code GLRPSEL-REPORT-DATES-TABLE OCCURS 5 group}.
     * Per CLAUDE.md's work-file rule, tables whose name contains {@code wk}
     * are owned by the Java side and bootstrap themselves; no extract pipeline.
     */
    @PostConstruct
    public void ensureTables() {
        try {
            jdbc.execute(
                "CREATE TABLE IF NOT EXISTS glrpwkc (" +
                "  company_no INT NOT NULL," +
                "  selection_no INT NOT NULL," +
                "  seq_no INT NOT NULL," +
                "  date_table VARCHAR(4)," +
                "  start_date DATE," +
                "  end_date DATE," +
                "  open_bal_date DATE," +
                "  audit_user_id VARCHAR(15)," +
                "  audit_date DATE," +
                "  PRIMARY KEY (company_no, selection_no, seq_no)" +
                ") ENGINE=InnoDB");
        } catch (Exception e) { log.warn("ensureTables glrpwkc: {}", e.getMessage()); }
    }

    // ── Public records (the user-visible shape of a definition) ──────────────

    /** One row in the saved-selection picker ({@code glrpsel}). */
    public record SelectionRow(
            int selectionNo, String desc1, String rptTitle,
            int vertFormatNo, String horizFormatKey, int yrNo,
            boolean zeroBalSuppress, String roundingFlag, String acctMask) {
        @Override public String toString() {
            String t = notBlank(rptTitle) ? rptTitle : (notBlank(desc1) ? desc1 : "Selection " + selectionNo);
            return selectionNo + " — " + t;
        }
    }

    /** A vertical format header from {@code glrpveh} (used for the manual picker). */
    public record VerticalFormatRow(
            int vertFormatNo, String desc1, String desc2, String vertFormatType) {
        @Override public String toString() {
            return vertFormatNo + " — " + (notBlank(desc1) ? desc1 : "(unnamed)");
        }
    }

    /** A horizontal table from {@code glrptah} (used for the manual picker). */
    public record HorizontalTableRow(
            String dateTableCode, String desc1) {
        @Override public String toString() {
            return dateTableCode + " — " + (notBlank(desc1) ? desc1 : "(unnamed)");
        }
    }

    // ── Public lookups (Reports Hub combos populate from these) ──────────────

    /** Saved selections for the company; empty until {@code glrpsel} is loaded. */
    public List<SelectionRow> getSelections(AppSession s) {
        List<SelectionRow> list = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT selection_no, desc_1, rpt_title, vert_format_no, horiz_format_no, " +
                "       yr_no, zero_bal_flag, rounding_flag, acct_mask " +
                "FROM glrpsel WHERE company_no=? ORDER BY selection_no",
                rs -> {
                    list.add(new SelectionRow(
                        rs.getInt("selection_no"),
                        trim(rs.getString("desc_1")),
                        trim(rs.getString("rpt_title")),
                        rs.getInt("vert_format_no"),
                        trim(rs.getString("horiz_format_no")),
                        rs.getInt("yr_no"),
                        "Y".equalsIgnoreCase(trim(rs.getString("zero_bal_flag"))),
                        trim(rs.getString("rounding_flag")),
                        trim(rs.getString("acct_mask"))));
                }, s.getCompanyNo());
        } catch (Exception e) { log.warn("getSelections: {}", e.getMessage()); }
        return list;
    }

    /** Vertical formats for the manual picker, ordered by number. */
    public List<VerticalFormatRow> getVerticalFormats(AppSession s) {
        List<VerticalFormatRow> list = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT vert_format_no, desc_1, desc_2, vert_format_type " +
                "FROM glrpveh WHERE company_no=? ORDER BY vert_format_no",
                rs -> {
                    list.add(new VerticalFormatRow(
                        rs.getInt("vert_format_no"),
                        trim(rs.getString("desc_1")),
                        trim(rs.getString("desc_2")),
                        trim(rs.getString("vert_format_type"))));
                }, s.getCompanyNo());
        } catch (Exception e) { log.warn("getVerticalFormats: {}", e.getMessage()); }
        return list;
    }

    /** Horizontal tables for the manual picker, ordered by code. */
    public List<HorizontalTableRow> getHorizontalTables(AppSession s) {
        List<HorizontalTableRow> list = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT date_table, desc1 FROM glrptah WHERE company_no=? ORDER BY date_table",
                rs -> {
                    list.add(new HorizontalTableRow(
                        trim(rs.getString("date_table")),
                        trim(rs.getString("desc1"))));
                }, s.getCompanyNo());
        } catch (Exception e) { log.warn("getHorizontalTables: {}", e.getMessage()); }
        return list;
    }

    // ── Engine entry point ───────────────────────────────────────────────────

    /**
     * Inputs for a one-shot matrix run. The user enters {@code startDate} and
     * {@code endDate} on the screen; the engine derives the column layout (1
     * PTD column, or PTD + YTD + Prior YTD, etc.) from {@code horizFormatKey}.
     * {@code selectionNo} is the glrpsel key when running a saved selection
     * (drives persistence to the glrpwkc rundates work table); 0 in manual mode.
     */
    public record RunParams(int selectionNo, int vertFormatNo, String horizFormatKey,
                            LocalDate startDate, LocalDate endDate,
                            boolean zeroBalSuppress, String roundingFlag) {}

    /**
     * Resolve definitions and produce the matrix. Phase 1 emits row labels with
     * empty cells so the wiring + Jasper output are testable end-to-end; Phase 2
     * fills the cells by aggregating {@code glbal} / {@code gltrx}.
     */
    public Map<String, Object> runMatrix(AppSession s, RunParams p) {
        VerticalFormat vert = loadVerticalFormat(s.getCompanyNo(), p.vertFormatNo());
        if (vert == null) {
            return warn("Vertical format " + p.vertFormatNo() + " not found in glrpveh.");
        }
        if (vert.rows().isEmpty()) {
            return warn("Vertical format " + p.vertFormatNo() + " has no lines in glrpvel.");
        }
        if (p.startDate() == null || p.endDate() == null) {
            return warn("Enter a start and end date — the engine builds the columns from these.");
        }

        // Three-tier column resolution (mirrors how COBOL populates
        // GLRPSEL-REPORT-DATES-TABLE at run time): persisted rundates first,
        // then synthesise from horiz_format_no using the entered date range
        // (PTD / YTD / Prior YTD for "1", year as-at for "2", etc.), finally
        // fall through to the glrptah/glrptab template tables for bespoke keys.
        List<ColumnDef> columns = resolveColumns(s, p);
        if (columns.isEmpty()) {
            return warn("Could not resolve any columns for horizontal '" + p.horizFormatKey()
                + "'. The engine knows '1' (PTD/YTD/Prior YTD), '2' (single range), and falls back to glrptah/glrptab for bespoke keys.");
        }
        String horizDesc = describeHoriz(s.getCompanyNo(), p.horizFormatKey());
        int colCount = Math.min(columns.size(), MAX_COLUMNS);
        // Engine state: running tracks the credit-positive net since the last
        // subtotal (income +, expense −); buckets accumulate the running into
        // a totalled bucket on each subtotal print so the final calculation row
        // (Net Profit Before Tax) can read the last-used bucket.
        BigDecimal[] running = zeroes(colCount);
        Map<Integer, BigDecimal[]> buckets = new HashMap<>();
        int lastBucketNo = 0;

        // Preload account descriptions once for the company so expansion rows
        // can render their own desc1 instead of the row's range label.
        Map<String, String> acctDescs = loadAccountDescriptions(s.getCompanyNo());

        List<Map<String, Object>> outRows = new ArrayList<>();
        for (RowDef r : vert.rows()) {
            String tt = trim(r.totalType()).toUpperCase();
            // Skip the 'V' (variance / special) and obviously out-of-range
            // account ranges — they leak through some glrpvel definitions.
            if ("V".equals(tt)) continue;
            if (r.startMain() > 999999) continue;
            // seq_no >= 999000 is a COBOL end-of-format sentinel — e.g. a stray
            // "CURRENT ASSETS" row on the end of a P&L vert format that belongs
            // to a separate Balance Sheet format. Don't render it.
            if (r.seqNo() >= 999000) continue;

            if (r.startMain() > 0) {
                // ── Account row ─────────────────────────────────────────────
                Map<String, BigDecimal[]> perAcct = aggregatePerAccount(s.getCompanyNo(), r, columns, colCount);
                boolean expand = "M".equalsIgnoreCase(r.printEachAcctFlag());
                boolean credit = "C".equalsIgnoreCase(r.drCrInd());

                if (expand) {
                    for (Map.Entry<String, BigDecimal[]> en : perAcct.entrySet()) {
                        BigDecimal[] net = en.getValue();  // dr-cr per column
                        BigDecimal[] display = credit ? negate(net) : net.clone();
                        if (p.zeroBalSuppress() && allZero(display)) continue;
                        String desc = acctDescs.getOrDefault(en.getKey(), en.getKey());
                        emit(outRows, desc, "account", display, colCount);
                        // running and bucket contribution = credit-positive net
                        // contribution = −(dr−cr) regardless of dr_cr_ind so both
                        // C and D rows feed the running net consistently.
                        for (int c = 0; c < colCount; c++) running[c] = running[c].subtract(net[c]);
                        if (r.totalNo() > 0) {
                            BigDecimal[] bk = buckets.computeIfAbsent(r.totalNo(), k -> zeroes(colCount));
                            for (int c = 0; c < colCount; c++) bk[c] = bk[c].subtract(net[c]);
                        }
                    }
                } else {
                    // Collapsed: sum across all accounts in the range
                    BigDecimal[] sum = zeroes(colCount);
                    for (BigDecimal[] net : perAcct.values()) {
                        for (int c = 0; c < colCount; c++) sum[c] = sum[c].add(net[c]);
                    }
                    BigDecimal[] display = credit ? negate(sum) : sum.clone();
                    if (!(p.zeroBalSuppress() && allZero(display))) {
                        emit(outRows, labelOf(r), "account", display, colCount);
                    }
                    for (int c = 0; c < colCount; c++) running[c] = running[c].subtract(sum[c]);
                    if (r.totalNo() > 0) {
                        BigDecimal[] bk = buckets.computeIfAbsent(r.totalNo(), k -> zeroes(colCount));
                        for (int c = 0; c < colCount; c++) bk[c] = bk[c].subtract(sum[c]);
                    }
                }
            } else if (!notBlank(r.drCrInd()) && notBlank(r.lineDesc())) {
                // ── Section header (CURRENT ASSETS / EQUITY / etc.) ─────────
                // No dr_cr_ind = no value contribution; emit label with blank cells.
                emit(outRows, labelOf(r), "header", new BigDecimal[colCount], colCount);
            } else if (("+".equals(tt) || "-".equals(tt)) && r.totalNo() > 0 && notBlank(r.lineDesc())) {
                // ── Subtotal print (e.g. "Total Income" / "Total Expenses") ─
                // Display the current running, sign-flipped by the row's dr_cr_ind:
                //   C-row label (income): running directly (positive for income)
                //   D-row label (expense): −running (positive for expense)
                boolean credit = "C".equalsIgnoreCase(r.drCrInd());
                BigDecimal[] display = credit ? running.clone() : negate(running);
                emit(outRows, labelOf(r), "subtotal", display, colCount);
                BigDecimal[] bk = buckets.computeIfAbsent(r.totalNo(), k -> zeroes(colCount));
                for (int c = 0; c < colCount; c++) bk[c] = bk[c].add(running[c]);
                lastBucketNo = r.totalNo();
                Arrays.fill(running, BigDecimal.ZERO);
            } else if (notBlank(r.lineDesc()) && r.totalNo() == 0 && tt.isEmpty()) {
                // ── Final calculation row (Net Profit Before Tax) ───────────
                // Display the last-used bucket — that's the running net of all
                // subtotals (income − expense in the canonical P&L).
                BigDecimal[] netBucket = buckets.getOrDefault(lastBucketNo > 0 ? lastBucketNo : 2, zeroes(colCount));
                emit(outRows, labelOf(r), "total", netBucket.clone(), colCount);
            }
            // else: blank separator / non-actionable row — skip
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("REPORT_TITLE", notBlank(vert.desc1()) ? vert.desc1() : "Report Writer Output");
        params.put("VERT_DESC",  notBlank(vert.desc2()) ? vert.desc2() : "");
        params.put("HORIZ_DESC", horizDesc);
        params.put("YEAR_DESC",  "PERIOD   " + fmt(p.startDate()) + " to " + fmt(p.endDate()));
        params.put("COL_COUNT",  colCount);
        for (int c = 1; c <= MAX_COLUMNS; c++) {
            params.put(colHeadKey(c), c <= colCount ? headingOf(columns.get(c - 1)) : "");
        }
        params.put("ROW_COUNT", outRows.size());
        return result(outRows, params);
    }

    // ── Definition loaders ───────────────────────────────────────────────────

    /** A loaded vertical format — header + ordered row list. */
    public record VerticalFormat(
            int vertFormatNo, String desc1, String desc2, String vertFormatType,
            int roundingMain, int roundingSub, List<RowDef> rows) {}

    /**
     * One {@code glrpvel} line — an output row template. {@code printEachAcctFlag}
     * carries the raw COBOL flag character (commonly {@code "M"} = expand into
     * one row per active account, {@code "N"} = collapse to a single row,
     * blank = default).
     */
    public record RowDef(
            int seqNo, int startMain, int startSub, int endMain, int endSub,
            String lineDesc, String drCrInd, String totalType, int totalNo,
            BigDecimal constant, int colNo, String printEachAcctFlag,
            String acctType, String printFlag,
            String startReportGroup, String endReportGroup) {}

    /** Loads {@code glrpveh} + {@code glrpvel} for a (company, vert_format_no). */
    public VerticalFormat loadVerticalFormat(int companyNo, int vertFormatNo) {
        VerticalFormat[] head = { null };
        try {
            jdbc.query(
                "SELECT desc_1, desc_2, vert_format_type, rounding_main_no, rounding_sub_no " +
                "FROM glrpveh WHERE company_no=? AND vert_format_no=?",
                rs -> {
                    head[0] = new VerticalFormat(
                        vertFormatNo,
                        trim(rs.getString("desc_1")),
                        trim(rs.getString("desc_2")),
                        trim(rs.getString("vert_format_type")),
                        rs.getInt("rounding_main_no"),
                        rs.getInt("rounding_sub_no"),
                        new ArrayList<>());
                }, companyNo, vertFormatNo);
        } catch (Exception e) { log.warn("loadVerticalFormat header: {}", e.getMessage()); }
        if (head[0] == null) return null;

        List<RowDef> rows = head[0].rows();
        try {
            jdbc.query(
                "SELECT seq_no, start_main_no, start_sub_no, end_main_no, end_sub_no, " +
                "       line_desc, dr_cr_ind, total_type, total_no, constant, col_no, " +
                "       print_each_acct_flag, acct_type, print_flag, " +
                "       start_report_group, end_report_group " +
                "FROM glrpvel WHERE company_no=? AND vert_format_no=? ORDER BY seq_no",
                rs -> {
                    rows.add(new RowDef(
                        rs.getInt("seq_no"),
                        rs.getInt("start_main_no"), rs.getInt("start_sub_no"),
                        rs.getInt("end_main_no"),   rs.getInt("end_sub_no"),
                        rs.getString("line_desc"),
                        trim(rs.getString("dr_cr_ind")),
                        trim(rs.getString("total_type")),
                        rs.getInt("total_no"),
                        z(rs.getBigDecimal("constant")),
                        rs.getInt("col_no"),
                        trim(rs.getString("print_each_acct_flag")),
                        trim(rs.getString("acct_type")),
                        trim(rs.getString("print_flag")),
                        trim(rs.getString("start_report_group")),
                        trim(rs.getString("end_report_group"))));
                }, companyNo, vertFormatNo);
        } catch (Exception e) { log.warn("loadVerticalFormat lines: {}", e.getMessage()); }
        return head[0];
    }

    /** A loaded horizontal table — header + ordered column list (period start/end pairs). */
    public record HorizontalTable(
            String dateTableCode, String desc1, int yearNo, List<ColumnDef> columns) {}

    /** One column's period bounds and (optional) explicit display label. */
    public record ColumnDef(int idx, LocalDate periodStart, LocalDate periodEnd, String label) {
        public ColumnDef(int idx, LocalDate s, LocalDate e) { this(idx, s, e, null); }
    }

    /**
     * Loads {@code glrptah} header + the {@code glrptab} body row for the given
     * fiscal year, then unpivots the 366 {@code report_date_NNN} slots into
     * (start, end) column pairs — odd slot = period start, even slot = period end.
     * Stops at the first sentinel ({@value SENTINEL}).
     */
    public HorizontalTable loadHorizontalTable(int companyNo, String dateTableCode, int yearNo) {
        String[] desc = { null };
        try {
            jdbc.query(
                "SELECT desc1 FROM glrptah WHERE company_no=? AND date_table=?",
                rs -> { desc[0] = trim(rs.getString("desc1")); },
                companyNo, dateTableCode);
        } catch (Exception e) { log.warn("loadHorizontalTable header: {}", e.getMessage()); }
        if (desc[0] == null) return null;

        // Pull all 366 date slots in one row, then unpivot.
        StringBuilder cols = new StringBuilder("year_no");
        for (int i = 1; i <= 366; i++) cols.append(String.format(", report_date_%03d", i));
        Map<String, Object>[] bodyRow = new Map[]{ null };
        try {
            jdbc.query(
                "SELECT " + cols + " FROM glrptab WHERE company_no=? AND date_table=? AND year_no=?",
                rs -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("year_no", rs.getInt("year_no"));
                    for (int i = 1; i <= 366; i++) {
                        java.sql.Date d = rs.getDate(String.format("report_date_%03d", i));
                        r.put(String.format("report_date_%03d", i), d == null ? null : d.toLocalDate());
                    }
                    bodyRow[0] = r;
                }, companyNo, dateTableCode, yearNo);
        } catch (Exception e) { log.warn("loadHorizontalTable body: {}", e.getMessage()); }
        if (bodyRow[0] == null) {
            return new HorizontalTable(dateTableCode, desc[0], yearNo, List.of());
        }

        List<ColumnDef> columns = new ArrayList<>();
        int colIdx = 0;
        for (int i = 1; i <= 365 && columns.size() < MAX_COLUMNS; i += 2) {
            LocalDate start = (LocalDate) bodyRow[0].get(String.format("report_date_%03d", i));
            LocalDate end   = (LocalDate) bodyRow[0].get(String.format("report_date_%03d", i + 1));
            if (start == null || SENTINEL.equals(start)) break;
            if (end == null || SENTINEL.equals(end)) end = start;
            columns.add(new ColumnDef(++colIdx, start, end));
        }
        return new HorizontalTable(dateTableCode, desc[0], yearNo, columns);
    }

    // ── Column resolution (rundates → synth → glrptah) ───────────────────────

    /**
     * Column resolver. Always synthesises from the user's start/end date range
     * + {@code horiz_format_no} (so a date change in the screen takes effect
     * immediately, no stale cache). Persists the resolved columns into
     * {@code glrpwkc} as an audit snapshot — equivalent to COBOL writing
     * {@code GLRPSEL-REPORT-DATES-TABLE} at run time. Falls through to
     * {@code glrptah}/{@code glrptab} only when synthesis returns empty
     * (bespoke keys like {@code "Q"}).
     */
    private List<ColumnDef> resolveColumns(AppSession s, RunParams p) {
        List<ColumnDef> synth = synthesizeColumns(s.getCompanyNo(), p);
        if (!synth.isEmpty()) {
            if (p.selectionNo() > 0) persistRunDates(s, p.selectionNo(), p.horizFormatKey(), synth);
            return synth;
        }
        int yr = p.endDate() != null ? p.endDate().getYear() : s.getYearNo();
        HorizontalTable horiz = loadHorizontalTable(s.getCompanyNo(), p.horizFormatKey(), yr);
        return horiz != null ? horiz.columns() : List.of();
    }

    /**
     * Synthesises columns from {@code horiz_format_no} given the user's date
     * range. The conventional Landmark codes verified against COBOL glrp output:
     * <ul>
     *   <li><b>"1"</b> — three columns: <b>Actual PTD</b> (start→end), <b>Actual YTD</b>
     *       (fiscal year start of the end-date's year → end), <b>Prior YTD</b>
     *       (same shape one fiscal year earlier).</li>
     *   <li><b>"2"</b> — single column over the start→end range (Balance Sheet
     *       "as at" — pass year-start / end-date pair).</li>
     * </ul>
     * Unknown codes return empty so the resolver falls through to {@code glrptah}.
     */
    private List<ColumnDef> synthesizeColumns(int companyNo, RunParams p) {
        String key = p.horizFormatKey() == null ? "" : p.horizFormatKey().trim().toUpperCase(Locale.ROOT);
        LocalDate start = p.startDate(), end = p.endDate();
        if (start == null || end == null) return List.of();

        return switch (key) {
            case "1", "1A", "1B" -> ptdYtdPriorYtd(companyNo, start, end);
            case "2", "2A", "2B" -> currentYearVsPriorAsAt(end);
            default -> List.of();
        };
    }

    /**
     * Balance-sheet horizontal layout: <b>Current Year</b> + <b>Prior Year</b>,
     * each a cumulative as-at column with no lower date bound. Using a 1900-01-01
     * sentinel start picks up open_bal-rolled history naturally, so the cell
     * value = {@code SUM(dr_amt − cr_amt) WHERE jnl_date ≤ end} — exactly the
     * cumulative balance the COBOL BS expects.
     */
    private List<ColumnDef> currentYearVsPriorAsAt(LocalDate end) {
        LocalDate sentinel = LocalDate.of(1900, 1, 1);
        return List.of(
            new ColumnDef(1, sentinel, end,                "Current Year"),
            new ColumnDef(2, sentinel, end.minusYears(1),  "Prior Year"));
    }

    /**
     * Builds the COBOL P&amp;L horizontal layout: Actual PTD, Actual YTD, Prior YTD.
     * YTD = fiscal-year start of the end-date's year → end. Prior YTD = same
     * window one fiscal year earlier. Falls back to a single PTD column if
     * gldates can't be resolved for the relevant year.
     */
    private List<ColumnDef> ptdYtdPriorYtd(int companyNo, LocalDate start, LocalDate end) {
        int endCalendarYear = end.getYear();
        Integer fyYearNo = pickFiscalYearForDate(companyNo, end);
        LocalDate yrStart = null;
        if (fyYearNo != null) {
            Map<String, Object> row = loadGlDatesRow(companyNo, fyYearNo);
            if (row != null) yrStart = sqlToLocal(row.get("yr_start_date"));
        }
        // Prior fiscal year — for the "Prior YTD" column we want the same shape
        // one fiscal year earlier; row picked by year_no = fyYearNo - 1.
        LocalDate priorYrStart = null;
        LocalDate priorEnd = end.minusYears(1);
        if (fyYearNo != null) {
            Map<String, Object> prior = loadGlDatesRow(companyNo, fyYearNo - 1);
            if (prior != null) priorYrStart = sqlToLocal(prior.get("yr_start_date"));
        }

        List<ColumnDef> cols = new ArrayList<>();
        cols.add(new ColumnDef(1, start, end, "Actual PTD"));
        if (yrStart != null) {
            cols.add(new ColumnDef(2, yrStart, end, "Actual YTD"));
        }
        if (priorYrStart != null) {
            cols.add(new ColumnDef(3, priorYrStart, priorEnd, "Prior YTD"));
        } else if (yrStart != null) {
            // No prior gldates row — fabricate a same-shape window one year back
            // so the column still renders (likely all zeros, matching glrp output).
            cols.add(new ColumnDef(3, yrStart.minusYears(1), priorEnd, "Prior YTD"));
        }
        return cols;
    }

    /**
     * Picks the gldates row whose fiscal year contains {@code refDate}. Most
     * Landmark companies have a single year_no per calendar year; pick that.
     */
    private Integer pickFiscalYearForDate(int companyNo, LocalDate refDate) {
        try {
            return jdbc.queryForObject(
                "SELECT yr_no FROM gldates " +
                "WHERE company_no=? AND yr_start_date <= ? AND yr_end_date >= ? " +
                "ORDER BY yr_no DESC LIMIT 1",
                Integer.class, companyNo, Date.valueOf(refDate), Date.valueOf(refDate));
        } catch (Exception e) {
            // Fallback: year_no equals the end-date's calendar year.
            try {
                return jdbc.queryForObject(
                    "SELECT yr_no FROM gldates WHERE company_no=? AND year_no=? LIMIT 1",
                    Integer.class, companyNo, refDate.getYear());
            } catch (Exception e2) { return null; }
        }
    }

    private static String fmt(LocalDate d) {
        return d == null ? "" : d.format(java.time.format.DateTimeFormatter.ofPattern("d/MM/yy"));
    }

    private Map<String, Object> loadGlDatesRow(int companyNo, int yrNoSeq) {
        try {
            StringBuilder cols = new StringBuilder("yr_start_date, yr_end_date");
            for (int i = 1; i <= 13; i++) cols.append(String.format(", period_end_%02d", i));
            return jdbc.queryForMap(
                "SELECT " + cols + " FROM gldates WHERE company_no=? AND yr_no=?",
                companyNo, yrNoSeq);
        } catch (Exception e) { return null; }
    }

    private static LocalDate sqlToLocal(Object v) {
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof LocalDate d)     return d;
        return null;
    }

    /** Loads persisted rundates for one selection from {@code glrpwkc}. */
    private List<ColumnDef> loadRunDates(int companyNo, int selectionNo) {
        List<ColumnDef> out = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT seq_no, start_date, end_date FROM glrpwkc " +
                "WHERE company_no=? AND selection_no=? ORDER BY seq_no",
                rs -> {
                    java.sql.Date sd = rs.getDate("start_date"), ed = rs.getDate("end_date");
                    if (sd != null && ed != null) {
                        out.add(new ColumnDef(rs.getInt("seq_no"), sd.toLocalDate(), ed.toLocalDate()));
                    }
                }, companyNo, selectionNo);
        } catch (Exception e) { log.warn("loadRunDates: {}", e.getMessage()); }
        return out;
    }

    /** Replaces persisted rundates for a selection (delete + insert in one tx). */
    private void persistRunDates(AppSession s, int selectionNo, String dateTable, List<ColumnDef> cols) {
        try {
            jdbc.update("DELETE FROM glrpwkc WHERE company_no=? AND selection_no=?",
                s.getCompanyNo(), selectionNo);
            for (ColumnDef c : cols) {
                jdbc.update(
                    "INSERT INTO glrpwkc (company_no, selection_no, seq_no, date_table, " +
                    "start_date, end_date, audit_user_id, audit_date) VALUES (?,?,?,?,?,?,?,?)",
                    s.getCompanyNo(), selectionNo, c.idx(), trim(dateTable),
                    Date.valueOf(c.periodStart()), Date.valueOf(c.periodEnd()),
                    s.getUserId(), Date.valueOf(LocalDate.now()));
            }
        } catch (Exception e) { log.warn("persistRunDates: {}", e.getMessage()); }
    }

    /** Title-bar caption for the horizontal — glrptah desc if known, else the key. */
    private String describeHoriz(int companyNo, String key) {
        try {
            String d = jdbc.queryForObject(
                "SELECT desc1 FROM glrptah WHERE company_no=? AND date_table=?",
                String.class, companyNo, key);
            if (notBlank(d)) return d;
        } catch (Exception ignored) {}
        // Friendly descriptions for the synthesised conventions.
        String c = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
        return switch (c) {
            case "1"  -> "Actual PTD / Actual YTD / Prior YTD";
            case "1A" -> "Actual PTD / Actual YTD / Prior YTD";
            case "1B" -> "Actual PTD / Actual YTD / Prior YTD (prior year)";
            case "2", "2A" -> "Current Year / Prior Year (as at)";
            case "2B"      -> "Current Year / Prior Year (as at — prior year)";
            default -> notBlank(key) ? key : "(no horizontal)";
        };
    }

    /**
     * Translates a COBOL fiscal-year sequence ({@code gldates.yr_no}) to its
     * 4-digit calendar {@code year_no}. Returns {@code null} when the year
     * doesn't exist for the company so callers can fall back to a session default.
     */
    private Integer lookupCalendarYear(int companyNo, int yrNoSeq) {
        try {
            return jdbc.queryForObject(
                "SELECT year_no FROM gldates WHERE company_no=? AND yr_no=?",
                Integer.class, companyNo, yrNoSeq);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Interpreter: aggregate one row's account range over the columns ──────

    /**
     * Pulls every {@code gltrx} posting in this row's account range across the
     * full date span (min column start … max column end) <b>in one query</b>,
     * then buckets each posting into its matching column AND keys the result
     * by account. The caller decides whether to render one row per account
     * ({@code print_each_acct_flag='M'}) or one collapsed row.
     *
     * <p>Sign convention: stored {@code dr_amt}/{@code cr_amt} are positive,
     * the returned cells carry {@code (dr − cr)} per column. Display sign is
     * applied by the caller using the row's {@code dr_cr_ind}.
     *
     * <p>One query per glrpvel row, regardless of column count, so a M×N
     * matrix costs M queries, not M×N.
     */
    private Map<String, BigDecimal[]> aggregatePerAccount(int companyNo, RowDef r,
                                                          List<ColumnDef> columns, int colCount) {
        Map<String, BigDecimal[]> perAcct = new LinkedHashMap<>();
        if (colCount == 0) return perAcct;

        LocalDate spanStart = columns.get(0).periodStart();
        LocalDate spanEnd   = columns.get(0).periodEnd();
        for (int i = 1; i < colCount; i++) {
            if (columns.get(i).periodStart().isBefore(spanStart)) spanStart = columns.get(i).periodStart();
            if (columns.get(i).periodEnd().isAfter(spanEnd))     spanEnd   = columns.get(i).periodEnd();
        }

        int endMain = r.endMain() > 0 ? r.endMain() : r.startMain();
        int endSub  = r.endSub()  > 0 ? r.endSub()  : 9999;

        final LocalDate[] cols0 = new LocalDate[colCount];
        final LocalDate[] cols1 = new LocalDate[colCount];
        for (int i = 0; i < colCount; i++) {
            cols0[i] = columns.get(i).periodStart();
            cols1[i] = columns.get(i).periodEnd();
        }

        try {
            jdbc.query(
                "SELECT acct_main_no, acct_sub_no, jnl_date, dr_amt, cr_amt FROM gltrx " +
                "WHERE company_no=? AND acct_main_no BETWEEN ? AND ? " +
                "  AND acct_sub_no BETWEEN ? AND ? " +
                "  AND jnl_date BETWEEN ? AND ? " +
                "ORDER BY acct_main_no, acct_sub_no",
                rs -> {
                    java.sql.Date dd = rs.getDate("jnl_date");
                    if (dd == null) return;
                    String key = rs.getInt("acct_main_no") + "." + rs.getInt("acct_sub_no");
                    BigDecimal[] cells = perAcct.computeIfAbsent(key, k -> zeroes(colCount));
                    LocalDate d = dd.toLocalDate();
                    BigDecimal net = z(rs.getBigDecimal("dr_amt")).subtract(z(rs.getBigDecimal("cr_amt")));
                    // Add to EVERY column whose date range contains this posting.
                    // PTD and YTD often overlap (when start_date = fiscal year
                    // start they're identical), so a single break here would
                    // leak the posting out of YTD. Each column is an independent
                    // aggregate; non-overlapping columns naturally match one.
                    for (int c = 0; c < colCount; c++) {
                        if (!d.isBefore(cols0[c]) && !d.isAfter(cols1[c])) {
                            cells[c] = cells[c].add(net);
                        }
                    }
                },
                companyNo, r.startMain(), endMain, r.startSub(), endSub,
                Date.valueOf(spanStart), Date.valueOf(spanEnd));
        } catch (Exception e) {
            log.warn("aggregatePerAccount row seq={} accts={}.{}–{}.{}: {}",
                r.seqNo(), r.startMain(), r.startSub(), endMain, endSub, e.getMessage());
        }
        return perAcct;
    }

    /** Loads the company's chart of accounts as a {@code "main.sub" → desc1} map. */
    private Map<String, String> loadAccountDescriptions(int companyNo) {
        Map<String, String> map = new HashMap<>();
        try {
            jdbc.query(
                "SELECT acct_main_no, acct_sub_no, desc1 FROM glchart WHERE company_no=?",
                rs -> { map.put(rs.getInt(1) + "." + rs.getInt(2), trim(rs.getString(3))); },
                companyNo);
        } catch (Exception e) { log.warn("loadAccountDescriptions: {}", e.getMessage()); }
        return map;
    }

    private static BigDecimal[] negate(BigDecimal[] a) {
        BigDecimal[] out = new BigDecimal[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i].negate();
        return out;
    }

    private static void emit(List<Map<String, Object>> rows, String label, String kind,
                             BigDecimal[] cells, int colCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rowLabel", label);
        row.put("rowKind",  kind);
        for (int c = 1; c <= MAX_COLUMNS; c++) {
            row.put(colKey(c), c <= colCount ? cells[c - 1] : null);
        }
        rows.add(row);
    }

    private static BigDecimal[] zeroes(int n) {
        BigDecimal[] a = new BigDecimal[n];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static BigDecimal[] copyOrZero(BigDecimal[] src, int n) {
        BigDecimal[] dst = zeroes(n);
        if (src != null) System.arraycopy(src, 0, dst, 0, Math.min(src.length, n));
        return dst;
    }

    private static boolean allZero(BigDecimal[] a) {
        for (BigDecimal v : a) if (v != null && v.signum() != 0) return false;
        return true;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static String colKey(int n)     { return String.format("col_%02d", n); }
    static String colHeadKey(int n) { return String.format("COL_HEAD_%02d", n); }

    private static String labelOf(RowDef r) {
        if (notBlank(r.lineDesc())) return r.lineDesc();
        if (r.startMain() == r.endMain()) return String.valueOf(r.startMain());
        return r.startMain() + "–" + r.endMain();
    }

    /** Coarse classification for the jrxml grouping / styling. */
    private static String kindOf(RowDef r) {
        String t = trim(r.totalType()).toUpperCase();
        if (t.isEmpty() || "+".equals(t) || "-".equals(t)) return "account";
        if ("U".equals(t)) return "subtotal";   // common COBOL convention
        if ("T".equals(t)) return "total";
        if ("C".equals(t)) return "constant";
        return "other";
    }

    /**
     * Column heading. An explicit {@link ColumnDef#label} wins (set by the
     * PTD/YTD/Prior YTD synth); otherwise a compact date label fitting the
     * 46-px PDF column — "MMM yy" for ~one-month spans, "FY yy" for full year,
     * "dd/MM/yy" end-date for anything else.
     */
    private static String headingOf(ColumnDef c) {
        if (c.label() != null && !c.label().isBlank()) return c.label();
        LocalDate s = c.periodStart(), e = c.periodEnd();
        long days = java.time.temporal.ChronoUnit.DAYS.between(s, e);
        if (days >= 27 && days <= 32)  return e.format(java.time.format.DateTimeFormatter.ofPattern("MMM yy"));
        if (days >= 360 && days <= 370) return "FY " + String.format("%02d", e.getYear() % 100);
        return e.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy"));
    }

    private Map<String, Object> result(List<Map<String, Object>> rows, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows); m.put("params", params); m.put("rowCount", rows.size());
        return m;
    }

    Map<String, Object> warn(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", new ArrayList<>()); m.put("params", new LinkedHashMap<>());
        m.put("rowCount", 0); m.put("warning", msg);
        return m;
    }

    static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    static String  trim(String s)     { return s == null ? "" : s.trim(); }
    static BigDecimal z(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
}
