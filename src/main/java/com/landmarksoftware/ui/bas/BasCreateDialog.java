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

import com.landmarksoftware.db.tables.records.CpbasgrRecord;
import com.landmarksoftware.model.bas.BasPullPreview;
import com.landmarksoftware.model.bas.CreateBasParams;
import com.landmarksoftware.payroll.ui.BatchPreviewDialog;
import com.landmarksoftware.payroll.ui.BatchProgressDialog;
import com.landmarksoftware.service.bas.BasProcessingService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.landmarksoftware.db.tables.Cpbasgr.CPBASGR;

/**
 * CPBA10 — the Create-BAS dialog. Guards on {@code cpbasgr.current_bas_no}
 * before it even opens, previews the pull-in via {@link BatchPreviewDialog}
 * (reusing the Wave 2 batch-operation pattern), then commits via {@link
 * BatchProgressDialog#runWithProgress} so the (potentially large) transaction
 * pull-in never blocks the JavaFX thread.
 */
final class BasCreateDialog {

    private BasCreateDialog() { }

    static void show(Window owner, BasProcessingService service, String userId,
                      BasProcessingService.GroupOption group, Runnable onCreated) {
        CpbasgrRecord grp = service.getGroup(group.code());
        if (grp == null) {
            BasUiSupport.showError("BAS group not found", "The selected BAS group could not be loaded.");
            return;
        }
        if (nz(grp.get(CPBASGR.CURRENT_BAS_NO)) != 0) {
            BasUiSupport.showInfo("Create BAS", "Current BAS not committed");
            return;
        }
        int companyNo = nz(grp.get(CPBASGR.COMPANY_NO));

        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Create BAS — " + group.label());
        dlg.setResizable(false);

        boolean hasLastEnd = BasUiSupport.isRealDate(grp.get(CPBASGR.LAST_END_DATE));
        LocalDate fromDefault = hasLastEnd ? grp.get(CPBASGR.LAST_END_DATE).plusDays(1) : null;

        DatePicker fFrom = new DatePicker(fromDefault);
        fFrom.setEditable(!hasLastEnd);
        fFrom.setDisable(hasLastEnd);
        DatePicker fTo = new DatePicker();
        TextField fBasId = BasUiSupport.tf("", 20);
        TextField fAbn = BasUiSupport.tf(service.lookupAbn(companyNo, nz(grp.get(CPBASGR.SUB_COY_NO))), 18);
        DatePicker fDue = new DatePicker();
        DatePicker fPay = new DatePicker();

        boolean gstConsolMember = "C".equals(BasUiSupport.trim(grp.get(CPBASGR.GST_GROUP_CONSOL_IND)));
        CheckBox cbIncludeGst = new CheckBox("Include GST transactions in this run");
        cbIncludeGst.setSelected(!gstConsolMember);
        cbIncludeGst.setDisable(gstConsolMember);

        TextField fFbt = BasUiSupport.tf("0.00", 12);
        TextField fDeferredImport = BasUiSupport.tf("0.00", 12);
        TextField fFuelOverclaim = BasUiSupport.tf("0.00", 12);
        TextField fFuelCredit = BasUiSupport.tf("0.00", 12);

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));
        form.getChildren().addAll(
            BasUiSupport.sectionHeader("Period"),
            BasUiSupport.twoColRow("From date:", fFrom),
            BasUiSupport.twoColRow("To date *:", fTo),
            BasUiSupport.sectionHeader("ATO details"),
            BasUiSupport.twoColRow("BAS document ID *:", fBasId),
            BasUiSupport.twoColRow("ABN:", fAbn),
            BasUiSupport.twoColRow("Due date:", fDue),
            BasUiSupport.twoColRow("Pay date:", fPay),
            cbIncludeGst,
            BasUiSupport.sectionHeader("Manually-entered figures (optional — leave 0.00 if none)"),
            BasUiSupport.twoColRow("F1  FBT amount:", fFbt),
            BasUiSupport.twoColRow("7A  Deferred import tax:", fDeferredImport),
            BasUiSupport.twoColRow("7C  Fuel tax credit overclaim:", fFuelOverclaim),
            BasUiSupport.twoColRow("7D  Fuel tax credit:", fFuelCredit));

        Button btnNext = BasUiSupport.btnPrimary("Preview…");
        btnNext.setDefaultButton(true);
        Button btnCancel = BasUiSupport.btnSecondary("Cancel");
        btnCancel.setCancelButton(true);
        btnCancel.setOnAction(e -> dlg.close());

        btnNext.setOnAction(e -> {
            LocalDate from = fFrom.getValue();
            LocalDate to = fTo.getValue();
            if (to == null) { BasUiSupport.showError("Validation", "To date is required."); return; }
            if (from != null && to.isBefore(from)) { BasUiSupport.showError("Validation", "To date cannot be before From date."); return; }
            String basId = fBasId.getText() == null ? "" : fBasId.getText().trim();
            if (basId.isEmpty()) { BasUiSupport.showError("Validation", "BAS document ID is required."); return; }
            BigDecimal fbt = BasUiSupport.parseDec(fFbt, "FBT amount");
            if (fbt == null) return;
            BigDecimal defImport = BasUiSupport.parseDec(fDeferredImport, "Deferred import tax");
            if (defImport == null) return;
            BigDecimal fuelOver = BasUiSupport.parseDec(fFuelOverclaim, "Fuel tax credit overclaim");
            if (fuelOver == null) return;
            BigDecimal fuelCredit = BasUiSupport.parseDec(fFuelCredit, "Fuel tax credit");
            if (fuelCredit == null) return;
            String includeGst = cbIncludeGst.isSelected() ? "Y" : "N";

            CreateBasParams params = new CreateBasParams(group.code(), from, to, basId, fAbn.getText(),
                fDue.getValue(), fPay.getValue(), includeGst, fbt, defImport, fuelOver, fuelCredit);

            BasPullPreview preview = service.previewPull(group.code(), to, includeGst);
            record PreviewLine(String metric, String value) { }
            List<PreviewLine> lines = List.of(
                new PreviewLine("Transaction lines to pull in", String.valueOf(preview.rowCount())),
                new PreviewLine("Total gross", BasUiSupport.moneyStr(preview.totalGross())),
                new PreviewLine("Total tax", BasUiSupport.moneyStr(preview.totalTax())));
            List<BatchPreviewDialog.Column<PreviewLine>> cols = List.of(
                new BatchPreviewDialog.Column<>("Metric", PreviewLine::metric),
                new BatchPreviewDialog.Column<>("Value", PreviewLine::value));
            BatchPreviewDialog<PreviewLine> preview_ = new BatchPreviewDialog<>(
                "Create BAS — Preview", "Unposted transactions matching this period and GST setting:",
                lines, cols, "Create BAS");
            boolean apply = preview_.showAndAwait(dlg);
            if (!apply) return;

            dlg.close();
            Task<Integer> task = new Task<>() {
                @Override protected Integer call() {
                    updateMessage("Creating BAS run…");
                    return service.createBas(params, userId);
                }
            };
            BatchProgressDialog.runWithProgress(owner, "Creating BAS…", task,
                newBasNo -> {
                    BasUiSupport.showInfo("BAS Created", "BAS " + group.code() + "/" + newBasNo + " created.");
                    if (onCreated != null) onCreated.run();
                },
                err -> BasUiSupport.showError("Create failed", err == null ? "Unknown error" : err.getMessage()));
        });

        VBox root = new VBox(0, form, BasUiSupport.buttonBar(btnCancel, btnNext));
        dlg.setScene(new Scene(root, 480, 620));
        dlg.showAndWait();
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }
}
