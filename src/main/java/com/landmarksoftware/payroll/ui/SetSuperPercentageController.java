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
import com.landmarksoftware.payroll.service.SetSuperPercentageService;
import com.landmarksoftware.payroll.service.SetSuperPercentageService.AffectedRow;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
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
 * PASU14 — Set Super Percentage.
 *
 * <p>Form-style controller for the mass paecode rate change. User picks:
 * <ul>
 *   <li>pay-code range (start..end, blank start = all codes)</li>
 *   <li>pay-type — 17 Superannuation or 20 Employer Super (Pay TYPE limit
 *       mirrors the COBOL guard in pasu14.pl CHECK-END-PAY-CODE)</li>
 *   <li>from % — only rows whose current rate equals this are touched</li>
 *   <li>to %   — new rate to set</li>
 * </ul>
 *
 * <p>Workflow: Preview → confirm in {@link BatchPreviewDialog} → Apply with
 * a {@link BatchProgressDialog}. The service writes a {@code pa_audit}
 * batch row plus one {@code papcaud} row per modified paecode.
 */
@Component
public class SetSuperPercentageController {

    private final SetSuperPercentageService service;
    private final AppSession                appSession;

    public SetSuperPercentageController(SetSuperPercentageService service,
                                         AppSession appSession) {
        this.service    = service;
        this.appSession = appSession;
    }

    public Scene buildScene(Stage stage) {
        // ── Header ────────────────────────────────────────────────────────
        Label hdr = new Label("Set Super Percentage");
        hdr.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("Mass-update paecode rate % for type 17/20 (super) pay codes");
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        VBox hdrBox = new VBox(2, hdr, sub);
        hdrBox.setPadding(new Insets(16, 20, 12, 20));

        // ── Inputs ────────────────────────────────────────────────────────
        TextField txtStart = new TextField();
        txtStart.setPromptText("(blank = all)");
        txtStart.setPrefColumnCount(8);

        TextField txtEnd = new TextField();
        txtEnd.setPromptText("(defaults to start)");
        txtEnd.setPrefColumnCount(8);

        ComboBox<String> cmbType = new ComboBox<>();
        cmbType.getItems().addAll("17 — Superannuation", "20 — Employer Super");
        cmbType.setValue("20 — Employer Super");

        TextField txtFrom = new TextField("9.00");
        txtFrom.setPrefColumnCount(6);
        TextField txtTo   = new TextField("9.25");
        txtTo.setPrefColumnCount(6);

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        form.setPadding(new Insets(0, 20, 0, 20));
        form.add(new Label("Start pay code:"), 0, 0);
        form.add(txtStart,                     1, 0);
        form.add(new Label("End pay code:"),   0, 1);
        form.add(txtEnd,                       1, 1);
        form.add(new Label("Pay type:"),       0, 2);
        form.add(cmbType,                      1, 2);
        form.add(new Label("From %:"),         0, 3);
        form.add(txtFrom,                      1, 3);
        form.add(new Label("To %:"),           0, 4);
        form.add(txtTo,                        1, 4);

        Label status = new Label(" ");
        status.setStyle("-fx-text-fill:#1A1A2E;");
        status.setWrapText(true);
        status.setMaxWidth(560);

        // ── Buttons ───────────────────────────────────────────────────────
        Button previewBtn = new Button("Preview…");
        previewBtn.setDefaultButton(true);
        Button closeBtn = new Button("Close");
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> stage.close());
        HBox buttons = new HBox(8, previewBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(16, 20, 16, 20));

