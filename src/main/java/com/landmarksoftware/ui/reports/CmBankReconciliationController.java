package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.cm.CmReportDataService;
import com.landmarksoftware.service.cm.CmReportDataService.BankReconParams;
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
 * CMTL02 — Bank Reconciliation Statement selection screen.
 * PDF shows header balances + transaction detail. Excel is the wide variant.
 * reconNo is REQUIRED — the service returns a warning if not chosen.
 */
@Component
@Scope("prototype")
public class CmBankReconciliationController implements Initializable {

    private static final String PDF_PATH   = "cm/bank-reconciliation";
    private static final String EXCEL_PATH = "cm/bank-reconciliation-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private CmReportDataService  cmReports;

    @FXML private ComboBox<CmReportDataService.CodeName> bankCode;
    @FXML private ComboBox<CmReportDataService.CodeName> reconNo;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        bankCode.setItems(FXCollections.observableArrayList(cmReports.getBanks(session)));
        bankCode.getSelectionModel().selectFirst();
        reloadRecons();
        bankCode.valueProperty().addListener((o, was, is) -> reloadRecons());
    }

    private void reloadRecons() {
        reconNo.setItems(FXCollections.observableArrayList(
            cmReports.getReconNumbers(session, code(bankCode))));
        reconNo.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        BankReconParams params = new BankReconParams(code(bankCode), code(reconNo));

        Map<String, Object> data = cmReports.getBankReconStatement(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Cannot run report", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No reconciled transactions found for that reconciliation.");
            return;
        }
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            new JRBeanCollectionDataSource(rows), format, owner);
        close(e);
    }

    private static String code(ComboBox<CmReportDataService.CodeName> cb) {
        CmReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
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
