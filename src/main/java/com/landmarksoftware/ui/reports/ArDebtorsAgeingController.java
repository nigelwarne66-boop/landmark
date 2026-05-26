package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ar.ArDataService;
import com.landmarksoftware.ui.ReportsHubController;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Debtors Ageing — summary mode only for v1.
 *
 * <p>cc-migration.md lists a Summary/Detail combo but the detail layout
 * needs a separate .jrxml (different field set: docDate / docType / docNo
 * / status instead of location / phone). Detail mode is deferred to a
 * follow-up — controller always passes 'S' for detailSummary.
 *
 * <p>Data is pre-fetched via ArDataService.getDebtorsListingData and
 * passed to Jasper as a JRBeanCollectionDataSource because the underlying
 * SQL is built dynamically based on the selection flags.
 */
@Component
@Scope("prototype")
public class ArDebtorsAgeingController implements Initializable {

    private static final String REPORT_PATH = "ar/debtors-ageing";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ArDataService        arData;

    @FXML private ComboBox<LabelValue> dateInd;
    @FXML private ComboBox<LabelValue> grossNet;
    @FXML private DatePicker           asAtDate;

    /** Small (label, value) pair for combo display vs Jasper param value. */
    record LabelValue(String label, String value) {
        @Override public String toString() { return label; }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        StringConverter<LabelValue> conv = new StringConverter<>() {
            @Override public String toString(LabelValue o) { return o == null ? "" : o.label(); }
            @Override public LabelValue fromString(String s) { return null; }
        };
        dateInd.setConverter(conv);
        dateInd.setItems(FXCollections.observableArrayList(
            new LabelValue("Due date",         "D"),
            new LabelValue("Transaction date", "T"),
            new LabelValue("Posting date",     "P")));
        dateInd.getSelectionModel().selectFirst();

        grossNet.setConverter(conv);
        grossNet.setItems(FXCollections.observableArrayList(
            new LabelValue("Net outstanding", "N"),
            new LabelValue("Gross original",  "G")));
        grossNet.getSelectionModel().selectFirst();

        asAtDate.setValue(LocalDate.now());
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        String dateBasis = dateInd.getSelectionModel().getSelectedItem().value();
        String amtBasis  = grossNet.getSelectionModel().getSelectedItem().value();
        LocalDate asAt   = asAtDate.getValue() != null ? asAtDate.getValue() : LocalDate.now();
        String asAtStr   = asAt.format(DateTimeFormatter.ISO_LOCAL_DATE);

        Map<String, Object> data = arData.getDebtorsListingData(session, "S", dateBasis, amtBasis, asAtStr);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.getOrDefault("rows", List.of());

        Map<String, Object> jasperParams = new HashMap<>();
        jasperParams.put("AS_AT_DATE", asAt.format(DateTimeFormatter.ofPattern("dd MMM yyyy")));
        jasperParams.put("DATE_BASIS", dateInd.getSelectionModel().getSelectedItem().label());
        jasperParams.put("AMT_BASIS",  grossNet.getSelectionModel().getSelectedItem().label());

        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(
            REPORT_PATH, jasperParams,
            new JRBeanCollectionDataSource(rows),
            format, owner);
        close(e);
    }

    private void close(ActionEvent e) {
        ((Stage) ((Node) e.getSource()).getScene().getWindow()).close();
    }
}
