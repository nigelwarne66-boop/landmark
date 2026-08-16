package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.sm.SmReportDataService;
import com.landmarksoftware.service.sm.SmReportDataService.PurchaseParams;
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

/**
 * SMTL12 — Purchase Analysis. Purchase receipts by item and supplier within the
 * selected filters.
 */
@Component
@Scope("prototype")
public class SmPurchaseAnalysisController implements Initializable {

    private static final String PDF_PATH   = "sm/purchase-analysis";
    private static final String EXCEL_PATH = "sm/purchase-analysis-excel";

    @Autowired private ReportsHubController  hub;
    @Autowired private AppSession            session;
    @Autowired private SmReportDataService   smReports;

    @FXML private ComboBox<SmReportDataService.CodeName> locNo;
    @FXML private ComboBox<SmReportDataService.CodeName> startItem;
    @FXML private ComboBox<SmReportDataService.CodeName> endItem;
    @FXML private ComboBox<SmReportDataService.CodeName> startSupplier;
    @FXML private ComboBox<SmReportDataService.CodeName> endSupplier;
    @FXML private DatePicker startDate;
    @FXML private DatePicker endDate;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        FxUtil.setAuDate(startDate, endDate);
        locNo.setItems(FXCollections.observableArrayList(smReports.getLocations(session)));
        locNo.getSelectionModel().selectFirst();

        // getItems — load once, share both combos
        List<SmReportDataService.CodeName> items = smReports.getItems(session);
        startItem.setItems(FXCollections.observableArrayList(items));
        endItem.setItems(FXCollections.observableArrayList(items));
        startItem.getSelectionModel().selectFirst();
        endItem.getSelectionModel().selectFirst();

        // getSuppliers — load once, share both combos
        List<SmReportDataService.CodeName> suppliers = smReports.getSuppliers(session);
        startSupplier.setItems(FXCollections.observableArrayList(suppliers));
        endSupplier.setItems(FXCollections.observableArrayList(suppliers));
        startSupplier.getSelectionModel().selectFirst();
        endSupplier.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        PurchaseParams params = new PurchaseParams(
            code(locNo),
            code(startItem), code(endItem),
            code(startSupplier), code(endSupplier),
            startDate.getValue(), endDate.getValue());

        Map<String, Object> data = smReports.getPurchaseAnalysis(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No purchase receipts matched the selection.");
            return;
        }
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            mapDataSource(rows), format, owner);
        close(e);
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
