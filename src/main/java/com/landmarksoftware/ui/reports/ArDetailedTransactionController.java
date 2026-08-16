package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ar.ArReportDataService;
import com.landmarksoftware.service.ar.ArReportDataService.DetailTxnParams;
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

/** ARRC09 — Detailed Transaction Listing selection screen. */
@Component
@Scope("prototype")
public class ArDetailedTransactionController implements Initializable {

    private static final String PDF_PATH   = "ar/detailed-transaction-listing";
    private static final String EXCEL_PATH = "ar/detailed-transaction-listing-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ArReportDataService  arReports;

    @FXML private ComboBox<ArReportDataService.CodeName> startCustomer;
    @FXML private ComboBox<ArReportDataService.CodeName> endCustomer;
    @FXML private ComboBox<ArReportDataService.CodeName> subLedger;
    @FXML private DatePicker startDate;
    @FXML private DatePicker endDate;
    @FXML private TextField startDocNo;
    @FXML private TextField endDocNo;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        FxUtil.setAuDate(startDate, endDate);
        subLedger.setItems(FXCollections.observableArrayList(arReports.getSubLedgers(session)));
        subLedger.getSelectionModel().selectFirst();
        startCustomer.setItems(FXCollections.observableArrayList(arReports.getCustomers(session, false)));
        startCustomer.getSelectionModel().selectFirst();
        endCustomer.setItems(FXCollections.observableArrayList(arReports.getCustomers(session, false)));
        endCustomer.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        DetailTxnParams params = new DetailTxnParams(
            code(startCustomer),
            code(endCustomer),
            code(subLedger),
            startDate.getValue(),
            endDate.getValue(),
            trimToNull(startDocNo.getText()),
            trimToNull(endDocNo.getText()));

        Map<String, Object> data = arReports.getDetailedTransactionData(session, params);
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
        String reportPath = "pdf".equals(format) ? PDF_PATH : EXCEL_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            mapDataSource(rows), format, owner);
        close(e);
    }

    private static String code(ComboBox<ArReportDataService.CodeName> cb) {
        ArReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
        String v = c == null ? null : c.code();
        return (v == null || v.isBlank()) ? null : v;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
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
