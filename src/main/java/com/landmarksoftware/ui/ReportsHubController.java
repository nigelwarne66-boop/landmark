package com.landmarksoftware.ui;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.report.JasperReportService;
import com.landmarksoftware.report.ModuleDef;
import com.landmarksoftware.report.ReportDef;
import com.landmarksoftware.report.ReportFavouritesStore;
import com.landmarksoftware.service.CpCntrlService;
import com.landmarksoftware.export.DepreciationPdfService;
import com.landmarksoftware.export.DepreciationExportService;
import com.landmarksoftware.export.AcquiredRetiredPdfService;
import com.landmarksoftware.export.AcquiredRetiredExportService;
import com.landmarksoftware.export.TransactionListPdfService;
import com.landmarksoftware.export.TransactionListExportService;
import com.landmarksoftware.export.EmployeePdfService;
import com.landmarksoftware.report.AssetRegisterViewerService;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.*;

@Component
public class ReportsHubController implements Initializable {

    /* ── FXML ──────────────────────────────────────────────────── */
    @FXML private Label     companyLabel;
    @FXML private Label     yearLabel;
    @FXML private Label     userLabel;
    @FXML private TextField searchField;
    @FXML private VBox      moduleList;
    @FXML private Label     moduleTitle;
    @FXML private Label     reportCount;
    @FXML private VBox      reportList;
    @FXML private Label     emptyLabel;

    /* ── Spring ────────────────────────────────────────────────── */
    @Autowired private AppSession                   session;
    @Autowired private ReportFavouritesStore        favStore;
    @Autowired private JasperReportService          jasper;
    @Autowired private ApplicationContext           springContext;
    @Autowired private CpCntrlService               cpCntrl;
    // Used only to spawn MENU23 (Switch Company) — same dialog code as the full app.
    @Autowired private MainMenuController           mainMenu;
    // Injected for future selection-screen wiring — runners below stub to
    // comingSoon() because actual service APIs don't take (AppSession).
    @Autowired private AssetRegisterViewerService   assetRegisterSvc;
    @Autowired private DepreciationPdfService       depreciationPdf;
    @Autowired private DepreciationExportService    depreciationXls;
    @Autowired private AcquiredRetiredPdfService    acquiredPdf;
    @Autowired private AcquiredRetiredExportService acquiredXls;
    @Autowired private TransactionListPdfService    txnListPdf;
    @Autowired private TransactionListExportService txnListXls;
    @Autowired private EmployeePdfService           employeePdf;

    /* ── State ─────────────────────────────────────────────────── */
    private List<ModuleDef> modules;
    private ModuleDef       activeModule;

    /* ── Colour + icon maps ────────────────────────────────────── */
    private static final Map<String, String> MODULE_STYLE = Map.of(
        "fa", "icon-fa", "gl", "icon-gl", "py", "icon-py",
        "ar", "icon-ar", "ap", "icon-ap", "cm", "icon-cm", "fav", "icon-fav"
    );
    // Feather (fth-*) — closest available substitute for the original Tabler ti-* names.
    private static final Map<String, String> MODULE_ICON = Map.of(
        "fa", "fth-package",       "gl", "fth-bar-chart-2",
        "py", "fth-users",         "ar", "fth-file-text",
        "ap", "fth-file",          "cm", "fth-dollar-sign",  "fav", "fth-star"
    );

