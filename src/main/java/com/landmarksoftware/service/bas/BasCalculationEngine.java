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
package com.landmarksoftware.service.bas;

import com.landmarksoftware.db.tables.records.CpbashdRecord;
import com.landmarksoftware.db.tables.records.CpbastxRecord;
import org.jooq.TableField;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.landmarksoftware.db.tables.Cpbashd.CPBASHD;
import static com.landmarksoftware.db.tables.Cpbastx.CPBASTX;

/**
 * CPBA10 — pure calculation logic for a Business Activity Statement.
 *
 * <p>No Spring annotations, no DB access — every method here mutates a
 * {@link CpbashdRecord} (and reads a {@link CpbastxRecord} where relevant)
 * already fetched/attached by {@link BasProcessingService}. Kept separate
 * from the DB-orchestration service so the ATO-label math is easy to read,
 * and easy to unit-test, in isolation.
 *
 * <p>Field access throughout uses the generic {@code record.get(FIELD)} /
 * {@code record.set(FIELD, value)} idiom (rather than jOOQ's generated
 * per-column getter/setter methods) — {@code cpbashd} has ~115 columns and
 * jOOQ's camel-casing of digit-led segments (e.g.
 * {@code bas_1a_gst_payable} → {@code getBas_1aGstPayable()}) is easy to
 * mistype; the {@code TableField} constants imported from {@link
 * com.landmarksoftware.db.tables.Cpbashd Cpbashd} are unambiguous.
 *
 * <p>Three entry points, called by the service at the points described in
 * the CLAUDE.md task brief:
 * <ul>
 *   <li>{@link #classify} — CLASSIFY-GST-TRX: apply (or, with
 *       {@code sign=-1}, reverse) one {@code cpbastx} line's contribution to
 *       the header's accumulator fields. Used both for the bulk pull-in at
 *       BAS creation and for the incremental UPDATE-BAS-TOTALS delta
 *       maintenance on single-line add/edit/delete.</li>
 *   <li>{@link #recalculate} — BAS-CALCULATIONS: the full G-label / BAS-label
 *       roll-up from the accumulator fields. Idempotent — safe to call as
 *       often as needed once the accumulators + manual figures are in their
 *       final state.</li>
 *   <li>{@link #calcIncomeTax} / {@link #calcFbt} — the T1-T4 → 5A and
 *       F1-F4 → 6A derivations, called by the service whenever those fields
 *       change, before {@link #recalculate}.</li>
 * </ul>
 */
public final class BasCalculationEngine {

    private BasCalculationEngine() { }

    private static final BigDecimal ELEVEN = BigDecimal.valueOf(11);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    // ── CLASSIFY-GST-TRX ─────────────────────────────────────────────────

