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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * FATL13 PDF replacement — produces a paginated A4 portrait report.
 *
 * Layout per page:
 *   - Report header (title, company, parameters, page n of N)
 *   - Column headings (fixed cols + period cols for this page segment)
 *   - Asset data rows
 *   - Totals row on final segment
 *
 * Because there may be many period columns, the report is segmented:
 * fixed columns (asset no, description, cost, opening WDV) appear on
 * every page segment, with different period columns shown per segment.
 *
 * Portrait A4: 595 x 842 pts. Usable width ~515 pts with 40pt margins.
 */
@Service
public class DepreciationPdfService {

    // ── Page geometry ────────────────────────────────────────────────────
    private static final float PAGE_W    = 595.27563f;  // A4 width in points
    private static final float PAGE_H    = 841.88977f;  // A4 height in points
    private static final float MARGIN    = 36f;
    private static final float CONTENT_W = PAGE_W - 2 * MARGIN;        // 523

    // ── Row / cell geometry ───────────────────────────────────────────────
    private static final float ROW_H      = 14f;
    private static final float HEADER_H   = 80f;  // space for report header block
    private static final float COL_HDR_H  = 24f;  // two-line column heading
    private static final float FOOTER_H   = 20f;

    // ── Fixed column widths (pts) ─────────────────────────────────────────
    private static final float W_ASSET  = 70f;
    private static final float W_DESC   = 110f;
    private static final float W_COST   = 60f;
    private static final float W_WDV    = 60f;
    private static final float FIXED_W  = W_ASSET + W_DESC + W_COST + W_WDV; // 300

    private static final float PERIOD_COL_W = 55f;  // width per period column

    // ── Colours ───────────────────────────────────────────────────────────
    private static final float[] BLUE_DARK  = {0.12f, 0.31f, 0.47f};
    private static final float[] BLUE_MED   = {0.18f, 0.46f, 0.71f};  // #2E75B6
    private static final float[] BLUE_LIGHT = {0.84f, 0.91f, 0.97f};  // #D6E4F7
    private static final float[] GREY_LIGHT = {0.95f, 0.96f, 0.98f};  // #F2F5FA
    private static final float[] WHITE      = {1f, 1f, 1f};
    private static final float[] BLACK      = {0f, 0f, 0f};
    private static final float[] GREY_MED   = {0.4f, 0.4f, 0.4f};

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FMT2 = DateTimeFormatter.ofPattern("dd/MM/yy");

    // ── Entry point ───────────────────────────────────────────────────────

    public void export(ProjectionOutput output, Path outputPath) throws IOException {
        ProjectionHeader       header  = output.header();
        List<ProjectionResult> results = output.results();
        List<ProjectionHeader.PeriodColumn> allCols = header.getColumns();

        // Work out how many period columns fit per page segment
        float availForPeriods = CONTENT_W - FIXED_W;
        int colsPerSegment = Math.max(1, (int)(availForPeriods / PERIOD_COL_W));

        // Split period columns into segments
        List<List<ProjectionHeader.PeriodColumn>> segments = new ArrayList<>();
        for (int i = 0; i < allCols.size(); i += colsPerSegment) {
            segments.add(allCols.subList(i, Math.min(i + colsPerSegment, allCols.size())));
        }
        if (segments.isEmpty()) segments.add(new ArrayList<>());

        try (PDDocument doc = new PDDocument()) {
            PDFont fontReg  = PDType1Font.HELVETICA;
            PDFont fontBold = PDType1Font.HELVETICA_BOLD;

            int totalPages = segments.size();
            int pageNo     = 0;

            for (List<ProjectionHeader.PeriodColumn> segment : segments) {
                pageNo++;
                boolean isLastSegment = (pageNo == totalPages);

                // Calculate actual period col width to fill available space
                float periodW = segment.isEmpty() ? 0
                    : Math.min(PERIOD_COL_W, availForPeriods / segment.size());

                // How many data rows fit per page
                float usable = PAGE_H - 2 * MARGIN - HEADER_H - COL_HDR_H - FOOTER_H;
                int rowsPerPage = (int)(usable / ROW_H);

                // Paginate assets
                int assetPage = 0;
                int totalAssetPages = results.isEmpty() ? 1
                    : (int) Math.ceil((double) results.size() / rowsPerPage);

                for (int ap = 0; ap < Math.max(1, totalAssetPages); ap++) {
                    assetPage++;
                    List<ProjectionResult> pageRows = results.isEmpty()
                        ? new ArrayList<>()
                        : results.subList(
                            ap * rowsPerPage,
                            Math.min((ap + 1) * rowsPerPage, results.size()));

                    boolean showTotals = isLastSegment && (ap == totalAssetPages - 1);

                    PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
                    doc.addPage(page);

                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        float y = PAGE_H - MARGIN;

                        y = drawReportHeader(cs, fontReg, fontBold, header, y,
                            pageNo, totalPages, assetPage, totalAssetPages);
                        y = drawColumnHeaders(cs, fontBold, segment, periodW, y);
                        y = drawDataRows(cs, fontReg, fontBold, pageRows, segment,
                            periodW, y, showTotals, results, allCols);
                        drawPageFooter(cs, fontReg, y);
                    }
                }
            }

            doc.save(outputPath.toFile());
        }

