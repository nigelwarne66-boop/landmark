package com.landmarksoftware.ui.reports;

import com.landmarksoftware.export.ApSupplierAnalysisExcelExporter;
import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.ap.ApReportDataService;
import com.landmarksoftware.service.ap.ApReportDataService.SupplierAnalysisParams;
import com.landmarksoftware.service.ap.ApReportDataService.SupplierAnalysisResult;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDate;
import java.util.ResourceBundle;

/** APTL10 — Supplier Analysis selection screen (Excel-only, dynamic month columns via POI). */
@Component
@Scope("prototype")
public class ApSupplierAnalysisController implements Initializable {

    @Autowired private ReportsHubController            hub;
    @Autowired private AppSession                      session;
    @Autowired private ApReportDataService             apReports;
    @Autowired private ApSupplierAnalysisExcelExporter exporter;

    @FXML private ComboBox<ApReportDataService.CodeName> startSupplier;
    @FXML private ComboBox<ApReportDataService.CodeName> endSupplier;
    @FXML private ComboBox<ApReportDataService.CodeName> startSubLedger;
    @FXML private ComboBox<ApReportDataService.CodeName> endSubLedger;
    @FXML private TextField noOfMonths;
    @FXML private DatePicker endDate;
    @FXML private TextField topNumber;
    @FXML private ComboBox<LabelValue> basedOn;

    record LabelValue(String label, String value) {
        @Override public String toString() { return label; }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        FxUtil.setAuDate(endDate);
        basedOn.setConverter(new StringConverter<>() {
            @Override public String toString(LabelValue o) { return o == null ? "" : o.label(); }
            @Override public LabelValue fromString(String s) { return null; }
        });
        basedOn.setItems(FXCollections.observableArrayList(
            new LabelValue("Total value of trxs", "TV"),
            new LabelValue("Total no of trxs", "TN"),
            new LabelValue("Avg value of trxs/mth", "AV"),
            new LabelValue("Avg no of trxs/mth", "AN")));
        basedOn.getSelectionModel().selectFirst();
        startSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, false)));
        startSupplier.getSelectionModel().selectFirst();
        endSupplier.setItems(FXCollections.observableArrayList(apReports.getSuppliers(session, false)));
        endSupplier.getSelectionModel().selectFirst();
        startSubLedger.setItems(FXCollections.observableArrayList(apReports.getSubLedgers(session)));
        startSubLedger.getSelectionModel().selectFirst();
        endSubLedger.setItems(FXCollections.observableArrayList(apReports.getSubLedgers(session)));
        endSubLedger.getSelectionModel().selectFirst();
        noOfMonths.setText("12");
    }

    @FXML private void onExcel(ActionEvent e)  { run(e); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    private void run(ActionEvent e) {
        LocalDate end = endDate.getValue();
        if (end == null) {
            alert(Alert.AlertType.WARNING, "End date", "Select an as-at (month-end) date.");
            return;
        }
        if (end.getDayOfMonth() != end.lengthOfMonth()) {
            alert(Alert.AlertType.WARNING, "Month end required", "The as-at date must be the last day of a month.");
            return;
        }
        int months = parseInt(noOfMonths.getText(), 12);
        if (months < 1 || months > 60) {
            alert(Alert.AlertType.WARNING, "Months", "Months history must be between 1 and 60.");
            return;
        }
        SupplierAnalysisParams params = new SupplierAnalysisParams(
            code(startSupplier), code(endSupplier),
            code(startSubLedger), code(endSubLedger),
            months, end, parseInt(topNumber.getText(), 0), val(basedOn));

        SupplierAnalysisResult result = apReports.getSupplierAnalysis(session, params);
        if (result.warning() != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", result.warning());
            return;
        }
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        try {
            byte[] xlsx = exporter.build(result, session.getCompanyName(),
                basedOn.getSelectionModel().getSelectedItem().label());
            hub.saveAndOpen(xlsx, "ap/supplier-analysis", ".xlsx", owner);
            close(e);
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Export failed", ex.getMessage());
        }
    }

    private static String val(ComboBox<LabelValue> cb) {
        LabelValue v = cb.getSelectionModel().getSelectedItem();
        return v == null ? "TV" : v.value();
    }

    private static String code(ComboBox<ApReportDataService.CodeName> cb) {
        ApReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
        String v = c == null ? null : c.code();
        return (v == null || v.isBlank()) ? null : v;
    }

    private static int parseInt(String s, int dflt) {
        if (s == null || s.trim().isEmpty()) return dflt;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ex) { return dflt; }
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
