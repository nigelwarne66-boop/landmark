package com.example.fixedassets.ui;

import com.example.fixedassets.model.AssetBarCode;
import com.example.fixedassets.model.AssetListRow;
import com.example.fixedassets.model.AssetMaintenanceRecord;
import com.example.fixedassets.model.AppSession;
import com.example.fixedassets.ui.AssetRegisterParamsController;
import java.util.Map;
import java.util.LinkedHashMap;
import com.example.fixedassets.repository.CompanyRepository;
import com.example.fixedassets.service.AssetMaintenanceService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FAAS01 — Asset Maintenance.
 *
 * Screen architecture (mirrors COBOL exactly):
 *
 *  Main window = P1 listbox  (all assets for company)
 *    Buttons: Edit | Status | Depn Details | Print | Documents | Exit
 *
 *  Edit button → S1 dialog (main asset fields)
 *    OK on S1 → S1Z option selector → S1A / S1B / bar codes
 *
 *  Status button → S2 dialog (change A/H/N only — not R, retirement is separate)
 *
 *  Depn Details button → S3 dialog (bulk depreciation parameter change)
 *
 *  Bar Codes button (from S1Z) → P5 listbox with Add/Delete
 *
 * Java implementation uses:
 *  - TableView for P1 (listbox)
 *  - Separate Stage/Scene for each sub-screen (dialog-style, modal)
 *  - All validation matches COBOL faas01.pl business rules
 */
@Component
public class AssetMaintenanceController {

    private final AssetMaintenanceService service;
    private final CompanyRepository       companyRepo;
    private final AssetRegisterParamsController assetRegisterParams;
    private final JdbcTemplate            jdbc;
    private final AppSession              appSession;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ── State ─────────────────────────────────────────────────────────
    private int     companyNo          = 0;
    private boolean overseasParentFlag = false;

    // Company selector
    private ComboBox<CompanyItem> cboCompany;

    // Currently selected asset in P1
    private AssetMaintenanceRecord currentAsset;
    private String                  currentBeforeSnapshot;

