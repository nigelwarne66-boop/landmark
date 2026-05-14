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
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Payroll Module Menu — the top-level hub for all PY programs.
 *
 * Opened from MainMenuController when the user clicks the "Payroll" tab.
 * Displays the payroll program groups in the same card-grid style as
 * the FA/GL tab.
 *
 * Current status:
 *   ■ PACD01 — Pay Code Maintenance     (implemented)
 *   ○ PAEM01 — Employee Maintenance     (Wave 1 — next)
 *   ○ PAPG01 — Pay Group Maintenance    (Wave 1)
 *   ○ PASU04 — Payroll Supervisor Setup (Wave 1)
 *   ○ PATM01 — Timesheet Entry          (Wave 3)
 *   ○ PAPP01 — Pay Run Processing       (Wave 3)
 *
 * Each card entry is a {@link PayrollMenuEntry} — a title, program code,
 * availability flag, and optional action.  When implemented, the action
 * opens a new Stage via the relevant controller's buildScene().
 *
 * This controller is passed into MainMenuController as a Spring bean
 * and called from the sidebar navigation.
 */
@Component
public class PayrollMenuController {

    private final PayCodeMaintenanceController     pacd01;
    private final EmployeeMaintenanceController    paem01;
    private final PayGroupMaintenanceController    papg01;
    private final TaxScaleMaintenanceController    pasu04;
    private final AwardMaintenanceController       paaw01;
    private final TaxScaleLoadController           taxScaleLoad;
    private final SetSuperPercentageController     pasu14;
    private final UpdateAwardRateChangesController pasu11;
    private final GlobalEmployeeAwardUpdateController pasu15;
    private final ChangeEmployeePayRatesController paem60;
    private final DuplicateTimesheetsController    paem11;
    private final LeaveAccrualReversalController   pasu55;
    private final TimesheetSplitsController        papc01;
    private final TimesheetEntryController         patm01;
    private final AppSession                       appSession;

    public PayrollMenuController(PayCodeMaintenanceController pacd01,
                                  EmployeeMaintenanceController paem01,
                                  PayGroupMaintenanceController papg01,
                                  TaxScaleMaintenanceController pasu04,
                                  AwardMaintenanceController paaw01,
                                  TaxScaleLoadController taxScaleLoad,
                                  SetSuperPercentageController pasu14,
                                  UpdateAwardRateChangesController pasu11,
                                  GlobalEmployeeAwardUpdateController pasu15,
                                  ChangeEmployeePayRatesController paem60,
                                  DuplicateTimesheetsController paem11,
                                  LeaveAccrualReversalController pasu55,
                                  TimesheetSplitsController papc01,
                                  TimesheetEntryController patm01,
                                  AppSession appSession) {
        this.pacd01       = pacd01;
        this.paem01       = paem01;
        this.papg01       = papg01;
        this.pasu04       = pasu04;
        this.paaw01       = paaw01;
        this.taxScaleLoad = taxScaleLoad;
        this.pasu14       = pasu14;
        this.pasu11       = pasu11;
        this.pasu15       = pasu15;
        this.paem60       = paem60;
        this.paem11       = paem11;
        this.pasu55       = pasu55;
        this.papc01       = papc01;
        this.patm01       = patm01;
        this.appSession   = appSession;
    }

    // ── Entry point ───────────────────────────────────────────────────────

