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
package com.landmarksoftware.ui;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.SessionService;
import com.landmarksoftware.model.SessionData;
import com.landmarksoftware.payroll.ui.AwardMaintenanceController;
import com.landmarksoftware.payroll.ui.PayGroupMaintenanceController;
import com.landmarksoftware.payroll.ui.PayrollMenuController;
import com.landmarksoftware.payroll.ui.TaxScaleLoadController;
import com.landmarksoftware.payroll.ui.SetSuperPercentageController;
import com.landmarksoftware.payroll.ui.UpdateAwardRateChangesController;
import com.landmarksoftware.payroll.ui.GlobalEmployeeAwardUpdateController;
import com.landmarksoftware.payroll.ui.ChangeEmployeePayRatesController;
import com.landmarksoftware.payroll.ui.DuplicateTimesheetsController;
import com.landmarksoftware.payroll.ui.LeaveAccrualReversalController;
import com.landmarksoftware.payroll.ui.TimesheetEntryController;
import com.landmarksoftware.payroll.ui.TimesheetSplitsController;
import com.landmarksoftware.payroll.ui.TaxScaleMaintenanceController;
import com.landmarksoftware.payroll.ui.PayCodeMaintenanceController;
import com.landmarksoftware.payroll.ui.EmployeeMaintenanceController;
import com.landmarksoftware.repository.CompanyRepository;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Main menu — LedgerPro-inspired layout.
 *
 * Layout:
 *   ┌──────────────────────────────────────────────────────┐
 *   │  Sidebar (192px)  │  Main content area               │
 *   │  ─────────────    │  ┌──────────────────────────┐   │
 *   │  Overview         │  │ Page title + subtitle     │   │
 *   │  ─────────────    │  └──────────────────────────┘   │
 *   │  Fixed Assets     │  ┌──────┐ ┌──────┐              │
 *   │    Asset Register │  │ FA   │ │ FA   │  2-col grid  │
 *   │    Depreciation   │  │Rpt   │ │Maint │              │
 *   │  ─────────────    │  └──────┘ └──────┘              │
 *   │  General Ledger   │  ┌──────┐ ┌──────┐              │
 *   │  AR / AP          │  │ GL   │ │ AR/AP│              │
 *   │  ─────────────    │  └──────┘ └──────┘              │
 *   │  [Org footer]     │  ┌────────────┐ ┌─────────┐     │
 *   │                   │  │ Recently   │ │ FA Stats│     │
 *   │                   │  │ used       │ │         │     │
 *   └──────────────────────────────────────────────────────┘
 */
@Component
public class MainMenuController {

    private final ProjectionScreenController         projectionScreen;
    private final TransactionListScreenController    transactionListScreen;
    private final AcquiredRetiredScreenController    acquiredRetiredScreen;
    private final AssetMaintenanceController         assetMaintenance;
    private final AssetRegisterController            assetRegister;
    private final AcquisitionEntryController         acquisitionEntry;
    private final FavouritesStore                    favouritesStore;
    private final CompanyMaintenanceController       companyMaintenance;
    private final PayrollMenuController              payrollMenu;
    private final PayCodeMaintenanceController       payCodeScreen;
    private final EmployeeMaintenanceController      employeeScreen;
    private final PayGroupMaintenanceController      payGroupScreen;
    private final TaxScaleMaintenanceController      taxScaleScreen;
    private final AwardMaintenanceController         awardScreen;
    private final TaxScaleLoadController             taxScaleLoadScreen;
    private final SetSuperPercentageController       setSuperPercentageScreen;
    private final UpdateAwardRateChangesController   updateAwardRatesScreen;
    private final GlobalEmployeeAwardUpdateController globalAwardUpdateScreen;
    private final ChangeEmployeePayRatesController   changeEmpPayRatesScreen;
    private final DuplicateTimesheetsController      dupTimesheetsScreen;
    private final LeaveAccrualReversalController     leaveAccrualScreen;
    private final TimesheetSplitsController          timesheetSplitsScreen;
    private final TimesheetEntryController           timesheetEntryScreen;
    private final JdbcTemplate                       jdbc;
    private final SessionService                     sessionService;
    private final CompanyRepository                  companyRepo;
    private final LastSessionStore                   lastSessionStore;
    private final AppSession                         appSession;

    // ── Session state (mirrors GLPASS / MENU23 selection) ────────
    private int    sessionCompanyNo   = 0;  // 0 = not yet loaded; set by loadDefaultSession
    private String sessionCompanyName = "";
    private int    sessionYearNo      = 0;       // 4-digit e.g. 2025
    private String sessionYearDesc    = "";      // e.g. "FY 2024–25"

    // ── Live footer labels (updated when session changes) ─────────
    private Label  lblFooterCompany;
    private Label  lblFooterYear;
    private Label  lblFooterUser;

    private final List<MenuEntry> allEntries = new ArrayList<>();

    // Recently-used list — last 5 opened
    private final LinkedList<MenuEntry> recentlyUsed = new LinkedList<>();
    private VBox recentlyUsedBox;

    public MainMenuController(ProjectionScreenController projectionScreen,
                               TransactionListScreenController transactionListScreen,
                               AcquiredRetiredScreenController acquiredRetiredScreen,
                               AssetMaintenanceController assetMaintenance,
                               AssetRegisterController assetRegister,
                               AcquisitionEntryController acquisitionEntry,
                               FavouritesStore favouritesStore,
                               CompanyMaintenanceController companyMaintenance,
                               PayrollMenuController payrollMenu,
                               PayCodeMaintenanceController payCodeScreen,
                               EmployeeMaintenanceController employeeScreen,
                               PayGroupMaintenanceController payGroupScreen,
                               TaxScaleMaintenanceController taxScaleScreen,
                               AwardMaintenanceController awardScreen,
                               TaxScaleLoadController taxScaleLoadScreen,
                               SetSuperPercentageController setSuperPercentageScreen,
                               UpdateAwardRateChangesController updateAwardRatesScreen,
                               GlobalEmployeeAwardUpdateController globalAwardUpdateScreen,
                               ChangeEmployeePayRatesController changeEmpPayRatesScreen,
                               DuplicateTimesheetsController dupTimesheetsScreen,
                               LeaveAccrualReversalController leaveAccrualScreen,
                               TimesheetSplitsController timesheetSplitsScreen,
                               TimesheetEntryController timesheetEntryScreen,
                               JdbcTemplate jdbc,
                               SessionService sessionService,
                               CompanyRepository companyRepo,
                               LastSessionStore lastSessionStore,
                               AppSession appSession) {
        this.projectionScreen      = projectionScreen;
        this.transactionListScreen = transactionListScreen;
        this.acquiredRetiredScreen = acquiredRetiredScreen;
        this.assetMaintenance      = assetMaintenance;
        this.assetRegister         = assetRegister;
        this.acquisitionEntry      = acquisitionEntry;
        this.favouritesStore       = favouritesStore;
        this.companyMaintenance    = companyMaintenance;
        this.payrollMenu           = payrollMenu;
        this.payCodeScreen         = payCodeScreen;
        this.employeeScreen        = employeeScreen;
        this.payGroupScreen        = payGroupScreen;
        this.taxScaleScreen        = taxScaleScreen;
        this.awardScreen           = awardScreen;
        this.taxScaleLoadScreen    = taxScaleLoadScreen;
        this.setSuperPercentageScreen = setSuperPercentageScreen;
        this.updateAwardRatesScreen = updateAwardRatesScreen;
        this.globalAwardUpdateScreen = globalAwardUpdateScreen;
        this.changeEmpPayRatesScreen = changeEmpPayRatesScreen;
        this.dupTimesheetsScreen   = dupTimesheetsScreen;
        this.leaveAccrualScreen    = leaveAccrualScreen;
        this.timesheetSplitsScreen = timesheetSplitsScreen;
        this.timesheetEntryScreen  = timesheetEntryScreen;
        this.jdbc                  = jdbc;
        this.sessionService        = sessionService;
        this.companyRepo           = companyRepo;
        this.lastSessionStore      = lastSessionStore;
        this.appSession            = appSession;
    }

