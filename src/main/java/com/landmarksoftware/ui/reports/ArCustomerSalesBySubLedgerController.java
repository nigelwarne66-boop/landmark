package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ar.ArReportDataService;
import com.landmarksoftware.service.ar.ArReportDataService.CustomerSalesParams;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Customer Sales by Sub Ledger (ARTL27) — YTD sales / cost / profit grouped
 * by sub-ledger, this year vs last year. Shares jrxml with ARTL06 (byType=false).
 */
@Component
@Scope("prototype")
public class ArCustomerSalesBySubLedgerController implements Initializable {

    private static final String PDF_PATH   = "ar/customer-sales";
    private static final String EXCEL_PATH = "ar/customer-sales-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ArReportDataService  arReports;

    @FXML private ComboBox<ArReportDataService.CodeName> startSubLedger;
    @FXML private ComboBox<ArReportDataService.CodeName> endSubLedger;
    @FXML private ComboBox<LocalDate>                    periodEndDate;
    @FXML private CheckBox                               includeLastYear;

    private final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private StringConverter<LocalDate> dateConv() {
        return new StringConverter<>() {
            @Override public String toString(LocalDate d)    { return d == null ? "" : d.format(dateFmt); }
            @Override public LocalDate fromString(String s)  { return null; }
        };
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        List<ArReportDataService.CodeName> subs = arReports.getSubLedgers(session);
        startSubLedger.setItems(FXCollections.observableArrayList(subs));
        endSubLedger.setItems(FXCollections.observableArrayList(subs));
        startSubLedger.getSelectionModel().selectFirst();
        endSubLedger.getSelectionModel().selectLast();

        periodEndDate.setConverter(dateConv());
        List<LocalDate> dates = arReports.getPeriodEndDates(session);
        periodEndDate.setItems(FXCollections.observableArrayList(dates));
        if (!dates.isEmpty()) periodEndDate.getSelectionModel().selectFirst();

        includeLastYear.setSelected(true);
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        CustomerSalesParams params = new CustomerSalesParams(
            false,  // byType = false for ARTL27
            code(startSubLedger), code(endSubLedger),
            periodEndDate.getSelectionModel().getSelectedItem(),
            includeLastYear.isSelected());

        Map<String, Object> data = arReports.getCustomerSalesData(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No customer sales matched the selection.");
            return;
        }
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            mapDataSource(rows), format, owner);
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

    private static JRDataSource mapDataSource(java.util.List<java.util.Map<String, Object>> rows) {
        return new JRDataSource() {
            private final java.util.Iterator<java.util.Map<String, Object>> it = rows.iterator();
            private java.util.Map<String, Object> current;
            @Override public boolean next() { if (!it.hasNext()) return false; current = it.next(); return true; }
            @Override public Object getFieldValue(JRField f) { return current.get(f.getName()); }
        };
    }
}
