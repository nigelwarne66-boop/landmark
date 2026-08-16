package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ap.ApReportDataService;
import com.landmarksoftware.service.ap.ApReportDataService.TxnListingParams;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * APRC05 — Transaction Listing selection screen.
 *
 * <p>PDF renders the COBOL print layout (supplier-grouped, narrow columns,
 * per-doc-type report totals); Excel renders the wide field set. Both fill from
 * the same {@link ApReportDataService#getTransactionListingData} row list via
 * {@link ReportsHubController#runJasperReportWithDataSource}.
 */
@Component
@Scope("prototype")
public class ApTransactionListingController implements Initializable {

    private static final String PDF_PATH   = "ap/transaction-listing";
    private static final String EXCEL_PATH = "ap/transaction-listing-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ApReportDataService  apReports;

    @FXML private ComboBox<LabelValue> printSeq;
    @FXML private ComboBox<ApReportDataService.CodeName> startSupplier;
    @FXML private ComboBox<ApReportDataService.CodeName> endSupplier;

    @FXML private CheckBox invoices;
    @FXML private CheckBox drNotes;
    @FXML private CheckBox crNotes;
    @FXML private CheckBox crClaims;
    @FXML private CheckBox claimReversals;
    @FXML private CheckBox payments;
    @FXML private CheckBox cancelledChqs;
    @FXML private CheckBox balances;

    @FXML private ComboBox<LabelValue> includePaid;
    @FXML private ComboBox<LabelValue> docPostInd;
    @FXML private DatePicker startDate;
    @FXML private DatePicker endDate;
    @FXML private ComboBox<ApReportDataService.CodeName> subLedger;
    @FXML private TextField batchNo;

    @FXML private CheckBox printLines;
    @FXML private CheckBox printPosMatched;

    @FXML private CheckBox unposted;
    @FXML private CheckBox standingInvoice;
    @FXML private CheckBox posted;
    @FXML private CheckBox onHold;

    @FXML private ComboBox<LabelValue> fullyDelivered;
    @FXML private ComboBox<LabelValue> withinTolerance;
    @FXML private ComboBox<LabelValue> paidButFlags;

    record LabelValue(String label, String value) {
        @Override public String toString() { return label; }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        StringConverter<LabelValue> conv = new StringConverter<>() {
            @Override public String toString(LabelValue o) { return o == null ? "" : o.label(); }
            @Override public LabelValue fromString(String s) { return null; }
        };

        printSeq.setConverter(conv);
        printSeq.setItems(FXCollections.observableArrayList(
            new LabelValue("Supplier number", "N"),
            new LabelValue("Supplier alpha key", "A")));
        printSeq.getSelectionModel().selectFirst();

        // pickers
        subLedger.setItems(FXCollections.observableArrayList(apReports.getSubLedgers(session)));
        subLedger.getSelectionModel().selectFirst();
        reloadSuppliers();
        printSeq.valueProperty().addListener((o, was, is) -> reloadSuppliers());

        includePaid.setConverter(conv);
        includePaid.setItems(FXCollections.observableArrayList(
            new LabelValue("No — outstanding only", "N"),
            new LabelValue("Yes — include fully paid", "Y")));
        includePaid.getSelectionModel().selectFirst();

        docPostInd.setConverter(conv);
        docPostInd.setItems(FXCollections.observableArrayList(
            new LabelValue("Document date", "D"),
            new LabelValue("Posting date", "P"),
            new LabelValue("Audit date", "A")));
        docPostInd.getSelectionModel().selectFirst();

        for (ComboBox<LabelValue> cb : List.of(fullyDelivered, withinTolerance)) {
            cb.setConverter(conv);
            cb.setItems(FXCollections.observableArrayList(
                new LabelValue("All", " "),
                new LabelValue("Yes", "Y"),
                new LabelValue("No", "N")));
            cb.getSelectionModel().selectFirst();
        }

        paidButFlags.setConverter(conv);
        paidButFlags.setItems(FXCollections.observableArrayList(
            new LabelValue("Yes", "Y"),
            new LabelValue("No", "N")));
        paidButFlags.getSelectionModel().selectFirst();

        // COBOL APRC05S0 defaults
        invoices.setSelected(true); drNotes.setSelected(true); crNotes.setSelected(true);
        crClaims.setSelected(true); claimReversals.setSelected(true); payments.setSelected(true);
        cancelledChqs.setSelected(true); balances.setSelected(true);
        posted.setSelected(true); onHold.setSelected(true);
        unposted.setSelected(false); standingInvoice.setSelected(false);
        printLines.setSelected(false); printPosMatched.setSelected(false);

        // on-hold sub-options only meaningful when on-hold is included
        onHold.selectedProperty().addListener((o, was, is) -> {
            fullyDelivered.setDisable(!is);
            withinTolerance.setDisable(!is);
            paidButFlags.setDisable(!is);
        });
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        TxnListingParams params = new TxnListingParams(
            val(printSeq),
            code(startSupplier),
            code(endSupplier),
            invoices.isSelected(), drNotes.isSelected(), crNotes.isSelected(), crClaims.isSelected(),
            claimReversals.isSelected(), payments.isSelected(), cancelledChqs.isSelected(), balances.isSelected(),
            val(includePaid),
            val(docPostInd),
            startDate.getValue(),
            endDate.getValue(),
            code(subLedger),
            parseInt(batchNo.getText()),
            printLines.isSelected() ? "Y" : "N",
            printPosMatched.isSelected() ? "Y" : "N",
            unposted.isSelected() ? "Y" : "N",
            standingInvoice.isSelected() ? "Y" : "N",
            posted.isSelected() ? "Y" : "N",
            onHold.isSelected() ? "Y" : "N",
            val(fullyDelivered),
            val(withinTolerance),
            val(paidButFlags));

        Map<String, Object> data = apReports.getTransactionListingData(session, params, "excel".equals(format));

        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data",
                "No transactions matched the selection.");
            return;
        }

        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));

        String reportPath = "pdf".equals(format) ? PDF_PATH : EXCEL_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            mapDataSource(rows), format, owner);
        close(e);
    }

    private void reloadSuppliers() {
        boolean alpha = "A".equals(val(printSeq));
        startSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, alpha)));
        endSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, alpha)));
        startSupplier.getSelectionModel().selectFirst();
        endSupplier.getSelectionModel().selectFirst();
    }

    private static String val(ComboBox<LabelValue> cb) {
        LabelValue v = cb.getSelectionModel().getSelectedItem();
        return v == null ? "" : v.value();
    }

    /** Selected code from a CodeName picker, or null for the "(All)" / empty entry. */
    private static String code(ComboBox<ApReportDataService.CodeName> cb) {
        ApReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
        String v = c == null ? null : c.code();
        return (v == null || v.isBlank()) ? null : v;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static int parseInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ex) { return 0; }
    }

    private void alert(Alert.AlertType type, String header, String msg) {
        Alert a = new Alert(type);
        a.setTitle(header);
        a.setHeaderText(header);
        a.setContentText(msg);
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
