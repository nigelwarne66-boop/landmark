package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ar.ArReportDataService;
import com.landmarksoftware.service.ar.ArReportDataService.AcctStatusParams;
import com.landmarksoftware.ui.ReportsHubController;
import javafx.collections.FXCollections;
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
 * Customer Account Status (ARTL21) — customers filtered by status (active /
 * no sales / on hold / inactive), across an optional customer and sub-ledger range.
 */
@Component
@Scope("prototype")
public class ArCustomerAccountStatusController implements Initializable {

    private static final String PDF_PATH   = "ar/customer-account-status";
    private static final String EXCEL_PATH = "ar/customer-account-status-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ArReportDataService  arReports;

    @FXML private ComboBox<ArReportDataService.CodeName> startCustomer;
    @FXML private ComboBox<ArReportDataService.CodeName> endCustomer;
    @FXML private ComboBox<ArReportDataService.CodeName> startSubLedger;
    @FXML private ComboBox<ArReportDataService.CodeName> endSubLedger;
    @FXML private CheckBox active;
    @FXML private CheckBox noSales;
    @FXML private CheckBox onHold;
    @FXML private CheckBox inactive;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        List<ArReportDataService.CodeName> custs = arReports.getCustomers(session, false);
        startCustomer.setItems(FXCollections.observableArrayList(custs));
        endCustomer.setItems(FXCollections.observableArrayList(custs));
        startCustomer.getSelectionModel().selectFirst();
        endCustomer.getSelectionModel().selectLast();

        List<ArReportDataService.CodeName> subs = arReports.getSubLedgers(session);
        startSubLedger.setItems(FXCollections.observableArrayList(subs));
        endSubLedger.setItems(FXCollections.observableArrayList(subs));
        startSubLedger.getSelectionModel().selectFirst();
        endSubLedger.getSelectionModel().selectLast();

        // Default: active, noSales, onHold selected; inactive not selected
        active.setSelected(true);
        noSales.setSelected(true);
        onHold.setSelected(true);
        inactive.setSelected(false);
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        AcctStatusParams params = new AcctStatusParams(
            code(startCustomer), code(endCustomer),
            code(startSubLedger), code(endSubLedger),
            active.isSelected(), noSales.isSelected(),
            onHold.isSelected(), inactive.isSelected());

        Map<String, Object> data = arReports.getCustomerAccountStatusData(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No customers matched the selection.");
            return;
        }
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            new JRBeanCollectionDataSource(rows), format, owner);
        close(e);
    }

    private static String code(ComboBox<ArReportDataService.CodeName> cb) {
        ArReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
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
