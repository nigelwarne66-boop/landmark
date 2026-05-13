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
import com.landmarksoftware.payroll.service.GlobalEmployeeAwardUpdateService;
import com.landmarksoftware.payroll.service.GlobalEmployeeAwardUpdateService.AffectedRow;
import com.landmarksoftware.payroll.service.GlobalEmployeeAwardUpdateService.Inputs;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
 * PASU15 — Global Employee Award Update.
 *
 * <p>Form-style controller. Five range filters and four behaviour switches
 * (rate/salary/timesheet/super). The "Include over-award" flag is the
 * fifth switch in the COBOL but UI-grouped with the range filters.
 *
 * <p>The preview cards rate + salary before/after; the timesheet/super
 * refresh detail isn't surfaced in the preview because the row counts
 * are per-employee, not per-paecode.
 */
@Component
public class GlobalEmployeeAwardUpdateController {

    private final GlobalEmployeeAwardUpdateService service;
    private final AppSession                       appSession;

    public GlobalEmployeeAwardUpdateController(GlobalEmployeeAwardUpdateService service,
                                                AppSession appSession) {
        this.service    = service;
        this.appSession = appSession;
    }

    public Scene buildScene(Stage stage) {
        Label hdr = new Label("Global Employee Award Update");
        hdr.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label(
            "Push paawjob values into selected pastaff/paecode rows " +
            "(active employees only)");
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        VBox hdrBox = new VBox(2, hdr, sub);
        hdrBox.setPadding(new Insets(16, 20, 12, 20));

        TextField txtStartEmp = num();
        TextField txtEndEmp   = num();
        TextField txtStartType = num();
        TextField txtEndType   = num();
        TextField txtStartPC = text(6);
        TextField txtEndPC   = text(6);
        TextField txtStartAw = text(3);
        TextField txtEndAw   = text(3);
        TextField txtStartJc = text(6);
        TextField txtEndJc   = text(6);

        CheckBox chkIncludeOA = new CheckBox("Include over-award employees");
        CheckBox chkUpdateRate = new CheckBox("Update std hrs / rate / gross");
        chkUpdateRate.setSelected(true);
        CheckBox chkUpdateSalary = new CheckBox("Update annual salary");
        chkUpdateSalary.setSelected(true);
        CheckBox chkUpdateTimesheets = new CheckBox("Update default timesheet lines");
        chkUpdateTimesheets.setSelected(true);
        CheckBox chkRecalcSuper = new CheckBox("Recalc super ext_amt off new gross");
        chkRecalcSuper.setSelected(true);

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(8);
        form.setPadding(new Insets(0, 20, 0, 20));
        int row = 0;
        form.add(new Label("Employee:"),   0, row); form.add(rangeBox(txtStartEmp, txtEndEmp), 1, row++);
        form.add(new Label("Pay-code type:"),0,row); form.add(rangeBox(txtStartType,txtEndType),1,row++);
        form.add(new Label("Pay code:"),   0, row); form.add(rangeBox(txtStartPC,  txtEndPC),  1, row++);
        form.add(new Label("Award:"),      0, row); form.add(rangeBox(txtStartAw,  txtEndAw),  1, row++);
        form.add(new Label("Job class:"),  0, row); form.add(rangeBox(txtStartJc,  txtEndJc),  1, row++);
        form.add(chkIncludeOA,            1, row++);
        form.add(chkUpdateRate,           1, row++);
        form.add(chkUpdateSalary,         1, row++);
        form.add(chkUpdateTimesheets,     1, row++);
        form.add(chkRecalcSuper,          1, row++);

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
            Inputs in = new Inputs(
                parseInt(txtStartEmp.getText(), 0),
                parseInt(txtEndEmp.getText(),   0),
                parseInt(txtStartType.getText(),0),
                parseInt(txtEndType.getText(),  0),
                up(txtStartPC.getText()), up(txtEndPC.getText()),
                up(txtStartAw.getText()), up(txtEndAw.getText()),
                up(txtStartJc.getText()), up(txtEndJc.getText()),
                chkIncludeOA.isSelected(),
                chkUpdateRate.isSelected(),
                chkUpdateSalary.isSelected(),
                chkUpdateTimesheets.isSelected(),
                chkRecalcSuper.isSelected());

            int companyNo = appSession.getCompanyNo();
            previewBtn.setDisable(true);
            try {
                List<AffectedRow> rows = service.preview(companyNo, in);
                if (rows.isEmpty()) {
                    setStatus(status, false, "No employees match.");
                    return;
                }
                String summary = "Employees " + in.startEmployee() + ".." + in.endEmployee() +
                    "  awards " + nzs(in.startAward()) + ".." + nzs(in.endAward()) +
                    "  job " + nzs(in.startJobClass()) + ".." + nzs(in.endJobClass());

                BatchPreviewDialog<AffectedRow> dlg = new BatchPreviewDialog<>(
                    "PASU15 — Global Employee Award Update — Preview",
                    summary,
                    rows,
                    List.of(
                        new BatchPreviewDialog.Column<>("Emp #",     r -> String.valueOf(r.employeeNo())),
                        new BatchPreviewDialog.Column<>("Name",      AffectedRow::employeeName),
                        new BatchPreviewDialog.Column<>("Award",     AffectedRow::award),
                        new BatchPreviewDialog.Column<>("Job class", AffectedRow::jobClass),
                        new BatchPreviewDialog.Column<>("Type",      AffectedRow::employeeType),
                        new BatchPreviewDialog.Column<>("OA?",       r -> r.overAwardCarried() ? "Y" : ""),
                        new BatchPreviewDialog.Column<>("Old rate",  r -> bd(r.oldRatePerHr())),
                        new BatchPreviewDialog.Column<>("New rate",  r -> bd(r.newRatePerHr())),
                        new BatchPreviewDialog.Column<>("Old salary",r -> bd(r.oldAnnualSalary())),
                        new BatchPreviewDialog.Column<>("New salary",r -> bd(r.newAnnualSalary()))));

                if (!dlg.showAndAwait(stage)) {
                    setStatus(status, false, "Cancelled — no changes made.");
                    return;
                }

                int total = rows.size();
                Task<Integer> task = new Task<>() {
                    @Override protected Integer call() {
                        updateMessage("Processing " + total + " employees…");
                        return service.apply(companyNo, in, appSession.getUserId(),
                            done -> {
                                if (isCancelled()) return;
                                updateProgress(done, total);
                                updateMessage("Updated " + done + " of " + total);
                            });
                    }
                };
                BatchProgressDialog.runWithProgress(stage,
                    "PASU15 — Global Employee Award Update", task,
                    n -> setStatus(status, false,
                        "Done — " + n + " employee" + (n == 1 ? "" : "s") + " processed."),
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
        return new Scene(root, 720, 560);
    }

    private static HBox rangeBox(TextField from, TextField to) {
        HBox b = new HBox(6, from, new Label("to"), to);
        b.setAlignment(Pos.CENTER_LEFT);
        return b;
    }
    private static TextField num()  { TextField t = new TextField(); t.setPrefColumnCount(6); t.setPromptText("0"); return t; }
    private static TextField text(int w) { TextField t = new TextField(); t.setPrefColumnCount(w + 2); return t; }
    private static int parseInt(String s, int def) {
        try { return s == null || s.trim().isEmpty() ? def : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }
    private static String up(String s) { return s == null ? "" : s.trim().toUpperCase(); }
    private static String nzs(String s) { return s == null ? "" : s; }
    private static String bd(java.math.BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }
    private static void setStatus(Label l, boolean err, String t) {
        l.setStyle(err ? "-fx-text-fill:#A33;" : "-fx-text-fill:#185A1A;");
        l.setText(t);
    }
}
