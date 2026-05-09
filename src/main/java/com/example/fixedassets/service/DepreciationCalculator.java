package com.example.fixedassets.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Strategy interface for the three FATL12 depreciation calculation methods.
 *
 * COBOL source: fatl12.pl — CALC-DEPN-FOR-PERIOD
 *
 * COBOL calc indicator (FAASSET-BOOK/TAX-DEPN-CALC-IND):
 *   'D' — Calendar days
 *   'W' — Work days
 *   'F' — Fixed frequency (periods per year)
 *
 * All implementations use BigDecimal with HALF_UP rounding to replicate
 * COBOL COMPUTE ROUNDED behaviour. Intermediate precision uses 6 decimal
 * places (matching COBOL S9(12)V9(6) workfields) before rounding to 2dp.
 */
public interface DepreciationCalculator {

    /**
     * Calculates depreciation for one period column.
     *
     * @param cost           depreciable amount (cost or opening WDV)
     * @param rate           annual depreciation rate (e.g. 10.00 for 10%)
     * @param periodStart    start of this period (inclusive)
     * @param periodEnd      end of this period (inclusive)
     * @param yearStart      start of the fiscal year containing this period
     * @param yearEnd        end of the fiscal year containing this period
     * @param workDaysInPeriod work-days in period (used by WorkDaysCalculator only)
     * @param workDaysInYear   work-days in year   (used by WorkDaysCalculator only)
     * @param freq           depreciation frequency — periods per year (FrequencyCalculator only)
     * @param periodsInYear  total periods in the fiscal year (FrequencyCalculator only)
     * @return calculated depreciation amount, rounded to 2 decimal places
     */
    BigDecimal calculate(
            BigDecimal cost,
            BigDecimal rate,
            LocalDate  periodStart,
            LocalDate  periodEnd,
            LocalDate  yearStart,
            LocalDate  yearEnd,
            long       workDaysInPeriod,
            long       workDaysInYear,
            int        freq,
            int        periodsInYear);

    // ────────────────────────────────────────────────────────────────────
    // Common rounding helper
    // ────────────────────────────────────────────────────────────────────

    /** Intermediate precision: 6dp (COBOL V9(6) workfield). */
    MathContext MC_INTERMEDIATE = new MathContext(18, RoundingMode.HALF_UP);
    /** Final result precision: 2dp (COBOL V99 output). */
    int FINAL_SCALE = 2;
    RoundingMode ROUNDING = RoundingMode.HALF_UP;
    BigDecimal HUNDRED = BigDecimal.valueOf(100);

    // ════════════════════════════════════════════════════════════════════
    // Implementation: Calendar Days  (CALC-IND = 'D')
    // ════════════════════════════════════════════════════════════════════

    /**
     * COBOL formula:
     *   COMPUTE WS-DEPN-AMT ROUNDED =
     *     WS-COST * WS-RATE * WS-DEPN-DAYS / 100 / WS-TOTAL-YR-DAYS
     *
     * where:
     *   WS-DEPN-DAYS      = calendar days in the projection period
     *   WS-TOTAL-YR-DAYS  = calendar days in the full fiscal year
     */
    class DaysCalculator implements DepreciationCalculator {

        @Override
        public BigDecimal calculate(
                BigDecimal cost, BigDecimal rate,
                LocalDate periodStart, LocalDate periodEnd,
                LocalDate yearStart,   LocalDate yearEnd,
                long workDaysInPeriod, long workDaysInYear,
                int freq, int periodsInYear) {

            long depnDays    = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
            long totalYrDays = ChronoUnit.DAYS.between(yearStart,  yearEnd)   + 1;

            if (totalYrDays == 0) return BigDecimal.ZERO;

            // cost × rate × depnDays / 100 / totalYrDays  — intermediate at 6dp
            BigDecimal result = cost
                    .multiply(rate, MC_INTERMEDIATE)
                    .multiply(BigDecimal.valueOf(depnDays), MC_INTERMEDIATE)
                    .divide(HUNDRED, 6, ROUNDING)
                    .divide(BigDecimal.valueOf(totalYrDays), 6, ROUNDING);

            return result.setScale(FINAL_SCALE, ROUNDING);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Implementation: Work Days  (CALC-IND = 'W')
    // ════════════════════════════════════════════════════════════════════

    /**
     * COBOL formula:
     *   COMPUTE WS-DEPN-AMT ROUNDED =
     *     WS-COST * WS-RATE * WS-DEPN-WORK-DAYS / 100 / WS-TOTAL-YR-WORK-DAYS
     *
     * Work-day counts come from GLDATES-UNIT(period) for each GL period.
     */
    class WorkDaysCalculator implements DepreciationCalculator {

        @Override
        public BigDecimal calculate(
                BigDecimal cost, BigDecimal rate,
                LocalDate periodStart, LocalDate periodEnd,
                LocalDate yearStart,   LocalDate yearEnd,
                long workDaysInPeriod, long workDaysInYear,
                int freq, int periodsInYear) {

            if (workDaysInYear == 0) return BigDecimal.ZERO;

            BigDecimal result = cost
                    .multiply(rate, MC_INTERMEDIATE)
                    .multiply(BigDecimal.valueOf(workDaysInPeriod), MC_INTERMEDIATE)
                    .divide(HUNDRED, 6, ROUNDING)
                    .divide(BigDecimal.valueOf(workDaysInYear), 6, ROUNDING);

            return result.setScale(FINAL_SCALE, ROUNDING);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Implementation: Fixed Frequency  (CALC-IND = 'F')
    // ════════════════════════════════════════════════════════════════════

    /**
     * COBOL formula:
     *   COMPUTE WS-DEPN-AMT ROUNDED =
     *     WS-COST * WS-RATE * WS-DEPN-FREQ / 100 / WS-NO-OF-PERIODS
     *
     * where:
     *   WS-DEPN-FREQ    = asset's depreciation frequency (e.g. 1=annual, 12=monthly)
     *   WS-NO-OF-PERIODS = total periods in the fiscal year (from GLDATES)
     */
    class FrequencyCalculator implements DepreciationCalculator {

        @Override
        public BigDecimal calculate(
                BigDecimal cost, BigDecimal rate,
                LocalDate periodStart, LocalDate periodEnd,
                LocalDate yearStart,   LocalDate yearEnd,
                long workDaysInPeriod, long workDaysInYear,
                int freq, int periodsInYear) {

            if (periodsInYear == 0) return BigDecimal.ZERO;

            BigDecimal result = cost
                    .multiply(rate, MC_INTERMEDIATE)
                    .multiply(BigDecimal.valueOf(freq), MC_INTERMEDIATE)
                    .divide(HUNDRED, 6, ROUNDING)
                    .divide(BigDecimal.valueOf(periodsInYear), 6, ROUNDING);

            return result.setScale(FINAL_SCALE, ROUNDING);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Factory
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns the correct calculator for a given COBOL calc indicator.
     *
     * @param calcInd 'D', 'W', or 'F'
     * @throws IllegalArgumentException for unknown indicators
     */
    static DepreciationCalculator forCalcInd(String calcInd) {
        if (calcInd == null) throw new IllegalArgumentException("calcInd is null");
        return switch (calcInd.trim().toUpperCase()) {
            case "D" -> new DaysCalculator();
            case "W" -> new WorkDaysCalculator();
            case "F" -> new FrequencyCalculator();
            default  -> throw new IllegalArgumentException(
                            "Unknown depreciation calc indicator: " + calcInd);
        };
    }
}
