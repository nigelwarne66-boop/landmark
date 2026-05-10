package com.landmarksoftware.export;

import com.landmarksoftware.model.AcquiredRetiredRow;
import com.landmarksoftware.model.AcquiredRetiredRow.PooledTrxLine;
import com.landmarksoftware.service.AcquiredRetiredService.AcquiredRetiredOutput;
import com.landmarksoftware.service.AcquiredRetiredService.ReportTotals;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * FATL03 PDF report — A4 landscape.
 *
 * COBOL report is 132 columns wide — landscape gives the room needed for
 * the BOOK + TAX columns side by side.
 *
 * Per non-pooled asset:
 *   LINE 03 (BOOK row): asset_no  status  BOOK  cost  last_reval  accum_depn  last_depn_date  WDV  proceeds  P/L
 *   LINE 04 (TAX  row):           TAX   cost  accum_depn  last_depn_date  WDV  proceeds  P/L
 *   LINE 05: desc_1
 *   LINE 06: desc_2 (if non-blank)
 *   LINE 07: loc  grp  subgrp  dept
 *   LINE 08: Acquisition date: (A mode only)
 *
 * Per pooled asset (DETAIL-LINE-10/11 + DETAIL-LINE-16/17):
 *   Summary BOOK + TAX rows using WS-TOTAL-* fields
 *   Desc, loc, grp, subgrp, dept, acqn_date lines
 *   Per-transaction BOOK/TAX lines
 *
 * Grand totals (TOTAL-LINE-01/02):
 *   N ASSETS LISTED  BOOK  cost  accum_depn  WDV  proceeds  P/L
 *                    TAX   cost  accum_depn  WDV             P/L
 */
@Service
public class AcquiredRetiredPdfService {

    // ── Page geometry (A4 landscape) ──────────────────────────────────
    private static final float PAGE_W  = 841.88977f;
    private static final float PAGE_H  = 595.27563f;
    private static final float MARGIN  = 26f;
    private static final float CONT_W  = PAGE_W - 2 * MARGIN;
    private static final float ROW_H   = 10.5f;
    private static final float HDR_H   = 48f;

    // ── Colours ───────────────────────────────────────────────────────
    private static final float[] DARK_BLUE  = {0.12f, 0.31f, 0.47f};
    private static final float[] MED_BLUE   = {0.18f, 0.46f, 0.71f};
    private static final float[] LIGHT_BLUE = {0.84f, 0.91f, 0.97f};
    private static final float[] GREY_LIGHT = {0.95f, 0.96f, 0.98f};
    private static final float[] WHITE      = {1f, 1f, 1f};
    private static final float[] BLACK      = {0f, 0f, 0f};
    private static final float[] GREY       = {0.45f, 0.45f, 0.45f};
    private static final float[] AMBER      = {0.95f, 0.75f, 0.1f};

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yy");
    private static final DateTimeFormatter DATE_FMT4 = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ── Column x-positions (relative to MARGIN) ───────────────────────
    // Mirrors COBOL column layout across 132 cols mapped to ~790 pts
    private static final float C_ASSETNO  =   0f;
    private static final float C_STATUS   = 120f;
    private static final float C_STREAM   = 180f; // BOOK/TAX label
    private static final float C_COST     = 205f;
    private static final float C_REVAL    = 275f;
    private static final float C_ACCUM    = 345f;
    private static final float C_LASTD    = 415f;
    private static final float C_WDV      = 465f;
    private static final float C_PROCEEDS = 535f;
    private static final float C_PL       = 615f;

    public void export(AcquiredRetiredOutput output, Path outputPath) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont reg  = PDType1Font.HELVETICA;
            PDFont bold = PDType1Font.HELVETICA_BOLD;

            float[]   y   = {0f};
            int[]     pg  = {0};
            PDPageContentStream[] cs = {null};

            Runnable newPage = () -> {
                try {
                    if (cs[0] != null) cs[0].close();
                    PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
                    doc.addPage(page);
                    pg[0]++;
                    cs[0] = new PDPageContentStream(doc, page);
                    y[0]  = PAGE_H - MARGIN;
                    drawPageHeader(cs[0], reg, bold, output, pg[0]);
                    y[0] -= HDR_H;
                    drawColumnHeaders(cs[0], bold, y[0]);
                    y[0] -= ROW_H + 1;
                } catch (IOException e) { throw new RuntimeException(e); }
            };

