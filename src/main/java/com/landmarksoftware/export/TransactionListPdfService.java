package com.landmarksoftware.export;

import com.landmarksoftware.model.AssetRow;
import com.landmarksoftware.model.TransactionRow;
import com.landmarksoftware.service.TransactionListService.AssetGroup;
import com.landmarksoftware.service.TransactionListService.TransactionListOutput;
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
 * FATL02 PDF report — A4 portrait, grouped by asset.
 *
 * Layout per asset group:
 *   Asset header line:  asset_no  desc_1  desc_2  acqn_date  status
 *   Column header line: TRX TYPE  DATE  BATCH  LOCN  DEPT  GROUP  SUBGRP
 *   Per-transaction detail line(s) — format varies by type:
 *     AQ: ACQUISITN  date  batch  loc  dept  grp  subgrp  ACTUAL COST  x  BOOK COST  x
 *     BD/TD: type  date  batch  loc  dept  grp  subgrp  METHOD x CODE x FREQ x RATE x AMT x DTE x
 *     BA/TA: type  date  batch  loc  dept  grp  subgrp  ADJUSTMENT AMT  x
 *     TR: header line + TRANSFER  date  batch  from  →  to  book_cost  reval_adj  depn_prov
 *     RV/TV: type  date  batch  loc  dept  grp  subgrp  REVALUATION AMT x  ADJUSTMENT AMT x
 *     RT: header line + RETIREMENT  date  batch  loc  dept  grp  subgrp  bk_profit  tx_profit  proceeds
 *   Control break: "N TRANSACTIONS THIS ASSET"
 *   Grand total:   "N ASSETS LISTED"
 */
@Service
public class TransactionListPdfService {

    // ── Page geometry ─────────────────────────────────────────────────
    private static final float PAGE_W   = 595.27563f;
    private static final float PAGE_H   = 841.88977f;
    private static final float MARGIN   = 30f;
    private static final float CONTENT_W = PAGE_W - 2 * MARGIN;
    private static final float ROW_H    = 11f;
    private static final float HDR_H    = 52f;

    // ── Colours ───────────────────────────────────────────────────────
    private static final float[] BLUE_DARK  = {0.12f, 0.31f, 0.47f};
    private static final float[] BLUE_MED   = {0.18f, 0.46f, 0.71f};
    private static final float[] BLUE_LIGHT = {0.84f, 0.91f, 0.97f};
    private static final float[] GREY_LIGHT = {0.95f, 0.96f, 0.98f};
    private static final float[] WHITE      = {1f, 1f, 1f};
    private static final float[] BLACK      = {0f, 0f, 0f};
    private static final float[] GREY_MED   = {0.4f, 0.4f, 0.4f};
    private static final float[] AMBER      = {0.95f, 0.75f, 0.1f};

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yy");
    private static final DateTimeFormatter DATE_FMT4 = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ── Entry point ───────────────────────────────────────────────────

    public void export(TransactionListOutput output, Path outputPath) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont reg  = PDType1Font.HELVETICA;
            PDFont bold = PDType1Font.HELVETICA_BOLD;

            // Page state
            float[] yRef    = {0f};
            int[]   pageRef = {0};
            PDPageContentStream[] csRef = {null};

            // Helper to start a new page
            Runnable newPage = () -> {
                try {
                    if (csRef[0] != null) csRef[0].close();
                    PDPage pg = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
                    doc.addPage(pg);
                    pageRef[0]++;
                    csRef[0] = new PDPageContentStream(doc, pg);
                    yRef[0] = PAGE_H - MARGIN;
                    drawPageHeader(csRef[0], reg, bold, output, pageRef[0]);
                    yRef[0] -= HDR_H;
                } catch (IOException e) { throw new RuntimeException(e); }
            };

            newPage.run();

            for (AssetGroup group : output.groups()) {
                // Ensure enough room for asset header + col header + at least 2 rows
                if (yRef[0] < MARGIN + ROW_H * 4) newPage.run();

                yRef[0] = drawAssetHeader(csRef[0], reg, bold, group, yRef[0]);
                yRef[0] = drawColHeader(csRef[0], bold, yRef[0]);

                for (TransactionRow t : group.transactions()) {
                    if (yRef[0] < MARGIN + ROW_H * 2) {
                        newPage.run();
                        yRef[0] = drawColHeader(csRef[0], bold, yRef[0]);
                    }
                    yRef[0] = drawTransaction(csRef[0], reg, bold, t, yRef[0]);
                }

                // Control break line
                if (yRef[0] < MARGIN + ROW_H * 2) newPage.run();
                yRef[0] = drawControlBreak(csRef[0], reg,
                    group.transactions().size(), yRef[0]);
                yRef[0] -= 6f; // spacing between assets
            }

