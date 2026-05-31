package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.gl.GlReportDataService;
import com.landmarksoftware.service.gl.GlReportDataService.AccountTxnParams;
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

/**
 * GLTL14/15 — Account Transactions. Filtered by account range, source, analysis
 * code range and date range; backed by GlReportDataService.getAccountTransactions.
 */
@Component
@Scope("prototype")
public class GlAccountTransactionsController implements Initializable {

    private static final String PDF_PATH   = "gl/account-transactions";
    private static final String EXCEL_PATH = "gl/account-transactions-excel";

    @Autowired private ReportsHubController  hub;
    @Autowired private AppSession            session;
    @Autowired private GlReportDataService   glReports;

    @FXML private ComboBox<GlReportDataService.CodeName> startAcct;
    @FXML private ComboBox<GlReportDataService.CodeName> endAcct;
    @FXML private ComboBox<GlReportDataService.CodeName> source;
    @FXML private TextField startAnalysis;
    @FXML private TextField endAnalysis;
    @FXML private DatePicker startDate;
    @FXML private DatePicker endDate;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        List<GlReportDataService.CodeName> accounts = glReports.getAccounts(session);
        startAcct.setItems(FXCollections.observableArrayList(accounts));
        endAcct.setItems(FXCollections.observableArrayList(accounts));
        startAcct.getSelectionModel().selectFirst();
        endAcct.getSelectionModel().selectFirst();

        List<GlReportDataService.CodeName> sources = glReports.getSources(session);
        source.setItems(FXCollections.observableArrayList(sources));
        source.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        AccountTxnParams params = new AccountTxnParams(
                acctInt(startAcct),
                acctInt(endAcct),
                code(source),
                blankToNull(startAnalysis.getText()),
                blankToNull(endAnalysis.getText()),
                startDate.getValue(),
                endDate.getValue());

        Map<String, Object> data = glReports.getAccountTransactions(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No transactions matched the selection.");
            return;
        }
        Map<String, Object> jp = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jp,
                mapDataSource(rows), format, owner);
        close(e);
    }

    /** Returns the selected source code, or null when "(All sources)" is selected. */
    private static String code(ComboBox<GlReportDataService.CodeName> cb) {
        GlReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
        String v = c == null ? null : c.code();
        return (v == null || v.isBlank()) ? null : v;
    }

    /** Parses the selected account code to Integer, or null for the "(All accounts)" blank entry. */
    private static Integer acctInt(ComboBox<GlReportDataService.CodeName> cb) {
        GlReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
        if (c == null || c.code() == null || c.code().isBlank()) return null;
        try { return Integer.parseInt(c.code().trim()); } catch (NumberFormatException ex) { return null; }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
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
