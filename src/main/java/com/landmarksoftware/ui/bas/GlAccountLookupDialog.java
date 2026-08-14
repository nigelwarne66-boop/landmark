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

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jooq.Condition;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static com.landmarksoftware.db.tables.Glchart.GLCHART;

/**
 * Shared GL account (glchart) live-search picker + direct-entry validator.
 *
 * <p>Used by every screen that needs a "main + sub" GL account field with a
 * magnifier-button picker and a description lookup — CPBA01 (this module),
 * and CPBA07/CPBA10 (built in parallel). Modelled on
 * {@code com.landmarksoftware.payroll.ui.FundLookupDialog}'s modal-Stage /
 * debounced-search / TableView shape.
 *
 * <p>Only accounts with {@code fin_acct_flag='Y'} and {@code posting_flag<>'N'}
 * are offered/considered valid — matches the validation rule every caller is
 * expected to enforce on GL account fields ("Not on file" / "Not a financial
 * account" / "Not a posting account").
 */
public final class GlAccountLookupDialog {

    private GlAccountLookupDialog() { }

    /** A single glchart row — {@code acctMain}/{@code acctSub} are the two halves of the account number. */
    public record AccountRow(int companyNo, int acctMain, int acctSub, String desc) { }

    /**
     * Opens a modal searchable picker (debounced TextField filter + TableView
     * of Account/Description) for the given company's GL accounts. Calls
     * {@code onPick} with the selection on double-click or the Select
     * button; the dialog closes itself either way (Cancel/Esc closes with
     * no callback).
     */
    public static void show(Window owner, DSLContext dsl, int companyNo, Consumer<AccountRow> onPick) {
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setTitle("GL Account Lookup — company " + companyNo);
        stage.setMinWidth(640);
        stage.setMinHeight(420);

        ObservableList<AccountRow> rows = FXCollections.observableArrayList();
        TableView<AccountRow> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No accounts match"));

        TableColumn<AccountRow, String> colAcct = new TableColumn<>("Account");
        colAcct.setCellValueFactory(p -> new SimpleStringProperty(
            p.getValue().acctMain() + "-" + p.getValue().acctSub()));
        colAcct.setPrefWidth(140);
        TableColumn<AccountRow, String> colDesc = new TableColumn<>("Description");
        colDesc.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().desc()));
        colDesc.setPrefWidth(400);
        table.getColumns().addAll(List.of(colAcct, colDesc));

        TextField txtSearch = new TextField();
        txtSearch.setPromptText("Type to filter by account no / description…");
        Label lblStatus = new Label("Loading…");
        lblStatus.setStyle("-fx-text-fill:#888888;-fx-font-size:11px;");

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gl-acct-lookup");
            t.setDaemon(true);
            return t;
        });
        Future<?>[] pending = new Future<?>[1];
        Runnable[] doSearch = new Runnable[1];

        doSearch[0] = () -> {
            String filter = txtSearch.getText() == null ? "" : txtSearch.getText().trim();
            lblStatus.setText("Searching…");
            exec.submit(() -> {
                try {
                    List<AccountRow> results = search(dsl, companyNo, filter);
                    Platform.runLater(() -> {
                        rows.setAll(results);
                        lblStatus.setText(results.size() + " account" +
                            (results.size() == 1 ? "" : "s") + " found");
                        if (!results.isEmpty()) table.getSelectionModel().selectFirst();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> lblStatus.setText("Error: " + ex.getMessage()));
                }
            });
        };

        txtSearch.textProperty().addListener((obs, ov, nv) -> {
            if (pending[0] != null) pending[0].cancel(false);
            pending[0] = exec.submit(() -> {
                try { Thread.sleep(180); } catch (InterruptedException ignored) { return; }
                Platform.runLater(doSearch[0]);
            });
        });

        Runnable[] selectAndClose = new Runnable[1];
        selectAndClose[0] = () -> {
            AccountRow sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            onPick.accept(sel);
            stage.close();
        };

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null)
                selectAndClose[0].run();
        });

        Button btnSelect = new Button("Select");
        btnSelect.setStyle("-fx-background-color:#1A6EF5;-fx-text-fill:white;-fx-font-weight:bold;" +
                           "-fx-background-radius:7;-fx-padding:6 16;-fx-cursor:hand;");
        btnSelect.setOnAction(e -> selectAndClose[0].run());

        Button btnCancel = new Button("Cancel");
        btnCancel.setStyle("-fx-background-color:white;-fx-text-fill:#374151;-fx-border-color:#D0CFC8;" +
                           "-fx-background-radius:7;-fx-padding:5 14;-fx-cursor:hand;");
        btnCancel.setOnAction(e -> stage.close());

        HBox footer = new HBox(10, btnSelect, btnCancel);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(10, 14, 12, 14));
        footer.setStyle("-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
                        "-fx-border-width:0.5 0 0 0;");

        VBox top = new VBox(6, new Label("Search:"), txtSearch, lblStatus);
        top.setPadding(new Insets(14, 14, 8, 14));

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(table);
        root.setBottom(footer);

        Scene scene = new Scene(root, 680, 440);
        scene.getStylesheets().add(
            GlAccountLookupDialog.class.getResource("/css/fixedassets.css").toExternalForm());
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.close();
            if (e.getCode() == KeyCode.ENTER) selectAndClose[0].run();
        });

        stage.setScene(scene);
        stage.setOnHidden(e -> exec.shutdownNow());
        stage.show();

        Platform.runLater(() -> { txtSearch.requestFocus(); doSearch[0].run(); });
    }

    /**
     * Validates a specific main+sub account for a company: must exist in
     * glchart, {@code fin_acct_flag='Y'}, {@code posting_flag<>'N'}. Returns
     * the resolved {@link AccountRow} if valid, or {@code null} if not
     * (caller distinguishes "not on file" vs "not financial" vs "not
     * posting" itself if it needs the granular message; treating {@code null}
     * as a generic "not on file" is fine for most callers).
     */
    public static AccountRow lookup(DSLContext dsl, int companyNo, int acctMain, int acctSub) {
        return dsl.select(GLCHART.COMPANY_NO, GLCHART.ACCT_MAIN_NO, GLCHART.ACCT_SUB_NO, GLCHART.DESC1)
            .from(GLCHART)
            .where(GLCHART.COMPANY_NO.eq(companyNo)
                .and(GLCHART.ACCT_MAIN_NO.eq(acctMain))
                .and(GLCHART.ACCT_SUB_NO.eq(acctSub))
                .and(GLCHART.FIN_ACCT_FLAG.eq("Y"))
                .and(GLCHART.POSTING_FLAG.ne("N")))
            .fetchOptional()
            .map(r -> new AccountRow(r.get(GLCHART.COMPANY_NO), r.get(GLCHART.ACCT_MAIN_NO),
                r.get(GLCHART.ACCT_SUB_NO), trim(r.get(GLCHART.DESC1))))
            .orElse(null);
    }

    private static List<AccountRow> search(DSLContext dsl, int companyNo, String filter) {
        Condition cond = GLCHART.COMPANY_NO.eq(companyNo)
            .and(GLCHART.FIN_ACCT_FLAG.eq("Y"))
            .and(GLCHART.POSTING_FLAG.ne("N"));
        if (filter != null && !filter.isBlank()) {
            String f = "%" + filter.trim() + "%";
            cond = cond.and(GLCHART.DESC1.likeIgnoreCase(f)
                .or(GLCHART.ALPHA_CODE.likeIgnoreCase(f))
                .or(GLCHART.ACCT_MAIN_NO.cast(String.class).like(f)));
        }
        List<AccountRow> out = new ArrayList<>();
        dsl.select(GLCHART.COMPANY_NO, GLCHART.ACCT_MAIN_NO, GLCHART.ACCT_SUB_NO, GLCHART.DESC1)
           .from(GLCHART)
           .where(cond)
           .orderBy(GLCHART.ACCT_MAIN_NO, GLCHART.ACCT_SUB_NO)
           .fetch()
           .forEach(r -> out.add(new AccountRow(r.get(GLCHART.COMPANY_NO), r.get(GLCHART.ACCT_MAIN_NO),
               r.get(GLCHART.ACCT_SUB_NO), trim(r.get(GLCHART.DESC1)))));
        return out;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
