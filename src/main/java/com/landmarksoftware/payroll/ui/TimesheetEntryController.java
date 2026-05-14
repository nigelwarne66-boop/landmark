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
import com.landmarksoftware.payroll.model.PayGroup;
import com.landmarksoftware.payroll.model.Payrun;
import com.landmarksoftware.payroll.model.PayrunGroup;
import com.landmarksoftware.payroll.service.PayGroupService;
import com.landmarksoftware.payroll.service.PayrunGroupService;
import com.landmarksoftware.payroll.service.PayrunService;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * PATM01 — Timesheet Entry.
 *
 * <p>Mirrors the COBOL screen flow:
 * <ul>
 *   <li><b>S0</b> — modal parameter dialog: date range, include-fully-posted /
 *       cancelled flags, payrun-type filter. Drives the P1 query.</li>
 *   <li><b>P1</b> — payrun list (parunhd). Add / Edit / Cancel a payrun; pick
 *       one to drill into P2.</li>
 *   <li><b>P2</b> — paygroup pick for the selected payrun (parungr).
 *       Select / Edit / Delete a paygroup; Create-Payrun (back-edit parent);
 *       Status (inquire only); Back returns to P1.</li>
 * </ul>
 *
 * <p>P3 (timesheet line entry) and S3B (per-line dialog) are <em>not yet
 * built</em> — Select on P2 currently surfaces an "in progress" notice. This
 * is intentional: the user has asked for P2 to be validated before further
 * progress.
 */
@Component
public class TimesheetEntryController {

    private static final DateTimeFormatter DDMMYY = DateTimeFormatter.ofPattern("dd/MM/yy");

    private final PayrunService      payruns;
    private final PayrunGroupService groups;
    private final PayGroupService    payGroupMaster;
    private final AppSession         appSession;

    private Stage      stage;
    private BorderPane root;

    // ── S0 parameters ─────────────────────────────────────────────────────
    private LocalDate s0Start;
    private LocalDate s0End;
    private boolean   s0IncludeFullyPosted = false;
    private boolean   s0IncludeCancelled   = false;
    private String    s0PayrunType         = "";   // "" = all

    // ── P1 state ──────────────────────────────────────────────────────────
    private final ObservableList<Payrun> p1Rows = FXCollections.observableArrayList();
    private TableView<Payrun>            p1Table;
    private Label                        p1Status;

    // ── P2 state ──────────────────────────────────────────────────────────
    private final ObservableList<PayrunGroup> p2Rows = FXCollections.observableArrayList();
    private TableView<PayrunGroup>            p2Table;
    private Label                             p2Status;
    private Payrun                            p2Payrun;   // current parent

    /** Active paygroup-range filter on P2 — set by the "Select Paygroups" modal. */
    private String  p2RangeStart    = "";       // empty = no lower bound
    private String  p2RangeEnd      = "zzzz";   // 'zzzz' = no upper bound
    /** Validate flag from "Select Paygroups" modal — prompts on each P2 Select. */
    private boolean p2ValidateOnSelect = false;

    public TimesheetEntryController(PayrunService payruns,
                                     PayrunGroupService groups,
                                     PayGroupService payGroupMaster,
                                     AppSession appSession) {
        this.payruns        = payruns;
        this.groups         = groups;
        this.payGroupMaster = payGroupMaster;
        this.appSession     = appSession;
    }

    // ── Entry point ───────────────────────────────────────────────────────

    public Scene buildScene(Stage stage) {
        this.stage = stage;
        root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildHeader());

        // Default S0 params before opening the dialog the first time
        s0Start = appSession.getYrStartDate() != null
            ? appSession.getYrStartDate()
            : LocalDate.now().minusDays(90);
        s0End = appSession.getYrEndDate() != null
            ? appSession.getYrEndDate()
            : LocalDate.now().plusDays(30);

        showP1();

        Scene scene = new Scene(root, 1000, 600);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    // ── Header ────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("Timesheet Entry");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("PATM01 · " + appSession.getCompanyName());
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

    // ── S0 — parameter dialog ─────────────────────────────────────────────

    /** Modal S0 — returns true if user clicked OK and params changed. */
    private boolean showS0(boolean cancelable) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Payrun Filter (S0)");
        dlg.setHeaderText("Select payruns by date range and status.");
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.initOwner(stage);

        DatePicker dpStart = new DatePicker(s0Start);
        DatePicker dpEnd   = new DatePicker(s0End);
        CheckBox   cbFP    = new CheckBox("Include fully posted (status F)");
        cbFP.setSelected(s0IncludeFullyPosted);
        CheckBox   cbCanc  = new CheckBox("Include cancelled (status D)");
        cbCanc.setSelected(s0IncludeCancelled);

