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
import com.landmarksoftware.service.gl.GlJournalService;
import com.landmarksoftware.service.gl.GlPostingService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * GLGN01 — Enter General / Standing Journals.
 *
 * <p>One controller, two modes via {@link #buildScene(Stage, String)}: "G"
 * General, "S" Standing. Lists the draft journals for the session's
 * company/year (committed and cancelled journals — {@code posted_flag='Y'}
 * — drop out of view, matching the COBOL P1 listbox), and opens a
 * header+lines editor. General journals can be checked individually or
 * left unchecked for "All", then committed to the ledger via
 * {@link #doCommit(Window)}.
 */
@Component
public class GeneralJournalController {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final GlJournalService svc;
    private final GlPostingService posting;
    private final AppSession       session;

    private String jnlType = "G";
    private final ObservableList<GlJournalHeader> headerRows = FXCollections.observableArrayList();
    private TableView<GlJournalHeader> headerTable;
    private Label lblStatus;

    /** Per-jnl_no checkbox state for the "select individual / All, then Commit" workflow — UI-only, not persisted. */
    private final java.util.Map<Integer, javafx.beans.property.BooleanProperty> selection = new java.util.HashMap<>();
    private CheckBox cbSelectAll;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "glgn01-thread");
        t.setDaemon(true);
        return t;
    });

    public GeneralJournalController(GlJournalService svc, GlPostingService posting, AppSession session) {
        this.svc     = svc;
        this.posting = posting;
        this.session = session;
    }

    private boolean standing() { return "S".equals(jnlType); }
    private String  title()    { return standing() ? "Enter Standing Journal" : "Enter General Journal"; }

    // ── Entry point ───────────────────────────────────────────────────────

    public Scene buildScene(Stage stage) { return buildScene(stage, "G"); }

    public Scene buildScene(Stage stage, String jnlType) {
        this.jnlType = "S".equals(jnlType) ? "S" : "G";
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildHeader());
        root.setCenter(buildContent(stage));
        root.setBottom(buildStatusBar());
        loadHeaders();
        Scene scene = new Scene(root, 860, 560);
        scene.getStylesheets().add(getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    private HBox buildHeader() {
        Label t = new Label(title());
        t.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label((standing() ? "GLGN01 (S)" : "GLGN01") + " · " + session.getCompanyName()
            + "  ·  FY " + session.getYrNo());
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        HBox bar = new HBox(new VBox(2, t, sub));
        bar.setPadding(new Insets(14, 20, 14, 20));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color:#FFFFFF;-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;-fx-border-width:0 0 0.5 0;");
        return bar;
    }

    private VBox buildContent(Stage stage) {
        headerTable = new TableView<>(headerRows);
        headerTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        headerTable.setEditable(true);
        VBox.setVgrow(headerTable, Priority.ALWAYS);
        headerTable.setPlaceholder(new Label("No " + (standing() ? "standing" : "general") + " draft journals for this year."));
        List<TableColumn<GlJournalHeader, ?>> columns = new java.util.ArrayList<>();
        columns.add(selectColumn());   // per-row checkbox + "select all" — Commit (General) / Run (Standing)
        columns.addAll(List.of(
            col("Jnl No",   h -> String.valueOf(h.jnlNo),        80),
            col("Date",     h -> h.jnlDate == null ? "" : h.jnlDate.format(DMY), 100),
            col("Notation", h -> h.note1,                        260),
            col("Debit",    h -> money(h.totalDr),               110),
            col("Credit",   h -> money(h.totalCr),               110),
            col("Status",   this::statusLabel,                    90)
        ));
        headerTable.getColumns().addAll(columns);
        headerTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && sel() != null) openEditor(sel(), stage);
        });

        Button btnNew  = btnPrimary("+ New");
        Button btnEdit = btnSecondary("✎ Edit");
        Button btnCan  = btnDanger("✕ Cancel Jnl");
        Button btnRef  = btnSecondary("↺");
        btnNew.setOnAction(e -> openEditor(null, stage));
        btnEdit.setOnAction(e -> { if (sel() != null) openEditor(sel(), stage); else info("Edit", "Select a journal."); });
        btnCan.setOnAction(e -> { if (sel() != null) confirmCancel(sel()); else info("Cancel", "Select a journal."); });
        btnRef.setOnAction(e -> loadHeaders());

        HBox toolbar = new HBox(8, btnNew, btnEdit, btnCan, new Separator(Orientation.VERTICAL), btnRef);
        if (standing()) {
            Button btnRun = btnPrimary("▶ Run");
            btnRun.setTooltip(new Tooltip("Run the selected standing journals"));
            btnRun.setOnAction(e -> doRun(stage));
            toolbar.getChildren().addAll(new Separator(Orientation.VERTICAL), btnRun);
        } else {
            Button btnCommit = btnPrimary("⚑ Commit");
            btnCommit.setTooltip(new Tooltip("Commit the selected journals from draft"));
            btnCommit.setOnAction(e -> doCommit(stage));
            toolbar.getChildren().addAll(new Separator(Orientation.VERTICAL), btnCommit);
        }
        toolbar.setPadding(new Insets(10, 16, 10, 16));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle("-fx-background-color:#F8F8F6;-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;-fx-border-width:0 0 0.5 0;");
        return new VBox(0, toolbar, headerTable);
    }

    private GlJournalHeader sel() { return headerTable.getSelectionModel().getSelectedItem(); }

    private String statusLabel(GlJournalHeader h) {
        String s = h.jnlStatus == null ? " " : h.jnlStatus.trim();
        return switch (s) {
            case "C" -> "Cancelled";
            case "X" -> "Expired";       // standing journal past its last occurrence
            case "P" -> "Committing…";   // transient lock while another session commits it
            case "U" -> "In use";
            case ""  -> h.isBalanced() ? "Draft" : "Draft — Unbalanced";
            default  -> s;
        };
    }

    // ── Selection column (checkbox per row + "select all" in the header) ──
    // Drives Commit (General) or Run (Standing) below — checked rows only,
    // or every eligible row when nothing is checked ("All").

    private javafx.beans.property.BooleanProperty selectedProp(GlJournalHeader h) {
        return selection.computeIfAbsent(h.jnlNo, k -> new javafx.beans.property.SimpleBooleanProperty(false));
    }

    private TableColumn<GlJournalHeader, Boolean> selectColumn() {
        TableColumn<GlJournalHeader, Boolean> c = new TableColumn<>();
        c.setCellValueFactory(p -> selectedProp(p.getValue()));
        c.setCellFactory(javafx.scene.control.cell.CheckBoxTableCell.forTableColumn(c));
        c.setEditable(true);
        c.setSortable(false);
        c.setPrefWidth(34);
        c.setResizable(false);
        cbSelectAll = new CheckBox();
        cbSelectAll.setOnAction(e -> {
            boolean v = cbSelectAll.isSelected();
            for (GlJournalHeader h : headerRows) selectedProp(h).set(v);
        });
        c.setGraphic(cbSelectAll);
        return c;
    }

    // ── Data ops ────────────────────────────────────────────────────────────

    private void loadHeaders() {
        status("Loading…", false);
        exec.submit(() -> {
            try {
                List<GlJournalHeader> data = svc.findHeaders(session.getCompanyNo(), session.getYrNo(), jnlType);
                Platform.runLater(() -> {
                    selection.clear();
                    if (cbSelectAll != null) cbSelectAll.setSelected(false);
                    headerRows.setAll(data);
                    status(data.size() + " draft journal(s)", false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    private void confirmCancel(GlJournalHeader h) {
        if ("C".equals(trim(h.jnlStatus))) { info("Cancel", "Journal already cancelled."); return; }
        if ("P".equals(trim(h.jnlStatus))) { info("Cancel", "Journal is being committed — cannot cancel."); return; }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "Cancel journal " + h.jnlNo + "? Its lines will be removed.", ButtonType.YES, ButtonType.NO);
        a.setTitle("Confirm Cancel"); a.setHeaderText(null);
        a.showAndWait().ifPresent(b -> { if (b == ButtonType.YES) {
            exec.submit(() -> {
                try { svc.cancel(session.getCompanyNo(), jnlType, session.getYrNo(), h.jnlNo);
                    Platform.runLater(() -> { loadHeaders(); status("Cancelled journal " + h.jnlNo, false); });
                } catch (Exception ex) { Platform.runLater(() -> status("Cancel error: " + ex.getMessage(), true)); }
            });
        }});
    }

    // ── Commit / post (glgn06/glgn07) ────────────────────────────────────────

    /**
     * Commits either the individually-checked journals, or — when nothing is
     * checked — every balanced draft journal (the "All" case).
     */
    private void doCommit(Window owner) {
        int co = session.getCompanyNo(); int yrNo = session.getYrNo();
        String user = session.getUserId(); int term = session.getTerminalNo();
        java.util.Set<Integer> chosen = selection.entrySet().stream()
            .filter(en -> en.getValue().get())
            .map(java.util.Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
        status("Checking committable journals…", false);
        exec.submit(() -> {
            try {
                List<GlPostingService.Postable> all = posting.findPostable(co, yrNo);
                List<GlPostingService.Postable> scope = chosen.isEmpty() ? all
                    : all.stream().filter(p -> chosen.contains(p.jnlNo())).toList();
                long bal = scope.stream().filter(GlPostingService.Postable::balanced).count();
                long unbal = scope.size() - bal;
                Platform.runLater(() -> {
                    if (bal == 0) {
                        info("Commit Journals", (chosen.isEmpty()
                                ? "No balanced draft general journals to commit."
                                : "None of the selected journals are ready to commit.")
                            + (unbal > 0 ? "\n(" + unbal + " unbalanced journal(s) cannot be committed.)" : ""));
                        return;
                    }
                    String scopeDesc = chosen.isEmpty()
                        ? "all " + bal + " balanced draft general journal(s)"
                        : bal + " of the " + chosen.size() + " selected journal(s)";
                    Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                        "Commit " + scopeDesc + " to the ledger?\n\n"
                        + "This writes gltrx and glbal period balances and marks the journals committed."
                        + (unbal > 0 ? "\n" + unbal + " unbalanced journal(s) will be skipped." : ""),
                        ButtonType.YES, ButtonType.NO);
                    a.setTitle("Confirm Commit"); a.setHeaderText(null);
                    a.showAndWait().ifPresent(btn -> {
                        if (btn != ButtonType.YES) return;
                        status("Committing…", false);
                        List<Integer> toPost = scope.stream().filter(GlPostingService.Postable::balanced)
                            .map(GlPostingService.Postable::jnlNo).toList();
                        exec.submit(() -> {
                            try {
                                GlPostingService.PostResult r = chosen.isEmpty()
                                    ? posting.postAll(co, yrNo, user, term)
                                    : posting.postSelected(co, yrNo, toPost, user, term);
                                Platform.runLater(() -> { loadHeaders(); status(r.message(), false); });
                            } catch (Exception ex) {
                                Platform.runLater(() -> status("Commit error: " + ex.getMessage(), true));
                            }
                        });
                    });
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Commit check error: " + ex.getMessage(), true));
            }
        });
    }

    // ── Run (standing journals — glgn09 UPDATE-STANDING-JNL) ─────────────────

    /**
     * Runs either the individually-checked standing journals, or — when
     * nothing is checked — every balanced, active standing journal (the
     * "All" case). Each run posts the template's current occurrence to the
     * ledger and rolls it forward to its next due date (or expires it).
     */
    private void doRun(Window owner) {
        int co = session.getCompanyNo(); int yrNo = session.getYrNo();
        String user = session.getUserId(); int term = session.getTerminalNo();
        java.util.Set<Integer> chosen = selection.entrySet().stream()
            .filter(en -> en.getValue().get())
            .map(java.util.Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
        status("Checking runnable standing journals…", false);
        exec.submit(() -> {
            try {
                List<GlPostingService.Postable> all = posting.findRunnableStanding(co, yrNo);
                List<GlPostingService.Postable> scope = chosen.isEmpty() ? all
                    : all.stream().filter(p -> chosen.contains(p.jnlNo())).toList();
                long bal = scope.stream().filter(GlPostingService.Postable::balanced).count();
                long unbal = scope.size() - bal;
                Platform.runLater(() -> {
                    if (bal == 0) {
                        info("Run Standing Journals", (chosen.isEmpty()
                                ? "No balanced standing journals to run."
                                : "None of the selected standing journals are ready to run.")
                            + (unbal > 0 ? "\n(" + unbal + " unbalanced journal(s) cannot be run.)" : ""));
                        return;
                    }
                    String scopeDesc = chosen.isEmpty()
                        ? "all " + bal + " balanced standing journal(s)"
                        : bal + " of the " + chosen.size() + " selected journal(s)";
                    Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                        "Run " + scopeDesc + "?\n\n"
                        + "This posts each journal's lines to the ledger for its current occurrence date, "
                        + "then rolls it forward to its next due date (or expires it)."
                        + (unbal > 0 ? "\n" + unbal + " unbalanced journal(s) will be skipped." : ""),
                        ButtonType.YES, ButtonType.NO);
                    a.setTitle("Confirm Run"); a.setHeaderText(null);
                    a.showAndWait().ifPresent(btn -> {
                        if (btn != ButtonType.YES) return;
                        status("Running…", false);
                        List<Integer> toRun = scope.stream().filter(GlPostingService.Postable::balanced)
                            .map(GlPostingService.Postable::jnlNo).toList();
                        exec.submit(() -> {
                            try {
                                GlPostingService.PostResult r = chosen.isEmpty()
                                    ? posting.runAllStanding(co, yrNo, user, term)
                                    : posting.runSelectedStanding(co, yrNo, toRun, user, term);
                                Platform.runLater(() -> { loadHeaders(); status(r.message(), false); });
                            } catch (Exception ex) {
                                Platform.runLater(() -> status("Run error: " + ex.getMessage(), true));
                            }
                        });
                    });
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Run check error: " + ex.getMessage(), true));
            }
        });
    }

    // ── Editor (header + lines) ──────────────────────────────────────────────

    private void openEditor(GlJournalHeader existing, Window owner) {
        boolean isNew = existing == null;
        if (!isNew && ("C".equals(trim(existing.jnlStatus)) || "P".equals(trim(existing.jnlStatus)))) {
            info("Edit", "Journal " + existing.jnlNo + " is " + statusLabel(existing).toLowerCase() + " — read only.");
            return;
        }
        // Load lines off-thread for an existing journal, then build the editor.
        if (isNew) {
            GlJournalHeader h = new GlJournalHeader();
            h.jnlType = jnlType; h.yrNo = session.getYrNo(); h.jnlDate = LocalDate.now();
            buildEditor(h, true, owner);
        } else {
            status("Loading lines…", false);
            exec.submit(() -> {
                try {
                    List<GlJournalLine> lines = svc.loadLines(session.getCompanyNo(), jnlType, session.getYrNo(), existing.jnlNo);
                    Platform.runLater(() -> {
                        existing.lines.clear(); existing.lines.addAll(lines);
                        buildEditor(existing, false, owner);
                        status("", false);
                    });
                } catch (Exception ex) { Platform.runLater(() -> status("Load lines error: " + ex.getMessage(), true)); }
            });
        }
    }

    private void buildEditor(GlJournalHeader h, boolean isNew, Window owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isNew ? "New" : "Edit") + " " + (standing() ? "Standing" : "General") + " Journal"
            + (isNew ? "" : "  #" + h.jnlNo));

        // Tracks unsaved edits so Close / the window's X can warn before discarding them.
        javafx.beans.property.BooleanProperty dirty = new javafx.beans.property.SimpleBooleanProperty(false);

        // Header form
        TextField fDate = tf(h.jnlDate == null ? "" : h.jnlDate.format(DMY), 12);
        TextField fN1   = tf(h.note1, 55);
        TextField fN2   = tf(h.note2, 55);
        CheckBox  cbRev = new CheckBox("Auto-reverse next period");
        cbRev.setSelected("Y".equals(trim(h.autoReverseFlag)));
        ChoiceBox<String> cbFreq = new ChoiceBox<>(FXCollections.observableArrayList(
            "", "M — Monthly", "B — Bi-monthly", "Q — Quarterly", "H — Half-yearly", "A — Annually", "O — One-off"));
        selectByCode(cbFreq, h.postFreq);
        TextField fExpiry = tf(h.expiryDate == null ? "" : h.expiryDate.format(DMY), 12);

        fDate.textProperty().addListener((o, ov, nv) -> dirty.set(true));
        fN1.textProperty().addListener((o, ov, nv) -> dirty.set(true));
        fN2.textProperty().addListener((o, ov, nv) -> dirty.set(true));
        cbRev.selectedProperty().addListener((o, ov, nv) -> dirty.set(true));
        cbFreq.valueProperty().addListener((o, ov, nv) -> dirty.set(true));
        fExpiry.textProperty().addListener((o, ov, nv) -> dirty.set(true));

        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(8); form.setPadding(new Insets(16, 16, 8, 16));
        int r = 0;
        form.add(lbl("Date *:"), 0, r); form.add(fDate, 1, r++);
        form.add(lbl("Notation 1:"), 0, r); form.add(fN1, 1, r++);
        form.add(lbl("Notation 2:"), 0, r); form.add(fN2, 1, r++);
        form.add(cbRev, 1, r++);
        if (standing()) {
            form.add(lbl("Post frequency:"), 0, r); form.add(cbFreq, 1, r++);
            form.add(lbl("Expiry date:"), 0, r);    form.add(fExpiry, 1, r++);
        }
        GridPane.setHgrow(fN1, Priority.ALWAYS);

        // Lines table
        ObservableList<GlJournalLine> lineRows = FXCollections.observableArrayList(h.lines);
        TableView<GlJournalLine> lineTable = new TableView<>(lineRows);
        lineTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        lineTable.setPlaceholder(new Label("No lines — click “+ Line”."));
        lineTable.getColumns().addAll(List.of(
            lcol("#",           l -> String.valueOf(l.lineNo),      45),
            lcol("Account",     GlJournalLine::accountDisplay,      90),
            lcol("Description", l -> l.acctDesc,                    200),
            lcol("Debit",       l -> money(l.drAmt),                110),
            lcol("Credit",      l -> money(l.crAmt),                110),
            lcol("Reference",   l -> l.ref,                         180)
        ));
        VBox.setVgrow(lineTable, Priority.ALWAYS);

        Label lblTot = new Label();
        Runnable recompute = () -> {
            BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
            for (GlJournalLine l : lineRows) { dr = dr.add(nz(l.drAmt)); cr = cr.add(nz(l.crAmt)); }
            BigDecimal bal = dr.subtract(cr);
            lblTot.setText(String.format("Debit  %s      Credit  %s      Balance  %s%s",
                money(dr), money(cr), money(bal.abs()), bal.signum() == 0 ? "  (balanced)" : (bal.signum() > 0 ? "  DR" : "  CR")));
            lblTot.setStyle("-fx-font-weight:bold;-fx-padding:6 0 0 0;-fx-text-fill:"
                + (bal.signum() == 0 ? "#1E8E3E" : "#C0392B") + ";");
        };
        recompute.run();
        Runnable onLineChanged = () -> { dirty.set(true); recompute.run(); };

        Button btnAddL = btnSecondary("+ Line");
        Button btnEdL  = btnSecondary("✎ Line");
        Button btnDelL = btnSecondary("✕ Line");
        btnAddL.setOnAction(e -> openLineDialog(null, lineRows, lineTable, onLineChanged, dlg));
        btnEdL.setOnAction(e -> {
            GlJournalLine s = lineTable.getSelectionModel().getSelectedItem();
            if (s != null) openLineDialog(s, lineRows, lineTable, onLineChanged, dlg); else info("Line", "Select a line.");
        });
        btnDelL.setOnAction(e -> {
            GlJournalLine s = lineTable.getSelectionModel().getSelectedItem();
            if (s != null) { lineRows.remove(s); renumber(lineRows); lineTable.refresh(); onLineChanged.run(); }
        });
        Button btnBalL = btnSecondary("⚖ Balancing line");
        btnBalL.setOnAction(e -> {
            BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
            for (GlJournalLine x : lineRows) { d = d.add(nz(x.drAmt)); c = c.add(nz(x.crAmt)); }
            BigDecimal imb = d.subtract(c);
            if (imb.signum() == 0) { info("Balance", "The journal already balances."); return; }
            openAccountPicker(dlg, ar -> {
                GlJournalLine nl = new GlJournalLine();
                nl.acctMainNo = ar.mainNo(); nl.acctSubNo = ar.subNo(); nl.acctDesc = ar.desc();
                if (imb.signum() > 0) nl.crAmt = imb; else nl.drAmt = imb.negate();
                nl.lineNo = lineRows.size() + 1; lineRows.add(nl);
                renumber(lineRows); lineTable.refresh(); onLineChanged.run();
            });
        });
        HBox lineBar = new HBox(8, btnAddL, btnEdL, btnDelL, btnBalL);
        lineBar.setPadding(new Insets(6, 16, 6, 16));

        Button btnSave = btnPrimary(isNew ? "Save Journal" : "Save Changes");
        Button btnCancel = btnSecondary("Close");
        btnSave.setDefaultButton(true);
        Runnable attemptClose = () -> {
            if (!dirty.get()) { dlg.close(); return; }
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                (isNew ? "This journal hasn't been saved yet." : "This journal has unsaved changes.")
                + " Closing now will discard " + (isNew ? "it" : "them") + ". Close anyway?",
                ButtonType.YES, ButtonType.NO);
            a.setTitle("Unsaved Changes"); a.setHeaderText(null);
            a.showAndWait().ifPresent(b -> { if (b == ButtonType.YES) dlg.close(); });
        };
        btnCancel.setOnAction(e -> attemptClose.run());
        dlg.setOnCloseRequest(ev -> {
            if (dirty.get()) { ev.consume(); attemptClose.run(); }
        });
        btnSave.setOnAction(e -> {
            LocalDate date = parseDate(fDate.getText());
            if (date == null) { markError(fDate, "Enter a valid date (dd/MM/yyyy)."); return; }
            if (lineRows.isEmpty()) { info("Save", "Add at least one line."); return; }
            // stage the header from the form
            h.jnlDate = date;
            h.note1 = fN1.getText().trim();
            h.note2 = fN2.getText().trim();
            h.autoReverseFlag = cbRev.isSelected() ? "Y" : "N";
            if (standing()) { h.postFreq = codeOf(cbFreq); h.expiryDate = parseDate(fExpiry.getText()); }
            h.lines.clear(); h.lines.addAll(lineRows);
            saveJournal(h, date, dlg);
        });
        HBox btnBar = new HBox(10, btnSave, btnCancel);
        btnBar.setPadding(new Insets(10, 16, 14, 16));
        btnBar.setAlignment(Pos.CENTER_RIGHT);

        VBox.setVgrow(lineTable, Priority.ALWAYS);
        BorderPane root = new BorderPane(new VBox(0, form, sep(), lineBar, lineTable, padded(lblTot)));
        root.setBottom(btnBar);
        dlg.setScene(new Scene(root, 780, 620));
        dlg.showAndWait();
    }

    private void saveJournal(GlJournalHeader h, LocalDate date, Stage dlg) {
        int coNo = session.getCompanyNo();
        String user = session.getUserId();
        int term = session.getTerminalNo();
        exec.submit(() -> {
            try {
                String dateErr = svc.checkJournalDate(coNo, h.yrNo, date);
                if (dateErr != null) { Platform.runLater(() -> info("Date", dateErr)); return; }
                int jnlNo = svc.save(coNo, h, user, term);
                Platform.runLater(() -> {
                    dlg.close();
                    loadHeaders();
                    status("Saved journal " + jnlNo
                        + (h.isBalanced() ? "" : " (UNBALANCED — will not commit until balanced)"), !h.isBalanced());
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Save error: " + ex.getMessage(), true));
            }
        });
    }

    // ── Line dialog ──────────────────────────────────────────────────────────

    private void openLineDialog(GlJournalLine existing, ObservableList<GlJournalLine> rows,
                                TableView<GlJournalLine> table, Runnable recompute, Window owner) {
        boolean isNew = existing == null;
        GlJournalLine l = isNew ? new GlJournalLine() : existing;

        Stage dlg = new Stage();
        dlg.initOwner(owner); dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isNew ? "Add" : "Edit") + " Line");

        TextField fMain = tf(isNew ? "" : String.valueOf(l.acctMainNo), 8);
        TextField fSub  = tf(isNew ? "0" : String.valueOf(l.acctSubNo), 6);
        Label lblDesc = new Label(isNew ? "" : l.acctDesc);
        lblDesc.setStyle("-fx-text-fill:#888780;-fx-font-size:11px;");
        TextField fDr = tf(isNew || l.drAmt.signum() == 0 ? "" : plain(l.drAmt), 14);
        TextField fCr = tf(isNew || l.crAmt.signum() == 0 ? "" : plain(l.crAmt), 14);
        TextField fRef = tf(l.ref, 40);
        CheckBox cbZero = new CheckBox("Clear amount after each post");
        cbZero.setSelected("Y".equals(trim(l.zeroAmtFlag)));

        GridPane g = new GridPane(); g.setHgap(10); g.setVgap(9); g.setPadding(new Insets(18));
        int r = 0;
        Button btnPick = new Button("🔍");
        btnPick.setStyle("-fx-background-color:white;-fx-text-fill:#374151;-fx-border-color:#D0CFC8;"
            + "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:3 8;-fx-cursor:hand;-fx-font-size:11px;");
        btnPick.setOnAction(ev -> openAccountPicker(dlg, ar -> {
            fMain.setText(String.valueOf(ar.mainNo()));
            fSub.setText(String.valueOf(ar.subNo()));
            lblDesc.setText(ar.desc());
        }));
        g.add(lbl("Account:"), 0, r);     g.add(new HBox(6, fMain, btnPick), 1, r++);
        g.add(lbl("Sub-Account:"), 0, r); g.add(fSub, 1, r++);
        g.add(new Label(), 0, r);         g.add(lblDesc, 1, r++);
        g.add(lbl("Debit:"), 0, r);  g.add(fDr, 1, r++);
        g.add(lbl("Credit:"), 0, r); g.add(fCr, 1, r++);
        g.add(lbl("Reference:"), 0, r); g.add(fRef, 1, r++);
        if (standing()) { g.add(cbZero, 1, r++); }

        // Enter one of DR/CR — typing in one clears the other.
        fDr.textProperty().addListener((o, ov, nv) -> { if (!nv.isBlank()) fCr.clear(); });
        fCr.textProperty().addListener((o, ov, nv) -> { if (!nv.isBlank()) fDr.clear(); });

        // Imbalance of the OTHER lines (excludes the line being edited) — drives
        // the "needs a debit/credit" hint and the one-click Balance helper.
        java.util.function.Supplier<BigDecimal> imbalance = () -> {
            BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
            for (GlJournalLine x : rows) {
                if (!isNew && x == l) continue;
                d = d.add(nz(x.drAmt)); c = c.add(nz(x.crAmt));
            }
            return d.subtract(c);
        };
        Label lblBal = new Label();
        lblBal.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        Runnable refreshBal = () -> {
            BigDecimal imb = imbalance.get();
            lblBal.setText(imb.signum() == 0 ? "Other lines balance."
                : "Other lines out by " + money(imb.abs())
                  + (imb.signum() > 0 ? " DR — needs a credit." : " CR — needs a debit."));
        };
        refreshBal.run();
        g.add(new Label(), 0, r); g.add(lblBal, 1, r++);

        Button balBtn = btnSecondary("⚖ Balance");
        balBtn.setOnAction(e -> {
            BigDecimal imb = imbalance.get();
            if (imb.signum() > 0)      { fDr.clear(); fCr.setText(imb.toPlainString()); }
            else if (imb.signum() < 0) { fCr.clear(); fDr.setText(imb.negate().toPlainString()); }
            else info("Balance", "The other lines already balance.");
        });

        Button ok    = btnPrimary(isNew ? "Add" : "Update");
        Button close = btnSecondary(isNew ? "Close" : "Cancel");
        ok.setDefaultButton(true);           // Enter commits
        close.setOnAction(e -> dlg.close());

        ok.setOnAction(e -> {
            Integer main = parseInt(fMain.getText());
            if (main == null || main <= 0) { markError(fMain, "Enter an account number."); return; }
            int sub = 0;
            if (!fSub.getText().trim().isEmpty()) {
                Integer s = parseInt(fSub.getText());
                if (s == null || s < 0) { markError(fSub, "Sub-account must be a whole number."); return; }
                sub = s;
            }
            final int mainNo = main, subNo = sub;
            BigDecimal dr = parseDec(fDr); if (dr == null) { markError(fDr, "Debit must be a number."); return; }
            BigDecimal cr = parseDec(fCr); if (cr == null) { markError(fCr, "Credit must be a number."); return; }
            if (dr.signum() == 0 && cr.signum() == 0) { info("Amount", "Enter a debit or a credit."); return; }
            if (dr.signum() != 0 && cr.signum() != 0) { info("Amount", "Enter a debit OR a credit, not both."); return; }
            final String ref = fRef.getText().trim();
            final String zero = standing() && cbZero.isSelected() ? "Y" : "N";
            ok.setDisable(true);
            exec.submit(() -> {
                GlJournalService.AccountResult ar = svc.checkAccount(session.getCompanyNo(), mainNo, subNo);
                Platform.runLater(() -> {
                    ok.setDisable(false);
                    if (!ar.ok()) { markError(fMain, ar.message()); return; }
                    if (isNew) {
                        // Add + stay open for the next line (COBOL S2 loop).
                        GlJournalLine nl = new GlJournalLine();
                        nl.acctMainNo = mainNo; nl.acctSubNo = subNo;
                        nl.drAmt = dr; nl.crAmt = cr; nl.ref = ref; nl.acctDesc = ar.desc(); nl.zeroAmtFlag = zero;
                        nl.lineNo = rows.size() + 1; rows.add(nl);
                        renumber(rows); table.refresh(); recompute.run(); refreshBal.run();
                        fMain.clear(); fSub.setText("0"); fDr.clear(); fCr.clear(); fRef.clear(); lblDesc.setText("");
                        fMain.requestFocus();
                    } else {
                        l.acctMainNo = mainNo; l.acctSubNo = subNo;
                        l.drAmt = dr; l.crAmt = cr; l.ref = ref; l.acctDesc = ar.desc();
                        if (standing()) l.zeroAmtFlag = zero;
                        renumber(rows); table.refresh(); recompute.run();
                        dlg.close();
                    }
                });
            });
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, balBtn, spacer, ok, close);
        bar.setAlignment(Pos.CENTER_LEFT); bar.setPadding(new Insets(8, 18, 16, 18));
        VBox root = new VBox(0, g, bar);
        dlg.setScene(new Scene(root, 470, standing() ? 400 : 370));
        if (isNew) Platform.runLater(fMain::requestFocus);
        dlg.showAndWait();
    }

    // ── Account picker (GLCHIND-style browse of glchart) ───────────────────────

    private void openAccountPicker(Window owner, Consumer<GlJournalService.AccountRow> onPick) {
        Stage dlg = new Stage();
        dlg.initOwner(owner); dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Select Account");

        TextField fFilter = new TextField();
        fFilter.setPromptText("Filter by account number or description…");

        ObservableList<GlJournalService.AccountRow> all   = FXCollections.observableArrayList();
        ObservableList<GlJournalService.AccountRow> shown = FXCollections.observableArrayList();
        TableView<GlJournalService.AccountRow> table = new TableView<>(shown);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Loading…"));
        TableColumn<GlJournalService.AccountRow, String> cA = new TableColumn<>("Account");
        cA.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().display()));
        cA.setPrefWidth(120);
        TableColumn<GlJournalService.AccountRow, String> cD = new TableColumn<>("Description");
        cD.setCellValueFactory(p -> new SimpleStringProperty(safe(p.getValue().desc())));
        cD.setPrefWidth(320);
        table.getColumns().addAll(List.of(cA, cD));

        fFilter.textProperty().addListener((o, ov, nv) -> {
            String q = nv == null ? "" : nv.trim().toLowerCase();
            if (q.isEmpty()) { shown.setAll(all); return; }
            ObservableList<GlJournalService.AccountRow> f = FXCollections.observableArrayList();
            for (GlJournalService.AccountRow a : all)
                if (a.display().toLowerCase().contains(q) || safe(a.desc()).toLowerCase().contains(q)) f.add(a);
            shown.setAll(f);
        });

        Runnable choose = () -> {
            GlJournalService.AccountRow s = table.getSelectionModel().getSelectedItem();
            if (s != null) { onPick.accept(s); dlg.close(); }
        };
        table.setOnMouseClicked(e -> { if (e.getClickCount() == 2) choose.run(); });

        Button sel = btnPrimary("Select"); sel.setOnAction(e -> choose.run());
        Button cancel = btnSecondary("Cancel"); cancel.setOnAction(e -> dlg.close());
        HBox bar = new HBox(10, sel, cancel);
        bar.setAlignment(Pos.CENTER_RIGHT); bar.setPadding(new Insets(10, 16, 14, 16));

        VBox top = new VBox(fFilter); top.setPadding(new Insets(12, 16, 8, 16));
        BorderPane root = new BorderPane(table); root.setTop(top); root.setBottom(bar);
        dlg.setScene(new Scene(root, 480, 460));

        exec.submit(() -> {
            try {
                List<GlJournalService.AccountRow> data = svc.listPostableAccounts(session.getCompanyNo());
                Platform.runLater(() -> {
                    all.setAll(data); shown.setAll(data);
                    table.setPlaceholder(new Label("No postable accounts."));
                    fFilter.requestFocus();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> table.setPlaceholder(new Label("Load error: " + ex.getMessage())));
            }
        });
        dlg.showAndWait();
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    private static void renumber(ObservableList<GlJournalLine> rows) {
        int n = 0; for (GlJournalLine l : rows) l.lineNo = ++n;
    }

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
    private BigDecimal parseDec(TextField f) {
        String s = f.getText().trim();
        if (s.isEmpty()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.replace(",", "")); } catch (NumberFormatException e) { return null; }
    }
    private static String money(BigDecimal v) {
        if (v == null) return "";
        return v.signum() == 0 ? "" : String.format("%,.2f", v);
    }
    private static String plain(BigDecimal v) { return v == null ? "" : v.toPlainString(); }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static String safe(String s) { return s == null ? "" : s; }

    private static void selectByCode(ChoiceBox<String> cb, String code) {
        if (code == null || code.isBlank()) { cb.getSelectionModel().selectFirst(); return; }
        char want = Character.toUpperCase(code.trim().charAt(0));
        for (String o : cb.getItems()) if (!o.isEmpty() && Character.toUpperCase(o.charAt(0)) == want) { cb.setValue(o); return; }
        cb.getSelectionModel().selectFirst();
    }
    private static String codeOf(ChoiceBox<String> cb) {
        String s = cb.getValue();
        return (s == null || s.isBlank()) ? "" : String.valueOf(s.charAt(0));
    }

    private Label lbl(String s) { Label l = new Label(s); l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;"); l.setMinWidth(160); return l; }
    private static Node sep() { Separator s = new Separator(); return s; }
    private static VBox padded(Node n) { VBox v = new VBox(n); v.setPadding(new Insets(4, 16, 8, 16)); return v; }
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