    // ═══════════════════════════════════════════════════════════════
    // Scene
    // ═══════════════════════════════════════════════════════════════

    public Scene buildScene() {
        buildEntries();

        // Load initial company/year from DB
        loadDefaultSession();

        // Sidebar
        VBox sidebar = buildSidebar();

        // Populate user line now that AppSession is set by LoginController
        refreshFooterUser();

        // Main scroll area
        ScrollPane scroll = new ScrollPane(buildMainContent());
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-pane");
        HBox.setHgrow(scroll, Priority.ALWAYS);

        HBox root = new HBox(sidebar, scroll);
        root.setStyle("-fx-background-color: #F2F1EC;");

        Scene scene = new Scene(root, 960, 680);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    /**
     * Restore the previous session's company + year if a previous MENU23
     * pick was persisted via {@link LastSessionStore}; otherwise default to
     * the first available company + most recent year.
     */
    private void loadDefaultSession() {
        var companies = java.util.Collections.<com.landmarksoftware.model.CompanyRow>emptyList();
        try { companies = companyRepo.findAll(); }
        catch (Exception ignored) { /* fall through to placeholder */ }

        com.landmarksoftware.model.CompanyRow chosen = null;
        // Try the persisted last pick first — only honour it if that company
        // still exists in the master list.
        if (lastSessionStore != null && lastSessionStore.hasLast()) {
            int wanted = lastSessionStore.getLastCompanyNo();
            for (var c : companies) {
                if (c.getCompanyNo() == wanted) { chosen = c; break; }
            }
        }
        if (chosen == null && !companies.isEmpty()) chosen = companies.get(0);

        if (chosen != null) {
            sessionCompanyNo   = chosen.getCompanyNo();
            sessionCompanyName = chosen.getName() != null
                ? chosen.getName() : "Company " + chosen.getCompanyNo();
        } else {
            sessionCompanyName = "Company " + sessionCompanyNo;
        }

        // Prefer the persisted year if it's still valid for this company.
        int persistedYear = lastSessionStore == null ? 0 : lastSessionStore.getLastYearNo();
        if (chosen != null && persistedYear > 0
                && sessionService.findCurrentYear(sessionCompanyNo) > 0) {
            sessionYearNo   = persistedYear;
            sessionYearDesc = buildYearDesc(persistedYear % 100);
        } else {
            loadYearForSession();
        }
        pushToAppSession();
    }

    /** Refresh the user/session line in the sidebar footer. Called after login. */
    private void refreshFooterUser() {
        if (lblFooterUser == null) return;
        String name = appSession.getUserName();
        int    sess = appSession.getTerminalNo();
        // Show full name, and session number if allocated
        // Show user name only (no session number — can get cut off in narrow sidebar)
        String line = name.isEmpty() ? appSession.getUserId() : name;
        lblFooterUser.setText(line);
    }

    /** Push current sidebar session state into the shared AppSession bean. */
    private void pushToAppSession() {
        appSession.setCompanyNo(sessionCompanyNo);
        appSession.setCompanyName(sessionCompanyName);
        appSession.setYrNo(sessionYearNo % 100);
        appSession.setYearNo(sessionYearNo);
        appSession.setYearDesc(sessionYearDesc);

        // Load FA config + year dates from CPCOYCO and GLDATES via SessionService
        SessionData sd = sessionService.loadSessionData(sessionCompanyNo, sessionYearNo);
        appSession.setFaTaxYrEndMth(sd.faTaxYrEndMth());
        if (sd.yrStartDate() != null) appSession.setYrStartDate(sd.yrStartDate());
        if (sd.yrEndDate()   != null) appSession.setYrEndDate(sd.yrEndDate());
        appSession.setBatchControlFlag(sd.batchControlFlag());

        // Note: userId already set by LoginController — do not overwrite
        System.out.println("AppSession: " + appSession);
    }

    /** Convert GLDATES day-number (days since some epoch) to LocalDate.
     *  Landmark stores dates as YYMMDD packed — try parsing that first. */
    private java.time.LocalDate dayNoToDate(int v) {
        try {
            // Try YYMMDD format: e.g. 250630 = 2025-06-30
            int yy = v / 10000, mm = (v / 100) % 100, dd = v % 100;
            int yyyy = yy < 40 ? 2000 + yy : 1900 + yy;
            return java.time.LocalDate.of(yyyy, mm, dd);
        } catch (Exception e) { return null; }
    }

    private void loadYearForSession() {
        int yr4 = sessionService.findCurrentYear(sessionCompanyNo);
        if (yr4 > 0) {
            sessionYearNo   = yr4;
            sessionYearDesc = buildYearDesc(yr4 % 100);
        } else {
            sessionYearDesc = "FY " + sessionYearNo;
        }
        System.out.println("loadYearForSession: year_no=" + sessionYearNo
            + " desc=" + sessionYearDesc);
    }

    private String buildYearDesc(int twoDigitYr) {
        int yr4 = twoDigitYr < 40 ? 2000 + twoDigitYr : 1900 + twoDigitYr;
        return "FY " + (yr4 - 1) + "–" + String.format("%02d", twoDigitYr);
    }

    // ═══════════════════════════════════════════════════════════════
    // Sidebar
    // ═══════════════════════════════════════════════════════════════

    private VBox buildSidebar() {
        VBox sb = new VBox(0);
        sb.getStyleClass().add("sidebar");

        // Logo
        sb.getChildren().add(buildSidebarLogo());

        // Overview section
        sb.getChildren().add(sidebarSectionLabel("Overview"));
        sb.getChildren().add(sidebarNavItem("Home", true));

        // Landmark
        sb.getChildren().add(sidebarSectionLabel("Landmark Software"));
        sb.getChildren().add(sidebarNavItem("Asset Register", false, "new"));
        sb.getChildren().add(sidebarNavItem("Depreciation", false, null));
        sb.getChildren().add(sidebarNavItem("Transactions", false, null));
        sb.getChildren().add(sidebarNavItem("Maintenance", false, null));

        // Payroll
        sb.getChildren().add(sidebarSectionLabel("Payroll"));
        sb.getChildren().add(sidebarNavItem("Pay Codes", false, "live"));
        sb.getChildren().add(sidebarNavItem("Employees", false, null));
        sb.getChildren().add(sidebarNavItem("Pay Runs", false, null));

        // General Ledger
        sb.getChildren().add(sidebarSectionLabel("General Ledger"));
        sb.getChildren().add(sidebarNavItem("Chart of Accounts", false, null));
        sb.getChildren().add(sidebarNavItem("Journal Entries", false, null));

        // Accounts
        sb.getChildren().add(sidebarSectionLabel("Accounts"));
        sb.getChildren().add(sidebarNavItem("Receivable", false, null));
        sb.getChildren().add(sidebarNavItem("Payable", false, null));

        // Spacer
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sb.getChildren().add(spacer);

        // Footer — org name
        sb.getChildren().add(buildSidebarFooter());

        return sb;
    }


    private HBox buildSidebarLogo() {
        javafx.scene.Node pin = LandmarkLogo.iconMark(56);

        Label name = new Label("Landmark");
        name.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1A1A1A;");
        Label sub = new Label("Software");
        sub.setStyle("-fx-font-size: 10px; -fx-text-fill: #888780;");
        VBox text = new VBox(0, name, sub);

        HBox logo = new HBox(9, pin, text);
        logo.setAlignment(Pos.CENTER_LEFT);
        logo.setPadding(new Insets(12, 16, 12, 16));
        logo.setStyle(
            "-fx-background-color: #FFFFFF;" +
            "-fx-border-color: transparent transparent rgba(0,0,0,0.10) transparent;" +
            "-fx-border-width: 0 0 0.5 0;");
        return logo;
    }

    private Label sidebarSectionLabel(String text) {
        Label lbl = new Label(text.toUpperCase());
        lbl.getStyleClass().add("sidebar-section-label");
        lbl.setMaxWidth(Double.MAX_VALUE);
        return lbl;
    }

    private HBox sidebarNavItem(String text, boolean active) {
        return sidebarNavItem(text, active, null);
    }

    private HBox sidebarNavItem(String text, boolean active, String badge) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size: 12px; -fx-text-fill: " +
            (active ? "#1A6EF5; -fx-font-weight: bold;" : "#555553;"));
        HBox.setHgrow(lbl, Priority.ALWAYS);

