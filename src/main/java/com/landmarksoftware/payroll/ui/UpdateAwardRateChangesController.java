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

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.payroll.service.UpdateAwardRateChangesService;
import com.landmarksoftware.payroll.service.UpdateAwardRateChangesService.AffectedRow;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PASU11 — Update Award Rate Changes.
 *
 * <p>Pushes pending paawchg entries down to active employees in the
 * selected award/job-class range. See {@link UpdateAwardRateChangesService}
 * for the full per-employee algorithm.
 */
@Component
public class UpdateAwardRateChangesController {

    private final UpdateAwardRateChangesService service;
    private final AppSession                    appSession;

    public UpdateAwardRateChangesController(UpdateAwardRateChangesService service,
                                             AppSession appSession) {
        this.service    = service;
        this.appSession = appSession;
    }

    public Scene buildScene(Stage stage) {
        Label hdr = new Label("Update Award Rate Changes");
        hdr.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label(
            "Apply pending paawchg entries to active employees (skips terminated " +
            "and over-award)");
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        VBox hdrBox = new VBox(2, hdr, sub);
        hdrBox.setPadding(new Insets(16, 20, 12, 20));

        TextField txtStartAw = new TextField();  txtStartAw.setPromptText("(blank = all)"); txtStartAw.setPrefColumnCount(6);
        TextField txtEndAw   = new TextField();  txtEndAw.setPromptText("(defaults to start)"); txtEndAw.setPrefColumnCount(6);
        TextField txtStartJc = new TextField();  txtStartJc.setPromptText("(blank = all)"); txtStartJc.setPrefColumnCount(8);
        TextField txtEndJc   = new TextField();  txtEndJc.setPromptText("(defaults to start)"); txtEndJc.setPrefColumnCount(8);

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        form.setPadding(new Insets(0, 20, 0, 20));
        form.add(new Label("Start award:"),     0, 0); form.add(txtStartAw, 1, 0);
        form.add(new Label("End award:"),       0, 1); form.add(txtEndAw,   1, 1);
        form.add(new Label("Start job class:"), 0, 2); form.add(txtStartJc, 1, 2);
        form.add(new Label("End job class:"),   0, 3); form.add(txtEndJc,   1, 3);

        Label status = new Label(" ");
        status.setStyle("-fx-text-fill:#1A1A2E;");
        status.setWrapText(true);
        status.setMaxWidth(560);

        Button previewBtn = new Button("Preview…");
        previewBtn.setDefaultButton(true);
        Button closeBtn = new Button("Close");
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> stage.close());
        HBox buttons = new HBox(8, previewBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(16, 20, 16, 20));

        previewBtn.setOnAction(e -> {
            String startAw = up(txtStartAw.getText());
            String endAw   = up(txtEndAw.getText());
            String startJc = up(txtStartJc.getText());
            String endJc   = up(txtEndJc.getText());
            // Default end-bound to matching start if user only filled one.
            if (endAw.isEmpty() && !startAw.isEmpty()) endAw = startAw;
            if (endJc.isEmpty() && !startJc.isEmpty()) endJc = startJc;

            int companyNo = appSession.getCompanyNo();
            previewBtn.setDisable(true);
            try {
                List<AffectedRow> rows = service.preview(companyNo,
                    startAw, endAw, startJc, endJc);
                if (rows.isEmpty()) {
                    setStatus(status, false,
                        "No employees match — nothing to update. (Check paawchg has pending rows.)");
                    return;
                }
                String summary = "Awards " + nz(startAw) + ".." + nz(endAw) +
                    " · Job classes " + nz(startJc) + ".." + nz(endJc);
                BatchPreviewDialog<AffectedRow> dlg = new BatchPreviewDialog<>(
                    "PASU11 — Update Award Rate Changes — Preview",
                    summary,
                    rows,
                    List.of(
                        new BatchPreviewDialog.Column<>("Award",     AffectedRow::award),
                        new BatchPreviewDialog.Column<>("Job class", AffectedRow::jobClass),
                        new BatchPreviewDialog.Column<>("Emp #",     r -> String.valueOf(r.employeeNo())),
                        new BatchPreviewDialog.Column<>("Name",      AffectedRow::employeeName),
                        new BatchPreviewDialog.Column<>("Type",      AffectedRow::employeeType),
                        new BatchPreviewDialog.Column<>("Old rate",  r -> nzStr(r.oldRatePerHr())),
                        new BatchPreviewDialog.Column<>("New rate",  r -> nzStr(r.newRatePerHr())),
                        new BatchPreviewDialog.Column<>("Old hrs",   r -> String.valueOf(r.oldStdMins())),
                        new BatchPreviewDialog.Column<>("New hrs",   r -> String.valueOf(r.newStdMins())),
                        new BatchPreviewDialog.Column<>("Old AL%",   r -> nzStr(r.oldAlLoading())),
                        new BatchPreviewDialog.Column<>("New AL%",   r -> nzStr(r.newAlLoading()))));

                if (!dlg.showAndAwait(stage)) {
                    setStatus(status, false, "Cancelled — no changes made.");
                    return;
                }

                int total = rows.size();
                Task<Integer> task = new Task<>() {
                    @Override protected Integer call() {
                        updateMessage("Processing " + total + " employees…");
                        return service.apply(companyNo,
                            up(txtStartAw.getText()), up(txtEndAw.getText()),
                            up(txtStartJc.getText()), up(txtEndJc.getText()),
                            appSession.getUserId(),
                            done -> {
                                if (isCancelled()) return;
                                updateProgress(done, total);
                                updateMessage("Updated " + done + " of " + total);
                            });
                    }
                };
                BatchProgressDialog.runWithProgress(stage,
                    "PASU11 — Update Award Rate Changes", task,
                    n -> setStatus(status, false,
                        "Done — " + n + " employee" + (n == 1 ? "" : "s") + " updated. " +
                        "paawchg cleared for selected range."),
                    err -> setStatus(status, true,
                        "Failed: " + (err == null ? "unknown error" : err.getMessage())));
            } catch (Exception ex) {
                setStatus(status, true, "Preview failed: " + ex.getMessage());
            } finally {
                previewBtn.setDisable(false);
            }
        });

        VBox center = new VBox(14, form, status);
        center.setPadding(new Insets(8, 0, 0, 0));

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(hdrBox);
        root.setCenter(center);
        root.setBottom(buttons);
        return new Scene(root, 640, 360);
    }

    private static String up(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String nzStr(java.math.BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }
    private static void setStatus(Label l, boolean err, String t) {
        l.setStyle(err ? "-fx-text-fill:#A33;" : "-fx-text-fill:#185A1A;");
        l.setText(t);
    }
}
