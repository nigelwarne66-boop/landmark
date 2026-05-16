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
import com.landmarksoftware.payroll.service.PayrollCalcService;
import com.landmarksoftware.payroll.service.PayrollLeaveService;
import com.landmarksoftware.payroll.service.PayrollPostingService;
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * PAPP01 — Pay Run Processing.
 *
 * <p>Lists open payruns and runs {@link PayrollCalcService#recalcPayrun} to
 * recompute every timesheet header's totals + tax. On full success flips
 * {@code parunhd.calcs_completed_flag} to {@code "Y"}.
 *
 * <p>Posting (moving patimes into paehist + writing GL summaries) is the
 * next program (PAPP28) and is not part of this screen.
 */
@Component
public class PayRunProcessingController {

    private static final DateTimeFormatter DDMMYY = DateTimeFormatter.ofPattern("dd/MM/yy");

    private final PayrunService           payruns;
    private final PayrollCalcService      payrollCalc;
    private final PayrollPostingService   posting;
    private final PayrollLeaveService     leave;
    private final AppSession              appSession;

    private final ObservableList<Payrun> rows = FXCollections.observableArrayList();
    private TableView<Payrun>            table;
    private Label                        status;
    private Stage                        stage;

    public PayRunProcessingController(PayrunService payruns,
                                       PayrollCalcService payrollCalc,
                                       PayrollPostingService posting,
                                       PayrollLeaveService leave,
                                       AppSession appSession) {
        this.payruns     = payruns;
        this.payrollCalc = payrollCalc;
        this.posting     = posting;
        this.leave       = leave;
        this.appSession  = appSession;
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
        Label title = new Label("Pay Run Processing");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("PAPP01 · " + appSession.getCompanyName());
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
            "No open payruns. Create one via PATM01 first."));
        addCol(table, "Payrun No", 70, p -> String.valueOf(p.payrunNo));
        addCol(table, "Date",      90, p -> fmt(p.payrunDate));
        addCol(table, "Type",     110, p -> p.typeDisplay());
        addCol(table, "Staff",     70, p -> String.valueOf(p.noOfEmployees));
        addCol(table, "Paid",      70, p -> String.valueOf(p.noOfEmployeesPaid));
        addCol(table, "Reference",260, p -> p.ref);
        addCol(table, "Calc",      60, p -> "Y".equalsIgnoreCase(p.calcsCompletedFlag) ? "Y" : "");
        addCol(table, "Status",    90, p -> p.statusDisplay());
        VBox.setVgrow(table, Priority.ALWAYS);

        Button bCalc    = new Button("Calculate Tax + Totals");
        Button bLeave   = new Button("Process Leave (PAPP03)");
        Button bPost    = new Button("Post ▸ paehist");
        Button bUnpost  = new Button("Un-post");
        Button bRefresh = new Button("Refresh");
        bCalc.setOnAction(e -> calculate(table.getSelectionModel().getSelectedItem()));
        bLeave.setOnAction(e -> processLeave(table.getSelectionModel().getSelectedItem()));
        bPost.setOnAction(e -> post(table.getSelectionModel().getSelectedItem()));
        bUnpost.setOnAction(e -> unpost(table.getSelectionModel().getSelectedItem()));
        bRefresh.setOnAction(e -> loadList());

        HBox toolbar = new HBox(8, bCalc, bLeave, bPost, bUnpost, bRefresh);
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
        // Show recent open + un-cancelled payruns. Year span is a reasonable
        // default; PATM01 has the more configurable filter dialog.
        LocalDate start = LocalDate.now().minusYears(1);
        LocalDate end   = LocalDate.now().plusMonths(3);
        List<Payrun> list = payruns.findFiltered(
            appSession.getCompanyNo(), start, end, "", false, false);
        rows.setAll(list);
        long calced = list.stream().filter(p -> "Y".equalsIgnoreCase(p.calcsCompletedFlag)).count();
        status.setText(list.size() + " open payrun" + (list.size() == 1 ? "" : "s")
            + " · " + calced + " calc'd");
    }

    /**
     * PAPP03 — accrue AL / SL / AL-loading onto pastaff for the payrun.
     * Uses paawjob award rates where available; falls back to flat factors.
     * Casuals and termination payruns are skipped (matches COBOL).
     */
    private void processLeave(Payrun p) {
        if (p == null) { info("Select a payrun first."); return; }
        if ("D".equalsIgnoreCase(p.payrunStatus)) {
            error("Payrun " + p.payrunNo + " is cancelled — cannot accrue leave.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Accrue leave (AL / SL / AL-loading) onto pastaff for every "
                + "employee on payrun " + p.payrunNo + "?\n\n"
                + "Uses paawjob.al_hrs / sick_hrs_1 / all_hrs as the per-period\n"
                + "entitlement; pro-rated by hours worked when accrue_al_by_hrs_flag\n"
                + "= Y or the employee is part-time. Casuals and termination payruns\n"
                + "are skipped.\n\n"
                + "WARNING: This MVP does NOT track per-payrun accrual state. Running\n"
                + "twice on the same payrun will double-accrue.",
            ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("PAPP03 — Leave Accrual");
        confirm.initOwner(stage);
        var ans = confirm.showAndWait();
        if (ans.isEmpty() || ans.get() != ButtonType.YES) return;

        try {
            PayrollLeaveService.Result r = leave.accruePayrun(
                p.companyNo, p.payrunNo, appSession.getUserId());
            java.math.BigDecimal alHrs = new java.math.BigDecimal(r.alMinAccrued())
                .divide(new java.math.BigDecimal("60"), 2,
                    java.math.RoundingMode.HALF_UP);
            java.math.BigDecimal slHrs = new java.math.BigDecimal(r.slMinAccrued())
                .divide(new java.math.BigDecimal("60"), 2,
                    java.math.RoundingMode.HALF_UP);
            StringBuilder sb = new StringBuilder()
                .append("Leave accrual complete for payrun ")
                .append(p.payrunNo).append(".\n\n")
                .append(r.processed()).append(" employee")
                .append(r.processed() == 1 ? "" : "s").append(" processed.\n")
                .append("AL: ").append(alHrs).append(" hours · SL: ").append(slHrs)
                .append(" hours.\n");
            if (r.skippedCasual() > 0) {
                sb.append("\n").append(r.skippedCasual()).append(" casual")
                  .append(r.skippedCasual() == 1 ? "" : "s")
                  .append(" skipped (AL/SL not accrued for casuals).");
            }
            if (r.skippedNoAward() > 0) {
                sb.append("\n").append(r.skippedNoAward())
                  .append(" employee").append(r.skippedNoAward() == 1 ? "" : "s")
                  .append(" with no award / job-class — fell back to flat factors.");
            }
            info(sb.toString());
        } catch (Exception ex) {
            error("Leave accrual failed: " + ex.getMessage());
        }
    }

    private void post(Payrun p) {
        if (p == null) { info("Select a payrun first."); return; }
        if (!"Y".equalsIgnoreCase(p.calcsCompletedFlag)) {
            error("Run Calculate Tax + Totals (PAPP01) on payrun "
                + p.payrunNo + " before posting.");
            return;
        }
        if ("P".equalsIgnoreCase(p.payrunStatus) || "F".equalsIgnoreCase(p.payrunStatus)) {
            error("Payrun " + p.payrunNo + " is already " + p.statusDisplay().toLowerCase()
                + " — use Un-post first if you need to re-post.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Post payrun " + p.payrunNo + " to paehist?\n\n"
                + "Each patimes line becomes a paehist row, the PAYG tax\n"
                + "amount calculated by PAPP01 lands as a synthetic tax\n"
                + "line (pay_type=22), and the payrun status flips to P.\n\n"
                + "Use Un-post to roll back if a mistake is found.",
            ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("PAPP28 — Payroll Posting");
        confirm.initOwner(stage);
        var ans = confirm.showAndWait();
        if (ans.isEmpty() || ans.get() != ButtonType.YES) return;

        try {
            PayrollPostingService.Result r = posting.postPayrun(
                p.companyNo, p.payrunNo, appSession.getUserId());
            info("Payrun " + p.payrunNo + " posted.\n\n"
                + r.employees() + " employee" + (r.employees() == 1 ? "" : "s")
                + " · " + r.linesPosted() + " paehist row"
                + (r.linesPosted() == 1 ? "" : "s") + " written.");
            loadList();
        } catch (Exception ex) {
            error("Posting failed: " + ex.getMessage()
                + "\n\nThe whole post was rolled back; payrun remains unposted.");
        }
    }

    private void unpost(Payrun p) {
        if (p == null) { info("Select a payrun first."); return; }
        if (!"P".equalsIgnoreCase(p.payrunStatus)) {
            error("Payrun " + p.payrunNo + " is " + p.statusDisplay().toLowerCase()
                + " — only a Posted payrun can be un-posted.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Un-post payrun " + p.payrunNo + "?\n\n"
                + "All paehist rows for this payrun are deleted; patimhd\n"
                + "rows flip back to Open. Patimes lines are left intact\n"
                + "so you can fix and re-post.",
            ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Reverse PAPP28 posting");
        confirm.initOwner(stage);
        var ans = confirm.showAndWait();
        if (ans.isEmpty() || ans.get() != ButtonType.YES) return;

        try {
            int removed = posting.unpostPayrun(p.companyNo, p.payrunNo,
                appSession.getUserId());
            info("Payrun " + p.payrunNo + " un-posted. " + removed
                + " paehist row" + (removed == 1 ? "" : "s") + " removed.");
            loadList();
        } catch (Exception ex) {
            error("Un-post failed: " + ex.getMessage());
        }
    }

    private void calculate(Payrun p) {
        if (p == null) { info("Select a payrun first."); return; }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Recalculate tax and totals for every timesheet on payrun "
                + p.payrunNo + "?\n\nThis reads patimes, recomputes patimhd "
                + "totals, and writes PAYG withholding from tax_brackets.",
            ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText(null);
        confirm.initOwner(stage);
        var ans = confirm.showAndWait();
        if (ans.isEmpty() || ans.get() != ButtonType.YES) return;

        try {
            PayrollCalcService.Result r = payrollCalc.recalcPayrun(
                p.companyNo, p.payrunNo, appSession.getUserId());
            StringBuilder sb = new StringBuilder()
                .append("Payrun ").append(p.payrunNo).append(":\n\n")
                .append(r.processed()).append(" timesheet")
                .append(r.processed() == 1 ? "" : "s").append(" recalculated.\n")
                .append(r.failed()).append(" failed.");
            if (r.firstError() != null) {
                sb.append("\n\nFirst error: ").append(r.firstError());
            }
            info(sb.toString());
            loadList();
        } catch (Exception ex) {
            error("Could not recalculate: " + ex.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

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
