package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.sm.SmReportDataService;
import com.landmarksoftware.service.sm.SmReportDataService.ItemStatusParams;
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
 * SMTL15 (+24) — Item Status. One list, choose the sort sequence (Item or Location).
 * Queries smstloc × smsthed — empty until the stock master is loaded in the extract.
 */
@Component
@Scope("prototype")
public class SmItemStatusController implements Initializable {

    private static final String PDF_PATH   = "sm/item-status";
    private static final String EXCEL_PATH = "sm/item-status-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private SmReportDataService  smReports;

    @FXML private ComboBox<LabelValue>                   sequence;
    @FXML private ComboBox<SmReportDataService.CodeName> locNo;
    @FXML private ComboBox<SmReportDataService.CodeName> startItem;
    @FXML private ComboBox<SmReportDataService.CodeName> endItem;
    @FXML private ComboBox<SmReportDataService.CodeName> startProdType;
    @FXML private ComboBox<SmReportDataService.CodeName> endProdType;
    @FXML private DatePicker reportDate;

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
            new LabelValue("Item", "ITEM"),
            new LabelValue("Location", "LOCATION")));
        sequence.getSelectionModel().selectFirst();

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

        List<SmReportDataService.CodeName> types = smReports.getProductTypes(session);
        startProdType.setItems(FXCollections.observableArrayList(types));
        endProdType.setItems(FXCollections.observableArrayList(types));
        startProdType.getSelectionModel().selectFirst();
        endProdType.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        ItemStatusParams params = new ItemStatusParams(
            code(locNo), code(startItem), code(endItem),
            code(startProdType), code(endProdType),
            reportDate.getValue(), val(sequence));

        Map<String, Object> data = smReports.getItemStatus(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No item-status rows matched the selection.");
            return;
        }
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            new JRBeanCollectionDataSource(rows), format, owner);
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
}