    /**
     * Build the full payroll module scene.
     * Called from MainMenuController when the user selects Payroll.
     */
    public Scene buildScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildPageHeader());
        root.setCenter(buildGrid(stage));

        Scene scene = new Scene(root, 880, 580);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    // ── Page header ───────────────────────────────────────────────────────

    private VBox buildPageHeader() {
        Label title = new Label("Payroll");
        title.setStyle("-fx-font-size:20px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label(
            appSession.getCompanyName() + "  ·  " + appSession.getYearDesc());
        sub.setStyle("-fx-font-size:12px;-fx-text-fill:#888780;");
        VBox hdr = new VBox(4, title, sub);
        hdr.setPadding(new Insets(24, 24, 18, 24));
        hdr.setStyle(
            "-fx-background-color:#FFFFFF;" +
            "-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;" +
            "-fx-border-width:0 0 0.5 0;");
        return hdr;
    }

    // ── Card grid ─────────────────────────────────────────────────────────

    private ScrollPane buildGrid(Stage parentStage) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);
        grid.setPadding(new Insets(20));

        // Col 0: Setup & Maintenance
        grid.add(buildCard(parentStage,
            "Setup & Maintenance",
            "Pay codes, employees, groups, awards",
            List.of(
                new PayrollMenuEntry("PACD01", "Pay Code Maintenance",
                    "Manage income, deduction & allowance codes",
                    true, () -> openPayCodeMaintenance(parentStage)),
                new PayrollMenuEntry("PAEM01", "Employee Maintenance",
                    "Add, edit and maintain employee records",
                    true, () -> openEmployeeMaintenance(parentStage)),
                new PayrollMenuEntry("PAPG01", "Pay Group Maintenance",
                    "Define payroll groups and frequencies",
                    true, () -> openPayGroupMaintenance(parentStage)),
                new PayrollMenuEntry("PASU04", "Tax Scale Maintenance",
                    "Maintain PAYG / HECS / STSL tax scale config",
                    true, () -> openTaxScaleMaintenance(parentStage)),
                new PayrollMenuEntry("PAAW01", "Award Maintenance",
                    "Enterprise agreements and award schedules",
                    true, () -> openAwardMaintenance(parentStage))
            )), 0, 0);

        // Col 1: Pay Processing
        grid.add(buildCard(parentStage,
            "Pay Processing",
            "Timesheets, pay runs and payments",
            List.of(
                new PayrollMenuEntry("PATM01", "Timesheet Entry",
                    "Enter employee timesheets",
                    true, () -> openTimesheetEntry(parentStage)),
                new PayrollMenuEntry("PAPP01", "Pay Run Processing",
                    "Create and process pay runs",
                    false, null),
                new PayrollMenuEntry("PABK02", "ABA Payment File",
                    "Generate bank payment (ABA) file",
                    false, null),
                new PayrollMenuEntry("PAPA14", "Leave Processing",
                    "Process leave payouts and accruals",
                    false, null),
                new PayrollMenuEntry("PAPP28", "Payroll Posting",
                    "Post pay run to General Ledger",
                    false, null)
            )), 1, 0);

        // Col 0 row 1: Reporting
        grid.add(buildCard(parentStage,
            "Reports",
            "Payroll summaries and compliance",
            List.of(
                new PayrollMenuEntry("PATL10", "Payroll Summary",
                    "Pay run summary by employee",
                    false, null),
                new PayrollMenuEntry("PATL12", "Employee Listing",
                    "Full employee details report",
                    false, null),
                new PayrollMenuEntry("PAST10", "Single Touch Payroll",
                    "STP submission to ATO",
                    false, null),
                new PayrollMenuEntry("PAPS26", "Payment Summaries",
                    "Annual payment summary (PAYG)",
                    false, null)
            )), 0, 1);

        // Col 1 row 1: Year-end
        grid.add(buildCard(parentStage,
            "Year End",
            "Financial year close-off",
            List.of(
                new PayrollMenuEntry("PATX01", "Load ATO Tax Scales",
                    "Annual NAT_1004 / NAT_3539 update",
                    true, () -> openTaxScaleLoad(parentStage)),
                new PayrollMenuEntry("PADE01", "Payroll Year End",
                    "Roll forward to new payroll year",
                    false, null)
            )), 1, 1);

        // Col 0 row 2: Mass Update — Wave 2 batch utilities
        grid.add(buildCard(parentStage,
            "Mass Update",
            "Batch changes across employees and pay codes",
            List.of(
                new PayrollMenuEntry("PASU14", "Set Super Percentage",
                    "Change super rate % on selected pay codes",
                    true, () -> openSetSuperPercentage(parentStage)),
                new PayrollMenuEntry("PASU11", "Update Award Rate Changes",
                    "Apply award rate changes to active employees",
                    true, () -> openUpdateAwardRateChanges(parentStage)),
                new PayrollMenuEntry("PASU15", "Global Employee Award Update",
                    "Move employees between awards globally",
                    true, () -> openGlobalEmployeeAwardUpdate(parentStage)),
                new PayrollMenuEntry("PAEM60", "Change Employee Pay Rates",
                    "Mass update employee pay rates",
                    true, () -> openChangeEmployeePayRates(parentStage))
            )), 0, 2);

        // Col 1 row 2: Batch Operations
        grid.add(buildCard(parentStage,
            "Batch Operations",
            "Pay run and timesheet utilities",
            List.of(
                new PayrollMenuEntry("PAEM11", "Duplicate Default Timesheets",
                    "Copy default timesheets to many employees",
                    true, () -> openDuplicateTimesheets(parentStage)),
                new PayrollMenuEntry("PASU55", "Leave Accrual Reversal",
                    "Reverse leave accruals for a payrun",
                    true, () -> openLeaveAccrualReversal(parentStage)),
                new PayrollMenuEntry("PAPC01", "Timesheet Splits",
                    "Maintain pay phase / phase group splits",
                    true, () -> openTimesheetSplits(parentStage))
            )), 1, 2);

        // Equal column widths
        ColumnConstraints cc = new ColumnConstraints();
        cc.setPercentWidth(50);
        grid.getColumnConstraints().addAll(cc, cc);

        ScrollPane sp = new ScrollPane(grid);
        sp.setFitToWidth(true);
        sp.setBorder(null);
        sp.setStyle("-fx-background-color:#F2F1EC;");
        return sp;
    }

    // ── Card builder ──────────────────────────────────────────────────────

    private VBox buildCard(Stage parentStage, String title, String subtitle,
                            List<PayrollMenuEntry> entries) {
        // Card header
        Label lblTitle = new Label(title);
        lblTitle.getStyleClass().add("mod-card-title");
        Label lblSub = new Label(subtitle);
        lblSub.getStyleClass().add("mod-card-subtitle");
        VBox cardHdr = new VBox(2, lblTitle, lblSub);
        cardHdr.getStyleClass().add("mod-card-header");
        cardHdr.setPadding(new Insets(12, 16, 12, 16));

        // Entry rows
        VBox entryList = new VBox(0);
        for (int i = 0; i < entries.size(); i++) {
            entryList.getChildren().add(
                buildEntryRow(entries.get(i), parentStage, i == entries.size() - 1));
        }

        VBox card = new VBox(0, cardHdr, entryList);
        card.getStyleClass().add("card");
        card.setStyle(
            "-fx-background-color:white;" +
            "-fx-background-radius:10;" +
            "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.07),8,0,0,2);");
        return card;
    }

    private HBox buildEntryRow(PayrollMenuEntry entry, Stage parentStage, boolean isLast) {
        Label nameLbl = new Label(entry.title());
        nameLbl.getStyleClass().add(
            entry.available() ? "mod-entry-title" : "mod-entry-title-dim");
        HBox.setHgrow(nameLbl, Priority.ALWAYS);

        HBox badges = new HBox(4);
        badges.setAlignment(Pos.CENTER_RIGHT);
        if (entry.available()) {
            Label live = new Label("live");
            live.getStyleClass().add("badge-new");
            badges.getChildren().add(live);
        } else {
            Label soon = new Label("soon");
            soon.getStyleClass().add("badge-soon");
            badges.getChildren().add(soon);
        }
        Label code = new Label(entry.programCode());
        code.getStyleClass().add("code-badge");
        badges.getChildren().add(code);

        HBox row = new HBox(8, nameLbl, badges);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mod-entry");

        Tooltip tt = new Tooltip(entry.subtitle());
        Tooltip.install(row, tt);

        if (entry.available() && entry.action() != null) {
            String borderBase = isLast
                ? "-fx-border-color:transparent;"
                : "-fx-border-color:transparent transparent rgba(0,0,0,0.07) transparent;" +
                  "-fx-border-width:0 0 0.5 0;";
            row.setOnMouseEntered(e -> row.setStyle(
                "-fx-background-color:#F8F8F6;-fx-cursor:hand;-fx-padding:6 16 6 16;" + borderBase));
            row.setOnMouseExited(e -> row.setStyle(
                "-fx-background-color:transparent;-fx-cursor:hand;-fx-padding:6 16 6 16;" + borderBase));
            row.setOnMouseClicked(e -> entry.action().run());
        }
        return row;
    }

    // ── Screen launchers ──────────────────────────────────────────────────

    private void openPayCodeMaintenance(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Pay Code Maintenance — PACD01");
        s.setScene(pacd01.buildScene(s));
        s.setMinWidth(820);
        s.setMinHeight(480);
        s.show();
    }

    private void openTimesheetEntry(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Timesheet Entry — PATM01");
        s.setScene(patm01.buildScene(s));
        s.setMinWidth(1000);
        s.setMinHeight(620);
        s.show();
    }

    private void openEmployeeMaintenance(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Employee Maintenance — PAEM01");
        s.setScene(paem01.buildScene(s));
        s.setMinWidth(960);
        s.setMinHeight(560);
        s.show();
    }

    private void openPayGroupMaintenance(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Pay Group Maintenance — PAPG01");
        s.setScene(papg01.buildScene(s));
        s.setMinWidth(900);
        s.setMinHeight(520);
        s.show();
    }

    private void openAwardMaintenance(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Award Maintenance — PAAW01");
        s.setScene(paaw01.buildScene(s));
        s.setMinWidth(900);
        s.setMinHeight(520);
        s.show();
    }

    private void openTaxScaleMaintenance(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Tax Scale Maintenance — PASU04");
        s.setScene(pasu04.buildScene(s));
        s.setMinWidth(880);
        s.setMinHeight(520);
        s.show();
    }

    private void openTaxScaleLoad(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Load ATO Tax Scales — PATX01");
        s.setScene(taxScaleLoad.buildScene(s));
        s.setMinWidth(600);
        s.setMinHeight(320);
        s.show();
    }

    private void openSetSuperPercentage(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Set Super Percentage — PASU14");
        s.setScene(pasu14.buildScene(s));
        s.setMinWidth(600);
        s.setMinHeight(400);
        s.show();
    }

    private void openUpdateAwardRateChanges(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Update Award Rate Changes — PASU11");
        s.setScene(pasu11.buildScene(s));
        s.setMinWidth(640);
        s.setMinHeight(380);
        s.show();
    }

    private void openGlobalEmployeeAwardUpdate(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Global Employee Award Update — PASU15");
        s.setScene(pasu15.buildScene(s));
        s.setMinWidth(720);
        s.setMinHeight(580);
        s.show();
    }

    private void openChangeEmployeePayRates(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Change Employee Pay Rates — PAEM60");
        s.setScene(paem60.buildScene(s));
        s.setMinWidth(760);
        s.setMinHeight(660);
        s.show();
    }

    private void openDuplicateTimesheets(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Duplicate Default Timesheets — PAEM11");
        s.setScene(paem11.buildScene(s));
        s.setMinWidth(680);
        s.setMinHeight(440);
        s.show();
    }

    private void openLeaveAccrualReversal(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Leave Accrual Reversal — PASU55");
        s.setScene(pasu55.buildScene(s));
        s.setMinWidth(840);
        s.setMinHeight(560);
        s.show();
    }

    private void openTimesheetSplits(Stage parentStage) {
        Stage s = new Stage();
        s.initOwner(parentStage);
        s.setTitle("Timesheet Splits — PAPC01");
        s.setScene(papc01.buildScene(s));
        s.setMinWidth(920);
        s.setMinHeight(560);
        s.show();
    }

    // ── Entry record ──────────────────────────────────────────────────────

    record PayrollMenuEntry(
        String   programCode,
        String   title,
        String   subtitle,
        boolean  available,
        Runnable action
    ) {}
}
