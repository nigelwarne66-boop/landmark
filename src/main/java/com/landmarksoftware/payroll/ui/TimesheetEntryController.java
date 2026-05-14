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
import com.landmarksoftware.payroll.model.Employee;
import com.landmarksoftware.payroll.model.Paecode;
import com.landmarksoftware.payroll.model.PayGroup;
import com.landmarksoftware.payroll.model.Payrun;
import com.landmarksoftware.payroll.model.PayrunGroup;
import com.landmarksoftware.payroll.model.TimesheetHeader;
import com.landmarksoftware.payroll.model.TimesheetLine;
import com.landmarksoftware.payroll.service.EmployeeService;
import com.landmarksoftware.payroll.service.PaecodeService;
import com.landmarksoftware.payroll.service.PayGroupService;
import com.landmarksoftware.payroll.service.PayrunGroupService;
import com.landmarksoftware.payroll.service.PayrunService;
import com.landmarksoftware.payroll.service.TimesheetHeaderService;
import com.landmarksoftware.payroll.service.TimesheetLineService;

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

    private final PayrunService           payruns;
    private final PayrunGroupService      groups;
    private final PayGroupService         payGroupMaster;
    private final TimesheetHeaderService  timesheetHeaders;
    private final TimesheetLineService    timesheetLines;
    private final PaecodeService          paecodes;
    private final EmployeeService         employees;
    private final AppSession              appSession;

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
                                     TimesheetHeaderService timesheetHeaders,
                                     TimesheetLineService timesheetLines,
                                     PaecodeService paecodes,
                                     EmployeeService employees,
                                     AppSession appSession) {
        this.payruns          = payruns;
        this.groups           = groups;
        this.payGroupMaster   = payGroupMaster;
        this.timesheetHeaders = timesheetHeaders;
        this.timesheetLines   = timesheetLines;
        this.paecodes         = paecodes;
        this.employees        = employees;
        this.appSession       = appSession;
    }

    // ── P3 state ─────────────────────────────────────────────────────────
    private final ObservableList<TimesheetHeader> p3Rows = FXCollections.observableArrayList();
    private TableView<TimesheetHeader>            p3Table;
    private Label                                 p3Status;
    private PayrunGroup                           p3Paygroup;

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

        // Map paygroup code → description for the cell renderer.
        final java.util.Map<String, String> descByCode = master.stream().collect(
            java.util.stream.Collectors.toMap(
                pg -> pg.paygroup,
                pg -> pg.desc1 == null ? "" : pg.desc1,
                (a, b) -> a));
        ComboBox<String> cbStart = new ComboBox<>(codes);
        ComboBox<String> cbEnd   = new ComboBox<>(codes);
        javafx.util.Callback<ListView<String>, ListCell<String>> cellFactory =
            lv -> new ListCell<>() {
                @Override protected void updateItem(String code, boolean empty) {
                    super.updateItem(code, empty);
                    if (empty || code == null) { setText(null); return; }
                    String desc = descByCode.getOrDefault(code, "");
                    setText(desc.isBlank() ? code : code + "  —  " + desc);
                }
            };
        cbStart.setCellFactory(cellFactory);
        cbStart.setButtonCell(cellFactory.call(null));
        cbEnd.setCellFactory(cellFactory);
        cbEnd.setButtonCell(cellFactory.call(null));
        cbStart.setPrefWidth(280);
        cbEnd.setPrefWidth(280);
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

        Button bBack         = new Button("◂ Back");
        Button bSelect       = new Button("Select ▸ Timesheets");
        Button bAdd          = new Button("Add");
        Button bEdit         = new Button("Edit");
        Button bDelete       = new Button("Delete");
        Button bCreatePayrun = new Button("Create Payrun");
        Button bStatus       = new Button("Status");
        Button bRange        = new Button("Range…");
        Button bRefresh      = new Button("Refresh");

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
        bCreatePayrun.setOnAction(e -> createPayrunTimesheets(p2Payrun));
        bStatus.setOnAction(e -> inquirePaygroup(p2Table.getSelectionModel().getSelectedItem()));
        bRange.setOnAction(e -> openSelectPaygroups(p2Payrun));
        bRefresh.setOnAction(e -> loadP2());

        HBox toolbar = new HBox(8, bBack, sep(), bSelect, sep(),
                                   bAdd, bEdit, bDelete, sep(),
                                   bCreatePayrun, bStatus, sep(),
                                   bRange, bRefresh);
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
        showP3(g);
    }

    // ── P3 — employee / timesheet list ───────────────────────────────────

    /**
     * Open P3. Pass {@code null} to show every patimhd on the current payrun
     * (the "Create Payrun" / PATM02 flow); pass a {@link PayrunGroup} to
     * filter to one paygroup (the "Select ▸ Timesheets" flow on P2).
     */
    private void showP3(PayrunGroup pg) {
        p3Paygroup = pg;   // null = all paygroups for the payrun

        p3Table = new TableView<>(p3Rows);
        p3Table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        p3Table.setPlaceholder(new Label(
            "No timesheets recorded for this paygroup. Click Add to create one,\n"
            + "or Standing Lines to maintain the default paecode rows for an employee."));
        VBox.setVgrow(p3Table, Priority.ALWAYS);

        addCol(p3Table, "Surname",     180,
            h -> new SimpleStringProperty(h.surname));
        addCol(p3Table, "First Name",  140,
            h -> new SimpleStringProperty(h.firstName));
        addCol(p3Table, "Employee No",  90,
            h -> new SimpleStringProperty(String.valueOf(h.employeeNo)));
        addCol(p3Table, "Paygroup",     80,
            h -> new SimpleStringProperty(h.altPaygroup));
        addCol(p3Table, "Total Hours", 100,
            h -> new SimpleStringProperty(money(h.totalHours())));
        addCol(p3Table, "Gross",       110,
            h -> new SimpleStringProperty(money(h.grossPay())));
        addCol(p3Table, "Net",         110,
            h -> new SimpleStringProperty(money(h.netPay())));

        Button bBack     = new Button("◂ Back to P2");
        Button bAdd      = new Button("Add");
        Button bEdit     = new Button("Edit");
        Button bDelete   = new Button("Delete");
        Button bPayMtd   = new Button("Pay Method");
        Button bPrint    = new Button("Print");
        Button bSuper    = new Button("Super");
        Button bPaecode  = new Button("Standing Lines (paecode)");
        Button bRefresh  = new Button("Refresh");

        bBack.setOnAction(e -> {
            p3Rows.clear();
            openP2(p2Payrun);
        });
        bAdd.setOnAction(e -> openS3Add());
        bEdit.setOnAction(e -> {
            TimesheetHeader sel = p3Table.getSelectionModel().getSelectedItem();
            if (sel != null) openS3Edit(sel);
        });
        bDelete.setOnAction(e -> deleteTimesheet(p3Table.getSelectionModel().getSelectedItem()));
        bPayMtd.setOnAction(e -> info("Payment Method (P3A) — not yet built."));
        bPrint.setOnAction(e -> info("Print payrun — not yet built."));
        bSuper.setOnAction(e -> info("Create super for all — not yet built."));
        bPaecode.setOnAction(e -> openPaecodeEditorForSelection());
        bRefresh.setOnAction(e -> loadP3());

        HBox toolbar = new HBox(8, bBack, sep(), bAdd, bEdit, bDelete, sep(),
                                   bPayMtd, bPrint, bSuper, sep(),
                                   bPaecode, sep(), bRefresh);
        toolbar.setPadding(new Insets(10, 14, 10, 14));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle("-fx-background-color:#FFFFFF;");

        String pgLabel = pg == null
            ? "All paygroups"
            : "Paygroup " + pg.paygroup
                + (pg.paygroupDesc.isBlank() ? "" : " — " + pg.paygroupDesc);
        Label crumb = new Label("Payrun " + p2Payrun.payrunNo + " · "
            + fmt(p2Payrun.payrunDate) + " · " + pgLabel);
        crumb.setStyle("-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        HBox crumbBar = new HBox(crumb);
        crumbBar.setPadding(new Insets(10, 14, 6, 14));

        p3Status = new Label();
        p3Status.setPadding(new Insets(6, 14, 8, 14));
        p3Status.setStyle("-fx-font-size:11px;-fx-text-fill:#666;");

        VBox content = new VBox(crumbBar, toolbar, p3Table, p3Status);
        VBox.setVgrow(p3Table, Priority.ALWAYS);
        root.setCenter(content);
        loadP3();
    }

    private void loadP3() {
        String filter = p3Paygroup == null ? null : p3Paygroup.paygroup;
        List<TimesheetHeader> list = timesheetHeaders.findForPayrun(
            p2Payrun.companyNo, p2Payrun.payrunNo, filter);
        p3Rows.setAll(list);
        TimesheetHeaderService.Totals t = timesheetHeaders.rollupForPayrun(
            p2Payrun.companyNo, p2Payrun.payrunNo, filter);
        String scope = p3Paygroup == null ? "all paygroups"
            : "paygroup " + p3Paygroup.paygroup;
        p3Status.setText(t.count() + " timesheet" + (t.count() == 1 ? "" : "s")
            + " · " + scope
            + " · gross $" + money(t.gross()) + " · net $" + money(t.net()));
    }

    private void deleteTimesheet(TimesheetHeader h) {
        if (h == null) return;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete the timesheet for " + h.displayName() + " (employee "
                + h.employeeNo + ") on payrun " + h.payrunNo + "?\n\n"
                + "All patimes lines for this header will also be removed.",
            ButtonType.YES, ButtonType.NO);
        a.setHeaderText(null);
        a.initOwner(stage);
        var res = a.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.YES) {
            try {
                timesheetHeaders.delete(h.companyNo, h.payrunNo, h.employeeNo);
                loadP3();
            } catch (Exception ex) {
                error("Could not delete timesheet: " + ex.getMessage());
            }
        }
    }

    private void openPaecodeEditorForSelection() {
        TimesheetHeader sel = p3Table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            // No timesheet selected — let the user enter an employee number.
            askEmployeeNo().ifPresent(emp ->
                openPaecodeEditor(emp, lookupEmployeeName(emp)));
            return;
        }
        openPaecodeEditor(sel.employeeNo, sel.displayName());
    }

    private java.util.Optional<Integer> askEmployeeNo() {
        TextInputDialog t = new TextInputDialog();
        t.setTitle("Standing Pay Lines");
        t.setHeaderText("Enter the employee number whose paecode rows you want to maintain.");
        t.setContentText("Employee #:");
        t.initOwner(stage);
        return t.showAndWait().map(s -> {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return null; }
        }).filter(java.util.Objects::nonNull);
    }

    private String lookupEmployeeName(int employeeNo) {
        // patimhd caches the name; reuse if a row exists for any payrun.
        try {
            return java.util.Optional.ofNullable(timesheetHeaders.findOne(
                p2Payrun.companyNo, p2Payrun.payrunNo, employeeNo).orElse(null))
                .map(TimesheetHeader::displayName)
                .orElse("Employee " + employeeNo);
        } catch (Exception e) {
            return "Employee " + employeeNo;
        }
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

    /**
     * COBOL P2 "&Create Payrun" — misleadingly labelled. The actual COBOL
     * proc (CREATE-PAYRUN in patm01.pl) does NOT create a new parunhd; it
     * validates the payrun is still open and at least one parungr row
     * exists, then hands off to PATM02 — the timesheet builder (my P3).
     *
     * <p>Behaviour for the open payrun:
     * <ul>
     *   <li>If the payrun is posted (P or F) or cancelled (D) — refuse.</li>
     *   <li>If no parungr rows exist — "No paygroups selected".</li>
     *   <li>Otherwise — drill into P3 / timesheet creation. Stubbed for now.</li>
     * </ul>
     */
    private void createPayrunTimesheets(Payrun p) {
        if (p == null) return;
        if ("P".equalsIgnoreCase(p.payrunStatus) || "F".equalsIgnoreCase(p.payrunStatus)) {
            error("Payrun " + p.payrunNo + " has been posted — cannot create timesheets.");
            return;
        }
        if ("D".equalsIgnoreCase(p.payrunStatus)) {
            error("Payrun " + p.payrunNo + " has been cancelled — cannot create timesheets.");
            return;
        }
        List<PayrunGroup> attached = groups.findByPayrun(p.companyNo, p.payrunNo);
        if (attached.isEmpty()) {
            error("No paygroups selected for payrun " + p.payrunNo
                + ".\n\nUse Options → Select (or the Add button) to attach paygroups first.");
            return;
        }
        // COBOL CREATE-PAYRUN destroys P1+P2 windows then calls PATM02 which is
        // the all-paygroups timesheet builder. Mirror by opening P3 in
        // "all paygroups" mode (null filter) so the user can add timesheets
        // across every attached paygroup.
        showP3(null);
    }

    /**
     * COBOL P2 "Stat&us" — read-only inquiry on the selected paygroup row.
     * Surfaces audit attribution and pay-thru detail in a non-editable popup.
     */
    private void inquirePaygroup(PayrunGroup g) {
        if (g == null) return;
        StringBuilder sb = new StringBuilder()
            .append("Payrun : ").append(g.payrunNo).append("\n")
            .append("Paygroup : ").append(g.paygroup);
        if (!g.paygroupDesc.isBlank()) sb.append("  —  ").append(g.paygroupDesc);
        sb.append("\nStatus : ").append(g.statusDisplay()).append("\n\n")
          .append("Weekly pay-thru   : ").append(fmt(g.payThruToWeek)).append("\n")
          .append("Fortnight pay-thru: ").append(fmt(g.payThruToFort)).append("\n")
          .append("Bimthly pay-thru  : ").append(fmt(g.payThruToBimth)).append("\n")
          .append("4-weekly pay-thru : ").append(fmt(g.payThruTo4Wk)).append("\n")
          .append("Monthly pay-thru  : ").append(fmt(g.payThruToMth)).append("\n\n")
          .append("Last modified by ").append(g.auditUserId.isBlank() ? "—" : g.auditUserId);
        if (g.auditDate != null && g.auditDate.getYear() > 1900) {
            sb.append(" on ").append(fmt(g.auditDate))
              .append(" at ").append(String.format("%02d:%02d:%02d",
                  g.auditTimeHr, g.auditTimeMin, g.auditTimeSec));
        }
        Alert a = new Alert(Alert.AlertType.INFORMATION, sb.toString(), ButtonType.CLOSE);
        a.setHeaderText("Paygroup status");
        a.initOwner(stage);
        a.showAndWait();
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

    // ── S3 — Add / Edit timesheet header for an employee ────────────────

    private void openS3Add() {
        // Prompt for employee number, look up pastaff, then open S3.
        TextInputDialog t = new TextInputDialog();
        t.setTitle("Add Timesheet");
        t.setHeaderText("Add a timesheet for an employee on payrun " + p2Payrun.payrunNo);
        t.setContentText("Employee #:");
        t.initOwner(stage);
        var input = t.showAndWait();
        if (input.isEmpty()) return;
        int empNo;
        try { empNo = Integer.parseInt(input.get().trim()); }
        catch (NumberFormatException e) { error("Employee number must be numeric."); return; }

        Employee emp = employees.findOne(p2Payrun.companyNo, empNo).orElse(null);
        if (emp == null) {
            error("Employee " + empNo + " not found in pastaff.");
            return;
        }
        if (timesheetHeaders.findOne(p2Payrun.companyNo, p2Payrun.payrunNo, empNo).isPresent()) {
            error("Employee " + empNo + " already has a timesheet on this payrun. "
                + "Use Edit instead.");
            return;
        }

        TimesheetHeader h = new TimesheetHeader();
        h.companyNo        = p2Payrun.companyNo;
        h.payrunNo         = p2Payrun.payrunNo;
        h.employeeNo       = empNo;
        h.surname          = emp.surname;
        h.firstName        = emp.firstName;
        h.altPayrunNo      = p2Payrun.payrunNo;
        h.altPaygroup      = emp.paygroup;
        h.altDept          = emp.dept;
        h.altEmployeeNo    = empNo;
        h.payThruStartDate = p2Payrun.startDate;
        h.payThruToDate    = p2Payrun.endDate != null ? p2Payrun.endDate : p2Payrun.payrunDate;
        h.timesheetStatus  = "O";
        h.timesheetInUse   = "Y";
        h.defaultTimesheetFlag  = "Y";
        h.costedTimesheetFlag   = "N";
        h.calcTaxUsingPayDates  = "Y";

        if (!editTimesheetHeader(h, true)) return;

        // S3 returned OK and the header is saved. Offer to seed from paecode.
        List<Paecode> standing = paecodes.findByEmployee(h.companyNo, h.employeeNo);
        if (!standing.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Seed " + standing.size() + " standing pay line"
                    + (standing.size() == 1 ? "" : "s")
                    + " from paecode into this timesheet?",
                ButtonType.YES, ButtonType.NO);
            a.setHeaderText("Seed timesheet from standing lines");
            a.initOwner(stage);
            var ans = a.showAndWait();
            if (ans.isPresent() && ans.get() == ButtonType.YES) {
                int n = timesheetLines.seedFromPaecode(h.companyNo, h.payrunNo,
                    h.employeeNo, standing, appSession.getUserId());
                info(n + " line" + (n == 1 ? "" : "s") + " seeded.");
            }
        }

        // Drill into S3B so the user can edit lines straight away.
        openS3B(h);
        loadP3();
    }

    private void openS3Edit(TimesheetHeader source) {
        TimesheetHeader h = copyOfHeader(source);
        if (editTimesheetHeader(h, false)) {
            openS3B(h);
            loadP3();
        }
    }

    /**
     * S3 — Add/Edit Timesheet header dialog. Returns true if saved.
     * Captures the editable subset of patimhd (dates, status, flags); the
     * monetary totals come from S3B (per-line entry).
     */
    private boolean editTimesheetHeader(TimesheetHeader h, boolean isAdd) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(isAdd ? "Add Timesheet — Employee " + h.employeeNo
                            : "Edit Timesheet — Employee " + h.employeeNo);
        dlg.setHeaderText(h.displayName().isBlank()
            ? "Employee " + h.employeeNo
            : h.displayName() + "  ·  Employee " + h.employeeNo);
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);

        DatePicker dpStart   = new DatePicker(h.payThruStartDate);
        DatePicker dpEnd     = new DatePicker(h.payThruToDate);
        TextField  tfPaygrp  = new TextField(h.altPaygroup);
        TextField  tfDept    = new TextField(h.altDept);
        CheckBox   cbDefault = new CheckBox("Default timesheet (seed from paecode)");
        cbDefault.setSelected("Y".equalsIgnoreCase(h.defaultTimesheetFlag));
        CheckBox   cbCosted  = new CheckBox("Costed timesheet");
        cbCosted.setSelected("Y".equalsIgnoreCase(h.costedTimesheetFlag));
        CheckBox   cbCalcTax = new CheckBox("Calc tax using pay dates");
        cbCalcTax.setSelected("Y".equalsIgnoreCase(h.calcTaxUsingPayDates));
        ComboBox<String> cbStatus = new ComboBox<>(FXCollections.observableArrayList(
            "Open (O)", "Closed (C)", "Posted (P)"));
        cbStatus.getSelectionModel().select(tsStatusIndex(h.timesheetStatus));

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(14));
        int r = 0;
        g.add(new Label("Period start:"), 0, r); g.add(dpStart,   1, r++);
        g.add(new Label("Period end:"),   0, r); g.add(dpEnd,     1, r++);
        g.add(new Label("Paygroup:"),     0, r); g.add(tfPaygrp,  1, r++);
        g.add(new Label("Department:"),   0, r); g.add(tfDept,    1, r++);
        g.add(new Label("Status:"),       0, r); g.add(cbStatus,  1, r++);
        g.add(cbDefault, 1, r++);
        g.add(cbCosted,  1, r++);
        g.add(cbCalcTax, 1, r++);
        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return false;

        h.payThruStartDate     = dpStart.getValue();
        h.payThruToDate        = dpEnd.getValue();
        h.altPaygroup          = nz(tfPaygrp.getText()).trim().toUpperCase();
        h.altDept              = nz(tfDept.getText()).trim().toUpperCase();
        h.defaultTimesheetFlag = cbDefault.isSelected() ? "Y" : "N";
        h.costedTimesheetFlag  = cbCosted.isSelected()  ? "Y" : "N";
        h.calcTaxUsingPayDates = cbCalcTax.isSelected() ? "Y" : "N";
        h.timesheetStatus      = tsStatusCode(cbStatus.getSelectionModel().getSelectedIndex());

        try {
            if (isAdd) timesheetHeaders.insert(h, appSession.getUserId());
            // Edit-only: header totals are recomputed in PAPP01, so we don't
            // update them here — only header flags / dates change via S3.
            return true;
        } catch (Exception ex) {
            error("Could not save timesheet header: " + ex.getMessage());
            return false;
        }
    }

    private static int tsStatusIndex(String code) {
        return switch (code == null ? "" : code.toUpperCase()) {
            case "C" -> 1; case "P" -> 2; default -> 0;
        };
    }
    private static String tsStatusCode(int idx) {
        return switch (idx) { case 1 -> "C"; case 2 -> "P"; default -> "O"; };
    }

    // ── S3B — per-line patimes editor ───────────────────────────────────

    /**
     * Modal listbox of patimes lines for a single patimhd, with Add / Edit /
     * Delete. Mirrors PATM01 S3B in COBOL. Totals on the parent patimhd are
     * NOT recomputed here — that's PAPP01's job.
     */
    private void openS3B(TimesheetHeader h) {
        Stage dlg = new Stage();
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Timesheet Lines — Employee " + h.employeeNo
            + " · Payrun " + h.payrunNo);

        ObservableList<TimesheetLine> rows = FXCollections.observableArrayList();
        TableView<TimesheetLine> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label(
            "No timesheet lines. Click Add or seed from paecode via the header dialog."));
        addCol(table, "Line", 50,
            r -> new SimpleStringProperty(String.valueOf(r.lineNo)));
        addCol(table, "Pay Type", 70,
            r -> new SimpleStringProperty(String.valueOf(r.payType)));
        addCol(table, "Pay Code", 90,
            r -> new SimpleStringProperty(r.payCode));
        addCol(table, "Paygroup", 80,
            r -> new SimpleStringProperty(r.paygroup));
        addCol(table, "Dept", 70,
            r -> new SimpleStringProperty(r.dept));
        addCol(table, "Hours", 70,
            r -> new SimpleStringProperty(money(r.hours())));
        addCol(table, "Qty", 70,
            r -> new SimpleStringProperty(money(r.qty)));
        addCol(table, "Rate", 80,
            r -> new SimpleStringProperty(money(r.ratePerc)));
        addCol(table, "Ext Amt", 90,
            r -> new SimpleStringProperty(money(r.extAmt)));
        addCol(table, "Ref", 200,
            r -> new SimpleStringProperty(r.ref));
        VBox.setVgrow(table, Priority.ALWAYS);

        Runnable reload = () ->
            rows.setAll(timesheetLines.findByHeader(h.companyNo, h.payrunNo, h.employeeNo));

        Button bAdd    = new Button("Add");
        Button bEdit   = new Button("Edit");
        Button bDelete = new Button("Delete");
        Button bClose  = new Button("Close");

        bAdd.setOnAction(e -> {
            TimesheetLine seed = new TimesheetLine();
            seed.companyNo  = h.companyNo;
            seed.payrunNo   = h.payrunNo;
            seed.employeeNo = h.employeeNo;
            seed.lineNo     = timesheetLines.nextLineNo(h.companyNo, h.payrunNo, h.employeeNo);
            seed.timesheetDate = h.payThruToDate;
            seed.paygroup   = h.altPaygroup;
            seed.dept       = h.altDept;
            if (editTimesheetLine(seed, dlg, true)) reload.run();
        });
        bEdit.setOnAction(e -> {
            TimesheetLine sel = table.getSelectionModel().getSelectedItem();
            if (sel != null && editTimesheetLine(copyOfLine(sel), dlg, false)) reload.run();
        });
        bDelete.setOnAction(e -> {
            TimesheetLine sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete line " + sel.lineNo + " ("
                    + (sel.payCode.isBlank() ? "type " + sel.payType : sel.payCode)
                    + ")?",
                ButtonType.YES, ButtonType.NO);
            a.setHeaderText(null);
            a.initOwner(dlg);
            var res = a.showAndWait();
            if (res.isPresent() && res.get() == ButtonType.YES) {
                try {
                    timesheetLines.delete(sel.companyNo, sel.payrunNo,
                        sel.employeeNo, sel.lineNo);
                    reload.run();
                } catch (Exception ex) {
                    error("Could not delete: " + ex.getMessage());
                }
            }
        });
        bClose.setOnAction(e -> dlg.close());

        HBox toolbar = new HBox(8, bAdd, bEdit, bDelete);
        toolbar.setPadding(new Insets(10, 14, 10, 14));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox closeBar = new HBox(bClose);
        closeBar.setPadding(new Insets(8, 14, 14, 14));
        closeBar.setAlignment(Pos.CENTER_RIGHT);

        Label crumb = new Label(h.displayName().isBlank()
            ? "Employee " + h.employeeNo + "  ·  Payrun " + h.payrunNo
            : h.displayName() + "  ·  Employee " + h.employeeNo
              + "  ·  Payrun " + h.payrunNo);
        crumb.setStyle("-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        HBox crumbBar = new HBox(crumb);
        crumbBar.setPadding(new Insets(12, 14, 6, 14));

        VBox content = new VBox(crumbBar, toolbar, table, closeBar);
        VBox.setVgrow(table, Priority.ALWAYS);
        dlg.setScene(new Scene(content, 1000, 480));
        reload.run();
        dlg.showAndWait();
    }

    /** Add/Edit dialog for a single patimes row. Returns true if saved. */
    private boolean editTimesheetLine(TimesheetLine l, Stage owner, boolean isAdd) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(isAdd ? "Add Timesheet Line"
                            : "Edit Timesheet Line " + l.lineNo);
        dlg.setHeaderText("Employee " + l.employeeNo
            + " · payrun " + l.payrunNo + " · line " + l.lineNo);
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);

        TextField tfPayType  = new TextField(String.valueOf(l.payType));
        TextField tfPayCode  = new TextField(l.payCode);
        TextField tfPaygrp   = new TextField(l.paygroup);
        TextField tfDept     = new TextField(l.dept);
        TextField tfAward    = new TextField(l.award);
        TextField tfJobClass = new TextField(l.jobClass);
        TextField tfHours    = new TextField(money(l.hours()));
        TextField tfQty      = new TextField(money(l.qty));
        TextField tfRate     = new TextField(money(l.ratePerc));
        TextField tfExt      = new TextField(money(l.extAmt));
        DatePicker dpDate    = new DatePicker(l.timesheetDate);
        TextField tfRef      = new TextField(l.ref);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(14));
        int r = 0;
        g.add(new Label("Pay type:"),   0, r); g.add(tfPayType,  1, r);
        g.add(new Label("Pay code:"),   2, r); g.add(tfPayCode,  3, r++);
        g.add(new Label("Paygroup:"),   0, r); g.add(tfPaygrp,   1, r);
        g.add(new Label("Dept:"),       2, r); g.add(tfDept,     3, r++);
        g.add(new Label("Award:"),      0, r); g.add(tfAward,    1, r);
        g.add(new Label("Job class:"),  2, r); g.add(tfJobClass, 3, r++);
        g.add(new Label("Hours:"),      0, r); g.add(tfHours,    1, r);
        g.add(new Label("Quantity:"),   2, r); g.add(tfQty,      3, r++);
        g.add(new Label("Rate / %:"),   0, r); g.add(tfRate,     1, r);
        g.add(new Label("Ext amount:"), 2, r); g.add(tfExt,      3, r++);
        g.add(new Label("Date:"),       0, r); g.add(dpDate,     1, r++);
        g.add(new Label("Reference:"),  0, r); g.add(tfRef,      1, r, 3, 1);
        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return false;

        try {
            l.payType        = parseIntOrZero(tfPayType.getText());
            l.payCode        = nz(tfPayCode.getText()).trim().toUpperCase();
            l.paygroup       = nz(tfPaygrp.getText()).trim().toUpperCase();
            l.dept           = nz(tfDept.getText()).trim().toUpperCase();
            l.award          = nz(tfAward.getText()).trim().toUpperCase();
            l.jobClass       = nz(tfJobClass.getText()).trim().toUpperCase();
            l.min            = hoursToMin(tfHours.getText());
            l.qty            = parseMoneyOrZero(tfQty.getText());
            l.ratePerc       = parseMoneyOrZero(tfRate.getText());
            l.extAmt         = parseMoneyOrZero(tfExt.getText());
            l.timesheetDate  = dpDate.getValue();
            l.ref            = nz(tfRef.getText());

            if (isAdd) timesheetLines.insert(l, appSession.getUserId());
            else       timesheetLines.update(l, appSession.getUserId());
            return true;
        } catch (Exception ex) {
            error("Could not save line: " + ex.getMessage());
            return false;
        }
    }

    private static TimesheetHeader copyOfHeader(TimesheetHeader s) {
        TimesheetHeader h = new TimesheetHeader();
        h.companyNo            = s.companyNo;
        h.payrunNo             = s.payrunNo;
        h.employeeNo           = s.employeeNo;
        h.surname              = s.surname;
        h.firstName            = s.firstName;
        h.altPayrunNo          = s.altPayrunNo;
        h.altPaygroup          = s.altPaygroup;
        h.altDept              = s.altDept;
        h.altEmployeeNo        = s.altEmployeeNo;
        h.payThruStartDate     = s.payThruStartDate;
        h.payThruToDate        = s.payThruToDate;
        h.defaultTimesheetFlag = s.defaultTimesheetFlag;
        h.costedTimesheetFlag  = s.costedTimesheetFlag;
        h.calcTaxUsingPayDates = s.calcTaxUsingPayDates;
        h.timesheetStatus      = s.timesheetStatus;
        h.timesheetInUse       = s.timesheetInUse;
        h.noteNo               = s.noteNo;
        return h;
    }

    private static TimesheetLine copyOfLine(TimesheetLine s) {
        TimesheetLine l = new TimesheetLine();
        l.companyNo            = s.companyNo;
        l.payrunNo             = s.payrunNo;
        l.employeeNo           = s.employeeNo;
        l.lineNo               = s.lineNo;
        l.payType              = s.payType;
        l.payCode              = s.payCode;
        l.min                  = s.min;
        l.qty                  = s.qty;
        l.ratePerc             = s.ratePerc;
        l.extAmt               = s.extAmt;
        l.paygroup             = s.paygroup;
        l.dept                 = s.dept;
        l.award                = s.award;
        l.jobClass             = s.jobClass;
        l.employeeDept         = s.employeeDept;
        l.costType             = s.costType;
        l.timesheetDate        = s.timesheetDate;
        l.ref                  = s.ref;
        l.leaveStartDate       = s.leaveStartDate;
        l.leaveReturnDate      = s.leaveReturnDate;
        l.reasonCode           = s.reasonCode;
        l.glAcctNoMain         = s.glAcctNoMain;
        l.glAcctNoSub          = s.glAcctNoSub;
        l.ledgerType           = s.ledgerType;
        l.ledgerCode           = s.ledgerCode;
        l.analysisCode         = s.analysisCode;
        l.absorpType           = s.absorpType;
        l.absorpAmt            = s.absorpAmt;
        l.gstFlag              = s.gstFlag;
        l.gstCode              = s.gstCode;
        l.noteNo               = s.noteNo;
        return l;
    }

    // ── paecode CRUD — standing pay lines for one employee ──────────────

    /**
     * Open a modal listbox of paecode rows for {@code employeeNo}. The user
     * can Add / Edit / Delete standing lines; every change goes through
     * {@link PaecodeService} which writes to {@code papcaud} as well.
     */
    private void openPaecodeEditor(int employeeNo, String employeeName) {
        Stage dlg = new Stage();
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Standing Pay Lines — Employee " + employeeNo);

        ObservableList<Paecode> rows = FXCollections.observableArrayList();
        TableView<Paecode> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label(
            "No standing pay lines for this employee. Click Add to create the first one."));
        addCol(table, "Line", 50,
            r -> new SimpleStringProperty(String.valueOf(r.lineNo)));
        addCol(table, "Pay Type", 70,
            r -> new SimpleStringProperty(String.valueOf(r.payType)));
        addCol(table, "Pay Code", 90,
            r -> new SimpleStringProperty(r.payCode));
        addCol(table, "Paygroup", 80,
            r -> new SimpleStringProperty(r.paygroup));
        addCol(table, "Dept", 70,
            r -> new SimpleStringProperty(r.dept));
        addCol(table, "Hours", 70,
            r -> new SimpleStringProperty(money(r.hours())));
        addCol(table, "Qty", 70,
            r -> new SimpleStringProperty(money(r.qty)));
        addCol(table, "Rate", 80,
            r -> new SimpleStringProperty(money(r.ratePerc)));
        addCol(table, "Ext Amt", 90,
            r -> new SimpleStringProperty(money(r.extAmt)));
        addCol(table, "Ref", 200,
            r -> new SimpleStringProperty(r.ref));
        VBox.setVgrow(table, Priority.ALWAYS);

        Runnable reload = () ->
            rows.setAll(paecodes.findByEmployee(appSession.getCompanyNo(), employeeNo));

        Button bAdd    = new Button("Add");
        Button bEdit   = new Button("Edit");
        Button bDelete = new Button("Delete");
        Button bClose  = new Button("Close");

        bAdd.setOnAction(e -> {
            Paecode seed = new Paecode();
            seed.companyNo = appSession.getCompanyNo();
            seed.employeeNo = employeeNo;
            seed.lineNo = paecodes.nextLineNo(appSession.getCompanyNo(), employeeNo);
            seed.startDate = LocalDate.now();
            seed.endDate   = LocalDate.of(9999, 12, 31);
            if (editPaecodeRow(seed, dlg, true)) reload.run();
        });
        bEdit.setOnAction(e -> {
            Paecode sel = table.getSelectionModel().getSelectedItem();
            if (sel != null && editPaecodeRow(copyOf(sel), dlg, false)) reload.run();
        });
        bDelete.setOnAction(e -> {
            Paecode sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete standing pay line " + sel.lineNo + " ("
                    + (sel.payCode.isBlank() ? "type " + sel.payType : sel.payCode)
                    + ") for employee " + employeeNo + "?",
                ButtonType.YES, ButtonType.NO);
            a.setHeaderText(null);
            a.initOwner(dlg);
            var res = a.showAndWait();
            if (res.isPresent() && res.get() == ButtonType.YES) {
                try {
                    paecodes.delete(sel.companyNo, sel.employeeNo, sel.lineNo,
                        appSession.getUserId());
                    reload.run();
                } catch (Exception ex) {
                    error("Could not delete: " + ex.getMessage());
                }
            }
        });
        bClose.setOnAction(e -> dlg.close());

        HBox toolbar = new HBox(8, bAdd, bEdit, bDelete);
        toolbar.setPadding(new Insets(10, 14, 10, 14));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox closeBar = new HBox(bClose);
        closeBar.setPadding(new Insets(8, 14, 14, 14));
        closeBar.setAlignment(Pos.CENTER_RIGHT);

        Label crumb = new Label("Employee " + employeeNo
            + (employeeName == null || employeeName.isBlank() ? "" : " — " + employeeName));
        crumb.setStyle("-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        HBox crumbBar = new HBox(crumb);
        crumbBar.setPadding(new Insets(12, 14, 6, 14));

        VBox content = new VBox(crumbBar, toolbar, table, closeBar);
        VBox.setVgrow(table, Priority.ALWAYS);
        dlg.setScene(new Scene(content, 1000, 480));
        reload.run();
        dlg.showAndWait();
    }

    /** Add/Edit dialog for a single paecode row. Returns true if saved. */
    private boolean editPaecodeRow(Paecode p, Stage owner, boolean isAdd) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(isAdd ? "Add Standing Line"
                            : "Edit Standing Line " + p.lineNo);
        dlg.setHeaderText("Employee " + p.employeeNo
            + " · line " + p.lineNo);
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);

        TextField   tfPayType    = new TextField(String.valueOf(p.payType));
        TextField   tfPayCode    = new TextField(p.payCode);
        TextField   tfPaygroup   = new TextField(p.paygroup);
        TextField   tfDept       = new TextField(p.dept);
        TextField   tfAward      = new TextField(p.award);
        TextField   tfJobClass   = new TextField(p.jobClass);
        TextField   tfHours      = new TextField(money(p.hours()));
        TextField   tfQty        = new TextField(money(p.qty));
        TextField   tfRate       = new TextField(money(p.ratePerc));
        TextField   tfExt        = new TextField(money(p.extAmt));
        DatePicker  dpStart      = new DatePicker(p.startDate);
        DatePicker  dpEnd        = new DatePicker(p.endDate);
        TextField   tfRef        = new TextField(p.ref);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(14));
        int r = 0;
        g.add(new Label("Pay type:"),   0, r); g.add(tfPayType,  1, r);
        g.add(new Label("Pay code:"),   2, r); g.add(tfPayCode,  3, r++);
        g.add(new Label("Paygroup:"),   0, r); g.add(tfPaygroup, 1, r);
        g.add(new Label("Dept:"),       2, r); g.add(tfDept,     3, r++);
        g.add(new Label("Award:"),      0, r); g.add(tfAward,    1, r);
        g.add(new Label("Job class:"),  2, r); g.add(tfJobClass, 3, r++);
        g.add(new Label("Hours:"),      0, r); g.add(tfHours,    1, r);
        g.add(new Label("Quantity:"),   2, r); g.add(tfQty,      3, r++);
        g.add(new Label("Rate / %:"),   0, r); g.add(tfRate,     1, r);
        g.add(new Label("Ext amount:"), 2, r); g.add(tfExt,      3, r++);
        g.add(new Label("Start date:"), 0, r); g.add(dpStart,    1, r);
        g.add(new Label("End date:"),   2, r); g.add(dpEnd,      3, r++);
        g.add(new Label("Reference:"),  0, r); g.add(tfRef,      1, r, 3, 1);

        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return false;

        try {
            p.payType   = parseIntOrZero(tfPayType.getText());
            p.payCode   = nz(tfPayCode.getText()).trim().toUpperCase();
            p.paygroup  = nz(tfPaygroup.getText()).trim().toUpperCase();
            p.dept      = nz(tfDept.getText()).trim().toUpperCase();
            p.award     = nz(tfAward.getText()).trim().toUpperCase();
            p.jobClass  = nz(tfJobClass.getText()).trim().toUpperCase();
            p.min       = hoursToMin(tfHours.getText());
            p.qty       = parseMoneyOrZero(tfQty.getText());
            p.ratePerc  = parseMoneyOrZero(tfRate.getText());
            p.extAmt    = parseMoneyOrZero(tfExt.getText());
            p.startDate = dpStart.getValue();
            p.endDate   = dpEnd.getValue();
            p.ref       = nz(tfRef.getText());

            if (isAdd) paecodes.insert(p, appSession.getUserId());
            else       paecodes.update(p, appSession.getUserId());
            return true;
        } catch (Exception ex) {
            error("Could not save standing line: " + ex.getMessage());
            return false;
        }
    }

    private static int parseIntOrZero(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }

    private static java.math.BigDecimal parseMoneyOrZero(String s) {
        if (s == null) return java.math.BigDecimal.ZERO;
        String t = s.trim().replace(",", "");
        if (t.isEmpty()) return java.math.BigDecimal.ZERO;
        try { return new java.math.BigDecimal(t); }
        catch (Exception e) { return java.math.BigDecimal.ZERO; }
    }

    private static int hoursToMin(String s) {
        java.math.BigDecimal h = parseMoneyOrZero(s);
        return h.multiply(new java.math.BigDecimal("60"))
            .setScale(0, java.math.RoundingMode.HALF_UP).intValue();
    }

    private static String money(java.math.BigDecimal v) {
        if (v == null) return "0.00";
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static Paecode copyOf(Paecode s) {
        Paecode p = new Paecode();
        p.companyNo            = s.companyNo;
        p.alt2PayCode          = s.alt2PayCode;
        p.employeeNo           = s.employeeNo;
        p.lineNo               = s.lineNo;
        p.alt1EmployeeNo       = s.alt1EmployeeNo;
        p.alt1PayCode          = s.alt1PayCode;
        p.alt1LineNo           = s.alt1LineNo;
        p.payFreq              = s.payFreq;
        p.paysSinceLastPaid    = s.paysSinceLastPaid;
        p.stdPayCodeFlag       = s.stdPayCodeFlag;
        p.startDate            = s.startDate;
        p.endDate              = s.endDate;
        p.lastPaidDate         = s.lastPaidDate;
        p.superMemberNo        = s.superMemberNo;
        p.payType              = s.payType;
        p.payCode              = s.payCode;
        p.min                  = s.min;
        p.qty                  = s.qty;
        p.ratePerc             = s.ratePerc;
        p.extAmt               = s.extAmt;
        p.paygroup             = s.paygroup;
        p.dept                 = s.dept;
        p.award                = s.award;
        p.jobClass             = s.jobClass;
        p.costType             = s.costType;
        p.glAcctNoMain         = s.glAcctNoMain;
        p.glAcctNoSub          = s.glAcctNoSub;
        p.ledgerType           = s.ledgerType;
        p.ledgerCode           = s.ledgerCode;
        p.analysisCode         = s.analysisCode;
        p.absorpType           = s.absorpType;
        p.absorpFactor         = s.absorpFactor;
        p.absorpAmt            = s.absorpAmt;
        p.ref                  = s.ref;
        p.leaveStartDate       = s.leaveStartDate;
        p.leaveReturnDate      = s.leaveReturnDate;
        p.fbtGrossValue        = s.fbtGrossValue;
        p.baLedgerId           = s.baLedgerId;
        p.baPrimaryCodes       = s.baPrimaryCodes;
        p.baDesc               = s.baDesc;
        p.baBillText           = s.baBillText;
        p.gstValue             = s.gstValue;
        p.baGlOverrideFlag     = s.baGlOverrideFlag;
        p.baEditBillDataFlag   = s.baEditBillDataFlag;
        p.noteNo               = s.noteNo;
        return p;
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
