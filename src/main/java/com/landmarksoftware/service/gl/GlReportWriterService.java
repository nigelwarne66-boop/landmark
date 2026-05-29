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
     * Inputs for a one-shot matrix run (Selection picker resolves to this).
     * {@code selectionNo} is the glrpsel key when running a saved selection
     * (drives persistence to the glrpwkc rundates work table); 0 in manual mode.
     */
    public record RunParams(int selectionNo, int vertFormatNo, String horizFormatKey,
                            int yearNo, boolean zeroBalSuppress, String roundingFlag) {}

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
        // yearNo > 1900 = 4-digit calendar year (from the new top-of-screen date
        // setup) — use directly. 1..99 = COBOL fiscal sequence (legacy callers
        // still passing glrpsel.yr_no) — translate via gldates. 0 = session default.
        int yr;
        if (p.yearNo() >= 1900) {
            yr = p.yearNo();
        } else if (p.yearNo() > 0) {
            Integer calendar = lookupCalendarYear(s.getCompanyNo(), p.yearNo());
            yr = (calendar != null) ? calendar : s.getYearNo();
        } else {
            yr = s.getYearNo();
        }
        if (vert.rows().isEmpty()) {
            return warn("Vertical format " + p.vertFormatNo() + " has no lines in glrpvel.");
        }

        // Three-tier column resolution (mirrors how COBOL populates
        // GLRPSEL-REPORT-DATES-TABLE at run time): persisted rundates first,
        // then synthesise from horiz_format_no + gldates conventions, finally
        // fall through to the glrptah/glrptab template tables.
        String horizDesc;
        List<ColumnDef> columns = resolveColumns(s, p, yr);
        if (columns.isEmpty()) {
            return warn("Could not resolve any columns for horizontal '" + p.horizFormatKey()
                + "' (year " + yr + "). Load a glrptah/glrptab entry for this key, or pick a key the engine knows how to synthesise ('1', '2', '1B', '1A').");
        }
        horizDesc = describeHoriz(s.getCompanyNo(), p.horizFormatKey());
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
        params.put("HORIZ_DESC", horizDesc);
        params.put("YEAR_DESC",  "Year " + yr);
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

    // ── Column resolution (rundates → synth → glrptah) ───────────────────────

    /**
     * Three-tier column resolver. Returns columns in display order; persists a
     * synthesised set into {@code glrpwkc} when the run is tied to a saved
     * selection so re-runs stay deterministic and the dates are inspectable.
     */
    private List<ColumnDef> resolveColumns(AppSession s, RunParams p, int calendarYear) {
        // 1. Persisted rundates from glrpwkc (populated on first run, or by a future admin UI).
        if (p.selectionNo() > 0) {
            List<ColumnDef> persisted = loadRunDates(s.getCompanyNo(), p.selectionNo());
            if (!persisted.isEmpty()) return persisted;
        }
        // 2. Synthesise from horiz_format_no convention + gldates periods.
        List<ColumnDef> synth = synthesizeColumns(s.getCompanyNo(), p.horizFormatKey(), calendarYear);
        if (!synth.isEmpty()) {
            if (p.selectionNo() > 0) persistRunDates(s, p.selectionNo(), p.horizFormatKey(), synth);
            return synth;
        }
        // 3. Fall through to glrptah / glrptab (handles bespoke keys like 'Q').
        HorizontalTable horiz = loadHorizontalTable(s.getCompanyNo(), p.horizFormatKey(), calendarYear);
        return horiz != null ? horiz.columns() : List.of();
    }

    /**
     * Synthesises columns from {@code gldates} for the conventional Landmark
     * {@code horiz_format_no} codes. A trailing {@code B} = before year-end
     * (prior year); trailing {@code A} = after year-end (current year). Unknown
     * codes return empty so the resolver falls through to {@code glrptah}.
     */
    private List<ColumnDef> synthesizeColumns(int companyNo, String key, int calendarYear) {
        String code = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
        int year = calendarYear;
        if (code.endsWith("B")) { year = year - 1; code = code.substring(0, code.length() - 1); }
        else if (code.endsWith("A")) { code = code.substring(0, code.length() - 1); }
        return switch (code) {
            case "1" -> monthlyColumns(companyNo, year);
            case "2" -> annualColumn(companyNo, year);
            default  -> List.of();
        };
    }

    /** 12 monthly columns derived from {@code gldates.period_end_01..12}. */
    private List<ColumnDef> monthlyColumns(int companyNo, int calendarYear) {
        Map<String, Object> row = loadGlDatesRow(companyNo, calendarYear);
        if (row == null) return List.of();
        LocalDate yrStart = sqlToLocal(row.get("yr_start_date"));
        List<ColumnDef> cols = new ArrayList<>();
        LocalDate prevEnd = null;
        for (int i = 1; i <= 12; i++) {
            LocalDate end = sqlToLocal(row.get(String.format("period_end_%02d", i)));
            if (end == null || SENTINEL.equals(end) || end.equals(LocalDate.of(1899,12,30))) break;
            LocalDate start = (prevEnd != null) ? prevEnd.plusDays(1) : (yrStart != null ? yrStart : end.withDayOfMonth(1));
            cols.add(new ColumnDef(cols.size() + 1, start, end));
            prevEnd = end;
        }
        return cols;
    }

    /** Single year-as-at column spanning {@code yr_start_date} to {@code yr_end_date}. */
    private List<ColumnDef> annualColumn(int companyNo, int calendarYear) {
        Map<String, Object> row = loadGlDatesRow(companyNo, calendarYear);
        if (row == null) return List.of();
        LocalDate start = sqlToLocal(row.get("yr_start_date"));
        LocalDate end   = sqlToLocal(row.get("yr_end_date"));
        if (end == null) end = sqlToLocal(row.get("period_end_12"));
        if (start == null && end != null) start = end.withDayOfYear(1);
        if (start == null || end == null) return List.of();
        return List.of(new ColumnDef(1, start, end));
    }

    private Map<String, Object> loadGlDatesRow(int companyNo, int calendarYear) {
        try {
            StringBuilder cols = new StringBuilder("yr_start_date, yr_end_date");
            for (int i = 1; i <= 13; i++) cols.append(String.format(", period_end_%02d", i));
            return jdbc.queryForMap(
                "SELECT " + cols + " FROM gldates WHERE company_no=? AND year_no=?",
                companyNo, calendarYear);
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
            case "1" -> "Monthly periods";
            case "1B" -> "Monthly periods — prior year";
            case "1A" -> "Monthly periods — current year";
            case "2" -> "Year as-at";
            case "2B" -> "Year as-at — prior year";
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

    /**
     * Compact column heading that fits the 46-px PDF column. A ~one-month span
     * collapses to "MMM yy"; a full year to "FY yy"; anything else to the end
     * date "dd/MM/yy". Keeps the wide Excel template happy too (same expression).
     */
    private static String headingOf(ColumnDef c) {
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
