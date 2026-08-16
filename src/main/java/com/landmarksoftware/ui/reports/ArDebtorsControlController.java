package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ar.ArReportDataService;
import com.landmarksoftware.service.ar.ArReportDataService.DebtorsControlParams;
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
 * Debtors Control (ARTL11) — customer account balances + period sales,
 * sortable by balance or sales, optional top-N, optional credit limit column.
 */
@Component
@Scope("prototype")
public class ArDebtorsControlController implements Initializable {

    private static final String PDF_PATH   = "ar/debtors-control";
    private static final String EXCEL_PATH = "ar/debtors-control-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ArReportDataService  arReports;

    @FXML private ComboBox<ArReportDataService.CodeName> subLedger;
    @FXML private ComboBox<ArReportDataService.CodeName> startCustomer;
    @FXML private ComboBox<ArReportDataService.CodeName> endCustomer;
    @FXML private ComboBox<LabelValue>                   sortSeq;
    @FXML private ComboBox<LabelValue>                   mtdYtd;
    @FXML private ComboBox<LocalDate>                    periodEndDate;
    @FXML private TextField                              topN;
    @FXML private CheckBox                               includeCreditLimit;

    record LabelValue(String label, String value) {
        @Override public String toString() { return label; }
    }

    private StringConverter<LabelValue> conv() {
        return new StringConverter<>() {
            @Override public String toString(LabelValue o)    { return o == null ? "" : o.label(); }
            @Override public LabelValue fromString(String s)  { return null; }
        };
    }

    private final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private StringConverter<LocalDate> dateConv() {
        return new StringConverter<>() {
            @Override public String toString(LocalDate d)    { return d == null ? "" : d.format(dateFmt); }
            @Override public LocalDate fromString(String s)  { return null; }
        };
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        sortSeq.setConverter(conv());
        sortSeq.setItems(FXCollections.observableArrayList(
            new LabelValue("Account balance", "A"),
            new LabelValue("Sales value",     "S")));
        sortSeq.getSelectionModel().selectFirst();

        mtdYtd.setConverter(conv());
        mtdYtd.setItems(FXCollections.observableArrayList(
            new LabelValue("Year to date",   "Y"),
            new LabelValue("Month to date",  "M")));
        mtdYtd.getSelectionModel().selectFirst();

        periodEndDate.setConverter(dateConv());
        List<LocalDate> dates = arReports.getPeriodEndDates(session);
        periodEndDate.setItems(FXCollections.observableArrayList(dates));
        if (!dates.isEmpty()) periodEndDate.getSelectionModel().selectFirst();

        List<ArReportDataService.CodeName> subs = arReports.getSubLedgers(session);
        subLedger.setItems(FXCollections.observableArrayList(subs));
        subLedger.getSelectionModel().selectFirst();

        List<ArReportDataService.CodeName> custs = arReports.getCustomers(session, false);
        startCustomer.setItems(FXCollections.observableArrayList(custs));
        endCustomer.setItems(FXCollections.observableArrayList(custs));
        startCustomer.getSelectionModel().selectFirst();
        endCustomer.getSelectionModel().selectLast();

        topN.setText("0");
        includeCreditLimit.setSelected(true);
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        int top = 0;
        try { top = Integer.parseInt(topN.getText().trim()); } catch (NumberFormatException ignored) {}

        DebtorsControlParams params = new DebtorsControlParams(
            code(subLedger),
            code(startCustomer), code(endCustomer),
            val(sortSeq), val(mtdYtd),
            periodEndDate.getSelectionModel().getSelectedItem(),
            top,
            includeCreditLimit.isSelected());

        Map<String, Object> data = arReports.getDebtorsControlData(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No customers matched the selection.");
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
