package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.gl.GlPeriodService;
import com.landmarksoftware.service.gl.GlReportDataService;
import com.landmarksoftware.service.gl.GlReportDataService.CodeName;
import com.landmarksoftware.service.gl.GlReportDataService.ProfitLossParams;
import com.landmarksoftware.ui.ReportsHubController;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
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

/**
 * GLTL12 (pl_bs_ind='P') — Profit & Loss Statement. Period-range movements
 * grouped Income / Expense with net profit in summary. Uses GlReportDataService
 * (glbal-based) and mapDataSource.
 */
@Component
@Scope("prototype")
public class GlProfitLossController implements Initializable {

    private static final String PDF_PATH   = "gl/profit-loss";
    private static final String EXCEL_PATH = "gl/profit-loss-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private GlReportDataService  glReports;
    @Autowired private GlPeriodService      periodService;

    @FXML private ComboBox<PeriodOption> fromPeriod;
    @FXML private ComboBox<PeriodOption> toPeriod;
    @FXML private ComboBox<CodeName>     startAcct;
    @FXML private ComboBox<CodeName>     endAcct;
    @FXML private CheckBox               includeZero;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        List<PeriodOption> opts = periodService.loadPeriods(
                session.getCompanyNo(), session.getYearNo());

        fromPeriod.setItems(FXCollections.observableArrayList(opts));
        toPeriod.setItems(FXCollections.observableArrayList(opts));

        if (!opts.isEmpty()) {
            // From = Period 1 (first), To = Period 12 / last
            fromPeriod.getSelectionModel().selectFirst();
            opts.stream().filter(p -> p.periodNo() == 12).findFirst()
                .ifPresentOrElse(toPeriod.getSelectionModel()::select,
                                 () -> toPeriod.getSelectionModel().selectLast());
        }

        List<CodeName> accts = glReports.getAccounts(session);
        startAcct.setItems(FXCollections.observableArrayList(accts));
        endAcct.setItems(FXCollections.observableArrayList(accts));
        startAcct.getSelectionModel().selectFirst();
        endAcct.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        PeriodOption from = fromPeriod.getSelectionModel().getSelectedItem();
        PeriodOption to   = toPeriod.getSelectionModel().getSelectedItem();
        int fromNo = from == null ? 1 : from.periodNo();
        int toNo   = to   == null ? 12 : to.periodNo();

        ProfitLossParams params = new ProfitLossParams(
                fromNo, toNo,
                acctInt(startAcct),
                acctInt(endAcct),
                includeZero.isSelected());

        Map<String, Object> data = glReports.getProfitLoss(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No P&L account movements matched the selection.");
            return;
        }
        Map<String, Object> jp = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jp,
                mapDataSource(rows), format, owner);
        close(e);
    }

    private Integer acctInt(ComboBox<CodeName> cb) {
        CodeName c = cb.getSelectionModel().getSelectedItem();
        if (c == null || c.code() == null || c.code().isBlank()) return null;
        try { return Integer.parseInt(c.code()); } catch (NumberFormatException ex) { return null; }
    }

    private void alert(Alert.AlertType type, String header, String msg) {
        Alert a = new Alert(type);
        a.setTitle(header); a.setHeaderText(header); a.setContentText(msg);
        a.showAndWait();
    }

    private void close(ActionEvent e) {
        ((Stage) ((Node) e.getSource()).getScene().getWindow()).close();
    }

    private static JRDataSource mapDataSource(java.util.List<java.util.Map<String, Object>> rows) {
        return new JRDataSource() {
            private final java.util.Iterator<java.util.Map<String, Object>> it = rows.iterator();
            private java.util.Map<String, Object> current;
            @Override public boolean next() { if (!it.hasNext()) return false; current = it.next(); return true; }
            @Override public Object getFieldValue(JRField f) { return current.get(f.getName()); }
        };
    }
}
