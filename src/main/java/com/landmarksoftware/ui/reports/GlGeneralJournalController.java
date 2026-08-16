package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.gl.GlReportDataService;
import com.landmarksoftware.service.gl.GlReportDataService.JournalParams;
import com.landmarksoftware.ui.FxUtil;
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
 * GLTL06 — General Journal. Filtered by source, journal number range,
 * date range and Detail/Summary mode; backed by GlReportDataService.getGeneralJournal.
 */
@Component
@Scope("prototype")
public class GlGeneralJournalController implements Initializable {

    private static final String PDF_PATH   = "gl/general-journal";
    private static final String EXCEL_PATH = "gl/general-journal-excel";

    @Autowired private ReportsHubController  hub;
    @Autowired private AppSession            session;
    @Autowired private GlReportDataService   glReports;

    @FXML private ComboBox<GlReportDataService.CodeName> source;
    @FXML private TextField startJnl;
    @FXML private TextField endJnl;
    @FXML private DatePicker startDate;
    @FXML private DatePicker endDate;
    @FXML private ComboBox<String> mode;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        FxUtil.setAuDate(startDate, endDate);
        List<GlReportDataService.CodeName> sources = glReports.getSources(session);
        source.setItems(FXCollections.observableArrayList(sources));
        source.getSelectionModel().selectFirst();

        mode.setItems(FXCollections.observableArrayList("Detail", "Summary"));
        mode.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        JournalParams params = new JournalParams(
                code(source),
                parseIntOrNull(startJnl.getText()),
                parseIntOrNull(endJnl.getText()),
                startDate.getValue(),
                endDate.getValue(),
                "Summary".equals(mode.getSelectionModel().getSelectedItem()));

        Map<String, Object> data = glReports.getGeneralJournal(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No journal entries matched the selection.");
            return;
        }
        Map<String, Object> jp = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jp,
                mapDataSource(rows), format, owner);
        close(e);
    }

    private static String code(ComboBox<GlReportDataService.CodeName> cb) {
        GlReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
        String v = c == null ? null : c.code();
        return (v == null || v.isBlank()) ? null : v;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ex) { return null; }
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
