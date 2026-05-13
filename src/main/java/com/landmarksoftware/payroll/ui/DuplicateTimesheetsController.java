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
import com.landmarksoftware.payroll.service.DuplicateTimesheetsService;
import com.landmarksoftware.payroll.service.DuplicateTimesheetsService.AffectedRow;
import com.landmarksoftware.payroll.service.DuplicateTimesheetsService.Inputs;
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
 * PAEM11 — Duplicate Default Timesheets.
 *
 * <p>One source employee, multiple targets defined by ranges. Target
 * employees have their existing paecode rows replaced with the source's
 * (a destructive operation — preview shows existing-line counts).
 */
@Component
public class DuplicateTimesheetsController {

    private final DuplicateTimesheetsService service;
    private final AppSession                 appSession;

    public DuplicateTimesheetsController(DuplicateTimesheetsService service,
                                          AppSession appSession) {
        this.service    = service;
        this.appSession = appSession;
    }

    public Scene buildScene(Stage stage) {
        Label hdr = new Label("Duplicate Default Timesheets");
        hdr.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label(
            "Copy a source employee's paecode template onto every target " +
            "(REPLACES target rows)");
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#A33;");
        VBox hdrBox = new VBox(2, hdr, sub);
        hdrBox.setPadding(new Insets(16, 20, 12, 20));

        TextField source  = num();
        TextField stEmp = num(); TextField enEmp = num();
        TextField stPg = text(4); TextField enPg = text(4);
        TextField stDp = text(4); TextField enDp = text(4);
        TextField stAw = text(3); TextField enAw = text(3);
        TextField stJc = text(6); TextField enJc = text(6);

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(8);
        form.setPadding(new Insets(0, 20, 0, 20));
        int row = 0;
        form.add(new Label("Source employee:"), 0, row); form.add(source, 1, row++);
        form.add(new Label("Target employee:"), 0, row); form.add(rangeBox(stEmp, enEmp), 1, row++);
        form.add(new Label("Paygroup:"),        0, row); form.add(rangeBox(stPg,  enPg),  1, row++);
        form.add(new Label("Dept:"),            0, row); form.add(rangeBox(stDp,  enDp),  1, row++);
        form.add(new Label("Award:"),           0, row); form.add(rangeBox(stAw,  enAw),  1, row++);
        form.add(new Label("Job class:"),       0, row); form.add(rangeBox(stJc,  enJc),  1, row++);

        Label status = new Label(" ");
        status.setStyle("-fx-text-fill:#1A1A2E;");
        status.setWrapText(true);
        status.setMaxWidth(620);

        Button previewBtn = new Button("Preview…");
        previewBtn.setDefaultButton(true);
        Button closeBtn = new Button("Close");
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> stage.close());
        HBox buttons = new HBox(8, previewBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(16, 20, 16, 20));

        previewBtn.setOnAction(e -> {
            int src = parseInt(source.getText(), 0);
            if (src == 0) {
                setStatus(status, true, "Source employee is required.");
                return;
            }
            Inputs in = new Inputs(src,
                parseInt(stEmp.getText(), 0), parseInt(enEmp.getText(), 0),
                up(stPg.getText()), up(enPg.getText()),
                up(stDp.getText()), up(enDp.getText()),
                up(stAw.getText()), up(enAw.getText()),
                up(stJc.getText()), up(enJc.getText()));

            int companyNo = appSession.getCompanyNo();
            previewBtn.setDisable(true);
            try {
                List<AffectedRow> rows = service.preview(companyNo, in);
                if (rows.isEmpty()) {
                    setStatus(status, false,
                        "No targets — either source has no paecode rows " +
                        "or the target range is empty.");
                    return;
                }
                String summary = "Source employee " + src + " → " + rows.size() +
                    " target employee(s). Existing target paecode rows will be deleted first.";
                BatchPreviewDialog<AffectedRow> dlg = new BatchPreviewDialog<>(
                    "PAEM11 — Duplicate Default Timesheets — Preview",
                    summary,
                    rows,
                    List.of(
                        new BatchPreviewDialog.Column<>("Emp #",      r -> String.valueOf(r.employeeNo())),
                        new BatchPreviewDialog.Column<>("Name",       AffectedRow::employeeName),
                        new BatchPreviewDialog.Column<>("Paygroup",   AffectedRow::paygroup),
                        new BatchPreviewDialog.Column<>("Dept",       AffectedRow::dept),
                        new BatchPreviewDialog.Column<>("Award",      AffectedRow::award),
                        new BatchPreviewDialog.Column<>("Job class",  AffectedRow::jobClass),
                        new BatchPreviewDialog.Column<>("Existing",   r -> String.valueOf(r.existingLines())),
                        new BatchPreviewDialog.Column<>("New lines",  r -> String.valueOf(r.newLines()))),
                    "Replace " + rows.size() + " employee timesheet" + (rows.size() == 1 ? "" : "s"));
                if (!dlg.showAndAwait(stage)) {
                    setStatus(status, false, "Cancelled — no changes made.");
                    return;
                }

                int total = rows.size();
                Task<Integer> task = new Task<>() {
                    @Override protected Integer call() {
                        updateMessage("Duplicating to " + total + " employees…");
                        return service.apply(companyNo, in, appSession.getUserId(),
                            done -> {
                                if (isCancelled()) return;
                                updateProgress(done, total);
                                updateMessage("Done " + done + " of " + total);
                            });
                    }
                };
                BatchProgressDialog.runWithProgress(stage,
                    "PAEM11 — Duplicate Default Timesheets", task,
                    n -> setStatus(status, false,
                        "Done — " + n + " employee" + (n == 1 ? "" : "s") + " refreshed."),
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
        return new Scene(root, 660, 420);
    }

    private static HBox rangeBox(TextField from, TextField to) {
        HBox b = new HBox(6, from, new Label("to"), to);
        b.setAlignment(Pos.CENTER_LEFT);
        return b;
    }
    private static TextField num()       { TextField t = new TextField(); t.setPrefColumnCount(6); t.setPromptText("0"); return t; }
    private static TextField text(int w) { TextField t = new TextField(); t.setPrefColumnCount(w + 2); return t; }
    private static int parseInt(String s, int def) {
        try { return s == null || s.trim().isEmpty() ? def : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }
    private static String up(String s) { return s == null ? "" : s.trim().toUpperCase(); }
    private static void setStatus(Label l, boolean err, String t) {
        l.setStyle(err ? "-fx-text-fill:#A33;" : "-fx-text-fill:#185A1A;");
        l.setText(t);
    }
}