    /**
     * Apply ({@code sign=+1}) or reverse ({@code sign=-1}) one {@code
     * cpbastx} line's contribution to the header's accumulator fields,
     * based on {@code bas_code}. Does NOT call {@link #recalculate} —
     * caller does that once, after all lines for the operation are
     * classified.
     */
    public static void classify(CpbashdRecord h, CpbastxRecord line, int sign) {
        String code = up(line.get(CPBASTX.BAS_CODE));
        boolean ledgerActual = "T".equals(up(h.get(CPBASHD.GST_METHOD)));
        BigDecimal taxAmt   = nz(line.get(CPBASTX.TAX_AMT));
        BigDecimal taxGross = nz(line.get(CPBASTX.TAX_GROSS_AMT));
        BigDecimal trxGross = nz(line.get(CPBASTX.TRX_GROSS_AMT));
        BigDecimal basGross = nz(line.get(CPBASTX.BAS_GROSS_AMT));
        boolean isF = "F".equals(up(line.get(CPBASTX.GST_CODE)));

        switch (code) {
            case "G1" -> {
                if (ledgerActual) {
                    bump(h, CPBASHD.G1_EX_OTHER_SALES, taxAmt.multiply(ELEVEN), sign);
                    bump(h, CPBASHD.G9_TOTAL_ACTUAL_GST, taxAmt, sign);
                } else {
                    bump(h, CPBASHD.G1_EX_OTHER_SALES, taxGross, sign);
                }
            }
            case "G2" -> bump(h, CPBASHD.G2_ACTUAL_TOTAL, taxGross, sign);
            case "G3" -> bump(h, CPBASHD.G3_ACTUAL_TOTAL, taxGross, sign);
            case "G4" -> bump(h, CPBASHD.G4_ACTUAL_TOTAL, taxGross, sign);
            case "G7" -> {
                if (ledgerActual) bump(h, CPBASHD.G7_SALES_ADJUSTMENTS, taxAmt.multiply(ELEVEN), sign);
                else               bump(h, CPBASHD.G7_SALES_ADJUSTMENTS, taxGross, sign);
                // Unlike G1, G7 accrues into G9 under BOTH methods — intentional asymmetry, per spec.
                bump(h, CPBASHD.G9_TOTAL_ACTUAL_GST, taxAmt, sign);
            }
            case "G10" -> {
                if (ledgerActual) {
                    bump(h, CPBASHD.G10_CAPITAL_PURCH, taxAmt.multiply(ELEVEN), sign);
                    bump(h, CPBASHD.G20_TOTAL_ACTUAL_GST, taxAmt, sign);
                } else {
                    bump(h, CPBASHD.G10_CAPITAL_PURCH, taxGross, sign);
                }
            }
            case "G11" -> {
                if (ledgerActual) {
                    bump(h, CPBASHD.G11_EX_OTHER_PURCH, taxAmt.multiply(ELEVEN), sign);
                    bump(h, CPBASHD.G20_TOTAL_ACTUAL_GST, taxAmt, sign);
                } else {
                    bump(h, CPBASHD.G11_EX_OTHER_PURCH, taxGross, sign);
                }
            }
            case "G13" -> classifyGxx(h, CPBASHD.G13_ACTUAL_TOTAL, sign, taxGross, isF, ledgerActual);
            case "G14" -> classifyGxx(h, CPBASHD.G14_ACTUAL_TOTAL, sign, taxGross, isF, ledgerActual);
            case "G15" -> classifyGxx(h, CPBASHD.G15_ACTUAL_TOTAL, sign, taxGross, isF, ledgerActual);
            case "G18" -> {
                if (ledgerActual) bump(h, CPBASHD.G18_PURCH_ADJUSTMENTS, taxAmt.multiply(ELEVEN), sign);
                else               bump(h, CPBASHD.G18_PURCH_ADJUSTMENTS, taxGross, sign);
                // Same asymmetry as G7 — accrues into G20 under BOTH methods.
                bump(h, CPBASHD.G20_TOTAL_ACTUAL_GST, taxAmt, sign);
            }
            case "1C" -> bump(h, CPBASHD.BAS_1C_WINE_EQUAL_PAYABLE, taxAmt, sign);
            case "1D" -> bump(h, CPBASHD.BAS_1D_WINE_EQUAL_CREDITS, taxAmt, sign);
            case "1E" -> bump(h, CPBASHD.BAS_1E_LUXURY_CAR_PAYABLE, taxAmt, sign);
            case "1F" -> bump(h, CPBASHD.BAS_1F_LUXURY_CAR_CREDITS, taxAmt, sign);
            case "1G" -> bump(h, CPBASHD.BAS_1G_SALES_TAX_CREDITS, taxAmt, sign);
            case "7"  -> bump(h, CPBASHD.BAS_7_DEFERRED_TAX, taxAmt, sign);
            case "W1" -> {
                BigDecimal wages = basGross.signum() != 0 ? basGross : trxGross;
                bump(h, CPBASHD.W1_TOTAL_WAGES, wages, sign);
                bump(h, CPBASHD.W2_WAGES_WITHHELD, taxAmt, sign);
            }
            case "W3" -> bump(h, CPBASHD.W3_INVESTMENT_WITHHELD, taxAmt, sign);
            case "W4" -> bump(h, CPBASHD.W4_PAYMENTS_WITHHELD, taxAmt, sign);
            case "5B" -> bump(h, CPBASHD.BAS_5B_INCOME_TAX_CREDITS, taxAmt, sign);
            case "6B" -> bump(h, CPBASHD.BAS_6B_FBT_CREDITS, taxAmt, sign);
            default -> {
                // Not a header-accumulated code — e.g. the synthetic "F"/"7A"/"7C"/"7D"
                // rows, which map 1:1 onto a manually-entered header field instead
                // (see BasProcessingService.updateManualFigure).
            }
        }
    }

