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

import com.landmarksoftware.db.tables.records.CpbastxRecord;
import com.landmarksoftware.service.bas.BasProcessingService;
import com.landmarksoftware.ui.components.CommandBar;
import com.landmarksoftware.ui.components.LmButton;
import com.landmarksoftware.ui.components.LmTableView;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jooq.DSLContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.landmarksoftware.db.tables.Cpbastx.CPBASTX;

/**
 * CPBA10 — P4: the transaction-line list for one {@code (bas_group, bas_no,
 * bas_code)}, opened either standalone or by clicking a transaction-driven
 * ATO label on {@link BasEditScreen}. Add/Edit/Delete all flow through
 * {@link BasProcessingService}'s per-line methods, each of which reclassifies
 * the header's accumulators and recalculates in one transaction.
 *
 * <p>Also hosts a lightweight S7 filter — an optional date range / source /
 * batch no / amount range popover, collapsed to one WHERE clause by {@link
 * BasProcessingService#getLines}.
 */
final class BasTransactionListDialog {

    private BasTransactionListDialog() { }

    static void show(Window owner, BasProcessingService service, DSLContext dsl, String basGroup, int basNo,
                      String basCode, String userId, boolean readOnly, Runnable onChanged) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Transaction Lines — " + basGroup + "/" + basNo + " — " + basCode);

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bas-p4-thread");
            t.setDaemon(true);
            return t;
        });

        ObservableList<CpbastxRecord> rows = FXCollections.observableArrayList();
        LmTableView<CpbastxRecord> table = new LmTableView<>(rows);
        table.setEmptyState("fth-file-text", "No transaction lines for this code.", null, null);
        table.getColumns().addAll(List.of(
            LmTableView.dateColumn("Trx Date", r -> r.get(CPBASTX.TRX_DATE), 100),
            LmTableView.dateColumn("Posting Date", r -> r.get(CPBASTX.POSTING_DATE), 100),
            LmTableView.numColumn("Gross Amount", r -> BasUiSupport.moneyStr(r.get(CPBASTX.TRX_GROSS_AMT)), 110),
            LmTableView.numColumn("Tax Amount", r -> BasUiSupport.moneyStr(r.get(CPBASTX.TAX_AMT)), 110),
            LmTableView.textColumn("GL Account", r -> glAcct(r), 110),
            LmTableView.textColumn("Reference", r -> ref(r), 200)
        ));

        Label lblStatus = new Label();
        lblStatus.setStyle("-fx-text-fill:#888780;-fx-font-size:11px;");

        BasProcessingService.LineFilter[] filterHolder = { null };

        Runnable[] loadRef = new Runnable[1];
        loadRef[0] = () -> {
            lblStatus.setText("Loading…");
            exec.submit(() -> {
                List<CpbastxRecord> data = service.getLines(basGroup, basNo, basCode, filterHolder[0]);
                Platform.runLater(() -> {
                    rows.setAll(data);
                    lblStatus.setText(data.size() + " line(s)");
                });
            });
        };
        Runnable load = loadRef[0];

        Button btnAdd = LmButton.primary("+ Add", () -> {
            if (readOnly) return;
            BasTransactionLineDialog.show(dlg, service, dsl, basGroup, basNo,
                basCode, null, userId, () -> { load.run(); if (onChanged != null) onChanged.run(); });
        });
        Button btnEdit = LmButton.secondary("✎ Edit", () -> {
            if (readOnly) return;
            CpbastxRecord sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) { BasUiSupport.showInfo("Edit", "Select a line to edit."); return; }
            BasTransactionLineDialog.show(dlg, service, dsl, basGroup, basNo,
                basCode, sel, userId, () -> { load.run(); if (onChanged != null) onChanged.run(); });
        });
        Button btnDelete = LmButton.danger("✕ Delete", () -> {
            if (readOnly) return;
            CpbastxRecord sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) { BasUiSupport.showInfo("Delete", "Select a line to delete."); return; }

            boolean isSynthetic = "U".equals(BasUiSupport.trim(sel.get(CPBASTX.TRX_STATUS)));
            if (!BasUiSupport.confirm("Remove Transaction",
                    "Remove this transaction from this BAS?\n\n"
                    + "It stays on file and will be picked up again the next time a BAS is created.")) return;

            boolean deleteEntirely = false;
            if (isSynthetic) {
                deleteEntirely = BasUiSupport.confirm("Delete Entirely?",
                    "This is a manually-entered line — delete it entirely instead of just removing it from this BAS?");
            }
            try {
                service.deleteLine(basGroup, basNo, sel.get(CPBASTX.TRX_DATE), sel.get(CPBASTX.DATE_ADDED),
                    sel.get(CPBASTX.TIME_ADDED), deleteEntirely, userId);
                load.run();
                if (onChanged != null) onChanged.run();
            } catch (Exception ex) {
                BasUiSupport.showError("Delete failed", ex.getMessage());
            }
        });
        Button btnFilter = LmButton.secondary("Filter…", () ->
            showFilterPopup(dlg, filterHolder[0], f -> { filterHolder[0] = f; load.run(); }));
        Button btnRefresh = LmButton.ghost("↺ Refresh", load);

        HBox toolbar = CommandBar.builder()
            .primary(btnAdd)
            .secondary(btnEdit)
            .secondary(btnDelete)
            .secondary(btnFilter)
            .ghost(btnRefresh)
            .build();
        if (readOnly) {
            btnAdd.setDisable(true);
            btnEdit.setDisable(true);
            btnDelete.setDisable(true);
        }

        table.setOnMouseClicked(e -> {
            if (!readOnly && e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) {
                btnEdit.fire();
            }
        });

        VBox center = new VBox(0, toolbar, table);
        VBox.setVgrow(table, Priority.ALWAYS);

        HBox statusBar = new HBox(lblStatus);
        statusBar.setPadding(new Insets(6, 12, 6, 12));

        BorderPane root = new BorderPane();
        root.setCenter(center);
        root.setBottom(statusBar);

        load.run();

        Scene scene = new Scene(root, 780, 480);
        dlg.setScene(scene);
        dlg.showAndWait();
    }

    private static void showFilterPopup(Window owner, BasProcessingService.LineFilter current,
                                         java.util.function.Consumer<BasProcessingService.LineFilter> onApply) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Filter Transaction Lines");
        dlg.setResizable(false);

        javafx.scene.control.DatePicker fFrom = new javafx.scene.control.DatePicker(current == null ? null : current.dateFrom());
        javafx.scene.control.DatePicker fTo = new javafx.scene.control.DatePicker(current == null ? null : current.dateTo());
        TextField fSource = BasUiSupport.tf(current == null ? "" : current.source(), 6);
        TextField fBatch = BasUiSupport.tf(current == null || current.batchNo() == null ? "" : String.valueOf(current.batchNo()), 8);
        TextField fAmtFrom = BasUiSupport.tf(current == null || current.amtFrom() == null ? "" : current.amtFrom().toPlainString(), 10);
        TextField fAmtTo = BasUiSupport.tf(current == null || current.amtTo() == null ? "" : current.amtTo().toPlainString(), 10);

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));
        form.getChildren().addAll(
            BasUiSupport.sectionHeader("Optional filters — blank = no restriction"),
            BasUiSupport.twoColRow("Date from:", fFrom),
            BasUiSupport.twoColRow("Date to:", fTo),
            BasUiSupport.twoColRow("Source:", fSource),
            BasUiSupport.twoColRow("Batch no:", fBatch),
            BasUiSupport.twoColRow("Tax amount from:", fAmtFrom),
            BasUiSupport.twoColRow("Tax amount to:", fAmtTo));

        Button btnApply = BasUiSupport.btnPrimary("Apply");
        btnApply.setDefaultButton(true);
        Button btnClear = BasUiSupport.btnSecondary("Clear");
        btnClear.setOnAction(e -> { onApply.accept(null); dlg.close(); });
        Button btnCancel = BasUiSupport.btnSecondary("Cancel");
        btnCancel.setCancelButton(true);
        btnCancel.setOnAction(e -> dlg.close());
        btnApply.setOnAction(e -> {
            LocalDate from = fFrom.getValue();
            LocalDate to = fTo.getValue();
            String source = fSource.getText() == null || fSource.getText().isBlank() ? null : fSource.getText().trim();
            Integer batch = parseIntOrNull(fBatch.getText());
            BigDecimal amtFrom = parseDecOrNull(fAmtFrom.getText());
            BigDecimal amtTo = parseDecOrNull(fAmtTo.getText());
            onApply.accept(new BasProcessingService.LineFilter(from, to, source, batch, amtFrom, amtTo));
            dlg.close();
        });

        VBox root = new VBox(0, form, BasUiSupport.buttonBar(btnClear, btnCancel, btnApply));
        dlg.setScene(new Scene(root, 380, 340));
        dlg.showAndWait();
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static BigDecimal parseDecOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String glAcct(CpbastxRecord r) {
        int main = r.get(CPBASTX.TAX_CLEARING_MAIN) == null ? 0 : r.get(CPBASTX.TAX_CLEARING_MAIN);
        int sub = r.get(CPBASTX.TAX_CLEARING_SUB) == null ? 0 : r.get(CPBASTX.TAX_CLEARING_SUB);
        return main == 0 && sub == 0 ? "" : main + "-" + sub;
    }

    private static String ref(CpbastxRecord r) {
        String r1 = BasUiSupport.trim(r.get(CPBASTX.REF_1));
        String r2 = BasUiSupport.trim(r.get(CPBASTX.REF_2));
        if (r1.isEmpty()) return r2;
        if (r2.isEmpty()) return r1;
        return r1 + " " + r2;
    }
}
