package com.landmarksoftware.ui;

import com.landmarksoftware.export.AcquiredRetiredExportService;
import com.landmarksoftware.export.AcquiredRetiredPdfService;
import com.landmarksoftware.model.AcquiredRetiredRequest;
import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.CodeLookupService;
import com.landmarksoftware.repository.CompanyRepository;
import com.landmarksoftware.service.AcquiredRetiredService;
import com.landmarksoftware.service.AcquiredRetiredService.AcquiredRetiredOutput;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FATL03 — Assets Acquired and Retired parameter screen.
 *
 * Mirrors FATL03S0 (11 fields, field order matches FATL03S0-FIELD-NAMES):
 *   1.  Start asset no      (with lookup)
 *   2.  End asset no        (with lookup)
 *   3.  Location code       (with lookup + desc display)
 *   4.  Department code     (with lookup + desc display)
 *   5.  Group code          (with lookup + desc display)
 *   6.  Sub-group code      (with lookup + desc display)
 *   7.  Starting date       (DatePicker, blank = all dates)
 *   8.  Ending date         (DatePicker)
 *   9.  Include leased ?    (Y/N, default N)
 *  10.  Include pooled ?    (Y/N, default blank/N)
 *  11.  Acquisitions or Retirements ? (A/R — required, no default)
 */
@Component
public class AcquiredRetiredScreenController {

    private final AcquiredRetiredService      service;
    private final AcquiredRetiredExportService excelService;
    private final AcquiredRetiredPdfService   pdfService;
    private final CompanyRepository           companyRepo;
    private final CodeLookupService lookupService;
    private final JdbcTemplate                jdbc;
    private final AppSession              appSession;

