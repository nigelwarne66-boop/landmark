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
package com.landmarksoftware.ui.gl;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.model.CompanyRow;
import com.landmarksoftware.model.GlChartAccount;
import com.landmarksoftware.payroll.ui.BatchPreviewDialog;
import com.landmarksoftware.payroll.ui.BatchProgressDialog;
import com.landmarksoftware.repository.CompanyRepository;
import com.landmarksoftware.service.gl.GlChartBulkCreateService;
import com.landmarksoftware.service.gl.GlChartDuplicateService;
import com.landmarksoftware.service.gl.GlChartRenameService;
import com.landmarksoftware.service.gl.GlChartService;
import com.landmarksoftware.service.gl.GlCodesService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
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
 * CPCM01 — Chart of Accounts Maintenance (glchart). Main-account list (P1)
 * drills into a tabbed editor (S1 General + a Sub-Accounts tab that's
 * itself a drill-down list, S2) — same "child rows save immediately,
 * independent of the parent Save/Cancel" pattern as PAPG01's Departments
 * tab. Three toolbar buttons open the bulk utilities that hang off this
 * screen in COBOL: Duplicate (CPCM09, cross-company range copy), Create
 * (CPCM10, bulk sub-account fan-out), Change Name (CPCM05, bulk rename +
 * report-writer cascade) — all via preview-then-apply, same as the Wave 2
 * payroll batch programs.
 */
@Component
public class ChartOfAccountsController {

    private final GlChartService svc;
    private final GlCodesService codesService;
    private final GlChartDuplicateService dupService;
    private final GlChartBulkCreateService createService;
    private final GlChartRenameService renameService;
    private final CompanyRepository companyRepo;
    private final AppSession session;

