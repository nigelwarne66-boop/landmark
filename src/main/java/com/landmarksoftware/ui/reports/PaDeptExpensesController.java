package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.pa.PayReportDataService;
import com.landmarksoftware.service.pa.PayReportDataService.CodeName;
import com.landmarksoftware.service.pa.PayReportDataService.DeptExpensesParams;
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
import java.util.*;

/** PATL16 — Department Expenses selection screen. */
@Component
@Scope("prototype")
public class PaDeptExpensesController implements Initializable {

    private static final String PDF_PATH   = "py/dept-expenses";
    private static final String EXCEL_PATH = "py/dept-expenses-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private PayReportDataService paReports;

    @FXML private ComboBox<CodeName> periodDate;
    @FXML private ComboBox<CodeName> startPaygroup;
    @FXML private ComboBox<CodeName> endPaygroup;
    @FXML private ComboBox<CodeName> startDept;
    @FXML private ComboBox<CodeName> endDept;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        List<CodeName> periods = paReports.getDistinctCostPeriods(session);
        periodDate.setItems(FXCollections.observableArrayList(periods));
        if (!periods.isEmpty()) periodDate.getSelectionModel().selectFirst();

        List<CodeName> pgs = paReports.getPaygroups(session);
        startPaygroup.setItems(FXCollections.observableArrayList(pgs));
        endPaygroup.setItems(FXCollections.observableArrayList(pgs));
        startPaygroup.getSelectionModel().selectFirst();
        endPaygroup.getSelectionModel().selectFirst();

        List<CodeName> depts = paReports.getDepts(session);
        startDept.setItems(FXCollections.observableArrayList(depts));
        endDept.setItems(FXCollections.observableArrayList(depts));
        startDept.getSelectionModel().selectFirst();
        endDept.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        CodeName sel = periodDate.getSelectionModel().getSelectedItem();
        if (sel == null || sel.code().isBlank()) {
            alert(Alert.AlertType.WARNING, "Period required", "Select a period end date.");
            return;
        }
        java.time.LocalDate pd;
        try { pd = java.time.LocalDate.parse(sel.code()); }
        catch (Exception ex) { alert(Alert.AlertType.ERROR, "Invalid date", "Cannot parse date: " + sel.code()); return; }

        DeptExpensesParams params = new DeptExpensesParams(
            pd, code(startPaygroup), code(endPaygroup),
            code(startDept), code(endDept));

        Map<String, Object> data = paReports.getDeptExpenses(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "No data", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No cost data matched the selection.");
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
