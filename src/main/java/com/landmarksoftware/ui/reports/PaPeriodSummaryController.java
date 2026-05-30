package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.pa.PayReportDataService;
import com.landmarksoftware.service.pa.PayReportDataService.CodeName;
import com.landmarksoftware.service.pa.PayReportDataService.PeriodSummaryParams;
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
import java.time.LocalDate;
import java.util.*;

/** PATL07 — Period Summary selection screen. */
@Component
@Scope("prototype")
public class PaPeriodSummaryController implements Initializable {

    private static final String PDF_PATH   = "py/period-summary";
    private static final String EXCEL_PATH = "py/period-summary-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private PayReportDataService paReports;

    @FXML private ComboBox<CodeName> startPaygroup;
    @FXML private ComboBox<CodeName> endPaygroup;
    @FXML private DatePicker         startDate;
    @FXML private DatePicker         endDate;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        List<CodeName> pgs = paReports.getPaygroups(session);
        startPaygroup.setItems(FXCollections.observableArrayList(pgs));
        endPaygroup.setItems(FXCollections.observableArrayList(pgs));
        startPaygroup.getSelectionModel().selectFirst();
        endPaygroup.getSelectionModel().selectFirst();

        LocalDate now = LocalDate.now();
        startDate.setValue(now.withDayOfYear(1));
        endDate.setValue(now);
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        PeriodSummaryParams params = new PeriodSummaryParams(
            code(startPaygroup), code(endPaygroup),
            startDate.getValue(), endDate.getValue());

        Map<String, Object> data = paReports.getPeriodSummary(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "No data", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No payrun data matched the selection.");
            return;
        }
        Map<String, Object> jp = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jp, mapDataSource(rows), format, owner);
        close(e);
    }

    private static JRDataSource mapDataSource(List<Map<String, Object>> rows) {
        return new JRDataSource() {
            private final Iterator<Map<String, Object>> it = rows.iterator();
            private Map<String, Object> current;
            @Override public boolean next() { if (!it.hasNext()) return false; current = it.next(); return true; }
            @Override public Object getFieldValue(JRField f) { return current.get(f.getName()); }
        };
    }

    private static String code(ComboBox<CodeName> cb) {
        CodeName c = cb.getSelectionModel().getSelectedItem(); return c == null ? "" : c.code();
    }
    private void alert(Alert.AlertType type, String header, String msg) {
        Alert a = new Alert(type); a.setTitle(header); a.setHeaderText(header); a.setContentText(msg); a.showAndWait();
    }
    private void close(ActionEvent e) { ((Stage)((Node)e.getSource()).getScene().getWindow()).close(); }
}
