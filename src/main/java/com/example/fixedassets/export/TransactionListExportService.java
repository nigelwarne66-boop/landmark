package com.example.fixedassets.export;

import com.example.fixedassets.model.TransactionRow;
import com.example.fixedassets.service.TransactionListService.AssetGroup;
import com.example.fixedassets.service.TransactionListService.TransactionListOutput;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * FATL02 Excel export — 40-column flat layout matching COBOL EXPORT-TRANSACTION.
 *
 * Column order (matches COBOL EXPORT-HEADINGS-ETC exactly):
 *  1  AssetNo         2  Description      3  Location       4  Dept
 *  5  Group           6  SubGroup         7  TrxDate        8  TrxType
 *  9  Batch           10 AcqnBookCost     11 AcqnTaxCost    12 AcqnActualCost
 *  13 BookDepn        14 TaxDepn          15 DepnMethod      16 DepnCode
 *  17 DepnFreq        18 DepnRate         19 RevalAmt        20 RevalAdjAmt
 *  21 RevalAccumDepn  22 TfrBookDepn      23 TfrNetRevalAmt  24 TfrProvDepnAmt
 *  25 RetmtBookProfit 26 RetmtTaxProfit   27 RetmtProceeds
 *  28 TrxLoc          29 TrxDept          30 TrxGrp         31 TrxSubGrp
 *  32 TfrToLoc        33 TfrToDept        34 TfrToGrp       35 TfrToSubGrp
 *  36 Reference       37 AuditUserID      38 AuditDate      39 AuditTime
 */
