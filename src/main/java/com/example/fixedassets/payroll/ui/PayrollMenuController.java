package com.example.fixedassets.payroll.ui;

import com.example.fixedassets.model.AppSession;
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

    private final PayCodeMaintenanceController pacd01;
    private final AppSession                   appSession;

    public PayrollMenuController(PayCodeMaintenanceController pacd01,
                                  AppSession appSession) {
        this.pacd01     = pacd01;
        this.appSession = appSession;
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
                    false, null),
                new PayrollMenuEntry("PAPG01", "Pay Group Maintenance",
                    "Define payroll groups and frequencies",
                    false, null),
                new PayrollMenuEntry("PASU04", "Supervisor Setup",
                    "Payroll supervisor and security settings",
                    false, null),
                new PayrollMenuEntry("PAAW01", "Award Maintenance",
                    "Enterprise agreements and award schedules",
                    false, null)
            )), 0, 0);

        // Col 1: Pay Processing
        grid.add(buildCard(parentStage,
            "Pay Processing",
            "Timesheets, pay runs and payments",
            List.of(
                new PayrollMenuEntry("PATM01", "Timesheet Entry",
                    "Enter employee timesheets",
                    false, null),
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
                new PayrollMenuEntry("PADE01", "Payroll Year End",
                    "Roll forward to new payroll year",
                    false, null),
                new PayrollMenuEntry("PASU11", "Carry Forward Leave",
                    "Carry forward leave balances",
                    false, null),
                new PayrollMenuEntry("PASU55", "Recalculate YTD",
                    "Rebuild year-to-date totals",
                    false, null)
            )), 1, 1);

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

    // ── Entry record ──────────────────────────────────────────────────────

    record PayrollMenuEntry(
        String   programCode,
        String   title,
        String   subtitle,
        boolean  available,
        Runnable action
    ) {}
}
