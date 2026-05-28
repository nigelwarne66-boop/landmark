package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.sm.SmReportDataService;
import com.landmarksoftware.service.sm.SmReportDataService.SerialParams;
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
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * SMTL27 — Serial/Batch History. Movement history by serial/batch number.
 * The serial tables (smserno/smsetrx) are not present in the current extract,
 * so the service always returns a warning — the controller handles this cleanly.
 */
@Component
@Scope("prototype")
public class SmSerialBatchHistoryController implements Initializable {

    private static final String PDF_PATH   = "sm/serial-batch-history";
    private static final String EXCEL_PATH = "sm/serial-batch-history-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private SmReportDataService  smReports;

    @FXML private ComboBox<SmReportDataService.CodeName> locNo;
    @FXML private ComboBox<SmReportDataService.CodeName> startItem;
    @FXML private ComboBox<SmReportDataService.CodeName> endItem;
    @FXML private TextField startSerial;
    @FXML private TextField endSerial;
    @FXML private DatePicker startDate;
    @FXML private DatePicker endDate;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        locNo.setItems(FXCollections.observableArrayList(smReports.getLocations(session)));
        locNo.getSelectionModel().selectFirst();

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
        SerialParams params = new SerialParams(
            code(locNo), code(startItem), code(endItem),
            text(startSerial), text(endSerial),
            startDate.getValue(), endDate.getValue());

        Map<String, Object> data = smReports.getSerialBatchHistory(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No serial/batch history matched the selection.");
            return;
        }
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            new JRBeanCollectionDataSource(rows), format, owner);
        close(e);
    }

    private static String code(ComboBox<SmReportDataService.CodeName> cb) {
        SmReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
        String v = c == null ? null : c.code();
        return (v == null || v.isBlank()) ? null : v;
    }

    private static String text(TextField tf) {
        String v = tf.getText();
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private void alert(Alert.AlertType type, String header, String msg) {
        Alert a = new Alert(type);
        a.setTitle(header); a.setHeaderText(header); a.setContentText(msg);
        a.showAndWait();
    }

    private void close(ActionEvent e) {
        ((Stage) ((Node) e.getSource()).getScene().getWindow()).close();
    }
}
