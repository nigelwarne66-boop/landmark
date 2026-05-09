package com.example.fixedassets.service;

import com.example.fixedassets.model.*;
import com.example.fixedassets.repository.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Core FATL12 depreciation projection engine.
 *
 * Replaces: fatl12.cbl + fatl12.pl (LOOP-THRU-YEARS, LOOP-THRU-EACH-YEAR,
 *           CREATE-DEPN-FOR-PERIOD, CALC-OPEN-BAL, pooled-asset logic).
 *
 * Produces two in-memory outputs (previously written to Vision work files):
 *   - List<ProjectionResult>  →  one entry per selected asset  (was FATLWK1)
 *   - ProjectionHeader        →  column headers + selection metadata  (was FATLWK2)
 *
 * These are passed directly to DepreciationExportService (FATL13 replacement).
 *
 * ── COBOL section → Java method mapping ──────────────────────────────────
 *
 *   LOOP-THRU-ASSET-FILE          → selectAssets()       (delegated to repository)
 *   LOOP-THRU-YEARS               → projectAsset()
 *   LOOP-THRU-EACH-YEAR           → projectYear()
 *   CREATE-DEPN-FOR-PERIOD        → calculatePeriodDepn()
 *   CALC-OPEN-BAL-FOR-STREAM      → computeOpeningWdv()
 *   CALC-REVAL-FOR-PERIOD         → applyRevaluationAdjustment()
 *   CREATE-DEPN-FOR-MULT-FREQS    → handled inside projectYear() multi-freq loop
 *   POOLED-ASSET logic            → computePooledDepn()
 *   STRAIGHT-LINE / DIM-VAL       → DepreciationCalculator strategy
 */
