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
import com.landmarksoftware.payroll.service.LeaveAccrualService;
import com.landmarksoftware.payroll.service.PayrunService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * PAPA14 — Leave Processing UI.
 *
 * <p>Lists posted payruns and accrues AL + SL onto pastaff for the selected
 * one. Uses the MVP flat accrual factors documented in
 * {@link LeaveAccrualService}.
 */
@Component
public class LeaveProcessingController {

    private static final DateTimeFormatter DDMMYY = DateTimeFormatter.ofPattern("dd/MM/yy");

    private final PayrunService          payruns;
    private final LeaveAccrualService    accrual;
    private final AppSession             appSession;

    private final ObservableList<Payrun> rows = FXCollections.observableArrayList();
    private TableView<Payrun>            table;
    private Label                        status;
    private Stage                        stage;

    public LeaveProcessingController(PayrunService payruns,
                                      LeaveAccrualService accrual,
                                      AppSession appSession) {
        this.payruns    = payruns;
        this.accrual    = accrual;
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
        Label title = new Label("Leave Processing");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("PAPA14 · " + appSession.getCompanyName());
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
        addCol(table, "Reference",260, p -> p.ref);
        addCol(table, "Status",   100, p -> p.statusDisplay());
        VBox.setVgrow(table, Priority.ALWAYS);

        Button bAccrue  = new Button("Accrue AL + SL");
        Button bRefresh = new Button("Refresh");
        bAccrue.setOnAction(e -> accrue(table.getSelectionModel().getSelectedItem()));
        bRefresh.setOnAction(e -> loadList());

        HBox toolbar = new HBox(8, bAccrue, bRefresh);
        toolbar.setPadding(new Insets(10, 14, 10, 14));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle("-fx-background-color:#FFFFFF;");

        status = new Label("AL factor: " + LeaveAccrualService.AL_FACTOR
            + " · SL factor: " + LeaveAccrualService.SL_FACTOR
            + " (per minute worked)");
        status.setPadding(new Insets(6, 14, 8, 14));
        status.setStyle("-fx-font-size:11px;-fx-text-fill:#666;");

        VBox v = new VBox(toolbar, table, status);
        VBox.setVgrow(table, Priority.ALWAYS);
        return v;
    }

    private void loadList() {
        LocalDate start = LocalDate.now().minusYears(1);
        LocalDate end   = LocalDate.now().plusMonths(3);
        List<Payrun> list = payruns.findFiltered(
            appSession.getCompanyNo(), start, end, "", true, false);
        list.removeIf(p -> !"P".equalsIgnoreCase(p.payrunStatus)
                       && !"F".equalsIgnoreCase(p.payrunStatus));
        rows.setAll(list);
    }

    private void accrue(Payrun p) {
        if (p == null) { info("Select a posted payrun first."); return; }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Accrue AL and SL on pastaff for every employee on payrun "
                + p.payrunNo + "?\n\n"
                + "MVP factors: AL = 4 weeks / 52, SL = 2 weeks / 52 of hours\n"
                + "worked (total_normal_min + total_otime_min_actual).\n\n"
                + "WARNING: This MVP does not yet track per-payrun accrual\n"
                + "state. Running twice on the same payrun will double-accrue.",
            ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("PAPA14 — Leave Processing");
        confirm.initOwner(stage);
        var ans = confirm.showAndWait();
        if (ans.isEmpty() || ans.get() != ButtonType.YES) return;

        try {
            LeaveAccrualService.Result r = accrual.accruePayrun(
                p.companyNo, p.payrunNo, appSession.getUserId());
            BigDecimal alHrs = new BigDecimal(r.alMinAccrued())
                .divide(new BigDecimal("60"), 2, RoundingMode.HALF_UP);
            BigDecimal slHrs = new BigDecimal(r.slMinAccrued())
                .divide(new BigDecimal("60"), 2, RoundingMode.HALF_UP);
            info("Accrual complete for payrun " + p.payrunNo + ".\n\n"
                + r.employees() + " employee" + (r.employees() == 1 ? "" : "s")
                + " updated.\n"
                + "AL accrued: " + alHrs + " hours total.\n"
                + "SL accrued: " + slHrs + " hours total.");
        } catch (Exception ex) {
            error("Accrual failed: " + ex.getMessage());
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
