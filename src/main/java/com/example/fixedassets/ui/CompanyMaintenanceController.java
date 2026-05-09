package com.example.fixedassets.ui;

import com.example.fixedassets.model.AppSession;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * MENU22 — Company Maintenance.
 *
 * Allows add, change and delete of company records in CPCOYCO.
 * Mirrors the COBOL screen sequence:
 *   P1  → Company list (TableView of all companies)
 *   S0  → Company detail — name, address, ACN, ABN, password
 *   S0A → Registration number
 *   FA  → Fixed Assets settings tab (fa_tax_yr_end_mth, fa_batch_control_flag,
 *           fa_instal_flag, fa_last_batch_no, fa_post_depn_to_cl etc.)
 *   GL/AR/AP/CM → Placeholder tabs (implemented as stubs)
 *
 * CPCOYCO confirmed columns (from CompanyRepository + menu22.pl):
 *   company_no, name1, name2, addr_1, addr_2, addr_3
 *   password, acn_no, abn, user_reg_no
 *   fa_instal_flag, fa_tax_yr_end_mth, fa_batch_control_flag,
 *   fa_last_batch_no, fa_post_depn_to_cl, fa_parent_accting_flag
 *   gl_instal_flag, ar_instal_flag, ap_instal_flag, cm_instal_flag
 */
@Component
public class CompanyMaintenanceController {

    private final JdbcTemplate jdbc;
    private final AppSession   appSession;

