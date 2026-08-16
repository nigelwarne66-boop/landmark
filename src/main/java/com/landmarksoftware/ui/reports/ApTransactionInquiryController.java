package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ap.ApReportDataService;
import com.landmarksoftware.service.ap.ApReportDataService.TxnInquiryParams;
import com.landmarksoftware.service.ap.ApReportDataService.TxnInquiryResult;
import com.landmarksoftware.service.ap.ApReportDataService.TxnInquiryRow;
import com.landmarksoftware.service.ap.ApReportDataService.DistributionRow;
import com.landmarksoftware.ui.FxUtil;
import com.landmarksoftware.ui.ReportsHubController;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ResourceBundle;

/**
 * APTI01 — AP Transaction Inquiry.
 *
 * <p>Interactive inquiry screen (not a JasperReport). Allows the user to select
 * a supplier, date range, doc-type filter and inclusion flags, then displays
 * matching {@code aptrans} rows in a TableView with running totals. Double-click
 * or the "Display" button opens a detail dialog; "Distributions" opens the
 * {@code apdistn} lines for the selected transaction.
 */
@Component
@Scope("prototype")
public class ApTransactionInquiryController implements Initializable {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    @Autowired private AppSession session;
    @Autowired private ApReportDataService apReports;
    @Autowired private ReportsHubController hub;

    @FXML private ComboBox<ApReportDataService.CodeName> supplierPicker;
    @FXML private TextField alphaField;
    @FXML private DatePicker startDate;
    @FXML private DatePicker endDate;
    @FXML private ComboBox<LabelValue> docTypePicker;
    @FXML private CheckBox includeFullyPaid;
    @FXML private CheckBox includeUnposted;

    @FXML private HBox accountBar;
    @FXML private Label lblSupplierName;
    @FXML private Label lblAcctBal;
    @FXML private Label lblAcctStatus;

    @FXML private TableView<TxnInquiryRow> txnTable;
    @FXML private TableColumn<TxnInquiryRow, String>     colDate;
    @FXML private TableColumn<TxnInquiryRow, String>     colType;
    @FXML private TableColumn<TxnInquiryRow, String>     colDocNo;
    @FXML private TableColumn<TxnInquiryRow, BigDecimal> colOrigAmt;
    @FXML private TableColumn<TxnInquiryRow, BigDecimal> colOutstanding;
    @FXML private TableColumn<TxnInquiryRow, String>     colFc;
    @FXML private TableColumn<TxnInquiryRow, String>     colRecon;
    @FXML private TableColumn<TxnInquiryRow, String>     colStatus;

    @FXML private HBox totalsBar;
    @FXML private Label lblTotalDr;
    @FXML private Label lblTotalCr;
    @FXML private Label lblTotal;

    record LabelValue(String label, String value) {
        @Override public String toString() { return label; }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        FxUtil.setAuDate(startDate, endDate);
        // Supplier picker — per-supplier inquiry so remove the "(All suppliers)" entry
        List<ApReportDataService.CodeName> suppliers = apReports.getSuppliers(session, false);
        if (!suppliers.isEmpty() && suppliers.get(0).code().isBlank()) {
            suppliers = new java.util.ArrayList<>(suppliers);
            suppliers.remove(0);
        }
        supplierPicker.setItems(FXCollections.observableArrayList(suppliers));
        PickerAutoComplete.install(supplierPicker);

        // Alpha key typeahead: load all keys and wire popup suggestions.
        installAlphaTypeahead(apReports.getAlphaKeys(session));

        // Doc type filter
        docTypePicker.setItems(FXCollections.observableArrayList(
            new LabelValue("All types", ""),
            new LabelValue("Invoice (I)", "I"),
            new LabelValue("DR Note (D)", "D"),
            new LabelValue("CR Note (C)", "C"),
            new LabelValue("Payment (P)", "P"),
            new LabelValue("Claim (K)", "K"),
            new LabelValue("Void (V)", "V"),
            new LabelValue("Journal (J)", "J")));
        docTypePicker.getSelectionModel().selectFirst();

        // Default date range: end = today; start left blank (= all)
        endDate.setValue(LocalDate.now());

        // Hide account info + totals until first filter
        accountBar.setVisible(false);
        accountBar.setManaged(false);
        totalsBar.setVisible(false);
        totalsBar.setManaged(false);

        // Wire table columns
        colDate.setCellValueFactory(cd -> new SimpleStringProperty(
            cd.getValue().docDate() != null
                ? cd.getValue().docDate().format(DATE_FMT) : ""));

