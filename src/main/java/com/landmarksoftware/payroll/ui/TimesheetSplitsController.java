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
import com.landmarksoftware.payroll.service.TimesheetSplitsService;
import com.landmarksoftware.payroll.service.TimesheetSplitsService.EmployeeDetailRow;
import com.landmarksoftware.payroll.service.TimesheetSplitsService.EmployeeHeaderRow;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * PAPC01 — Timesheet Splits.
 *
 * <p>Two-pane viewer: master list of employees that carry a pasphde header,
 * with a child list of the paspgre detail rows for the selected employee.
 * Add/Edit dialogs are deferred — Delete is wired and writes a pa_audit
 * batch row.
 *
 * <p>The pasphdg/paspgrg variant (by-paygroup/dept rather than by-employee)
 * is also deferred; the schema is in place via the extract pipeline.
 */
@Component
public class TimesheetSplitsController {

    private final TimesheetSplitsService service;
    private final AppSession             appSession;

    public TimesheetSplitsController(TimesheetSplitsService service,
                                      AppSession appSession) {
        this.service    = service;
        this.appSession = appSession;
    }

    public Scene buildScene(Stage stage) {
        Label hdr = new Label("Timesheet Splits");
        hdr.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label(
            "pasphde / paspgre — per-employee pay-phase split percentages");
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        VBox hdrBox = new VBox(2, hdr, sub);
        hdrBox.setPadding(new Insets(16, 20, 12, 20));

        // ── Master: employee header list ─────────────────────────────────
        TableView<EmployeeHeaderRow> hdrTable = new TableView<>();
        TableColumn<EmployeeHeaderRow,String> cEmp = colString("Emp #",  r -> String.valueOf(r.employeeNo()));
        TableColumn<EmployeeHeaderRow,String> cNm  = colString("Name",   EmployeeHeaderRow::employeeName);
        TableColumn<EmployeeHeaderRow,String> cTot = colString("Total %", r -> r.totalPerc() == null ? "" : r.totalPerc().toPlainString());
        TableColumn<EmployeeHeaderRow,String> cCnt = colString("Splits", r -> String.valueOf(r.detailCount()));
        hdrTable.getColumns().addAll(cEmp, cNm, cTot, cCnt);

        // ── Detail: paspgre rows for selected employee ───────────────────
        TableView<EmployeeDetailRow> detTable = new TableView<>();
        detTable.getColumns().add(colDetail("Paygroup", EmployeeDetailRow::paygroup));
        detTable.getColumns().add(colDetail("Dept",     EmployeeDetailRow::dept));
        detTable.getColumns().add(colDetail("%",        r -> r.perc() == null ? "" : r.perc().toPlainString()));

        hdrTable.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> {
            if (nv == null) {
                detTable.getItems().clear();
                return;
            }
            detTable.setItems(FXCollections.observableArrayList(
                service.findDetailsForEmployee(appSession.getCompanyNo(), nv.employeeNo())));
        });

        Label status = new Label(" ");
        status.setStyle("-fx-text-fill:#1A1A2E;-fx-padding:0 20 0 20;");

        Button refreshBtn = new Button("Refresh");
        Button deleteBtn  = new Button("Delete split for selected employee");
        deleteBtn.setStyle("-fx-text-fill:#A33;");
        deleteBtn.setDisable(true);
        Button closeBtn   = new Button("Close");
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> stage.close());

        hdrTable.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) ->
            deleteBtn.setDisable(nv == null));

        refreshBtn.setOnAction(e -> {
            List<EmployeeHeaderRow> rows = service.findAllHeaders(appSession.getCompanyNo());
            hdrTable.setItems(FXCollections.observableArrayList(rows));
            setStatus(status, false, rows.size() + " employee split header(s).");
        });

        deleteBtn.setOnAction(e -> {
            EmployeeHeaderRow sel = hdrTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            int n = service.deleteEmployeeSplit(
                appSession.getCompanyNo(), sel.employeeNo(), appSession.getUserId());
            setStatus(status, false,
                "Deleted " + n + " row(s) for employee " + sel.employeeNo() +
                " (pa_audit logged).");
            refreshBtn.fire();
        });

        HBox buttons = new HBox(8, refreshBtn, deleteBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(12, 20, 14, 20));

        SplitPane sp = new SplitPane(hdrTable, detTable);
        sp.setDividerPositions(0.55);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(hdrBox);
        root.setCenter(sp);
        root.setBottom(new VBox(status, buttons));

        // Initial load
        javafx.application.Platform.runLater(refreshBtn::fire);

        return new Scene(root, 900, 540);
    }

    private static TableColumn<EmployeeHeaderRow,String> colString(String label,
            java.util.function.Function<EmployeeHeaderRow,String> extract) {
        TableColumn<EmployeeHeaderRow,String> c = new TableColumn<>(label);
        c.setCellValueFactory(cd -> new SimpleStringProperty(extract.apply(cd.getValue())));
        c.setPrefWidth(140);
        return c;
    }
    private static TableColumn<EmployeeDetailRow,String> colDetail(String label,
            java.util.function.Function<EmployeeDetailRow,String> extract) {
        TableColumn<EmployeeDetailRow,String> c = new TableColumn<>(label);
        c.setCellValueFactory(cd -> new SimpleStringProperty(extract.apply(cd.getValue())));
        c.setPrefWidth(120);
        return c;
    }
    private static void setStatus(Label l, boolean err, String t) {
        l.setStyle((err ? "-fx-text-fill:#A33;" : "-fx-text-fill:#185A1A;") +
            "-fx-padding:6 20 0 20;");
        l.setText(t);
    }
}