    // P1 table
    private TableView<CompanyRow>       table;
    private ObservableList<CompanyRow>  items = FXCollections.observableArrayList();
    private Stage                       mainStage;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "menu22-thread"); t.setDaemon(true); return t;
    });

    // ── Row model ─────────────────────────────────────────────────
    record CompanyRow(int companyNo, String name1, String name2) {}

    public CompanyMaintenanceController(JdbcTemplate jdbc, AppSession appSession) {
        this.jdbc = jdbc; this.appSession = appSession;
    }

    // ══════════════════════════════════════════════════════════════
    // Entry point
    // ══════════════════════════════════════════════════════════════

    public void show(Window owner) {
        mainStage = new Stage();
        mainStage.initOwner(owner);
        mainStage.initModality(Modality.WINDOW_MODAL);
        mainStage.setTitle("Company Maintenance — MENU22");
        mainStage.setMinWidth(820); mainStage.setMinHeight(560);

        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setCenter(buildP1());
        root.setBottom(buildFooter());
        root.setStyle("-fx-background-color:#F2F1EC;");

        mainStage.setScene(new Scene(root, 820, 560));
        loadCompanies();
        mainStage.show();
    }

    // ══════════════════════════════════════════════════════════════
    // P1 — Company list
    // ══════════════════════════════════════════════════════════════

    private VBox buildP1() {
        // Toolbar
        Button btnAdd    = btn("＋ Add",    "#1A6EF5");
        Button btnChange = btn("✎ Change",  "#374151");
        Button btnDelete = btn("✕ Delete",  "#DC2626");
        Button btnRefresh= btn("↺ Refresh", "#374151");

        btnAdd.setOnAction(e -> showCompanyDialog(null));
        btnChange.setOnAction(e -> {
            CompanyRow sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showCompanyDialog(sel);
            else alert("Please select a company to change.");
        });
        btnDelete.setOnAction(e -> {
            CompanyRow sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDelete(sel);
            else alert("Please select a company to delete.");
        });
        btnRefresh.setOnAction(e -> loadCompanies());

        HBox toolbar = new HBox(8, btnAdd, btnChange, btnDelete, new Separator(javafx.geometry.Orientation.VERTICAL), btnRefresh);
        toolbar.setPadding(new Insets(10, 16, 8, 16));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle("-fx-background-color:#FFFFFF;" +
                "-fx-border-color:transparent transparent rgba(0,0,0,0.08) transparent;" +
                "-fx-border-width:0 0 1 0;");

        // Table
        table = new TableView<>(items);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setStyle("-fx-background-color:white;");

        TableColumn<CompanyRow,String> colNo   = col("No",   80, r -> String.valueOf(r.companyNo()));
        TableColumn<CompanyRow,String> colName = col("Company Name", 280, CompanyRow::name1);
        TableColumn<CompanyRow,String> colName2= col("Second Name",  260, CompanyRow::name2);

        table.getColumns().addAll(colNo, colName, colName2);
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null)
                showCompanyDialog(table.getSelectionModel().getSelectedItem());
        });

        VBox panel = new VBox(0, toolbar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        panel.setPadding(new Insets(12));
        panel.setStyle("-fx-background-color:#F2F1EC;");
        VBox.setVgrow(panel, Priority.ALWAYS);
        return panel;
    }

    private void loadCompanies() {
        exec.submit(() -> {
            try {
                List<CompanyRow> rows = loadCompanyRows();
                Platform.runLater(() -> items.setAll(rows));
            } catch (Exception e) {
                Platform.runLater(() -> alert("Could not load companies: " + e.getMessage()));
            }
        });
    }

    private List<CompanyRow> loadCompanyRows() {
        // Confirmed from live DB 2026-03-27:
        //   primary name = name1,  secondary name = name_2 (backtick-quoted)
        try {
            return jdbc.query(
                "SELECT company_no, name1, `name_2` FROM CPCOYCO ORDER BY company_no",
                (rs, i) -> new CompanyRow(
                    rs.getInt("company_no"),
                    nullStr(rs.getString("name1")),
                    nullStr(rs.getString("name_2"))));
        } catch (Exception ex) {
            System.out.println("MENU22: list load failed: " + ex.getMessage());
            return jdbc.query(
                "SELECT company_no, name1 FROM CPCOYCO ORDER BY company_no",
                (rs, i) -> new CompanyRow(
                    rs.getInt("company_no"),
                    nullStr(rs.getString("name1")),
                    ""));
        }
    }


    // ══════════════════════════════════════════════════════════════
    // S0 + FA tab — Company detail dialog
    // ══════════════════════════════════════════════════════════════

    private void showCompanyDialog(CompanyRow existing) {
        boolean isAdd = (existing == null);
        Stage dlg = new Stage();
        dlg.initOwner(mainStage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(isAdd ? "Add Company — MENU22S0" : "Change Company " + existing.companyNo() + " — MENU22S0");
        dlg.setResizable(true);

        // Load full record if changing
        Map<String,Object> rec = new HashMap<>();
        if (!isAdd) {
            try {
                rec = jdbc.queryForMap("SELECT * FROM CPCOYCO WHERE company_no=?",
                    existing.companyNo());
                // Normalise the second-name column → always accessible as "name_2"
                // The actual column name varies by DB version — discover it
                // name_2 is the confirmed column name; backtick it if reading from SELECT *
                if (!rec.containsKey("name_2") && rec.containsKey("name_2 "))
                    rec.put("name_2", rec.get("name_2 "));
                rec.putIfAbsent("name_2", "");
                rec.putIfAbsent("name1", "");
            } catch (Exception e) {
                System.out.println("MENU22: load failed: " + e.getMessage());
            }
        }
        final Map<String,Object> data = rec;

        // ── Tab pane ──────────────────────────────────────────────
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Tab 1 — General
        TextField fCompanyNo = tf(isAdd ? "" : String.valueOf(existing.companyNo()), 3);
        if (!isAdd) fCompanyNo.setEditable(false);
        TextField fName1    = tf(sv(data,"name1"),    50);
        TextField fName2    = tf(sv(data,"name_2"),    50);
        TextField fAddr1    = tf(sv(data,"addr_1"),   35);
        TextField fAddr2    = tf(sv(data,"addr_2"),   35);
        TextField fAddr3    = tf(sv(data,"addr_3"),   35);
        TextField fPassword = tf(sv(data,"password"), 8);
        TextField fAcn      = tf(sv(data,"acn_no"),   12);
        TextField fAbn      = tf(sv(data,"abn"),      16);

        GridPane gGeneral = grid();
        addRow(gGeneral, 0, "Company No:",   fCompanyNo);
        addRow(gGeneral, 1, "Name 1:",       fName1);
        addRow(gGeneral, 2, "Name 2:",       fName2);
        addRow(gGeneral, 3, "Address 1:",    fAddr1);
        addRow(gGeneral, 4, "Address 2:",    fAddr2);
        addRow(gGeneral, 5, "Address 3:",    fAddr3);
        addRow(gGeneral, 6, "Password:",     fPassword);
        addRow(gGeneral, 7, "ACN No:",       fAcn);
        addRow(gGeneral, 8, "ABN:",          fAbn);

        Tab tabGeneral = tab("General", padded(gGeneral));

        // Tab 2 — Fixed Assets
        // fa_instal_flag: 'Y'=installed, 'N'=not, 'O'=owned (no licence)
        ComboBox<String> cboFaInstall = new ComboBox<>();
        cboFaInstall.getItems().addAll("Y — Installed", "N — Not installed", "O — Owned (no licence)");
        String faFlag = sv(data, "fa_instal_flag");
        cboFaInstall.setValue(faFlag.isEmpty() || faFlag.equals("N") ? "N — Not installed"
            : faFlag.equals("Y") ? "Y — Installed" : "O — Owned (no licence)");

        ComboBox<String> cboTaxYrMth = new ComboBox<>();
        String[] months = {"January","February","March","April","May","June",
                           "July","August","September","October","November","December"};
        for (int i = 1; i <= 12; i++) cboTaxYrMth.getItems().add(i + " — " + months[i-1]);
        int taxMth = iv(data, "fa_tax_yr_end_mth");
        cboTaxYrMth.setValue((taxMth >= 1 && taxMth <= 12)
            ? taxMth + " — " + months[taxMth-1] : "6 — June");

        CheckBox chkBatchControl = new CheckBox("Batch control required");
        chkBatchControl.setSelected("Y".equals(sv(data,"fa_batch_control_flag")));

        TextField fLastBatch   = tf(sv(data,"fa_last_batch_no"), 6);
        fLastBatch.setEditable(false);
        fLastBatch.setStyle(fLastBatch.getStyle() + "-fx-background-color:#F0EFE8;");

        CheckBox chkPostDepnCL = new CheckBox("Post depreciation to Cost Ledger");
        chkPostDepnCL.setSelected("Y".equals(sv(data,"fa_post_depn_to_cl")));

        CheckBox chkParentAcct = new CheckBox("Overseas parent accounting");
        chkParentAcct.setSelected("Y".equals(sv(data,"fa_parent_accting_flag")));

        GridPane gFa = grid();
        addRow(gFa, 0, "FA Installed:",        cboFaInstall);
        addRow(gFa, 1, "Tax year end month:",  cboTaxYrMth);
        addRow(gFa, 2, "Last batch no:",       fLastBatch);
        addRow(gFa, 3, "",                     chkBatchControl);
        addRow(gFa, 4, "",                     chkPostDepnCL);
        addRow(gFa, 5, "",                     chkParentAcct);

        Tab tabFa = tab("Fixed Assets", padded(gFa));

        // Tab 3 — Modules (read-only installed flags overview)
        GridPane gModules = grid();
        String[][] modFlags = {
            {"General Ledger",       "gl_instal_flag"},
            {"Accounts Receivable",  "ar_instal_flag"},
            {"Accounts Payable",     "ap_instal_flag"},
            {"Cash Management",      "cm_instal_flag"},
            {"Payroll",              "pa_instal_flag"},
            {"Job Ledger",           "jl_instal_flag"},
            {"Order Processing",     "op_instal_flag"},
            {"Purchasing",           "po_instal_flag"},
            {"Service Management",   "sm_instal_flag"},
            {"Production Mgmt",      "pm_instal_flag"},
        };
        for (int i = 0; i < modFlags.length; i++) {
            CheckBox cb = new CheckBox(modFlags[i][0]);
            cb.setSelected("Y".equals(sv(data, modFlags[i][1])));
            addRow(gModules, i, "", cb);
        }
        Tab tabModules = tab("Modules", padded(gModules));

        tabs.getTabs().addAll(tabGeneral, tabFa, tabModules);

        // ── Buttons ───────────────────────────────────────────────
        Button btnSave   = btn(isAdd ? "Add Company" : "Save Changes", "#1A6EF5");
        Button btnCancel = btn("Cancel", "#374151");
        btnCancel.setOnAction(e -> dlg.close());

        btnSave.setOnAction(e -> {
            String coNoStr = fCompanyNo.getText().trim();
            if (coNoStr.isEmpty()) { alert("Company number is required."); return; }
            int coNo;
            try { coNo = Integer.parseInt(coNoStr); } catch (NumberFormatException ex) {
                alert("Company number must be numeric."); return;
            }
            if (fName1.getText().trim().isEmpty()) { alert("Company name is required."); return; }

            // Extract single-char FA flag
            String faInstVal = cboFaInstall.getValue();
            String faInst = faInstVal.startsWith("Y") ? "Y"
                          : faInstVal.startsWith("O") ? "O" : "N";

            // Extract tax year month number
            int taxMthVal = 6;
            try { taxMthVal = Integer.parseInt(cboTaxYrMth.getValue().split(" ")[0]); }
            catch (Exception ignored) {}

            final int finalCoNo = coNo;
            final int finalTaxMth = taxMthVal;
            final String finalFaInst = faInst;

            btnSave.setDisable(true);
            exec.submit(() -> {
                try {
                    if (isAdd) {
                        // Check not already on file
                        int exists = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM CPCOYCO WHERE company_no=?",
                            Integer.class, finalCoNo);
                        if (exists > 0) {
                            Platform.runLater(() -> {
                                alert("Company " + finalCoNo + " already exists.");
                                btnSave.setDisable(false);
                            }); return;
                        }
                        jdbc.update(
                            "INSERT INTO CPCOYCO (company_no, name1, `name_2`, " +
                            "addr_1, addr_2, addr_3, password, acn_no, abn, " +
                            "fa_instal_flag, fa_tax_yr_end_mth, fa_batch_control_flag, " +
                            "fa_last_batch_no, fa_post_depn_to_cl, fa_parent_accting_flag) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
                            finalCoNo,
                            fName1.getText().trim(), fName2.getText().trim(),
                            fAddr1.getText().trim(), fAddr2.getText().trim(),
                            fAddr3.getText().trim(), fPassword.getText().trim(),
                            fAcn.getText().trim(), fAbn.getText().trim(),
                            finalFaInst, finalTaxMth,
                            chkBatchControl.isSelected() ? "Y" : "N",
                            chkPostDepnCL.isSelected()   ? "Y" : "N",
                            chkParentAcct.isSelected()   ? "Y" : "N");
                    } else {
                        jdbc.update(
                            "UPDATE CPCOYCO SET name1=?, `name_2`=?, " +
                            "addr_1=?, addr_2=?, addr_3=?, password=?, acn_no=?, abn=?, " +
                            "fa_instal_flag=?, fa_tax_yr_end_mth=?, fa_batch_control_flag=?, " +
                            "fa_post_depn_to_cl=?, fa_parent_accting_flag=? " +
                            "WHERE company_no=?",
                            fName1.getText().trim(), fName2.getText().trim(),
                            fAddr1.getText().trim(), fAddr2.getText().trim(),
                            fAddr3.getText().trim(), fPassword.getText().trim(),
                            fAcn.getText().trim(), fAbn.getText().trim(),
                            finalFaInst, finalTaxMth,
                            chkBatchControl.isSelected() ? "Y" : "N",
                            chkPostDepnCL.isSelected()   ? "Y" : "N",
                            chkParentAcct.isSelected()   ? "Y" : "N",
                            existing.companyNo());
                    }
                    Platform.runLater(() -> { dlg.close(); loadCompanies(); });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        alert("Save failed: " + ex.getMessage());
                        btnSave.setDisable(false);
                    });
                }
            });
        });

        HBox btnBar = new HBox(10, btnSave, btnCancel);
        btnBar.setPadding(new Insets(12, 16, 12, 16));
        btnBar.setAlignment(Pos.CENTER_RIGHT);
        btnBar.setStyle("-fx-background-color:#F2F1EC;" +
                "-fx-border-color:rgba(0,0,0,0.08) transparent transparent transparent;" +
                "-fx-border-width:1 0 0 0;");

        BorderPane root = new BorderPane(tabs);
        root.setBottom(btnBar);
        root.setStyle("-fx-background-color:#F2F1EC;");

        dlg.setScene(new Scene(root, 540, 460));
        dlg.show();
    }

    // ══════════════════════════════════════════════════════════════
    // Delete
    // ══════════════════════════════════════════════════════════════

    private void confirmDelete(CompanyRow row) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete company " + row.companyNo() + " — " + row.name1() + "?\n\n" +
            "This will remove the record from CPCOYCO.\n" +
            "All associated asset and transaction data must be removed separately.",
            ButtonType.YES, ButtonType.NO);
        a.setTitle("Delete Company");
        a.setHeaderText("Delete Company " + row.companyNo());
        a.initOwner(mainStage);
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.YES) {
                exec.submit(() -> {
                    try {
                        jdbc.update("DELETE FROM CPCOYCO WHERE company_no=?", row.companyNo());
                        Platform.runLater(this::loadCompanies);
                    } catch (Exception e) {
                        Platform.runLater(() -> alert("Delete failed: " + e.getMessage()));
                    }
                });
            }
        });
    }

    // ══════════════════════════════════════════════════════════════
    // Header / Footer
    // ══════════════════════════════════════════════════════════════

    private HBox buildHeader() {
        Label title = new Label("Company Maintenance");
        title.setStyle("-fx-font-size:15px; -fx-font-weight:bold; -fx-text-fill:#1A1A1A;");
        Label sub = new Label("MENU22  ·  Add, change or delete company records");
        sub.setStyle("-fx-font-size:11px; -fx-text-fill:#888780; -fx-padding:2 0 0 12;");
        HBox hdr = new HBox(8, title, sub);
        hdr.setPadding(new Insets(14, 16, 10, 16));
        hdr.setAlignment(Pos.CENTER_LEFT);
        hdr.setStyle("-fx-background-color:white;" +
                "-fx-border-color:transparent transparent rgba(0,0,0,0.08) transparent;" +
                "-fx-border-width:0 0 1 0;");
        return hdr;
    }

    private HBox buildFooter() {
        Label lbl = new Label("Double-click a company to edit  ·  " +
            appSession.getCompanyNo() + " companies loaded");
        lbl.setStyle("-fx-font-size:10px; -fx-text-fill:#888780;");
        HBox footer = new HBox(lbl);
        footer.setPadding(new Insets(6, 16, 6, 16));
        footer.setStyle("-fx-background-color:white;" +
                "-fx-border-color:rgba(0,0,0,0.08) transparent transparent transparent;" +
                "-fx-border-width:1 0 0 0;");
        return footer;
    }

    // ══════════════════════════════════════════════════════════════
    // UI helpers
    // ══════════════════════════════════════════════════════════════

    private <T> TableColumn<T,String> col(String title, int w, java.util.function.Function<T,String> fn) {
        TableColumn<T,String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new SimpleStringProperty(fn.apply(cd.getValue())));
        c.setPrefWidth(w);
        return c;
    }

    private GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(12); g.setVgap(10);
        ColumnConstraints lc = new ColumnConstraints(140);
        ColumnConstraints fc = new ColumnConstraints();
        fc.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(lc, fc);
        return g;
    }

    private void addRow(GridPane g, int row, String label, javafx.scene.Node field) {
        if (!label.isEmpty()) {
            Label l = new Label(label);
            l.setStyle("-fx-font-size:12px; -fx-text-fill:#374151;");
            l.setAlignment(Pos.CENTER_RIGHT);
            l.setMaxWidth(Double.MAX_VALUE);
            g.add(l, 0, row);
        }
        g.add(field, 1, row);
    }

    private TextField tf(String val, int maxLen) {
        TextField tf = new TextField(val);
        tf.setPrefWidth(maxLen * 8 + 24);
        tf.setMaxWidth(Double.MAX_VALUE);
        tf.setStyle("-fx-background-color:white; -fx-border-color:#D1D5DB;" +
                    "-fx-border-radius:6; -fx-background-radius:6;" +
                    "-fx-font-size:12px; -fx-padding:4 8; -fx-border-width:1.5;");
        return tf;
    }

    private Button btn(String text, String color) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color:" + color + "; -fx-text-fill:white;" +
                   "-fx-font-size:12px; -fx-background-radius:6;" +
                   "-fx-padding:6 14; -fx-cursor:hand;");
        return b;
    }

    private Tab tab(String title, javafx.scene.Node content) {
        Tab t = new Tab(title, content);
        return t;
    }

    private ScrollPane padded(javafx.scene.Node content) {
        VBox box = new VBox(content);
        box.setPadding(new Insets(16));
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color:white;");
        return sp;
    }

    private void alert(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.initOwner(mainStage);
        a.showAndWait();
    }

    private static String sv(Map<String,Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static int iv(Map<String,Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return 0;
        try { return ((Number)v).intValue(); } catch (Exception e) { return 0; }
    }

    /** Null-safe String — returns empty string if the column value is null */
    private static String nullStr(String v) { return v == null ? "" : v.trim(); }
}
