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

import com.landmarksoftware.model.AcquisitionRecord;
import com.landmarksoftware.model.AssetFullRecord;
import com.landmarksoftware.model.BatchInfo;
import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.AcquisitionService;
import com.landmarksoftware.service.CodeLookupService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * FAAQ01 — Asset Acquisition Transaction Entry.
 *
 * Screen flow (matching COBOL):
 *   S1  → Batch header  (CMBATCH: batch_no, date, entered_by, ref)
 *   P1  → Transaction list (FATRXIN rows for the batch)
 *         [Add] → S2 → S2A → S2B → S3 (if opening bal) → P3 barcodes
 *         [Edit]   → same screens, pre-filled
 *         [Delete] → confirmation → remove FATRXIN + FAASSET + FATRANS
 *
 * Java layout:
 *   Modal batch-header dialog (S1)
 *   Main BorderPane:
 *     top    = batch info bar
 *     centre = TableView of batch transactions (P1) + toolbar
 *     bottom = status bar
 *   Add/Edit opens tabbed dialog:
 *     Tab 1 — Asset Details  (S2:  asset_no, desc, alpha, loc, dept, grp, subgrp,
 *                                  stocktake, attach_to, pooled, qty, acqn_date,
 *                                  acqn_type, internal_order)
 *     Tab 2 — Supplier/Other (S2A: supplier, invoice, leased, insurance)
 *     Tab 3 — Cost & Depn    (S2B: actual_cost, tax/book depn cost, method, code,
 *                                  rate, freq, start dates, write-down date)
 *     Tab 4 — Opening Bals   (S3:  accum depn, last depn date, reval — only for
 *                                  opening balance batches)
 *     Tab 5 — Bar Codes      (P3 / S5)
 */
@Component
public class AcquisitionEntryController {

    private final AcquisitionService acqService;
    private final CodeLookupService lookupService;
    private final AppSession    appSession;

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ── Batch state ────────────────────────────────────────────
    private int     companyNo;
    private int     batchNo;
    private String  batchDate  = "";
    private String  batchRef   = "";
    private String  enteredBy  = "";

    // ── Scene refs — needed for post-batch screen reset ────────
    private Stage       mainStage;
    private BorderPane  mainRoot;

