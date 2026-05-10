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
        // 24 type labels — sourced from PACD01.cbl WS-PAYCODE-TYPE-LIT-TABLE.
        for (int t = 1; t <= 24; t++) {
            PayCode tmp = new PayCode(); tmp.payType = t;
            cbType.getItems().add(String.format("%2d — %s", t, tmp.payTypeLabel()));
        }
        cbType.getSelectionModel().select(Math.max(0, Math.min(23, pc.payType - 1)));
        cbType.setPrefWidth(260);

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

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));

        Label hdr = headerLine("Pay Code Details");
        form.getChildren().add(hdr);

        form.getChildren().add(twoColRow("Pay Code:",      fCode));
        form.getChildren().add(twoColRow("Description *:", fDesc));
        form.getChildren().add(twoColRow("Payslip Desc:",  fPayslip));
        form.getChildren().add(twoColRow("Abbrev Desc:",   fAbbrev));
        form.getChildren().add(twoColRow("Pay Type *:",    cbType));

        form.getChildren().add(sectionHeader("Behaviour"));
        form.getChildren().addAll(cbPrint, cbSuper, cbWcomp, cbTermE);

        // ── Type-specific field group sections ──────────────────────────
        // Each section is a VBox toggled visible based on the selected type.
        VBox payBox = sectionBox("Income (Normal Pay / Overtime / Other Pay)",
            twoColRow("Pay Rate:",   fPayRate),
            twoColRow("Pay Factor:", fPayFactor),
            hint("Used when type = 1, 2, or 3 (pay rate × factor)"));

        VBox allowBox = sectionBox("Allowance / Termination",
            twoColRow("Allow Rate:", fAllowRate),
            twoColRow("Allow Amt:",  fAllowAmt),
            hint("Used when type = 10-14 (allowances and terminations A/B/C)"));

        VBox dednBox = sectionBox("Deduction",
            twoColRow("Dedn %:",   fDednPerc),
            twoColRow("Dedn Amt:", fDednAmt),
            hint("Used when type = 15 or 16 (% of gross or fixed $)"));

        Label placeholderMsg = new Label();
        placeholderMsg.setWrapText(true);
        placeholderMsg.setStyle("-fx-text-fill:#888780;-fx-font-style:italic;");
        VBox placeholderBox = sectionBox("Type-specific fields",
            placeholderMsg);

        form.getChildren().addAll(payBox, allowBox, dednBox, placeholderBox);

        // ── Type → field-group visibility + flag locks ──────────────────
        Runnable applyTypeLogic = () -> {
            int selectedType = cbType.getSelectionModel().getSelectedIndex() + 1;
            PayCode.FieldGroup grp = PayCode.fieldGroupFor(selectedType);

            setSectionVisible(payBox,         grp == PayCode.FieldGroup.PAY);
            setSectionVisible(allowBox,       grp == PayCode.FieldGroup.ALLOW);
            setSectionVisible(dednBox,        grp == PayCode.FieldGroup.DEDN);

            // Placeholder for groups whose UI isn't built yet
            String msg = switch (grp) {
                case LEAVE   -> "Leave settings (max taken, accrual flags, leave loading) "
                              + "for type " + selectedType + " will be saved with default "
                              + "values in this version. Full leave UI is planned for "
                              + "a follow-up release.";
                case SUPER   -> "Superannuation fund details, EFT, SuperStream and "
                              + "before/after-tax flag for type " + selectedType + " "
                              + "will be saved with default values. Full super UI is "
                              + "planned for a follow-up release.";
                case TAX     -> "Tax remittance method and EFT reference for type 18 "
                              + "will be saved with default values. Full tax UI is "
                              + "planned for a follow-up release.";
                case TERM_E  -> "Termination earning (term_e) flag will be set; no "
                              + "additional fields apply for type 19.";
                case CONTRIB -> "Employer contribution settings (clearing accounts, "
                              + "EFT, GST) for type 21 will be saved with default "
                              + "values. Full contribution UI is planned for a "
                              + "follow-up release.";
                case NONE    -> "Type " + selectedType + " is header-only (no detail "
                              + "fields apply). The pay code will be saved with the "
                              + "behaviour flags above only.";
                default      -> "";
            };
            setSectionVisible(placeholderBox, !msg.isEmpty());
            placeholderMsg.setText(msg);

            // Flag locks (mirrors PACD01.pl CHECK-PAY-TYPE)
            if (PayCode.superFlagLockedNo(selectedType)) {
                cbSuper.setSelected(false);
                cbSuper.setDisable(true);
            } else {
                cbSuper.setDisable(false);
            }
            if (PayCode.wcompFlagLockedNo(selectedType)) {
                cbWcomp.setSelected(false);
                cbWcomp.setDisable(true);
            } else {
                cbWcomp.setDisable(false);
            }
        };
        cbType.getSelectionModel().selectedIndexProperty()
            .addListener((o, ov, nv) -> applyTypeLogic.run());
        applyTypeLogic.run();  // initial state

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

    private Label sectionHeader(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:12px;-fx-font-weight:bold;-fx-text-fill:#374151;-fx-padding:8 0 0 0;");
        return l;
    }

    /** Two-column form row: 130-wide label + control. */
    private HBox twoColRow(String label, Node control) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(140);
        HBox row = new HBox(10, l, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** A field-group section: bold header + arbitrary content rows, ready for show/hide. */
    private VBox sectionBox(String title, Node... children) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(8, 0, 0, 0));
        Label hdr = sectionHeader(title);
        box.getChildren().add(hdr);
        for (Node n : children) box.getChildren().add(n);
        return box;
    }

    /** Toggle visibility AND managed (so hidden sections don't reserve layout space). */
    private static void setSectionVisible(Node section, boolean visible) {
        section.setVisible(visible);
        section.setManaged(visible);
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
