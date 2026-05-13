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
import com.landmarksoftware.payroll.service.ChangeEmployeePayRatesService;
import com.landmarksoftware.payroll.service.ChangeEmployeePayRatesService.AffectedRow;
import com.landmarksoftware.payroll.service.ChangeEmployeePayRatesService.Inputs;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * PAEM60 — Change Employee Pay Rates.
 *
 * <p>Form-style controller. Range filters + a single percentage change
 * + four optional pay-type sections with their own pay-code ranges.
 */
@Component
public class ChangeEmployeePayRatesController {

    private final ChangeEmployeePayRatesService service;
    private final AppSession                    appSession;

    public ChangeEmployeePayRatesController(ChangeEmployeePayRatesService service,
                                             AppSession appSession) {
        this.service    = service;
        this.appSession = appSession;
    }

    public Scene buildScene(Stage stage) {
        Label hdr = new Label("Change Employee Pay Rates");
        hdr.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label(
            "Apply a percentage change to selected employees' rates and " +
            "default timesheet lines");
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        VBox hdrBox = new VBox(2, hdr, sub);
        hdrBox.setPadding(new Insets(16, 20, 12, 20));

        TextField stEmp = num();  TextField enEmp = num();
        TextField stPg  = text(4); TextField enPg = text(4);
        TextField stDp  = text(4); TextField enDp = text(4);
        TextField stAw  = text(3); TextField enAw = text(3);
        TextField stJc  = text(6); TextField enJc = text(6);
        TextField percTxt = new TextField("5.0"); percTxt.setPrefColumnCount(6);
        CheckBox chkUpdateMaster = new CheckBox("Update master file (pastaff rate, gross, salary, actual_paid_rate)");
        chkUpdateMaster.setSelected(true);

        CheckBox chkN = new CheckBox("Include Normal pay (type 1)"); chkN.setSelected(true);
        TextField stN = text(6), enN = text(6);
        CheckBox chkO = new CheckBox("Include Overtime (type 2)");
        TextField stO = text(6), enO = text(6);
        CheckBox chkX = new CheckBox("Include Other pay (type 3)");
        TextField stX = text(6), enX = text(6);
        CheckBox chkL = new CheckBox("Include Leave (types 4-9)");
        TextField stL = text(6), enL = text(6);

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(8);
        form.setPadding(new Insets(0, 20, 0, 20));
        int row = 0;
        form.add(new Label("Employee:"),  0, row); form.add(rangeBox(stEmp, enEmp), 1, row++);
        form.add(new Label("Paygroup:"),  0, row); form.add(rangeBox(stPg,  enPg),  1, row++);
        form.add(new Label("Dept:"),      0, row); form.add(rangeBox(stDp,  enDp),  1, row++);
        form.add(new Label("Award:"),     0, row); form.add(rangeBox(stAw,  enAw),  1, row++);
        form.add(new Label("Job class:"), 0, row); form.add(rangeBox(stJc,  enJc),  1, row++);
        form.add(new Label("% change:"),  0, row); form.add(percTxt,                1, row++);
        form.add(chkUpdateMaster, 1, row++);
        form.add(chkN, 1, row++); form.add(new Label("Normal codes:"),   0, row); form.add(rangeBox(stN, enN), 1, row++);
        form.add(chkO, 1, row++); form.add(new Label("Overtime codes:"), 0, row); form.add(rangeBox(stO, enO), 1, row++);
        form.add(chkX, 1, row++); form.add(new Label("Other codes:"),    0, row); form.add(rangeBox(stX, enX), 1, row++);
        form.add(chkL, 1, row++); form.add(new Label("Leave codes:"),    0, row); form.add(rangeBox(stL, enL), 1, row++);

        Label status = new Label(" ");
        status.setStyle("-fx-text-fill:#1A1A2E;");
        status.setWrapText(true);
        status.setMaxWidth(640);

        Button previewBtn = new Button("Preview…");
        previewBtn.setDefaultButton(true);
        Button closeBtn = new Button("Close");
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> stage.close());
        HBox buttons = new HBox(8, previewBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(16, 20, 16, 20));