    // ── Parameter controls ────────────────────────────────────────────
    private ComboBox<CompanyItem> cboCompany;
    private TextField txtStartAsset, txtEndAsset;
    private TextField txtLoc, txtDept, txtGrp, txtSubgrp;
    private DatePicker dpStart, dpEnd;
    private CheckBox  chkIncludeLeased, chkIncludePooled;
    private ToggleGroup modeGroup;
    private RadioButton rdoAcqn, rdoRetmt;
    private RadioButton rdoPdf, rdoExcel;
    private TextField   txtOutDir;
    private Button      btnRun;
    private Label       lblStatus;
    private ProgressBar progressBar;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fatl03-thread");
        t.setDaemon(true);
        return t;
    });

    public AcquiredRetiredScreenController(
            AcquiredRetiredService      service,
            AcquiredRetiredExportService excelService,
            AcquiredRetiredPdfService   pdfService,
            CompanyRepository           companyRepo,
            CodeLookupService           lookupService,
            JdbcTemplate                jdbc,
                               AppSession appSession) {
        this.service      = service;
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
            buildDateCard(),
            buildOptionsCard(),
            buildModeCard(),
            buildOutputCard(),
            buildFooter(owner)
        );

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: #F7F8FA; -fx-background: #F7F8FA;");

        Scene scene = new Scene(scroll, 680, 700);
        try {
            scene.getStylesheets().add(
                getClass().getResource("/css/fixedassets.css").toExternalForm());
        } catch (Exception ignored) {}

        loadCompanies();
        return scene;
    }

    // ── Header ────────────────────────────────────────────────────────

    private VBox buildHeader() {
        Label title = new Label("Assets Acquired and Retired");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1F4E79;");
        Label sub = new Label("FATL03  \u2014  Acquisitions or Retirements in a date period");
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

    // ── Asset range + code filters ────────────────────────────────────

    private VBox buildSelectionCard() {
        txtStartAsset = field(20); txtEndAsset = field(20);
        txtLoc    = field(6);  txtDept   = field(6);
        txtGrp    = field(6);  txtSubgrp = field(6);

        return card("Selection Ranges",
            rangeRow("Asset Number", txtStartAsset, txtEndAsset, LookupDialog.LookupType.ASSET),
            filterRow("Location",   txtLoc,    LookupDialog.LookupType.LOCATION),
            filterRow("Department", txtDept,   LookupDialog.LookupType.DEPARTMENT),
            filterRow("Group",      txtGrp,    LookupDialog.LookupType.GROUP),
            filterRow("Sub-Group",  txtSubgrp, LookupDialog.LookupType.SUBGROUP)
        );
    }

    // ── Date range ────────────────────────────────────────────────────

    private VBox buildDateCard() {
        dpStart = new DatePicker();
        dpStart.setPromptText("blank = all dates");
        dpStart.setPrefWidth(150);

        dpEnd = new DatePicker();
        dpEnd.setPromptText("blank = all dates");
        dpEnd.setPrefWidth(150);

        // Default end to start when start is set
        dpStart.valueProperty().addListener((o, oldV, newV) -> {
            if (newV != null && dpEnd.getValue() == null)
                dpEnd.setValue(newV);
        });

        Label to = new Label("to");
        to.setStyle("-fx-text-fill: #888;");
        HBox row = new HBox(8, dpStart, to, dpEnd);
        row.setAlignment(Pos.CENTER_LEFT);

        return card("Date Range", formRow("For the Period", row));
    }

    // ── Options ───────────────────────────────────────────────────────

    private VBox buildOptionsCard() {
        chkIncludeLeased = new CheckBox("Include leased assets");
        chkIncludePooled = new CheckBox("Include pooled assets");
        // Defaults: leased=N, pooled=blank (false)
        chkIncludeLeased.setSelected(false);
        chkIncludePooled.setSelected(false);
        HBox row = new HBox(24, chkIncludeLeased, chkIncludePooled);
        row.setAlignment(Pos.CENTER_LEFT);
        return card("Other Options", row);
    }

    // ── A/R mode selector ─────────────────────────────────────────────

    private VBox buildModeCard() {
        modeGroup = new ToggleGroup();
        rdoAcqn  = new RadioButton("Acquisitions  (A)");
        rdoRetmt = new RadioButton("Retirements   (R)");
        rdoAcqn.setToggleGroup(modeGroup);
        rdoRetmt.setToggleGroup(modeGroup);
        rdoAcqn.setSelected(true); // default A

        rdoAcqn.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
        rdoRetmt.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");

        HBox row = new HBox(30, rdoAcqn, rdoRetmt);
        row.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("A = Acquisitions   R = Retirements");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748B; -fx-padding: 2 0 0 0;");

        return card("Report Type", row, hint);
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
        btnRun.setOnAction(e -> runReport());

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

        HBox buttons = new HBox(8, btnRun, btnClear, btnClose, progressBar, lblStatus);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(8, 0, 0, 0));
        return new VBox(buttons);
    }

    // ── Run logic ─────────────────────────────────────────────────────

    private void runReport() {
        AcquiredRetiredRequest req = buildRequest();
        if (req == null) return;

        btnRun.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        setStatus("Running\u2026", "normal");

        executor.submit(() -> {
            try {
                AcquiredRetiredOutput output = service.run(req);

                String mode = req.isAcquisitions() ? "Acqn" : "Retmt";
                String ext  = rdoPdf.isSelected() ? "pdf" : "xlsx";
                String fname = String.format("FA_%s_%d.%s", mode, req.getCompanyNo(), ext);
                java.nio.file.Files.createDirectories(Paths.get(txtOutDir.getText().trim()));
                var outPath = Paths.get(txtOutDir.getText().trim(), fname);

                if (rdoPdf.isSelected())
                    pdfService.export(output, outPath);
                else
                    excelService.export(output, outPath);

                Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    setStatus(String.format(
                        "\u2713  Done \u2014 %d assets  \u2014  %s",
                        output.totals().assetCount(), outPath.getFileName()), "success");
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

    private AcquiredRetiredRequest buildRequest() {
        CompanyItem co = cboCompany.getValue();
        if (co == null) { setStatus("Please select a company", "error"); return null; }

        // Validate A/R selection
        if (modeGroup.getSelectedToggle() == null) {
            setStatus("Please select Acquisitions or Retirements", "error");
            return null;
        }

        AcquiredRetiredRequest req = new AcquiredRetiredRequest();
        req.setCompanyNo(co.companyNo());
        req.setStartAssetNo(txtStartAsset.getText().trim());
        req.setEndAssetNo(txtEndAsset.getText().trim());
        req.setLocCode(txtLoc.getText().trim());
        req.setDeptCode(txtDept.getText().trim());
        req.setGrpCode(txtGrp.getText().trim());
        req.setSubgrpCode(txtSubgrp.getText().trim());
        req.setStartDate(dpStart.getValue());
        req.setEndDate(dpEnd.getValue());
        req.setIncludeLeased(chkIncludeLeased.isSelected());
        req.setIncludePooled(chkIncludePooled.isSelected());
        req.setAcqnRetmtInd(rdoAcqn.isSelected() ? "A" : "R");

        return req;
    }

    private void clearForm() {
        txtStartAsset.clear(); txtEndAsset.clear();
        txtLoc.clear(); txtDept.clear(); txtGrp.clear(); txtSubgrp.clear();
        dpStart.setValue(null); dpEnd.setValue(null);
        chkIncludeLeased.setSelected(false);
        chkIncludePooled.setSelected(false);
        rdoAcqn.setSelected(true);
        rdoPdf.setSelected(true);
        setStatus("Ready", "normal");
    }

    // ── Company loader ────────────────────────────────────────────────

    private void loadCompanies() {
        executor.submit(() -> {
            var items = FXCollections.observableArrayList(
                companyRepo.findAll().stream()
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

    private HBox rangeRow(String label, TextField start, TextField end,
                           LookupDialog.LookupType type) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #374151;");
        lbl.setMinWidth(140);
        Button b1 = lookupBtn(start, type);
        Button b2 = lookupBtn(end,   type);
        Label to  = new Label("to"); to.setStyle("-fx-text-fill: #888;");
        HBox row  = new HBox(6, lbl, start, b1, to, end, b2);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox filterRow(String label, TextField field, LookupDialog.LookupType type) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #374151;");
        lbl.setMinWidth(140);
        Button btn = lookupBtn(field, type);
        HBox row = new HBox(6, lbl, field, btn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button lookupBtn(TextField field, LookupDialog.LookupType type) {
        Button btn = new Button("...");
        btn.setStyle("-fx-background-color: #EBF2FA; -fx-border-color: #AABDD0;" +
                     "-fx-border-radius: 4; -fx-background-radius: 4;" +
                     "-fx-padding: 4 8 4 8; -fx-font-size: 11px; -fx-cursor: hand;");
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

    private TextField field(int maxChars) {
        TextField tf = new TextField();
        tf.setPrefWidth(maxChars * 9);
        return tf;
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