        previewBtn.setOnAction(e -> {
            Inputs in = readInputs(txtStart, txtEnd, cmbType, txtFrom, txtTo, status);
            if (in == null) return;
            previewBtn.setDisable(true);
            try {
                List<AffectedRow> rows = service.preview(
                    appSession.getCompanyNo(),
                    in.startCode, in.endCode, in.payType, in.fromPerc, in.toPerc);
                if (rows.isEmpty()) {
                    setStatus(status, false,
                        "No paecode rows match — nothing would change.");
                    return;
                }
                String summary = "Type " + in.payType +
                    " · pay codes " + nz(in.startCode) + ".." + nz(in.endCode) +
                    " · rate " + in.fromPerc + " → " + in.toPerc;
                BatchPreviewDialog<AffectedRow> dlg = new BatchPreviewDialog<>(
                    "PASU14 — Set Super Percentage — Preview",
                    summary,
                    rows,
                    List.of(
                        new BatchPreviewDialog.Column<>("Emp #",      r -> String.valueOf(r.employeeNo())),
                        new BatchPreviewDialog.Column<>("Name",       AffectedRow::employeeName),
                        new BatchPreviewDialog.Column<>("Pay code",   AffectedRow::payCode),
                        new BatchPreviewDialog.Column<>("Type",       r -> String.valueOf(r.payType())),
                        new BatchPreviewDialog.Column<>("Line",       r -> String.valueOf(r.lineNo())),
                        new BatchPreviewDialog.Column<>("Current %",  r -> r.currentRate().toPlainString()),
                        new BatchPreviewDialog.Column<>("New %",      r -> r.newRate().toPlainString())));
                if (!dlg.showAndAwait(stage)) {
                    setStatus(status, false, "Cancelled — no changes made.");
                    return;
                }
                runApply(stage, in, rows.size(), status);
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
        return new Scene(root, 600, 380);
    }

    // ── Apply ────────────────────────────────────────────────────────────

    private void runApply(Stage stage, Inputs in, int total, Label status) {
        int companyNo = appSession.getCompanyNo();
        String userId = appSession.getUserId();

        Task<Integer> task = new Task<>() {
            @Override protected Integer call() {
                updateMessage("Updating " + total + " rows…");
                return service.apply(companyNo,
                    in.startCode, in.endCode, in.payType, in.fromPerc, in.toPerc,
                    userId,
                    rowsDone -> {
                        if (isCancelled()) return;
                        updateProgress(rowsDone, total);
                        updateMessage("Updated " + rowsDone + " of " + total);
                    });
            }
        };
        BatchProgressDialog.runWithProgress(stage,
            "PASU14 — Set Super Percentage", task,
            n -> setStatus(status, false,
                "Done — " + n + " paecode row" + (n == 1 ? "" : "s") + " updated."),
            err -> setStatus(status, true,
                "Failed: " + (err == null ? "unknown error" : err.getMessage())));
    }

    // ── Input parsing ────────────────────────────────────────────────────

    private record Inputs(String startCode, String endCode, int payType,
                          BigDecimal fromPerc, BigDecimal toPerc) {}

    private static Inputs readInputs(TextField txtStart, TextField txtEnd,
                                     ComboBox<String> cmbType,
                                     TextField txtFrom, TextField txtTo,
                                     Label status) {
        String start = txtStart.getText() == null ? "" : txtStart.getText().trim().toUpperCase();
        String end   = txtEnd.getText()   == null ? "" : txtEnd.getText().trim().toUpperCase();
        // Empty start = scan all codes (zzzzzz upper bound); empty end = start
        if (end.isEmpty() && !start.isEmpty()) end = start;
        if (start.isEmpty() && end.isEmpty()) { start = ""; end = "zzzzzz"; }

        String t = cmbType.getValue();
        int payType;
        if (t == null || t.startsWith("17")) payType = 17;
        else if (t.startsWith("20"))         payType = 20;
        else { fail(status, "Pick a pay type (17 or 20)."); return null; }

        BigDecimal from = parseDec(txtFrom.getText());
        BigDecimal to   = parseDec(txtTo.getText());
        if (from == null || from.signum() < 0 || from.compareTo(BigDecimal.valueOf(100)) > 0) {
            fail(status, "From % must be 0–100.");
            return null;
        }
        if (to == null || to.signum() < 0 || to.compareTo(BigDecimal.valueOf(100)) > 0) {
            fail(status, "To % must be 0–100.");
            return null;
        }
        if (from.compareTo(to) == 0) {
            fail(status, "From % and To % are the same — nothing to change.");
            return null;
        }
        return new Inputs(start, end, payType, from, to);
    }

    private static BigDecimal parseDec(String s) {
        try { return new BigDecimal(s.trim()); } catch (Exception e) { return null; }
    }

    private static void setStatus(Label l, boolean isError, String text) {
        l.setStyle(isError ? "-fx-text-fill:#A33;" : "-fx-text-fill:#185A1A;");
        l.setText(text);
    }

    private static void fail(Label l, String msg) { setStatus(l, true, msg); }

    private static String nz(String s) { return s == null ? "" : s; }
}
