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

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.model.bas.BasRun;
import com.landmarksoftware.service.bas.BasProcessingService;
import com.landmarksoftware.service.bas.BasReasonCodeService;
import com.landmarksoftware.ui.components.CommandBar;
import com.landmarksoftware.ui.components.LmButton;
import com.landmarksoftware.ui.components.LmTableView;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CPBA10 — Business Activity Statement processing/generation.
 *
 * <p>Entry point for menu wiring elsewhere: {@link #buildScene(Stage)} — a
 * P1 list of {@code cpbashd} runs for the session company, scoped by a BAS
 * Group selector (sourced from {@code cpbasgr}). Toolbar: Create / Edit /
 * Delete / Inquire / Refresh.
 *
 * <ul>
 *   <li><b>Create</b> — {@link BasCreateDialog}: guards on {@code
 *       cpbasgr.current_bas_no}, previews the pull-in, commits via {@link
 *       BasProcessingService#createBas}.</li>
 *   <li><b>Edit</b> — {@link BasEditScreen}, writable, only for draft-status
 *       ({@code bas_status} blank) runs.</li>
 *   <li><b>Inquire</b> — the same {@link BasEditScreen}, all fields
 *       read-only, for any status.</li>
 *   <li><b>Delete</b> — {@link BasProcessingService#deleteBas}, blocked for
 *       committed ('C') or already-cancelled ('D') runs.</li>
 * </ul>
 */
@Component
public class BasProcessingController {

    private final BasProcessingService service;
    private final BasReasonCodeService reasonCodeService;
    private final DSLContext dsl;
    private final AppSession appSession;

    private final ObservableList<BasRun> rows = FXCollections.observableArrayList();
    private LmTableView<BasRun> table;
    private ComboBox<BasProcessingService.GroupOption> cbGroup;
    private Label lblStatus;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cpba10-thread");
        t.setDaemon(true);
        return t;
    });

    public BasProcessingController(BasProcessingService service, BasReasonCodeService reasonCodeService,
                                    DSLContext dsl, AppSession appSession) {
        this.service = service;
        this.reasonCodeService = reasonCodeService;
        this.dsl = dsl;
        this.appSession = appSession;
    }

    // ── Entry point ──────────────────────────────────────────────────────

    public Scene buildScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildHeader());
        root.setCenter(buildContent(stage));
        root.setBottom(buildStatusBar());

        loadGroups();

        Scene scene = new Scene(root, 1040, 640);
        scene.getStylesheets().add(getClass().getResource("/css/fixedassets.css").toExternalForm());
        scene.getStylesheets().add(getClass().getResource("/com/landmarksoftware/ui/css/landmark-theme.css").toExternalForm());
        return scene;
    }

    // ── Header ───────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("Business Activity Statement");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("CPBA10 · " + appSession.getCompanyName());
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

    // ── Content ──────────────────────────────────────────────────────────

    private VBox buildContent(Stage stage) {
        table = new LmTableView<>(rows);
        VBox.setVgrow(table, Priority.ALWAYS);
        table.setEmptyState("fth-file-text", "No BAS runs found. Select a BAS Group and click Create.", null, null);

        TableColumn<BasRun, String> colGroup = LmTableView.codeColumn("BAS Group", BasRun::basGroup, 90);
        LmTableView.markDrillable(colGroup);

        table.getColumns().addAll(List.of(
            colGroup,
            LmTableView.textColumn("BAS No", r -> String.valueOf(r.basNo()), 70),
            LmTableView.textColumn("Status", BasRun::statusLabel, 90),
            LmTableView.dateColumn("Period From", BasRun::fromDate, 100),
            LmTableView.dateColumn("Period To", BasRun::toDate, 100),
            LmTableView.numColumn("Net Amount", r -> BasUiSupport.moneyStr(r.netAmount()), 110),
            LmTableView.dateColumn("Due Date", BasRun::dueDate, 100)
        ));

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) {
                openForEditOrInquire(table.getSelectionModel().getSelectedItem(), stage);
            }
        });

        cbGroup = new ComboBox<>();
        cbGroup.setPromptText("Select BAS group");
        cbGroup.setPrefWidth(240);
        cbGroup.valueProperty().addListener((o, ov, nv) -> loadList());

        Button btnCreate = LmButton.primary("+ Create", () -> openCreate(stage));
        Button btnEdit = LmButton.secondary("✎ Edit", () -> {
            BasRun sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) { showInfo("Edit", "Select a BAS run to edit."); return; }
            openForEditOrInquire(sel, stage);
        });
        Button btnInquire = LmButton.secondary("👁 Inquire", () -> {
            BasRun sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) { showInfo("Inquire", "Select a BAS run to view."); return; }
            openEditScreen(sel, stage, true);
        });
        Button btnDelete = LmButton.danger("✕ Delete", () -> {
            BasRun sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) { showInfo("Delete", "Select a BAS run to delete."); return; }
            confirmDelete(sel);
        });
        Button btnRefresh = LmButton.ghost("↺ Refresh", this::loadList);

        HBox toolbar = CommandBar.builder()
            .primary(btnCreate)
            .secondary(btnEdit)
            .secondary(btnInquire)
            .secondary(btnDelete)
            .ghost(btnRefresh)
            .rightSlot(cbGroup)
            .build();

        return new VBox(0, toolbar, table);
    }

    private HBox buildStatusBar() {
        lblStatus = new Label("Ready");
        lblStatus.setStyle("-fx-text-fill:#888780;-fx-font-size:11px;");
        HBox bar = new HBox(lblStatus);
        bar.setPadding(new Insets(6, 20, 6, 20));
        bar.setStyle("-fx-background-color:#FFFFFF;" +
            "-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
            "-fx-border-width:0.5 0 0 0;");
        return bar;
    }

    // ── Data operations ──────────────────────────────────────────────────

    private void loadGroups() {
        status("Loading BAS groups…", false);
        exec.submit(() -> {
            List<BasProcessingService.GroupOption> groups = service.getBasGroups();
            Platform.runLater(() -> {
                cbGroup.getItems().setAll(groups);
                if (!groups.isEmpty()) cbGroup.getSelectionModel().selectFirst();
                else loadList();
            });
        });
    }

    private void loadList() {
        status("Loading…", false);
        BasProcessingService.GroupOption group = cbGroup.getValue();
        exec.submit(() -> {
            List<BasRun> data = service.findRuns(group == null ? null : group.code());
            Platform.runLater(() -> {
                rows.setAll(data);
                status(data.size() + " BAS run(s)", false);
            });
        });
    }

    private void openCreate(Stage stage) {
        BasProcessingService.GroupOption group = cbGroup.getValue();
        if (group == null) {
            showInfo("Create BAS", "Select a BAS Group first.");
            return;
        }
        BasCreateDialog.show(stage, service, appSession.getUserId(), group, this::loadList);
    }

    private void openForEditOrInquire(BasRun run, Stage stage) {
        if (run.isCommitted() || run.isCancelled()) {
            openEditScreen(run, stage, true);
        } else {
            openEditScreen(run, stage, false);
        }
    }

    private void openEditScreen(BasRun run, Stage stage, boolean readOnly) {
        BasEditScreen.show(stage, service, reasonCodeService, dsl, run.basGroup(), run.basNo(),
            appSession.getUserId(), readOnly, this::loadList);
    }

    private void confirmDelete(BasRun run) {
        if (run.isCommitted()) { showInfo("Delete", "Statement committed — no changes"); return; }
        if (run.isCancelled()) { showInfo("Delete", "Statement cancelled — no changes"); return; }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete BAS " + run.basGroup() + "/" + run.basNo() + "?", ButtonType.YES, ButtonType.NO);
        a.setTitle("Confirm Delete");
        a.setHeaderText(null);
        a.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.YES) return;
            exec.submit(() -> {
                try {
                    service.deleteBas(run.basGroup(), run.basNo(), appSession.getUserId());
                    Platform.runLater(() -> { loadList(); status("Deleted: " + run.basGroup() + "/" + run.basNo(), false); });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        status("Delete error: " + ex.getMessage(), true);
                        showError("Delete failed", ex.getMessage());
                    });
                }
            });
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void status(String msg, boolean error) {
        lblStatus.setText(msg);
        lblStatus.setStyle("-fx-font-size:11px;-fx-text-fill:" + (error ? "#DC2626" : "#888780") + ";");
    }

    private void showInfo(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.showAndWait();
    }

    private void showError(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.showAndWait();
    }
}
