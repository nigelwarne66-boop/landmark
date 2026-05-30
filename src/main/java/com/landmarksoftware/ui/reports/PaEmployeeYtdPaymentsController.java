package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.pa.PayReportDataService;
import com.landmarksoftware.service.pa.PayReportDataService.CodeName;
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
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRField;
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

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private PayReportDataService paReports;

    @FXML private ComboBox<CodeName> yearNo;
    @FXML private ComboBox<CodeName> startEmployee;
    @FXML private ComboBox<CodeName> endEmployee;
    @FXML private ComboBox<CodeName> startPaygroup;
    @FXML private ComboBox<CodeName> endPaygroup;
    @FXML private ComboBox<CodeName> startDept;
    @FXML private ComboBox<CodeName> endDept;
    @FXML private ComboBox<CodeName> startCode;
    @FXML private ComboBox<CodeName> endCode;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        List<CodeName> years = paReports.getYtdYears(session);
        yearNo.setItems(FXCollections.observableArrayList(years));
        if (!years.isEmpty()) yearNo.getSelectionModel().selectFirst();

        List<CodeName> paygroups = paReports.getPaygroups(session);
        startPaygroup.setItems(FXCollections.observableArrayList(paygroups));
        endPaygroup.setItems(FXCollections.observableArrayList(paygroups));
        startPaygroup.getSelectionModel().selectFirst();
        endPaygroup.getSelectionModel().selectFirst();

        List<CodeName> depts = paReports.getDepts(session);
        startDept.setItems(FXCollections.observableArrayList(depts));
        endDept.setItems(FXCollections.observableArrayList(depts));
        startDept.getSelectionModel().selectFirst();
        endDept.getSelectionModel().selectFirst();

        List<CodeName> emps = paReports.getEmployees(session);
        startEmployee.setItems(FXCollections.observableArrayList(emps));
        endEmployee.setItems(FXCollections.observableArrayList(emps));
        startEmployee.getSelectionModel().selectFirst();
        endEmployee.getSelectionModel().selectFirst();

        List<CodeName> codes = paReports.getPayCodes(session);
        startCode.setItems(FXCollections.observableArrayList(codes));
        endCode.setItems(FXCollections.observableArrayList(codes));
        startCode.getSelectionModel().selectFirst();
        endCode.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        CodeName sel = yearNo.getSelectionModel().getSelectedItem();
        if (sel == null) {
            alert(Alert.AlertType.WARNING, "Tax year", "Select a tax year.");
            return;
        }
        int yr;
        try { yr = Integer.parseInt(sel.code()); }
        catch (NumberFormatException ex) { yr = 0; }

        YtdPaymentsParams params = new YtdPaymentsParams(
            codeInt(startEmployee), codeInt(endEmployee),
            code(startPaygroup),     code(endPaygroup),
            code(startDept),         code(endDept),
            code(startCode),         code(endCode),
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
        // Use a direct Map-access DataSource instead of JRBeanCollectionDataSource
        // to bypass Apache BeanUtils, which silently returns null for BigDecimal fields
        // on Map beans in some class-loader contexts.
        hub.runJasperReportWithDataSource(reportPath, jp,
            mapDataSource(rows), format, owner);
        close(e);
    }

    /** Direct map-get DataSource — bypasses BeanUtils which silently drops BigDecimal values. */
    private static JRDataSource mapDataSource(List<Map<String, Object>> rows) {
        return new JRDataSource() {
            private final java.util.Iterator<Map<String, Object>> it = rows.iterator();
            private Map<String, Object> current;
            @Override public boolean next() {
                if (!it.hasNext()) return false;
                current = it.next();
                return true;
            }
            @Override public Object getFieldValue(JRField f) { return current.get(f.getName()); }
        };
    }

    private static String code(ComboBox<CodeName> cb) {
        CodeName c = cb.getSelectionModel().getSelectedItem();
        return c == null ? "" : c.code();
    }

    private static Integer codeInt(ComboBox<CodeName> cb) {
        String v = code(cb);
        if (v.isBlank()) return null;
        try { return Integer.parseInt(v); } catch (NumberFormatException ex) { return null; }
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
