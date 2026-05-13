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
import com.landmarksoftware.payroll.model.Fund;
import com.landmarksoftware.payroll.model.PayCode;
import com.landmarksoftware.payroll.service.FundService;
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
    private final FundService    fundService;
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
                                         FundService fundService,
                                         AppSession appSession) {
        this.payCodeService = payCodeService;
        this.fundService    = fundService;
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

    /**
     * Two-screen dialog flow mirroring COBOL PACD01:
     *   S1 (header)  → user enters code/desc/type/behaviour, clicks "Next →"
     *   S2 (type-spec) → user enters PAY/ALLOW/DEDN fields, clicks Add/Save
     *   For groups without UI yet (LEAVE/SUPER/TAX/TERM_E/CONTRIB/NONE),
     *   S2 is skipped and the record saves directly with sentinel defaults.
     */
    private void openDialog(PayCode existing, Window owner) {
        boolean isAdd = (existing == null);
        PayCode pc;
        if (isAdd) {
            pc = new PayCode();
            pc.payType            = 1;
            pc.printOnPayslipFlag = "Y";
            pc.superFlag          = "N";
            pc.wcompFlag          = "N";
            pc.termEFlag          = "N";
        } else {
            pc = existing;
        }
        openHeaderDialog(pc, isAdd, owner);
    }

    // ── Screen 1 — Header (code, desc, type, behaviour flags) ────────────

    private void openHeaderDialog(PayCode pc, boolean isAdd, Window owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isAdd ? "Add" : "Edit") + " Pay Code — Header (S1)");
        dlg.setResizable(false);

        TextField fCode    = tf(pc.payCode, 10);
        fCode.setEditable(isAdd); fCode.setDisable(!isAdd);
        TextField fDesc    = tf(pc.desc1, 30);
        TextField fPayslip = tf(pc.payslipDesc, 30);
        TextField fAbbrev  = tf(pc.abbrevDesc, 10);

        ChoiceBox<String> cbType = new ChoiceBox<>();
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

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));
        form.getChildren().add(headerLine("Pay Code — Header"));
        form.getChildren().addAll(
            twoColRow("Pay Code:",      fCode),
            twoColRow("Description *:", fDesc),
            twoColRow("Payslip Desc:",  fPayslip),
            twoColRow("Abbrev Desc:",   fAbbrev),
            twoColRow("Pay Type *:",    cbType));
        form.getChildren().add(sectionHeader("Behaviour"));
        form.getChildren().addAll(cbPrint, cbSuper, cbWcomp, cbTermE);

        Label nextHint = new Label();
        nextHint.setWrapText(true);
        nextHint.setStyle("-fx-text-fill:#888780;-fx-font-style:italic;-fx-padding:8 0 0 0;");
        form.getChildren().add(nextHint);

        // Type listener: super/wcomp locks + next-step description
        Runnable applyTypeLogic = () -> {
            int t = cbType.getSelectionModel().getSelectedIndex() + 1;
            if (PayCode.superFlagLockedNo(t)) { cbSuper.setSelected(false); cbSuper.setDisable(true); }
            else cbSuper.setDisable(false);
            if (PayCode.wcompFlagLockedNo(t)) { cbWcomp.setSelected(false); cbWcomp.setDisable(true); }
            else cbWcomp.setDisable(false);
            nextHint.setText(nextStepHint(PayCode.fieldGroupFor(t)));
        };
        cbType.getSelectionModel().selectedIndexProperty()
            .addListener((o, ov, nv) -> applyTypeLogic.run());
        applyTypeLogic.run();

        // Buttons — "Next →" advances to S2 (or saves directly if no S2 for the type)
        Button btnNext   = btnPrimary("Next →");
        Button btnCancel = btnSecondary("Cancel");
        btnNext.setDefaultButton(true);
        btnCancel.setOnAction(e -> dlg.close());

        btnNext.setOnAction(e -> {
            // Validate header
            String code = fCode.getText().trim().toUpperCase();
            if (isAdd) {
                if (code.isEmpty()) { markError(fCode, "Pay Code is required."); return; }
                if (code.length() > 10) { markError(fCode, "Pay Code must be 10 chars or fewer."); return; }
                clearError(fCode);
            }
            String desc = fDesc.getText().trim();
            if (desc.isEmpty()) { markError(fDesc, "Description is required."); return; }
            clearError(fDesc);

            // Write header into pc (carries forward to S2 / save)
            pc.payCode            = isAdd ? code : pc.payCode;
            pc.desc1              = desc;
            pc.payslipDesc        = fPayslip.getText().trim();
            pc.abbrevDesc         = fAbbrev.getText().trim();
            pc.payType            = cbType.getSelectionModel().getSelectedIndex() + 1;
            pc.printOnPayslipFlag = cbPrint.isSelected() ? "Y" : "N";
            pc.superFlag          = cbSuper.isSelected() ? "Y" : "N";
            pc.wcompFlag          = cbWcomp.isSelected() ? "Y" : "N";
            pc.termEFlag          = cbTermE.isSelected() ? "Y" : "N";

            if (isAdd) {
                // Off-thread duplicate check before opening S2
                int coNo = appSession.getCompanyNo();
                exec.submit(() -> {
                    boolean dup = payCodeService.exists(coNo, pc.payCode);
                    Platform.runLater(() -> {
                        if (dup) {
                            markError(fCode, "Pay code " + pc.payCode + " already exists.");
                        } else {
                            dlg.close();
                            // Defer reopen so this dialog fully closes first.
                            Platform.runLater(() -> openS2OrSave(pc, isAdd, owner));
                        }
                    });
                });
            } else {
                dlg.close();
                Platform.runLater(() -> openS2OrSave(pc, isAdd, owner));
            }
        });

        HBox btnBar = new HBox(10, btnNext, btnCancel);
        btnBar.setPadding(new Insets(10, 20, 16, 20));
        btnBar.setAlignment(Pos.CENTER_RIGHT);
        btnBar.setStyle(
            "-fx-background-color:#F2F1EC;" +
            "-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
            "-fx-border-width:0.5 0 0 0;");

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true); scroll.setBorder(null);
        VBox root = new VBox(0, scroll, btnBar);
        dlg.setScene(new Scene(root, 480, 480));
        dlg.showAndWait();
    }

    /** Route to the appropriate S2 sub-screen based on the chosen type. */
    private void openS2OrSave(PayCode pc, boolean isAdd, Window owner) {
        switch (pc.fieldGroup()) {
            case PAY     -> openS2Pay(pc, isAdd, owner);
            case ALLOW   -> openS2Allow(pc, isAdd, owner);
            case DEDN    -> openS2Dedn(pc, isAdd, owner);
            case LEAVE   -> openS2Leave(pc, isAdd, owner);
            case SUPER   -> openS2Super(pc, isAdd, owner);
            case CONTRIB -> openS2Contrib(pc, isAdd, owner);
            case TAX     -> openS2Tax(pc, isAdd, owner);
            case TERM_E  -> openS2TermE(pc, isAdd, owner);
            case NONE    -> doSave(pc, isAdd);   // header-only types (22-24)
        }
    }

    // ── Screen 2 — common modal builder ──────────────────────────────────

    /**
     * Build and show a modal S2 dialog. {@code validate} returns {@code true}
     * to commit (close dialog + run {@code onSuccess}) or {@code false} to
     * keep the dialog open after a validation failure.
     */
    private void showS2(String titleSuffix, PayCode pc, boolean isAdd, Window owner,
                        java.util.List<Node> contentRows,
                        java.util.function.BooleanSupplier validate,
                        Runnable onSuccess,
                        int dlgHeight) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isAdd ? "Add" : "Edit") + " Pay Code — " + titleSuffix + " (S2)");
        dlg.setResizable(false);

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));
        form.setMinWidth(440);
        form.setFillWidth(true);
        form.getChildren().add(headerLine(
            pc.payCode + " — " + pc.desc1 + "  (" + pc.payTypeLabel() + ")"));
        Label intro = new Label("Enter the type-specific fields for this pay code:");
        intro.setStyle("-fx-text-fill:#888780;-fx-padding:0 0 8 0;");
        form.getChildren().add(intro);
        form.getChildren().addAll(contentRows);

        Button btnSave   = btnPrimary(isAdd ? "Add" : "Save");
        Button btnBack   = btnSecondary("← Back");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(e -> dlg.close());
        btnBack.setOnAction(e -> {
            dlg.close();
            Platform.runLater(() -> openHeaderDialog(pc, isAdd, owner));
        });
        btnSave.setOnAction(e -> {
            if (!validate.getAsBoolean()) return;   // validation failed, stay open
            dlg.close();
            onSuccess.run();
        });

        HBox btnBar = new HBox(10, btnBack, btnSave, btnCancel);
        btnBar.setPadding(new Insets(10, 20, 16, 20));
        btnBar.setAlignment(Pos.CENTER_RIGHT);
        btnBar.setStyle(
            "-fx-background-color:#F2F1EC;" +
            "-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
            "-fx-border-width:0.5 0 0 0;");

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setBorder(null);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox root = new VBox(0, scroll, btnBar);
        root.setMinSize(560, dlgHeight);
        dlg.setScene(new Scene(root, 560, dlgHeight));
        dlg.showAndWait();
    }

    /**
     * If the chosen pay-method is "D" (direct credit) or "Q" (cleared funds),
     * open the S2G EFT-details modal before committing; otherwise save now.
     */
    private void saveOrEft(PayCode pc, boolean isAdd, Window owner, String payMethod) {
        String m = payMethod == null ? "" : payMethod.trim().toUpperCase();
        if ("D".equals(m) || "Q".equals(m)) {
            Platform.runLater(() -> openS2Eft(pc, isAdd, owner));
        } else {
            doSave(pc, isAdd);
        }
    }

    // ── PACD01S2 — PAY (types 1-3) ───────────────────────────────────────

    private void openS2Pay(PayCode pc, boolean isAdd, Window owner) {
        TextField fPayFactor = tf(decStr(pc.payFactor), 12);
        TextField fPayRate   = tf(decStr(pc.payRate),   12);
        CheckBox cbPayable   = checkBox("Are hours payable?",                  pc.payPayableFlag);
        CheckBox cbRdoAcc    = checkBox("Used for rostered day off accruals?", pc.payRdoAccrualFlag);
        CheckBox cbLslAcc    = checkBox("(PT) Include hours in long service leave accrual",
                                        pc.payLslAccrualFlag);
        CheckBox cbAlAcc     = checkBox("(PT) Include hours in annual leave accrual",
                                        pc.payAlAccrualFlag);
        CheckBox cbSickAcc   = checkBox("(PT) Include hours in sick leave accrual",
                                        pc.paySickAccrualFlag);
        CheckBox cbLslCas    = checkBox("(Casual) Include in LSL accrual",     pc.payLslCasAccrual);
        CheckBox cbIncRdo    = checkBox("Include in RDO accrual calculation",  pc.payIncludeForRdo);
        CheckBox cbLslRet    = checkBox("Include in LSL return report",        pc.payLslReturnFlag);
        CheckBox cbCdep      = checkBox("Include in CDEP",                     pc.payCdepFlag);
        TextField fRetComm   = tf(pc.payRetCommInd,    2);
        TextField fUsualPaid = tf(pc.payUsualPaidFlag, 2);

        java.util.List<Node> rows = java.util.List.of(
            twoColRow("Pay Factor:",   fPayFactor),
            twoColRow("Pay Rate /hr:", fPayRate),
            hint("Income = pay_factor × normal rate, OR explicit pay_rate per hour."),
            sectionHeader("Behaviour"), cbPayable, cbRdoAcc,
            sectionHeader("Part-time staff: include hours worked in"),
            cbLslAcc, cbAlAcc, cbSickAcc, cbLslCas, cbIncRdo,
            twoColRow("Retainer/commission type:", fRetComm),
            cbLslRet,
            twoColRow("Use usual or actual rate?", fUsualPaid),
            cbCdep);

        showS2(pc.payTypeLabel(), pc, isAdd, owner, rows, () -> {
            BigDecimal pr = parseDec(fPayRate,   "Pay Rate");   if (pr == null) return false;
            BigDecimal pf = parseDec(fPayFactor, "Pay Factor"); if (pf == null) return false;
            pc.payRate            = pr;
            pc.payFactor          = pf;
            pc.payPayableFlag     = ynOf(cbPayable);
            pc.payRdoAccrualFlag  = ynOf(cbRdoAcc);
            pc.payLslAccrualFlag  = ynOf(cbLslAcc);
            pc.payAlAccrualFlag   = ynOf(cbAlAcc);
            pc.paySickAccrualFlag = ynOf(cbSickAcc);
            pc.payLslCasAccrual   = ynOf(cbLslCas);
            pc.payIncludeForRdo   = ynOf(cbIncRdo);
            pc.payLslReturnFlag   = ynOf(cbLslRet);
            pc.payCdepFlag        = ynOf(cbCdep);
            pc.payRetCommInd      = fRetComm.getText().trim().toUpperCase();
            pc.payUsualPaidFlag   = fUsualPaid.getText().trim().toUpperCase();
            return true;
        }, () -> doSave(pc, isAdd), 660);
    }

    // ── PACD01S2A / S2B — ALLOWANCE (types 10-14) ────────────────────────

    private void openS2Allow(PayCode pc, boolean isAdd, Window owner) {
        TextField fAllowRate = tf(decStr(pc.allowRate), 12);
        TextField fAllowAmt  = tf(decStr(pc.allowAmt),  12);
        TextField fUnit      = tf(pc.allowUnitPerDesc, 20);
        CheckBox cbIncSumm   = checkBox("Include on payment summaries?",        pc.allowIncludeForGc);
        CheckBox cbPayrollTx = checkBox("Include in payroll tax calculations?", pc.allowPayrollTaxFlag);
        CheckBox cbLslAcc    = checkBox("(PT) Include hours in long service leave accrual",
                                        pc.allowLslAccrualFlag);
        CheckBox cbAlAcc     = checkBox("(PT) Include hours in annual leave accrual",
                                        pc.allowAlAccrualFlag);
        CheckBox cbSlAcc     = checkBox("(PT) Include hours in sick leave accrual",
                                        pc.allowSlAccrualFlag);
        CheckBox cbLslCas    = checkBox("(Casual) Include hrs in LSL accrual",   pc.allowLslCasAccrual);
        CheckBox cbRdoAcc    = checkBox("Include hrs in rostered day off accrual",
                                        pc.allowRdoAccrualFlag);
        CheckBox cbIncRdo    = checkBox("Include in RDO accrual calculation",   pc.allowIncludeForRdo);
        TextField fRetComm   = tf(pc.allowRetCommInd, 2);
        CheckBox cbLslRet    = checkBox("Include in LSL return report",         pc.allowLslReturnFlag);
        CheckBox cbGst       = checkBox("GST applies to this code",             pc.allowGstFlag);
        TextField fGstCode   = tf(pc.allowGstCode, 4);
        CheckBox cbFbt       = checkBox("Includes FBT grossed up",              pc.allowFbtFlag);
        CheckBox cbRptInc    = checkBox("Reportable income",                    pc.allowRptIncFlag);
        CheckBox cbCdep      = checkBox("Included in CDEP",                     pc.allowCdepFlag);

        java.util.List<Node> rows = new java.util.ArrayList<>();
        rows.add(twoColRow("Rate:",                    fAllowRate));
        rows.add(twoColRow("per (unit description):",  fUnit));
        rows.add(twoColRow("or amount per payrun:",    fAllowAmt));
        rows.add(sectionHeader("Behaviour"));
        rows.add(cbIncSumm);
        rows.add(cbPayrollTx);
        rows.add(sectionHeader("Part-time staff: include hours worked in"));
        rows.add(cbLslAcc);
        rows.add(cbAlAcc);
        rows.add(cbSlAcc);
        rows.add(cbLslCas);
        rows.add(cbRdoAcc);
        rows.add(cbIncRdo);
        rows.add(twoColRow("Retainer/commission type:", fRetComm));
        rows.add(cbLslRet);
        rows.add(sectionHeader("GST / Tax / Reporting"));
        rows.add(cbGst);
        rows.add(twoColRow("Tax code:", fGstCode));
        rows.add(cbFbt);
        rows.add(cbRptInc);
        rows.add(cbCdep);

        showS2(pc.payTypeLabel(), pc, isAdd, owner, rows, () -> {
            BigDecimal r = parseDec(fAllowRate, "Rate");           if (r == null) return false;
            BigDecimal a = parseDec(fAllowAmt,  "Amount per pay"); if (a == null) return false;
            pc.allowRate           = r;
            pc.allowAmt            = a;
            pc.allowUnitPerDesc    = fUnit.getText().trim();
            pc.allowIncludeForGc   = ynOf(cbIncSumm);
            pc.allowPayrollTaxFlag = ynOf(cbPayrollTx);
            pc.allowLslAccrualFlag = ynOf(cbLslAcc);
            pc.allowAlAccrualFlag  = ynOf(cbAlAcc);
            pc.allowSlAccrualFlag  = ynOf(cbSlAcc);
            pc.allowLslCasAccrual  = ynOf(cbLslCas);
            pc.allowRdoAccrualFlag = ynOf(cbRdoAcc);
            pc.allowIncludeForRdo  = ynOf(cbIncRdo);
            pc.allowRetCommInd     = fRetComm.getText().trim().toUpperCase();
            pc.allowLslReturnFlag  = ynOf(cbLslRet);
            pc.allowGstFlag        = ynOf(cbGst);
            pc.allowGstCode        = fGstCode.getText().trim().toUpperCase();
            pc.allowFbtFlag        = ynOf(cbFbt);
            pc.allowRptIncFlag     = ynOf(cbRptInc);
            pc.allowCdepFlag       = ynOf(cbCdep);
            return true;
        }, () -> doSave(pc, isAdd), 700);
    }

    // ── PACD01S2D — DEDUCTION (types 15, 16) ─────────────────────────────

    private void openS2Dedn(PayCode pc, boolean isAdd, Window owner) {
        TextField fDednPerc = tf(decStr(pc.dednPerc), 12);
        TextField fDednAmt  = tf(decStr(pc.dednAmt),  12);
        TextField fFundName = tf(pc.fundName,    30);
        TextField fAddr1    = tf(pc.fundAddr1,   30);
        TextField fAddr2    = tf(pc.fundAddr2,   30);
        TextField fAddr3    = tf(pc.fundAddr3,   30);
        TextField fContact  = tf(pc.contactName, 30);
        TextField fPhone    = tf(pc.contactPhone,20);
        TextField fAbn      = tf(pc.fundAbn,     14);
        TextField fUsi      = tf(pc.fundUsi,     30);
        TextField fEsa      = tf(pc.fundEsa,     30);
        ChoiceBox<String> cbFundType = choiceBox(fundTypeOptions(),             pc.apraSmsfFundInd);
        ChoiceBox<String> cbCategory = choiceBox(superStreamCategoryOptions(),  pc.superstreamCategory);
        ChoiceBox<String> cbFreq   = choiceBox(remitFreqOptions(),    pc.dednRemittanceFreq);
        ChoiceBox<String> cbMethod = choiceBox(payMethodOptions(),    pc.dednPayMethod);
        TextField fClearMain = tf(String.valueOf(pc.dednClearAcctMain), 8);
        TextField fClearSub  = tf(String.valueOf(pc.dednClearAcctSub),  8);
        CheckBox cbReportable= checkBox("Reportable super on payment summaries?", pc.dednReportableFlag);
        CheckBox cbSalSac    = checkBox("Salary sacrifice?",                      pc.dednSalSacFlag);
        CheckBox cbUsedSuper = checkBox("Used for super?",                        pc.dednUsedForSuper);
        CheckBox cbWplace    = checkBox("Workplace giving?",                      pc.dednWplaceGiveFlag);
        CheckBox cbUnion     = checkBox("Union or professional association fees?",
                                        pc.dednUnionFeesFlag);

        java.util.List<Node> rows = new java.util.ArrayList<>();
        rows.add(sectionHeader("Deduction amount"));
        rows.add(twoColRow("Percent of gross:",  fDednPerc));
        rows.add(twoColRow("OR amount per pay:", fDednAmt));
        rows.add(cbSalSac);
        rows.add(sectionHeader("Fund classification"));
        rows.add(cbUsedSuper);
        rows.add(twoColRow("Fund type:",            cbFundType));
        rows.add(twoColRow("SuperStream category:", cbCategory));
        rows.add(cbReportable);
        rows.add(sectionHeader("Payee"));
        rows.add(twoColRow("Fund/payee name:", fFundName));
        rows.add(twoColRow("Address 1:",       fAddr1));
        rows.add(twoColRow("Address 2:",       fAddr2));
        rows.add(twoColRow("Address 3:",       fAddr3));
        rows.add(twoColRow("Contact name:",    fContact));
        rows.add(twoColRow("Phone:",           fPhone));
        rows.add(twoColRow("Fund ABN:",        fAbn));
        rows.add(twoColRow("Fund USI:",        fUsi));
        rows.add(twoColRow("Fund ESA:",        fEsa));
        rows.add(sectionHeader("Remittance"));
        rows.add(twoColRow("Frequency:",        cbFreq));
        rows.add(twoColRow("Payment method:",   cbMethod));
        rows.add(hint("If method is Direct Credit (D) or Cleared (Q), EFT details must be provided."));
        rows.add(twoColRow("GL clearing main:", fClearMain));
        rows.add(twoColRow("GL clearing sub:",  fClearSub));
        rows.add(sectionHeader("Categorisation"));
        rows.add(cbWplace);
        rows.add(cbUnion);

        showS2(pc.payTypeLabel(), pc, isAdd, owner, rows, () -> {
            BigDecimal p = parseDec(fDednPerc, "Percent of gross");  if (p == null) return false;
            BigDecimal a = parseDec(fDednAmt,  "Amount per pay");    if (a == null) return false;
            Integer cm = parseIntField(fClearMain, "GL clearing main"); if (cm == null) return false;
            Integer cs = parseIntField(fClearSub,  "GL clearing sub");  if (cs == null) return false;
            pc.dednPerc            = p;
            pc.dednAmt             = a;
            pc.fundName            = fFundName.getText().trim();
            pc.fundAddr1           = fAddr1.getText().trim();
            pc.fundAddr2           = fAddr2.getText().trim();
            pc.fundAddr3           = fAddr3.getText().trim();
            pc.contactName         = fContact.getText().trim();
            pc.contactPhone        = fPhone.getText().trim();
            pc.fundAbn             = fAbn.getText().trim();
            pc.fundUsi             = fUsi.getText().trim();
            pc.fundEsa             = fEsa.getText().trim();
            pc.apraSmsfFundInd     = codeOf(cbFundType);
            pc.superstreamCategory = codeOf(cbCategory);
            pc.dednRemittanceFreq  = codeOf(cbFreq);
            pc.dednPayMethod       = codeOf(cbMethod);
            pc.dednClearAcctMain   = cm;
            pc.dednClearAcctSub    = cs;
            pc.dednReportableFlag  = ynOf(cbReportable);
            pc.dednSalSacFlag      = ynOf(cbSalSac);
            pc.dednUsedForSuper    = ynOf(cbUsedSuper);
            pc.dednWplaceGiveFlag  = ynOf(cbWplace);
            pc.dednUnionFeesFlag   = ynOf(cbUnion);
            return true;
        }, () -> saveOrEft(pc, isAdd, owner, pc.dednPayMethod), 760);
    }

    // ── PACD01S2C / S2F — LEAVE (types 4-9) ──────────────────────────────

    private void openS2Leave(PayCode pc, boolean isAdd, Window owner) {
        // pacodes.leave_max_taken is stored in MINUTES — display in HOURS.
        TextField fMaxHrs   = tf(intStr(pc.leaveMaxTaken / 60), 8);
        TextField fMaxMths  = tf(intStr(pc.leaveMaxPeriod),     6);
        TextField fFactor   = tf(decStr(pc.leavePayFactor),     12);
        CheckBox cbPayable  = checkBox("Are hours payable?",                pc.leavePayableFlag);
        CheckBox cbTermPay  = checkBox("Termination pay only?",             pc.leaveTermPayFlag);
        CheckBox cbLslAcc   = checkBox("(PT) Include hours in LSL accrual", pc.leaveLslAccrualFlag);
        CheckBox cbAlAcc    = checkBox("(PT) Include hours in AL accrual",  pc.leaveAlAccrualFlag);
        CheckBox cbSlAcc    = checkBox("(PT) Include hours in sick accrual",pc.leaveSlAccrualFlag);
        CheckBox cbLslCas   = checkBox("(Casual) Include in LSL accrual",   pc.leaveLslCasAccrual);
        CheckBox cbRdoAcc   = checkBox("Include hrs in RDO accrual",        pc.leaveRdoAccrualFlag);
        CheckBox cbIncRdo   = checkBox("Include in RDO accrual calculation",pc.leaveIncludeForRdo);
        CheckBox cbLslRet   = checkBox("Include in LSL return report",      pc.leaveLslReturnFlag);
        TextField fUsual    = tf(pc.leaveUsualPaidFlag, 2);
        CheckBox cbCdep     = checkBox("Include in CDEP",                   pc.leaveCdepFlag);

        java.util.List<Node> rows = new java.util.ArrayList<>();
        rows.add(sectionHeader("Maximum allowed"));
        rows.add(twoColRow("Maximum hours:",          fMaxHrs));
        rows.add(twoColRow("within a period of (mths):", fMaxMths));
        rows.add(sectionHeader("Behaviour"));
        rows.add(cbPayable);
        rows.add(cbTermPay);
        rows.add(twoColRow("Factor of standard rate:", fFactor));
        rows.add(twoColRow("Use usual or actual rate?", fUsual));
        rows.add(sectionHeader("Part-time staff: include hours worked in"));
        rows.add(cbLslAcc);
        rows.add(cbAlAcc);
        rows.add(cbSlAcc);
        rows.add(cbLslCas);
        rows.add(cbRdoAcc);
        rows.add(cbIncRdo);
        rows.add(cbLslRet);
        rows.add(cbCdep);

        showS2(pc.payTypeLabel(), pc, isAdd, owner, rows, () -> {
            Integer mh = parseIntField(fMaxHrs,  "Maximum hours");  if (mh == null) return false;
            Integer mm = parseIntField(fMaxMths, "Period (months)"); if (mm == null) return false;
            BigDecimal pf = parseDec (fFactor,  "Factor of standard rate"); if (pf == null) return false;
            pc.leaveMaxTaken      = mh * 60;   // convert hours → minutes for storage
            pc.leaveMaxPeriod     = mm;
            pc.leavePayFactor     = pf;
            pc.leavePayableFlag   = ynOf(cbPayable);
            pc.leaveTermPayFlag   = ynOf(cbTermPay);
            pc.leaveLslAccrualFlag= ynOf(cbLslAcc);
            pc.leaveAlAccrualFlag = ynOf(cbAlAcc);
            pc.leaveSlAccrualFlag = ynOf(cbSlAcc);
            pc.leaveLslCasAccrual = ynOf(cbLslCas);
            pc.leaveRdoAccrualFlag= ynOf(cbRdoAcc);
            pc.leaveIncludeForRdo = ynOf(cbIncRdo);
            pc.leaveLslReturnFlag = ynOf(cbLslRet);
            pc.leaveUsualPaidFlag = fUsual.getText().trim().toUpperCase();
            pc.leaveCdepFlag      = ynOf(cbCdep);
            return true;
        }, () -> doSave(pc, isAdd), 660);
    }

    // ── PACD01S2E — SUPER (types 17, 20) ─────────────────────────────────

    private void openS2Super(PayCode pc, boolean isAdd, Window owner) {
        TextField fFundName = tf(pc.fundName,    30);
        TextField fAddr1    = tf(pc.fundAddr1,   30);
        TextField fAddr2    = tf(pc.fundAddr2,   30);
        TextField fAddr3    = tf(pc.fundAddr3,   30);
        TextField fContact  = tf(pc.contactName, 30);
        TextField fPhone    = tf(pc.contactPhone,20);
        TextField fAbn      = tf(pc.fundAbn,     14);
        TextField fUsi      = tf(pc.fundUsi,     30);
        TextField fEsa      = tf(pc.fundEsa,     30);
        ChoiceBox<String> cbFundType = choiceBox(fundTypeOptions(),            pc.apraSmsfFundInd);
        ChoiceBox<String> cbCategory = choiceBox(superStreamCategoryOptions(), pc.superstreamCategory);
        TextField fPerc     = tf(decStr(pc.superEmployeePerc), 12);
        TextField fMaxYtd   = tf(decStr(pc.maxSuperYtd),       12);
        ChoiceBox<String> cbFreq   = choiceBox(remitFreqOptions(), pc.superRemittanceFreq);
        ChoiceBox<String> cbMethod = choiceBox(payMethodOptions(), pc.superPayMethod);
        CheckBox cbPayrollTx= checkBox("Payroll taxable?",                 pc.superPayrollTaxFlag);
        CheckBox cbReport   = checkBox("Reportable on payment summary?",   pc.superReportableFlag);
        TextField fClearMain= tf(String.valueOf(pc.superClearAcctMain), 8);
        TextField fClearSub = tf(String.valueOf(pc.superClearAcctSub),  8);

        // Suppress the auto-open-picker listener while we're actively
        // populating the form from a picked fund (otherwise picking
        // re-triggers the type listener which re-opens the picker).
        final boolean[] populating = {false};

        // Picker callback — populates form + pc.* from the chosen fund.
        java.util.function.Consumer<Fund> populate = f -> {
            populating[0] = true;
            try {
                pc.apraSmsfFundInd = f.apraSmsfFundInd;
                // SMSFs use smsf_abn; APRA funds use fund_abn.
                String abn = "S".equalsIgnoreCase(f.apraSmsfFundInd) && !f.smsfAbn.isBlank()
                             ? f.smsfAbn : f.fundAbn;
                pc.fundAbn       = abn;
                // Off-form fields the user can't see on S2E but that EFT (S2G) uses.
                pc.bankCode      = f.bankCode;
                pc.bankBsb       = f.bankBsb;
                pc.bankAcctNo    = f.bankAcctNo;
                pc.acctName      = f.acctName;
                // Sync the choice box to the chosen fund's type
                for (String opt : fundTypeOptions()) {
                    if (!opt.isEmpty()
                      && Character.toUpperCase(opt.charAt(0)) ==
                         Character.toUpperCase(f.apraSmsfFundInd.charAt(0))) {
                        cbFundType.setValue(opt);
                        break;
                    }
                }
                fFundName.setText(f.fundName);
                fAddr1.setText(f.fundAddr1);
                fAddr2.setText(f.fundAddr2);
                fAddr3.setText(f.fundAddr3);
                fContact.setText(f.contactName);
                fPhone.setText(f.contactPhone);
                fAbn.setText(abn);
                fUsi.setText(f.usi());
                fEsa.setText(f.esa());
            } finally {
                populating[0] = false;
            }
        };

        // Auto-open the appropriate picker when user selects A or S.
        // Skip when the populate callback is the one setting the value, or
        // when the relevant USI/ESA field already has a value (the user is
        // just confirming their existing choice).
        cbFundType.valueProperty().addListener((obs, ov, nv) -> {
            if (populating[0] || nv == null || nv.isEmpty()) return;
            char code = Character.toUpperCase(nv.charAt(0));
            if (code == 'A' && fUsi.getText().trim().isEmpty()) {
                new FundLookupDialog(fundService, appSession.getCompanyNo(),
                                     "A", populate).show(owner);
            } else if (code == 'S' && fEsa.getText().trim().isEmpty()) {
                new FundLookupDialog(fundService, appSession.getCompanyNo(),
                                     "S", populate).show(owner);
            }
        });

        java.util.List<Node> rows = new java.util.ArrayList<>();
        rows.add(sectionHeader("Fund classification"));
        rows.add(twoColRow("Fund type:",            cbFundType));
        rows.add(twoColRow("SuperStream category:", cbCategory));
        rows.add(sectionHeader("Fund details"));
        rows.add(twoColRow("Fund USI:",    fieldWithPicker(fUsi,
                              fundPickerButton("A", owner, populate))));
        rows.add(twoColRow("ESA alias:",   fieldWithPicker(fEsa,
                              fundPickerButton("S", owner, populate))));
        rows.add(hint("Selecting fund type A or S opens the corresponding picker; or click 🔍 directly."));
        rows.add(twoColRow("Fund name:",   fFundName));
        rows.add(twoColRow("Address 1:",   fAddr1));
        rows.add(twoColRow("Address 2:",   fAddr2));
        rows.add(twoColRow("Address 3:",   fAddr3));
        rows.add(twoColRow("Contact name:",fContact));
        rows.add(twoColRow("Phone:",       fPhone));
        rows.add(twoColRow("Fund ABN:",    fAbn));
        rows.add(sectionHeader("Contribution"));
        rows.add(twoColRow("Percentage contribution:", fPerc));
        rows.add(twoColRow("Maximum YTD amount:",      fMaxYtd));
        rows.add(sectionHeader("Remittance"));
        rows.add(twoColRow("Frequency:",      cbFreq));
        rows.add(twoColRow("Payment method:", cbMethod));
        rows.add(twoColRow("GL clearing main:", fClearMain));
        rows.add(twoColRow("GL clearing sub:",  fClearSub));
        rows.add(cbPayrollTx);
        rows.add(cbReport);

        showS2(pc.payTypeLabel(), pc, isAdd, owner, rows, () -> {
            BigDecimal p = parseDec(fPerc,   "Percentage contribution"); if (p == null) return false;
            BigDecimal m = parseDec(fMaxYtd, "Maximum YTD amount");      if (m == null) return false;
            Integer cm = parseIntField(fClearMain, "GL clearing main"); if (cm == null) return false;
            Integer cs = parseIntField(fClearSub,  "GL clearing sub");  if (cs == null) return false;
            pc.fundName            = fFundName.getText().trim();
            pc.fundAddr1           = fAddr1.getText().trim();
            pc.fundAddr2           = fAddr2.getText().trim();
            pc.fundAddr3           = fAddr3.getText().trim();
            pc.contactName         = fContact.getText().trim();
            pc.contactPhone        = fPhone.getText().trim();
            pc.fundAbn             = fAbn.getText().trim();
            pc.fundUsi             = fUsi.getText().trim();
            pc.fundEsa             = fEsa.getText().trim();
            pc.apraSmsfFundInd     = codeOf(cbFundType);
            pc.superstreamCategory = codeOf(cbCategory);
            pc.superEmployeePerc   = p;
            pc.maxSuperYtd         = m;
            pc.superRemittanceFreq = codeOf(cbFreq);
            pc.superPayMethod      = codeOf(cbMethod);
            pc.superClearAcctMain  = cm;
            pc.superClearAcctSub   = cs;
            pc.superPayrollTaxFlag = ynOf(cbPayrollTx);
            pc.superReportableFlag = ynOf(cbReport);
            return true;
        }, () -> saveOrEft(pc, isAdd, owner, pc.superPayMethod), 720);
    }

    // ── PACD01S2H — EMPLOYER CONTRIBUTION (type 21) ──────────────────────

    private void openS2Contrib(PayCode pc, boolean isAdd, Window owner) {
        // Top-line flags (line 1 in COBOL S2H — render them first so they're
        // immediately visible).
        CheckBox cbPaid     = checkBox("Includes paid contribution?", pc.contribPaidFlag);
        CheckBox cbUsedSup  = checkBox("Used for super?",             pc.contribUsedForSuper);
        ChoiceBox<String> cbCategory = choiceBox(superStreamCategoryOptions(), pc.superstreamCategory);
        // Fund classification
        ChoiceBox<String> cbFundType = choiceBox(fundTypeOptions(),  pc.apraSmsfFundInd);
        TextField fAbn      = tf(pc.fundAbn, 14);
        TextField fUsi      = tf(pc.fundUsi, 30);
        TextField fEsa      = tf(pc.fundEsa, 30);
        // Payee
        TextField fFundName = tf(pc.fundName,    30);
        TextField fAddr1    = tf(pc.fundAddr1,   30);
        TextField fAddr2    = tf(pc.fundAddr2,   30);
        TextField fAddr3    = tf(pc.fundAddr3,   30);
        TextField fContact  = tf(pc.contactName, 30);
        TextField fPhone    = tf(pc.contactPhone,20);
        // Remittance
        ChoiceBox<String> cbFreq   = choiceBox(remitFreqOptions(), pc.contribRemitFreq);
        ChoiceBox<String> cbMethod = choiceBox(payMethodOptions(), pc.contribPayMethod);
        TextField fClearMain= tf(String.valueOf(pc.contribClearMain), 8);
        TextField fClearSub = tf(String.valueOf(pc.contribClearSub),  8);
        // Categorisation / GST / tax
        CheckBox cbFbt      = checkBox("Includes FBT grossed up value?", pc.contribFbtFlag);
        CheckBox cbRptInc   = checkBox("Included in reportable income?", pc.contribRptIncFlag);
        CheckBox cbGst      = checkBox("Inclusive of GST?",              pc.contribGstFlag);
        TextField fGstCode  = tf(pc.contribGstCode, 4);
        CheckBox cbPayrollTx= checkBox("Calc payroll tax?",              pc.contribReportFlag);

        // Picker callback for S2H — same field-fill pattern as S2E.
        final boolean[] populating = {false};
        java.util.function.Consumer<Fund> populate = f -> {
            populating[0] = true;
            try {
                pc.apraSmsfFundInd = f.apraSmsfFundInd;
                String abn = "S".equalsIgnoreCase(f.apraSmsfFundInd) && !f.smsfAbn.isBlank()
                             ? f.smsfAbn : f.fundAbn;
                pc.fundAbn       = abn;
                pc.bankCode      = f.bankCode;
                pc.bankBsb       = f.bankBsb;
                pc.bankAcctNo    = f.bankAcctNo;
                pc.acctName      = f.acctName;
                for (String opt : fundTypeOptions()) {
                    if (!opt.isEmpty()
                      && Character.toUpperCase(opt.charAt(0)) ==
                         Character.toUpperCase(f.apraSmsfFundInd.charAt(0))) {
                        cbFundType.setValue(opt);
                        break;
                    }
                }
                fFundName.setText(f.fundName);
                fAddr1.setText(f.fundAddr1);
                fAddr2.setText(f.fundAddr2);
                fAddr3.setText(f.fundAddr3);
                fContact.setText(f.contactName);
                fPhone.setText(f.contactPhone);
                fAbn.setText(abn);
                fUsi.setText(f.usi());
                fEsa.setText(f.esa());
            } finally {
                populating[0] = false;
            }
        };

        // Auto-open the appropriate picker when user selects A or S.
        cbFundType.valueProperty().addListener((obs, ov, nv) -> {
            if (populating[0] || nv == null || nv.isEmpty()) return;
            char code = Character.toUpperCase(nv.charAt(0));
            if (code == 'A' && fUsi.getText().trim().isEmpty()) {
                new FundLookupDialog(fundService, appSession.getCompanyNo(),
                                     "A", populate).show(owner);
            } else if (code == 'S' && fEsa.getText().trim().isEmpty()) {
                new FundLookupDialog(fundService, appSession.getCompanyNo(),
                                     "S", populate).show(owner);
            }
        });

        java.util.List<Node> rows = new java.util.ArrayList<>();
        rows.add(sectionHeader("Contribution"));
        rows.add(cbPaid);
        rows.add(cbUsedSup);
        rows.add(twoColRow("SuperStream category:", cbCategory));
        rows.add(sectionHeader("Fund classification"));
        rows.add(twoColRow("Fund type:", cbFundType));
        rows.add(twoColRow("Fund USI:",  fieldWithPicker(fUsi,
                              fundPickerButton("A", owner, populate))));
        rows.add(twoColRow("Fund ESA:",  fieldWithPicker(fEsa,
                              fundPickerButton("S", owner, populate))));
        rows.add(hint("Selecting fund type A or S opens the corresponding picker; or click 🔍 directly."));
        rows.add(twoColRow("Fund ABN:",  fAbn));
        rows.add(sectionHeader("Payee"));
        rows.add(twoColRow("Fund/payee name:", fFundName));
        rows.add(twoColRow("Address 1:",       fAddr1));
        rows.add(twoColRow("Address 2:",       fAddr2));
        rows.add(twoColRow("Address 3:",       fAddr3));
        rows.add(twoColRow("Contact name:",    fContact));
        rows.add(twoColRow("Phone:",           fPhone));
        rows.add(sectionHeader("Remittance"));
        rows.add(twoColRow("Frequency:",        cbFreq));
        rows.add(twoColRow("Payment method:",   cbMethod));
        rows.add(twoColRow("Contribution GL clearing main:", fClearMain));
        rows.add(twoColRow("Contribution GL clearing sub:",  fClearSub));
        rows.add(sectionHeader("GST / Tax / Reporting"));
        rows.add(cbFbt);
        rows.add(cbRptInc);
        rows.add(cbGst);
        rows.add(twoColRow("GST code:", fGstCode));
        rows.add(cbPayrollTx);

        showS2(pc.payTypeLabel(), pc, isAdd, owner, rows, () -> {
            Integer cm = parseIntField(fClearMain, "GL clearing main"); if (cm == null) return false;
            Integer cs = parseIntField(fClearSub,  "GL clearing sub");  if (cs == null) return false;
            pc.contribPaidFlag     = ynOf(cbPaid);
            pc.contribUsedForSuper = ynOf(cbUsedSup);
            pc.superstreamCategory = codeOf(cbCategory);
            pc.apraSmsfFundInd     = codeOf(cbFundType);
            pc.fundAbn             = fAbn.getText().trim();
            pc.fundUsi             = fUsi.getText().trim();
            pc.fundEsa             = fEsa.getText().trim();
            pc.fundName            = fFundName.getText().trim();
            pc.fundAddr1           = fAddr1.getText().trim();
            pc.fundAddr2           = fAddr2.getText().trim();
            pc.fundAddr3           = fAddr3.getText().trim();
            pc.contactName         = fContact.getText().trim();
            pc.contactPhone        = fPhone.getText().trim();
            pc.contribRemitFreq    = codeOf(cbFreq);
            pc.contribPayMethod    = codeOf(cbMethod);
            pc.contribClearMain    = cm;
            pc.contribClearSub     = cs;
            pc.contribFbtFlag      = ynOf(cbFbt);
            pc.contribRptIncFlag   = ynOf(cbRptInc);
            pc.contribGstFlag      = ynOf(cbGst);
            pc.contribGstCode      = fGstCode.getText().trim().toUpperCase();
            pc.contribReportFlag   = ynOf(cbPayrollTx);
            return true;
        }, () -> saveOrEft(pc, isAdd, owner, pc.contribPayMethod), 740);
    }

    // ── PACD01S2I — TAX (pay code = "TAX") ───────────────────────────────

    private void openS2Tax(PayCode pc, boolean isAdd, Window owner) {
        TextField fFundName = tf(pc.fundName,    30);
        TextField fAddr1    = tf(pc.fundAddr1,   30);
        TextField fAddr2    = tf(pc.fundAddr2,   30);
        TextField fAddr3    = tf(pc.fundAddr3,   30);
        TextField fContact  = tf(pc.contactName, 30);
        TextField fPhone    = tf(pc.contactPhone,20);
        ChoiceBox<String> cbFreq   = choiceBox(remitFreqOptions(), pc.taxRemitFreq);
        ChoiceBox<String> cbMethod = choiceBox(payMethodOptions(), pc.taxPayMethod);

        java.util.List<Node> rows = new java.util.ArrayList<>();
        rows.add(sectionHeader("Tax office"));
        rows.add(twoColRow("Tax Office name:", fFundName));
        rows.add(twoColRow("Address 1:",       fAddr1));
        rows.add(twoColRow("Address 2:",       fAddr2));
        rows.add(twoColRow("Address 3:",       fAddr3));
        rows.add(twoColRow("Contact name:",    fContact));
        rows.add(twoColRow("Phone:",           fPhone));
        rows.add(sectionHeader("Remittance"));
        rows.add(twoColRow("Frequency:",        cbFreq));
        rows.add(twoColRow("Payment method:",   cbMethod));
        rows.add(hint("If method is Direct Credit (D) or Cleared (Q), the EFT details screen will follow."));

        showS2(pc.payTypeLabel(), pc, isAdd, owner, rows, () -> {
            pc.fundName     = fFundName.getText().trim();
            pc.fundAddr1    = fAddr1.getText().trim();
            pc.fundAddr2    = fAddr2.getText().trim();
            pc.fundAddr3    = fAddr3.getText().trim();
            pc.contactName  = fContact.getText().trim();
            pc.contactPhone = fPhone.getText().trim();
            pc.taxRemitFreq = codeOf(cbFreq);
            pc.taxPayMethod = codeOf(cbMethod);
            return true;
        }, () -> saveOrEft(pc, isAdd, owner, pc.taxPayMethod), 540);
    }

    // ── PACD01S2J — TERMINATION-E (type 19) ──────────────────────────────

    private void openS2TermE(PayCode pc, boolean isAdd, Window owner) {
        // S2J in COBOL has only the lump-sum-E flag. pacodes already has
        // term_e_flag captured on S1 as "Termination earning" — this screen
        // is a simple confirmation that the type-D classification stands.
        CheckBox cbLumpE = checkBox("Lump sum E?", pc.termEFlag);

        java.util.List<Node> rows = java.util.List.of(
            sectionHeader("Termination — Lump Sum E"),
            cbLumpE,
            hint("This pay code will be reported on payment summaries as Lump Sum E."));

        showS2(pc.payTypeLabel(), pc, isAdd, owner, rows, () -> {
            pc.termEFlag = ynOf(cbLumpE);
            return true;
        }, () -> doSave(pc, isAdd), 320);
    }

    // ── PACD01S2G — EFT details (chained from S2D / S2E / S2H / S2I) ─────

    /**
     * Capture EFT details — bank code, account name, BSB, account number,
     * and EFT reference. Mirrors COBOL ENTER-EFT-DETAILS: account-name
     * defaults to the fund name when blank.
     */
    private void openS2Eft(PayCode pc, boolean isAdd, Window owner) {
        if (pc.acctName == null || pc.acctName.isBlank()) pc.acctName = pc.fundName;

        TextField fBankCode = tf(pc.bankCode,   8);
        TextField fAcctName = tf(pc.acctName,   30);
        TextField fBsb      = tf(pc.bankBsb,    7);
        TextField fAcctNo   = tf(pc.bankAcctNo, 12);
        TextField fEftRef   = tf(pc.eftReference, 30);

        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isAdd ? "Add" : "Edit") + " Pay Code — EFT Details (S2G)");
        dlg.setResizable(false);

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));
        form.setMinWidth(440);
        form.setFillWidth(true);
        form.getChildren().add(headerLine(
            pc.payCode + " — " + pc.desc1 + "  (EFT details)"));
        Label intro = new Label("Bank account details for the EFT remittance:");
        intro.setStyle("-fx-text-fill:#888780;-fx-padding:0 0 8 0;");
        form.getChildren().add(intro);
        form.getChildren().addAll(
            sectionHeader("Draw from"),
            twoColRow("Bank code:",     fBankCode),
            sectionHeader("Transfer funds to"),
            twoColRow("Account name:",  fAcctName),
            twoColRow("Bank BSB:",      fBsb),
            twoColRow("Account no:",    fAcctNo),
            twoColRow("EFT reference:", fEftRef));

        Button btnSave   = btnPrimary(isAdd ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(e -> dlg.close());
        btnSave.setOnAction(e -> {
            if (fBsb.getText().trim().isEmpty()) {
                markError(fBsb, "Bank BSB is required for EFT.");
                return;
            }
            if (fAcctNo.getText().trim().isEmpty()) {
                markError(fAcctNo, "Account number is required for EFT.");
                return;
            }
            pc.bankCode     = fBankCode.getText().trim();
            pc.acctName     = fAcctName.getText().trim();
            pc.bankBsb      = fBsb.getText().trim();
            pc.bankAcctNo   = fAcctNo.getText().trim();
            pc.eftReference = fEftRef.getText().trim();
            dlg.close();
            doSave(pc, isAdd);
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
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox root = new VBox(0, scroll, btnBar);
        root.setMinSize(520, 420);
        dlg.setScene(new Scene(root, 520, 420));
        dlg.showAndWait();
    }

    /** Common save path for both Add (after S2) and Edit. */
    private void doSave(PayCode pc, boolean isAdd) {
        int coNo = appSession.getCompanyNo();
        String userId = appSession.getUserId();
        exec.submit(() -> {
            try {
                if (isAdd) payCodeService.insert(coNo, pc, userId);
                else        payCodeService.update(coNo, pc);
                Platform.runLater(() -> {
                    status((isAdd ? "Added: " : "Updated: ") + pc.payCode, false);
                    loadList();
                });
            } catch (Exception ex) {
                Platform.runLater(() ->
                    status("Save error: " + ex.getMessage(), true));
            }
        });
    }

    /** Description shown on S1 telling the user what S2 will look like. */
    private static String nextStepHint(PayCode.FieldGroup g) {
        return switch (g) {
            case PAY     -> "Next: pay rate, pay factor, and accrual flags (Income / Overtime / Other Pay).";
            case ALLOW   -> "Next: allowance rate, amount, accrual flags, GST and reporting.";
            case DEDN    -> "Next: deduction %, fund/payee details, remittance frequency and method.";
            case LEAVE   -> "Next: maximum hours, leave factor, accrual flags.";
            case SUPER   -> "Next: super fund details, employee % contribution, remittance.";
            case TAX     -> "Next: tax office details and remittance frequency / method.";
            case TERM_E  -> "Next: confirm Lump Sum E reporting.";
            case CONTRIB -> "Next: employer contribution payee, remittance, GST and reporting.";
            case NONE    -> "Header-only type — clicking Next will save the record immediately.";
        };
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

    /** Checkbox bound to a Y/N flag string. */
    private static CheckBox checkBox(String label, String ynFlag) {
        CheckBox cb = new CheckBox(label);
        cb.setSelected("Y".equalsIgnoreCase(ynFlag == null ? "" : ynFlag.trim()));
        return cb;
    }

    /** "Y" if the checkbox is selected, "N" otherwise. */
    private static String ynOf(CheckBox cb) {
        return cb.isSelected() ? "Y" : "N";
    }

    /** First character of "X — Description" — the leading code, "" if none. */
    private static String codeOf(ChoiceBox<String> cb) {
        String s = cb.getValue();
        if (s == null || s.isBlank()) return "";
        return String.valueOf(s.charAt(0));
    }

    /**
     * ChoiceBox of "X — Description" options. Pre-selects the option whose
     * leading char matches {@code currentCode} (case-insensitive).
     */
    private static ChoiceBox<String> choiceBox(java.util.List<String> options, String currentCode) {
        ChoiceBox<String> cb = new ChoiceBox<>();
        cb.getItems().addAll(options);
        cb.setPrefWidth(220);
        if (currentCode != null && !currentCode.isBlank()) {
            char want = Character.toUpperCase(currentCode.trim().charAt(0));
            for (String opt : options) {
                if (!opt.isEmpty() && Character.toUpperCase(opt.charAt(0)) == want) {
                    cb.setValue(opt);
                    break;
                }
            }
        }
        if (cb.getValue() == null && !options.isEmpty()) cb.setValue(options.get(0));
        return cb;
    }

    /** Remittance frequency options — match COBOL letter codes. */
    private static java.util.List<String> remitFreqOptions() {
        return java.util.List.of(
            "",                       // unset
            "P — Per pay run",
            "M — Monthly",
            "Q — Quarterly",
            "Y — Yearly");
    }

    /** Payment method options — match COBOL letter codes. */
    private static java.util.List<String> payMethodOptions() {
        return java.util.List.of(
            "",                       // unset
            "C — Cheque",
            "D — Direct credit (EFT)",
            "Q — Cleared funds (EFT)");
    }

    /** Fund type — A=APRA fund, S=SMSF (S2D/S2E/S2H). */
    private static java.util.List<String> fundTypeOptions() {
        return java.util.List.of(
            "",
            "A — APRA fund",
            "S — SMSF");
    }

    /** SuperStream contribution category 1-5 (S2D/S2E/S2H). */
    private static java.util.List<String> superStreamCategoryOptions() {
        return java.util.List.of(
            "",
            "1 — Superannuation Guarantee",
            "2 — Award or productivity",
            "3 — Personal contribution",
            "4 — Salary sacrifice",
            "5 — Voluntary employer");
    }

    /** Parse an int field; null on failure (with markError). Empty → 0. */
    private Integer parseIntField(TextField fld, String label) {
        String s = fld.getText().trim();
        if (s.isEmpty()) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException ex) {
            markError(fld, label + " must be a whole number.");
            return null;
        }
    }

    private static String intStr(int v) {
        return v == 0 ? "" : String.valueOf(v);
    }

    /**
     * A small magnifier button that opens {@link FundLookupDialog} filtered
     * by APRA ('A') or SMSF ('S'). On select, the supplied callback runs to
     * push the chosen fund's values into the form.
     */
    private Button fundPickerButton(String fundTypeInd, Window owner,
                                     java.util.function.Consumer<Fund> onPick) {
        Button b = new Button("🔍");   // 🔍
        b.setStyle("-fx-background-color:white;-fx-text-fill:#374151;-fx-border-color:#D0CFC8;" +
                   "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:3 8;-fx-cursor:hand;" +
                   "-fx-font-size:11px;");
        b.setOnAction(e ->
            new FundLookupDialog(fundService, appSession.getCompanyNo(),
                                  fundTypeInd, onPick).show(owner));
        return b;
    }

    /** Wrap a TextField + magnifier in a single horizontal box. */
    private HBox fieldWithPicker(TextField fld, Button picker) {
        HBox box = new HBox(4, fld, picker);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
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
