package com.landmarksoftware.export;

import com.landmarksoftware.service.ap.ApReportDataService.SupplierAnalysis;
import com.landmarksoftware.service.ap.ApReportDataService.SupplierAnalysisResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * APTL10 Supplier Analysis Excel writer. The COBOL forces Excel and emits a
 * dynamic column set (one $ + one # column per analysed month), which a static
 * Jasper template can't express — so this builds the workbook directly with POI.
 * Column order mirrors the COBOL CSV: identity + totals/averages up front, then
 * the per-month value/count pairs oldest→newest.
 */
@Component
public class ApSupplierAnalysisExcelExporter {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public byte[] build(SupplierAnalysisResult r, String companyName, String basedOnDesc) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Supplier Analysis");
            List<LocalDate> months = r.monthEnds();

            CellStyle bold = wb.createCellStyle();
            Font bf = wb.createFont(); bf.setBold(true); bold.setFont(bf);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00;(#,##0.00)"));

            int rowIdx = 0;
            Row title = sheet.createRow(rowIdx++);
            cell(title, 0, "Supplier Analysis — " + companyName + "  (" + basedOnDesc + ")", bold);

            // header
            Row hdr = sheet.createRow(rowIdx++);
            String[] fixed = {"Supplier No", "Supplier Name", "SubLedger", "City", "State", "Postcode",
                "Country", "FC code", "Total $", "%ofTotal $", "Total #", "%ofTotal #",
                "HighestMth$", "HighestMth#", "LowestMth$", "LowestMth#", "AverageMth$", "AverageMth#"};
            int c = 0;
            for (String h : fixed) cell(hdr, c++, h, bold);
            for (LocalDate m : months) {
                cell(hdr, c++, m.format(MONTH) + " $", bold);
                cell(hdr, c++, m.format(MONTH) + " #", bold);
            }

            // detail
            for (SupplierAnalysis a : r.suppliers()) {
                Row row = sheet.createRow(rowIdx++);
                c = 0;
                cell(row, c++, a.supplierNo(), null);
                cell(row, c++, a.name(), null);
                cell(row, c++, a.subLedger(), null);
                cell(row, c++, a.city(), null);
                cell(row, c++, a.state(), null);
                cell(row, c++, a.postcode(), null);
                cell(row, c++, a.country(), null);
                cell(row, c++, a.forCurrCode(), null);
                num(row, c++, a.totalValue(), money);
                num(row, c++, a.valuePct(), money);
                row.createCell(c++).setCellValue(a.totalNo());
                num(row, c++, a.noPct(), money);
                num(row, c++, a.highVal(), money);
                row.createCell(c++).setCellValue(a.highNo());
                num(row, c++, a.lowVal(), money);
                row.createCell(c++).setCellValue(a.lowNo());
                num(row, c++, a.avgValue(), money);
                row.createCell(c++).setCellValue(a.avgNo());
                for (int i = 0; i < months.size(); i++) {
                    num(row, c++, a.monthValue()[i], money);
                    row.createCell(c++).setCellValue(a.monthNo()[i]);
                }
            }

            // grand total
            Row gt = sheet.createRow(rowIdx++);
            c = 0;
            cell(gt, c++, "", bold);
            cell(gt, c++, "Total all suppliers", bold);
            for (int i = 0; i < 6; i++) cell(gt, c++, "", bold);   // subledger..fc code
            num(gt, c++, r.grandValue(), money);
            cell(gt, c++, "100.00", bold);
            gt.createCell(c++).setCellValue(r.grandNo());
            cell(gt, c++, "100.00", bold);
            for (int i = 0; i < 6; i++) cell(gt, c++, "", bold);   // high/low/avg
            for (int i = 0; i < months.size(); i++) {
                num(gt, c++, r.grandMonthValue()[i], money);
                gt.createCell(c++).setCellValue(r.grandMonthNo()[i]);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Supplier Analysis Excel build failed: " + e.getMessage(), e);
        }
    }

    private static void cell(Row row, int col, String v, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(v == null ? "" : v);
        if (style != null) cell.setCellStyle(style);
    }

    private static void num(Row row, int col, BigDecimal v, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(v == null ? 0d : v.doubleValue());
        cell.setCellStyle(style);
    }
}
