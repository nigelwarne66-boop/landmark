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

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Wave 2 reusable preview dialog — shows the user EXACTLY which rows the
 * batch is about to change before any writes happen. User clicks
 * {@code Apply} to proceed, {@code Cancel} to back out without modifying
 * anything. Modal — caller blocks on {@link #showAndAwait}.
 *
 * <p>Used by all seven Wave 2 batch programs (PASU14, PASU11, PASU15,
 * PAEM60, PAEM11, PASU55, PAPC01). Each program builds a list of preview
 * rows describing the change, hands the list to this dialog, and only
 * proceeds with the real DB writes if {@code showAndAwait} returns
 * {@code true}.
 *
 * @param <T> the row type — typically a record with {@code key},
 *           {@code before}, {@code after} fields, but any value works
 */
public class BatchPreviewDialog<T> {

    /** Column spec — header text plus an extractor that pulls the cell value. */
    public record Column<T>(String header, Function<T, String> extract) {}

    private final String        title;
    private final String        summary;
    private final List<T>       rows;
    private final List<Column<T>> columns;
    private final String        applyButtonText;

    /** Programmatic result. {@code null} until the user closes the dialog. */
    private Boolean result;

    public BatchPreviewDialog(String title, String summary, List<T> rows,
                              List<Column<T>> columns) {
        this(title, summary, rows, columns, null);
    }

    public BatchPreviewDialog(String title, String summary, List<T> rows,
                              List<Column<T>> columns, String applyButtonText) {
        this.title    = title;
        this.summary  = summary;
        this.rows     = rows == null ? new ArrayList<>() : rows;
        this.columns  = columns == null ? new ArrayList<>() : columns;
        this.applyButtonText = applyButtonText;
    }

    /**
     * Show modally and block until the user closes the dialog.
     *
     * @return {@code true} if the user clicked Apply, {@code false} on
     *         Cancel or Escape.
     */
    public boolean showAndAwait(Window owner) {
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setTitle(title);
        stage.setMinWidth(720);
        stage.setMinHeight(460);

        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setCenter(buildTable());
        root.setBottom(buildFooter(stage));

        Scene scene = new Scene(root, 820, 540);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) { result = Boolean.FALSE; stage.close(); }
        });

        stage.setScene(scene);
        stage.showAndWait();
        return Boolean.TRUE.equals(result);
    }

    private VBox buildHeader() {
        Label lblTitle = new Label(title);
        lblTitle.setStyle("-fx-font-weight:bold;-fx-font-size:14px;");

        Label lblSummary = new Label(summary == null ? "" : summary);
        lblSummary.setWrapText(true);

        Label lblCount = new Label(rows.size() + " row(s) affected");
        lblCount.setStyle("-fx-text-fill:#444;-fx-font-style:italic;");

        VBox box = new VBox(6, lblTitle, lblSummary, lblCount);
        box.setPadding(new Insets(14, 14, 8, 14));
        return box;
    }

    private TableView<T> buildTable() {
        TableView<T> table = new TableView<>();
        table.setItems(FXCollections.observableArrayList(rows));
        table.setPlaceholder(new Label("No rows would be affected by this batch."));

        for (Column<T> c : columns) {
            TableColumn<T, String> col = new TableColumn<>(c.header());
            col.setCellValueFactory(cd ->
                new SimpleStringProperty(safe(c.extract().apply(cd.getValue()))));
            col.setPrefWidth(140);
            table.getColumns().add(col);
        }
        return table;
    }

    private HBox buildFooter(Stage stage) {
        Button btnApply = new Button(
            applyButtonText == null
                ? ("Apply " + rows.size() + " change" + (rows.size() == 1 ? "" : "s"))
                : applyButtonText);
        btnApply.setDefaultButton(true);
        btnApply.setDisable(rows.isEmpty());
        btnApply.setOnAction(e -> { result = Boolean.TRUE;  stage.close(); });

        Button btnCancel = new Button("Cancel");
        btnCancel.setCancelButton(true);
        btnCancel.setOnAction(e -> { result = Boolean.FALSE; stage.close(); });

        HBox bar = new HBox(10, btnCancel, btnApply);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(12, 14, 12, 14));
        return bar;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
