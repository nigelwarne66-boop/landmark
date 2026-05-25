package com.landmarksoftware.ui.reports;

import com.landmarksoftware.ui.ReportsHubController;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Scope("prototype")
public class GlAccountTransactionsController {

    private static final String REPORT_PATH = "gl/account-transactions";

    @Autowired private ReportsHubController hub;
    @FXML private GlPeriodSelectorController periodSelectorController;
    @FXML private TextField acctNoField;

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    private void run(ActionEvent e, String format) {
        String acctNo = acctNoField.getText();
        if (acctNo == null || acctNo.isBlank()) acctNo = "%";
        Map<String, Object> params = Map.of(
            "FROM_PERIOD", periodSelectorController.getFromPeriod(),
            "TO_PERIOD",   periodSelectorController.getToPeriod(),
            "ACCT_NO",     acctNo);
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReport(REPORT_PATH, params, format, owner);
        close(e);
    }

    private void close(ActionEvent e) {
        ((Stage) ((Node) e.getSource()).getScene().getWindow()).close();
    }
}
