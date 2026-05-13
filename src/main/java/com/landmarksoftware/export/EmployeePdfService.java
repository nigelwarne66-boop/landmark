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

import com.landmarksoftware.payroll.model.Employee;
import com.landmarksoftware.payroll.model.EmployeePay;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * One-page employee record PDF — produced by the PAEM01 "Print" button.
 *
 * <p>A4 portrait, sectioned: Identity, Personal, Employment, Pay &amp; Tax,
 * Superannuation, Bank Splits. Tax File Number is masked
 * ({@code ***-***-NNN}) in print to comply with the project rule that TFNs
 * are never logged or printed in full.
 */
@Service
public class EmployeePdfService {

    // ── Page geometry (A4 portrait) ──────────────────────────────────────
    private static final float PAGE_W = 595.27563f;
    private static final float PAGE_H = 841.88977f;
    private static final float MARGIN = 36f;
    private static final float CONT_W = PAGE_W - 2 * MARGIN;
    private static final float ROW_H  = 14f;

    // ── Colours ──────────────────────────────────────────────────────────
    private static final float[] DARK_BLUE  = {0.12f, 0.31f, 0.47f};
    private static final float[] LIGHT_BLUE = {0.84f, 0.91f, 0.97f};
    private static final float[] GREY_LIGHT = {0.95f, 0.96f, 0.98f};
    private static final float[] WHITE      = {1f, 1f, 1f};
    private static final float[] BLACK      = {0f, 0f, 0f};
    private static final float[] GREY       = {0.45f, 0.45f, 0.45f};

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter STAMP_FMT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public void export(Employee emp, List<EmployeePay> splits,
                        String companyName, int companyNo,
                        Path outputPath) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont reg  = PDType1Font.HELVETICA;
            PDFont bold = PDType1Font.HELVETICA_BOLD;

            PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = PAGE_H - MARGIN;

                y = drawHeader(cs, reg, bold, companyName, companyNo, emp, y);
                y -= 10;

                y = drawSection(cs, bold, "Identity", y);
                y = drawField(cs, reg, bold, "Employee #",
                              String.valueOf(emp.employeeNo), y);
                y = drawField(cs, reg, bold, "Name",
                              join(emp.firstName, emp.secondName, emp.surname), y);
                y = drawField(cs, reg, bold, "Status",
                              describeStatus(emp.employeeStatus), y);
                y -= 6;

                y = drawSection(cs, bold, "Personal", y);
                y = drawField(cs, reg, bold, "Address",
                              joinNonEmpty(", ",
                                  emp.addr1, emp.addr2,
                                  joinNonEmpty(" ", emp.city, emp.state, emp.postcode)),
                              y);
                y = drawField(cs, reg, bold, "Phone",
                              joinPhone(emp.phoneArea, emp.phoneNo), y);
                y = drawField(cs, reg, bold, "Mobile",  safe(emp.mobile), y);
                y = drawField(cs, reg, bold, "Email",   safe(emp.emailAddress), y);
                y -= 6;

                y = drawSection(cs, bold, "Employment", y);
                y = drawField(cs, reg, bold, "Department", safe(emp.dept), y);
                y = drawField(cs, reg, bold, "Pay Group",  safe(emp.paygroup), y);
                y = drawField(cs, reg, bold, "Type",       describeType(emp.employeeType), y);
                y = drawField(cs, reg, bold, "Pay Frequency",
                              describeFreq(emp.payFreq), y);
                y = drawField(cs, reg, bold, "Date Started",
                              fmtDate(emp.dateStarted), y);
                y = drawField(cs, reg, bold, "Date Terminated",
                              fmtDate(emp.dateTerminated), y);
                y = drawField(cs, reg, bold, "Award",      safe(emp.award), y);
                y = drawField(cs, reg, bold, "Job Class",  safe(emp.jobClass), y);
                y -= 6;

                y = drawSection(cs, bold, "Pay & Tax", y);
                y = drawField(cs, reg, bold, "Annual Salary",
                              fmtDec(emp.annualSalary), y);
                y = drawField(cs, reg, bold, "Std Hours/Week",
                              emp.stdHrs == 0 ? "" : Employee.minutesAsHours(emp.stdHrs), y);
                y = drawField(cs, reg, bold, "Std Rate /hr",
                              fmtDec(emp.stdRatePerHr), y);
                y = drawField(cs, reg, bold, "Tax Scale",   safe(emp.taxScaleNo), y);
                // TFN: ALWAYS print masked — never show the full number.
                y = drawField(cs, reg, bold, "Tax File No",
                              emp.maskedTfn(), y);
                y = drawField(cs, reg, bold, "Extra Tax $",
                              fmtDec(emp.extraTaxAmt), y);
                y -= 6;

