package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.cm.CmReportDataService;
import com.landmarksoftware.service.cm.CmReportDataService.CashbookTxnParams;
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
 * CMTL10 — Cashbook Transactions selection screen. PDF mirrors the COBOL print
 * layout; Excel is the wide variant. Establishes the CM-module pattern.
 */
@Component
@Scope("prototype")
public class CmCashbookTransactionsController implements Initializable {

    private static final String PDF_PATH   = "cm/cashbook-transactions";
    private static final String EXCEL_PATH = "cm/cashbook-transactions-excel";

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
    @FXML private TextField startDocNo;
    @FXML private TextField endDocNo;
    @FXML private CheckBox unreconciledOnly;
    @FXML private ComboBox<CmReportDataService.CodeName> reconNo;
    @FXML private ComboBox<LabelValue> printSeq;
    @FXML private ComboBox<LabelValue> detailSummary;

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
        reloadRecons();
        bankCode.valueProperty().addListener((o, was, is) -> reloadRecons());

        printSeq.setConverter(conv());
        printSeq.setItems(FXCollections.observableArrayList(
            new LabelValue("Date", "D"), new LabelValue("Document number", "N")));
        printSeq.getSelectionModel().selectFirst();

        detailSummary.setConverter(conv());
        detailSummary.setItems(FXCollections.observableArrayList(
            new LabelValue("Detail", "D"), new LabelValue("Summary", "S")));
        detailSummary.getSelectionModel().selectFirst();

        inclDeposits.setSelected(true); inclBankCredits.setSelected(true);
        inclCheques.setSelected(true); inclBankDebits.setSelected(true);
        unreconciledOnly.setSelected(false);
        unreconciledOnly.selectedProperty().addListener((o, was, is) -> reconNo.setDisable(is));
    }

    private void reloadRecons() {
        reconNo.setItems(FXCollections.observableArrayList(cmReports.getReconNumbers(session, code(bankCode))));
        reconNo.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        CashbookTxnParams params = new CashbookTxnParams(
            code(bankCode),
            startDate.getValue(), endDate.getValue(),
            inclDeposits.isSelected(), inclBankCredits.isSelected(),
            inclCheques.isSelected(), inclBankDebits.isSelected(),
            parseInt(startDocNo.getText()), parseInt(endDocNo.getText()),
            unreconciledOnly.isSelected(),
            unreconciledOnly.isSelected() ? null : code(reconNo),
            val(printSeq), val(detailSummary));

        Map<String, Object> data = cmReports.getCashbookTransactions(session, params);
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

    private static int parseInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ex) { return 0; }
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
