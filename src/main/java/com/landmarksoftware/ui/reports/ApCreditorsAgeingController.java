package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ap.ApDataService;
import com.landmarksoftware.service.ap.ApDataService.CreditorsAgeingParams;
import com.landmarksoftware.service.ap.ApReportDataService;
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
 * Creditors Ageing (Aged Trial Balance) — port of COBOL APTL07's three selection
 * screens (s0 range/status, s1 ageing details, s2 ageing dates) consolidated into
 * one form. PDF mirrors the COBOL layout; Excel shares the template per mode.
 */
@Component
@Scope("prototype")
public class ApCreditorsAgeingController implements Initializable {

    private static final String SUMMARY_PATH = "ap/creditors-ageing";
    private static final String DETAIL_PATH  = "ap/creditors-ageing-detail";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private ApDataService        apData;
    @Autowired private ApReportDataService  apReports;

    // s0 — range & status
    @FXML private ComboBox<ApReportDataService.CodeName> subLedgerStart;
    @FXML private ComboBox<ApReportDataService.CodeName> subLedgerEnd;
    @FXML private ComboBox<LabelValue> printSeq;
    @FXML private ComboBox<ApReportDataService.CodeName> supplierStart;
    @FXML private ComboBox<ApReportDataService.CodeName> supplierEnd;
    @FXML private ComboBox<LabelValue> detailSummary;
    @FXML private CheckBox zeroSupplier;
    @FXML private CheckBox includeUnposted;
    @FXML private CheckBox includePosted;
    @FXML private CheckBox includeOnHold;
    @FXML private ComboBox<LabelValue> fullyDelivered;
    @FXML private ComboBox<LabelValue> withinTolerance;
    // s1 — ageing details
    @FXML private ComboBox<LabelValue> dateInd;
    @FXML private ComboBox<LabelValue> ageUnallocCr;
    @FXML private ComboBox<LabelValue> grossNet;
    @FXML private CheckBox includeClaims;
    @FXML private CheckBox includeArchived;
    // s2 — ageing dates
    @FXML private ComboBox<LabelValue> datesType;
    @FXML private DatePicker anchorDate;
    @FXML private DatePicker manual1;
    @FXML private DatePicker manual2;
    @FXML private DatePicker manual3;
    @FXML private DatePicker manual4;
    @FXML private DatePicker manual5;
    @FXML private DatePicker manual6;

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
        FxUtil.setAuDate(anchorDate, manual1, manual2, manual3, manual4, manual5, manual6);
        printSeq.setConverter(conv());
        printSeq.setItems(FXCollections.observableArrayList(
            new LabelValue("Supplier number", "N"), new LabelValue("Alpha key", "A")));
        printSeq.getSelectionModel().selectFirst();

        subLedgerStart.setItems(FXCollections.observableArrayList(apReports.getSubLedgers(session)));
        subLedgerStart.getSelectionModel().selectFirst();
        subLedgerEnd.setItems(FXCollections.observableArrayList(apReports.getSubLedgers(session)));
        subLedgerEnd.getSelectionModel().selectFirst();
        reloadSuppliers();
        printSeq.valueProperty().addListener((o, was, is) -> reloadSuppliers());

        detailSummary.setConverter(conv());
        detailSummary.setItems(FXCollections.observableArrayList(
            new LabelValue("Summary (per supplier)", "S"), new LabelValue("Detail (per transaction)", "D")));
        detailSummary.getSelectionModel().selectFirst();
        detailSummary.valueProperty().addListener((o, was, is) -> {
            boolean detail = is != null && "D".equals(is.value());
            if (detail) { zeroSupplier.setSelected(true); }   // COBOL forces Y in detail
            zeroSupplier.setDisable(detail);
        });

        for (ComboBox<LabelValue> cb : List.of(fullyDelivered, withinTolerance)) {
            cb.setConverter(conv());
            cb.setItems(FXCollections.observableArrayList(
                new LabelValue("All", ""), new LabelValue("Yes", "Y"), new LabelValue("No", "N")));
            cb.getSelectionModel().selectFirst();
        }
        includeOnHold.selectedProperty().addListener((o, was, is) -> {
            fullyDelivered.setDisable(!is); withinTolerance.setDisable(!is);
        });

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

        // defaults
        includePosted.setSelected(true); includeOnHold.setSelected(true); includeUnposted.setSelected(false);
        includeClaims.setSelected(true); includeArchived.setSelected(false); zeroSupplier.setSelected(false);
        anchorDate.setValue(LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth()));
        toggleDateMode("C");
    }

    private void toggleDateMode(String type) {
        boolean manual = "N".equals(type);
        anchorDate.setDisable(manual);
        for (DatePicker dp : manualPickers()) dp.setDisable(!manual);
    }

    private List<DatePicker> manualPickers() {
        return List.of(manual1, manual2, manual3, manual4, manual5, manual6);
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        List<LocalDate> manual = new ArrayList<>();
        for (DatePicker dp : manualPickers()) if (dp.getValue() != null) manual.add(dp.getValue());

        CreditorsAgeingParams params = new CreditorsAgeingParams(
            code(subLedgerStart), code(subLedgerEnd),
            val(printSeq),
            code(supplierStart), code(supplierEnd),
            val(detailSummary),
            zeroSupplier.isSelected(),
            includeUnposted.isSelected(), includePosted.isSelected(), includeOnHold.isSelected(),
            val(fullyDelivered), val(withinTolerance),
            val(dateInd), val(ageUnallocCr), val(grossNet),
            includeClaims.isSelected(), includeArchived.isSelected(),
            val(datesType), anchorDate.getValue(), manual);

        Map<String, Object> data = apData.getCreditorsAgeingData(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No transactions matched the selection.");
            return;
        }
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        boolean detail = "D".equals(val(detailSummary));
        String base = detail ? DETAIL_PATH : SUMMARY_PATH;
        String reportPath = "excel".equals(format) ? base + "-excel" : base;   // wide Excel variant
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            mapDataSource(rows), format, owner);
        close(e);
    }

    private void reloadSuppliers() {
        boolean alpha = "A".equals(val(printSeq));
        supplierStart.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, alpha)));
        supplierEnd.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, alpha)));
        supplierStart.getSelectionModel().selectFirst();
        supplierEnd.getSelectionModel().selectFirst();
    }

    private static String val(ComboBox<LabelValue> cb) {
        LabelValue v = cb.getSelectionModel().getSelectedItem();
        return v == null ? "" : v.value();
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
