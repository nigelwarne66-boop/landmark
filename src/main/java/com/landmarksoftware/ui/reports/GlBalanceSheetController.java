package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.gl.GlPeriodService;
import com.landmarksoftware.ui.ReportsHubController;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Balance Sheet — single "as at end of period" combo (not a range).
 * Doesn't share GlPeriodSelector because the layout differs (one combo, not two).
 */
@Component
@Scope("prototype")
public class GlBalanceSheetController implements Initializable {

    private static final String REPORT_PATH = "gl/balance-sheet";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private GlPeriodService      periodService;

    @FXML private ComboBox<PeriodOption> asAtPeriod;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        List<PeriodOption> opts = periodService.loadPeriods(
            session.getCompanyNo(), session.getYearNo());
        asAtPeriod.setItems(FXCollections.observableArrayList(opts));
        if (!opts.isEmpty()) {
            // Default to period 12 if present, else last available.
            opts.stream()
                .filter(p -> p.periodNo() == 12)
                .findFirst()
                .ifPresentOrElse(asAtPeriod.getSelectionModel()::select,
                                 () -> asAtPeriod.getSelectionModel().selectLast());
        }
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    private void run(ActionEvent e, String format) {
        PeriodOption sel = asAtPeriod.getSelectionModel().getSelectedItem();
        int periodNo = sel == null ? 0 : sel.periodNo();
        Map<String, Object> params = Map.of("AS_AT_PERIOD", periodNo);
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReport(REPORT_PATH, params, format, owner);
        close(e);
    }

    private void close(ActionEvent e) {
        ((Stage) ((Node) e.getSource()).getScene().getWindow()).close();
    }
}
