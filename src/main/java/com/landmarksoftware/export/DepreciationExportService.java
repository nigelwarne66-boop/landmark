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
package com.landmarksoftware.export;

import com.landmarksoftware.model.ProjectionHeader;
import com.landmarksoftware.model.ProjectionResult;
import com.landmarksoftware.service.DepreciationProjectionService.ProjectionOutput;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * FATL13 replacement — exports the projection result to an .xlsx file using Apache POI.
 *
 * COBOL source: fatl13.pl — EXPORT-DEPRECIATION-REPORT
 *
 * Replicates the exact Excel layout produced by the COBOL ActiveX automation:
 *
 *   Row 1      : blank
 *   Row 2      : "FA Depreciation Projection Worksheet" (title)
 *   Row 3      : blank
 *   Row 4      : "Assets in the range X to Y"
 *   Row 5      : "Locations in the range X to Y"
 *   Row 6      : "Groups in the range X to Y"
 *   Row 7      : "Subgroups in the range X to Y"
 *   Row 8      : "Departments in the range X to Y"
 *   Row 9      : "Book or Tax? B/T"
 *   Row 10     : "Projected depreciation date DD/MM/YY"
 *   Row 11     : "Projected depreciation rate ZZ.ZZ"
 *   Row 12     : blank
 *   Row 13     : Column headings line 1
 *   Row 14     : Column headings line 2
 *   Row 15+    : One row per asset
 *   Last row   : Grand totals
 *
 * Fixed columns (1–14):
 *   1  Asset Number          (text)
 *   2  Description           (text)
 *   3  Location              (text)
 *   4  Group                 (text)
 *   5  Sub Group             (text)
 *   6  Department            (text)
 *   7  Acqn Date             (date dd/mm/yy)
 *   8  Actual Cost           (#,##0)
 *   9  Write Down Date       (date dd/mm/yyyy)
 *   10 Opening WDV           (#,##0)
 *   11 Last Depreciation Date(date dd/mm/yy)
 *   12 Depreciation Frequency(text)
 *   13 Depreciation Rate     (number)
 *   14 Projected Depn Rate   (number)
 *
 * Dynamic columns (15 .. 14+N):
 *   One column per GL period from ProjectionHeader.getColumns()
 *   Format: #,##0.00;[Red]-#,##0.00
 *
 * Final two columns:
 *   Total Depreciation       (#,##0.00;[Red]-#,##0.00)
 *   Projected WDV            (#,##0.00;[Red]-#,##0.00)
 */
@Service
public class DepreciationExportService {

    private static final DateTimeFormatter FMT_DDMMYY   = DateTimeFormatter.ofPattern("dd/MM/yy");
    private static final DateTimeFormatter FMT_DDMMYYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Column index constants (0-based)
    private static final int COL_ASSET_NO    = 0;
    private static final int COL_DESC        = 1;
    private static final int COL_LOC         = 2;
    private static final int COL_GRP         = 3;
    private static final int COL_SUBGRP      = 4;
    private static final int COL_DEPT        = 5;
    private static final int COL_ACQN_DATE   = 6;
    private static final int COL_ACTUAL_COST = 7;
    private static final int COL_WD_DATE     = 8;
    private static final int COL_OPEN_WDV    = 9;
    private static final int COL_LAST_DEP_DT = 10;
    private static final int COL_FREQ        = 11;
    private static final int COL_RATE        = 12;
    private static final int COL_PROJ_RATE   = 13;
    private static final int COL_PERIOD_START= 14;  // dynamic period columns begin here

    // ════════════════════════════════════════════════════════════════════
    // Public entry point
    // ════════════════════════════════════════════════════════════════════

    /**
     * Generates the Excel workbook and writes it to the specified output path.
     *
     * @param output    result of DepreciationProjectionService.project()
     * @param outputPath target .xlsx file path (created or overwritten)
     * @throws IOException on file write failure
     */
    public void export(ProjectionOutput output, Path outputPath) throws IOException {

        ProjectionHeader       header  = output.header();
        List<ProjectionResult> results = output.results();
        List<ProjectionHeader.PeriodColumn> cols = header.getColumns();
        int totalDataCols = COL_PERIOD_START + cols.size() + 2; // +2 for Total Depn + Proj WDV

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Depreciation Projection");

            // ── Cell styles ─────────────────────────────────────────────
            Styles styles = new Styles(wb);

            // ── Legend rows (rows 0-10, 0-based) ────────────────────────
            int rowIdx = 0;
            rowIdx++;  // blank row 0
            writeLegend(sheet, styles, header, rowIdx);
            rowIdx += 11; // legend takes rows 1-11

            // ── Column headings (two rows) ───────────────────────────────
            int hdr1Row = rowIdx++;
            int hdr2Row = rowIdx++;
            writeColumnHeadings(sheet, styles, cols, hdr1Row, hdr2Row, totalDataCols);

            // ── Data rows ────────────────────────────────────────────────
            int firstDataRow = rowIdx;
            for (ProjectionResult res : results) {
                writeDataRow(sheet, styles, wb, res, cols, rowIdx++);
            }

            // ── Totals row ───────────────────────────────────────────────
            writeTotalsRow(sheet, styles, results, cols, rowIdx++);

            // ── AutoFit columns ──────────────────────────────────────────
            for (int c = 0; c < totalDataCols; c++) {
                sheet.autoSizeColumn(c);
            }

            // ── Write file ───────────────────────────────────────────────
            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                wb.write(fos);
            }
        }
        openFile(outputPath);
    }

    private void openFile(Path path) {
        // Use rundll32 shell open — most reliable on Windows via Maven exec
        String absPath = path.toAbsolutePath().toString();
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                // rundll32 is the most reliable way to open files from a non-interactive
                // Java process on Windows — bypasses AWT headless restrictions
                Runtime.getRuntime().exec(
                    new String[]{"rundll32", "url.dll,FileProtocolHandler", absPath});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", absPath});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", absPath});
            }
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                .warn("Could not open file {}: {}", absPath, ex.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Legend  (COBOL: populate-legends)
    // ════════════════════════════════════════════════════════════════════

    private void writeLegend(Sheet sheet, Styles s, ProjectionHeader h, int startRow) {
        int r = startRow;

        setStringCell(sheet, r++, 1, s.legend,
            "FA Depreciation Projection Worksheet");
        r++; // blank
        setStringCell(sheet, r++, 1, s.legend,
            "Assets in the range " + trim(h.getStartAssetNo()) + " to " + trim(h.getEndAssetNo()));
        setStringCell(sheet, r++, 1, s.legend,
            "Locations in the range " + trim(h.getStartLoc()) + " to " + trim(h.getEndLoc()));
        setStringCell(sheet, r++, 1, s.legend,
            "Groups in the range " + trim(h.getStartGrp()) + " to " + trim(h.getEndGrp()));
        setStringCell(sheet, r++, 1, s.legend,
            "Subgroups in the range " + trim(h.getStartSubgrp()) + " to " + trim(h.getEndSubgrp()));
        setStringCell(sheet, r++, 1, s.legend,
            "Departments in the range " + trim(h.getStartDept()) + " to " + trim(h.getEndDept()));
        setStringCell(sheet, r++, 1, s.legend,
            "Book or Tax ? " + h.getTaxBookInd());
        setStringCell(sheet, r++, 1, s.legend,
            "Projected depreciation date " + format(h.getDepnThruToDate(), FMT_DDMMYY));
        setStringCell(sheet, r++, 1, s.legend,
            "Projected depreciation rate " +
            (h.getProjectedRate() != null ? h.getProjectedRate().toPlainString() : "0.00"));
    }

    // ════════════════════════════════════════════════════════════════════
    // Column headings  (COBOL: populate-headings)
    // ════════════════════════════════════════════════════════════════════

    private void writeColumnHeadings(Sheet sheet, Styles s,
            List<ProjectionHeader.PeriodColumn> cols,
            int row1, int row2, int totalCols) {

        String[][] fixed = {
            {"Asset", "Number"},
            {"Description", ""},
            {"Location", ""},
            {"Group", ""},
            {"Sub Group", ""},
            {"Department", ""},
            {"Acqn Date", ""},
            {"Actual Cost", ""},
            {"Write Down Date", ""},
            {"Opening WDV", ""},
            {"Last Depreciation", "      Date"},
            {"Depreciation", "Frequency"},
            {"Depreciation", "Rate"},
            {"Projected", "Depreciation Rate"},
        };

        for (int c = 0; c < fixed.length; c++) {
            setStringCell(sheet, row1, c, s.colHead, fixed[c][0]);
            setStringCell(sheet, row2, c, s.colHead, fixed[c][1]);
        }

        // Dynamic period columns
        if (!cols.isEmpty()) {
            // First dynamic column: "UP TO" + label
            setStringCell(sheet, row1, COL_PERIOD_START, s.colHead, "UP TO");
            setStringCell(sheet, row2, COL_PERIOD_START, s.colHead, cols.get(0).getLabel());

            for (int i = 1; i < cols.size(); i++) {
                setStringCell(sheet, row1, COL_PERIOD_START + i, s.colHead, cols.get(i).getLabel());
                setStringCell(sheet, row2, COL_PERIOD_START + i, s.colHead, "");
            }
        }

        // Total + WDV
        int totalCol = COL_PERIOD_START + cols.size();
        setStringCell(sheet, row1, totalCol,     s.colHead, "Total");
        setStringCell(sheet, row2, totalCol,     s.colHead, "Depreciation");
        setStringCell(sheet, row1, totalCol + 1, s.colHead, "Projected");
        setStringCell(sheet, row2, totalCol + 1, s.colHead, "WDV");
    }

    // ════════════════════════════════════════════════════════════════════
    // Data row  (COBOL: EXPORT-THE-WORKSHEET)
    // ════════════════════════════════════════════════════════════════════

    private void writeDataRow(Sheet sheet, Styles s, Workbook wb,
            ProjectionResult res,
            List<ProjectionHeader.PeriodColumn> cols,
            int rowIdx) {

        Row row = sheet.createRow(rowIdx);

        setTextCell(row, COL_ASSET_NO,    s.text,   res.getAssetNo());
        setTextCell(row, COL_DESC,        s.text,   res.getDescription());
        setTextCell(row, COL_LOC,         s.text,   res.getLocCode());
        setTextCell(row, COL_GRP,         s.text,   res.getGrpCode());
        setTextCell(row, COL_SUBGRP,      s.text,   res.getSubgrpCode());
        setTextCell(row, COL_DEPT,        s.text,   res.getDeptCode());
        setDateCell(row,  COL_ACQN_DATE,  s.date2,  res.getAcqnDate());
        setNumCell(row,   COL_ACTUAL_COST,s.money,  res.getActualCost());
        setDateCell(row,  COL_WD_DATE,    s.date4,  res.getWriteDownDate());
        setNumCell(row,   COL_OPEN_WDV,   s.money,  res.getOpeningWdv());
        setDateCell(row,  COL_LAST_DEP_DT,s.date2,  res.getLastDepnDate());
        setTextCell(row,  COL_FREQ,       s.text,   String.valueOf(res.getDepnFreq()));
        setNumCell(row,   COL_RATE,       s.decimal,res.getDepnRate());
        setNumCell(row,   COL_PROJ_RATE,  s.decimal,res.getProjectedRate());

        BigDecimal[] depnAmt = res.getDepnAmt();
        for (int i = 0; i < cols.size() && i < depnAmt.length; i++) {
            setNumCell(row, COL_PERIOD_START + i, s.amount, depnAmt[i]);
        }

        int totalCol = COL_PERIOD_START + cols.size();
        setNumCell(row, totalCol,     s.amount, res.getTotalDepn());
        setNumCell(row, totalCol + 1, s.amount, res.getProjClosingWdv());
    }

    // ════════════════════════════════════════════════════════════════════
    // Totals row  (COBOL: PRINT-REPORT-TOTAL-DATA)
    // ════════════════════════════════════════════════════════════════════

    private void writeTotalsRow(Sheet sheet, Styles s,
            List<ProjectionResult> results,
            List<ProjectionHeader.PeriodColumn> cols,
            int rowIdx) {

        Row row = sheet.createRow(rowIdx);
        setTextCell(row, COL_DESC, s.bold, "Totals");

        // Sum each period column
        for (int c = 0; c < cols.size(); c++) {
            BigDecimal colTotal = BigDecimal.ZERO;
            for (ProjectionResult res : results) {
                BigDecimal[] arr = res.getDepnAmt();
                if (c < arr.length && arr[c] != null) colTotal = colTotal.add(arr[c]);
            }
            setNumCell(row, COL_PERIOD_START + c, s.amount, colTotal);
        }

        BigDecimal grandTotal    = results.stream().map(ProjectionResult::getTotalDepn)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grandProjWdv  = results.stream().map(ProjectionResult::getProjClosingWdv)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int totalCol = COL_PERIOD_START + cols.size();
        setNumCell(row, totalCol,     s.amount, grandTotal);
        setNumCell(row, totalCol + 1, s.amount, grandProjWdv);
    }

    // ════════════════════════════════════════════════════════════════════
    // Cell helpers
    // ════════════════════════════════════════════════════════════════════

    private void setStringCell(Sheet sheet, int row, int col, CellStyle style, String value) {
        Row r = sheet.getRow(row);
        if (r == null) r = sheet.createRow(row);
        Cell c = r.createCell(col, CellType.STRING);
        c.setCellValue(value == null ? "" : value);
        c.setCellStyle(style);
    }

    private void setTextCell(Row row, int col, CellStyle style, String value) {
        Cell c = row.createCell(col, CellType.STRING);
        c.setCellValue(value == null ? "" : value.trim());
        c.setCellStyle(style);
    }

    private void setNumCell(Row row, int col, CellStyle style, BigDecimal value) {
        Cell c = row.createCell(col, CellType.NUMERIC);
        c.setCellValue(value != null ? value.doubleValue() : 0.0);
        c.setCellStyle(style);
    }

    private void setDateCell(Row row, int col, CellStyle style, LocalDate date) {
        Cell c = row.createCell(col, CellType.NUMERIC);
        // Guard: treat null and pre-1900 dates (COBOL 1899-12-30 sentinel) as blank
        if (date != null && date.getYear() >= 1900) {
            c.setCellValue(java.util.Date.from(
                date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()));
            c.setCellStyle(style);
        }
        // Leave blank cell unstyled so it shows as empty, not 00/01/00
    }

    private String trim(String s) { return s == null ? "" : s.trim(); }
    private String format(LocalDate d, DateTimeFormatter f) {
        return d == null ? "" : d.format(f);
    }

    // ════════════════════════════════════════════════════════════════════
    // Style factory — mirrors FATL13 FORMAT-THE-COLUMN number formats
    // ════════════════════════════════════════════════════════════════════

    private static class Styles {
        final CellStyle legend, colHead, text, bold, date2, date4, money, decimal, amount;

        Styles(Workbook wb) {
            DataFormat fmt = wb.createDataFormat();

            Font headerFont = wb.createFont();
            headerFont.setBold(true);

            Font legendFont = wb.createFont();
            legendFont.setBold(true);
            legendFont.setFontHeightInPoints((short) 11);

            legend = wb.createCellStyle();
            legend.setFont(legendFont);

            colHead = wb.createCellStyle();
            colHead.setFont(headerFont);
            colHead.setAlignment(HorizontalAlignment.CENTER);
            colHead.setWrapText(true);

            text = wb.createCellStyle();
            // "@" format — forces text display (COBOL SUB-1 = 1-6, 12)
            text.setDataFormat(fmt.getFormat("@"));

            bold = wb.createCellStyle();
            bold.setFont(headerFont);

            // dd/mm/yy  (COBOL: columns 7, 11)
            date2 = wb.createCellStyle();
            date2.setDataFormat(fmt.getFormat("dd/mm/yy"));

            // dd/mm/yyyy  (COBOL: column 9)
            date4 = wb.createCellStyle();
            date4.setDataFormat(fmt.getFormat("dd/mm/yyyy"));

            // #,##0;[Red]-#,##0  (COBOL: columns 8, 10)
            money = wb.createCellStyle();
            money.setDataFormat(fmt.getFormat("#,##0;[Red]-#,##0"));

            // plain numeric (rate columns 13, 14)
            decimal = wb.createCellStyle();
            decimal.setDataFormat(fmt.getFormat("0.00"));

            // #,##0.00;[Red]-#,##0.00  (COBOL: columns > 13)
            amount = wb.createCellStyle();
            amount.setDataFormat(fmt.getFormat("#,##0.00;[Red]-#,##0.00"));
        }
    }
}
