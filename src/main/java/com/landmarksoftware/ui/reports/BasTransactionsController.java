package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.bas.BasReportDataService;
import com.landmarksoftware.service.bas.BasReportDataService.BasTxnParams;
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

/**
 * CPBA06 — BAS Transactions. Filtered listing of cpbastx rows with date range,
 * BAS group/number, date basis and GST code selectors.
 */
@Component
@Scope("prototype")
public class BasTransactionsController implements Initializable {

    private static final String PDF_PATH   = "bas/bas-transactions";
    private static final String EXCEL_PATH = "bas/bas-transactions-excel";

    @Autowired private ReportsHubController  hub;
    @Autowired private AppSession            session;
    @Autowired private BasReportDataService  basReports;

    @FXML private ComboBox<BasReportDataService.CodeName> basGroup;
    @FXML private ComboBox<BasReportDataService.CodeName> basNo;
    @FXML private ComboBox<LabelValue>                    dateInd;
    @FXML private DatePicker                              startDate;
    @FXML private DatePicker                              endDate;
    @FXML private ComboBox<BasReportDataService.CodeName> gstCode;

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
        basGroup.setItems(FXCollections.observableArrayList(basReports.getBasGroupsAll(session)));
        basGroup.getSelectionModel().selectFirst();
        reloadBasNos();
        basGroup.valueProperty().addListener((o, was, is) -> reloadBasNos());

        dateInd.setConverter(conv());
        dateInd.setItems(FXCollections.observableArrayList(
                new LabelValue("Transaction date", "T"),
                new LabelValue("Posting date", "P")));
        dateInd.getSelectionModel().selectFirst();

        gstCode.setItems(FXCollections.observableArrayList(basReports.getGstCodes(session)));
        gstCode.getSelectionModel().selectFirst();
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
        BasTxnParams params = new BasTxnParams(
                code(basGroup), code(basNo),
                startDate.getValue(), endDate.getValue(),
                val(dateInd), code(gstCode));

        Map<String, Object> data = basReports.getBasTransactions(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No BAS transactions matched the selection.");
            return;
        }
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
                new JRBeanCollectionDataSource(rows), format, owner);
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
}
