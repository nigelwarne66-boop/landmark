package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.pa.PayReportDataService;
import com.landmarksoftware.service.pa.PayReportDataService.YtdPaymentsParams;
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

/** PATL02 — Employee YTD Payments selection screen. */
@Component
@Scope("prototype")
public class PaEmployeeYtdPaymentsController implements Initializable {

    private static final String PDF_PATH   = "py/employee-ytd-payments";
    private static final String EXCEL_PATH = "py/employee-ytd-payments-excel";

    @Autowired private ReportsHubController   hub;
    @Autowired private AppSession             session;
    @Autowired private PayReportDataService   paReports;

    @FXML private ComboBox<PayReportDataService.CodeName> yearNo;
    @FXML private TextField startPaygroup;
    @FXML private TextField endPaygroup;
    @FXML private TextField startDept;
    @FXML private TextField endDept;
    @FXML private TextField startEmployee;
    @FXML private TextField endEmployee;
    @FXML private TextField startCode;
    @FXML private TextField endCode;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        List<PayReportDataService.CodeName> years = paReports.getYtdYears(session);
        yearNo.setItems(FXCollections.observableArrayList(years));
        if (!years.isEmpty()) yearNo.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        PayReportDataService.CodeName sel = yearNo.getSelectionModel().getSelectedItem();
        if (sel == null) {
            alert(Alert.AlertType.WARNING, "Tax year", "Select a tax year.");
            return;
        }
        int yr;
        try { yr = Integer.parseInt(sel.code()); }
        catch (NumberFormatException ex) { yr = 0; }

        YtdPaymentsParams params = new YtdPaymentsParams(
            parseInt(startEmployee), parseInt(endEmployee),
            text(startPaygroup),     text(endPaygroup),
            text(startDept),         text(endDept),
            text(startCode),         text(endCode),
            yr);

        Map<String, Object> data = paReports.getYtdPayments(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No YTD data matched the selection.");
            return;
        }
        Map<String, Object> jp = new HashMap<>((Map<String, Object>) data.get("params"));
        jp.put("COMPANY_NAME", session.getCompanyName());
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jp,
            new JRBeanCollectionDataSource(rows), format, owner);
        close(e);
    }

    private static String  text(TextField f)   { String v = f.getText(); return v == null ? "" : v.trim(); }
    private static Integer parseInt(TextField f) {
        try { int v = Integer.parseInt(f.getText().trim()); return v > 0 ? v : null; }
        catch (NumberFormatException ex) { return null; }
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
