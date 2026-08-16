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
import com.landmarksoftware.model.GlCode;
import com.landmarksoftware.service.gl.GlCodesService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * GLNM01 — Sub Account [code] Maintenance. Flat, company-wide list of
 * 4-digit sub-account codes and names (glcodes) — a reference/pick-list
 * used elsewhere (CPCM01 S2's description lookup, CPCM10's "S" bulk-create
 * mode), not a child list of any one main account. No delete guard, per
 * COBOL (DELETE-SUB-ACCOUNTS is an unconditional code-table delete).
 */
@Component
public class SubAccountCodesController {

    private final GlCodesService svc;
    private final AppSession     session;

    private final ObservableList<GlCode> rows = FXCollections.observableArrayList();
    private TableView<GlCode> table;
    private Label lblStatus;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "glnm01-thread");
        t.setDaemon(true);
        return t;
    });

    public SubAccountCodesController(GlCodesService svc, AppSession session) {
        this.svc = svc;
        this.session = session;
    }

    public Scene buildScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildHeader());
        root.setCenter(buildContent(stage));
        root.setBottom(buildStatusBar());
        loadList();
        Scene scene = new Scene(root, 560, 560);
        scene.getStylesheets().add(getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    private HBox buildHeader() {
        Label t = new Label("Sub Account Codes");
        t.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("GLNM01 · " + session.getCompanyName());
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        HBox bar = new HBox(new VBox(2, t, sub));
        bar.setPadding(new Insets(14, 20, 14, 20));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color:#FFFFFF;-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;-fx-border-width:0 0 0.5 0;");
        return bar;
    }

    private VBox buildContent(Stage stage) {
        table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(table, Priority.ALWAYS);
        table.setPlaceholder(new Label("No sub account codes defined."));
        table.getColumns().addAll(List.of(
            col("Sub", c -> String.format("%04d", c.subAcct), 80),
            col("Description", c -> c.desc1, 320)
        ));
        table.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && sel() != null) openDialog(sel(), stage); });

        Button btnAdd  = btnPrimary("+ Add");
        Button btnEdit = btnSecondary("✎ Edit");
        Button btnDel  = btnDanger("✕ Delete");
        Button btnRef  = btnSecondary("↺");
        btnAdd.setOnAction(e -> openDialog(null, stage));
        btnEdit.setOnAction(e -> { if (sel() != null) openDialog(sel(), stage); else info("Edit", "Select a code."); });
        btnDel.setOnAction(e -> { if (sel() != null) confirmDelete(sel()); else info("Delete", "Select a code."); });
        btnRef.setOnAction(e -> loadList());

        HBox toolbar = new HBox(8, btnAdd, btnEdit, btnDel, new Separator(Orientation.VERTICAL), btnRef);
        toolbar.setPadding(new Insets(10, 16, 10, 16));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle("-fx-background-color:#F8F8F6;-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;-fx-border-width:0 0 0.5 0;");
        return new VBox(0, toolbar, table);
    }

    private GlCode sel() { return table.getSelectionModel().getSelectedItem(); }

    private void loadList() {
        status("Loading…", false);
        exec.submit(() -> {
            try {
                List<GlCode> data = svc.findAll(session.getCompanyNo());
                Platform.runLater(() -> { rows.setAll(data); status(data.size() + " code(s)", false); });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    private void confirmDelete(GlCode c) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete sub account code " + String.format("%04d", c.subAcct) + " — " + c.desc1 + "?\n\n"
            + "This does not affect any chart-of-accounts sub-accounts already using this code.",
            ButtonType.YES, ButtonType.NO);
        a.setTitle("Confirm Delete"); a.setHeaderText(null);
        a.showAndWait().ifPresent(b -> { if (b == ButtonType.YES) {
            exec.submit(() -> {
                try { svc.delete(session.getCompanyNo(), c.subAcct);
                    Platform.runLater(() -> { loadList(); status("Deleted " + c.subAcct, false); });
                } catch (Exception ex) { Platform.runLater(() -> status("Delete error: " + ex.getMessage(), true)); }
            });
        }});
    }

    private void openDialog(GlCode existing, Window owner) {
        boolean isNew = existing == null;
        GlCode c = isNew ? new GlCode() : existing;

        Stage dlg = new Stage();
        dlg.initOwner(owner); dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isNew ? "Add" : "Edit") + " Sub Account Code");

        TextField fSub  = tf(isNew ? "" : String.valueOf(c.subAcct), 6);
        fSub.setEditable(isNew); fSub.setDisable(!isNew);
        TextField fDesc = tf(c.desc1, 35);

        GridPane g = new GridPane(); g.setHgap(10); g.setVgap(10); g.setPadding(new Insets(18));
        g.add(lbl("Sub Account *:"), 0, 0); g.add(fSub, 1, 0);
        g.add(lbl("Description *:"), 0, 1); g.add(fDesc, 1, 1);

        Button ok = btnPrimary(isNew ? "Add" : "Save");
        Button cancel = btnSecondary("Cancel");
        ok.setDefaultButton(true);
        cancel.setOnAction(e -> dlg.close());
        ok.setOnAction(e -> {
            Integer sub = parseInt(fSub.getText());
            if (sub == null || sub < 0 || sub > 9999) { markError(fSub, "Enter a sub account code 0-9999."); return; }
            String desc = fDesc.getText().trim();
            if (desc.isEmpty()) { markError(fDesc, "Description is required."); return; }
            int co = session.getCompanyNo();
            String user = session.getUserId();
            ok.setDisable(true);
            exec.submit(() -> {
                try {
                    if (isNew && svc.exists(co, sub)) {
                        Platform.runLater(() -> { ok.setDisable(false); markError(fSub, "Sub account " + sub + " already exists."); });
                        return;
                    }
                    c.subAcct = sub; c.desc1 = desc;
                    svc.save(co, c, isNew, user);
                    Platform.runLater(() -> {
                        dlg.close(); loadList();
                        status((isNew ? "Added " : "Updated ") + sub, false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> { ok.setDisable(false); status("Save error: " + ex.getMessage(), true); });
                }
            });
        });
        HBox bar = new HBox(10, ok, cancel);
        bar.setAlignment(Pos.CENTER_RIGHT); bar.setPadding(new Insets(10, 16, 14, 16));
        VBox root = new VBox(0, g, bar);
        dlg.setScene(new Scene(root, 380, 160));
        dlg.showAndWait();
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    private TableColumn<GlCode, String> col(String h, Function<GlCode, String> fn, double w) {
        TableColumn<GlCode, String> c = new TableColumn<>(h);
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
    private static String safe(String s) { return s == null ? "" : s; }

    private Label lbl(String s) { Label l = new Label(s); l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;"); l.setMinWidth(100); return l; }
    private TextField tf(String v, int max) { TextField f = new TextField(v == null ? "" : v.trim()); f.setPrefWidth(Math.min(max * 9 + 20, 300)); return f; }
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