    /** Shared G13/G14/G15 pattern — identical shape, different target accumulator. */
    private static void classifyGxx(CpbashdRecord h, TableField<CpbashdRecord, BigDecimal> target,
                                     int sign, BigDecimal taxGross, boolean isF, boolean ledgerActual) {
        bump(h, target, taxGross, sign);
        if (ledgerActual && isF) {
            bump(h, CPBASHD.G20_TOTAL_ACTUAL_GST, taxGross, sign);
            bump(h, CPBASHD.G11_F_PURCH, taxGross, sign);
        } else if (isF) {
            bump(h, CPBASHD.G11_F_PURCH, taxGross, sign);
        }
    }

    private static void bump(CpbashdRecord h, TableField<CpbashdRecord, BigDecimal> field,
                              BigDecimal delta, int sign) {
        BigDecimal d = sign >= 0 ? delta : delta.negate();
        h.set(field, nz(h.get(field)).add(d));
    }

    // ── BAS-CALCULATIONS ─────────────────────────────────────────────────

    /**
     * Full roll-up of every derived/summary field from the current
     * accumulator + manual-figure field values. Idempotent — call any
     * number of times once the header's accumulators are in their final
     * state for this operation.
     */
    public static void recalculate(CpbashdRecord h) {
        boolean ledgerActual = "T".equals(up(h.get(CPBASHD.GST_METHOD)));

        // ── Sales (G1-G9, 1A) ──────────────────────────────────────────
        BigDecimal g2 = maxZero(h.get(CPBASHD.G2_ACTUAL_TOTAL));
        BigDecimal g3 = maxZero(h.get(CPBASHD.G3_ACTUAL_TOTAL));
        BigDecimal g4 = maxZero(h.get(CPBASHD.G4_ACTUAL_TOTAL));
        BigDecimal g1Total = nz(h.get(CPBASHD.G1_EX_OTHER_SALES)).add(g2).add(g3).add(g4);
        h.set(CPBASHD.G1_TOTAL_SALES, g1Total);
        h.set(CPBASHD.G2_EXPORT_SALES, g2);
        h.set(CPBASHD.G3_TAX_FREE_SUPPLIES, g3);
        h.set(CPBASHD.G4_INPUT_TAXED_SALES, g4);

        long g1Rpt = rpt(g1Total);
        long g2Rpt = rpt(g2);
        long g3Rpt = rpt(g3);
        long g4Rpt = rpt(g4);
        long g7Rpt = rpt(h.get(CPBASHD.G7_SALES_ADJUSTMENTS));
        h.set(CPBASHD.G1_TOTAL_SALES_RPT, g1Rpt);
        h.set(CPBASHD.G2_EXPORT_SALES_RPT, g2Rpt);
        h.set(CPBASHD.G3_TAX_FREE_RPT, g3Rpt);
        h.set(CPBASHD.G4_INPUT_TAXED_RPT, g4Rpt);
        h.set(CPBASHD.G7_SALES_ADJ_RPT, g7Rpt);

        BigDecimal g5 = bd(g2Rpt).add(bd(g3Rpt)).add(bd(g4Rpt));
        h.set(CPBASHD.G5_TOTAL_NON_TAX_SALES, g5);
        BigDecimal g6 = bd(g1Rpt).subtract(g5);
        h.set(CPBASHD.G6_TOTAL_TAXABLE_SALES, g6);
        BigDecimal g8 = g6.add(bd(g7Rpt));
        h.set(CPBASHD.G8_TOTAL_GST_SALES, g8);
        BigDecimal g9 = ledgerActual
            ? nz(h.get(CPBASHD.G9_TOTAL_ACTUAL_GST))
            : g8.divide(ELEVEN, 2, RoundingMode.HALF_UP);
        h.set(CPBASHD.G9_GST_ON_SALES, g9);
        h.set(CPBASHD.BAS_1A_GST_PAYABLE, g9);
        long bas1aRpt = rpt(g9);
        h.set(CPBASHD.BAS_1A_GST_PAYABLE_RPT, bas1aRpt);

        // ── Purchases (G10-G20, 1B) ────────────────────────────────────
        BigDecimal g13 = maxZero(h.get(CPBASHD.G13_ACTUAL_TOTAL));
        BigDecimal g14 = maxZero(h.get(CPBASHD.G14_ACTUAL_TOTAL));
        BigDecimal g15 = maxZero(h.get(CPBASHD.G15_ACTUAL_TOTAL));
        h.set(CPBASHD.G13_INPUT_TAXED_PURCH, g13);
        h.set(CPBASHD.G14_TAX_FREE_PURCH, g14);
        h.set(CPBASHD.G15_PRIVATE_USE_VALUE, g15);

        BigDecimal g11NonCap = nz(h.get(CPBASHD.G11_EX_OTHER_PURCH)).add(g13).add(g14).add(g15)
            .subtract(nz(h.get(CPBASHD.G11_F_PURCH)));
        h.set(CPBASHD.G11_NON_CAPITAL_PURCH, g11NonCap);

        long g10Rpt = rpt(h.get(CPBASHD.G10_CAPITAL_PURCH));
        long g11Rpt = rpt(g11NonCap);
        long g13Rpt = rpt(g13);
        long g14Rpt = rpt(g14);
        long g15Rpt = rpt(g15);
        long g18Rpt = rpt(h.get(CPBASHD.G18_PURCH_ADJUSTMENTS));
        h.set(CPBASHD.G10_CAP_PURCH_RPT, g10Rpt);
        h.set(CPBASHD.G11_NON_CAP_PURCH_RPT, g11Rpt);
        h.set(CPBASHD.G13_INP_TAX_PURCH_RPT, g13Rpt);
        h.set(CPBASHD.G14_TAX_FRE_PURCH_RPT, g14Rpt);
        h.set(CPBASHD.G15_PRIVATE_USE_RPT, g15Rpt);
        h.set(CPBASHD.G18_PURCH_ADJ_RPT, g18Rpt);

        BigDecimal g12 = bd(g10Rpt).add(bd(g11Rpt));
        h.set(CPBASHD.G12_TOTAL_PURCH, g12);
        BigDecimal g16 = bd(g13Rpt).add(bd(g14Rpt)).add(bd(g15Rpt));
        h.set(CPBASHD.G16_TOTAL_NONTAX_PURCH, g16);
        BigDecimal g17 = g12.subtract(g16);
        h.set(CPBASHD.G17_TOTAL_TAXED_PURCH, g17);
        BigDecimal g19 = g17.add(bd(g18Rpt));
        h.set(CPBASHD.G19_TOTAL_GST_PURCH, g19);
        BigDecimal g20 = ledgerActual
            ? nz(h.get(CPBASHD.G20_TOTAL_ACTUAL_GST))
            : g19.divide(ELEVEN, 2, RoundingMode.HALF_UP);
        h.set(CPBASHD.G20_GST_ON_PURCH, g20);
        h.set(CPBASHD.BAS_1B_GST_CREDITS, g20);
        long bas1bRpt = rpt(g20);
        h.set(CPBASHD.BAS_1B_GST_CREDITS_RPT, bas1bRpt);

        // ── 1C-1G mirrors, 2A/2B/3 ──────────────────────────────────────
        long r1c = rpt(h.get(CPBASHD.BAS_1C_WINE_EQUAL_PAYABLE));
        long r1d = rpt(h.get(CPBASHD.BAS_1D_WINE_EQUAL_CREDITS));
        long r1e = rpt(h.get(CPBASHD.BAS_1E_LUXURY_CAR_PAYABLE));
        long r1f = rpt(h.get(CPBASHD.BAS_1F_LUXURY_CAR_CREDITS));
        long r1g = rpt(h.get(CPBASHD.BAS_1G_SALES_TAX_CREDITS));
        h.set(CPBASHD.BAS_1C_WINE_EQUAL_PAY_RPT, r1c);
        h.set(CPBASHD.BAS_1D_WINE_EQUAL_CRE_RPT, r1d);
        h.set(CPBASHD.BAS_1E_LUXURY_CAR_PAY_RPT, r1e);
        h.set(CPBASHD.BAS_1F_LUXURY_CAR_CRE_RPT, r1f);
        h.set(CPBASHD.BAS_1G_SALES_TAX_CRE_RPT, r1g);

        BigDecimal bas2a = bd(bas1aRpt).add(bd(r1c)).add(bd(r1e));
        h.set(CPBASHD.BAS_2A_GST_PAYABLE, bas2a);
        BigDecimal bas2b = bd(bas1bRpt).add(bd(r1d)).add(bd(r1f)).add(bd(r1g));
        h.set(CPBASHD.BAS_2B_GST_CREDITS, bas2b);
        h.set(CPBASHD.BAS_3_NET_GST_AMT, bas2a.subtract(bas2b));

        // ── Withholding (4) ─────────────────────────────────────────────
        long w2Rpt = rpt(h.get(CPBASHD.W2_WAGES_WITHHELD));
        long w3Rpt = rpt(h.get(CPBASHD.W3_INVESTMENT_WITHHELD));
        long w4Rpt = rpt(h.get(CPBASHD.W4_PAYMENTS_WITHHELD));
        h.set(CPBASHD.W2_WAGES_WITHHELD_RPT, w2Rpt);
        h.set(CPBASHD.W3_INVEST_WITHHELD_RPT, w3Rpt);
        h.set(CPBASHD.W4_PAYMTS_WITHHELD_RPT, w4Rpt);
        BigDecimal bas4 = bd(w2Rpt).add(bd(w3Rpt)).add(bd(w4Rpt));
        h.set(CPBASHD.BAS_4_WITHHOLD_TAX, bas4);
        long bas4Rpt = rpt(bas4);
        h.set(CPBASHD.BAS_4_WITHHOLD_TAX_RPT, bas4Rpt);

        // ── Income tax instalment (5A/5B) — mirrors only; the payable
        //    figure itself is set by calcIncomeTax(), the credit by classify() ──
        long bas5aRpt = rpt(h.get(CPBASHD.BAS_5A_INCOME_TAX_PAYABLE));
        h.set(CPBASHD.BAS_5A_INCOME_TAX_PAY_RPT, bas5aRpt);
        long bas5bRpt = rpt(h.get(CPBASHD.BAS_5B_INCOME_TAX_CREDITS));
        h.set(CPBASHD.BAS_5B_INCOME_TAX_CRE_RPT, bas5bRpt);

        // ── FBT instalment (6A/6B) — mirrors only ───────────────────────
        long bas6aRpt = rpt(h.get(CPBASHD.BAS_6A_FBT_PAYABLE));
        h.set(CPBASHD.BAS_6A_FBT_PAYABLE_RPT, bas6aRpt);
        long bas6bRpt = rpt(h.get(CPBASHD.BAS_6B_FBT_CREDITS));
        h.set(CPBASHD.BAS_6B_FBT_CREDITS_RPT, bas6bRpt);

        // ── 7 / 7A / 7C / 7D mirrors ─────────────────────────────────────
        long bas7Rpt  = rpt(h.get(CPBASHD.BAS_7_DEFERRED_TAX));
        long bas7aRpt = rpt(h.get(CPBASHD.BAS_7A_DEFERRED_IMPORT_TAX));
        long bas7cRpt = rpt(h.get(CPBASHD.BAS_7C_FUEL_TAX_CRED_CLAIM));
        long bas7dRpt = rpt(h.get(CPBASHD.BAS_7D_FUEL_TAX_CREDIT));
        h.set(CPBASHD.BAS_7_DEFERRED_TAX_RPT, bas7Rpt);
        h.set(CPBASHD.BAS_7A_DEFER_IMP_TAX_RPT, bas7aRpt);
        h.set(CPBASHD.BAS_7C_FUEL_CREDIT_CLM_RPT, bas7cRpt);
        h.set(CPBASHD.BAS_7D_FUEL_CREDIT_RPT, bas7dRpt);

        // ── Summary (8A/8B/9) ────────────────────────────────────────────
        BigDecimal bas8a = bas2a
            .add(bd(bas4Rpt))
            .add(bd(bas5aRpt))
            .add(bd(bas6aRpt))
            .add(bd(bas7Rpt))
            .add(bd(bas7aRpt))
            .add(bd(bas7cRpt));
        h.set(CPBASHD.BAS_8A_TAX_PAYABLE, bas8a);
        BigDecimal bas8b = bas2b
            .add(bd(bas5bRpt))
            .add(bd(bas6bRpt))
            .add(bd(bas7dRpt));
        h.set(CPBASHD.BAS_8B_TAX_CREDITS, bas8b);
        h.set(CPBASHD.BAS_9_NET_TAX_AMT, bas8a.subtract(bas8b));
    }

