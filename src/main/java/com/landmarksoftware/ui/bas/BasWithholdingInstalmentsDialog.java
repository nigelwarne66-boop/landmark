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

import com.landmarksoftware.db.tables.records.CpbashdRecord;
import com.landmarksoftware.service.bas.BasProcessingService;
import com.landmarksoftware.service.bas.BasReasonCodeService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.landmarksoftware.db.tables.Cpbashd.CPBASHD;

/**
 * CPBA10 — the real COBOL "BAS CALCULATION SHEET - WITHHOLDING TAXES" screen:
 * Withholding (W1-W4), Income Tax Instalment (T1-T4), and FBT Instalment
 * (F1-F4) all together in ONE window, reached by clicking the amount on
 * label 4, 5A, OR 6A on the "Other Taxes" tab — verified against real-app
 * screenshots 2026-08-13 (not three separate screens as first built).
 *
 * <p>Unlike the transaction-driven labels (1C-1G/5B/6B/7), W1-W4 here are
 * plain directly-editable amount fields — typing a value and saving simply
 * overwrites {@code cpbashd.w1_total_wages} etc. (no drill to a transaction
 * list, no synthetic {@code cpbastx} line — those fields have no {@code
 * w*_cpbastx_key} column to back one).
 */
final class BasWithholdingInstalmentsDialog {

    private BasWithholdingInstalmentsDialog() { }

    static void show(Window owner, BasProcessingService service, BasReasonCodeService reasonCodeService,
                      String basGroup, int basNo, CpbashdRecord header, String userId, Runnable onSaved) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("BAS Calculation Sheet — Withholding Taxes");
        dlg.setResizable(false);

        TextField fW1 = BasUiSupport.tf(BasUiSupport.moneyStr(header.get(CPBASHD.W1_TOTAL_WAGES)), 14);
        TextField fW2 = BasUiSupport.tf(BasUiSupport.moneyStr(header.get(CPBASHD.W2_WAGES_WITHHELD)), 14);
        TextField fW3 = BasUiSupport.tf(BasUiSupport.moneyStr(header.get(CPBASHD.W3_INVESTMENT_WITHHELD)), 14);
        TextField fW4 = BasUiSupport.tf(BasUiSupport.moneyStr(header.get(CPBASHD.W4_PAYMENTS_WITHHELD)), 14);

        Button btnCalcPayroll = BasUiSupport.btnSecondary("Calculate from Payroll…");
        btnCalcPayroll.setOnAction(e -> {
            btnCalcPayroll.setDisable(true);
            int companyNo = header.get(CPBASHD.COMPANY_NO);
            java.time.LocalDate from = header.get(CPBASHD.A3_FROM_DATE);
            java.time.LocalDate to = header.get(CPBASHD.A4_TO_DATE);
            Thread t = new Thread(() -> {
                try {
                    BasProcessingService.PayrollTotals totals = service.getPayrollTotals(companyNo, from, to);
                    Platform.runLater(() -> {
                        fW1.setText(BasUiSupport.moneyStr(totals.grossWages()));
                        fW2.setText(BasUiSupport.moneyStr(totals.taxWithheld()));
                        btnCalcPayroll.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        btnCalcPayroll.setDisable(false);
                        BasUiSupport.showError("Calculate from Payroll failed", ex.getMessage());
                    });
                }
            }, "bas-payroll-calc-thread");
            t.setDaemon(true);
            t.start();
        });

        BigDecimal t2 = header.get(CPBASHD.T2_TAX_RATE);
        TextField fT1 = BasUiSupport.tf(BasUiSupport.moneyStr(header.get(CPBASHD.T1_TAXABLE_INCOME)), 14);
        TextField fT2 = BasUiSupport.tf(BasUiSupport.moneyStr(t2), 8);
        fT2.setEditable(false);
        fT2.setDisable(true);
        TextField fT3 = BasUiSupport.tf(BasUiSupport.moneyStr(header.get(CPBASHD.T3_VARIED_TAX_RATE)), 8);
        TextField fT4 = BasUiSupport.tf(BasUiSupport.trim(header.get(CPBASHD.T4_REASON_CODE)), 4);
        Button btnT4Lookup = new Button("🔍");
        btnT4Lookup.setOnAction(e -> BasReasonCodePicker.show(dlg, reasonCodeService, "T4",
            code -> fT4.setText(code.reasonCode())));

        TextField fF1 = BasUiSupport.tf(BasUiSupport.moneyStr(header.get(CPBASHD.F1_FBT_AMT)), 14);
        TextField fF2 = BasUiSupport.tf(BasUiSupport.moneyStr(header.get(CPBASHD.F2_EST_FBT_PAYABLE)), 14);
        TextField fF3 = BasUiSupport.tf(BasUiSupport.moneyStr(header.get(CPBASHD.F3_VARIED_FBT_AMT)), 14);
        TextField fF4 = BasUiSupport.tf(BasUiSupport.trim(header.get(CPBASHD.F4_REASON_CODE)), 4);
        Button btnF4Lookup = new Button("🔍");
        btnF4Lookup.setOnAction(e -> BasReasonCodePicker.show(dlg, reasonCodeService, "F4",
            code -> fF4.setText(code.reasonCode())));

