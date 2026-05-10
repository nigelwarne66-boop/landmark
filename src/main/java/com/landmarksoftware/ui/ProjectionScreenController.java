package com.landmarksoftware.ui;

import com.landmarksoftware.model.ProjectionRequest;
import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.CodeLookupService;
import com.landmarksoftware.repository.CompanyRepository;
import com.landmarksoftware.repository.GlDateRepository;
import com.landmarksoftware.service.DepreciationProjectionService;
import com.landmarksoftware.export.DepreciationExportService;
import com.landmarksoftware.export.DepreciationPdfService;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FATL12S0 parameter screen — JavaFX implementation.
 *
 * Replicates all 13 fields from the COBOL parameter screen:
 *   - Start / End Asset Number
 *   - Start / End Location
 *   - Start / End Group
 *   - Start / End Sub-Group
 *   - Start / End Department
 *   - Book or Tax depreciation  (radio buttons)
 *   - Projected-to date         (date picker, validated against GL period-ends)
 *   - Projected rate            (0 = use master file)
 *
 * Plus Java-specific additions:
 *   - Company selector          (multi-company)
 *   - Output folder chooser
 *   - Progress / status bar
 *   - Run / Clear buttons
 */
@Component
public class ProjectionScreenController {

    private static final DateTimeFormatter DT = DateTimeFormatter.ISO_LOCAL_DATE;

    // ── Spring services ───────────────────────────────────────────────
    private final DepreciationProjectionService projectionService;
    private final DepreciationExportService     exportService;
    private final DepreciationPdfService        pdfService;
    private final CompanyRepository             companyRepo;
    private final GlDateRepository              glDateRepo;
    private final CodeLookupService lookupService;
    private final JdbcTemplate                  jdbc;
    private final AppSession              appSession;

