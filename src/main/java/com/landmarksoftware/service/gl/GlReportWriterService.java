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

    /** Inputs for a one-shot matrix run (Selection picker resolves to this). */
    public record RunParams(int vertFormatNo, String horizFormatKey, int yearNo,
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
        // COBOL convention: yr_no=0 on a saved selection means "current year".
        int yr = p.yearNo() > 0 ? p.yearNo() : s.getYearNo();
        HorizontalTable horiz = loadHorizontalTable(s.getCompanyNo(), p.horizFormatKey(), yr);
        if (horiz == null) {
            return warn("Horizontal table '" + p.horizFormatKey() + "' (year " + yr + ") not found in glrptah/glrptab. Load the matching horizontal-table entry, or pick another in the screen.");
        }
        if (vert.rows().isEmpty()) {
            return warn("Vertical format " + p.vertFormatNo() + " has no lines in glrpvel.");
        }

        List<ColumnDef> columns = horiz.columns();
        int colCount = Math.min(columns.size(), MAX_COLUMNS);
        // Total accumulators keyed by total_no — running per column. Each "+" /
        // "-" row contributes; "U" (subtotal) prints the accumulator without
        // resetting; "T" (total) prints and resets.
        Map<Integer, BigDecimal[]> totals = new HashMap<>();

        List<Map<String, Object>> outRows = new ArrayList<>();
        for (RowDef r : vert.rows()) {
            String kind = kindOf(r);
            BigDecimal[] cells;

            switch (kind) {
                case "constant" -> {
                    cells = new BigDecimal[colCount];
                    Arrays.fill(cells, r.constant());
                }
                case "subtotal", "total" -> {
                    cells = copyOrZero(totals.get(r.totalNo()), colCount);
                    if ("total".equals(kind)) totals.remove(r.totalNo());
                }
                default -> {
                    if (r.startMain() == 0 && r.endMain() == 0) {
                        // Structure / label row, no account range. Emit label
                        // with blank cells; if there is no label either, skip.
                        // TODO: confirm against glrp60.cbl whether total_type='+'
                        // with no account range should print accumulator[total_no]
                        // (i.e. true subtotal printing) — needs COBOL verification.
                        if (!notBlank(r.lineDesc())) continue;
                        cells = new BigDecimal[colCount]; // all null = blank in jrxml
                    } else {
                        cells = aggregateAccountRow(s.getCompanyNo(), r, columns, colCount);
                        String op = trim(r.totalType()).toUpperCase();
                        if (r.totalNo() > 0 && ("+".equals(op) || "-".equals(op) || op.isEmpty())) {
                            BigDecimal[] acc = totals.computeIfAbsent(r.totalNo(), k -> zeroes(colCount));
                            boolean subtract = "-".equals(op);
                            for (int c = 0; c < colCount; c++) {
                                acc[c] = subtract ? acc[c].subtract(cells[c]) : acc[c].add(cells[c]);
                            }
                        }
                    }
                }
            }

            // Zero-suppression: omit account rows whose every cell is zero, but
            // always keep structure rows (subtotal/total/constant).
            if (p.zeroBalSuppress() && "account".equals(kind) && allZero(cells)) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rowLabel", labelOf(r));
            row.put("rowKind",  kind);
            for (int c = 1; c <= MAX_COLUMNS; c++) {
                row.put(colKey(c), c <= colCount ? cells[c - 1] : null);
            }
            outRows.add(row);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("REPORT_TITLE", notBlank(vert.desc1()) ? vert.desc1() : "Report Writer Output");
        params.put("VERT_DESC",  notBlank(vert.desc2()) ? vert.desc2() : "");
        params.put("HORIZ_DESC", notBlank(horiz.desc1()) ? horiz.desc1() : horiz.dateTableCode());
        params.put("YEAR_DESC",  "Year " + yr);
        params.put("COL_COUNT",  colCount);
        for (int c = 1; c <= MAX_COLUMNS; c++) {
            params.put(colHeadKey(c), c <= colCount ? headingOf(horiz.columns().get(c - 1)) : "");
        }
        params.put("ROW_COUNT", outRows.size());
        return result(outRows, params);
    }

    // ── Definition loaders ───────────────────────────────────────────────────

    /** A loaded vertical format — header + ordered row list. */
    public record VerticalFormat(
            int vertFormatNo, String desc1, String desc2, String vertFormatType,
            int roundingMain, int roundingSub, List<RowDef> rows) {}

    /** One {@code glrpvel} line — an output row template. */
    public record RowDef(
            int seqNo, int startMain, int startSub, int endMain, int endSub,
            String lineDesc, String drCrInd, String totalType, int totalNo,
            BigDecimal constant, int colNo, boolean printEachAcct,
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
                        "Y".equalsIgnoreCase(trim(rs.getString("print_each_acct_flag"))),
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

    /** One column's period bounds. */
    public record ColumnDef(int idx, LocalDate periodStart, LocalDate periodEnd) {}

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

    // ── Interpreter: aggregate one row's account range over the columns ──────

    /**
     * Pulls every {@code gltrx} posting in this row's account range across the
     * full date span (min column start … max column end) <b>in one query</b>,
     * then buckets each posting into its matching column in memory. With M rows
     * and N columns this gives M queries instead of M×N.
     *
     * <p>Sign convention: stored {@code dr_amt}/{@code cr_amt} are positive.
     * Net = (dr − cr). A credit-normal row ({@code dr_cr_ind='C'}) negates the
     * net so income rows render positive; debit-normal rows pass net through.
     */
    private BigDecimal[] aggregateAccountRow(int companyNo, RowDef r,
                                             List<ColumnDef> columns, int colCount) {
        BigDecimal[] cells = zeroes(colCount);
        if (colCount == 0) return cells;

        LocalDate spanStart = columns.get(0).periodStart();
        LocalDate spanEnd   = columns.get(0).periodEnd();
        for (int i = 1; i < colCount; i++) {
            if (columns.get(i).periodStart().isBefore(spanStart)) spanStart = columns.get(i).periodStart();
            if (columns.get(i).periodEnd().isAfter(spanEnd))     spanEnd   = columns.get(i).periodEnd();
        }

        int endMain = r.endMain() > 0 ? r.endMain() : r.startMain();
        int endSub  = r.endSub()  > 0 ? r.endSub()  : 9999;

        try {
            final LocalDate[] cols0 = new LocalDate[colCount];
            final LocalDate[] cols1 = new LocalDate[colCount];
            for (int i = 0; i < colCount; i++) {
                cols0[i] = columns.get(i).periodStart();
                cols1[i] = columns.get(i).periodEnd();
            }
            jdbc.query(
                "SELECT jnl_date, dr_amt, cr_amt FROM gltrx " +
                "WHERE company_no=? AND acct_main_no BETWEEN ? AND ? " +
                "  AND acct_sub_no BETWEEN ? AND ? " +
                "  AND jnl_date BETWEEN ? AND ?",
                rs -> {
                    java.sql.Date dd = rs.getDate("jnl_date");
                    if (dd == null) return;
                    LocalDate d = dd.toLocalDate();
                    BigDecimal net = z(rs.getBigDecimal("dr_amt")).subtract(z(rs.getBigDecimal("cr_amt")));
                    for (int c = 0; c < colCount; c++) {
                        if (!d.isBefore(cols0[c]) && !d.isAfter(cols1[c])) {
                            cells[c] = cells[c].add(net);
                            break;
                        }
                    }
                },
                companyNo, r.startMain(), endMain, r.startSub(), endSub,
                Date.valueOf(spanStart), Date.valueOf(spanEnd));
        } catch (Exception e) {
            log.warn("aggregateAccountRow row seq={} accts={}.{}–{}.{}: {}",
                r.seqNo(), r.startMain(), r.startSub(), endMain, endSub, e.getMessage());
        }

        if ("C".equalsIgnoreCase(r.drCrInd())) {
            for (int c = 0; c < colCount; c++) cells[c] = cells[c].negate();
        }
        return cells;
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

    private static String headingOf(ColumnDef c) {
        if (c.periodStart().equals(c.periodEnd())) return c.periodEnd().toString();
        return c.periodStart() + " to " + c.periodEnd();
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