        ComboBox<String> cbType = new ComboBox<>(FXCollections.observableArrayList(
            "All", "Primary (P)", "Termination (T)", "Backpay (B)", "Supplementary (S)"));
        cbType.getSelectionModel().select(typeIndex(s0PayrunType));

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(14));
        g.add(new Label("Start date:"),  0, 0); g.add(dpStart, 1, 0);
        g.add(new Label("End date:"),    0, 1); g.add(dpEnd,   1, 1);
        g.add(new Label("Payrun type:"), 0, 2); g.add(cbType,  1, 2);
        g.add(cbFP,                      1, 3);
        g.add(cbCanc,                    1, 4);

        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (!cancelable) {
            dlg.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true);
        }

        var result = dlg.showAndWait();
        if (result.isEmpty() || result.get() == ButtonType.CANCEL) {
            return false;
        }
        s0Start              = dpStart.getValue();
        s0End                = dpEnd.getValue();
        s0IncludeFullyPosted = cbFP.isSelected();
        s0IncludeCancelled   = cbCanc.isSelected();
        s0PayrunType         = typeCode(cbType.getSelectionModel().getSelectedIndex());
        return true;
    }

    private static int typeIndex(String code) {
        return switch (code == null ? "" : code.toUpperCase()) {
            case "P" -> 1; case "T" -> 2; case "B" -> 3; case "S" -> 4;
            default  -> 0;
        };
    }
    private static String typeCode(int idx) {
        return switch (idx) {
            case 1 -> "P"; case 2 -> "T"; case 3 -> "B"; case 4 -> "S";
            default -> "";
        };
    }

    // ── P1 — payrun list ──────────────────────────────────────────────────

    private void showP1() {
        p1Table = new TableView<>(p1Rows);
        p1Table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        p1Table.setPlaceholder(new Label(
            "No payruns match the current filter. Click Filter… to widen, or Add to create one."));
        VBox.setVgrow(p1Table, Priority.ALWAYS);

        addCol(p1Table, "Payrun No",  60,
            p -> new SimpleStringProperty(String.valueOf(p.payrunNo)));
        addCol(p1Table, "Date",       90,
            p -> new SimpleStringProperty(fmt(p.payrunDate)));
        addCol(p1Table, "Type",       110,
            p -> new SimpleStringProperty(p.typeDisplay()));
        addCol(p1Table, "Staff",      60,
            p -> new SimpleStringProperty(String.valueOf(p.noOfEmployees)));
        addCol(p1Table, "Paid",       60,
            p -> new SimpleStringProperty(String.valueOf(p.noOfEmployeesPaid)));
        addCol(p1Table, "Reference",  260,
            p -> new SimpleStringProperty(p.ref));
        addCol(p1Table, "Status",     90,
            p -> new SimpleStringProperty(p.statusDisplay()));

        p1Table.setRowFactory(tv -> {
            TableRow<Payrun> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    openOptions(row.getItem(), false);
                }
            });
            return row;
        });

        Button bFilter  = new Button("Filter…");
        Button bOptions = new Button("Options ▸");
        Button bAdd     = new Button("Add");
        Button bCancel  = new Button("Cancel");
        Button bRefresh = new Button("Refresh");

        bFilter.setOnAction(e -> { if (showS0(true)) loadP1(); });
        bOptions.setOnAction(e -> {
            Payrun sel = p1Table.getSelectionModel().getSelectedItem();
            if (sel != null) openOptions(sel, false);
        });
        bAdd.setOnAction(e -> {
            Payrun added = addPayrun();
            if (added != null) openOptions(added, true);
        });
        bCancel.setOnAction(e -> cancelPayrun(p1Table.getSelectionModel().getSelectedItem()));
        bRefresh.setOnAction(e -> loadP1());

        HBox toolbar = new HBox(8, bFilter, sep(), bOptions, bAdd, bCancel, sep(), bRefresh);
        toolbar.setPadding(new Insets(10, 14, 10, 14));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle("-fx-background-color:#FFFFFF;");

        p1Status = new Label();
        p1Status.setPadding(new Insets(6, 14, 8, 14));
        p1Status.setStyle("-fx-font-size:11px;-fx-text-fill:#666;");

        VBox content = new VBox(toolbar, p1Table, p1Status);
        VBox.setVgrow(p1Table, Priority.ALWAYS);
        root.setCenter(content);
        loadP1();
    }

    private void loadP1() {
        List<Payrun> list = payruns.findFiltered(
            appSession.getCompanyNo(), s0Start, s0End, s0PayrunType,
            s0IncludeFullyPosted, s0IncludeCancelled);
        p1Rows.setAll(list);
        p1Status.setText(list.size() + " payrun" + (list.size() == 1 ? "" : "s")
            + " · " + fmt(s0Start) + " to " + fmt(s0End)
            + (s0PayrunType.isBlank() ? "" : " · type " + s0PayrunType)
            + (s0IncludeFullyPosted ? " · +fully posted" : "")
            + (s0IncludeCancelled   ? " · +cancelled"    : ""));
    }

    // ── P1 Edit / Add modal ──────────────────────────────────────────────

    /** Run the Add flow; returns the inserted Payrun, or null on cancel / error. */
    private Payrun addPayrun() {
        Payrun seed = new Payrun();
        seed.companyNo    = appSession.getCompanyNo();
        seed.payrunNo     = payruns.nextPayrunNo(appSession.getCompanyNo());
        seed.payrunDate   = LocalDate.now();
        seed.yrNo         = appSession.getYrNo();
        seed.payrunType   = "P";
        seed.payrunStatus = "O";
        seed.startDate    = LocalDate.now();
        seed.endDate      = LocalDate.now();
        seed.paymtDate    = LocalDate.now();
        seed.paymtYrNo    = appSession.getYearNo();
        return runPayrunEditor(seed, true);
    }

    /** Run the Edit flow on an existing row. */
    private void editPayrun(Payrun source) {
        if (source == null) return;
        runPayrunEditor(copyOf(source), false);
    }

    /**
     * Common Add/Edit dialog. Returns the saved Payrun on OK, null on cancel or
     * error. {@code loadP1()} is called on a successful save so the list reflects.
     */
    private Payrun runPayrunEditor(Payrun p, boolean isAdd) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(isAdd ? "Add Payrun" : "Edit Payrun " + p.payrunNo);
        dlg.setHeaderText(isAdd ? "Create a new payrun." : "Edit payrun " + p.payrunNo + ".");
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);

        DatePicker dpDate    = new DatePicker(p.payrunDate);
        ComboBox<String> cbType = new ComboBox<>(FXCollections.observableArrayList(
            "Primary (P)", "Termination (T)", "Backpay (B)", "Supplementary (S)"));
        cbType.getSelectionModel().select(Math.max(0, typeIndex(p.payrunType) - 1));
        TextField tfRef       = new TextField(p.ref);
        DatePicker dpStart    = new DatePicker(p.startDate);
        DatePicker dpEnd      = new DatePicker(p.endDate);
        DatePicker dpPaymt    = new DatePicker(p.paymtDate);
        TextField  tfPayrunNo = new TextField(String.valueOf(p.payrunNo));
        tfPayrunNo.setDisable(true);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(14));
        int r = 0;
        g.add(new Label("Payrun No:"), 0, r); g.add(tfPayrunNo, 1, r++);
        g.add(new Label("Date:"),      0, r); g.add(dpDate,     1, r++);
        g.add(new Label("Type:"),      0, r); g.add(cbType,     1, r++);
        g.add(new Label("Reference:"), 0, r); g.add(tfRef,      1, r++);
        g.add(new Label("Period start:"), 0, r); g.add(dpStart, 1, r++);
        g.add(new Label("Period end:"),   0, r); g.add(dpEnd,   1, r++);
        g.add(new Label("Payment date:"), 0, r); g.add(dpPaymt, 1, r++);
        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return null;

        p.payrunDate = dpDate.getValue();
        p.payrunType = typeCode(cbType.getSelectionModel().getSelectedIndex() + 1);
        p.ref        = tfRef.getText() == null ? "" : tfRef.getText();
        p.startDate  = dpStart.getValue();
        p.endDate    = dpEnd.getValue();
        p.paymtDate  = dpPaymt.getValue();

        try {
            if (isAdd) payruns.insert(p, appSession.getUserId());
            else       payruns.update(p, appSession.getUserId());
            loadP1();
            return p;
        } catch (Exception ex) {
            error("Could not save payrun: " + ex.getMessage());
            return null;
        }
    }

    // ── Options dialog — 5 buttons mirroring COBOL post-Add flow ──────────

    /**
     * Post-add Payrun Options dialog. Five actions on the selected payrun:
     * <ol>
     *   <li><b>Edit</b> — re-open the header editor.</li>
     *   <li><b>Default</b> — set parunhd flag defaults (cost type, calc tax /
     *       super flags, skip-paygroup flags, retainer/splits/RDO flags).</li>
     *   <li><b>Select</b> — drill into P2 paygroup pick. Closes the modal.</li>
     *   <li><b>Create</b> — auto-create timesheets from paecode standing rows.
     *       Stubbed until P3 + paecode CRUD lands.</li>
     *   <li><b>Import</b> — copy timesheets from a prior payrun. Stubbed.</li>
     * </ol>
     *
     * @param p         the payrun the actions operate on.
     * @param fromAdd   true if invoked immediately after Add — surfaces the
     *                  modal automatically with a "Just added" caption.
     */
    private void openOptions(Payrun p, boolean fromAdd) {
        if (p == null) return;
        if (!p.isOpen()) {
            info("Payrun " + p.payrunNo + " is " + p.statusDisplay().toLowerCase()
                + " — options are read-only.");
        }

        Stage dlg = new Stage();
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Payrun " + p.payrunNo + " — Options");

        Label header = new Label((fromAdd ? "Payrun " + p.payrunNo + " created. "
                                          : "Payrun " + p.payrunNo + ". ")
            + "Choose an action:");
        header.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label(fmt(p.payrunDate) + " · " + p.typeDisplay()
            + (p.ref.isBlank() ? "" : " — " + p.ref));
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");

        Button b1 = optionButton("1  Edit",
            "Re-open the payrun header editor.");
        Button b2 = optionButton("2  Default",
            "Set payrun defaults — cost type, calc tax / super flags,\n"
            + "skip-paygroup flags, retainer / splits / RDO flags.");
        Button b3 = optionButton("3  Select",
            "Drill into Paygroup Pick (P2) for this payrun.");
        Button b4 = optionButton("4  Create",
            "Auto-create timesheet rows from each employee's paecode\n"
            + "standing lines. Available once P3 / paecode CRUD lands.");
        Button b5 = optionButton("5  Import",
            "Copy timesheet rows from a prior payrun. Available once P3\n"
            + "/ paecode CRUD lands.");
        Button bClose = new Button("Close");

        // Important: when an Options button leads to another dialog, close the
        // Options Stage first and defer the next dialog to the next FX pulse
        // via Platform.runLater. Opening a nested showAndWait() before the
        // outer one returns leaves the new dialog non-interactive (its
        // ComboBoxes / CheckBoxes silently swallow input).
        b1.setOnAction(e -> {
            dlg.close();
            Platform.runLater(() -> { editPayrun(p); refreshAndReopen(p, false); });
        });
        b2.setOnAction(e -> {
            dlg.close();
            Platform.runLater(() -> openDefaultsDialog(p));
        });
        b3.setOnAction(e -> {
            dlg.close();
            Platform.runLater(() -> openSelectPaygroups(p));
        });
        b4.setOnAction(e -> info(
            "Create timesheets from defaults — coming with P3 / paecode CRUD."));
        b5.setOnAction(e -> info(
            "Import timesheets from a prior payrun — coming with P3 / paecode CRUD."));
        bClose.setOnAction(e -> dlg.close());

        if (!p.isOpen()) { b1.setDisable(true); b2.setDisable(true); b4.setDisable(true); b5.setDisable(true); }

        VBox col = new VBox(8, b1, b2, b3, b4, b5);
        col.setPadding(new Insets(14, 18, 6, 18));

        HBox closeBar = new HBox(bClose);
        closeBar.setAlignment(Pos.CENTER_RIGHT);
        closeBar.setPadding(new Insets(8, 18, 14, 18));

        VBox content = new VBox(4, header, sub, col, new Separator(), closeBar);
        content.setPadding(new Insets(14, 0, 0, 0));
        VBox.setVgrow(col, Priority.ALWAYS);

        dlg.setScene(new Scene(content, 380, 360));
        dlg.showAndWait();
    }

    private static Button optionButton(String label, String tooltip) {
        Button b = new Button(label);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setStyle("-fx-padding:8 14 8 14;-fx-font-size:13px;");
        b.setTooltip(new Tooltip(tooltip));
        return b;
    }

    /** Re-fetch the latest copy of a payrun and refresh P1. Used after Edit. */
    private Payrun refreshAndReopen(Payrun p, boolean reopenOptions) {
        loadP1();
        Payrun fresh = payruns.findOne(p.companyNo, p.payrunNo).orElse(p);
        if (reopenOptions) openOptions(fresh, false);
        return fresh;
    }

    /**
     * Payrun-level defaults editor (Option 2). Captures the parunhd flags that
     * govern how the payrun behaves for subsequent screens.
     */
    private void openDefaultsDialog(Payrun source) {
        Payrun p = copyOf(source);

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Payrun " + p.payrunNo + " — Defaults");
        dlg.setHeaderText("Defaults that apply to the rest of this payrun.");
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);

        ComboBox<String> cbCost = new ComboBox<>(FXCollections.observableArrayList(
            "(none)",
            "I — Indirect Expenses",
            "G — General Ledger",
            "L — Cost Ledger"));
        cbCost.getSelectionModel().select(costIndex(p.defaultCostType));

        CheckBox cbCalcTax    = new CheckBox("Calculate tax by default");
        cbCalcTax.setSelected("Y".equalsIgnoreCase(p.defaultCalcTaxFlag));
        CheckBox cbCalcSuper  = new CheckBox("Calculate super by default");
        cbCalcSuper.setSelected("Y".equalsIgnoreCase(p.defltCalcSuperFlag));
        CheckBox cbSkipAdd    = new CheckBox("Skip paygroup prompt on Add");
        cbSkipAdd.setSelected("Y".equalsIgnoreCase(p.skipPaygroupOnAdd));
        CheckBox cbSkipEdit   = new CheckBox("Skip paygroup prompt on Edit");
        cbSkipEdit.setSelected("Y".equalsIgnoreCase(p.skipPaygroupOnEdit));
        CheckBox cbRetainer   = new CheckBox("Retainer payrun");
        cbRetainer.setSelected("Y".equalsIgnoreCase(p.retainerRunFlag));
        CheckBox cbSplits     = new CheckBox("Splits payrun");
        cbSplits.setSelected("Y".equalsIgnoreCase(p.splitsRunFlag));
        CheckBox cbRdo        = new CheckBox("Create RDO accruals");
        cbRdo.setSelected("Y".equalsIgnoreCase(p.createRdoFlag));

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(14));
        int r = 0;
        g.add(new Label("Default cost type:"), 0, r); g.add(cbCost,      1, r++);
        g.add(cbCalcTax,                       1, r++);
        g.add(cbCalcSuper,                     1, r++);
        g.add(new Separator(),                 0, r, 2, 1); r++;
        g.add(cbSkipAdd,                       1, r++);
        g.add(cbSkipEdit,                      1, r++);
        g.add(new Separator(),                 0, r, 2, 1); r++;
        g.add(cbRetainer,                      1, r++);
        g.add(cbSplits,                        1, r++);
        g.add(cbRdo,                           1, r++);

        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return;

        p.defaultCostType    = costCode(cbCost.getSelectionModel().getSelectedIndex());
        p.defaultCalcTaxFlag = cbCalcTax.isSelected()    ? "Y" : "N";
        p.defltCalcSuperFlag = cbCalcSuper.isSelected()  ? "Y" : "N";
        p.skipPaygroupOnAdd  = cbSkipAdd.isSelected()    ? "Y" : "N";
        p.skipPaygroupOnEdit = cbSkipEdit.isSelected()   ? "Y" : "N";
        p.retainerRunFlag    = cbRetainer.isSelected()   ? "Y" : "N";
        p.splitsRunFlag      = cbSplits.isSelected()     ? "Y" : "N";
        p.createRdoFlag      = cbRdo.isSelected()        ? "Y" : "N";

        try {
            payruns.update(p, appSession.getUserId());
            loadP1();
        } catch (Exception ex) {
            error("Could not save defaults: " + ex.getMessage());
        }
    }

    private static int costIndex(String code) {
        return switch (code == null ? "" : code.toUpperCase()) {
            case "I" -> 1; case "G" -> 2; case "L" -> 3; default -> 0;
        };
    }
    private static String costCode(int idx) {
        return switch (idx) { case 1 -> "I"; case 2 -> "G"; case 3 -> "L"; default -> ""; };
    }

    private void cancelPayrun(Payrun p) {
        if (p == null) return;
        if (!p.isOpen()) {
            info("Payrun " + p.payrunNo + " is already " + p.statusDisplay().toLowerCase() + ".");
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "Cancel payrun " + p.payrunNo + " (" + fmt(p.payrunDate) + ")?\n"
                + "Status will be set to Cancelled. Timesheet rows are not deleted.",
            ButtonType.YES, ButtonType.NO);
        a.setHeaderText(null);
        a.initOwner(stage);
        var res = a.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.YES) {
            payruns.cancel(p.companyNo, p.payrunNo, appSession.getUserId());
            loadP1();
        }
    }

    // ── Options → Select : paygroup range + Validate ─────────────────────

    /**
     * "Select Paygroups" — the screen between Options→Select and the P2
     * listbox. Captures a paygroup range (start..end) and the "Validate
     * paygroup before selection" flag, then opens P2 with those filters
     * applied. If the payrun has no paygroups attached yet, we skip straight
     * to P2 so the user can Add one.
     */
    private void openSelectPaygroups(Payrun p) {
        if (p == null) return;

        // Source: pagroup master for this company (all defined paygroups), so the
        // user can pick a range even when no parungr rows are attached yet.
        // The attached set is only used to surface "(attached)" hints alongside.
        List<PayGroup> master = payGroupMaster.findAll(p.companyNo);
        if (master.isEmpty()) {
            info("No pay groups are defined for this company. Open Pay Group "
                + "Maintenance (PAPG01) first to create at least one.");
            return;
        }
        java.util.Set<String> attachedCodes = groups.findByPayrun(p.companyNo, p.payrunNo)
            .stream().map(g -> g.paygroup).collect(java.util.stream.Collectors.toSet());

        ObservableList<String> codes = FXCollections.observableArrayList(
            master.stream().map(pg -> pg.paygroup).sorted().toList());

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Payrun " + p.payrunNo + " — Select Paygroups");
        dlg.setHeaderText("Choose the range of paygroups to work on for this payrun.");
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);

        ComboBox<String> cbStart = new ComboBox<>(codes);
        ComboBox<String> cbEnd   = new ComboBox<>(codes);
        cbStart.getSelectionModel().select(
            codes.contains(p2RangeStart) ? p2RangeStart : codes.get(0));
        cbEnd.getSelectionModel().select(
            codes.contains(p2RangeEnd) ? p2RangeEnd : codes.get(codes.size() - 1));

        CheckBox cbValidate = new CheckBox("Validate paygroup before selection");
        cbValidate.setSelected(p2ValidateOnSelect);
        cbValidate.setTooltip(new Tooltip(
            "When on, P2 prompts for confirmation before drilling into each\n"
            + "paygroup's timesheet entry."));

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(14));
        int r = 0;
        g.add(new Label("Start paygroup:"), 0, r); g.add(cbStart, 1, r++);
        g.add(new Label("End paygroup:"),   0, r); g.add(cbEnd,   1, r++);
        g.add(cbValidate,                   1, r++);
        Label count = new Label(master.size() + " paygroup"
            + (master.size() == 1 ? "" : "s") + " defined · "
            + attachedCodes.size() + " attached to this payrun.");
        count.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        g.add(count, 0, r, 2, 1);

        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return;

        String start = nz(cbStart.getValue());
        String end   = nz(cbEnd.getValue());
        if (start.compareToIgnoreCase(end) > 0) { String swap = start; start = end; end = swap; }
        p2RangeStart       = start;
        p2RangeEnd         = end;
        p2ValidateOnSelect = cbValidate.isSelected();

        // Attach every master paygroup in the range to the payrun (insert a
        // parungr row with default pay-thru dates) if not already attached.
        // Validate=on prompts before each insert; Cancel skips that paygroup.
        LocalDate defaultThru = p.endDate != null ? p.endDate : p.payrunDate;
        int attached = 0;
        int skipped  = 0;
        for (PayGroup pg : master) {
            String code = pg.paygroup;
            if (code == null) continue;
            if (code.compareToIgnoreCase(start) < 0)  continue;
            if (code.compareToIgnoreCase(end)   > 0)  continue;
            if (attachedCodes.contains(code))         continue;   // already there
            if (cbValidate.isSelected()) {
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "Attach paygroup " + code
                        + (pg.desc1 == null || pg.desc1.isBlank() ? "" : " (" + pg.desc1 + ")")
                        + " to payrun " + p.payrunNo + "?",
                    ButtonType.YES, ButtonType.NO);
                a.setHeaderText("Validate paygroup before selection");
                a.initOwner(stage);
                var ans = a.showAndWait();
                if (ans.isEmpty() || ans.get() != ButtonType.YES) { skipped++; continue; }
            }
            PayrunGroup row = new PayrunGroup();
            row.companyNo       = p.companyNo;
            row.payrunNo        = p.payrunNo;
            row.paygroup        = code;
            row.paygroupStatus  = "O";
            row.payThruToWeek   = defaultThru;
            row.payThruToFort   = defaultThru;
            row.payThruToBimth  = defaultThru;
            row.payThruTo4Wk    = defaultThru;
            row.payThruToMth    = defaultThru;
            try {
                groups.insert(row, appSession.getUserId());
                attached++;
            } catch (Exception ex) {
                error("Could not attach " + code + ": " + ex.getMessage());
            }
        }
        if (attached > 0 || skipped > 0) {
            // Surface the result so an empty P2 isn't read as "nothing happened".
            String msg = attached + " paygroup" + (attached == 1 ? "" : "s") + " attached"
                + (skipped > 0 ? " · " + skipped + " skipped" : "") + ".";
            info(msg);
        }
        openP2(p);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ── P2 — paygroup pick (the critical screen) ─────────────────────────

    private void openP2(Payrun parent) {
        if (parent == null) return;
        if (!parent.isOpen()) {
            info("Payrun " + parent.payrunNo + " is " + parent.statusDisplay().toLowerCase()
                + " — cannot drill into paygroup detail.");
            return;
        }
        p2Payrun = parent;
        appSession.setSelectedPayrunNo(parent.payrunNo);
        appSession.setSelectedPayrunDate(fmt(parent.payrunDate));
        appSession.setSelectedPayrunDesc(parent.ref);

        p2Table = new TableView<>(p2Rows);
        p2Table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        p2Table.setPlaceholder(new Label(
            "No paygroups attached to this payrun. Click Add to add one."));
        VBox.setVgrow(p2Table, Priority.ALWAYS);

        addCol(p2Table, "Paygroup",   80,
            r -> new SimpleStringProperty(r.paygroup));
        addCol(p2Table, "Description", 240,
            r -> new SimpleStringProperty(r.paygroupDesc));
        addCol(p2Table, "Monthly",    90,
            r -> new SimpleStringProperty(fmt(r.payThruToMth)));
        addCol(p2Table, "4-Weekly",   90,
            r -> new SimpleStringProperty(fmt(r.payThruTo4Wk)));
        addCol(p2Table, "Bimthly",    90,
            r -> new SimpleStringProperty(fmt(r.payThruToBimth)));
        addCol(p2Table, "Fortnight",  90,
            r -> new SimpleStringProperty(fmt(r.payThruToFort)));
        addCol(p2Table, "Weekly",     90,
            r -> new SimpleStringProperty(fmt(r.payThruToWeek)));
        addCol(p2Table, "Status",     80,
            r -> new SimpleStringProperty(r.statusDisplay()));

        p2Table.setRowFactory(tv -> {
            TableRow<PayrunGroup> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    selectPaygroup(row.getItem());
                }
            });
            return row;
        });

        Button bBack    = new Button("◂ Back");
        Button bSelect  = new Button("Select ▸ Timesheets");
        Button bAdd     = new Button("Add");
        Button bEdit    = new Button("Edit");
        Button bDelete  = new Button("Delete");
        Button bRange   = new Button("Range…");
        Button bRefresh = new Button("Refresh");

        bBack.setOnAction(e -> {
            appSession.clearPayrun();
            // Reset the range / validate state so a fresh drill-in starts clean.
            p2RangeStart       = "";
            p2RangeEnd         = "zzzz";
            p2ValidateOnSelect = false;
            showP1();
        });
        bSelect.setOnAction(e -> selectPaygroup(p2Table.getSelectionModel().getSelectedItem()));
        bAdd.setOnAction(e -> editPaygroup(null));
        bEdit.setOnAction(e -> editPaygroup(p2Table.getSelectionModel().getSelectedItem()));
        bDelete.setOnAction(e -> deletePaygroup(p2Table.getSelectionModel().getSelectedItem()));
        bRange.setOnAction(e -> openSelectPaygroups(p2Payrun));
        bRefresh.setOnAction(e -> loadP2());

        HBox toolbar = new HBox(8, bBack, sep(), bSelect, sep(),
                                   bAdd, bEdit, bDelete, sep(), bRange, bRefresh);
        toolbar.setPadding(new Insets(10, 14, 10, 14));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle("-fx-background-color:#FFFFFF;");

        Label crumb = new Label("Payrun " + parent.payrunNo + " · "
            + fmt(parent.payrunDate) + " · " + parent.typeDisplay()
            + (parent.ref.isBlank() ? "" : " — " + parent.ref));
        crumb.setStyle("-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        HBox crumbBar = new HBox(crumb);
        crumbBar.setPadding(new Insets(10, 14, 6, 14));

        p2Status = new Label();
        p2Status.setPadding(new Insets(6, 14, 8, 14));
        p2Status.setStyle("-fx-font-size:11px;-fx-text-fill:#666;");

        VBox content = new VBox(crumbBar, toolbar, p2Table, p2Status);
        VBox.setVgrow(p2Table, Priority.ALWAYS);
        root.setCenter(content);
        loadP2();
    }

    private void loadP2() {
        List<PayrunGroup> all = groups.findByPayrun(p2Payrun.companyNo, p2Payrun.payrunNo);
        // Apply the active range filter set by Options → Select.
        List<PayrunGroup> list = all.stream()
            .filter(g -> rangeIncludes(g.paygroup))
            .toList();
        p2Rows.setAll(list);

        long open   = list.stream().filter(g -> "O".equalsIgnoreCase(g.paygroupStatus)).count();
        long closed = list.size() - open;
        StringBuilder s = new StringBuilder()
            .append(list.size()).append(" paygroup").append(list.size() == 1 ? "" : "s")
            .append(" · ").append(open).append(" open · ")
            .append(closed).append(" closed/full");
        if (isRangeActive()) {
            s.append(" · range ").append(p2RangeStart.isBlank() ? "*" : p2RangeStart)
             .append("…").append("zzzz".equals(p2RangeEnd) ? "*" : p2RangeEnd);
            if (all.size() != list.size()) {
                s.append(" (").append(all.size() - list.size()).append(" hidden)");
            }
        }
        if (p2ValidateOnSelect) s.append(" · validate on");
        p2Status.setText(s.toString());
    }

    private boolean rangeIncludes(String paygroup) {
        if (paygroup == null) return false;
        if (!p2RangeStart.isBlank() && paygroup.compareToIgnoreCase(p2RangeStart) < 0) return false;
        if (paygroup.compareToIgnoreCase(p2RangeEnd) > 0) return false;
        return true;
    }
    private boolean isRangeActive() {
        return !p2RangeStart.isBlank() || !"zzzz".equalsIgnoreCase(p2RangeEnd);
    }

    private void selectPaygroup(PayrunGroup g) {
        if (g == null) return;
        if (p2ValidateOnSelect) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Open timesheet entry for paygroup " + g.paygroup
                    + (g.paygroupDesc.isBlank() ? "" : " (" + g.paygroupDesc + ")")
                    + "?\nStatus: " + g.statusDisplay(),
                ButtonType.YES, ButtonType.NO);
            a.setHeaderText("Validate paygroup before selection");
            a.initOwner(stage);
            var res = a.showAndWait();
            if (res.isEmpty() || res.get() != ButtonType.YES) return;
        }
        // P3 / S3B not yet built. Tell the user where we are.
        info("Paygroup " + g.paygroup
            + (g.paygroupDesc.isBlank() ? "" : " (" + g.paygroupDesc + ")")
            + " selected on payrun " + p2Payrun.payrunNo + ".\n\n"
            + "Timesheet entry (P3 / S3B) is the next Wave 3 step — not yet built.\n"
            + "P2 paygroup pick is in for review before P3 work begins.");
    }

    private void editPaygroup(PayrunGroup source) {
        boolean isAdd = (source == null);
        PayrunGroup g = isAdd ? new PayrunGroup() : copyOf(source);
        if (isAdd) {
            g.companyNo = p2Payrun.companyNo;
            g.payrunNo  = p2Payrun.payrunNo;
            g.paygroupStatus = "O";
            LocalDate dft = p2Payrun.endDate != null ? p2Payrun.endDate : p2Payrun.payrunDate;
            g.payThruToWeek  = dft;
            g.payThruToFort  = dft;
            g.payThruToBimth = dft;
            g.payThruTo4Wk   = dft;
            g.payThruToMth   = dft;
        }

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(isAdd ? "Add Paygroup to Payrun " + p2Payrun.payrunNo
                            : "Edit Paygroup " + g.paygroup);
        dlg.setHeaderText(null);
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);

        TextField tfPaygroup = new TextField(g.paygroup);
        tfPaygroup.setDisable(!isAdd);
        DatePicker dpWeek    = new DatePicker(g.payThruToWeek);
        DatePicker dpFort    = new DatePicker(g.payThruToFort);
        DatePicker dpBimth   = new DatePicker(g.payThruToBimth);
        DatePicker dp4Wk     = new DatePicker(g.payThruTo4Wk);
        DatePicker dpMth     = new DatePicker(g.payThruToMth);
        ComboBox<String> cbStatus = new ComboBox<>(FXCollections.observableArrayList(
            "Open (O)", "Closed (C)", "Full (F)"));
        cbStatus.getSelectionModel().select(statusIndex(g.paygroupStatus));

        GridPane gp = new GridPane();
        gp.setHgap(10); gp.setVgap(8); gp.setPadding(new Insets(14));
        int r = 0;
        gp.add(new Label("Paygroup:"),         0, r); gp.add(tfPaygroup, 1, r++);
        gp.add(new Label("Weekly pay-thru:"),  0, r); gp.add(dpWeek,     1, r++);
        gp.add(new Label("Fortnight pay-thru:"), 0, r); gp.add(dpFort,   1, r++);
        gp.add(new Label("Bimthly pay-thru:"), 0, r); gp.add(dpBimth,    1, r++);
        gp.add(new Label("4-weekly pay-thru:"), 0, r); gp.add(dp4Wk,     1, r++);
        gp.add(new Label("Monthly pay-thru:"), 0, r); gp.add(dpMth,      1, r++);
        gp.add(new Label("Status:"),           0, r); gp.add(cbStatus,   1, r++);
        dlg.getDialogPane().setContent(gp);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return;

        g.paygroup       = tfPaygroup.getText() == null ? "" : tfPaygroup.getText().trim();
        g.payThruToWeek  = dpWeek.getValue();
        g.payThruToFort  = dpFort.getValue();
        g.payThruToBimth = dpBimth.getValue();
        g.payThruTo4Wk   = dp4Wk.getValue();
        g.payThruToMth   = dpMth.getValue();
        g.paygroupStatus = statusCode(cbStatus.getSelectionModel().getSelectedIndex());

        if (g.paygroup.isBlank()) {
            error("Paygroup code is required.");
            return;
        }

        try {
            if (isAdd) groups.insert(g, appSession.getUserId());
            else       groups.update(g, appSession.getUserId());
            loadP2();
        } catch (Exception ex) {
            error("Could not save paygroup: " + ex.getMessage());
        }
    }

    private void deletePaygroup(PayrunGroup g) {
        if (g == null) return;
        if (groups.hasTimesheets(g.companyNo, g.payrunNo, g.paygroup)) {
            error("Paygroup " + g.paygroup
                + " has timesheet rows recorded and cannot be removed from this payrun.");
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "Remove paygroup " + g.paygroup + " from payrun " + g.payrunNo + "?",
            ButtonType.YES, ButtonType.NO);
        a.setHeaderText(null);
        a.initOwner(stage);
        var res = a.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.YES) {
            try {
                groups.delete(g.companyNo, g.payrunNo, g.paygroup);
                loadP2();
            } catch (Exception ex) {
                error("Could not delete paygroup: " + ex.getMessage());
            }
        }
    }

    private static int statusIndex(String code) {
        return switch (code == null ? "" : code.toUpperCase()) {
            case "C" -> 1; case "F" -> 2; default -> 0;
        };
    }
    private static String statusCode(int idx) {
        return switch (idx) { case 1 -> "C"; case 2 -> "F"; default -> "O"; };
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static <T> void addCol(TableView<T> tv, String label, double width,
                                    java.util.function.Function<T, javafx.beans.value.ObservableValue<String>> getter) {
        TableColumn<T, String> col = new TableColumn<>(label);
        col.setMinWidth(width);
        col.setCellValueFactory(d -> getter.apply(d.getValue()));
        tv.getColumns().add(col);
    }

    private static String fmt(LocalDate d) {
        if (d == null) return "";
        if (d.getYear() <= 1900) return "";   // COBOL date-zero sentinel
        return DDMMYY.format(d);
    }

    private static Separator sep() {
        Separator s = new Separator(javafx.geometry.Orientation.VERTICAL);
        s.setPrefHeight(20);
        return s;
    }

    private static Payrun copyOf(Payrun s) {
        Payrun p = new Payrun();
        p.companyNo            = s.companyNo;
        p.payrunNo             = s.payrunNo;
        p.authLevelNo          = s.authLevelNo;
        p.payrunDate           = s.payrunDate;
        p.payrunStatus         = s.payrunStatus;
        p.yrNo                 = s.yrNo;
        p.payrunType           = s.payrunType;
        p.calcsCompletedFlag   = s.calcsCompletedFlag;
        p.defaultCostType      = s.defaultCostType;
        p.defaultCalcTaxFlag   = s.defaultCalcTaxFlag;
        p.skipPaygroupOnAdd    = s.skipPaygroupOnAdd;
        p.skipPaygroupOnEdit   = s.skipPaygroupOnEdit;
        p.defltCalcSuperFlag   = s.defltCalcSuperFlag;
        p.retainerRunFlag      = s.retainerRunFlag;
        p.splitsRunFlag        = s.splitsRunFlag;
        p.createRdoFlag        = s.createRdoFlag;
        p.noOfEmployees        = s.noOfEmployees;
        p.noOfEmployeesPaid    = s.noOfEmployeesPaid;
        p.paymtDate            = s.paymtDate;
        p.paymtYrNo            = s.paymtYrNo;
        p.ref                  = s.ref;
        p.remitStatus          = s.remitStatus;
        p.startDate            = s.startDate;
        p.endDate              = s.endDate;
        p.payerAbn             = s.payerAbn;
        p.lastSuperSeqNo       = s.lastSuperSeqNo;
        p.superstreamOnly      = s.superstreamOnly;
        p.superPayMethod       = s.superPayMethod;
        p.noteNo               = s.noteNo;
        return p;
    }

    private static PayrunGroup copyOf(PayrunGroup s) {
        PayrunGroup g = new PayrunGroup();
        g.companyNo      = s.companyNo;
        g.payrunNo       = s.payrunNo;
        g.paygroup       = s.paygroup;
        g.payThruToMth   = s.payThruToMth;
        g.payThruTo4Wk   = s.payThruTo4Wk;
        g.payThruToBimth = s.payThruToBimth;
        g.payThruToFort  = s.payThruToFort;
        g.payThruToWeek  = s.payThruToWeek;
        g.paygroupStatus = s.paygroupStatus;
        g.noteNo         = s.noteNo;
        g.paygroupDesc   = s.paygroupDesc;
        return g;
    }

    private void info(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.initOwner(stage);
        a.showAndWait();
    }

    private void error(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.initOwner(stage);
        a.showAndWait();
    }
}
