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
package com.landmarksoftware.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import com.landmarksoftware.service.CodeLookupService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Reusable live-search lookup dialog.
 *
 * Opens as a modal window. User types in a search box; results filter
 * as they type. Double-click or Enter selects a row and populates the
 * calling field.
 *
 * Supports five lookup types, each querying a different table:
 *   ASSET      — FAASSET   (asset_no, desc_1, loc_code, grp_code)
 *   LOCATION   — FACODLO   (loc_code, description)
 *   GROUP      — FACODGR   (grp_code, description)
 *   SUBGROUP   — FACODSG   (subgrp_code, description)
 *   DEPARTMENT — FACODDT   (dept_code, description)
 */
public class LookupDialog {

    // ── Lookup types ──────────────────────────────────────────────────
    public enum LookupType {
        ASSET("Asset", "asset_no"),
        LOCATION("Location", "loc_code"),
        GROUP("Group", "grp_code"),
        SUBGROUP("Sub-Group", "subgrp_code"),
        DEPARTMENT("Department", "dept_code"),
        STOCKTAKE_SITE("Stocktake Site", "stake_site_code"),
        INSURANCE_TYPE("Insurance Type", "ins_type_code"),
        DEPN_CODE("Depreciation Code", "depn_code"),
        PAYGROUP("Pay Group", "pay_group"),
        AWARD("Award", "award_code"),
        JOB_CLASS("Job Class", "job_class_code"),
        SUPER_PAY_CODE("Super Pay Code", "pay_code"),
        TAX_SCALE("Tax Scale", "scale_no");

        final String label;
        final String codeColumn;

        LookupType(String label, String codeColumn) {
            this.label = label;
            this.codeColumn = codeColumn;
        }
    }

    // ── Column definition for the results table ───────────────────────
    record Column(String header, String key) {}

    // ── State ─────────────────────────────────────────────────────────
    private final CodeLookupService     lookupService;
    private final LookupType            type;
    private final int                   companyNo;
    private final Consumer<String>      onSelect;   // called with the selected code

