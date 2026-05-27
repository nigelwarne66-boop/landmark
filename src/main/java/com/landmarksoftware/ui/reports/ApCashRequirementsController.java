package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ap.ApReportDataService;
import com.landmarksoftware.service.ap.ApReportDataService.CashReqParams;
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

/** APTL01 — Cash Requirements selection screen (detail = APTL02, summary = APTL03). */
@Component
@Scope("prototype")
public class ApCashRequirementsController implements Initializable {

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ApReportDataService  apReports;

    @FXML private ComboBox<ApReportDataService.CodeName> subLedger;
    @FXML private ComboBox<ApReportDataService.CodeName> startSupplier;
    @FXML private ComboBox<ApReportDataService.CodeName> endSupplier;
    @FXML private DatePicker paymentDate;
    @FXML private DatePicker nextPaymentDate;
    @FXML private ComboBox<LabelValue> detailSummary;
    @FXML private CheckBox onHold;
    @FXML private CheckBox selected;
    @FXML private CheckBox deductWithholding;
    @FXML private CheckBox flagMustPay;
    @FXML private CheckBox flagPromptPay;
    @FXML private CheckBox flagOverdue;
    @FXML private CheckBox flagDue;
    @FXML private CheckBox flagDiscAvail;

    record LabelValue(String label, String value) {
        @Override public String toString() { return label; }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        detailSummary.setConverter(new StringConverter<>() {
            @Override public String toString(LabelValue o) { return o == null ? "" : o.label(); }
            @Override public LabelValue fromString(String s) { return null; }
        });
        detailSummary.setItems(FXCollections.observableArrayList(
            new LabelValue("Detail (per transaction)", "D"),
            new LabelValue("Summary (per supplier)", "S")));
        detailSummary.getSelectionModel().selectFirst();
        subLedger.setItems(FXCollections.observableArrayList(apReports.getSubLedgers(session)));
        subLedger.getSelectionModel().selectFirst();
        startSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, false)));
        startSupplier.getSelectionModel().selectFirst();
        endSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, false)));
        endSupplier.getSelectionModel().selectFirst();
        // sensible defaults — all allocation rules on so the report produces output
        flagMustPay.setSelected(true); flagPromptPay.setSelected(true); flagOverdue.setSelected(true);
        flagDue.setSelected(true); flagDiscAvail.setSelected(true);
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        if (paymentDate.getValue() == null || nextPaymentDate.getValue() == null) {
            alert(Alert.AlertType.WARNING, "Dates required", "Enter both a payment date and a next payment date.");
            return;
        }
        boolean summary = "S".equals(val(detailSummary));
        CashReqParams params = new CashReqParams(
            code(subLedger),
            code(startSupplier),
            code(endSupplier),
            paymentDate.getValue(),
            nextPaymentDate.getValue(),
            onHold.isSelected(), selected.isSelected(), deductWithholding.isSelected(),
            flagMustPay.isSelected(), flagPromptPay.isSelected(), flagOverdue.isSelected(),
            flagDue.isSelected(), flagDiscAvail.isSelected());

        Map<String, Object> data = apReports.getCashRequirementsData(session, params, summary);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String base = summary ? "ap/cash-requirements-summary" : "ap/cash-requirements-detail";
        String reportPath = "excel".equals(format) ? base + "-excel" : base;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            new JRBeanCollectionDataSource(rows), format, owner);
        close(e);
    }

    private static String val(ComboBox<LabelValue> cb) {
        LabelValue v = cb.getSelectionModel().getSelectedItem();
        return v == null ? "D" : v.value();
    }

    private static String code(ComboBox<ApReportDataService.CodeName> cb) {
        ApReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
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
}
