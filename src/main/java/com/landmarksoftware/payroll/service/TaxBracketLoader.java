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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses ATO tax-scale Excel workbooks into {@link TaxBracket} rows.
 *
 * <p>Landmark uses the ATO's <em>combined</em> withholding formula — one
 * bracket lookup per employee, no PAYG-plus-STSL add-on:
 *
 * <pre>
 *   if (employee has STSL debt):
 *       weekly_tax = a₃₅₃₉ · x − b₃₅₃₉    (NAT_3539 — PAYG and STSL combined)
 *   else:
 *       weekly_tax = a₁₀₀₄ · x − b₁₀₀₄    (NAT_1004 — PAYG only)
 * </pre>
 *
 * <p>This matches the ATO Tax Calculator output exactly (e.g. scale 2 with
 * STSL at $1,500 weekly → {@code 0.47 × 1500 − 369.85 = $335.15} → $336 as
 * the ATO calc reports). The component-only formula on NAT_3539 sheet 5
 * <em>doesn't</em> reconcile, so we don't use it.
 *
 * <p>Both source files publish brackets the <em>same way</em> on sheet
 * index 1 ("Statement of Formula - CSV"), four columns:
 * {@code scale_no | upper_earnings | a | b}. The loader is a single parser
 * dispatched only to validate the {@code source_file} label.
 */
@Component
public class TaxBracketLoader {

    /**
     * Sheet index for the "Statement of Formula - CSV" tab — same on both
     * NAT_1004 and NAT_3539 workbooks.
     */
    private static final int CSV_SHEET = 1;

    /** {@code upper_earnings} value used for the terminator "X & over" row. */
    private static final BigDecimal NO_CAP = new BigDecimal("999999");

    public List<TaxBracket> parse(Path xlsx,
                                   int companyNo,
                                   String sourceFile,
                                   LocalDate effectiveFrom) throws IOException {
        if (!"NAT_1004".equals(sourceFile) && !"NAT_3539".equals(sourceFile)) {
            throw new IOException(
                "Unknown source_file: " + sourceFile + " (expected NAT_1004 or NAT_3539)");
        }
        try (InputStream in = new FileInputStream(xlsx.toFile());
             Workbook wb = new XSSFWorkbook(in)) {
            return parseCsvSheet(wb, companyNo, sourceFile, effectiveFrom);
        }
    }

    /**
     * Parse the 4-column "Statement of Formula - CSV" sheet — identical
     * layout for NAT_1004 (PAYG only) and NAT_3539 (PAYG + STSL combined).
     */
    private List<TaxBracket> parseCsvSheet(Workbook wb,
                                            int companyNo,
                                            String sourceFile,
                                            LocalDate effectiveFrom) throws IOException {
        if (wb.getNumberOfSheets() <= CSV_SHEET) {
            throw new IOException(sourceFile + " workbook has only "
                + wb.getNumberOfSheets() + " sheets — expected "
                + "'Statement of Formula - CSV' at index " + CSV_SHEET);
        }
        Sheet sheet = wb.getSheetAt(CSV_SHEET);

        List<TaxBracket> out = new ArrayList<>();
        String prevScale = null;
        int bracketNo = 0;

        for (Row row : sheet) {
            String scaleNo = readString(row.getCell(0));
            if (scaleNo == null || scaleNo.isBlank()) continue;
            BigDecimal earnings = readDecimal(row.getCell(1));
            BigDecimal coeffA   = readDecimal(row.getCell(2));
            BigDecimal coeffB   = readDecimal(row.getCell(3));
            if (earnings == null || coeffA == null || coeffB == null) continue;

            if (!scaleNo.equals(prevScale)) { bracketNo = 0; prevScale = scaleNo; }
            bracketNo++;
            out.add(makeBracket(companyNo, sourceFile, effectiveFrom,
                                scaleNo, bracketNo, earnings, coeffA, coeffB));
        }
        return out;
    }

    // ─── Shared helpers ─────────────────────────────────────────────────────

    private static TaxBracket makeBracket(int companyNo, String sourceFile,
                                           LocalDate effectiveFrom,
                                           String scaleNo, int bracketNo,
                                           BigDecimal earnings,
                                           BigDecimal coeffA, BigDecimal coeffB) {
        TaxBracket b = new TaxBracket();
        b.companyNo     = companyNo;
        b.sourceFile    = sourceFile;
        b.effectiveFrom = effectiveFrom;
        b.scaleNo       = scaleNo;
        b.bracketNo     = bracketNo;
        b.upperEarnings = earnings.setScale(2, RoundingMode.HALF_UP);
        b.coeffA        = coeffA.setScale(6, RoundingMode.HALF_UP);
        b.coeffB        = coeffB.setScale(4, RoundingMode.HALF_UP);
        return b;
    }

    private static String readString(Cell c) {
        if (c == null) return null;
        return switch (c.getCellType()) {
            case STRING  -> c.getStringCellValue().trim();
            case NUMERIC -> stripTrailingZero(c.getNumericCellValue());
            case BLANK   -> null;
            case FORMULA -> readFormulaString(c);
            default      -> null;
        };
    }

    private static String readFormulaString(Cell c) {
        try {
            if (c.getCachedFormulaResultType() == CellType.STRING) {
                return c.getStringCellValue().trim();
            }
            return stripTrailingZero(c.getNumericCellValue());
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    private static String stripTrailingZero(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private static BigDecimal readDecimal(Cell c) {
        if (c == null) return null;
        return switch (c.getCellType()) {
            case NUMERIC -> BigDecimal.valueOf(c.getNumericCellValue());
            case STRING  -> parseOrNull(c.getStringCellValue());
            case FORMULA -> {
                try {
                    yield BigDecimal.valueOf(c.getNumericCellValue());
                } catch (IllegalStateException ex) {
                    yield parseOrNull(c.getStringCellValue());
                }
            }
            default -> null;
        };
    }

    private static BigDecimal parseOrNull(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); }
        catch (NumberFormatException ex) { return null; }
    }
}