    private TextField                   txtSearch;
    private TableView<Map<String, String>> table;
    private Label                       lblStatus;
    private final ObservableList<Map<String, String>> rows =
            FXCollections.observableArrayList();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lookup-search");
        t.setDaemon(true);
        return t;
    });
    private Future<?> pendingSearch;

    // ── Constructor ───────────────────────────────────────────────────

    public LookupDialog(CodeLookupService lookupService, LookupType type,
                        int companyNo, Consumer<String> onSelect) {
        this.lookupService = lookupService;
        this.type      = type;
        this.companyNo = companyNo;
        this.onSelect  = onSelect;
    }

    // ── Show ──────────────────────────────────────────────────────────

    public void show(Window owner) {
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setTitle(type.label + " Lookup");
        stage.setMinWidth(560);
        stage.setMinHeight(440);

        BorderPane root = new BorderPane();
        root.setTop(buildSearchBar());
        root.setCenter(buildTable());
        root.setBottom(buildFooter(stage));

        Scene scene = new Scene(root, 580, 460);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());

        // Keyboard: Escape = close, Enter = select
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.close();
            if (e.getCode() == KeyCode.ENTER)  selectAndClose(stage);
        });

        stage.setScene(scene);
        stage.show();

        // Load all records on open, focus search box
        Platform.runLater(() -> {
            txtSearch.requestFocus();
            search("");
        });
    }

    // ── Search bar ────────────────────────────────────────────────────

    private VBox buildSearchBar() {
        txtSearch = new TextField();
        txtSearch.setPromptText("Type to filter " + type.label.toLowerCase() + "s…");
        txtSearch.getStyleClass().add("text-field");

        // Live search — fire on every keystroke with a short debounce
        txtSearch.textProperty().addListener((obs, oldVal, newVal) -> {
            if (pendingSearch != null) pendingSearch.cancel(false);
            pendingSearch = executor.submit(() -> {
                try { Thread.sleep(180); } catch (InterruptedException ignored) {}
                Platform.runLater(() -> search(newVal.trim()));
            });
        });

        lblStatus = new Label("Loading…");
        lblStatus.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");

        VBox bar = new VBox(6, new Label("Search:"), txtSearch, lblStatus);
        bar.setPadding(new Insets(14, 14, 8, 14));
        return bar;
    }

    // ── Results table ─────────────────────────────────────────────────

    private TableView<Map<String, String>> buildTable() {
        table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No results"));

        for (Column col : columns()) {
            TableColumn<Map<String, String>, String> tc = new TableColumn<>(col.header());
            tc.setCellValueFactory(p ->
                new SimpleStringProperty(p.getValue().getOrDefault(col.key(), "")));
            table.getColumns().add(tc);
        }

        // Double-click to select
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) {
                selectAndClose((Stage) table.getScene().getWindow());
            }
        });

        VBox wrap = new VBox(table);
        VBox.setVgrow(table, Priority.ALWAYS);
        wrap.setPadding(new Insets(0, 14, 0, 14));
        VBox.setVgrow(wrap, Priority.ALWAYS);
        return table;
    }

    // ── Footer ────────────────────────────────────────────────────────

    private HBox buildFooter(Stage stage) {
        Button btnSelect = new Button("Select");
        btnSelect.getStyleClass().add("btn-primary");
        btnSelect.setOnAction(e -> selectAndClose(stage));

        Button btnCancel = new Button("Cancel");
        btnCancel.getStyleClass().add("btn-secondary");
        btnCancel.setOnAction(e -> stage.close());

        HBox footer = new HBox(10, btnSelect, btnCancel);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(10, 14, 12, 14));
        footer.setStyle("-fx-border-color: #D0DDE8; -fx-border-width: 1 0 0 0;");
        return footer;
    }

    // ── Search execution ──────────────────────────────────────────────

    private void search(String filter) {
        lblStatus.setText("Searching…");
        executor.submit(() -> {
            try {
                List<Map<String, String>> results = fetchResults(filter);
                Platform.runLater(() -> {
                    rows.setAll(results);
                    lblStatus.setText(results.size() + " record" +
                        (results.size() == 1 ? "" : "s") + " found");
                    if (!results.isEmpty())
                        table.getSelectionModel().selectFirst();
                });
            } catch (Exception ex) {
                Platform.runLater(() ->
                    lblStatus.setText("Error: " + ex.getMessage()));
            }
        });
    }

    private List<Map<String, String>> fetchResults(String filter) {
        return switch (type) {
            case ASSET          -> lookupService.searchAssets(companyNo, filter);
            case LOCATION       -> lookupService.searchLocations(companyNo, filter);
            case GROUP          -> lookupService.searchGroups(companyNo, filter);
            case SUBGROUP       -> lookupService.searchSubgroups(companyNo, filter);
            case DEPARTMENT     -> lookupService.searchDepartments(companyNo, filter);
            case STOCKTAKE_SITE -> lookupService.searchStocktakeSites(companyNo, filter);
            case INSURANCE_TYPE -> lookupService.searchInsuranceTypes(companyNo, filter);
            case DEPN_CODE      -> lookupService.searchDepnCodes(companyNo, filter);
            case PAYGROUP       -> lookupService.searchPaygroups(companyNo, filter);
            case AWARD          -> lookupService.searchAwards(companyNo, filter);
            case JOB_CLASS      -> lookupService.searchJobClasses(companyNo, filter);
            case SUPER_PAY_CODE -> lookupService.searchSuperPayCodes(companyNo, filter);
            case TAX_SCALE      -> lookupService.searchTaxScales(companyNo, filter);
        };
    }

    // ── Select and close ──────────────────────────────────────────────

    private void selectAndClose(Stage stage) {
        Map<String, String> selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        String code = selected.getOrDefault(type.codeColumn, "");
        if (!code.isBlank()) {
            onSelect.accept(code);
            stage.close();
        }
    }

    // ── Column definitions per lookup type ────────────────────────────

    private List<Column> columns() {
        return switch (type) {
            case ASSET      -> List.of(
                new Column("Asset No",    "asset_no"),
                new Column("Description", "desc_1"),
                new Column("Location",    "loc_code"),
                new Column("Group",       "grp_code"),
                new Column("Status",      "asset_status"));
            case LOCATION   -> List.of(
                new Column("Location",    "loc_code"),
                new Column("Description", "desc1"));
            case GROUP      -> List.of(
                new Column("Group",       "grp_code"),
                new Column("Description", "desc1"));
            case SUBGROUP   -> List.of(
                new Column("Sub-Group",   "subgrp_code"),
                new Column("Description", "desc1"));
            case STOCKTAKE_SITE -> List.of(
                    new Column("Code", "stake_site_code"),
                    new Column("Description", "desc1"));
            case INSURANCE_TYPE -> List.of(
                    new Column("Code", "ins_type_code"),
                    new Column("Description", "desc1"));
            case DEPN_CODE -> List.of(
                    new Column("Code", "depn_code"),
                    new Column("Description", "desc1"));
            case DEPARTMENT -> List.of(
                new Column("Department",  "dept_code"),
                new Column("Description", "desc1"));
            case PAYGROUP   -> List.of(
                new Column("Pay Group",   "pay_group"),
                new Column("Description", "desc1"));
            case AWARD      -> List.of(
                new Column("Award",       "award_code"),
                new Column("Description", "desc1"));
            case JOB_CLASS  -> List.of(
                new Column("Award",       "award_code"),
                new Column("Job Class",   "job_class_code"),
                new Column("Description", "desc1"));
            case SUPER_PAY_CODE -> List.of(
                new Column("Pay Code",    "pay_code"),
                new Column("Description", "desc1"),
                new Column("Type",        "type"));
            case TAX_SCALE -> List.of(
                new Column("Scale",       "scale_no"),
                new Column("Description", "desc_1"));
        };
    }
}
