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
package com.landmarksoftware.ui.bas;

import com.landmarksoftware.desktop.AppMode;
import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.model.bas.BasReasonCode;
import com.landmarksoftware.service.bas.BasReasonCodeService;
import com.landmarksoftware.ui.components.CommandBar;
import com.landmarksoftware.ui.components.LmButton;
import com.landmarksoftware.ui.components.LmTableView;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CPBA02 — BAS Reason Codes Maintenance.
 *
 * <p>Maintains ATO variation-reason codes in {@code cpbascd}, restricted in
 * this UI to exactly two categories: {@code T4} (Income Tax Instalment
 * Variation Reason Codes) and {@code F4} (Fringe Benefits Tax Instalment
 * Variation Reason Codes). The table also holds {@code 7A}/{@code 7C}/
 * {@code 7D} rows used elsewhere (CPBA10) — this screen never creates,
 * edits, or deletes those.
 *
 * <p>Unlike the COBOL, the category (T4/F4) picker is a toggle at the top
 * of the screen rather than a separate popup dialog.
 *
 * Screen layout:
 *   top     — header bar
 *   toolbar — Add | Edit | Delete | Refresh
 *   category — T4 / F4 toggle
 *   centre  — TableView of reason codes for the selected category
 *   bottom  — status bar
 */
@Component
public class BasReasonCodeMaintenanceController {

    private static final String CAT_T4 = "T4";
    private static final String CAT_F4 = "F4";

    private final BasReasonCodeService basReasonCodeService;
    private final AppSession           appSession;

