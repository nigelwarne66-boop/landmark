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

import com.landmarksoftware.export.TransactionListExportService;
import com.landmarksoftware.export.TransactionListPdfService;
import com.landmarksoftware.model.TransactionListRequest;
import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.CodeLookupService;
import com.landmarksoftware.repository.CompanyRepository;
import com.landmarksoftware.service.TransactionListService;
import com.landmarksoftware.service.TransactionListService.TransactionListOutput;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FATL02 — Fixed Assets Transaction List parameter screen.
 *
 * Mirrors the FATL02S0 COBOL screen:
 *   - Asset number range with lookup buttons
 *   - Location / Department / Group / Sub-group filters with lookups
 *   - Transaction type checkboxes (9 types, all default checked)
 *   - Date range (start / end)
 *   - Include leased assets toggle
 *   - Pooled assets only toggle
 *   - Output format (PDF default / Excel)
 *   - Output folder with Browse
 */
@Component
public class TransactionListScreenController {

    private final TransactionListService      listService;
    private final TransactionListExportService excelService;
    private final TransactionListPdfService   pdfService;
    private final CompanyRepository           companyRepo;
    private final CodeLookupService lookupService;
    private final JdbcTemplate                jdbc;
    private final AppSession              appSession;

    // ── Parameter fields ──────────────────────────────────────────────
    private ComboBox<CompanyItem> cboCompany;
    private TextField txtStartAsset, txtEndAsset;
    private TextField txtLoc, txtDept, txtGrp, txtSubgrp;

    // Transaction type checkboxes
    private CheckBox chkAcqn, chkBookDepn, chkBookDepnAdj;
    private CheckBox chkTaxDepn, chkTaxDepnAdj;
    private CheckBox chkBookReval, chkTaxReval;
    private CheckBox chkTransfer, chkRetirement;

    // Date fields
    private DatePicker dpStart, dpEnd;

    // Other options
    private CheckBox chkIncludeLeased, chkPooledOnly;

    // Output
    private RadioButton rdoPdf, rdoExcel;
    private TextField   txtOutDir;

