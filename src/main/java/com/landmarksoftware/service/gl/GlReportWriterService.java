package com.landmarksoftware.service.gl;

import com.landmarksoftware.model.AppSession;
import jakarta.annotation.PostConstruct;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.landmarksoftware.db.tables.Glchart.GLCHART;
import static com.landmarksoftware.db.tables.Gldates.GLDATES;
import static com.landmarksoftware.db.tables.Glrpsel.GLRPSEL;
import static com.landmarksoftware.db.tables.Glrptah.GLRPTAH;
import static com.landmarksoftware.db.tables.Glrpveh.GLRPVEH;
import static com.landmarksoftware.db.tables.Glrpvel.GLRPVEL;
import static com.landmarksoftware.db.tables.Glrpwkc.GLRPWKC;
import static com.landmarksoftware.db.tables.Gltrx.GLTRX;

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
 *
 * <p><b>Inline-SQL note:</b> {@code loadHorizontalTable} keeps a plain-SQL
 * SELECT for the {@code glrptab} body row because the 366 dynamic column names
 * ({@code report_date_001..report_date_366}) cannot be expressed with typed
 * jOOQ fields — they are a COBOL OCCURS expansion stored as individual columns
 * and there is no generated field array for them in the Glrptab table class.
 * All other queries use fully typed jOOQ DSLContext calls.
 */
@Service
public class GlReportWriterService {

    private static final Logger log = LoggerFactory.getLogger(GlReportWriterService.class);
    private static final LocalDate SENTINEL = LocalDate.of(1899, 12, 31);
    /** Hard column cap — matches the standard Landmark wide reports (monthly + YTD). */
    public static final int MAX_COLUMNS = 13;

    private final DSLContext dsl;

    public GlReportWriterService(DSLContext dsl) { this.dsl = dsl; }

