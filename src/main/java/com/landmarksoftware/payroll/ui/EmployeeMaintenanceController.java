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

import com.landmarksoftware.export.EmployeePdfService;
import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.payroll.model.Employee;
import com.landmarksoftware.payroll.model.EmployeePay;
import com.landmarksoftware.payroll.model.PayCode;
import com.landmarksoftware.payroll.service.EmployeePayService;
import com.landmarksoftware.payroll.service.EmployeeService;
import com.landmarksoftware.payroll.service.MvrService;
import com.landmarksoftware.payroll.service.PayCodeService;
import com.landmarksoftware.service.CodeLookupService;
import com.landmarksoftware.ui.LookupDialog;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * PAEM01 — Employee Maintenance.
 *
 * Mirrors COBOL PAEM01 — Edit and Terminate flows on the pastaff master.
 *
 * Add (INSERT) is intentionally not enabled — pastaff has 121 NOT NULL
 * columns and must be seeded by the company-creation flow or a future
 * dedicated New-Employee wizard. The Add button shows an explanatory
 * info dialog rather than an INSERT failure.
 *
 * Screen layout (P1 listbox pattern, mirrors PACD01):
 *   top     — header bar with company and page title
 *   toolbar — Edit | Terminate | Leave Balances | search | Refresh | Show Terminated
 *   centre  — TableView of employees
 *   bottom  — status bar
 *
 * Edit opens a tabbed modal: Personal | Employment | Pay & Tax.
 */
@Component
public class EmployeeMaintenanceController {

    private static final DateTimeFormatter D_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final EmployeeService     employeeService;
    private final EmployeePayService  employeePayService;
    private final PayCodeService      payCodeService;
    private final MvrService          mvrService;
    private final CodeLookupService   lookupService;
    private final EmployeePdfService  pdfService;
    private final AppSession          appSession;