        previewBtn.setOnAction(e -> {
            BigDecimal perc;
            try { perc = new BigDecimal(percTxt.getText().trim()); }
            catch (NumberFormatException ex) {
                setStatus(status, true, "Percentage must be numeric.");
                return;
            }
            Inputs in = new Inputs(
                parseInt(stEmp.getText(), 0), parseInt(enEmp.getText(), 0),
                up(stPg.getText()), up(enPg.getText()),
                up(stDp.getText()), up(enDp.getText()),
                up(stAw.getText()), up(enAw.getText()),
                up(stJc.getText()), up(enJc.getText()),
                perc,
                chkUpdateMaster.isSelected(),
                chkN.isSelected(), up(stN.getText()), up(enN.getText()),
                chkO.isSelected(), up(stO.getText()), up(enO.getText()),
                chkX.isSelected(), up(stX.getText()), up(enX.getText()),
                chkL.isSelected(), up(stL.getText()), up(enL.getText()));

            int companyNo = appSession.getCompanyNo();
            previewBtn.setDisable(true);
            try {
                List<AffectedRow> rows = service.preview(companyNo, in);
                if (rows.isEmpty()) {
                    setStatus(status, false, "No employees match.");
                    return;
                }
                String summary = "Apply " + perc + "% to " + rows.size() +
                    " employee(s) · " + (in.updateMasterFile() ? "master" : "no-master") +
                    "/" + (in.includeNormal() ? "N" : "-") +
                    "/" + (in.includeOvertime() ? "OT" : "-") +
                    "/" + (in.includeOther() ? "O" : "-") +
                    "/" + (in.includeLeave() ? "L" : "-");
                BatchPreviewDialog<AffectedRow> dlg = new BatchPreviewDialog<>(
                    "PAEM60 — Change Employee Pay Rates — Preview",
                    summary,
                    rows,
                    List.of(
                        new BatchPreviewDialog.Column<>("Emp #",     r -> String.valueOf(r.employeeNo())),
                        new BatchPreviewDialog.Column<>("Name",      AffectedRow::employeeName),
                        new BatchPreviewDialog.Column<>("Paygroup",  AffectedRow::paygroup),
                        new BatchPreviewDialog.Column<>("Dept",      AffectedRow::dept),
                        new BatchPreviewDialog.Column<>("Award",     AffectedRow::award),
                        new BatchPreviewDialog.Column<>("Job class", AffectedRow::jobClass),
                        new BatchPreviewDialog.Column<>("Old rate",  r -> bd(r.oldRatePerHr())),
                        new BatchPreviewDialog.Column<>("New rate",  r -> bd(r.newRatePerHr()))));

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
                    "PAEM60 — Change Employee Pay Rates", task,
                    n -> setStatus(status, false,
                        "Done — " + n + " employee" + (n == 1 ? "" : "s") + " updated."),
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
        ScrollPane sp = new ScrollPane(center);
        sp.setFitToWidth(true);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(hdrBox);
        root.setCenter(sp);
        root.setBottom(buttons);
        return new Scene(root, 740, 640);
    }

    private static HBox rangeBox(TextField from, TextField to) {
        HBox b = new HBox(6, from, new Label("to"), to);
        b.setAlignment(Pos.CENTER_LEFT);
        return b;
    }
    private static TextField num()      { TextField t = new TextField(); t.setPrefColumnCount(6); t.setPromptText("0"); return t; }
    private static TextField text(int w) { TextField t = new TextField(); t.setPrefColumnCount(w + 2); return t; }
    private static int parseInt(String s, int def) {
        try { return s == null || s.trim().isEmpty() ? def : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }
    private static String up(String s) { return s == null ? "" : s.trim().toUpperCase(); }
    private static String bd(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }
    private static void setStatus(Label l, boolean err, String t) {
        l.setStyle(err ? "-fx-text-fill:#A33;" : "-fx-text-fill:#185A1A;");
        l.setText(t);
    }
}
