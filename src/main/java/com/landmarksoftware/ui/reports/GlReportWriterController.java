package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.gl.GlReportWriterService;
import com.landmarksoftware.service.gl.GlReportWriterService.HorizontalTableRow;
import com.landmarksoftware.service.gl.GlReportWriterService.RunParams;
import com.landmarksoftware.service.gl.GlReportWriterService.SelectionRow;
import com.landmarksoftware.service.gl.GlReportWriterService.VerticalFormatRow;
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
 * GLRP40 Report Writer Output — selection screen. Lists saved selections from
 * {@code glrpsel} (auto-fill on pick) and lets the user override or pick
 * vertical / horizontal formats directly. Hands off to
 * {@link GlReportWriterService#runMatrix} and renders via Jasper.
 */
@Component
@Scope("prototype")
public class GlReportWriterController implements Initializable {

    private static final String PDF_PATH   = "gl/report-writer";
    private static final String EXCEL_PATH = "gl/report-writer-excel";

    @Autowired private ReportsHubController     hub;
    @Autowired private AppSession                session;
    @Autowired private GlReportWriterService     glRw;

    @FXML private ComboBox<SelectionRow>        selectionCombo;
    @FXML private Label                         selectionStatus;
    @FXML private ComboBox<VerticalFormatRow>   vertCombo;
    @FXML private ComboBox<HorizontalTableRow>  horizCombo;
    @FXML private TextField                     yearField;
    @FXML private CheckBox                      zeroBalSuppress;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Saved selections — empty until glrpsel is loaded.
        List<SelectionRow> selections = glRw.getSelections(session);
        selectionCombo.setItems(FXCollections.observableArrayList(selections));
        if (selections.isEmpty()) {
            selectionCombo.setDisable(true);
            selectionStatus.setText("No saved selections loaded — pick a vertical + horizontal format below.");
        } else {
            selectionStatus.setText(selections.size() + " saved selection(s). Pick one to auto-fill the fields below, or override.");
        }

        // Vertical / horizontal formats — directly from glrpveh / glrptah.
        vertCombo.setItems(FXCollections.observableArrayList(glRw.getVerticalFormats(session)));
        if (!vertCombo.getItems().isEmpty()) vertCombo.getSelectionModel().selectFirst();

        horizCombo.setItems(FXCollections.observableArrayList(glRw.getHorizontalTables(session)));
        if (!horizCombo.getItems().isEmpty()) horizCombo.getSelectionModel().selectFirst();

        // Year defaults to the session year.
        yearField.setText(String.valueOf(session.getYearNo()));

        // Picking a saved selection auto-fills the manual fields with its values.
        selectionCombo.valueProperty().addListener((obs, oldV, sel) -> {
            if (sel == null) return;
            selectFromVerticals(sel.vertFormatNo());
            selectFromHorizontals(sel.horizFormatKey());
            yearField.setText(String.valueOf(sel.yrNo()));
            zeroBalSuppress.setSelected(sel.zeroBalSuppress());
        });
    }

    @FXML private void onPdf(ActionEvent e)    { run(e, "pdf"); }
    @FXML private void onExcel(ActionEvent e)  { run(e, "excel"); }
    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void run(ActionEvent e, String format) {
        VerticalFormatRow v = vertCombo.getValue();
        HorizontalTableRow h = horizCombo.getValue();
        if (v == null) { alert(Alert.AlertType.WARNING, "Pick a vertical format", "No vertical formats loaded in glrpveh for this company."); return; }
        if (h == null) { alert(Alert.AlertType.WARNING, "Pick a horizontal table", "No horizontal tables loaded in glrptah for this company."); return; }
        int year = parseIntOr(yearField.getText(), session.getYearNo());

        RunParams params = new RunParams(
            v.vertFormatNo(),
            h.dateTableCode(),
            year,
            zeroBalSuppress.isSelected(),
            ""); // rounding flag — Phase 3 will wire this from glrpsel

        Map<String, Object> data = glRw.runMatrix(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Report Writer", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data", "The report produced no rows.");
            return;
        }
        Map<String, Object> jp = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jp,
            new JRBeanCollectionDataSource(rows), format, owner);
        close(e);
    }

    private void selectFromVerticals(int vertFormatNo) {
        for (VerticalFormatRow vf : vertCombo.getItems()) {
            if (vf.vertFormatNo() == vertFormatNo) { vertCombo.getSelectionModel().select(vf); return; }
        }
    }

    private void selectFromHorizontals(String key) {
        if (key == null || key.isBlank()) return;
        for (HorizontalTableRow hf : horizCombo.getItems()) {
            if (key.equalsIgnoreCase(hf.dateTableCode())) { horizCombo.getSelectionModel().select(hf); return; }
        }
    }

    private static int parseIntOr(String s, int dflt) {
        if (s == null) return dflt;
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