        Label lbl5a = new Label();
        Label lbl6a = new Label();
        lbl5a.setStyle("-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        lbl6a.setStyle("-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Runnable recompute = () -> {
            BigDecimal t1v = safeDec(fT1.getText());
            BigDecimal t2v = safeDec(fT2.getText());
            BigDecimal t3v = safeDec(fT3.getText());
            String t4v = fT4.getText() == null ? "" : fT4.getText().trim();
            BigDecimal rate = t4v.isEmpty() ? t2v : t3v;
            BigDecimal amt5a = t1v.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            lbl5a.setText("5A = " + BasUiSupport.moneyStr(amt5a));

            BigDecimal f1v = safeDec(fF1.getText());
            BigDecimal f3v = safeDec(fF3.getText());
            String f4v = fF4.getText() == null ? "" : fF4.getText().trim();
            BigDecimal amt6a = f4v.isEmpty() ? f1v : f3v;
            lbl6a.setText("6A = " + BasUiSupport.moneyStr(amt6a));
        };
        fT1.textProperty().addListener((o, ov, nv) -> recompute.run());
        fT3.textProperty().addListener((o, ov, nv) -> recompute.run());
        fT4.textProperty().addListener((o, ov, nv) -> recompute.run());
        fF1.textProperty().addListener((o, ov, nv) -> recompute.run());
        fF3.textProperty().addListener((o, ov, nv) -> recompute.run());
        fF4.textProperty().addListener((o, ov, nv) -> recompute.run());
        recompute.run();

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));
        form.getChildren().addAll(
            BasUiSupport.sectionHeader("Withholding Taxes"),
            btnCalcPayroll,
            BasUiSupport.twoColRow("W1 Wages/salaries:", fW1),
            BasUiSupport.twoColRow("W2 Wages withheld:", fW2),
            BasUiSupport.twoColRow("W3 Investment withheld:", fW3),
            BasUiSupport.twoColRow("W4 Payments withheld:", fW4),
            BasUiSupport.sectionHeader("Income Tax"),
            BasUiSupport.twoColRow("T1 Instalment income:", fT1),
            BasUiSupport.twoColRow("T2 ATO rate (%):", fT2),
            BasUiSupport.twoColRow("T3 Varied rate (%):", fT3),
            BasUiSupport.twoColRow("T4 Reason code:", new HBox(4, fT4, btnT4Lookup)),
            lbl5a,
            BasUiSupport.sectionHeader("Fringe Benefits Tax"),
            BasUiSupport.twoColRow("F1 FBT instalment:", fF1),
            BasUiSupport.twoColRow("F2 Est FBT payable:", fF2),
            BasUiSupport.twoColRow("F3 Varied FBT instalment:", fF3),
            BasUiSupport.twoColRow("F4 Variation reason:", new HBox(4, fF4, btnF4Lookup)),
            lbl6a);

        Button btnSave = BasUiSupport.btnPrimary("Save");
        btnSave.setDefaultButton(true);
        Button btnCancel = BasUiSupport.btnSecondary("Cancel");
        btnCancel.setCancelButton(true);
        btnCancel.setOnAction(e -> dlg.close());
        btnSave.setOnAction(e -> {
            BigDecimal w1 = BasUiSupport.parseDec(fW1, "W1 Wages/salaries"); if (w1 == null) return;
            BigDecimal w2 = BasUiSupport.parseDec(fW2, "W2 Wages withheld"); if (w2 == null) return;
            BigDecimal w3 = BasUiSupport.parseDec(fW3, "W3 Investment withheld"); if (w3 == null) return;
            BigDecimal w4 = BasUiSupport.parseDec(fW4, "W4 Payments withheld"); if (w4 == null) return;
            BigDecimal t1 = BasUiSupport.parseDec(fT1, "T1 Instalment income"); if (t1 == null) return;
            BigDecimal t3 = BasUiSupport.parseDec(fT3, "T3 Varied rate"); if (t3 == null) return;
            String t4 = fT4.getText() == null ? "" : fT4.getText().trim().toUpperCase();
            BigDecimal f1 = BasUiSupport.parseDec(fF1, "F1 FBT instalment"); if (f1 == null) return;
            BigDecimal f2 = BasUiSupport.parseDec(fF2, "F2 Est FBT payable"); if (f2 == null) return;
            BigDecimal f3 = BasUiSupport.parseDec(fF3, "F3 Varied FBT instalment"); if (f3 == null) return;
            String f4 = fF4.getText() == null ? "" : fF4.getText().trim().toUpperCase();
            try {
                service.updateWithholdingAndInstalments(basGroup, basNo, w1, w2, w3, w4, t1, t3, t4, f1, f2, f3, f4, userId);
                dlg.close();
                if (onSaved != null) Platform.runLater(onSaved);
            } catch (IllegalArgumentException ex) {
                BasUiSupport.showError("Validation", ex.getMessage());
            } catch (Exception ex) {
                BasUiSupport.showError("Save failed", ex.getMessage());
            }
        });

        VBox root = new VBox(0, form, BasUiSupport.buttonBar(btnCancel, btnSave));
        dlg.setScene(new Scene(root, 460, 680));
        dlg.showAndWait();
    }

    private static BigDecimal safeDec(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