    private final ObservableList<Employee> rows = FXCollections.observableArrayList();
    private TableView<Employee> table;
    private Label               lblStatus;
    private TextField           fSearch;
    private ToggleButton        btnShowTerminated;
    private boolean             showTerminated = false;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "paem01-thread");
        t.setDaemon(true);
        return t;
    });

    public EmployeeMaintenanceController(EmployeeService employeeService,
                                         EmployeePayService employeePayService,
                                         PayCodeService payCodeService,
                                         MvrService mvrService,
                                         CodeLookupService lookupService,
                                         EmployeePdfService pdfService,
                                         AppSession appSession) {
        this.employeeService    = employeeService;
        this.employeePayService = employeePayService;
        this.payCodeService     = payCodeService;
        this.mvrService         = mvrService;
        this.lookupService      = lookupService;
        this.pdfService         = pdfService;
        this.appSession         = appSession;
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
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    // ── Header ────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("Employee Maintenance");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("PAEM01 · " + appSession.getCompanyName());
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
        table.setPlaceholder(new Label("No employees found for this company."));

        table.getColumns().addAll(List.of(
            colInt("Emp #",       e -> e.employeeNo,                     70),
            col   ("Name",        Employee::fullName,                    220),
            col   ("Dept",        e -> e.dept,                           80),
            col   ("Status",      Employee::statusLabel,                 85),
            col   ("Type",        Employee::employeeTypeLabel,           95),
            col   ("Pay Freq",    Employee::payFreqLabel,                90),
            col   ("Rate /hr",    e -> rateStr(e.stdRatePerHr),          80),
            col   ("Started",     e -> dateStr(e.dateStarted),           95),
            col   ("Terminated",  e -> dateStr(e.dateTerminated),        95)
        ));

        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(Employee emp, boolean empty) {
                super.updateItem(emp, empty);
                setStyle(emp != null && emp.isTerminated()
                    ? "-fx-text-fill:#AAAAAA;" : "");
            }
        });

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null)
                openDialog(table.getSelectionModel().getSelectedItem(), stage);
        });

        // Toolbar
        Button btnAdd   = btnPrimary("+ Add");
        Button btnEdit  = btnSecondary("✎ Edit");
        Button btnTerm  = btnDanger("⏻ Terminate");
        Button btnLeave = btnSecondary("⏱ Leave Balances");
        Button btnPrint = btnSecondary("⎙ Print");
        Button btnRef   = btnSecondary("↺");

        btnAdd.setOnAction(e -> openDialog(null, stage));

        fSearch = new TextField();
        fSearch.setPromptText("Search name or emp #…");
        fSearch.setPrefWidth(220);
        fSearch.setStyle(
            "-fx-background-radius:7;-fx-border-radius:7;" +
            "-fx-border-color:#D0CFC8;-fx-padding:5 10;");
        fSearch.textProperty().addListener((o, ov, nv) -> applySearch(nv));

        btnShowTerminated = new ToggleButton("Show Terminated");
        btnShowTerminated.setStyle(
            "-fx-background-color:white;-fx-border-color:#D0CFC8;" +
            "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:5 12;-fx-cursor:hand;");

        btnEdit.setOnAction(e -> {
            Employee sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openDialog(sel, stage);
            else showInfo("Edit", "Select an employee to edit.");
        });
        btnTerm.setOnAction(e -> {
            Employee sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmTerminate(sel);
            else showInfo("Terminate", "Select an employee to terminate.");
        });
        btnLeave.setOnAction(e -> {
            Employee sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openLeaveBalances(sel, stage);
            else showInfo("Leave Balances", "Select an employee.");
        });
        btnPrint.setOnAction(e -> {
            Employee sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) printEmployee(sel, stage);
            else showInfo("Print", "Select an employee to print.");
        });
        btnRef.setOnAction(e -> loadList());
        btnShowTerminated.setOnAction(e -> {
            showTerminated = btnShowTerminated.isSelected();
            loadList();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(8,
            btnAdd, btnEdit, btnTerm, btnLeave, btnPrint,
            new Separator(Orientation.VERTICAL),
            fSearch,
            spacer,
            btnRef, btnShowTerminated);
        toolbar.setPadding(new Insets(10, 16, 10, 16));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle(
            "-fx-background-color:#F8F8F6;" +
            "-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;" +
            "-fx-border-width:0 0 0.5 0;");

        return new VBox(0, toolbar, table);
    }

    // ── Data operations ───────────────────────────────────────────────────

    private void loadList() {
        status("Loading…", false);
        applySearch(fSearch == null ? "" : fSearch.getText());
    }

    private void applySearch(String term) {
        exec.submit(() -> {
            try {
                List<Employee> data = (term == null || term.isBlank())
                    ? employeeService.findAll(appSession.getCompanyNo())
                    : employeeService.search(appSession.getCompanyNo(), term);
                List<Employee> filtered = showTerminated ? data
                    : data.stream().filter(e -> !e.isTerminated()).toList();
                Platform.runLater(() -> {
                    rows.setAll(filtered);
                    status(filtered.size() + " employee(s)", false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    private void confirmTerminate(Employee emp) {
        if (emp.isTerminated()) {
            showInfo("Terminate",
                "Employee " + emp.employeeNo + " is already terminated.");
            return;
        }
        DatePicker dp = new DatePicker(LocalDate.now());
        Dialog<LocalDate> dlg = new Dialog<>();
        dlg.setTitle("Terminate Employee");
        dlg.setHeaderText("Terminate " + emp.fullName() + " (" + emp.employeeNo + ")?");
        VBox box = new VBox(8,
            new Label("Termination date:"),
            dp,
            new Label("This sets status='T' and date_terminated.\nPay history is preserved."));
        box.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(box);
        dlg.getDialogPane().getButtonTypes().addAll(
            new ButtonType("Terminate", ButtonBar.ButtonData.OK_DONE),
            ButtonType.CANCEL);
        dlg.setResultConverter(bt ->
            bt.getButtonData() == ButtonBar.ButtonData.OK_DONE ? dp.getValue() : null);
        dlg.showAndWait().ifPresent(date -> {
            if (date == null) return;
            exec.submit(() -> {
                try {
                    employeeService.terminate(
                        appSession.getCompanyNo(), emp.employeeNo, date);
                    Platform.runLater(() -> {
                        loadList();
                        status("Terminated: " + emp.employeeNo + " " + emp.fullName(), false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() ->
                        status("Terminate error: " + ex.getMessage(), true));
                }
            });
        });
    }

    // ── Print employee record (PDF) ───────────────────────────────────────

    private void printEmployee(Employee emp, Window owner) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Employee PDF");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("PDF document (*.pdf)", "*.pdf"));
        String safeName = (emp.surname == null ? "employee" : emp.surname)
            .replaceAll("[^A-Za-z0-9]", "_");
        fc.setInitialFileName(String.format("Employee_%d_%s.pdf",
            emp.employeeNo, safeName));
        java.io.File picked = fc.showSaveDialog(owner);
        if (picked == null) return;

        int coNo = appSession.getCompanyNo();
        String companyName = appSession.getCompanyName();
        status("Printing employee " + emp.employeeNo + "…", false);
        exec.submit(() -> {
            try {
                List<EmployeePay> splits = employeePayService.findByEmployee(coNo, emp.employeeNo);
                pdfService.export(emp, splits, companyName, coNo, picked.toPath());
                Platform.runLater(() ->
                    status("Printed: " + picked.getName(), false));
            } catch (Exception ex) {
                Platform.runLater(() ->
                    status("Print error: " + ex.getMessage(), true));
            }
        });
    }

    // ── Leave balances dialog (read-only) ─────────────────────────────────

    private void openLeaveBalances(Employee emp, Window owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Leave Balances — " + emp.fullName());
        dlg.setResizable(false);

        Label sub = new Label("Employee " + emp.employeeNo + " · accruals are calculated by the pay run.");
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");

        GridPane g = new GridPane();
        g.setHgap(20); g.setVgap(12); g.setPadding(new Insets(20));

        Label lAl  = leaveValue(Employee.minutesAsHours(emp.alHrsAccrued));
        Label lSl  = leaveValue(Employee.minutesAsHours(emp.accruedSickLeave));
        Label lLsl = leaveValue(emp.lslWeeksAccrued.compareTo(BigDecimal.ZERO) == 0
            ? "" : emp.lslWeeksAccrued.toPlainString());

        g.add(leaveLabel("Annual Leave"),     0, 0); g.add(lAl,  1, 0); g.add(unit("hours"), 2, 0);
        g.add(leaveLabel("Sick Leave"),       0, 1); g.add(lSl,  1, 1); g.add(unit("hours"), 2, 1);
        g.add(leaveLabel("Long Service"),     0, 2); g.add(lLsl, 1, 2); g.add(unit("weeks"), 2, 2);

        Button btnRef = btnSecondary("↺ Refresh");
        btnRef.setOnAction(e -> exec.submit(() -> {
            int[] bal = employeeService.loadLeaveBalances(
                appSession.getCompanyNo(), emp.employeeNo);
            Platform.runLater(() -> {
                lAl.setText(Employee.minutesAsHours(bal[0]));
                lSl.setText(Employee.minutesAsHours(bal[1]));
                lLsl.setText(bal[2] == 0 ? "" : String.format("%.2f", bal[2] / 100.0));
            });
        }));
        Button btnClose = btnPrimary("Close");
        btnClose.setOnAction(e -> dlg.close());

        HBox btnBar = new HBox(10, btnRef, btnClose);
        btnBar.setPadding(new Insets(10, 20, 16, 20));
        btnBar.setAlignment(Pos.CENTER_RIGHT);

        VBox top = new VBox(2, headerLine("Leave Balances"), sub);
        top.setPadding(new Insets(16, 20, 0, 20));

        VBox root = new VBox(8, top, g, btnBar);
        dlg.setScene(new Scene(root, 360, 280));
        dlg.showAndWait();
    }

    private Label leaveLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(140);
        return l;
    }
    private Label leaveValue(String text) {
        Label l = new Label(text.isEmpty() ? "0.0" : text);
        l.setStyle("-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        l.setMinWidth(60);
        return l;
    }
    private Label unit(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        return l;
    }

    // ── Edit dialog ───────────────────────────────────────────────────────

    private void openDialog(Employee existing, Window owner) {
        boolean isAdd = (existing == null);
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(isAdd ? "Add Employee — PAEM01" : "Edit Employee — PAEM01");
        dlg.setResizable(false);

        Employee e;
        if (isAdd) {
            e = new Employee();
            e.employeeNo     = employeeService.nextEmployeeNo(appSession.getCompanyNo());
            e.employeeStatus = "A";
            e.employeeType   = "F";
            e.payFreq        = "W";
            e.dateStarted    = LocalDate.now();
        } else {
            e = existing;
        }

        // ── Personal tab ──────────────────────────────────────────────────
        TextField fEmpNo   = tf(String.valueOf(e.employeeNo), 8);
        fEmpNo.setEditable(isAdd);
        fEmpNo.setDisable(!isAdd);
        TextField fSurname  = tf(e.surname,    30);
        TextField fFirst    = tf(e.firstName,  30);
        TextField fSecond   = tf(e.secondName, 30);
        TextField fAddr1    = tf(e.addr1,      30);
        TextField fAddr2    = tf(e.addr2,      30);
        TextField fCity     = tf(e.city,       30);
        TextField fState    = tf(e.state,      4);
        TextField fPost     = tf(e.postcode,   10);
        TextField fPhArea   = tf(e.phoneArea,  4);
        TextField fPhNo     = tf(e.phoneNo,    20);
        TextField fMobile   = tf(e.mobile,     20);
        TextField fEmail    = tf(e.emailAddress, 40);

        GridPane gPersonal = new GridPane();
        gPersonal.setHgap(10); gPersonal.setVgap(10); gPersonal.setPadding(new Insets(16));
        int r = 0;
        addFormRow(gPersonal, r++, "Employee #:",  fEmpNo);
        addFormRow(gPersonal, r++, "Surname *:",   fSurname);
        addFormRow(gPersonal, r++, "First Name:",  fFirst);
        addFormRow(gPersonal, r++, "Second Name:", fSecond);
        addFormRow(gPersonal, r++, "Address 1:",   fAddr1);
        addFormRow(gPersonal, r++, "Address 2:",   fAddr2);
        addFormRow(gPersonal, r++, "City:",        fCity);
        addFormRow(gPersonal, r++, "State:",       fState);
        addFormRow(gPersonal, r++, "Postcode:",    fPost);
        addFormRow(gPersonal, r++, "Phone Area:",  fPhArea);
        addFormRow(gPersonal, r++, "Phone:",       fPhNo);
        addFormRow(gPersonal, r++, "Mobile:",      fMobile);
        addFormRow(gPersonal, r++, "Email:",       fEmail);

        // ── Employment tab ────────────────────────────────────────────────
        TextField fDept     = tf(e.dept,     10);
        TextField fPaygroup = tf(e.paygroup, 10);
        ChoiceBox<String> cbStatus = new ChoiceBox<>();
        cbStatus.getItems().addAll("A — Active", "I — Inactive", "T — Terminated");
        cbStatus.getSelectionModel().select(switch (e.employeeStatus) {
            case "I" -> 1; case "T" -> 2; default -> 0;
        });
        cbStatus.setPrefWidth(180);

        ChoiceBox<String> cbType = new ChoiceBox<>();
        cbType.getItems().addAll("F — Full-time", "P — Part-time", "C — Casual");
        cbType.getSelectionModel().select(switch (e.employeeType) {
            case "P" -> 1; case "C" -> 2; default -> 0;
        });
        cbType.setPrefWidth(180);

        DatePicker dpStarted = new DatePicker(
            Employee.isValidDate(e.dateStarted) ? e.dateStarted : LocalDate.now());
        DatePicker dpTerm    = new DatePicker(
            Employee.isValidDate(e.dateTerminated) ? e.dateTerminated : null);

        ChoiceBox<String> cbFreq = new ChoiceBox<>();
        cbFreq.getItems().addAll("W — Weekly", "F — Fortnightly", "M — Monthly");
        cbFreq.getSelectionModel().select(switch (e.payFreq) {
            case "F" -> 1; case "M" -> 2; default -> 0;
        });
        cbFreq.setPrefWidth(180);

        TextField fAward = tf(e.award,    10);
        TextField fJob   = tf(e.jobClass, 10);

        GridPane gEmp = new GridPane();
        gEmp.setHgap(10); gEmp.setVgap(10); gEmp.setPadding(new Insets(16));
        r = 0;
        addFormRow(gEmp, r++, "Department:",      lookupField(fDept,     LookupDialog.LookupType.DEPARTMENT));
        addFormRow(gEmp, r++, "Pay Group:",       lookupField(fPaygroup, LookupDialog.LookupType.PAYGROUP));
        addFormRow(gEmp, r++, "Status *:",        cbStatus);
        addFormRow(gEmp, r++, "Employee Type:",   cbType);
        addFormRow(gEmp, r++, "Date Started:",    dpStarted);
        addFormRow(gEmp, r++, "Date Terminated:", dpTerm);
        addFormRow(gEmp, r++, "Pay Frequency:",   cbFreq);
        addFormRow(gEmp, r++, "Award:",           lookupField(fAward,    LookupDialog.LookupType.AWARD));
        addFormRow(gEmp, r++, "Job Class:",       lookupField(fJob,      LookupDialog.LookupType.JOB_CLASS));

        // ── Pay & Tax tab ─────────────────────────────────────────────────
        TextField fSalary  = tf(decStr(e.annualSalary), 12);
        TextField fStdHrs  = tf(e.stdHrs == 0 ? "" : Employee.minutesAsHours(e.stdHrs), 8);
        TextField fRate    = tf(decStr(e.stdRatePerHr), 12);
        TextField fScale   = tf(e.taxScaleNo, 4);
        TextField fExtra   = tf(decStr(e.extraTaxAmt), 12);

        // TFN — held off-screen in rawTfn, masked in fTfn; "Show" toggles reveal/edit.
        final String[] rawTfn = { e.taxFileNo == null ? "" : e.taxFileNo.trim() };
        TextField fTfn = tf(Employee.maskTfn(rawTfn[0]), 11);
        fTfn.setEditable(false);
        ToggleButton btnTfnShow = new ToggleButton("Show");
        btnTfnShow.setFocusTraversable(false);
        btnTfnShow.setStyle(
            "-fx-background-color:white;-fx-text-fill:#1A6EF5;" +
            "-fx-border-color:#D0CFC8;-fx-border-width:1;" +
            "-fx-background-radius:0 7 7 0;-fx-border-radius:0 7 7 0;" +
            "-fx-padding:4 12;-fx-cursor:hand;");
        btnTfnShow.setOnAction(ev -> {
            if (btnTfnShow.isSelected()) {
                fTfn.setText(rawTfn[0]);
                fTfn.setEditable(true);
                btnTfnShow.setText("Hide");
                fTfn.requestFocus();
            } else {
                rawTfn[0] = fTfn.getText().trim();
                fTfn.setText(Employee.maskTfn(rawTfn[0]));
                fTfn.setEditable(false);
                btnTfnShow.setText("Show");
            }
        });
        HBox tfnBox = new HBox(0, fTfn, btnTfnShow);
        HBox.setHgrow(fTfn, Priority.ALWAYS);

        Label tfnHint = hint("Masked by default. Click Show to view or edit.");
        Label hrsHint = hint("Standard weekly hours (e.g. 38.0)");

        GridPane gPay = new GridPane();
        gPay.setHgap(10); gPay.setVgap(10); gPay.setPadding(new Insets(16));
        r = 0;
        addFormRow(gPay, r++, "Annual Salary:",       fSalary);
        addFormRowWithHint(gPay, r++, "Std Hours/Week:", fStdHrs, hrsHint);
        addFormRow(gPay, r++, "Std Rate /hr:",        fRate);
        addFormRow(gPay, r++, "Tax Scale No:",
            lookupField(fScale, LookupDialog.LookupType.TAX_SCALE));
        addFormRowWithHint(gPay, r++, "Tax File No:", tfnBox, tfnHint);
        addFormRow(gPay, r++, "Extra Tax $:",         fExtra);

        // ── Superannuation tab (PAEM01 S1C) ─────────────────────────────
        TextField fSuperCode    = tf(e.superCode,     10);
        TextField fSuperMember  = tf(e.superMemberNo, 20);
        DatePicker dpSuperComm  = new DatePicker(
            Employee.isValidDate(e.superCommDate) ? e.superCommDate : null);
        TextField fQualifyDays  = tf(e.qualifyDays == 0 ? "" : String.valueOf(e.qualifyDays), 6);
        CheckBox cbForcePay     = new CheckBox("Force payment");
        cbForcePay.setSelected("Y".equalsIgnoreCase(e.forcePayFlag));
        CheckBox cbUseExtSuper  = new CheckBox("Calc super based on hrs/$ worked");
        cbUseExtSuper.setSelected("Y".equalsIgnoreCase(e.useExtSuperFlag));

        // Identity fields the MVR call needs (also useful elsewhere on this screen)
        ChoiceBox<String> cbSex = new ChoiceBox<>();
        cbSex.getItems().addAll("", "M — Male", "F — Female");
        cbSex.getSelectionModel().select(switch (e.sex) {
            case "M" -> 1; case "F" -> 2; default -> 0;
        });
        cbSex.setPrefWidth(180);
        DatePicker dpDob = new DatePicker(
            Employee.isValidDate(e.dateOfBirth) ? e.dateOfBirth : null);

        Label lblLastPayrun = new Label(
            e.lastSuperPayrun == 0 ? "—" : String.valueOf(e.lastSuperPayrun));
        Label lblLastSuperDate = new Label(
            Employee.isValidDate(e.lastSuperDate) ? dateStr(e.lastSuperDate) : "—");
        Label lblCurrPayrun = new Label(
            e.currentSuperPayrun == 0 ? "—" : String.valueOf(e.currentSuperPayrun));

        Button btnMvr = btnSecondary("🛈 MVR Check");
        btnMvr.setTooltip(new Tooltip(
            "Verify the super-fund member number against the ATO MVR service.\n" +
            "Requires Super code and (Member no OR Tax File No)."));

        GridPane gSuper = new GridPane();
        gSuper.setHgap(10); gSuper.setVgap(10); gSuper.setPadding(new Insets(16));
        r = 0;
        addFormRow(gSuper, r++, "Employer Super Code *:",
            lookupField(fSuperCode, LookupDialog.LookupType.SUPER_PAY_CODE));
        addFormRow(gSuper, r++, "Super Member No:",       fSuperMember);
        addFormRow(gSuper, r++, "Super Commence Date:",   dpSuperComm);
        addFormRow(gSuper, r++, "Qualifying Days:",       fQualifyDays);
        addFormRow(gSuper, r++, "Force Payment:",         cbForcePay);
        addFormRow(gSuper, r++, "Calc Based on Hrs/$:",   cbUseExtSuper);
        addFormRow(gSuper, r++, "Sex:",                   cbSex);
        addFormRow(gSuper, r++, "Date of Birth:",         dpDob);
        addFormRow(gSuper, r++, "MVR (Member Verify):",   btnMvr);
        addFormRow(gSuper, r++, "Last Super Payrun:",     lblLastPayrun);
        addFormRow(gSuper, r++, "Last Super Date:",       lblLastSuperDate);
        addFormRow(gSuper, r++, "Current Super Payrun:",  lblCurrPayrun);

        // MVR button — validate pre-conditions, look up the super fund pacodes
        // row, and call MvrService.verify().
        btnMvr.setOnAction(ev -> {
            // Build a transient Employee from the current form values so the
            // user doesn't have to Save first.
            Employee txn = new Employee();
            txn.employeeNo    = e.employeeNo;
            txn.firstName     = fFirst.getText().trim();
            txn.secondName    = fSecond.getText().trim();
            txn.surname       = fSurname.getText().trim();
            txn.sex           = code(cbSex.getValue(), "");
            txn.dateOfBirth   = dpDob.getValue();
            txn.taxFileNo     = fTfn.getText().trim();
            txn.superCode     = fSuperCode.getText().trim().toUpperCase();
            txn.superMemberNo = fSuperMember.getText().trim();
            performMvrCheck(dlg, txn);
        });

        // ── Bank / EFT tab (PAEM01 S2 — paempay) ──────────────────────────
        // Pay-method splits live in their own table (paempay), so this tab
        // saves immediately on Add/Edit/Delete — independent of the outer
        // Save/Cancel on the employee record.
        Node gBank = buildBankTab(dlg, e.employeeNo, isAdd);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
            tab("Personal",       gPersonal),
            tab("Employment",     gEmp),
            tab("Pay & Tax",      gPay),
            tab("Bank / EFT",     gBank),
            tab("Superannuation", gSuper));

        // ── Save ──────────────────────────────────────────────────────────
        Button btnSave   = btnPrimary(isAdd ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(ev -> dlg.close());

        btnSave.setOnAction(ev -> {
            int empNo = e.employeeNo;
            if (isAdd) {
                try { empNo = Integer.parseInt(fEmpNo.getText().trim()); }
                catch (NumberFormatException nfe) {
                    markError(fEmpNo, "Employee # must be a whole number.");
                    tabs.getSelectionModel().select(0); return;
                }
                if (empNo <= 0) {
                    markError(fEmpNo, "Employee # must be positive.");
                    tabs.getSelectionModel().select(0); return;
                }
                clearError(fEmpNo);
            }
            if (fSurname.getText().trim().isEmpty()) {
                markError(fSurname, "Surname is required.");
                tabs.getSelectionModel().select(0); return;
            }
            clearError(fSurname);

            BigDecimal salary = parseDec(fSalary, "Annual Salary"); if (salary == null) {
                tabs.getSelectionModel().select(2); return;
            }
            BigDecimal rate   = parseDec(fRate,   "Std Rate");      if (rate == null) {
                tabs.getSelectionModel().select(2); return;
            }
            BigDecimal extra  = parseDec(fExtra,  "Extra Tax");     if (extra == null) {
                tabs.getSelectionModel().select(2); return;
            }
            int stdHrsMins = parseHoursToMinutes(fStdHrs.getText());
            if (stdHrsMins < 0) {
                markError(fStdHrs, "Std Hours must be a number (e.g. 38.0).");
                tabs.getSelectionModel().select(2); return;
            }

            Employee out = new Employee();
            out.employeeNo       = empNo;
            out.surname          = fSurname.getText().trim();
            out.firstName        = fFirst.getText().trim();
            out.secondName       = fSecond.getText().trim();
            out.addr1            = fAddr1.getText().trim();
            out.addr2            = fAddr2.getText().trim();
            out.city             = fCity.getText().trim();
            out.state            = fState.getText().trim().toUpperCase();
            out.postcode         = fPost.getText().trim();
            out.phoneArea        = fPhArea.getText().trim();
            out.phoneNo          = fPhNo.getText().trim();
            out.mobile           = fMobile.getText().trim();
            out.emailAddress     = fEmail.getText().trim();
            out.dept             = fDept.getText().trim().toUpperCase();
            out.paygroup         = fPaygroup.getText().trim().toUpperCase();
            out.employeeStatus   = code(cbStatus.getValue(), "A");
            out.employeeType     = code(cbType.getValue(),   "");
            out.dateStarted      = dpStarted.getValue();
            out.dateTerminated   = dpTerm.getValue();
            out.payFreq          = code(cbFreq.getValue(),   "W");
            out.award            = fAward.getText().trim().toUpperCase();
            out.jobClass         = fJob.getText().trim().toUpperCase();
            out.annualSalary     = salary;
            out.stdHrs           = stdHrsMins;
            out.stdRatePerHr     = rate;
            // If TFN is currently revealed (Show toggled on), the field has the
            // raw value; otherwise the field shows the mask and rawTfn[] is canonical.
            out.taxFileNo        = btnTfnShow.isSelected()
                                   ? fTfn.getText().trim()
                                   : rawTfn[0];
            out.taxScaleNo       = fScale.getText().trim().toUpperCase();
            out.extraTaxAmt      = extra;
            // Superannuation (S1C)
            out.superCode        = fSuperCode.getText().trim().toUpperCase();
            out.superMemberNo    = fSuperMember.getText().trim();
            out.superCommDate    = dpSuperComm.getValue();
            try { out.qualifyDays = fQualifyDays.getText().trim().isEmpty()
                                   ? 0 : Integer.parseInt(fQualifyDays.getText().trim()); }
            catch (NumberFormatException nfe) {
                markError(fQualifyDays, "Qualifying days must be a whole number.");
                tabs.getSelectionModel().select(3); return;
            }
            out.forcePayFlag     = cbForcePay.isSelected()   ? "Y" : "N";
            out.useExtSuperFlag  = cbUseExtSuper.isSelected() ? "Y" : "N";
            out.sex              = code(cbSex.getValue(), "");
            out.dateOfBirth      = dpDob.getValue();
            // Carry forward read-only fields so the update doesn't lose them
            out.lastSuperPayrun    = e.lastSuperPayrun;
            out.currentSuperPayrun = e.currentSuperPayrun;
            out.lastSuperDate      = e.lastSuperDate;

            int coNo = appSession.getCompanyNo();
            String userId = appSession.getUserId();
            int finalEmpNo = out.employeeNo;

            exec.submit(() -> {
                try {
                    if (isAdd) {
                        if (employeeService.exists(coNo, finalEmpNo)) {
                            Platform.runLater(() ->
                                status("Employee " + finalEmpNo + " already exists.", true));
                            return;
                        }
                        employeeService.insert(coNo, out, userId);
                    } else {
                        employeeService.update(coNo, out);
                    }
                    Platform.runLater(() -> {
                        status((isAdd ? "Added: " : "Updated: ")
                            + finalEmpNo + " " + out.fullName(), false);
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
            isAdd ? "New Employee" : "Edit Employee #" + e.employeeNo));
        top.setPadding(new Insets(16, 20, 8, 20));

        VBox root = new VBox(0, top, tabs, btnBar);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        dlg.setScene(new Scene(root, 640, 620));
        dlg.showAndWait();
    }

    private Tab tab(String title, Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setBorder(null);
        return new Tab(title, sp);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Bank / EFT tab — paempay splits
    // ═══════════════════════════════════════════════════════════════════

    private Node buildBankTab(Window owner, int employeeNo, boolean isAdd) {
        TableView<EmployeePay> table = new TableView<>();
        ObservableList<EmployeePay> rows = FXCollections.observableArrayList();
        table.setItems(rows);
        table.setPlaceholder(new Label(
            isAdd ? "Save the employee first, then add pay splits here."
                  : "No pay splits — Add to create one."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        table.getColumns().add(payCol("#",       p -> String.valueOf(p.paymentNo), 50));
        table.getColumns().add(payCol("Method",  p -> describePayMethod(p.payMethod), 110));
        table.getColumns().add(payCol("BSB",     p -> p.tfrToBankNo, 90));
        table.getColumns().add(payCol("Account", p -> p.tfrToBankAcctNo, 150));
        table.getColumns().add(payCol("Payee",   p -> p.payeeName, 200));
        table.getColumns().add(payCol("Amount",  p -> describeAmount(p), 100));

        final int coNo = appSession.getCompanyNo();
        Runnable reload = () -> {
            if (isAdd) { rows.clear(); return; }
            try {
                List<EmployeePay> data = employeePayService.findByEmployee(coNo, employeeNo);
                Platform.runLater(() -> rows.setAll(data));
            } catch (Exception ex) {
                Platform.runLater(() -> status("Bank load error: " + ex.getMessage(), true));
            }
        };
        if (!isAdd) exec.submit(reload);

        Button btnAdd  = btnSecondary("Add Split");
        Button btnEdit = btnSecondary("Edit");
        Button btnDel  = btnDanger("Delete");
        btnAdd.setDisable(isAdd);
        btnEdit.setDisable(true);
        btnDel.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            boolean has = n != null;
            btnEdit.setDisable(!has);
            btnDel.setDisable(!has);
        });

        btnAdd.setOnAction(ev -> openSplitDialog(owner, null, employeeNo, reload));
        btnEdit.setOnAction(ev -> {
            EmployeePay sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openSplitDialog(owner, sel, employeeNo, reload);
        });
        btnDel.setOnAction(ev -> {
            EmployeePay sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDeleteSplit(sel, reload);
        });

        HBox bar = new HBox(8, btnAdd, btnEdit, btnDel);
        bar.setPadding(new Insets(10, 12, 10, 12));
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(0, bar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(0));
        return box;
    }

    private TableColumn<EmployeePay, String> payCol(String header,
                                                     Function<EmployeePay, String> fn,
                                                     double w) {
        TableColumn<EmployeePay, String> c = new TableColumn<>(header);
        c.setCellValueFactory(p -> new SimpleStringProperty(safe(fn.apply(p.getValue()))));
        c.setPrefWidth(w);
        return c;
    }

    private void openSplitDialog(Window owner, EmployeePay existing,
                                  int employeeNo, Runnable reload) {
        boolean isAddSplit = (existing == null);
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((isAddSplit ? "Add" : "Edit") + " Pay Split — PAEM01 S2");
        dlg.setResizable(false);

        final int coNo = appSession.getCompanyNo();
        EmployeePay p = isAddSplit ? new EmployeePay() : existing;
        if (isAddSplit) {
            p.companyNo  = coNo;
            p.employeeNo = employeeNo;
            p.paymentNo  = employeePayService.nextPaymentNo(coNo, employeeNo);
            p.payMethod  = "E";   // sensible default: EFT
            p.payCalcMethod = "B"; // balance
        }

        TextField fSeq = tf(String.valueOf(p.paymentNo), 4);
        fSeq.setEditable(false);
        fSeq.setDisable(true);

        ChoiceBox<String> cbMethod = new ChoiceBox<>();
        cbMethod.getItems().addAll(
            "E — EFT (bank transfer)",
            "C — Cheque",
            "X — Cash",
            "O — Other");
        cbMethod.getSelectionModel().select(switch (p.payMethod == null ? "" : p.payMethod) {
            case "C" -> 1; case "X" -> 2; case "O" -> 3; default -> 0;
        });

        ChoiceBox<String> cbCalc = new ChoiceBox<>();
        cbCalc.getItems().addAll(
            "B — Balance (whatever's left)",
            "P — Percent of net",
            "A — Fixed amount $");
        cbCalc.getSelectionModel().select(switch (p.payCalcMethod == null ? "" : p.payCalcMethod) {
            case "P" -> 1; case "A" -> 2; default -> 0;
        });

        TextField fAmt    = tf(decStr(p.payAmtPerc), 12);
        TextField fBsb    = tf(p.tfrToBankNo, 7);
        TextField fAcct   = tf(p.tfrToBankAcctNo, 20);
        TextField fBank   = tf(p.bankCode, 2);
        TextField fPayee  = tf(p.payeeName, 50);

        // Disable amount/BSB/acct based on the calc/method choice
        Runnable applyFieldStates = () -> {
            String calc = code(cbCalc.getValue(), "B");
            String meth = code(cbMethod.getValue(), "E");
            boolean amountUsed = !"B".equals(calc);
            fAmt.setDisable(!amountUsed);
            boolean bankUsed = "E".equals(meth);
            fBsb.setDisable(!bankUsed);
            fAcct.setDisable(!bankUsed);
            fBank.setDisable(!bankUsed);
        };
        cbCalc.valueProperty().addListener((obs, o, n) -> applyFieldStates.run());
        cbMethod.valueProperty().addListener((obs, o, n) -> applyFieldStates.run());
        applyFieldStates.run();

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(10); g.setPadding(new Insets(16));
        int r = 0;
        addFormRow(g, r++, "Sequence:",      fSeq);
        addFormRow(g, r++, "Pay Method:",    cbMethod);
        addFormRow(g, r++, "Calc Method:",   cbCalc);
        addFormRow(g, r++, "Amount / %:",    fAmt);
        addFormRow(g, r++, "BSB:",           fBsb);
        addFormRow(g, r++, "Account No:",    fAcct);
        addFormRow(g, r++, "Bank Code:",     fBank);
        addFormRow(g, r++, "Payee Name:",    fPayee);

        Button btnOk     = btnPrimary(isAddSplit ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnCancel.setOnAction(ev -> dlg.close());

        btnOk.setOnAction(ev -> {
            String calc = code(cbCalc.getValue(),   "B");
            String meth = code(cbMethod.getValue(), "E");
            BigDecimal amt = parseDec(fAmt, "Amount / %");
            if (amt == null) return;
            if ("P".equals(calc) && (amt.signum() < 0
                    || amt.compareTo(new BigDecimal("100")) > 0)) {
                markError(fAmt, "Percent must be between 0 and 100.");
                return;
            }
            if ("E".equals(meth) && fBsb.getText().trim().isEmpty()) {
                markError(fBsb, "BSB is required for EFT.");
                return;
            }
            if ("E".equals(meth) && fAcct.getText().trim().isEmpty()) {
                markError(fAcct, "Account number is required for EFT.");
                return;
            }

            EmployeePay out = new EmployeePay();
            out.companyNo       = coNo;
            out.employeeNo      = employeeNo;
            out.paymentNo       = p.paymentNo;
            out.payMethod       = meth;
            out.payCalcMethod   = calc;
            out.payAmtPerc      = "B".equals(calc) ? BigDecimal.ZERO : amt;
            out.tfrToBankNo     = "E".equals(meth) ? fBsb.getText().trim()   : "";
            out.tfrToBankAcctNo = "E".equals(meth) ? fAcct.getText().trim()  : "";
            out.bankCode        = "E".equals(meth) ? fBank.getText().trim().toUpperCase() : "";
            out.payeeName       = fPayee.getText().trim();
            out.noteNo          = p.noteNo;

            String userId = appSession.getUserId();
            exec.submit(() -> {
                try {
                    if (isAddSplit) employeePayService.insert(out, userId);
                    else            employeePayService.update(out, userId);
                    Platform.runLater(() -> {
                        status((isAddSplit ? "Added" : "Updated") +
                            " split " + out.paymentNo +
                            " for employee " + employeeNo + ".", false);
                        dlg.close();
                        reload.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() ->
                        status("Split save error: " + ex.getMessage(), true));
                }
            });
        });

        HBox bar = new HBox(10, btnOk, btnCancel);
        bar.setPadding(new Insets(10, 20, 16, 20));
        bar.setAlignment(Pos.CENTER_RIGHT);

        VBox top = new VBox(2, headerLine(
            (isAddSplit ? "Add" : "Edit") + " pay split #" + p.paymentNo));
        top.setPadding(new Insets(16, 20, 8, 20));

        VBox root = new VBox(0, top, g, bar);
        dlg.setScene(new Scene(root, 460, 420));
        dlg.showAndWait();
    }

    private void confirmDeleteSplit(EmployeePay sel, Runnable reload) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete pay split #" + sel.paymentNo + "?",
            ButtonType.OK, ButtonType.CANCEL);
        a.setTitle("Delete split"); a.setHeaderText(null);
        a.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            exec.submit(() -> {
                try {
                    employeePayService.delete(sel.companyNo, sel.employeeNo, sel.paymentNo);
                    Platform.runLater(() -> {
                        status("Deleted split " + sel.paymentNo +
                            " for employee " + sel.employeeNo + ".", false);
                        reload.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() ->
                        status("Split delete error: " + ex.getMessage(), true));
                }
            });
        });
    }

    private static String describePayMethod(String code) {
        return switch (code == null ? "" : code) {
            case "E" -> "E — EFT";
            case "C" -> "C — Cheque";
            case "X" -> "X — Cash";
            case "O" -> "O — Other";
            case ""  -> "";
            default  -> code;
        };
    }

    private static String describeAmount(EmployeePay p) {
        return switch (p.payCalcMethod == null ? "" : p.payCalcMethod) {
            case "B" -> "Balance";
            case "P" -> p.payAmtPerc.stripTrailingZeros().toPlainString() + " %";
            case "A" -> "$ " + p.payAmtPerc.stripTrailingZeros().toPlainString();
            default  -> "";
        };
    }


    /**
     * MVR (Member Verification Request) — PAEM01 S1C "🛈 MVR Check" button.
     *
     * <p>Mirrors COBOL {@code DO-MVR-CHECK} in {@code paem01.pl}: validates
     * super_code + member_no/TFN, looks up the super-fund pacodes row for
     * ABN/USI/ESA, and dispatches the request to {@link MvrService} (which
     * stubs the real ATO call for now).
     */
    private void performMvrCheck(Window owner, Employee txn) {
        // Quick UI-side validation before going to the service
        if (txn.superCode.isBlank()) {
            showInfo("MVR", "Super code required for MVR check.");
            return;
        }
        if (txn.superMemberNo.isBlank() && txn.taxFileNo.isBlank()) {
            showInfo("MVR", "Member no or TFN required for MVR.");
            return;
        }

        final int coNo = appSession.getCompanyNo();
        final int yearNo = appSession.getYearNo();   // STP year (calendar)

        exec.submit(() -> {
            try {
                // Look up the super fund's pacodes row (for ABN / USI / ESA / fund-type)
                PayCode fund = payCodeService.findOne(coNo, txn.superCode).orElse(null);
                if (fund == null) {
                    Platform.runLater(() -> showInfo("MVR",
                        "Super code '" + txn.superCode + "' not found in pay codes."));
                    return;
                }
                MvrService.Result res = mvrService.verify(coNo, yearNo, txn, fund);
                Platform.runLater(() -> {
                    Alert a = new Alert(res.ok() ? Alert.AlertType.INFORMATION
                                                  : Alert.AlertType.WARNING);
                    a.setTitle("MVR — " + res.status());
                    a.setHeaderText(null);
                    a.setContentText(res.message());
                    a.initOwner(owner);
                    a.showAndWait();
                });
            } catch (Exception ex) {
                Platform.runLater(() ->
                    showInfo("MVR", "MVR check failed: " + ex.getMessage()));
            }
        });
    }

    private Label headerLine(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#1A6EF5;");
        return l;
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

    /** Parse hours-as-decimal text (e.g. "38.0") into minutes. -1 on error. */
    private static int parseHoursToMinutes(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.isEmpty()) return 0;
        try { return new BigDecimal(t).multiply(new BigDecimal(60)).intValue(); }
        catch (NumberFormatException ex) { return -1; }
    }

    private static String code(String choiceValue, String def) {
        if (choiceValue == null || choiceValue.isBlank()) return def;
        return choiceValue.substring(0, 1);
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

    private TableColumn<Employee, String> col(String header,
                                               Function<Employee, String> fn, double w) {
        TableColumn<Employee, String> c = new TableColumn<>(header);
        c.setCellValueFactory(p -> new SimpleStringProperty(safe(fn.apply(p.getValue()))));
        c.setPrefWidth(w);
        return c;
    }

    private TableColumn<Employee, String> colInt(String header,
                                                  Function<Employee, Integer> fn, double w) {
        TableColumn<Employee, String> c = new TableColumn<>(header);
        c.setCellValueFactory(p -> new SimpleStringProperty(String.valueOf(fn.apply(p.getValue()))));
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
        f.setPrefWidth(Math.min(maxLen * 9 + 10, 320));
        return f;
    }

    /**
     * Wrap a TextField in an HBox with a magnifier button on the right.
     * F5 (when the field is focused) and the button both open a LookupDialog
     * for the given type; the selected code is written back into the field.
     */
    private Node lookupField(TextField field, LookupDialog.LookupType type) {
        Button btn = new Button("⋯");
        btn.setStyle(
            "-fx-background-color:white;-fx-text-fill:#1A6EF5;" +
            "-fx-border-color:#D0CFC8;-fx-border-width:1;" +
            "-fx-background-radius:0 7 7 0;-fx-border-radius:0 7 7 0;" +
            "-fx-padding:4 12;-fx-cursor:hand;-fx-font-weight:bold;");
        btn.setTooltip(new Tooltip("Lookup (F5)"));
        btn.setFocusTraversable(false);
        btn.setOnAction(e -> openLookup(field, type));

        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F5) openLookup(field, type);
        });

        HBox box = new HBox(0, field, btn);
        HBox.setHgrow(field, Priority.ALWAYS);
        return box;
    }

    private void openLookup(TextField field, LookupDialog.LookupType type) {
        Window owner = field.getScene() != null ? field.getScene().getWindow() : null;
        int coNo = appSession.getCompanyNo();
        LookupDialog dlg = new LookupDialog(
            lookupService, type, coNo, code -> field.setText(code));
        dlg.show(owner);
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

    private static String rateStr(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return v.toPlainString();
    }

    private static String dateStr(LocalDate d) {
        return Employee.isValidDate(d) ? D_FMT.format(d) : "";
    }
}