    private final ObservableList<BasReasonCode> rows = FXCollections.observableArrayList();
    private LmTableView<BasReasonCode>          table;
    private Label                               lblStatus;
    private String                              currentCategory = CAT_T4;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cpba02-thread");
        t.setDaemon(true);
        return t;
    });

    public BasReasonCodeMaintenanceController(BasReasonCodeService basReasonCodeService,
                                                AppSession appSession) {
        this.basReasonCodeService = basReasonCodeService;
        this.appSession           = appSession;
    }

    // ── Entry point ───────────────────────────────────────────────────────

    public Scene buildScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildHeader());
        root.setCenter(buildContent(stage));
        root.setBottom(buildStatusBar());

        loadList();

        Scene scene = new Scene(root, 780, 540);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        scene.getStylesheets().add(
            getClass().getResource(AppMode.themeCssPath()).toExternalForm());
        return scene;
    }

    // ── Header ────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("BAS Reason Codes Maintenance");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("CPBA02 · " + appSession.getCompanyName());
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        VBox titleBox = new VBox(2, title, sub);

        HBox bar = new HBox(titleBox);
        bar.setPadding(new Insets(14, 20, 14, 20));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle(
            "-fx-background-color:#FFFFFF;" +
            "-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;" +
            "-fx-border-width:0 0 0.5 0;");
        return bar;
    }

    // ── Content — toolbar + category toggle + table ─────────────────────────

    private VBox buildContent(Stage stage) {
        table = new LmTableView<>(rows);
        VBox.setVgrow(table, Priority.ALWAYS);
        table.setEmptyState("fth-file-text", "No reason codes found for this category.",
            "+ Add reason code", () -> openDialog(null, stage));

        TableColumn<BasReasonCode, String> colCode =
            LmTableView.codeColumn("Code", BasReasonCode::reasonCode, 90);
        LmTableView.markDrillable(colCode);   // double-click on the row opens the edit dialog

        table.getColumns().addAll(List.of(
            colCode,
            LmTableView.textColumn("Description", BasReasonCode::description, 500)
        ));

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null)
                openDialog(table.getSelectionModel().getSelectedItem(), stage);
        });

        Button btnAdd  = LmButton.primary("+ Add", () -> openDialog(null, stage));
        Button btnEdit = LmButton.secondary("✎ Edit", () -> {
            BasReasonCode sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openDialog(sel, stage);
            else showInfo("Edit", "Select a reason code to edit.");
        });
        Button btnDel  = LmButton.danger("✕ Delete", () -> {
            BasReasonCode sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDelete(sel);
            else showInfo("Delete", "Select a reason code to delete.");
        });
        Button btnRef  = LmButton.ghost("↺ Refresh", this::loadList);

        HBox toolbar = CommandBar.builder()
            .primary(btnAdd)
            .secondary(btnEdit)
            .secondary(btnDel)
            .ghost(btnRef)
            .build();

        HBox categoryToggle = buildCategoryToggle();

        return new VBox(0, toolbar, categoryToggle, table);
    }

    private HBox buildCategoryToggle() {
        HBox bar = new HBox(0);
        bar.setStyle("-fx-background-color:#FFFFFF;" +
            "-fx-border-color:transparent transparent rgba(0,0,0,.08) transparent;" +
            "-fx-border-width:0 0 0.5 0;");

        ToggleGroup tg = new ToggleGroup();
        ToggleButton tbT4 = new ToggleButton("T4 — Income Tax Instalment Variation");
        ToggleButton tbF4 = new ToggleButton("F4 — Fringe Benefits Tax Instalment Variation");
        for (ToggleButton tb : List.of(tbT4, tbF4)) {
            tb.setToggleGroup(tg);
            tb.setStyle(
                "-fx-background-color:transparent;-fx-border-color:transparent;" +
                "-fx-padding:8 14;-fx-cursor:hand;-fx-font-size:12px;");
        }
        tbT4.setSelected(true);
        tbT4.selectedProperty().addListener((o, ov, nv) -> {
            if (nv) { currentCategory = CAT_T4; loadList(); }
        });
        tbF4.selectedProperty().addListener((o, ov, nv) -> {
            if (nv) { currentCategory = CAT_F4; loadList(); }
        });
        bar.getChildren().addAll(tbT4, tbF4);
        return bar;
    }

    private static String categoryLabel(String basCode) {
        return CAT_F4.equals(basCode)
            ? "Fringe Benefits Tax Instalment Variation Reason Codes"
            : "Income Tax Instalment Variation Reason Codes";
    }

    // ── Data operations ───────────────────────────────────────────────────

    private void loadList() {
        status("Loading…", false);
        String cat = currentCategory;
        exec.submit(() -> {
            try {
                List<BasReasonCode> data = basReasonCodeService.findByCategory(cat);
                Platform.runLater(() -> {
                    rows.setAll(data);
                    status(data.size() + " code(s)", false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    private void confirmDelete(BasReasonCode r) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete this code?", ButtonType.YES, ButtonType.NO);
        a.setTitle("Confirm Delete");
        a.setHeaderText(null);
        a.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                String cat = currentCategory;
                exec.submit(() -> {
                    try {
                        basReasonCodeService.delete(cat, r.reasonCode());
                        Platform.runLater(() -> {
                            loadList();
                            status("Deleted: " + r.reasonCode(), false);
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() ->
                            status("Delete error: " + ex.getMessage(), true));
                    }
                });
            }
        });
    }

    // ── Add/Edit dialog ──────────────────────────────────────────────────

    private void openDialog(BasReasonCode existing, Window owner) {
        boolean isAdd = (existing == null);
        String cat = currentCategory;

        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isAdd ? "Add" : "Edit") + " Reason Code — " + cat);
        dlg.setResizable(false);

        TextField fCode  = tf(isAdd ? "" : existing.reasonCode(), 2);
        fCode.setEditable(isAdd); fCode.setDisable(!isAdd);
        TextField fDesc1 = tf(isAdd ? "" : existing.desc1(), 35);
        TextField fDesc2 = tf(isAdd ? "" : existing.desc2(), 35);

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));
        form.getChildren().add(headerLine(cat + " — " + categoryLabel(cat)));
        form.getChildren().addAll(
            twoColRow("Reason Code *:",      fCode),
            twoColRow("Description Line 1 *:", fDesc1),
            twoColRow("Description Line 2:",   fDesc2));

        Button btnSave   = btnPrimary(isAdd ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(e -> dlg.close());

        btnSave.setOnAction(e -> {
            String code = fCode.getText().trim().toUpperCase();
            if (code.isEmpty()) { markError(fCode, "Reason Code is required."); return; }
            if (code.length() > 2) { markError(fCode, "Reason Code must be 2 characters or fewer."); return; }
            clearError(fCode);

            String d1 = fDesc1.getText().trim();
            if (d1.isEmpty()) { markError(fDesc1, "Description Line 1 is required."); return; }
            clearError(fDesc1);

            String d2 = fDesc2.getText().trim();
            String userId = appSession.getUserId();

            if (isAdd) {
                // Off-thread duplicate check before writing.
                exec.submit(() -> {
                    boolean dup = basReasonCodeService.exists(cat, code);
                    Platform.runLater(() -> {
                        if (dup) {
                            markError(fCode, "Already on file.");
                        } else {
                            dlg.close();
                            exec.submit(() -> {
                                try {
                                    basReasonCodeService.insert(cat, code, d1, d2, userId);
                                    Platform.runLater(() -> {
                                        loadList();
                                        status("Added: " + code, false);
                                    });
                                } catch (Exception ex) {
                                    Platform.runLater(() ->
                                        status("Save error: " + ex.getMessage(), true));
                                }
                            });
                        }
                    });
                });
            } else {
                // Defensive existence check — the key is read-only and the
                // list is freshly loaded, so this should never fire in
                // normal use.
                exec.submit(() -> {
                    boolean stillExists = basReasonCodeService.exists(cat, code);
                    Platform.runLater(() -> {
                        if (!stillExists) {
                            showInfo("Not on file",
                                "Reason code " + code + " is not on file.");
                        } else {
                            dlg.close();
                            exec.submit(() -> {
                                try {
                                    basReasonCodeService.update(cat, code, d1, d2, userId);
                                    Platform.runLater(() -> {
                                        loadList();
                                        status("Saved: " + code, false);
                                    });
                                } catch (Exception ex) {
                                    Platform.runLater(() ->
                                        status("Save error: " + ex.getMessage(), true));
                                }
                            });
                        }
                    });
                });
            }
        });

        HBox btnBar = new HBox(10, btnSave, btnCancel);
        btnBar.setPadding(new Insets(10, 20, 16, 20));
        btnBar.setAlignment(Pos.CENTER_RIGHT);
        btnBar.setStyle(
            "-fx-background-color:#F2F1EC;" +
            "-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
            "-fx-border-width:0.5 0 0 0;");

        VBox root = new VBox(0, form, btnBar);
        dlg.setScene(new Scene(root, 460, 260));
        dlg.showAndWait();
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private Label headerLine(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#1A6EF5;");
        l.setWrapText(true);
        return l;
    }

    /** Two-column form row: 140-wide label + control. */
    private HBox twoColRow(String label, Node control) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(150);
        HBox row = new HBox(10, l, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private TextField tf(String value, int maxLen) {
        TextField f = new TextField(value == null ? "" : value.trim());
        f.setPrefWidth(Math.min(maxLen * 9 + 40, 300));
        return f;
    }

    private Button btnPrimary(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color:#1A6EF5;-fx-text-fill:white;-fx-font-weight:bold;" +
                   "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:6 16;-fx-cursor:hand;");
        return b;
    }

    private Button btnSecondary(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color:white;-fx-text-fill:#374151;-fx-border-color:#D0CFC8;" +
                   "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:5 14;-fx-cursor:hand;");
        return b;
    }

    private static void markError(TextField fld, String msg) {
        fld.setStyle("-fx-border-color:#DC2626;-fx-border-width:2;");
        fld.requestFocus(); fld.selectAll();
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setTitle("Validation"); a.setHeaderText(null); a.showAndWait();
    }

    private static void clearError(TextField fld) {
        fld.setStyle("-fx-border-color:#D1D5DB;-fx-border-width:1.5;");
    }

    private void showInfo(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle(title); a.setHeaderText(null); a.showAndWait();
    }

    // ── Status bar ────────────────────────────────────────────────────────

    private HBox buildStatusBar() {
        lblStatus = new Label("Ready");
        lblStatus.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        HBox bar = new HBox(lblStatus);
        bar.setPadding(new Insets(5, 16, 5, 16));
        bar.setStyle(
            "-fx-background-color:#F8F8F6;" +
            "-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
            "-fx-border-width:0.5 0 0 0;");
        return bar;
    }

    private void status(String msg, boolean err) {
        Platform.runLater(() -> {
            lblStatus.setText(msg);
            lblStatus.setStyle("-fx-font-size:11px;-fx-text-fill:" +
                (err ? "#C0392B" : "#888780") + ";");
        });
    }
}