    // ── List ──────────────────────────────────────────────────
    private TableView<AcqnRow>          tbl;
    private ObservableList<AcqnRow>     rows = FXCollections.observableArrayList();
    private Label                       lblStatus;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "faaq01-thread"); t.setDaemon(true); return t;
    });

    public AcquisitionEntryController(AcquisitionService acqService,
                                       CodeLookupService lookupService,
                                       AppSession appSession) {
        this.acqService    = acqService;
        this.lookupService = lookupService;
        this.appSession    = appSession;
    }

    // ══════════════════════════════════════════════════════════
    // Entry point — called from MainMenuController
    // ══════════════════════════════════════════════════════════

    public Scene buildScene(Stage stage, int companyNo) {
        this.companyNo  = companyNo;
        this.mainStage  = stage;

        if (!showBatchDialog(stage)) return null;

        mainRoot = new BorderPane();
        mainRoot.setStyle("-fx-background-color:#F2F1EC;");
        mainRoot.setTop(batchBar());
        mainRoot.setCenter(listPanel(stage));
        mainRoot.setBottom(statusBar());

        loadList();

        Scene scene = new Scene(mainRoot, 980, 620);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    /** After posting, return to batch selection and rebuild the list panel. */
    private void resetToBatchSelection() {
        batchNo   = 0;
        batchDate = "";
        batchRef  = "";
        enteredBy = "";
        rows.clear();

        if (!showBatchDialog(mainStage)) {
            // User cancelled — close the FAAQ01 window
            if (mainStage != null) mainStage.close();
            return;
        }

        // Rebuild the content with the newly selected batch
        mainRoot.setTop(batchBar());
        mainRoot.setCenter(listPanel(mainStage));
        mainRoot.setBottom(statusBar());
        loadList();
    }

    // ══════════════════════════════════════════════════════════
    // S1 — Batch Header Dialog
    // ══════════════════════════════════════════════════════════

    private boolean showBatchDialog(Window owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Asset Acquisition — Batch Header — FAAQ01S1");
        dlg.setResizable(false);

        int nextNo = acqService.nextBatchNo(companyNo);

        // ── Batch number field — editable so user can enter existing batch ──
        TextField fBatch = tf("", 6);
        fBatch.setPromptText("blank = new batch " + nextNo);
        Label lBatchStatus = new Label("");
        lBatchStatus.setStyle("-fx-font-size:11px; -fx-text-fill:#888780;");

        DatePicker dpDate = new DatePicker(LocalDate.now());
        dpDate.setPrefWidth(140);

        String uid = appSession.getUserId();
        String defaultEnteredBy = uid.isEmpty()
            ? System.getProperty("user.name", "")
            : uid.substring(0, Math.min(3, uid.length()));
        TextField fEnteredBy = tf(defaultEnteredBy, 3);
        TextField fRef       = tf("", 20);

        // ── Load existing batch when user tabs out of batch number field ────
        fBatch.focusedProperty().addListener((o, ov, focused) -> {
            if (focused) return;
            String entered = fBatch.getText().trim();
            if (entered.isEmpty()) {
                lBatchStatus.setText("New batch " + nextNo + " will be created.");
                lBatchStatus.setStyle("-fx-font-size:11px; -fx-text-fill:#888780;");
                dpDate.setValue(LocalDate.now());
                fEnteredBy.setText(defaultEnteredBy);
                fRef.setText("");
                dpDate.setDisable(false);
                fEnteredBy.setDisable(false);
                fRef.setDisable(false);
                return;
            }
            // Try to parse as number and look up in CMBATCH
            int enteredNo;
            try { enteredNo = Integer.parseInt(entered); }
            catch (NumberFormatException ex) {
                lBatchStatus.setText("⚠ Batch number must be numeric.");
                lBatchStatus.setStyle("-fx-font-size:11px; -fx-text-fill:#DC2626;");
                return;
            }
            Optional<BatchInfo> found = acqService.loadBatch(companyNo, enteredNo);
            if (found.isEmpty()) {
                lBatchStatus.setText("Batch " + enteredNo + " not found — will create new.");
                lBatchStatus.setStyle("-fx-font-size:11px; -fx-text-fill:#F59E0B;");
                dpDate.setValue(LocalDate.now());
                dpDate.setDisable(false); fEnteredBy.setDisable(false); fRef.setDisable(false);
            } else {
                BatchInfo b = found.get();
                if (!b.isOpen()) {
                    lBatchStatus.setText("⚠ Batch " + enteredNo + " is not open (status='" + b.status + "'). Only open batches can be resumed.");
                    lBatchStatus.setStyle("-fx-font-size:11px; -fx-text-fill:#DC2626;");
                    fBatch.selectAll();
                } else {
                    if (b.batchDate != null) dpDate.setValue(b.batchDate);
                    fEnteredBy.setText(b.enteredBy);
                    fRef.setText(b.ref);
                    dpDate.setDisable(true); fEnteredBy.setDisable(true); fRef.setDisable(true);
                    lBatchStatus.setText("✓ Resuming batch " + enteredNo + " — " + b.ref + " (" + b.enteredBy + ")");
                    lBatchStatus.setStyle("-fx-font-size:11px; -fx-text-fill:#059669;");
                }
            }
        });

        // ── Unposted batch list ─────────────────────────────────────────────
        Label lUnposted = new Label("Unposted batches:");
        lUnposted.setStyle("-fx-font-size:11px; -fx-font-weight:bold; -fx-text-fill:#374151;");
        ListView<String> lvBatches = new ListView<>();
        lvBatches.setPrefHeight(100);
        lvBatches.setStyle("-fx-font-size:11px;");
        List<BatchInfo> unposted = acqService.loadUnpostedBatches(companyNo);
        if (unposted.isEmpty()) {
            lvBatches.getItems().add("(no unposted batches found)");
        } else {
            unposted.forEach(b -> lvBatches.getItems().add(b.toString()));
        }
        // Click on list → populate batch number field and trigger focus-out lookup
        lvBatches.setOnMouseClicked(ev -> {
            String sel = lvBatches.getSelectionModel().getSelectedItem();
            if (sel == null || sel.startsWith("(")) return;
            String bno = sel.trim().split("\\s+")[0];
            fBatch.setText(bno);
            // Manually trigger the lookup
            fBatch.fireEvent(new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                javafx.scene.input.KeyCode.TAB, false, false, false, false));
            dpDate.requestFocus();
        });

        GridPane form = grid();
        addRow(form, 0, "Batch number:", new HBox(6, fBatch, lBatchStatus));
        addRow(form, 1, "Batch date:",   dpDate);
        addRow(form, 2, "Entered by:",   fEnteredBy);
        addRow(form, 3, "Reference:",    fRef);

        boolean[] ok = {false};
        Button btnOk = btnPrimary("OK");
        Button btnCancel = btnSecondary("Cancel");
        btnOk.setDefaultButton(true);
        btnOk.setOnAction(e -> {
            String enteredBno = fBatch.getText().trim();
            if (enteredBno.isEmpty()) {
                batchNo = nextNo;
            } else {
                try { batchNo = Integer.parseInt(enteredBno); }
                catch (NumberFormatException ex) {
                    showAlert("Batch", "Batch number must be numeric.");
                    return;
                }
            }
            batchDate = dpDate.getValue() != null ? dpDate.getValue().format(DF) : "";
            enteredBy = fEnteredBy.getText().trim();
            batchRef  = fRef.getText().trim();
            ok[0] = true;
            dlg.close();
        });
        btnCancel.setOnAction(e -> dlg.close());

        VBox root = new VBox(8,
            hdr("Asset Acquisition — Batch Header"),
            form,
            lUnposted,
            lvBatches,
            btnBar(btnOk, btnCancel));
        root.setPadding(new Insets(0, 0, 0, 0));
        dlg.setScene(new Scene(root, 480, 380));
        dlg.showAndWait();
        return ok[0];
    }

    // ══════════════════════════════════════════════════════════
    // Batch info bar
    // ══════════════════════════════════════════════════════════

    private HBox batchBar() {
        Label lBatch = infoLbl("Batch: " + batchNo);
        Label lDate  = infoLbl("Date: " + batchDate);
        Label lRef   = infoLbl("Ref: " + (batchRef.isEmpty() ? "—" : batchRef));
        Label lUser  = infoLbl("User: " + enteredBy);
        // Allow user label to grow — prevents truncation of long usernames
        lBatch.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        lDate.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        lRef.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        lUser.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        lUser.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(lUser, Priority.ALWAYS);
        HBox bar = new HBox(24, lBatch, lDate, lRef, lUser);
        bar.setPadding(new Insets(10, 16, 10, 16));
        bar.setStyle("-fx-background-color:#FFFFFF;" +
                     "-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;" +
                     "-fx-border-width:0 0 0.5 0;");
        return bar;
    }

    // ══════════════════════════════════════════════════════════
    // P1 — Transaction list + toolbar
    // ══════════════════════════════════════════════════════════

    private VBox listPanel(Stage stage) {
        tbl = new TableView<>(rows);
        tbl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(tbl, Priority.ALWAYS);

        tbl.getColumns().addAll(List.of(
            tcol("Asset No",    r -> r.assetNo,    130),
            tcol("Description", r -> r.desc1,      210),
            tcol("Acqn Date",   r -> r.acqnDate,    90),
            tcol("Loc",         r -> r.locCode,     50),
            tcol("Dept",        r -> r.deptCode,    50),
            tcol("Grp",         r -> r.grpCode,     50),
            tcol("Sub-Grp",     r -> r.subgrpCode,  60),
            tcol("Cost",        r -> r.cost,        110),
            tcol("Status",      r -> r.status,      80)
        ));

        // Double-click to edit
        tbl.setRowFactory(tv -> {
            TableRow<AcqnRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty())
                    openEntryDlg(row.getItem(), stage);
            });
            return row;
        });

        Button btnAdd    = btnPrimary("+ Add");
        Button btnEdit   = btnSecondary("✎ Edit");
        Button btnDel    = btnDanger("✕ Delete");
        Button btnPost   = btnSuccess("▶ Post Batch");
        Button btnRefresh = btnSecondary("↺");
        Button btnExport = btnSecondary("⬇ Export Excel");
        Button btnImport = btnSecondary("⬆ Import Excel");

        btnAdd.setOnAction(e -> openEntryDlg(null, stage));
        btnEdit.setOnAction(e -> {
            AcqnRow sel = tbl.getSelectionModel().getSelectedItem();
            if (sel != null) openEntryDlg(sel, stage);
            else showInfo("Edit", "Select a transaction to edit.");
        });
        btnDel.setOnAction(e -> {
            AcqnRow sel = tbl.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDelete(sel);
        });
        btnPost.setOnAction(e -> postBatch(stage));
        btnRefresh.setOnAction(e -> loadList());
        btnExport.setOnAction(e -> exportToExcel(stage));
        btnImport.setOnAction(e -> importFromExcel(stage));

        HBox toolbar = new HBox(8,
            btnAdd, btnEdit, btnDel,
            new Separator(Orientation.VERTICAL),
            btnExport, btnImport,
            new Separator(Orientation.VERTICAL),
            btnRefresh,
            spacer(),
            btnPost);
        toolbar.setPadding(new Insets(10, 16, 10, 16));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle("-fx-background-color:#F8F8F6;" +
                         "-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;" +
                         "-fx-border-width:0 0 0.5 0;");

        return new VBox(0, toolbar, tbl);
    }

    // ══════════════════════════════════════════════════════════
    // Add / Edit dialog — tabs matching S2 + S2A + S2B + S3 + P3
    // ══════════════════════════════════════════════════════════

    private void openEntryDlg(AcqnRow existing, Window owner) {
        boolean isAdd = (existing == null);
        // Load full asset record from DB for edit pre-fill
        AssetFullRecord full = isAdd ? null
            : acqService.loadFullAsset(companyNo, existing.assetNo).orElse(null);
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(isAdd ? "Add Acquisition — FAAQ01" : "Edit Acquisition — FAAQ01");
        dlg.setMinWidth(740);
        dlg.setMinHeight(580);

        // ── Tab 1: Asset Details (S2) ─────────────────────────
        TextField fAssetNo   = tf((full != null ? full.assetNo : ""), 20);
        TextField fDesc1     = tf((full != null ? full.desc1 : ""),   40);
        TextField fDesc2     = tf((full != null ? full.desc2 : ""),   40);
        TextField fAlpha     = tf((full != null ? full.alpha : ""),   20);
        // COBOL default: alpha_code = desc_1 (on Add, auto-fill when desc1 loses focus)
        if (isAdd) {
            fDesc1.focusedProperty().addListener((o, ov, focused) -> {
                if (!focused && fAlpha.getText().trim().isEmpty()) {
                    String d1 = fDesc1.getText().trim();
                    fAlpha.setText(d1.length() > 20 ? d1.substring(0, 20) : d1);
                }
            });
        }
        TextField fLoc       = tf((full != null ? full.locCode : ""),      6); Label lLoc  = descLbl();
        TextField fDept      = tf((full != null ? full.deptCode : ""),     6); Label lDept = descLbl();
        TextField fGrp       = tf((full != null ? full.grpCode : ""),      6); Label lGrp  = descLbl();
        TextField fSubGrp    = tf((full != null ? full.subgrpCode : ""),   6); Label lSGrp = descLbl();
        TextField fSite      = tf((full != null ? full.site : ""),     6); Label lSite = descLbl();
        TextField fAttachTo  = tf((full != null ? full.attachTo : ""),20);
        CheckBox  cbPooled   = new CheckBox(); cbPooled.setSelected(full != null && full.pooled);
        TextField fQty       = tf(full != null ? String.valueOf(full.qty) : "1", 6);
        DatePicker dpAcqn    = dp(full != null ? full.acqnDate : null);
        TextField fAcqnType  = tf(isAdd ? "P" : (full != null ? full.acqnType : ""), 1);
        TextField fIntOrder  = tf((full != null ? full.intOrder : ""), 15);

        // Wire validated focus-out lookups
        wireLookup(fLoc,    lLoc,   "FACODLO", "loc_code",        true, dlg);
        wireLookup(fDept,   lDept,  "FACODDT", "dept_code",       true, dlg);
        wireLookup(fGrp,    lGrp,   "FACODGR", "grp_code",        true, dlg);
        wireLookup(fSubGrp, lSGrp,  "FACODSG", "subgrp_code",     false, dlg);
        wireLookup(fSite,   lSite,  "FACODSS", "stake_site_code", false, dlg);

        if (existing != null) {
            lLoc.setText(acqService.lookupDesc(companyNo, "FACODLO", "loc_code", fLoc.getText()));
            lDept.setText(acqService.lookupDesc(companyNo, "FACODDT", "dept_code", fDept.getText()));
            lGrp.setText(acqService.lookupDesc(companyNo, "FACODGR", "grp_code", fGrp.getText()));
            lSGrp.setText(acqService.lookupDesc(companyNo, "FACODSG", "subgrp_code", fSubGrp.getText()));
            lSite.setText(acqService.lookupDesc(companyNo, "FACODSS", "stake_site_code", fSite.getText()));
        }

        GridPane s2 = grid();
        addRow(s2, 0,  "Asset No:",           lookupRow(fAssetNo, LookupDialog.LookupType.ASSET, dlg));
        addRow(s2, 1,  "Description 1:",      fDesc1);
        addRow(s2, 2,  "Description 2:",      fDesc2);
        addRow(s2, 3,  "Alpha key:",           fAlpha);
        addLookupRow(s2, 4,  "Location:",      fLoc, lLoc, LookupDialog.LookupType.LOCATION, dlg);
        addLookupRow(s2, 5,  "Department:",    fDept, lDept, LookupDialog.LookupType.DEPARTMENT, dlg);
        addLookupRow(s2, 6,  "Group:",          fGrp, lGrp, LookupDialog.LookupType.GROUP, dlg);
        addLookupRow(s2, 7,  "Sub-group:",      fSubGrp, lSGrp, LookupDialog.LookupType.SUBGROUP, dlg);
        addLookupRow(s2, 8,  "Stocktake site:", fSite, lSite, LookupDialog.LookupType.STOCKTAKE_SITE, dlg);
        addRow(s2, 9,  "Attached to asset:",  lookupRow(fAttachTo, LookupDialog.LookupType.ASSET, dlg));
        s2.add(fmtLbl("Pooled asset:"), 0, 10); s2.add(cbPooled, 1, 10);
        addRow(s2, 11, "Quantity:",            fQty);
        s2.add(fmtLbl("Acquisition date:"), 0, 12); s2.add(dpAcqn, 1, 12);
        addRow(s2, 13, "Acquisition type\n(N=New U=Used I=Imported):", fAcqnType);
        addRow(s2, 14, "Internal order no:",   fIntOrder);

        // ── Tab 2: Supplier / Other (S2A) ────────────────────
        TextField fSupplierNo   = tf((full != null ? full.suppNo : ""),  10);
        Label     lSupplierName = descLbl(); lSupplierName.setMinWidth(220);
        TextField fInvoiceNo    = tf((full != null ? full.suppInv : ""),   20);
        CheckBox  cbLeased      = new CheckBox(); cbLeased.setSelected(full != null && full.leased);
        TextField fContractNo   = tf((full != null ? full.contractNo : ""),  20);
        TextField fPayAmt       = tf((full != null ? decStr(full.payAmt) : ""),      14);
        TextField fPayFreq      = tf((full != null ? full.payFreq : ""),      3);
        DatePicker dpExpiry     = dp(null);
        TextField fResidual     = tf((full != null ? decStr(full.residual) : ""),  14);
        TextField fContractVal  = tf((full != null ? decStr(full.contractVal) : ""),  14);
        TextField fDisposalVal  = tf((full != null ? decStr(full.disposalVal) : ""),  14);
        TextField fInsType      = tf((full != null ? full.insType : ""),       6); Label lInsType = descLbl();
        TextField fCurrentIns   = tf((full != null ? decStr(full.currIns) : ""),  14);
        TextField fReplNewVal   = tf((full != null ? decStr(full.replNew) : ""),   14);
        DatePicker dpReplAsAt   = dp(null);

        fSupplierNo.focusedProperty().addListener((o,ov,focused) -> {
            if (!focused) lSupplierName.setText(acqService.lookupSupplier(companyNo, fSupplierNo.getText().trim()));
        });
        wireLookup(fInsType, lInsType, "FACODIN", "ins_type_code");
        if (existing != null) lInsType.setText(acqService.lookupDesc(companyNo, "FACODIN", "ins_type_code", fInsType.getText()));

        // Enable/disable lease fields based on checkbox
        List<TextField> leaseFlds = List.of(fContractNo,fPayAmt,fPayFreq,fResidual,fContractVal,fDisposalVal);
        Runnable updateLease = () -> leaseFlds.forEach(f -> f.setDisable(!cbLeased.isSelected()));
        cbLeased.selectedProperty().addListener((o,ov,nv) -> updateLease.run());
        updateLease.run();

        GridPane s2a = grid();
        int r2a = 0;
        sectionHdr(s2a, r2a++, "Supplier details:");
        addRowWithDesc(s2a, r2a++, "Supplier no:",  fSupplierNo, lSupplierName);
        addRow(s2a, r2a++, "Invoice no:",            fInvoiceNo);
        sectionHdr(s2a, r2a++, "Lease details:");
        s2a.add(fmtLbl("Is this leased ?"), 0, r2a); s2a.add(cbLeased, 1, r2a++);
        addRow(s2a, r2a++, "Contract no:",           fContractNo);
        addRow(s2a, r2a++, "Payment amount:",         fPayAmt);
        addRow(s2a, r2a++, "Payment freq'y:",         fPayFreq);
        s2a.add(fmtLbl("Expiry date:"), 0, r2a); s2a.add(dpExpiry, 1, r2a++);
        addRow(s2a, r2a++, "Residual value:",         fResidual);
        addRow(s2a, r2a++, "Contract value:",         fContractVal);
        addRow(s2a, r2a++, "Disposal value:",         fDisposalVal);
        sectionHdr(s2a, r2a++, "Insurance details:");
        addLookupRow(s2a, r2a++, "Insurance type:", fInsType, lInsType,
            LookupDialog.LookupType.INSURANCE_TYPE, dlg);
        addRow(s2a, r2a++, "Current ins value:",     fCurrentIns);
        addRow(s2a, r2a++, "Replace (new) value:",   fReplNewVal);
        s2a.add(fmtLbl("As at date:"), 0, r2a); s2a.add(dpReplAsAt, 1, r2a);

        // ── Tab 3: Cost & Depreciation (S2B) ─────────────────
        // Reference / cost fields
        TextField fRef         = tf((full != null ? full.ref : ""),          20);
        TextField fActualCost  = tf((full != null ? decStr(full.actualCost) : ""),         14);
        TextField fTaxDepnCost = tf((full != null ? decStr(full.taxDepnCost) : ""),  14);
        TextField fBookDepnCost= tf((full != null ? decStr(full.bookDepnCost) : ""), 14);
        // COBOL default: tax/book depn cost = actual_cost if not entered
        if (isAdd) {
            fActualCost.focusedProperty().addListener((o, ov, focused) -> {
                if (!focused) {
                    String cost = fActualCost.getText().trim();
                    if (!cost.isEmpty()) {
                        if (fTaxDepnCost.getText().trim().isEmpty()) fTaxDepnCost.setText(cost);
                        if (fBookDepnCost.getText().trim().isEmpty()) fBookDepnCost.setText(cost);
                    }
                }
            });
        }
        // Write down date: Add=default 2039-12-31, Edit=from DB (null/sentinel → 2039-12-31)
        DatePicker dpWriteDown = dp(
            full != null && full.writeDown != null
                ? full.writeDown
                : java.time.LocalDate.of(2039, 12, 31));

        // ── Tax depreciation fields ──────────────────────────
        // D-06: Method S/D
        javafx.scene.control.ChoiceBox<String> fTaxMethod = new javafx.scene.control.ChoiceBox<>();
        fTaxMethod.getItems().addAll("", "S — Straight-line", "D — Diminishing");
        String _txm = (full != null ? full.taxMethod : "").trim().toUpperCase();
        fTaxMethod.setValue(_txm.isEmpty() ? "" : _txm.equals("S") ? "S — Straight-line" : "D — Diminishing");
        fTaxMethod.setPrefWidth(180);

        // D-05: Start tax depn date — default = yr start on Add
        DatePicker dpStartTax = dp(
            full != null && full.startTax != null
                ? full.startTax : appSession.getYrStartDate());

        // D-07: Depn code — blank = use specific rate (COBOL prompt: "Enter valid code or blank to define specific rate")
        TextField fTaxCode    = tf((full != null ? full.taxCode : ""),    6);
        Label     lTaxCDesc   = descLbl(); lTaxCDesc.setMinWidth(160);

        // D-07-02/03: Rates — enabled always (whether code is blank or not)
        TextField fTaxRateYr1 = tf((full != null ? decStr(full.taxRate1) : ""), 8);
        TextField fTaxRateYr2 = tf((full != null ? decStr(full.taxRate2) : ""), 8);

        // D-07-04: Calc ind — D=days W=work days F=fixed amount
        javafx.scene.control.ChoiceBox<String> fTaxCalcInd = new javafx.scene.control.ChoiceBox<>();
        fTaxCalcInd.getItems().addAll("", "D — Days", "W — Work days", "F — Fixed amount");
        String _tci = (full != null ? full.taxCalcInd : "").trim().toUpperCase();
        fTaxCalcInd.setValue(_tci.isEmpty() ? "" :
            _tci.equals("D") ? "D — Days" : _tci.equals("W") ? "W — Work days" : "F — Fixed amount");
        fTaxCalcInd.setPrefWidth(180);

        // D-07-05: Calc base — D=depn to date O=opening balance for year
        javafx.scene.control.ChoiceBox<String> fTaxCalcBase = new javafx.scene.control.ChoiceBox<>();
        fTaxCalcBase.getItems().addAll("", "D — Depn to date", "O — Opening balance");
        String _tcb = (full != null ? full.taxCalcBase : "").trim().toUpperCase();
        fTaxCalcBase.setValue(_tcb.isEmpty() ? "" :
            _tcb.equals("D") ? "D — Depn to date" : "O — Opening balance");
        fTaxCalcBase.setPrefWidth(180);

        // D-08: Tax frequency — required, 1–13 (COBOL: "1 = every period  2 = every second period etc")
        // Default to 1 on Add
        TextField fTaxFreq = tf(
            full != null && !full.taxFreq.isEmpty()
                ? (full != null ? full.taxFreq : "") : isAdd ? "1" : "", 3);

        // ── Book depreciation fields ─────────────────────────
        // D-10: Book method — defaults to tax method on Add
        javafx.scene.control.ChoiceBox<String> fBookMethod = new javafx.scene.control.ChoiceBox<>();
        fBookMethod.getItems().addAll("", "S — Straight-line", "D — Diminishing");
        String _bkm = (full != null ? full.bookMethod : "").trim().toUpperCase();
        fBookMethod.setValue(_bkm.isEmpty() ? "" : _bkm.equals("S") ? "S — Straight-line" : "D — Diminishing");
        fBookMethod.setPrefWidth(180);
        // On Add: book method defaults to tax method when tax method changes
        if (isAdd) {
            fTaxMethod.valueProperty().addListener((o, ov, nv) -> {
                if (fBookMethod.getValue() == null || fBookMethod.getValue().isEmpty()
                        || fBookMethod.getValue().equals(ov)) {
                    fBookMethod.setValue(nv);
                }
            });
        }

        // D-09: Start book depn date — default = acqn date (same logic as tax)
        DatePicker dpStartBook = dp(
            full != null && full.startBook != null
                ? full.startBook : appSession.getYrStartDate());

        // D-11: Book depn code — defaults to tax code on Add
        TextField fBookCode  = tf((full != null ? full.bookCode : ""),    6);
        Label     lBookCDesc = descLbl(); lBookCDesc.setMinWidth(160);
        // On Add: book code defaults to tax code when tax code loses focus
        if (isAdd) {
            fTaxCode.focusedProperty().addListener((o, ov, focused) -> {
                if (!focused && fBookCode.getText().trim().isEmpty()) {
                    fBookCode.setText(fTaxCode.getText().trim());
                    lBookCDesc.setText(acqService.lookupDesc(companyNo, "FACODDN", "depn_code", fBookCode.getText()));
                }
            });
        }

        // D-11-02/03: Book rates — default to tax rates on Add
        TextField fBookRateYr1 = tf((full != null ? decStr(full.bookRate1) : ""), 8);
        TextField fBookRateYr2 = tf((full != null ? decStr(full.bookRate2) : ""), 8);
        if (isAdd) {
            fTaxRateYr1.focusedProperty().addListener((o, ov, focused) -> {
                if (!focused && fBookRateYr1.getText().trim().isEmpty())
                    fBookRateYr1.setText(fTaxRateYr1.getText().trim());
            });
            fTaxRateYr2.focusedProperty().addListener((o, ov, focused) -> {
                if (!focused && fBookRateYr2.getText().trim().isEmpty())
                    fBookRateYr2.setText(fTaxRateYr2.getText().trim());
            });
        }

        // D-11-04: Book calc ind — defaults to tax calc ind on Add
        javafx.scene.control.ChoiceBox<String> fBookCalcInd = new javafx.scene.control.ChoiceBox<>();
        fBookCalcInd.getItems().addAll("", "D — Days", "W — Work days", "F — Fixed amount");
        String _bci = (full != null ? full.bookCalcInd : "").trim().toUpperCase();
        fBookCalcInd.setValue(_bci.isEmpty() ? "" :
            _bci.equals("D") ? "D — Days" : _bci.equals("W") ? "W — Work days" : "F — Fixed amount");
        fBookCalcInd.setPrefWidth(180);
        if (isAdd) {
            fTaxCalcInd.valueProperty().addListener((o, ov, nv) -> {
                if (fBookCalcInd.getValue() == null || fBookCalcInd.getValue().isEmpty()
                        || fBookCalcInd.getValue().equals(ov))
                    fBookCalcInd.setValue(nv);
            });
        }

        // D-11-05: Book calc base — defaults to tax calc base on Add
        javafx.scene.control.ChoiceBox<String> fBookCalcBase = new javafx.scene.control.ChoiceBox<>();
        fBookCalcBase.getItems().addAll("", "D — Depn to date", "O — Opening balance");
        String _bcb = (full != null ? full.bookCalcBase : "").trim().toUpperCase();
        fBookCalcBase.setValue(_bcb.isEmpty() ? "" :
            _bcb.equals("D") ? "D — Depn to date" : "O — Opening balance");
        fBookCalcBase.setPrefWidth(180);
        if (isAdd) {
            fTaxCalcBase.valueProperty().addListener((o, ov, nv) -> {
                if (fBookCalcBase.getValue() == null || fBookCalcBase.getValue().isEmpty()
                        || fBookCalcBase.getValue().equals(ov))
                    fBookCalcBase.setValue(nv);
            });
        }

        // D-12: Book freq — required 1–13, defaults to 1 (COBOL: book freq required same as tax)
        TextField fBookFreq = tf(
            full != null && !full.bookFreq.isEmpty()
                ? (full != null ? full.bookFreq : "") : isAdd ? "1" : "", 3);

        // Post depn to — ChoiceBox N/C/B
        javafx.scene.control.ChoiceBox<String> fPostTo = new javafx.scene.control.ChoiceBox<>();
        fPostTo.getItems().addAll("N — Neither", "C — Cost Ledger", "B — BA Ledger");
        String _pt = (full != null ? full.postTo : "N").trim().toUpperCase();
        fPostTo.setValue(_pt.isEmpty() || _pt.equals("N") ? "N — Neither"
            : _pt.equals("C") ? "C — Cost Ledger" : "B — BA Ledger");
        fPostTo.setPrefWidth(180);
        TextField fLedgerCode = tf((full != null ? full.ledgerCode : ""), 20);

        // Wire depn code focus-out description lookup
        fTaxCode.focusedProperty().addListener((o,ov,foc) -> {
            if (!foc) lTaxCDesc.setText(acqService.lookupDesc(companyNo, "FACODDN", "depn_code", fTaxCode.getText()));
        });
        fBookCode.focusedProperty().addListener((o,ov,foc) -> {
            if (!foc) lBookCDesc.setText(acqService.lookupDesc(companyNo, "FACODDN", "depn_code", fBookCode.getText()));
        });
        if (existing != null) {
            lTaxCDesc.setText(acqService.lookupDesc(companyNo, "FACODDN", "depn_code", fTaxCode.getText()));
            lBookCDesc.setText(acqService.lookupDesc(companyNo, "FACODDN", "depn_code", fBookCode.getText()));
        }

        // Two-column layout: Tax left, Book right
        GridPane s2b = new GridPane();
        s2b.setHgap(14); s2b.setVgap(8); s2b.setPadding(new Insets(16));
        ColumnConstraints ca = new ColumnConstraints(130), cb = new ColumnConstraints(220),
                          cc = new ColumnConstraints(16),  cd = new ColumnConstraints(130),
                          ce = new ColumnConstraints(220);
        ca.setHalignment(HPos.RIGHT); cd.setHalignment(HPos.RIGHT);
        s2b.getColumnConstraints().addAll(ca,cb,cc,cd,ce);

        // Cost fields (full width)
        int r3 = 0;
        Label lCostHdr = bold("Cost:");
        s2b.add(lCostHdr, 0, r3, 5, 1);
        r3++;
        addFullRow(s2b, r3++, "Reference:",       fRef);
        addFullRow(s2b, r3++, "Actual cost:",      fActualCost);
        addFullRow(s2b, r3++, "Tax depn cost:",    fTaxDepnCost);
        addFullRow(s2b, r3++, "Book depn cost:",   fBookDepnCost);
        s2b.add(fmtLbl("Write down by date:"), 0, r3); s2b.add(dpWriteDown, 1, r3, 4, 1); r3++;

        // Depreciation two-column headers
        s2b.add(bold("Tax Depreciation:"),  0, r3, 2, 1);
        s2b.add(bold("Book Depreciation:"), 3, r3, 2, 1); r3++;

        // Method row
        s2b.add(fmtLbl("Method (S/D):"), 0, r3);
        HBox taxMRow = new HBox(6, fTaxMethod); taxMRow.setAlignment(Pos.CENTER_LEFT);
        s2b.add(taxMRow, 1, r3);
        s2b.add(fmtLbl("Method (S/D):"), 3, r3);
        HBox bookMRow = new HBox(6, fBookMethod); bookMRow.setAlignment(Pos.CENTER_LEFT);
        s2b.add(bookMRow, 4, r3); r3++;

        // Start depn date
        s2b.add(fmtLbl("Start depn date:"), 0, r3); s2b.add(dpStartTax, 1, r3);
        s2b.add(fmtLbl("Start depn date:"), 3, r3); s2b.add(dpStartBook, 1+3, r3); r3++;

        // Code row with lookup
        Button btnTaxCode  = lookupBtn();
        Button btnBookCode = lookupBtn();
        btnTaxCode.setOnAction(e -> new LookupDialog(lookupService, LookupDialog.LookupType.DEPN_CODE, companyNo,
            code -> { fTaxCode.setText(code.trim()); lTaxCDesc.setText(acqService.lookupDesc(companyNo, "FACODDN", "depn_code", code.trim())); }).show(dlg));
        btnBookCode.setOnAction(e -> new LookupDialog(lookupService, LookupDialog.LookupType.DEPN_CODE, companyNo,
            code -> { fBookCode.setText(code.trim()); lBookCDesc.setText(acqService.lookupDesc(companyNo, "FACODDN", "depn_code", code.trim())); }).show(dlg));
        s2b.add(fmtLbl("Code:"), 0, r3);
        HBox tcRow = new HBox(4, fTaxCode, btnTaxCode, lTaxCDesc); tcRow.setAlignment(Pos.CENTER_LEFT);
        s2b.add(tcRow, 1, r3);
        s2b.add(fmtLbl("Code:"), 3, r3);
        HBox bcRow = new HBox(4, fBookCode, btnBookCode, lBookCDesc); bcRow.setAlignment(Pos.CENTER_LEFT);
        s2b.add(bcRow, 4, r3); r3++;

        // Rate Yr1/Yr2
        s2b.add(fmtLbl("Rate Yr1 / Yr2+:"), 0, r3);
        s2b.add(new HBox(4, fTaxRateYr1, new Label("/"), fTaxRateYr2), 1, r3);
        s2b.add(fmtLbl("Rate Yr1 / Yr2+:"), 3, r3);
        s2b.add(new HBox(4, fBookRateYr1, new Label("/"), fBookRateYr2), 4, r3); r3++;

        // Calc ind — D=days W=work days F=fixed (COBOL D-07-04)
        s2b.add(fmtLbl("Calc method:"), 0, r3);
        s2b.add(fTaxCalcInd, 1, r3);
        s2b.add(fmtLbl("Calc method:"), 3, r3);
        s2b.add(fBookCalcInd, 4, r3); r3++;
        // Calc base — D=depn to date O=opening balance (COBOL D-07-05)
        s2b.add(fmtLbl("Calc base:"), 0, r3);
        s2b.add(fTaxCalcBase, 1, r3);
        s2b.add(fmtLbl("Calc base:"), 3, r3);
        s2b.add(fBookCalcBase, 4, r3); r3++;

        // Frequency
        s2b.add(fmtLbl("Frequency (periods):"), 0, r3); s2b.add(fTaxFreq, 1, r3);
        s2b.add(fmtLbl("Frequency (periods):"), 3, r3); s2b.add(fBookFreq, 4, r3); r3++;

        // Post depn to (full width) — C=Cost Ledger B=BA Ledger N=Neither
        s2b.add(fmtLbl("Post depreciation to:"), 0, r3); s2b.add(fPostTo, 1, r3, 4, 1); r3++;
        addFullRow(s2b, r3,   "Ledger code:",                  fLedgerCode);

        // ── Tab 4: Opening Balances (S3) ─────────────────────
        // Tax side
        DatePicker dpLastTaxDepn  = dp(null);
        TextField fAccumTaxDepn   = tf((full != null ? decStr(full.accumTaxDepn) : ""),  14);
        TextField fAccumTaxAdj    = tf((full != null ? decStr(full.accumTaxAdj) : ""),   14);
        DatePicker dpLastTaxReval = dp(null);
        TextField fLastTaxRevalVal= tf((full != null ? decStr(full.lastTaxRevalVal) : ""),14);
        TextField fPoolTaxBal     = tf((full != null ? decStr(full.poolTaxBal) : ""),    14);

        // Book side
        DatePicker dpLastBookDepn  = dp(null);
        TextField fAccumBookDepn   = tf((full != null ? decStr(full.accumBookDepn) : ""),  14);
        TextField fAccumBookAdj    = tf((full != null ? decStr(full.accumBookAdj) : ""),   14);
        DatePicker dpLastBookReval = dp(null);
        TextField fLastBookRevalVal= tf((full != null ? decStr(full.lastBookRevalVal) : ""),14);
        TextField fPoolBookBal     = tf((full != null ? decStr(full.poolBookBal) : ""),    14);

        GridPane s3 = new GridPane();
        s3.setHgap(14); s3.setVgap(8); s3.setPadding(new Insets(16));
        s3.getColumnConstraints().addAll(
            new ColumnConstraints(160), new ColumnConstraints(180),
            new ColumnConstraints(16),
            new ColumnConstraints(160), new ColumnConstraints(180));

        int r4 = 0;
        s3.add(bold("Tax Depreciation:"),  0, r4, 2, 1);
        s3.add(bold("Book Depreciation:"), 3, r4, 2, 1); r4++;
        ob2col(s3, r4++, "Depreciated thru to:", dpLastTaxDepn, dpLastBookDepn);
        ob2col(s3, r4++, "Accum depn:",           fAccumTaxDepn,  fAccumBookDepn);
        ob2col(s3, r4++, "Accum adjustmts:",      fAccumTaxAdj,   fAccumBookAdj);
        s3.add(bold("Revaluation:"), 0, r4, 2, 1);
        s3.add(bold("Revaluation:"), 3, r4, 2, 1); r4++;
        ob2col(s3, r4++, "Last reval date:",      dpLastTaxReval, dpLastBookReval);
        ob2col(s3, r4++, "Last reval value:",     fLastTaxRevalVal, fLastBookRevalVal);
        s3.add(bold("Pool balances (at last yr end):"), 0, r4, 5, 1); r4++;
        ob2col(s3, r4, "Pool tax balance:", fPoolTaxBal, fPoolBookBal);

        // ── Tab 5: Bar Codes (P3 / S5) ───────────────────────
        TextArea taBarCodes = new TextArea("");
        taBarCodes.setPrefRowCount(8);
        taBarCodes.setPromptText("Enter bar codes, one per line");
        taBarCodes.setStyle("-fx-font-family:'Courier New';-fx-font-size:12px;");
        VBox s5 = new VBox(8, new Label("Bar codes (one per line):"), taBarCodes);
        s5.setPadding(new Insets(16));

        // ── Assemble tabs ─────────────────────────────────────
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
            tab("Asset Details",   scroll(s2)),
            tab("Supplier / Other",scroll(s2a)),
            tab("Cost & Depn",     scroll(s2b)),
            tab("Opening Balances",scroll(s3)),
            tab("Bar Codes",       s5)
        );

        // ── Save / Cancel ─────────────────────────────────────
        Button btnSave   = btnPrimary(isAdd ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(e -> dlg.close());
        btnSave.setOnAction(e -> {
            String assetNoV  = fAssetNo.getText().trim().toUpperCase();
            String desc1V    = fDesc1.getText().trim();
            String locV      = fLoc.getText().trim();
            String deptV     = fDept.getText().trim();
            String grpV      = fGrp.getText().trim();
            String subgrpV   = fSubGrp.getText().trim();
            String siteV     = fSite.getText().trim();
            String attachV   = fAttachTo.getText().trim().toUpperCase();
            String acqnTypeV = fAcqnType.getText().trim().toUpperCase();
            String costV     = fActualCost.getText().trim().replace(",","");
            // Extract single-char values from ChoiceBoxes
            String taxMethodV  = choiceChar(fTaxMethod.getValue());
            String bookMethodV = choiceChar(fBookMethod.getValue());
            String taxCalcIndV = choiceChar(fTaxCalcInd.getValue());
            String taxCalcBaseV= choiceChar(fTaxCalcBase.getValue());
            String bookCalcIndV= choiceChar(fBookCalcInd.getValue());
            String bookCalcBaseV=choiceChar(fBookCalcBase.getValue());
            String postToV     = choiceChar(fPostTo.getValue());
            String taxCodeV    = fTaxCode.getText().trim();
            String bookCodeV   = fBookCode.getText().trim();

            // S2: Asset No required
            if (assetNoV.isEmpty()) {
                tabs.getSelectionModel().select(0);
                markError(fAssetNo, "Asset No is required.");
                return;
            }
            clearError(fAssetNo);
            // S2: Description 1 required
            if (desc1V.isEmpty()) {
                tabs.getSelectionModel().select(0);
                markError(fDesc1, "Description 1 is required.");
                return;
            }
            clearError(fDesc1);
            // S2: Duplicate + code checks run in saveTransaction background thread (DB calls not safe on FX thread)
            // S2: Acquisition date required and within fiscal year
            if (dpAcqn.getValue() == null) {
                showAlert("Validation", "Acquisition date is required.");
                tabs.getSelectionModel().select(0); dpAcqn.requestFocus(); return;
            }
            java.time.LocalDate acqnD  = dpAcqn.getValue();
            java.time.LocalDate yrStart = appSession.getYrStartDate();
            java.time.LocalDate yrEnd   = appSession.getYrEndDate();
            if (yrStart != null && yrEnd != null &&
                    (acqnD.isBefore(yrStart) || acqnD.isAfter(yrEnd))) {
                showAlert("Validation",
                    "Acquisition date " + acqnD + " is outside the fiscal year\n" +
                    "(" + yrStart + "  to  " + yrEnd + ").");
                tabs.getSelectionModel().select(0); dpAcqn.requestFocus(); return;
            }
            // S2: Acquisition type must be P/N/U/I or blank
            if (!acqnTypeV.isEmpty() && !List.of("P","N","U","I").contains(acqnTypeV)) {
                showAlert("Validation", "Acquisition type must be P (Purchase), N (New), U (Used) or I (Imported).");
                tabs.getSelectionModel().select(0); return;
            }
            // S2: Location required
            if (locV.isEmpty()) {
                tabs.getSelectionModel().select(0);
                markError(fLoc, "Location code is required."); return;
            }
            // S2: Department required
            if (deptV.isEmpty()) {
                tabs.getSelectionModel().select(0);
                markError(fDept, "Department code is required."); return;
            }
            // S2: Group required
            if (grpV.isEmpty()) {
                tabs.getSelectionModel().select(0);
                markError(fGrp, "Group code is required."); return;
            }
            clearError(fLoc); clearError(fDept); clearError(fGrp);
            // S2: Code existence + attach-to checks run in saveTransaction background thread
            // S2B: Actual cost required and > 0
            if (costV.isEmpty()) {
                tabs.getSelectionModel().select(2);
                markError(fActualCost, "Actual cost is required."); return;
            }
            try {
                java.math.BigDecimal costBd = new java.math.BigDecimal(costV);
                if (costBd.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                    tabs.getSelectionModel().select(2);
                    markError(fActualCost, "Actual cost must be greater than zero."); return;
                }
                clearError(fActualCost);
            } catch (NumberFormatException nfe) {
                tabs.getSelectionModel().select(2);
                markError(fActualCost, "Actual cost must be a valid number."); return;
            }
            // S2B: Tax depn method must be S, D or blank
            if (!taxMethodV.isEmpty() && !List.of("S","D").contains(taxMethodV)) {
                showAlert("Validation", "Tax depreciation method must be S (Straight-line) or D (Diminishing).");
                tabs.getSelectionModel().select(2); return;
            }
            // S2B: Book depn method must be S, D or blank
            if (!bookMethodV.isEmpty() && !List.of("S","D").contains(bookMethodV)) {
                showAlert("Validation", "Book depreciation method must be S (Straight-line) or D (Diminishing).");
                tabs.getSelectionModel().select(2); return;
            }
            // S2B: If method entered, code is required
            // D-08: Tax frequency required, 1–13
            String taxFreqStr = fTaxFreq.getText().trim();
            if (taxFreqStr.isEmpty()) {
                markError(fTaxFreq, "Tax depreciation frequency is required (1–13).");
                tabs.getSelectionModel().select(2); fTaxFreq.requestFocus(); return;
            }
            try {
                int tf2 = Integer.parseInt(taxFreqStr);
                if (tf2 < 1 || tf2 > 13) {
                    markError(fTaxFreq, "Tax frequency must be 1–13.");
                    tabs.getSelectionModel().select(2); fTaxFreq.requestFocus(); return;
                }
            } catch (NumberFormatException ex) {
                markError(fTaxFreq, "Tax frequency must be a number.");
                tabs.getSelectionModel().select(2); fTaxFreq.requestFocus(); return;
            }
            // D-12: Book frequency required, 1–13
            String bookFreqStr = fBookFreq.getText().trim();
            if (bookFreqStr.isEmpty()) {
                markError(fBookFreq, "Book depreciation frequency is required (1–13).");
                tabs.getSelectionModel().select(2); fBookFreq.requestFocus(); return;
            }
            try {
                int bf2 = Integer.parseInt(bookFreqStr);
                if (bf2 < 1 || bf2 > 13) {
                    markError(fBookFreq, "Book frequency must be 1–13.");
                    tabs.getSelectionModel().select(2); fBookFreq.requestFocus(); return;
                }
            } catch (NumberFormatException ex) {
                markError(fBookFreq, "Book frequency must be a number.");
                tabs.getSelectionModel().select(2); fBookFreq.requestFocus(); return;
            }
            if (!taxMethodV.isEmpty() && taxCodeV.isEmpty()) {
                showAlert("Validation", "Tax depreciation code is required when a method is entered.");
                tabs.getSelectionModel().select(2); return;
            }
            if (!bookMethodV.isEmpty() && bookCodeV.isEmpty()) {
                showAlert("Validation", "Book depreciation code is required when a method is entered.");
                tabs.getSelectionModel().select(2); return;
            }
            // S2B: Depn code existence checks run in saveTransaction background thread
            // S2B: Depn rates must be numeric if entered
            for (TextField rateField : new TextField[]{fTaxRateYr1, fTaxRateYr2, fBookRateYr1, fBookRateYr2}) {
                String rv = rateField.getText().trim().replace(",","");
                if (!rv.isEmpty()) {
                    try { new java.math.BigDecimal(rv); }
                    catch (NumberFormatException nfe) {
                        showAlert("Validation", "Depreciation rates must be numeric.");
                        tabs.getSelectionModel().select(2); return;
                    }
                }
            }
            // S2B: Post depn to must be N/C/B or blank
            if (!postToV.isEmpty() && !List.of("N","C","B").contains(postToV)) {
                showAlert("Validation", "Post depreciation to must be N (None), C (Cost Ledger) or B (BA Ledger).");
                tabs.getSelectionModel().select(2); return;
            }
            // All field values validated — pass to background thread for DB checks + save
            // (DB lookups for duplicate/code checks run on background thread)
            saveTransaction(existing, isAdd, dlg,
                // Capture all validated values as strings now (on FX thread)
                assetNoV, desc1V, fDesc2.getText().trim(), fAlpha.getText().trim(),
                locV, deptV, grpV, subgrpV, siteV, attachV,
                cbPooled.isSelected() ? "Y" : "N",
                fQty.getText().trim(), dpAcqn, acqnTypeV, fIntOrder.getText().trim(),
                fSupplierNo.getText().trim(), fInvoiceNo.getText().trim(),
                cbLeased.isSelected() ? "Y" : "N",
                fContractNo.getText().trim(), fPayAmt.getText().trim(), fPayFreq.getText().trim(),
                dpExpiry, fResidual.getText().trim(), fContractVal.getText().trim(),
                fDisposalVal.getText().trim(),
                fInsType.getText().trim(), fCurrentIns.getText().trim(),
                fReplNewVal.getText().trim(), dpReplAsAt,
                fRef.getText().trim(), costV,
                fTaxDepnCost.getText().trim(), fBookDepnCost.getText().trim(), dpWriteDown,
                taxMethodV, dpStartTax, taxCodeV,
                fTaxRateYr1.getText().trim(), fTaxRateYr2.getText().trim(),
                taxCalcIndV, taxCalcBaseV, fTaxFreq.getText().trim(),
                bookMethodV, dpStartBook, bookCodeV,
                fBookRateYr1.getText().trim(), fBookRateYr2.getText().trim(),
                bookCalcIndV, bookCalcBaseV, fBookFreq.getText().trim(),
                postToV, fLedgerCode.getText().trim(),
                dpLastTaxDepn, fAccumTaxDepn.getText().trim(), fAccumTaxAdj.getText().trim(),
                dpLastTaxReval, fLastTaxRevalVal.getText().trim(),
                dpLastBookDepn, fAccumBookDepn.getText().trim(), fAccumBookAdj.getText().trim(),
                dpLastBookReval, fLastBookRevalVal.getText().trim(),
                fPoolTaxBal.getText().trim(), fPoolBookBal.getText().trim(),
                taBarCodes.getText());
            // dlg.close() is called inside saveTransaction on success
        });

        VBox root = new VBox(0, tabs, btnBar(btnSave, btnCancel));
        Scene scene = new Scene(root, 760, 560);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        dlg.setScene(scene);
        dlg.showAndWait();
    }

    // ══════════════════════════════════════════════════════════
    // Data operations
    // ══════════════════════════════════════════════════════════

    private void loadList() {
        status("Loading…", false);
        exec.submit(() -> {
            try {
                List<Map<String, Object>> data = acqService.loadBatchTransactions(companyNo, batchNo);
                List<AcqnRow> rowList = data.stream().map(m -> {
                    AcqnRow r = new AcqnRow();
                    r.assetNo    = strMap(m, "asset_no");
                    r.desc1      = strMap(m, "desc_1");
                    r.acqnDate   = strMap(m, "trans_trx_date");
                    r.locCode    = strMap(m, "loc_code");
                    r.deptCode   = strMap(m, "dept_code");
                    r.grpCode    = strMap(m, "grp_code");
                    r.subgrpCode = strMap(m, "subgrp_code");
                    Object costObj = m.get("actual_cost");
                    r.cost       = costObj == null ? "" : costObj.toString();
                    r.status     = statusLabel(strMap(m, "asset_status"));
                    return r;
                }).toList();
                Platform.runLater(() -> {
                    rows.setAll(rowList);
                    status(rowList.size() + " transaction(s) in batch " + batchNo, false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    /**
     * Build AcquisitionRecord DTO from validated field values and delegate to service.
     * All fields are pre-extracted on the FX thread before this call.
     */
    private void saveTransaction(AcqnRow existing, boolean isAdd, Stage dlg, Object... fields) {
        String assetNo        = s(fields,  0).toUpperCase();
        String desc1          = s(fields,  1);
        String desc2          = s(fields,  2);
        String alpha          = s(fields,  3);
        String locCode        = s(fields,  4);
        String deptCode       = s(fields,  5);
        String grpCode        = s(fields,  6);
        String subgrp         = s(fields,  7);
        String site           = s(fields,  8);
        String attachTo       = s(fields,  9).toUpperCase();
        boolean pooled        = "Y".equals(s(fields, 10));
        String qtyStr         = s(fields, 11);
        String acqnType       = s(fields, 13);
        String intOrder       = s(fields, 14);
        String supplierNo     = s(fields, 15);
        String invoiceNo      = s(fields, 16);
        boolean leased        = "Y".equals(s(fields, 17));
        String contractNo     = s(fields, 18);
        String payAmt         = s(fields, 19);
        String payFreq        = s(fields, 20);
        String residual       = s(fields, 22);
        String insType        = s(fields, 25);
        String currIns        = s(fields, 26);
        String replNew        = s(fields, 27);
        String costStr        = s(fields, 30);
        String taxDepnCostStr = s(fields, 31);
        String bookDepnCostStr= s(fields, 32);
        String taxMethod      = s(fields, 34);
        String taxCode        = s(fields, 36);
        String taxR1          = s(fields, 37);
        String taxR2          = s(fields, 38);
        String taxCalcInd     = s(fields, 39);
        String taxCalcBase    = s(fields, 40);
        String taxFreq        = s(fields, 41);
        String bookMethod     = s(fields, 42);
        String bookCode       = s(fields, 44);
        String bookR1         = s(fields, 45);
        String bookR2         = s(fields, 46);
        String bookCalcInd    = s(fields, 47);
        String bookCalcBase   = s(fields, 48);
        String bookFreq       = s(fields, 49);
        String postTo         = s(fields, 50);
        String ledgerCode     = s(fields, 51);

        // Extract DatePicker values on FX thread before submitting to background
        LocalDate acqnDate  = dpVal(fields, 12, LocalDate.now());
        LocalDate lseExpiry = dpVal(fields, 21, null);
        LocalDate replAsAt  = dpVal(fields, 28, null);
        LocalDate writeDown = dpVal(fields, 33, null);
        LocalDate startTax  = dpVal(fields, 35, null);
        LocalDate startBook = dpVal(fields, 43, null);

        if (assetNo.isEmpty()) { status("Asset number is required.", true); return; }

        int qty = 1;
        try { if (!qtyStr.isEmpty()) qty = Integer.parseInt(qtyStr); } catch (Exception ignored) {}

        // Build DTO
        AcquisitionRecord rec = new AcquisitionRecord();
        rec.assetNo      = assetNo;
        rec.desc1        = desc1;
        rec.desc2        = desc2;
        rec.alpha        = alpha;
        rec.locCode      = locCode;
        rec.deptCode     = deptCode;
        rec.grpCode      = grpCode;
        rec.subgrpCode   = subgrp;
        rec.site         = site;
        rec.attachTo     = attachTo;
        rec.pooled       = pooled;
        rec.qty          = qty;
        rec.acqnDate     = acqnDate;
        rec.acqnType     = acqnType.isEmpty() ? "P" : acqnType;
        rec.intOrder     = intOrder;
        rec.suppNo       = supplierNo;
        rec.invoiceNo    = invoiceNo;
        rec.leased       = leased;
        rec.contractNo   = contractNo;
        rec.payAmt       = bd(payAmt);
        rec.payFreq      = payFreq;
        rec.lseExpiry    = lseExpiry;
        rec.residual     = bd(residual);
        rec.insType      = insType;
        rec.currIns      = bd(currIns);
        rec.replNew      = bd(replNew);
        rec.replAsAt     = replAsAt;
        rec.actualCost   = bd(costStr);
        rec.taxDepnCost  = bd(taxDepnCostStr);
        rec.bookDepnCost = bd(bookDepnCostStr);
        rec.writeDown    = writeDown;
        rec.taxMethod    = taxMethod;
        rec.startTax     = startTax;
        rec.taxCode      = taxCode;
        rec.taxRate1     = bd(taxR1);
        rec.taxRate2     = bd(taxR2);
        rec.taxCalcInd   = taxCalcInd;
        rec.taxCalcBase  = taxCalcBase;
        rec.taxFreq      = toInt(taxFreq);
        rec.bookMethod   = bookMethod;
        rec.startBook    = startBook;
        rec.bookCode     = bookCode;
        rec.bookRate1    = bd(bookR1);
        rec.bookRate2    = bd(bookR2);
        rec.bookCalcInd  = bookCalcInd;
        rec.bookCalcBase = bookCalcBase;
        rec.bookFreq     = toInt(bookFreq);
        rec.postTo       = postTo.isEmpty() ? "N" : postTo;
        rec.ledgerCode   = ledgerCode;

        exec.submit(() -> {
            String err = acqService.save(companyNo, batchNo, isAdd, rec, appSession.getUserId());
            Platform.runLater(() -> {
                if (err != null) {
                    showAlertOnStage(dlg, "Validation", err);
                } else {
                    status((isAdd ? "Added: " : "Updated: ") + rec.assetNo, false);
                    dlg.close();
                    loadList();
                }
            });
        });
    }

    /** Extract LocalDate from a DatePicker at position i in fields array. */
    /**
     * Extract a trimmed String from a saveTransaction varargs fields array.
     * Returns empty string for null entries.
     */
    private static String s(Object[] fields, int i) {
        if (fields == null || i >= fields.length || fields[i] == null) return "";
        return fields[i].toString().trim();
    }

    /**
     * Extract a trimmed String from a JDBC row-map by column key.
     * Returns empty string if the map is null, the key is absent, or the value is null.
     */
    private static String strMap(Map<String, Object> m, String key) {
        if (m == null) return "";
        Object v = m.get(key);
        return v == null ? "" : v.toString().trim();
    }

    /**
     * Extract the leading character from a ChoiceBox value of the form "X — Label".
     * Returns empty string for null/blank input.
     */
    private static String choiceChar(String choiceValue) {
        if (choiceValue == null) return "";
        String s = choiceValue.trim();
        return s.isEmpty() ? "" : s.substring(0, 1);
    }

    /**
     * Decode an FAASSET.asset_status code to a human-readable label.
     * Codes mirror the filter checkboxes on the Asset Register screen.
     */
    private static String statusLabel(String code) {
        if (code == null) return "";
        return switch (code.trim().toUpperCase()) {
            case "A" -> "Active";
            case "H" -> "On Hold";
            case "I" -> "Inactive";
            case "R" -> "Retired";
            case "U" -> "Unposted";
            case ""  -> "";
            default  -> code;
        };
    }


    private static LocalDate dpVal(Object[] fields, int i, LocalDate def) {
        if (i >= fields.length) return def;
        if (fields[i] instanceof DatePicker dp) return dp.getValue() != null ? dp.getValue() : def;
        return def;
    }

    /** Format a BigDecimal for display in a TextField — empty string if null or zero. */
    private static String decStr(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return v.stripTrailingZeros().toPlainString();
    }


    /** Parse BigDecimal safely, returning ZERO on blank/invalid. */
    private static BigDecimal bd(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.replace(",", "")); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    /** Convert string to int for frequency/period columns, returns 0 if blank/invalid. */
    private static int toInt(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private void confirmDelete(AcqnRow row) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete acquisition for asset " + row.assetNo + "?\n" +
            "This will also remove the FAASSET and FATRANS records.",
            ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Confirm Delete");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                exec.submit(() -> {
                    try {
                        acqService.deleteBatchLine(companyNo, batchNo, row.assetNo);
                        Platform.runLater(() -> { loadList(); status("Deleted: " + row.assetNo, false); });
                    } catch (Exception ex) {
                        Platform.runLater(() -> status("Delete error: " + ex.getMessage(), true));
                    }
                });
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // Excel Export / Import
    // ═══════════════════════════════════════════════════════════════

    /** Column headers — order matches both export and import. */
    private static final String[] XL_HEADERS = {
        "asset_no*",  "desc_1*",   "desc_2",    "alpha_code",
        "loc_code*",  "dept_code*","grp_code*",  "subgrp_code",
        "stake_site", "attach_to", "pooled_YN",  "qty",
        "acqn_date*", "acqn_type", "int_order",
        "supp_name",  "supp_no",   "supp_inv_no",
        "actual_cost*","tax_depn_cost","book_depn_cost",
        "tax_method", "tax_code",  "tax_rate_yr1","tax_rate_yr2",
        "book_method","book_code", "book_rate_yr1","book_rate_yr2",
        "post_to",    "ledger_code"
    };
    // Columns marked * are required; others optional

    private void exportToExcel(Window owner) {
        // Choose save location — default to user Downloads folder
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Export Acquisitions to Excel");
        fc.setInitialFileName("batch_" + batchNo + "_acquisitions.xlsx");
        fc.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("Excel Files", "*.xlsx"));
        // Default directory: Downloads
        java.io.File downloads = new java.io.File(
            System.getProperty("user.home"), "Downloads");
        if (!downloads.exists()) downloads = new java.io.File(System.getProperty("user.home"));
        fc.setInitialDirectory(downloads);
        java.io.File chosenFile = fc.showSaveDialog(owner instanceof javafx.stage.Stage s ? s : null);
        if (chosenFile == null) return;
        // Ensure .xlsx extension — Windows FileChooser doesn't auto-append it
        final java.io.File file = chosenFile.getName().toLowerCase().endsWith(".xlsx")
            ? chosenFile
            : new java.io.File(chosenFile.getParentFile(), chosenFile.getName() + ".xlsx");

        exec.submit(() -> {
            try {
                List<Map<String,Object>> assets = acqService.loadBatchAssetsForExport(companyNo, batchNo);

                System.out.println("FAAQ01 export step 3: building workbook");
                org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                    new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                org.apache.poi.xssf.usermodel.XSSFSheet sh = wb.createSheet("Acquisitions");

                // ── Header styles ─────────────────────────────────────────
                org.apache.poi.xssf.usermodel.XSSFCellStyle hdrStyle = wb.createCellStyle();
                org.apache.poi.xssf.usermodel.XSSFFont hdrFont = wb.createFont();
                hdrFont.setBold(true);
                hdrFont.setFontHeightInPoints((short)10);
                hdrFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
                hdrStyle.setFont(hdrFont);
                hdrStyle.setFillForegroundColor(
                    org.apache.poi.ss.usermodel.IndexedColors.DARK_BLUE.getIndex());
                hdrStyle.setFillPattern(
                    org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

                org.apache.poi.xssf.usermodel.XSSFCellStyle optStyle = wb.createCellStyle();
                org.apache.poi.xssf.usermodel.XSSFFont optFont = wb.createFont();
                optFont.setBold(true);
                optFont.setFontHeightInPoints((short)10);
                optFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
                optStyle.setFont(optFont);
                optStyle.setFillForegroundColor(
                    org.apache.poi.ss.usermodel.IndexedColors.CORNFLOWER_BLUE.getIndex());
                optStyle.setFillPattern(
                    org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

                org.apache.poi.xssf.usermodel.XSSFCellStyle dataStyle = wb.createCellStyle();
                dataStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                dataStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                dataStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                dataStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);

                org.apache.poi.xssf.usermodel.XSSFCellStyle numStyle = wb.createCellStyle();
                numStyle.cloneStyleFrom(dataStyle);
                numStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

                // ── Info + header rows ────────────────────────────────────
                org.apache.poi.xssf.usermodel.XSSFRow infoRow = sh.createRow(0);
                infoRow.createCell(0).setCellValue(
                    "Landmark FA — Batch " + batchNo +
                    "   Company: " + companyNo +
                    "   (* = required)   Dates: DD/MM/YYYY");

                org.apache.poi.xssf.usermodel.XSSFRow hdr = sh.createRow(1);
                for (int ci = 0; ci < XL_HEADERS.length; ci++) {
                    org.apache.poi.xssf.usermodel.XSSFCell cell = hdr.createCell(ci);
                    cell.setCellValue(XL_HEADERS[ci]);
                    cell.setCellStyle(XL_HEADERS[ci].endsWith("*") ? hdrStyle : optStyle);
                    sh.setColumnWidth(ci, ci == 1 ? 8000 : ci == 0 ? 4500 : 3500);
                }

                // ── Data rows ─────────────────────────────────────────────
                int rowIdx = 2;
                for (Map<String,Object> a : assets) {
                    org.apache.poi.xssf.usermodel.XSSFRow row = sh.createRow(rowIdx++);
                    String[] vals = {
                        str(a,"asset_no"),          str(a,"desc_1"),           str(a,"desc_2"),
                        str(a,"alpha_code"),         str(a,"loc_code"),         str(a,"dept_code"),
                        str(a,"grp_code"),           str(a,"subgrp_code"),      str(a,"stake_site"),
                        str(a,"attach_to_asset_no"), str(a,"asset_pool_flag"),  str(a,"qty"),
                        fmtDate(a,"acqn_date"),      str(a,"acqn_type"),        str(a,"internal_order_no"),
                        str(a,"supp_name"),          str(a,"supp_no"),          str(a,"supp_inv_no"),
                        str(a,"actual_cost"),        str(a,"tax_depn_cost"),    str(a,"book_depn_cost"),
                        str(a,"tax_depn_method"),    str(a,"tax_depn_code"),    str(a,"tax_depn_rate_1"),
                        str(a,"tax_depn_rate_2"),    str(a,"book_depn_method"), str(a,"book_depn_code"),
                        str(a,"book_depn_rate_1"),   str(a,"book_depn_rate_2"),
                        str(a,"post_depn_to_cl"),    str(a,"ledger_code")
                    };
                    for (int ci = 0; ci < vals.length; ci++) {
                        org.apache.poi.xssf.usermodel.XSSFCell cell = row.createCell(ci);
                        if (ci >= 18 && ci <= 29 && !vals[ci].isEmpty()) {
                            try {
                                cell.setCellStyle(numStyle);
                                cell.setCellValue(Double.parseDouble(vals[ci]));
                                continue;
                            } catch (NumberFormatException ignored) {}
                        }
                        cell.setCellStyle(dataStyle);
                        cell.setCellValue(vals[ci]);
                    }
                }
                if (assets.isEmpty()) sh.createRow(2); // blank template row
                sh.createFreezePane(0, 2);

                System.out.println("FAAQ01 export step 4: writing workbook to memory");
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(65536);
                wb.write(baos);
                wb.close();
                int byteCount = baos.size();
                System.out.println("FAAQ01 export step 5: " + byteCount + " bytes in memory, writing to disk");

                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    fos.write(baos.toByteArray());
                    fos.flush();
                }
                System.out.println("FAAQ01 export step 6: file written OK - " + file.length() + " bytes on disk");

                final java.io.File fFile = file;
                final int fCount = assets.size();
                Platform.runLater(() -> {
                    status("Exported " + fCount + " asset(s) to " + fFile.getName(), false);
                    try {
                        String os = System.getProperty("os.name", "").toLowerCase();
                        ProcessBuilder pb;
                        if (os.contains("win")) {
                            pb = new ProcessBuilder("cmd", "/c", "start", "",
                                fFile.getAbsolutePath());
                        } else if (os.contains("mac")) {
                            pb = new ProcessBuilder("open", fFile.getAbsolutePath());
                        } else {
                            pb = new ProcessBuilder("xdg-open", fFile.getAbsolutePath());
                        }
                        pb.start();
                    } catch (Exception openEx) {
                        System.out.println("FAAQ01 export: could not auto-open: " + openEx.getMessage());
                        showInfo("Export Complete",
                            "Saved " + fCount + " asset(s) to:\n" + fFile.getAbsolutePath());
                    }
                });

            } catch (Throwable ex) {
                // Catch Throwable not just Exception — catches Errors too
                ex.printStackTrace();
                final String errMsg = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                System.err.println("FAAQ01 export FAILED: " + errMsg);
                Platform.runLater(() -> {
                    status("Export error: " + errMsg, true);
                    showAlertOnStage(null, "Export Failed",
                        "Could not save Excel file.\n\n" + errMsg + "\n\nSee console for full stack trace.");
                });
            }
        });
    }

    /** Format a java.sql.Date map value as DD/MM/YYYY string, or "" if zero/null. */
    private String fmtDate(Map<String,Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return "";
        java.time.LocalDate ld;
        if (v instanceof java.sql.Date d) ld = d.toLocalDate();
        else try { ld = java.time.LocalDate.parse(v.toString()); }
             catch (Exception e) { return v.toString(); }
        if (ld.getYear() <= 1900) return "";
        return String.format("%02d/%02d/%04d", ld.getDayOfMonth(), ld.getMonthValue(), ld.getYear());
    }

    private void importFromExcel(Window owner) {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Import Acquisitions from Excel");
        fc.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("Excel Files", "*.xlsx","*.xls"));
        java.io.File dlDir = new java.io.File(System.getProperty("user.home"), "Downloads");
        if (!dlDir.exists()) dlDir = new java.io.File(System.getProperty("user.home"));
        fc.setInitialDirectory(dlDir);
        java.io.File file = fc.showOpenDialog(owner instanceof javafx.stage.Stage s ? s : null);
        if (file == null) return;

        exec.submit(() -> {
            try {
                org.apache.poi.ss.usermodel.Workbook wb =
                    org.apache.poi.ss.usermodel.WorkbookFactory.create(file);
                org.apache.poi.ss.usermodel.Sheet sh = wb.getSheetAt(0);

                // Find header row (row index 1 = row 2 in Excel)
                org.apache.poi.ss.usermodel.Row hdrRow = sh.getRow(1);
                if (hdrRow == null) {
                    Platform.runLater(() -> showAlertOnStage(null, "Import",
                        "Could not find header row (row 2). Check the template format."));
                    wb.close(); return;
                }

                // Map column index from header names
                java.util.Map<String,Integer> colIdx = new java.util.HashMap<>();
                for (int c = 0; c <= hdrRow.getLastCellNum(); c++) {
                    org.apache.poi.ss.usermodel.Cell cell = hdrRow.getCell(c);
                    if (cell != null) {
                        String h = cell.getStringCellValue().replace("*","").trim();
                        colIdx.put(h, c);
                    }
                }

                int imported = 0, skipped = 0;
                java.util.List<String> errors = new java.util.ArrayList<>();

                for (int r = 2; r <= sh.getLastRowNum(); r++) {
                    org.apache.poi.ss.usermodel.Row row = sh.getRow(r);
                    if (row == null) continue;

                    // Read all mapped columns
                    String assetNo  = xlStr(row, colIdx, "asset_no").toUpperCase().trim();
                    String desc1    = xlStr(row, colIdx, "desc_1").trim();
                    String locCode  = xlStr(row, colIdx, "loc_code").trim();
                    String deptCode = xlStr(row, colIdx, "dept_code").trim();
                    String grpCode  = xlStr(row, colIdx, "grp_code").trim();

                    // Skip blank rows
                    if (assetNo.isEmpty() && desc1.isEmpty()) continue;

                    // Validate required fields
                    if (assetNo.isEmpty()) { errors.add("Row " + (r+1) + ": asset_no is required"); skipped++; continue; }
                    if (desc1.isEmpty())   { errors.add("Row " + (r+1) + ": desc_1 is required for " + assetNo); skipped++; continue; }
                    if (locCode.isEmpty()) { errors.add("Row " + (r+1) + " [" + assetNo + "]: loc_code is required"); skipped++; continue; }
                    if (deptCode.isEmpty()){ errors.add("Row " + (r+1) + " [" + assetNo + "]: dept_code is required"); skipped++; continue; }
                    if (grpCode.isEmpty()) { errors.add("Row " + (r+1) + " [" + assetNo + "]: grp_code is required"); skipped++; continue; }

                    String desc2    = xlStr(row, colIdx, "desc_2");
                    String alpha    = xlStr(row, colIdx, "alpha_code");
                    String subgrp   = xlStr(row, colIdx, "subgrp_code");
                    String site     = xlStr(row, colIdx, "stake_site");
                    String attachTo = xlStr(row, colIdx, "attach_to").toUpperCase();
                    String pooled   = xlStr(row, colIdx, "pooled_YN").toUpperCase();
                    int qty = 1; try { qty = (int) Math.round(xlNum(row, colIdx, "qty")); } catch (Exception ignored) {}
                    String acqnType = xlStr(row, colIdx, "acqn_type").toUpperCase();
                    if (acqnType.isEmpty()) acqnType = "P";
                    String intOrder  = xlStr(row, colIdx, "int_order");
                    String suppName  = xlStr(row, colIdx, "supp_name");
                    String suppNo    = xlStr(row, colIdx, "supp_no");
                    String suppInv   = xlStr(row, colIdx, "supp_inv_no");
                    String taxMeth   = xlStr(row, colIdx, "tax_method").toUpperCase();
                    String taxCode   = xlStr(row, colIdx, "tax_code");
                    String bookMeth  = xlStr(row, colIdx, "book_method").toUpperCase();
                    String bookCode  = xlStr(row, colIdx, "book_code");
                    String postTo    = xlStr(row, colIdx, "post_to").toUpperCase();
                    String ledgerCode= xlStr(row, colIdx, "ledger_code");

                    BigDecimal actualCost = BigDecimal.valueOf(xlNum(row, colIdx, "actual_cost"));
                    if (actualCost.compareTo(BigDecimal.ZERO) <= 0) {
                        errors.add("Row " + (r+1) + " [" + assetNo + "]: actual_cost must be > 0"); skipped++; continue;
                    }
                    BigDecimal taxDepnCost  = BigDecimal.valueOf(xlNum(row, colIdx, "tax_depn_cost"));
                    BigDecimal bookDepnCost = BigDecimal.valueOf(xlNum(row, colIdx, "book_depn_cost"));
                    BigDecimal taxR1  = BigDecimal.valueOf(xlNum(row, colIdx, "tax_rate_yr1"));
                    BigDecimal taxR2  = BigDecimal.valueOf(xlNum(row, colIdx, "tax_rate_yr2"));
                    BigDecimal bookR1 = BigDecimal.valueOf(xlNum(row, colIdx, "book_rate_yr1"));
                    BigDecimal bookR2 = BigDecimal.valueOf(xlNum(row, colIdx, "book_rate_yr2"));

                    // Parse acquisition date (DD/MM/YYYY or YYYY-MM-DD)
                    LocalDate acqnDate = LocalDate.now();
                    String dateStr = xlStr(row, colIdx, "acqn_date");
                    if (!dateStr.isEmpty()) {
                        try {
                            String[] parts = dateStr.contains("/") ? dateStr.split("/") : dateStr.split("-");
                            if (parts.length == 3) {
                                int d, mo, yr;
                                if (parts[0].length() == 4) { yr=Integer.parseInt(parts[0]); mo=Integer.parseInt(parts[1]); d=Integer.parseInt(parts[2]); }
                                else { d=Integer.parseInt(parts[0]); mo=Integer.parseInt(parts[1]); yr=Integer.parseInt(parts[2]); }
                                acqnDate = LocalDate.of(yr, mo, d);
                            }
                        } catch (Exception ignored) {}
                    }

                    final LocalDate fAcqnDate = acqnDate;

                    // Build import record
                    AcquisitionRecord importRec = new AcquisitionRecord();
                    importRec.assetNo    = assetNo;
                    importRec.desc1      = desc1;
                    importRec.desc2      = desc2;
                    importRec.alpha      = alpha;
                    importRec.locCode    = locCode;
                    importRec.deptCode   = deptCode;
                    importRec.grpCode    = grpCode;
                    importRec.subgrpCode = subgrp;
                    importRec.site       = site;
                    importRec.attachTo   = attachTo;
                    importRec.pooled     = "Y".equalsIgnoreCase(pooled);
                    importRec.qty        = qty;
                    importRec.acqnType   = acqnType;
                    importRec.intOrder   = intOrder;
                    importRec.suppNo     = suppNo;
                    importRec.invoiceNo  = suppInv;
                    importRec.taxMethod  = taxMeth;
                    importRec.taxCode    = taxCode;
                    importRec.bookMethod = bookMeth;
                    importRec.bookCode   = bookCode;
                    importRec.postTo     = postTo.isEmpty() ? "N" : postTo;
                    importRec.ledgerCode = ledgerCode;
                    importRec.actualCost   = actualCost;
                    importRec.taxDepnCost  = taxDepnCost;
                    importRec.bookDepnCost = bookDepnCost;
                    importRec.taxRate1     = taxR1;
                    importRec.taxRate2     = taxR2;
                    importRec.bookRate1    = bookR1;
                    importRec.bookRate2    = bookR2;
                    importRec.acqnDate     = fAcqnDate;

                    AcquisitionService.ImportResult importResult =
                        acqService.importAsset(companyNo, batchNo, importRec, appSession.getUserId());
                    if (importResult.outcome() == AcquisitionService.ImportOutcome.SKIPPED_DUPLICATE ||
                        importResult.outcome() == AcquisitionService.ImportOutcome.SKIPPED_ERROR) {
                        errors.add("Row " + (r+1) + " [" + assetNo + "]: " + importResult.message());
                        skipped++;
                        continue;
                    }
                                        imported++;
                }
                wb.close();

                final int fImported = imported, fSkipped = skipped;
                final String errSummary = errors.isEmpty() ? "" :
                    "\n\nSkipped rows:\n" + String.join("\n", errors);
                Platform.runLater(() -> {
                    loadList();
                    status("Import: " + fImported + " imported, " + fSkipped + " skipped.", fSkipped > 0);
                    showInfo("Import Complete",
                        "Imported: " + fImported + " asset(s)\n" +
                        "Skipped:  " + fSkipped + " row(s)" + errSummary);
                });

            } catch (Exception ex) {
                ex.printStackTrace();
                Platform.runLater(() -> {
                    status("Import error: " + ex.getMessage(), true);
                    showAlertOnStage(null, "Import Error", ex.getMessage());
                });
            }
        });
    }

    /** Read a string cell value from a row by column name. */
    private static String xlStr(org.apache.poi.ss.usermodel.Row row,
                                  java.util.Map<String,Integer> colIdx, String name) {
        Integer ci = colIdx.get(name);
        if (ci == null) return "";
        org.apache.poi.ss.usermodel.Cell cell = row.getCell(ci);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)
                ? String.format("%02d/%02d/%04d",
                    cell.getLocalDateTimeCellValue().getDayOfMonth(),
                    cell.getLocalDateTimeCellValue().getMonthValue(),
                    cell.getLocalDateTimeCellValue().getYear())
                : String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> cell.getBooleanCellValue() ? "Y" : "N";
            case FORMULA -> cell.getCachedFormulaResultType() ==
                org.apache.poi.ss.usermodel.CellType.NUMERIC
                ? String.valueOf(cell.getNumericCellValue())
                : cell.getStringCellValue().trim();
            default -> "";
        };
    }

    /** Read a numeric cell value from a row by column name. Returns 0 if missing/blank. */
    private static double xlNum(org.apache.poi.ss.usermodel.Row row,
                                  java.util.Map<String,Integer> colIdx, String name) {
        String s = xlStr(row, colIdx, name).replace(",","");
        if (s.isEmpty()) return 0.0;
        try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
    }

    private void postBatch(Window owner) {
        if (batchNo <= 0) { showAlert("Post Batch", "No batch selected."); return; }

        // Confirm with summary of what will be posted
        int finalCount = acqService.countBatchTransactions(companyNo, batchNo);

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Post batch " + batchNo + "?\n\n" +
            "This will post " + finalCount + " acquisition transaction(s):\n\n" +
            "  • Set each asset status from 'U' (Unposted) → '' (Active)\n" +
            "  • Write FATRANS acquisition records\n" +
            "  • Set CMBATCH status to 'C' (Completed)\n\n" +
            "This cannot be undone.",
            ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Post Batch " + batchNo);
        confirm.setHeaderText("Post Acquisition Batch " + batchNo);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.YES) return;

            status("Posting batch " + batchNo + "…", false);
            exec.submit(() -> {
                AcquisitionService.PostResult result =
                    acqService.postBatch(companyNo, batchNo, appSession.getUserId());
                Platform.runLater(() -> {
                    if (result.success()) {
                        showInfo("Batch Posted",
                            "Batch " + batchNo + " posted — " + result.posted() + " asset(s) activated.");
                        resetToBatchSelection();
                    } else {
                        status("Post error: " + result.error(), true);
                        showAlertOnStage(null, "Post Error", result.error());
                    }
                });
            });
        });
    }

    /** Safe decimal from map — returns ZERO if null/missing. */
    private static BigDecimal dec(Map<String,Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** Safe string from map — returns "" if null/missing. */
    private static String str(Map<String,Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString().trim();
    }

    /** Safe int from map — returns 0 if null/missing. */
    private static int toInt2(Map<String,Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    // ══════════════════════════════════════════════════════════
    // Helpers — data
    // ══════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════
    // Helpers — data
    // ══════════════════════════════════════════════════════════

    private void wireLookup(TextField fld, Label lbl,
                              String table, String keyCol,
                              boolean required, Window owner) {
        fld.focusedProperty().addListener((o, ov, focused) -> {
            if (focused) return;
            String code = fld.getText().trim();
            if (code.isEmpty()) { lbl.setText(""); return; }
            String err = acqService.validateCode(companyNo, code, table, keyCol, "");
            if (err == null) {
                lbl.setText(acqService.lookupDesc(companyNo, table, keyCol, code));
                lbl.setStyle("-fx-text-fill:#555553;");
            } else if (required) {
                lbl.setText("⚠ Not on file");
                lbl.setStyle("-fx-text-fill:#DC2626;");
                Platform.runLater(fld::requestFocus);
            } else {
                lbl.setText("⚠ Not on file");
                lbl.setStyle("-fx-text-fill:#F59E0B;");
            }
        });
    }

    // Legacy single-arg wrapper used by openEntryDlg for non-required code fields
    private void wireLookup(TextField fld, Label lbl, String table, String keyCol) {
        wireLookup(fld, lbl, table, keyCol, false, null);
    }


    private static LocalDate toLocalDate(Map<String,Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        LocalDate ld = (v instanceof java.sql.Date d) ? d.toLocalDate()
            : LocalDate.parse(v.toString());
        return (ld.getYear() <= 1900) ? null : ld;
    }

    private HBox statusBar() {
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
            lblStatus.setStyle("-fx-font-size:11px;-fx-text-fill:" + (err?"#C0392B":"#888780") + ";");
        });
    }

    private Label hdr(String t) {
        Label l = new Label(t);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setStyle("-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:white;" +
                   "-fx-background-color:#1A6EF5;-fx-padding:10 16 10 16;");
        return l;
    }

    private Label infoLbl(String t) {
        Label l = new Label(t); l.setStyle("-fx-font-size:12px;-fx-text-fill:#555553;"); return l;
    }

    private Label fmtLbl(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(140);
        return l;
    }

    private Label bold(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-font-size:12px;-fx-font-weight:bold;-fx-text-fill:#1A6EF5;" +
                   "-fx-padding:6 0 2 0;");
        return l;
    }

    private Label descLbl() {
        Label l = new Label(); l.setStyle("-fx-font-size:11px;-fx-text-fill:#555553;"); return l;
    }

    private TextField tf(String v, int max) {
        TextField f = new TextField(v == null ? "" : v.trim());
        f.setPrefWidth(Math.min(max * 9 + 10, 300));
        return f;
    }

    private DatePicker dp(LocalDate v) { DatePicker p = new DatePicker(v); p.setPrefWidth(140); return p; }

    private Button lookupBtn() {
        Button b = new Button("…");
        b.setStyle("-fx-background-color:#EEF4FF;-fx-text-fill:#1A6EF5;-fx-border-color:#BDD1FA;" +
                   "-fx-border-radius:4;-fx-background-radius:4;-fx-padding:3 7;-fx-cursor:hand;");
        return b;
    }

    private Button btnPrimary(String t) {
        Button b = new Button(t);
        b.setStyle("-fx-background-color:#1A6EF5;-fx-text-fill:white;-fx-font-weight:bold;" +
                   "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:6 16;-fx-cursor:hand;");
        return b;
    }

    private Button btnSecondary(String t) {
        Button b = new Button(t);
        b.setStyle("-fx-background-color:white;-fx-text-fill:#374151;-fx-border-color:#D0CFC8;" +
                   "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:5 14;-fx-cursor:hand;");
        return b;
    }

    private Button btnDanger(String t) {
        Button b = new Button(t);
        b.setStyle("-fx-background-color:white;-fx-text-fill:#C0392B;-fx-border-color:#E8BDB8;" +
                   "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:5 14;-fx-cursor:hand;");
        return b;
    }

    private Button btnSuccess(String t) {
        Button b = new Button(t);
        b.setStyle("-fx-background-color:#059669;-fx-text-fill:white;-fx-font-weight:bold;" +
                   "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:6 16;-fx-cursor:hand;");
        return b;
    }

    private GridPane grid() {
        GridPane g = new GridPane(); g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(16)); return g;
    }

    private void addRow(GridPane g, int row, String label, javafx.scene.Node ctrl) {
        g.add(fmtLbl(label), 0, row); g.add(ctrl, 1, row);
    }

    private void addFullRow(GridPane g, int row, String label, javafx.scene.Node ctrl) {
        g.add(fmtLbl(label), 0, row); g.add(ctrl, 1, row, 4, 1);
    }

    private void addRowWithDesc(GridPane g, int row, String label, TextField f, Label desc) {
        g.add(fmtLbl(label), 0, row); g.add(f, 1, row); g.add(desc, 2, row);
        GridPane.setHgrow(desc, Priority.ALWAYS);
    }

    private void addLookupRow(GridPane g, int row, String label,
                               TextField fld, Label desc,
                               LookupDialog.LookupType type, Window owner) {
        Button btn = lookupBtn();
        btn.setOnAction(e -> new LookupDialog(lookupService, type, companyNo, code -> {
            fld.setText(code.trim());
            fld.fireEvent(new javafx.event.ActionEvent()); // trigger focus-out equivalent
        }).show(owner));
        HBox row2 = new HBox(4, fld, btn); row2.setAlignment(Pos.CENTER_LEFT);
        g.add(fmtLbl(label), 0, row); g.add(row2, 1, row); g.add(desc, 2, row);
        GridPane.setHgrow(desc, Priority.ALWAYS);
    }

    private HBox lookupRow(TextField fld, LookupDialog.LookupType type, Window owner) {
        Button btn = lookupBtn();
        btn.setOnAction(e -> new LookupDialog(lookupService, type, companyNo,
            code -> fld.setText(code.trim())).show(owner));
        HBox box = new HBox(4, fld, btn); box.setAlignment(Pos.CENTER_LEFT); return box;
    }

    private void sectionHdr(GridPane g, int row, String title) {
        g.add(bold(title), 0, row, 3, 1);
    }

    private void ob2col(GridPane g, int row, String label,
                         javafx.scene.Node taxCtrl, javafx.scene.Node bookCtrl) {
        g.add(fmtLbl(label), 0, row); g.add(taxCtrl, 1, row);
        g.add(fmtLbl(label), 3, row); g.add(bookCtrl, 4, row);
    }

    private HBox btnBar(Button... btns) {
        HBox bar = new HBox(10, btns);
        bar.setPadding(new Insets(10, 16, 14, 16));
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setStyle("-fx-background-color:#F2F1EC;" +
                     "-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
                     "-fx-border-width:0.5 0 0 0;");
        return bar;
    }

    private Region spacer() {
        Region r = new Region(); HBox.setHgrow(r, Priority.ALWAYS); return r;
    }

    private ScrollPane scroll(javafx.scene.Node n) {
        ScrollPane sp = new ScrollPane(n); sp.setFitToWidth(true); sp.setBorder(null); return sp;
    }

    private Tab tab(String title, javafx.scene.Node content) {
        Tab t = new Tab(title, content); return t;
    }

    @SuppressWarnings("unchecked")
    private TableColumn<AcqnRow,String> tcol(String hdr,
                                              Function<AcqnRow,String> fn, double w) {
        TableColumn<AcqnRow,String> c = new TableColumn<>(hdr);
        c.setCellValueFactory(p -> new SimpleStringProperty(fn.apply(p.getValue())));
        c.setPrefWidth(w);
        return c;
    }

    /** Highlight a field red and show a tooltip/alert — COBOL DISPLAY-MESSAGE equivalent. */
    private static void markError(TextField fld, String msg) {
        fld.setStyle(fld.getStyle()
            .replaceAll("-fx-border-color:[^;]+;", "")
            + "-fx-border-color:#DC2626; -fx-border-width:2;");
        fld.requestFocus();
        fld.selectAll();
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setTitle("Validation"); a.setHeaderText(null); a.showAndWait();
    }

    private static void clearError(TextField fld) {
        fld.setStyle(fld.getStyle()
            .replaceAll("-fx-border-color:[^;]+;", "")
            .replaceAll("-fx-border-width:[^;]+;", "")
            + "-fx-border-color:#D1D5DB; -fx-border-width:1.5;");
    }

    /** Show a blocking alert dialog — safe to call from Platform.runLater on background thread. */
    private void showAlertOnStage(Stage owner, String title, String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        if (owner != null && owner.isShowing()) a.initOwner(owner);
        a.showAndWait();
    }

    private void showAlert(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setTitle(title); a.showAndWait();
    }

    private void showInfo(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle(title); a.showAndWait();
    }

    // ══════════════════════════════════════════════════════════
    // Model
    // ══════════════════════════════════════════════════════════

    /** List-view row — only the columns shown in the P1 TableView. */
    static class AcqnRow {
        String assetNo, desc1, desc2, acqnDate;
        String locCode, deptCode, grpCode, subgrpCode;
        String cost, status;
    }
}
