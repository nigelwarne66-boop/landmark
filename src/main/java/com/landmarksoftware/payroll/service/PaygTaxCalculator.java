/*
 * Copyright (c) 2026 Landmark Software Pty Ltd.
 * All rights reserved.
 *
 * This software is proprietary and confidential.
 * Unauthorised copying, modification, distribution or use
 * of this software, via any medium, is strictly prohibited.
 * Decompilation and reverse engineering are expressly forbidden.
 *
 * Licenced under the terms of the Landmark Software Licence Agreement.
 */
package com.landmarksoftware.payroll.service;

import com.landmarksoftware.payroll.model.TaxBracket;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * ATO PAYG / STSL withholding calculation.
 *
 * <p>Single-lookup formula — no PAYG + STSL add-on. The choice of source file
 * selects whether STSL is included in the coefficients:
 * <pre>
 *   employee with no STSL debt → NAT_1004 (PAYG only)
 *   employee with STSL debt     → NAT_3539 (PAYG + STSL combined)
 *   tax = max(0, ROUND_HALF_UP(a · x − b, 0))
 * </pre>
 * Verified against the ATO Tax Calculator — scale 2, $1,500 weekly, STSL on
 * → $336 ({@code 0.47 × 1500 − 369.85 = 335.15 → 336}).
 *
 * <p>For fortnightly and monthly pay frequencies the ATO method converts to a
 * weekly equivalent before bracket lookup, then scales the result back up.
 * Monthly uses the {@code 3/13} ↔ {@code 13/3} conversion (treats a month as
 * 4&nbsp;1/3 weeks).
 *
 * <p>Special scales:
 * <ul>
 *   <li>{@code "H"} — legacy HECS marker; no brackets are loaded for it.
 *       Calling this for scale H returns zero with no error.</li>
 *   <li>Empty / null scale — throws.</li>
 * </ul>
 */
@Service
public class PaygTaxCalculator {

    private static final String NAT_1004 = "NAT_1004";
    private static final String NAT_3539 = "NAT_3539";

    private static final BigDecimal MONTHLY_TO_WEEKLY = new BigDecimal("3")
        .divide(new BigDecimal("13"), 10, RoundingMode.HALF_UP);
    private static final BigDecimal WEEKLY_TO_MONTHLY = new BigDecimal("13")
        .divide(new BigDecimal("3"), 10, RoundingMode.HALF_UP);

    private final TaxBracketService taxBrackets;

    public PaygTaxCalculator(TaxBracketService taxBrackets) {
        this.taxBrackets = taxBrackets;
    }

    /**
     * Calculate weekly PAYG withholding.
     *
     * @param companyNo       active company.
     * @param scaleNo         pastaff.tax_scale_no — "1".."6" (or "H" for legacy).
     * @param hasStsl         {@code true} if the employee carries an STSL debt
     *                        (HELP / SFSS / SSL / TSL etc.). Selects NAT_3539.
     * @param weeklyEarnings  ordinary gross for the week. Negative or zero → 0.
     * @param asOf            payrun date — picks the ATO publication in force.
     * @return weekly withholding, rounded to whole dollars, never negative.
     * @throws IllegalStateException if no ATO publication is loaded for the
     *                               selected source file ≤ {@code asOf}, or no
     *                               bracket matches.
     */
    public BigDecimal calculateWeeklyTax(int companyNo,
                                          String scaleNo,
                                          boolean hasStsl,
                                          BigDecimal weeklyEarnings,
                                          LocalDate asOf) {
        if (scaleNo == null || scaleNo.isBlank()) {
            throw new IllegalArgumentException("tax scale is required");
        }
        if ("H".equalsIgnoreCase(scaleNo)) {
            return BigDecimal.ZERO;
        }
        if (weeklyEarnings == null || weeklyEarnings.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        String sourceFile = hasStsl ? NAT_3539 : NAT_1004;
        LocalDate effFrom = taxBrackets.findEffectiveFromOnOrBefore(
            companyNo, sourceFile, asOf);
        if (effFrom == null) {
            throw new IllegalStateException(
                "No " + sourceFile + " publication loaded on or before "
                    + asOf + " — run PATX01 to load ATO tax tables.");
        }

        TaxBracket b = taxBrackets.findApplicableBracket(
            companyNo, sourceFile, effFrom, scaleNo, weeklyEarnings);
        if (b == null) {
            throw new IllegalStateException(
                "No bracket matched scale " + scaleNo + " for $" + weeklyEarnings
                    + " in " + sourceFile + " effective " + effFrom);
        }

        BigDecimal tax = b.coeffA.multiply(weeklyEarnings).subtract(b.coeffB)
            .setScale(0, RoundingMode.HALF_UP);
        return tax.signum() < 0 ? BigDecimal.ZERO : tax;
    }

    /**
     * Fortnightly withholding — ATO method: round F/2 to nearest dollar, look
     * up weekly, multiply by 2.
     */
    public BigDecimal calculateFortnightlyTax(int companyNo, String scaleNo,
                                               boolean hasStsl,
                                               BigDecimal fortnightlyEarnings,
                                               LocalDate asOf) {
        if (fortnightlyEarnings == null || fortnightlyEarnings.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal weekly = fortnightlyEarnings
            .divide(new BigDecimal("2"), 0, RoundingMode.HALF_UP);
        return calculateWeeklyTax(companyNo, scaleNo, hasStsl, weekly, asOf)
            .multiply(new BigDecimal("2"));
    }

    /**
     * Monthly withholding — ATO method: weekly equivalent = M × 3/13, round to
     * nearest dollar, look up weekly tax, multiply by 13/3, round to whole
     * dollars.
     */
    public BigDecimal calculateMonthlyTax(int companyNo, String scaleNo,
                                           boolean hasStsl,
                                           BigDecimal monthlyEarnings,
                                           LocalDate asOf) {
        if (monthlyEarnings == null || monthlyEarnings.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal weekly = monthlyEarnings.multiply(MONTHLY_TO_WEEKLY)
            .setScale(0, RoundingMode.HALF_UP);
        BigDecimal weeklyTax = calculateWeeklyTax(companyNo, scaleNo, hasStsl, weekly, asOf);
        return weeklyTax.multiply(WEEKLY_TO_MONTHLY)
            .setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Dispatch by pay frequency code. {@code 'W'} weekly, {@code 'F'}
     * fortnightly, {@code 'M'} monthly. Any other value → weekly.
     */
    public BigDecimal calculate(int companyNo, String scaleNo, boolean hasStsl,
                                 BigDecimal grossForPeriod, String payFreq,
                                 LocalDate asOf) {
        String f = payFreq == null ? "W" : payFreq.toUpperCase();
        return switch (f) {
            case "F" -> calculateFortnightlyTax(companyNo, scaleNo, hasStsl, grossForPeriod, asOf);
            case "M" -> calculateMonthlyTax(companyNo, scaleNo, hasStsl, grossForPeriod, asOf);
            default  -> calculateWeeklyTax(companyNo, scaleNo, hasStsl, grossForPeriod, asOf);
        };
    }
}
