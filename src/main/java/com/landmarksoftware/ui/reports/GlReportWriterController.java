package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.gl.GlReportWriterService;
import com.landmarksoftware.service.gl.GlReportWriterService.RunParams;
import com.landmarksoftware.service.gl.GlReportWriterService.SelectionRow;
import com.landmarksoftware.ui.ReportsHubController;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
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
 * GLRP40 Report Writer Output — two-section screen mirroring the COBOL flow:
 * <ol>
 *   <li>Enter the date period (start + end) once.</li>
 *   <li>List every saved selection in {@code glrpsel} with per-row
 *       <b>PDF</b> / <b>Excel</b> buttons. Each click runs that selection's
 *       vertical format against the entered period using its own
 *       horizontal-layout key (which the engine renders as PTD/YTD/Prior YTD
 *       for "1", single as-at column for "2", etc.). The screen stays open
 *       so a batch of reports can be fired off back-to-back.</li>
 * </ol>
 */
@Component
@Scope("prototype")
public class GlReportWriterController implements Initializable {

    private static final String PDF_PATH   = "gl/report-writer";
    private static final String EXCEL_PATH = "gl/report-writer-excel";

    @Autowired private ReportsHubController hub;
    @Autowired private AppSession            session;
    @Autowired private GlReportWriterService glRw;

    @FXML private DatePicker                 startDate;
    @FXML private DatePicker                 endDate;
    @FXML private Label                      periodHint;
    @FXML private CheckBox                   zeroBalSuppress;
    @FXML private Label                      reportsStatus;
    @FXML private VBox                       reportRows;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // ── Period defaults: end-of-current-month and the fiscal year-start before it ──
        LocalDate today = LocalDate.now();
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        LocalDate guessYrStart = today.withDayOfMonth(1).withMonth(7); // Jul 1 of current calendar year
        if (today.isBefore(guessYrStart)) guessYrStart = guessYrStart.minusYears(1);
        startDate.setValue(guessYrStart);
        endDate.setValue(monthEnd);

        // ── Reports list ─────────────────────────────────────────────────────
        List<SelectionRow> selections = glRw.getSelections(session);
        if (selections.isEmpty()) {
            reportsStatus.setText("No saved selections in glrpsel for " + session.getCompanyName()
                + " — load glrpsel entries to populate this list.");
        } else {
            reportsStatus.setText(selections.size() + " saved selection(s) — click PDF or Excel to run.");
            for (SelectionRow sel : selections) {
                reportRows.getChildren().add(buildReportRow(sel));
            }
        }
    }

    /** Builds one row: # · description · vert/horiz hint · [PDF] [Excel]. */
    private HBox buildReportRow(SelectionRow sel) {
        Label num = new Label(String.valueOf(sel.selectionNo()));
        num.setMinWidth(36);
        num.getStyleClass().add("rw-row-num");

        String title = sel.rptTitle() != null && !sel.rptTitle().isBlank()
            ? sel.rptTitle()
            : (sel.desc1() != null && !sel.desc1().isBlank() ? sel.desc1() : "Selection " + sel.selectionNo());
        Label desc = new Label(title);
        desc.getStyleClass().add("rw-row-desc");
        HBox.setHgrow(desc, Priority.ALWAYS);
        desc.setMaxWidth(Double.MAX_VALUE);

        String horiz = sel.horizFormatKey() == null || sel.horizFormatKey().isBlank() ? "?" : sel.horizFormatKey();
        Label hint = new Label("vert " + sel.vertFormatNo() + " · horiz " + horiz);
        hint.getStyleClass().add("rw-row-hint");

        Button pdfBtn   = new Button("PDF");
        Button excelBtn = new Button("Excel");
        pdfBtn.getStyleClass().add("sel-btn-pdf");
        excelBtn.getStyleClass().add("sel-btn-excel");
        pdfBtn.setOnAction(e   -> runOne(sel, "pdf",   e));
        excelBtn.setOnAction(e -> runOne(sel, "excel", e));

        HBox row = new HBox(10, num, desc, hint, pdfBtn, excelBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("rw-row");
        return row;
    }

    @FXML private void onCancel(ActionEvent e) { close(e); }

    @SuppressWarnings("unchecked")
    private void runOne(SelectionRow sel, String format, ActionEvent e) {
        LocalDate s = startDate.getValue();
        LocalDate t = endDate.getValue();
        if (s == null || t == null) {
            alert(Alert.AlertType.WARNING, "Period required",
                "Enter both a start date and an end date for the report period.");
            return;
        }
        if (t.isBefore(s)) {
            alert(Alert.AlertType.WARNING, "Date order",
                "End date must be on or after the start date.");
            return;
        }

        int vertFormatNo = sel.vertFormatNo();
        if (vertFormatNo <= 0) {
            alert(Alert.AlertType.WARNING, "Bad vertical format",
                "Selection " + sel.selectionNo() + " has no usable vert_format_no.");
            return;
        }
        String horizKey = (sel.horizFormatKey() == null || sel.horizFormatKey().isBlank())
            ? "1" : sel.horizFormatKey();

        RunParams params = new RunParams(
            sel.selectionNo(),
            vertFormatNo,
            horizKey,
            s,
            t,
            zeroBalSuppress.isSelected(),
            sel.roundingFlag() == null ? "" : sel.roundingFlag());

        Map<String, Object> data = glRw.runMatrix(session, params);
        if (data.get("warning") != null) {
            alert(Alert.AlertType.WARNING, "Report Writer", (String) data.get("warning"));
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "No data",
                "Selection " + sel.selectionNo() + " produced no rows.");
            return;
        }
        Map<String, Object> jp = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        Window owner = ((Node) e.getSource()).getScene().getWindow();
        hub.runJasperReportWithDataSource(reportPath, jp,
            new JRBeanCollectionDataSource(rows), format, owner);
        // Leave the screen open so the user can run the next report.
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