    // Run controls
    private Button   btnRun;
    private Label    lblStatus;
    private ProgressBar progressBar;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fatl02-thread");
        t.setDaemon(true);
        return t;
    });

    public TransactionListScreenController(
            TransactionListService      listService,
            TransactionListExportService excelService,
            TransactionListPdfService   pdfService,
            CompanyRepository           companyRepo,
            CodeLookupService           lookupService,
            JdbcTemplate                jdbc,
                               AppSession appSession) {
        this.listService  = listService;
        this.excelService = excelService;
        this.pdfService   = pdfService;
        this.companyRepo  = companyRepo;
        this.lookupService = lookupService;
        this.jdbc         = jdbc;
        this.appSession = appSession;
    }

    // ── Build scene ───────────────────────────────────────────────────

    public Scene buildScene(Stage owner) {
        VBox root = new VBox(10);
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: #F7F8FA;");

        root.getChildren().addAll(
            buildHeader(),
            buildCompanyRow(),
            buildSelectionCard(),
            buildTrxTypeCard(),
            buildDateCard(),
            buildOptionsCard(),
            buildOutputCard(),
            buildFooter(owner)
        );

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: #F7F8FA; -fx-background: #F7F8FA;");

        Scene scene = new Scene(scroll, 700, 720);
        try {
            scene.getStylesheets().add(
                getClass().getResource("/css/fixedassets.css").toExternalForm());
        } catch (Exception ignored) {}

        loadCompanies();
        return scene;
    }

    // ── Header ────────────────────────────────────────────────────────

    private VBox buildHeader() {
        Label title = new Label("Landmark — Transaction List");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1F4E79;");
        Label sub = new Label("FATL02  \u2014  Select parameters then click Run");
        sub.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748B;");
        VBox hdr = new VBox(2, title, sub);
        hdr.setPadding(new Insets(0, 0, 4, 0));
        return hdr;
    }

    // ── Company ───────────────────────────────────────────────────────

    private HBox buildCompanyRow() {
        cboCompany = new ComboBox<>();
        cboCompany.setMaxWidth(300);
        cboCompany.setPromptText("Loading companies\u2026");
        return formRow("Company", cboCompany);
    }

    // ── Selection ranges ──────────────────────────────────────────────

    private VBox buildSelectionCard() {
        txtStartAsset = field(20); txtEndAsset = field(20);
        txtLoc    = field(6);  txtDept   = field(6);
        txtGrp    = field(6);  txtSubgrp = field(6);

        return card("Selection Ranges",
            rangeRowWithLookup("Asset Number",
                txtStartAsset, txtEndAsset, LookupDialog.LookupType.ASSET),
            filterRowWithLookup("Location",
                txtLoc, LookupDialog.LookupType.LOCATION),
            filterRowWithLookup("Department",
                txtDept, LookupDialog.LookupType.DEPARTMENT),
            filterRowWithLookup("Group",
                txtGrp, LookupDialog.LookupType.GROUP),
            filterRowWithLookup("Sub-Group",
                txtSubgrp, LookupDialog.LookupType.SUBGROUP)
        );
    }

    // ── Transaction types ─────────────────────────────────────────────

    private VBox buildTrxTypeCard() {
        chkAcqn       = chk("Acquisitions (AQ)",        true);
        chkBookDepn    = chk("Book Depreciation (BD)",   true);
        chkBookDepnAdj = chk("Book Depn Adjustment (BA)",true);
        chkTaxDepn     = chk("Tax Depreciation (TD)",    true);
        chkTaxDepnAdj  = chk("Tax Depn Adjustment (TA)", true);
        chkBookReval   = chk("Book Revaluations (RV)",   true);
        chkTaxReval    = chk("Tax Revaluations (TV)",    true);
        chkTransfer    = chk("Transfers (TR)",           true);
        chkRetirement  = chk("Retirements (RT)",         true);

        GridPane grid = new GridPane();
        grid.setHgap(16); grid.setVgap(4);
        grid.add(chkAcqn,       0, 0); grid.add(chkBookDepn,    1, 0);
        grid.add(chkBookReval,  0, 1); grid.add(chkBookDepnAdj, 1, 1);
        grid.add(chkTaxReval,   0, 2); grid.add(chkTaxDepn,     1, 2);
        grid.add(chkTransfer,   0, 3); grid.add(chkTaxDepnAdj,  1, 3);
        grid.add(chkRetirement, 0, 4);

        // Select all / none buttons
        Button btnAll  = new Button("All");
        Button btnNone = new Button("None");
        btnAll.setStyle("-fx-font-size: 10px; -fx-padding: 2 8 2 8;");
        btnNone.setStyle("-fx-font-size: 10px; -fx-padding: 2 8 2 8;");
        btnAll.setOnAction(e  -> setAllTypes(true));
        btnNone.setOnAction(e -> setAllTypes(false));
        HBox btnRow = new HBox(6, btnAll, btnNone);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox body = new VBox(8, grid, btnRow);
        return card("Transaction Types", body);
    }

    private void setAllTypes(boolean v) {
        chkAcqn.setSelected(v); chkBookDepn.setSelected(v);
        chkBookDepnAdj.setSelected(v); chkTaxDepn.setSelected(v);
        chkTaxDepnAdj.setSelected(v); chkBookReval.setSelected(v);
        chkTaxReval.setSelected(v); chkTransfer.setSelected(v);
        chkRetirement.setSelected(v);
    }

    // ── Date range ────────────────────────────────────────────────────

    private VBox buildDateCard() {
        dpStart = new DatePicker();
        dpStart.setPromptText("Leave blank for all dates");
        dpStart.setPrefWidth(160);

        dpEnd = new DatePicker();
        dpEnd.setPromptText("Leave blank for all dates");
        dpEnd.setPrefWidth(160);

        Label to = new Label("to");
        to.setStyle("-fx-text-fill: #888888;");

        HBox dateRow = new HBox(8, dpStart, to, dpEnd);
        dateRow.setAlignment(Pos.CENTER_LEFT);

        return card("Date Range", formRow("Transaction Date", dateRow));
    }

    // ── Other options ─────────────────────────────────────────────────

    private VBox buildOptionsCard() {
        chkIncludeLeased = new CheckBox("Include leased assets");
        chkPooledOnly    = new CheckBox("Pooled assets only");
        HBox row = new HBox(20, chkIncludeLeased, chkPooledOnly);
        row.setAlignment(Pos.CENTER_LEFT);
        return card("Other Options", row);
    }

    // ── Output ────────────────────────────────────────────────────────

    private VBox buildOutputCard() {
        ToggleGroup fmtGroup = new ToggleGroup();
        rdoPdf   = new RadioButton("PDF Report  (default)");
        rdoExcel = new RadioButton("Excel Spreadsheet");
        rdoPdf.setToggleGroup(fmtGroup);
        rdoExcel.setToggleGroup(fmtGroup);
        rdoPdf.setSelected(true);
        HBox fmtRow = new HBox(20, rdoPdf, rdoExcel);
        fmtRow.setAlignment(Pos.CENTER_LEFT);

        txtOutDir = new TextField(
            System.getProperty("user.home") + File.separator + "FA_Exports");
        HBox.setHgrow(txtOutDir, Priority.ALWAYS);

        Button btnBrowse = new Button("Browse\u2026");
        btnBrowse.setStyle("-fx-font-size: 11px;");
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

    // ── Footer ────────────────────────────────────────────────────────

    private VBox buildFooter(Stage owner) {
        btnRun = new Button("\u25B6  Run");
        btnRun.setStyle("-fx-background-color: #1F4E79; -fx-text-fill: white;" +
                        "-fx-font-weight: bold; -fx-padding: 6 20 6 20;");
        btnRun.setOnAction(e -> runReport(owner));

        Button btnClear = new Button("Clear");
        btnClear.setOnAction(e -> clearForm());

        Button btnClose = new Button("Close");
        btnClose.setOnAction(e -> owner.close());

        progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);

        lblStatus = new Label("Ready");
        lblStatus.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");
        HBox.setHgrow(lblStatus, Priority.ALWAYS);

        HBox buttons = new HBox(8, btnRun, btnClear, btnClose,
                                progressBar, lblStatus);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(8, 0, 0, 0));

        return new VBox(buttons);
    }

    // ── Run logic ─────────────────────────────────────────────────────

    private void runReport(Stage owner) {
        TransactionListRequest req = buildRequest();
        if (req == null) return;

        btnRun.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        setStatus("Running\u2026", "normal");

        executor.submit(() -> {
            try {
                TransactionListOutput output = listService.run(req);

                boolean usePdf  = rdoPdf.isSelected();
                String  outDir  = txtOutDir.getText().trim();
                String  ext     = usePdf ? "pdf" : "xlsx";
                String  fname   = String.format("FA_TrxList_%d%s.%s",
                    req.getCompanyNo(),
                    req.getStartDate() != null
                        ? "_" + req.getStartDate().toString().replace("-", "")
                        : "",
                    ext);
                java.nio.file.Files.createDirectories(Paths.get(outDir));
                var outPath = Paths.get(outDir, fname);

                if (usePdf)
                    pdfService.export(output, outPath);
                else
                    excelService.export(output, outPath);

                Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    setStatus(String.format(
                        "\u2713  Done \u2014 %d assets, %d transactions  \u2014  %s",
                        output.totalAssets(), output.totalTransactions(),
                        outPath.getFileName()), "success");
                    btnRun.setDisable(false);
                    progressBar.setVisible(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setStatus("Error: " + ex.getMessage(), "error");
                    btnRun.setDisable(false);
                    progressBar.setVisible(false);
                });
            }
        });
    }

    private TransactionListRequest buildRequest() {
        CompanyItem company = cboCompany.getValue();
        if (company == null) {
            setStatus("Please select a company", "error");
            return null;
        }

        TransactionListRequest req = new TransactionListRequest();
        req.setCompanyNo(company.companyNo());
        req.setStartAssetNo(txtStartAsset.getText().trim());
        req.setEndAssetNo(txtEndAsset.getText().trim());
        req.setLocCode(txtLoc.getText().trim());
        req.setDeptCode(txtDept.getText().trim());
        req.setGrpCode(txtGrp.getText().trim());
        req.setSubgrpCode(txtSubgrp.getText().trim());

        req.setInclAcqn(chkAcqn.isSelected());
        req.setInclBookDepn(chkBookDepn.isSelected());
        req.setInclBookDepnAdj(chkBookDepnAdj.isSelected());
        req.setInclTaxDepn(chkTaxDepn.isSelected());
        req.setInclTaxDepnAdj(chkTaxDepnAdj.isSelected());
        req.setInclBookReval(chkBookReval.isSelected());
        req.setInclTaxReval(chkTaxReval.isSelected());
        req.setInclTransfer(chkTransfer.isSelected());
        req.setInclRetirement(chkRetirement.isSelected());

        req.setStartDate(dpStart.getValue());
        req.setEndDate(dpEnd.getValue());

        req.setIncludeLeased(chkIncludeLeased.isSelected());
        req.setPooledOnly(chkPooledOnly.isSelected());

        return req;
    }

    private void clearForm() {
        txtStartAsset.clear(); txtEndAsset.clear();
        txtLoc.clear(); txtDept.clear(); txtGrp.clear(); txtSubgrp.clear();
        setAllTypes(true);
        dpStart.setValue(null); dpEnd.setValue(null);
        chkIncludeLeased.setSelected(false);
        chkPooledOnly.setSelected(false);
        rdoPdf.setSelected(true);
        setStatus("Ready", "normal");
    }

    // ── Company loader ────────────────────────────────────────────────

    private void loadCompanies() {
        executor.submit(() -> {
            var companies = companyRepo.findAll();
            var items = FXCollections.observableArrayList(
                companies.stream()
                    .map(c -> new CompanyItem(c.getCompanyNo(), c.getName()))
                    .toList());
            Platform.runLater(() -> {
                cboCompany.setItems(items);
                // Pre-select session company from AppSession (GLPASS equivalent)
                items.stream()
                    .filter(c -> c.companyNo() == appSession.getCompanyNo())
                    .findFirst()
                    .ifPresentOrElse(
                        cboCompany.getSelectionModel()::select,
                        () -> { if (!items.isEmpty()) cboCompany.getSelectionModel().selectFirst(); }
                    );
            });
        });
    }

    // ── UI helpers ────────────────────────────────────────────────────

    private HBox formRow(String label, javafx.scene.Node control) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #374151;");
        lbl.setMinWidth(140);
        HBox row = new HBox(8, lbl, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox rangeRowWithLookup(String label, TextField start, TextField end,
                                     LookupDialog.LookupType type) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #374151;");
        lbl.setMinWidth(140);
        Button b1 = lookupBtn(start, type);
        Button b2 = lookupBtn(end,   type);
        Label to  = new Label("to");
        to.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
        HBox.setHgrow(start, Priority.ALWAYS);
        HBox.setHgrow(end,   Priority.ALWAYS);
        HBox row = new HBox(6, lbl, start, b1, to, end, b2);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox filterRowWithLookup(String label, TextField field,
                                      LookupDialog.LookupType type) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #374151;");
        lbl.setMinWidth(140);
        Button btn = lookupBtn(field, type);
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox row = new HBox(6, lbl, field, btn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button lookupBtn(TextField field, LookupDialog.LookupType type) {
        Button btn = new Button("...");
        btn.setStyle("-fx-background-color: #EBF2FA; -fx-border-color: #AABDD0;" +
                     "-fx-border-radius: 4; -fx-background-radius: 4;" +
                     "-fx-padding: 4 8 4 8; -fx-font-size: 11px; -fx-cursor: hand;");
        btn.setTooltip(new Tooltip("Search " + type.label + "s"));
        btn.setOnAction(e -> {
            CompanyItem co = cboCompany.getValue();
            int cno = co != null ? co.companyNo() : 1;
            new LookupDialog(lookupService, type, cno, code -> field.setText(code.trim()))
                .show(field.getScene().getWindow());
        });
        return btn;
    }

    private VBox card(String title, javafx.scene.Node... rows) {
        Label hdr = new Label(title);
        hdr.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;" +
                     "-fx-text-fill: #1F4E79; -fx-padding: 0 0 4 0;");
        VBox body = new VBox(6);
        body.getChildren().add(hdr);
        for (var row : rows) body.getChildren().add(row);
        body.setStyle("-fx-background-color: white; -fx-padding: 12;" +
                      "-fx-border-color: #E2E8F0; -fx-border-radius: 6;" +
                      "-fx-background-radius: 6;");
        return body;
    }

    private VBox card(String title, javafx.scene.Node singleNode) {
        return card(title, new javafx.scene.Node[]{singleNode});
    }

    private TextField field(int maxChars) {
        TextField tf = new TextField();
        tf.setPrefWidth(maxChars * 9);
        return tf;
    }

    private CheckBox chk(String label, boolean selected) {
        CheckBox cb = new CheckBox(label);
        cb.setSelected(selected);
        cb.setStyle("-fx-font-size: 11px;");
        return cb;
    }

    private void setStatus(String msg, String type) {
        lblStatus.setText(msg);
        String colour = switch (type) {
            case "error"   -> "#DC2626";
            case "success" -> "#16A34A";
            default        -> "#64748B";
        };
        lblStatus.setStyle("-fx-text-fill: " + colour + "; -fx-font-size: 11px;");
    }
}