            // Grand total
            if (yRef[0] < MARGIN + ROW_H * 2) newPage.run();
            drawGrandTotal(csRef[0], bold, output.totalAssets(), yRef[0]);

            if (csRef[0] != null) csRef[0].close();
            doc.save(outputPath.toFile());
        }

        openFile(outputPath);
    }

    // ── Page header ───────────────────────────────────────────────────

    private void drawPageHeader(PDPageContentStream cs, PDFont reg, PDFont bold,
                                 TransactionListOutput out, int pageNo) throws IOException {
        float y = PAGE_H - MARGIN;

        // Title bar
        fillRect(cs, MARGIN, y - 18, CONTENT_W, 18, BLUE_DARK);
        drawText(cs, bold, 10, WHITE, "Landmark — Transaction List", MARGIN + 4, y - 12);
        drawTextRight(cs, reg, 8, WHITE, "Page " + pageNo, MARGIN + CONTENT_W - 4, y - 12);
        y -= 18;

        // Parameter summary
        fillRect(cs, MARGIN, y - 32, CONTENT_W, 32, GREY_LIGHT);
        var req = out.request();
        float px = MARGIN + 4, py = y - 10;
        float col2 = MARGIN + CONTENT_W / 2;

        drawText(cs, reg, 7, GREY_MED, "Assets", px, py);
        String aRange = nvl(req.getStartAssetNo()) + " to " + nvl(req.effectiveEndAssetNo());
        drawText(cs, bold, 7, BLACK, aRange, px + 36, py);

        drawText(cs, reg, 7, GREY_MED, "Location", col2, py);
        drawText(cs, bold, 7, BLACK, req.getLocCode().isBlank() ? "(all)" : req.getLocCode(),
            col2 + 44, py);
        py -= 10;

        String types = buildTypeList(req);
        drawText(cs, reg, 7, GREY_MED, "Types", px, py);
        drawText(cs, bold, 7, BLACK, types, px + 36, py);

        if (req.getStartDate() != null || req.getEndDate() != null) {
            drawText(cs, reg, 7, GREY_MED, "Dates", col2, py);
            String dates = (req.getStartDate() != null ? req.getStartDate().format(DATE_FMT4) : "(all)")
                + " to "
                + (req.getEndDate() != null ? req.getEndDate().format(DATE_FMT4) : "(all)");
            drawText(cs, bold, 7, BLACK, dates, col2 + 36, py);
        }
    }

    private String buildTypeList(com.landmarksoftware.model.TransactionListRequest req) {
        StringBuilder sb = new StringBuilder();
        if (req.isInclAcqn())        sb.append("AQ ");
        if (req.isInclBookDepn())    sb.append("BD ");
        if (req.isInclTaxDepn())     sb.append("TD ");
        if (req.isInclBookDepnAdj()) sb.append("BA ");
        if (req.isInclTaxDepnAdj())  sb.append("TA ");
        if (req.isInclTransfer())    sb.append("TR ");
        if (req.isInclBookReval())   sb.append("RV ");
        if (req.isInclTaxReval())    sb.append("TV ");
        if (req.isInclRetirement())  sb.append("RT ");
        return sb.toString().trim().isBlank() ? "(none)" : sb.toString().trim();
    }

    // ── Asset header ──────────────────────────────────────────────────

    private float drawAssetHeader(PDPageContentStream cs, PDFont reg, PDFont bold,
                                   AssetGroup group, float y) throws IOException {
        AssetRow a = group.asset();
        fillRect(cs, MARGIN, y - ROW_H, CONTENT_W, ROW_H, BLUE_MED);

        float x = MARGIN + 2;
        drawText(cs, bold, 7.5f, WHITE,
            clip(a.getAssetNo(), 20), x, y - ROW_H + 3);
        x += 110;
        drawText(cs, bold, 7.5f, WHITE,
            clip(nvl(a.getDesc1()), 35), x, y - ROW_H + 3);
        x += 190;
        drawText(cs, reg, 7, WHITE,
            fmtDate(a.getAcqnDate()), x, y - ROW_H + 3);
        x += 50;
        drawText(cs, reg, 7, AMBER,
            statusDesc(a.getAssetStatus()), x, y - ROW_H + 3);

        return y - ROW_H - 1;
    }

    // ── Column header ─────────────────────────────────────────────────

    private float drawColHeader(PDPageContentStream cs, PDFont bold, float y) throws IOException {
        fillRect(cs, MARGIN, y - ROW_H, CONTENT_W, ROW_H, BLUE_DARK);
        float x = MARGIN + 2;
        float ty = y - ROW_H + 3;
        String[] cols = {"TRX TYPE", "DATE", "BATCH", "LOCN", "DEPT", "GROUP", "SUBGRP", "DETAILS"};
        float[]  xpos = {0, 55, 90, 130, 155, 180, 210, 245};
        for (int i = 0; i < cols.length; i++)
            drawText(cs, bold, 6.5f, WHITE, cols[i], x + xpos[i], ty);
        return y - ROW_H - 1;
    }

    // ── Transaction lines ─────────────────────────────────────────────

    private float drawTransaction(PDPageContentStream cs, PDFont reg, PDFont bold,
                                   TransactionRow t, float y) throws IOException {
        float ty = y - ROW_H + 3;
        float x  = MARGIN + 2;

        // For TR (transfer), print a sub-header line first
        if ("TR".equals(t.getTrxType())) {
            fillRect(cs, MARGIN, y - ROW_H, CONTENT_W, ROW_H, GREY_LIGHT);
            drawText(cs, reg, 6.5f, GREY_MED,
                "TFR TO:  LOC    DEPT   GRP    SUBGRP         " +
                "BOOK COST       REVAL ADJ      DEPN PROV",
                x + 55, ty);
            y -= ROW_H;
            ty  = y - ROW_H + 3;
        }

        // For RT (retirement), print a sub-header line first
        if ("RT".equals(t.getTrxType())) {
            fillRect(cs, MARGIN, y - ROW_H, CONTENT_W, ROW_H, GREY_LIGHT);
            drawText(cs, reg, 6.5f, GREY_MED,
                "BOOK PROFIT         TAX PROFIT          PROCEEDS",
                x + 230, ty);
            y -= ROW_H;
            ty  = y - ROW_H + 3;
        }

        // Main detail line
        fillRect(cs, MARGIN, y - ROW_H, CONTENT_W, ROW_H,
            y % (ROW_H * 2) < ROW_H ? GREY_LIGHT : WHITE);

        // Type description
        drawText(cs, bold, 7, BLACK, t.trxTypeDesc(), x, ty);

        // Date / Batch / Loc / Dept / Grp / Subgrp
        drawText(cs, reg, 7, BLACK, fmtDate(t.getTrxDate()),   x + 55,  ty);
        drawText(cs, reg, 7, BLACK, String.valueOf(t.getBatchNo()), x + 90, ty);
        drawText(cs, reg, 7, BLACK, nvl(t.trxLoc()),           x + 130, ty);
        drawText(cs, reg, 7, BLACK, nvl(t.trxDept()),          x + 155, ty);
        drawText(cs, reg, 7, BLACK, nvl(t.trxGrp()),           x + 180, ty);
        drawText(cs, reg, 7, BLACK, nvl(t.trxSubgrp()),        x + 210, ty);

        // Type-specific details from column 245 onwards
        float dx = x + 245;
        switch (t.getTrxType() == null ? "" : t.getTrxType()) {
            case "AQ" -> {
                drawText(cs, reg, 6.5f, GREY_MED, "ACTUAL COST", dx, ty);
                drawTextRight(cs, reg, 7, BLACK, fmtAmt(t.getAcqnActualCost()), dx + 130, ty);
                drawText(cs, reg, 6.5f, GREY_MED, "BOOK COST", dx + 135, ty);
                drawTextRight(cs, reg, 7, BLACK, fmtAmt(t.getAcqnBookDepnCost()), dx + 260, ty);
            }
            case "BD", "TD" -> {
                drawText(cs, reg, 6.5f, GREY_MED, "M", dx, ty);
                drawText(cs, reg, 7, BLACK, nvl(t.getDepnMethod()), dx + 8, ty);
                drawText(cs, reg, 6.5f, GREY_MED, "CODE", dx + 22, ty);
                drawText(cs, reg, 7, BLACK, nvl(t.getDepnCode()),   dx + 42, ty);
                drawText(cs, reg, 6.5f, GREY_MED, "FREQ", dx + 62, ty);
                drawText(cs, reg, 7, BLACK, String.valueOf(t.getDepnFreq()), dx + 82, ty);
                drawText(cs, reg, 6.5f, GREY_MED, "RATE", dx + 97, ty);
                drawText(cs, reg, 7, BLACK, fmtRate(t.getDepnRate()), dx + 118, ty);
                drawText(cs, reg, 6.5f, GREY_MED, "AMT", dx + 140, ty);
                drawTextRight(cs, reg, 7, BLACK, fmtAmt(t.getDepnAmt()), dx + 210, ty);
                drawText(cs, reg, 6.5f, GREY_MED, "DTE", dx + 215, ty);
                drawText(cs, reg, 7, BLACK, fmtDate(t.getDepnThruToDate()), dx + 228, ty);
            }
            case "BA", "TA" -> {
                drawText(cs, reg, 6.5f, GREY_MED, "ADJUSTMENT AMT", dx, ty);
                drawTextRight(cs, reg, 7, BLACK, fmtAmt(t.getDepnAdjAmt()), dx + 130, ty);
            }
            case "TR" -> {
                // Transfer-to codes
                drawText(cs, reg, 7, BLACK, nvl(t.getTfrToLoc()),    dx,      ty);
                drawText(cs, reg, 7, BLACK, nvl(t.getTfrToDept()),   dx + 30, ty);
                drawText(cs, reg, 7, BLACK, nvl(t.getTfrToGrp()),    dx + 60, ty);
                drawText(cs, reg, 7, BLACK, nvl(t.getTfrToSubgrp()), dx + 90, ty);
                drawTextRight(cs, reg, 7, BLACK, fmtAmt(t.getTfrBookDepnCost()), dx + 170, ty);
                drawTextRight(cs, reg, 7, BLACK, fmtAmt(t.getTfrNetRevalAmt()),  dx + 230, ty);
                drawTextRight(cs, reg, 7, BLACK, fmtAmt(t.getTfrProvDepnAmt()),  dx + 295, ty);
            }
            case "RV", "TV" -> {
                drawText(cs, reg, 6.5f, GREY_MED, "REVAL AMT", dx, ty);
                drawTextRight(cs, reg, 7, BLACK, fmtAmt(t.getRevalVal()),    dx + 120, ty);
                drawText(cs, reg, 6.5f, GREY_MED, "ADJ AMT", dx + 125, ty);
                drawTextRight(cs, reg, 7, BLACK, fmtAmt(t.getRevalAdjAmt()), dx + 255, ty);
            }
            case "RT" -> {
                drawTextRight(cs, reg, 7, BLACK, fmtAmt(t.getRetmtBkProfitAmt()),  dx + 110, ty);
                drawTextRight(cs, reg, 7, BLACK, fmtAmt(t.getRetmtTxProfitAmt()),  dx + 210, ty);
                drawTextRight(cs, reg, 7, BLACK, fmtAmt(t.getRetmtProceedsAmt()),  dx + 295, ty);
            }
        }

        y -= ROW_H;

        // Reference line if present
        if (t.getRef() != null && !t.getRef().isBlank()) {
            drawText(cs, reg, 6.5f, GREY_MED, "Ref:", x + 55, y - ROW_H + 3);
            drawText(cs, reg, 7, BLACK, t.getRef().trim(), x + 75, y - ROW_H + 3);
            y -= ROW_H;
        }

        return y;
    }

    // ── Control break ─────────────────────────────────────────────────

    private float drawControlBreak(PDPageContentStream cs, PDFont reg,
                                    int count, float y) throws IOException {
        fillRect(cs, MARGIN, y - ROW_H, CONTENT_W, ROW_H, BLUE_LIGHT);
        String msg = count + " TRANSACTION" + (count == 1 ? "" : "S") + " THIS ASSET";
        drawText(cs, reg, 7, BLUE_MED, msg, MARGIN + 4, y - ROW_H + 3);
        drawLine(cs, MARGIN, y - ROW_H, MARGIN + CONTENT_W, y - ROW_H, GREY_MED, 0.3f);
        return y - ROW_H;
    }

    // ── Grand total ───────────────────────────────────────────────────

    private void drawGrandTotal(PDPageContentStream cs, PDFont bold,
                                 int assetCount, float y) throws IOException {
        y -= 6;
        fillRect(cs, MARGIN, y - ROW_H, CONTENT_W, ROW_H, BLUE_DARK);
        drawText(cs, bold, 8, WHITE,
            assetCount + " ASSET" + (assetCount == 1 ? "" : "S") + " LISTED",
            MARGIN + 4, y - ROW_H + 3);
    }

    // ── Drawing primitives ────────────────────────────────────────────

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
        float w = font.getStringWidth(sanitise(text)) / 1000 * size;
        drawText(cs, font, size, rgb, text, rightX - w, y);
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

    // ── Helpers ───────────────────────────────────────────────────────

    private String fmtDate(LocalDate d) {
        if (d == null || d.getYear() < 1900) return "";
        return d.format(DATE_FMT);
    }

    private String fmtAmt(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return String.format("%,.2f", v);
    }

    private String fmtRate(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return String.format("%.2f", v);
    }

    private String statusDesc(String s) {
        if (s == null || s.isBlank()) return "ACTIVE";
        return switch (s.trim()) {
            case "H" -> "ON HOLD";
            case "N" -> "NOT IN USE";
            case "R" -> "RETIRED";
            case "U" -> "UNPOSTED";
            default  -> s;
        };
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
