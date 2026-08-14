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
import com.landmarksoftware.model.bas.BasGroup;
import com.landmarksoftware.model.bas.BasGroupMember;
import com.landmarksoftware.service.bas.BasGroupService;
import com.landmarksoftware.ui.components.CommandBar;
import com.landmarksoftware.ui.components.LmButton;
import com.landmarksoftware.ui.components.LmTableView;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CPBA01 — BAS Report Group Maintenance.
 *
 * <p>Maintains the grouping structure that determines which
 * companies/subsidiaries are consolidated together for a single BAS
 * lodgement:
 * <ul>
 *   <li><b>P1</b> — list of ALL {@code cpbasgr} groups (not restricted to the
 *       session company — a BAS group's purpose is to consolidate potentially
 *       several companies, so the list is company-agnostic). Add / Edit /
 *       Delete / Members / Refresh.</li>
 *   <li><b>S1</b> — Add/Edit a group. Also auto-manages the group's own
 *       "owner" {@code cpbasco} row and the owner's {@code cpsubcy}
 *       tax-paid/variance GL accounts.</li>
 *   <li><b>P2</b> — member-company list for a group ({@code cpbasco}),
 *       opened automatically right after a successful S1 Add/Edit, or any
 *       time via the "Members" button.</li>
 *   <li><b>S2</b> — Add/Edit a member company's 12 BAS-relevant GL account
 *       pairs (on {@code cpsubcy}) — NOT tax-paid/variance, those are
 *       S1-only (see {@code cpba01s2.sd}).</li>
 * </ul>
 *
 * <p>All business logic (uniqueness of company/sub-coy ownership across
 * groups, GST consolidation exclusivity, GL account validation, cascading
 * writes across cpbasgr/cpbasco/cpsubcy) lives in {@link BasGroupService} —
 * this controller is pure JavaFX (CLAUDE.md: zero JDBC in controllers).
 */
@Component
public class BasGroupMaintenanceController {

    private static final DateTimeFormatter D_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String DESC_OK_STYLE  = "-fx-font-size:11px;-fx-text-fill:#6B7280;";
    private static final String DESC_ERR_STYLE = "-fx-font-size:11px;-fx-text-fill:#DC2626;";
    private static final String PICKER_STYLE =
        "-fx-background-color:white;-fx-text-fill:#374151;-fx-border-color:#D0CFC8;" +
        "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:3 8;-fx-cursor:hand;-fx-font-size:11px;";

    private final BasGroupService basGroupService;
    private final AppSession      appSession;
    private final DSLContext      dsl;

