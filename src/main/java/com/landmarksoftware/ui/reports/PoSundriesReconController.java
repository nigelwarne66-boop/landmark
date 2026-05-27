package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.po.PoReportDataService;
import com.landmarksoftware.service.po.PoReportDataService.SundriesReconParams;
import com.landmarksoftware.ui.ReportsHubController;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
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
 * POTL39 — Sundries Reconcile. AP purchase documents (apdocno): matched value +
 * adjustments, filtered by as-at date and supplier range.
 */
@Component
@Scope("prototype")
public class PoSundriesReconController implements Initializable {

    private static final String PDF_PATH   = "po/sundries-reconcile";
    private static final String EXCEL_PATH = "po/sundries-reconcile-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession           session;
    @Autowired private PoReportDataService  poReports;

    @FXML private DatePicker                             asAtDate;
    @FXML private ComboBox<PoReportDataService.CodeName> startSupplier;
    @FXML private ComboBox<PoReportDataService.CodeName> endSupplier;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        startSupplier.setItems(FXCollections.observableArrayList(poReports.getSuppliers(session)));
        endSupplier.setItems(FXCollections.observableArrayList(poReports.getSuppliers(session)));
        startSupplier.getSelectionModel().selectFirst();
        endSupplier.getSelectionModel().selectFirst();
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        SundriesReconParams params = new SundriesReconParams(
            asAtDate.getValue(),
            code(startSupplier), code(endSupplier));

        Map<String, Object> data = poReports.getSundriesReconcile(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Nothing to list", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "No purchase documents matched the selection.");
            return;
        }
        Map<String, Object> jasperParams = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jasperParams,
            new JRBeanCollectionDataSource(rows), format, owner);
        close(e);
    }

    private static String code(ComboBox<PoReportDataService.CodeName> cb) {
        PoReportDataService.CodeName c = cb.getSelectionModel().getSelectedItem();
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
