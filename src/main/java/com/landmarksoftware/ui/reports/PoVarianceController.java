package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.po.PoReportDataService;
import com.landmarksoftware.service.po.PoReportDataService.VarianceParams;
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
 * POTL28 — Order Delivery/Invoice Variance.
 * Per PO line: delivered value vs invoiced value, and the variance.
 */
@Component
@Scope("prototype")
public class PoVarianceController implements Initializable {

    private static final String PDF_PATH   = "po/delivery-invoice-variance";
    private static final String EXCEL_PATH = "po/delivery-invoice-variance-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private PoReportDataService  poReports;

    @FXML private ComboBox<PoReportDataService.CodeName> locNo;
    @FXML private ComboBox<PoReportDataService.CodeName> startSupplier;
    @FXML private ComboBox<PoReportDataService.CodeName> endSupplier;
    @FXML private ComboBox<PoReportDataService.CodeName> startPoNo;
    @FXML private ComboBox<PoReportDataService.CodeName> endPoNo;
    @FXML private ComboBox<LabelValue> printSeq;
    @FXML private CheckBox excludeCompleted;

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
        printSeq.setConverter(conv());
        printSeq.setItems(FXCollections.observableArrayList(
            new LabelValue("Order number", "P"),
            new LabelValue("Supplier",     "S")));
        printSeq.getSelectionModel().selectFirst();

        locNo.setItems(FXCollections.observableArrayList(poReports.getLocations(session)));
        locNo.getSelectionModel().selectFirst();
        reloadPoNumbers();
        locNo.valueProperty().addListener((o, was, is) -> reloadPoNumbers());

        startSupplier.setItems(FXCollections.observableArrayList(poReports.getSuppliers(session)));
        endSupplier.setItems(FXCollections.observableArrayList(poReports.getSuppliers(session)));
        startSupplier.getSelectionModel().selectFirst();
        endSupplier.getSelectionModel().selectFirst();
    }

    private void reloadPoNumbers() {
        startPoNo.setItems(FXCollections.observableArrayList(poReports.getPoNumbers(session, code(locNo))));
        endPoNo.setItems(FXCollections.observableArrayList(poReports.getPoNumbers(session, code(locNo))));
        startPoNo.getSelectionModel().selectFirst();
        endPoNo.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        VarianceParams params = new VarianceParams(
            code(locNo),
            code(startSupplier), code(endSupplier),
            parseInt(code(startPoNo)), parseInt(code(endPoNo)),
            val(printSeq),
            excludeCompleted.isSelected());

        Map<String, Object> data = poReports.getDeliveryInvoiceVariance(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No purchase order variance lines matched the selection.");
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

    private static String code(ComboBox<PoReportDataService.CodeName> cb) {
        PoReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
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
