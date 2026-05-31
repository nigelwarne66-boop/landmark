package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.sm.SmReportDataService;
import com.landmarksoftware.service.sm.SmReportDataService.ConsignmentParams;
import com.landmarksoftware.ui.ReportsHubController;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
 * SMTL53 (+56) — Consignment Stock. One list, choose the sort sequence
 * (Customer or Item). Queries smtrans consignment-flagged rows (opordhd/opordln
 * are not in the extract, so this usually returns a warning).
 */
@Component
@Scope("prototype")
public class SmConsignmentStockController implements Initializable {

    private static final String PDF_PATH   = "sm/consignment-stock";
    private static final String EXCEL_PATH = "sm/consignment-stock-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private SmReportDataService  smReports;

    @FXML private ComboBox<LabelValue>                   sequence;
    @FXML private ComboBox<SmReportDataService.CodeName> startCustomer;
    @FXML private ComboBox<SmReportDataService.CodeName> endCustomer;
    @FXML private ComboBox<SmReportDataService.CodeName> startItem;
    @FXML private ComboBox<SmReportDataService.CodeName> endItem;

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
        sequence.setConverter(conv());
        sequence.setItems(FXCollections.observableArrayList(
            new LabelValue("Customer", "CUSTOMER"),
            new LabelValue("Item", "ITEM")));
        sequence.getSelectionModel().selectFirst();

        // Load customers ONCE — back both combos from separate ObservableLists
        List<SmReportDataService.CodeName> customers = smReports.getCustomers(session);
        ObservableList<SmReportDataService.CodeName> custStart = FXCollections.observableArrayList(customers);
        ObservableList<SmReportDataService.CodeName> custEnd   = FXCollections.observableArrayList(customers);
        startCustomer.setItems(custStart);
        endCustomer.setItems(custEnd);
        startCustomer.getSelectionModel().selectFirst();
        endCustomer.getSelectionModel().selectFirst();

        // Load items ONCE — back both combos from separate ObservableLists
        List<SmReportDataService.CodeName> items = smReports.getItems(session);
        ObservableList<SmReportDataService.CodeName> itemsStart = FXCollections.observableArrayList(items);
        ObservableList<SmReportDataService.CodeName> itemsEnd   = FXCollections.observableArrayList(items);
        startItem.setItems(itemsStart);
        endItem.setItems(itemsEnd);
        startItem.getSelectionModel().selectFirst();
        endItem.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        ConsignmentParams params = new ConsignmentParams(
            code(startCustomer), code(endCustomer),
            code(startItem), code(endItem),
            val(sequence));

        Map<String, Object> data = smReports.getConsignmentStock(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No consignment stock matched the selection.");
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

    private static String code(ComboBox<SmReportDataService.CodeName> cb) {
        SmReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
        String v = c == null ? null : c.code();
        return (v == null || v.isBlank()) ? null : v;
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