    // ── CALC-INCOME-TAX (T1-T4 → 5A) ────────────────────────────────────

    /** Derives {@code bas_5a_income_tax_payable} from T1/T2/T3/T4. Call before {@link #recalculate}. */
    public static void calcIncomeTax(CpbashdRecord h) {
        BigDecimal t1 = nz(h.get(CPBASHD.T1_TAXABLE_INCOME));
        String t4 = trim(h.get(CPBASHD.T4_REASON_CODE));
        BigDecimal rate = t4.isEmpty() ? nz(h.get(CPBASHD.T2_TAX_RATE)) : nz(h.get(CPBASHD.T3_VARIED_TAX_RATE));
        BigDecimal amt = t1.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        h.set(CPBASHD.BAS_5A_INCOME_TAX_PAYABLE, amt);
    }

    // ── FBT (F1-F4 → 6A) — straight copy, NOT a rate calculation ────────

    /** Derives {@code bas_6a_fbt_payable} from F1/F3/F4. Call before {@link #recalculate}. */
    public static void calcFbt(CpbashdRecord h) {
        String f4 = trim(h.get(CPBASHD.F4_REASON_CODE));
        BigDecimal amt = f4.isEmpty() ? nz(h.get(CPBASHD.F1_FBT_AMT)) : nz(h.get(CPBASHD.F3_VARIED_FBT_AMT));
        h.set(CPBASHD.BAS_6A_FBT_PAYABLE, amt);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static BigDecimal maxZero(BigDecimal v) { BigDecimal n = nz(v); return n.signum() > 0 ? n : BigDecimal.ZERO; }
    private static long rpt(BigDecimal v) { return nz(v).setScale(0, RoundingMode.HALF_UP).longValue(); }
    private static BigDecimal bd(long v) { return BigDecimal.valueOf(v); }
    private static String up(String s) { return s == null ? "" : s.trim().toUpperCase(); }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
