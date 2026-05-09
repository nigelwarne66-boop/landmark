package com.example.fixedassets.export;

import com.example.fixedassets.model.AcquiredRetiredRow;
import com.example.fixedassets.model.AcquiredRetiredRow.PooledTrxLine;
import com.example.fixedassets.service.AcquiredRetiredService.AcquiredRetiredOutput;
import com.example.fixedassets.service.AcquiredRetiredService.ReportTotals;
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
 * FATL03 Excel export — 24-column layout matching COBOL EXPORT-HEADINGS-ETC.
 *
 * Column order (matches COBOL column-headings exactly):
 *  1  Location        2  Dept           3  Group           4  SubGrp
 *  5  AssetNo         6  Description1   7  Description2    8  AcqnDate/TrxDate
 *  9  Status
 *  Book:
 *  10 BkDepnCost      11 BkLastReval    12 BkAccumDepn     13 LastBkDepn
 *  14 BookWDV         15 RetmntProceeds 16 BookProfitLoss
 *  Tax:
 *  17 TxDepnCost      18 TxLastReval    19 TxAccumDepn     20 LastTxDepn
 *  21 TaxWDV          22 RetmntProceeds 23 TaxProfitLoss
 *  24 RetmntDate/TrxDate
 */
@Service
public class AcquiredRetiredExportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String[] HEADERS = {
        "Location", "Dept", "Group", "SubGrp",
        "AssetNo", "Description 1", "Description 2",
        "AcqnDate", "Status",
        // Book
        "BkDepnCost", "BkLastReval", "BkAccumDepn", "LastBkDepn",
        "BookWDV", "RetmntProceeds", "BookProfitLoss",
        // Tax
        "TxDepnCost", "TxLastReval", "TxAccumDepn", "LastTxDepn",
        "TaxWDV", "RetmntProceeds", "TaxProfitLoss",
        // Last
        "RetmntDate"
    };

    public void export(AcquiredRetiredOutput output, Path outputPath) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle hdrStyle   = buildHeaderStyle(wb);
            CellStyle titleStyle = buildTitleStyle(wb);
            CellStyle moneyStyle = buildMoneyStyle(wb);
            CellStyle textStyle  = wb.createCellStyle();
            CellStyle totStyle   = buildTotalStyle(wb);
            CellStyle altStyle   = buildAltStyle(wb);

            Sheet sheet = wb.createSheet("Assets Acquired & Retired");
            sheet.createFreezePane(0, 3);

            // ── Title row ─────────────────────────────────────────────
            Row t1 = sheet.createRow(0);
            createText(t1, 0, "Assets Acquired and Retired", titleStyle);
            createText(t1, 4,
                output.request().acqnRetmtLiteral() +
                " for the period " +
                fmt(output.request().getStartDate()) +
                " to " + fmt(output.request().getEndDate()),
                titleStyle);

            // ── Blank row ──────────────────────────────────────────────
            sheet.createRow(1);

            // ── Column headers ────────────────────────────────────────
            Row hdr = sheet.createRow(2);
            for (int i = 0; i < HEADERS.length; i++)
                createText(hdr, i, HEADERS[i], hdrStyle);

            // ── Data rows ─────────────────────────────────────────────
            int rowNum = 3;
            boolean alt = false;

            for (AcquiredRetiredRow r : output.rows()) {
                CellStyle bg = alt ? altStyle : null;

                if (r.isPooledAsset()) {
                    // Pooled: summary row + individual transaction lines
                    rowNum = writePooledSummary(sheet, rowNum, r, moneyStyle, textStyle, bg, wb);
                    for (PooledTrxLine trx : r.getPooledTrxs()) {
                        rowNum = writePooledTrx(sheet, rowNum, r, trx, moneyStyle, textStyle, wb);
                    }
                } else {
                    rowNum = writeNonPooled(sheet, rowNum, r, moneyStyle, textStyle, bg);
                }
                alt = !alt;
            }

            // ── Totals row ────────────────────────────────────────────
            sheet.createRow(rowNum++); // blank
            writeTotals(sheet, rowNum, output.totals(), totStyle, moneyStyle);

            // Auto-size first 9 columns
            for (int i = 0; i < 9; i++) sheet.autoSizeColumn(i);

            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                wb.write(fos);
            }
        }
        openFile(outputPath);
    }

    // ── Row writers ───────────────────────────────────────────────────

    private int writeNonPooled(Sheet sheet, int rowNum,
                                AcquiredRetiredRow r,
                                CellStyle money, CellStyle text, CellStyle bg) {
        Row row = sheet.createRow(rowNum);
        int c = 0;
        CellStyle ts = bg != null ? bg : text;
        CellStyle ms = bg != null ? bg : money;

        createText(row, c++, r.getLocCode(),    ts);
        createText(row, c++, r.getDeptCode(),   ts);
        createText(row, c++, r.getGrpCode(),    ts);
        createText(row, c++, r.getSubgrpCode(), ts);
        createText(row, c++, r.getAssetNo(),    ts);
        createText(row, c++, r.getDesc1(),      ts);
        createText(row, c++, r.getDesc2(),      ts);
        createText(row, c++, fmt(r.getAcqnDate()), ts);
        createText(row, c++, r.assetStatusDesc(), ts);
        // Book
        createAmt(row, c++, r.getBookDepnCost(),                    ms);
        createAmt(row, c++, r.getLastRevalVal(),                    ms);
        createAmt(row, c++, r.totalAccumBookDepnForDisplay(),        ms);
        createText(row, c++, fmt(r.getLastBookDepnDate()),           ts);
        createAmt(row, c++, r.getBookWrittenDownVal(),               ms);
        createAmt(row, c++, r.getRetmtProceedsVal(),                 ms);
        createAmt(row, c++, r.getBookDisposalProfitLoss(),           ms);
        // Tax
        createAmt(row, c++, r.getTaxDepnCost(),                     ms);
        createAmt(row, c++, r.getLastTaxRevalVal(),                  ms);
        createAmt(row, c++, r.getAccumTaxDepn().add(r.getAccumTaxDepnAdj()), ms);
        createText(row, c++, fmt(r.getLastTaxDepnDate()),            ts);
        createAmt(row, c++, r.getTaxWrittenDownVal(),                ms);
        createAmt(row, c++, r.getRetmtProceedsVal(),                 ms);
        createAmt(row, c++, r.getTaxDisposalProfitLoss(),            ms);
        createText(row, c,   fmt(r.getRetmtDate()),                  ts);

        return rowNum + 1;
    }

    private int writePooledSummary(Sheet sheet, int rowNum,
                                    AcquiredRetiredRow r,
                                    CellStyle money, CellStyle text, CellStyle bg,
                                    Workbook wb) {
        Row row = sheet.createRow(rowNum);
        // Pooled summary uses WS-TOTAL-* fields (mirrors DETAIL-LINE-10/11)
        // AcqnDate column = blank (transactions provide individual dates)
        int c = 0;
        CellStyle ts = bg != null ? bg : text;
        CellStyle ms = bg != null ? bg : money;

        createText(row, c++, r.getLocCode(),    ts);
        createText(row, c++, r.getDeptCode(),   ts);
        createText(row, c++, r.getGrpCode(),    ts);
        createText(row, c++, r.getSubgrpCode(), ts);
        createText(row, c++, r.getAssetNo(),    ts);
        createText(row, c++, r.getDesc1(),      ts);
        createText(row, c++, r.getDesc2(),      ts);
        createText(row, c++, "",                ts);  // date from transactions
        createText(row, c++, r.assetStatusDesc(), ts);
        // Book totals
        createAmt(row, c++, r.getTotalBookDepnCost(),   ms);
        row.createCell(c++);  // no last-reval for pooled summary
        createAmt(row, c++, r.getTotalAccumBookDepn(),  ms);
        createText(row, c++, fmt(r.getLastBookDepnDate()), ts);
        createAmt(row, c++, r.getBookDepnCost(),        ms);  // pool book balance
        createAmt(row, c++, r.getTotalRetmtProceeds(),  ms);
        createAmt(row, c++, r.getBookDisposalProfitLoss(), ms);
        // Tax totals
        createAmt(row, c++, r.getTotalTaxDepnCost(),    ms);
        row.createCell(c++);
        createAmt(row, c++, r.getTotalAccumTaxDepn(),   ms);
        createText(row, c++, fmt(r.getLastTaxDepnDate()), ts);
        createAmt(row, c++, r.getTaxDepnCost(),         ms);  // pool tax balance
        createAmt(row, c++, r.getTotalRetmtProceeds(),  ms);
        createAmt(row, c,   r.getTaxDisposalProfitLoss(), ms);

        return rowNum + 1;
    }

    private int writePooledTrx(Sheet sheet, int rowNum,
                                AcquiredRetiredRow r, PooledTrxLine trx,
                                CellStyle money, CellStyle text, Workbook wb) {
        // Indent under the pooled summary (mirrors DETAIL-LINE-16/17)
        CellStyle indentStyle = wb.createCellStyle();
        indentStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        indentStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Row row = sheet.createRow(rowNum);
        int c = 0;
        createText(row, c++, r.getLocCode(),     indentStyle);
        createText(row, c++, r.getDeptCode(),    indentStyle);
        createText(row, c++, r.getGrpCode(),     indentStyle);
        createText(row, c++, r.getSubgrpCode(),  indentStyle);
        createText(row, c++, "  " + r.getAssetNo(), indentStyle);  // indented
        createText(row, c++, trx.trxType(),      indentStyle);
        row.createCell(c++);  // desc2
        createText(row, c++, fmt(trx.trxDate()), indentStyle);
        row.createCell(c++);  // status
        // Book
        createAmt(row, c++, trx.bookDepnCost(),        indentStyle != null ? indentStyle : money);
        row.createCell(c++);
        row.createCell(c++);
        row.createCell(c++);
        row.createCell(c++);  // WDV
        createAmt(row, c++, trx.retmtProceeds(),       money);
        createAmt(row, c++, trx.bookDisposalProfit(),  money);
        // Tax
        createAmt(row, c++, trx.taxDepnCost(),         money);
        row.createCell(c++);
        row.createCell(c++);
        row.createCell(c++);
        row.createCell(c++);
        row.createCell(c++);
        createAmt(row, c++, trx.taxDisposalProfit(),   money);
        createText(row, c,   fmt(trx.trxDate()),       text);

        return rowNum + 1;
    }

    private void writeTotals(Sheet sheet, int rowNum,
                              ReportTotals t, CellStyle tot, CellStyle money) {
        Row r = sheet.createRow(rowNum);
        createText(r, 0,
            t.assetCount() + " ASSETS LISTED", tot);
        // BOOK totals at columns matching report headers
        createAmt(r, 9,  t.totalBookCost(),      tot);
        createAmt(r, 11, t.totalAccumBookDepn(),  tot);
        createAmt(r, 13, t.totalBookWdv(),        tot);
        createAmt(r, 14, t.totalProceeds(),       tot);
        createAmt(r, 15, t.totalBookProfit(),     tot);
        // TAX totals
        createAmt(r, 16, t.totalTaxCost(),        tot);
        createAmt(r, 18, t.totalAccumTaxDepn(),   tot);
        createAmt(r, 20, t.totalTaxWdv(),         tot);
        createAmt(r, 22, t.totalTaxProfit(),      tot);
    }

    // ── Cell helpers ──────────────────────────────────────────────────

    private void createText(Row row, int col, String val, CellStyle style) {
        Cell c = row.createCell(col, CellType.STRING);
        c.setCellValue(val == null ? "" : val.trim());
        if (style != null) c.setCellStyle(style);
    }

    private void createAmt(Row row, int col, BigDecimal val, CellStyle style) {
        if (val == null || val.compareTo(BigDecimal.ZERO) == 0) {
            row.createCell(col);
            return;
        }
        Cell c = row.createCell(col, CellType.NUMERIC);
        c.setCellValue(val.doubleValue());
        if (style != null) c.setCellStyle(style);
    }

    private String fmt(LocalDate d) {
        if (d == null || d.getYear() < 1900) return "";
        return d.format(DATE_FMT);
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
        DataFormat df = wb.createDataFormat();
        s.setDataFormat(df.getFormat("#,##0.00;[Red]-#,##0.00"));
        return s;
    }

    private CellStyle buildMoneyStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        DataFormat df = wb.createDataFormat();
        s.setDataFormat(df.getFormat("#,##0.00;[Red]-#,##0.00"));
        return s;
    }

    private CellStyle buildAltStyle(Workbook wb) {
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
