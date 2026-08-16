package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ar.ArReportDataService;
import com.landmarksoftware.service.ar.ArReportDataService.TxnListingParams;
import com.landmarksoftware.ui.FxUtil;
import com.landmarksoftware.ui.ReportsHubController;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRField;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * ARRC05 — Transaction Listing selection screen (AR twin of APRC05).
 *
 * <p>PDF renders the COBOL print layout (customer-grouped, per-doc-type report
 * totals); Excel renders the wide field set. Both fill from the same
 * {@link ArReportDataService#getTransactionListingData} row list via
 * {@link ReportsHubController#runJasperReportWithDataSource}.
 */
@Component
@Scope("prototype")
public class ArTransactionListingController implements Initializable {

    private static final String PDF_PATH   = "ar/transaction-listing";
    private static final String EXCEL_PATH = "ar/transaction-listing-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ArReportDataService  arReports;

    @FXML private ComboBox<LabelValue> printSeq;
    @FXML private ComboBox<ArReportDataService.CodeName> startCustomer;
    @FXML private ComboBox<ArReportDataService.CodeName> endCustomer;
    @FXML private ComboBox<ArReportDataService.CodeName> subLedger;

    @FXML private CheckBox invoices;
    @FXML private CheckBox drNotes;
    @FXML private CheckBox crNotes;
    @FXML private CheckBox payments;
    @FXML private CheckBox dishonourChqs;
    @FXML private CheckBox balances;

    @FXML private ComboBox<LabelValue> includePaid;
    @FXML private ComboBox<LabelValue> docPostInd;
    @FXML private DatePicker startDate;
    @FXML private DatePicker endDate;
    @FXML private TextField batchNo;

    @FXML private CheckBox holdOnly;
    @FXML private ComboBox<LabelValue> detailSummary;
    @FXML private CheckBox excludeUnconfirmed;

    @FXML private CheckBox activeAccts;
    @FXML private CheckBox noSalesAccts;
    @FXML private CheckBox onHoldAccts;
    @FXML private CheckBox inactiveAccts;

    @FXML private CheckBox printLines;
    @FXML private CheckBox includeArchived;

    record LabelValue(String label, String value) {
        @Override public String toString() { return label; }
    }

    private StringConverter<LabelValue> conv() {
        return new StringConverter<>() {
            @Override public String toString(LabelValue o) { return o == null ? "" : o.label(); }
            @Override public LabelValue fromString(String s) { return null; }
        };
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        FxUtil.setAuDate(startDate, endDate);
        printSeq.setConverter(conv());
        printSeq.setItems(FXCollections.observableArrayList(
            new LabelValue("Customer number", "N"), new LabelValue("Alpha key", "A")));
        printSeq.getSelectionModel().selectFirst();

        subLedger.setItems(FXCollections.observableArrayList(arReports.getSubLedgers(session)));
        subLedger.getSelectionModel().selectFirst();
        reloadCustomers();
        printSeq.valueProperty().addListener((o, was, is) -> reloadCustomers());

        includePaid.setConverter(conv());
        includePaid.setItems(FXCollections.observableArrayList(
            new LabelValue("No — outstanding only", "N"), new LabelValue("Yes — include fully paid", "Y")));
        includePaid.getSelectionModel().selectFirst();

        docPostInd.setConverter(conv());
        docPostInd.setItems(FXCollections.observableArrayList(
            new LabelValue("Document date", "D"), new LabelValue("Posting date", "P"),
            new LabelValue("Due date", "U"), new LabelValue("Audit date", "A")));
        docPostInd.getSelectionModel().selectFirst();

        detailSummary.setConverter(conv());
        detailSummary.setItems(FXCollections.observableArrayList(
            new LabelValue("Detail transactions", "D"), new LabelValue("Customer summary", "S")));
        detailSummary.getSelectionModel().selectFirst();

        // COBOL ARRC05S0 defaults
        invoices.setSelected(true); drNotes.setSelected(true); crNotes.setSelected(true);
        payments.setSelected(true); dishonourChqs.setSelected(true); balances.setSelected(true);
        excludeUnconfirmed.setSelected(true);
        activeAccts.setSelected(true); noSalesAccts.setSelected(true); onHoldAccts.setSelected(true);
        inactiveAccts.setSelected(false);
        holdOnly.setSelected(false); printLines.setSelected(false); includeArchived.setSelected(false);
    }

    private void reloadCustomers() {
        boolean alpha = "A".equals(val(printSeq));
        startCustomer.setItems(FXCollections.observableArrayList(arReports.getCustomers(session, alpha)));
        endCustomer.setItems(FXCollections.observableArrayList(arReports.getCustomers(session, alpha)));
        startCustomer.getSelectionModel().selectFirst();
        endCustomer.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        TxnListingParams params = new TxnListingParams(
            val(printSeq),
            code(startCustomer), code(endCustomer),
            code(subLedger),
            invoices.isSelected(), drNotes.isSelected(), crNotes.isSelected(),
            payments.isSelected(), dishonourChqs.isSelected(), balances.isSelected(),
            val(includePaid),
            val(docPostInd),
            startDate.getValue(), endDate.getValue(),
            parseInt(batchNo.getText()),
            holdOnly.isSelected() ? "Y" : "N",
            val(detailSummary),
            excludeUnconfirmed.isSelected() ? "Y" : "N",
            activeAccts.isSelected(), noSalesAccts.isSelected(), onHoldAccts.isSelected(), inactiveAccts.isSelected(),
            printLines.isSelected() ? "Y" : "N",
            includeArchived.isSelected() ? "Y" : "N");

        Map<String, Object> data = arReports.getTransactionListingData(session, params, "excel".equals(format));
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No transactions matched the selection.");
            return;
        }
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            mapDataSource(rows), format, owner);
        close(e);
    }

    private static String val(ComboBox<LabelValue> cb) {
        LabelValue v = cb.getSelectionModel().getSelectedItem();
        return v == null ? "" : v.value();
    }

    private static String code(ComboBox<ArReportDataService.CodeName> cb) {
        ArReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
        String v = c == null ? null : c.code();
        return (v == null || v.isBlank()) ? null : v;
    }

    private static int parseInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ex) { return 0; }
    }

    private void alert(Alert.AlertType type, String header, String msg) {
        Alert a = new Alert(type);
        a.setTitle(header); a.setHeaderText(header); a.setContentText(msg);
        a.showAndWait();
    }

    private void close(ActionEvent e) {
        ((Stage) ((Node) e.getSource()).getScene().getWindow()).close();
    }

    private static JRDataSource mapDataSource(java.util.List<java.util.Map<String, Object>> rows) {
        return new JRDataSource() {
            private final java.util.Iterator<java.util.Map<String, Object>> it = rows.iterator();
            private java.util.Map<String, Object> current;
            @Override public boolean next() { if (!it.hasNext()) return false; current = it.next(); return true; }
            @Override public Object getFieldValue(JRField f) { return current.get(f.getName()); }
        };
    }
}