                y = drawSection(cs, bold, "Superannuation", y);
                y = drawField(cs, reg, bold, "Super Code",
                              safe(emp.superCode), y);
                y = drawField(cs, reg, bold, "Member No",
                              safe(emp.superMemberNo), y);
                y = drawField(cs, reg, bold, "Commenced",
                              fmtDate(emp.superCommDate), y);
                y = drawField(cs, reg, bold, "Qualifying Days",
                              emp.qualifyDays == 0 ? "" : String.valueOf(emp.qualifyDays), y);
                y = drawField(cs, reg, bold, "Force Payment",
                              describeYN(emp.forcePayFlag), y);
                y -= 6;

                y = drawSection(cs, bold, "Bank / EFT Splits", y);
                y = drawSplits(cs, reg, bold, splits, y);

                drawFooter(cs, reg, emp);
            }
            doc.save(outputPath.toFile());
        }
        openFile(outputPath);
    }

    // ── Sections ─────────────────────────────────────────────────────────

    private float drawHeader(PDPageContentStream cs, PDFont reg, PDFont bold,
                              String companyName, int companyNo,
                              Employee emp, float y) throws IOException {
        fillRect(cs, MARGIN, y - 22, CONT_W, 22, DARK_BLUE);
        text(cs, bold, 11, WHITE, "EMPLOYEE RECORD — PAEM01", MARGIN + 6, y - 15);
        textRight(cs, reg, 8, WHITE,
            "Printed " + LocalDateTime.now().format(STAMP_FMT),
            MARGIN + CONT_W - 6, y - 15);
        y -= 22;

        fillRect(cs, MARGIN, y - 18, CONT_W, 18, GREY_LIGHT);
        text(cs, reg,  8, GREY,  "Company",       MARGIN + 6,   y - 12);
        text(cs, bold, 8, BLACK,
             companyNo + " — " + safe(companyName), MARGIN + 56,  y - 12);
        textRight(cs, reg,  8, GREY,  "Employee",
                  MARGIN + CONT_W - 110, y - 12);
        textRight(cs, bold, 9, BLACK,
            emp.employeeNo + "  " + safe(emp.surname),
            MARGIN + CONT_W - 6, y - 12);
        return y - 18;
    }

    private float drawSection(PDPageContentStream cs, PDFont bold,
                               String title, float y) throws IOException {
        fillRect(cs, MARGIN, y - 14, CONT_W, 14, LIGHT_BLUE);
        text(cs, bold, 9, DARK_BLUE, title, MARGIN + 6, y - 10);
        return y - 14 - 2;
    }

    private float drawField(PDPageContentStream cs, PDFont reg, PDFont bold,
                             String label, String value, float y) throws IOException {
        text(cs, reg,  8, GREY,  label,           MARGIN + 6,    y - 10);
        text(cs, bold, 9, BLACK, safe(value),     MARGIN + 130,  y - 10);
        return y - ROW_H;
    }

    private float drawSplits(PDPageContentStream cs, PDFont reg, PDFont bold,
                              List<EmployeePay> splits, float y) throws IOException {
        if (splits == null || splits.isEmpty()) {
            text(cs, reg, 8, GREY, "(no pay splits on record)", MARGIN + 6, y - 10);
            return y - ROW_H;
        }
        // Column headers
        float xSeq   = MARGIN + 6;
        float xMeth  = MARGIN + 36;
        float xBsb   = MARGIN + 130;
        float xAcct  = MARGIN + 190;
        float xPayee = MARGIN + 310;
        float xAmt   = MARGIN + CONT_W - 6;

        fillRect(cs, MARGIN, y - 12, CONT_W, 12, GREY_LIGHT);
        text(cs, bold, 8, GREY, "#",       xSeq,   y - 9);
        text(cs, bold, 8, GREY, "Method",  xMeth,  y - 9);
        text(cs, bold, 8, GREY, "BSB",     xBsb,   y - 9);
        text(cs, bold, 8, GREY, "Account", xAcct,  y - 9);
        text(cs, bold, 8, GREY, "Payee",   xPayee, y - 9);
        textRight(cs, bold, 8, GREY, "Amount", xAmt, y - 9);
        y -= 12;

        for (EmployeePay p : splits) {
            text(cs, reg, 8, BLACK, String.valueOf(p.paymentNo),
                 xSeq,  y - 10);
            text(cs, reg, 8, BLACK, describeMethod(p.payMethod),
                 xMeth, y - 10);
            text(cs, reg, 8, BLACK, safe(p.tfrToBankNo),
                 xBsb,  y - 10);
            text(cs, reg, 8, BLACK, safe(p.tfrToBankAcctNo),
                 xAcct, y - 10);
            text(cs, reg, 8, BLACK, truncate(safe(p.payeeName), 28),
                 xPayee, y - 10);
            textRight(cs, reg, 8, BLACK, describeAmount(p), xAmt, y - 10);
            y -= ROW_H;
        }
        return y;
    }

    private void drawFooter(PDPageContentStream cs, PDFont reg, Employee emp)
            throws IOException {
        textRight(cs, reg, 7, GREY,
            "TFN masked for confidentiality • Landmark Payroll",
            MARGIN + CONT_W, MARGIN - 8);
    }

    // ── Lookup tables (mirror UI choice labels) ─────────────────────────

    private static String describeStatus(String code) {
        return switch (code == null ? "" : code) {
            case "A" -> "Active";
            case "I" -> "Inactive";
            case "T" -> "Terminated";
            default  -> safe(code);
        };
    }

    private static String describeType(String code) {
        return switch (code == null ? "" : code) {
            case "F" -> "Full-time";
            case "P" -> "Part-time";
            case "C" -> "Casual";
            default  -> safe(code);
        };
    }

    private static String describeFreq(String code) {
        return switch (code == null ? "" : code) {
            case "W" -> "Weekly";
            case "F" -> "Fortnightly";
            case "M" -> "Monthly";
            default  -> safe(code);
        };
    }

    private static String describeYN(String code) {
        return "Y".equalsIgnoreCase(code) ? "Yes" : "No";
    }

    private static String describeMethod(String code) {
        return switch (code == null ? "" : code) {
            case "E" -> "EFT";
            case "C" -> "Cheque";
            case "X" -> "Cash";
            case "O" -> "Other";
            default  -> safe(code);
        };
    }

    private static String describeAmount(EmployeePay p) {
        return switch (p.payCalcMethod == null ? "" : p.payCalcMethod) {
            case "B" -> "Balance";
            case "P" -> p.payAmtPerc.stripTrailingZeros().toPlainString() + " %";
            case "A" -> "$ " + p.payAmtPerc.stripTrailingZeros().toPlainString();
            default  -> "";
        };
    }

    // ── Drawing primitives ───────────────────────────────────────────────

    private static void fillRect(PDPageContentStream cs, float x, float y,
                                  float w, float h, float[] rgb) throws IOException {
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.addRect(x, y, w, h);
        cs.fill();
    }

    private static void text(PDPageContentStream cs, PDFont font, float size,
                              float[] rgb, String s, float x, float y) throws IOException {
        if (s == null || s.isEmpty()) return;
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitise(s));
        cs.endText();
    }

    private static void textRight(PDPageContentStream cs, PDFont font, float size,
                                   float[] rgb, String s, float xRight, float y) throws IOException {
        if (s == null || s.isEmpty()) return;
        String safe = sanitise(s);
        float w = font.getStringWidth(safe) / 1000f * size;
        text(cs, font, size, rgb, s, xRight - w, y);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String safe(String s) { return s == null ? "" : s; }

    private static String fmtDate(LocalDate d) {
        return d == null || d.getYear() < 1900 ? "" : d.format(DATE_FMT);
    }

    private static String fmtDec(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return v.stripTrailingZeros().toPlainString();
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p.trim());
        }
        return sb.toString();
    }

    private static String joinNonEmpty(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p.trim());
        }
        return sb.toString();
    }

    private static String joinPhone(String area, String no) {
        if ((area == null || area.isBlank()) && (no == null || no.isBlank())) return "";
        if (area == null || area.isBlank()) return safe(no);
        return "(" + area.trim() + ") " + safe(no).trim();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    /** Strip characters outside PDFBox's default WinAnsi range. */
    private static String sanitise(String s) {
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