@Service
public class TransactionListExportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String[] HEADERS = {
        "AssetNo", "Description", "Location", "Dept", "Group", "SubGroup",
        "TrxDate", "TrxType", "Batch",
        "AcqnBookCost", "AcqnTaxCost", "AcqnActualCost",
        "BookDepn", "TaxDepn",
        "DepnMethod", "DepnCode", "DepnFreq", "DepnRate",
        "RevalAmt", "RevalAdjAmt", "RevalAccumDepn",
        "TfrBookDepn", "TfrNetRevalAmt", "TfrProvDepnAmt",
        "RetmtBookProfit", "RetmtTaxProfit", "RetmtProceeds",
        "TrxLoc", "TrxDept", "TrxGrp", "TrxSubGrp",
        "TfrToLoc", "TfrToDept", "TfrToGrp", "TfrToSubGrp",
        "Reference", "AuditUserID", "AuditDate", "AuditTime"
    };

    public void export(TransactionListOutput output, Path outputPath) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle headerStyle  = buildHeaderStyle(wb);
            CellStyle dateStyle    = buildDateStyle(wb);
            CellStyle moneyStyle   = buildMoneyStyle(wb);
            CellStyle intStyle     = buildIntStyle(wb);
            CellStyle rateStyle    = buildRateStyle(wb);
            CellStyle textStyle    = buildTextStyle(wb);
            CellStyle altStyle     = buildAltRowStyle(wb);

            Sheet sheet = wb.createSheet("FA Transaction List");
            sheet.createFreezePane(2, 2);

            // ── Title row ─────────────────────────────────────────────
            Row title = sheet.createRow(0);
            CellStyle titleStyle = buildTitleStyle(wb);
            createTextCell(title, 0, "Landmark — Transaction List", titleStyle);

            // Sub-title: date range
            String range = buildRangeText(output);
            createTextCell(title, 2, range, titleStyle);

            // ── Column headers ────────────────────────────────────────
            Row hdr = sheet.createRow(1);
            for (int i = 0; i < HEADERS.length; i++) {
                createTextCell(hdr, i, HEADERS[i], headerStyle);
            }

            // ── Data rows ─────────────────────────────────────────────
            int rowNum = 2;
            boolean alt = false;

            for (AssetGroup group : output.groups()) {
                for (TransactionRow t : group.transactions()) {
                    CellStyle bg = alt ? altStyle : null;
                    Row row = sheet.createRow(rowNum++);
                    writeRow(row, t, bg, dateStyle, moneyStyle, intStyle, rateStyle, textStyle);
                }
                alt = !alt;
            }

            // ── Totals row ────────────────────────────────────────────
            if (!output.groups().isEmpty()) {
                CellStyle totStyle = buildTotalStyle(wb);
                Row totRow = sheet.createRow(rowNum);
                createTextCell(totRow, 0,
                    output.totalAssets() + " assets  |  " +
                    output.totalTransactions() + " transactions", totStyle);
            }

            // ── Auto-size first 10 columns ────────────────────────────
            for (int i = 0; i < Math.min(HEADERS.length, 10); i++) {
                sheet.autoSizeColumn(i);
            }

            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                wb.write(fos);
            }
        }

        openFile(outputPath);
    }

    // ── Row writer ────────────────────────────────────────────────────

    private void writeRow(Row row, TransactionRow t,
                          CellStyle bg,
                          CellStyle dateSty, CellStyle moneySty,
                          CellStyle intSty, CellStyle rateSty, CellStyle textSty) {
        int c = 0;
        // Text columns
        createTextCell(row, c++, t.getAssetNo(),   bg != null ? bg : textSty);
        createTextCell(row, c++, t.getDesc1(),      bg != null ? bg : textSty);
        createTextCell(row, c++, t.getLocCode(),    bg != null ? bg : textSty);
        createTextCell(row, c++, t.getDeptCode(),   bg != null ? bg : textSty);
        createTextCell(row, c++, t.getGrpCode(),    bg != null ? bg : textSty);
        createTextCell(row, c++, t.getSubgrpCode(), bg != null ? bg : textSty);

        // TrxDate as formatted string (matches COBOL DD/MM/YYYY)
        createTextCell(row, c++, fmtDate(t.getTrxDate()), bg != null ? bg : textSty);
        createTextCell(row, c++, t.getTrxType(),    bg != null ? bg : textSty);

        // Batch as integer
        createNumCell(row, c++, t.getBatchNo(), bg != null ? bg : intSty);

        // Acquisition amounts
        createAmtCell(row, c++, t.getAcqnBookDepnCost(),  bg != null ? bg : moneySty);
        createAmtCell(row, c++, t.getAcqnTaxDepnCost(),   bg != null ? bg : moneySty);
        createAmtCell(row, c++, t.getAcqnActualCost(),    bg != null ? bg : moneySty);

        // Depreciation amounts (BD/BA = book, TD/TA = tax)
        createAmtCell(row, c++, t.bookDepnForExport(),    bg != null ? bg : moneySty);
        createAmtCell(row, c++, t.taxDepnForExport(),     bg != null ? bg : moneySty);

        // Depreciation details
        createTextCell(row, c++, t.getDepnMethod(), bg != null ? bg : textSty);
        createTextCell(row, c++, t.getDepnCode(),   bg != null ? bg : textSty);
        if (t.getDepnFreq() != 0)
            createNumCell(row, c++, t.getDepnFreq(), bg != null ? bg : intSty);
        else
            row.createCell(c++);
        createAmtCell(row, c++, t.getDepnRate(),    bg != null ? bg : rateSty);

        // Revaluation
        createAmtCell(row, c++, t.getRevalVal(),        bg != null ? bg : moneySty);
        createAmtCell(row, c++, t.getRevalAdjAmt(),     bg != null ? bg : moneySty);
        createAmtCell(row, c++, t.getRevalAccumDepn(),  bg != null ? bg : moneySty);

        // Transfer
        createAmtCell(row, c++, t.getTfrBookDepnCost(), bg != null ? bg : moneySty);
        createAmtCell(row, c++, t.getTfrNetRevalAmt(),  bg != null ? bg : moneySty);
        createAmtCell(row, c++, t.getTfrProvDepnAmt(),  bg != null ? bg : moneySty);

        // Retirement
        createAmtCell(row, c++, t.getRetmtBkProfitAmt(),  bg != null ? bg : moneySty);
        createAmtCell(row, c++, t.getRetmtTxProfitAmt(),  bg != null ? bg : moneySty);
        createAmtCell(row, c++, t.getRetmtProceedsAmt(),  bg != null ? bg : moneySty);

        // Transaction codes (type-specific location etc.)
        createTextCell(row, c++, t.trxLoc(),    bg != null ? bg : textSty);
        createTextCell(row, c++, t.trxDept(),   bg != null ? bg : textSty);
        createTextCell(row, c++, t.trxGrp(),    bg != null ? bg : textSty);
        createTextCell(row, c++, t.trxSubgrp(), bg != null ? bg : textSty);

        // Transfer-to codes
        createTextCell(row, c++, t.getTfrToLoc(),    bg != null ? bg : textSty);
        createTextCell(row, c++, t.getTfrToDept(),   bg != null ? bg : textSty);
        createTextCell(row, c++, t.getTfrToGrp(),    bg != null ? bg : textSty);
        createTextCell(row, c++, t.getTfrToSubgrp(), bg != null ? bg : textSty);

        // Reference and audit
        createTextCell(row, c++, t.getRef(),         bg != null ? bg : textSty);
        createTextCell(row, c++, t.getAuditUserId(), bg != null ? bg : textSty);
        createTextCell(row, c++, fmtDate(t.getAuditDate()), bg != null ? bg : textSty);
        createTextCell(row, c,   t.getAuditTime(),   bg != null ? bg : textSty);
    }

    // ── Cell helpers ──────────────────────────────────────────────────

    private void createTextCell(Row row, int col, String val, CellStyle style) {
        Cell c = row.createCell(col, CellType.STRING);
        c.setCellValue(val == null ? "" : val.trim());
        if (style != null) c.setCellStyle(style);
    }

    private void createNumCell(Row row, int col, long val, CellStyle style) {
        Cell c = row.createCell(col, CellType.NUMERIC);
        c.setCellValue(val);
        if (style != null) c.setCellStyle(style);
    }

    private void createAmtCell(Row row, int col, BigDecimal val, CellStyle style) {
        if (val == null || val.compareTo(BigDecimal.ZERO) == 0) {
            row.createCell(col);
            return;
        }
        Cell c = row.createCell(col, CellType.NUMERIC);
        c.setCellValue(val.doubleValue());
        if (style != null) c.setCellStyle(style);
    }

    private String fmtDate(LocalDate d) {
        if (d == null || d.getYear() < 1900) return "";
        return d.format(DATE_FMT);
    }

    private String buildRangeText(TransactionListOutput out) {
        var req = out.request();
        StringBuilder sb = new StringBuilder("Transactions");
        if (req.getStartDate() != null || req.getEndDate() != null) {
            sb.append(" from ");
            if (req.getStartDate() != null) sb.append(req.getStartDate().format(DATE_FMT));
            sb.append(" to ");
            if (req.getEndDate()   != null) sb.append(req.getEndDate().format(DATE_FMT));
        }
        return sb.toString();
    }

    // ── Cell style builders ───────────────────────────────────────────

    private CellStyle buildHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setWrapText(false);
        return s;
    }

    private CellStyle buildTitleStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 12);
        s.setFont(f);
        return s;
    }

    private CellStyle buildTotalStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private CellStyle buildDateStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        DataFormat fmt = wb.createDataFormat();
        s.setDataFormat(fmt.getFormat("dd/mm/yyyy"));
        return s;
    }

    private CellStyle buildMoneyStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        DataFormat fmt = wb.createDataFormat();
        s.setDataFormat(fmt.getFormat("#,##0.00;[Red]-#,##0.00"));
        return s;
    }

    private CellStyle buildIntStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        DataFormat fmt = wb.createDataFormat();
        s.setDataFormat(fmt.getFormat("0"));
        return s;
    }

    private CellStyle buildRateStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        DataFormat fmt = wb.createDataFormat();
        s.setDataFormat(fmt.getFormat("0.00"));
        return s;
    }

    private CellStyle buildTextStyle(Workbook wb) {
        return wb.createCellStyle();
    }

    private CellStyle buildAltRowStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    // ── File open ─────────────────────────────────────────────────────

    private void openFile(Path path) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                Runtime.getRuntime().exec(
                    new String[]{"rundll32", "url.dll,FileProtocolHandler",
                        path.toAbsolutePath().toString()});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", path.toAbsolutePath().toString()});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", path.toAbsolutePath().toString()});
            }
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                .warn("Could not open file: {}", ex.getMessage());
        }
    }
}