    /* ── Init ──────────────────────────────────────────────────── */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        buildModuleRegistry();
        populateHeader();
        buildSidebar();
        selectModule(modules.get(0));
        searchField.textProperty().addListener((obs, old, val) -> filterReports(val));
    }

    private void populateHeader() {
        companyLabel.setText(session.getCompanyName());
        yearLabel.setText(session.getYearDesc());
        userLabel.setText(session.getUserId());
    }

    /* ── Module registry ───────────────────────────────────────── */
    private void buildModuleRegistry() {

        /* Fixed Assets */
        ReportDef assetRegister = ReportDef.withParams(
            "asset-register", "Asset Register",
            "Full asset listing by group and location",
            "fth-package");
        assetRegister.setRunner(fmt -> comingSoon("Asset Register"));

        ReportDef depreciation = ReportDef.withParams(
            "depreciation", "Depreciation",
            "Depreciation charges by period",
            "fth-trending-down");
        depreciation.setRunner(fmt -> comingSoon("Depreciation"));

        ReportDef acquiredRetired = ReportDef.withParams(
            "acquired-retired", "Acquired & Retired",
            "Assets acquired or retired in the year",
            "fth-repeat");
        acquiredRetired.setRunner(fmt -> comingSoon("Acquired & Retired"));

        ReportDef txnList = ReportDef.withParams(
            "transaction-list", "Transaction List",
            "All asset transactions for the year",
            "fth-list");
        txnList.setRunner(fmt -> comingSoon("Transaction List"));

        /* Payroll */
        ReportDef payrollSummary = ReportDef.withParams(
            "payroll-summary", "Payroll Summary",
            "Gross, tax, super and net by pay run",
            "fth-dollar-sign");
        payrollSummary.setRunner(fmt -> comingSoon("Payroll Summary"));

        ReportDef employeeList = ReportDef.withParams(
            "employee-list", "Employee List",
            "All staff with rate and current status",
            "fth-user");
        employeeList.setRunner(fmt -> comingSoon("Employee List"));

        /* General Ledger */
        ReportDef trialBalance = ReportDef.withParams(
            "trial-balance", "Trial Balance",
            "Account balances for a period range",
            "fth-bar-chart-2");
        trialBalance.setRunner(fmt -> comingSoon("Trial Balance"));

        ReportDef profitLoss = ReportDef.withParams(
            "profit-loss", "Profit & Loss",
            "Income vs expenses summary",
            "fth-trending-up");
        profitLoss.setRunner(fmt -> comingSoon("Profit & Loss"));

        ReportDef balanceSheet = ReportDef.withParams(
            "balance-sheet", "Balance Sheet",
            "Assets, liabilities and equity at a date",
            "fth-credit-card");
        balanceSheet.setRunner(fmt -> comingSoon("Balance Sheet"));

        ReportDef generalJournal = ReportDef.withParams(
            "general-journal", "General Journal",
            "All posted journal entries",
            "fth-book");
        generalJournal.setRunner(fmt -> comingSoon("General Journal"));

        ReportDef acctTxns = ReportDef.withParams(
            "account-transactions", "Account Transactions",
            "Drilldown transactions for one account",
            "fth-file-text");
        acctTxns.setRunner(fmt -> comingSoon("Account Transactions"));

        /* Accounts Receivable */
        ReportDef debtorsAgeing = ReportDef.withParams(
            "debtors-ageing", "Debtors Ageing",
            "Customer balances aged across 6 monthly buckets",
            "fth-users");
        debtorsAgeing.setRunner(fmt -> comingSoon("Debtors Ageing"));

        /* Accounts Payable */
        ReportDef creditorsAgeing = ReportDef.withParams(
            "creditors-ageing", "Creditors Ageing",
            "Supplier balances aged across 6 monthly buckets",
            "fth-users");
        creditorsAgeing.setRunner(fmt -> comingSoon("Creditors Ageing"));

        modules = List.of(
            new ModuleDef("fa", "Fixed Assets",
                List.of(assetRegister, depreciation, acquiredRetired, txnList)),
            new ModuleDef("py", "Payroll",
                List.of(payrollSummary, employeeList)),
            new ModuleDef("gl", "General Ledger",
                List.of(trialBalance, profitLoss, balanceSheet, generalJournal, acctTxns)),
            new ModuleDef("ar", "Accounts Receivable",
                List.of(debtorsAgeing)),
            new ModuleDef("ap", "Accounts Payable",
                List.of(creditorsAgeing))
        );
    }

    /* ── Sidebar ────────────────────────────────────────────────── */
    private void buildSidebar() {
        moduleList.getChildren().clear();
        moduleList.getChildren().add(buildModuleRow(null)); // Favourites
        for (ModuleDef mod : modules) {
            moduleList.getChildren().add(buildModuleRow(mod));
        }
    }

    private HBox buildModuleRow(ModuleDef mod) {
        boolean isFav = (mod == null);
        String  id    = isFav ? "fav" : mod.getId();

        HBox row = new HBox(8);
        row.getStyleClass().add("module-item");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setUserData(id);

        StackPane iconBadge = new StackPane();
        iconBadge.getStyleClass().addAll("module-icon-badge",
            MODULE_STYLE.getOrDefault(id, "icon-fa"));
        FontIcon icon = new FontIcon(MODULE_ICON.getOrDefault(id, "fth-file"));
        icon.getStyleClass().add("module-badge-icon");
        iconBadge.getChildren().add(icon);

        Label lbl = new Label(isFav ? "Favourites" : mod.getLabel());
        lbl.getStyleClass().add("module-item-label");
        HBox.setHgrow(lbl, Priority.ALWAYS);

        Label countLbl = new Label(
            isFav ? String.valueOf(favStore.count()) : String.valueOf(mod.getReportCount()));
        countLbl.getStyleClass().add(isFav ? "module-badge-fav" : "module-badge-count");

        row.getChildren().addAll(iconBadge, lbl, countLbl);
        row.setOnMouseClicked(e -> selectModule(mod));
        return row;
    }

    /* ── Module selection ───────────────────────────────────────── */
    private void selectModule(ModuleDef mod) {
        activeModule = mod;
        searchField.clear();
        String activeId = (mod == null) ? "fav" : mod.getId();

        moduleList.getChildren().forEach(n ->
            n.getStyleClass().removeAll("module-item-active", "module-item-active-fav"));

        moduleList.getChildren().stream()
            .filter(n -> activeId.equals(n.getUserData()))
            .findFirst()
            .ifPresent(n -> n.getStyleClass().add(
                "fav".equals(activeId) ? "module-item-active-fav" : "module-item-active"));

        if (mod == null) {
            moduleTitle.setText("Favourites");
            renderFavourites();
        } else {
            moduleTitle.setText(mod.getLabel());
            buildReportRows(mod.getReports(), mod.getId());
        }
    }

    /* ── Report rows ────────────────────────────────────────────── */
    private void buildReportRows(List<ReportDef> reports, String moduleId) {
        reportList.getChildren().clear();
        if (emptyLabel != null) emptyLabel.setVisible(false);
        reports.forEach(r -> reportList.getChildren().add(buildReportCard(r, moduleId)));
        reportCount.setText(reports.size() + " reports");
    }

    private void renderFavourites() {
        reportList.getChildren().clear();
        boolean any = false;
        for (ModuleDef mod : modules) {
            for (ReportDef r : mod.getReports()) {
                if (favStore.isFavourite(mod.getId() + ":" + r.getName())) {
                    reportList.getChildren().add(buildReportCard(r, mod.getId()));
                    any = true;
                }
            }
        }
        reportCount.setText(favStore.count() + " reports");
        if (emptyLabel != null) emptyLabel.setVisible(!any);
    }

    /* ── Build one card — icon + name/hint + star, NO format buttons ── */
    private HBox buildReportCard(ReportDef report, String moduleId) {
        String favKey = moduleId + ":" + report.getName();

        HBox card = new HBox(12);
        card.getStyleClass().add("report-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setUserData(report.getLabel().toLowerCase()); // for search

        /* Coloured icon badge */
        StackPane iconBadge = new StackPane();
        iconBadge.getStyleClass().addAll("report-icon-badge",
            MODULE_STYLE.getOrDefault(moduleId, "icon-fa"));
        FontIcon icon = new FontIcon(report.getIconLiteral());
        icon.getStyleClass().add("report-badge-icon");
        iconBadge.getChildren().add(icon);

        /* Name + hint — fills all available width */
        VBox body = new VBox(3);
        HBox.setHgrow(body, Priority.ALWAYS);
        Label name = new Label(report.getLabel());
        name.getStyleClass().add("report-name");
        Label hint = new Label(report.getDescription());
        hint.getStyleClass().add("report-hint");
        body.getChildren().addAll(name, hint);

        /* Favourite star — right-aligned, no format buttons */
        Label star = new Label(favStore.isFavourite(favKey) ? "★" : "☆");
        star.getStyleClass().addAll("fav-star",
            favStore.isFavourite(favKey) ? "fav-star-active" : "");
        star.setOnMouseClicked(e -> {
            e.consume(); // don't trigger card click
            favStore.toggle(favKey);
            boolean nowFav = favStore.isFavourite(favKey);
            star.setText(nowFav ? "★" : "☆");
            if (nowFav) star.getStyleClass().add("fav-star-active");
            else        star.getStyleClass().remove("fav-star-active");
            refreshFavBadge();
            if (activeModule == null) renderFavourites();
        });

        /* Card click → open selection screen */
        card.setOnMouseClicked(e -> openSelectionScreen(report, moduleId));
        card.getStyleClass().add("report-card-clickable");

        card.getChildren().addAll(iconBadge, body, star);
        return card;
    }

    /* ── Open the report's selection/params screen ────────────────
     *
     * Tries to load /fxml/reports/{moduleId}/{report.getName()}.fxml.
     * If the FXML doesn't exist yet (i.e. report not built in any wave),
     * falls back to the "Coming soon" alert so future-wave cards keep
     * their current behaviour automatically.
     */
    private void openSelectionScreen(ReportDef report, String moduleId) {
        String fxmlPath = "/fxml/reports/" + moduleId + "/" + report.getName() + ".fxml";
        java.net.URL fxmlUrl = getClass().getResource(fxmlPath);
        if (fxmlUrl == null) {
            comingSoon(report.getLabel());
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();
            Stage dialog = new Stage();
            dialog.initOwner(reportList.getScene().getWindow());
            dialog.initModality(Modality.WINDOW_MODAL);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(
                getClass().getResource("/css/fixedassets.css").toExternalForm());
            scene.getStylesheets().add(
                getClass().getResource("/css/reporting.css").toExternalForm());
            dialog.setScene(scene);
            dialog.setTitle(report.getLabel());
            dialog.setResizable(false);
            dialog.show();
        } catch (Exception ex) {
            showError("Could not open report", ex.getMessage());
        }
    }

    /* ── Favourites badge refresh ───────────────────────────────── */
    private void refreshFavBadge() {
        moduleList.getChildren().stream()
            .filter(n -> "fav".equals(n.getUserData()))
            .findFirst()
            .ifPresent(n -> ((HBox) n).getChildren().stream()
                .filter(c -> c instanceof Label &&
                    ((Label) c).getStyleClass().contains("module-badge-fav"))
                .map(c -> (Label) c)
                .findFirst()
                .ifPresent(l -> l.setText(String.valueOf(favStore.count()))));
    }

    /* ── Search ─────────────────────────────────────────────────── */
    private void filterReports(String query) {
        if (activeModule == null) return;
        String q = query == null ? "" : query.trim().toLowerCase();
        reportList.getChildren().forEach(node -> {
            String key = (String) node.getUserData();
            boolean show = q.isEmpty() || (key != null && key.contains(q));
            node.setVisible(show);
            node.setManaged(show);
        });
    }

    /* ── Navigation ─────────────────────────────────────────────── */
    @FXML
    private void onSwitchCompany() {
        javafx.stage.Window owner = companyLabel.getScene().getWindow();
        mainMenu.showCompanyYearDialog(owner);
        // AppSession is now updated. Refresh the top-bar labels so the
        // user sees the new company/year selection immediately.
        populateHeader();
    }

    @FXML private void onSignOut() { javafx.application.Platform.exit(); }

    /* ── Jasper bridge — every selection screen controller calls this ──
     *
     * runJasperReport pulls the standard params from AppSession, merges in
     * any per-report extras, runs the export off the FX thread, then prompts
     * the user to save + opens the file with the system default viewer.
     */
    public void runJasperReport(String reportPath, Map<String, Object> extraParams,
                                  String format, javafx.stage.Window owner) {
        Map<String, Object> params = buildStandardParams();
        params.putAll(extraParams);

        new Thread(() -> {
            try {
                byte[] data;
                String ext;
                if ("pdf".equals(format)) {
                    data = jasper.exportPdf(reportPath, params);
                    ext = ".pdf";
                } else {
                    data = jasper.exportExcel(reportPath, params);
                    ext = ".xlsx";
                }
                final byte[] finalData = data;
                final String finalExt = ext;
                javafx.application.Platform.runLater(() ->
                    saveOrOpen(finalData, reportPath, finalExt, owner));
            } catch (Exception ex) {
                javafx.application.Platform.runLater(() ->
                    showError("Report failed", ex.getMessage()));
            }
        }, "jasper-" + reportPath).start();
    }

    /**
     * Variant of runJasperReport for reports whose query is too dynamic
     * for a static .jrxml SQL block — the caller pre-fetches rows and
     * passes a JRDataSource (typically a JRBeanCollectionDataSource).
     * Used by AR / AP ageing.
     */
    public void runJasperReportWithDataSource(String reportPath,
                                                Map<String, Object> extraParams,
                                                net.sf.jasperreports.engine.JRDataSource dataSource,
                                                String format,
                                                javafx.stage.Window owner) {
        Map<String, Object> params = buildStandardParams();
        params.putAll(extraParams);

        new Thread(() -> {
            try {
                byte[] data;
                String ext;
                if ("pdf".equals(format)) {
                    data = jasper.exportPdfFromDataSource(reportPath, params, dataSource);
                    ext = ".pdf";
                } else {
                    data = jasper.exportExcelFromDataSource(reportPath, params, dataSource);
                    ext = ".xlsx";
                }
                final byte[] finalData = data;
                final String finalExt = ext;
                javafx.application.Platform.runLater(() ->
                    saveOrOpen(finalData, reportPath, finalExt, owner));
            } catch (Exception ex) {
                javafx.application.Platform.runLater(() ->
                    showError("Report failed", ex.getMessage()));
            }
        }, "jasper-" + reportPath).start();
    }

    private Map<String, Object> buildStandardParams() {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("COMPANY_NO",    session.getCompanyNo());
        p.put("YEAR_NO",       session.getYearNo());
        p.put("COMPANY_NAME",  session.getCompanyName());
        p.put("YEAR_DESC",     session.getYearDesc());
        p.put("YR_START_DATE", session.getYrStartDate() != null
            ? java.sql.Date.valueOf(session.getYrStartDate()) : null);
        p.put("YR_END_DATE",   session.getYrEndDate() != null
            ? java.sql.Date.valueOf(session.getYrEndDate()) : null);
        p.put("USER_ID",       session.getUserId());
        return p;
    }

    /**
     * Save the report to CPCNTRL.local_pc_dir using a derived filename, then
     * open it with the system default viewer. Falls back to a FileChooser if
     * the configured directory is missing, blank, or unwritable.
     *
     * <p>The {@code reportPath} ("fa/asset-register") is converted to a
     * filesystem-friendly slug ("fa_asset-register") and stamped with the
     * current timestamp so repeat runs don't overwrite earlier files.
     */
    private void saveOrOpen(byte[] data, String reportPath, String ext, javafx.stage.Window owner) {
        String slug = reportPath.replace('/', '_').replace('\\', '_');
        String stamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = slug + "_" + stamp + ext;

        java.io.File file = resolveOutputFile(filename, ext, owner);
        if (file == null) return;  // user cancelled the fallback chooser

        // Step 1 — write to disk.
        try {
            java.nio.file.Files.createDirectories(file.toPath().getParent());
            java.nio.file.Files.write(file.toPath(), data);
        } catch (Exception ex) {
            ex.printStackTrace();
            showError("Could not save file", ex.toString());
            return;
        }

        // Step 2 — open with system viewer.
        // Use OS-native commands instead of java.awt.Desktop — JavaFX
        // apps on Windows often report Desktop as unsupported because
        // AWT was never initialised, so Desktop.open silently no-ops.
        if (!openWithOsViewer(file)) {
            infoSaved(file);
        }
    }

    /**
     * Open the file with the OS's default viewer. Returns true on success.
     *
     * <p>Windows: {@code cmd /c start "" "<path>"} — the empty title arg is
     * what {@code start} expects when the path itself is quoted.
     * <br>Mac: {@code open <path>}.
     * <br>Linux/other: {@code xdg-open <path>}.
     */
    private boolean openWithOsViewer(java.io.File file) {
        String os = System.getProperty("os.name", "").toLowerCase();
        java.util.List<String> cmd;
        if (os.contains("win")) {
            cmd = java.util.List.of("cmd", "/c", "start", "", file.getAbsolutePath());
        } else if (os.contains("mac") || os.contains("darwin")) {
            cmd = java.util.List.of("open", file.getAbsolutePath());
        } else {
            cmd = java.util.List.of("xdg-open", file.getAbsolutePath());
        }
        try {
            new ProcessBuilder(cmd).inheritIO().start();
            return true;
        } catch (Exception ex) {
            System.err.println("Could not open " + file + " via " + cmd.get(0) + ": " + ex);
            return false;
        }
    }

    /**
     * Return the target File. Prefer CPCNTRL.local_pc_dir; if that's missing
     * or unwritable, fall back to a FileChooser so the user still gets the file.
     */
    private java.io.File resolveOutputFile(String filename, String ext, javafx.stage.Window owner) {
        String dir = cpCntrl.getLocalPcDir(session.getCompanyNo());
        if (dir != null && !dir.isBlank()) {
            java.io.File f = new java.io.File(dir, filename);
            try {
                java.nio.file.Files.createDirectories(f.toPath().getParent());
                return f;
            } catch (Exception ex) {
                System.err.println("local_pc_dir not writable (" + dir
                    + ") — falling back to chooser: " + ex);
            }
        }
        // Fallback — let the user pick.
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Save Report");
        fc.setInitialFileName(filename);
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
            ext.equals(".pdf") ? "PDF" : "Excel", "*" + ext));
        java.io.File chosen = fc.showSaveDialog(owner);
        if (chosen == null) return null;
        return chosen.getName().toLowerCase().endsWith(ext)
            ? chosen
            : new java.io.File(chosen.getParentFile(), chosen.getName() + ext);
    }

    private void infoSaved(java.io.File file) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Saved");
        a.setHeaderText("Report saved");
        a.setContentText("Saved to:\n" + file.getAbsolutePath()
            + "\n\n(Couldn't auto-open — open it manually from there.)");
        a.showAndWait();
    }

    private void showError(String title, String message) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle(title);
        a.setHeaderText(title);
        a.setContentText(message);
        a.showAndWait();
    }

    /* ── Helpers ─────────────────────────────────────────────────── */
    private void comingSoon(String name) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Coming soon");
        a.setHeaderText(name);
        a.setContentText("Selection screen for this report is being developed.");
        a.showAndWait();
    }
}
