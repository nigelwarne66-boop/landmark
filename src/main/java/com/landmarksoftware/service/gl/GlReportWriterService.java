package com.landmarksoftware.service.gl;

import com.landmarksoftware.model.AppSession;
import jakarta.annotation.PostConstruct;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.landmarksoftware.db.tables.Glchart.GLCHART;
import static com.landmarksoftware.db.tables.Gldates.GLDATES;
import static com.landmarksoftware.db.tables.Glrphof.GLRPHOF;
import static com.landmarksoftware.db.tables.Glrphoh.GLRPHOH;
import static com.landmarksoftware.db.tables.Glrpsel.GLRPSEL;
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
 *   <li><b>Columns</b> come from a "horizontal format" — {@code glrphoh} (header,
 *       layout description) + {@code glrphof} (one row per output column). Each
 *       {@code glrphof} row defines the date range via {@code data_period_select}
 *       (0=PTD, 1=YTD, 2=Prior YTD, 4=As-at cumulative, 6=Specific period).</li>
 *   <li>A <b>"selection"</b> ({@code glrpsel}) ties a vertical format to a
 *       horizontal format plus print options (zero-balance suppression,
 *       rounding, year, before / after year-end indicator, account mask).</li>
 * </ul>
 *
 * <p><b>Phase 1 scope</b> — load definitions, return the matrix shape (one
 * row per {@code glrpvel} line, up to 13 columns derived from {@code glrphof})
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
            String vertFormatNo, String horizFormatKey, int yrNo,
            boolean zeroBalSuppress, String roundingFlag, String acctMask) {
        @Override public String toString() {
            String t = notBlank(rptTitle) ? rptTitle : (notBlank(desc1) ? desc1 : "Selection " + selectionNo);
            return selectionNo + " — " + t;
        }
    }

    /** A vertical format header from {@code glrpveh} (used for the manual picker). */
    public record VerticalFormatRow(
            String vertFormatNo, String desc1, String desc2, String vertFormatType) {
        @Override public String toString() {
            return vertFormatNo + " — " + (notBlank(desc1) ? desc1 : "(unnamed)");
        }
    }

    /** A horizontal format from {@code glrphoh} (used for the manual picker). */
    public record HorizontalTableRow(
            String layoutNo, String desc1) {
        @Override public String toString() {
            return layoutNo + " — " + (notBlank(desc1) ? desc1 : "(unnamed)");
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
                   trim(r.get(GLRPSEL.VERT_FORMAT_NO)),
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
                   trim(r.get(GLRPVEH.VERT_FORMAT_NO)),
                   trim(r.get(GLRPVEH.DESC_1)),
                   trim(r.get(GLRPVEH.DESC_2)),
                   trim(r.get(GLRPVEH.VERT_FORMAT_TYPE)))));
        } catch (Exception e) { log.warn("getVerticalFormats: {}", e.getMessage()); }
        return list;
    }

    /** Horizontal formats for the manual picker, ordered by layout_no. */
    public List<HorizontalTableRow> getHorizontalTables(AppSession s) {
        List<HorizontalTableRow> list = new ArrayList<>();
        try {
            dsl.select(GLRPHOH.LAYOUT_NO, GLRPHOH.DESC_1)
               .from(GLRPHOH)
               .where(GLRPHOH.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(GLRPHOH.LAYOUT_NO)
               .fetch()
               .forEach(r -> list.add(new HorizontalTableRow(
                   trim(r.get(GLRPHOH.LAYOUT_NO)),
                   trim(r.get(GLRPHOH.DESC_1)))));
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
    public record RunParams(int selectionNo, String vertFormatNo, String horizFormatKey,
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

        // Resolve columns from glrphof (horizontal format column definitions).
        // Each printable D row with data_select<=9 yields one date range.
        // data_select: 1=this yr, 2=last yr, 3-9=budget/cost. data_select>9 = label column, skipped.
        List<ColumnDef> columns = resolveColumns(s, p);
        if (columns.isEmpty()) {
            return warn("Could not resolve any columns for horizontal '" + p.horizFormatKey()
                + "'. Check that glrphof has printable data rows for this layout_no.");
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
                        fillCalcColumns(display, colCount, columns, !credit);
                        emit(outRows, desc, "account", display, colCount);
                    }
                    for (int c = 0; c < colCount; c++) sum[c] = sum[c].add(value[c]);
                }
                if (!expand) {
                    BigDecimal[] sumDisplay = credit ? sum.clone() : negate(sum);
                    if (!(p.zeroBalSuppress() && allZero(sumDisplay))) {
                        fillCalcColumns(sumDisplay, colCount, columns, !credit);
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
                    fillCalcColumns(display, colCount, columns, !credit);
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
                fillCalcColumns(display, colCount, columns, !credit);
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
            String vertFormatNo, String desc1, String desc2, String vertFormatType,
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
    public VerticalFormat loadVerticalFormat(int companyNo, String vertFormatNo) {
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
                   .and(GLRPVEH.VERT_FORMAT_NO.eq(vertFormatNo)))
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
                   .and(GLRPVEL.VERT_FORMAT_NO.eq(vertFormatNo)))
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

    /**
     * One column definition. Data columns carry {@code periodStart}/{@code periodEnd} and fetch
     * from {@code gltrx}. Calc columns ({@code isCalcPct()==true}) have null dates and are
     * computed post-aggregation as {@code cells[calcNumeratorIdx] / cells[calcDenominatorIdx] * 100},
     * truncated, then sign-flipped for DR-side rows.
     */
    public record ColumnDef(int idx, LocalDate periodStart, LocalDate periodEnd, String label,
                            int calcNumeratorIdx, int calcDenominatorIdx) {
        public ColumnDef(int idx, LocalDate s, LocalDate e, String label) {
            this(idx, s, e, label, -1, -1);
        }
        public ColumnDef(int idx, LocalDate s, LocalDate e) { this(idx, s, e, null, -1, -1); }
        public boolean isCalcPct() { return calcNumeratorIdx >= 0; }
    }

    // ── Column resolution (glrphof) ──────────────────────────────────────────

    /**
     * Column resolver — reads column definitions from {@code glrphof} and persists
     * the resolved date ranges to {@code glrpwkc} for saved selections.
     */
    private List<ColumnDef> resolveColumns(AppSession s, RunParams p) {
        if (p.startDate() == null || p.endDate() == null) return List.of();
        List<ColumnDef> cols = resolveColumnsFromGlrphof(
            s.getCompanyNo(), p.horizFormatKey(), p.startDate(), p.endDate());
        if (!cols.isEmpty() && p.selectionNo() > 0) {
            persistRunDates(s, p.selectionNo(), p.horizFormatKey(), cols);
        }
        return cols;
    }

    /**
     * Resolves column date ranges from {@code glrphof} for the given horizontal layout.
     * Uses <b>both</b> {@code data_select} and {@code data_period_select} (the two
     * dimensions of a column definition in COBOL {@code glrp60}):
     * <ul>
     *   <li>{@code data_select} — which year's data: 1=this year, 2=last year, 3-8=budgets, 9=costs.
     *       Values &gt; 9 (10=Account No, 11=Description, 12=Ratio) are skipped — they are
     *       row-label columns, not numeric data columns.</li>
     *   <li>{@code data_period_select} — which period within that year:
     *       1=PTD, 2=YTD, 3=Opening balance, 4=Closing balance,
     *       5=Full year, 6=Specific period (uses {@code data_period_no} 1-13), 7=Period prior.</li>
     * </ul>
     */
    private List<ColumnDef> resolveColumnsFromGlrphof(int companyNo, String layoutNo,
                                                       LocalDate startDate, LocalDate endDate) {
        Integer fyYearNo = pickFiscalYearForDate(companyNo, endDate);
        Map<String, Object> curGlDates = fyYearNo != null ? loadGlDatesRow(companyNo, fyYearNo) : null;
        LocalDate yrStart = curGlDates != null ? (LocalDate) curGlDates.get("yr_start_date") : null;
        LocalDate yrEnd   = curGlDates != null ? (LocalDate) curGlDates.get("yr_end_date")   : null;

        // Prior year gldates — needed for data_select = 2/4/7 (last year)
        Integer priorFyNo = yrStart != null
                ? pickFiscalYearForDate(companyNo, yrStart.minusDays(1)) : null;
        Map<String, Object> priorGlDates = priorFyNo != null ? loadGlDatesRow(companyNo, priorFyNo) : null;
        LocalDate priorYrStart = priorGlDates != null
                ? (LocalDate) priorGlDates.get("yr_start_date")
                : (yrStart != null ? yrStart.minusYears(1) : startDate.minusYears(1));
        LocalDate priorYrEnd = priorGlDates != null
                ? (LocalDate) priorGlDates.get("yr_end_date")
                : (yrEnd != null ? yrEnd.minusYears(1) : endDate.minusYears(1));

        LocalDate sentinel = LocalDate.of(1900, 1, 1);
        List<ColumnDef> columns = new ArrayList<>();
        // Track 0-based indices of "Actual YTD" and "Prior YTD" for the % Dif calc column.
        int[] lastThisYrYtdIdx  = { -1 };
        int[] lastPriorYrYtdIdx = { -1 };
        try {
            dsl.select(
                    GLRPHOF.FIELD_NO,
                    GLRPHOF.DATA_OR_CALC_IND,
                    GLRPHOF.PRINT_FLAG,
                    GLRPHOF.DATA_SELECT,
                    GLRPHOF.DATA_PERIOD_SELECT,
                    GLRPHOF.DATA_PERIOD_NO)
               .from(GLRPHOF)
               .where(GLRPHOF.COMPANY_NO.eq(companyNo)
                   .and(GLRPHOF.LAYOUT_NO.eq(layoutNo)))
               .orderBy(GLRPHOF.FIELD_NO)
               .fetch()
               .forEach(r -> {
                   if (!"Y".equalsIgnoreCase(trim(r.get(GLRPHOF.PRINT_FLAG)))) return;
                   int dataSelect   = r.get(GLRPHOF.DATA_SELECT);
                   int periodSelect = r.get(GLRPHOF.DATA_PERIOD_SELECT);
                   int periodNo     = r.get(GLRPHOF.DATA_PERIOD_NO);
                   boolean isCalc   = "C".equalsIgnoreCase(trim(r.get(GLRPHOF.DATA_OR_CALC_IND)));

                   // Calc column (data_or_calc_ind='C'): emit a % Dif derived column.
                   // Formula: cells[numerator] / cells[denominator] * 100, truncated.
                   // Sign flipped for DR-side (expense) rows. Requires both YTD columns seen first.
                   if (isCalc) {
                       if (lastThisYrYtdIdx[0] >= 0 && lastPriorYrYtdIdx[0] >= 0) {
                           int idx = columns.size() + 1;
                           columns.add(new ColumnDef(idx, null, null, "% Dif",
                                                     lastThisYrYtdIdx[0], lastPriorYrYtdIdx[0]));
                       }
                       return;
                   }

                   // Skip non-numeric label columns: Description(11), Account No(10), Ratio(12)
                   if (dataSelect > 9) return;

                   // Year dimension: 2/4/7 = last year, else this year (5/8 next-year not yet handled)
                   boolean isLastYear = (dataSelect == 2 || dataSelect == 4 || dataSelect == 7);

                   LocalDate colYrStart   = isLastYear ? priorYrStart : (yrStart != null ? yrStart : startDate);
                   LocalDate colYrEnd     = isLastYear ? priorYrEnd   : yrEnd;
                   LocalDate colStartDate = isLastYear ? startDate.minusYears(1) : startDate;
                   LocalDate colEndDate   = isLastYear ? endDate.minusYears(1)   : endDate;
                   Map<String, Object> colDates = isLastYear ? priorGlDates : curGlDates;

                   int zeroIdx = columns.size(); // 0-based index this column will occupy
                   int idx     = zeroIdx + 1;    // 1-based for ColumnDef.idx
                   ColumnDef col = null;
                   switch (periodSelect) {
                       case 1 -> col = new ColumnDef(idx, colStartDate, colEndDate,
                                                      isLastYear ? "Prior Period" : "Actual PTD");
                       case 2 -> {
                           col = new ColumnDef(idx, colYrStart, colEndDate,
                                               isLastYear ? "Prior YTD" : "Actual YTD");
                           // Track for % Dif calc
                           if (!isLastYear) lastThisYrYtdIdx[0]  = zeroIdx;
                           else             lastPriorYrYtdIdx[0] = zeroIdx;
                       }
                       case 3 -> {
                           LocalDate obEnd = colYrStart != null
                               ? colYrStart.minusDays(1) : colStartDate.minusDays(1);
                           col = new ColumnDef(idx, sentinel, obEnd,
                                               isLastYear ? "Prior Opening" : "Opening Bal");
                       }
                       case 4 -> col = new ColumnDef(idx, sentinel, colEndDate,
                                                      isLastYear ? "Prior Year" : "Current Year");
                       case 5 -> {
                           if (colYrEnd != null)
                               col = new ColumnDef(idx, colYrStart, colYrEnd,
                                                   isLastYear ? "Prior Full Yr" : "Full Year");
                       }
                       case 6 -> {
                           if (periodNo >= 1 && periodNo <= 13 && colDates != null) {
                               LocalDate pe = (LocalDate) colDates.get(
                                   String.format("period_end_%02d", periodNo));
                               if (pe != null && !SENTINEL.equals(pe)) {
                                   LocalDate ps;
                                   if (periodNo == 1) {
                                       ps = colYrStart;
                                   } else {
                                       LocalDate prev = (LocalDate) colDates.get(
                                           String.format("period_end_%02d", periodNo - 1));
                                       ps = (prev != null && !SENTINEL.equals(prev))
                                           ? prev.plusDays(1) : colYrStart;
                                   }
                                   col = new ColumnDef(idx, ps, pe,
                                       pe.format(java.time.format.DateTimeFormatter.ofPattern("MMM yy")));
                               }
                           }
                       }
                       case 7 -> {
                           // Period prior — approximate as N calendar months before PTD
                           int shift = periodNo > 0 ? periodNo : 1;
                           col = new ColumnDef(idx, colStartDate.minusMonths(shift),
                                               colEndDate.minusMonths(shift),
                                               isLastYear ? "Budget Prior" : "Prior Period");
                       }
                   }
                   if (col != null) columns.add(col);
               });
        } catch (Exception e) {
            log.warn("resolveColumnsFromGlrphof layout={}: {}", layoutNo, e.getMessage());
        }
        return columns;
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

    /** Title-bar caption for the horizontal — glrphoh desc_1 if known, else the key. */
    private String describeHoriz(int companyNo, String key) {
        try {
            String d = dsl.select(GLRPHOH.DESC_1)
                          .from(GLRPHOH)
                          .where(GLRPHOH.COMPANY_NO.eq(companyNo)
                              .and(GLRPHOH.LAYOUT_NO.eq(key)))
                          .fetchOne(GLRPHOH.DESC_1);
            if (notBlank(d)) return trim(d);
        } catch (Exception ignored) {}
        return notBlank(key) ? key : "(no horizontal)";
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

        // Calc columns have null dates — find initial span from the first data column.
        LocalDate spanStart = null, spanEnd = null;
        for (int i = 0; i < colCount; i++) {
            ColumnDef ci = columns.get(i);
            if (ci.isCalcPct()) continue;
            if (spanStart == null) { spanStart = ci.periodStart(); spanEnd = ci.periodEnd(); continue; }
            if (ci.periodStart().isBefore(spanStart)) spanStart = ci.periodStart();
            if (ci.periodEnd().isAfter(spanEnd))      spanEnd   = ci.periodEnd();
        }
        if (spanStart == null) return perAcct; // all columns are calc — nothing to query

        int endMain = r.endMain() > 0 ? r.endMain() : r.startMain();
        int endSub  = r.endSub()  > 0 ? r.endSub()  : 9999;

        // Calc columns have null dates; their cells stay zero (filled by fillCalcColumns).
        final LocalDate[] cols0 = new LocalDate[colCount];
        final LocalDate[] cols1 = new LocalDate[colCount];
        for (int i = 0; i < colCount; i++) {
            ColumnDef ci = columns.get(i);
            cols0[i] = ci.isCalcPct() ? LocalDate.MIN : ci.periodStart();
            cols1[i] = ci.isCalcPct() ? LocalDate.MIN : ci.periodEnd();
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

    /**
     * Fills calc columns (isCalcPct) in {@code cells}: ratio = cells[numeratorIdx] / cells[denominatorIdx] * 100,
     * truncated to integer. For DR-side rows ({@code drRow=true}) the ratio is negated so that
     * expenses show as (72) when Actual YTD is 72% of Prior YTD — matching COBOL convention.
     * Zero denominator → zero (div-guard). Called after all data-column values are known.
     */
    private static void fillCalcColumns(BigDecimal[] cells, int colCount,
                                        List<ColumnDef> columns, boolean drRow) {
        for (int c = 0; c < colCount; c++) {
            ColumnDef col = columns.get(c);
            if (!col.isCalcPct()) continue;
            int n = col.calcNumeratorIdx(), d = col.calcDenominatorIdx();
            if (n < 0 || d < 0 || n >= colCount || d >= colCount
                    || cells[d] == null || cells[d].signum() == 0) {
                cells[c] = BigDecimal.ZERO;
                continue;
            }
            BigDecimal ratio = cells[n].divide(cells[d], 4, java.math.RoundingMode.DOWN)
                                       .multiply(BigDecimal.valueOf(100))
                                       .setScale(0, java.math.RoundingMode.DOWN);
            cells[c] = drRow ? ratio.negate() : ratio;
        }
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

    /**
     * Column heading. An explicit {@link ColumnDef#label} wins (set by the
     * PTD/YTD/Prior YTD synth); otherwise a compact date label fitting the
     * 46-px PDF column — "MMM yy" for ~one-month spans, "FY yy" for full year,
     * "dd-MM-yyyy" end-date for anything else.
     */
    private static String headingOf(ColumnDef c) {
        if (c.isCalcPct()) return c.label() != null ? c.label() : "% Dif";
        if (c.label() != null && !c.label().isBlank()) return c.label();
        LocalDate s = c.periodStart(), e = c.periodEnd();
        long days = java.time.temporal.ChronoUnit.DAYS.between(s, e);
        if (days >= 27 && days <= 32)  return e.format(java.time.format.DateTimeFormatter.ofPattern("MMM yy"));
        if (days >= 360 && days <= 370) return "FY " + String.format("%02d", e.getYear() % 100);
        return e.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"));
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