    private final ObservableList<GlChartAccount> rows = FXCollections.observableArrayList();
    private TableView<GlChartAccount> table;
    private Label lblStatus;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cpcm01-thread");
        t.setDaemon(true);
        return t;
    });

    public ChartOfAccountsController(GlChartService svc, GlCodesService codesService,
                                     GlChartDuplicateService dupService, GlChartBulkCreateService createService,
                                     GlChartRenameService renameService, CompanyRepository companyRepo,
                                     AppSession session) {
        this.svc = svc;
        this.codesService = codesService;
        this.dupService = dupService;
        this.createService = createService;
        this.renameService = renameService;
        this.companyRepo = companyRepo;
        this.session = session;
    }

    // ── Entry point ───────────────────────────────────────────────────────

    public Scene buildScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildHeader());
        root.setCenter(buildContent(stage));
        root.setBottom(buildStatusBar());
        loadList();
        Scene scene = new Scene(root, 900, 600);
        scene.getStylesheets().add(getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    private HBox buildHeader() {
        Label t = new Label("Chart of Accounts Maintenance");
        t.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("CPCM01 · " + session.getCompanyName());
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        HBox bar = new HBox(new VBox(2, t, sub));
        bar.setPadding(new Insets(14, 20, 14, 20));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color:#FFFFFF;-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;-fx-border-width:0 0 0.5 0;");
        return bar;
    }

    private VBox buildContent(Stage stage) {
        table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(table, Priority.ALWAYS);
        table.setPlaceholder(new Label("No main accounts found for this company."));
        table.getColumns().addAll(List.of(
            col("Account",     a -> String.valueOf(a.acctMainNo),               90),
            col("Description", a -> a.desc1,                                    280),
            col("Fin Acct?",   a -> yn(a.finAcctFlag),                          80),
            col("Cash Acct?",  a -> yn(a.cashAcctFlag),                         80),
            col("Stat Acct?",  a -> yn(a.statAcctFlag),                         80),
            col("Non Post?",   a -> yn(a.allNonPostingFlag),                    80)
        ));
        table.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && sel() != null) openMainEditor(sel(), stage); });

        Button btnNew  = btnPrimary("+ New");
        Button btnEdit = btnSecondary("✎ Edit");
        Button btnDel  = btnDanger("✕ Delete");
        Button btnRef  = btnSecondary("↺");
        Button btnDup  = btnSecondary("⧉ Duplicate…");
        Button btnCreate = btnSecondary("+ Create…");
        Button btnRename = btnSecondary("✎ Change Name…");
        btnNew.setOnAction(e -> openMainEditor(null, stage));
        btnEdit.setOnAction(e -> { if (sel() != null) openMainEditor(sel(), stage); else info("Edit", "Select an account."); });
        btnDel.setOnAction(e -> { if (sel() != null) confirmDeleteMain(sel()); else info("Delete", "Select an account."); });
        btnRef.setOnAction(e -> loadList());
        btnDup.setTooltip(new Tooltip("CPCM09 — copy an account range into another company"));
        btnCreate.setTooltip(new Tooltip("CPCM10 — bulk-create sub-accounts across a main-account range"));
        btnRename.setTooltip(new Tooltip("CPCM05 — bulk-rename sub-accounts, optionally cascading into report-writer lines"));
        btnDup.setOnAction(e -> openDuplicateDialog(stage));
        btnCreate.setOnAction(e -> openCreateDialog(stage));
        btnRename.setOnAction(e -> openRenameDialog(stage));

        HBox toolbar = new HBox(8, btnNew, btnEdit, btnDel, new Separator(Orientation.VERTICAL), btnRef,
            new Separator(Orientation.VERTICAL), btnDup, btnCreate, btnRename);
        toolbar.setPadding(new Insets(10, 16, 10, 16));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle("-fx-background-color:#F8F8F6;-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;-fx-border-width:0 0 0.5 0;");
        return new VBox(0, toolbar, table);
    }

    private GlChartAccount sel() { return table.getSelectionModel().getSelectedItem(); }

    private void loadList() {
        status("Loading…", false);
        exec.submit(() -> {
            try {
                List<GlChartAccount> data = svc.findMainAccounts(session.getCompanyNo());
                Platform.runLater(() -> { rows.setAll(data); status(data.size() + " account(s)", false); });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    private void confirmDeleteMain(GlChartAccount a) {
        int co = session.getCompanyNo();
        exec.submit(() -> {
            boolean posted = svc.hasPostedJournals(co, a.acctMainNo);
            Platform.runLater(() -> {
                if (posted) {
                    info("Delete", "Journals have posted to account " + a.acctMainNo + " or its sub-accounts — cannot delete.");
                    return;
                }
                Alert al = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete account " + a.acctMainNo + " — " + a.desc1 + " and all its sub-accounts?",
                    ButtonType.YES, ButtonType.NO);
                al.setTitle("Confirm Delete"); al.setHeaderText(null);
                al.showAndWait().ifPresent(b -> { if (b == ButtonType.YES) {
                    exec.submit(() -> {
                        try { svc.deleteMainAccount(co, a.acctMainNo);
                            Platform.runLater(() -> { loadList(); status("Deleted account " + a.acctMainNo, false); });
                        } catch (Exception ex) { Platform.runLater(() -> status("Delete error: " + ex.getMessage(), true)); }
                    });
                }});
            });
        });
    }

    // ── Main account editor (S1 General + Sub-Accounts drill tab) ─────────

    private void openMainEditor(GlChartAccount existing, Window owner) {
        boolean isNew = existing == null;
        GlChartAccount a = isNew ? new GlChartAccount() : existing;

        Stage dlg = new Stage();
        dlg.initOwner(owner); dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isNew ? "New" : "Edit") + " Main Account" + (isNew ? "" : "  #" + a.acctMainNo));

        TextField fAcct = tf(isNew ? "" : String.valueOf(a.acctMainNo), 8);
        fAcct.setEditable(isNew); fAcct.setDisable(!isNew);
        TextField fDesc = tf(a.desc1, 35);
        TextField fAbbrev = tf(a.abbrevDesc, 20);
        TextField fAlpha = tf(a.alphaCode, 35);
        TextField fType = tf(a.acctType, 4);
        ChoiceBox<String> cbDrCr = choiceBox(List.of("D — Debit", "C — Credit"), a.drCrInd);
        ChoiceBox<String> cbPlBs = choiceBox(List.of("B — Balance Sheet", "P — Profit & Loss"), a.plBsInd);
        CheckBox cbFin = checkBox("Financial account", a.finAcctFlag);
        CheckBox cbCash = checkBox("Cash accounting account", a.cashAcctFlag);
        CheckBox cbStat = checkBox("Statistical account", a.statAcctFlag);
        CheckBox cbRollStat = checkBox("Roll statistical balances at year end", a.rollStatOpenBals);
        CheckBox cbForCurr = checkBox("Revalue for parent currency", a.forCurrRevalueInd);
        CheckBox cbDefAnalysis = checkBox("Default analysis code required", a.defAnalysisCodeInd);
        CheckBox cbNonPost = checkBox("Non-posting (header/group) account", a.allNonPostingFlag);

        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(8); form.setPadding(new Insets(16));
        int r = 0;
        form.add(lbl("Account No *:"), 0, r); form.add(fAcct, 1, r++);
        form.add(lbl("Description *:"), 0, r); form.add(fDesc, 1, r++);
        form.add(lbl("Abbrev Desc *:"), 0, r); form.add(fAbbrev, 1, r++);
        form.add(lbl("Alpha Code:"), 0, r); form.add(fAlpha, 1, r++);
        form.add(lbl("Account Type:"), 0, r); form.add(fType, 1, r++);
        form.add(lbl("Debit/Credit:"), 0, r); form.add(cbDrCr, 1, r++);
        form.add(lbl("P&L / Bal Sheet:"), 0, r); form.add(cbPlBs, 1, r++);
        form.add(cbFin, 1, r++);
        form.add(cbCash, 1, r++);
        form.add(cbStat, 1, r++);
        form.add(cbRollStat, 1, r++);
        form.add(cbForCurr, 1, r++);
        form.add(cbDefAnalysis, 1, r++);
        form.add(cbNonPost, 1, r++);
        ScrollPane generalScroll = new ScrollPane(form);
        generalScroll.setFitToWidth(true);

        Tab tabGeneral = new Tab("General", generalScroll);
        tabGeneral.setClosable(false);
        Tab tabSubs = new Tab("Sub-Accounts", buildSubAccountsTab(dlg, isNew ? 0 : a.acctMainNo, isNew));
        tabSubs.setClosable(false);
        TabPane tabs = new TabPane(tabGeneral, tabSubs);

        Button btnSave = btnPrimary(isNew ? "Add" : "Save");
        Button btnClose = btnSecondary("Close");
        btnSave.setDefaultButton(true);
        btnClose.setOnAction(e -> dlg.close());
        btnSave.setOnAction(e -> {
            Integer acctNo = parseInt(fAcct.getText());
            if (acctNo == null || acctNo <= 0) { markError(fAcct, "Enter a valid account number."); return; }
            String desc = fDesc.getText().trim();
            if (desc.isEmpty()) { markError(fDesc, "Description is required."); return; }
            String abbrev = fAbbrev.getText().trim();
            if (abbrev.isEmpty()) { markError(fAbbrev, "Abbreviated description is required."); return; }

            a.acctMainNo = acctNo; a.desc1 = desc; a.abbrevDesc = abbrev;
            a.alphaCode = fAlpha.getText().trim(); a.acctType = fType.getText().trim();
            a.drCrInd = codeOf(cbDrCr); a.plBsInd = codeOf(cbPlBs);
            a.finAcctFlag = ynOf(cbFin); a.cashAcctFlag = ynOf(cbCash); a.statAcctFlag = ynOf(cbStat);
            a.rollStatOpenBals = ynOf(cbRollStat); a.forCurrRevalueInd = ynOf(cbForCurr);
            a.defAnalysisCodeInd = ynOf(cbDefAnalysis); a.allNonPostingFlag = ynOf(cbNonPost);

            int co = session.getCompanyNo(); String user = session.getUserId();
            btnSave.setDisable(true);
            exec.submit(() -> {
                try {
                    if (isNew && svc.exists(co, a.acctMainNo, 0)) {
                        Platform.runLater(() -> { btnSave.setDisable(false); markError(fAcct, "Account " + a.acctMainNo + " already exists."); });
                        return;
                    }
                    svc.saveMainAccount(co, a, isNew, user);
                    Platform.runLater(() -> {
                        dlg.close(); loadList();
                        status((isNew ? "Added " : "Updated ") + "account " + a.acctMainNo, false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> { btnSave.setDisable(false); status("Save error: " + ex.getMessage(), true); });
                }
            });
        });
        HBox btnBar = new HBox(10, btnSave, btnClose);
        btnBar.setAlignment(Pos.CENTER_RIGHT); btnBar.setPadding(new Insets(10, 16, 14, 16));

        BorderPane root = new BorderPane(tabs);
        root.setBottom(btnBar);
        dlg.setScene(new Scene(root, 620, 640));
        dlg.showAndWait();
    }

    // ── Sub-Accounts drill tab (P2/S2) ─────────────────────────────────────

    private Node buildSubAccountsTab(Window dlgOwner, int acctMainNo, boolean isNewMain) {
        if (isNewMain) {
            Label hint = new Label("Save the main account first, then add sub-accounts here.");
            hint.setStyle("-fx-text-fill:#888780;-fx-padding:20;");
            return new VBox(hint);
        }
        ObservableList<GlChartAccount> subRows = FXCollections.observableArrayList();
        TableView<GlChartAccount> subTable = new TableView<>(subRows);
        subTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        subTable.setPlaceholder(new Label("No sub-accounts."));
        subTable.getColumns().addAll(List.of(
            col2("Sub No",      a -> String.valueOf(a.acctSubNo),  70),
            col2("Description", a -> a.desc1,                      240),
            col2("Posting",     a -> safe(a.postingFlag),           70)
        ));
        VBox.setVgrow(subTable, Priority.ALWAYS);

        Runnable reload = () -> exec.submit(() -> {
            List<GlChartAccount> data = svc.findSubAccounts(session.getCompanyNo(), acctMainNo);
            Platform.runLater(() -> subRows.setAll(data));
        });
        reload.run();

        Button btnAdd = btnSecondary("+ Add");
        Button btnEdit = btnSecondary("✎ Edit");
        Button btnDel = btnDanger("✕ Delete");
        btnAdd.setOnAction(e -> openSubEditor(null, acctMainNo, reload, dlgOwner));
        btnEdit.setOnAction(e -> {
            GlChartAccount s = subTable.getSelectionModel().getSelectedItem();
            if (s != null) openSubEditor(s, acctMainNo, reload, dlgOwner); else info("Edit", "Select a sub-account.");
        });
        btnDel.setOnAction(e -> {
            GlChartAccount s = subTable.getSelectionModel().getSelectedItem();
            if (s == null) { info("Delete", "Select a sub-account."); return; }
            int co = session.getCompanyNo();
            exec.submit(() -> {
                boolean posted = svc.hasPostedJournals(co, acctMainNo, s.acctSubNo);
                Platform.runLater(() -> {
                    if (posted) { info("Delete", "Journals have posted to this sub-account — cannot delete."); return; }
                    Alert al = new Alert(Alert.AlertType.CONFIRMATION,
                        "Delete sub-account " + acctMainNo + "." + s.acctSubNo + " — " + s.desc1 + "?",
                        ButtonType.YES, ButtonType.NO);
                    al.setTitle("Confirm Delete"); al.setHeaderText(null);
                    al.showAndWait().ifPresent(b -> { if (b == ButtonType.YES) {
                        exec.submit(() -> {
                            try { svc.deleteSubAccount(co, acctMainNo, s.acctSubNo);
                                Platform.runLater(reload);
                            } catch (Exception ex) { Platform.runLater(() -> info("Delete error", ex.getMessage())); }
                        });
                    }});
                });
            });
        });
        HBox bar = new HBox(8, btnAdd, btnEdit, btnDel);
        bar.setPadding(new Insets(8, 0, 8, 0));

        VBox root = new VBox(6, bar, subTable);
        root.setPadding(new Insets(12));
        return root;
    }

    private void openSubEditor(GlChartAccount existing, int acctMainNo, Runnable onSaved, Window owner) {
        boolean isNew = existing == null;
        GlChartAccount s = isNew ? new GlChartAccount() : existing;
        if (isNew) s.acctMainNo = acctMainNo;

        Stage dlg = new Stage();
        dlg.initOwner(owner); dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isNew ? "Add" : "Edit") + " Sub-Account — " + acctMainNo + (isNew ? "" : "." + s.acctSubNo));

        TextField fSub = tf(isNew ? "" : String.valueOf(s.acctSubNo), 6);
        fSub.setEditable(isNew); fSub.setDisable(!isNew);
        Label lblCodeDesc = new Label();
        lblCodeDesc.setStyle("-fx-text-fill:#888780;-fx-font-size:11px;");
        fSub.textProperty().addListener((o, ov, nv) -> {
            Integer sub = parseInt(nv);
            if (sub != null && isNew) {
                exec.submit(() -> {
                    String d = codesService.descOf(session.getCompanyNo(), sub);
                    Platform.runLater(() -> {
                        lblCodeDesc.setText(d.isBlank() ? "(no matching sub account code)" : "Code: " + d);
                    });
                });
            }
        });
        TextField fDesc = tf(s.desc1, 35);
        TextField fAbbrev = tf(s.abbrevDesc, 20);
        TextField fAlpha = tf(s.alphaCode, 35);
        TextField fCounterMain = tf(s.counterBalMainNo == 0 ? "" : String.valueOf(s.counterBalMainNo), 8);
        TextField fCounterSub = tf(String.valueOf(s.counterBalSubNo), 6);
        TextField fConsolMain = tf(s.consolMainNo == 0 ? "" : String.valueOf(s.consolMainNo), 8);
        TextField fConsolSub = tf(String.valueOf(s.consolSubNo), 6);
        TextField fDistType = tf(s.distType, 1);
        TextField fDistCode = tf(s.distCode, 6);
        TextField fAllocMain = tf(s.allocMainNo == 0 ? "" : String.valueOf(s.allocMainNo), 8);
        TextField fAllocSub = tf(String.valueOf(s.allocSubNo), 6);
        TextField fCostAllocType = tf(s.costAllocAcctType, 1);
        TextField fAllocMethod = tf(s.allocMethod, 1);
        CheckBox cbPrepay = checkBox("Create prepayment journals", s.prepaymentAcctFlag);
        TextField fGst = tf(s.gstPercClaimable == null ? "" : s.gstPercClaimable.toPlainString(), 8);
        TextField fCompress = tf(s.compressCode, 1);
        CheckBox cbRecon = checkBox("Use reconciliation IDs", s.useReconIdsFlag);
        TextField fPosting = tf(s.postingFlag, 1);
        TextField fSubCoy = tf(s.subCoyNo == 0 ? "" : String.valueOf(s.subCoyNo), 6);
        TextField fCashBank = tf(s.cashAcctBankNo == 0 ? "" : String.valueOf(s.cashAcctBankNo), 6);
        CheckBox cbAnalysis = checkBox("Analysis codes required", s.analysisCodeInd);
        CheckBox cbGuardian = checkBox("Export to Guardian", s.guardianExportFlag);

        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(8); form.setPadding(new Insets(16));
        int r = 0;
        form.add(lbl("Sub Account *:"), 0, r); form.add(new HBox(8, fSub, lblCodeDesc), 1, r++);
        form.add(lbl("Description *:"), 0, r); form.add(fDesc, 1, r++);
        form.add(lbl("Abbrev Desc:"), 0, r); form.add(fAbbrev, 1, r++);
        form.add(lbl("Alpha Code:"), 0, r); form.add(fAlpha, 1, r++);
        form.add(sectionHeader("Distribution & consolidation"), 0, r++, 2, 1);
        form.add(lbl("Counter-bal Main/Sub:"), 0, r); form.add(new HBox(6, fCounterMain, fCounterSub), 1, r++);
        form.add(lbl("Consol Main/Sub *:"), 0, r); form.add(new HBox(6, fConsolMain, fConsolSub), 1, r++);
        form.add(lbl("Dist Type/Code:"), 0, r); form.add(new HBox(6, fDistType, fDistCode), 1, r++);
        form.add(lbl("Cost Alloc Main/Sub:"), 0, r); form.add(new HBox(6, fAllocMain, fAllocSub), 1, r++);
        form.add(lbl("Cost Alloc Type/Method:"), 0, r); form.add(new HBox(6, fCostAllocType, fAllocMethod), 1, r++);
        form.add(sectionHeader("Posting & tax"), 0, r++, 2, 1);
        form.add(lbl("Posting Flag:"), 0, r); form.add(fPosting, 1, r++);
        form.add(lbl("Sub Coy No:"), 0, r); form.add(fSubCoy, 1, r++);
        form.add(lbl("Cash Acct Bank No:"), 0, r); form.add(fCashBank, 1, r++);
        form.add(lbl("GST % Claimable:"), 0, r); form.add(fGst, 1, r++);
        form.add(lbl("Compress Code:"), 0, r); form.add(fCompress, 1, r++);
        form.add(cbPrepay, 1, r++);
        form.add(cbRecon, 1, r++);
        form.add(cbAnalysis, 1, r++);
        form.add(cbGuardian, 1, r++);

        Button ok = btnPrimary(isNew ? "Add" : "Save");
        Button cancel = btnSecondary("Cancel");
        ok.setDefaultButton(true);
        cancel.setOnAction(e -> dlg.close());
        ok.setOnAction(e -> {
            Integer sub = parseInt(fSub.getText());
            if (sub == null || sub <= 0) { markError(fSub, "Enter a valid sub account number."); return; }
            String desc = fDesc.getText().trim();
            if (desc.isEmpty()) { markError(fDesc, "Description is required."); return; }
            Integer consolMain = parseIntOrZero(fConsolMain);
            if (consolMain == null || consolMain <= 0) { markError(fConsolMain, "Consolidation main account is required."); return; }

            s.acctSubNo = sub; s.desc1 = desc; s.abbrevDesc = fAbbrev.getText().trim(); s.alphaCode = fAlpha.getText().trim();
            s.counterBalMainNo = nvlInt(parseIntOrZero(fCounterMain)); s.counterBalSubNo = nvlInt(parseIntOrZero(fCounterSub));
            s.consolMainNo = consolMain; s.consolSubNo = nvlInt(parseIntOrZero(fConsolSub));
            s.distType = fDistType.getText().trim(); s.distCode = fDistCode.getText().trim();
            s.allocMainNo = nvlInt(parseIntOrZero(fAllocMain)); s.allocSubNo = nvlInt(parseIntOrZero(fAllocSub));
            s.costAllocAcctType = fCostAllocType.getText().trim(); s.allocMethod = fAllocMethod.getText().trim();
            s.prepaymentAcctFlag = ynOf(cbPrepay);
            s.gstPercClaimable = parseDecOrZero(fGst.getText());
            s.compressCode = fCompress.getText().trim();
            s.useReconIdsFlag = ynOf(cbRecon);
            s.postingFlag = fPosting.getText().trim();
            s.subCoyNo = nvlInt(parseIntOrZero(fSubCoy));
            s.cashAcctBankNo = nvlInt(parseIntOrZero(fCashBank));
            s.analysisCodeInd = ynOf(cbAnalysis);
            s.guardianExportFlag = ynOf(cbGuardian);

            int co = session.getCompanyNo(); String user = session.getUserId();
            ok.setDisable(true);
            exec.submit(() -> {
                try {
                    if (isNew && svc.exists(co, acctMainNo, sub)) {
                        Platform.runLater(() -> { ok.setDisable(false); markError(fSub, "Sub-account " + sub + " already exists."); });
                        return;
                    }
                    svc.saveSubAccount(co, s, isNew, user);
                    Platform.runLater(() -> { dlg.close(); onSaved.run(); });
                } catch (Exception ex) {
                    Platform.runLater(() -> { ok.setDisable(false); status("Save error: " + ex.getMessage(), true); });
                }
            });
        });
        HBox bar = new HBox(10, ok, cancel);
        bar.setAlignment(Pos.CENTER_RIGHT); bar.setPadding(new Insets(10, 16, 14, 16));
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        VBox root = new VBox(0, scroll, bar);
        dlg.setScene(new Scene(root, 480, 620));
        dlg.showAndWait();
    }

    // ── CPCM09 — Duplicate ───────────────────────────────────────────────

    private void openDuplicateDialog(Window owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner); dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Duplicate Chart of Accounts — CPCM09");

        ComboBox<CompanyRow> cbToCompany = new ComboBox<>();
        cbToCompany.setPromptText("Loading companies…");
        exec.submit(() -> {
            List<CompanyRow> cos = companyRepo.findAll();
            Platform.runLater(() -> cbToCompany.setItems(FXCollections.observableArrayList(cos)));
        });
        cbToCompany.setConverter(companyConverter());
        cbToCompany.setPrefWidth(260);

        TextField fStartMain = tf("", 8), fEndMain = tf("999999", 8);
        TextField fStartSub = tf("0", 6), fEndSub = tf("9999", 6);
        RadioButton rbDetails = new RadioButton("Overwrite full account setup");
        RadioButton rbDescOnly = new RadioButton("Description only");
        ToggleGroup tg = new ToggleGroup(); rbDetails.setToggleGroup(tg); rbDescOnly.setToggleGroup(tg);
        rbDetails.setSelected(true);

        GridPane g = new GridPane(); g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(16));
        int r = 0;
        g.add(lbl("To Company *:"), 0, r); g.add(cbToCompany, 1, r++);
        g.add(lbl("Main Account:"), 0, r); g.add(new HBox(6, fStartMain, new Label("to"), fEndMain), 1, r++);
        g.add(lbl("Sub Account:"), 0, r); g.add(new HBox(6, fStartSub, new Label("to"), fEndSub), 1, r++);
        g.add(lbl("If already on file:"), 0, r); g.add(new VBox(4, rbDetails, rbDescOnly), 1, r++);

        Button btnPreview = btnPrimary("Preview…");
        Button btnClose = btnSecondary("Close");
        btnClose.setOnAction(e -> dlg.close());
        btnPreview.setOnAction(e -> {
            CompanyRow to = cbToCompany.getValue();
            if (to == null) { info("Duplicate", "Select the target company."); return; }
            Integer sm = parseInt(fStartMain.getText()), em = parseInt(fEndMain.getText());
            Integer ss = parseInt(fStartSub.getText()), es = parseInt(fEndSub.getText());
            if (sm == null || em == null || ss == null || es == null) { info("Duplicate", "Enter a valid account range."); return; }
            int from = session.getCompanyNo(), toNo = to.getCompanyNo();
            exec.submit(() -> {
                List<GlChartDuplicateService.Candidate> cands = dupService.preview(from, toNo, sm, em, ss, es);
                Platform.runLater(() -> {
                    if (cands.isEmpty()) { info("Duplicate", "No accounts matched that range."); return; }
                    var preview = new BatchPreviewDialog<>("Duplicate Chart of Accounts",
                        "Copy " + cands.size() + " account(s) from " + session.getCompanyName() + " into " + to.getName() + ".",
                        cands, List.of(
                            new BatchPreviewDialog.Column<GlChartDuplicateService.Candidate>("Account", c -> c.acctMainNo() + (c.acctSubNo() == 0 ? "" : "." + c.acctSubNo())),
                            new BatchPreviewDialog.Column<GlChartDuplicateService.Candidate>("Description", GlChartDuplicateService.Candidate::desc1),
                            new BatchPreviewDialog.Column<GlChartDuplicateService.Candidate>("In Target?", c -> c.existsInTarget() ? "Already exists — will overwrite per option" : "New")
                        ));
                    if (!preview.showAndAwait(dlg)) return;
                    boolean details = rbDetails.isSelected();
                    String user = session.getUserId();
                    Task<Integer> task = new Task<>() {
                        @Override protected Integer call() {
                            updateMessage("Copying accounts…");
                            return dupService.apply(from, toNo, sm, em, ss, es, details, !details, null, user);
                        }
                    };
                    BatchProgressDialog.runWithProgress(dlg, "Duplicating accounts…", task,
                        n -> { info("Duplicate Complete", n + " account(s) copied into " + to.getName() + "."); dlg.close(); },
                        err -> info("Duplicate Failed", err.getMessage()));
                });
            });
        });
        HBox bar = new HBox(10, btnPreview, btnClose);
        bar.setAlignment(Pos.CENTER_RIGHT); bar.setPadding(new Insets(10, 16, 14, 16));
        VBox root = new VBox(0, g, bar);
        dlg.setScene(new Scene(root, 460, 320));
        dlg.showAndWait();
    }

    // ── CPCM10 — Create ──────────────────────────────────────────────────

    private void openCreateDialog(Window owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner); dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Create Sub Accounts — CPCM10");

        TextField fStartMain = tf("", 8), fEndMain = tf("999999", 8);
        RadioButton rbGroup = new RadioButton("Report group range");
        RadioButton rbCode = new RadioButton("Sub account code range");
        ToggleGroup tg = new ToggleGroup(); rbGroup.setToggleGroup(tg); rbCode.setToggleGroup(tg);
        rbCode.setSelected(true);
        TextField fStartCode = tf("", 8), fEndCode = tf("9999", 8);
        CheckBox cbDesc = checkBox("Set description from sub account code", "Y");
        TextField fPosting = tf("", 1);

        GridPane g = new GridPane(); g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(16));
        int r = 0;
        g.add(lbl("Main Account Range *:"), 0, r); g.add(new HBox(6, fStartMain, new Label("to"), fEndMain), 1, r++);
        g.add(lbl("Fan out by:"), 0, r); g.add(new VBox(4, rbCode, rbGroup), 1, r++);
        g.add(lbl("Code/Group Range:"), 0, r); g.add(new HBox(6, fStartCode, new Label("to"), fEndCode), 1, r++);
        g.add(new Label(), 0, r); g.add(cbDesc, 1, r++);
        g.add(lbl("Posting Flag:"), 0, r); g.add(fPosting, 1, r++);

        Button btnPreview = btnPrimary("Preview…");
        Button btnClose = btnSecondary("Close");
        btnClose.setOnAction(e -> dlg.close());
        btnPreview.setOnAction(e -> {
            Integer sm = parseInt(fStartMain.getText()), em = parseInt(fEndMain.getText());
            if (sm == null || em == null) { info("Create", "Enter a valid main account range."); return; }
            String mode = rbGroup.isSelected() ? "G" : "S";
            String sc = fStartCode.getText().trim(), ec = fEndCode.getText().trim();
            boolean useDesc = cbDesc.isSelected();
            int co = session.getCompanyNo();
            exec.submit(() -> {
                List<GlChartBulkCreateService.Candidate> cands = createService.preview(co, sm, em, mode, sc, ec, useDesc);
                Platform.runLater(() -> {
                    if (cands.isEmpty()) { info("Create", "No main accounts / codes matched that range."); return; }
                    long newCount = cands.stream().filter(c -> !c.alreadyExists()).count();
                    var preview = new BatchPreviewDialog<>("Create Sub Accounts",
                        newCount + " new sub-account(s) will be created (existing combinations are skipped).",
                        cands, List.of(
                            new BatchPreviewDialog.Column<GlChartBulkCreateService.Candidate>("Account", c -> c.acctMainNo() + "." + c.acctSubNo()),
                            new BatchPreviewDialog.Column<GlChartBulkCreateService.Candidate>("Description", GlChartBulkCreateService.Candidate::desc1),
                            new BatchPreviewDialog.Column<GlChartBulkCreateService.Candidate>("Status", c -> c.alreadyExists() ? "Already exists — skipped" : "Will create")
                        ), "Create " + newCount + " sub-account(s)");
                    if (!preview.showAndAwait(dlg)) return;
                    String user = session.getUserId();
                    Task<Integer> task = new Task<>() {
                        @Override protected Integer call() {
                            updateMessage("Creating sub-accounts…");
                            return createService.apply(co, cands, fPosting.getText().trim(), user);
                        }
                    };
                    BatchProgressDialog.runWithProgress(dlg, "Creating sub-accounts…", task,
                        n -> { loadList(); info("Create Complete", n + " sub-account(s) created."); dlg.close(); },
                        err -> info("Create Failed", err.getMessage()));
                });
            });
        });
        HBox bar = new HBox(10, btnPreview, btnClose);
        bar.setAlignment(Pos.CENTER_RIGHT); bar.setPadding(new Insets(10, 16, 14, 16));
        VBox root = new VBox(0, g, bar);
        dlg.setScene(new Scene(root, 460, 320));
        dlg.showAndWait();
    }

    // ── CPCM05 — Change Name ─────────────────────────────────────────────

    private void openRenameDialog(Window owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner); dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Change Account Names — CPCM05");

        TextField fStartMain = tf("", 8), fEndMain = tf("999999", 8);
        ToggleGroup tg = new ToggleGroup();
        RadioButton rbM = new RadioButton("Same name for every sub-account (typed below)"); rbM.setToggleGroup(tg);
        RadioButton rbS = new RadioButton("From each sub-account's own code name");        rbS.setToggleGroup(tg);
        RadioButton rbC = new RadioButton("Main name + sub-account code name");            rbC.setToggleGroup(tg);
        rbS.setSelected(true);
        TextField fMainDesc = tf("", 35), fMainAlpha = tf("", 35), fMainAbbrev = tf("", 20);
        CheckBox cbDesc = checkBox("Change description", "Y");
        CheckBox cbAlpha = checkBox("Change alpha code", "N");
        CheckBox cbAbbrev = checkBox("Change abbreviated description", "N");
        CheckBox cbCascade = checkBox("Cascade into matching report-writer lines (glrpvel)", "Y");

        GridPane g = new GridPane(); g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(16));
        int r = 0;
        g.add(lbl("Main Account Range *:"), 0, r); g.add(new HBox(6, fStartMain, new Label("to"), fEndMain), 1, r++);
        g.add(lbl("Naming source:"), 0, r); g.add(new VBox(4, rbS, rbC, rbM), 1, r++);
        g.add(lbl("Typed name (mode M):"), 0, r); g.add(fMainDesc, 1, r++);
        g.add(lbl("Typed alpha (mode M):"), 0, r); g.add(fMainAlpha, 1, r++);
        g.add(lbl("Typed abbrev (mode M):"), 0, r); g.add(fMainAbbrev, 1, r++);
        g.add(new Label(), 0, r); g.add(cbDesc, 1, r++);
        g.add(new Label(), 0, r); g.add(cbAlpha, 1, r++);
        g.add(new Label(), 0, r); g.add(cbAbbrev, 1, r++);
        g.add(new Label(), 0, r); g.add(cbCascade, 1, r++);

        Button btnPreview = btnPrimary("Preview…");
        Button btnClose = btnSecondary("Close");
        btnClose.setOnAction(e -> dlg.close());
        btnPreview.setOnAction(e -> {
            Integer sm = parseInt(fStartMain.getText()), em = parseInt(fEndMain.getText());
            if (sm == null || em == null) { info("Change Name", "Enter a valid main account range."); return; }
            String option = rbM.isSelected() ? "M" : rbC.isSelected() ? "C" : "S";
            int co = session.getCompanyNo();
            boolean changeDesc = cbDesc.isSelected(), changeAlpha = cbAlpha.isSelected(), changeAbbrev = cbAbbrev.isSelected();
            String mainDesc = fMainDesc.getText().trim(), mainAlpha = fMainAlpha.getText().trim(), mainAbbrev = fMainAbbrev.getText().trim();
            if (!changeDesc && !changeAlpha && !changeAbbrev) { info("Change Name", "Select at least one field to change."); return; }
            exec.submit(() -> {
                List<GlChartRenameService.Candidate> cands = renameService.preview(co, sm, em, option, mainDesc, mainAlpha, mainAbbrev, changeDesc, changeAlpha, changeAbbrev);
                Platform.runLater(() -> {
                    if (cands.isEmpty()) { info("Change Name", "No sub-accounts matched that range."); return; }
                    var preview = new BatchPreviewDialog<>("Change Account Names",
                        cands.size() + " sub-account(s) will be renamed.",
                        cands, List.of(
                            new BatchPreviewDialog.Column<GlChartRenameService.Candidate>("Account", c -> c.acctMainNo() + "." + c.acctSubNo()),
                            new BatchPreviewDialog.Column<GlChartRenameService.Candidate>("Current Name", GlChartRenameService.Candidate::oldDesc),
                            new BatchPreviewDialog.Column<GlChartRenameService.Candidate>("New Name", GlChartRenameService.Candidate::newDesc)
                        ));
                    if (!preview.showAndAwait(dlg)) return;
                    String user = session.getUserId();
                    boolean cascade = cbCascade.isSelected();
                    Task<Integer> task = new Task<>() {
                        @Override protected Integer call() {
                            updateMessage("Renaming accounts…");
                            return renameService.apply(co, sm, em, option, mainDesc, mainAlpha, mainAbbrev,
                                changeDesc, changeAlpha, changeAbbrev, cascade, user);
                        }
                    };
                    BatchProgressDialog.runWithProgress(dlg, "Renaming accounts…", task,
                        n -> { loadList(); info("Change Name Complete", n + " sub-account(s) renamed."); dlg.close(); },
                        err -> info("Change Name Failed", err.getMessage()));
                });
            });
        });
        HBox bar = new HBox(10, btnPreview, btnClose);
        bar.setAlignment(Pos.CENTER_RIGHT); bar.setPadding(new Insets(10, 16, 14, 16));
        VBox root = new VBox(0, g, bar);
        dlg.setScene(new Scene(root, 480, 460));
        dlg.showAndWait();
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    private TableColumn<GlChartAccount, String> col(String h, Function<GlChartAccount, String> fn, double w) {
        TableColumn<GlChartAccount, String> c = new TableColumn<>(h);
        c.setCellValueFactory(p -> new SimpleStringProperty(safe(fn.apply(p.getValue()))));
        c.setPrefWidth(w); return c;
    }
    private TableColumn<GlChartAccount, String> col2(String h, Function<GlChartAccount, String> fn, double w) { return col(h, fn, w); }

    private HBox buildStatusBar() {
        lblStatus = new Label("Ready");
        lblStatus.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        HBox bar = new HBox(lblStatus);
        bar.setPadding(new Insets(5, 16, 5, 16));
        bar.setStyle("-fx-background-color:#F8F8F6;-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;-fx-border-width:0.5 0 0 0;");
        return bar;
    }
    private void status(String m, boolean err) {
        Platform.runLater(() -> { lblStatus.setText(m);
            lblStatus.setStyle("-fx-font-size:11px;-fx-text-fill:" + (err ? "#C0392B" : "#888780") + ";"); });
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }
    private static Integer parseIntOrZero(TextField f) {
        String s = f.getText();
        if (s == null || s.isBlank()) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }
    private static int nvlInt(Integer v) { return v == null ? 0 : v; }
    private static BigDecimal parseDecOrZero(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
    private static String yn(String s) { return "Y".equals(safe(s).trim()) ? "✓" : ""; }
    private static String ynOf(CheckBox cb) { return cb.isSelected() ? "Y" : "N"; }
    private static String codeOf(ChoiceBox<String> cb) {
        String s = cb.getValue();
        return (s == null || s.isBlank()) ? "" : String.valueOf(s.charAt(0));
    }
    private static void selectByCode(ChoiceBox<String> cb, String code) {
        if (code == null || code.isBlank()) { cb.getSelectionModel().selectFirst(); return; }
        char want = Character.toUpperCase(code.trim().charAt(0));
        for (String o : cb.getItems()) if (!o.isEmpty() && Character.toUpperCase(o.charAt(0)) == want) { cb.setValue(o); return; }
        cb.getSelectionModel().selectFirst();
    }
    private ChoiceBox<String> choiceBox(List<String> options, String selectedCode) {
        ChoiceBox<String> cb = new ChoiceBox<>(FXCollections.observableArrayList(options));
        selectByCode(cb, selectedCode);
        return cb;
    }
    private CheckBox checkBox(String label, String selectedFlag) {
        CheckBox cb = new CheckBox(label);
        cb.setSelected("Y".equals(safe(selectedFlag).trim()));
        return cb;
    }
    private static javafx.util.StringConverter<CompanyRow> companyConverter() {
        return new javafx.util.StringConverter<>() {
            @Override public String toString(CompanyRow c) { return c == null ? "" : c.getCompanyNo() + " — " + c.getName(); }
            @Override public CompanyRow fromString(String s) { return null; }
        };
    }
    private static Label sectionHeader(String s) {
        Label l = new Label(s);
        l.setStyle("-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:#1A6EF5;-fx-padding:10 0 2 0;");
        return l;
    }
    private static String safe(String s) { return s == null ? "" : s; }

    private Label lbl(String s) { Label l = new Label(s); l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;"); l.setMinWidth(160); return l; }
    private TextField tf(String v, int max) { TextField f = new TextField(v == null ? "" : v.trim()); f.setPrefWidth(Math.min(max * 9 + 20, 260)); return f; }
    private Button btnPrimary(String t) { Button b = new Button(t); b.setStyle("-fx-background-color:#1A6EF5;-fx-text-fill:white;-fx-font-weight:bold;-fx-background-radius:7;-fx-padding:6 16;-fx-cursor:hand;"); return b; }
    private Button btnSecondary(String t) { Button b = new Button(t); b.setStyle("-fx-background-color:white;-fx-text-fill:#374151;-fx-border-color:#D0CFC8;-fx-background-radius:7;-fx-border-radius:7;-fx-padding:5 14;-fx-cursor:hand;"); return b; }
    private Button btnDanger(String t) { Button b = new Button(t); b.setStyle("-fx-background-color:white;-fx-text-fill:#C0392B;-fx-border-color:#E8BDB8;-fx-background-radius:7;-fx-border-radius:7;-fx-padding:5 14;-fx-cursor:hand;"); return b; }
    private static void markError(TextField f, String msg) {
        f.requestFocus(); f.selectAll();
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK); a.setTitle("Validation"); a.setHeaderText(null); a.showAndWait();
    }
    private void info(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK); a.setTitle(title); a.setHeaderText(null); a.showAndWait();
    }
}