        HBox row = new HBox(lbl);
        row.setAlignment(Pos.CENTER_LEFT);

        if (badge != null) {
            Label b = new Label(badge);
            b.setStyle(
                "-fx-font-size: 9px; -fx-font-weight: bold;" +
                "-fx-text-fill: #1A6EF5; -fx-background-color: #EEF4FF;" +
                "-fx-background-radius: 4; -fx-padding: 1 5 1 5;");
            row.getChildren().add(b);
        }

        row.setPadding(new Insets(6, 16, 6, active ? 14 : 16));
        row.setStyle(
            "-fx-background-color: " + (active ? "#EEF4FF" : "transparent") + ";" +
            "-fx-border-color: transparent transparent transparent " +
            (active ? "#1A6EF5" : "transparent") + ";" +
            "-fx-border-width: 0 0 0 2;" +
            "-fx-cursor: hand;");

        if (!active) {
            row.setOnMouseEntered(e ->
                row.setStyle("-fx-background-color: #F8F8F6; -fx-cursor: hand;" +
                    "-fx-border-color: transparent; -fx-border-width: 0 0 0 2;"));
            row.setOnMouseExited(e ->
                row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;" +
                    "-fx-border-color: transparent; -fx-border-width: 0 0 0 2;"));
        }
        return row;
    }

    private HBox buildSidebarFooter() {
        Label icon = new Label("⚙");
        icon.setStyle(
            "-fx-font-size: 13px; -fx-text-fill: #888780;" +
            "-fx-min-width: 26px; -fx-min-height: 26px; -fx-alignment: center;");

        // User line — populated by refreshFooterUser() after login sets AppSession
        lblFooterUser = new Label("");
        lblFooterUser.setStyle(
            "-fx-font-size: 10px; -fx-text-fill: #1A6EF5;" +
            "-fx-font-weight: bold; -fx-max-width: 150px;");

        lblFooterCompany = new Label(sessionCompanyName.isEmpty() ? "No company" : sessionCompanyName);
        lblFooterCompany.setStyle(
            "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #1A1A1A;" +
            "-fx-wrap-text: true; -fx-max-width: 150px;");

        lblFooterYear = new Label(sessionYearDesc.isEmpty() ? "No year set" : sessionYearDesc);
        lblFooterYear.setStyle("-fx-font-size: 10px; -fx-text-fill: #888780;");

        VBox textBox = new VBox(2, lblFooterCompany, lblFooterYear, lblFooterUser);

        HBox footer = new HBox(8, icon, textBox);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("sidebar-footer");
        footer.setCursor(javafx.scene.Cursor.HAND);

        Tooltip.install(footer, new Tooltip("Click to change company or financial year"));

        footer.setOnMouseEntered(e ->
            footer.setStyle("-fx-background-color: rgba(26,110,245,0.07);"));
        footer.setOnMouseExited(e ->
            footer.setStyle("-fx-background-color: transparent;"));
        footer.setOnMouseClicked(e ->
            showCompanyYearDialog(footer.getScene().getWindow()));

        return footer;
    }


    // ═══════════════════════════════════════════════════════════════
    // Main content
    // ═══════════════════════════════════════════════════════════════

    private VBox buildMainContent() {
        VBox main = new VBox(0);
        main.setStyle("-fx-background-color: #F2F1EC;");

        // ── Xero-style top navigation bar ─────────────────────────────────
        main.getChildren().add(buildTopNavBar());

        // ── Tab content area ───────────────────────────────────────────────
        tabContentArea = new StackPane();
        tabContentArea.setPadding(new Insets(20, 24, 24, 24));
        tabContentArea.setStyle("-fx-background-color: #F2F1EC;");
        VBox.setVgrow(tabContentArea, Priority.ALWAYS);

        // Build content for each tab
        tabPanes = new java.util.HashMap<>();
        tabPanes.put("Sales",       buildTabContent("Accounts Receivable",
            "#D97706", "Debtors, receipts & sales",
            List.of(entry("AR","ARTL01"), entry("AR","ARTL02"), entry("AR","ARMA01"))));
        tabPanes.put("Purchasing",  buildTabContent("Accounts Payable",
            "#7C3AED", "Creditors, payments & purchasing",
            List.of(entry("AP","APTL01"), entry("AP","APMA01"))));
        tabPanes.put("Inventory",   buildTabContent("Inventory",
            "#059669", "Stock management & warehousing",
            List.of(entry("SM","SMTL01"))));
        tabPanes.put("Accounting",  buildAccountingTab());
        tabPanes.put("Reporting",   buildTabContent("Reporting",
            "#1A6EF5", "Reports, analysis & enquiries",
            List.of(entry("FA","FATL12"), entry("FA","FATL10"),
                    entry("FA","FATL02"), entry("FA","FATL03"),
                    entry("FA","FATL14"))));
        tabPanes.put("Payroll",     buildPayrollTabContent());
        tabPanes.put("BAS",         buildTabContent("BAS / Tax",
            "#DC2626", "Business Activity Statement & tax",
            List.of()));
        tabPanes.put("System",      buildTabContent("System",
            "#374151", "Company & user maintenance",
            List.of(entry("SYS","MENU22"))));

        // Show Accounting tab by default
        showTab("Accounting");
        main.getChildren().add(tabContentArea);

        return main;
    }

    // Stores tab content nodes keyed by tab name
    private java.util.Map<String, javafx.scene.Node> tabPanes;
    private StackPane tabContentArea;

    private void showTab(String name) {
        tabContentArea.getChildren().clear();
        javafx.scene.Node content = tabPanes.get(name);
        if (content != null) tabContentArea.getChildren().add(content);
        // Update tab button styles
        if (topNavButtons != null) {
            topNavButtons.forEach((n, btn) -> {
                boolean active = n.equals(name);
                btn.setStyle(
                    "-fx-background-color: " + (active ? "#FFFFFF" : "transparent") + ";" +
                    "-fx-text-fill: " + (active ? "#1A1A1A" : "#6B7280") + ";" +
                    "-fx-font-size: 13px;" +
                    "-fx-font-weight: " + (active ? "bold" : "normal") + ";" +
                    "-fx-padding: 10 16;" +
                    "-fx-border-color: " + (active ? "rgba(0,0,0,0.08)" : "transparent") + ";" +
                    "-fx-border-width: 0 0 2 0;" +
                    "-fx-border-radius: 0;" +
                    "-fx-background-radius: 0;" +
                    "-fx-cursor: hand;" +
                    (active ? "-fx-border-color: #1A6EF5; -fx-border-width: 0 0 2 0;" : ""));
            });
        }
    }

    private java.util.LinkedHashMap<String, Button> topNavButtons;

    private HBox buildTopNavBar() {
        topNavButtons = new java.util.LinkedHashMap<>();
        String[] tabs = {"Sales", "Purchasing", "Inventory", "Accounting", "Payroll", "BAS", "Reporting", "System"};

        HBox nav = new HBox(0);
        nav.setAlignment(Pos.CENTER_LEFT);
        nav.setStyle(
            "-fx-background-color: #FFFFFF;" +
            "-fx-border-color: transparent transparent rgba(0,0,0,0.08) transparent;" +
            "-fx-border-width: 0 0 1 0;");
        nav.setPadding(new Insets(0, 16, 0, 16));

        for (String tab : tabs) {
            Button btn = new Button(tab);
            btn.setOnAction(e -> showTab(tab));
            topNavButtons.put(tab, btn);
            nav.getChildren().add(btn);
        }

        // Apply initial default style
        topNavButtons.forEach((n, btn) ->
            btn.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-text-fill: #6B7280;" +
                "-fx-font-size: 13px;" +
                "-fx-padding: 10 16;" +
                "-fx-border-radius: 0;" +
                "-fx-background-radius: 0;" +
                "-fx-cursor: hand;"));

        return nav;
    }

    /** Build the Accounting tab — two column grid with FA + GL cards */
    private VBox buildAccountingTab() {
        VBox tab = new VBox(16);
        tab.setPadding(new Insets(4, 0, 0, 0));

        // Bottom row: recently used + stats
        recentlyUsedBox = buildRecentlyUsedBody();
        VBox recentCard = wrapCard(buildRecentlyUsedHeader(), recentlyUsedBox);
        VBox statsCard  = buildStatsCard();
        HBox.setHgrow(recentCard, Priority.ALWAYS);
        statsCard.setMinWidth(220); statsCard.setMaxWidth(260);

        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(12);
        ColumnConstraints col1 = new ColumnConstraints(); col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints(); col2.setPercentWidth(50);
        grid.getColumnConstraints().addAll(col1, col2);

        grid.add(buildModuleCard("#7C3AED", "Fixed Assets — Maintenance",
            "Asset records & acquisitions",
            List.of(entry("FA-MAINT","FAAS01"), entry("FA-MAINT","FAAQ01"),
                    entry("FA","FAAS04"))), 0, 0);

        grid.add(buildModuleCard("#1A6EF5", "Fixed Assets — Reporting",
            "Depreciation, listings & analysis",
            List.of(entry("FA","FATL12"), entry("FA","FATL10"),
                    entry("FA","FATL02"), entry("FA","FATL03"),
                    entry("FA","FATL14"))), 1, 0);

        grid.add(buildModuleCard("#059669", "General Ledger",
            "Accounts, journals & balances",
            List.of(entry("GL","GLTL01"), entry("GL","GLTL02"),
                    entry("GL","GLTL03"), entry("GL","GLMA01"))), 0, 1);

        grid.add(buildModuleCard("#374151", "System",
            "Company & user maintenance",
            List.of(entry("SYS","MENU22"))), 1, 1);

        tab.getChildren().addAll(grid, new HBox(12, recentCard, statsCard));
        return tab;
    }

    /** Build the Payroll tab content — launches PayrollMenuController. */
    private VBox buildPayrollTabContent() {
        VBox tab = new VBox(16);
        tab.setPadding(new Insets(4, 0, 0, 0));

        // Header
        Label title = new Label("Payroll");
        title.setStyle("-fx-font-size:18px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("Pay codes, employees, timesheets and pay processing");
        sub.setStyle("-fx-font-size:12px;-fx-text-fill:#888780;");
        VBox hdr = new VBox(4, title, sub);
        tab.getChildren().add(hdr);

        // Quick-launch grid: two rows × two columns
        GridPane grid = new GridPane();
        grid.setHgap(16); grid.setVgap(16);
        ColumnConstraints cc = new ColumnConstraints(); cc.setPercentWidth(50);
        grid.getColumnConstraints().addAll(cc, cc);

        // Setup card
        grid.add(buildPayrollModuleCard("Setup & Maintenance",
            "#1A6EF5",
            "Pay codes, employees, groups and awards",
            List.of(entry("PY","PACD01"), entry("PY","PAEM01"),
                    entry("PY","PAPG01"), entry("PY","PASU04"),
                    entry("PY","PAAW01"))), 0, 0);

        // Pay Processing card
        grid.add(buildPayrollModuleCard("Pay Processing",
            "#059669",
            "Timesheets, pay runs and bank payments",
            List.of(entry("PY","PATM01"), entry("PY","PAPP01"),
                    entry("PY","PABK02"), entry("PY","PAPA14"))), 1, 0);

        // Reports card
        grid.add(buildPayrollModuleCard("Reports & Compliance",
            "#7C3AED",
            "Payroll reports, STP and payment summaries",
            List.of(entry("PY","PATL10"), entry("PY","PATL12"),
                    entry("PY","PAST10"), entry("PY","PAPS26"))), 0, 1);

        // Year End card
        grid.add(buildPayrollModuleCard("Year End",
            "#D97706",
            "Year close and carry-forward processing",
            List.of(entry("PY","PATX01"), entry("PY","PADE01"))), 1, 1);

        // Mass Update card (Wave 2 batch utilities)
        grid.add(buildPayrollModuleCard("Mass Update",
            "#7C3AED",
            "Batch changes across employees and pay codes",
            List.of(entry("PY","PASU14"), entry("PY","PASU11"),
                    entry("PY","PASU15"), entry("PY","PAEM60"))), 0, 2);

        // Batch Operations card (pay-run + timesheet utilities)
        grid.add(buildPayrollModuleCard("Batch Operations",
            "#0EA5E9",
            "Pay run and timesheet utilities",
            List.of(entry("PY","PAEM11"), entry("PY","PASU55"),
                    entry("PY","PAPC01"))), 1, 2);

        tab.getChildren().add(grid);
        VBox.setVgrow(grid, Priority.ALWAYS);
        return tab;
    }

    /** Build a named module card for the payroll tab grid. */
    private VBox buildPayrollModuleCard(String title, String accentColor,
                                         String subtitle, List<MenuEntry> entries) {
        Label lTitle = new Label(title);
        lTitle.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label lSub = new Label(subtitle);
        lSub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        VBox cardHdr = new VBox(2, lTitle, lSub);
        cardHdr.setPadding(new Insets(12, 16, 12, 16));
        cardHdr.setStyle("-fx-border-color:transparent transparent rgba(0,0,0,.07) transparent;" +
                         "-fx-border-width:0 0 0.5 0;" +
                         "-fx-border-left-color:" + accentColor + ";" +
                         "-fx-border-left-width:3;");

        VBox entryList = new VBox(0);
        for (int i = 0; i < entries.size(); i++)
            entryList.getChildren().add(
                buildModuleEntryRow(entries.get(i), i == entries.size() - 1));

        VBox card = new VBox(0, cardHdr, entryList);
        card.setStyle("-fx-background-color:white;-fx-background-radius:10;" +
                      "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.07),8,0,0,2);");
        return card;
    }

    /** Launcher: open the full Payroll module hub screen. */
    private void openPayrollMenu() {
        Stage s = new Stage();
        s.setTitle("Payroll — Landmark Software");
        s.setScene(payrollMenu.buildScene(s));
        s.setMinWidth(880);
        s.setMinHeight(580);
        s.show();
    }


    /** Build a simple tab content panel for non-FA modules */
    private VBox buildTabContent(String title, String accentColor,
                                  String subtitle, List<MenuEntry> entries) {
        VBox tab = new VBox(16);
        tab.setPadding(new Insets(4, 0, 0, 0));
        tab.getChildren().add(buildModuleCard(accentColor, title, subtitle, entries));
        return tab;
    }

    private HBox buildPageTitle() {
        javafx.scene.Node logoImg = LandmarkLogo.iconMark(56);

        Label title = new Label("Landmark Software");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1A1A1A;");
        VBox titleBox = new VBox(2, title);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        HBox bar = new HBox(12, logoImg, titleBox);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 0, 18, 0));
        return bar;
    }

    // ── Module card ────────────────────────────────────────────────

    private VBox buildModuleCard(String accentColor, String title,
                                  String subtitle, List<MenuEntry> entries) {
        // Header
        Label icon = new Label();
        icon.setStyle(
            "-fx-background-color: " + accentColor + "; -fx-background-radius: 7;" +
            "-fx-min-width: 28px; -fx-min-height: 28px;");

        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("mod-card-title");
        Label subLbl = new Label(subtitle);
        subLbl.getStyleClass().add("mod-card-subtitle");
        VBox titleBox = new VBox(1, titleLbl, subLbl);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        HBox header = new HBox(10, icon, titleBox);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("mod-card-header");

        VBox card = new VBox(0);
        card.getStyleClass().add("card");
        card.getChildren().add(header);

        for (int i = 0; i < entries.size(); i++) {
            MenuEntry e = entries.get(i);
            HBox row = buildModuleEntryRow(e, i == entries.size() - 1);
            card.getChildren().add(row);
        }

        return card;
    }

    private HBox buildModuleEntryRow(MenuEntry entry, boolean isLast) {
        // Entry name
        Label nameLbl = new Label(entry.getTitle());
        nameLbl.getStyleClass().add(
            entry.isAvailable() ? "mod-entry-title" : "mod-entry-title-dim");
        HBox.setHgrow(nameLbl, Priority.ALWAYS);

        // Badges
        HBox badges = new HBox(4);
        badges.setAlignment(Pos.CENTER_RIGHT);

        // "new" badge for recently-added programs
        if ("FATL10".equals(entry.getProgramCode()) || "FAAS01".equals(entry.getProgramCode())) {
            Label newBadge = new Label("new");
            newBadge.getStyleClass().add("badge-new");
            badges.getChildren().add(newBadge);
        }
        if (!entry.isAvailable()) {
            Label soon = new Label("soon");
            soon.getStyleClass().add("badge-soon");
            badges.getChildren().add(soon);
        }

        Label code = new Label(entry.getProgramCode());
        code.getStyleClass().add("code-badge");
        badges.getChildren().add(code);

        HBox row = new HBox(8, nameLbl, badges);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mod-entry");

        // Remove bottom border on last row
        if (isLast) {
            row.setStyle("-fx-background-color: transparent; -fx-padding: 6 16 6 16;" +
                "-fx-border-color: transparent; -fx-cursor: " +
                (entry.isAvailable() ? "hand" : "default") + ";");
        }

        if (entry.isAvailable()) {
            row.setOnMouseEntered(evt ->
                row.setStyle("-fx-background-color: #F8F8F6; -fx-cursor: hand;" +
                    "-fx-padding: 6 16 6 16;" +
                    (isLast ? "-fx-border-color: transparent;" :
                     "-fx-border-color: transparent transparent rgba(0,0,0,0.07) transparent;" +
                     "-fx-border-width: 0 0 0.5 0;")));
            row.setOnMouseExited(evt ->
                row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;" +
                    "-fx-padding: 6 16 6 16;" +
                    (isLast ? "-fx-border-color: transparent;" :
                     "-fx-border-color: transparent transparent rgba(0,0,0,0.07) transparent;" +
                     "-fx-border-width: 0 0 0.5 0;")));
            row.setOnMouseClicked(evt -> {
                if (entry.getAction() != null) {
                    trackRecent(entry);
                    entry.getAction().run();
                }
            });
        }
        return row;
    }

    // ── Recently used card ─────────────────────────────────────────

    private HBox buildRecentlyUsedHeader() {
        Label t = new Label("Recently used");
        t.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1A1A1A;");
        HBox.setHgrow(t, Priority.ALWAYS);
        Label lnk = new Label("View all ›");
        lnk.setStyle("-fx-font-size: 11px; -fx-text-fill: #1A6EF5; -fx-cursor: hand;");
        HBox hdr = new HBox(t, lnk);
        hdr.setAlignment(Pos.CENTER_LEFT);
        hdr.setPadding(new Insets(12, 16, 12, 16));
        hdr.setStyle("-fx-border-color: transparent transparent rgba(0,0,0,0.10) transparent;" +
            "-fx-border-width: 0 0 0.5 0;");
        return hdr;
    }

    private VBox buildRecentlyUsedBody() {
        VBox body = new VBox(0);
        refreshRecentlyUsedRows(body);
        return body;
    }

    private void refreshRecentlyUsedRows(VBox body) {
        body.getChildren().clear();
        List<MenuEntry> toShow = recentlyUsed.isEmpty()
            ? allEntries.stream().filter(MenuEntry::isAvailable).limit(4).toList()
            : new ArrayList<>(recentlyUsed);

        for (MenuEntry e : toShow) {
            HBox row = buildRecentRow(e);
            body.getChildren().add(row);
        }

        if (toShow.isEmpty()) {
            Label empty = new Label("No recent activity");
            empty.setStyle("-fx-font-size: 12px; -fx-text-fill: #888780; -fx-padding: 12 16 12 16;");
            body.getChildren().add(empty);
        }
    }

    private HBox buildRecentRow(MenuEntry e) {
        Label av = new Label(e.getProgramCode().substring(0, Math.min(2, e.getProgramCode().length())));
        av.setStyle(
            "-fx-background-color: #EEF4FF; -fx-text-fill: #1A6EF5;" +
            "-fx-font-size: 9px; -fx-font-weight: bold; -fx-background-radius: 5;" +
            "-fx-min-width: 24px; -fx-min-height: 24px; -fx-alignment: center;");

        Label name = new Label(e.getTitle());
        name.setStyle("-fx-font-size: 12px; -fx-text-fill: #1A1A1A;");
        Label module = new Label(e.getProgramCode() + " · " + moduleLabel(e.getModuleCode()));
        module.setStyle("-fx-font-size: 10px; -fx-text-fill: #888780;");
        VBox nameBox = new VBox(1, name, module);
        HBox.setHgrow(nameBox, Priority.ALWAYS);

        HBox row = new HBox(8, av, nameBox);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 16, 8, 16));
        row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;" +
            "-fx-border-color: transparent transparent rgba(0,0,0,0.07) transparent;" +
            "-fx-border-width: 0 0 0.5 0;");
        row.setOnMouseEntered(evt ->
            row.setStyle("-fx-background-color: #F8F8F6; -fx-cursor: hand;" +
                "-fx-border-color: transparent transparent rgba(0,0,0,0.07) transparent;" +
                "-fx-border-width: 0 0 0.5 0;"));
        row.setOnMouseExited(evt ->
            row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;" +
                "-fx-border-color: transparent transparent rgba(0,0,0,0.07) transparent;" +
                "-fx-border-width: 0 0 0.5 0;"));
        row.setOnMouseClicked(evt -> {
            if (e.getAction() != null) e.getAction().run();
        });
        return row;
    }

    private VBox buildRecentlyUsedCard() {
        // placeholder — replaced in buildMainContent
        return new VBox();
    }

    // ── Stats card ─────────────────────────────────────────────────

    private VBox buildStatsCard() {
        Label t = new Label("System Overview");
        t.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1A1A1A;");
        Label lnk = new Label("FATL10 ›");
        lnk.setStyle("-fx-font-size: 11px; -fx-text-fill: #1A6EF5; -fx-cursor: hand;");
        lnk.setOnMouseClicked(e -> openAssetRegister());
        HBox.setHgrow(t, Priority.ALWAYS);
        HBox hdr = new HBox(t, lnk);
        hdr.setAlignment(Pos.CENTER_LEFT);
        hdr.setPadding(new Insets(12, 16, 12, 16));
        hdr.setStyle("-fx-border-color: transparent transparent rgba(0,0,0,0.10) transparent;" +
            "-fx-border-width: 0 0 0.5 0;");

        GridPane stats = new GridPane();
        stats.setStyle("-fx-background-color: transparent;");

        addStat(stats, 0, 0, "Total assets",  "—",   "", false);
        addStat(stats, 1, 0, "Total cost",    "—",   "", false);
        addStat(stats, 0, 1, "Active",        "—",   "", false);
        addStat(stats, 1, 1, "Retired YTD",   "—",   "", false);

        ColumnConstraints sc = new ColumnConstraints();
        sc.setPercentWidth(50);
        stats.getColumnConstraints().addAll(sc, new ColumnConstraints() {{ setPercentWidth(50); }});

        VBox card = new VBox(0);
        card.getStyleClass().add("card");
        card.getChildren().addAll(hdr, stats);
        return card;
    }

    private void addStat(GridPane grid, int col, int row,
                          String label, String value, String footer, boolean up) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #888780; -fx-padding: 0 0 4 0;");
        Label val = new Label(value);
        val.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1A1A1A;");
        Label ft = new Label(footer);
        ft.setStyle("-fx-font-size: 10px; -fx-text-fill: #888780;");

        VBox cell = new VBox(0, lbl, val, ft);
        cell.setPadding(new Insets(12, 14, 12, 14));

        // Build border once — top right bottom left
        // Right border only on col 0; bottom border only on row 0
        String right  = (col == 0) ? "rgba(0,0,0,0.10)" : "transparent";
        String bottom = (row == 0) ? "rgba(0,0,0,0.10)" : "transparent";
        String rw     = (col == 0) ? "0.5" : "0";
        String bw     = (row == 0) ? "0.5" : "0";
        cell.setStyle(
            "-fx-border-color: transparent " + right + " " + bottom + " transparent;" +
            "-fx-border-width: 0 " + rw + " " + bw + " 0;");

        grid.add(cell, col, row);
    }

    // ── Card wrapper ───────────────────────────────────────────────

    private VBox wrapCard(Node... children) {
        VBox card = new VBox(0);
        card.getStyleClass().add("card");
        card.getChildren().addAll(children);
        return card;
    }

    // ═══════════════════════════════════════════════════════════════
    // Menu entries
    // ═══════════════════════════════════════════════════════════════

    private void buildEntries() {
        allEntries.clear();

        // FA Reporting
        allEntries.add(new MenuEntry("FA", "FATL12", "Projected Depreciation",
            "Project future depreciation", true, this::openProjectionScreen));
        allEntries.add(new MenuEntry("FA", "FATL10", "Asset Register",
            "Full listing with cost & WDV", true, this::openAssetRegister));
        allEntries.add(new MenuEntry("FA", "FATL02", "Transaction List",
            "Transactions for selected assets", true, this::openTransactionListScreen));
        allEntries.add(new MenuEntry("FA", "FATL03", "Acquired & Retired",
            "Assets in/out for a period", true, this::openAcquiredRetiredScreen));
        allEntries.add(MenuEntry.placeholder("FA", "FATL14",
            "Depreciation Report", "Period depreciation summary"));

        // FA Maintenance
        allEntries.add(new MenuEntry("FA-MAINT", "FAAS01", "Asset Maintenance",
            "Add, edit and maintain assets", true, this::openAssetMaintenance));
        allEntries.add(new MenuEntry("FA-MAINT", "FAAQ01", "Asset Acquisitions",
            "Enter new asset acquisition transactions", true, this::openAcquisitionEntry));
        allEntries.add(MenuEntry.placeholder("FA", "FAAS04",
            "Bulk Depn Change", "Mass depreciation parameter update"));

        // GL
        allEntries.add(MenuEntry.placeholder("GL", "GLTL01", "Trial Balance", ""));
        allEntries.add(MenuEntry.placeholder("GL", "GLTL02", "Profit & Loss", ""));
        allEntries.add(MenuEntry.placeholder("GL", "GLTL03", "Balance Sheet", ""));
        allEntries.add(MenuEntry.placeholder("GL", "GLMA01", "Chart of Accounts", ""));

        // AR
        allEntries.add(MenuEntry.placeholder("AR", "ARTL01", "Aged Debtors", ""));
        allEntries.add(MenuEntry.placeholder("AR", "ARTL02", "Debtor Statements", ""));
        allEntries.add(MenuEntry.placeholder("AR", "ARMA01", "Customer Master", ""));

        // AP
        allEntries.add(MenuEntry.placeholder("AP", "APTL01", "Aged Creditors", ""));
        allEntries.add(MenuEntry.placeholder("AP", "APMA01", "Supplier Master", ""));

        // Payroll
        allEntries.add(new MenuEntry("PY", "PACD01", "Pay Code Maintenance",
            "Manage income, deduction & allowance codes",
            true, this::openPayCodeMaintenance));
        allEntries.add(new MenuEntry("PY", "PAEM01", "Employee Maintenance",
            "Add, edit and maintain employee records",
            true, this::openEmployeeMaintenance));
        allEntries.add(new MenuEntry("PY", "PAPG01", "Pay Group Maintenance",
            "Define payroll groups and frequencies",
            true, this::openPayGroupMaintenance));
        allEntries.add(new MenuEntry("PY", "PASU04", "Tax Scale Maintenance",
            "Maintain PAYG / HECS / STSL tax scale config",
            true, this::openTaxScaleMaintenance));
        allEntries.add(new MenuEntry("PY", "PAAW01", "Award Maintenance",
            "Awards, job classes and worker's comp rates",
            true, this::openAwardMaintenance));
        allEntries.add(new MenuEntry("PY", "PATX01", "Load ATO Tax Scales",
            "Annual NAT_1004 / NAT_3539 update",
            true, this::openTaxScaleLoad));
        allEntries.add(new MenuEntry("PY", "PASU14", "Set Super Percentage",
            "Change super rate % on selected pay codes",
            true, this::openSetSuperPercentage));
        allEntries.add(new MenuEntry("PY", "PASU11", "Update Award Rate Changes",
            "Apply award rate changes to active employees",
            true, this::openUpdateAwardRateChanges));
        allEntries.add(new MenuEntry("PY", "PASU15", "Global Employee Award Update",
            "Move employees between awards globally",
            true, this::openGlobalEmployeeAwardUpdate));
        allEntries.add(new MenuEntry("PY", "PAEM60", "Change Employee Pay Rates",
            "Mass update employee pay rates",
            true, this::openChangeEmployeePayRates));
        allEntries.add(new MenuEntry("PY", "PAEM11", "Duplicate Default Timesheets",
            "Copy default timesheets to many employees",
            true, this::openDuplicateTimesheets));
        allEntries.add(new MenuEntry("PY", "PASU55", "Leave Accrual Reversal",
            "Reverse leave accruals for a payrun",
            true, this::openLeaveAccrualReversal));
        allEntries.add(new MenuEntry("PY", "PAPC01", "Timesheet Splits",
            "Maintain pay phase / phase group splits",
            true, this::openTimesheetSplits));
        allEntries.add(new MenuEntry("PY", "PATM01", "Timesheet Entry",
            "Enter employee timesheets for a payrun",
            true, this::openTimesheetEntry));

        // System maintenance programs
        allEntries.add(new MenuEntry("SYS", "MENU22", "Company Maintenance",
            "Add, change and delete company records",
            true, this::openCompanyMaintenance));

        // Restore favourites
        allEntries.forEach(e ->
            e.setFavourite(favouritesStore.isFavourite(e.getProgramCode())));
        allEntries.forEach(e ->
            e.favouriteProperty().addListener((obs, o, n) ->
                favouritesStore.setFavourite(e.getProgramCode(), n)));
    }

    /** Look up a MenuEntry by module + program code */
    private MenuEntry entry(String moduleCode, String programCode) {
        return allEntries.stream()
            .filter(e -> e.getModuleCode().equals(moduleCode)
                      && e.getProgramCode().equals(programCode))
            .findFirst()
            .orElse(MenuEntry.placeholder(moduleCode, programCode, programCode, ""));
    }

    // ═══════════════════════════════════════════════════════════════
    // Recently used tracking
    // ═══════════════════════════════════════════════════════════════

    private void trackRecent(MenuEntry e) {
        recentlyUsed.removeIf(r -> r.getProgramCode().equals(e.getProgramCode()));
        recentlyUsed.addFirst(e);
        if (recentlyUsed.size() > 5) recentlyUsed.removeLast();
        if (recentlyUsedBox != null) refreshRecentlyUsedRows(recentlyUsedBox);
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private String moduleLabel(String code) {
        return switch (code) {
            case "FA", "FA-MAINT" -> "Landmark Software";
            case "SYS" -> "System";
            case "GL" -> "General Ledger";
            case "AR" -> "Accounts Receivable";
            case "AP" -> "Accounts Payable";
            default   -> code;
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // Navigation
    // ═══════════════════════════════════════════════════════════════

    private void openAssetRegister() {
        Stage s = new Stage(); s.setScene(assetRegister.buildScene(s));
        s.setMinWidth(780); s.setMinHeight(600); s.show();
    }
    private void openAssetMaintenance() {
        Stage s = new Stage(); s.setTitle("Asset Maintenance — FAAS01");
        s.setScene(assetMaintenance.buildScene(s));
        s.setMinWidth(900); s.setMinHeight(550); s.show();
    }
    private void openAcquiredRetiredScreen() {
        Stage s = new Stage(); s.setTitle("Assets Acquired and Retired — FATL03");
        s.setScene(acquiredRetiredScreen.buildScene(s));
        s.setMinWidth(680); s.setMinHeight(680); s.show();
    }
    private void openTransactionListScreen() {
        Stage s = new Stage(); s.setTitle("Transaction List — FATL02");
        s.setScene(transactionListScreen.buildScene(s));
        s.setMinWidth(700); s.setMinHeight(680); s.show();
    }
    private void openProjectionScreen() {
        Stage s = new Stage(); s.setTitle("Projected Depreciation — FATL12");
        s.setScene(projectionScreen.buildScene(s));
        s.setMinWidth(700); s.setMinHeight(620); s.show();
    }
    private void openCompanyMaintenance() {
        companyMaintenance.show(
            javafx.stage.Window.getWindows().stream()
                .filter(javafx.stage.Window::isShowing)
                .findFirst().orElse(null));
    }

    private void openAcquisitionEntry() {
        Stage s = new Stage(); s.setTitle("Asset Acquisition Entry — FAAQ01");
        Scene scene = acquisitionEntry.buildScene(s, appSession.getCompanyNo());
        if (scene != null) {
            s.setScene(scene);
            s.setMinWidth(980); s.setMinHeight(620); s.show();
        }
    }

    private void openPayCodeMaintenance() {
        Stage s = new Stage(); s.setTitle("Pay Code Maintenance — PACD01");
        s.setScene(payCodeScreen.buildScene(s));
        s.setMinWidth(820); s.setMinHeight(480); s.show();
    }

    private void openEmployeeMaintenance() {
        Stage s = new Stage(); s.setTitle("Employee Maintenance — PAEM01");
        s.setScene(employeeScreen.buildScene(s));
        s.setMinWidth(960); s.setMinHeight(560); s.show();
    }

    private void openPayGroupMaintenance() {
        Stage s = new Stage(); s.setTitle("Pay Group Maintenance — PAPG01");
        s.setScene(payGroupScreen.buildScene(s));
        s.setMinWidth(900); s.setMinHeight(520); s.show();
    }

    private void openAwardMaintenance() {
        Stage s = new Stage(); s.setTitle("Award Maintenance — PAAW01");
        s.setScene(awardScreen.buildScene(s));
        s.setMinWidth(900); s.setMinHeight(520); s.show();
    }

    private void openTaxScaleMaintenance() {
        Stage s = new Stage(); s.setTitle("Tax Scale Maintenance — PASU04");
        s.setScene(taxScaleScreen.buildScene(s));
        s.setMinWidth(880); s.setMinHeight(520); s.show();
    }

    private void openTaxScaleLoad() {
        Stage s = new Stage(); s.setTitle("Load ATO Tax Scales — PATX01");
        s.setScene(taxScaleLoadScreen.buildScene(s));
        s.setMinWidth(600); s.setMinHeight(320); s.show();
    }

    private void openSetSuperPercentage() {
        Stage s = new Stage(); s.setTitle("Set Super Percentage — PASU14");
        s.setScene(setSuperPercentageScreen.buildScene(s));
        s.setMinWidth(600); s.setMinHeight(400); s.show();
    }

    private void openUpdateAwardRateChanges() {
        Stage s = new Stage(); s.setTitle("Update Award Rate Changes — PASU11");
        s.setScene(updateAwardRatesScreen.buildScene(s));
        s.setMinWidth(640); s.setMinHeight(380); s.show();
    }

    private void openGlobalEmployeeAwardUpdate() {
        Stage s = new Stage(); s.setTitle("Global Employee Award Update — PASU15");
        s.setScene(globalAwardUpdateScreen.buildScene(s));
        s.setMinWidth(720); s.setMinHeight(580); s.show();
    }

    private void openChangeEmployeePayRates() {
        Stage s = new Stage(); s.setTitle("Change Employee Pay Rates — PAEM60");
        s.setScene(changeEmpPayRatesScreen.buildScene(s));
        s.setMinWidth(760); s.setMinHeight(660); s.show();
    }

    private void openDuplicateTimesheets() {
        Stage s = new Stage(); s.setTitle("Duplicate Default Timesheets — PAEM11");
        s.setScene(dupTimesheetsScreen.buildScene(s));
        s.setMinWidth(680); s.setMinHeight(440); s.show();
    }

    private void openLeaveAccrualReversal() {
        Stage s = new Stage(); s.setTitle("Leave Accrual Reversal — PASU55");
        s.setScene(leaveAccrualScreen.buildScene(s));
        s.setMinWidth(840); s.setMinHeight(560); s.show();
    }

    private void openTimesheetSplits() {
        Stage s = new Stage(); s.setTitle("Timesheet Splits — PAPC01");
        s.setScene(timesheetSplitsScreen.buildScene(s));
        s.setMinWidth(920); s.setMinHeight(560); s.show();
    }

    private void openTimesheetEntry() {
        Stage s = new Stage(); s.setTitle("Timesheet Entry — PATM01");
        s.setScene(timesheetEntryScreen.buildScene(s));
        s.setMinWidth(1000); s.setMinHeight(620); s.show();
    }

    // ═══════════════════════════════════════════════════════════════
    // MENU23 — Company & Year Selection Dialog
    // ═══════════════════════════════════════════════════════════════

    private void showCompanyYearDialog(Window owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Change Company & Financial Year — MENU23");
        dlg.setResizable(false);

        // Header
        Label hdrLbl = new Label("Company & Financial Year");
        hdrLbl.setMaxWidth(Double.MAX_VALUE);
        hdrLbl.setStyle(
            "-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:white;" +
            "-fx-background-color:#1A6EF5;-fx-padding:10 16 10 16;");

        // Company combo — loaded from CPCOYCO
        ComboBox<CompanyItem> cboCompany = new ComboBox<>();
        cboCompany.setPrefWidth(320);
        cboCompany.setStyle("-fx-font-size:12px;");
        cboCompany.setConverter(new javafx.util.StringConverter<CompanyItem>() {
            public String toString(CompanyItem c) {
                return c == null ? "" : c.companyNo() + "  " + (c.displayName() != null ? c.displayName() : "");
            }
            public CompanyItem fromString(String s) { return null; }
        });
        try {
            var cos = companyRepo.findAll();
            System.out.println("MENU23: loaded " + cos.size() + " companies from CPCOYCO");
            cos.forEach(co ->
                cboCompany.getItems().add(new CompanyItem(co.getCompanyNo(), co.getName())));
        } catch (Exception e) {
            System.out.println("MENU23: company load failed: " + e.getMessage());
            cboCompany.getItems().add(new CompanyItem(sessionCompanyNo, sessionCompanyName));
        }
        cboCompany.getItems().stream()
            .filter(c -> c.companyNo() == sessionCompanyNo)
            .findFirst().ifPresent(cboCompany::setValue);
        if (cboCompany.getValue() == null && !cboCompany.getItems().isEmpty())
            cboCompany.setValue(cboCompany.getItems().get(0));

        // Company name 2 info label
        Label lblInfo = new Label();
        lblInfo.setStyle("-fx-font-size:11px;-fx-text-fill:#555553;-fx-padding:0 0 4 0;");
        lblInfo.setWrapText(true);

        // Year combo — loaded from GLDATES
        ComboBox<YearItem> cboYear = new ComboBox<>();
        cboYear.setPrefWidth(200);
        cboYear.setStyle("-fx-font-size:12px;");
        cboYear.setConverter(new javafx.util.StringConverter<YearItem>() {
            public String toString(YearItem y) { return y == null ? "" : y.desc(); }
            public YearItem fromString(String s) { return null; }
        });

        Runnable loadYears = () -> {
            cboYear.getItems().clear();
            CompanyItem sel = cboCompany.getValue();
            if (sel == null) { System.out.println("loadYears: no company selected"); return; }
            try {
                // Discover actual column names from INFORMATION_SCHEMA
                List<Map<String, Object>> schemaCols = jdbc.queryForList(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_NAME='GLDATES' AND TABLE_SCHEMA=DATABASE() " +
                    "ORDER BY ORDINAL_POSITION LIMIT 15");
                String allCols = schemaCols.stream()
                    .map(r -> r.get("COLUMN_NAME").toString().toLowerCase())
                    .collect(java.util.stream.Collectors.joining(", "));
                System.out.println("GLDATES columns: " + allCols);
                boolean hasYearNo = allCols.contains("year_no");
                boolean hasCoyNo  = allCols.contains("company_no");
                String yrCol      = hasYearNo ? "year_no" : "yr_no";
                System.out.println("Using col=" + yrCol + " hasCoyNo=" + hasCoyNo);

                List<Map<String, Object>> rows;
                if (hasCoyNo) {
                    rows = jdbc.queryForList(
                        "SELECT " + yrCol + " FROM GLDATES WHERE company_no=? ORDER BY " + yrCol + " DESC",
                        sel.companyNo());
                } else {
                    rows = jdbc.queryForList(
                        "SELECT " + yrCol + " FROM GLDATES ORDER BY " + yrCol + " DESC");
                }
                System.out.println("GLDATES year rows: " + rows);

                for (var row : rows) {
                    int yrVal = ((Number) row.get(yrCol)).intValue();
                    int yr2   = (yrVal > 100) ? yrVal % 100 : yrVal;
                    cboYear.getItems().add(new YearItem(yr2, buildYearDesc(yr2)));
                }
            } catch (Exception ex) {
                System.out.println("GLDATES load failed: " + ex.getMessage());
                cboYear.getItems().add(
                    new YearItem(sessionYearNo % 100, buildYearDesc(sessionYearNo % 100)));
            }
            if (cboYear.getItems().isEmpty()) {
                cboYear.getItems().add(
                    new YearItem(sessionYearNo % 100, buildYearDesc(sessionYearNo % 100)));
            }
            cboYear.getItems().stream()
                .filter(y -> y.twoDigit() == (sessionYearNo % 100))
                .findFirst().ifPresent(cboYear::setValue);
            if (cboYear.getValue() == null && !cboYear.getItems().isEmpty())
                cboYear.setValue(cboYear.getItems().get(0));
        };

        // Wire company change → reload years + update info label
        cboCompany.valueProperty().addListener((o, ov, nv) -> {
            loadYears.run();
            if (nv == null) { lblInfo.setText(""); return; }
            try {
                String name2 = jdbc.queryForObject(
                    "SELECT COALESCE(name2,'') FROM CPCOYCO WHERE company_no=?",
                    String.class, nv.companyNo());
                lblInfo.setText(name2 != null && !name2.isBlank() ? name2.trim() : "");
            } catch (Exception ex) { lblInfo.setText(""); }
        });
        loadYears.run();

        // Form grid
        GridPane form = new GridPane();
        form.setHgap(12); form.setVgap(10);
        form.setPadding(new Insets(20));
        form.add(dialogLbl("Company:"),        0, 0); form.add(cboCompany, 1, 0);
        form.add(lblInfo,                      1, 1);
        form.add(dialogLbl("Financial Year:"), 0, 2); form.add(cboYear, 1, 2);

        // Buttons
        Button btnOk     = new Button("✓  Set Company & Year");
        Button btnCancel = new Button("Cancel");
        btnOk.setDefaultButton(true);
        btnOk.setStyle(
            "-fx-background-color:#1A6EF5;-fx-text-fill:white;-fx-font-weight:bold;" +
            "-fx-background-radius:7;-fx-padding:7 18;-fx-cursor:hand;");
        btnCancel.setStyle(
            "-fx-background-color:white;-fx-border-color:#D0CFC8;" +
            "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:6 14;-fx-cursor:hand;");
        btnCancel.setOnAction(e -> dlg.close());

        btnOk.setOnAction(e -> {
            CompanyItem selCo = cboCompany.getValue();
            YearItem    selYr = cboYear.getValue();
            if (selCo == null) {
                new Alert(Alert.AlertType.WARNING, "Please select a company.", ButtonType.OK)
                    .showAndWait(); return;
            }
            if (selYr == null) {
                new Alert(Alert.AlertType.WARNING, "Please select a financial year.", ButtonType.OK)
                    .showAndWait(); return;
            }
            // Apply to session
            sessionCompanyNo   = selCo.companyNo();
            sessionCompanyName = selCo.displayName();
            int yr             = selYr.twoDigit();
            sessionYearNo      = yr < 40 ? 2000 + yr : 1900 + yr;
            sessionYearDesc    = selYr.desc();

            // Persist for next login
            if (lastSessionStore != null) {
                lastSessionStore.save(sessionCompanyNo, sessionYearNo);
            }

            // Update sidebar live
            lblFooterCompany.setText(sessionCompanyName);
            lblFooterYear.setText(sessionYearDesc);

            // Push to shared AppSession — all screens/services will now use this
            pushToAppSession();

            dlg.close();
        });

        HBox btnBar = new HBox(10, btnOk, btnCancel);
        btnBar.setPadding(new Insets(12, 20, 16, 20));
        btnBar.setAlignment(Pos.CENTER_RIGHT);
        btnBar.setStyle(
            "-fx-background-color:#F2F1EC;" +
            "-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
            "-fx-border-width:0.5 0 0 0;");

        VBox dialogRoot = new VBox(0, hdrLbl, form, btnBar);
        dialogRoot.setStyle("-fx-background-color:#F2F1EC;");
        dlg.setScene(new Scene(dialogRoot, 460, 240));
        dlg.showAndWait();
    }

    // ── Helpers ────────────────────────────────────────────────────

    private Label dialogLbl(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(120);
        return l;
    }

    // ── Value objects ──────────────────────────────────────────────
    private record YearItem(int twoDigit, String desc) {
        @Override public String toString() { return desc; }
    }

    // ── Session accessors (for other controllers to read) ──────────
    public int    getSessionCompanyNo()   { return sessionCompanyNo; }
    public String getSessionCompanyName() { return sessionCompanyName; }
    public int    getSessionYearNo()      { return sessionYearNo; }
}