    // ── Form controls (kept as fields for clear/validate/run access) ──
    private ComboBox<CompanyItem> cboCompany;
    private TextField  txtStartAsset,  txtEndAsset;
    private TextField  txtStartLoc,    txtEndLoc;
    private TextField  txtStartGrp,    txtEndGrp;
    private TextField  txtStartSubgrp, txtEndSubgrp;
    private TextField  txtStartDept,   txtEndDept;
    private RadioButton rdoBook, rdoTax;
    private DatePicker  dpProjDate;
    private TextField   txtProjRate;
    private TextField   txtOutDir;
    private RadioButton rdoPdf, rdoExcel;
    private Label       lblStatus;
    private ProgressBar progressBar;
    private Button      btnRun;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "projection-thread");
        t.setDaemon(true);
        return t;
    });

    public ProjectionScreenController(
            DepreciationProjectionService projectionService,
            DepreciationExportService     exportService,
            DepreciationPdfService        pdfService,
            CompanyRepository             companyRepo,
            GlDateRepository              glDateRepo,
            CodeLookupService             lookupService,
            JdbcTemplate                  jdbc,
                               AppSession appSession) {
        this.projectionService = projectionService;
        this.exportService     = exportService;
        this.pdfService        = pdfService;
        this.companyRepo       = companyRepo;
        this.glDateRepo        = glDateRepo;
        this.lookupService = lookupService;
        this.jdbc              = jdbc;
        this.appSession = appSession;
    }

    // ────────────────────────────────────────────────────────────────────
    // Build scene
    // ────────────────────────────────────────────────────────────────────

    public Scene buildScene(Stage owner) {
        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setCenter(buildForm());
        root.setBottom(buildFooter(owner));

        Scene scene = new Scene(root, 700, 640);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());

        // Load companies after scene is shown
        Platform.runLater(this::loadCompanies);
        return scene;
    }

    // ── Header ────────────────────────────────────────────────────────

    private VBox buildHeader() {
        Label title    = new Label("Projected Depreciation Extract");
        title.getStyleClass().add("header-title");
        Label subtitle = new Label("FATL12 / FATL13  —  Select parameters then click Run");
        subtitle.getStyleClass().add("header-subtitle");
        VBox hdr = new VBox(2, title, subtitle);
        hdr.getStyleClass().add("header-bar");
        return hdr;
    }

    // ── Main form ─────────────────────────────────────────────────────

    private ScrollPane buildForm() {
        VBox form = new VBox(16);
        form.setPadding(new Insets(20));

        form.getChildren().addAll(
            buildCompanyCard(),
            buildRangeCard(),
            buildOptionsCard(),
            buildOutputCard()
        );

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: #f0f4f8;");
        return scroll;
    }

    // ── Card: Company ─────────────────────────────────────────────────

    private VBox buildCompanyCard() {
        cboCompany = new ComboBox<>();
        cboCompany.setMaxWidth(Double.MAX_VALUE);
        cboCompany.setPromptText("Loading companies...");

        return card("Company", formRow("Company", cboCompany));
    }

    // ── Card: Selection ranges ────────────────────────────────────────

    private VBox buildRangeCard() {
        txtStartAsset  = new TextField();  txtEndAsset  = new TextField("~~~~~~~~~~~~~~~~~~~~");
        txtStartLoc    = new TextField();  txtEndLoc    = new TextField("~~~~~~");
        txtStartGrp    = new TextField();  txtEndGrp    = new TextField("~~~~~~");
        txtStartSubgrp = new TextField();  txtEndSubgrp = new TextField("~~~~~~");
        txtStartDept   = new TextField();  txtEndDept   = new TextField("~~~~~~");

        // Hints for the user
        txtStartAsset.setPromptText("First  (blank = all)");
        txtEndAsset.setPromptText("Last   (~~~~ = all)");
        txtStartLoc.setPromptText("blank = all");
        txtEndLoc.setPromptText("~~~~ = all");

        VBox card = card("Selection Ranges",
            rangeRowWithLookup("Asset Number",  txtStartAsset,  txtEndAsset,  LookupDialog.LookupType.ASSET),
            rangeRowWithLookup("Location",      txtStartLoc,    txtEndLoc,    LookupDialog.LookupType.LOCATION),
            rangeRowWithLookup("Group",         txtStartGrp,    txtEndGrp,    LookupDialog.LookupType.GROUP),
            rangeRowWithLookup("Sub-Group",     txtStartSubgrp, txtEndSubgrp, LookupDialog.LookupType.SUBGROUP),
            rangeRowWithLookup("Department",    txtStartDept,   txtEndDept,   LookupDialog.LookupType.DEPARTMENT)
        );
        return card;
    }

    // ── Card: Depreciation options ────────────────────────────────────

    private VBox buildOptionsCard() {
        ToggleGroup tg = new ToggleGroup();
        rdoBook = new RadioButton("Book depreciation");
        rdoTax  = new RadioButton("Tax depreciation");
        rdoBook.setToggleGroup(tg);
        rdoTax.setToggleGroup(tg);
        rdoBook.setSelected(true);
        rdoBook.getStyleClass().add("radio-button");
        rdoTax.getStyleClass().add("radio-button");
        HBox radioRow = new HBox(20, rdoBook, rdoTax);
        radioRow.setAlignment(Pos.CENTER_LEFT);

        // Projected-to date - restrict to valid GL period-end dates
        dpProjDate = new DatePicker();
        dpProjDate.setMaxWidth(Double.MAX_VALUE);
        dpProjDate.setPromptText("Select a GL period-end date");
        dpProjDate.setConverter(new javafx.util.StringConverter<LocalDate>() {
            final java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
            @Override public String toString(LocalDate d) {
                return d == null ? "" : d.format(fmt);
            }
            @Override public LocalDate fromString(String s) {
                if (s == null || s.isBlank()) return null;
                try { return LocalDate.parse(s, fmt); } catch (Exception e) { return null; }
            }
        });
        // Load valid period-end dates when company changes
        cboCompany.valueProperty().addListener((obs, o, n) -> {
            if (n != null) loadPeriodEndDates(n.companyNo());
        });

        // Projected rate
        txtProjRate = new TextField("0");
        txtProjRate.setMaxWidth(120);
        txtProjRate.setPromptText("0 = use master file rate");
        Label rateHint = new Label("  (0.00 = use asset master file rate)");
        rateHint.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
        HBox rateRow = new HBox(4, txtProjRate, rateHint);
        rateRow.setAlignment(Pos.CENTER_LEFT);

        return card("Depreciation Options",
            formRow("Book or Tax",          radioRow),
            formRow("Projected-to Date",    dpProjDate),
            formRow("Projected Rate (%)",   rateRow)
        );
    }

    // ── Card: Output ──────────────────────────────────────────────────

    private VBox buildOutputCard() {

        // Output format - PDF (default) or Excel
        ToggleGroup fmtGroup = new ToggleGroup();
        rdoPdf   = new RadioButton("PDF Report  (default)");
        rdoExcel = new RadioButton("Excel Spreadsheet");
        rdoPdf.setToggleGroup(fmtGroup);
        rdoExcel.setToggleGroup(fmtGroup);
        rdoPdf.setSelected(true);
        rdoPdf.getStyleClass().add("radio-button");
        rdoExcel.getStyleClass().add("radio-button");
        HBox fmtRow = new HBox(20, rdoPdf, rdoExcel);
        fmtRow.setAlignment(Pos.CENTER_LEFT);

        // Output folder
        txtOutDir = new TextField(System.getProperty("user.home") + File.separator + "FA_Exports");
        HBox.setHgrow(txtOutDir, Priority.ALWAYS);

        Button btnBrowse = new Button("Browse…");
        btnBrowse.getStyleClass().add("btn-secondary");
        btnBrowse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Choose output folder");
            File chosen = dc.showDialog(txtOutDir.getScene().getWindow());
            if (chosen != null) txtOutDir.setText(chosen.getAbsolutePath());
        });

        HBox outRow = new HBox(8, txtOutDir, btnBrowse);
        outRow.setAlignment(Pos.CENTER_LEFT);

        return card("Output",
            formRow("Output Format", fmtRow),
            formRow("Export Folder", outRow));
    }

    // ── Footer: buttons + status ──────────────────────────────────────

    private VBox buildFooter(Stage owner) {
        btnRun = new Button("▶  Run Projection");
        btnRun.getStyleClass().add("btn-primary");
        btnRun.setOnAction(e -> runProjection(owner));

        Button btnClear = new Button("Clear");
        btnClear.getStyleClass().add("btn-secondary");
        btnClear.setOnAction(e -> clearForm());

        Button btnClose = new Button("Close");
        btnClose.getStyleClass().add("btn-secondary");
        btnClose.setOnAction(e -> owner.close());

        HBox buttons = new HBox(10, btnRun, btnClear, btnClose);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(10, 20, 10, 20));

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.getStyleClass().add("progress-bar");

        lblStatus = new Label("Ready");
        lblStatus.getStyleClass().add("status-text");

        HBox statusRow = new HBox(8, progressBar, lblStatus);
        statusRow.getStyleClass().add("status-bar");
        statusRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        return new VBox(0, new Separator(), buttons, statusRow);
    }

    // ────────────────────────────────────────────────────────────────────
    // Actions
    // ────────────────────────────────────────────────────────────────────

    /** Populate company combo from cp_company table. */
    private void loadCompanies() {
        executor.submit(() -> {
            try {
                var items = FXCollections.<CompanyItem>observableArrayList();
                companyRepo.findAll().forEach(c ->
                    items.add(new CompanyItem(c.getCompanyNo(), c.getName())));
                Platform.runLater(() -> {
                    cboCompany.setItems(items);
                    // Pre-select session company from AppSession (GLPASS equivalent)
                    items.stream()
                        .filter(c -> c.companyNo() == appSession.getCompanyNo())
                        .findFirst()
                        .ifPresentOrElse(c -> {
                            cboCompany.getSelectionModel().select(c);
                            loadPeriodEndDates(c.companyNo());
                        }, () -> {
                            if (!items.isEmpty()) {
                                cboCompany.getSelectionModel().selectFirst();
                                loadPeriodEndDates(items.get(0).companyNo());
                            }
                        });
                    cboCompany.setPromptText(items.isEmpty() ? "No companies found" : null);
                });
            } catch (Exception ex) {
                Platform.runLater(() ->
                    setStatus("Could not load companies: " + ex.getMessage(), "error"));
            }
        });
    }

    /** Validate and run the projection on a background thread. */
    private void runProjection(Stage owner) {
        // ── Validate ──────────────────────────────────────────────────
        if (cboCompany.getValue() == null) {
            setStatus("Please select a company.", "error"); return;
        }
        if (dpProjDate.getValue() == null) {
            setStatus("Please enter the projected-to date.", "error"); return;
        }
        BigDecimal rate;
        try {
            rate = new BigDecimal(txtProjRate.getText().trim());
        } catch (NumberFormatException e) {
            setStatus("Invalid projected rate — enter a number (e.g. 0 or 10.00).", "error"); return;
        }

        // ── Build request ─────────────────────────────────────────────
        ProjectionRequest req = new ProjectionRequest();
        req.setCompanyNo(cboCompany.getValue().companyNo());
        req.setProjectedToDate(dpProjDate.getValue());
        req.setTaxOrBook(rdoTax.isSelected() ? 'T' : 'B');
        req.setProjectedRate(rate);
        req.setStartAssetNo(blankToMin(txtStartAsset.getText()));
        req.setEndAssetNo(blankToMax(txtEndAsset.getText(), 20));
        req.setStartLocation(blankToMin(txtStartLoc.getText()));
        req.setEndLocation(blankToMax(txtEndLoc.getText(), 6));
        req.setStartGroup(blankToMin(txtStartGrp.getText()));
        req.setEndGroup(blankToMax(txtEndGrp.getText(), 6));
        req.setStartSubGroup(blankToMin(txtStartSubgrp.getText()));
        req.setEndSubGroup(blankToMax(txtEndSubgrp.getText(), 6));
        req.setStartDept(blankToMin(txtStartDept.getText()));
        req.setEndDept(blankToMax(txtEndDept.getText(), 6));

        String outDir = txtOutDir.getText().trim().isEmpty()
            ? System.getProperty("user.home") : txtOutDir.getText().trim();

        // ── Run on background thread ───────────────────────────────────
        btnRun.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        setStatus("Running projection…", "running");

        executor.submit(() -> {
            try {
                var output = projectionService.project(req);
                int assets = output.results().size();
                int cols   = output.header().columnCount();

                boolean usePdf = rdoPdf.isSelected();
                String ext = usePdf ? "pdf" : "xlsx";
                String filename = String.format("FA_DepnProjection_%d_%s_%s.%s",
                    req.getCompanyNo(),
                    rdoTax.isSelected() ? "Tax" : "Book",
                    req.getProjectedToDate().toString().replace("-",""),
                    ext);
                Path outPath = Paths.get(outDir, filename);
                java.nio.file.Files.createDirectories(Paths.get(outDir));
                if (usePdf) {
                    pdfService.export(output, outPath);
                } else {
                    exportService.export(output, outPath);
                }

                Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    setStatus(String.format(
                        "✓  Done — %d assets, %d periods. Saved: %s",
                        assets, cols, outPath.getFileName()), "success");
                    btnRun.setDisable(false);
                    progressBar.setVisible(false);
                });

            } catch (IllegalArgumentException e) {
                Platform.runLater(() -> {
                    progressBar.setVisible(false);
                    btnRun.setDisable(false);
                    setStatus("Error: " + e.getMessage(), "error");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.initOwner(owner);
                    alert.setTitle("Projection Error");
                    alert.setHeaderText("Invalid parameters");
                    alert.setContentText(e.getMessage()
                        + "\n\nHint: the projected-to date must match a GL period-end date.");
                    alert.showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    progressBar.setVisible(false);
                    btnRun.setDisable(false);
                    setStatus("Unexpected error: " + e.getMessage(), "error");
                });
            }
        });
    }

    /** Loads valid GL period-end dates for the company and sets the default. */
    private void loadPeriodEndDates(int companyNo) {
        executor.submit(() -> {
            try {
                // Query all non-null, non-1899 period-end dates for this company
                String sql = """
                    SELECT DISTINCT pe FROM (
                      SELECT period_end_01 pe FROM GLDATES WHERE company_no = ? AND period_end_01 > '1900-01-01'
                      UNION SELECT period_end_02 FROM GLDATES WHERE company_no = ? AND period_end_02 > '1900-01-01'
                      UNION SELECT period_end_03 FROM GLDATES WHERE company_no = ? AND period_end_03 > '1900-01-01'
                      UNION SELECT period_end_04 FROM GLDATES WHERE company_no = ? AND period_end_04 > '1900-01-01'
                      UNION SELECT period_end_05 FROM GLDATES WHERE company_no = ? AND period_end_05 > '1900-01-01'
                      UNION SELECT period_end_06 FROM GLDATES WHERE company_no = ? AND period_end_06 > '1900-01-01'
                      UNION SELECT period_end_07 FROM GLDATES WHERE company_no = ? AND period_end_07 > '1900-01-01'
                      UNION SELECT period_end_08 FROM GLDATES WHERE company_no = ? AND period_end_08 > '1900-01-01'
                      UNION SELECT period_end_09 FROM GLDATES WHERE company_no = ? AND period_end_09 > '1900-01-01'
                      UNION SELECT period_end_10 FROM GLDATES WHERE company_no = ? AND period_end_10 > '1900-01-01'
                      UNION SELECT period_end_11 FROM GLDATES WHERE company_no = ? AND period_end_11 > '1900-01-01'
                      UNION SELECT period_end_12 FROM GLDATES WHERE company_no = ? AND period_end_12 > '1900-01-01'
                      UNION SELECT period_end_13 FROM GLDATES WHERE company_no = ? AND period_end_13 > '1900-01-01'
                    ) dates ORDER BY pe
                    """;
                // 13 period columns x 1 param each
                Object[] params = new Object[13];
                java.util.Arrays.fill(params, companyNo);
                java.util.List<java.sql.Date> dates = jdbc.queryForList(sql, java.sql.Date.class, params);
                java.util.List<LocalDate> periodEnds = dates.stream()
                    .map(java.sql.Date::toLocalDate)
                    .filter(d -> d.getYear() > 1900)
                    .sorted()
                    .toList();

                // Find the most recent period-end on or before today
                LocalDate today = LocalDate.now();
                LocalDate defaultDate = periodEnds.stream()
                    .filter(d -> !d.isAfter(today))
                    .reduce((a, b) -> b)  // last element
                    .orElse(periodEnds.isEmpty() ? today : periodEnds.get(0));

                // Build a set for fast lookup in the day cell factory
                java.util.Set<LocalDate> validDates = new java.util.HashSet<>(periodEnds);
                LocalDate finalDefault = defaultDate;

                javafx.application.Platform.runLater(() -> {
                    dpProjDate.setValue(finalDefault);
                    // Disable days that are not valid period-end dates
                    dpProjDate.setDayCellFactory(picker -> new javafx.scene.control.DateCell() {
                        @Override public void updateItem(LocalDate item, boolean empty) {
                            super.updateItem(item, empty);
                            if (!validDates.contains(item)) {
                                setDisable(true);
                                setStyle("-fx-background-color: #F5F5F5; -fx-text-fill: #CCCCCC;");
                            } else {
                                setStyle("-fx-background-color: #EBF5FB; -fx-font-weight: bold;");
                            }
                        }
                    });
                    setStatus("Ready  \u2014  " + periodEnds.size() + " valid period-end dates loaded", "normal");
                });
            } catch (Exception ex) {
                javafx.application.Platform.runLater(() ->
                    setStatus("Could not load period-end dates: " + ex.getMessage(), "error"));
            }
        });
    }

    /** Resets all form fields to defaults. */
    private void clearForm() {
        if (!cboCompany.getItems().isEmpty())
            cboCompany.getSelectionModel().selectFirst();
        txtStartAsset.clear();  txtEndAsset.setText("~~~~~~~~~~~~~~~~~~~~");
        txtStartLoc.clear();    txtEndLoc.setText("~~~~~~");
        txtStartGrp.clear();    txtEndGrp.setText("~~~~~~");
        txtStartSubgrp.clear(); txtEndSubgrp.setText("~~~~~~");
        txtStartDept.clear();   txtEndDept.setText("~~~~~~");
        rdoBook.setSelected(true);
        rdoPdf.setSelected(true);
        txtProjRate.setText("0");
        if (cboCompany.getValue() != null)
            loadPeriodEndDates(cboCompany.getValue().companyNo());
        progressBar.setVisible(false);
        setStatus("Ready", "normal");
    }

    // ────────────────────────────────────────────────────────────────────
    // Layout helpers
    // ────────────────────────────────────────────────────────────────────

    private VBox card(String title, javafx.scene.Node... rows) {
        Label lbl = new Label(title);
        lbl.getStyleClass().add("section-label");
        VBox box = new VBox(10);
        box.getChildren().add(lbl);
        box.getChildren().addAll(rows);
        box.getStyleClass().add("card");
        return box;
    }

    private HBox formRow(String labelText, javafx.scene.Node field) {
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("form-label");
        lbl.setMinWidth(170);
        if (field instanceof Region r) HBox.setHgrow(r, Priority.ALWAYS);
        HBox row = new HBox(10, lbl, field);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }


    private HBox rangeRowWithLookup(String labelText, TextField start, TextField end,
                                     LookupDialog.LookupType lookupType) {
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("form-label");
        lbl.setMinWidth(170);
        Button btnStart = lookupButton(start, lookupType);
        Button btnEnd   = lookupButton(end,   lookupType);
        Label to = new Label("to");
        to.setStyle("-fx-text-fill: #888888; -fx-font-size: 12px;");
        to.setMinWidth(20);
        to.setAlignment(Pos.CENTER);
        HBox.setHgrow(start, Priority.ALWAYS);
        HBox.setHgrow(end,   Priority.ALWAYS);
        HBox row = new HBox(6, lbl, start, btnStart, to, end, btnEnd);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button lookupButton(TextField field, LookupDialog.LookupType type) {
        Button btn = new Button("...");
        btn.setStyle(
            "-fx-background-color: #EBF2FA; -fx-border-color: #AABDD0;" +
            "-fx-border-radius: 4; -fx-background-radius: 4;" +
            "-fx-padding: 4 8 4 8; -fx-font-size: 11px; -fx-cursor: hand;");
        btn.setTooltip(new Tooltip("Search " + type.label + "s"));
        btn.setOnAction(e -> {
            CompanyItem company = cboCompany.getValue();
            int cno = company != null ? company.companyNo() : 1;
            new LookupDialog(lookupService, type, cno, code -> field.setText(code.trim()))
                .show(field.getScene().getWindow());
        });
        return btn;
    }

    /** Two fields with a "to" label between them — for range pairs. */
    private HBox rangeRow(String labelText, TextField start, TextField end) {
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("form-label");
        lbl.setMinWidth(170);

        Label to = new Label("to");
        to.setStyle("-fx-text-fill: #888888; -fx-font-size: 12px;");
        to.setMinWidth(20);
        to.setAlignment(Pos.CENTER);

        HBox.setHgrow(start, Priority.ALWAYS);
        HBox.setHgrow(end,   Priority.ALWAYS);

        HBox row = new HBox(8, lbl, start, to, end);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void setStatus(String msg, String styleKey) {
        lblStatus.setText(msg);
        // Simpler: just set inline style
        lblStatus.setStyle(switch (styleKey) {
            case "running" -> "-fx-text-fill: #1F5C99; -fx-font-weight: bold;";
            case "success" -> "-fx-text-fill: #1A7A3A; -fx-font-weight: bold;";
            case "error"   -> "-fx-text-fill: #922B21; -fx-font-weight: bold;";
            default        -> "-fx-text-fill: #555555;";
        });
    }

    // ────────────────────────────────────────────────────────────────────
    // Value helpers
    // ────────────────────────────────────────────────────────────────────

    private String blankToMin(String s) {
        return (s == null || s.isBlank()) ? " " : s;
    }

    private String blankToMax(String s, int len) {
        if (s == null || s.isBlank()) return "~".repeat(len);
        return s;
    }

}