        colType.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().docTypeDesc()));

        colDocNo.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().docNoDisplay()));

        colOrigAmt.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().origAmt()));
        colOrigAmt.setCellFactory(col -> amtCell());

        colOutstanding.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().outstandingAmt()));
        colOutstanding.setCellFactory(col -> amtCell());

        colFc.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().forCurrCode()));

        colRecon.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().reconNoDisplay()));

        colStatus.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().status()));

        // Double-click = display detail
        txnTable.setOnMouseClicked(e -> { if (e.getClickCount() == 2) openDisplay(); });
    }

    private void installAlphaTypeahead(List<String> allKeys) {
        Popup popup = new Popup();
        popup.setAutoHide(true);

        ListView<String> suggestions = new ListView<>();
        suggestions.setPrefWidth(200);
        suggestions.setMaxHeight(160);
        suggestions.setFocusTraversable(true);
        popup.getContent().add(suggestions);

        alphaField.textProperty().addListener((obs, old, q) -> {
            if (q == null || q.isBlank()) { popup.hide(); return; }
            String lower = q.trim().toLowerCase();
            List<String> filtered = new java.util.ArrayList<>();
            for (String k : allKeys) if (k.toLowerCase().startsWith(lower)) filtered.add(k);
            for (String k : allKeys) if (!k.toLowerCase().startsWith(lower) && k.toLowerCase().contains(lower)) filtered.add(k);
            if (filtered.size() > 12) filtered = filtered.subList(0, 12);
            if (filtered.isEmpty()) { popup.hide(); return; }
            suggestions.getItems().setAll(filtered);
            suggestions.getSelectionModel().clearSelection();
            if (!popup.isShowing()) {
                Bounds b = alphaField.localToScreen(alphaField.getBoundsInLocal());
                if (b != null) popup.show(alphaField, b.getMinX(), b.getMaxY() + 1);
            }
        });

        suggestions.setOnMouseClicked(e -> {
            String sel = suggestions.getSelectionModel().getSelectedItem();
            if (sel != null) commitAlpha(popup, sel);
        });

        alphaField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN && popup.isShowing()) {
                suggestions.requestFocus();
                suggestions.getSelectionModel().selectFirst();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                popup.hide();
            }
        });

        alphaField.setOnAction(e -> {
            if (popup.isShowing() && !suggestions.getItems().isEmpty()) {
                String top = suggestions.getSelectionModel().getSelectedItem();
                commitAlpha(popup, top != null ? top : suggestions.getItems().get(0));
            } else {
                onAlphaSearch();
            }
        });

        suggestions.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                String sel = suggestions.getSelectionModel().getSelectedItem();
                if (sel != null) commitAlpha(popup, sel);
            } else if (e.getCode() == KeyCode.ESCAPE) {
                popup.hide();
                alphaField.requestFocus();
            }
        });
    }

    private void commitAlpha(Popup popup, String alphaKey) {
        popup.hide();
        alphaField.setText(alphaKey);
        alphaField.requestFocus();
        onAlphaSearch();
    }

    private void onAlphaSearch() {
        String alpha = alphaField.getText() == null ? "" : alphaField.getText().trim();
        if (alpha.isEmpty()) return;
        List<ApReportDataService.CodeName> matches = apReports.findSuppliersByAlphaKey(session, alpha);
        if (matches.isEmpty()) {
            alert("Alpha key not found", "No supplier has alpha key \"" + alpha + "\".");
            return;
        }
        if (matches.size() > 1) {
            alert("Duplicate alpha key",
                matches.size() + " suppliers share alpha key \"" + alpha
                + "\" — use the supplier picker to select.");
            return;
        }
        String supplierNo = matches.get(0).code();
        for (ApReportDataService.CodeName item : supplierPicker.getItems()) {
            if (supplierNo.equals(item.code())) {
                supplierPicker.getSelectionModel().select(item);
                alphaField.clear();
                return;
            }
        }
        alert("Alpha key not found", "Supplier " + supplierNo + " not in list — try refreshing.");
    }

    @FXML
    void onFilter(ActionEvent e) {
        ApReportDataService.CodeName supplier = supplierPicker.getSelectionModel().getSelectedItem();
        if (supplier == null || supplier.code().isBlank()) {
            alert("Select a supplier", "Please select a supplier before filtering.");
            return;
        }
        LabelValue dt = docTypePicker.getSelectionModel().getSelectedItem();
        TxnInquiryParams params = new TxnInquiryParams(
            supplier.code(),
            startDate.getValue(),
            endDate.getValue(),
            dt == null ? "" : dt.value(),
            includeFullyPaid.isSelected(),
            includeUnposted.isSelected());

        TxnInquiryResult result = apReports.getTransactionInquiry(session, params);

        txnTable.setItems(FXCollections.observableArrayList(result.rows()));

        // Account info
        lblSupplierName.setText(supplier.code() + " — " + result.supplierName());
        lblAcctBal.setText(fmt(result.acctBal()));
        String statusCode = result.acctStatus() == null ? "" : result.acctStatus().trim();
        lblAcctStatus.setText(switch (statusCode) {
            case "H" -> "* Hold Payment *";
            case "N" -> "* No Purchases *";
            case "Y" -> "* No Payments *";
            default  -> "";
        });
        accountBar.setVisible(true);
        accountBar.setManaged(true);

        // Totals
        lblTotalDr.setText("DR: " + fmt(result.totalDr()));
        lblTotalCr.setText("CR: " + fmt(result.totalCr()));
        lblTotal.setText("Total: " + fmt(result.totalGross()));
        totalsBar.setVisible(true);
        totalsBar.setManaged(true);
    }

    @FXML
    void onDisplay(ActionEvent e) {
        openDisplay();
    }

    @FXML
    void onDistributions(ActionEvent e) {
        TxnInquiryRow row = txnTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            alert("No selection", "Select a transaction row first.");
            return;
        }
        showDistributionsDialog(row);
    }

    @FXML
    void onTrxDocs(ActionEvent e) {
        TxnInquiryRow row = txnTable.getSelectionModel().getSelectedItem();
        if (row == null) { alert("No selection", "Select a transaction row first."); return; }
        if (!apReports.isDtInstalled(session)) {
            alert("Document Tracking not installed",
                "Document Tracking is not licensed for this company (cpcoyco.dt_instal_flag != 'Y').");
            return;
        }
        String searchCode = ApReportDataService.buildDtSearchCodeTransaction(
            row.supplierNoRaw(), row.docDateRaw(), row.docTypeRaw(),
            row.retentFlagRaw(), row.docNoRaw());
        String directory = apReports.getDocumentDirectory(session, "TX");
        List<ApReportDataService.DtDocument> docs = apReports.getDocuments(session, 8, searchCode);
        String title = "Documents — " + row.supplierNoRaw() + " / " + row.docNoDisplay();
        showDocumentsDialog(title, directory, docs);
    }

    @FXML
    void onSupplierDocs(ActionEvent e) {
        ApReportDataService.CodeName supplier = supplierPicker.getSelectionModel().getSelectedItem();
        if (supplier == null || supplier.code().isBlank()) {
            alert("No supplier", "Select a supplier first.");
            return;
        }
        if (!apReports.isDtInstalled(session)) {
            alert("Document Tracking not installed",
                "Document Tracking is not licensed for this company (cpcoyco.dt_instal_flag != 'Y').");
            return;
        }
        String searchCode = ApReportDataService.buildDtSearchCodeSupplier(supplier.code());
        String directory = apReports.getDocumentDirectory(session, "SP");
        List<ApReportDataService.DtDocument> docs = apReports.getDocuments(session, 2, searchCode);
        String title = "Documents — Supplier " + supplier.code();
        showDocumentsDialog(title, directory, docs);
    }

    private void showDocumentsDialog(String title, String directory,
                                     List<ApReportDataService.DtDocument> docs) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(title);
        stage.setWidth(620);
        stage.setHeight(380);

        TableView<ApReportDataService.DtDocument> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<ApReportDataService.DtDocument, String> cFile  = new TableColumn<>("File Name");
        TableColumn<ApReportDataService.DtDocument, Integer> cSeq  = new TableColumn<>("Seq");
        TableColumn<ApReportDataService.DtDocument, String> cUser  = new TableColumn<>("Added By");
        TableColumn<ApReportDataService.DtDocument, String> cDate  = new TableColumn<>("Added Date");

        cFile.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().docNo()));
        cSeq .setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().seqNo()));
        cUser.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().addedBy()));
        cDate.setCellValueFactory(cd -> new SimpleStringProperty(
            cd.getValue().addedDate() != null ? cd.getValue().addedDate().format(DATE_FMT) : ""));

        cFile.setPrefWidth(280); cSeq.setPrefWidth(50);
        cUser.setPrefWidth(120); cDate.setPrefWidth(90);
        table.getColumns().addAll(cFile, cSeq, cUser, cDate);
        table.setItems(FXCollections.observableArrayList(docs));

        String dirLabel = directory.isEmpty() ? "(directory not configured in dtpaths)" : directory;
        Label dirInfo = new Label("Directory: " + dirLabel);
        dirInfo.setStyle("-fx-font-size: 11; -fx-text-fill: #555;");
        dirInfo.setPadding(new Insets(4, 10, 4, 10));

        if (docs.isEmpty())
            table.setPlaceholder(new Label("No documents attached to this record."));

        Button open = new Button("Open");
        open.setOnAction(ev -> {
            ApReportDataService.DtDocument sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            String path = directory.isEmpty() ? sel.docNo()
                : (directory.endsWith("\\") || directory.endsWith("/")
                    ? directory + sel.docNo()
                    : directory + java.io.File.separator + sel.docNo());
            try {
                new ProcessBuilder("cmd", "/c", "start", "", path).start();
            } catch (Exception ex) {
                alert("Cannot open file", path + "\n" + ex.getMessage());
            }
        });
        Button close = new Button("Close");
        close.setOnAction(ev -> stage.close());
        HBox footer = new HBox(10, open, close);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(8, 16, 12, 16));

        VBox root = new VBox(dirInfo, table, footer);
        VBox.setVgrow(table, Priority.ALWAYS);
        stage.setScene(new Scene(root));
        stage.showAndWait();
    }

    @FXML
    void onExportExcel(ActionEvent e) {
        if (txnTable.getItems().isEmpty()) {
            alert("No data", "Filter transactions first.");
            return;
        }
        try {
            byte[] data = buildTransactionExcel();
            Window owner = ((Node) e.getSource()).getScene().getWindow();
            hub.saveAndOpen(data, "ap-transaction-inquiry", ".xlsx", owner);
        } catch (Exception ex) {
            alert("Export failed", ex.getMessage());
        }
    }

    private static final DateTimeFormatter XL_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private byte[] buildTransactionExcel() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Transaction Inquiry");

            // Columns match the APRC05 Transaction Listing Excel output
            String[] headers = {
                "Supplier No", "Supplier Name",
                "Doc Date", "Type", "Document No", "Ref", "Batch No",
                "Original Amt", "Tax Amt", "Outstanding",
                "Posting Date", "Due Date", "PO No",
                "FC", "Recon", "Status"
            };
            Row hdrRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++)
                hdrRow.createCell(i).setCellValue(headers[i]);

            int rowNum = 1;
            for (TxnInquiryRow r : txnTable.getItems()) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(s(r.supplierNoRaw()));
                row.createCell(1).setCellValue(s(r.supplierName()));
                row.createCell(2).setCellValue(r.docDate() != null ? r.docDate().format(XL_DATE) : "");
                row.createCell(3).setCellValue(s(r.docTypeDesc()));
                row.createCell(4).setCellValue(s(r.docNoDisplay()));
                row.createCell(5).setCellValue(s(r.ref()));
                row.createCell(6).setCellValue(r.batchNo() > 0 ? String.valueOf(r.batchNo()) : "");
                row.createCell(7).setCellValue(r.origAmt()       != null ? r.origAmt().doubleValue()       : 0d);
                row.createCell(8).setCellValue(r.taxAmt()        != null ? r.taxAmt().doubleValue()        : 0d);
                row.createCell(9).setCellValue(r.outstandingAmt() != null ? r.outstandingAmt().doubleValue() : 0d);
                row.createCell(10).setCellValue(r.postingDate() != null ? r.postingDate().format(XL_DATE) : "");
                row.createCell(11).setCellValue(r.dueDate()     != null ? r.dueDate().format(XL_DATE)     : "");
                row.createCell(12).setCellValue(s(r.poNo()));
                row.createCell(13).setCellValue(s(r.forCurrCode()));
                row.createCell(14).setCellValue(s(r.reconNoDisplay()));
                row.createCell(15).setCellValue(s(r.status()));
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private static String s(String v) { return v == null ? "" : v; }

    @FXML
    void onCancel(ActionEvent e) {
        ((Stage) ((Node) e.getSource()).getScene().getWindow()).close();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void openDisplay() {
        TxnInquiryRow row = txnTable.getSelectionModel().getSelectedItem();
        if (row == null) return;
        showDetailDialog(row);
    }

    private void showDetailDialog(TxnInquiryRow row) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Transaction Detail — " + row.supplierNoRaw() + " / " + row.docNoDisplay());

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(8);
        grid.setPadding(new Insets(20));

        int r = 0;
        addDetailRow(grid, r++, "Supplier",      row.supplierNoRaw());
        addDetailRow(grid, r++, "Doc Date",       row.docDate() != null ? row.docDate().format(DATE_FMT) : "");
        addDetailRow(grid, r++, "Doc Type",       row.docTypeDesc());
        addDetailRow(grid, r++, "Document No",    row.docNoDisplay());
        addDetailRow(grid, r++, "Original Amt",   fmt(row.origAmt()));
        addDetailRow(grid, r++, "Outstanding",    fmt(row.outstandingAmt()));
        addDetailRow(grid, r++, "Gross Amt",      fmt(row.grossAmt()));
        addDetailRow(grid, r++, "FC Code",        row.forCurrCode());
        addDetailRow(grid, r++, "Recon No",       row.reconNoDisplay());
        addDetailRow(grid, r++, "Status",         row.status());

        Button close = new Button("Close");
        close.setOnAction(ev -> stage.close());
        HBox footer = new HBox(close);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(10, 20, 16, 20));

        VBox root = new VBox(grid, footer);
        stage.setScene(new Scene(root));
        stage.setResizable(false);
        stage.showAndWait();
    }

    private static void addDetailRow(GridPane grid, int row, String label, String value) {
        Label lbl = new Label(label + ":");
        lbl.setStyle("-fx-font-weight: bold; -fx-min-width: 120;");
        Label val = new Label(value == null ? "" : value);
        grid.add(lbl, 0, row);
        grid.add(val, 1, row);
    }

    @SuppressWarnings("unchecked")
    private void showDistributionsDialog(TxnInquiryRow row) {
        if (row.docDateRaw() == null) return;

        List<DistributionRow> dists = apReports.getDistributions(
            session,
            row.supplierNoRaw(),
            row.docDateRaw(),
            row.docTypeRaw(),
            row.retentFlagRaw(),
            row.docNoRaw());

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Distributions — " + row.supplierNoRaw() + " / " + row.docNoDisplay());
        stage.setWidth(700);
        stage.setHeight(380);

        TableView<DistributionRow> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // Column order matches COBOL P2 screen: GL Acct | Description | Amount | Tax Amt | Tax Cd | Ref/Costed To
        TableColumn<DistributionRow, Integer>    cLine     = new TableColumn<>("Line");
        TableColumn<DistributionRow, String>     cAcct     = new TableColumn<>("GL Account");
        TableColumn<DistributionRow, String>     cDesc     = new TableColumn<>("Description");
        TableColumn<DistributionRow, BigDecimal> cAmt      = new TableColumn<>("Amount");
        TableColumn<DistributionRow, BigDecimal> cTax      = new TableColumn<>("Tax Amt");
        TableColumn<DistributionRow, String>     cCode     = new TableColumn<>("Tax Cd");
        TableColumn<DistributionRow, String>     cCostedTo = new TableColumn<>("Ref / Costed To");

        cLine    .setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().lineNo()));
        cAcct    .setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().glAcctNo()));
        cDesc    .setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().desc()));
        cAmt     .setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().amt()));
        cAmt     .setCellFactory(col -> amtCell());
        cTax     .setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().taxAmt()));
        cTax     .setCellFactory(col -> amtCell());
        cCode    .setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().taxCode()));
        cCostedTo.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().costedTo()));

        cLine    .setPrefWidth(42);
        cAcct    .setPrefWidth(90);
        cDesc    .setPrefWidth(220);
        cAmt     .setPrefWidth(100);
        cTax     .setPrefWidth(100);
        cCode    .setPrefWidth(52);
        cCostedTo.setPrefWidth(120);

        table.getColumns().addAll(cLine, cAcct, cDesc, cAmt, cTax, cCode, cCostedTo);
        table.setItems(FXCollections.observableArrayList(dists));
        if (dists.isEmpty()) table.setPlaceholder(new Label("No distribution lines for this transaction."));

        Button close = new Button("Close");
        close.setOnAction(ev -> stage.close());
        HBox footer = new HBox(close);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(8, 16, 12, 16));

        VBox root = new VBox(table, footer);
        VBox.setVgrow(table, Priority.ALWAYS);
        stage.setScene(new Scene(root));
        stage.showAndWait();
    }

    private static <T> TableCell<T, BigDecimal> amtCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) {
                    setText(null);
                } else {
                    setText(String.format("%,.2f", v));
                    setStyle("-fx-alignment: CENTER-RIGHT;");
                }
            }
        };
    }

    private static String fmt(BigDecimal v) {
        return v == null ? "0.00" : String.format("%,.2f", v);
    }

    private void alert(String header, String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING);
        a.setTitle(header);
        a.setHeaderText(header);
        a.setContentText(msg);
        a.showAndWait();
    }
}
