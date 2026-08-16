package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ap.ApReportDataService;
import com.landmarksoftware.service.ap.ApReportDataService.DetailTxnParams;
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

/** APRC09 — Detailed Transaction Listing selection screen. */
@Component
@Scope("prototype")
public class ApDetailedTransactionController implements Initializable {

    private static final String PDF_PATH   = "ap/detailed-transaction-listing";
    private static final String EXCEL_PATH = "ap/detailed-transaction-listing-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ApReportDataService  apReports;

    @FXML private ComboBox<ApReportDataService.CodeName> startSupplier;
    @FXML private ComboBox<ApReportDataService.CodeName> endSupplier;
    @FXML private ComboBox<ApReportDataService.CodeName> subLedger;
    @FXML private ComboBox<LabelValue> dateType;
    @FXML private DatePicker startDate;
    @FXML private DatePicker endDate;
    @FXML private TextField startDocNo;
    @FXML private TextField endDocNo;
    @FXML private TextField batchNo;
    @FXML private TextField taxCode;

    record LabelValue(String label, String value) {
        @Override public String toString() { return label; }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        dateType.setConverter(new StringConverter<>() {
            @Override public String toString(LabelValue o) { return o == null ? "" : o.label(); }
            @Override public LabelValue fromString(String s) { return null; }
        });
        dateType.setItems(FXCollections.observableArrayList(
            new LabelValue("Document date", "D"),
            new LabelValue("Posting date", "P")));
        dateType.getSelectionModel().selectFirst();

        subLedger.setItems(FXCollections.observableArrayList(apReports.getSubLedgers(session)));
        subLedger.getSelectionModel().selectFirst();
        startSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, false)));
        startSupplier.getSelectionModel().selectFirst();
        endSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, false)));
        endSupplier.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        LabelValue dt = dateType.getSelectionModel().getSelectedItem();
        DetailTxnParams params = new DetailTxnParams(
            code(startSupplier),
            code(endSupplier),
            code(subLedger),
            dt == null ? "D" : dt.value(),
            startDate.getValue(),
            endDate.getValue(),
            trimToNull(startDocNo.getText()),
            trimToNull(endDocNo.getText()),
            parseInt(batchNo.getText()),
            trimToNull(taxCode.getText()));

        Map<String, Object> data = apReports.getDetailedTransactionData(session, params, "excel".equals(format));
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No distribution lines matched the selection.");
            return;
        }
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "pdf".equals(format) ? PDF_PATH : EXCEL_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            mapDataSource(rows), format, owner);
        close(e);
    }

    private static String code(ComboBox<ApReportDataService.CodeName> cb) { ApReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem(); String v = c == null ? null : c.code(); return (v == null || v.isBlank()) ? null : v; }

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