            newPage.run();

            for (AcquiredRetiredRow r : output.rows()) {
                // Estimate lines needed for this asset
                int linesNeeded = r.isPooledAsset()
                    ? 4 + r.getPooledTrxs().size() * 2
                    : 6;
                if (y[0] < MARGIN + linesNeeded * ROW_H) newPage.run();

                if (r.isPooledAsset()) {
                    y[0] = drawPooledAsset(cs[0], reg, bold, r, output.request().isAcquisitions(), y[0]);
                } else {
                    y[0] = drawNonPooledAsset(cs[0], reg, bold, r, output.request().isAcquisitions(), y[0]);
                }
                y[0] -= 2; // inter-asset spacing
            }

            // Grand totals
            if (y[0] < MARGIN + ROW_H * 4) newPage.run();
            drawGrandTotals(cs[0], reg, bold, output.totals(), y[0]);

            if (cs[0] != null) cs[0].close();
            doc.save(outputPath.toFile());
        }
        openFile(outputPath);
    }

    // ── Page header ───────────────────────────────────────────────────

    private void drawPageHeader(PDPageContentStream cs, PDFont reg, PDFont bold,
                                 AcquiredRetiredOutput out, int pageNo) throws IOException {
        float y = PAGE_H - MARGIN;

        // Title bar
        fillRect(cs, MARGIN, y - 18, CONT_W, 18, DARK_BLUE);
        text(cs, bold, 10, WHITE,
            "A S S E T S   A C Q U I R E D   A N D   R E T I R E D",
            MARGIN + 4, y - 12);
        textRight(cs, reg, 8, WHITE, "Page " + pageNo, MARGIN + CONT_W - 4, y - 12);
        y -= 18;

        // Parameter summary bar
        fillRect(cs, MARGIN, y - 28, CONT_W, 28, GREY_LIGHT);
        var req = out.request();
        float px = MARGIN + 4, py = y - 10;
        float col2 = MARGIN + CONT_W / 2;

        text(cs, reg, 7, GREY,  "Report",    px,       py);
        text(cs, bold, 7, BLACK, req.acqnRetmtLiteral(), px + 36, py);

        if (req.getStartDate() != null || req.getEndDate() != null) {
            text(cs, reg, 7, GREY,  "Period", col2,      py);
            String period = fmt4(req.getStartDate()) + " to " + fmt4(req.getEndDate());
            text(cs, bold, 7, BLACK, period, col2 + 36, py);
        }
        py -= 9;

        text(cs, reg, 7, GREY, "Assets", px, py);
        text(cs, bold, 7, BLACK,
            nvl(req.getStartAssetNo()) + " to " + req.effectiveEndAssetNo().replace("~","z"),
            px + 36, py);
        if (!req.getLocCode().isBlank()) {
            text(cs, reg, 7, GREY, "Location", col2, py);
            text(cs, bold, 7, BLACK, req.getLocCode(), col2 + 44, py);
        }
    }

    // ── Column headers ────────────────────────────────────────────────

    private void drawColumnHeaders(PDPageContentStream cs, PDFont bold, float y) throws IOException {
        // Two-line header matching COBOL HEADER-1 / HEADER-2
        fillRect(cs, MARGIN, y - ROW_H * 2, CONT_W, ROW_H * 2, DARK_BLUE);
        float x = MARGIN, ty = y - ROW_H + 3;

        // Header line 1
        text(cs, bold, 6, WHITE, "ASSET NO",              x + C_ASSETNO,  ty);
        text(cs, bold, 6, WHITE, "STATUS",                x + C_STATUS,   ty);
        text(cs, bold, 6, WHITE, "OPENING COST",          x + C_COST,     ty);
        text(cs, bold, 6, WHITE, "LAST REVAL",            x + C_REVAL,    ty);
        text(cs, bold, 6, WHITE, "ACCUM DEPN",            x + C_ACCUM,    ty);
        text(cs, bold, 6, WHITE, "LAST DEPN",             x + C_LASTD,    ty);
        text(cs, bold, 6, WHITE, "WRITTEN DOWN",          x + C_WDV,      ty);
        text(cs, bold, 6, WHITE, "PROCEEDS",              x + C_PROCEEDS, ty);
        text(cs, bold, 6, WHITE, "PROFIT/LOSS",           x + C_PL,       ty);
        ty -= ROW_H;
        // Header line 2
        text(cs, bold, 6, WHITE, "LOC  GRP  SUBGRP DEPT", x + C_STATUS + 20, ty);
        text(cs, bold, 6, WHITE, "REVALUATION",           x + C_REVAL,    ty);
        text(cs, bold, 6, WHITE, "DEPREC'N",              x + C_ACCUM,    ty);
        text(cs, bold, 6, WHITE, "DATE",                  x + C_LASTD,    ty);
        text(cs, bold, 6, WHITE, "VALUE",                 x + C_WDV,      ty);
        text(cs, bold, 6, WHITE, "DISPOSAL",              x + C_PROCEEDS, ty);
        text(cs, bold, 6, WHITE, "ON DISPOSAL",           x + C_PL,       ty);
    }

    // ── Non-pooled asset ──────────────────────────────────────────────

    private float drawNonPooledAsset(PDPageContentStream cs, PDFont reg, PDFont bold,
                                      AcquiredRetiredRow r, boolean isAcqn, float y) throws IOException {
        float x = MARGIN;

        // LINE 03 — BOOK row
        y -= ROW_H;
        fillRect(cs, x, y, CONT_W, ROW_H, r.isPooledAsset() ? MED_BLUE : GREY_LIGHT);
        float ty = y + 2;
        text(cs, bold, 7, BLACK, clip(r.getAssetNo(), 20), x + C_ASSETNO, ty);
        text(cs, reg,  6, GREY,  r.assetStatusDesc(),      x + C_STATUS,  ty);
        text(cs, bold, 6.5f, MED_BLUE, "BOOK",             x + C_STREAM,  ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getBookDepnCost()),          x + C_REVAL - 2,    ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getLastRevalVal()),          x + C_ACCUM - 2,    ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.totalAccumBookDepnForDisplay()), x + C_LASTD - 2, ty);
        text(cs,  reg, 7, BLACK, fmt(r.getLastBookDepnDate()),             x + C_LASTD,        ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getBookWrittenDownVal()),    x + C_PROCEEDS - 2, ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getRetmtProceedsVal()),      x + C_PL - 2,       ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getBookDisposalProfitLoss()), x + C_PL + 65,     ty);

        // LINE 04 — TAX row
        y -= ROW_H;
        ty = y + 2;
        text(cs, bold, 6.5f, MED_BLUE, "TAX",             x + C_STREAM,  ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getTaxDepnCost()),           x + C_REVAL - 2,    ty);
        BigDecimal accumTax = r.getAccumTaxDepn().add(r.getAccumTaxDepnAdj());
        textRight(cs, reg, 7, BLACK, fmtAmt(accumTax),                    x + C_LASTD - 2,    ty);
        text(cs,  reg, 7, BLACK, fmt(r.getLastTaxDepnDate()),              x + C_LASTD,        ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getTaxWrittenDownVal()),     x + C_PROCEEDS - 2, ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getRetmtProceedsVal()),      x + C_PL - 2,       ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getTaxDisposalProfitLoss()), x + C_PL + 65,      ty);

        // LINE 05 — desc_1
        y -= ROW_H; ty = y + 2;
        text(cs, reg, 7, BLACK, clip(r.getDesc1(), 40), x + C_STATUS, ty);

        // LINE 06 — desc_2 (if present)
        if (r.getDesc2() != null && !r.getDesc2().isBlank()) {
            y -= ROW_H; ty = y + 2;
            text(cs, reg, 7, GREY, clip(r.getDesc2(), 40), x + C_STATUS, ty);
        }

        // LINE 07 — loc grp subgrp dept
        y -= ROW_H; ty = y + 2;
        text(cs, reg, 7, GREY, nvl(r.getLocCode()),    x + C_STATUS,       ty);
        text(cs, reg, 7, GREY, nvl(r.getGrpCode()),    x + C_STATUS + 40,  ty);
        text(cs, reg, 7, GREY, nvl(r.getSubgrpCode()), x + C_STATUS + 82,  ty);
        text(cs, reg, 7, GREY, nvl(r.getDeptCode()),   x + C_STATUS + 124, ty);

        // LINE 08 — Acquisition date (A mode only)
        if (isAcqn && r.getAcqnDate() != null) {
            y -= ROW_H; ty = y + 2;
            text(cs, reg, 7, GREY, "Acquisition date:", x + C_STATUS, ty);
            text(cs, reg, 7, BLACK, fmt(r.getAcqnDate()), x + C_STATUS + 90, ty);
        }

        return y;
    }

    // ── Pooled asset ──────────────────────────────────────────────────

    private float drawPooledAsset(PDPageContentStream cs, PDFont reg, PDFont bold,
                                   AcquiredRetiredRow r, boolean isAcqn, float y) throws IOException {
        float x = MARGIN;

        // DETAIL-LINE-10 (BOOK pooled summary)
        y -= ROW_H;
        fillRect(cs, x, y, CONT_W, ROW_H, LIGHT_BLUE);
        float ty = y + 2;
        text(cs, bold, 7, BLACK, clip(r.getAssetNo(), 20), x + C_ASSETNO, ty);
        text(cs, reg,  6, GREY,  r.assetStatusDesc(),      x + C_STATUS,  ty);
        text(cs, bold, 6.5f, DARK_BLUE, "BOOK",            x + C_STREAM,  ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getTotalBookDepnCost()),     x + C_REVAL - 2,    ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getTotalAccumBookDepn()),    x + C_LASTD - 2,    ty);
        text(cs, reg,  7, BLACK, fmt(r.getLastBookDepnDate()),             x + C_LASTD,        ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getBookDepnCost()),          x + C_PROCEEDS - 2, ty); // pool balance = WDV col
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getTotalRetmtProceeds()),    x + C_PL - 2,       ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getBookDisposalProfitLoss()), x + C_PL + 65,     ty);

        // DETAIL-LINE-11 (TAX pooled summary)
        y -= ROW_H; ty = y + 2;
        text(cs, bold, 6.5f, DARK_BLUE, "TAX",             x + C_STREAM,  ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getTotalTaxDepnCost()),      x + C_REVAL - 2,    ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getTotalAccumTaxDepn()),     x + C_LASTD - 2,    ty);
        text(cs, reg,  7, BLACK, fmt(r.getLastTaxDepnDate()),              x + C_LASTD,        ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getTaxDepnCost()),           x + C_PROCEEDS - 2, ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getTotalRetmtProceeds()),    x + C_PL - 2,       ty);
        textRight(cs, reg, 7, BLACK, fmtAmt(r.getTaxDisposalProfitLoss()), x + C_PL + 65,      ty);

        // desc / loc / acqn — same as non-pooled (DETAIL-12/13/14/15)
        y -= ROW_H; ty = y + 2;
        text(cs, reg, 7, BLACK, clip(r.getDesc1(), 40), x + C_STATUS, ty);
        if (r.getDesc2() != null && !r.getDesc2().isBlank()) {
            y -= ROW_H; ty = y + 2;
            text(cs, reg, 7, GREY, clip(r.getDesc2(), 40), x + C_STATUS, ty);
        }
        y -= ROW_H; ty = y + 2;
        text(cs, reg, 7, GREY, nvl(r.getLocCode()),    x + C_STATUS,       ty);
        text(cs, reg, 7, GREY, nvl(r.getGrpCode()),    x + C_STATUS + 40,  ty);
        text(cs, reg, 7, GREY, nvl(r.getSubgrpCode()), x + C_STATUS + 82,  ty);
        text(cs, reg, 7, GREY, nvl(r.getDeptCode()),   x + C_STATUS + 124, ty);

        if (isAcqn && r.getAcqnDate() != null) {
            y -= ROW_H; ty = y + 2;
            text(cs, reg, 7, GREY,  "Acquisition date:", x + C_STATUS, ty);
            text(cs, reg, 7, BLACK, fmt(r.getAcqnDate()), x + C_STATUS + 90, ty);
        }

        // Per-transaction lines (DETAIL-LINE-16/17)
        for (PooledTrxLine trx : r.getPooledTrxs()) {
            y -= ROW_H; ty = y + 2;
            // BOOK line (16)
            text(cs, bold, 6, MED_BLUE, "BOOK", x + C_STREAM, ty);
            textRight(cs, reg, 7, BLACK, fmtAmt(trx.bookDepnCost()),      x + C_REVAL - 2,    ty);
            textRight(cs, reg, 7, BLACK, fmtAmt(trx.retmtProceeds()),     x + C_PL - 2,       ty);
            textRight(cs, reg, 7, BLACK, fmtAmt(trx.bookDisposalProfit()), x + C_PL + 65,     ty);
            y -= ROW_H; ty = y + 2;
            // TAX line (17)
            text(cs, bold, 6, MED_BLUE, "TAX",  x + C_STREAM, ty);
            textRight(cs, reg, 7, BLACK, fmtAmt(trx.taxDepnCost()),       x + C_REVAL - 2,    ty);
            textRight(cs, reg, 7, BLACK, fmtAmt(trx.taxDisposalProfit()), x + C_PL + 65,      ty);
        }

        return y;
    }

    // ── Grand totals ──────────────────────────────────────────────────

    private void drawGrandTotals(PDPageContentStream cs, PDFont reg, PDFont bold,
                                  ReportTotals t, float y) throws IOException {
        y -= ROW_H * 2; // two blank lines
        float x = MARGIN, ty = y + 2;
        fillRect(cs, x, y, CONT_W, ROW_H, DARK_BLUE);

        // TOTAL-LINE-01 BOOK
        text(cs, bold, 7, WHITE, t.assetCount() + " ASSETS LISTED", x + C_ASSETNO, ty);
        text(cs, bold, 7, AMBER, "BOOK", x + C_STREAM, ty);
        textRight(cs, bold, 7, WHITE, fmtAmt(t.totalBookCost()),      x + C_REVAL - 2,    ty);
        textRight(cs, bold, 7, WHITE, fmtAmt(t.totalAccumBookDepn()), x + C_LASTD - 2,    ty);
        textRight(cs, bold, 7, WHITE, fmtAmt(t.totalBookWdv()),       x + C_PROCEEDS - 2, ty);
        textRight(cs, bold, 7, WHITE, fmtAmt(t.totalProceeds()),      x + C_PL - 2,       ty);
        textRight(cs, bold, 7, WHITE, fmtAmt(t.totalBookProfit()),    x + C_PL + 65,      ty);

        // TOTAL-LINE-02 TAX
        y -= ROW_H; ty = y + 2;
        fillRect(cs, x, y, CONT_W, ROW_H, DARK_BLUE);
        text(cs, bold, 7, AMBER, "TAX",  x + C_STREAM, ty);
        textRight(cs, bold, 7, WHITE, fmtAmt(t.totalTaxCost()),      x + C_REVAL - 2,    ty);
        textRight(cs, bold, 7, WHITE, fmtAmt(t.totalAccumTaxDepn()), x + C_LASTD - 2,    ty);
        textRight(cs, bold, 7, WHITE, fmtAmt(t.totalTaxWdv()),       x + C_PROCEEDS - 2, ty);
        textRight(cs, bold, 7, WHITE, fmtAmt(t.totalTaxProfit()),    x + C_PL + 65,      ty);
    }

    // ── Drawing primitives ────────────────────────────────────────────

    private void fillRect(PDPageContentStream cs, float x, float y,
                           float w, float h, float[] rgb) throws IOException {
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.addRect(x, y, w, h);
        cs.fill();
        cs.setNonStrokingColor(0, 0, 0);
    }

    private void text(PDPageContentStream cs, PDFont font, float size,
                       float[] rgb, String val, float x, float y) throws IOException {
        if (val == null || val.isBlank()) return;
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitise(val));
        cs.endText();
        cs.setNonStrokingColor(0, 0, 0);
    }

    private void textRight(PDPageContentStream cs, PDFont font, float size,
                            float[] rgb, String val, float rightX, float y) throws IOException {
        if (val == null || val.isBlank()) return;
        float w = font.getStringWidth(sanitise(val)) / 1000 * size;
        text(cs, font, size, rgb, val, rightX - w, y);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private String fmt(LocalDate d)  {
        if (d == null || d.getYear() < 1900) return "";
        return d.format(DATE_FMT);
    }

    private String fmt4(LocalDate d) {
        if (d == null) return "(all)";
        return d.format(DATE_FMT4);
    }

    private String fmtAmt(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return String.format("%,.2f", v);
    }

    private String clip(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String nvl(String s) { return s == null ? "" : s.trim(); }

    private String sanitise(String s) {
        if (s == null) return "";
        return s.chars()
            .filter(c -> c >= 32 && c < 256)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    }

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
