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

import com.landmarksoftware.model.bas.BasReasonCode;
import com.landmarksoftware.service.bas.BasReasonCodeService;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/**
 * CPBA10 — a simple searchable picker over {@code cpbascd} rows for one
 * category ({@code T4} or {@code F4}), used by the 5A and 6A dialogs' reason
 * code fields. Deliberately small — a plain {@link ListView} modal, in the
 * same "doesn't need to be the same class as {@code GlAccountLookupDialog}"
 * spirit called for in the task brief.
 */
final class BasReasonCodePicker {

    private BasReasonCodePicker() { }

    static void show(Window owner, BasReasonCodeService service, String basCode, Consumer<BasReasonCode> onPick) {
        List<BasReasonCode> codes = service.findByCategory(basCode);

        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(basCode + " Reason Codes");
        dlg.setResizable(true);

        ListView<BasReasonCode> list = new ListView<>(FXCollections.observableArrayList(codes));
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(BasReasonCode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.reasonCode() + " — " + item.description());
            }
        });
        list.setPrefSize(420, 320);
        if (codes.isEmpty()) {
            Label empty = new Label("No " + basCode + " reason codes on file.\nUse CPBA02 to add some.");
            empty.setPadding(new Insets(20));
            list.setPlaceholder(empty);
        }

        Button btnSelect = BasUiSupport.btnPrimary("Select");
        btnSelect.setDefaultButton(true);
        Button btnCancel = BasUiSupport.btnSecondary("Cancel");
        btnCancel.setCancelButton(true);
        btnCancel.setOnAction(e -> dlg.close());
        btnSelect.setOnAction(e -> {
            BasReasonCode sel = list.getSelectionModel().getSelectedItem();
            if (sel != null) {
                onPick.accept(sel);
                dlg.close();
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                onPick.accept(list.getSelectionModel().getSelectedItem());
                dlg.close();
            }
        });

        HBox btnBar = new HBox(10, BasUiSupport.spacer(), btnCancel, btnSelect);
        btnBar.setPadding(new Insets(10, 16, 14, 16));

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(14, 16, 0, 16));
        root.setCenter(list);
        BorderPane.setMargin(list, new Insets(0, 0, 10, 0));
        VBox box = new VBox(root, btnBar);
        dlg.setScene(new Scene(box, 460, 420));
        dlg.showAndWait();
    }
}
