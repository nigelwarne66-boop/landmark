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
import com.landmarksoftware.payroll.model.Payrun;
import com.landmarksoftware.payroll.model.PayrunGroup;
import com.landmarksoftware.payroll.service.PayrunGroupService;
import com.landmarksoftware.payroll.service.PayrunService;

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

    public TimesheetEntryController(PayrunService payruns,
                                     PayrunGroupService groups,
                                     AppSession appSession) {
        this.payruns    = payruns;
        this.groups     = groups;
        this.appSession = appSession;
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
                    openP2(row.getItem());
                }
            });
            return row;
        });

        Button bFilter = new Button("Filter…");
        Button bOpen   = new Button("Open ▸");
        Button bAdd    = new Button("Add");
        Button bEdit   = new Button("Edit");
        Button bCancel = new Button("Cancel");
        Button bRefresh = new Button("Refresh");

        bFilter.setOnAction(e -> { if (showS0(true)) loadP1(); });
        bOpen.setOnAction(e -> {
            Payrun sel = p1Table.getSelectionModel().getSelectedItem();
            if (sel != null) openP2(sel);
        });
        bAdd.setOnAction(e -> editPayrun(null));
        bEdit.setOnAction(e -> {
            Payrun sel = p1Table.getSelectionModel().getSelectedItem();
            if (sel != null) editPayrun(sel);
        });
        bCancel.setOnAction(e -> cancelPayrun(p1Table.getSelectionModel().getSelectedItem()));
        bRefresh.setOnAction(e -> loadP1());

        HBox toolbar = new HBox(8, bFilter, sep(), bOpen, bAdd, bEdit, bCancel, sep(), bRefresh);
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

    private void editPayrun(Payrun source) {
        boolean isAdd = (source == null);
        Payrun p = isAdd ? new Payrun() : copyOf(source);
        if (isAdd) {
            p.companyNo  = appSession.getCompanyNo();
            p.payrunNo   = payruns.nextPayrunNo(appSession.getCompanyNo());
            p.payrunDate = LocalDate.now();
            p.yrNo       = appSession.getYrNo();
            p.payrunType = "P";
            p.payrunStatus = "O";
            p.startDate  = LocalDate.now();
            p.endDate    = LocalDate.now();
            p.paymtDate  = LocalDate.now();
            p.paymtYrNo  = appSession.getYearNo();
        }

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
        if (res.isEmpty() || res.get() != ButtonType.OK) return;

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
        } catch (Exception ex) {
            error("Could not save payrun: " + ex.getMessage());
        }
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
        Button bRefresh = new Button("Refresh");

        bBack.setOnAction(e -> {
            appSession.clearPayrun();
            showP1();
        });
        bSelect.setOnAction(e -> selectPaygroup(p2Table.getSelectionModel().getSelectedItem()));
        bAdd.setOnAction(e -> editPaygroup(null));
        bEdit.setOnAction(e -> editPaygroup(p2Table.getSelectionModel().getSelectedItem()));
        bDelete.setOnAction(e -> deletePaygroup(p2Table.getSelectionModel().getSelectedItem()));
        bRefresh.setOnAction(e -> loadP2());

        HBox toolbar = new HBox(8, bBack, sep(), bSelect, sep(),
                                   bAdd, bEdit, bDelete, sep(), bRefresh);
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
        List<PayrunGroup> list = groups.findByPayrun(p2Payrun.companyNo, p2Payrun.payrunNo);
        p2Rows.setAll(list);
        long open   = list.stream().filter(g -> "O".equalsIgnoreCase(g.paygroupStatus)).count();
        long closed = list.size() - open;
        p2Status.setText(list.size() + " paygroup" + (list.size() == 1 ? "" : "s")
            + " · " + open + " open · " + closed + " closed/full");
    }

    private void selectPaygroup(PayrunGroup g) {
        if (g == null) return;
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
