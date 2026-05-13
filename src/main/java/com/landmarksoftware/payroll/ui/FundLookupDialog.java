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
package com.landmarksoftware.payroll.ui;

import com.landmarksoftware.payroll.model.Fund;
import com.landmarksoftware.payroll.service.FundService;
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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Live-search picker for the pafunds super-fund master.
 *
 * Filters by APRA/SMSF indicator and presents the user with USI (APRA) or
 * ESA alias (SMSF) plus fund name / ABN / product. Selection returns the
 * full {@link Fund} to the caller so pacodes fund_* columns can be filled.
 */
public class FundLookupDialog {

    private final FundService     fundService;
    private final int             companyNo;
    private final String          fundTypeInd;   // "A" or "S"
    private final Consumer<Fund>  onSelect;

    private TextField                       txtSearch;
    private TableView<Fund>                 table;
    private Label                           lblStatus;
    private final ObservableList<Fund>      rows = FXCollections.observableArrayList();

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fund-lookup");
        t.setDaemon(true);
        return t;
    });
    private Future<?> pendingSearch;

    public FundLookupDialog(FundService fundService, int companyNo,
                            String fundTypeInd, Consumer<Fund> onSelect) {
        this.fundService = fundService;
        this.companyNo   = companyNo;
        this.fundTypeInd = fundTypeInd;
        this.onSelect    = onSelect;
    }

    public void show(Window owner) {
        boolean isApra = "A".equalsIgnoreCase(fundTypeInd);
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setTitle((isApra ? "APRA Fund (by USI)" : "SMSF (by ESA / ABN)") + " — Lookup");
        stage.setMinWidth(720);
        stage.setMinHeight(460);

        BorderPane root = new BorderPane();
        root.setTop(buildSearchBar(isApra));
        root.setCenter(buildTable(isApra));
        root.setBottom(buildFooter(stage));

        Scene scene = new Scene(root, 760, 480);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());

        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.close();
            if (e.getCode() == KeyCode.ENTER)  selectAndClose(stage);
        });

        stage.setScene(scene);
        stage.show();

        Platform.runLater(() -> { txtSearch.requestFocus(); search(""); });
    }

    private VBox buildSearchBar(boolean isApra) {
        txtSearch = new TextField();
        txtSearch.setPromptText("Type to filter by " +
            (isApra ? "USI / fund name / ABN" : "ESA / fund name / ABN") + "…");

        txtSearch.textProperty().addListener((obs, ov, nv) -> {
            if (pendingSearch != null) pendingSearch.cancel(false);
            pendingSearch = exec.submit(() -> {
                try { Thread.sleep(180); } catch (InterruptedException ignored) {}
                Platform.runLater(() -> search(nv.trim()));
            });
        });

        lblStatus = new Label("Loading…");
        lblStatus.setStyle("-fx-text-fill:#888888;-fx-font-size:11px;");

        VBox bar = new VBox(6, new Label("Search:"), txtSearch, lblStatus);
        bar.setPadding(new Insets(14, 14, 8, 14));
        return bar;
    }

    private TableView<Fund> buildTable(boolean isApra) {
        table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No funds match"));

        if (isApra) {
            addCol("USI",          f -> f.fundId,       160);
            addCol("Fund name",    f -> f.fundName,     240);
            addCol("Product",      f -> f.productName,  180);
            addCol("ABN",          f -> f.fundAbn,      120);
        } else {
            addCol("ESA alias",    f -> f.fundId,       240);
            addCol("SMSF ABN",     f -> f.smsfAbn,      130);
            addCol("Fund name",    f -> f.fundName,     230);
            addCol("Contact",      f -> f.contactName,  150);
        }

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null)
                selectAndClose((Stage) table.getScene().getWindow());
        });
        return table;
    }

    private void addCol(String header, java.util.function.Function<Fund,String> fn, double w) {
        TableColumn<Fund, String> c = new TableColumn<>(header);
        c.setCellValueFactory(p -> new SimpleStringProperty(
            fn.apply(p.getValue()) == null ? "" : fn.apply(p.getValue())));
        c.setPrefWidth(w);
        table.getColumns().add(c);
    }

    private HBox buildFooter(Stage stage) {
        Button btnSelect = new Button("Select");
        btnSelect.setStyle("-fx-background-color:#1A6EF5;-fx-text-fill:white;-fx-font-weight:bold;" +
                           "-fx-background-radius:7;-fx-padding:6 16;-fx-cursor:hand;");
        btnSelect.setOnAction(e -> selectAndClose(stage));

        Button btnCancel = new Button("Cancel");
        btnCancel.setStyle("-fx-background-color:white;-fx-text-fill:#374151;-fx-border-color:#D0CFC8;" +
                           "-fx-background-radius:7;-fx-padding:5 14;-fx-cursor:hand;");
        btnCancel.setOnAction(e -> stage.close());

        HBox footer = new HBox(10, btnSelect, btnCancel);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(10, 14, 12, 14));
        footer.setStyle("-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
                        "-fx-border-width:0.5 0 0 0;");
        return footer;
    }

    private void search(String filter) {
        lblStatus.setText("Searching…");
        exec.submit(() -> {
            try {
                List<Fund> results = fundService.searchByType(companyNo, fundTypeInd, filter);
                Platform.runLater(() -> {
                    rows.setAll(results);
                    lblStatus.setText(results.size() + " fund" +
                        (results.size() == 1 ? "" : "s") + " found");
                    if (!results.isEmpty()) table.getSelectionModel().selectFirst();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> lblStatus.setText("Error: " + ex.getMessage()));
            }
        });
    }

    private void selectAndClose(Stage stage) {
        Fund f = table.getSelectionModel().getSelectedItem();
        if (f == null) return;
        onSelect.accept(f);
        stage.close();
    }
}
