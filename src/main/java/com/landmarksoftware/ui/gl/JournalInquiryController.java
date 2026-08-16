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
package com.landmarksoftware.ui.gl;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.model.GlJournalHeader;
import com.landmarksoftware.model.GlJournalLine;
import com.landmarksoftware.report.JasperReportService;
import com.landmarksoftware.service.CpCntrlService;
import com.landmarksoftware.service.gl.GlJournalService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRField;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * GLGN02 + GLGN08 — Journal Inquiry, combined.
 *
 * <p>GLGN02 ("Journal Inquiry") browses {@code glgnhed} by financial year,
 * journal-no range and an unposted-only toggle (its S1 screen — the S0C is
 * an empty stub). GLGN08 ("Journal Register") is a print-only step in the
 * glgn06→07→08→09 posting pipeline with no filter fields of its own — its
 * useful selection criteria (date range, user-id range) actually live on
 * glgn06's S0. This screen folds all of that into one search: journal type,
 * financial year, journal-no range, date range, user-id range, and a
 * draft/committed/cancelled/expired status filter (replacing GLGN02's
 * blunter Y/N unposted-only toggle).
 *
 * <p>The Reverse button has no COBOL original — GLGN08 never writes
 * anything (see class doc above), and the only "reversal" concept in COBOL
 * is auto-reverse-at-posting-time on a standing journal (unrelated). This
 * one creates a new draft General journal with every line's debit/credit
 * swapped from a committed journal, for the user to review and commit.
 *
 * <p>PDF/Excel export reuses the exact {@code gl/general-journal(-excel)}
 * jrxml that GLTL06 ("General Journal") uses in the Reports Hub, fed from
 * the on-screen search results (draft/cancelled/expired included, not just
 * gltrx-posted like GLTL06) instead of a fresh query. It calls
 * {@link JasperReportService} directly rather than going through
 * {@code ReportsHubController} — that controller field-autowires
 * {@code MainMenuController}, which constructor-injects this class, so
 * routing through it would be a circular Spring dependency.
 */
@Component
public class JournalInquiryController {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final GlJournalService svc;
    private final AppSession       session;
    private final JasperReportService jasper;
    private final CpCntrlService      cpCntrl;

    private final ObservableList<GlJournalHeader> rows = FXCollections.observableArrayList();
    private TableView<GlJournalHeader> table;
    private Label lblStatus;

    private ChoiceBox<String> cbSource;
    private ChoiceBox<String> cbStatus;
    private TextField fYear, fStartJnl, fEndJnl, fStartDate, fEndDate, fStartUser, fEndUser;

