package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ar.ArReportDataService;
import com.landmarksoftware.service.ar.ArReportDataService.CustomerSalesYearParams;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * SMTL38 — Customer Sales by Year selection screen. Five trailing-year sales
 * totals per customer; year 1 is the chosen date range, years 2–5 trail it.
 * PDF mirrors the COBOL columnar layout; Excel is the wide variant.
 */
@Component
@Scope("prototype")
public class ArCustomerSalesByYearController implements Initializable {

    private static final String PDF_PATH   = "ar/customer-sales-by-year";
    private static final String EXCEL_PATH = "ar/customer-sales-by-year-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ArReportDataService  arReports;

    @FXML private ComboBox<ArReportDataService.CodeName> startCustomer;
    @FXML private ComboBox<ArReportDataService.CodeName> endCustomer;
    @FXML private ComboBox<ArReportDataService.CodeName> startSubLedger;
    @FXML private ComboBox<ArReportDataService.CodeName> endSubLedger;
    @FXML private ComboBox<ArReportDataService.CodeName> startCustType;
    @FXML private ComboBox<ArReportDataService.CodeName> endCustType;
    @FXML private ComboBox<ArReportDataService.CodeName> startProductType;
    @FXML private ComboBox<ArReportDataService.CodeName> endProductType;
    @FXML private ComboBox<ArReportDataService.CodeName> startSalesman;
    @FXML private ComboBox<ArReportDataService.CodeName> endSalesman;
    @FXML private DatePicker startDate;
    @FXML private DatePicker endDate;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        fill(startCustomer, endCustomer, arReports.getCustomers(session, false));
        fill(startSubLedger, endSubLedger, arReports.getSubLedgers(session));
        fill(startCustType, endCustType, arReports.getCustomerTypes(session));
        fill(startProductType, endProductType, arReports.getProductTypes(session));
        fill(startSalesman, endSalesman, arReports.getSalesmen(session));

        // Default year-1 window = the last 12 months (user can adjust).
        endDate.setValue(LocalDate.now());
        startDate.setValue(LocalDate.now().minusYears(1).plusDays(1));
    }

    private void fill(ComboBox<ArReportDataService.CodeName> start, ComboBox<ArReportDataService.CodeName> end,
                      List<ArReportDataService.CodeName> items) {
        start.setItems(FXCollections.observableArrayList(items));
        end.setItems(FXCollections.observableArrayList(items));
        start.getSelectionModel().selectFirst();
        end.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        CustomerSalesYearParams params = new CustomerSalesYearParams(
            code(startCustomer), code(endCustomer),
            code(startSubLedger), code(endSubLedger),
            code(startCustType), code(endCustType),
            code(startProductType), code(endProductType),
            code(startSalesman), code(endSalesman),
            startDate.getValue(), endDate.getValue());

        Map<String, Object> data = arReports.getCustomerSalesByYearData(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No sales matched the selection.");
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
