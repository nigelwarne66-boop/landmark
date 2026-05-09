package com.example.fixedassets.payroll.ui;

import com.example.fixedassets.model.AppSession;
import com.example.fixedassets.payroll.model.PayCode;
import com.example.fixedassets.payroll.service.PayCodeService;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * PACD01 — Pay Code Maintenance.
 *
 * Mirrors COBOL PACD01: full CRUD on the pacodes master table.
 *
 * Screen layout (P1 listbox pattern):
 *   top     — header bar with company and page title
 *   toolbar — Add | Edit | Delete | [spacer] | Show Inactive toggle
 *   centre  — TableView of pay codes (pay_code, desc, type, super, taxable)
 *   bottom  — status bar (count + last action)
 *
 * Add/Edit opens a modal dialog with all editable fields.
 * Delete checks isInUse() first — offers Deactivate if referenced.
 *
 * No JDBC in this class. All data access via PayCodeService.
 */
@Component
public class PayCodeMaintenanceController {

    private final PayCodeService payCodeService;
    private final AppSession     appSession;

    // ── UI state ──────────────────────────────────────────────────────────
    private final ObservableList<PayCode> rows     = FXCollections.observableArrayList();
    private TableView<PayCode>            table;
    private Label                         lblStatus;
    private ToggleButton                  btnShowInactive;
    private boolean                       showInactive = false;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pacd01-thread");
        t.setDaemon(true);
        return t;
    });

    public PayCodeMaintenanceController(PayCodeService payCodeService,
                                         AppSession appSession) {
        this.payCodeService = payCodeService;
        this.appSession     = appSession;
    }

    // ── Entry point ───────────────────────────────────────────────────────

    public Scene buildScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(buildHeader());
        root.setCenter(buildContent(stage));
        root.setBottom(buildStatusBar());

        loadList();

        Scene scene = new Scene(root, 820, 560);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    // ── Header ────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("Pay Code Maintenance");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("PACD01 · " + appSession.getCompanyName());
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
        // ── Table ─────────────────────────────────────────────────────────
        table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(table, Priority.ALWAYS);
        table.setPlaceholder(new Label("No pay codes found for this company."));

        table.getColumns().addAll(List.of(
            col("Code",        pc -> pc.payCode,         90),
            col("Description", pc -> pc.desc1,           240),
            col("Type",        pc -> pc.payTypeLabel(),  100),
            col("Super",       pc -> "Y".equals(pc.superFlag) ? "✓" : "",  55),
            col("Taxable",     pc -> "Y".equals(pc.incomeTaxFlag) ? "✓" : "", 65),
            col("Std Rate",    pc -> rateStr(pc.stdRate),  90),
            col("Std Amount",  pc -> rateStr(pc.stdAmount), 90),
            col("GL Code",     pc -> pc.glCode,           90),
            col("Active",      pc -> "N".equals(pc.activeFlag) ? "Inactive" : "", 70)
        ));

        // Style inactive rows differently
        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(PayCode pc, boolean empty) {
                super.updateItem(pc, empty);
                setStyle("N".equals(pc == null ? "" : pc.activeFlag)
                    ? "-fx-text-fill:#AAAAAA;" : "");
            }
        });

        // Double-click to edit
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null)
                openDialog(table.getSelectionModel().getSelectedItem(), stage);
        });

        // ── Toolbar ───────────────────────────────────────────────────────
        Button btnAdd  = btnPrimary("+ Add");
        Button btnEdit = btnSecondary("✎ Edit");
        Button btnDel  = btnDanger("✕ Delete");
        Button btnRef  = btnSecondary("↺");
        btnShowInactive = new ToggleButton("Show Inactive");
        btnShowInactive.setStyle(
            "-fx-background-color:white;-fx-border-color:#D0CFC8;" +
            "-fx-background-radius:7;-fx-border-radius:7;-fx-padding:5 12;-fx-cursor:hand;");

        btnAdd.setOnAction(e -> openDialog(null, stage));
        btnEdit.setOnAction(e -> {
            PayCode sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openDialog(sel, stage);
            else showInfo("Edit", "Select a pay code to edit.");
        });
        btnDel.setOnAction(e -> {
            PayCode sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) confirmDelete(sel);
            else showInfo("Delete", "Select a pay code to delete.");
        });
        btnRef.setOnAction(e -> loadList());
        btnShowInactive.setOnAction(e -> {
            showInactive = btnShowInactive.isSelected();
            loadList();
        });

        HBox toolbar = new HBox(8,
            btnAdd, btnEdit, btnDel,
            new Separator(Orientation.VERTICAL),
            btnRef, btnShowInactive);
        toolbar.setPadding(new Insets(10, 16, 10, 16));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle(
            "-fx-background-color:#F8F8F6;" +
            "-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;" +
            "-fx-border-width:0 0 0.5 0;");

        // ── Type filter tabs ──────────────────────────────────────────────
        HBox typeFilter = buildTypeFilter();

        return new VBox(0, toolbar, typeFilter, table);
    }

    private HBox buildTypeFilter() {
        HBox bar = new HBox(0);
        bar.setStyle("-fx-background-color:#FFFFFF;" +
            "-fx-border-color:transparent transparent rgba(0,0,0,.08) transparent;" +
            "-fx-border-width:0 0 0.5 0;");

        String[] labels = {"All", "Income", "Allowance", "Deduction", "Tax", "Super"};
        ToggleGroup tg = new ToggleGroup();
        for (int i = 0; i < labels.length; i++) {
            final int typeFilter = i; // 0 = All, 1-5 = specific type
            ToggleButton tb = new ToggleButton(labels[i]);
            tb.setToggleGroup(tg);
            tb.setStyle(
                "-fx-background-color:transparent;-fx-border-color:transparent;" +
                "-fx-padding:8 14;-fx-cursor:hand;-fx-font-size:12px;");
            tb.selectedProperty().addListener((o, ov, nv) -> {
                if (nv) filterByType(typeFilter);
            });
            if (i == 0) tb.setSelected(true);
            bar.getChildren().add(tb);
        }
        return bar;
    }

    /** Filter the visible rows by pay type. 0 = show all. */
    private void filterByType(int typeFilter) {
        exec.submit(() -> {
            try {
                List<PayCode> data = typeFilter == 0
                    ? payCodeService.findAll(appSession.getCompanyNo())
                    : payCodeService.findByType(appSession.getCompanyNo(), typeFilter);
                List<PayCode> filtered = showInactive ? data
                    : data.stream().filter(PayCode::isActive).toList();
                Platform.runLater(() -> {
                    rows.setAll(filtered);
                    status(filtered.size() + " code(s)", false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status("Load error: " + ex.getMessage(), true));
            }
        });
    }

    // ── Data operations ───────────────────────────────────────────────────

    private void loadList() {
        status("Loading…", false);
        filterByType(0);
    }

    private void confirmDelete(PayCode pc) {
        exec.submit(() -> {
            boolean inUse = payCodeService.isInUse(appSession.getCompanyNo(), pc.payCode);
            Platform.runLater(() -> {
                if (inUse) {
                    Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                        "Pay code " + pc.payCode + " is referenced in pay history.\n\n" +
                        "It cannot be deleted. Mark it as Inactive instead?",
                        new ButtonType("Mark Inactive"), ButtonType.CANCEL);
                    a.setTitle("Pay Code In Use");
                    a.setHeaderText(null);
                    a.showAndWait().ifPresent(btn -> {
                        if ("Mark Inactive".equals(btn.getText())) {
                            exec.submit(() -> {
                                payCodeService.deactivate(appSession.getCompanyNo(), pc.payCode);
                                Platform.runLater(() -> { loadList(); status("Deactivated: " + pc.payCode, false); });
                            });
                        }
                    });
                } else {
                    Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                        "Delete pay code " + pc.payCode + " — " + pc.desc1 + "?",
                        ButtonType.YES, ButtonType.NO);
                    a.setTitle("Confirm Delete");
                    a.setHeaderText(null);
                    a.showAndWait().ifPresent(btn -> {
                        if (btn == ButtonType.YES) {
                            exec.submit(() -> {
                                payCodeService.delete(appSession.getCompanyNo(), pc.payCode);
                                Platform.runLater(() -> { loadList(); status("Deleted: " + pc.payCode, false); });
                            });
                        }
                    });
                }
            });
        });
    }

    // ── Add / Edit dialog ─────────────────────────────────────────────────

    private void openDialog(PayCode existing, Window owner) {
        boolean isAdd = (existing == null);
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(isAdd ? "Add Pay Code — PACD01" : "Edit Pay Code — PACD01");
        dlg.setResizable(false);

        // ── Fields ────────────────────────────────────────────────────────
        TextField fCode  = tf(existing != null ? existing.payCode       : "", 10);
        TextField fDesc  = tf(existing != null ? existing.desc1         : "", 40);
        fCode.setEditable(isAdd);
        fCode.setDisable(!isAdd);  // PK — not editable on Edit

        // Pay type
        ChoiceBox<String> cbType = new ChoiceBox<>();
        cbType.getItems().addAll(
            "1 — Income", "2 — Allowance", "3 — Deduction", "4 — Tax", "5 — Super");
        int existingType = existing != null ? existing.payType : 1;
        cbType.getSelectionModel().select(existingType - 1);
        cbType.setPrefWidth(200);

        CheckBox cbSuper  = new CheckBox("Counts toward superannuation");
        CheckBox cbTaxable= new CheckBox("Income taxable");
        CheckBox cbActive = new CheckBox("Active");
        cbSuper.setSelected("Y".equals(existing != null ? existing.superFlag : "N"));
        cbTaxable.setSelected("Y".equals(existing != null ? existing.incomeTaxFlag : "Y"));
        cbActive.setSelected(existing == null || !"N".equals(existing.activeFlag));

        TextField fRate   = tf(existing != null ? decStr(existing.stdRate)   : "", 12);
        TextField fAmount = tf(existing != null ? decStr(existing.stdAmount) : "", 12);
        TextField fGl     = tf(existing != null ? existing.glCode : "", 20);
        TextArea  taNote  = new TextArea(existing != null ? existing.notes : "");
        taNote.setPrefRowCount(3);
        taNote.setPrefWidth(300);
        taNote.setWrapText(true);

        // ── Type-aware field hints ────────────────────────────────────────
        Label lRateHint = hint("e.g. 0.0975 for 9.75%");
        Label lAmtHint  = hint("Fixed $ amount per period");
        cbType.valueProperty().addListener((o, ov, nv) -> {
            if (nv == null) return;
            int t = cbType.getSelectionModel().getSelectedIndex() + 1;
            cbSuper.setDisable(t != 5);
            if (t == 4) { lRateHint.setText("Tax rate (e.g. 0.325 for 32.5%)"); }
            else if (t == 5) { lRateHint.setText("Super rate (e.g. 0.115 for 11.5%)"); cbSuper.setSelected(true); }
            else { lRateHint.setText("Standard rate (leave blank if variable)"); }
        });
        // Trigger once to init
        cbType.getSelectionModel().select(existingType - 1);

        // ── Layout ────────────────────────────────────────────────────────
        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(10); form.setPadding(new Insets(20));
        int row = 0;

        Label lHdr = new Label("Pay Code Details");
        lHdr.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#1A6EF5;");
        form.add(lHdr, 0, row, 2, 1); row++;

        addFormRow(form, row++, "Pay Code *:",   fCode);
        addFormRow(form, row++, "Description *:", fDesc);
        addFormRow(form, row++, "Pay Type *:",   cbType);

        Label lBehav = new Label("Behaviour");
        lBehav.setStyle("-fx-font-size:12px;-fx-font-weight:bold;-fx-text-fill:#374151;-fx-padding:8 0 0 0;");
        form.add(lBehav, 0, row, 2, 1); row++;

        form.add(cbTaxable, 0, row, 2, 1); row++;
        form.add(cbSuper,   0, row, 2, 1); row++;
        form.add(cbActive,  0, row, 2, 1); row++;

        Label lRates = new Label("Rates / Amounts");
        lRates.setStyle("-fx-font-size:12px;-fx-font-weight:bold;-fx-text-fill:#374151;-fx-padding:8 0 0 0;");
        form.add(lRates, 0, row, 2, 1); row++;

        addFormRowWithHint(form, row++, "Std Rate:",   fRate, lRateHint);
        addFormRowWithHint(form, row++, "Std Amount:", fAmount, lAmtHint);
        addFormRow(form, row++, "GL Code:",            fGl);
        addFormRow(form, row++, "Notes:",              taNote);

        // ── Buttons ───────────────────────────────────────────────────────
        Button btnSave   = btnPrimary(isAdd ? "Add" : "Save");
        Button btnCancel = btnSecondary("Cancel");
        btnSave.setDefaultButton(true);
        btnCancel.setOnAction(e -> dlg.close());

        btnSave.setOnAction(e -> {
            // ── Validation ────────────────────────────────────────────────
            String code = fCode.getText().trim().toUpperCase();
            String desc = fDesc.getText().trim();
            if (code.isEmpty()) { markError(fCode, "Pay Code is required."); return; }
            if (code.length() > 10) { markError(fCode, "Pay Code must be 10 characters or fewer."); return; }
            if (desc.isEmpty())  { markError(fDesc, "Description is required."); return; }
            clearError(fCode); clearError(fDesc);

            BigDecimal rate = BigDecimal.ZERO, amount = BigDecimal.ZERO;
            try { if (!fRate.getText().trim().isEmpty()) rate = new BigDecimal(fRate.getText().trim()); }
            catch (NumberFormatException ex) { markError(fRate, "Rate must be a number (e.g. 0.115)."); return; }
            try { if (!fAmount.getText().trim().isEmpty()) amount = new BigDecimal(fAmount.getText().trim()); }
            catch (NumberFormatException ex) { markError(fAmount, "Amount must be a number."); return; }

            // ── Build DTO ─────────────────────────────────────────────────
            PayCode pc = new PayCode();
            pc.payCode       = code;
            pc.desc1         = desc;
            pc.payType       = cbType.getSelectionModel().getSelectedIndex() + 1;
            pc.superFlag     = cbSuper.isSelected()   ? "Y" : "N";
            pc.incomeTaxFlag = cbTaxable.isSelected() ? "Y" : "N";
            pc.activeFlag    = cbActive.isSelected()  ? "Y" : "N";
            pc.stdRate       = rate;
            pc.stdAmount     = amount;
            pc.glCode        = fGl.getText().trim();
            pc.notes         = taNote.getText().trim();

            int coNo = appSession.getCompanyNo();

            exec.submit(() -> {
                try {
                    if (isAdd) {
                        if (payCodeService.exists(coNo, code)) {
                            Platform.runLater(() ->
                                showAlert("Duplicate", "Pay code " + code + " already exists."));
                            return;
                        }
                        payCodeService.insert(coNo, pc);
                    } else {
                        payCodeService.update(coNo, pc);
                    }
                    Platform.runLater(() -> {
                        status((isAdd ? "Added: " : "Updated: ") + code, false);
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

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setBorder(null);
        VBox root = new VBox(0, scroll, btnBar);
        dlg.setScene(new Scene(root, 480, 640));
        dlg.showAndWait();
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

    private TableColumn<PayCode, String> col(String header,
                                               Function<PayCode, String> fn, double w) {
        TableColumn<PayCode, String> c = new TableColumn<>(header);
        c.setCellValueFactory(p -> new SimpleStringProperty(fn.apply(p.getValue())));
        c.setPrefWidth(w);
        return c;
    }

    private void addFormRow(GridPane g, int row, String label, javafx.scene.Node ctrl) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(130);
        g.add(l, 0, row);
        g.add(ctrl, 1, row);
    }

    private void addFormRowWithHint(GridPane g, int row, String label,
                                     javafx.scene.Node ctrl, Label hint) {
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
        f.setPrefWidth(Math.min(maxLen * 9 + 10, 300));
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

    private static void markError(TextField fld, String msg) {
        fld.setStyle("-fx-border-color:#DC2626;-fx-border-width:2;");
        fld.requestFocus(); fld.selectAll();
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setTitle("Validation"); a.setHeaderText(null); a.showAndWait();
    }

    private static void clearError(TextField fld) {
        fld.setStyle("-fx-border-color:#D1D5DB;-fx-border-width:1.5;");
    }

    private void showAlert(String title, String msg) {
        new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK)
            {{ setTitle(title); setHeaderText(null); }}.showAndWait();
    }

    private void showInfo(String title, String msg) {
        new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK)
            {{ setTitle(title); setHeaderText(null); }}.showAndWait();
    }

    private static String decStr(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return v.stripTrailingZeros().toPlainString();
    }

    private static String rateStr(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) return "";
        return v.toPlainString();
    }
}
