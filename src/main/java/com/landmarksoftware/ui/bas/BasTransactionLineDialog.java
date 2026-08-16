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
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jooq.DSLContext;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.landmarksoftware.db.tables.Cpbastx.CPBASTX;

/**
 * CPBA10 — S5: add/edit a single {@code cpbastx} transaction line, always
 * opened from within a {@link BasTransactionListDialog} (P4) already
 * filtered to one {@code bas_code} — that code is fixed here, not editable.
 */
final class BasTransactionLineDialog {

    private BasTransactionLineDialog() { }

    static void show(Window owner, BasProcessingService service, DSLContext dsl, String basGroup, int basNo,
                      String basCode, CpbastxRecord existing, String userId, Runnable onSaved) {
        boolean isAdd = existing == null;
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isAdd ? "Add" : "Edit") + " Transaction Line — " + basCode);
        dlg.setResizable(false);

        DatePicker fDate = new DatePicker(isAdd ? LocalDate.now() : existing.get(CPBASTX.TRX_DATE));
        TextField fCompanyNo = BasUiSupport.tf(isAdd ? "0" : String.valueOf(nz(existing.get(CPBASTX.COMPANY_NO))), 6);
        TextField fSubCoyNo = BasUiSupport.tf(isAdd ? "0" : String.valueOf(nz(existing.get(CPBASTX.SUB_COY_NO))), 6);
        TextField fGross = BasUiSupport.tf(isAdd ? "0.00" : BasUiSupport.moneyStr(existing.get(CPBASTX.TRX_GROSS_AMT)), 14);
        TextField fTax = BasUiSupport.tf(isAdd ? "0.00" : BasUiSupport.moneyStr(existing.get(CPBASTX.TAX_AMT)), 14);
        TextField fClearMain = BasUiSupport.tf(isAdd ? "0" : String.valueOf(nz(existing.get(CPBASTX.TAX_CLEARING_MAIN))), 8);
        TextField fClearSub = BasUiSupport.tf(isAdd ? "0" : String.valueOf(nz(existing.get(CPBASTX.TAX_CLEARING_SUB))), 8);
        fClearMain.setEditable(false);
        fClearSub.setEditable(false);
        TextField fRef1 = BasUiSupport.tf(isAdd ? "" : existing.get(CPBASTX.REF_1), 20);
        TextField fRef2 = BasUiSupport.tf(isAdd ? "" : existing.get(CPBASTX.REF_2), 20);

        Button btnClearPick = new Button("🔍");
        Runnable syncClearEnabled = () -> {
            int co = safeInt(fCompanyNo.getText());
            btnClearPick.setDisable(co == 0);
            if (co == 0) { fClearMain.setText("0"); fClearSub.setText("0"); }
        };
        fCompanyNo.textProperty().addListener((o, ov, nv) -> syncClearEnabled.run());
        syncClearEnabled.run();
        btnClearPick.setOnAction(e -> {
            int co = safeInt(fCompanyNo.getText());
            if (co == 0) return;
            GlAccountLookupDialog.show(dlg, dsl, co, row -> {
                fClearMain.setText(String.valueOf(row.acctMain()));
                fClearSub.setText(String.valueOf(row.acctSub()));
            });
        });

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));
        Label lblCode = new Label("BAS code: " + basCode);
        lblCode.setStyle("-fx-font-weight:bold;-fx-padding:0 0 6 0;");
        form.getChildren().addAll(
            lblCode,
            BasUiSupport.twoColRow("Transaction date *:", fDate),
            BasUiSupport.twoColRow("Company no (0=none):", fCompanyNo),
            BasUiSupport.twoColRow("Sub coy no:", fSubCoyNo),
            BasUiSupport.twoColRow("Gross amount:", fGross),
            BasUiSupport.twoColRow("Tax amount:", fTax),
            BasUiSupport.twoColRow("Tax clearing GL a/c:", new HBox(4, fClearMain, fClearSub, btnClearPick)),
            BasUiSupport.twoColRow("Reference 1:", fRef1),
            BasUiSupport.twoColRow("Reference 2:", fRef2));

        Button btnSave = BasUiSupport.btnPrimary(isAdd ? "Add" : "Save");
        btnSave.setDefaultButton(true);
        Button btnCancel = BasUiSupport.btnSecondary("Cancel");
        btnCancel.setCancelButton(true);
        btnCancel.setOnAction(e -> dlg.close());
        btnSave.setOnAction(e -> {
            LocalDate trxDate = fDate.getValue();
            if (trxDate == null) { BasUiSupport.showError("Validation", "Transaction date is required."); return; }
            Integer co = BasUiSupport.parseIntOrNull(fCompanyNo, "Company no");
            if (co == null) co = 0;
            if (co == Integer.MIN_VALUE) return;
            Integer sc = BasUiSupport.parseIntOrNull(fSubCoyNo, "Sub coy no");
            if (sc == null) sc = 0;
            if (sc == Integer.MIN_VALUE) return;
            BigDecimal gross = BasUiSupport.parseDec(fGross, "Gross amount");
            if (gross == null) return;
            BigDecimal tax = BasUiSupport.parseDec(fTax, "Tax amount");
            if (tax == null) return;
            Integer clearMain = BasUiSupport.parseIntOrNull(fClearMain, "Clearing main");
            if (clearMain != null && clearMain == Integer.MIN_VALUE) return;
            Integer clearSub = BasUiSupport.parseIntOrNull(fClearSub, "Clearing sub");
            if (clearSub != null && clearSub == Integer.MIN_VALUE) return;

            try {
                if (isAdd) {
                    service.addLine(basGroup, basNo, basCode, trxDate, co, sc, gross, tax,
                        clearMain, clearSub, fRef1.getText(), fRef2.getText(), userId);
                } else {
                    service.editLine(basGroup, basNo,
                        existing.get(CPBASTX.TRX_DATE), existing.get(CPBASTX.DATE_ADDED), existing.get(CPBASTX.TIME_ADDED),
                        trxDate, co, sc, gross, tax, clearMain, clearSub, fRef1.getText(), fRef2.getText(), userId);
                }
                dlg.close();
                if (onSaved != null) Platform.runLater(onSaved);
            } catch (Exception ex) {
                BasUiSupport.showError(isAdd ? "Add failed" : "Save failed", ex.getMessage());
            }
        });

        VBox root = new VBox(0, form, BasUiSupport.buttonBar(btnCancel, btnSave));
        dlg.setScene(new Scene(root, 460, 460));
        dlg.showAndWait();
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }
    private static int safeInt(String s) {
        try { return s == null || s.isBlank() ? 0 : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
