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
import com.landmarksoftware.payroll.model.Award;
import com.landmarksoftware.payroll.model.AwardJobClass;
import com.landmarksoftware.payroll.model.AwardWcomp;
import com.landmarksoftware.payroll.model.Employee;
import com.landmarksoftware.payroll.service.AwardJobClassService;
import com.landmarksoftware.payroll.service.AwardService;
import com.landmarksoftware.payroll.service.AwardWcompService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * PAAW01 — Award Maintenance.
 *
 * <p>Three tables, three drill levels:
 * <ul>
 *   <li><b>P1 list</b> → paawhed (award headers)</li>
 *   <li><b>S1 modal — Award</b> with two drill-in tabs:
 *     <ul>
 *       <li><b>Job Classes</b> → paawjob (80+ columns, 7-sub-tab editor)</li>
 *       <li><b>WC / On-Costs</b> → paawwcp (rate per paygroup)</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Drill rows save immediately on Add/Edit/Delete — independent of the
 * outer Award Save/Cancel, same convention as PAEM01 bank splits and
 * PAPG01 departments.
 *
 * <p>Deletion cascades — deleting an award removes its job classes and
 * WC rows (in {@link AwardService#delete}); deleting a job class removes
 * its WC rows.
 */
@Component
public class AwardMaintenanceController {

    private final AwardService          awardService;
    private final AwardJobClassService  jobService;
    private final AwardWcompService     wcompService;
    private final AppSession            appSession;

    private final ObservableList<Award> rows = FXCollections.observableArrayList();
    private TableView<Award> table;
    private Label            lblStatus;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "paaw01-thread");
        t.setDaemon(true);
        return t;
    });

    public AwardMaintenanceController(AwardService awardService,
                                       AwardJobClassService jobService,
                                       AwardWcompService wcompService,
                                       AppSession appSession) {
        this.awardService = awardService;
        this.jobService   = jobService;
        this.wcompService = wcompService;
        this.appSession   = appSession;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scene + P1 listbox
    // ═══════════════════════════════════════════════════════════════════

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
        Label title = new Label("Award Maintenance — PAAW01");
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
        table.setPlaceholder(new Label("No awards defined for this company."));

        table.getColumns().addAll(List.of(
            col("Award",       a -> a.awardCode, 80),
            col("Description", a -> a.desc1,    420),
            col("Job Classes", a -> String.valueOf(awardService.countJobClasses(
                                       appSession.getCompanyNo(), a.awardCode)), 110)
        ));

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null)
                openAwardDialog(table.getSelectionModel().getSelectedItem(), stage);
        });

        Button btnAdd  = btnPrimary("+ Add");
        Button btnEdit = btnSecondary("✎ Edit");
        Button btnDel  = btnDanger("🗑 Delete");
        Button btnRef  = btnSecondary("↺");

        btnAdd.setOnAction(e -> openAwardDialog(null, stage));
        btnEdit.setOnAction(e -> {
            Award sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openAwardDialog(sel, stage);
            else showInfo("Edit", "Select an award to edit.");
        });
        btnDel.setOnAction(e -> {
            Award sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDeleteAward(sel);
            else showInfo("Delete", "Select an award to delete.");
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

    private void loadList() {
        int coNo = appSession.getCompanyNo();
        status("Loading…", false);
        exec.submit(() -> {
            try {
                List<Award> data = awardService.findAll(coNo);
                Platform.runLater(() -> {
                    rows.setAll(data);
                    status(data.size() + " award(s)", false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    private void confirmDeleteAward(Award sel) {
        int coNo = appSession.getCompanyNo();
        exec.submit(() -> {
            int inUse = 0;
            try { inUse = awardService.countAttachedEmployees(coNo, sel.awardCode); }
            catch (Exception ex) {
                final String err = ex.getMessage();
                Platform.runLater(() -> status("Delete check error: " + err, true));
                return;
            }
            final int n = inUse;
            Platform.runLater(() -> {
                if (n > 0) {
                    showInfo("Delete blocked",
                        "Award '" + sel.awardCode + "' is attached to " + n +
                        " active employee(s).\nReassign or terminate them before deleting.");
                    return;
                }
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete award " + sel.awardCode + " — " + sel.desc1 +
                    "?\n\nThis cascades to all job classes and WC rows under this award.",
                    ButtonType.OK, ButtonType.CANCEL);
                a.setTitle("Delete award"); a.setHeaderText(null);
                a.showAndWait().ifPresent(bt -> {
                    if (bt != ButtonType.OK) return;
                    exec.submit(() -> {
                        try {
                            awardService.delete(coNo, sel.awardCode);
                            Platform.runLater(() -> {
                                status("Deleted: " + sel.awardCode, false);
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

    // ═══════════════════════════════════════════════════════════════════
    // S1 — Award edit dialog (header + drill tabs)
    // ═══════════════════════════════════════════════════════════════════

    private void openAwardDialog(Award existing, Window owner) {
        boolean isAdd = (existing == null);
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(isAdd ? "Add Award — PAAW01" : "Edit Award — PAAW01");
        dlg.setResizable(false);

        Award a = isAdd ? new Award() : existing;
        if (isAdd) a.companyNo = appSession.getCompanyNo();

        // ── General tab ─────────────────────────────────────────────────
        TextField fCode = tf(a.awardCode, 3);
        fCode.setEditable(isAdd);
        fCode.setDisable(!isAdd);
        TextField fDesc = tf(a.desc1, 35);

        GridPane gGen = formGrid();
        int r = 0;
        addFormRow(gGen, r++, "Award Code *:",   fCode);
        addFormRow(gGen, r++, "Description *:",  fDesc);

        // ── Job Classes tab ─────────────────────────────────────────────
        Node gJobs = buildJobClassesTab(dlg, isAdd ? "" : a.awardCode, isAdd);

        // ── WC / On-Costs tab ───────────────────────────────────────────
        Node gWcomp = buildWcompTab(dlg, isAdd ? "" : a.awardCode, isAdd);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
            tab("General",     gGen),
            tab("Job Classes", gJobs),
            tab("WC / On-Costs", gWcomp));

        Button btnSave   = btnPrimary(isAdd ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(ev -> dlg.close());

        btnSave.setOnAction(ev -> {
            String code = fCode.getText().trim().toUpperCase();
            if (isAdd) {
                if (code.isEmpty()) {
                    markError(fCode, "Award code is required.");
                    tabs.getSelectionModel().select(0); return;
                }
                if (code.length() > 3) {
                    markError(fCode, "Award code must be 3 characters or fewer.");
                    tabs.getSelectionModel().select(0); return;
                }
            }
            if (fDesc.getText().trim().isEmpty()) {
                markError(fDesc, "Description is required.");
                tabs.getSelectionModel().select(0); return;
            }

            Award out = new Award();
            out.companyNo = appSession.getCompanyNo();
            out.awardCode = isAdd ? code : a.awardCode;
            out.desc1     = fDesc.getText().trim();
            out.noteNo    = a.noteNo;

            String userId = appSession.getUserId();
            exec.submit(() -> {
                try {
                    if (isAdd) {
                        if (awardService.exists(out.companyNo, out.awardCode)) {
                            Platform.runLater(() ->
                                status("Award '" + out.awardCode + "' already exists.", true));
                            return;
                        }
                        awardService.insert(out, userId);
                    } else {
                        awardService.update(out, userId);
                    }
                    Platform.runLater(() -> {
                        status((isAdd ? "Added: " : "Updated: ") + out.awardCode, false);
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
            isAdd ? "New Award" : "Edit Award " + a.awardCode));
        top.setPadding(new Insets(14, 20, 8, 20));

        VBox root = new VBox(0, top, tabs, btnBar);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        dlg.setScene(new Scene(root, 760, 540));
        dlg.showAndWait();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Job Classes drill (paawjob)
    // ═══════════════════════════════════════════════════════════════════

    private Node buildJobClassesTab(Window owner, String awardCode, boolean isAdd) {
        TableView<AwardJobClass> table = new TableView<>();
        ObservableList<AwardJobClass> data = FXCollections.observableArrayList();
        table.setItems(data);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label(isAdd
            ? "Save the award first, then add job classes here."
            : "No job classes — Add to create one."));

        table.getColumns().add(jobCol("Code",        j -> j.jobClassCode, 90));
        table.getColumns().add(jobCol("Description", j -> j.desc1, 240));
        table.getColumns().add(jobCol("Std Hrs/wk",  j -> Employee.minutesAsHours(j.stdHrs), 100));
        table.getColumns().add(jobCol("Rate /hr",    j -> decStr(j.ratePerHr), 100));
        table.getColumns().add(jobCol("Rate /wk",    j -> decStr(j.ratePerWeek), 100));

        final int coNo = appSession.getCompanyNo();
        Runnable reload = () -> {
            if (awardCode.isEmpty()) { data.clear(); return; }
            try {
                List<AwardJobClass> rows = jobService.findByAward(coNo, awardCode);
                Platform.runLater(() -> data.setAll(rows));
            } catch (Exception ex) {
                Platform.runLater(() -> status("Job class load error: " + ex.getMessage(), true));
            }
        };
        if (!isAdd) exec.submit(reload);

        Button btnAdd  = btnSecondary("+ Add");
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

        btnAdd.setOnAction(ev -> openJobClassDialog(owner, null, awardCode, reload));
        btnEdit.setOnAction(ev -> {
            AwardJobClass sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openJobClassDialog(owner, sel, awardCode, reload);
        });
        btnDel.setOnAction(ev -> {
            AwardJobClass sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDeleteJobClass(sel, reload);
        });
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                AwardJobClass sel = table.getSelectionModel().getSelectedItem();
                if (sel != null) openJobClassDialog(owner, sel, awardCode, reload);
            }
        });

        HBox bar = new HBox(8, btnAdd, btnEdit, btnDel);
        bar.setPadding(new Insets(10, 12, 10, 12));
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(0, bar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    private TableColumn<AwardJobClass, String> jobCol(String header,
                                                       Function<AwardJobClass, String> fn,
                                                       double w) {
        TableColumn<AwardJobClass, String> c = new TableColumn<>(header);
        c.setCellValueFactory(p -> new SimpleStringProperty(safe(fn.apply(p.getValue()))));
        c.setPrefWidth(w);
        return c;
    }

    private void confirmDeleteJobClass(AwardJobClass sel, Runnable reload) {
        int coNo = appSession.getCompanyNo();
        exec.submit(() -> {
            int inUse = 0;
            try { inUse = jobService.countAttachedEmployees(coNo, sel.jobClassCode); }
            catch (Exception ex) {
                final String err = ex.getMessage();
                Platform.runLater(() -> status("Delete check error: " + err, true));
                return;
            }
            final int n = inUse;
            Platform.runLater(() -> {
                if (n > 0) {
                    showInfo("Delete blocked",
                        "Job class '" + sel.jobClassCode + "' is attached to " + n +
                        " active employee(s).\nReassign or terminate them before deleting.");
                    return;
                }
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete job class " + sel.jobClassCode + " — " + sel.desc1 +
                    "?\n\nWC rows for this job class will also be removed.",
                    ButtonType.OK, ButtonType.CANCEL);
                a.setTitle("Delete job class"); a.setHeaderText(null);
                a.showAndWait().ifPresent(bt -> {
                    if (bt != ButtonType.OK) return;
                    exec.submit(() -> {
                        try {
                            jobService.delete(sel.companyNo, sel.awardCode, sel.jobClassCode);
                            Platform.runLater(() -> {
                                status("Deleted job class " + sel.jobClassCode, false);
                                reload.run();
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

    // ═══════════════════════════════════════════════════════════════════
    // S2 — Job Class editor (80+ fields, multi-tab)
    // ═══════════════════════════════════════════════════════════════════

    private void openJobClassDialog(Window owner, AwardJobClass existing,
                                     String awardCode, Runnable reload) {
        boolean isAddJob = (existing == null);
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isAddJob ? "Add" : "Edit") + " Job Class — PAAW01 S2");
        dlg.setResizable(false);

        AwardJobClass j = isAddJob ? new AwardJobClass() : existing;
        if (isAddJob) {
            j.companyNo      = appSession.getCompanyNo();
            j.awardCode      = awardCode;
            j.lslCalcMethod  = "H";
            j.percOrAmtFlag  = "%";
            j.hrsOrAmtFlag   = "H";
        }

        // ─── Identity & Rates ──────────────────────────────────────────
        TextField fJobCode = tf(j.jobClassCode, 6);
        fJobCode.setEditable(isAddJob);
        fJobCode.setDisable(!isAddJob);
        TextField fDesc    = tf(j.desc1, 35);
        TextField fStdHrs  = tf(j.stdHrs == 0 ? "" : Employee.minutesAsHours(j.stdHrs), 8);
        TextField fRateHr  = tf(decStr(j.ratePerHr), 10);
        TextField fRateWk  = tf(decStr(j.ratePerWeek), 10);
        TextField fAnnual  = tf(decStr(j.annualAmt), 12);

        GridPane gId = formGrid();
        int r = 0;
        addFormRow(gId, r++, "Award:",        new Label(awardCode));
        addFormRow(gId, r++, "Job Code *:",   fJobCode);
        addFormRow(gId, r++, "Description *:", fDesc);
        addFormRow(gId, r++, "Std Hours/wk:", fStdHrs);
        addFormRow(gId, r++, "Rate /hr:",     fRateHr);
        addFormRow(gId, r++, "Rate /week:",   fRateWk);
        addFormRow(gId, r++, "Annual Salary:", fAnnual);

        // ─── LSL ──────────────────────────────────────────────────────
        TextField fLslYr        = intField(j.lslStartYr);
        TextField fLslHrs       = tf(j.lslHrs == 0 ? "" : Employee.minutesAsHours(j.lslHrs), 10);
        ChoiceBox<String> cbLslCalc = new ChoiceBox<>();
        cbLslCalc.getItems().addAll("H — Hours", "W — Weeks");
        cbLslCalc.getSelectionModel().select("W".equals(j.lslCalcMethod) ? 1 : 0);
        cbLslCalc.setPrefWidth(140);
        TextField fLslWeeks     = tf(decStr(j.lslWeeks), 8);
        CheckBox  cbLslLump     = yn("Include lump sums", j.lslIncLumpInd);
        CheckBox  cbLslDateInd  = yn("Use day/month anniversary", j.lslDateInd);
        TextField fLslDay       = intField(j.lslDateDay);
        TextField fLslMonth     = intField(j.lslDateMonth);
        TextField fLslCasYr     = intField(j.lslCasStartYr);
        TextField fLslCasWks    = tf(decStr(j.lslCasWksPerYr), 8);
        TextField fLslCasAve1   = intField(j.lslCasAveWks1);
        TextField fLslCasAve2   = intField(j.lslCasAveWks2);

        GridPane gLsl = formGrid();
        r = 0;
        addFormRow(gLsl, r++, "Start Year:",       fLslYr);
        addFormRow(gLsl, r++, "LSL Hours:",        fLslHrs);
        addFormRow(gLsl, r++, "Calc Method:",      cbLslCalc);
        addFormRow(gLsl, r++, "LSL Weeks:",        fLslWeeks);
        addFormRow(gLsl, r++, "",                  cbLslLump);
        addFormRow(gLsl, r++, "",                  cbLslDateInd);
        addFormRow(gLsl, r++, "Anniversary Day:",  fLslDay);
        addFormRow(gLsl, r++, "Anniversary Month:", fLslMonth);
        addFormRow(gLsl, r++, "Casual Start Year:", fLslCasYr);
        addFormRow(gLsl, r++, "Casual Wks/Year:",  fLslCasWks);
        addFormRow(gLsl, r++, "Casual Ave Wks 1:", fLslCasAve1);
        addFormRow(gLsl, r++, "Casual Ave Wks 2:", fLslCasAve2);

        // ─── Annual Leave + AL Loading ────────────────────────────────
        TextField fAlHrs       = tf(j.alHrs == 0 ? "" : Employee.minutesAsHours(j.alHrs), 10);
        TextField fAlAfter     = intField(j.alAfterMths);
        CheckBox  cbAlLump     = yn("Include lump sums", j.alIncLumpInd);
        CheckBox  cbAlDateInd  = yn("Use day/month anniversary", j.alDateInd);
        TextField fAlDay       = intField(j.alDateDay);
        TextField fAlMonth     = intField(j.alDateMonth);

        TextField fAllPerc     = tf(decStr(j.allPerc), 8);
        TextField fAllMax      = tf(j.allAccrualMax == 0 ? "" : Employee.minutesAsHours(j.allAccrualMax), 10);
        TextField fAllHrs      = tf(j.allHrs == 0 ? "" : Employee.minutesAsHours(j.allHrs), 10);
        TextField fAllAfter    = intField(j.allAfterMths);
        CheckBox  cbAllLump    = yn("Include lump sums", j.allIncLumpInd);
        CheckBox  cbAllDateInd = yn("Use day/month anniversary", j.allDateInd);
        TextField fAllDay      = intField(j.allDateDay);
        TextField fAllMonth    = intField(j.allDateMonth);

        GridPane gAl = formGrid();
        r = 0;
        addFormRow(gAl, r++, "AL Hours/yr:",      fAlHrs);
        addFormRow(gAl, r++, "Eligible After (mths):", fAlAfter);
        addFormRow(gAl, r++, "",                  cbAlLump);
        addFormRow(gAl, r++, "",                  cbAlDateInd);
        addFormRow(gAl, r++, "Anniversary Day:",  fAlDay);
        addFormRow(gAl, r++, "Anniversary Month:", fAlMonth);
        Label spacer = new Label("AL Loading");
        spacer.setStyle("-fx-font-weight:bold;-fx-text-fill:#374151;");
        gAl.add(spacer, 0, r++, 2, 1);
        addFormRow(gAl, r++, "Loading %:",        fAllPerc);
        addFormRow(gAl, r++, "Accrual Max (hrs):", fAllMax);
        addFormRow(gAl, r++, "Loading Hrs:",      fAllHrs);
        addFormRow(gAl, r++, "Loading After (mths):", fAllAfter);
        addFormRow(gAl, r++, "",                  cbAllLump);
        addFormRow(gAl, r++, "",                  cbAllDateInd);
        addFormRow(gAl, r++, "Anniversary Day:",  fAllDay);
        addFormRow(gAl, r++, "Anniversary Month:", fAllMonth);

        // ─── Sick Leave (3 tiers) ─────────────────────────────────────
        TextField[][] sickFields = new TextField[3][6]; // 3 tiers × 6 fields
        for (int i = 0; i < 3; i++) {
            sickFields[i][0] = tf(getSickHrs(j, i) == 0 ? "" : Employee.minutesAsHours(getSickHrs(j, i)), 8);
            sickFields[i][1] = intField(getSickAfter(j, i));
            sickFields[i][2] = intField(getSickDay(j, i));
            sickFields[i][3] = intField(getSickMonth(j, i));
        }
        CheckBox[] cbSickLump    = new CheckBox[3];
        CheckBox[] cbSickDateInd = new CheckBox[3];
        for (int i = 0; i < 3; i++) {
            cbSickLump[i]    = yn("Include lump sums",        getSickLump(j, i));
            cbSickDateInd[i] = yn("Use day/month anniversary", getSickDateInd(j, i));
        }
        TextField fSickMax = tf(j.sickAccrualMax == 0 ? "" : Employee.minutesAsHours(j.sickAccrualMax), 10);

        GridPane gSick = formGrid();
        r = 0;
        for (int i = 0; i < 3; i++) {
            Label tier = new Label("Tier " + (i + 1));
            tier.setStyle("-fx-font-weight:bold;-fx-text-fill:#1A6EF5;");
            gSick.add(tier, 0, r++, 2, 1);
            addFormRow(gSick, r++, "Sick Hours:",     sickFields[i][0]);
            addFormRow(gSick, r++, "After (mths):",   sickFields[i][1]);
            addFormRow(gSick, r++, "",                cbSickLump[i]);
            addFormRow(gSick, r++, "",                cbSickDateInd[i]);
            addFormRow(gSick, r++, "Anniversary Day:", sickFields[i][2]);
            addFormRow(gSick, r++, "Anniversary Month:", sickFields[i][3]);
        }
        addFormRow(gSick, r++, "Accrual Max (hrs):", fSickMax);

        // ─── Accrual / RDO ────────────────────────────────────────────
        TextField fAccCode   = tf(j.accrualPayCode, 6);
        TextField fPaidHrs   = intField(j.paidHrsPerDay);
        TextField fAccMins   = intField(j.accrualMinsPerDay);
        TextField fMinMins   = intField(j.minimumAccrualMins);
        TextField fRdoMax    = intField(j.rdoAccrualMax);

        GridPane gAcc = formGrid();
        r = 0;
        addFormRow(gAcc, r++, "Accrual Pay Code:",   fAccCode);
        addFormRow(gAcc, r++, "Paid Hrs/Day:",       fPaidHrs);
        addFormRow(gAcc, r++, "Accrual Mins/Day:",   fAccMins);
        addFormRow(gAcc, r++, "Min Accrual Mins:",   fMinMins);
        addFormRow(gAcc, r++, "RDO Accrual Max:",    fRdoMax);

        // ─── Super ────────────────────────────────────────────────────
        DatePicker dpSupComm   = new DatePicker(
            Employee.isValidDate(j.superCommenceDate) ? j.superCommenceDate : null);
        TextField  fQualifyDay = intField(j.qualifyDays);
        TextField  fMinHrs     = intField(j.minHrs);
        TextField  fMinAmt     = tf(decStr(j.minAmt), 10);
        ChoiceBox<String> cbPercAmt = new ChoiceBox<>();
        cbPercAmt.getItems().addAll("% — Percent", "$ — Amount");
        cbPercAmt.getSelectionModel().select("$".equals(j.percOrAmtFlag) ? 1 : 0);
        ChoiceBox<String> cbHrsAmt = new ChoiceBox<>();
        cbHrsAmt.getItems().addAll("H — Hours basis", "$ — Amount basis");
        cbHrsAmt.getSelectionModel().select("$".equals(j.hrsOrAmtFlag) ? 1 : 0);

        TextField fB1From = tf(decStr(j.band1FromAmt), 10);
        TextField fB1To   = tf(decStr(j.band1ToAmt), 10);
        TextField fB1Val  = tf(decStr(j.band1Value), 10);
        TextField fB2From = tf(decStr(j.band2FromAmt), 10);
        TextField fB2To   = tf(decStr(j.band2ToAmt), 10);
        TextField fB2Val  = tf(decStr(j.band2Value), 10);
        TextField fB3From = tf(decStr(j.band3FromAmt), 10);
        TextField fB3To   = tf(decStr(j.band3ToAmt), 10);
        TextField fB3Val  = tf(decStr(j.band3Value), 10);

        CheckBox  cbVolSup  = yn("Voluntary super enabled", j.volSuperFlag);
        TextField fTotalVol = tf(decStr(j.totalVolValue), 10);
        TextField fAddnVol  = tf(decStr(j.addnVolValue), 10);
        TextField fVest     = tf(j.vestSuperCode, 6);
        TextField fNonvest  = tf(j.nonvestSuperCode, 6);

        GridPane gSup = formGrid();
        r = 0;
        addFormRow(gSup, r++, "Commence Date:",    dpSupComm);
        addFormRow(gSup, r++, "Qualifying Days:",  fQualifyDay);
        addFormRow(gSup, r++, "Min Hours:",        fMinHrs);
        addFormRow(gSup, r++, "Min Amount:",       fMinAmt);
        addFormRow(gSup, r++, "%/$ Flag:",         cbPercAmt);
        addFormRow(gSup, r++, "Hrs/$ Basis:",      cbHrsAmt);
        for (int i = 0; i < 3; i++) {
            int row = r;
            Label band = new Label("Band " + (i + 1));
            band.setStyle("-fx-font-weight:bold;-fx-text-fill:#1A6EF5;");
            gSup.add(band, 0, row, 2, 1);
            r++;
            TextField from = (i == 0 ? fB1From : i == 1 ? fB2From : fB3From);
            TextField to   = (i == 0 ? fB1To   : i == 1 ? fB2To   : fB3To);
            TextField val  = (i == 0 ? fB1Val  : i == 1 ? fB2Val  : fB3Val);
            addFormRow(gSup, r++, "From Amt:",  from);
            addFormRow(gSup, r++, "To Amt:",    to);
            addFormRow(gSup, r++, "Value:",     val);
        }
        addFormRow(gSup, r++, "",                  cbVolSup);
        addFormRow(gSup, r++, "Total Vol Value:",  fTotalVol);
        addFormRow(gSup, r++, "Addn Vol Value:",   fAddnVol);
        addFormRow(gSup, r++, "Vest Super Code:",  fVest);
        addFormRow(gSup, r++, "Nonvest Super Code:", fNonvest);

        // ─── Misc ─────────────────────────────────────────────────────
        TextField fU18 = intField(j.minHrsUnder18);
        TextField fMax = intField(j.maxHrs);
        TextArea  fOther = new TextArea(j.otherData);
        fOther.setPrefRowCount(4);
        fOther.setWrapText(true);

        GridPane gMisc = formGrid();
        r = 0;
        addFormRow(gMisc, r++, "Min Hrs Under-18:", fU18);
        addFormRow(gMisc, r++, "Max Hours:",        fMax);
        addFormRow(gMisc, r++, "Other Data:",       fOther);

        // ─── Tabs ─────────────────────────────────────────────────────
        TabPane subTabs = new TabPane();
        subTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        subTabs.getTabs().addAll(
            tab("Identity & Rates", gId),
            tab("LSL",              gLsl),
            tab("AL & Loading",     gAl),
            tab("Sick (3 tiers)",   gSick),
            tab("Accrual / RDO",    gAcc),
            tab("Super",            gSup),
            tab("Misc",             gMisc));

        Button btnSave   = btnPrimary(isAddJob ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(ev -> dlg.close());

        btnSave.setOnAction(ev -> {
            String code = fJobCode.getText().trim().toUpperCase();
            if (isAddJob) {
                if (code.isEmpty()) {
                    markError(fJobCode, "Job code is required.");
                    subTabs.getSelectionModel().select(0); return;
                }
                if (code.length() > 6) {
                    markError(fJobCode, "Job code must be 6 characters or fewer.");
                    subTabs.getSelectionModel().select(0); return;
                }
            }
            if (fDesc.getText().trim().isEmpty()) {
                markError(fDesc, "Description is required.");
                subTabs.getSelectionModel().select(0); return;
            }

            // Decimals
            BigDecimal rateHr  = parseDec(fRateHr, "Rate /hr");      if (rateHr  == null) { subTabs.getSelectionModel().select(0); return; }
            BigDecimal rateWk  = parseDec(fRateWk, "Rate /week");    if (rateWk  == null) { subTabs.getSelectionModel().select(0); return; }
            BigDecimal annual  = parseDec(fAnnual, "Annual Salary"); if (annual  == null) { subTabs.getSelectionModel().select(0); return; }
            BigDecimal lslWks  = parseDec(fLslWeeks, "LSL Weeks");   if (lslWks  == null) { subTabs.getSelectionModel().select(1); return; }
            BigDecimal lslCasW = parseDec(fLslCasWks, "Casual Wks/Year"); if (lslCasW == null) { subTabs.getSelectionModel().select(1); return; }
            BigDecimal allPerc = parseDec(fAllPerc, "Loading %");    if (allPerc == null) { subTabs.getSelectionModel().select(2); return; }
            BigDecimal minAmt  = parseDec(fMinAmt, "Min Amount");    if (minAmt  == null) { subTabs.getSelectionModel().select(5); return; }
            BigDecimal b1f = parseDec(fB1From, "Band 1 From"); if (b1f == null) { subTabs.getSelectionModel().select(5); return; }
            BigDecimal b1t = parseDec(fB1To,   "Band 1 To");   if (b1t == null) { subTabs.getSelectionModel().select(5); return; }
            BigDecimal b1v = parseDec(fB1Val,  "Band 1 Value"); if (b1v == null) { subTabs.getSelectionModel().select(5); return; }
            BigDecimal b2f = parseDec(fB2From, "Band 2 From"); if (b2f == null) { subTabs.getSelectionModel().select(5); return; }
            BigDecimal b2t = parseDec(fB2To,   "Band 2 To");   if (b2t == null) { subTabs.getSelectionModel().select(5); return; }
            BigDecimal b2v = parseDec(fB2Val,  "Band 2 Value"); if (b2v == null) { subTabs.getSelectionModel().select(5); return; }
            BigDecimal b3f = parseDec(fB3From, "Band 3 From"); if (b3f == null) { subTabs.getSelectionModel().select(5); return; }
            BigDecimal b3t = parseDec(fB3To,   "Band 3 To");   if (b3t == null) { subTabs.getSelectionModel().select(5); return; }
            BigDecimal b3v = parseDec(fB3Val,  "Band 3 Value"); if (b3v == null) { subTabs.getSelectionModel().select(5); return; }
            BigDecimal totVol  = parseDec(fTotalVol, "Total Vol"); if (totVol  == null) { subTabs.getSelectionModel().select(5); return; }
            BigDecimal addnVol = parseDec(fAddnVol,  "Addn Vol");  if (addnVol == null) { subTabs.getSelectionModel().select(5); return; }

            AwardJobClass out = new AwardJobClass();
            out.companyNo          = appSession.getCompanyNo();
            out.awardCode          = awardCode;
            out.jobClassCode       = isAddJob ? code : j.jobClassCode;
            out.desc1              = fDesc.getText().trim();
            out.stdHrs             = parseHoursToMinutes(fStdHrs.getText());
            out.ratePerHr          = rateHr;
            out.ratePerWeek        = rateWk;
            out.annualAmt          = annual;
            out.lslStartYr         = parseInt(fLslYr);
            out.lslHrs             = parseHoursToMinutes(fLslHrs.getText());
            out.lslCalcMethod      = firstChar(cbLslCalc.getValue(), "H");
            out.lslWeeks           = lslWks;
            out.lslIncLumpInd      = ynStr(cbLslLump);
            out.lslDateInd         = ynStr(cbLslDateInd);
            out.lslDateDay         = parseInt(fLslDay);
            out.lslDateMonth       = parseInt(fLslMonth);
            out.lslCasStartYr      = parseInt(fLslCasYr);
            out.lslCasWksPerYr     = lslCasW;
            out.lslCasAveWks1      = parseInt(fLslCasAve1);
            out.lslCasAveWks2      = parseInt(fLslCasAve2);
            out.alHrs              = parseHoursToMinutes(fAlHrs.getText());
            out.alAfterMths        = parseInt(fAlAfter);
            out.alIncLumpInd       = ynStr(cbAlLump);
            out.alDateInd          = ynStr(cbAlDateInd);
            out.alDateDay          = parseInt(fAlDay);
            out.alDateMonth        = parseInt(fAlMonth);
            out.allPerc            = allPerc;
            out.allAccrualMax      = parseHoursToMinutes(fAllMax.getText());
            out.allHrs             = parseHoursToMinutes(fAllHrs.getText());
            out.allAfterMths       = parseInt(fAllAfter);
            out.allIncLumpInd      = ynStr(cbAllLump);
            out.allDateInd         = ynStr(cbAllDateInd);
            out.allDateDay         = parseInt(fAllDay);
            out.allDateMonth       = parseInt(fAllMonth);
            out.sickHrs1           = parseHoursToMinutes(sickFields[0][0].getText());
            out.sickHrs2           = parseHoursToMinutes(sickFields[1][0].getText());
            out.sickHrs3           = parseHoursToMinutes(sickFields[2][0].getText());
            out.sickAfterMths1     = parseInt(sickFields[0][1]);
            out.sickAfterMths2     = parseInt(sickFields[1][1]);
            out.sickAfterMths3     = parseInt(sickFields[2][1]);
            out.sickIncLumpInd1    = ynStr(cbSickLump[0]);
            out.sickIncLumpInd2    = ynStr(cbSickLump[1]);
            out.sickIncLumpInd3    = ynStr(cbSickLump[2]);
            out.sickDateInd1       = ynStr(cbSickDateInd[0]);
            out.sickDateInd2       = ynStr(cbSickDateInd[1]);
            out.sickDateInd3       = ynStr(cbSickDateInd[2]);
            out.sickDateDay1       = parseInt(sickFields[0][2]);
            out.sickDateDay2       = parseInt(sickFields[1][2]);
            out.sickDateDay3       = parseInt(sickFields[2][2]);
            out.sickDateMonth1     = parseInt(sickFields[0][3]);
            out.sickDateMonth2     = parseInt(sickFields[1][3]);
            out.sickDateMonth3     = parseInt(sickFields[2][3]);
            out.sickAccrualMax     = parseHoursToMinutes(fSickMax.getText());
            out.accrualPayCode     = fAccCode.getText().trim().toUpperCase();
            out.paidHrsPerDay      = parseInt(fPaidHrs);
            out.accrualMinsPerDay  = parseInt(fAccMins);
            out.minimumAccrualMins = parseInt(fMinMins);
            out.rdoAccrualMax      = parseInt(fRdoMax);
            out.superCommenceDate  = dpSupComm.getValue();
            out.qualifyDays        = parseInt(fQualifyDay);
            out.minHrs             = parseInt(fMinHrs);
            out.minAmt             = minAmt;
            out.percOrAmtFlag      = firstChar(cbPercAmt.getValue(), "%");
            out.hrsOrAmtFlag       = firstChar(cbHrsAmt.getValue(),  "H");
            out.band1FromAmt = b1f; out.band1ToAmt = b1t; out.band1Value = b1v;
            out.band2FromAmt = b2f; out.band2ToAmt = b2t; out.band2Value = b2v;
            out.band3FromAmt = b3f; out.band3ToAmt = b3t; out.band3Value = b3v;
            out.volSuperFlag       = ynStr(cbVolSup);
            out.totalVolValue      = totVol;
            out.addnVolValue       = addnVol;
            out.vestSuperCode      = fVest.getText().trim().toUpperCase();
            out.nonvestSuperCode   = fNonvest.getText().trim().toUpperCase();
            out.minHrsUnder18      = parseInt(fU18);
            out.maxHrs             = parseInt(fMax);
            out.otherData          = fOther.getText();
            out.noteNo             = j.noteNo;

            String userId = appSession.getUserId();
            exec.submit(() -> {
                try {
                    if (isAddJob) {
                        if (jobService.exists(out.companyNo, out.awardCode, out.jobClassCode)) {
                            Platform.runLater(() ->
                                status("Job class '" + out.jobClassCode +
                                       "' already exists in award " + out.awardCode + ".", true));
                            return;
                        }
                        jobService.insert(out, userId);
                    } else {
                        jobService.update(out, userId);
                    }
                    Platform.runLater(() -> {
                        status((isAddJob ? "Added" : "Updated") +
                            " job class " + out.jobClassCode + ".", false);
                        dlg.close();
                        reload.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() ->
                        status("Job class save error: " + ex.getMessage(), true));
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
            (isAddJob ? "Add" : "Edit") +
            " job class " + (isAddJob ? "" : j.jobClassCode) +
            " — award " + awardCode));
        top.setPadding(new Insets(14, 20, 8, 20));

        VBox root = new VBox(0, top, subTabs, bar);
        VBox.setVgrow(subTabs, Priority.ALWAYS);
        dlg.setScene(new Scene(root, 640, 600));
        dlg.showAndWait();
    }

    // Sick-leave field accessors — index 0/1/2 → tiers 1/2/3
    private static int    getSickHrs(AwardJobClass j, int i)     { return i == 0 ? j.sickHrs1 : i == 1 ? j.sickHrs2 : j.sickHrs3; }
    private static int    getSickAfter(AwardJobClass j, int i)   { return i == 0 ? j.sickAfterMths1 : i == 1 ? j.sickAfterMths2 : j.sickAfterMths3; }
    private static String getSickLump(AwardJobClass j, int i)    { return i == 0 ? j.sickIncLumpInd1 : i == 1 ? j.sickIncLumpInd2 : j.sickIncLumpInd3; }
    private static String getSickDateInd(AwardJobClass j, int i) { return i == 0 ? j.sickDateInd1 : i == 1 ? j.sickDateInd2 : j.sickDateInd3; }
    private static int    getSickDay(AwardJobClass j, int i)     { return i == 0 ? j.sickDateDay1 : i == 1 ? j.sickDateDay2 : j.sickDateDay3; }
    private static int    getSickMonth(AwardJobClass j, int i)   { return i == 0 ? j.sickDateMonth1 : i == 1 ? j.sickDateMonth2 : j.sickDateMonth3; }

    // ═══════════════════════════════════════════════════════════════════
    // S3 — WC / On-Costs drill (paawwcp)
    // ═══════════════════════════════════════════════════════════════════

    private Node buildWcompTab(Window owner, String awardCode, boolean isAdd) {
        TableView<AwardWcomp> table = new TableView<>();
        ObservableList<AwardWcomp> data = FXCollections.observableArrayList();
        table.setItems(data);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label(isAdd
            ? "Save the award first, then add WC rows here."
            : "No WC / on-costs rows — Add to create one."));

        table.getColumns().add(wcCol("Job Class",  w -> w.jobClassCode, 110));
        table.getColumns().add(wcCol("Pay Group",  w -> w.paygroup, 110));
        table.getColumns().add(wcCol("WComp %",    w -> decStr(w.wcompPerc), 110));
        table.getColumns().add(wcCol("On-Costs %", w -> decStr(w.onCostsPerc), 110));

        final int coNo = appSession.getCompanyNo();
        Runnable reload = () -> {
            if (awardCode.isEmpty()) { data.clear(); return; }
            try {
                List<AwardWcomp> rows = wcompService.findByAward(coNo, awardCode);
                Platform.runLater(() -> data.setAll(rows));
            } catch (Exception ex) {
                Platform.runLater(() -> status("WC load error: " + ex.getMessage(), true));
            }
        };
        if (!isAdd) exec.submit(reload);

        Button btnAdd  = btnSecondary("+ Add");
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

        btnAdd.setOnAction(ev -> openWcompDialog(owner, null, awardCode, reload));
        btnEdit.setOnAction(ev -> {
            AwardWcomp sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openWcompDialog(owner, sel, awardCode, reload);
        });
        btnDel.setOnAction(ev -> {
            AwardWcomp sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDeleteWcomp(sel, reload);
        });
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                AwardWcomp sel = table.getSelectionModel().getSelectedItem();
                if (sel != null) openWcompDialog(owner, sel, awardCode, reload);
            }
        });

        HBox bar = new HBox(8, btnAdd, btnEdit, btnDel);
        bar.setPadding(new Insets(10, 12, 10, 12));
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(0, bar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    private TableColumn<AwardWcomp, String> wcCol(String header,
                                                    Function<AwardWcomp, String> fn,
                                                    double w) {
        TableColumn<AwardWcomp, String> c = new TableColumn<>(header);
        c.setCellValueFactory(p -> new SimpleStringProperty(safe(fn.apply(p.getValue()))));
        c.setPrefWidth(w);
        return c;
    }

    private void openWcompDialog(Window owner, AwardWcomp existing,
                                   String awardCode, Runnable reload) {
        boolean isAddWc = (existing == null);
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isAddWc ? "Add" : "Edit") + " WC Rate — PAAW01 S3");
        dlg.setResizable(false);

        final int coNo = appSession.getCompanyNo();
        AwardWcomp w = isAddWc ? new AwardWcomp() : existing;
        if (isAddWc) {
            w.companyNo = coNo;
            w.awardCode = awardCode;
        }

        TextField fJob   = tf(w.jobClassCode, 6);
        TextField fGroup = tf(w.paygroup, 4);
        fJob.setEditable(isAddWc);
        fJob.setDisable(!isAddWc);
        fGroup.setEditable(isAddWc);
        fGroup.setDisable(!isAddWc);
        TextField fWcomp = tf(decStr(w.wcompPerc), 10);
        TextField fOnC   = tf(decStr(w.onCostsPerc), 10);

        GridPane g = formGrid();
        int r = 0;
        addFormRow(g, r++, "Award:",         new Label(awardCode));
        addFormRow(g, r++, "Job Class *:",   fJob);
        addFormRow(g, r++, "Pay Group *:",   fGroup);
        addFormRow(g, r++, "Workers Comp %:", fWcomp);
        addFormRow(g, r++, "On-Costs %:",     fOnC);

        Button btnSave   = btnPrimary(isAddWc ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(ev -> dlg.close());

        btnSave.setOnAction(ev -> {
            String jobCode = fJob.getText().trim().toUpperCase();
            String pg      = fGroup.getText().trim().toUpperCase();
            if (isAddWc) {
                if (jobCode.isEmpty()) { markError(fJob, "Job class is required.");  return; }
                if (pg.isEmpty())      { markError(fGroup, "Pay group is required."); return; }
            }
            BigDecimal wc  = parseDec(fWcomp, "Workers Comp %"); if (wc  == null) return;
            BigDecimal onC = parseDec(fOnC, "On-Costs %");       if (onC == null) return;

            AwardWcomp out = new AwardWcomp();
            out.companyNo    = coNo;
            out.awardCode    = awardCode;
            out.jobClassCode = isAddWc ? jobCode : w.jobClassCode;
            out.paygroup     = isAddWc ? pg      : w.paygroup;
            out.wcompPerc    = wc;
            out.onCostsPerc  = onC;
            out.noteNo       = w.noteNo;

            String userId = appSession.getUserId();
            exec.submit(() -> {
                try {
                    if (isAddWc) {
                        if (wcompService.exists(out.companyNo, out.awardCode,
                                                  out.jobClassCode, out.paygroup)) {
                            Platform.runLater(() ->
                                status("WC row already exists for " + out.jobClassCode +
                                       "/" + out.paygroup + ".", true));
                            return;
                        }
                        wcompService.insert(out, userId);
                    } else {
                        wcompService.update(out, userId);
                    }
                    Platform.runLater(() -> {
                        status((isAddWc ? "Added" : "Updated") + " WC row " +
                            out.jobClassCode + "/" + out.paygroup + ".", false);
                        dlg.close();
                        reload.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() ->
                        status("WC save error: " + ex.getMessage(), true));
                }
            });
        });

        HBox bar = new HBox(10, btnSave, btnCancel);
        bar.setPadding(new Insets(10, 20, 16, 20));
        bar.setAlignment(Pos.CENTER_RIGHT);

        VBox top = new VBox(2, headerLine(
            (isAddWc ? "Add" : "Edit") + " WC rate — award " + awardCode));
        top.setPadding(new Insets(14, 20, 8, 20));

        VBox root = new VBox(0, top, g, bar);
        dlg.setScene(new Scene(root, 460, 360));
        dlg.showAndWait();
    }

    private void confirmDeleteWcomp(AwardWcomp sel, Runnable reload) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete WC row " + sel.jobClassCode + "/" + sel.paygroup + "?",
            ButtonType.OK, ButtonType.CANCEL);
        a.setTitle("Delete WC row"); a.setHeaderText(null);
        a.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            exec.submit(() -> {
                try {
                    wcompService.delete(sel.companyNo, sel.awardCode,
                                          sel.jobClassCode, sel.paygroup);
                    Platform.runLater(() -> {
                        status("Deleted WC row " + sel.jobClassCode + "/" + sel.paygroup, false);
                        reload.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() ->
                        status("WC delete error: " + ex.getMessage(), true));
                }
            });
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // UI helpers
    // ═══════════════════════════════════════════════════════════════════

    private Tab tab(String title, Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setBorder(null);
        return new Tab(title, sp);
    }

    private GridPane formGrid() {
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(10); g.setPadding(new Insets(16));
        return g;
    }

    private CheckBox yn(String label, String flag) {
        CheckBox cb = new CheckBox(label);
        cb.setSelected("Y".equalsIgnoreCase(flag));
        return cb;
    }

    private static String ynStr(CheckBox cb) { return cb.isSelected() ? "Y" : "N"; }

    private TextField intField(int v) {
        TextField f = tf(v == 0 ? "" : String.valueOf(v), 8);
        f.setPrefWidth(100);
        return f;
    }

    private static int parseInt(TextField f) {
        String s = f.getText().trim();
        if (s.isEmpty()) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException ex) { return 0; }
    }

    /** Parse hours-as-decimal text (e.g. "38.0") into minutes. */
    private static int parseHoursToMinutes(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.isEmpty()) return 0;
        try { return new BigDecimal(t).multiply(new BigDecimal(60)).intValue(); }
        catch (NumberFormatException ex) { return 0; }
    }

    private static String firstChar(String choiceValue, String def) {
        if (choiceValue == null || choiceValue.isBlank()) return def;
        return choiceValue.substring(0, 1);
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

    private TableColumn<Award, String> col(String header,
                                             Function<Award, String> fn, double w) {
        TableColumn<Award, String> c = new TableColumn<>(header);
        c.setCellValueFactory(p -> new SimpleStringProperty(safe(fn.apply(p.getValue()))));
        c.setPrefWidth(w);
        return c;
    }

    private void addFormRow(GridPane g, int row, String label, Node ctrl) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(160);
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

    // ─── Status bar ─────────────────────────────────────────────────────

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

    private static String safe(String s) { return s == null ? "" : s; }
}
