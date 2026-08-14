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
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.math.BigDecimal;

import static com.landmarksoftware.db.tables.Cpbashd.CPBASHD;

/**
 * CPBA10 — the tiny single-BigDecimal-field dialog for 7A (Deferred Import
 * Tax), 7C (Fuel Tax Credit overclaim), and 7D (Fuel Tax Credit). On save,
 * {@link BasProcessingService#updateManualFigure} creates or updates the
 * backing synthetic {@code cpbastx} row and recalculates the header.
 */
final class BasManualFigureDialog {

    private BasManualFigureDialog() { }

    static void show(Window owner, BasProcessingService service, String basGroup, int basNo,
                      BasProcessingService.ManualFigureKind kind, String label, BigDecimal current,
                      String userId, Runnable onSaved) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(label);
        dlg.setResizable(false);

        TextField field = BasUiSupport.tf(BasUiSupport.moneyStr(current), 14);

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));
        form.getChildren().addAll(
            BasUiSupport.sectionHeader(label),
            BasUiSupport.twoColRow("Amount:", field));

        Button btnSave = BasUiSupport.btnPrimary("Save");
        btnSave.setDefaultButton(true);
        Button btnCancel = BasUiSupport.btnSecondary("Cancel");
        btnCancel.setCancelButton(true);
        btnCancel.setOnAction(e -> dlg.close());
        btnSave.setOnAction(e -> {
            BigDecimal amt = BasUiSupport.parseDec(field, "Amount");
            if (amt == null) return;
            try {
                service.updateManualFigure(basGroup, basNo, kind, amt, userId);
                dlg.close();
                if (onSaved != null) onSaved.run();
            } catch (Exception ex) {
                BasUiSupport.showError("Save failed", ex.getMessage());
            }
        });

        VBox root = new VBox(0, form, BasUiSupport.buttonBar(btnCancel, btnSave));
        dlg.setScene(new Scene(root, 380, 160));
        dlg.showAndWait();
    }

    /** Convenience: current stored value for a kind, read from an already-fetched header. */
    static BigDecimal currentValue(CpbashdRecord h, BasProcessingService.ManualFigureKind kind) {
        return switch (kind) {
            case SEVEN_A -> h.get(CPBASHD.BAS_7A_DEFERRED_IMPORT_TAX);
            case SEVEN_C -> h.get(CPBASHD.BAS_7C_FUEL_TAX_CRED_CLAIM);
            case SEVEN_D -> h.get(CPBASHD.BAS_7D_FUEL_TAX_CREDIT);
        };
    }
}