    /**
     * gltrx.source domain — verbatim from GLTI08 ("Source Cross Reference
     * Inquiry")'s WS-TABLE-GRP / F5 pick-list (glti08.ws), not just the
     * GN/ST/CT this screen's own glgnhed search can filter on. Journal
     * Inquiry only ever browses glgnhed (General "GN" / Standing "ST"
     * templates), so any other source picked here is honoured on the
     * dropdown for parity with GLTI08 but explained rather than silently
     * returning zero rows — see {@link #runSearch()}.
     */
    private static final String[][] SOURCES = {
        {"AP", "Accts Payable Journals"},
        {"AR", "Accts Receivable Journals"},
        {"CM", "Cash Management Journals"},
        {"CL", "Cost Ledger Journals"},
        {"FA", "Fixed Assets Journals"},
        {"GN", "GL General Journals"},
        {"ST", "GL Standing Journals"},
        {"SS", "GL Statistical Journals"},
        {"IC", "GL Intercompany Journals"},
        {"SC", "GL Standard Cost Journals"},
        {"BA", "BAS Journals"},
        {"PA", "Payroll Journals"},
        {"PM", "Production Management Journals"},
        {"SM", "Inventory Management Journals"},
        {"RE", "Retail POS Journals"},
        {"SV", "Service Management Journals"},
        {"BL", "Business Analysis Ledger Journals"},
        {"TM", "Time Recording Journals"},
        {"CJ", "Closing Journals"},
        {"FC", "Currency Revaluation Journals"},
        {"CC", "Company Consolidation Jnls"},
        {"PO", "PO Amend/Cancellation Jnls"},
    };
    private static final String ALL_SOURCES_LABEL = "(All sources)";

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "glgn02-thread");
        t.setDaemon(true);
        return t;
    });

    public JournalInquiryController(GlJournalService svc, AppSession session,
                                    JasperReportService jasper, CpCntrlService cpCntrl) {
        this.svc = svc;
        this.session = session;
        this.jasper = jasper;
        this.cpCntrl = cpCntrl;
    }

    // ── Entry point ───────────────────────────────────────────────────────

    public Scene buildScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildHeader());
        root.setCenter(buildContent(stage));
        root.setBottom(buildStatusBar());
        runSearch();
        Scene scene = new Scene(root, 1000, 620);
        scene.getStylesheets().add(getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    private HBox buildHeader() {
        Label t = new Label("Journal Inquiry");
        t.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("GLGN02 · GLGN08  ·  " + session.getCompanyName() + "  ·  FY " + session.getYrNo());
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        HBox bar = new HBox(new VBox(2, t, sub));
        bar.setPadding(new Insets(14, 20, 14, 20));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color:#FFFFFF;-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;-fx-border-width:0 0 0.5 0;");
        return bar;
    }

    private VBox buildContent(Stage stage) {
        List<String> sourceLabels = new ArrayList<>();
        sourceLabels.add(ALL_SOURCES_LABEL);
        for (String[] s : SOURCES) sourceLabels.add(s[0] + " — " + s[1]);
        cbSource = new ChoiceBox<>(FXCollections.observableArrayList(sourceLabels));
        cbSource.setValue(ALL_SOURCES_LABEL);
        cbSource.setPrefWidth(260);
        cbStatus = new ChoiceBox<>(FXCollections.observableArrayList("All", "Draft", "Committed", "Cancelled", "Expired"));
        cbStatus.setValue("All");
        fYear      = tf(String.valueOf(session.getYrNo()), 4);
        fStartJnl  = tf("", 8);
        fEndJnl    = tf("", 8);
        fStartDate = tf("", 10);
        fEndDate   = tf("", 10);
        fStartUser = tf("", 10);
        fEndUser   = tf("", 10);

        Button btnSearch = btnPrimary("🔍 Search");
        btnSearch.setDefaultButton(true);
        btnSearch.setOnAction(e -> runSearch());

        GridPane filters = new GridPane();
        filters.setHgap(10); filters.setVgap(8); filters.setPadding(new Insets(12, 16, 8, 16));
        int c = 0;
        filters.add(lbl("Source:"), c++, 0);   filters.add(cbSource, c++, 0);
        filters.add(lbl("Year:"), c++, 0);     filters.add(fYear, c++, 0);
        filters.add(lbl("Status:"), c++, 0);   filters.add(cbStatus, c++, 0);
        filters.add(btnSearch, c, 0);
        c = 0;
        filters.add(lbl("Jnl No:"), c++, 1);   filters.add(new HBox(4, fStartJnl, new Label("to"), fEndJnl), c++, 1);
        filters.add(lbl("Date:"), c++, 1);     filters.add(new HBox(4, fStartDate, new Label("to"), fEndDate), c++, 1);
        filters.add(lbl("User:"), c++, 1);     filters.add(new HBox(4, fStartUser, new Label("to"), fEndUser), c++, 1);

        table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No journals match this search."));
        table.getColumns().addAll(List.of(
            col("Jnl No",   h -> String.valueOf(h.jnlNo),        70),
            col("Date",     h -> h.jnlDate == null ? "" : h.jnlDate.format(DMY), 90),
            col("Notation", h -> h.note1,                        260),
            col("Debit",    h -> money(h.totalDr),               110),
            col("Credit",   h -> money(h.totalCr),               110),
            col("Status",   this::statusLabel,                    90),
            col("User ID",  h -> h.auditUserId,                  100)
        ));
        VBox.setVgrow(table, Priority.ALWAYS);
        table.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && sel() != null) openViewer(sel(), stage); });

        Button btnView    = btnSecondary("👁 View");
        Button btnReverse = btnDanger("↺ Reverse…");
        Button btnPdf     = btnSecondary("📄 PDF");
        Button btnExcel   = btnSecondary("📊 Excel");
        btnView.setOnAction(e -> { if (sel() != null) openViewer(sel(), stage); else info("View", "Select a journal."); });
        btnReverse.setOnAction(e -> { if (sel() != null) doReverse(sel(), stage); else info("Reverse", "Select a journal."); });
        btnPdf.setTooltip(new Tooltip("Export the current search results to PDF"));
        btnExcel.setTooltip(new Tooltip("Export the current search results to Excel"));
        btnPdf.setOnAction(e -> exportReport("pdf", stage));
        btnExcel.setOnAction(e -> exportReport("excel", stage));

        HBox toolbar = new HBox(8, btnView, btnReverse, new Separator(Orientation.VERTICAL), btnPdf, btnExcel);
        toolbar.setPadding(new Insets(0, 16, 10, 16));
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox filterCard = new VBox(filters);
        filterCard.setStyle("-fx-background-color:#F8F8F6;-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;-fx-border-width:0 0 0.5 0;");

        return new VBox(0, filterCard, toolbar, table);
    }

    private GlJournalHeader sel() { return table.getSelectionModel().getSelectedItem(); }

    /** Source dropdown label ("GN — GL General Journals") → its 2-letter gltrx.source code, or "" for "(All sources)". */
    private static String sourceCode(String label) {
        if (label == null || ALL_SOURCES_LABEL.equals(label)) return "";
        int dash = label.indexOf(" — ");
        return dash > 0 ? label.substring(0, dash) : label;
    }
    /** gltrx.source code → glgnhed.jnl_type, for the two sources Journal Inquiry can actually search (GN/ST); null otherwise. */
    private static String jnlTypeForSource(String sourceCode) {
        return switch (sourceCode) {
            case "GN" -> "G";
            case "ST" -> "S";
            default   -> null;
        };
    }
    /** glgnhed.jnl_type → display name, for dialog titles and messages. */
    private static String typeName(String jnlType) {
        return switch (jnlType == null ? "" : jnlType) {
            case "S" -> "Standing";
            case "C" -> "Cash";
            default  -> "General";
        };
    }

    private String statusLabel(GlJournalHeader h) {
        String s = h.jnlStatus == null ? " " : h.jnlStatus.trim();
        return switch (s) {
            case "C" -> "Cancelled";
            case "X" -> "Expired";
            case "P" -> "Committed";
            case "U" -> "In use";
            case ""  -> h.isBalanced() ? "Draft" : "Draft — Unbalanced";
            default  -> s;
        };
    }

    // ── Search ─────────────────────────────────────────────────────────────

    private void runSearch() {
        Integer yr = parseInt(fYear.getText());
        if (yr == null) { markError(fYear, "Enter a financial year."); return; }
        Integer sj = parseInt(fStartJnl.getText());
        Integer ej = parseInt(fEndJnl.getText());
        LocalDate sd = null, ed = null;
        if (!fStartDate.getText().isBlank()) {
            sd = parseDate(fStartDate.getText());
            if (sd == null) { markError(fStartDate, "Enter a valid date (dd/MM/yyyy) or leave blank."); return; }
        }
        if (!fEndDate.getText().isBlank()) {
            ed = parseDate(fEndDate.getText());
            if (ed == null) { markError(fEndDate, "Enter a valid date (dd/MM/yyyy) or leave blank."); return; }
        }
        String code = sourceCode(cbSource.getValue());
        List<String> jnlTypes;
        if (code.isEmpty()) {
            jnlTypes = List.of("G", "S");   // "(All sources)" — every jnl_type Journal Inquiry can show
        } else {
            String jt = jnlTypeForSource(code);
            if (jt == null) {
                rows.clear();
                info("Search", cbSource.getValue() + " transactions post straight to the ledger and have no "
                    + "glgnhed journal entries for this screen to show — Journal Inquiry only covers General (GN) "
                    + "and Standing (ST) journals. Try the Reports Hub's Account Transactions or General Journal "
                    + "report for " + code + " transactions.");
                status("0 journal(s)", false);
                return;
            }
            jnlTypes = List.of(jt);
        }

        int co = session.getCompanyNo();
        String su = fStartUser.getText(), eu = fEndUser.getText();
        String status = switch (cbStatus.getValue()) {
            case "Draft"     -> "DRAFT";
            case "Committed" -> "COMMITTED";
            case "Cancelled" -> "CANCELLED";
            case "Expired"   -> "EXPIRED";
            default          -> "ALL";
        };
        final LocalDate startDate = sd, endDate = ed;
        status("Searching…", false);
        exec.submit(() -> {
            try {
                List<GlJournalHeader> data = new ArrayList<>();
                for (String jt : jnlTypes)
                    data.addAll(svc.searchInquiry(co, jt, yr, sj, ej, startDate, endDate, su, eu, status));
                if (jnlTypes.size() > 1)
                    data.sort(java.util.Comparator.<GlJournalHeader, LocalDate>comparing(h -> h.jnlDate, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                        .thenComparing(h -> h.jnlNo, java.util.Comparator.reverseOrder()));
                List<GlJournalHeader> finalData = data;
                Platform.runLater(() -> { rows.setAll(finalData); status(finalData.size() + " journal(s)", false); });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Search error: " + ex.getMessage(), true));
            }
        });
    }

    // ── View (read-only header + lines) ───────────────────────────────────

    private void openViewer(GlJournalHeader h, Window owner) {
        status("Loading lines…", false);
        exec.submit(() -> {
            try {
                List<GlJournalLine> lines = svc.loadLines(session.getCompanyNo(), h.jnlType, h.yrNo, h.jnlNo);
                Platform.runLater(() -> { showViewerDialog(h, lines, owner); status("", false); });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    private void showViewerDialog(GlJournalHeader h, List<GlJournalLine> lines, Window owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(typeName(h.jnlType) + " Journal #" + h.jnlNo + " — " + statusLabel(h));

        GridPane info = new GridPane();
        info.setHgap(10); info.setVgap(6); info.setPadding(new Insets(16, 16, 8, 16));
        int r = 0;
        info.add(lbl("Date:"), 0, r);       info.add(new Label(h.jnlDate == null ? "" : h.jnlDate.format(DMY)), 1, r++);
        info.add(lbl("Status:"), 0, r);     info.add(new Label(statusLabel(h)), 1, r++);
        info.add(lbl("Notation 1:"), 0, r); info.add(new Label(safe(h.note1)), 1, r++);
        if (!safe(h.note2).isBlank()) { info.add(lbl("Notation 2:"), 0, r); info.add(new Label(h.note2), 1, r++); }
        info.add(lbl("User:"), 0, r);       info.add(new Label(safe(h.auditUserId)), 1, r++);
        info.add(lbl("Debit / Credit:"), 0, r); info.add(new Label(money(h.totalDr) + "   /   " + money(h.totalCr)), 1, r++);

        TableView<GlJournalLine> lineTable = new TableView<>(FXCollections.observableArrayList(lines));
        lineTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        lineTable.setPlaceholder(new Label("No lines."));
        lineTable.getColumns().addAll(List.of(
            lcol("#",           l -> String.valueOf(l.lineNo), 45),
            lcol("Account",     GlJournalLine::accountDisplay, 90),
            lcol("Description", l -> l.acctDesc,                220),
            lcol("Debit",       l -> money(l.drAmt),            110),
            lcol("Credit",      l -> money(l.crAmt),            110),
            lcol("Reference",   l -> l.ref,                     180)
        ));
        VBox.setVgrow(lineTable, Priority.ALWAYS);

        Button btnClose = btnSecondary("Close");
        btnClose.setDefaultButton(true);
        btnClose.setOnAction(e -> dlg.close());
        HBox bar = new HBox(btnClose);
        bar.setAlignment(Pos.CENTER_RIGHT); bar.setPadding(new Insets(10, 16, 14, 16));

        BorderPane root = new BorderPane(new VBox(0, info, sep(), lineTable));
        root.setBottom(bar);
        dlg.setScene(new Scene(root, 700, 540));
        dlg.showAndWait();
    }

    // ── Reverse ────────────────────────────────────────────────────────────

    private void doReverse(GlJournalHeader h, Window owner) {
        if (!"G".equals(h.jnlType)) {
            info("Reverse", typeName(h.jnlType) + " journals can't be reversed directly — only committed "
                + "General journals can. Reverse the resulting ledger entries with a General journal instead, "
                + "or expire/edit the template.");
            return;
        }
        if (!"P".equals(trim(h.jnlStatus))) {
            info("Reverse", "Only committed journals can be reversed. Journal " + h.jnlNo
                + " is " + statusLabel(h).toLowerCase() + ".");
            return;
        }

        Stage dlg = new Stage();
        dlg.initOwner(owner); dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Reverse Journal #" + h.jnlNo);

        TextField fDate = tf(LocalDate.now().format(DMY), 12);
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(10); g.setPadding(new Insets(18));
        g.add(lbl("Reversal date *:"), 0, 0); g.add(fDate, 1, 0);
        Label hint = new Label("Creates a new draft General journal with every line's debit and credit "
            + "swapped from #" + h.jnlNo + ", for you to review and commit.");
        hint.setWrapText(true); hint.setMaxWidth(320);
        hint.setStyle("-fx-text-fill:#888780;-fx-font-size:11px;");
        g.add(hint, 0, 1, 2, 1);

        Button ok = btnPrimary("Create Reversal");
        Button cancel = btnSecondary("Cancel");
        ok.setDefaultButton(true);
        cancel.setOnAction(e -> dlg.close());
        ok.setOnAction(e -> {
            LocalDate date = parseDate(fDate.getText());
            if (date == null) { markError(fDate, "Enter a valid date (dd/MM/yyyy)."); return; }
            int co = session.getCompanyNo();
            String user = session.getUserId();
            int term = session.getTerminalNo();
            ok.setDisable(true);
            exec.submit(() -> {
                try {
                    String dateErr = svc.checkJournalDate(co, h.yrNo, date);
                    if (dateErr != null) { Platform.runLater(() -> { ok.setDisable(false); info("Date", dateErr); }); return; }
                    int newJnlNo = svc.createReversal(co, h.jnlType, h.yrNo, h.jnlNo, date, user, term);
                    Platform.runLater(() -> {
                        dlg.close();
                        runSearch();
                        info("Reversal Created", "Created draft General journal #" + newJnlNo
                            + " reversing #" + h.jnlNo + ". Review and commit it from Enter General Journal.");
                        status("Created reversal journal " + newJnlNo, false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> { ok.setDisable(false); status("Reverse error: " + ex.getMessage(), true); });
                }
            });
        });
        HBox bar = new HBox(10, ok, cancel);
        bar.setAlignment(Pos.CENTER_RIGHT); bar.setPadding(new Insets(10, 16, 14, 16));
        VBox root = new VBox(0, g, bar);
        dlg.setScene(new Scene(root, 420, 210));
        dlg.showAndWait();
    }

    // ── Export (reuses the GLTL06 general-journal jrxml) ──────────────────

    /** Exports the current on-screen search results — same shape GLTL06 feeds gl/general-journal(-excel). */
    private void exportReport(String format, Window owner) {
        if (rows.isEmpty()) { info("Export", "No journals to export — run a search first."); return; }
        List<GlJournalHeader> snapshot = new ArrayList<>(rows);
        String sourceDesc = cbSource.getValue() == null ? ALL_SOURCES_LABEL : cbSource.getValue();
        String jnlRangeDesc = rangeDesc(fStartJnl.getText(), fEndJnl.getText(), "end");
        String dateRangeDesc = rangeDesc(fStartDate.getText(), fEndDate.getText(), "today");

        status("Building " + format.toUpperCase() + "…", false);
        exec.submit(() -> {
            try {
                List<Map<String, Object>> exportRows = new ArrayList<>();
                BigDecimal sumDr = BigDecimal.ZERO, sumCr = BigDecimal.ZERO;
                int lineCount = 0;
                for (GlJournalHeader h : snapshot) {
                    List<GlJournalLine> lines = svc.loadLines(session.getCompanyNo(), h.jnlType, h.yrNo, h.jnlNo);
                    String src = gltrxSourceCode(h.jnlType);
                    String journal = src + "-" + h.jnlNo;
                    exportRows.add(jnlHeaderRow(journal, h.jnlDate));
                    BigDecimal jnlDr = BigDecimal.ZERO, jnlCr = BigDecimal.ZERO;
                    for (GlJournalLine l : lines) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("rowType", "data");
                        row.put("source", src);
                        row.put("journal", journal);
                        row.put("jnlDate", h.jnlDate == null ? null : java.sql.Date.valueOf(h.jnlDate));
                        row.put("acctMain", String.valueOf(l.acctMainNo));
                        row.put("acctSub", l.acctSubNo > 0 ? String.valueOf(l.acctSubNo) : "0");
                        row.put("description", l.acctDesc);
                        row.put("reference", l.ref);
                        row.put("debit", l.drAmt);
                        row.put("credit", l.crAmt);
                        row.put("user", h.auditUserId);
                        exportRows.add(row);
                        jnlDr = jnlDr.add(nz(l.drAmt));
                        jnlCr = jnlCr.add(nz(l.crAmt));
                    }
                    exportRows.add(jnlTotalRow(journal, jnlDr, jnlCr));
                    sumDr = sumDr.add(jnlDr);
                    sumCr = sumCr.add(jnlCr);
                    lineCount += lines.size();
                }

                Map<String, Object> params = new LinkedHashMap<>();
                params.put("COMPANY_NAME", session.getCompanyName());
                params.put("YEAR_DESC", session.getYearDesc());
                params.put("SOURCE_DESC", sourceDesc);
                params.put("JNL_RANGE", jnlRangeDesc);
                params.put("DATE_RANGE", dateRangeDesc);
                params.put("MODE_DESC", "Detail");
                params.put("SUM_DEBIT", sumDr);
                params.put("SUM_CREDIT", sumCr);
                params.put("ROW_COUNT", lineCount);

                byte[] data = "pdf".equals(format)
                    ? jasper.exportPdfFromDataSource("gl/general-journal", params, mapDataSource(exportRows))
                    : jasper.exportExcelFromDataSource("gl/general-journal-excel", params, mapDataSource(exportRows));
                String ext = "pdf".equals(format) ? ".pdf" : ".xlsx";
                int finalLineCount = lineCount;
                Platform.runLater(() -> {
                    saveAndOpen(data, ext);
                    status("Exported " + finalLineCount + " line(s) across " + snapshot.size() + " journal(s)", false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Export error: " + ex.getMessage(), true));
            }
        });
    }

    private static String gltrxSourceCode(String jnlType) {
        return switch (jnlType == null ? "" : jnlType) {
            case "S" -> "ST";
            case "C" -> "CT";
            default  -> "GN";
        };
    }
    private static Map<String, Object> jnlHeaderRow(String journal, LocalDate date) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rowType", "header"); r.put("source", ""); r.put("journal", journal);
        r.put("jnlDate", date == null ? null : java.sql.Date.valueOf(date));
        r.put("acctMain", ""); r.put("acctSub", ""); r.put("description", "");
        r.put("reference", ""); r.put("debit", null); r.put("credit", null); r.put("user", "");
        return r;
    }
    private static Map<String, Object> jnlTotalRow(String journal, BigDecimal dr, BigDecimal cr) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rowType", "total"); r.put("source", ""); r.put("journal", journal);
        r.put("jnlDate", null); r.put("acctMain", ""); r.put("acctSub", "");
        r.put("description", ""); r.put("reference", ""); r.put("debit", dr); r.put("credit", cr); r.put("user", "");
        return r;
    }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** "12 to 45" / "up to 45" / "12 to end" / "All journals" style range description for the report header. */
    private static String rangeDesc(String start, String end, String openEndedWord) {
        boolean hasStart = start != null && !start.isBlank();
        boolean hasEnd   = end != null && !end.isBlank();
        if (!hasStart && !hasEnd) return "All";
        if (hasStart && hasEnd)   return start.trim() + " to " + end.trim();
        return hasStart ? start.trim() + " to " + openEndedWord : "up to " + end.trim();
    }

    private static JRDataSource mapDataSource(List<Map<String, Object>> rows) {
        return new JRDataSource() {
            private final Iterator<Map<String, Object>> it = rows.iterator();
            private Map<String, Object> current;
            @Override public boolean next() { if (!it.hasNext()) return false; current = it.next(); return true; }
            @Override public Object getFieldValue(JRField f) { return current.get(f.getName()); }
        };
    }

    /** Save to CPCNTRL.local_pc_dir (falling back to a GL_Exports folder under the user's home) and open it. */
    private void saveAndOpen(byte[] data, String ext) {
        try {
            String dir = cpCntrl.getLocalPcDir(session.getCompanyNo());
            if (dir == null || dir.isBlank()) dir = System.getProperty("user.home") + File.separator + "GL_Exports";
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            File file = new File(dir, "gl_journal-inquiry_" + stamp + ext);
            Files.createDirectories(file.toPath().getParent());
            Files.write(file.toPath(), data);
            openWithOsViewer(file);
        } catch (Exception ex) {
            status("Save error: " + ex.getMessage(), true);
        }
    }

    private static void openWithOsViewer(File file) {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> cmd = os.contains("win") ? List.of("cmd", "/c", "start", "", file.getAbsolutePath())
            : os.contains("mac") || os.contains("darwin") ? List.of("open", file.getAbsolutePath())
            : List.of("xdg-open", file.getAbsolutePath());
        try { new ProcessBuilder(cmd).inheritIO().start(); }
        catch (Exception ex) { org.slf4j.LoggerFactory.getLogger(JournalInquiryController.class).warn("Could not open {}: {}", file, ex.getMessage()); }
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    private TableColumn<GlJournalHeader, String> col(String h, Function<GlJournalHeader, String> fn, double w) {
        TableColumn<GlJournalHeader, String> c = new TableColumn<>(h);
        c.setCellValueFactory(p -> new SimpleStringProperty(safe(fn.apply(p.getValue()))));
        c.setPrefWidth(w); return c;
    }
    private TableColumn<GlJournalLine, String> lcol(String h, Function<GlJournalLine, String> fn, double w) {
        TableColumn<GlJournalLine, String> c = new TableColumn<>(h);
        c.setCellValueFactory(p -> new SimpleStringProperty(safe(fn.apply(p.getValue()))));
        c.setPrefWidth(w); return c;
    }

    private HBox buildStatusBar() {
        lblStatus = new Label("Ready");
        lblStatus.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        HBox bar = new HBox(lblStatus);
        bar.setPadding(new Insets(5, 16, 5, 16));
        bar.setStyle("-fx-background-color:#F8F8F6;-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;-fx-border-width:0.5 0 0 0;");
        return bar;
    }
    private void status(String m, boolean err) {
        Platform.runLater(() -> { lblStatus.setText(m);
            lblStatus.setStyle("-fx-font-size:11px;-fx-text-fill:" + (err ? "#C0392B" : "#888780") + ";"); });
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }
    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.trim(), DMY); }
        catch (Exception e) {
            try { return LocalDate.parse(s.trim(), DateTimeFormatter.ofPattern("d/M/yyyy")); }
            catch (Exception e2) { return null; }
        }
    }
    private static String money(BigDecimal v) {
        if (v == null) return "";
        return v.signum() == 0 ? "" : String.format("%,.2f", v);
    }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static String safe(String s) { return s == null ? "" : s; }

    private Label lbl(String s) { Label l = new Label(s); l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;"); l.setMinWidth(70); return l; }
    private static Separator sep() { return new Separator(); }
    private TextField tf(String v, int max) { TextField f = new TextField(v == null ? "" : v.trim()); f.setPrefWidth(Math.min(max * 9 + 20, 360)); return f; }
    private Button btnPrimary(String t) { Button b = new Button(t); b.setStyle("-fx-background-color:#1A6EF5;-fx-text-fill:white;-fx-font-weight:bold;-fx-background-radius:7;-fx-padding:6 16;-fx-cursor:hand;"); return b; }
    private Button btnSecondary(String t) { Button b = new Button(t); b.setStyle("-fx-background-color:white;-fx-text-fill:#374151;-fx-border-color:#D0CFC8;-fx-background-radius:7;-fx-border-radius:7;-fx-padding:5 14;-fx-cursor:hand;"); return b; }
    private Button btnDanger(String t) { Button b = new Button(t); b.setStyle("-fx-background-color:white;-fx-text-fill:#C0392B;-fx-border-color:#E8BDB8;-fx-background-radius:7;-fx-border-radius:7;-fx-padding:5 14;-fx-cursor:hand;"); return b; }
    private static void markError(TextField f, String msg) {
        f.requestFocus(); f.selectAll();
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK); a.setTitle("Validation"); a.setHeaderText(null); a.showAndWait();
    }
    private void info(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK); a.setTitle(title); a.setHeaderText(null); a.showAndWait();
    }
}
