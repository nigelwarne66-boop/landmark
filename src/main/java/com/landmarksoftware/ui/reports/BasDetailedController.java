package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.bas.BasReportDataService;
import com.landmarksoftware.service.bas.BasReportDataService.DetailedBasParams;
import com.landmarksoftware.ui.ReportsHubController;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;
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
 * CPBA13 — Detailed BAS. Summary mode shows one row per BAS code;
 * detail mode shows the individual transactions.
 */
@Component
@Scope("prototype")
public class BasDetailedController implements Initializable {

    private static final String PDF_PATH   = "bas/detailed-bas";
    private static final String EXCEL_PATH = "bas/detailed-bas-excel";

    @Autowired private ReportsHubController  hub;
    @Autowired private AppSession            session;
    @Autowired private BasReportDataService  basReports;

    @FXML private ComboBox<BasReportDataService.CodeName> basGroup;
    @FXML private ComboBox<BasReportDataService.CodeName> basNo;
    @FXML private ComboBox<LabelValue>                    detailSummary;

    record LabelValue(String label, String value) {
        @Override public String toString() { return label; }
    }

    private StringConverter<LabelValue> conv() {
        return new StringConverter<>() {
            @Override public String toString(LabelValue o) { return o == null ? "" : o.label(); }
            @Override public LabelValue fromString(String s) { return null; }
        };
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        basGroup.setItems(FXCollections.observableArrayList(basReports.getBasGroups(session)));
        basGroup.getSelectionModel().selectFirst();
        reloadBasNos();
        basGroup.valueProperty().addListener((o, was, is) -> reloadBasNos());

        detailSummary.setConverter(conv());
        detailSummary.setItems(FXCollections.observableArrayList(
                new LabelValue("Summary", "S"), new LabelValue("Detail", "D")));
        detailSummary.getSelectionModel().selectFirst();
    }

    private void reloadBasNos() {
        basNo.setItems(FXCollections.observableArrayList(
                basReports.getBasNumbers(session, code(basGroup))));
        basNo.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        String grp = code(basGroup);
        String no  = code(basNo);
        if (grp == null || no == null) {
            alert(Alert.AlertType.WARNING, "Selection required", "Please choose a BAS group and BAS number.");
            return;
        }
        String mode = val(detailSummary);
        Map<String, Object> data = basReports.getDetailedBas(session, new DetailedBasParams(grp, no, mode));
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No BAS transactions found for the selection.");
            return;
        }
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
                mapDataSource(rows), format, owner);
        close(e);
    }

    private static String val(ComboBox<LabelValue> cb) {
        LabelValue v = cb.getSelectionModel().getSelectedItem();
        return v == null ? "" : v.value();
    }

    private static String code(ComboBox<BasReportDataService.CodeName> cb) {
        BasReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
        String v = c == null ? null : c.code();
        return (v == null || v.isBlank()) ? null : v;
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
