package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ar.ArReportDataService;
import com.landmarksoftware.service.ar.ArReportDataService.SalesDistParams;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * ARTL10 — Sales Distribution selection screen.
 *
 * <p>Shows MTD and YTD sales by sub-ledger + sales code, this year vs last year,
 * with variance and variance %. Both PDF and Excel fill from
 * {@link ArReportDataService#getSalesDistributionData} via
 * {@link ReportsHubController#runJasperReportWithDataSource}.
 */
@Component
@Scope("prototype")
public class ArSalesDistributionController implements Initializable {

    private static final String PDF_PATH   = "ar/sales-distribution";
    private static final String EXCEL_PATH = "ar/sales-distribution-excel";

    @Autowired private ReportsHubController  hub;
    @Autowired private AppSession            session;
    @Autowired private ArReportDataService   arReports;

    @FXML private ComboBox<ArReportDataService.CodeName> subLedger;
    @FXML private ComboBox<LocalDate>                    periodEndDate;

    private StringConverter<LocalDate> dateConv() {
        return new StringConverter<>() {
            @Override public String toString(LocalDate d)   { return d == null ? "" : d.toString(); }
            @Override public LocalDate fromString(String s) { return null; }
        };
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        periodEndDate.setConverter(dateConv());
        periodEndDate.setItems(FXCollections.observableArrayList(arReports.getPeriodEndDates(session)));
        if (!periodEndDate.getItems().isEmpty()) periodEndDate.getSelectionModel().selectFirst();

        subLedger.setItems(FXCollections.observableArrayList(arReports.getSubLedgers(session)));
        subLedger.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        LocalDate pe = periodEndDate.getSelectionModel().getSelectedItem();
        if (pe == null) {
            alert(Alert.AlertType.WARNING, "No period selected",
                  "Choose a period-ending date before running the report.");
            return;
        }

        SalesDistParams params = new SalesDistParams(code(subLedger), pe);

        Map<String, Object> data = arReports.getSalesDistributionData(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data",
                  "No sales matched the selection.");
            return;
        }
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            new JRBeanCollectionDataSource(rows), format, owner);
        close(e);
    }

    private static String code(ComboBox<ArReportDataService.CodeName> cb) {
        ArReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
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
