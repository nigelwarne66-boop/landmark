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
package com.landmarksoftware.ui.bas;

import com.landmarksoftware.desktop.AppMode;
import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.model.bas.BasTransaction;
import com.landmarksoftware.service.bas.BasTransactionService;
import com.landmarksoftware.ui.components.CommandBar;
import com.landmarksoftware.ui.components.LmButton;
import com.landmarksoftware.ui.components.LmTableView;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CPBA07 — BAS Transaction (Tax Transaction) Maintenance.
 *
 * <p>Manual entry/correction screen for individual {@code cpbastx} rows — the
 * row-level detail behind a BAS statement. Most rows are written
 * automatically by other modules' posting (AR/AP/CM/Payroll); this screen
 * lets a user hand-key or correct a line (e.g. a GST-bearing transaction that
 * didn't flow through automatically).
 *
 * <p>{@link #dsl} is injected for exactly one purpose: forwarding to the
 * shared {@link GlAccountLookupDialog} (picker + validation) — this
 * controller never issues a query itself, honouring the "zero jdbc.* calls
 * in a controller" rule; all real SQL lives in {@link BasTransactionService}.
 *
 * <p>Add is COBOL-batch-friendly: after a successful Add the dialog clears
 * and stays open (Add / Done buttons) so a user can key many lines in one
 * sitting, mirroring the COBOL screen's loop.
 */
@Component
public class BasTransactionMaintenanceController {

    private final BasTransactionService service;
    private final AppSession            appSession;
    private final DSLContext            dsl;

    private final ObservableList<BasTransaction> rows = FXCollections.observableArrayList();
    private LmTableView<BasTransaction>          table;
    private Label                                lblStatus;
    private BasTransactionService.Filter         currentFilter = BasTransactionService.Filter.none();

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cpba07-thread");
        t.setDaemon(true);
        return t;
    });

    public BasTransactionMaintenanceController(BasTransactionService service,
                                                AppSession appSession,
                                                DSLContext dsl) {
        this.service    = service;
        this.appSession = appSession;
        this.dsl        = dsl;
    }

    // ── Entry point ───────────────────────────────────────────────────────

    public Scene buildScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildHeader());
        root.setCenter(buildContent(stage));
        root.setBottom(buildStatusBar());

        loadList();

        Scene scene = new Scene(root, 980, 600);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        scene.getStylesheets().add(
            getClass().getResource(AppMode.themeCssPath()).toExternalForm());
        return scene;
    }

    // ── Header ────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("BAS Transaction Maintenance");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("CPBA07 · " + appSession.getCompanyName());
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
        table = new LmTableView<>(rows);
        VBox.setVgrow(table, Priority.ALWAYS);
        table.setEmptyState("fth-file-text", "No BAS transactions found.",
            "+ Add transaction", () -> openDialog(null, stage));

        TableColumn<BasTransaction, String> colGroup =
            LmTableView.codeColumn("BAS Group", t -> t.basGroup, 90);
        LmTableView.markDrillable(colGroup);

        table.getColumns().addAll(List.of(
            colGroup,
            LmTableView.codeColumn("BAS Code",     t -> t.basCode, 80),
            LmTableView.textColumn("Company",      t -> t.companyNo > 0 ? String.valueOf(t.companyNo) : "—", 80),
            LmTableView.dateColumn("Trx Date",     t -> t.trxDate, 100),
            LmTableView.dateColumn("Posting Date", t -> t.postingDate, 100),
            LmTableView.numColumn("Gross Amount",  t -> money(t.taxGrossAmt), 110),
            LmTableView.numColumn("Tax Amount",    t -> money(t.taxAmt), 100),
            LmTableView.textColumn("Journal ID",   t -> t.journalId().isEmpty() ? "—" : t.journalId(), 110)
        ));

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null)
                openDialog(table.getSelectionModel().getSelectedItem(), stage);
        });

        Button btnAdd  = LmButton.primary("+ Add", () -> openDialog(null, stage));
        Button btnEdit = LmButton.secondary("✎ Edit", () -> {
            BasTransaction sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openDialog(sel, stage);
            else showInfo("Edit", "Select a transaction to edit.");
        });
        Button btnDel  = LmButton.danger("✕ Delete", () -> {
            BasTransaction sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDelete(sel);
            else showInfo("Delete", "Select a transaction to delete.");
        });
        Button btnFilter = LmButton.secondary("⚲ Filter", () -> openFilterDialog(stage));
        Button btnRef    = LmButton.ghost("↺ Refresh", this::loadList);

        HBox toolbar = CommandBar.builder()
            .primary(btnAdd)
            .secondary(btnEdit)
            .secondary(btnDel)
            .secondary(btnFilter)
            .ghost(btnRef)
            .build();

        return new VBox(0, toolbar, table);
    }

    // ── Data operations ───────────────────────────────────────────────────

    private void loadList() {
        status("Loading…", false);
        exec.submit(() -> {
            try {
                List<BasTransaction> data = service.find(currentFilter);
                Platform.runLater(() -> {
                    rows.setAll(data);
                    status(data.size() + " transaction(s)", false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    private void confirmDelete(BasTransaction t) {
        // Tier 1 (bas_no "already on a statement") doesn't apply to CPBA07 —
        // this table's bas_no concept isn't gated on by this screen.
        if (!BasTransactionService.canDelete(t)) {
            showInfo("Can't Delete", "Can't delete Landmark transactions with tax amount.");
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete this transaction?", ButtonType.YES, ButtonType.NO);
        a.setTitle("Confirm Delete");
        a.setHeaderText(null);
        a.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                exec.submit(() -> {
                    try {
                        service.delete(t.trxDate, t.dateAdded, t.timeAdded);
                        Platform.runLater(() -> {
                            loadList();
                            status("Deleted.", false);
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() -> status("Delete error: " + ex.getMessage(), true));
                    }
                });
            }
        });
    }

    // ── Filter dialog ────────────────────────────────────────────────────

    private void openFilterDialog(Window owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Filter BAS Transactions");
        dlg.setResizable(false);

        ComboBox<BasTransactionService.GroupOption> cbGroup = new ComboBox<>();
        cbGroup.getItems().add(new BasTransactionService.GroupOption("", "(All BAS groups)"));
        cbGroup.setPrefWidth(240);
        cbGroup.setValue(cbGroup.getItems().get(0));
        exec.submit(() -> {
            List<BasTransactionService.GroupOption> opts = service.basGroupOptions();
            Platform.runLater(() -> {
                BasTransactionService.GroupOption sel = cbGroup.getValue();
                cbGroup.getItems().addAll(opts);
                if (notBlank(currentFilter.basGroup())) {
                    opts.stream().filter(o -> o.code().equalsIgnoreCase(currentFilter.basGroup()))
                        .findFirst().ifPresent(cbGroup::setValue);
                } else {
                    cbGroup.setValue(sel);
                }
            });
        });

        ComboBox<String> cbCode = new ComboBox<>();
        cbCode.getItems().add("(All BAS codes)");
        cbCode.getItems().addAll(BasTransactionService.VALID_BAS_CODES);
        cbCode.setValue(notBlank(currentFilter.basCode()) ? currentFilter.basCode() : "(All BAS codes)");
        cbCode.setPrefWidth(160);

        DatePicker dpFrom = new DatePicker(currentFilter.trxDateFrom());
        DatePicker dpTo   = new DatePicker(currentFilter.trxDateTo());
        TextField fCompany = tf(currentFilter.companyNo() != null ? String.valueOf(currentFilter.companyNo()) : "", 8);
        TextField fGrossFrom = tf(currentFilter.grossFrom() != null ? currentFilter.grossFrom().toPlainString() : "", 12);
        TextField fGrossTo   = tf(currentFilter.grossTo()   != null ? currentFilter.grossTo().toPlainString()   : "", 12);

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));
        form.getChildren().add(headerLine("Filter — each field optional"));
        form.getChildren().addAll(
            twoColRow("BAS Group:",  cbGroup),
            twoColRow("BAS Code:",   cbCode),
            twoColRow("Trx Date from:", dpFrom),
            twoColRow("Trx Date to:",   dpTo),
            twoColRow("Company No:", fCompany),
            twoColRow("Gross Amount from:", fGrossFrom),
            twoColRow("Gross Amount to:",   fGrossTo));

        Button btnApply = btnPrimary("Apply");
        Button btnClear = btnSecondary("Clear");
        Button btnCancel = btnSecondary("Cancel");
        btnApply.setDefaultButton(true);
        btnCancel.setOnAction(e -> dlg.close());
        btnClear.setOnAction(e -> {
            currentFilter = BasTransactionService.Filter.none();
            dlg.close();
            loadList();
        });
        btnApply.setOnAction(e -> {
            Integer companyNo = null;
            String companyText = fCompany.getText().trim();
            if (!companyText.isEmpty()) {
                try { companyNo = Integer.parseInt(companyText); }
                catch (NumberFormatException ex) { markError(fCompany, "Company No must be a whole number."); return; }
            }
            BigDecimal grossFrom = parseDecOrNull(fGrossFrom, "Gross Amount from");
            if (fGrossFrom.getStyle().contains("DC2626")) return;
            BigDecimal grossTo = parseDecOrNull(fGrossTo, "Gross Amount to");
            if (fGrossTo.getStyle().contains("DC2626")) return;

            String group = cbGroup.getValue() == null ? "" : cbGroup.getValue().code();
            String code = "(All BAS codes)".equals(cbCode.getValue()) ? "" : cbCode.getValue();

            currentFilter = new BasTransactionService.Filter(
                notBlank(group) ? group : null,
                notBlank(code) ? code : null,
                dpFrom.getValue(), dpTo.getValue(),
                companyNo, grossFrom, grossTo);
            dlg.close();
            loadList();
        });

        HBox btnBar = new HBox(10, btnApply, btnClear, btnCancel);
        btnBar.setPadding(new Insets(10, 20, 16, 20));
        btnBar.setAlignment(Pos.CENTER_RIGHT);
        btnBar.setStyle(
            "-fx-background-color:#F2F1EC;" +
            "-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
            "-fx-border-width:0.5 0 0 0;");

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true); scroll.setBorder(null);
        VBox root = new VBox(0, scroll, btnBar);
        dlg.setScene(new Scene(root, 460, 460));
        dlg.showAndWait();
    }

    /** Parses a BigDecimal field; null if blank, null (with markError applied) on bad input. */
    private BigDecimal parseDecOrNull(TextField fld, String label) {
        clearError(fld);
        String s = fld.getText().trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); }
        catch (NumberFormatException ex) { markError(fld, label + " must be a number."); return null; }
    }

    // ── Add / Edit dialog ────────────────────────────────────────────────

    private void openDialog(BasTransaction existing, Window owner) {
        boolean isAdd = existing == null;
        openFormDialog(isAdd ? new BasTransaction() : copy(existing), existing, isAdd, owner);
    }

    private static BasTransaction copy(BasTransaction s) {
        BasTransaction t = new BasTransaction();
        t.trxDate = s.trxDate; t.dateAdded = s.dateAdded; t.timeAdded = s.timeAdded;
        t.postingDate = s.postingDate; t.taxGrossAmt = s.taxGrossAmt; t.taxAmt = s.taxAmt;
        t.ref1 = s.ref1; t.ref2 = s.ref2; t.basGroup = s.basGroup; t.basCode = s.basCode;
        t.companyNo = s.companyNo; t.taxClearingMain = s.taxClearingMain; t.taxClearingSub = s.taxClearingSub;
        t.subCoyNo = s.subCoyNo; t.source = s.source; t.batchNo = s.batchNo; t.trxGrossAmt = s.trxGrossAmt;
        t.custNo = s.custNo; t.custDocType = s.custDocType; t.custDocNo = s.custDocNo;
        t.supplierNo = s.supplierNo; t.supplierDocType = s.supplierDocType; t.supplierDocNo = s.supplierDocNo;
        t.cmtransBankCode = s.cmtransBankCode; t.cmtransDocType = s.cmtransDocType; t.cmtransDocNo = s.cmtransDocNo;
        return t;
    }

    private void openFormDialog(BasTransaction t, BasTransaction original, boolean isAdd, Window owner) {
        boolean systemSourced = !isAdd && original.isSystemSourced();

        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isAdd ? "Add" : "Edit") + " BAS Transaction");
        dlg.setResizable(false);

        DatePicker dpTrx = new DatePicker(t.trxDate);
        dpTrx.setPrefWidth(150);
        DatePicker dpPosting = new DatePicker(t.postingDate);
        dpPosting.setPrefWidth(150);
        TextField fGross = tf(decStr(t.taxGrossAmt), 14);
        TextField fTax   = tf(decStr(t.taxAmt), 14);
        TextField fRef1  = tf(t.ref1, 40);
        TextField fRef2  = tf(t.ref2, 40);
        TextField fBasGroup = tf(t.basGroup, 3);
        Label lblGroupName = new Label("");
        lblGroupName.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");

        ComboBox<String> cbBasCode = new ComboBox<>(FXCollections.observableArrayList(BasTransactionService.VALID_BAS_CODES));
        cbBasCode.setPrefWidth(100);
        if (!t.basCode.isEmpty()) cbBasCode.setValue(t.basCode);

        TextField fCompany = tf(t.companyNo > 0 ? String.valueOf(t.companyNo) : "", 8);
        TextField fClearMain = tf(t.taxClearingMain != null && t.taxClearingMain != 0 ? String.valueOf(t.taxClearingMain) : "", 8);
        TextField fClearSub  = tf(t.taxClearingSub  != null && t.taxClearingSub  != 0 ? String.valueOf(t.taxClearingSub)  : "", 8);
        Label lblGlDesc = new Label("");
        lblGlDesc.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        Button btnGlPick = new Button("🔍");
        btnGlPick.setStyle("-fx-background-color:white;-fx-text-fill:#374151;-fx-border-color:#D0CFC8;" +
                            "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:3 8;-fx-cursor:hand;-fx-font-size:11px;");

        int[] lastValidCompanyNo = { t.companyNo };
        boolean[] glFieldsEnabled = { t.companyNo > 0 };
        boolean[] postingTouched = { !isAdd };  // existing rows already have a real posting date

        Runnable applyGlEnablement = () -> {
            boolean enabled = glFieldsEnabled[0] && !systemSourced;
            fClearMain.setDisable(!enabled);
            fClearSub.setDisable(!enabled);
            btnGlPick.setDisable(!enabled);
            if (!glFieldsEnabled[0]) {
                fClearMain.clear(); fClearSub.clear(); lblGlDesc.setText("");
            }
        };
        applyGlEnablement.run();

        btnGlPick.setOnAction(e -> GlAccountLookupDialog.show(dlg, dsl, lastValidCompanyNo[0], row -> {
            fClearMain.setText(String.valueOf(row.acctMain()));
            fClearSub.setText(String.valueOf(row.acctSub()));
            lblGlDesc.setText(row.desc());
        }));

        Runnable validateGlOnBlur = () -> {
            if (!glFieldsEnabled[0]) return;
            String mainText = fClearMain.getText().trim(), subText = fClearSub.getText().trim();
            if (mainText.isEmpty() && subText.isEmpty()) { lblGlDesc.setText(""); return; }
            int main, sub;
            try { main = mainText.isEmpty() ? 0 : Integer.parseInt(mainText); sub = subText.isEmpty() ? 0 : Integer.parseInt(subText); }
            catch (NumberFormatException ex) { lblGlDesc.setText("Invalid account number."); return; }
            exec.submit(() -> {
                GlAccountLookupDialog.AccountRow row = GlAccountLookupDialog.lookup(dsl, lastValidCompanyNo[0], main, sub);
                Platform.runLater(() -> lblGlDesc.setText(row == null ? "Account not found." : row.desc()));
            });
        };
        fClearMain.focusedProperty().addListener((o, was, isNow) -> { if (was && !isNow) validateGlOnBlur.run(); });
        fClearSub.focusedProperty().addListener((o, was, isNow) -> { if (was && !isNow) validateGlOnBlur.run(); });

        // Posting Date defaults to Trx Date the first time it's touched, in Add mode only.
        if (isAdd) {
            dpPosting.getEditor().focusedProperty().addListener((o, was, isNow) -> {
                if (isNow && !postingTouched[0]) {
                    postingTouched[0] = true;
                    if (dpPosting.getValue() == null) dpPosting.setValue(dpTrx.getValue());
                }
            });
        }

        // Field-disable rule: system-sourced existing rows lock Trx Date + Company No.
        if (systemSourced) {
            dpTrx.setDisable(true);
            fCompany.setEditable(false);
            fCompany.setDisable(true);
        } else {
            fCompany.focusedProperty().addListener((o, was, isNow) -> {
                if (was && !isNow) {
                    onCompanyBlur(fCompany, fBasGroup, lblGroupName, lastValidCompanyNo, glFieldsEnabled, applyGlEnablement, dlg);
                }
            });
        }

        // Resolve + display the BAS group name whenever the group code changes.
        Runnable refreshGroupName = () -> {
            String g = fBasGroup.getText().trim();
            if (g.isEmpty()) { lblGroupName.setText(""); return; }
            exec.submit(() -> {
                String name = service.basGroupName(g);
                Platform.runLater(() -> lblGroupName.setText(name.isEmpty() ? "" : "— " + name));
            });
        };
        fBasGroup.focusedProperty().addListener((o, was, isNow) -> { if (was && !isNow) refreshGroupName.run(); });
        if (!t.basGroup.isEmpty()) refreshGroupName.run();

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));
        form.getChildren().add(headerLine((isAdd ? "Add" : "Edit") + " BAS Transaction"));
        form.getChildren().addAll(
            sectionHeader("Transaction"),
            twoColRow("Trx Date *:",     dpTrx),
            twoColRow("Posting Date *:", dpPosting),
            twoColRow("Gross amount:",   fGross),
            twoColRow("Tax amount:",     fTax),
            twoColRow("Reference 1:",    fRef1),
            twoColRow("Reference 2:",    fRef2),
            sectionHeader("BAS classification"),
            twoColRow("BAS Group *:", new HBox(6, fBasGroup, lblGroupName)),
            twoColRow("BAS Code *:",  cbBasCode),
            sectionHeader("Company / GL"),
            twoColRow("Company No (0 = none):", fCompany),
            twoColRow("Tax clearing main:", new HBox(6, fClearMain, btnGlPick)),
            twoColRow("Tax clearing sub:",  fClearSub),
            lblGlDesc);

        if (!isAdd) {
            form.getChildren().add(buildReadOnlyContext(original));
        }

        Label errLine = new Label();
        errLine.setStyle("-fx-text-fill:#C0392B;-fx-font-size:11px;");
        form.getChildren().add(errLine);

        Button btnSave = btnPrimary(isAdd ? "Add" : "Save");
        Button btnDone = btnSecondary(isAdd ? "Done" : "Cancel");
        btnSave.setDefaultButton(true);
        btnDone.setOnAction(e -> dlg.close());

        btnSave.setOnAction(e -> {
            errLine.setText("");
            LocalDate trxDate = dpTrx.getValue();
            LocalDate postDate = dpPosting.getValue();
            if (trxDate == null) { errLine.setText("Trx Date is required."); return; }
            if (postDate == null) { errLine.setText("Posting Date is required."); return; }
            BigDecimal gross = parseDec(fGross, "Gross amount"); if (gross == null) return;
            BigDecimal tax   = parseDec(fTax,   "Tax amount");   if (tax == null) return;
            String basGroup = fBasGroup.getText().trim().toUpperCase();
            if (basGroup.isEmpty()) { markError(fBasGroup, "BAS group is required."); return; }
            String basCode = cbBasCode.getValue();
            if (basCode == null || basCode.isBlank()) { errLine.setText("BAS Code is required."); return; }
            int companyNo;
            String companyText = fCompany.getText().trim();
            try { companyNo = companyText.isEmpty() ? 0 : Integer.parseInt(companyText); }
            catch (NumberFormatException ex) { markError(fCompany, "Company No must be a whole number."); return; }
            Integer clearMain = null, clearSub = null;
            if (companyNo > 0) {
                try {
                    clearMain = fClearMain.getText().trim().isEmpty() ? 0 : Integer.parseInt(fClearMain.getText().trim());
                    clearSub  = fClearSub.getText().trim().isEmpty()  ? 0 : Integer.parseInt(fClearSub.getText().trim());
                } catch (NumberFormatException ex) { errLine.setText("Tax clearing account must be numeric."); return; }
            }

            t.trxDate = trxDate;
            t.postingDate = postDate;
            t.taxGrossAmt = gross;
            t.taxAmt = tax;
            t.ref1 = fRef1.getText().trim();
            t.ref2 = fRef2.getText().trim();
            t.basGroup = basGroup;
            t.basCode = basCode;
            t.companyNo = companyNo;
            t.taxClearingMain = clearMain;
            t.taxClearingSub = clearSub;

            btnSave.setDisable(true);
            final Integer fMain = clearMain, fSub = clearSub;
            exec.submit(() -> {
                try {
                    if (!BasTransactionService.VALID_BAS_CODES.contains(t.basCode)) {
                        fail(errLine, btnSave, "Not a valid BAS code."); return;
                    }
                    if (!service.basGroupExists(t.basGroup)) {
                        fail(errLine, btnSave, "BAS group not on file."); return;
                    }
                    if (t.companyNo > 0) {
                        if (!service.companyExists(t.companyNo)) {
                            fail(errLine, btnSave, "Company not on file."); return;
                        }
                        GlAccountLookupDialog.AccountRow row =
                            GlAccountLookupDialog.lookup(dsl, t.companyNo, fMain == null ? 0 : fMain, fSub == null ? 0 : fSub);
                        if (row == null) {
                            fail(errLine, btnSave, "Tax clearing GL account is not valid for this company."); return;
                        }
                    }
                    if (isAdd) {
                        service.insert(t, appSession.getUserId());
                    } else {
                        service.update(original.trxDate, original.dateAdded, original.timeAdded, t, appSession.getUserId());
                    }
                    Platform.runLater(() -> {
                        btnSave.setDisable(false);
                        loadList();
                        if (isAdd) {
                            // Batch-friendly loop — clear and stay open for the next entry.
                            status("Added.", false);
                            BasTransaction fresh = new BasTransaction();
                            dpTrx.setValue(fresh.trxDate);
                            dpPosting.setValue(null);
                            postingTouched[0] = false;
                            fGross.setText("");
                            fTax.setText("");
                            fRef1.clear();
                            fRef2.clear();
                            // Leave BAS Group / BAS Code / Company / GL account as-is —
                            // batch entry typically repeats the same classification.
                            errLine.setText("Added — ready for the next line.");
                            errLine.setStyle("-fx-text-fill:#2E7D32;-fx-font-size:11px;");
                        } else {
                            dlg.close();
                            status("Updated.", false);
                        }
                    });
                } catch (Exception ex) {
                    fail(errLine, btnSave, "Save error: " + ex.getMessage());
                }
            });
        });

        HBox btnBar = new HBox(10, btnSave, btnDone);
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
        root.setMinSize(560, 620);
        dlg.setScene(new Scene(root, 560, 620));
        dlg.showAndWait();
    }

    private void fail(Label errLine, Button btnSave, String msg) {
        Platform.runLater(() -> {
            btnSave.setDisable(false);
            errLine.setText(msg);
        });
    }

    /**
     * The company-number blur handler — the cpbasco cross-check described in
     * the CPBA07 spec: reject unknown companies, reject companies with no BAS
     * group at all, and confirm before silently resetting a mismatched group.
     */
    private void onCompanyBlur(TextField fCompany, TextField fBasGroup, Label lblGroupName,
                                int[] lastValidCompanyNo, boolean[] glFieldsEnabled,
                                Runnable applyGlEnablement, Window owner) {
        String text = fCompany.getText().trim();
        if (text.isEmpty()) {
            lastValidCompanyNo[0] = 0;
            glFieldsEnabled[0] = false;
            applyGlEnablement.run();
            return;
        }
        int newCo;
        try { newCo = Integer.parseInt(text); }
        catch (NumberFormatException ex) {
            markError(fCompany, "Company No must be a whole number.");
            fCompany.setText(lastValidCompanyNo[0] > 0 ? String.valueOf(lastValidCompanyNo[0]) : "");
            return;
        }
        if (newCo == 0) {
            lastValidCompanyNo[0] = 0;
            glFieldsEnabled[0] = false;
            applyGlEnablement.run();
            return;
        }
        if (newCo == lastValidCompanyNo[0]) return;   // unchanged, already validated

        exec.submit(() -> {
            boolean exists = service.companyExists(newCo);
            if (!exists) {
                Platform.runLater(() -> {
                    markError(fCompany, "Company not on file.");
                    fCompany.setText(lastValidCompanyNo[0] > 0 ? String.valueOf(lastValidCompanyNo[0]) : "");
                });
                return;
            }
            BasTransactionService.CompanyGroupLink link = service.companyGroupLink(newCo);
            Platform.runLater(() -> {
                if (!link.found()) {
                    showInfo("Company / BAS Group", "Company does not belong to a BAS group.");
                    fCompany.setText(lastValidCompanyNo[0] > 0 ? String.valueOf(lastValidCompanyNo[0]) : "");
                    return;
                }
                String currentGroup = fBasGroup.getText().trim();
                if (!currentGroup.isEmpty() && !currentGroup.equalsIgnoreCase(link.basGroup())) {
                    Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                        "BAS Group different — reset group?", ButtonType.YES, ButtonType.NO);
                    a.setTitle("BAS Group Mismatch");
                    a.setHeaderText(null);
                    a.showAndWait().ifPresent(btn -> {
                        if (btn == ButtonType.YES) {
                            fBasGroup.setText(link.basGroup());
                            exec.submit(() -> {
                                String name = service.basGroupName(link.basGroup());
                                Platform.runLater(() -> lblGroupName.setText(name.isEmpty() ? "" : "— " + name));
                            });
                            acceptCompany(newCo, lastValidCompanyNo, glFieldsEnabled, applyGlEnablement, fCompany);
                        } else {
                            fCompany.setText(lastValidCompanyNo[0] > 0 ? String.valueOf(lastValidCompanyNo[0]) : "");
                        }
                    });
                } else {
                    if (currentGroup.isEmpty()) {
                        fBasGroup.setText(link.basGroup());
                        exec.submit(() -> {
                            String name = service.basGroupName(link.basGroup());
                            Platform.runLater(() -> lblGroupName.setText(name.isEmpty() ? "" : "— " + name));
                        });
                    }
                    acceptCompany(newCo, lastValidCompanyNo, glFieldsEnabled, applyGlEnablement, fCompany);
                }
            });
        });
    }

    private void acceptCompany(int newCo, int[] lastValidCompanyNo, boolean[] glFieldsEnabled,
                                Runnable applyGlEnablement, TextField fCompany) {
        lastValidCompanyNo[0] = newCo;
        glFieldsEnabled[0] = true;
        fCompany.setText(String.valueOf(newCo));
        applyGlEnablement.run();
    }

    /** The read-only "system lineage" block — shown only when editing an existing row. */
    private VBox buildReadOnlyContext(BasTransaction t) {
        VBox box = new VBox(4);
        box.setPadding(new Insets(8, 0, 0, 0));
        box.getChildren().add(sectionHeader("Origin (read-only)"));
        box.getChildren().add(infoLine("Sub company:", t.subCoyNo == 0 ? "—" : String.valueOf(t.subCoyNo)));
        box.getChildren().add(infoLine("Journal ID:", t.journalId().isEmpty() ? "—" : t.journalId()));
        if (t.hasCustomer()) {
            box.getChildren().add(infoLine("Customer:", t.custNo));
            box.getChildren().add(infoLine("Doc type:", t.custDocType));
            box.getChildren().add(infoLine("Doc No:",   t.custDocNo));
        } else if (t.hasSupplier()) {
            box.getChildren().add(infoLine("Supplier:", t.supplierNo));
            box.getChildren().add(infoLine("Doc type:", t.supplierDocType));
            box.getChildren().add(infoLine("Doc No:",   t.supplierDocNo));
        }
        if (!t.cmtransBankCode.isEmpty() || !t.cmtransDocType.isEmpty() || t.cmtransDocNo != 0) {
            box.getChildren().add(infoLine("Cashbook:",
                t.cmtransBankCode + " " + t.cmtransDocType + " " + (t.cmtransDocNo == 0 ? "" : t.cmtransDocNo)));
        }
        box.getChildren().add(infoLine("Trx gross amount:", money(t.trxGrossAmt)));
        return box;
    }

    private HBox infoLine(String label, String value) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        l.setMinWidth(120);
        Label v = new Label(value == null || value.isBlank() ? "—" : value);
        v.setStyle("-fx-font-size:11px;-fx-text-fill:#374151;");
        HBox row = new HBox(10, l, v);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
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

    private HBox twoColRow(String label, Node control) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(150);
        HBox row = new HBox(10, l, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private TextField tf(String value, int maxLen) {
        TextField f = new TextField(value == null ? "" : value.trim());
        f.setPrefWidth(Math.min(maxLen * 9 + 10, 320));
        return f;
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

    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }

    private static String decStr(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return v.stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
