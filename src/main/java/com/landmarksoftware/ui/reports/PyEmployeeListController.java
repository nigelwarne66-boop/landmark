package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.ui.ReportsHubController;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Employee List — department + status filters.
 *
 * <p>Note: the current employee-list.jrxml hardcodes
 * {@code employee_status = 'A'} and doesn't reference DEPT/EMP_STATUS
 * params. Selecting "All" or "Terminated" / a department here has no
 * effect until the .jrxml SQL is updated. Fields are present so the UX
 * matches cc-migration.md and is ready for that change.
 */
@Component
@Scope("prototype")
public class PyEmployeeListController implements Initializable {

    private static final String REPORT_PATH = "py/employee-list";
    private static final String ALL_DEPTS = "(All departments)";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private JdbcTemplate         jdbc;

    @FXML private ComboBox<String>       department;
    @FXML private ComboBox<StatusOption> statusCombo;

    /** value = what we pass to Jasper as EMP_STATUS. */
    record StatusOption(String label, String value) {
        @Override public String toString() { return label; }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Departments — "(All departments)" first, then DISTINCT dept values.
        List<String> depts = new ArrayList<>();
        depts.add(ALL_DEPTS);
        depts.addAll(loadDepartments(session.getCompanyNo()));
        department.setItems(FXCollections.observableArrayList(depts));
        department.getSelectionModel().selectFirst();

        // Status — Active default, then All, then Terminated.
        statusCombo.setConverter(new StringConverter<>() {
            @Override public String toString(StatusOption o) { return o == null ? "" : o.label(); }
            @Override public StatusOption fromString(String s) { return null; }
        });
        statusCombo.setItems(FXCollections.observableArrayList(
            new StatusOption("Active only", "A"),
            new StatusOption("All",         ""),
            new StatusOption("Terminated",  "T")));
        statusCombo.getSelectionModel().selectFirst();
    }

    private List<String> loadDepartments(int companyNo) {
        List<String> out = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT DISTINCT dept FROM pastaff WHERE company_no = ? ORDER BY dept",
                rs -> {
                    String d = rs.getString("dept");
                    if (d != null && !d.isBlank()) out.add(d);
                },
                companyNo);
        } catch (Exception ignored) { }
        return out;
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    private void run(ActionEvent e, String format) {
        String dept = department.getSelectionModel().getSelectedItem();
        String deptParam = (dept == null || ALL_DEPTS.equals(dept)) ? "%" : dept;
        StatusOption st = statusCombo.getSelectionModel().getSelectedItem();
        String statusParam = st == null ? "A" : st.value();

        Map<String, Object> params = new HashMap<>();
        params.put("DEPT", deptParam);
        params.put("EMP_STATUS", statusParam);

        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReport(REPORT_PATH, params, format, owner);
        close(e);
    }

    private void close(ActionEvent e) {
        ((Stage) ((Node) e.getSource()).getScene().getWindow()).close();
    }
}
