package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ar.ArReportDataService;
import com.landmarksoftware.service.ar.ArReportDataService.DocNumberParams;
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
 * ARTL20 — Document Number register selection screen.
 *
 * <p>Searches ardocno with optional doc-no / date / customer / source / batch filters.
 * Sort sequence: document number (default), customer, or order number.
 */
@Component
@Scope("prototype")
public class ArDocumentNumberController implements Initializable {

    private static final String PDF_PATH   = "ar/document-number";
    private static final String EXCEL_PATH = "ar/document-number-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ArReportDataService  arReports;

    @FXML private TextField                              startDocNo;
    @FXML private TextField                              endDocNo;
    @FXML private DatePicker                             startDate;
    @FXML private DatePicker                             endDate;
    @FXML private ComboBox<ArReportDataService.CodeName> startCustomer;
    @FXML private ComboBox<ArReportDataService.CodeName> endCustomer;
    @FXML private ComboBox<LabelValue>                   source;
    @FXML private TextField                              batchNo;
    @FXML private ComboBox<LabelValue>                   reportSeq;

    record LabelValue(String label, String value) {
        @Override public String toString() { return label; }
    }

    private StringConverter<LabelValue> conv() {
        return new StringConverter<>() {
            @Override public String toString(LabelValue o)    { return o == null ? "" : o.label(); }
            @Override public LabelValue fromString(String s)  { return null; }
        };
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        source.setConverter(conv());
        source.setItems(FXCollections.observableArrayList(
            new LabelValue("All", ""),
            new LabelValue("AR", "AR"),
            new LabelValue("OP", "OP"),
            new LabelValue("CL", "CL")));
        source.getSelectionModel().selectFirst();

        reportSeq.setConverter(conv());
        reportSeq.setItems(FXCollections.observableArrayList(
            new LabelValue("Document number", "D"),
            new LabelValue("Customer", "C"),
            new LabelValue("Order number", "O")));
        reportSeq.getSelectionModel().selectFirst();   // default "D"

        List<ArReportDataService.CodeName> customers = arReports.getCustomers(session, false);
        startCustomer.setItems(FXCollections.observableArrayList(customers));
        endCustomer.setItems(FXCollections.observableArrayList(customers));
        startCustomer.getSelectionModel().selectFirst();
        endCustomer.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        DocNumberParams params = new DocNumberParams(
            blank(startDocNo.getText()), blank(endDocNo.getText()),
            startDate.getValue(), endDate.getValue(),
            code(startCustomer), code(endCustomer),
            val(source),
            parseInt(batchNo.getText()),
            val(reportSeq));

        Map<String, Object> data = arReports.getDocumentNumberData(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No document-number records matched the selection.");
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

    private static String blank(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim();
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
