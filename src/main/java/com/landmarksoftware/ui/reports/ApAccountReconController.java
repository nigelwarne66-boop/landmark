package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ap.ApReportDataService;
import com.landmarksoftware.service.ap.ApReportDataService.AccountReconParams;
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
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/** APRC03 — Account Reconciliation selection screen. */
@Component
@Scope("prototype")
public class ApAccountReconController implements Initializable {

    private static final String PDF_PATH   = "ap/account-reconciliation";
    private static final String EXCEL_PATH = "ap/account-reconciliation-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ApReportDataService  apReports;

    @FXML private ComboBox<ApReportDataService.CodeName> startSupplier;
    @FXML private ComboBox<ApReportDataService.CodeName> endSupplier;
    @FXML private TextField startReconNo;
    @FXML private TextField endReconNo;
    @FXML private ComboBox<LabelValue> unbalancedFlag;
    @FXML private ComboBox<LabelValue> localFcFlag;
    @FXML private ComboBox<String> forCurrCode;
    @FXML private CheckBox  paymtDocNoInd;

    record LabelValue(String label, String value) {
        @Override public String toString() { return label; }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        StringConverter<LabelValue> conv = new StringConverter<>() {
            @Override public String toString(LabelValue o) { return o == null ? "" : o.label(); }
            @Override public LabelValue fromString(String s) { return null; }
        };
        unbalancedFlag.setConverter(conv);
        unbalancedFlag.setItems(FXCollections.observableArrayList(
            new LabelValue("All", "A"),
            new LabelValue("Unbalanced only", "U"),
            new LabelValue("Balanced only", "B")));
        unbalancedFlag.getSelectionModel().selectFirst();

        localFcFlag.setConverter(conv);
        localFcFlag.setItems(FXCollections.observableArrayList(
            new LabelValue("Local", "L"),
            new LabelValue("Foreign", "F")));
        localFcFlag.getSelectionModel().selectFirst();

        startSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, false)));
        startSupplier.getSelectionModel().selectFirst();
        endSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, false)));
        endSupplier.getSelectionModel().selectFirst();

        java.util.List<String> ccs = new java.util.ArrayList<>();
        ccs.add("");
        ccs.addAll(apReports.getForeignCurrencyCodes(session));
        forCurrCode.setItems(FXCollections.observableArrayList(ccs));
        forCurrCode.getSelectionModel().selectFirst();

        forCurrCode.setDisable(true);
        localFcFlag.valueProperty().addListener((o, was, is) ->
            forCurrCode.setDisable(is == null || !"F".equals(is.value())));

        paymtDocNoInd.setSelected(true);   // COBOL default Y
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        AccountReconParams params = new AccountReconParams(
            code(startSupplier),
            code(endSupplier),
            parseInt(startReconNo.getText()),
            parseInt(endReconNo.getText()),
            val(unbalancedFlag),
            val(localFcFlag),
            trimToNull(forCurrCode.getValue()),
            paymtDocNoInd.isSelected() ? "Y" : "N");

        Map<String, Object> data = apReports.getAccountReconData(session, params, "excel".equals(format));
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "pdf".equals(format) ? PDF_PATH : EXCEL_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            new JRBeanCollectionDataSource(rows), format, owner);
        close(e);
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

    private static int parseInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ex) { return 0; }
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
