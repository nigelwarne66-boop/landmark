package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ar.ArDataService;
import com.landmarksoftware.service.ar.ArDataService.DebtorsAgeingParams;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Debtors Ageing (Aged Trial Balance) — port of COBOL ARTL32 (Aging Summary,
 * one row per customer, 4 ageing buckets). PDF mirrors the COBOL layout; Excel
 * is the wide variant. Mirrors the AP Creditors Ageing screen, adapted to AR.
 */
@Component
@Scope("prototype")
public class ArDebtorsAgeingController implements Initializable {

    private static final String PDF_PATH          = "ar/debtors-ageing";
    private static final String EXCEL_PATH        = "ar/debtors-ageing-excel";
    private static final String PDF_DETAIL_PATH   = "ar/debtors-ageing-detail";
    private static final String EXCEL_DETAIL_PATH = "ar/debtors-ageing-detail-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ArDataService        arData;

    @FXML private ComboBox<ArDataService.CodeName> subLedgerStart;
    @FXML private ComboBox<ArDataService.CodeName> subLedgerEnd;
    @FXML private ComboBox<LabelValue> printSeq;
    @FXML private ComboBox<LabelValue> reportType;
    @FXML private ComboBox<ArDataService.CodeName> customerStart;
    @FXML private ComboBox<ArDataService.CodeName> customerEnd;
    @FXML private CheckBox sortDescBalance;
    @FXML private CheckBox includeZeroBalance;
    @FXML private ComboBox<LabelValue> dateInd;
    @FXML private ComboBox<LabelValue> ageUnallocCr;
    @FXML private ComboBox<LabelValue> grossNet;
    @FXML private ComboBox<LabelValue> datesType;
    @FXML private DatePicker anchorDate;
    @FXML private DatePicker manual1;
    @FXML private DatePicker manual2;
    @FXML private DatePicker manual3;
    @FXML private DatePicker manual4;

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
        FxUtil.setAuDate(anchorDate, manual1, manual2, manual3, manual4);
        printSeq.setConverter(conv());
        printSeq.setItems(FXCollections.observableArrayList(
            new LabelValue("Customer number", "N"), new LabelValue("Alpha key", "A")));
        printSeq.getSelectionModel().selectFirst();

        reportType.setConverter(conv());
        reportType.setItems(FXCollections.observableArrayList(
            new LabelValue("Summary", "S"), new LabelValue("Detail", "D")));
        reportType.getSelectionModel().selectFirst();

        dateInd.setConverter(conv());
        dateInd.setItems(FXCollections.observableArrayList(
            new LabelValue("Document date", "T"), new LabelValue("Posting date", "P"), new LabelValue("Due date", "D")));
        dateInd.getSelectionModel().selectFirst();

        ageUnallocCr.setConverter(conv());
        ageUnallocCr.setItems(FXCollections.observableArrayList(
            new LabelValue("By date", "D"), new LabelValue("Against oldest", "O")));
        ageUnallocCr.getSelectionModel().selectFirst();

        grossNet.setConverter(conv());
        grossNet.setItems(FXCollections.observableArrayList(
            new LabelValue("Net outstanding", "N"), new LabelValue("Gross original", "G")));
        grossNet.getSelectionModel().selectFirst();

        datesType.setConverter(conv());
        datesType.setItems(FXCollections.observableArrayList(
            new LabelValue("Calendar months", "C"), new LabelValue("Accounting periods", "P"),
            new LabelValue("Weeks", "W"), new LabelValue("Manual dates", "N")));
        datesType.getSelectionModel().selectFirst();
        datesType.valueProperty().addListener((o, was, is) -> toggleDateMode(is == null ? "C" : is.value()));

        subLedgerStart.setItems(FXCollections.observableArrayList(arData.getSubLedgers(session)));
        subLedgerEnd.setItems(FXCollections.observableArrayList(arData.getSubLedgers(session)));
        subLedgerStart.getSelectionModel().selectFirst();
        subLedgerEnd.getSelectionModel().selectFirst();
        reloadCustomers();
        printSeq.valueProperty().addListener((o, was, is) -> reloadCustomers());

        anchorDate.setValue(LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth()));
        toggleDateMode("C");
    }

    private void toggleDateMode(String type) {
        boolean manual = "N".equals(type);
        anchorDate.setDisable(manual);
        for (DatePicker dp : List.of(manual1, manual2, manual3, manual4)) dp.setDisable(!manual);
    }

    private void reloadCustomers() {
        boolean alpha = "A".equals(val(printSeq));
        customerStart.setItems(FXCollections.observableArrayList(arData.getCustomers(session, alpha)));
        customerEnd.setItems(FXCollections.observableArrayList(arData.getCustomers(session, alpha)));
        customerStart.getSelectionModel().selectFirst();
        customerEnd.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        List<LocalDate> manual = new ArrayList<>();
        for (DatePicker dp : List.of(manual1, manual2, manual3, manual4)) if (dp.getValue() != null) manual.add(dp.getValue());

        boolean detail = "D".equals(val(reportType));
        DebtorsAgeingParams params = new DebtorsAgeingParams(
            code(subLedgerStart), code(subLedgerEnd),
            val(printSeq),
            code(customerStart), code(customerEnd),
            sortDescBalance.isSelected(), includeZeroBalance.isSelected(),
            val(dateInd), val(ageUnallocCr), val(grossNet),
            val(datesType), anchorDate.getValue(), manual,
            detail ? "D" : "S");

        Map<String, Object> data = arData.getDebtorsAgeingData(session, params);
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
        String reportPath = "excel".equals(format)
            ? (detail ? EXCEL_DETAIL_PATH : EXCEL_PATH)
            : (detail ? PDF_DETAIL_PATH   : PDF_PATH);
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            mapDataSource(rows), format, owner);
        close(e);
    }

    private static String val(ComboBox<LabelValue> cb) {
        LabelValue v = cb.getSelectionModel().getSelectedItem();
        return v == null ? "" : v.value();
    }

    private static String code(ComboBox<ArDataService.CodeName> cb) {
        ArDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
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

    private static JRDataSource mapDataSource(List<Map<String, Object>> rows) {
        return new JRDataSource() {
            private final java.util.Iterator<Map<String, Object>> it = rows.iterator();
            private Map<String, Object> current;
            @Override public boolean next() { if (!it.hasNext()) return false; current = it.next(); return true; }
            @Override public Object getFieldValue(JRField f) { return current.get(f.getName()); }
        };
    }
}