    private final ObservableList<BasGroup> rows = FXCollections.observableArrayList();
    private LmTableView<BasGroup> table;
    private Label lblStatus;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cpba01-thread");
        t.setDaemon(true);
        return t;
    });

    public BasGroupMaintenanceController(BasGroupService basGroupService, AppSession appSession, DSLContext dsl) {
        this.basGroupService = basGroupService;
        this.appSession       = appSession;
        this.dsl              = dsl;
    }

    // ── Entry point ───────────────────────────────────────────────────────

    public Scene buildScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildHeader());
        root.setCenter(buildContent(stage));
        root.setBottom(buildStatusBar());

        loadList();

        Scene scene = new Scene(root, 960, 600);
        scene.getStylesheets().add(getClass().getResource("/css/fixedassets.css").toExternalForm());
        scene.getStylesheets().add(getClass().getResource(AppMode.themeCssPath()).toExternalForm());
        return scene;
    }

    private HBox buildHeader() {
        Label title = new Label("BAS Report Group Maintenance");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("CPBA01 · " + appSession.getCompanyName());
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

    // ── P1 — list + toolbar ──────────────────────────────────────────────

    private VBox buildContent(Stage stage) {
        table = new LmTableView<>(rows);
        VBox.setVgrow(table, Priority.ALWAYS);
        table.setEmptyState("fth-file-text", "No BAS report groups found.",
            "+ Add BAS group", () -> openAddGroupDialog(stage));

        TableColumn<BasGroup, String> colCode = LmTableView.codeColumn("BAS Group", g -> g.basGroup, 90);
        LmTableView.markDrillable(colCode);

        table.getColumns().addAll(List.of(
            colCode,
            LmTableView.textColumn("Company", g -> String.valueOf(g.companyNo), 90),
            LmTableView.textColumn("Sub Coy", g -> g.subCoyNo == 0 ? "-" : String.valueOf(g.subCoyNo), 80),
            LmTableView.textColumn("Name", g -> g.basGroupName, 220),
            LmTableView.textColumn("Frequency", g -> freqLabel(g.basFreq), 100),
            LmTableView.dateColumn("Last Start", g -> g.lastStartDate, 110),
            LmTableView.dateColumn("Last End", g -> g.lastEndDate, 110)
        ));

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null)
                openEditGroupDialog(table.getSelectionModel().getSelectedItem(), stage);
        });

        Button btnAdd     = LmButton.primary("+ Add", () -> openAddGroupDialog(stage));
        Button btnEdit    = LmButton.secondary("✎ Edit", () -> {
            BasGroup sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openEditGroupDialog(sel, stage);
            else showInfo("Edit", "Select a BAS group to edit.");
        });
        Button btnMembers = LmButton.secondary("👥 Members", () -> {
            BasGroup sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openMembersDialog(sel, stage);
            else showInfo("Members", "Select a BAS group to view its members.");
        });
        Button btnDel     = LmButton.danger("✕ Delete", () -> {
            BasGroup sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDelete(sel);
            else showInfo("Delete", "Select a BAS group to delete.");
        });
        Button btnRef     = LmButton.ghost("↺ Refresh", this::loadList);

        HBox toolbar = CommandBar.builder()
            .primary(btnAdd)
            .secondary(btnEdit)
            .secondary(btnMembers)
            .secondary(btnDel)
            .ghost(btnRef)
            .build();

        return new VBox(0, toolbar, table);
    }

    // ── P1 data operations ───────────────────────────────────────────────

    private void loadList() {
        status("Loading…", false);
        exec.submit(() -> {
            try {
                List<BasGroup> data = basGroupService.findAll();
                Platform.runLater(() -> {
                    rows.setAll(data);
                    status(data.size() + " BAS group(s)", false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    private void confirmDelete(BasGroup g) {
        exec.submit(() -> {
            boolean hasStmts = basGroupService.hasStatements(g.basGroup);
            Platform.runLater(() -> {
                if (hasStmts) {
                    showInfo("Delete blocked", "BAS Group has statements created — deletion not allowed.");
                    return;
                }
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete this BAS Group code?\n\n" + g.basGroup + " — " + g.basGroupName,
                    ButtonType.YES, ButtonType.NO);
                a.setTitle("Confirm Delete"); a.setHeaderText(null);
                a.showAndWait().ifPresent(bt -> {
                    if (bt != ButtonType.YES) return;
                    exec.submit(() -> {
                        try {
                            basGroupService.delete(g);
                            Platform.runLater(() -> {
                                status("Deleted: " + g.basGroup, false);
                                loadList();
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> status("Delete error: " + ex.getMessage(), true));
                        }
                    });
                });
            });
        });
    }

    // ── S1 — Add / Edit BAS group ────────────────────────────────────────

    private void openAddGroupDialog(Window owner) {
        BasGroup g = new BasGroup();
        g.companyNo = appSession.getCompanyNo();
        g.trxPostingDateInd = "P";
        showGroupDialog(g, true, 0, 0, owner);
    }

    private void openEditGroupDialog(BasGroup row, Window owner) {
        status("Loading…", false);
        exec.submit(() -> {
            Optional<BasGroup> full = basGroupService.findForEdit(row.basGroup);
            Platform.runLater(() -> {
                if (full.isEmpty()) { status("BAS group not found.", true); return; }
                BasGroup g = full.get();
                status("Ready", false);
                showGroupDialog(g, false, g.companyNo, g.subCoyNo, owner);
            });
        });
    }

    private void showGroupDialog(BasGroup g, boolean isAdd, int origCompanyNo, int origSubCoyNo, Window owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isAdd ? "Add" : "Edit") + " BAS Report Group — CPBA01 S1");
        dlg.setResizable(false);

        TextField fBasGroup = tf(g.basGroup, 3);
        fBasGroup.setEditable(isAdd); fBasGroup.setDisable(!isAdd);
        TextField fCompanyNo = tf(intStr(g.companyNo), 6);
        TextField fSubCoyNo  = tf(intStr(g.subCoyNo), 6);
        TextField fName      = tf(g.basGroupName, 35);

        ChoiceBox<String> cbFreq      = choiceBox(freqOptions(), g.basFreq);
        ChoiceBox<String> cbGstInd    = choiceBox(gstIndOptions(), g.gstGroupConsolInd);
        ChoiceBox<String> cbGstFreq   = choiceBox(freqOptions(), g.gstFreq);
        ChoiceBox<String> cbGstMethod = choiceBox(gstMethodOptions(), g.gstMethod);
        ChoiceBox<String> cbTrxInd    = choiceBox(trxIndOptions(), isAdd ? "P" : g.trxPostingDateInd);

        Runnable applyGstIndState = () -> cbGstFreq.setDisable(!"G".equals(codeOf(cbGstInd)));
        cbGstInd.valueProperty().addListener((o, ov, nv) -> applyGstIndState.run());
        applyGstIndState.run();

        TextField fTaxMain = tf(intStr(g.taxPaidAcctMain), 8);
        TextField fTaxSub  = tf(intStr(g.taxPaidAcctSub), 6);
        Label lblTaxDesc = descLabel();
        TextField fVarMain = tf(intStr(g.varianceAcctMain), 8);
        TextField fVarSub  = tf(intStr(g.varianceAcctSub), 6);
        Label lblVarDesc = descLabel();

        java.util.function.IntSupplier coNoSupplier = () -> parseIntSafe(fCompanyNo.getText());
        Node taxRow = glAccountRow(fTaxMain, fTaxSub, lblTaxDesc, coNoSupplier, dlg);
        Node varRow = glAccountRow(fVarMain, fVarSub, lblVarDesc, coNoSupplier, dlg);

        if (!isAdd) {
            prefillGlDesc(fTaxMain, fTaxSub, lblTaxDesc, g.companyNo);
            prefillGlDesc(fVarMain, fVarSub, lblVarDesc, g.companyNo);
        }

        // On Add, default the name from the company/sub-company once one is entered (only while name is blank).
        Runnable defaultName = () -> {
            if (!isAdd || !fName.getText().trim().isEmpty()) return;
            int coNo  = parseIntSafe(fCompanyNo.getText());
            int subNo = parseIntSafe(fSubCoyNo.getText());
            if (coNo <= 0) return;
            exec.submit(() -> {
                String nm = subNo > 0 ? basGroupService.subCoyName(coNo, subNo) : "";
                if (nm.isEmpty()) nm = basGroupService.companyName(coNo);
                final String finalNm = nm;
                Platform.runLater(() -> {
                    if (fName.getText().trim().isEmpty() && !finalNm.isEmpty()) fName.setText(finalNm);
                });
            });
        };
        fCompanyNo.focusedProperty().addListener((o, was, is) -> { if (!is) defaultName.run(); });
        fSubCoyNo.focusedProperty().addListener((o, was, is) -> { if (!is) defaultName.run(); });

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));
        form.getChildren().add(headerLine(isAdd ? "New BAS Report Group" : "Edit BAS Report Group " + g.basGroup));
        form.getChildren().addAll(
            twoColRow("BAS Group Code *:", fBasGroup),
            twoColRow("Company No *:", fCompanyNo),
            twoColRow("Sub Coy No:", fSubCoyNo),
            twoColRow("BAS Group Name *:", fName),
            twoColRow("Report Frequency:", cbFreq));
        form.getChildren().add(sectionHeader("GST"));
        form.getChildren().addAll(
            twoColRow("GST Group/Consol Ind:", cbGstInd),
            hint("G = this BAS group reports its own GST, C = consolidated into another group"),
            twoColRow("GST Frequency:", cbGstFreq),
            twoColRow("GST Method:", cbGstMethod),
            hint("G = gross entered, T = tax × 11"));
        form.getChildren().add(sectionHeader("Posting"));
        form.getChildren().addAll(
            twoColRow("Trx/Posting Date Ind:", cbTrxInd),
            hint("P = posting date, T = transaction date"));
        form.getChildren().add(sectionHeader("GL Accounts"));
        form.getChildren().addAll(
            twoColRow("Tax Paid Account:", taxRow),
            twoColRow("Variance Account:", varRow));
        form.getChildren().add(sectionHeader("System (read-only)"));
        form.getChildren().addAll(
            twoColRow("Last BAS No:", new Label(intStr(g.lastBasNo))),
            twoColRow("Last Start Date:", new Label(dateStr(g.lastStartDate))),
            twoColRow("Last End Date:", new Label(dateStr(g.lastEndDate))),
            twoColRow("Current BAS No:", new Label(intStr(g.currentBasNo))));

        Button btnSave   = btnPrimary(isAdd ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(e -> dlg.close());

        btnSave.setOnAction(e -> {
            String code = fBasGroup.getText().trim().toUpperCase();
            if (isAdd) {
                if (code.isEmpty()) { markError(fBasGroup, "BAS Group code is required."); return; }
                if (code.length() > 3) { markError(fBasGroup, "BAS Group code must be 3 characters or fewer."); return; }
            }
            Integer coNo = parseIntField(fCompanyNo, "Company No");
            if (coNo == null) return;
            if (coNo <= 0) { markError(fCompanyNo, "Company No is required."); return; }
            Integer subNo = parseIntField(fSubCoyNo, "Sub Coy No");
            if (subNo == null) return;
            String name = fName.getText().trim();
            if (name.isEmpty()) { markError(fName, "BAS Group Name is required."); return; }
            Integer taxMain = parseIntField(fTaxMain, "Tax Paid Account (main)"); if (taxMain == null) return;
            Integer taxSub  = parseIntField(fTaxSub,  "Tax Paid Account (sub)");  if (taxSub == null) return;
            Integer varMain = parseIntField(fVarMain, "Variance Account (main)"); if (varMain == null) return;
            Integer varSub  = parseIntField(fVarSub,  "Variance Account (sub)");  if (varSub == null) return;

            g.basGroup         = isAdd ? code : g.basGroup;
            g.companyNo         = coNo;
            g.subCoyNo           = subNo;
            g.basGroupName      = name;
            g.basFreq            = codeOf(cbFreq);
            g.gstGroupConsolInd = codeOf(cbGstInd);
            g.gstFreq           = codeOf(cbGstFreq);
            g.gstMethod         = codeOf(cbGstMethod);
            g.trxPostingDateInd = codeOf(cbTrxInd);
            g.taxPaidAcctMain   = taxMain;
            g.taxPaidAcctSub    = taxSub;
            g.varianceAcctMain  = varMain;
            g.varianceAcctSub   = varSub;

            String userId = appSession.getUserId();
            btnSave.setDisable(true);
            exec.submit(() -> {
                try {
                    if (isAdd && basGroupService.groupExists(g.basGroup)) {
                        Platform.runLater(() -> { btnSave.setDisable(false);
                            markError(fBasGroup, "BAS Group '" + g.basGroup + "' already exists."); });
                        return;
                    }
                    if (!basGroupService.companyExists(g.companyNo)) {
                        Platform.runLater(() -> { btnSave.setDisable(false);
                            markError(fCompanyNo, "Company not on file."); });
                        return;
                    }
                    if (g.subCoyNo > 0 && !basGroupService.subCoyExists(g.companyNo, g.subCoyNo)) {
                        Platform.runLater(() -> { btnSave.setDisable(false);
                            markError(fSubCoyNo, "Subsidiary company not on file."); });
                        return;
                    }
                    Optional<String> conflict = basGroupService.findConflictingOwner(
                        g.companyNo, g.subCoyNo, isAdd ? null : g.basGroup);
                    if (conflict.isPresent()) {
                        Platform.runLater(() -> { btnSave.setDisable(false);
                            markError(fCompanyNo, "Already attached to BAS group " + conflict.get()); });
                        return;
                    }
                    if ("C".equals(g.gstGroupConsolInd) && basGroupService.existsConsolInto(g.basGroup)) {
                        Platform.runLater(() -> { btnSave.setDisable(false);
                            showInfo("GST Consolidation",
                                "Already being consolidated into for GST — delete from consolidation first."); });
                        return;
                    }
                    if ("G".equals(g.gstGroupConsolInd) && basGroupService.existsConsolFrom(g.basGroup)) {
                        Platform.runLater(() -> { btnSave.setDisable(false);
                            showInfo("GST Consolidation",
                                "Already included in a consolidation — delete from consolidation first."); });
                        return;
                    }
                    if (!(taxMain == 0 && taxSub == 0)) {
                        GlAccountLookupDialog.AccountRow taxAcct =
                            GlAccountLookupDialog.lookup(dsl, g.companyNo, g.taxPaidAcctMain, g.taxPaidAcctSub);
                        if (taxAcct == null) {
                            Platform.runLater(() -> { btnSave.setDisable(false);
                                markError(fTaxMain, "Tax Paid GL account is not valid (not on file, not a "
                                    + "financial account, or not a posting account)."); });
                            return;
                        }
                    }
                    if (!(varMain == 0 && varSub == 0)) {
                        GlAccountLookupDialog.AccountRow varAcct =
                            GlAccountLookupDialog.lookup(dsl, g.companyNo, g.varianceAcctMain, g.varianceAcctSub);
                        if (varAcct == null) {
                            Platform.runLater(() -> { btnSave.setDisable(false);
                                markError(fVarMain, "Variance GL account is not valid (not on file, not a "
                                    + "financial account, or not a posting account)."); });
                            return;
                        }
                    }

                    if (isAdd) basGroupService.insert(g, userId);
                    else basGroupService.update(g, origCompanyNo, origSubCoyNo, userId);

                    Platform.runLater(() -> {
                        dlg.close();
                        status((isAdd ? "Added: " : "Updated: ") + g.basGroup, false);
                        loadList();
                        openMembersDialog(g, owner);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> { btnSave.setDisable(false); status("Save error: " + ex.getMessage(), true); });
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
        scroll.setFitToWidth(true); scroll.setBorder(null);
        VBox root = new VBox(0, scroll, btnBar);
        dlg.setScene(new Scene(root, 560, 660));
        dlg.showAndWait();
    }

    // ── P2 — member companies list ───────────────────────────────────────

    private void openMembersDialog(BasGroup group, Window owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("BAS Group Members — " + group.basGroup + " (CPBA01 P2)");
        dlg.setResizable(true);

        ObservableList<BasGroupMember> memberRows = FXCollections.observableArrayList();
        LmTableView<BasGroupMember> memberTable = new LmTableView<>(memberRows);
        VBox.setVgrow(memberTable, Priority.ALWAYS);
        Runnable[] reloadHolder = new Runnable[1];
        memberTable.setEmptyState("fth-users", "No member companies yet.", "+ Add company",
            () -> openMemberDialog(group, null, dlg, () -> reloadHolder[0].run()));

        memberTable.getColumns().addAll(List.of(
            LmTableView.textColumn("Company No", m -> String.valueOf(m.companyNo), 100),
            LmTableView.textColumn("Sub Coy", m -> m.subCoyNo == 0 ? "-" : String.valueOf(m.subCoyNo), 80),
            LmTableView.textColumn("Company Name", m -> m.companyName, 280)
        ));

        Runnable reload = () -> exec.submit(() -> {
            try {
                List<BasGroupMember> data = basGroupService.findMembers(group.basGroup);
                Platform.runLater(() -> memberRows.setAll(data));
            } catch (Exception ex) {
                Platform.runLater(() -> status("Member load error: " + ex.getMessage(), true));
            }
        });
        reloadHolder[0] = reload;
        reload.run();

        memberTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                BasGroupMember sel = memberTable.getSelectionModel().getSelectedItem();
                if (sel != null) openMemberDialog(group, sel, dlg, reload);
            }
        });

        Button btnAdd  = LmButton.primary("+ Add", () -> openMemberDialog(group, null, dlg, reload));
        Button btnEdit = LmButton.secondary("✎ Edit", () -> {
            BasGroupMember sel = memberTable.getSelectionModel().getSelectedItem();
            if (sel != null) openMemberDialog(group, sel, dlg, reload);
            else showInfo("Edit", "Select a company to edit.");
        });
        Button btnDel  = LmButton.danger("✕ Delete", () -> {
            BasGroupMember sel = memberTable.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDeleteMember(group, sel, reload);
            else showInfo("Delete", "Select a company to delete.");
        });
        Button btnRef  = LmButton.ghost("↺ Refresh", reload);
        Button btnClose = LmButton.secondary("Close", dlg::close);

        HBox toolbar = CommandBar.builder()
            .primary(btnAdd).secondary(btnEdit).secondary(btnDel).ghost(btnRef).build();

        Label title = new Label("Member Companies — " + group.basGroup + " (" + group.basGroupName + ")");
        title.setStyle("-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        VBox top = new VBox(4, title);
        top.setPadding(new Insets(16, 20, 8, 20));

        HBox footer = new HBox(btnClose);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(10, 20, 14, 20));
        footer.setStyle(
            "-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
            "-fx-border-width:0.5 0 0 0;");

        VBox root = new VBox(0, top, toolbar, memberTable, footer);
        dlg.setScene(new Scene(root, 660, 500));
        dlg.showAndWait();
    }

    private void confirmDeleteMember(BasGroup group, BasGroupMember m, Runnable reload) {
        if (m.isOwnerOf(group)) {
            showInfo("Delete blocked", "Can't delete the BAS group's owner company.");
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete company " + m.companyNo + (m.subCoyNo > 0 ? " / sub " + m.subCoyNo : "")
                + " — " + m.companyName + "?",
            ButtonType.YES, ButtonType.NO);
        a.setTitle("Delete Company"); a.setHeaderText(null);
        a.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.YES) return;
            exec.submit(() -> {
                try {
                    basGroupService.deleteMember(group.basGroup, m.companyNo, m.subCoyNo);
                    Platform.runLater(reload);
                } catch (Exception ex) {
                    Platform.runLater(() -> status("Delete error: " + ex.getMessage(), true));
                }
            });
        });
    }

    // ── S2 — Add / Edit member company ───────────────────────────────────

    private void openMemberDialog(BasGroup group, BasGroupMember existingRow, Window owner, Runnable reload) {
        boolean isAdd = (existingRow == null);
        if (isAdd) {
            BasGroupMember m = new BasGroupMember();
            m.basGroup = group.basGroup;
            showMemberDialog(group, m, true, owner, reload);
        } else {
            exec.submit(() -> {
                Optional<BasGroupMember> full = basGroupService.findMemberForEdit(
                    group.basGroup, existingRow.companyNo, existingRow.subCoyNo);
                Platform.runLater(() -> {
                    if (full.isEmpty()) { status("Member not found.", true); return; }
                    showMemberDialog(group, full.get(), false, owner, reload);
                });
            });
        }
    }

    private record GlFieldSpec(int main, int sub, TextField mainField, String label) { }

    private void showMemberDialog(BasGroup group, BasGroupMember m, boolean isAdd, Window owner, Runnable reload) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isAdd ? "Add" : "Edit") + " Member Company — CPBA01 S2");
        dlg.setResizable(false);

        TextField fCompanyNo = tf(intStr(m.companyNo), 6);
        fCompanyNo.setEditable(isAdd); fCompanyNo.setDisable(!isAdd);
        TextField fSubCoyNo = tf(intStr(m.subCoyNo), 6);
        fSubCoyNo.setEditable(isAdd); fSubCoyNo.setDisable(!isAdd);

        // Field order below matches the real COBOL screen (cpba01s2.sd) exactly:
        // 1C-1D, 1E-1F, 1G, 4, 5A-5B, 6A-6B, 7, 7A, 7C-7D, Inter-Co, then the
        // "Subsidiary company accounting:" section (HO Loan, Sub Loan).
        // Tax Paid / Variance are NOT on this screen — those are S1-only fields.
        TextField f1cdMain = tf(intStr(m.bas1c1dAcctMain), 8), f1cdSub = tf(intStr(m.bas1c1dAcctSub), 6);
        Label lbl1cdDesc = descLabel();
        TextField f1efMain = tf(intStr(m.bas1e1fAcctMain), 8), f1efSub = tf(intStr(m.bas1e1fAcctSub), 6);
        Label lbl1efDesc = descLabel();
        TextField f1gMain = tf(intStr(m.bas1gAcctMain), 8), f1gSub = tf(intStr(m.bas1gAcctSub), 6);
        Label lbl1gDesc = descLabel();
        TextField f4Main = tf(intStr(m.bas4AcctMain), 8), f4Sub = tf(intStr(m.bas4AcctSub), 6);
        Label lbl4Desc = descLabel();
        TextField f5abMain = tf(intStr(m.bas5a5bAcctMain), 8), f5abSub = tf(intStr(m.bas5a5bAcctSub), 6);
        Label lbl5abDesc = descLabel();
        TextField f6abMain = tf(intStr(m.bas6a6bAcctMain), 8), f6abSub = tf(intStr(m.bas6a6bAcctSub), 6);
        Label lbl6abDesc = descLabel();
        TextField f7Main = tf(intStr(m.bas7AcctMain), 8), f7Sub = tf(intStr(m.bas7AcctSub), 6);
        Label lbl7Desc = descLabel();
        TextField f7aMain = tf(intStr(m.bas7aAcctMain), 8), f7aSub = tf(intStr(m.bas7aAcctSub), 6);
        Label lbl7aDesc = descLabel();
        TextField f7cdMain = tf(intStr(m.bas7c7dAcctMain), 8), f7cdSub = tf(intStr(m.bas7c7dAcctSub), 6);
        Label lbl7cdDesc = descLabel();
        TextField fInterMain = tf(intStr(m.interCoyAcctMain), 8), fInterSub = tf(intStr(m.interCoyAcctSub), 6);
        Label lblInterDesc = descLabel();
        TextField fHoMain = tf(intStr(m.basHoLoanAcctMain), 8), fHoSub = tf(intStr(m.basHoLoanAcctSub), 6);
        Label lblHoDesc = descLabel();
        TextField fSlMain = tf(intStr(m.basSubLoanAcctMain), 8), fSlSub = tf(intStr(m.basSubLoanAcctSub), 6);
        Label lblSlDesc = descLabel();

        java.util.function.IntSupplier coNoSupplier = () -> parseIntSafe(fCompanyNo.getText());

        Node r1cd     = glAccountRow(f1cdMain, f1cdSub, lbl1cdDesc, coNoSupplier, dlg);
        Node r1ef     = glAccountRow(f1efMain, f1efSub, lbl1efDesc, coNoSupplier, dlg);
        Node r1g      = glAccountRow(f1gMain, f1gSub, lbl1gDesc, coNoSupplier, dlg);
        Node r4       = glAccountRow(f4Main, f4Sub, lbl4Desc, coNoSupplier, dlg);
        Node r5ab     = glAccountRow(f5abMain, f5abSub, lbl5abDesc, coNoSupplier, dlg);
        Node r6ab     = glAccountRow(f6abMain, f6abSub, lbl6abDesc, coNoSupplier, dlg);
        Node r7       = glAccountRow(f7Main, f7Sub, lbl7Desc, coNoSupplier, dlg);
        Node r7a      = glAccountRow(f7aMain, f7aSub, lbl7aDesc, coNoSupplier, dlg);
        Node r7cd     = glAccountRow(f7cdMain, f7cdSub, lbl7cdDesc, coNoSupplier, dlg);
        Node interRow = glAccountRow(fInterMain, fInterSub, lblInterDesc, coNoSupplier, dlg);
        Node hoRow    = glAccountRow(fHoMain, fHoSub, lblHoDesc, coNoSupplier, dlg);
        Node slRow    = glAccountRow(fSlMain, fSlSub, lblSlDesc, coNoSupplier, dlg);

        if (!isAdd) {
            int coNo = m.companyNo;
            prefillGlDesc(f1cdMain, f1cdSub, lbl1cdDesc, coNo);
            prefillGlDesc(f1efMain, f1efSub, lbl1efDesc, coNo);
            prefillGlDesc(f1gMain, f1gSub, lbl1gDesc, coNo);
            prefillGlDesc(f4Main, f4Sub, lbl4Desc, coNo);
            prefillGlDesc(f5abMain, f5abSub, lbl5abDesc, coNo);
            prefillGlDesc(f6abMain, f6abSub, lbl6abDesc, coNo);
            prefillGlDesc(f7Main, f7Sub, lbl7Desc, coNo);
            prefillGlDesc(f7aMain, f7aSub, lbl7aDesc, coNo);
            prefillGlDesc(f7cdMain, f7cdSub, lbl7cdDesc, coNo);
            prefillGlDesc(fInterMain, fInterSub, lblInterDesc, coNo);
            prefillGlDesc(fHoMain, fHoSub, lblHoDesc, coNo);
            prefillGlDesc(fSlMain, fSlSub, lblSlDesc, coNo);
        }

        // HO Loan / Sub Loan are only relevant when this member is itself a sub-company.
        Runnable applySubCoyState = () -> {
            boolean hasSub = parseIntSafe(fSubCoyNo.getText()) > 0;
            fHoMain.setDisable(!hasSub); fHoSub.setDisable(!hasSub);
            fSlMain.setDisable(!hasSub); fSlSub.setDisable(!hasSub);
        };
        fSubCoyNo.textProperty().addListener((o, ov, nv) -> applySubCoyState.run());
        applySubCoyState.run();

        VBox form = new VBox(10);
        form.setPadding(new Insets(20));
        form.getChildren().add(headerLine((isAdd ? "Add" : "Edit") + " Member Company — group " + group.basGroup));
        form.getChildren().addAll(
            twoColRow("Company No *:", fCompanyNo),
            twoColRow("Sub Coy No:", fSubCoyNo));
        form.getChildren().add(sectionHeader("General Ledger accounts"));
        form.getChildren().addAll(
            twoColRow("1C 1D Wine Equal Tax:", r1cd),
            twoColRow("1E 1F Luxury Car Tax:", r1ef),
            twoColRow("1G Wholesale Tax Cr:", r1g),
            twoColRow("4 Withholding Taxes:", r4),
            twoColRow("5A 5B Income Tax:", r5ab),
            twoColRow("6A 6B FBT:", r6ab),
            twoColRow("7 Deferred Amt:", r7),
            twoColRow("7A Deferred Imports:", r7a),
            twoColRow("7C 7D Fuel Tax:", r7cd),
            twoColRow("Intercompany acct:", interRow));
        form.getChildren().add(sectionHeader("Subsidiary company accounting"));
        form.getChildren().addAll(
            twoColRow("Head office loans:", hoRow),
            twoColRow("Subsidiary loans:", slRow));

        Button btnSave   = btnPrimary(isAdd ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(e -> dlg.close());

        btnSave.setOnAction(e -> {
            Integer coNo = parseIntField(fCompanyNo, "Company No");
            if (coNo == null) return;
            if (coNo <= 0) { markError(fCompanyNo, "Company No is required."); return; }
            Integer subNo = parseIntField(fSubCoyNo, "Sub Coy No");
            if (subNo == null) return;

            Integer hoMain = parseIntField(fHoMain, "HO Loan (main)"); if (hoMain == null) return;
            Integer hoSub  = parseIntField(fHoSub,  "HO Loan (sub)");  if (hoSub == null) return;
            Integer slMain = parseIntField(fSlMain, "Sub Loan (main)"); if (slMain == null) return;
            Integer slSub  = parseIntField(fSlSub,  "Sub Loan (sub)");  if (slSub == null) return;
            Integer interMain = parseIntField(fInterMain, "Inter-Co (main)"); if (interMain == null) return;
            Integer interSub  = parseIntField(fInterSub,  "Inter-Co (sub)");  if (interSub == null) return;
            Integer cdMain1 = parseIntField(f1cdMain, "1C-1D (main)"); if (cdMain1 == null) return;
            Integer cdSub1  = parseIntField(f1cdSub,  "1C-1D (sub)");  if (cdSub1 == null) return;
            Integer efMain1 = parseIntField(f1efMain, "1E-1F (main)"); if (efMain1 == null) return;
            Integer efSub1  = parseIntField(f1efSub,  "1E-1F (sub)");  if (efSub1 == null) return;
            Integer gMain1  = parseIntField(f1gMain, "1G (main)"); if (gMain1 == null) return;
            Integer gSub1   = parseIntField(f1gSub,  "1G (sub)");  if (gSub1 == null) return;
            Integer main4   = parseIntField(f4Main, "4 (main)"); if (main4 == null) return;
            Integer sub4    = parseIntField(f4Sub,  "4 (sub)");  if (sub4 == null) return;
            Integer abMain5 = parseIntField(f5abMain, "5A-5B (main)"); if (abMain5 == null) return;
            Integer abSub5  = parseIntField(f5abSub,  "5A-5B (sub)");  if (abSub5 == null) return;
            Integer abMain6 = parseIntField(f6abMain, "6A-6B (main)"); if (abMain6 == null) return;
            Integer abSub6  = parseIntField(f6abSub,  "6A-6B (sub)");  if (abSub6 == null) return;
            Integer main7   = parseIntField(f7Main, "7 (main)"); if (main7 == null) return;
            Integer sub7    = parseIntField(f7Sub,  "7 (sub)");  if (sub7 == null) return;
            Integer aMain7  = parseIntField(f7aMain, "7A (main)"); if (aMain7 == null) return;
            Integer aSub7   = parseIntField(f7aSub,  "7A (sub)");  if (aSub7 == null) return;
            Integer cdMain7 = parseIntField(f7cdMain, "7C-7D (main)"); if (cdMain7 == null) return;
            Integer cdSub7  = parseIntField(f7cdSub,  "7C-7D (sub)");  if (cdSub7 == null) return;

            m.basGroup = group.basGroup;
            m.companyNo = isAdd ? coNo : m.companyNo;
            m.subCoyNo  = isAdd ? subNo : m.subCoyNo;
            m.basHoLoanAcctMain = hoMain;    m.basHoLoanAcctSub = hoSub;
            m.basSubLoanAcctMain = slMain;   m.basSubLoanAcctSub = slSub;
            m.interCoyAcctMain = interMain;  m.interCoyAcctSub = interSub;
            m.bas1c1dAcctMain = cdMain1;     m.bas1c1dAcctSub = cdSub1;
            m.bas1e1fAcctMain = efMain1;     m.bas1e1fAcctSub = efSub1;
            m.bas1gAcctMain = gMain1;        m.bas1gAcctSub = gSub1;
            m.bas4AcctMain = main4;          m.bas4AcctSub = sub4;
            m.bas5a5bAcctMain = abMain5;     m.bas5a5bAcctSub = abSub5;
            m.bas6a6bAcctMain = abMain6;     m.bas6a6bAcctSub = abSub6;
            m.bas7AcctMain = main7;          m.bas7AcctSub = sub7;
            m.bas7aAcctMain = aMain7;        m.bas7aAcctSub = aSub7;
            m.bas7c7dAcctMain = cdMain7;     m.bas7c7dAcctSub = cdSub7;

            List<GlFieldSpec> specs = List.of(
                new GlFieldSpec(hoMain, hoSub, fHoMain, "HO Loan"),
                new GlFieldSpec(slMain, slSub, fSlMain, "Sub Loan"),
                new GlFieldSpec(interMain, interSub, fInterMain, "Inter-Co"),
                new GlFieldSpec(cdMain1, cdSub1, f1cdMain, "1C-1D"),
                new GlFieldSpec(efMain1, efSub1, f1efMain, "1E-1F"),
                new GlFieldSpec(gMain1, gSub1, f1gMain, "1G"),
                new GlFieldSpec(main4, sub4, f4Main, "4"),
                new GlFieldSpec(abMain5, abSub5, f5abMain, "5A-5B"),
                new GlFieldSpec(abMain6, abSub6, f6abMain, "6A-6B"),
                new GlFieldSpec(main7, sub7, f7Main, "7"),
                new GlFieldSpec(aMain7, aSub7, f7aMain, "7A"),
                new GlFieldSpec(cdMain7, cdSub7, f7cdMain, "7C-7D"));

            String userId = appSession.getUserId();
            int finalCoNo = m.companyNo;
            btnSave.setDisable(true);
            exec.submit(() -> {
                try {
                    if (isAdd) {
                        if (!basGroupService.companyExists(finalCoNo)) {
                            Platform.runLater(() -> { btnSave.setDisable(false);
                                markError(fCompanyNo, "Company not on file."); });
                            return;
                        }
                        if (m.subCoyNo > 0 && !basGroupService.subCoyExists(finalCoNo, m.subCoyNo)) {
                            Platform.runLater(() -> { btnSave.setDisable(false);
                                markError(fSubCoyNo, "Subsidiary company not on file."); });
                            return;
                        }
                        Optional<String> conflict = basGroupService.findConflictingOwner(finalCoNo, m.subCoyNo, null);
                        if (conflict.isPresent()) {
                            Platform.runLater(() -> { btnSave.setDisable(false);
                                markError(fCompanyNo, "Already attached to BAS group " + conflict.get()); });
                            return;
                        }
                    }
                    for (GlFieldSpec spec : specs) {
                        if (spec.main() == 0 && spec.sub() == 0) continue;   // not set — optional
                        GlAccountLookupDialog.AccountRow acct =
                            GlAccountLookupDialog.lookup(dsl, finalCoNo, spec.main(), spec.sub());
                        if (acct == null) {
                            Platform.runLater(() -> { btnSave.setDisable(false);
                                markError(spec.mainField(), spec.label() + " GL account is not valid (not on "
                                    + "file, not a financial account, or not a posting account)."); });
                            return;
                        }
                    }

                    if (isAdd) basGroupService.insertMember(m, userId);
                    else basGroupService.updateMember(m, userId);

                    Platform.runLater(() -> {
                        dlg.close();
                        reload.run();
                        status((isAdd ? "Added" : "Updated") + " member " + m.companyNo, false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> { btnSave.setDisable(false); status("Save error: " + ex.getMessage(), true); });
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
        scroll.setFitToWidth(true); scroll.setBorder(null);
        VBox root = new VBox(0, scroll, btnBar);
        dlg.setScene(new Scene(root, 620, 680));
        dlg.showAndWait();
    }

    // ── Shared GL account field widget ───────────────────────────────────

    /** Main+sub TextFields + magnifier picker + live description label, wired for both picker and direct entry. */
    private Node glAccountRow(TextField main, TextField sub, Label descLabelCtl,
                               java.util.function.IntSupplier companyNoSupplier, Window owner) {
        Button picker = new Button("🔍");
        picker.setStyle(PICKER_STYLE);
        picker.setOnAction(e -> GlAccountLookupDialog.show(owner, dsl, companyNoSupplier.getAsInt(), row -> {
            main.setText(String.valueOf(row.acctMain()));
            sub.setText(String.valueOf(row.acctSub()));
            descLabelCtl.setText(row.desc());
            descLabelCtl.setStyle(DESC_OK_STYLE);
            clearError(main); clearError(sub);
        }));

        Runnable revalidate = () -> {
            Integer mv = tryParseInt(main.getText());
            Integer sv = tryParseInt(sub.getText());
            if (mv == null || sv == null) return;
            if (mv == 0 && sv == 0) { descLabelCtl.setText(""); return; }
            int coNo = companyNoSupplier.getAsInt();
            exec.submit(() -> {
                GlAccountLookupDialog.AccountRow r = GlAccountLookupDialog.lookup(dsl, coNo, mv, sv);
                Platform.runLater(() -> {
                    if (r != null) { descLabelCtl.setText(r.desc()); descLabelCtl.setStyle(DESC_OK_STYLE); }
                    else { descLabelCtl.setText("Not on file"); descLabelCtl.setStyle(DESC_ERR_STYLE); }
                });
            });
        };
        main.focusedProperty().addListener((o, was, is) -> { if (!is) revalidate.run(); });
        sub.focusedProperty().addListener((o, was, is) -> { if (!is) revalidate.run(); });

        HBox box = new HBox(4, main, new Label("-"), sub, picker, descLabelCtl);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void prefillGlDesc(TextField main, TextField sub, Label descLabelCtl, int companyNo) {
        int mv = parseIntSafe(main.getText());
        int sv = parseIntSafe(sub.getText());
        if (mv == 0 && sv == 0) return;
        exec.submit(() -> {
            GlAccountLookupDialog.AccountRow r = GlAccountLookupDialog.lookup(dsl, companyNo, mv, sv);
            Platform.runLater(() -> {
                if (r != null) { descLabelCtl.setText(r.desc()); descLabelCtl.setStyle(DESC_OK_STYLE); }
                else { descLabelCtl.setText("Not on file"); descLabelCtl.setStyle(DESC_ERR_STYLE); }
            });
        });
    }

    private Label descLabel() {
        Label l = new Label("");
        l.setStyle(DESC_OK_STYLE);
        l.setMinWidth(180);
        l.setWrapText(true);
        return l;
    }

    // ── Small option lists ───────────────────────────────────────────────

    private static List<String> freqOptions() {
        return List.of("M — Monthly", "Q — Quarterly");
    }

    private static List<String> gstIndOptions() {
        return List.of("G — Reports its own GST", "C — Consolidated into another group");
    }

    private static List<String> gstMethodOptions() {
        return List.of("G — Gross entered", "T — Tax x 11");
    }

    private static List<String> trxIndOptions() {
        return List.of("P — Posting date", "T — Transaction date");
    }

    private static String freqLabel(String f) {
        if ("M".equalsIgnoreCase(f)) return "Monthly";
        if ("Q".equalsIgnoreCase(f)) return "Quarterly";
        return f == null ? "" : f;
    }

    /**
     * ChoiceBox of "X — Description" options, pre-selected by leading char
     * of {@code currentCode} (case-insensitive). Same pattern as
     * PayCodeMaintenanceController.
     */
    private static ChoiceBox<String> choiceBox(List<String> options, String currentCode) {
        ChoiceBox<String> cb = new ChoiceBox<>();
        cb.getItems().addAll(options);
        cb.setPrefWidth(260);
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

    private static String codeOf(ChoiceBox<String> cb) {
        String s = cb.getValue();
        return (s == null || s.isBlank()) ? "" : String.valueOf(s.charAt(0));
    }

    // ── Generic helpers ──────────────────────────────────────────────────

    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.isEmpty()) return 0;
        try { return Integer.parseInt(t); }
        catch (NumberFormatException ex) { return 0; }
    }

    /** Null on unparsable input, without raising a validation dialog (used by revalidate listeners). */
    private static Integer tryParseInt(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException ex) { return null; }
    }

    /** Parse an int field; null on failure (with markError). Empty → 0. */
    private Integer parseIntField(TextField fld, String label) {
        String s = fld.getText() == null ? "" : fld.getText().trim();
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

    private static String dateStr(LocalDate d) {
        return d == null ? "(none)" : d.format(D_FMT);
    }

    private TextField tf(String value, int maxLen) {
        TextField f = new TextField(value == null ? "" : value.trim());
        f.setPrefWidth(Math.min(maxLen * 9 + 10, 320));
        return f;
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

    /** Two-column form row: 140-wide label + control. */
    private HBox twoColRow(String label, Node control) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(140);
        HBox row = new HBox(10, l, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Label hint(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:10px;-fx-text-fill:#9CA3AF;-fx-padding:0 0 0 150;");
        l.setWrapText(true);
        return l;
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

    // ── Status bar ────────────────────────────────────────────────────────

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
            lblStatus.setStyle("-fx-font-size:11px;-fx-text-fill:" + (err ? "#C0392B" : "#888780") + ";");
        });
    }
}
