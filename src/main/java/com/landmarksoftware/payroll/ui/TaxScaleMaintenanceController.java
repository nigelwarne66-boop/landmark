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
import com.landmarksoftware.payroll.model.TaxBracket;
import com.landmarksoftware.payroll.model.TaxScale;
import com.landmarksoftware.payroll.service.TaxScaleService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * PASU04 — Tax Scale Maintenance.
 *
 * <p>P1 listbox + tabbed S1 dialog. Maintains scale-level config in
 * {@code pataxfl}; brackets are read-only here (owned by PATX01).
 *
 * <p>Special-scale rules (from COBOL):
 * <ul>
 *   <li>Scale {@code "H"} — HECS marker; deletion blocked.</li>
 *   <li>Scales already attached to active employees can't be deleted —
 *       the {@code countAttachedEmployees} check mirrors the COBOL guard.</li>
 * </ul>
 */
@Component
public class TaxScaleMaintenanceController {

    private static final DateTimeFormatter D_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final TaxScaleService scaleService;
    private final AppSession      appSession;

    private final ObservableList<TaxScale> rows = FXCollections.observableArrayList();
    private TableView<TaxScale> table;
    private Label               lblStatus;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pasu04-thread");
        t.setDaemon(true);
        return t;
    });

    public TaxScaleMaintenanceController(TaxScaleService scaleService,
                                          AppSession appSession) {
        this.scaleService = scaleService;
        this.appSession   = appSession;
    }

    // ─── Scene ──────────────────────────────────────────────────────────

    public Scene buildScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildHeader());
        root.setCenter(buildContent(stage));
        root.setBottom(buildStatusBar());
        Platform.runLater(this::loadList);

        Scene scene = new Scene(root, 920, 540);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    private VBox buildHeader() {
        Label title = new Label("Tax Scale Maintenance — PASU04");
        title.setStyle("-fx-font-size:18px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label(
            appSession.getCompanyName() + "  ·  " + appSession.getYearDesc());
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        VBox box = new VBox(2, title, sub);
        box.setPadding(new Insets(18, 20, 14, 20));
        box.setStyle("-fx-background-color:white;" +
            "-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;" +
            "-fx-border-width:0 0 0.5 0;");
        return box;
    }

    private VBox buildContent(Stage stage) {
        table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No tax scales loaded for this company."));

        table.getColumns().addAll(List.of(
            col("Scale",       s -> s.scaleNo,                            70),
            col("Description", s -> s.desc1,                              250),
            col("Includes HECS", s -> "Y".equalsIgnoreCase(s.includesHecsFlag)
                                       ? "Yes" : "No",                    100),
            col("Term Tax %",  s -> decStr(s.termTaxPerc),                90),
            col("FBT %",       s -> decStr(s.fbtPercRate),                70),
            col("Rounding",    TaxScale::roundingLabel,                   110)
        ));

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null)
                openDialog(table.getSelectionModel().getSelectedItem(), stage);
        });

        Button btnAdd  = btnPrimary("+ Add");
        Button btnEdit = btnSecondary("✎ Edit");
        Button btnDel  = btnDanger("🗑 Delete");
        Button btnRef  = btnSecondary("↺");

        btnAdd.setOnAction(e -> openDialog(null, stage));
        btnEdit.setOnAction(e -> {
            TaxScale sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openDialog(sel, stage);
            else showInfo("Edit", "Select a tax scale to edit.");
        });
        btnDel.setOnAction(e -> {
            TaxScale sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDelete(sel);
            else showInfo("Delete", "Select a tax scale to delete.");
        });
        btnRef.setOnAction(e -> loadList());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(8, btnAdd, btnEdit, btnDel,
            new Separator(Orientation.VERTICAL), spacer, btnRef);
        toolbar.setPadding(new Insets(10, 16, 10, 16));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle("-fx-background-color:#F8F8F6;" +
            "-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;" +
            "-fx-border-width:0 0 0.5 0;");

        VBox box = new VBox(0, toolbar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    // ─── Load + delete ──────────────────────────────────────────────────

    private void loadList() {
        int coNo = appSession.getCompanyNo();
        status("Loading…", false);
        exec.submit(() -> {
            try {
                List<TaxScale> data = scaleService.findAll(coNo);
                Platform.runLater(() -> {
                    rows.setAll(data);
                    status(data.size() + " tax scale(s)", false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    private void confirmDelete(TaxScale sel) {
        if (sel.isHecsMarker()) {
            showInfo("Delete blocked",
                "Scale 'H' is the legacy HECS marker and can't be deleted.");
            return;
        }
        int coNo = appSession.getCompanyNo();
        exec.submit(() -> {
            int inUse = 0;
            try {
                inUse = scaleService.countAttachedEmployees(coNo, sel.scaleNo);
            } catch (Exception ex) {
                final String err = ex.getMessage();
                Platform.runLater(() -> status("Delete check error: " + err, true));
                return;
            }
            final int n = inUse;
            Platform.runLater(() -> {
                if (n > 0) {
                    showInfo("Delete blocked",
                        "Tax scale '" + sel.scaleNo + "' is attached to " + n +
                        " active employee(s).\nReassign or terminate them before deleting.");
                    return;
                }
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete tax scale " + sel.scaleNo + " — " + sel.desc1 + "?",
                    ButtonType.OK, ButtonType.CANCEL);
                a.setTitle("Delete"); a.setHeaderText(null);
                a.showAndWait().ifPresent(bt -> {
                    if (bt != ButtonType.OK) return;
                    exec.submit(() -> {
                        try {
                            scaleService.delete(coNo, sel.scaleNo);
                            Platform.runLater(() -> {
                                status("Deleted: " + sel.scaleNo, false);
                                loadList();
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() ->
                                status("Delete error: " + ex.getMessage(), true));
                        }
                    });
                });
            });
        });
    }

    // ─── Edit / Add dialog ──────────────────────────────────────────────

    private void openDialog(TaxScale existing, Window owner) {
        boolean isAdd = (existing == null);
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(isAdd ? "Add Tax Scale — PASU04" : "Edit Tax Scale — PASU04");
        dlg.setResizable(false);

        TaxScale s = isAdd ? new TaxScale() : existing;
        if (isAdd) {
            s.companyNo        = appSession.getCompanyNo();
            s.includesHecsFlag = "N";
            s.roundingInd      = "N";
        }

        // ── Config tab ─────────────────────────────────────────────────
        TextField fScale = tf(s.scaleNo, 2);
        fScale.setEditable(isAdd);
        fScale.setDisable(!isAdd);
        TextField fDesc1 = tf(s.desc1, 40);
        TextField fDesc2 = tf(s.desc2, 40);
        TextField fLeaveLimit = intField(s.leaveLoadingLimit);
        TextField fTermTax    = tf(decStr(s.termTaxPerc), 8);
        TextField fFbtPerc    = tf(decStr(s.fbtPercRate), 8);
        TextField fFbtAmt     = tf(decStr(s.fbtTaxableAmt), 12);

        CheckBox cbHecs = new CheckBox("Scale already includes HECS/HELP component");
        cbHecs.setSelected("Y".equalsIgnoreCase(s.includesHecsFlag));

        ChoiceBox<String> cbRound = new ChoiceBox<>();
        cbRound.getItems().addAll("N — No rounding", "U — Round up", "D — Round down");
        cbRound.getSelectionModel().select(switch (s.roundingInd == null ? "" : s.roundingInd) {
            case "U" -> 1; case "D" -> 2; default -> 0;
        });
        cbRound.setPrefWidth(200);

        GridPane gConfig = formGrid();
        int r = 0;
        addFormRow(gConfig, r++, "Scale No *:",          fScale);
        addFormRow(gConfig, r++, "Description 1 *:",     fDesc1);
        addFormRow(gConfig, r++, "Description 2:",       fDesc2);
        addFormRow(gConfig, r++, "Leave Loading Limit:", fLeaveLimit);
        addFormRow(gConfig, r++, "Termination Tax %:",   fTermTax);
        addFormRow(gConfig, r++, "FBT %:",               fFbtPerc);
        addFormRow(gConfig, r++, "FBT Taxable Amt:",     fFbtAmt);
        addFormRow(gConfig, r++, "Rounding:",            cbRound);
        addFormRow(gConfig, r++, "HECS / HELP:",         cbHecs);

        // ── Brackets tab (read-only) ───────────────────────────────────
        Node gBrackets = buildBracketsTab(isAdd ? "" : s.scaleNo);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
            tab("Scale Config", gConfig),
            tab("Brackets",     gBrackets));

        Button btnSave   = btnPrimary(isAdd ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(ev -> dlg.close());

        btnSave.setOnAction(ev -> {
            String code = fScale.getText().trim().toUpperCase();
            if (isAdd) {
                if (code.isEmpty()) {
                    markError(fScale, "Scale No is required.");
                    tabs.getSelectionModel().select(0); return;
                }
                if (code.length() > 2) {
                    markError(fScale, "Scale No must be 2 characters or fewer.");
                    tabs.getSelectionModel().select(0); return;
                }
            }
            if (fDesc1.getText().trim().isEmpty()) {
                markError(fDesc1, "Description 1 is required.");
                tabs.getSelectionModel().select(0); return;
            }
            BigDecimal termTax = parseDec(fTermTax, "Termination Tax %");
            if (termTax == null) { tabs.getSelectionModel().select(0); return; }
            BigDecimal fbtPerc = parseDec(fFbtPerc, "FBT %");
            if (fbtPerc == null) { tabs.getSelectionModel().select(0); return; }
            BigDecimal fbtAmt  = parseDec(fFbtAmt, "FBT Taxable Amt");
            if (fbtAmt  == null) { tabs.getSelectionModel().select(0); return; }

            TaxScale out = new TaxScale();
            out.companyNo         = appSession.getCompanyNo();
            out.scaleNo           = isAdd ? code : s.scaleNo;
            out.desc1             = fDesc1.getText().trim();
            out.desc2             = fDesc2.getText().trim();
            out.leaveLoadingLimit = parseInt(fLeaveLimit);
            out.termTaxPerc       = termTax;
            out.includesHecsFlag  = cbHecs.isSelected() ? "Y" : "N";
            out.fbtPercRate       = fbtPerc;
            out.fbtTaxableAmt     = fbtAmt;
            out.roundingInd       = code(cbRound.getValue(), "N");
            out.noteNo            = s.noteNo;

            String userId = appSession.getUserId();
            exec.submit(() -> {
                try {
                    if (isAdd) {
                        if (scaleService.exists(out.companyNo, out.scaleNo)) {
                            Platform.runLater(() ->
                                status("Tax scale '" + out.scaleNo + "' already exists.", true));
                            return;
                        }
                        scaleService.insert(out, userId);
                    } else {
                        scaleService.update(out, userId);
                    }
                    Platform.runLater(() -> {
                        status((isAdd ? "Added: " : "Updated: ") + out.scaleNo, false);
                        dlg.close();
                        loadList();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() ->
                        status("Save error: " + ex.getMessage(), true));
                }
            });
        });

        HBox btnBar = new HBox(10, btnSave, btnCancel);
        btnBar.setPadding(new Insets(10, 20, 16, 20));
        btnBar.setAlignment(Pos.CENTER_RIGHT);
        btnBar.setStyle(
            "-fx-background-color:#F2F1EC;" +
            "-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
            "-fx-border-width:0.5 0 0 0;");

        VBox top = new VBox(2, headerLine(
            isAdd ? "New Tax Scale" : "Edit Tax Scale " + s.scaleNo));
        top.setPadding(new Insets(14, 20, 8, 20));

        VBox root = new VBox(0, top, tabs, btnBar);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        dlg.setScene(new Scene(root, 640, 520));
        dlg.showAndWait();
    }

    private Node buildBracketsTab(String scaleNo) {
        TableView<TaxBracket> bt = new TableView<>();
        ObservableList<TaxBracket> brackets = FXCollections.observableArrayList();
        bt.setItems(brackets);
        bt.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        bt.setPlaceholder(new Label(
            scaleNo.isEmpty()
                ? "Save the scale first, then load brackets via PATX01."
                : "No brackets loaded for scale '" + scaleNo + "'. " +
                  "Use Year End → Load ATO Tax Scales (PATX01) to populate."));

        TableColumn<TaxBracket, String> cSrc = new TableColumn<>("Source");
        cSrc.setCellValueFactory(p -> new SimpleStringProperty(safe(p.getValue().sourceFile)));
        cSrc.setPrefWidth(90);
        TableColumn<TaxBracket, String> cEff = new TableColumn<>("Effective");
        cEff.setCellValueFactory(p -> new SimpleStringProperty(
            p.getValue().effectiveFrom == null ? "" :
            p.getValue().effectiveFrom.format(D_FMT)));
        cEff.setPrefWidth(100);
        TableColumn<TaxBracket, String> cNo = new TableColumn<>("Bracket #");
        cNo.setCellValueFactory(p -> new SimpleStringProperty(
            String.valueOf(p.getValue().bracketNo)));
        cNo.setPrefWidth(80);
        TableColumn<TaxBracket, String> cUpper = new TableColumn<>("Upper Earnings");
        cUpper.setCellValueFactory(p -> new SimpleStringProperty(
            decStr(p.getValue().upperEarnings)));
        cUpper.setPrefWidth(130);
        TableColumn<TaxBracket, String> cA = new TableColumn<>("Coeff a");
        cA.setCellValueFactory(p -> new SimpleStringProperty(
            decStr(p.getValue().coeffA)));
        cA.setPrefWidth(110);
        TableColumn<TaxBracket, String> cB = new TableColumn<>("Coeff b");
        cB.setCellValueFactory(p -> new SimpleStringProperty(
            decStr(p.getValue().coeffB)));
        cB.setPrefWidth(110);
        bt.getColumns().addAll(List.of(cSrc, cEff, cNo, cUpper, cA, cB));

        Label note = new Label(
            "Brackets are read-only here — they're loaded annually from the ATO " +
            "via Year End → Load ATO Tax Scales (PATX01). Showing the latest " +
            "publication per source file.");
        note.setWrapText(true);
        note.setStyle("-fx-font-size:11px;-fx-text-fill:#6B7280;-fx-padding:6 8;");

        VBox box = new VBox(0, note, bt);
        VBox.setVgrow(bt, Priority.ALWAYS);
        box.setPadding(new Insets(0));

        if (!scaleNo.isEmpty()) {
            int coNo = appSession.getCompanyNo();
            exec.submit(() -> {
                try {
                    List<TaxBracket> data = scaleService.findBracketsForScale(coNo, scaleNo);
                    Platform.runLater(() -> brackets.setAll(data));
                } catch (Exception ex) {
                    Platform.runLater(() ->
                        status("Bracket load error: " + ex.getMessage(), true));
                }
            });
        }
        return box;
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private Tab tab(String title, Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setBorder(null);
        return new Tab(title, sp);
    }

    private GridPane formGrid() {
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(10); g.setPadding(new Insets(16));
        return g;
    }

    private TextField intField(int v) {
        TextField f = tf(v == 0 ? "" : String.valueOf(v), 8);
        f.setPrefWidth(110);
        return f;
    }

    private static int parseInt(TextField f) {
        String s = f.getText().trim();
        if (s.isEmpty()) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException ex) { return 0; }
    }

    private static String code(String choiceValue, String def) {
        if (choiceValue == null || choiceValue.isBlank()) return def;
        return choiceValue.substring(0, 1);
    }

    private BigDecimal parseDec(TextField fld, String label) {
        String s = fld.getText().trim();
        if (s.isEmpty()) return BigDecimal.ZERO;
        try { return new BigDecimal(s); }
        catch (NumberFormatException ex) {
            markError(fld, label + " must be a number.");
            return null;
        }
    }

    private static String decStr(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return v.stripTrailingZeros().toPlainString();
    }

    // ─── Status + UI primitives ─────────────────────────────────────────

    private HBox buildStatusBar() {
        lblStatus = new Label("Ready");
        lblStatus.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        HBox bar = new HBox(lblStatus);
        bar.setPadding(new Insets(5, 16, 5, 16));
        bar.setStyle("-fx-background-color:#F8F8F6;" +
            "-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
            "-fx-border-width:0.5 0 0 0;");
        return bar;
    }

    private void status(String msg, boolean err) {
        Platform.runLater(() -> {
            lblStatus.setText(msg);
            lblStatus.setStyle("-fx-font-size:11px;-fx-text-fill:" +
                (err ? "#C0392B" : "#888780") + ";");
        });
    }

    private TableColumn<TaxScale, String> col(String header,
                                                Function<TaxScale, String> fn, double w) {
        TableColumn<TaxScale, String> c = new TableColumn<>(header);
        c.setCellValueFactory(p -> new SimpleStringProperty(safe(fn.apply(p.getValue()))));
        c.setPrefWidth(w);
        return c;
    }

    private void addFormRow(GridPane g, int row, String label, Node ctrl) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(150);
        g.add(l, 0, row);
        g.add(ctrl, 1, row);
    }

    private TextField tf(String value, int maxLen) {
        TextField f = new TextField(value == null ? "" : value.trim());
        f.setPrefWidth(Math.min(maxLen * 9 + 10, 320));
        return f;
    }

    private Button btnPrimary(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color:#1A6EF5;-fx-text-fill:white;-fx-font-weight:bold;" +
                   "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:6 16;-fx-cursor:hand;");
        return b;
    }

    private Button btnSecondary(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color:white;-fx-text-fill:#374151;-fx-border-color:#D0CFC8;" +
                   "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:5 14;-fx-cursor:hand;");
        return b;
    }

    private Button btnDanger(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color:white;-fx-text-fill:#C0392B;-fx-border-color:#E8BDB8;" +
                   "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:5 14;-fx-cursor:hand;");
        return b;
    }

    private Label headerLine(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#1A6EF5;");
        return l;
    }

    private static void markError(TextField fld, String msg) {
        fld.setStyle("-fx-border-color:#DC2626;-fx-border-width:2;");
        fld.requestFocus(); fld.selectAll();
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setTitle("Validation"); a.setHeaderText(null); a.showAndWait();
    }

    private void showInfo(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle(title); a.setHeaderText(null); a.showAndWait();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