    // P1 table
    private TableView<AssetListRow>      assetTable;
    private ObservableList<AssetListRow> assetItems;
    private Label                        lblStatus;
    private ProgressBar                  progressBar;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "faas01-thread");
        t.setDaemon(true);
        return t;
    });

    public AssetMaintenanceController(AssetMaintenanceService service,
                                       CompanyRepository companyRepo,
                                       AssetRegisterParamsController assetRegisterParams,
                                       JdbcTemplate jdbc,
                                       AppSession appSession) {
        this.service               = service;
        this.companyRepo           = companyRepo;
        this.assetRegisterParams   = assetRegisterParams;
        this.jdbc                  = jdbc;
        this.appSession            = appSession;
    }

    // ── Build main scene (P1 listbox) ──────────────────────────────────

    public Scene buildScene(Stage stage) {
        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: #F0F4F8;");

        TableView<AssetListRow> table = buildTable();
        root.getChildren().addAll(
            buildHeader(),
            buildToolbar(stage),
            table,
            buildFooter()
        );
        VBox.setVgrow(table, Priority.ALWAYS);

        Scene scene = new Scene(root, 1000, 600);
        loadCompanies();
        return scene;
    }

    // ── Header with company selector ───────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("Asset Maintenance");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1F4E79;");

        cboCompany = new ComboBox<>();
        cboCompany.setMaxWidth(320);
        cboCompany.setPromptText("Loading companies\u2026");
        cboCompany.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                companyNo = newVal.companyNo();
                loadAssets();
            }
        });

        Label lblCompany = new Label("Company");
        lblCompany.setStyle("-fx-font-size: 11px; -fx-text-fill: #374151;");

        HBox companyRow = new HBox(8, lblCompany, cboCompany);
        companyRow.setAlignment(Pos.CENTER_LEFT);

        VBox text = new VBox(4, title, companyRow);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox hdr = new HBox(text);
        hdr.setPadding(new Insets(12, 16, 10, 16));
        hdr.setStyle("-fx-background-color: white; -fx-border-color: #E2E8F0;" +
                     "-fx-border-width: 0 0 1 0;");
        return hdr;
    }

    // ── Toolbar (buttons matching COBOL P1 buttons) ────────────────────

    private HBox buildToolbar(Stage stage) {
        Button btnEdit   = btn("Edit",         "#0061FF", "#FFFFFF");
        Button btnStatus = btn("Status",       "#475569", "#FFFFFF");
        Button btnDepn   = btn("Depn Details", "#475569", "#FFFFFF");
        Button btnPrint  = btn("Print",        "#475569", "#FFFFFF");
        Button btnRegister = btn("📊  Asset Register", "#1A6EF5", "#FFFFFF");
        Button btnExit   = btn("Exit",         "#EF4444", "#FFFFFF");

        btnEdit.setOnAction(e -> openEditScreen(stage));
        btnStatus.setOnAction(e -> openStatusScreen(stage));
        btnDepn.setOnAction(e -> openDepnChangeScreen(stage));
        btnPrint.setOnAction(e -> showInfo("Print", "Print Asset Listing — not yet implemented.\n(Calls FAAS10 / FATL10 in the original system.)"));
        btnRegister.setOnAction(e -> openAssetRegister(stage));
        btnExit.setOnAction(e -> stage.close());

        HBox bar = new HBox(6, btnEdit, btnStatus, btnDepn, btnPrint,
            new Separator(Orientation.VERTICAL), btnRegister,
            new Separator(Orientation.VERTICAL), btnExit);
        bar.setPadding(new Insets(8, 16, 8, 16));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: #F8FAFC; -fx-border-color: #E2E8F0;" +
                     "-fx-border-width: 0 0 1 0;");
        return bar;
    }

    // ── P1 asset table ─────────────────────────────────────────────────

    private TableView<AssetListRow> buildTable() {
        assetTable = new TableView<>();
        assetItems = FXCollections.observableArrayList();
        assetTable.setItems(assetItems);
        assetTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // Columns matching COBOL P1: Asset No | Description | Loc | Dept | Grp | SubGrp | Status
        assetTable.getColumns().addAll(
            col("Asset Number",  20, r -> r.assetNo()),
            col("Description",   35, r -> r.desc1()),
            col("Loc",            6, r -> r.locCode()),
            col("Dept",           6, r -> r.deptCode()),
            col("Grp",            6, r -> r.grpCode()),
            col("Sub Grp",        6, r -> r.subgrpCode()),
            col("Status",        12, r -> r.statusDisplay())
        );

        // Row styling — pooled assets shown in a different colour
        assetTable.setRowFactory(tv -> {
            TableRow<AssetListRow> row = new TableRow<>();
            row.itemProperty().addListener((obs, old, item) -> {
                if (item != null && item.isPooled()) {
                    row.setStyle("-fx-background-color: #EFF6FF;");
                } else {
                    row.setStyle("");
                }
            });
            return row;
        });

        assetTable.setStyle("-fx-font-size: 12px;");
        VBox.setVgrow(assetTable, Priority.ALWAYS);
        return assetTable;
    }

    @SuppressWarnings("unchecked")
    private <T> TableColumn<T, String> col(String header, int pref,
                                            java.util.function.Function<T, String> getter) {
        TableColumn<T, String> c = new TableColumn<>(header);
        c.setCellValueFactory(cell -> new SimpleStringProperty(
            getter.apply(cell.getValue())));
        c.setPrefWidth(pref * 9.0);
        return c;
    }

    // ── Footer ─────────────────────────────────────────────────────────

    private HBox buildFooter() {
        progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        progressBar.setPrefWidth(150);

        lblStatus = new Label("Ready");
        lblStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748B;");
        HBox.setHgrow(lblStatus, Priority.ALWAYS);

        HBox footer = new HBox(8, progressBar, lblStatus);
        footer.setPadding(new Insets(6, 16, 6, 16));
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setStyle("-fx-background-color: #F8FAFC; -fx-border-color: #E2E8F0;" +
                        "-fx-border-width: 1 0 0 0;");
        return footer;
    }

    // ── Asset Register report ────────────────────────────────────────────

    private void openAssetRegister(Stage owner) {
        CompanyItem company = cboCompany.getValue();
        if (company == null) { showInfo("No Company", "Please select a company first."); return; }
        assetRegisterParams.show(company.companyNo(), owner);
    }

    private void openInBrowser(String url, Stage owner) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", "", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception ex) {
            showInfo("Report URL", url);
        }
    }

    private void openInBrowser(java.nio.file.Path path, Stage owner) {
        openInBrowser(path.toUri().toString(), owner);
    }

    // ── Load companies ─────────────────────────────────────────────────

    private void loadCompanies() {
        executor.submit(() -> {
            var items = FXCollections.observableArrayList(
                companyRepo.findAll().stream()
                    .map(c -> new CompanyItem(c.getCompanyNo(), c.getName()))
                    .toList());
            Platform.runLater(() -> {
                cboCompany.setItems(items);
                // Pre-select the session company from AppSession (GLPASS)
                items.stream()
                    .filter(c -> c.companyNo() == appSession.getCompanyNo())
                    .findFirst()
                    .ifPresentOrElse(
                        cboCompany.getSelectionModel()::select,
                        () -> { if (!items.isEmpty()) cboCompany.getSelectionModel().selectFirst(); }
                    );
                if (items.isEmpty()) cboCompany.setPromptText("No companies found");
            });
        });
    }

    // ── Load assets ────────────────────────────────────────────────────

    private void loadAssets() {
        setStatus("Loading assets\u2026", "normal");
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        executor.submit(() -> {
            List<AssetListRow> rows = service.getAssetList(companyNo);
            Platform.runLater(() -> {
                assetItems.setAll(rows);
                progressBar.setVisible(false);
                setStatus(rows.size() + " assets", "normal");
            });
        });
    }

    // ── Get selected asset (guards all action buttons) ─────────────────

    private AssetListRow getSelectedRow() {
        AssetListRow sel = assetTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            showInfo("No Selection", "Please select an asset from the list first.");
        }
        return sel;
    }

    // ══════════════════════════════════════════════════════════════════
    // S1 — Edit Asset Screen
    // ══════════════════════════════════════════════════════════════════

    private void openEditScreen(Stage owner) {
        AssetListRow sel = getSelectedRow();
        if (sel == null) return;

        setStatus("Loading asset " + sel.assetNo() + "\u2026", "normal");
        executor.submit(() -> {
            try {
                System.out.println("DEBUG: Loading asset " + sel.assetNo() + " for company " + companyNo);
                var validation = service.validateForEdit(companyNo, sel.assetNo());
                System.out.println("DEBUG: validation = " + validation);
                var assetOpt   = service.loadAsset(companyNo, sel.assetNo());
                System.out.println("DEBUG: assetOpt.isEmpty() = " + assetOpt.isEmpty());

                Platform.runLater(() -> {
                    try {
                        System.out.println("DEBUG: In Platform.runLater, assetOpt.isEmpty()=" + assetOpt.isEmpty());
                        setStatus("Ready", "normal");
                        if (assetOpt.isEmpty()) { showError("Asset not found: " + sel.assetNo()); return; }
                        currentAsset = assetOpt.get();
                        currentBeforeSnapshot = service.snapshot(currentAsset);

                        if (validation != null) {
                            if (validation.inquireOnly()) {
                                showInfo("Unposted Transactions",
                                    "Asset has unposted transactions.\nOpening in view-only mode.");
                                openS1Dialog(owner, true);
                            } else {
                                showError(validation.message());
                            }
                            return;
                        }
                        openS1Dialog(owner, false);
                    } catch (Exception uiEx) {
                        setStatus("UI Error: " + uiEx.getMessage(), "error");
                        showError("Error opening edit screen:\n" + uiEx.getClass().getSimpleName()
                            + ": " + uiEx.getMessage());
                        uiEx.printStackTrace();
                    }
                });
            } catch (Exception ex) {
                System.out.println("DEBUG: Exception in background thread: " + ex.getClass().getName() + ": " + ex.getMessage());
                ex.printStackTrace();
                Platform.runLater(() -> {
                    setStatus("Error: " + ex.getMessage(), "error");
                    showError("Failed to load asset:\n" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                });
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // Edit dialog — tabbed: Main | Other Details | Depreciation | Bar Codes
    // ══════════════════════════════════════════════════════════════════

    private void openS1Dialog(Stage owner, boolean readOnly) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Edit Asset — " + currentAsset.getAssetNo()
            + "  " + currentAsset.getDesc1());

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
            buildMainTab(readOnly),
            buildOtherDetailsTab(readOnly),
            buildDepreciationTab(readOnly),
            buildBarCodesTab()
        );

        Button btnOk     = btn("OK",     "#1F4E79", "#FFFFFF");
        Button btnCancel = btn("Cancel", "#EF4444", "#FFFFFF");
        Button btnExit   = btn("Exit",   "#64748B", "#FFFFFF");

        if (readOnly) btnOk.setDisable(true);

        btnCancel.setOnAction(e -> { showInfo("Cancelled", "Asset changes cancelled."); dlg.close(); });
        btnExit.setOnAction(e -> dlg.close());
        btnOk.setOnAction(e -> {
            gatherMainTab();
            gatherOtherDetailsTab();
            gatherDepreciationTab();
            String attachErr = service.validateAttachTo(companyNo, currentAsset);
            if (attachErr != null) { showError(attachErr); return; }
            executor.submit(() -> {
                service.saveWithAudit(currentAsset, currentBeforeSnapshot);
                Platform.runLater(() -> { dlg.close(); loadAssets();
                    setStatus("Saved: " + currentAsset.getAssetNo(), "success"); });
            });
        });

        HBox buttons = new HBox(8, btnOk, btnCancel, btnExit);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(8, 12, 8, 12));
        buttons.setStyle("-fx-border-color: #E2E8F0; -fx-border-width: 1 0 0 0;");

        VBox root = new VBox(tabs, buttons);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        dlg.setScene(new Scene(root, 760, 640));
        dlg.showAndWait();
        loadAssets();
    }

    // ── Tab field references (populated by build, read by gather) ─────
    private TextField fDesc1, fDesc2, fAlphaCode, fStakeSite, fAttachTo,
                      fAcqnType, fOrderNo, fParentRateInd, fParentRate, fParentFcMathsInd;
    private TextField fSupplierName, fSupplierNo, fInvoiceNo, fLseContractNo,
                      fPaymentAmt, fPaymentFreq, fCurrentInsVal, fReplNewVal, fInsType;
    private DatePicker dpLeaseExpiry, dpReplAsAt;
    private RadioButton rdoLeased, rdoNotLeased;
    private TextField fTaxMethod, fTaxCode, fTaxRate1, fTaxRate2, fTaxFreq;
    private TextField fBookMethod, fBookCode, fBookRate1, fBookRate2, fBookFreq;
    private DatePicker dpWriteDown, dpStartDepn, dpStartTax;
    private RadioButton rdoPostNone, rdoPostCL, rdoPostBA;

    // ── Tab 1: Main ───────────────────────────────────────────────────

    private Tab buildMainTab(boolean readOnly) {
        fDesc1      = tf(currentAsset.getDesc1(), 35);
        fDesc2      = tf(currentAsset.getDesc2(), 35);
        fAlphaCode  = tf(currentAsset.getAlphaCode(), 35);
        fStakeSite  = tf(currentAsset.getStakeSite(), 6);
        Label lStakeSiteDesc = displayLabel(currentAsset.getStakeSiteDesc());
        fAttachTo   = tf(currentAsset.getAttachToAssetNo(), 20);
        fAcqnType   = tf(currentAsset.getAcqnType(), 3);
        fOrderNo    = tf(currentAsset.getInternalOrderNo(), 10);
        fParentRateInd  = tf(currentAsset.getParentRateInd(), 1);
        fParentRate     = tf(fmtDec(currentAsset.getParentRateCurr()), 9);
        fParentFcMathsInd = tf(currentAsset.getParentFcMathsInd(), 1);

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(7);
        grid.setPadding(new Insets(12));
        int row = 0;
        addRow(grid, row++, "Asset no",          displayLabel(currentAsset.getAssetNo()));
        addRow(grid, row++, "Status",             displayLabel(currentAsset.statusDisplayDesc()));
        addRow(grid, row++, "Description 1",     fDesc1);
        addRow(grid, row++, "Description 2",     fDesc2);
        addRow(grid, row++, "Alpha key",          fAlphaCode);
        addRow(grid, row++, "Location",           displayLabel(currentAsset.getLocCode() + "  " + currentAsset.getLocDesc()));
        addRow(grid, row++, "Department",         displayLabel(currentAsset.getDeptCode() + "  " + currentAsset.getDeptDesc()));
        addRow(grid, row++, "Group",              displayLabel(currentAsset.getGrpCode() + "  " + currentAsset.getGrpDesc()));
        addRow(grid, row++, "Sub-group",          displayLabel(currentAsset.getSubgrpCode() + "  " + currentAsset.getSubgrpDesc()));
        addRow(grid, row++, "Stocktake site",     buildLookupRow(fStakeSite, lStakeSiteDesc,
            LookupDialog.LookupType.STOCKTAKE_SITE, () ->
                lStakeSiteDesc.setText(lookupCodeDesc("FACODSS", "stake_site_code", fStakeSite.getText().trim()))));
        addRow(grid, row++, "Attached to asset",  fAttachTo);
        addRow(grid, row++, "Asset pool ?",       buildRow(displayLabel(currentAsset.getAssetPoolFlag()),
                                                            new Label("  Qty: "),
                                                            displayLabel(fmtDec(currentAsset.getQty()))));
        addRow(grid, row++, "Acquisition date",   displayLabel(fmtDate(currentAsset.getAcqnDate())));
        addRow(grid, row++, "Acquisition type",   fAcqnType);
        addRow(grid, row++, "Internal order no",  fOrderNo);
        if (overseasParentFlag) {
            addSectionHeader(grid, row++, "Parent accounting (overseas):");
            addRow(grid, row++, "C=current / P=purchase rate", fParentRateInd);
            addRow(grid, row++, "Exchange rate at purchase",   fParentRate);
            addRow(grid, row++, "D=divide / M=multiply",       fParentFcMathsInd);
        }
        if (readOnly) List.of(fDesc1, fDesc2, fAlphaCode, fStakeSite, fAttachTo,
                fAcqnType, fOrderNo, fParentRateInd, fParentRate, fParentFcMathsInd)
            .forEach(f -> f.setEditable(false));

        ScrollPane sp = new ScrollPane(grid); sp.setFitToWidth(true);
        Tab tab = new Tab("Main"); tab.setContent(sp); return tab;
    }

    private void gatherMainTab() {
        currentAsset.setDesc1(fDesc1.getText());
        currentAsset.setDesc2(fDesc2.getText());
        currentAsset.setAlphaCode(fAlphaCode.getText());
        currentAsset.setStakeSite(fStakeSite.getText());
        currentAsset.setAttachToAssetNo(fAttachTo.getText());
        currentAsset.setAcqnType(fAcqnType.getText());
        currentAsset.setInternalOrderNo(fOrderNo.getText());
        if (overseasParentFlag) {
            currentAsset.setParentRateInd(fParentRateInd.getText().toUpperCase());
            currentAsset.setParentRateCurr(parseDec(fParentRate.getText()));
            currentAsset.setParentFcMathsInd(fParentFcMathsInd.getText().toUpperCase());
        }
    }

    // ── Tab 2: Other Details (S1A) ────────────────────────────────────

    private Tab buildOtherDetailsTab(boolean readOnly) {
        fSupplierName = tf(currentAsset.getSupplierName(), 35);
        fSupplierNo   = tf(currentAsset.getSupplierNo(), 10);
        fInvoiceNo    = tf(currentAsset.getInvoiceNo(), 15);
        ToggleGroup leasedGroup = new ToggleGroup();
        rdoLeased    = new RadioButton("Y — Leased");
        rdoNotLeased = new RadioButton("N — Not leased");
        rdoLeased.setToggleGroup(leasedGroup);
        rdoNotLeased.setToggleGroup(leasedGroup);
        ("Y".equals(currentAsset.getLeasedAssetFlag()) ? rdoLeased : rdoNotLeased).setSelected(true);
        dpLeaseExpiry = datePicker(currentAsset.getLseExpiryDate());
        fLseContractNo = tf(currentAsset.getLseContractNo(), 20);
        fPaymentAmt   = tf(fmtDec(currentAsset.getLsePaymentAmt()), 12);
        fPaymentFreq  = tf(currentAsset.getLsePaymentFreq(), 5);
        fCurrentInsVal = tf(fmtDec(currentAsset.getCurrentInsValue()), 14);
        fReplNewVal   = tf(fmtDec(currentAsset.getReplNewVal()), 14);
        dpReplAsAt    = datePicker(currentAsset.getReplValAsAtDate());
        fInsType      = tf(currentAsset.getInsType(), 6);
        Label lInsTypeDesc = displayLabel(currentAsset.getInsTypeDesc());

        Runnable updateLeaseFields = () -> {
            boolean leased = rdoLeased.isSelected();
            dpLeaseExpiry.setDisable(!leased);
            fLseContractNo.setDisable(!leased);
            fPaymentAmt.setDisable(!leased);
            fPaymentFreq.setDisable(!leased);
        };
        rdoLeased.setOnAction(e -> updateLeaseFields.run());
        rdoNotLeased.setOnAction(e -> updateLeaseFields.run());
        updateLeaseFields.run();

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(7);
        grid.setPadding(new Insets(12));
        int row = 0;
        addSectionHeader(grid, row++, "Supplier details:");
        addRow(grid, row++, "Supplier name",     fSupplierName);
        addRow(grid, row++, "Supplier no",       fSupplierNo);
        addRow(grid, row++, "Invoice no",        fInvoiceNo);
        addSectionHeader(grid, row++, "Lease details:");
        addRow(grid, row++, "Is this leased ?",  new HBox(10, rdoLeased, rdoNotLeased));
        addRow(grid, row++, "Lease expiry date", dpLeaseExpiry);
        addRow(grid, row++, "Contract no",       fLseContractNo);
        addRow(grid, row++, "Payment amount",    fPaymentAmt);
        addRow(grid, row++, "Payment freq'y",   fPaymentFreq);
        addSectionHeader(grid, row++, "Insurance details:");
        addRow(grid, row++, "Current ins value", fCurrentInsVal);
        addRow(grid, row++, "Replace new value", fReplNewVal);
        addRow(grid, row++, "As at date",        dpReplAsAt);
        addRow(grid, row++, "Insurance type",    buildLookupRow(fInsType, lInsTypeDesc,
            LookupDialog.LookupType.INSURANCE_TYPE, () ->
                lInsTypeDesc.setText(lookupCodeDesc("FACODIN", "ins_type_code", fInsType.getText().trim()))));

        if (readOnly) List.of(fSupplierName, fSupplierNo, fInvoiceNo, fLseContractNo,
                fPaymentAmt, fPaymentFreq, fCurrentInsVal, fReplNewVal, fInsType)
            .forEach(f -> f.setEditable(false));

        ScrollPane sp = new ScrollPane(grid); sp.setFitToWidth(true);
        Tab tab = new Tab("Other Details"); tab.setContent(sp); return tab;
    }

    private void gatherOtherDetailsTab() {
        currentAsset.setSupplierName(fSupplierName.getText());
        currentAsset.setSupplierNo(fSupplierNo.getText());
        currentAsset.setInvoiceNo(fInvoiceNo.getText());
        currentAsset.setLeasedAssetFlag(rdoLeased.isSelected() ? "Y" : "N");
        currentAsset.setLseExpiryDate(dpLeaseExpiry.getValue());
        currentAsset.setLseContractNo(fLseContractNo.getText());
        currentAsset.setLsePaymentAmt(parseDec(fPaymentAmt.getText()));
        currentAsset.setLsePaymentFreq(fPaymentFreq.getText());
        currentAsset.setCurrentInsValue(parseDec(fCurrentInsVal.getText()));
        currentAsset.setReplNewVal(parseDec(fReplNewVal.getText()));
        currentAsset.setReplValAsAtDate(dpReplAsAt.getValue());
        currentAsset.setInsType(fInsType.getText());
    }

    // ── Tab 3: Depreciation (S1B) ─────────────────────────────────────

    private Tab buildDepreciationTab(boolean readOnly) {
        boolean leased = currentAsset.isLeased();
        boolean pooled = currentAsset.isPooled();

        fTaxMethod  = tf(currentAsset.getTaxDepnMethod(), 1);
        Label lTaxMethodDesc  = displayLabel(currentAsset.getTaxDepnMethodDesc());
        lTaxMethodDesc.setMinWidth(200);
        lTaxMethodDesc.setWrapText(false);
        fTaxCode    = tf(currentAsset.getTaxDepnCode(), 6);
        Label lTaxCodeDesc    = displayLabel(currentAsset.getTaxDepnCodeDesc());
        fTaxRate1   = tf(fmtDec(currentAsset.getTaxRate1()), 6);
        fTaxRate2   = tf(fmtDec(currentAsset.getTaxRate2()), 6);
        fTaxFreq    = tf(String.valueOf(currentAsset.getTaxDepnFreq()), 4);
        fBookMethod = tf(currentAsset.getBookDepnMethod(), 1);
        Label lBookMethodDesc = displayLabel(currentAsset.getBookDepnMethodDesc());
        lBookMethodDesc.setMinWidth(200);
        lBookMethodDesc.setWrapText(false);
        fBookCode   = tf(currentAsset.getBookDepnCode(), 6);
        Label lBookCodeDesc   = displayLabel(currentAsset.getBookDepnCodeDesc());
        fBookRate1  = tf(fmtDec(currentAsset.getBookRate1()), 6);
        fBookRate2  = tf(fmtDec(currentAsset.getBookRate2()), 6);
        fBookFreq   = tf(String.valueOf(currentAsset.getBookDepnFreq()), 4);
        dpWriteDown = datePicker(currentAsset.getWriteDownDate());
        dpStartDepn = datePicker(currentAsset.getStartDepnDate());
        dpStartTax  = datePicker(currentAsset.getStartTaxDepnDate());

        ToggleGroup postGroup = new ToggleGroup();
        rdoPostNone = new RadioButton("N — None");
        rdoPostCL   = new RadioButton("C — Cost Ledger");
        rdoPostBA   = new RadioButton("B — Business Analysis");
        rdoPostNone.setToggleGroup(postGroup);
        rdoPostCL.setToggleGroup(postGroup);
        rdoPostBA.setToggleGroup(postGroup);
        switch (currentAsset.getPostDepnToClBa()) {
            case "C" -> rdoPostCL.setSelected(true);
            case "B" -> rdoPostBA.setSelected(true);
            default  -> rdoPostNone.setSelected(true);
        }

        if (leased || pooled || readOnly) {
            List.of(fTaxMethod, fTaxCode, fTaxRate1, fTaxRate2, fTaxFreq,
                    fBookMethod, fBookCode, fBookRate1, fBookRate2, fBookFreq)
                .forEach(f -> f.setEditable(false));
            dpWriteDown.setDisable(true); dpStartDepn.setDisable(true); dpStartTax.setDisable(true);
        }
        if (!currentAsset.getTaxDepnCode().isBlank()) {
            fTaxRate1.setEditable(false); fTaxRate2.setEditable(false); fTaxFreq.setEditable(false);
        }
        if (!currentAsset.getBookDepnCode().isBlank()) {
            fBookRate1.setEditable(false); fBookRate2.setEditable(false); fBookFreq.setEditable(false);
        }

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(7);
        grid.setPadding(new Insets(12));
        int row = 0;
        addSectionHeader(grid, row++, "Costs (display only):");
        addRow(grid, row++, "Actual cost",         displayLabel(fmtDec(currentAsset.getActualCost())));
        addRow(grid, row++, "Tax depn cost",       displayLabel(fmtDec(currentAsset.getTaxDepnCost())));
        addRow(grid, row++, "Book depn cost",      displayLabel(fmtDec(currentAsset.getBookDepnCost())));
        addRow(grid, row++, "Interest component",  displayLabel(fmtDec(currentAsset.getInterestComponent())));
        addRow(grid, row++, "Write down by date",  dpWriteDown);
        addRow(grid, row++, "Start tax depn date", dpStartTax);
        addSectionHeader(grid, row++, "Tax Depreciation:");
        addRow(grid, row++, "Method  (S/D)",   buildRow(fTaxMethod,  lTaxMethodDesc));
        addRow(grid, row++, "Code",             buildLookupRow(fTaxCode, lTaxCodeDesc,
            LookupDialog.LookupType.DEPN_CODE, () ->
                lTaxCodeDesc.setText(lookupDepnCodeDesc(fTaxCode.getText().trim()))));
        addRow(grid, row++, "Rate Yr1 / Yr2+", buildRow(fTaxRate1, new Label(" / "), fTaxRate2));
        addRow(grid, row++, "Frequency (mths)", fTaxFreq);
        addSectionHeader(grid, row++, "Book Depreciation:");
        addRow(grid, row++, "Method  (S/D)",   buildRow(fBookMethod, lBookMethodDesc));
        addRow(grid, row++, "Code",             buildLookupRow(fBookCode, lBookCodeDesc,
            LookupDialog.LookupType.DEPN_CODE, () ->
                lBookCodeDesc.setText(lookupDepnCodeDesc(fBookCode.getText().trim()))));
        addRow(grid, row++, "Rate Yr1 / Yr2+", buildRow(fBookRate1, new Label(" / "), fBookRate2));
        addRow(grid, row++, "Frequency (mths)", fBookFreq);
        if (pooled) {
            addSectionHeader(grid, row++, "Pool Balances:");
            addRow(grid, row++, "Book balance", buildRow(
                displayLabel(fmtDec(currentAsset.getPoolBookBal())),
                displayLabel("  " + fmtDate(currentAsset.getPoolBookBalDate()))));
            addRow(grid, row++, "Tax balance", buildRow(
                displayLabel(fmtDec(currentAsset.getPoolTaxBal())),
                displayLabel("  " + fmtDate(currentAsset.getPoolTaxBalDate()))));
        }
        addSectionHeader(grid, row++, "Post depreciation to:");
        grid.add(new VBox(5, rdoPostNone, rdoPostCL, rdoPostBA), 1, row);

        ScrollPane sp = new ScrollPane(grid); sp.setFitToWidth(true);
        Tab tab = new Tab("Depreciation"); tab.setContent(sp); return tab;
    }

    private void gatherDepreciationTab() {
        currentAsset.setWriteDownDate(dpWriteDown.getValue());
        currentAsset.setStartTaxDepnDate(dpStartTax.getValue());
        if (!currentAsset.isLeased() && !currentAsset.isPooled()) {
            currentAsset.setTaxDepnMethod(fTaxMethod.getText().toUpperCase());
            currentAsset.setTaxDepnCode(fTaxCode.getText());
            currentAsset.setTaxRate1(parseDec(fTaxRate1.getText()));
            currentAsset.setTaxRate2(parseDec(fTaxRate2.getText()));
            currentAsset.setTaxDepnFreq(parseInt(fTaxFreq.getText()));
            currentAsset.setBookDepnMethod(fBookMethod.getText().toUpperCase());
            currentAsset.setBookDepnCode(fBookCode.getText());
            currentAsset.setBookRate1(parseDec(fBookRate1.getText()));
            currentAsset.setBookRate2(parseDec(fBookRate2.getText()));
            currentAsset.setBookDepnFreq(parseInt(fBookFreq.getText()));
        }
        if (rdoPostCL.isSelected())        currentAsset.setPostDepnToClBa("C");
        else if (rdoPostBA.isSelected())   currentAsset.setPostDepnToClBa("B");
        else                               currentAsset.setPostDepnToClBa("N");
    }

    // ── Tab 4: Bar Codes (P5) ─────────────────────────────────────────

    private Tab buildBarCodesTab() {
        ObservableList<AssetBarCode> codes = FXCollections.observableArrayList(
            service.getBarCodes(companyNo, currentAsset.getAssetNo()));

        TableView<AssetBarCode> table = new TableView<>(codes);
        TableColumn<AssetBarCode, String> col = new TableColumn<>("Bar Code");
        col.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().barCode()));
        col.setPrefWidth(320);
        table.getColumns().add(col);
        VBox.setVgrow(table, Priority.ALWAYS);

        Button btnAdd    = btn("Add",    "#1F4E79", "#FFFFFF");
        Button btnDelete = btn("Delete", "#EF4444", "#FFFFFF");

        btnAdd.setOnAction(e -> {
            TextInputDialog input = new TextInputDialog();
            input.setTitle("Add Bar Code");
            input.setHeaderText("Enter new bar code for " + currentAsset.getAssetNo() + ":");
            input.setContentText("Bar code:");
            input.showAndWait().ifPresent(code -> {
                if (code.isBlank()) return;
                String err = service.addBarCode(companyNo, currentAsset.getAssetNo(), code.trim());
                if (err != null) { showError(err); return; }
                codes.setAll(service.getBarCodes(companyNo, currentAsset.getAssetNo()));
            });
        });

        btnDelete.setOnAction(e -> {
            AssetBarCode sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) { showInfo("No Selection", "Select a bar code to delete."); return; }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete bar code " + sel.barCode() + "?", ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.YES) {
                    service.deleteBarCode(companyNo, currentAsset.getAssetNo(), sel.barCode());
                    codes.setAll(service.getBarCodes(companyNo, currentAsset.getAssetNo()));
                }
            });
        });

        HBox buttons = new HBox(8, btnAdd, btnDelete);
        buttons.setPadding(new Insets(8, 0, 0, 0));

        VBox content = new VBox(8, table, buttons);
        content.setPadding(new Insets(12));
        Tab tab = new Tab("Bar Codes"); tab.setContent(content); return tab;
    }

    // ══════════════════════════════════════════════════════════════════
    // S2 — Change Asset Status
    // ══════════════════════════════════════════════════════════════════

    private void openStatusScreen(Stage owner) {
        AssetListRow sel = getSelectedRow();
        if (sel == null) return;

        executor.submit(() -> {
            try {
                var assetOpt = service.loadAsset(companyNo, sel.assetNo());
                Platform.runLater(() -> {
                    if (assetOpt.isEmpty()) { showError("Asset not found."); return; }
                    currentAsset = assetOpt.get();
                    currentBeforeSnapshot = service.snapshot(currentAsset);

                Stage dlg = new Stage();
                dlg.initOwner(owner);
                dlg.initModality(Modality.WINDOW_MODAL);
                dlg.setTitle("Change Asset Status");

                // Display fields (all read-only on S2 except new status + reference)
                GridPane grid = new GridPane();
                grid.setHgap(10); grid.setVgap(8);
                grid.setPadding(new Insets(14));
                int row = 0;
                addRow(grid, row++, "Asset no",      displayLabel(currentAsset.getAssetNo()));
                addRow(grid, row++, "Description",   displayLabel(currentAsset.getDesc1()));
                addRow(grid, row++, "Location",      displayLabel(currentAsset.getLocCode() + "  " + currentAsset.getLocDesc()));
                addRow(grid, row++, "Department",    displayLabel(currentAsset.getDeptCode() + "  " + currentAsset.getDeptDesc()));
                addRow(grid, row++, "Group",         displayLabel(currentAsset.getGrpCode() + "  " + currentAsset.getGrpDesc()));
                addRow(grid, row++, "Sub-group",     displayLabel(currentAsset.getSubgrpCode() + "  " + currentAsset.getSubgrpDesc()));
                addRow(grid, row++, "Current status", displayLabel(currentAsset.statusDisplayDesc()));

                // New status: A = active, H = on hold, N = not in use (R = retired is not done here)
                ToggleGroup newStatusGroup = new ToggleGroup();
                RadioButton rdoA = new RadioButton("A — Active");
                RadioButton rdoH = new RadioButton("H — On Hold");
                RadioButton rdoN = new RadioButton("N — Not In Use");
                rdoA.setToggleGroup(newStatusGroup);
                rdoH.setToggleGroup(newStatusGroup);
                rdoN.setToggleGroup(newStatusGroup);
                rdoA.setSelected(true); // default

                VBox statusBox = new VBox(6, rdoA, rdoH, rdoN);
                addRow(grid, row++, "New status", statusBox);

                TextField fRef = tf(currentAsset.getAssetStatusRef(), 40);
                addRow(grid, row++, "Reference", fRef);

                Button btnOk   = btn("OK",   "#1F4E79", "#FFFFFF");
                Button btnExit = btn("Exit",  "#64748B", "#FFFFFF");

                btnExit.setOnAction(e -> dlg.close());
                btnOk.setOnAction(e -> {
                    String newStatus = rdoA.isSelected() ? "A" : rdoH.isSelected() ? "H" : "N";
                    String currentStatus = currentAsset.getAssetStatus().isBlank() ? "A"
                        : currentAsset.getAssetStatus();

                    if (newStatus.equals(currentStatus)) {
                        showInfo("No Change", "No change to status.");
                        return;
                    }
                    String ref = fRef.getText();
                    executor.submit(() -> {
                        service.saveStatusChange(currentAsset, newStatus, ref, currentBeforeSnapshot);
                        Platform.runLater(() -> {
                            dlg.close();
                            loadAssets();
                            setStatus("Status updated for " + currentAsset.getAssetNo(), "success");
                        });
                    });
                });

                HBox buttons = new HBox(8, btnOk, btnExit);
                buttons.setAlignment(Pos.CENTER_RIGHT);
                buttons.setPadding(new Insets(8, 12, 8, 12));
                buttons.setStyle("-fx-border-color: #E2E8F0; -fx-border-width: 1 0 0 0;");

                dlg.setScene(new Scene(new VBox(grid, buttons), 520, 420));
                dlg.showAndWait();
            });
            } catch (Exception ex) {
                Platform.runLater(() -> showError("Failed to load asset:\n" + ex.getMessage()));
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // S3 — Bulk Depreciation Change
    // ══════════════════════════════════════════════════════════════════

    private void openDepnChangeScreen(Stage owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Change Depreciation Details");

        // Parameters matching COBOL S3
        TextField fStartAsset = tf("", 20);
        TextField fEndAsset   = tf("", 20);
        TextField fLoc        = tf("", 6);
        TextField fDept       = tf("", 6);
        TextField fGrp        = tf("", 6);
        TextField fSubGrp     = tf("", 6);

        // Change flags (Y/N)
        CheckBox chkTaxMethod  = new CheckBox("Change tax depn method");
        CheckBox chkTaxCode    = new CheckBox("Change tax depn code");
        CheckBox chkTaxFreq    = new CheckBox("Change tax depn frequency");
        CheckBox chkBookMethod = new CheckBox("Change book depn method");
        CheckBox chkBookCode   = new CheckBox("Change book depn code");
        CheckBox chkBookFreq   = new CheckBox("Change book depn frequency");
        CheckBox chkWriteDown  = new CheckBox("Change write-down date");

        // From/To fields (enabled only when checkbox ticked)
        TextField fFromTaxMethod  = tf("", 1); TextField fToTaxMethod  = tf("", 1);
        TextField fFromTaxCode    = tf("", 6); TextField fToTaxCode    = tf("", 6);
        TextField fFromTaxFreq    = tf("", 2); TextField fToTaxFreq    = tf("", 2);
        TextField fFromBookMethod = tf("", 1); TextField fToBookMethod = tf("", 1);
        TextField fFromBookCode   = tf("", 6); TextField fToBookCode   = tf("", 6);
        TextField fFromBookFreq   = tf("", 2); TextField fToBookFreq   = tf("", 2);
        DatePicker dpFromWD = new DatePicker(); DatePicker dpToWD = new DatePicker();

        // Wire checkboxes to enable/disable from-to fields
        wireCheckToFields(chkTaxMethod,  fFromTaxMethod, fToTaxMethod);
        wireCheckToFields(chkTaxCode,    fFromTaxCode,   fToTaxCode);
        wireCheckToFields(chkTaxFreq,    fFromTaxFreq,   fToTaxFreq);
        wireCheckToFields(chkBookMethod, fFromBookMethod, fToBookMethod);
        wireCheckToFields(chkBookCode,   fFromBookCode,  fToBookCode);
        wireCheckToFields(chkBookFreq,   fFromBookFreq,  fToBookFreq);

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(6);
        grid.setPadding(new Insets(12));
        int row = 0;
        addSectionHeader(grid, row++, "Asset selection range:");
        addRow(grid, row++, "Start asset no",  fStartAsset);
        addRow(grid, row++, "End asset no",    fEndAsset);
        addRow(grid, row++, "Location",        fLoc);
        addRow(grid, row++, "Department",      fDept);
        addRow(grid, row++, "Group",           fGrp);
        addRow(grid, row++, "Sub-group",       fSubGrp);
        addSectionHeader(grid, row++, "Changes to make:  (tick, enter From → To values)");

        addRow(grid, row++, "", chkTaxMethod);
        addRow(grid, row++, "  Tax method  From → To", buildRow(fFromTaxMethod, new Label("→"), fToTaxMethod));
        addRow(grid, row++, "", chkTaxCode);
        addRow(grid, row++, "  Tax code  From → To", buildRow(fFromTaxCode, new Label("→"), fToTaxCode));
        addRow(grid, row++, "", chkTaxFreq);
        addRow(grid, row++, "  Tax freq  From → To", buildRow(fFromTaxFreq, new Label("→"), fToTaxFreq));
        addRow(grid, row++, "", chkBookMethod);
        addRow(grid, row++, "  Book method  From → To", buildRow(fFromBookMethod, new Label("→"), fToBookMethod));
        addRow(grid, row++, "", chkBookCode);
        addRow(grid, row++, "  Book code  From → To", buildRow(fFromBookCode, new Label("→"), fToBookCode));
        addRow(grid, row++, "", chkBookFreq);
        addRow(grid, row++, "  Book freq  From → To", buildRow(fFromBookFreq, new Label("→"), fToBookFreq));
        addRow(grid, row++, "", chkWriteDown);
        addRow(grid, row++, "  Write-down  From → To", buildRow(dpFromWD, new Label("→"), dpToWD));

        Button btnOk   = btn("OK",   "#1F4E79", "#FFFFFF");
        Button btnExit = btn("Exit", "#64748B", "#FFFFFF");
        btnExit.setOnAction(e -> dlg.close());
        btnOk.setOnAction(e -> {
            boolean anyChange = chkTaxMethod.isSelected() || chkTaxCode.isSelected()
                || chkTaxFreq.isSelected() || chkBookMethod.isSelected()
                || chkBookCode.isSelected() || chkBookFreq.isSelected()
                || chkWriteDown.isSelected();
            if (!anyChange) {
                showInfo("No Changes", "Please select at least one field to change.");
                return;
            }
            dlg.close();
            showInfo("Depreciation Change",
                "Depreciation changes have been logged.\n\n" +
                "In the original system, this calls FAAS04 (a batch program)\n" +
                "to process the changes. Implement FAAS04 as a separate background\n" +
                "service when ready.\n\n" +
                "Pooled assets and assets with unposted depn transactions will be skipped.");
            loadAssets();
        });

        HBox buttons = new HBox(8, btnOk, btnExit);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(8, 12, 8, 12));
        buttons.setStyle("-fx-border-color: #E2E8F0; -fx-border-width: 1 0 0 0;");

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        dlg.setScene(new Scene(new VBox(scroll, buttons), 600, 680));
        dlg.showAndWait();
    }

    // ══════════════════════════════════════════════════════════════════
    // P5 — Bar Code Listbox
    // ══════════════════════════════════════════════════════════════════


    // ══════════════════════════════════════════════════════════════════
    // Layout helpers
    // ══════════════════════════════════════════════════════════════════

    private void addRow(GridPane g, int row, String label, javafx.scene.Node control) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #374151;");
        lbl.setMinWidth(160);
        g.add(lbl, 0, row);
        g.add(control, 1, row);
    }

    private void addSectionHeader(GridPane g, int row, String text) {
        Label h = new Label(text);
        h.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #1F4E79;" +
                   "-fx-padding: 6 0 2 0;");
        g.add(h, 0, row, 2, 1);
    }

    private HBox buildRow(javafx.scene.Node... nodes) {
        HBox box = new HBox(6, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Look up desc1 from any codes table by code value and company */
    private String lookupCodeDesc(String table, String codeCol, String code) {
        if (code == null || code.isBlank()) return "";
        try {
            return jdbc.queryForObject(
                "SELECT desc1 FROM " + table + " WHERE " + codeCol + " = ? AND company_no = ?",
                String.class, code, companyNo);
        } catch (Exception e) { return ""; }
    }

    /** Look up depreciation code description from FACODDN */
    private String lookupDepnCodeDesc(String code) {
        if (code == null || code.isBlank()) return "";
        try {
            return jdbc.queryForObject(
                "SELECT desc1 FROM FACODDN WHERE depn_code = ? AND company_no = ?",
                String.class, code, companyNo);
        } catch (Exception e) { return ""; }
    }

    private HBox buildLookupRow(TextField field, Label descLabel,
                                 LookupDialog.LookupType type, Runnable onSelect) {
        Button btn = new Button("...");
        btn.setStyle("-fx-background-color: #EBF2FA; -fx-border-color: #AABDD0;" +
                     "-fx-border-radius: 4; -fx-background-radius: 4;" +
                     "-fx-padding: 4 8; -fx-font-size: 11px; -fx-cursor: hand;");
        btn.setOnAction(e ->
            new LookupDialog(jdbc, type, companyNo, code -> {
                field.setText(code.trim());
                onSelect.run();
            }).show(field.getScene().getWindow()));
        return buildRow(field, btn, descLabel);
    }

    private VBox buildParentSection(boolean visible,
                                     TextField fInd, TextField fRate, TextField fMaths) {
        if (!visible) return new VBox();
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(6);
        addSectionHeader(g, 0, "Parent accounting (overseas):");
        addRow(g, 1, "Use current or purchase rate (C/P)", fInd);
        addRow(g, 2, "Parent exchange rate at purchase",   fRate);
        addRow(g, 3, "Divide or multiply (D/M)",           fMaths);
        return new VBox(g);
    }

    private void wireCheckToFields(CheckBox cb, javafx.scene.Node... fields) {
        for (var f : fields) f.setDisable(true);
        cb.selectedProperty().addListener((o, old, v) -> {
            for (var f : fields) f.setDisable(!v);
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // Field factory helpers
    // ══════════════════════════════════════════════════════════════════

    private TextField field(String val, int maxChars) {
        TextField tf = new TextField(val == null ? "" : val.trim());
        tf.setPrefWidth(maxChars * 9 + 10);
        return tf;
    }

    private TextField tf(String val, int maxChars) { return field(val, maxChars); }

    private Label displayLabel(String val) {
        Label l = new Label(val == null ? "" : val.trim());
        l.setStyle("-fx-text-fill: #374151; -fx-font-size: 12px;");
        return l;
    }

    private DatePicker datePicker(LocalDate val) {
        DatePicker dp = new DatePicker(val);
        dp.setPrefWidth(140);
        return dp;
    }

    private Button btn(String text, String bg, String fg) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: " + fg + ";" +
                   "-fx-font-weight: bold; -fx-padding: 5 14 5 14;" +
                   "-fx-background-radius: 4; -fx-cursor: hand;");
        return b;
    }

    // ── Value conversion helpers ───────────────────────────────────────

    private String fmtDate(LocalDate d) {
        if (d == null || d.getYear() < 1900) return "";
        return d.format(DATE_FMT);
    }

    private String fmtDec(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return v.toPlainString();
    }

    private BigDecimal parseDec(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.trim().replace(",", "")); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private int parseInt(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    // ── Messaging ─────────────────────────────────────────────────────

    private void showInfo(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle(title); a.setHeaderText(null);
        a.showAndWait();
    }

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle("Error"); a.setHeaderText(null);
        a.showAndWait();
    }

    private void setStatus(String msg, String type) {
        String colour = "success".equals(type) ? "#16A34A"
            : "error".equals(type) ? "#DC2626" : "#64748B";
        lblStatus.setText(msg);
        lblStatus.setStyle("-fx-text-fill: " + colour + "; -fx-font-size: 11px;");
    }
}
