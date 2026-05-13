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
import com.landmarksoftware.payroll.model.Department;
import com.landmarksoftware.payroll.model.PayGroup;
import com.landmarksoftware.payroll.service.DepartmentService;
import com.landmarksoftware.payroll.service.PayGroupService;
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
 * PAPG01 — Pay Group Maintenance.
 *
 * <p>P1 listbox + tabbed S1 dialog. Covers the {@code pagroup} table only;
 * department maintenance (the COBOL S2 path against {@code padepts}) is a
 * separate concern handled in a future program.
 *
 * <p>Pay-run state columns ({@code payrun_active_*}, {@code last_payrun_*})
 * are owned by PAPP01 and shown read-only on the "Pay Run State" tab.
 */
@Component
public class PayGroupMaintenanceController {

    private static final DateTimeFormatter D_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PayGroupService    groupService;
    private final DepartmentService  deptService;
    private final AppSession         appSession;

    private final ObservableList<PayGroup> rows = FXCollections.observableArrayList();
    private TableView<PayGroup> table;
    private Label               lblStatus;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "papg01-thread");
        t.setDaemon(true);
        return t;
    });

    public PayGroupMaintenanceController(PayGroupService groupService,
                                          DepartmentService deptService,
                                          AppSession appSession) {
        this.groupService = groupService;
        this.deptService  = deptService;
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

        Scene scene = new Scene(root, 960, 580);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    private VBox buildHeader() {
        Label title = new Label("Pay Group Maintenance — PAPG01");
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
        VBox.setVgrow(table, Priority.ALWAYS);
        table.setPlaceholder(new Label("No pay groups found for this company."));

        table.getColumns().addAll(List.of(
            col("Code",        g -> g.paygroup,                       80),
            col("Description", g -> g.desc1,                          260),
            col("Type",        PayGroup::typeLabel,                   100),
            col("Rounding",    PayGroup::roundingLabel,               160),
            col("Payroll Tax", g -> "Y".equalsIgnoreCase(g.allowPayrollTaxFlag)
                                  ? g.payrollTaxPerc.stripTrailingZeros().toPlainString() + " %"
                                  : "—",                              100),
            col("Net Pay GL",  g -> PayGroup.glDisplay(g.netPayAcctMain, g.netPayAcctSub), 90)
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
            PayGroup sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openDialog(sel, stage);
            else showInfo("Edit", "Select a pay group to edit.");
        });
        btnDel.setOnAction(e -> {
            PayGroup sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDelete(sel);
            else showInfo("Delete", "Select a pay group to delete.");
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
                List<PayGroup> data = groupService.findAll(coNo);
                Platform.runLater(() -> {
                    rows.setAll(data);
                    status(data.size() + " pay group(s)", false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    private void confirmDelete(PayGroup sel) {
        int coNo = appSession.getCompanyNo();
        exec.submit(() -> {
            int inUse = 0;
            try {
                inUse = groupService.countAttachedEmployees(coNo, sel.paygroup);
            } catch (Exception ex) {
                final String err = ex.getMessage();
                Platform.runLater(() -> status("Delete check error: " + err, true));
                return;
            }
            final int n = inUse;
            Platform.runLater(() -> {
                if (n > 0) {
                    showInfo("Delete blocked",
                        "Pay group '" + sel.paygroup + "' is attached to " + n +
                        " active employee(s).\nReassign or terminate them before deleting.");
                    return;
                }
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete pay group " + sel.paygroup + " — " + sel.desc1 + "?",
                    ButtonType.OK, ButtonType.CANCEL);
                a.setTitle("Delete"); a.setHeaderText(null);
                a.showAndWait().ifPresent(bt -> {
                    if (bt != ButtonType.OK) return;
                    exec.submit(() -> {
                        try {
                            groupService.delete(coNo, sel.paygroup);
                            Platform.runLater(() -> {
                                status("Deleted: " + sel.paygroup, false);
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

    private void openDialog(PayGroup existing, Window owner) {
        boolean isAdd = (existing == null);
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(isAdd ? "Add Pay Group — PAPG01" : "Edit Pay Group — PAPG01");
        dlg.setResizable(false);

        PayGroup g = isAdd ? new PayGroup() : existing;
        if (isAdd) {
            g.companyNo            = appSession.getCompanyNo();
            g.roundPayUpDownInd    = "N";
            g.allowPayrollTaxFlag  = "N";
            g.useGroupBank         = "N";
            g.paygroupType         = "ACTIVE";
        }

        // ── General tab ────────────────────────────────────────────────
        TextField fCode = tf(g.paygroup, 4);
        fCode.setEditable(isAdd);
        fCode.setDisable(!isAdd);
        TextField fDesc = tf(g.desc1, 35);
        ChoiceBox<String> cbType = new ChoiceBox<>();
        cbType.getItems().addAll("ACTIVE — Active", "ARCH — Archived");
        cbType.getSelectionModel().select("ARCH".equals(g.paygroupType) ? 1 : 0);
        cbType.setPrefWidth(200);

        ChoiceBox<String> cbRound = new ChoiceBox<>();
        cbRound.getItems().addAll("N — No rounding", "U — Round up", "D — Round down");
        cbRound.getSelectionModel().select(switch (g.roundPayUpDownInd == null ? "" : g.roundPayUpDownInd) {
            case "U" -> 1; case "D" -> 2; default -> 0;
        });
        cbRound.setPrefWidth(200);
        TextField fRoundFactor = tf(decStr(g.roundPayFactor), 6);

        CheckBox cbPayrollTax = new CheckBox("Subject to payroll tax");
        cbPayrollTax.setSelected("Y".equalsIgnoreCase(g.allowPayrollTaxFlag));
        TextField fPayTaxPerc = tf(decStr(g.payrollTaxPerc), 8);

        GridPane gGen = formGrid();
        int r = 0;
        addFormRow(gGen, r++, "Pay Group Code *:", fCode);
        addFormRow(gGen, r++, "Description *:",    fDesc);
        addFormRow(gGen, r++, "Type:",             cbType);
        addFormRow(gGen, r++, "Rounding:",         cbRound);
        addFormRow(gGen, r++, "Rounding Factor:",  fRoundFactor);
        addFormRow(gGen, r++, "Payroll Tax:",      cbPayrollTax);
        addFormRow(gGen, r++, "Payroll Tax %:",    fPayTaxPerc);

        // ── GL Accounts tab ────────────────────────────────────────────
        TextField fNetMain = intField(g.netPayAcctMain);
        TextField fNetSub  = intField(g.netPayAcctSub);
        TextField fTaxMain = intField(g.incomeTaxAcctMain);
        TextField fTaxSub  = intField(g.incomeTaxAcctSub);
        TextField fWcMain  = intField(g.wcompClearMain);
        TextField fWcSub   = intField(g.wcompClearSub);
        TextField fPtMain  = intField(g.payTaxClearMain);
        TextField fPtSub   = intField(g.payTaxClearSub);
        TextField fOnMain  = intField(g.oncostsClearMain);
        TextField fOnSub   = intField(g.oncostsClearSub);
        TextField fGstMain = intField(g.gstClearMain);
        TextField fGstSub  = intField(g.gstClearSub);

        GridPane gGl = formGrid();
        r = 0;
        addFormRow(gGl, r++, "Net Pay:",          glPair(fNetMain, fNetSub));
        addFormRow(gGl, r++, "Income Tax:",       glPair(fTaxMain, fTaxSub));
        addFormRow(gGl, r++, "W-Comp Clearing:",  glPair(fWcMain,  fWcSub));
        addFormRow(gGl, r++, "Pay-Tax Clearing:", glPair(fPtMain,  fPtSub));
        addFormRow(gGl, r++, "On-Costs Clear:",   glPair(fOnMain,  fOnSub));
        addFormRow(gGl, r++, "GST Clearing:",     glPair(fGstMain, fGstSub));

        // ── Payslip tab ────────────────────────────────────────────────
        CheckBox cbRdo       = ynBox("Print RDO on payslip",          g.printRdoOnPayslip);
        CheckBox cbSlipReqd  = ynBox("Payslip forms required",         g.slipFormsReqdFlag);
        TextField fSlipUser  = tf(g.slipFormsUserCode, 4);
        CheckBox cbSlipPrint = ynBox("Print payslip",                   g.slipFormsPrintFlag);
        CheckBox cbSlipEmail = ynBox("Email payslip",                   g.slipFormsEmailFlag);
        CheckBox cbCoyName   = ynBox("Print company name",              g.slipPrintCoyName);
        CheckBox cbAbn       = ynBox("Print ABN",                       g.slipPrintAbn);
        CheckBox cbLsl       = ynBox("Print LSL balance",               g.slipPrintLslFlag);
        CheckBox cbAl        = ynBox("Print AL balance",                g.slipPrintAlFlag);
        CheckBox cbSl        = ynBox("Print sick-leave balance",        g.slipPrintSlFlag);
        CheckBox cbAnnSal    = ynBox("Print annual salary",             g.slipPrintAnnualSal);
        TextField fSlipAbn   = tf(g.slipAbn, 16);
        TextField fSlipName  = tf(g.slipPaygroupName, 50);

        GridPane gSlip = formGrid();
        r = 0;
        addFormRow(gSlip, r++, "Payslip name:",  fSlipName);
        addFormRow(gSlip, r++, "ABN:",           fSlipAbn);
        addFormRow(gSlip, r++, "Form code:",     fSlipUser);
        addFormRow(gSlip, r++, "",               cbSlipReqd);
        addFormRow(gSlip, r++, "",               cbSlipPrint);
        addFormRow(gSlip, r++, "",               cbSlipEmail);
        addFormRow(gSlip, r++, "Print options:", cbCoyName);
        addFormRow(gSlip, r++, "",               cbAbn);
        addFormRow(gSlip, r++, "",               cbLsl);
        addFormRow(gSlip, r++, "",               cbAl);
        addFormRow(gSlip, r++, "",               cbSl);
        addFormRow(gSlip, r++, "",               cbAnnSal);
        addFormRow(gSlip, r++, "",               cbRdo);

        // ── Payment Summary tab ────────────────────────────────────────
        CheckBox cbSummReqd  = ynBox("Forms required",       g.summFormsReqdFlag);
        TextField fSummUser  = tf(g.summFormsUserCode, 4);
        CheckBox cbSummPrint = ynBox("Print summaries",      g.summFormsPrintFlag);
        CheckBox cbSummEmail = ynBox("Email summaries",      g.summFormsEmailFlag);

        GridPane gSumm = formGrid();
        r = 0;
        addFormRow(gSumm, r++, "Form code:",  fSummUser);
        addFormRow(gSumm, r++, "",            cbSummReqd);
        addFormRow(gSumm, r++, "",            cbSummPrint);
        addFormRow(gSumm, r++, "",            cbSummEmail);

        // ── STP / Bank tab ─────────────────────────────────────────────
        CheckBox cbUseGroupBank = ynBox("Use pay-group bank account", g.useGroupBank);
        TextField fBankCode     = tf(g.bankCode, 2);
        TextField fSsContact    = tf(g.ssContactCode, 10);
        TextField fOzediClient  = intField(g.stpOzediClientId);

        // Disable bank_code unless useGroupBank is ticked (mirrors COBOL guard)
        Runnable applyBankState = () ->
            fBankCode.setDisable(!cbUseGroupBank.isSelected());
        cbUseGroupBank.selectedProperty().addListener((obs, o, n) -> applyBankState.run());
        applyBankState.run();

        GridPane gStp = formGrid();
        r = 0;
        addFormRow(gStp, r++, "",                cbUseGroupBank);
        addFormRow(gStp, r++, "Bank code:",      fBankCode);
        addFormRow(gStp, r++, "SS contact code:", fSsContact);
        addFormRow(gStp, r++, "OZEDI client id:", fOzediClient);

        // ── Pay Run State tab (read-only) ──────────────────────────────
        GridPane gState = formGrid();
        r = 0;
        addFormRow(gState, r++, "",            new Label("Owned by PAPP01 — read only here."));
        addFormRow(gState, r++, "Weekly:",     stateRow(g.payrunActiveWeek,
                                                          g.payrunNoActiveWeek,
                                                          g.lastPayrunNoWeek,
                                                          g.payrunDateWeek,
                                                          g.paidThruToWeek));
        addFormRow(gState, r++, "Fortnightly:",stateRow(g.payrunActiveFort,
                                                          g.payrunNoActiveFort,
                                                          g.lastPayrunNoFort,
                                                          g.payrunDateFort,
                                                          g.paidThruToFort));
        addFormRow(gState, r++, "4-Weekly:",   stateRow(g.payrunActive4Wk,
                                                          g.payrunNoActive4Wk,
                                                          g.lastPayrunNo4Wk,
                                                          g.payrunDate4Wk,
                                                          g.paidThruTo4Wk));
        addFormRow(gState, r++, "Bi-Monthly:", stateRow(g.payrunActiveBimth,
                                                          g.payrunNoActiveBimth,
                                                          g.lastPayrunNoBimth,
                                                          g.payrunDateBimth,
                                                          g.paidThruToBimth));
        addFormRow(gState, r++, "Monthly:",    stateRow(g.payrunActiveMth,
                                                          g.payrunNoActiveMth,
                                                          g.lastPayrunNoMth,
                                                          g.payrunDateMth,
                                                          g.paidThruToMth));

        // ── Departments tab (PAPG01 P2/S2 — padepts drill-down) ─────────
        // Departments are independent rows in padepts and save immediately
        // on Add/Edit/Delete — like the Bank/EFT splits in PAEM01.
        Node gDepts = buildDepartmentsTab(dlg, isAdd ? "" : g.paygroup, isAdd);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
            tab("General",          gGen),
            tab("GL Accounts",      gGl),
            tab("Departments",      gDepts),
            tab("Payslip",          gSlip),
            tab("Payment Summary",  gSumm),
            tab("STP / Bank",       gStp),
            tab("Pay Run State",    gState));

        Button btnSave   = btnPrimary(isAdd ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(ev -> dlg.close());

        btnSave.setOnAction(ev -> {
            String code = fCode.getText().trim().toUpperCase();
            if (isAdd) {
                if (code.isEmpty()) {
                    markError(fCode, "Pay group code is required.");
                    tabs.getSelectionModel().select(0); return;
                }
                if (code.length() > 4) {
                    markError(fCode, "Pay group code must be 4 characters or fewer.");
                    tabs.getSelectionModel().select(0); return;
                }
            }
            if (fDesc.getText().trim().isEmpty()) {
                markError(fDesc, "Description is required.");
                tabs.getSelectionModel().select(0); return;
            }
            BigDecimal roundFactor = parseDec(fRoundFactor, "Rounding Factor");
            if (roundFactor == null) { tabs.getSelectionModel().select(0); return; }
            BigDecimal payTaxPerc  = parseDec(fPayTaxPerc, "Payroll Tax %");
            if (payTaxPerc == null) { tabs.getSelectionModel().select(0); return; }

            PayGroup out = isAdd ? new PayGroup() : copyKeepingPayRunState(g);
            out.companyNo            = appSession.getCompanyNo();
            out.paygroup             = isAdd ? code : g.paygroup;
            out.desc1                = fDesc.getText().trim();
            // Store the bare value from the choice label (e.g. "ACTIVE", "ARCH")
            out.paygroupType         = firstWord(cbType.getValue());
            out.roundPayUpDownInd    = code(cbRound.getValue(), "N");
            out.roundPayFactor       = roundFactor;
            out.allowPayrollTaxFlag  = cbPayrollTax.isSelected() ? "Y" : "N";
            out.payrollTaxPerc       = payTaxPerc;

            out.netPayAcctMain       = parseInt(fNetMain);
            out.netPayAcctSub        = parseInt(fNetSub);
            out.incomeTaxAcctMain    = parseInt(fTaxMain);
            out.incomeTaxAcctSub     = parseInt(fTaxSub);
            out.wcompClearMain       = parseInt(fWcMain);
            out.wcompClearSub        = parseInt(fWcSub);
            out.payTaxClearMain      = parseInt(fPtMain);
            out.payTaxClearSub       = parseInt(fPtSub);
            out.oncostsClearMain     = parseInt(fOnMain);
            out.oncostsClearSub      = parseInt(fOnSub);
            out.gstClearMain         = parseInt(fGstMain);
            out.gstClearSub          = parseInt(fGstSub);

            out.printRdoOnPayslip    = ynStr(cbRdo);
            out.slipFormsReqdFlag    = ynStr(cbSlipReqd);
            out.slipFormsUserCode    = fSlipUser.getText().trim();
            out.slipFormsPrintFlag   = ynStr(cbSlipPrint);
            out.slipFormsEmailFlag   = ynStr(cbSlipEmail);
            out.slipPrintCoyName     = ynStr(cbCoyName);
            out.slipPrintAbn         = ynStr(cbAbn);
            out.slipPrintLslFlag     = ynStr(cbLsl);
            out.slipPrintAlFlag      = ynStr(cbAl);
            out.slipPrintSlFlag      = ynStr(cbSl);
            out.slipPrintAnnualSal   = ynStr(cbAnnSal);
            out.slipAbn              = fSlipAbn.getText().trim();
            out.slipPaygroupName     = fSlipName.getText().trim();

            out.summFormsReqdFlag    = ynStr(cbSummReqd);
            out.summFormsUserCode    = fSummUser.getText().trim();
            out.summFormsPrintFlag   = ynStr(cbSummPrint);
            out.summFormsEmailFlag   = ynStr(cbSummEmail);

            out.useGroupBank         = ynStr(cbUseGroupBank);
            out.bankCode             = cbUseGroupBank.isSelected()
                                       ? fBankCode.getText().trim().toUpperCase() : "";
            out.ssContactCode        = fSsContact.getText().trim();
            out.stpOzediClientId     = parseInt(fOzediClient);

            String userId = appSession.getUserId();
            exec.submit(() -> {
                try {
                    if (isAdd) {
                        if (groupService.exists(out.companyNo, out.paygroup)) {
                            Platform.runLater(() ->
                                status("Pay group '" + out.paygroup + "' already exists.", true));
                            return;
                        }
                        groupService.insert(out, userId);
                    } else {
                        groupService.update(out, userId);
                    }
                    Platform.runLater(() -> {
                        status((isAdd ? "Added: " : "Updated: ") + out.paygroup, false);
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
            isAdd ? "New Pay Group" : "Edit Pay Group " + g.paygroup));
        top.setPadding(new Insets(14, 20, 8, 20));

        VBox root = new VBox(0, top, tabs, btnBar);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        dlg.setScene(new Scene(root, 640, 560));
        dlg.showAndWait();
    }

    /** When editing, carry forward the pay-run state columns we don't touch. */
    private PayGroup copyKeepingPayRunState(PayGroup g) {
        PayGroup out = new PayGroup();
        out.lastPayrunNoMth       = g.lastPayrunNoMth;
        out.lastPayrunNo4Wk       = g.lastPayrunNo4Wk;
        out.lastPayrunNoBimth     = g.lastPayrunNoBimth;
        out.lastPayrunNoFort      = g.lastPayrunNoFort;
        out.lastPayrunNoWeek      = g.lastPayrunNoWeek;
        out.payrunDateMth         = g.payrunDateMth;
        out.payrunDate4Wk         = g.payrunDate4Wk;
        out.payrunDateBimth       = g.payrunDateBimth;
        out.payrunDateFort        = g.payrunDateFort;
        out.payrunDateWeek        = g.payrunDateWeek;
        out.paidThruToMth         = g.paidThruToMth;
        out.paidThruTo4Wk         = g.paidThruTo4Wk;
        out.paidThruToBimth       = g.paidThruToBimth;
        out.paidThruToFort        = g.paidThruToFort;
        out.paidThruToWeek        = g.paidThruToWeek;
        out.payrunActiveMth       = g.payrunActiveMth;
        out.payrunActive4Wk       = g.payrunActive4Wk;
        out.payrunActiveBimth     = g.payrunActiveBimth;
        out.payrunActiveFort      = g.payrunActiveFort;
        out.payrunActiveWeek      = g.payrunActiveWeek;
        out.payrunNoActiveMth     = g.payrunNoActiveMth;
        out.payrunNoActive4Wk     = g.payrunNoActive4Wk;
        out.payrunNoActiveBimth   = g.payrunNoActiveBimth;
        out.payrunNoActiveFort    = g.payrunNoActiveFort;
        out.payrunNoActiveWeek    = g.payrunNoActiveWeek;
        out.noteNo                = g.noteNo;
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Departments tab — padepts drill-down (PAPG01 P2/S2)
    // ═══════════════════════════════════════════════════════════════════

    private Node buildDepartmentsTab(Window owner, String paygroup, boolean isAdd) {
        TableView<Department> table = new TableView<>();
        ObservableList<Department> rows = FXCollections.observableArrayList();
        table.setItems(rows);
        table.setPlaceholder(new Label(
            isAdd ? "Save the pay group first, then add departments here."
                  : "No departments — Add to create one."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        table.getColumns().add(deptCol("Dept",        d -> d.dept, 70));
        table.getColumns().add(deptCol("Description", d -> d.desc1, 240));
        table.getColumns().add(deptCol("W-Comp Exp",  d -> Department.glDisplay(d.wcompExpMain, d.wcompExpSub), 100));
        table.getColumns().add(deptCol("Sup Exp",     d -> Department.glDisplay(d.employSupAcctMain, d.employSupAcctSub), 100));
        table.getColumns().add(deptCol("Inter-Cr",    d -> Department.glDisplay(d.interCrAcctMain, d.interCrAcctSub), 100));

        final int coNo = appSession.getCompanyNo();
        Runnable reload = () -> {
            if (paygroup.isEmpty()) { rows.clear(); return; }
            try {
                List<Department> data = deptService.findByPaygroup(coNo, paygroup);
                Platform.runLater(() -> rows.setAll(data));
            } catch (Exception ex) {
                Platform.runLater(() -> status("Dept load error: " + ex.getMessage(), true));
            }
        };
        if (!isAdd) exec.submit(reload);

        Button btnAdd  = btnSecondary("+ Add Dept");
        Button btnEdit = btnSecondary("✎ Edit");
        Button btnDel  = btnDanger("🗑 Delete");
        btnAdd.setDisable(isAdd);
        btnEdit.setDisable(true);
        btnDel.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            boolean has = n != null;
            btnEdit.setDisable(!has);
            btnDel.setDisable(!has);
        });

        btnAdd.setOnAction(ev -> openDeptDialog(owner, null, paygroup, reload));
        btnEdit.setOnAction(ev -> {
            Department sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openDeptDialog(owner, sel, paygroup, reload);
        });
        btnDel.setOnAction(ev -> {
            Department sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDeleteDept(sel, reload);
        });

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Department sel = table.getSelectionModel().getSelectedItem();
                if (sel != null) openDeptDialog(owner, sel, paygroup, reload);
            }
        });

        HBox bar = new HBox(8, btnAdd, btnEdit, btnDel);
        bar.setPadding(new Insets(10, 12, 10, 12));
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(0, bar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    private TableColumn<Department, String> deptCol(String header,
                                                     Function<Department, String> fn,
                                                     double w) {
        TableColumn<Department, String> c = new TableColumn<>(header);
        c.setCellValueFactory(p -> new SimpleStringProperty(safe(fn.apply(p.getValue()))));
        c.setPrefWidth(w);
        return c;
    }

    private void openDeptDialog(Window owner, Department existing,
                                 String paygroup, Runnable reload) {
        boolean isAddDept = (existing == null);
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isAddDept ? "Add" : "Edit") + " Department — PAPG01 S2");
        dlg.setResizable(false);

        final int coNo = appSession.getCompanyNo();
        Department d = isAddDept ? new Department() : existing;
        if (isAddDept) {
            d.companyNo = coNo;
            d.paygroup  = paygroup;
        }

        TextField fDept  = tf(d.dept,  4);
        fDept.setEditable(isAddDept);
        fDept.setDisable(!isAddDept);
        TextField fDesc  = tf(d.desc1, 35);

        // GL account pairs — only those the COBOL S2 screen exposes.
        // Other padepts columns (sick/al/lsl prov+exp, oncosts_exp, wage_accr*)
        // are populated elsewhere and carried forward unchanged on update.
        TextField fWcExpM    = intField(d.wcompExpMain),         fWcExpS    = intField(d.wcompExpSub);
        TextField fPtExpM    = intField(d.payTaxExpMain),        fPtExpS    = intField(d.payTaxExpSub);
        TextField fAccSalM   = intField(d.accruedSalAcctMain),   fAccSalS   = intField(d.accruedSalAcctSub);
        TextField fRevAccM   = intField(d.revAccrSalAcctMain),   fRevAccS   = intField(d.revAccrSalAcctSub);
        TextField fAccVarM   = intField(d.accrSalVarAcctMain),   fAccVarS   = intField(d.accrSalVarAcctSub);
        TextField fAccTimM   = intField(d.accruedTimeAcctMain),  fAccTimS   = intField(d.accruedTimeAcctSub);
        TextField fEmpSupM   = intField(d.employSupAcctMain),    fEmpSupS   = intField(d.employSupAcctSub);
        TextField fInterCrM  = intField(d.interCrAcctMain),      fInterCrS  = intField(d.interCrAcctSub);
        TextField fInterChM  = intField(d.interChargeAcctMain),  fInterChS  = intField(d.interChargeAcctSub);

        // ── Identity tab ────────────────────────────────────────────
        GridPane gId = formGrid();
        int r = 0;
        addFormRow(gId, r++, "Pay Group:",     new Label(d.paygroup));
        addFormRow(gId, r++, "Dept Code *:",   fDept);
        addFormRow(gId, r++, "Description *:", fDesc);
        if (d.lastWageAccrDate != null && d.lastWageAccrDate.getYear() > 1900) {
            Label lwa = new Label(d.lastWageAccrDate.format(D_FMT));
            lwa.setStyle("-fx-text-fill:#6B7280;");
            addFormRow(gId, r++, "Last Wage Accr:", lwa);
        }

        // ── GL Accounts tab — single tab matching COBOL S2 layout ───
        GridPane gGl = formGrid();
        r = 0;
        addFormRow(gGl, r++, "Workers Comp Exp:",   glPair(fWcExpM,   fWcExpS));
        addFormRow(gGl, r++, "Payroll Tax Exp:",    glPair(fPtExpM,   fPtExpS));
        addFormRow(gGl, r++, "Inter-Co Credit:",    glPair(fInterCrM, fInterCrS));
        addFormRow(gGl, r++, "Inter-Co Charge:",    glPair(fInterChM, fInterChS));
        addFormRow(gGl, r++, "Accrued Salary:",     glPair(fAccSalM,  fAccSalS));
        addFormRow(gGl, r++, "Reverse Accr Sal:",   glPair(fRevAccM,  fRevAccS));
        addFormRow(gGl, r++, "Accr Sal Variance:",  glPair(fAccVarM,  fAccVarS));
        addFormRow(gGl, r++, "Accrued Time:",       glPair(fAccTimM,  fAccTimS));
        addFormRow(gGl, r++, "Employer Super:",     glPair(fEmpSupM,  fEmpSupS));

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
            tab("Identity",    gId),
            tab("GL Accounts", gGl));

        Button btnSave   = btnPrimary(isAddDept ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(ev -> dlg.close());

        btnSave.setOnAction(ev -> {
            String code = fDept.getText().trim().toUpperCase();
            if (isAddDept) {
                if (code.isEmpty()) {
                    markError(fDept, "Dept code is required.");
                    tabs.getSelectionModel().select(0); return;
                }
                if (code.length() > 4) {
                    markError(fDept, "Dept code must be 4 characters or fewer.");
                    tabs.getSelectionModel().select(0); return;
                }
            }
            if (fDesc.getText().trim().isEmpty()) {
                markError(fDesc, "Description is required.");
                tabs.getSelectionModel().select(0); return;
            }

            Department out = new Department();
            out.companyNo            = coNo;
            out.paygroup             = paygroup;
            out.dept                 = isAddDept ? code : d.dept;
            out.desc1                = fDesc.getText().trim();
            // S2-editable fields
            out.wcompExpMain         = parseInt(fWcExpM);    out.wcompExpSub        = parseInt(fWcExpS);
            out.payTaxExpMain        = parseInt(fPtExpM);    out.payTaxExpSub       = parseInt(fPtExpS);
            out.accruedSalAcctMain   = parseInt(fAccSalM);   out.accruedSalAcctSub  = parseInt(fAccSalS);
            out.revAccrSalAcctMain   = parseInt(fRevAccM);   out.revAccrSalAcctSub  = parseInt(fRevAccS);
            out.accrSalVarAcctMain   = parseInt(fAccVarM);   out.accrSalVarAcctSub  = parseInt(fAccVarS);
            out.accruedTimeAcctMain  = parseInt(fAccTimM);   out.accruedTimeAcctSub = parseInt(fAccTimS);
            out.employSupAcctMain    = parseInt(fEmpSupM);   out.employSupAcctSub   = parseInt(fEmpSupS);
            out.interCrAcctMain      = parseInt(fInterCrM);  out.interCrAcctSub     = parseInt(fInterCrS);
            out.interChargeAcctMain  = parseInt(fInterChM);  out.interChargeAcctSub = parseInt(fInterChS);
            // Carry forward columns not exposed in S2 — they are owned by
            // other COBOL paths (CPCOYCO defaults, wage-accrual processing,
            // leave-provision processing) and must not be blanked on update.
            out.oncostsExpAcctMain   = d.oncostsExpAcctMain;  out.oncostsExpAcctSub  = d.oncostsExpAcctSub;
            out.sickProvAcctMain     = d.sickProvAcctMain;    out.sickProvAcctSub    = d.sickProvAcctSub;
            out.sickExpAcctMain      = d.sickExpAcctMain;     out.sickExpAcctSub     = d.sickExpAcctSub;
            out.alProvAcctMain       = d.alProvAcctMain;      out.alProvAcctSub      = d.alProvAcctSub;
            out.alExpAcctMain        = d.alExpAcctMain;       out.alExpAcctSub       = d.alExpAcctSub;
            out.lslProvAcctMain      = d.lslProvAcctMain;     out.lslProvAcctSub     = d.lslProvAcctSub;
            out.lslExpAcctMain       = d.lslExpAcctMain;      out.lslExpAcctSub      = d.lslExpAcctSub;
            out.wageAcctProvMain     = d.wageAcctProvMain;    out.wageAccrProvSub    = d.wageAccrProvSub;
            out.wageAccrAcctMain     = d.wageAccrAcctMain;    out.wageAccrAcctSub    = d.wageAccrAcctSub;
            out.lastWageAccrDate     = d.lastWageAccrDate;
            out.noteNo               = d.noteNo;

            String userId = appSession.getUserId();
            exec.submit(() -> {
                try {
                    if (isAddDept) {
                        if (deptService.exists(coNo, paygroup, out.dept)) {
                            Platform.runLater(() ->
                                status("Dept '" + out.dept + "' already exists in pay group "
                                       + paygroup + ".", true));
                            return;
                        }
                        deptService.insert(out, userId);
                    } else {
                        deptService.update(out, userId);
                    }
                    Platform.runLater(() -> {
                        status((isAddDept ? "Added" : "Updated") +
                            " dept " + out.dept + " in pay group " + paygroup + ".", false);
                        dlg.close();
                        reload.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() ->
                        status("Dept save error: " + ex.getMessage(), true));
                }
            });
        });

        HBox bar = new HBox(10, btnSave, btnCancel);
        bar.setPadding(new Insets(10, 20, 16, 20));
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setStyle(
            "-fx-background-color:#F2F1EC;" +
            "-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
            "-fx-border-width:0.5 0 0 0;");

        VBox top = new VBox(2, headerLine(
            (isAddDept ? "Add" : "Edit") +
            " department " + (isAddDept ? "" : d.dept) +
            " — pay group " + paygroup));
        top.setPadding(new Insets(14, 20, 8, 20));

        VBox root = new VBox(0, top, tabs, bar);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        dlg.setScene(new Scene(root, 580, 480));
        dlg.showAndWait();
    }

    private void confirmDeleteDept(Department sel, Runnable reload) {
        int coNo = appSession.getCompanyNo();
        exec.submit(() -> {
            int inUse = 0;
            try { inUse = deptService.countAttachedEmployees(coNo, sel.dept); }
            catch (Exception ex) {
                final String err = ex.getMessage();
                Platform.runLater(() -> status("Dept delete check error: " + err, true));
                return;
            }
            final int n = inUse;
            Platform.runLater(() -> {
                if (n > 0) {
                    showInfo("Delete blocked",
                        "Department '" + sel.dept + "' is attached to " + n +
                        " active employee(s).\nReassign or terminate them before deleting.");
                    return;
                }
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete department " + sel.dept + " — " + sel.desc1 + "?",
                    ButtonType.OK, ButtonType.CANCEL);
                a.setTitle("Delete department"); a.setHeaderText(null);
                a.showAndWait().ifPresent(bt -> {
                    if (bt != ButtonType.OK) return;
                    exec.submit(() -> {
                        try {
                            deptService.delete(sel.companyNo, sel.paygroup, sel.dept);
                            Platform.runLater(() -> {
                                status("Deleted dept " + sel.dept, false);
                                reload.run();
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() ->
                                status("Dept delete error: " + ex.getMessage(), true));
                        }
                    });
                });
            });
        });
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private Tab tab(String title, Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setBorder(null);
        return new Tab(title, sp);
    }

    private Node glPair(TextField main, TextField sub) {
        Label dash = new Label(" - ");
        HBox h = new HBox(4, main, dash, sub);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private Node stateRow(String activeFlag, int activePayrunNo, int lastPayrunNo,
                           java.time.LocalDate lastDate, int paidThruJulian) {
        boolean active = "Y".equalsIgnoreCase(activeFlag);
        StringBuilder sb = new StringBuilder();
        sb.append(active ? "ACTIVE" : "Idle");
        if (active) sb.append(" (run #").append(activePayrunNo).append(")");
        sb.append("    last #").append(lastPayrunNo);
        if (lastDate != null && lastDate.getYear() > 1900) {
            sb.append(" on ").append(lastDate.format(D_FMT));
        }
        if (paidThruJulian != 0) sb.append("    paid-thru julian ").append(paidThruJulian);
        Label l = new Label(sb.toString());
        l.setStyle("-fx-font-family:monospace;-fx-text-fill:#374151;");
        return l;
    }

    private GridPane formGrid() {
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(10); g.setPadding(new Insets(16));
        return g;
    }

    private CheckBox ynBox(String label, String flag) {
        CheckBox cb = new CheckBox(label);
        cb.setSelected("Y".equalsIgnoreCase(flag));
        return cb;
    }

    private static String ynStr(CheckBox cb) { return cb.isSelected() ? "Y" : "N"; }

    private TextField intField(int v) {
        TextField f = tf(v == 0 ? "" : String.valueOf(v), 8);
        f.setPrefWidth(90);
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

    private static String firstWord(String choiceValue) {
        if (choiceValue == null) return "";
        int sp = choiceValue.indexOf(' ');
        return sp < 0 ? choiceValue.trim() : choiceValue.substring(0, sp).trim();
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

    private TableColumn<PayGroup, String> col(String header,
                                                Function<PayGroup, String> fn, double w) {
        TableColumn<PayGroup, String> c = new TableColumn<>(header);
        c.setCellValueFactory(p -> new SimpleStringProperty(safe(fn.apply(p.getValue()))));
        c.setPrefWidth(w);
        return c;
    }

    private void addFormRow(GridPane g, int row, String label, Node ctrl) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(140);
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
