package com.landmarksoftware.ui;

import com.landmarksoftware.model.AssetRegisterParams;
import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.CodeLookupService;
import com.landmarksoftware.model.AssetRegisterRow;
import com.landmarksoftware.model.CompanyRow;
import com.landmarksoftware.service.AssetRegisterService;
import com.landmarksoftware.repository.CompanyRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FATL10 — Fixed Asset Register
 *
 * Two-stage UI:
 *   1. Parameter screen (mirrors FATL10S0 — all 17 fields)
 *   2. Results grid (with group/location totals and grand total)
 *      plus toolbar: Print | Asset Register | Export CSV | Close
 */
@Component
public class AssetRegisterController {

    private final AssetRegisterService  service;
    private final CompanyRepository     companyRepo;
    private final CodeLookupService     lookupService;
    private final JdbcTemplate          jdbc;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final AppSession              appSession;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fatl10-thread");
        t.setDaemon(true);
        return t;
    });

    // Parameter fields
    private TextField   fStartAsset, fEndAsset;
    private TextField   fLocn, fDept, fGroup, fSubGroup;
    private Label       lLocnDesc, lDeptDesc, lGroupDesc, lSubGroupDesc;
    private CheckBox    cbUnposted, cbActive, cbOnHold, cbInactive, cbRetired;
    private ToggleGroup tgLeased, tgBookTax, tgCost;
    private DatePicker  dpAsAt, dpStart;
    private CheckBox    cbActivity;
    private ComboBox<CompanyItem> cboCompany;
    private Label       lblStatus;
    private int         companyNo;

    public AssetRegisterController(AssetRegisterService service,
                                    CompanyRepository companyRepo,
                                    CodeLookupService lookupService,
                            JdbcTemplate jdbc,
                               AppSession appSession) {
        this.service     = service;
        this.companyRepo = companyRepo;
        this.lookupService = lookupService;
        this.jdbc        = jdbc;
        this.appSession = appSession;
    }

    // ── Entry point ────────────────────────────────────────────────────

    public Scene buildScene(Stage stage) {
        stage.setTitle("FATL10 — Fixed Asset Register");

        // Company selector at top
        cboCompany = new ComboBox<>();
        loadCompanies();
        cboCompany.setPrefWidth(300);
        cboCompany.setOnAction(e -> {
            if (cboCompany.getValue() != null)
                companyNo = cboCompany.getValue().companyNo();
        });
        // Pre-select session company from AppSession (GLPASS equivalent)
        cboCompany.getItems().stream()
            .filter(c -> c.companyNo() == appSession.getCompanyNo())
            .findFirst()
            .ifPresentOrElse(c -> {
                cboCompany.setValue(c);
                companyNo = c.companyNo();
            }, () -> {
                if (!cboCompany.getItems().isEmpty()) {
                    cboCompany.setValue(cboCompany.getItems().get(0));
                    companyNo = cboCompany.getItems().get(0).companyNo();
                }
            });

        HBox companyBar = new HBox(10,
            styledLabel("Company:"), cboCompany);
        companyBar.setAlignment(Pos.CENTER_LEFT);
        companyBar.setPadding(new Insets(10, 16, 10, 16));
        companyBar.setStyle("-fx-background-color: white;" +
            "-fx-border-color: #E0E6ED; -fx-border-width: 0 0 1 0;");

        ScrollPane paramScroll = new ScrollPane(buildParamForm());
        paramScroll.setFitToWidth(true);

        lblStatus = new Label("Enter parameters and click Run Report");
        lblStatus.setStyle("-fx-font-size: 12px; -fx-text-fill: #6B7280;");

        Button btnRun   = btn("▶  Run Report",  "#0061FF", "#FFFFFF");
        Button btnReset = btn("Reset",           "#FFFFFF", "#374151", "#E0E6ED");
        Button btnClose = btn("Close",           "#FFFFFF", "#DC2626", "#DC2626");

        btnRun.setOnAction(e -> runReport(stage));
        btnReset.setOnAction(e -> resetParams());
        btnClose.setOnAction(e -> stage.close());

        HBox footer = new HBox(8, btnRun, btnReset, lblStatus);
        HBox.setHgrow(lblStatus, Priority.ALWAYS);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(10, 16, 10, 16));
        footer.setStyle("-fx-background-color: #F8FAFC;" +
            "-fx-border-color: #E0E6ED; -fx-border-width: 1 0 0 0;");
        HBox closeBar = new HBox(btnClose);
        closeBar.setAlignment(Pos.CENTER_RIGHT);
        closeBar.setPadding(new Insets(0, 16, 10, 0));

        VBox root = new VBox(
            buildHeader("Fixed Asset Register", "FATL10"),
            companyBar, paramScroll,
            footer, closeBar);
        VBox.setVgrow(paramScroll, Priority.ALWAYS);

        Scene scene = new Scene(root, 760, 680);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    // ── Parameter form ─────────────────────────────────────────────────

    private GridPane buildParamForm() {
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8);
        grid.setPadding(new Insets(16));

        int row = 0;

        // Section: Print selections
        addSectionHeader(grid, row++, "Print selections");

        // Asset range
        fStartAsset = tf("", 20); fEndAsset = tf("", 20);
        addRow(grid, row++, "Starting asset no", fStartAsset);
        addRow(grid, row++, "Ending asset no",   fEndAsset);

        fStartAsset.focusedProperty().addListener((obs, old, focused) -> {
            if (!focused && fEndAsset.getText().isBlank())
                fEndAsset.setText(fStartAsset.getText());
        });

        // Filters with lookup descriptions
        fLocn = tf("", 6); lLocnDesc = displayLabel("ALL LOCATIONS");
        fDept = tf("", 6); lDeptDesc = displayLabel("ALL DEPARTMENTS");
        fGroup = tf("", 6); lGroupDesc = displayLabel("ALL GROUPS");
        fSubGroup = tf("", 6); lSubGroupDesc = displayLabel("ALL SUB-GROUPS");

        fLocn.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) updateLocnDesc();
        });
        fDept.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) updateDeptDesc();
        });
        fGroup.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) updateGroupDesc();
        });
        fSubGroup.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) updateSubGroupDesc();
        });

        addRow(grid, row++, "Location",  buildLookupRow(fLocn, lLocnDesc,  LookupDialog.LookupType.LOCATION));
        addRow(grid, row++, "Department",buildLookupRow(fDept, lDeptDesc,   LookupDialog.LookupType.DEPARTMENT));
        addRow(grid, row++, "Group",     buildLookupRow(fGroup, lGroupDesc,  LookupDialog.LookupType.GROUP));
        addRow(grid, row++, "Sub-group", buildLookupRow(fSubGroup, lSubGroupDesc, LookupDialog.LookupType.SUBGROUP));

        // Asset status checkboxes
        addSectionHeader(grid, row++, "Print assets with status");
        cbUnposted = new CheckBox("Unposted");  cbUnposted.setSelected(true);
        cbActive   = new CheckBox("Active");    cbActive.setSelected(true);
        cbOnHold   = new CheckBox("On Hold");   cbOnHold.setSelected(true);
        cbInactive = new CheckBox("Inactive");  cbInactive.setSelected(true);
        cbRetired  = new CheckBox("Retired");   cbRetired.setSelected(true);
        HBox statusBoxes = new HBox(12, cbUnposted, cbActive, cbOnHold, cbInactive, cbRetired);
        grid.add(statusBoxes, 1, row++);

        // Leased indicator
        addSectionHeader(grid, row++, "Additional selections");
        tgLeased = new ToggleGroup();
        RadioButton rdoAll = radio("All assets", tgLeased, "A", true);
        RadioButton rdoLeased = radio("Leased only", tgLeased, "L", false);
        RadioButton rdoNonLeased = radio("Non-leased only", tgLeased, "N", false);
        addRow(grid, row++, "Leased / non-leased", new HBox(10, rdoAll, rdoLeased, rdoNonLeased));

        // Book/Tax
        tgBookTax = new ToggleGroup();
        RadioButton rdoBook = radio("Book depreciation", tgBookTax, "B", true);
        RadioButton rdoTax  = radio("Tax depreciation",  tgBookTax, "T", false);
        addRow(grid, row++, "Depreciation type", new HBox(10, rdoBook, rdoTax));

        // Cost indicator
        tgCost = new ToggleGroup();
        RadioButton rdoActual   = radio("Actual cost",   tgCost, "A", true);
        RadioButton rdoRevalued = radio("Revalued cost", tgCost, "R", false);
        addRow(grid, row++, "Cost basis", new HBox(10, rdoActual, rdoRevalued));

        // Dates
        addSectionHeader(grid, row++, "Date range");
        dpAsAt = new DatePicker(LocalDate.now());
        dpStart = new DatePicker(LocalDate.of(1900, 1, 1));
        addRow(grid, row++, "Accumulated depn as at",       dpAsAt);
        addRow(grid, row++, "Exclude if retired prior to",  dpStart);

        cbActivity = new CheckBox("Only include if activity between above dates");
        grid.add(cbActivity, 1, row++);

        return grid;
    }

    // ── Run report ─────────────────────────────────────────────────────

    private void runReport(Stage ownerStage) {
        AssetRegisterParams p = gatherParams();
        String err = p.validate();
        if (err != null) {
            showError(err, ownerStage);
            return;
        }

        lblStatus.setText("Running\u2026");
        lblStatus.setStyle("-fx-text-fill: #0061FF; -fx-font-weight: bold;");

        executor.submit(() -> {
            try {
                List<AssetRegisterRow> rows = service.runReport(companyNo, p);
                Platform.runLater(() -> {
                    lblStatus.setText("Done — " + rows.size() + " assets");
                    lblStatus.setStyle("-fx-text-fill: #059669; -fx-font-weight: bold;");
                    showResultsWindow(ownerStage, p, rows);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    lblStatus.setText("Error: " + ex.getMessage());
                    lblStatus.setStyle("-fx-text-fill: #DC2626;");
                    ex.printStackTrace();
                });
            }
        });
    }

    private AssetRegisterParams gatherParams() {
        AssetRegisterParams p = new AssetRegisterParams();
        p.setStartAssetNo(fStartAsset.getText());
        p.setEndAssetNo(fEndAsset.getText());
        p.setPrintLocn(fLocn.getText().toUpperCase().trim());
        p.setPrintDept(fDept.getText().toUpperCase().trim());
        p.setPrintGroup(fGroup.getText().toUpperCase().trim());
        p.setPrintSubGroup(fSubGroup.getText().toUpperCase().trim());
        p.setIncludeUnposted(cbUnposted.isSelected());
        p.setIncludeActive(cbActive.isSelected());
        p.setIncludeOnHold(cbOnHold.isSelected());
        p.setIncludeInactive(cbInactive.isSelected());
        p.setIncludeRetired(cbRetired.isSelected());
        RadioButton selLeased  = (RadioButton) tgLeased.getSelectedToggle();
        RadioButton selBT      = (RadioButton) tgBookTax.getSelectedToggle();
        RadioButton selCost    = (RadioButton) tgCost.getSelectedToggle();
        if (selLeased != null) p.setLeasedInd((String) selLeased.getUserData());
        if (selBT     != null) p.setBookTaxInd((String) selBT.getUserData());
        if (selCost   != null) p.setCostInd((String) selCost.getUserData());
        if (dpAsAt.getValue()  != null) p.setAsAtDate(dpAsAt.getValue());
        if (dpStart.getValue() != null) p.setStartDate(dpStart.getValue());
        p.setIncludeIfActivity(cbActivity.isSelected());

        // Store descriptions
        p.setLocnDesc(lLocnDesc.getText());
        p.setDeptDesc(lDeptDesc.getText());
        p.setGroupDesc(lGroupDesc.getText());
        p.setSubGroupDesc(lSubGroupDesc.getText());
        return p;
    }

    // ── Results window ─────────────────────────────────────────────────

    private void showResultsWindow(Stage owner, AssetRegisterParams p,
                                    List<AssetRegisterRow> rows) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.setTitle("FATL10 — Asset Register Results  (" + rows.size() + " assets)");

        // Build table
        TableView<AssetRegisterRow> table = buildResultsTable();
        ObservableList<AssetRegisterRow> items = FXCollections.observableArrayList(rows);
        table.setItems(items);
        VBox.setVgrow(table, Priority.ALWAYS);

        // Summary totals at bottom
        BigDecimal totalCost  = rows.stream().map(AssetRegisterRow::getCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDepn  = rows.stream().map(AssetRegisterRow::getAccumDepn)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalWdv   = rows.stream().map(AssetRegisterRow::getWdv)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        String btLabel = "B".equals(p.getBookTaxInd()) ? "Book" : "Tax";
        Label lblTotals = new Label(String.format(
            "%d assets   |   %s cost: %,.2f   |   %s accum depn: %,.2f   |   WDV: %,.2f",
            rows.size(), btLabel, totalCost, btLabel, totalDepn, totalWdv));
        lblTotals.setStyle("-fx-font-size: 12px; -fx-text-fill: #1A1F36; -fx-font-weight: bold;");

        // Toolbar
        Button btnClose  = btn("Close",         "#FFFFFF", "#374151", "#E0E6ED");
        Button btnExport = btn("Export CSV",     "#059669", "#FFFFFF");
        btnClose.setOnAction(e -> dlg.close());
        btnExport.setOnAction(e -> exportCsv(rows, p, dlg));

        HBox toolbar = new HBox(8, btnExport, new Separator(javafx.geometry.Orientation.VERTICAL), btnClose);
        toolbar.setAlignment(Pos.CENTER_RIGHT);
        toolbar.setPadding(new Insets(8, 16, 8, 16));
        toolbar.setStyle("-fx-background-color: #F8FAFC;" +
            "-fx-border-color: #E0E6ED; -fx-border-width: 1 0 0 0;");

        HBox totalsBar = new HBox(lblTotals);
        totalsBar.setPadding(new Insets(8, 16, 8, 16));
        totalsBar.setStyle("-fx-background-color: #EFF6FF;" +
            "-fx-border-color: #BFDBFE; -fx-border-width: 1;");

        // Param summary header
        Label paramSummary = new Label(buildParamSummary(p));
        paramSummary.setStyle("-fx-font-size: 11px; -fx-text-fill: #6B7280;" +
            "-fx-padding: 6 16 6 16;");
        paramSummary.setWrapText(true);

        VBox root = new VBox(
            buildHeader("Fixed Asset Register", "FATL10"),
            paramSummary, totalsBar, table, toolbar);

        dlg.setScene(new Scene(root, 1100, 620));
        dlg.getScene().getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        dlg.show();
    }

    @SuppressWarnings("unchecked")
    private TableView<AssetRegisterRow> buildResultsTable() {
        TableView<AssetRegisterRow> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        table.getColumns().addAll(
            col("Asset No",    80, r -> r.getAssetNo()),
            col("Description", 180, r -> r.getDesc1() +
                (r.getDesc2().isBlank() ? "" : " / " + r.getDesc2())),
            col("Loc",  50, r -> r.getLocCode()),
            col("Dept", 50, r -> r.getDeptCode()),
            col("Grp",  50, r -> r.getGrpCode()),
            col("SbGp", 50, r -> r.getSubgrpCode()),
            col("Acqn Date", 80, r -> r.getAcqnDate() != null
                ? r.getAcqnDate().format(DATE_FMT) : ""),
            colDec("Cost",       100, AssetRegisterRow::getCost),
            colDec("Accum Depn", 100, AssetRegisterRow::getAccumDepn),
            colDec("WDV",        100, AssetRegisterRow::getWdv),
            col("M", 30, r -> r.getDepnMethod()),
            col("Code", 55, r -> r.getDepnCode()),
            col("Rate",  55, r -> r.getDepnRate().compareTo(BigDecimal.ZERO) == 0
                ? "" : r.getDepnRate().toPlainString()),
            col("Freq",  40, r -> r.getDepnFreq() == 0
                ? "" : String.valueOf(r.getDepnFreq())),
            col("Start Depn", 80, r -> r.getStartDepnDate() != null
                ? r.getStartDepnDate().format(DATE_FMT) : ""),
            col("Last Depn",  80, r -> r.getLastDepnDate() != null
                ? r.getLastDepnDate().format(DATE_FMT) : ""),
            col("Status", 75, r -> r.getStatusDisplay())
        );

        return table;
    }

    // ── Export CSV ─────────────────────────────────────────────────────

    private void exportCsv(List<AssetRegisterRow> rows,
                            AssetRegisterParams p, Window owner) {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Export Asset Register CSV");
        fc.setInitialFileName("asset_register.csv");
        fc.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("CSV files", "*.csv"));
        java.io.File file = fc.showSaveDialog(owner);
        if (file == null) return;

        try (var writer = new java.io.PrintWriter(file, "UTF-8")) {
            // 18-column format matching EXPORT-EXCEL-ASSET in fatl10.pl
            writer.println("Asset No,Desc 1,Desc 2,Location,Dept,Group,SubGrp," +
                "Acqn Date,Cost,Accum Depn,WDV,Depn Method,Depn Code,Depn Rate," +
                "Depn Freq,Start Depn,Last Depn,Status");
            for (AssetRegisterRow r : rows) {
                writer.println(String.join(",",
                    csv(r.getAssetNo()),
                    csv(r.getDesc1()),
                    csv(r.getDesc2()),
                    csv(r.getLocCode()),
                    csv(r.getDeptCode()),
                    csv(r.getGrpCode()),
                    csv(r.getSubgrpCode()),
                    r.getAcqnDate() != null ? r.getAcqnDate().format(DATE_FMT) : "",
                    r.getCost().toPlainString(),
                    r.getAccumDepn().toPlainString(),
                    r.getWdv().toPlainString(),
                    csv(r.getDepnMethod()),
                    csv(r.getDepnCode()),
                    r.getDepnRate().toPlainString(),
                    String.valueOf(r.getDepnFreq()),
                    r.getStartDepnDate() != null ? r.getStartDepnDate().format(DATE_FMT) : "",
                    r.getLastDepnDate()  != null ? r.getLastDepnDate().format(DATE_FMT)  : "",
                    csv(r.getStatusDisplay())
                ));
            }
        } catch (Exception ex) {
            showError("Export failed: " + ex.getMessage(), owner);
        }
    }

    // ── Lookup desc updates ────────────────────────────────────────────

    private void updateLocnDesc() {
        if (companyNo == 0) return;
        String v = fLocn.getText().toUpperCase().trim();
        String err = service.validateLocn(companyNo, v);
        lLocnDesc.setText(err != null ? "— " + err : service.lookupLocnDesc(companyNo, v));
    }
    private void updateDeptDesc() {
        if (companyNo == 0) return;
        String v = fDept.getText().toUpperCase().trim();
        String err = service.validateDept(companyNo, v);
        lDeptDesc.setText(err != null ? "— " + err : service.lookupDeptDesc(companyNo, v));
    }
    private void updateGroupDesc() {
        if (companyNo == 0) return;
        String v = fGroup.getText().toUpperCase().trim();
        String err = service.validateGroup(companyNo, v);
        lGroupDesc.setText(err != null ? "— " + err : service.lookupGroupDesc(companyNo, v));
    }
    private void updateSubGroupDesc() {
        if (companyNo == 0) return;
        String v = fSubGroup.getText().toUpperCase().trim();
        String err = service.validateSubGroup(companyNo, v);
        lSubGroupDesc.setText(err != null ? "— " + err : service.lookupSubGroupDesc(companyNo, v));
    }

    // ── Company loading ────────────────────────────────────────────────

    private void loadCompanies() {
        try {
            List<CompanyRow> companies = companyRepo.findAll();
            for (CompanyRow c : companies)
                cboCompany.getItems().add(new CompanyItem(c.getCompanyNo(), c.getName()));
        } catch (Exception e) {
            System.err.println("Could not load companies: " + e.getMessage());
        }
    }

    record CompanyItem(int companyNo, String name) {
        @Override public String toString() { return companyNo + "  " + name; }
    }

    // ── Reset ──────────────────────────────────────────────────────────

    private void resetParams() {
        fStartAsset.clear(); fEndAsset.clear();
        fLocn.clear(); fDept.clear(); fGroup.clear(); fSubGroup.clear();
        lLocnDesc.setText("ALL LOCATIONS"); lDeptDesc.setText("ALL DEPARTMENTS");
        lGroupDesc.setText("ALL GROUPS");   lSubGroupDesc.setText("ALL SUB-GROUPS");
        cbUnposted.setSelected(true); cbActive.setSelected(true);
        cbOnHold.setSelected(true);   cbInactive.setSelected(true);
        cbRetired.setSelected(true);
        dpAsAt.setValue(LocalDate.now());
        dpStart.setValue(LocalDate.of(1900, 1, 1));
        cbActivity.setSelected(false);
        lblStatus.setText("Parameters reset");
        lblStatus.setStyle("-fx-text-fill: #6B7280;");
    }

    // ── UI helpers ─────────────────────────────────────────────────────

    private HBox buildHeader(String title, String code) {
        Label lTitle = new Label(title);
        lTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: white;");
        Label lCode = new Label(code);
        lCode.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(255,255,255,0.7);" +
            "-fx-background-color: rgba(255,255,255,0.15); -fx-padding: 3 8 3 8;" +
            "-fx-background-radius: 4;");
        HBox hdr = new HBox(12, lTitle, lCode);
        hdr.setAlignment(Pos.CENTER_LEFT);
        hdr.setPadding(new Insets(14, 20, 14, 20));
        hdr.setStyle("-fx-background-color: #0061FF; -fx-min-height: 50px;");
        return hdr;
    }

    private void addSectionHeader(GridPane grid, int row, String text) {
        Label lbl = new Label(text.toUpperCase());
        lbl.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #0061FF;" +
            "-fx-padding: 10 0 2 0;");
        GridPane.setColumnSpan(lbl, 2);
        grid.add(lbl, 0, row);
    }

    private void addRow(GridPane grid, int row, String label, javafx.scene.Node field) {
        Label lbl = new Label(label + ":");
        lbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #374151; -fx-min-width: 190px;");
        lbl.setAlignment(Pos.CENTER_RIGHT);
        grid.add(lbl, 0, row);
        grid.add(field, 1, row);
    }

    private HBox buildLookupRow(TextField field, Label descLabel,
                                 LookupDialog.LookupType type) {
        Button btnLookup = new Button("\u2026");
        btnLookup.setStyle("-fx-background-color: #F4F6F9; -fx-border-color: #D1D9E0;" +
            "-fx-border-radius: 4; -fx-background-radius: 4; -fx-padding: 4 8 4 8;" +
            "-fx-cursor: hand; -fx-font-size: 12px;");
        btnLookup.setOnAction(e -> {
            if (companyNo == 0) return;
            new LookupDialog(lookupService, type, companyNo, selected -> {
                field.setText(selected);
                // Trigger focus-lost to update description label
                field.getParent().requestFocus();
                field.requestFocus();
            }).show(field.getScene().getWindow());
        });
        return new HBox(4, field, btnLookup, descLabel);
    }

    private TextField tf(String value, int maxLen) {
        TextField f = new TextField(value);
        f.setMaxWidth(maxLen * 9.0);
        return f;
    }

    private Label displayLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: #6B7280; -fx-padding: 0 0 0 6;");
        return l;
    }

    private Label styledLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: #374151;");
        return l;
    }

    private RadioButton radio(String text, ToggleGroup group,
                               String userData, boolean selected) {
        RadioButton rb = new RadioButton(text);
        rb.setToggleGroup(group);
        rb.setUserData(userData);
        rb.setSelected(selected);
        return rb;
    }

    private Button btn(String text, String bg, String fg) {
        return btn(text, bg, fg, "transparent");
    }
    private Button btn(String text, String bg, String fg, String border) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: " + fg + ";" +
            "-fx-font-size: 13px; -fx-font-weight: bold; -fx-background-radius: 6;" +
            "-fx-padding: 8 18 8 18; -fx-cursor: hand;" +
            "-fx-border-color: " + border + "; -fx-border-radius: 6; -fx-border-width: 1;");
        return b;
    }

    private <S> TableColumn<S, String> col(String header, int minWidth,
                                             java.util.function.Function<S, String> fn) {
        TableColumn<S, String> c = new TableColumn<>(header);
        c.setCellValueFactory(cd -> new SimpleStringProperty(fn.apply(cd.getValue())));
        c.setMinWidth(minWidth);
        return c;
    }

    private TableColumn<AssetRegisterRow, String> colDec(String header, int minWidth,
            java.util.function.Function<AssetRegisterRow, BigDecimal> fn) {
        TableColumn<AssetRegisterRow, String> c = new TableColumn<>(header);
        c.setCellValueFactory(cd -> {
            BigDecimal v = fn.apply(cd.getValue());
            return new SimpleStringProperty(v == null || v.compareTo(BigDecimal.ZERO) == 0
                ? "" : String.format("%,.2f", v));
        });
        c.setStyle("-fx-alignment: CENTER-RIGHT;");
        c.setMinWidth(minWidth);
        return c;
    }

    private String buildParamSummary(AssetRegisterParams p) {
        String bt   = "B".equals(p.getBookTaxInd()) ? "Book" : "Tax";
        String cost = "A".equals(p.getCostInd()) ? "Actual cost" : "Revalued cost";
        return String.format("Company: %d  |  %s depn  |  %s  |  As at: %s  |  Loc: %s  |  Grp: %s",
            companyNo, bt, cost,
            p.getAsAtDate().format(DATE_FMT),
            p.getPrintLocn().isEmpty() ? "All" : p.getPrintLocn(),
            p.getPrintGroup().isEmpty() ? "All" : p.getPrintGroup());
    }

    private void showError(String msg, Window owner) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.initOwner(owner);
        a.showAndWait();
    }

    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\""))
            return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }
}
