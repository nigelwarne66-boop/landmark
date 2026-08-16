package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.po.PoReportDataService;
import com.landmarksoftware.service.po.PoReportDataService.PoInSequenceParams;
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
 * POTL22 family — "Purchase Orders in Sequence". One selection screen with a
 * Sequence chooser that drives the sort order, plus the COBOL F5 dropdowns
 * (location, supplier, PO number). PDF mirrors the COBOL layout; Excel is wide.
 */
@Component
@Scope("prototype")
public class PoInSequenceController implements Initializable {

    private static final String PDF_PATH   = "po/orders-in-sequence";
    private static final String EXCEL_PATH = "po/orders-in-sequence-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private PoReportDataService  poReports;

    @FXML private ComboBox<LabelValue> sequence;
    @FXML private ComboBox<PoReportDataService.CodeName> locNo;
    @FXML private ComboBox<PoReportDataService.CodeName> startPoNo;
    @FXML private ComboBox<PoReportDataService.CodeName> endPoNo;
    @FXML private ComboBox<PoReportDataService.CodeName> startSupplier;
    @FXML private ComboBox<PoReportDataService.CodeName> endSupplier;
    @FXML private DatePicker startDate;
    @FXML private DatePicker endDate;
    @FXML private ComboBox<LabelValue> origCurr;
    @FXML private CheckBox outstandingOnly;

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
        sequence.setConverter(conv());
        sequence.setItems(FXCollections.observableArrayList(
            new LabelValue("Order Number", "ORDER_NO"),
            new LabelValue("Supplier", "SUPPLIER"),
            new LabelValue("Item / Stock Code", "ITEM"),
            new LabelValue("Delivery Date", "DELIVERY_DATE"),
            new LabelValue("Order Date", "ORDER_DATE"),
            new LabelValue("GL Account", "GL_ACCOUNT"),
            new LabelValue("Cost Ledger", "COST_LEDGER"),
            new LabelValue("BA Ledger", "BA_LEDGER")));
        sequence.getSelectionModel().selectFirst();

        origCurr.setConverter(conv());
        origCurr.setItems(FXCollections.observableArrayList(
            new LabelValue("Current order", "C"), new LabelValue("Original order", "O")));
        origCurr.getSelectionModel().selectFirst();

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
        PoInSequenceParams params = new PoInSequenceParams(
            code(locNo),
            parseInt(code(startPoNo)), parseInt(code(endPoNo)),
            code(startSupplier), code(endSupplier),
            startDate.getValue(), endDate.getValue(),
            val(sequence), val(origCurr),
            outstandingOnly.isSelected());

        Map<String, Object> data = poReports.getPurchaseOrdersInSequence(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No purchase orders matched the selection.");
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
