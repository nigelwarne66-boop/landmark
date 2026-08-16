package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ap.ApReportDataService;
import com.landmarksoftware.service.ap.ApReportDataService.FcRevalParams;
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
import javafx.util.StringConverter;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRField;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/** APRC11 — Foreign Currency Revaluation selection screen. */
@Component
@Scope("prototype")
public class ApFcRevaluationController implements Initializable {

    private static final String PDF_PATH   = "ap/fc-revaluation";
    private static final String EXCEL_PATH = "ap/fc-revaluation-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ApReportDataService  apReports;

    @FXML private ComboBox<LabelValue> printSeq;
    @FXML private ComboBox<ApReportDataService.CodeName> startSupplier;
    @FXML private ComboBox<ApReportDataService.CodeName> endSupplier;
    @FXML private ComboBox<ApReportDataService.CodeName> subLedger;
    @FXML private CheckBox invoices;
    @FXML private CheckBox drNotes;
    @FXML private CheckBox crNotes;
    @FXML private ComboBox<LabelValue> includeRecon;
    @FXML private ComboBox<LabelValue> docPostInd;
    @FXML private DatePicker startDate;
    @FXML private DatePicker endDate;
    @FXML private DatePicker revalStartDate;
    @FXML private DatePicker revalEndDate;
    @FXML private ComboBox<String> forCurrCode;

    record LabelValue(String label, String value) {
        @Override public String toString() { return label; }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        FxUtil.setAuDate(startDate, endDate, revalStartDate, revalEndDate);
        StringConverter<LabelValue> conv = new StringConverter<>() {
            @Override public String toString(LabelValue o) { return o == null ? "" : o.label(); }
            @Override public LabelValue fromString(String s) { return null; }
        };
        printSeq.setConverter(conv);
        printSeq.setItems(FXCollections.observableArrayList(
            new LabelValue("Supplier number", "N"), new LabelValue("Alpha key", "A")));
        printSeq.getSelectionModel().selectFirst();

        subLedger.setItems(FXCollections.observableArrayList(apReports.getSubLedgers(session)));
        subLedger.getSelectionModel().selectFirst();
        reloadSuppliers();
        printSeq.valueProperty().addListener((o, was, is) -> reloadSuppliers());

        includeRecon.setConverter(conv);
        includeRecon.setItems(FXCollections.observableArrayList(
            new LabelValue("All", "A"), new LabelValue("Reconciled only", "R"),
            new LabelValue("Unreconciled only", "U")));
        includeRecon.getSelectionModel().selectFirst();

        docPostInd.setConverter(conv);
        docPostInd.setItems(FXCollections.observableArrayList(
            new LabelValue("Document date", "D"), new LabelValue("Posting date", "P")));
        docPostInd.getSelectionModel().selectFirst();

        invoices.setSelected(true); drNotes.setSelected(true); crNotes.setSelected(true);

        List<String> codes = new ArrayList<>();
        codes.add("");                       // All
        codes.addAll(apReports.getForeignCurrencyCodes(session));
        forCurrCode.setItems(FXCollections.observableArrayList(codes));
        forCurrCode.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        FcRevalParams params = new FcRevalParams(
            val(printSeq),
            code(startSupplier),
            code(endSupplier),
            code(subLedger),
            invoices.isSelected(), drNotes.isSelected(), crNotes.isSelected(),
            val(includeRecon),
            val(docPostInd),
            startDate.getValue(), endDate.getValue(),
            revalStartDate.getValue(), revalEndDate.getValue(),
            trimToNull(forCurrCode.getValue()));

        Map<String, Object> data = apReports.getFcRevaluationData(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "pdf".equals(format) ? PDF_PATH : EXCEL_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            mapDataSource(rows), format, owner);
        close(e);
    }

    private void reloadSuppliers() {
        boolean alpha = "A".equals(val(printSeq));
        startSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, alpha)));
        endSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, alpha)));
        startSupplier.getSelectionModel().selectFirst();
        endSupplier.getSelectionModel().selectFirst();
    }

    private static String val(ComboBox<LabelValue> cb) {
        LabelValue v = cb.getSelectionModel().getSelectedItem();
        return v == null ? null : v.value();
    }

    private static String code(ComboBox<ApReportDataService.CodeName> cb) {
        ApReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
        String v = c == null ? null : c.code();
        return (v == null || v.isBlank()) ? null : v;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
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
