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
import com.landmarksoftware.payroll.model.PayCode;
import com.landmarksoftware.payroll.service.PayCodeService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * PACD01 — Pay Code Maintenance.
 *
 * Mirrors COBOL PACD01 — Edit and Delete on the pacodes master.
 *
 * Add (INSERT) is intentionally not enabled — pacodes has 122 NOT NULL
 * columns and seeding requires a dedicated Setup wizard that fills
 * super-fund, GL clearing, and SuperStream details. The Add button
 * shows an explanatory info dialog rather than an INSERT failure.
 *
 * Screen layout:
 *   top     — header bar
 *   toolbar — Edit | Delete | type-filter tabs | Refresh
 *   centre  — TableView of pay codes
 *   bottom  — status bar
 */
@Component
public class PayCodeMaintenanceController {

    private final PayCodeService payCodeService;
    private final AppSession     appSession;

    private final ObservableList<PayCode> rows = FXCollections.observableArrayList();
    private TableView<PayCode>            table;
    private Label                         lblStatus;
    private int                           currentTypeFilter = 0;  // 0 = all

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pacd01-thread");
        t.setDaemon(true);
        return t;
    });

    public PayCodeMaintenanceController(PayCodeService payCodeService,
                                         AppSession appSession) {
        this.payCodeService = payCodeService;
        this.appSession     = appSession;
    }

    // ── Entry point ───────────────────────────────────────────────────────

    public Scene buildScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildHeader());
        root.setCenter(buildContent(stage));
        root.setBottom(buildStatusBar());

        loadList();

        Scene scene = new Scene(root, 880, 560);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    // ── Header ────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("Pay Code Maintenance");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("PACD01 · " + appSession.getCompanyName());
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        VBox titleBox = new VBox(2, title, sub);

        HBox bar = new HBox(titleBox);
        bar.setPadding(new Insets(14, 20, 14, 20));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle(
            "-fx-background-color:#FFFFFF;" +
            "-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;" +
            "-fx-border-width:0 0 0.5 0;");
        return bar;
    }

    // ── Content — toolbar + table ─────────────────────────────────────────

    private VBox buildContent(Stage stage) {
        table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(table, Priority.ALWAYS);
        table.setPlaceholder(new Label("No pay codes found for this company."));

        table.getColumns().addAll(List.of(
            col("Code",         pc -> pc.payCode,                       90),
            col("Description",  pc -> pc.desc1,                         220),
            col("Payslip Desc", pc -> pc.payslipDesc,                   140),
            col("Type",         PayCode::payTypeLabel,                  100),
            col("Super",        pc -> "Y".equals(pc.superFlag) ? "✓" : "",  55),
            col("Wcomp",        pc -> "Y".equals(pc.wcompFlag) ? "✓" : "",  55),
            col("Rate",         PayCode::primaryRate,                   90),
            col("Amount",       PayCode::primaryAmount,                 90)
        ));

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null)
                openDialog(table.getSelectionModel().getSelectedItem(), stage);
        });

        // Toolbar
        Button btnAdd  = btnPrimary("+ Add");
        Button btnEdit = btnSecondary("✎ Edit");
        Button btnDel  = btnDanger("✕ Delete");
        Button btnRef  = btnSecondary("↺");

        btnAdd.setOnAction(e -> openDialog(null, stage));

        btnEdit.setOnAction(e -> {
            PayCode sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openDialog(sel, stage);
            else showInfo("Edit", "Select a pay code to edit.");
        });
        btnDel.setOnAction(e -> {
            PayCode sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDelete(sel);
            else showInfo("Delete", "Select a pay code to delete.");
        });
        btnRef.setOnAction(e -> loadList());

        HBox toolbar = new HBox(8,
            btnAdd, btnEdit, btnDel,
            new Separator(Orientation.VERTICAL),
            btnRef);
        toolbar.setPadding(new Insets(10, 16, 10, 16));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle(
            "-fx-background-color:#F8F8F6;" +
            "-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;" +
            "-fx-border-width:0 0 0.5 0;");

        // Type filter tabs
        HBox typeFilter = buildTypeFilter();

        return new VBox(0, toolbar, typeFilter, table);
    }

    private HBox buildTypeFilter() {
        HBox bar = new HBox(0);
        bar.setStyle("-fx-background-color:#FFFFFF;" +
            "-fx-border-color:transparent transparent rgba(0,0,0,.08) transparent;" +
            "-fx-border-width:0 0 0.5 0;");

        String[] labels = {"All", "Income", "Allowance", "Deduction", "Tax", "Super"};
        ToggleGroup tg = new ToggleGroup();
        for (int i = 0; i < labels.length; i++) {
            final int typeFilter = i;
            ToggleButton tb = new ToggleButton(labels[i]);
            tb.setToggleGroup(tg);
            tb.setStyle(
                "-fx-background-color:transparent;-fx-border-color:transparent;" +
                "-fx-padding:8 14;-fx-cursor:hand;-fx-font-size:12px;");
            tb.selectedProperty().addListener((o, ov, nv) -> {
                if (nv) { currentTypeFilter = typeFilter; loadList(); }
            });
            if (i == 0) tb.setSelected(true);
            bar.getChildren().add(tb);
        }
        return bar;
    }

    // ── Data operations ───────────────────────────────────────────────────

    private void loadList() {
        status("Loading…", false);
        exec.submit(() -> {
            try {
                List<PayCode> data = currentTypeFilter == 0
                    ? payCodeService.findAll(appSession.getCompanyNo())
                    : payCodeService.findByType(appSession.getCompanyNo(), currentTypeFilter);
                Platform.runLater(() -> {
                    rows.setAll(data);
                    status(data.size() + " code(s)", false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    private void confirmDelete(PayCode pc) {
        exec.submit(() -> {
            boolean inUse = payCodeService.isInUse(appSession.getCompanyNo(), pc.payCode);
            Platform.runLater(() -> {
                if (inUse) {
                    showInfo("Pay Code In Use",
                        "Pay code " + pc.payCode + " is referenced in pay history.\n\n" +
                        "It cannot be deleted while history exists.");
                    return;
                }
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete pay code " + pc.payCode + " — " + pc.desc1 + "?",
                    ButtonType.YES, ButtonType.NO);
                a.setTitle("Confirm Delete");
                a.setHeaderText(null);
                a.showAndWait().ifPresent(btn -> {
                    if (btn == ButtonType.YES) {
                        exec.submit(() -> {
                            try {
                                payCodeService.delete(appSession.getCompanyNo(), pc.payCode);
                                Platform.runLater(() -> {
                                    loadList();
                                    status("Deleted: " + pc.payCode, false);
                                });
                            } catch (Exception ex) {
                                Platform.runLater(() ->
                                    status("Delete error: " + ex.getMessage(), true));
                            }
                        });
                    }
                });
            });
        });
    }

    // ── Edit dialog ───────────────────────────────────────────────────────

    private void openDialog(PayCode existing, Window owner) {
        boolean isAdd = (existing == null);
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(isAdd ? "Add Pay Code — PACD01" : "Edit Pay Code — PACD01");
        dlg.setResizable(false);

        PayCode pc = isAdd ? new PayCode() : existing;
        if (isAdd) {
            // Sensible defaults for a fresh pay code
            pc.payType            = 1;       // Income
            pc.printOnPayslipFlag = "Y";
            pc.superFlag          = "N";
            pc.wcompFlag          = "N";
            pc.termEFlag          = "N";
        }

        TextField fCode    = tf(pc.payCode, 10);
        fCode.setEditable(isAdd);
        fCode.setDisable(!isAdd);
        TextField fDesc    = tf(pc.desc1, 30);
        TextField fPayslip = tf(pc.payslipDesc, 30);
        TextField fAbbrev  = tf(pc.abbrevDesc, 10);

        ChoiceBox<String> cbType = new ChoiceBox<>();
        cbType.getItems().addAll(
            "1 — Income", "2 — Allowance", "3 — Deduction", "4 — Tax", "5 — Super");
        cbType.getSelectionModel().select(Math.max(0, Math.min(4, pc.payType - 1)));
        cbType.setPrefWidth(200);

        CheckBox cbPrint = new CheckBox("Print on payslip");
        cbPrint.setSelected("Y".equals(pc.printOnPayslipFlag));
        CheckBox cbSuper = new CheckBox("Counts toward superannuation");
        cbSuper.setSelected("Y".equals(pc.superFlag));
        CheckBox cbWcomp = new CheckBox("Counts toward workers comp");
        cbWcomp.setSelected("Y".equals(pc.wcompFlag));
        CheckBox cbTermE = new CheckBox("Termination earning");
        cbTermE.setSelected("Y".equals(pc.termEFlag));

        TextField fPayRate    = tf(decStr(pc.payRate),   12);
        TextField fPayFactor  = tf(decStr(pc.payFactor), 12);
        TextField fAllowRate  = tf(decStr(pc.allowRate), 12);
        TextField fAllowAmt   = tf(decStr(pc.allowAmt),  12);
        TextField fDednPerc   = tf(decStr(pc.dednPerc),  12);
        TextField fDednAmt    = tf(decStr(pc.dednAmt),   12);

        Label payHint   = hint("Used when type = Income (pay rate × factor)");
        Label allowHint = hint("Used when type = Allowance");
        Label dednHint  = hint("Used when type = Deduction (% of gross or fixed $)");

        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(10); form.setPadding(new Insets(20));
        int r = 0;
        Label hdr = headerLine("Pay Code Details");
        form.add(hdr, 0, r, 2, 1); r++;

        addFormRow(form, r++, "Pay Code:",       fCode);
        addFormRow(form, r++, "Description *:",  fDesc);
        addFormRow(form, r++, "Payslip Desc:",   fPayslip);
        addFormRow(form, r++, "Abbrev Desc:",    fAbbrev);
        addFormRow(form, r++, "Pay Type *:",     cbType);

        Label lBehav = new Label("Behaviour");
        lBehav.setStyle("-fx-font-size:12px;-fx-font-weight:bold;-fx-text-fill:#374151;-fx-padding:8 0 0 0;");
        form.add(lBehav, 0, r, 2, 1); r++;
        form.add(cbPrint, 0, r, 2, 1); r++;
        form.add(cbSuper, 0, r, 2, 1); r++;
        form.add(cbWcomp, 0, r, 2, 1); r++;
        form.add(cbTermE, 0, r, 2, 1); r++;

        Label lInc = new Label("Income (type 1)");
        lInc.setStyle("-fx-font-size:12px;-fx-font-weight:bold;-fx-text-fill:#374151;-fx-padding:8 0 0 0;");
        form.add(lInc, 0, r, 2, 1); r++;
        addFormRowWithHint(form, r++, "Pay Rate:",   fPayRate,   payHint);
        addFormRow(form, r++,         "Pay Factor:", fPayFactor);

        Label lAllow = new Label("Allowance (type 2)");
        lAllow.setStyle("-fx-font-size:12px;-fx-font-weight:bold;-fx-text-fill:#374151;-fx-padding:8 0 0 0;");
        form.add(lAllow, 0, r, 2, 1); r++;
        addFormRowWithHint(form, r++, "Allow Rate:", fAllowRate, allowHint);
        addFormRow(form, r++,         "Allow Amt:",  fAllowAmt);

        Label lDedn = new Label("Deduction (type 3)");
        lDedn.setStyle("-fx-font-size:12px;-fx-font-weight:bold;-fx-text-fill:#374151;-fx-padding:8 0 0 0;");
        form.add(lDedn, 0, r, 2, 1); r++;
        addFormRowWithHint(form, r++, "Dedn %:",     fDednPerc,  dednHint);
        addFormRow(form, r++,         "Dedn Amt:",   fDednAmt);

        // ── Buttons ───────────────────────────────────────────────────────
        Button btnSave   = btnPrimary(isAdd ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(e -> dlg.close());

        btnSave.setOnAction(e -> {
            String code = fCode.getText().trim().toUpperCase();
            if (isAdd) {
                if (code.isEmpty()) { markError(fCode, "Pay Code is required."); return; }
                if (code.length() > 10) { markError(fCode, "Pay Code must be 10 chars or fewer."); return; }
                clearError(fCode);
            }
            String desc = fDesc.getText().trim();
            if (desc.isEmpty()) { markError(fDesc, "Description is required."); return; }
            clearError(fDesc);

            BigDecimal payRate   = parseDec(fPayRate,   "Pay Rate");   if (payRate   == null) return;
            BigDecimal payFactor = parseDec(fPayFactor, "Pay Factor"); if (payFactor == null) return;
            BigDecimal allowRate = parseDec(fAllowRate, "Allow Rate"); if (allowRate == null) return;
            BigDecimal allowAmt  = parseDec(fAllowAmt,  "Allow Amt");  if (allowAmt  == null) return;
            BigDecimal dednPerc  = parseDec(fDednPerc,  "Dedn %");     if (dednPerc  == null) return;
            BigDecimal dednAmt   = parseDec(fDednAmt,   "Dedn Amt");   if (dednAmt   == null) return;

            PayCode out = new PayCode();
            out.payCode            = isAdd ? code : pc.payCode;
            out.desc1              = desc;
            out.payslipDesc        = fPayslip.getText().trim();
            out.abbrevDesc         = fAbbrev.getText().trim();
            out.payType            = cbType.getSelectionModel().getSelectedIndex() + 1;
            out.printOnPayslipFlag = cbPrint.isSelected() ? "Y" : "N";
            out.superFlag          = cbSuper.isSelected() ? "Y" : "N";
            out.wcompFlag          = cbWcomp.isSelected() ? "Y" : "N";
            out.termEFlag          = cbTermE.isSelected() ? "Y" : "N";
            out.payRate            = payRate;
            out.payFactor          = payFactor;
            out.allowRate          = allowRate;
            out.allowAmt           = allowAmt;
            out.dednPerc           = dednPerc;
            out.dednAmt            = dednAmt;

            int coNo = appSession.getCompanyNo();
            String userId = appSession.getUserId();

            exec.submit(() -> {
                try {
                    if (isAdd) {
                        if (payCodeService.exists(coNo, out.payCode)) {
                            Platform.runLater(() ->
                                status("Pay code " + out.payCode + " already exists.", true));
                            return;
                        }
                        payCodeService.insert(coNo, out, userId);
                    } else {
                        payCodeService.update(coNo, out);
                    }
                    Platform.runLater(() -> {
                        status((isAdd ? "Added: " : "Updated: ") + out.payCode, false);
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

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setBorder(null);
        VBox root = new VBox(0, scroll, btnBar);
        dlg.setScene(new Scene(root, 480, 680));
        dlg.showAndWait();
    }

    private Label headerLine(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#1A6EF5;");
        return l;
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

    // ── Status bar ────────────────────────────────────────────────────────

    private HBox buildStatusBar() {
        lblStatus = new Label("Ready");
        lblStatus.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        HBox bar = new HBox(lblStatus);
        bar.setPadding(new Insets(5, 16, 5, 16));
        bar.setStyle(
            "-fx-background-color:#F8F8F6;" +
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

    // ── UI helpers ────────────────────────────────────────────────────────

    private TableColumn<PayCode, String> col(String header,
                                              Function<PayCode, String> fn, double w) {
        TableColumn<PayCode, String> c = new TableColumn<>(header);
        c.setCellValueFactory(p -> new SimpleStringProperty(safe(fn.apply(p.getValue()))));
        c.setPrefWidth(w);
        return c;
    }

    private void addFormRow(GridPane g, int row, String label, Node ctrl) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(130);
        g.add(l, 0, row);
        g.add(ctrl, 1, row);
    }

    private void addFormRowWithHint(GridPane g, int row, String label,
                                     Node ctrl, Label hint) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(130);
        g.add(l, 0, row);
        VBox box = new VBox(2, ctrl, hint);
        g.add(box, 1, row);
    }

    private Label hint(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:10px;-fx-text-fill:#9CA3AF;");
        return l;
    }

    private TextField tf(String value, int maxLen) {
        TextField f = new TextField(value == null ? "" : value.trim());
        f.setPrefWidth(Math.min(maxLen * 9 + 10, 300));
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

    private static void markError(TextField fld, String msg) {
        fld.setStyle("-fx-border-color:#DC2626;-fx-border-width:2;");
        fld.requestFocus(); fld.selectAll();
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setTitle("Validation"); a.setHeaderText(null); a.showAndWait();
    }

    private static void clearError(TextField fld) {
        fld.setStyle("-fx-border-color:#D1D5DB;-fx-border-width:1.5;");
    }

    private void showInfo(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle(title); a.setHeaderText(null); a.showAndWait();
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String decStr(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return v.stripTrailingZeros().toPlainString();
    }
}