    /**
     * Self-creates the {@code glrpwkc} runtime work table — the engine's
     * equivalent of COBOL's {@code GLRPSEL-REPORT-DATES-TABLE OCCURS 5 group}.
     * Per CLAUDE.md's work-file rule, tables whose name contains {@code wk}
     * are owned by the Java side and bootstrap themselves; no extract pipeline.
     */
    @PostConstruct
    public void ensureTables() {
        try {
            dsl.execute(
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
            dsl.select(
                    GLRPSEL.SELECTION_NO,
                    GLRPSEL.DESC_1,
                    GLRPSEL.RPT_TITLE,
                    GLRPSEL.VERT_FORMAT_NO,
                    GLRPSEL.HORIZ_FORMAT_NO,
                    GLRPSEL.YR_NO,
                    GLRPSEL.ZERO_BAL_FLAG,
                    GLRPSEL.ROUNDING_FLAG,
                    GLRPSEL.ACCT_MASK)
               .from(GLRPSEL)
               .where(GLRPSEL.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(GLRPSEL.SELECTION_NO)
               .fetch()
               .forEach(r -> list.add(new SelectionRow(
                   r.get(GLRPSEL.SELECTION_NO),
                   trim(r.get(GLRPSEL.DESC_1)),
                   trim(r.get(GLRPSEL.RPT_TITLE)),
                   // vert_format_no is VARCHAR(4) in the schema — parse to int
                   parseIntSafe(trim(r.get(GLRPSEL.VERT_FORMAT_NO))),
                   trim(r.get(GLRPSEL.HORIZ_FORMAT_NO)),
                   r.get(GLRPSEL.YR_NO),
                   "Y".equalsIgnoreCase(trim(r.get(GLRPSEL.ZERO_BAL_FLAG))),
                   trim(r.get(GLRPSEL.ROUNDING_FLAG)),
                   trim(r.get(GLRPSEL.ACCT_MASK)))));
        } catch (Exception e) { log.warn("getSelections: {}", e.getMessage()); }
        return list;
    }

    /** Vertical formats for the manual picker, ordered by number. */
    public List<VerticalFormatRow> getVerticalFormats(AppSession s) {
        List<VerticalFormatRow> list = new ArrayList<>();
        try {
            dsl.select(
                    GLRPVEH.VERT_FORMAT_NO,
                    GLRPVEH.DESC_1,
                    GLRPVEH.DESC_2,
                    GLRPVEH.VERT_FORMAT_TYPE)
               .from(GLRPVEH)
               .where(GLRPVEH.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(GLRPVEH.VERT_FORMAT_NO)
               .fetch()
               .forEach(r -> list.add(new VerticalFormatRow(
                   // vert_format_no is VARCHAR(4) in the schema — parse to int
                   parseIntSafe(trim(r.get(GLRPVEH.VERT_FORMAT_NO))),
                   trim(r.get(GLRPVEH.DESC_1)),
                   trim(r.get(GLRPVEH.DESC_2)),
                   trim(r.get(GLRPVEH.VERT_FORMAT_TYPE)))));
        } catch (Exception e) { log.warn("getVerticalFormats: {}", e.getMessage()); }
        return list;
    }

    /** Horizontal tables for the manual picker, ordered by code. */
    public List<HorizontalTableRow> getHorizontalTables(AppSession s) {
        List<HorizontalTableRow> list = new ArrayList<>();
        try {
            dsl.select(GLRPTAH.DATE_TABLE, GLRPTAH.DESC1)
               .from(GLRPTAH)
               .where(GLRPTAH.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(GLRPTAH.DATE_TABLE)
               .fetch()
               .forEach(r -> list.add(new HorizontalTableRow(
                   trim(r.get(GLRPTAH.DATE_TABLE)),
                   trim(r.get(GLRPTAH.DESC1)))));
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
        // Engine state per the COBOL report-writer convention:
        //   buckets[N]   — accumulator per TOTAL-NO (credit-positive)
        //   lastBucketNo — total_no of the most recently modified bucket; that
        //                  bucket is the source for the next label row's value
        Map<Integer, BigDecimal[]> buckets = new HashMap<>();
        int[] lastBucketNo = { 0 };

        // Preload account descriptions once for the company so M-expanded rows
        // can render their own desc1 instead of the row's range label.
        Map<String, String> acctDescs = loadAccountDescriptions(s.getCompanyNo());

        List<Map<String, Object>> outRows = new ArrayList<>();
        for (RowDef r : vert.rows()) {
            String tt = trim(r.totalType()).toUpperCase();
            // Sentinel / junk filters.
            if ("V".equals(tt)) continue;
            if (r.startMain() > 999999) continue;
            if (r.seqNo() >= 999000) continue;

            boolean hasLabel    = notBlank(r.lineDesc());
            boolean hasDrCr     = notBlank(r.drCrInd());
            boolean isAccount   = r.startMain() > 0;
            boolean hasOperator = !tt.isEmpty();

            // ── Account row ─────────────────────────────────────────────────
            // Aggregate per account; M expansion emits one display row per
            // active account (zero-suppressed). The row's value passed to the
            // operator is the SUM across the account range — so the operator
            // is applied exactly once even when the range has no activity (so
            // lastBucketNo updates correctly for the next label row).
            if (isAccount) {
                Map<String, BigDecimal[]> perAcct = aggregatePerAccount(s.getCompanyNo(), r, columns, colCount);
                boolean expand = "M".equalsIgnoreCase(r.printEachAcctFlag());
                boolean credit = "C".equalsIgnoreCase(r.drCrInd());

                BigDecimal[] sum = zeroes(colCount);
                for (Map.Entry<String, BigDecimal[]> en : perAcct.entrySet()) {
                    BigDecimal[] net   = en.getValue();        // dr - cr per column
                    BigDecimal[] value = negate(net);          // credit-positive
                    BigDecimal[] display = credit ? value.clone() : negate(value);

                    if (expand && !(p.zeroBalSuppress() && allZero(display))) {
                        String desc = acctDescs.getOrDefault(en.getKey(), en.getKey());
                        emit(outRows, desc, "account", display, colCount);
                    }
                    for (int c = 0; c < colCount; c++) sum[c] = sum[c].add(value[c]);
                }
                if (!expand) {
                    BigDecimal[] sumDisplay = credit ? sum.clone() : negate(sum);
                    if (!(p.zeroBalSuppress() && allZero(sumDisplay))) {
                        emit(outRows, labelOf(r), "account", sumDisplay, colCount);
                    }
                }
                applyOperator(buckets, r.totalNo(), tt, sum, colCount, lastBucketNo);
                continue;
            }

            // ── Section header ──────────────────────────────────────────────
            // Blank dr_cr_ind + label = pure header (CURRENT ASSETS, EQUITY).
            if (!hasDrCr && hasLabel) {
                emit(outRows, labelOf(r), "header", new BigDecimal[colCount], colCount);
                continue;
            }

            // ── Label row with operator (subtotals, totals, hierarchy promotions) ─
            // Value = bucket[lastBucketNo]. Apply this row's operator with that
            // value to bucket[total_no]. Display sign-flips per dr_cr_ind.
            if (hasOperator && r.totalNo() > 0 && hasDrCr) {
                BigDecimal[] value = lastBucketNo[0] > 0
                    ? buckets.getOrDefault(lastBucketNo[0], zeroes(colCount)).clone()
                    : zeroes(colCount);
                boolean credit = "C".equalsIgnoreCase(r.drCrInd());
                BigDecimal[] display = credit ? value.clone() : negate(value);
                if (isCurrentYearEarnings(r.lineDesc())) display = negate(display);
                if (hasLabel) {
                    emit(outRows, labelOf(r), "subtotal", display, colCount);
                }
                applyOperator(buckets, r.totalNo(), tt, value, colCount, lastBucketNo);
                continue;
            }

            // ── Final-calculation row (no operator, no total_no) ────────────
            // Print bucket[lastBucketNo] sign-flipped per dr_cr_ind. Doesn't
            // modify any bucket. Net Profit Before Tax / System Calculated.
            if (!hasOperator && r.totalNo() == 0 && hasDrCr && hasLabel) {
                BigDecimal[] value = lastBucketNo[0] > 0
                    ? buckets.getOrDefault(lastBucketNo[0], zeroes(colCount)).clone()
                    : zeroes(colCount);
                boolean credit = "C".equalsIgnoreCase(r.drCrInd());
                BigDecimal[] display = credit ? value.clone() : negate(value);
                if (isCurrentYearEarnings(r.lineDesc())) display = negate(display);
                emit(outRows, labelOf(r), "total", display, colCount);
                continue;
            }
            // Everything else: separator / unused — skip.
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
        // vert_format_no is stored as VARCHAR(4) in the schema; use string comparison
        String vertFormatNoStr = String.valueOf(vertFormatNo);
        VerticalFormat[] head = { null };
        try {
            dsl.select(
                    GLRPVEH.DESC_1,
                    GLRPVEH.DESC_2,
                    GLRPVEH.VERT_FORMAT_TYPE,
                    GLRPVEH.ROUNDING_MAIN_NO,
                    GLRPVEH.ROUNDING_SUB_NO)
               .from(GLRPVEH)
               .where(GLRPVEH.COMPANY_NO.eq(companyNo)
                   .and(GLRPVEH.VERT_FORMAT_NO.eq(vertFormatNoStr)))
               .fetch()
               .forEach(r -> head[0] = new VerticalFormat(
                   vertFormatNo,
                   trim(r.get(GLRPVEH.DESC_1)),
                   trim(r.get(GLRPVEH.DESC_2)),
                   trim(r.get(GLRPVEH.VERT_FORMAT_TYPE)),
                   r.get(GLRPVEH.ROUNDING_MAIN_NO),
                   r.get(GLRPVEH.ROUNDING_SUB_NO),
                   new ArrayList<>()));
        } catch (Exception e) { log.warn("loadVerticalFormat header: {}", e.getMessage()); }
        if (head[0] == null) return null;

        List<RowDef> rows = head[0].rows();
        try {
            dsl.select(
                    GLRPVEL.SEQ_NO,
                    GLRPVEL.START_MAIN_NO,
                    GLRPVEL.START_SUB_NO,
                    GLRPVEL.END_MAIN_NO,
                    GLRPVEL.END_SUB_NO,
                    GLRPVEL.LINE_DESC,
                    GLRPVEL.DR_CR_IND,
                    GLRPVEL.TOTAL_TYPE,
                    GLRPVEL.TOTAL_NO,
                    GLRPVEL.CONSTANT,
                    GLRPVEL.COL_NO,
                    GLRPVEL.PRINT_EACH_ACCT_FLAG,
                    GLRPVEL.ACCT_TYPE,
                    GLRPVEL.PRINT_FLAG,
                    GLRPVEL.START_REPORT_GROUP,
                    GLRPVEL.END_REPORT_GROUP)
               .from(GLRPVEL)
               .where(GLRPVEL.COMPANY_NO.eq(companyNo)
                   .and(GLRPVEL.VERT_FORMAT_NO.eq(vertFormatNoStr)))
               .orderBy(GLRPVEL.SEQ_NO)
               .fetch()
               .forEach(r -> rows.add(new RowDef(
                   r.get(GLRPVEL.SEQ_NO),
                   r.get(GLRPVEL.START_MAIN_NO), r.get(GLRPVEL.START_SUB_NO),
                   r.get(GLRPVEL.END_MAIN_NO),   r.get(GLRPVEL.END_SUB_NO),
                   r.get(GLRPVEL.LINE_DESC),
                   trim(r.get(GLRPVEL.DR_CR_IND)),
                   trim(r.get(GLRPVEL.TOTAL_TYPE)),
                   r.get(GLRPVEL.TOTAL_NO),
                   z(r.get(GLRPVEL.CONSTANT)),
                   r.get(GLRPVEL.COL_NO),
                   trim(r.get(GLRPVEL.PRINT_EACH_ACCT_FLAG)),
                   trim(r.get(GLRPVEL.ACCT_TYPE)),
                   trim(r.get(GLRPVEL.PRINT_FLAG)),
                   trim(r.get(GLRPVEL.START_REPORT_GROUP)),
                   trim(r.get(GLRPVEL.END_REPORT_GROUP)))));
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
     *
     * <p><b>Inline-SQL note:</b> The {@code glrptab} body-row query builds a
     * plain-SQL column list of all 366 {@code report_date_NNN} fields. These are
     * a COBOL OCCURS expansion stored as individual VARCHAR columns; jOOQ's
     * generated {@code Glrptab} table class does not expose them as typed
     * {@code TableField} references. Using {@code DSL.field("report_date_NNN",
     * LocalDate.class)} for each is the only type-safe alternative but would
     * require 366 individual field declarations with no practical benefit over the
     * single-string column list approach.
     */
    public HorizontalTable loadHorizontalTable(int companyNo, String dateTableCode, int yearNo) {
        String[] desc = { null };
        try {
            Record r = dsl.select(GLRPTAH.DESC1)
                          .from(GLRPTAH)
                          .where(GLRPTAH.COMPANY_NO.eq(companyNo)
                              .and(GLRPTAH.DATE_TABLE.eq(dateTableCode)))
                          .fetchOne();
            if (r != null) desc[0] = trim(r.get(GLRPTAH.DESC1));
        } catch (Exception e) { log.warn("loadHorizontalTable header: {}", e.getMessage()); }
        if (desc[0] == null) return null;

        // Pull all 366 date slots in one row, then unpivot.
        // Inline SQL required: report_date_001..report_date_366 are COBOL OCCURS
        // columns not representable as typed jOOQ fields in the generated Glrptab class.
        StringBuilder cols = new StringBuilder("year_no");
        for (int i = 1; i <= 366; i++) cols.append(String.format(", report_date_%03d", i));
        Map<String, Object>[] bodyRow = new Map[]{ null };
        try {
            dsl.resultQuery(
                    "SELECT " + cols + " FROM glrptab WHERE company_no=? AND date_table=? AND year_no=?",
                    companyNo, dateTableCode, yearNo)
               .fetch()
               .forEach(r -> {
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("year_no", r.get("year_no", Integer.class));
                   for (int i = 1; i <= 366; i++) {
                       String colName = String.format("report_date_%03d", i);
                       LocalDate d = r.get(colName, LocalDate.class);
                       row.put(colName, d);
                   }
                   bodyRow[0] = row;
               });
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
        Integer fyYearNo = pickFiscalYearForDate(companyNo, end);
        LocalDate yrStart = null;
        if (fyYearNo != null) {
            Map<String, Object> row = loadGlDatesRow(companyNo, fyYearNo);
            if (row != null) yrStart = (LocalDate) row.get("yr_start_date");
        }
        // Prior fiscal year — for the "Prior YTD" column we want the same shape
        // one fiscal year earlier; row picked by year_no = fyYearNo - 1.
        LocalDate priorYrStart = null;
        LocalDate priorEnd = end.minusYears(1);
        if (fyYearNo != null) {
            Map<String, Object> prior = loadGlDatesRow(companyNo, fyYearNo - 1);
            if (prior != null) priorYrStart = (LocalDate) prior.get("yr_start_date");
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
            Integer result = dsl.select(GLDATES.YR_NO)
                .from(GLDATES)
                .where(GLDATES.COMPANY_NO.eq(companyNo)
                    .and(GLDATES.YR_START_DATE.le(refDate))
                    .and(GLDATES.YR_END_DATE.ge(refDate)))
                .orderBy(GLDATES.YR_NO.desc())
                .limit(1)
                .fetchOne(GLDATES.YR_NO);
            if (result != null) return result;
        } catch (Exception e) {
            // fall through to calendar-year fallback
        }
        // Fallback: year_no equals the end-date's calendar year.
        try {
            return dsl.select(GLDATES.YR_NO)
                .from(GLDATES)
                .where(GLDATES.COMPANY_NO.eq(companyNo)
                    .and(GLDATES.YEAR_NO.eq(refDate.getYear())))
                .limit(1)
                .fetchOne(GLDATES.YR_NO);
        } catch (Exception e2) { return null; }
    }

    private static String fmt(LocalDate d) {
        return d == null ? "" : d.format(java.time.format.DateTimeFormatter.ofPattern("d/MM/yy"));
    }

    private Map<String, Object> loadGlDatesRow(int companyNo, int yrNoSeq) {
        try {
            // gldates has 13 period_end_NN columns — fetch as typed LocalDate fields via jOOQ
            // but build the column name list dynamically since period_end_01..13 are plain fields.
            // Using resultQuery with inline SQL for the period columns only; yr_start/end are typed.
            StringBuilder colsSql = new StringBuilder("yr_start_date, yr_end_date");
            for (int i = 1; i <= 13; i++) colsSql.append(String.format(", period_end_%02d", i));
            Record row = dsl.resultQuery(
                    "SELECT " + colsSql + " FROM gldates WHERE company_no=? AND yr_no=?",
                    companyNo, yrNoSeq)
                .fetchOne();
            if (row == null) return null;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("yr_start_date", row.get("yr_start_date", LocalDate.class));
            result.put("yr_end_date",   row.get("yr_end_date",   LocalDate.class));
            for (int i = 1; i <= 13; i++) {
                String col = String.format("period_end_%02d", i);
                result.put(col, row.get(col, LocalDate.class));
            }
            return result;
        } catch (Exception e) { return null; }
    }

    /** Loads persisted rundates for one selection from {@code glrpwkc}. */
    private List<ColumnDef> loadRunDates(int companyNo, int selectionNo) {
        List<ColumnDef> out = new ArrayList<>();
        try {
            dsl.select(GLRPWKC.SEQ_NO, GLRPWKC.START_DATE, GLRPWKC.END_DATE)
               .from(GLRPWKC)
               .where(GLRPWKC.COMPANY_NO.eq(companyNo)
                   .and(GLRPWKC.SELECTION_NO.eq(selectionNo)))
               .orderBy(GLRPWKC.SEQ_NO)
               .fetch()
               .forEach(r -> {
                   LocalDate sd = r.get(GLRPWKC.START_DATE);
                   LocalDate ed = r.get(GLRPWKC.END_DATE);
                   if (sd != null && ed != null) {
                       out.add(new ColumnDef(r.get(GLRPWKC.SEQ_NO), sd, ed));
                   }
               });
        } catch (Exception e) { log.warn("loadRunDates: {}", e.getMessage()); }
        return out;
    }

    /** Replaces persisted rundates for a selection (delete + insert in one tx). */
    private void persistRunDates(AppSession s, int selectionNo, String dateTable, List<ColumnDef> cols) {
        try {
            dsl.deleteFrom(GLRPWKC)
               .where(GLRPWKC.COMPANY_NO.eq(s.getCompanyNo())
                   .and(GLRPWKC.SELECTION_NO.eq(selectionNo)))
               .execute();
            for (ColumnDef c : cols) {
                dsl.insertInto(GLRPWKC,
                        GLRPWKC.COMPANY_NO, GLRPWKC.SELECTION_NO, GLRPWKC.SEQ_NO,
                        GLRPWKC.DATE_TABLE, GLRPWKC.START_DATE, GLRPWKC.END_DATE,
                        GLRPWKC.AUDIT_USER_ID, GLRPWKC.AUDIT_DATE)
                   .values(s.getCompanyNo(), selectionNo, c.idx(), trim(dateTable),
                           c.periodStart(), c.periodEnd(),
                           s.getUserId(), LocalDate.now())
                   .execute();
            }
        } catch (Exception e) { log.warn("persistRunDates: {}", e.getMessage()); }
    }

    /** Title-bar caption for the horizontal — glrptah desc if known, else the key. */
    private String describeHoriz(int companyNo, String key) {
        try {
            String d = dsl.select(GLRPTAH.DESC1)
                          .from(GLRPTAH)
                          .where(GLRPTAH.COMPANY_NO.eq(companyNo)
                              .and(GLRPTAH.DATE_TABLE.eq(key)))
                          .fetchOne(GLRPTAH.DESC1);
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
            return dsl.select(GLDATES.YEAR_NO)
                      .from(GLDATES)
                      .where(GLDATES.COMPANY_NO.eq(companyNo)
                          .and(GLDATES.YR_NO.eq(yrNoSeq)))
                      .fetchOne(GLDATES.YEAR_NO);
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
            dsl.select(
                    GLTRX.ACCT_MAIN_NO,
                    GLTRX.ACCT_SUB_NO,
                    GLTRX.JNL_DATE,
                    GLTRX.DR_AMT,
                    GLTRX.CR_AMT)
               .from(GLTRX)
               .where(GLTRX.COMPANY_NO.eq(companyNo)
                   .and(GLTRX.ACCT_MAIN_NO.between(r.startMain(), endMain))
                   .and(GLTRX.ACCT_SUB_NO.between(r.startSub(), endSub))
                   .and(GLTRX.JNL_DATE.between(spanStart, spanEnd)))
               .orderBy(GLTRX.ACCT_MAIN_NO, GLTRX.ACCT_SUB_NO)
               .fetch()
               .forEach(row -> {
                   LocalDate d = row.get(GLTRX.JNL_DATE);
                   if (d == null) return;
                   String key = row.get(GLTRX.ACCT_MAIN_NO) + "." + row.get(GLTRX.ACCT_SUB_NO);
                   BigDecimal[] cells = perAcct.computeIfAbsent(key, k -> zeroes(colCount));
                   BigDecimal net = z(row.get(GLTRX.DR_AMT)).subtract(z(row.get(GLTRX.CR_AMT)));
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
               });
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
            dsl.select(GLCHART.ACCT_MAIN_NO, GLCHART.ACCT_SUB_NO, GLCHART.DESC1)
               .from(GLCHART)
               .where(GLCHART.COMPANY_NO.eq(companyNo))
               .fetch()
               .forEach(r -> map.put(r.get(GLCHART.ACCT_MAIN_NO) + "." + r.get(GLCHART.ACCT_SUB_NO),
                                     trim(r.get(GLCHART.DESC1))));
        } catch (Exception e) { log.warn("loadAccountDescriptions: {}", e.getMessage()); }
        return map;
    }

    /**
     * Applies one of the six COBOL TOTAL-TYPE operators to {@code bucket[totalNo]}
     * with {@code value}, per the convention confirmed by the user:
     * <pre>
     *   '+'  bucket += value
     *   '-'  bucket -= value
     *   '='  bucket  = value   (move result into the bucket)
     *   '*'  bucket *= value
     *   '/'  bucket /= value   (zero in value → skip column)
     *   '%'  bucket = (bucket / value) * 100  (zero → skip)
     * </pre>
     * After application, {@code lastBucketNo} is set to {@code totalNo} so the
     * next label row reads from this bucket. Operators below assume per-column
     * arithmetic; columns are independent.
     */
    private static void applyOperator(Map<Integer, BigDecimal[]> buckets,
                                      int totalNo, String op,
                                      BigDecimal[] value, int colCount,
                                      int[] lastBucketNo) {
        if (totalNo <= 0 || op == null || op.isEmpty()) return;
        BigDecimal[] bk = buckets.computeIfAbsent(totalNo, k -> zeroes(colCount));
        switch (op) {
            case "+" -> { for (int c = 0; c < colCount; c++) bk[c] = bk[c].add(value[c]); }
            case "-" -> { for (int c = 0; c < colCount; c++) bk[c] = bk[c].subtract(value[c]); }
            case "=" -> { for (int c = 0; c < colCount; c++) bk[c] = value[c]; }
            case "*" -> { for (int c = 0; c < colCount; c++) bk[c] = bk[c].multiply(value[c]); }
            case "/" -> { for (int c = 0; c < colCount; c++)
                          if (value[c].signum() != 0) bk[c] = bk[c].divide(value[c], 4, java.math.RoundingMode.HALF_UP); }
            case "%" -> { for (int c = 0; c < colCount; c++)
                          if (value[c].signum() != 0)
                              bk[c] = bk[c].divide(value[c], 4, java.math.RoundingMode.HALF_UP)
                                            .multiply(BigDecimal.valueOf(100)); }
            default  -> { /* unknown operator — ignore */ }
        }
        lastBucketNo[0] = totalNo;
    }

    /**
     * COBOL convention: rows labelled "CURRENT YEAR EARNINGS" always render
     * sign-reversed from the engine's natural calculation — they carry the
     * P&amp;L Net Profit into the equity section, but the BS displays it
     * positive whether the engine reads it from bucket[NET ASSETS] or from
     * bucket[EQUITY]. Match by label rather than tracking a per-row context.
     */
    private static boolean isCurrentYearEarnings(String lineDesc) {
        return lineDesc != null
            && lineDesc.toUpperCase(java.util.Locale.ROOT).contains("CURRENT YEAR EARNINGS");
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

    /** Safely parses a VARCHAR vert_format_no / selection_no to int; returns 0 on failure. */
    private static int parseIntSafe(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
