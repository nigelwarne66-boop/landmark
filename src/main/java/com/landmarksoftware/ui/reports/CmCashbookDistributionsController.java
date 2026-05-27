package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.cm.CmReportDataService;
import com.landmarksoftware.service.cm.CmReportDataService.CashbookDistParams;
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
 * CMTL14 — Cashbook Distributions selection screen.
 * PDF mirrors the COBOL print layout; Excel is the wide variant.
 */
@Component
@Scope("prototype")
public class CmCashbookDistributionsController implements Initializable {

    private static final String PDF_PATH   = "cm/cashbook-distributions";
    private static final String EXCEL_PATH = "cm/cashbook-distributions-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private CmReportDataService  cmReports;

    @FXML private ComboBox<CmReportDataService.CodeName> bankCode;
    @FXML private DatePicker startDate;
    @FXML private DatePicker endDate;
    @FXML private CheckBox inclDeposits;
    @FXML private CheckBox inclBankCredits;
    @FXML private CheckBox inclCheques;
    @FXML private CheckBox inclBankDebits;
    @FXML private ComboBox<CmReportDataService.CodeName> startGlMain;
    @FXML private ComboBox<CmReportDataService.CodeName> endGlMain;
    @FXML private ComboBox<CmReportDataService.CodeName> taxCode;
    @FXML private ComboBox<LabelValue> printSeq;

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
        bankCode.setItems(FXCollections.observableArrayList(cmReports.getBanks(session)));
        bankCode.getSelectionModel().selectFirst();

        List<CmReportDataService.CodeName> glMains = cmReports.getGlMainAccounts(session);
        startGlMain.setItems(FXCollections.observableArrayList(glMains));
        startGlMain.getSelectionModel().selectFirst();
        endGlMain.setItems(FXCollections.observableArrayList(glMains));
        endGlMain.getSelectionModel().selectFirst();

        taxCode.setItems(FXCollections.observableArrayList(cmReports.getTaxCodes(session)));
        taxCode.getSelectionModel().selectFirst();

        printSeq.setConverter(conv());
        printSeq.setItems(FXCollections.observableArrayList(
            new LabelValue("Date", "D"), new LabelValue("Document number", "N")));
        printSeq.getSelectionModel().selectFirst();

        inclDeposits.setSelected(true);
        inclBankCredits.setSelected(true);
        inclCheques.setSelected(true);
        inclBankDebits.setSelected(true);
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        CashbookDistParams params = new CashbookDistParams(
            code(bankCode),
            startDate.getValue(), endDate.getValue(),
            inclDeposits.isSelected(), inclBankCredits.isSelected(),
            inclCheques.isSelected(), inclBankDebits.isSelected(),
            code(startGlMain), code(endGlMain),
            code(taxCode), val(printSeq));

        Map<String, Object> data = cmReports.getCashbookDistributions(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No distribution lines matched the selection.");
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

    private static String code(ComboBox<CmReportDataService.CodeName> cb) {
        CmReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
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
