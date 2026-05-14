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
import com.landmarksoftware.payroll.model.Payrun;
import com.landmarksoftware.payroll.service.AbaFileService;
import com.landmarksoftware.payroll.service.PayrunService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * PABK02 — ABA Payment File generator UI.
 *
 * <p>Lists payruns in status {@code "P"} (Posted) and {@code "F"} (Fully
 * posted). Selecting a payrun and clicking <em>Generate</em> writes an
 * APCA-format file to {@code appSession.payrollFilesDir} (fallback:
 * {@code ~/landmark-payroll/}).
 *
 * <p>Generation warnings (missing BSB, no paempay split, etc.) are surfaced
 * after the file is written. The dialog gives a Copy-path button so the
 * user can take the file to the banking portal.
 */
@Component
public class AbaPaymentController {

    private static final DateTimeFormatter DDMMYY = DateTimeFormatter.ofPattern("dd/MM/yy");

    private final PayrunService    payruns;
    private final AbaFileService   aba;
    private final AppSession       appSession;

    private final ObservableList<Payrun> rows = FXCollections.observableArrayList();
    private TableView<Payrun>            table;
    private Label                        status;
    private Stage                        stage;

    public AbaPaymentController(PayrunService payruns,
                                 AbaFileService aba,
                                 AppSession appSession) {
        this.payruns    = payruns;
        this.aba        = aba;
        this.appSession = appSession;
    }

    public Scene buildScene(Stage stage) {
        this.stage = stage;
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildHeader());
        root.setCenter(buildContent());

        Scene scene = new Scene(root, 1000, 560);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        loadList();
        return scene;
    }

    private HBox buildHeader() {
        Label title = new Label("ABA Payment File");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("PABK02 · " + appSession.getCompanyName());
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        VBox titleBox = new VBox(2, title, sub);
        HBox bar = new HBox(titleBox);
        bar.setPadding(new Insets(14, 20, 14, 20));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color:#FFFFFF;" +
            "-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;" +
            "-fx-border-width:0 0 0.5 0;");
        return bar;
    }

    private VBox buildContent() {
        table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label(
            "No posted payruns. Post one via PAPP01 → PAPP28 first."));
        addCol(table, "Payrun No", 70, p -> String.valueOf(p.payrunNo));
        addCol(table, "Date",      90, p -> fmt(p.payrunDate));
        addCol(table, "Type",     110, p -> p.typeDisplay());
        addCol(table, "Staff",     70, p -> String.valueOf(p.noOfEmployees));
        addCol(table, "Reference",240, p -> p.ref);
        addCol(table, "Status",   100, p -> p.statusDisplay());
        VBox.setVgrow(table, Priority.ALWAYS);

        Button bGen     = new Button("Generate ABA");
        Button bRefresh = new Button("Refresh");
        bGen.setOnAction(e -> generate(table.getSelectionModel().getSelectedItem()));
        bRefresh.setOnAction(e -> loadList());

        HBox toolbar = new HBox(8, bGen, bRefresh);
        toolbar.setPadding(new Insets(10, 14, 10, 14));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle("-fx-background-color:#FFFFFF;");

        status = new Label();
        status.setPadding(new Insets(6, 14, 8, 14));
        status.setStyle("-fx-font-size:11px;-fx-text-fill:#666;");

        VBox v = new VBox(toolbar, table, status);
        VBox.setVgrow(table, Priority.ALWAYS);
        return v;
    }

    private void loadList() {
        // Show posted payruns within a wide date window.
        LocalDate start = LocalDate.now().minusYears(1);
        LocalDate end   = LocalDate.now().plusMonths(3);
        // "Include fully posted" = true so status F shows; status P comes
        // through with the standard filter (it's not D and not F).
        List<Payrun> list = payruns.findFiltered(
            appSession.getCompanyNo(), start, end, "", true, false);
        // Filter down to P / F only — only posted payruns are eligible.
        list.removeIf(p -> !"P".equalsIgnoreCase(p.payrunStatus)
                       && !"F".equalsIgnoreCase(p.payrunStatus));
        rows.setAll(list);
        status.setText(list.size() + " posted payrun" + (list.size() == 1 ? "" : "s")
            + " · output dir: " + outputDirLabel());
    }

    private String outputDirLabel() {
        String d = appSession.getPayrollFilesDir();
        if (d == null || d.isBlank()) {
            return System.getProperty("user.home") + "\\landmark-payroll  (default)";
        }
        return d;
    }

    private void generate(Payrun p) {
        if (p == null) { info("Select a posted payrun first."); return; }
        try {
            AbaFileService.Result r = aba.generate(
                p.companyNo, p.payrunNo, appSession.getUserId(), appSession);

            StringBuilder msg = new StringBuilder()
                .append("ABA file written for payrun ").append(p.payrunNo).append(".\n\n")
                .append("File   : ").append(r.file().toString()).append("\n")
                .append("Detail : ").append(r.detailCount()).append(" credit row")
                .append(r.detailCount() == 1 ? "" : "s").append("\n")
                .append("Total  : $").append(r.totalCredit());
            if (!r.warnings().isEmpty()) {
                msg.append("\n\nWarnings:");
                for (int i = 0; i < r.warnings().size() && i < 10; i++) {
                    msg.append("\n  • ").append(r.warnings().get(i));
                }
                if (r.warnings().size() > 10) {
                    msg.append("\n  … and ").append(r.warnings().size() - 10).append(" more.");
                }
            }

            Alert a = new Alert(Alert.AlertType.INFORMATION, msg.toString(),
                ButtonType.OK, new ButtonType("Copy path"));
            a.setHeaderText("PABK02 — ABA file generated");
            a.initOwner(stage);
            var ans = a.showAndWait();
            if (ans.isPresent() && "Copy path".equals(ans.get().getText())) {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(r.file().toString());
                Clipboard.getSystemClipboard().setContent(cc);
            }
        } catch (Exception ex) {
            error("ABA generation failed: " + ex.getMessage());
        }
    }

    private static <T> void addCol(TableView<T> tv, String label, double width,
                                    java.util.function.Function<T, String> getter) {
        TableColumn<T, String> col = new TableColumn<>(label);
        col.setMinWidth(width);
        col.setCellValueFactory(d -> new SimpleStringProperty(getter.apply(d.getValue())));
        tv.getColumns().add(col);
    }

    private static String fmt(LocalDate d) {
        if (d == null) return "";
        if (d.getYear() <= 1900) return "";
        return DDMMYY.format(d);
    }

    private void info(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.initOwner(stage);
        a.showAndWait();
    }
    private void error(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.initOwner(stage);
        a.showAndWait();
    }
}
