package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ap.ApReportDataService;
import com.landmarksoftware.service.ap.ApReportDataService.PurchaseHistParams;
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

/** APTL09 — Supplier Purchase History selection screen. */
@Component
@Scope("prototype")
public class ApPurchaseHistoryController implements Initializable {

    private static final String PDF_PATH   = "ap/purchase-history";
    private static final String EXCEL_PATH = "ap/purchase-history-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ApReportDataService  apReports;

    @FXML private ComboBox<ApReportDataService.CodeName> subLedger;
    @FXML private DatePicker periodEndDate;
    @FXML private ComboBox<ApReportDataService.CodeName> startSupplier;
    @FXML private ComboBox<ApReportDataService.CodeName> endSupplier;
    @FXML private CheckBox   suppressZero;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        subLedger.setItems(FXCollections.observableArrayList(apReports.getSubLedgers(session)));
        subLedger.getSelectionModel().selectFirst();
        startSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, false)));
        startSupplier.getSelectionModel().selectFirst();
        endSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, false)));
        endSupplier.getSelectionModel().selectFirst();
        suppressZero.setSelected(true);   // COBOL default
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        if (periodEndDate.getValue() == null) {
            alert(Alert.AlertType.WARNING, "Period ending date", "Select a period ending date.");
            return;
        }
        PurchaseHistParams params = new PurchaseHistParams(
            code(subLedger),
            periodEndDate.getValue(),
            code(startSupplier),
            code(endSupplier),
            suppressZero.isSelected());

        Map<String, Object> data = apReports.getPurchaseHistoryData(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "pdf".equals(format) ? PDF_PATH : EXCEL_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            new JRBeanCollectionDataSource(rows), format, owner);
        close(e);
    }

    private static String code(ComboBox<ApReportDataService.CodeName> cb) {
        ApReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
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