        openInViewer(outputPath);
    }

    // ── Report header ─────────────────────────────────────────────────────

    private float drawReportHeader(PDPageContentStream cs, PDFont reg, PDFont bold,
            ProjectionHeader h, float y,
            int segNo, int totalSegs, int assetPage, int totalAssetPages)
            throws IOException {

        // Blue title bar
        fillRect(cs, MARGIN, y - 22, CONTENT_W, 22, BLUE_DARK);
        drawText(cs, bold, 11, WHITE, "FA Depreciation Projection Worksheet",
            MARGIN + 6, y - 15);
        String pageInfo = String.format("Page %d of %d", assetPage, totalAssetPages);
        if (totalSegs > 1) pageInfo += String.format("  |  Columns %d of %d", segNo, totalSegs);
        drawTextRight(cs, reg, 8, WHITE, pageInfo, MARGIN + CONTENT_W - 6, y - 15);
        y -= 22;

        // Parameter lines
        fillRect(cs, MARGIN, y - 44, CONTENT_W, 44, GREY_LIGHT);
        float px = MARGIN + 6;
        float py = y - 12;
        float col2 = MARGIN + CONTENT_W / 2;

        drawText(cs, reg, 7.5f, GREY_MED, "Assets", px, py);
        drawText(cs, bold, 7.5f, BLACK, fmt(h.getStartAssetNo()) + "  to  " + fmt(h.getEndAssetNo()), px + 38, py);

        drawText(cs, reg, 7.5f, GREY_MED, "Locations", col2, py);
        drawText(cs, bold, 7.5f, BLACK, fmt(h.getStartLoc()) + "  to  " + fmt(h.getEndLoc()), col2 + 44, py);
        py -= 11;

        drawText(cs, reg, 7.5f, GREY_MED, "Groups", px, py);
        drawText(cs, bold, 7.5f, BLACK, fmt(h.getStartGrp()) + "  to  " + fmt(h.getEndGrp()), px + 38, py);

        drawText(cs, reg, 7.5f, GREY_MED, "Departments", col2, py);
        drawText(cs, bold, 7.5f, BLACK, fmt(h.getStartDept()) + "  to  " + fmt(h.getEndDept()), col2 + 56, py);
        py -= 11;

        String stream = h.getTaxBookInd() == 'T' ? "Tax" : "Book";
        drawText(cs, reg, 7.5f, GREY_MED, "Stream", px, py);
        drawText(cs, bold, 7.5f, BLACK, stream, px + 38, py);

        drawText(cs, reg, 7.5f, GREY_MED, "Projected to", col2, py);
        drawText(cs, bold, 7.5f, BLACK,
            h.getDepnThruToDate() != null ? h.getDepnThruToDate().format(FMT) : "",
            col2 + 60, py);

        if (h.getProjectedRate() != null && h.getProjectedRate().compareTo(BigDecimal.ZERO) != 0) {
            py -= 11;
            drawText(cs, reg, 7.5f, GREY_MED, "Override rate", px, py);
            drawText(cs, bold, 7.5f, BLACK, h.getProjectedRate().toPlainString() + "%", px + 60, py);
        }

        y -= 44;
        return y - 4;
    }

    // ── Column headers ────────────────────────────────────────────────────

    private float drawColumnHeaders(PDPageContentStream cs, PDFont bold,
            List<ProjectionHeader.PeriodColumn> segment, float periodW, float y)
            throws IOException {

        fillRect(cs, MARGIN, y - COL_HDR_H, CONTENT_W, COL_HDR_H, BLUE_MED);

        float x = MARGIN;
        float y1 = y - 9;
        float y2 = y - 18;

        // Fixed columns
        drawTextCentered(cs, bold, 7f, WHITE, "Asset No",    x, W_ASSET,  y1);
        drawTextCentered(cs, bold, 7f, WHITE, "Description", x + W_ASSET, W_DESC, y1);
        drawTextCentered(cs, bold, 7f, WHITE, "Actual",      x + W_ASSET + W_DESC, W_COST, y1);
        drawTextCentered(cs, bold, 7f, WHITE, "Cost",        x + W_ASSET + W_DESC, W_COST, y2);
        drawTextCentered(cs, bold, 7f, WHITE, "Opening",     x + W_ASSET + W_DESC + W_COST, W_WDV, y1);
        drawTextCentered(cs, bold, 7f, WHITE, "WDV",         x + W_ASSET + W_DESC + W_COST, W_WDV, y2);

        // Period columns
        float px = MARGIN + FIXED_W;
        for (int i = 0; i < segment.size(); i++) {
            ProjectionHeader.PeriodColumn col = segment.get(i);
            String lbl = col.getLabel();
            // Split DD/MM/YY into two lines
            String[] parts = lbl.split("/");
            if (parts.length == 3) {
                drawTextCentered(cs, bold, 7f, WHITE, parts[0] + "/" + parts[1], px, periodW, y1);
                drawTextCentered(cs, bold, 7f, WHITE, "/" + parts[2], px, periodW, y2);
            } else {
                drawTextCentered(cs, bold, 7f, WHITE, lbl, px, periodW, y1);
            }
            px += periodW;
        }

        // Thin separator line
        drawLine(cs, MARGIN, y - COL_HDR_H, MARGIN + CONTENT_W, y - COL_HDR_H, BLUE_DARK, 0.5f);
        return y - COL_HDR_H;
    }

    // ── Data rows ─────────────────────────────────────────────────────────

    private float drawDataRows(PDPageContentStream cs, PDFont reg, PDFont bold,
            List<ProjectionResult> rows,
            List<ProjectionHeader.PeriodColumn> segment, float periodW,
            float y, boolean showTotals,
            List<ProjectionResult> allResults,
            List<ProjectionHeader.PeriodColumn> allCols) throws IOException {

        int colOffset = allCols.indexOf(segment.isEmpty() ? null : segment.get(0));

        for (int ri = 0; ri < rows.size(); ri++) {
            ProjectionResult r = rows.get(ri);
            float rowY = y - ROW_H;

            // Alternating row background
            if (ri % 2 == 0) fillRect(cs, MARGIN, rowY, CONTENT_W, ROW_H, GREY_LIGHT);

            float x = MARGIN;
            float ty = rowY + 4; // text baseline

            // Fixed columns
            drawTextClipped(cs, reg, 7f, BLACK, r.getAssetNo(), x + 2, ty, W_ASSET - 4);
            drawTextClipped(cs, reg, 7f, BLACK, nvl(r.getDescription()), x + W_ASSET + 2, ty, W_DESC - 4);
            drawNumRight(cs, reg, 7f, BLACK, r.getActualCost(), x + W_ASSET + W_DESC + W_COST - 2, ty);
            drawNumRight(cs, reg, 7f, BLACK, r.getOpeningWdv(), x + W_ASSET + W_DESC + W_COST + W_WDV - 2, ty);

            // Period columns for this segment
            float px = MARGIN + FIXED_W;
            BigDecimal[] depnAmt = r.getDepnAmt();
            for (int ci = 0; ci < segment.size(); ci++) {
                int absIdx = colOffset + ci;
                if (absIdx < depnAmt.length && depnAmt[absIdx] != null
                        && depnAmt[absIdx].compareTo(BigDecimal.ZERO) != 0) {
                    drawNumRight(cs, reg, 7f, BLACK, depnAmt[absIdx], px + periodW - 2, ty);
                }
                px += periodW;
            }

            // Row separator
            drawLine(cs, MARGIN, rowY, MARGIN + CONTENT_W, rowY, new float[]{0.88f,0.88f,0.88f}, 0.25f);
            y = rowY;
        }

        // Totals row
        if (showTotals && !allResults.isEmpty()) {
            y -= 2;
            fillRect(cs, MARGIN, y - ROW_H, CONTENT_W, ROW_H, BLUE_DARK);
            float ty = y - ROW_H + 4;

            drawText(cs, bold, 7f, WHITE, "TOTALS", MARGIN + 2, ty);

            // Sum opening WDV
            BigDecimal totalWdv = allResults.stream()
                .map(r -> r.getOpeningWdv() != null ? r.getOpeningWdv() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            drawNumRight(cs, bold, 7f, WHITE, totalWdv,
                MARGIN + W_ASSET + W_DESC + W_COST + W_WDV - 2, ty);

            float px = MARGIN + FIXED_W;
            int colOffset2 = allCols.indexOf(segment.isEmpty() ? null : segment.get(0));
            for (int ci = 0; ci < segment.size(); ci++) {
                int absIdx = colOffset2 + ci;
                BigDecimal colTotal = BigDecimal.ZERO;
                for (ProjectionResult r : allResults) {
                    BigDecimal[] arr = r.getDepnAmt();
                    if (absIdx < arr.length && arr[absIdx] != null)
                        colTotal = colTotal.add(arr[absIdx]);
                }
                if (colTotal.compareTo(BigDecimal.ZERO) != 0)
                    drawNumRight(cs, bold, 7f, WHITE, colTotal, px + periodW - 2, ty);
                px += periodW;
            }
            y -= ROW_H;
        }

        return y;
    }

    // ── Page footer ───────────────────────────────────────────────────────

    private void drawPageFooter(PDPageContentStream cs, PDFont reg, float y) throws IOException {
        drawLine(cs, MARGIN, MARGIN + FOOTER_H, MARGIN + CONTENT_W,
            MARGIN + FOOTER_H, GREY_MED, 0.5f);
        drawText(cs, reg, 7f, GREY_MED,
            "Landmark | FA Depreciation Projection",
            MARGIN, MARGIN + 6);
        drawTextRight(cs, reg, 7f, GREY_MED,
            "Confidential", MARGIN + CONTENT_W, MARGIN + 6);
    }

    // ── Drawing primitives ────────────────────────────────────────────────

    private void fillRect(PDPageContentStream cs, float x, float y,
            float w, float h, float[] rgb) throws IOException {
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.addRect(x, y, w, h);
        cs.fill();
        cs.setNonStrokingColor(0, 0, 0);
    }

    private void drawText(PDPageContentStream cs, PDFont font, float size,
            float[] rgb, String text, float x, float y) throws IOException {
        if (text == null || text.isBlank()) return;
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitise(text));
        cs.endText();
        cs.setNonStrokingColor(0, 0, 0);
    }

    private void drawTextRight(PDPageContentStream cs, PDFont font, float size,
            float[] rgb, String text, float rightX, float y) throws IOException {
        if (text == null || text.isBlank()) return;
        float tw = font.getStringWidth(sanitise(text)) / 1000 * size;
        drawText(cs, font, size, rgb, text, rightX - tw, y);
    }

    private void drawTextCentered(PDPageContentStream cs, PDFont font, float size,
            float[] rgb, String text, float colX, float colW, float y) throws IOException {
        if (text == null || text.isBlank()) return;
        String s = sanitise(text);
        float tw = font.getStringWidth(s) / 1000 * size;
        drawText(cs, font, size, rgb, s, colX + (colW - tw) / 2, y);
    }

    private void drawTextClipped(PDPageContentStream cs, PDFont font, float size,
            float[] rgb, String text, float x, float y, float maxW) throws IOException {
        if (text == null || text.isBlank()) return;
        String s = sanitise(text);
        // Truncate to fit
        while (s.length() > 1 && font.getStringWidth(s) / 1000 * size > maxW)
            s = s.substring(0, s.length() - 1);
        drawText(cs, font, size, rgb, s, x, y);
    }

    private void drawNumRight(PDPageContentStream cs, PDFont font, float size,
            float[] rgb, BigDecimal value, float rightX, float y) throws IOException {
        if (value == null || value.compareTo(BigDecimal.ZERO) == 0) return;
        String s = String.format("%,.2f", value);
        drawTextRight(cs, font, size, rgb, s, rightX, y);
    }

    private void drawLine(PDPageContentStream cs, float x1, float y1,
            float x2, float y2, float[] rgb, float width) throws IOException {
        cs.setStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.setLineWidth(width);
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
        cs.setStrokingColor(0, 0, 0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String fmt(String s) {
        if (s == null || s.isBlank() || s.trim().equals("~".repeat(s.trim().length())))
            return "(all)";
        return s.trim();
    }

    private String nvl(String s) { return s == null ? "" : s.trim(); }

    /** Strip non-WinAnsi characters to prevent PDFBox encoding errors. */
    private String sanitise(String s) {
        if (s == null) return "";
        return s.chars()
            .filter(c -> c >= 32 && c < 256)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    }

    private void openInViewer(Path path) {
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
}
