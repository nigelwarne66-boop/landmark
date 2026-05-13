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
import com.landmarksoftware.payroll.service.LeaveAccrualReversalService;
import com.landmarksoftware.payroll.service.LeaveAccrualReversalService.LeaveRow;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PASU55 — Leave Accrual Reversal.
 *
 * <p>Read-only listing of paleave (leave accrual) rows for a chosen employee.
 * COBOL PASU55 is a fully interactive add/edit/delete screen — the full CRUD
 * is deferred (see CLAUDE.md). What's wired here:
 * <ul>
 *   <li>Employee picker</li>
 *   <li>Listbox of paleave rows (pay_code, type, start/end dates,
 *       mins, amt, payrun_no, accrued-or-taken indicator)</li>
 *   <li>"Bulk-reverse selected employee" button — clears all paleave rows
 *       for the employee, writes pasuwk3 tracking + pa_audit batch</li>
 * </ul>
 *
 * <p>Per-row Add / Edit dialogs are deferred to the next iteration.
 */
@Component
public class LeaveAccrualReversalController {

    private final LeaveAccrualReversalService service;
    private final AppSession                  appSession;

    public LeaveAccrualReversalController(LeaveAccrualReversalService service,
                                           AppSession appSession) {
        this.service    = service;
        this.appSession = appSession;
    }

    public Scene buildScene(Stage stage) {
        Label hdr = new Label("Leave Accrual Reversal");
        hdr.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label(
            "View an employee's paleave history; bulk-reverse clears every accrual " +
            "(use with care)");
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        VBox hdrBox = new VBox(2, hdr, sub);
        hdrBox.setPadding(new Insets(16, 20, 12, 20));

        TextField txtEmp = new TextField();
        txtEmp.setPromptText("Employee #");
        txtEmp.setPrefColumnCount(8);
        Button loadBtn = new Button("Load");
        HBox topBar = new HBox(8, new Label("Employee:"), txtEmp, loadBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(0, 20, 8, 20));

        TableView<LeaveRow> table = new TableView<>();
        addCol(table, "Pay code",  r -> r.payCode());
        addCol(table, "Type",      r -> String.valueOf(r.payType()));
        addCol(table, "A/T",       r -> r.accruedTakenInd());
        addCol(table, "Start",     r -> nz(r.leaveStartDate()));
        addCol(table, "End",       r -> nz(r.leaveEndDate()));
        addCol(table, "Mins",      r -> String.valueOf(r.min()));
        addCol(table, "Rate",      r -> r.rate() == null ? "" : r.rate().toPlainString());
        addCol(table, "Amt",       r -> r.amt()  == null ? "" : r.amt().toPlainString());
        addCol(table, "Payrun",    r -> String.valueOf(r.payrunNo()));
        table.setPlaceholder(new Label("Enter an employee number and click Load."));

        Label status = new Label(" ");
        status.setStyle("-fx-text-fill:#1A1A2E;");
        status.setWrapText(true);

        Button reverseBtn = new Button("Bulk Reverse (clear all)");
        reverseBtn.setStyle("-fx-text-fill:#A33;");
        reverseBtn.setDisable(true);
        Button closeBtn = new Button("Close");
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> stage.close());
        HBox buttons = new HBox(8, reverseBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(12, 20, 14, 20));

        loadBtn.setOnAction(e -> {
            int empNo;
            try { empNo = Integer.parseInt(txtEmp.getText().trim()); }
            catch (NumberFormatException ex) {
                setStatus(status, true, "Enter a numeric employee number.");
                return;
            }
            List<LeaveRow> rows = service.findByEmployee(appSession.getCompanyNo(), empNo);
            table.setItems(FXCollections.observableArrayList(rows));
            reverseBtn.setDisable(rows.isEmpty());
            setStatus(status, false, rows.size() + " paleave row(s) for employee " + empNo + ".");
        });

        reverseBtn.setOnAction(e -> {
            int empNo;
            try { empNo = Integer.parseInt(txtEmp.getText().trim()); }
            catch (NumberFormatException ex) { return; }
            int n = service.reverseAllForEmployee(
                appSession.getCompanyNo(), empNo, appSession.getUserId());
            table.getItems().clear();
            reverseBtn.setDisable(true);
            setStatus(status, false,
                n + " paleave row(s) cleared. pasuwk3 + pa_audit updated.");
        });

        VBox center = new VBox(8, topBar, table);
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(hdrBox);
        root.setCenter(center);
        root.setBottom(new VBox(status, buttons));
        return new Scene(root, 820, 540);
    }

    private static void addCol(TableView<LeaveRow> t, String header,
                                java.util.function.Function<LeaveRow, String> extract) {
        TableColumn<LeaveRow, String> c = new TableColumn<>(header);
        c.setCellValueFactory(cd -> new SimpleStringProperty(extract.apply(cd.getValue())));
        c.setPrefWidth(100);
        t.getColumns().add(c);
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static void setStatus(Label l, boolean err, String t) {
        l.setStyle((err ? "-fx-text-fill:#A33;" : "-fx-text-fill:#185A1A;") +
            "-fx-padding:6 20 0 20;");
        l.setText(t);
    }
}
