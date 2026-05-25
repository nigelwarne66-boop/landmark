package com.landmarksoftware.ui.reports;

import com.landmarksoftware.ui.ReportsHubController;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Scope("prototype")
public class GlTrialBalanceController {

    private static final String REPORT_PATH = "gl/trial-balance";

    @Autowired private ReportsHubController hub;

    // Injected by FXMLLoader from <fx:include fx:id="periodSelector" .../>
    // — the magic name is includeFxId + "Controller".
    @FXML private GlPeriodSelectorController periodSelectorController;

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    private void run(ActionEvent e, String format) {
        Map<String, Object> params = Map.of(
            "FROM_PERIOD", periodSelectorController.getFromPeriod(),
            "TO_PERIOD",   periodSelectorController.getToPeriod());
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReport(REPORT_PATH, params, format, owner);
        close(e);
    }

    private void close(ActionEvent e) {
        ((Stage) ((Node) e.getSource()).getScene().getWindow()).close();
    }
}
