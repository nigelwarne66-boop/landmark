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
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Payroll Summary — pay-run date range.
 *
 * <p>Note: the current payroll-summary.jrxml only declares the 4 standard
 * params (COMPANY_NO, YEAR_NO, COMPANY_NAME, YEAR_DESC). FROM_DATE/TO_DATE
 * are passed to Jasper but the SQL inside the .jrxml doesn't reference them
 * yet, so the report ignores the range filter. Update the .jrxml when ready.
 */
@Component
@Scope("prototype")
public class PyPayrollSummaryController implements Initializable {

    private static final String REPORT_PATH = "py/payroll-summary";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private JdbcTemplate         jdbc;

    @FXML private ComboBox<LocalDate> payrunFrom;
    @FXML private ComboBox<LocalDate> payrunTo;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        StringConverter<LocalDate> conv = new StringConverter<>() {
            @Override public String toString(LocalDate d) { return d == null ? "" : d.format(FMT); }
            @Override public LocalDate fromString(String s) { return null; }
        };
        payrunFrom.setConverter(conv);
        payrunTo.setConverter(conv);

        List<LocalDate> dates = loadPayrunDates(session.getCompanyNo(), session.getYrNo());
        payrunFrom.setItems(FXCollections.observableArrayList(dates));
        payrunTo.setItems(FXCollections.observableArrayList(dates));
        if (!dates.isEmpty()) {
            payrunFrom.getSelectionModel().selectFirst();
            payrunTo.getSelectionModel().selectLast();
        }
    }

    private List<LocalDate> loadPayrunDates(int companyNo, int yrNo) {
        List<LocalDate> out = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT DISTINCT payrun_date FROM parunhd " +
                "WHERE company_no = ? AND yr_no = ? ORDER BY payrun_date",
                rs -> {
                    Date d = rs.getDate("payrun_date");
                    if (d != null) out.add(d.toLocalDate());
                },
                companyNo, yrNo);
        } catch (Exception ignored) { }
        return out;
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    private void run(ActionEvent e, String format) {
        LocalDate from = payrunFrom.getSelectionModel().getSelectedItem();
        LocalDate to   = payrunTo.getSelectionModel().getSelectedItem();
        Map<String, Object> params = new HashMap<>();
        params.put("FROM_DATE", from == null ? null : Date.valueOf(from));
        params.put("TO_DATE",   to   == null ? null : Date.valueOf(to));
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReport(REPORT_PATH, params, format, owner);
        close(e);
    }

    private void close(ActionEvent e) {
        ((Stage) ((Node) e.getSource()).getScene().getWindow()).close();
    }
}