@Service
public class DepreciationProjectionService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    private final FaAssetRepository       assetRepo;
    private final FaTransactionRepository trxRepo;
    private final GlDateRepository        glDateRepo;
    private final CompanyRepository       companyRepo;

    public DepreciationProjectionService(
            FaAssetRepository       assetRepo,
            FaTransactionRepository trxRepo,
            GlDateRepository        glDateRepo,
            CompanyRepository       companyRepo) {
        this.assetRepo   = assetRepo;
        this.trxRepo     = trxRepo;
        this.glDateRepo  = glDateRepo;
        this.companyRepo = companyRepo;
    }

    // ════════════════════════════════════════════════════════════════════
    // Public entry point
    // ════════════════════════════════════════════════════════════════════

    /**
     * Runs the full FATL12 projection for the given parameters.
     *
     * @param req  all screen parameters from the FATL12S0 parameter screen
     * @return ProjectionOutput containing the header and per-asset result list
     */
    public ProjectionOutput project(ProjectionRequest req) {

        // ── 1. Validate projection date against GL period-end dates ─────
        if (!glDateRepo.isPeriodEndDate(req.getCompanyNo(), req.getProjectedToDate())) {
            throw new IllegalArgumentException(
                "Projected-to date " + req.getProjectedToDate() +
                " does not match any GL period-end date.");
        }

        // ── 2. Load all GL years needed for the projection span ─────────
        List<GlYear> glYears = glDateRepo.findAllByCompany(req.getCompanyNo());
        if (glYears.isEmpty()) {
            throw new IllegalStateException("No GL year records found in gl_year table.");
        }

        // ── 3. Build column header list (was FATLWK2) ───────────────────
        ProjectionHeader header = buildHeader(req, glYears);

        // ── 4. Select assets and project each one ───────────────────────
        List<AssetRow> assets = assetRepo.findByRanges(
                req.getCompanyNo(),
                req.getStartAssetNo(), req.getEndAssetNo(),
                req.getStartLocation(), req.getEndLocation(),
                req.getStartGroup(),    req.getEndGroup(),
                req.getStartSubGroup(), req.getEndSubGroup(),
                req.getStartDept(),     req.getEndDept());

        List<ProjectionResult> results = new ArrayList<>(assets.size());
        for (AssetRow asset : assets) {
            ProjectionResult result = projectAsset(asset, req, header, glYears);
            if (result != null) {
                results.add(result);
            }
        }

        return new ProjectionOutput(header, results);
    }

    // ════════════════════════════════════════════════════════════════════
    // Header / column construction  (FATLWK2)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Builds the ProjectionHeader by iterating GL years and periods up to
     * the requested projection date, recording each period-end date as a column.
     *
     * COBOL equivalent: the inner loop in LOOP-THRU-YEARS that writes
     * FATLWK2-COL-HEAD(n) and FATLWK2-COL-LIT(n).
     */
    private ProjectionHeader buildHeader(ProjectionRequest req, List<GlYear> glYears) {
        ProjectionHeader h = new ProjectionHeader();

        h.setStartAssetNo(req.getStartAssetNo());
        h.setEndAssetNo(req.getEndAssetNo());
        h.setStartLoc(req.getStartLocation());
        h.setEndLoc(req.getEndLocation());
        h.setStartGrp(req.getStartGroup());
        h.setEndGrp(req.getEndGroup());
        h.setStartSubgrp(req.getStartSubGroup());
        h.setEndSubgrp(req.getEndSubGroup());
        h.setStartDept(req.getStartDept());
        h.setEndDept(req.getEndDept());
        h.setDepnThruToDate(req.getProjectedToDate());
        h.setProjectedRate(req.getProjectedRate());
        h.setTaxBookInd(req.getTaxOrBook());

        LocalDate projTo = req.getProjectedToDate();

        // Build columns from all GL periods up to projTo.
        // Per-asset projection start is determined by last_book/tax_depn_date
        // in projectAsset() -- periods before that date get zero for that asset.
        for (GlYear yr : glYears) {
            for (int p = 1; p <= 13; p++) {
                LocalDate pe = yr.getPeriodEnd(p);
                if (pe == null) continue;
                if (pe.isAfter(projTo)) break;
                int dayNum = encodeDateYYMMDD(pe);
                String label = formatColLit(pe);
                h.addColumn(dayNum, label, pe);
            }
        }

        if (h.columnCount() == 0) {
            throw new IllegalStateException(
                "No GL periods found up to projection date " + projTo);
        }

        return h;
    }

    // ════════════════════════════════════════════════════════════════════
    // Per-asset projection  (LOOP-THRU-YEARS)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Projects depreciation for a single asset across all period columns.
     * Returns null if the asset should be skipped (e.g. no depn method set).
     */
    private ProjectionResult projectAsset(
            AssetRow asset, ProjectionRequest req,
            ProjectionHeader header, List<GlYear> glYears) {

        char stream = req.getTaxOrBook();

        // Skip assets with no depreciation method configured
        String method  = asset.depnMethod(stream);
        String calcInd = asset.depnCalcInd(stream);
        if (method == null || method.isBlank() || calcInd == null || calcInd.isBlank()) {
            return null;
        }

        // ── Compute opening WDV ─────────────────────────────────────────
        BigDecimal openingWdv = computeOpeningWdv(asset, stream, glYears, req);

        // ── Set up result record ────────────────────────────────────────
        ProjectionResult result = new ProjectionResult();
        result.setAssetNo(asset.getAssetNo());
        result.setDescription(asset.getDesc1());
        result.setLocCode(asset.getLocCode());
        result.setGrpCode(asset.getGrpCode());
        result.setSubgrpCode(asset.getSubgrpCode());
        result.setDeptCode(asset.getDeptCode());
        result.setAcqnDate(asset.getAcqnDate());
        result.setActualCost(asset.getActualCost());
        result.setWriteDownDate(asset.getWriteDownDate());
        result.setLastDepnDate(asset.lastDepnDate(stream));
        result.setDepnFreq(asset.depnFreq(stream));

        BigDecimal effectiveRate = req.getProjectedRate().compareTo(ZERO) != 0
                ? req.getProjectedRate()
                : asset.depnRate1(stream);
        result.setDepnRate(effectiveRate);
        result.setProjectedRate(req.getProjectedRate());
        result.setOpeningWdv(openingWdv);
        result.initialiseTotals();

        // ── Tax year end month (CPCOYCO-FA-TAX-YR-END-MTH) ─────────────
        // Loaded once per asset; only materially affects stream='T'.
        // For stream='B' effectiveYearBounds() passes GL year dates unchanged.
        int taxYrEndMth = loadTaxYrEndMth(req.getCompanyNo());

        // ── Depreciation calculator strategy ────────────────────────────
        DepreciationCalculator calc = DepreciationCalculator.forCalcInd(calcInd);

        // ── Walk columns and calculate each period ──────────────────────
        // Only project from the period AFTER the asset's last depreciation date.
        // Periods on or before last_book/tax_depn_date get zero (already depreciated).
        LocalDate lastDepnDt = asset.lastDepnDate(stream);

        List<ProjectionHeader.PeriodColumn> columns = header.getColumns();
        BigDecimal runningWdv = openingWdv;

        for (int colIdx = 0; colIdx < columns.size(); colIdx++) {
            ProjectionHeader.PeriodColumn col = columns.get(colIdx);

            // Skip periods already covered by posted depreciation
            if (lastDepnDt != null && !col.getPeriodEndDate().isAfter(lastDepnDt)) {
                continue;
            }
            GlYear yr = findYearForDate(glYears, col.getPeriodEndDate());
            if (yr == null) continue;

            LocalDate periodEnd   = col.getPeriodEndDate();
            // Use period_start_NN from GLDATES directly (confirmed column in actual DB)
            int periodNum = periodOf(yr, periodEnd);
            LocalDate periodStart = yr.getPeriodStart(periodNum) != null
                    ? yr.getPeriodStart(periodNum)
                    : (colIdx == 0 ? yr.getYrStartDate()
                                   : columns.get(colIdx - 1).getPeriodEndDate().plusDays(1));

            // For tax stream, year boundaries follow CPCOYCO-FA-TAX-YR-END-MTH
            // rather than the GL year. This affects the day-fraction denominator
            // in the 'D' (calendar days) and 'W' (work days) calc methods.
            LocalDate[] yBounds = effectiveYearBounds(
                    yr.getYrStartDate(), yr.getYrEndDate(),
                    periodEnd, stream, taxYrEndMth);
            LocalDate effYrStart = yBounds[0];
            LocalDate effYrEnd   = yBounds[1];

            int  periodsInYear    = yr.activePeriodCount();
            long workDaysInPeriod = Math.round(yr.getUnit(periodOf(yr, periodEnd)));
            long workDaysInYear   = sumWorkDays(yr);

            BigDecimal depnBase = "D".equals(method) // D=diminishing value
                    ? runningWdv : effectiveCost(asset, stream);

            // Revaluation adjustment
            depnBase = applyRevaluationAdjustment(depnBase, asset, stream, periodStart, req.getCompanyNo());

            // Pooled asset logic
            BigDecimal periodDepn;
            if (asset.isPooled()) {
                periodDepn = computePooledDepn(asset, stream, req, yr, periodStart, periodEnd,
                                               workDaysInPeriod, workDaysInYear, periodsInYear, calc);
            } else {
                periodDepn = calc.calculate(
                        depnBase, effectiveRate,
                        periodStart, periodEnd,
                        effYrStart, effYrEnd,          // tax-aware year boundaries
                        workDaysInPeriod, workDaysInYear,
                        asset.depnFreq(stream), periodsInYear);
            }

            // Cap at remaining WDV (can't depreciate below zero)
            if (periodDepn.compareTo(runningWdv) > 0) periodDepn = runningWdv;

            result.addDepnForPeriod(colIdx + 1, periodDepn);
            runningWdv = runningWdv.subtract(periodDepn);
        }

        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    // Opening WDV  (CALC-OPEN-BAL-FOR-STREAM)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Computes the written-down value at the start of the projection period.
     *
     * COBOL CALC-BASE logic:
     *   'O' — Reconstruct from transactions: sum all BD/TD + BA/TA depn
     *         amounts on FATRANS up to the projection start, subtract from cost.
     *   else — Use FAASSET accumulated depreciation fields directly.
     */
    private BigDecimal computeOpeningWdv(
            AssetRow asset, char stream,
            List<GlYear> glYears, ProjectionRequest req) {

        String calcBase = asset.depnCalcBase(stream);
        BigDecimal cost  = effectiveCost(asset, stream);

        if ("O".equals(calcBase)) {
            // Sum depreciation transactions up to the projection start date
            // (first period start = start of the GL year containing projection date)
            LocalDate projStart = findProjectionStart(glYears, req.getProjectedToDate());
            List<FaTransactionRow> depnTrxs =
                trxRepo.findDepnTransactions(req.getCompanyNo(), asset.getAssetNo(), stream, projStart);

            BigDecimal accumDepn = depnTrxs.stream()
                    .map(t -> t.getDepnAmt() != null ? t.getDepnAmt() : ZERO)
                    .reduce(ZERO, BigDecimal::add);

            return cost.subtract(accumDepn).max(ZERO);
        } else {
            // Use stored accumulated depreciation from asset master
            BigDecimal accum    = asset.accumDepn(stream);
            BigDecimal accumAdj = stream == 'T'
                    ? asset.getAccumTaxDepnAdj()
                    : asset.getAccumBookDepnAdj();
            BigDecimal netAccum = accum.add(accumAdj != null ? accumAdj : ZERO);
            return cost.subtract(netAccum).max(ZERO);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Revaluation adjustment  (CALC-REVAL-FOR-PERIOD)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Subtracts any post-period revaluation adjustments from the depreciable base.
     *
     * COBOL logic: reads FATRANS RV records dated after periodStart and
     * subtracts FATRANS-REVAL-ADJ-AMT from the cost base for this period.
     */
    private BigDecimal applyRevaluationAdjustment(
            BigDecimal base, AssetRow asset, char stream, LocalDate periodStart, int companyNo) {

        List<FaTransactionRow> revals =
                trxRepo.findRevalTransactionsAfter(companyNo, asset.getAssetNo(), periodStart);

        for (FaTransactionRow rv : revals) {
            if (rv.getRevalAdjAmt() != null) {
                base = base.subtract(rv.getRevalAdjAmt());
            }
        }
        return base.max(ZERO);
    }

    // ════════════════════════════════════════════════════════════════════
    // Pooled asset depreciation  (FATL12 pooled-asset section)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Pooled assets use two rates:
     *   Rate-1 applied to current-year acquisitions (POOL-ACQN-POSTED-FLAG)
     *   Rate-2 applied to the pool opening balance
     *
     * TODO: complete implementation once full pooled-asset transaction flow
     *       is confirmed. Current skeleton returns a zero placeholder.
     */
    private BigDecimal computePooledDepn(
            AssetRow asset, char stream, ProjectionRequest req,
            GlYear yr, LocalDate periodStart, LocalDate periodEnd,
            long workDaysInPeriod, long workDaysInYear, int periodsInYear,
            DepreciationCalculator calc) {

        // TODO: implement pooled-asset Rate-1/Rate-2 split
        // Acquire pool balance and current-year acquisition amounts from FATRANS
        // Apply Rate-1 to current-year acqns, Rate-2 to pool balance
        return ZERO;
    }

    // ════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════

    /** Cost base for the selected depreciation stream. */
    private BigDecimal effectiveCost(AssetRow asset, char stream) {
        BigDecimal cost = asset.depnCost(stream);
        return cost != null ? cost : ZERO;
    }

    /** Finds the GL year record whose period-end dates contain the given date. */
    private GlYear findYearForDate(List<GlYear> glYears, LocalDate date) {
        for (GlYear yr : glYears) {
            if (!date.isBefore(yr.getYrStartDate()) &&
                !date.isAfter(yr.getYrEndDate())) {
                return yr;
            }
        }
        return null;
    }

    /** Returns the 1-based period number within a year for a given period-end date. */
    private int periodOf(GlYear yr, LocalDate periodEnd) {
        for (int p = 1; p <= 13; p++) {
            if (periodEnd.equals(yr.getPeriodEnd(p))) return p;
        }
        return 1;
    }

    /** Sums all work-day units across a GL year's periods. */
    private long sumWorkDays(GlYear yr) {
        double total = 0;
        for (int p = 1; p <= 13; p++) total += yr.getUnit(p);
        return Math.round(total);
    }

    /** Returns the start of the GL year that contains the projection-to date. */
    private LocalDate findProjectionStart(List<GlYear> glYears, LocalDate projTo) {
        for (GlYear yr : glYears) {
            if (!projTo.isBefore(yr.getYrStartDate()) &&
                !projTo.isAfter(yr.getYrEndDate())) {
                return yr.getYrStartDate();
            }
        }
        return projTo.withDayOfYear(1); // fallback
    }

    /** YYMMDD integer encoding matching COBOL PIC 9(6). */
    private int encodeDateYYMMDD(LocalDate d) {
        int yy = d.getYear() % 100;
        return yy * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    /** Formats a date as "DD/MM/YY" matching FATLWK2-COL-LIT PIC X(8). */
    private String formatColLit(LocalDate d) {
        return String.format("%02d/%02d/%02d",
                d.getDayOfMonth(), d.getMonthValue(), d.getYear() % 100);
    }


    // ════════════════════════════════════════════════════════════════════
    // Tax year boundary  (CPCOYCO-FA-TAX-YR-END-MTH)
    // Resolves the tax-stream TODO from the original skeleton.
    // ════════════════════════════════════════════════════════════════════

    /**
     * Returns the start date of the tax year containing the given date.
     *
     * COBOL source: FATL12 reads CPCOYCO-FA-TAX-YR-END-MTH (also stored
     * in GLPASS-FA-TAX-YR-END-MTH) to compute WS-TAX-YR-START-DATE and
     * WS-TAX-YR-END-DATE when WS-TAX-BOOK-IND = 'T'.
     *
     * Example — June year-end (taxYrEndMth=6):
     *   date 2025-04-15  →  tax year started 2024-07-01
     *   date 2025-09-30  →  tax year started 2025-07-01
     *
     * @param date         any date within the desired tax year
     * @param taxYrEndMth  month number 1-12; from CPCOYCO-FA-TAX-YR-END-MTH
     */
    private LocalDate taxYearStart(LocalDate date, int taxYrEndMth) {
        int startMth = (taxYrEndMth % 12) + 1;   // month after year-end = first month of new year
        int year     = date.getMonthValue() >= startMth ? date.getYear() : date.getYear() - 1;
        return LocalDate.of(year, startMth, 1);
    }

    /**
     * Returns the last day of the tax year containing the given date.
     * This is the last day of taxYrEndMth in the appropriate calendar year.
     */
    private LocalDate taxYearEnd(LocalDate date, int taxYrEndMth) {
        LocalDate start = taxYearStart(date, taxYrEndMth);
        return start.plusMonths(12).minusDays(1);
    }

    /**
     * Loads the FA tax year end month for the given company number.
     * Returns 12 (calendar year-end) as a safe default if not found.
     *
     * Called from projectAsset() when stream='T' to determine year boundaries
     * for the calc-ind='D'/'W' day-fraction formulas.
     *
     * @param companyNo  from ProjectionRequest / GLPASS-COMPANY-NO
     */
    private int loadTaxYrEndMth(int companyNo) {
        return companyRepo.findByCompanyNo(companyNo)
                .map(CompanyRow::getFaTaxYrEndMth)
                .orElse(12);
    }

    /**
     * Returns the correct year-start/end pair for a projection period,
     * respecting the tax year boundary when stream = 'T'.
     *
     * For stream='B' this just returns the GL year boundaries from GLDATES.
     * For stream='T' it uses the company's tax year end month to compute the
     * tax year that contains the period, which may straddle two GL years.
     *
     * @param glYrStart    GL year start from GLDATES
     * @param glYrEnd      GL year end from GLDATES
     * @param periodDate   any date within the period column
     * @param stream       'B' or 'T'
     * @param taxYrEndMth  from CPCOYCO-FA-TAX-YR-END-MTH (ignored for 'B')
     * @return array {yearStart, yearEnd}
     */
    LocalDate[] effectiveYearBounds(
            LocalDate glYrStart, LocalDate glYrEnd,
            LocalDate periodDate, char stream, int taxYrEndMth) {
        if (stream == 'T') {
            return new LocalDate[]{
                taxYearStart(periodDate, taxYrEndMth),
                taxYearEnd(periodDate,   taxYrEndMth)
            };
        }
        return new LocalDate[]{ glYrStart, glYrEnd };
    }

    // ════════════════════════════════════════════════════════════════════
    // Output container
    // ════════════════════════════════════════════════════════════════════

    /** Bundles the header and result list returned from project(). */
    public record ProjectionOutput(
            ProjectionHeader        header,
            List<ProjectionResult>  results) {}
}
