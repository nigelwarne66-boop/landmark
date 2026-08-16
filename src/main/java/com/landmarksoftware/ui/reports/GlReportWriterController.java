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
 * GLRP40 Report Writer Output — bulk selection screen mirroring the COBOL
 * "Select reports to process" dispatcher:
 * <ol>
 *   <li>Enter the date period (start + end) once at the top.</li>
 *   <li>Tick the reports to run from the list of saved selections in
 *       {@code glrpsel}. "Select all" toggles the lot.</li>
 *   <li>Click <b>PDF</b> or <b>Excel</b> at the bottom — the engine runs every
 *       ticked report against the entered period and renders each through
 *       Jasper. A summary alert lists what was generated when the batch
 *       finishes.</li>
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
    @FXML private CheckBox                   selectAllCheck;
    @FXML private Label                      reportsStatus;
    @FXML private VBox                       reportRows;

    /** One row in the list — pairs the rendered checkbox with its selection. */
    private record RowEntry(CheckBox check, SelectionRow sel) {}
    private final List<RowEntry> rowEntries = new ArrayList<>();

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
            selectAllCheck.setDisable(true);
        } else {
            reportsStatus.setText(selections.size() + " saved selection(s) — tick reports, then PDF or Excel below.");
            for (SelectionRow sel : selections) {
                reportRows.getChildren().add(buildReportRow(sel));
            }
        }

        // Master "Select all" toggles every row checkbox.
        selectAllCheck.selectedProperty().addListener((obs, old, val) -> {
            for (RowEntry re : rowEntries) re.check().setSelected(val);
        });
    }

    /** Builds one row: ☐ · # · description · vert/horiz hint. */
    private HBox buildReportRow(SelectionRow sel) {
        CheckBox check = new CheckBox();
        check.getStyleClass().add("rw-row-check");

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

        HBox row = new HBox(10, check, num, desc, hint);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("rw-row");
        // Clicking anywhere on the row toggles the checkbox — friendlier target.
        row.setOnMouseClicked(e -> {
            if (!(e.getTarget() instanceof CheckBox)) check.setSelected(!check.isSelected());
        });

        rowEntries.add(new RowEntry(check, sel));
        return row;
    }

    @FXML private void onPdfAll(ActionEvent e)    { runAll(e, "pdf");   }
    @FXML private void onExcelAll(ActionEvent e)  { runAll(e, "excel"); }
    @FXML private void onCancel(ActionEvent e)    { close(e); }

    /** Runs every ticked report, collects successes/failures, summary alert. */
    private void runAll(ActionEvent e, String format) {
        List<SelectionRow> selected = new ArrayList<>();
        for (RowEntry re : rowEntries) if (re.check().isSelected()) selected.add(re.sel());
        if (selected.isEmpty()) {
            alert(Alert.AlertType.WARNING, "No reports selected",
                "Tick at least one report to run.");
            return;
        }

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

        Window owner = ((Node) e.getSource()).getScene().getWindow();
        List<String> okSelections = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (SelectionRow sel : selected) {
            String status = runOne(sel, format, s, t, owner);
            if (status == null) okSelections.add(sel.selectionNo() + " — " + safeTitle(sel));
            else                errors.add(sel.selectionNo() + " — " + safeTitle(sel) + ": " + status);
        }

        StringBuilder msg = new StringBuilder();
        if (!okSelections.isEmpty()) {
            msg.append("Generated ").append(okSelections.size()).append(" report(s):\n");
            for (String r : okSelections) msg.append("  • ").append(r).append('\n');
        }
        if (!errors.isEmpty()) {
            if (msg.length() > 0) msg.append('\n');
            msg.append(errors.size()).append(" skipped / failed:\n");
            for (String r : errors) msg.append("  • ").append(r).append('\n');
        }
        Alert.AlertType type = errors.isEmpty() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING;
        alert(type, "Bulk run complete", msg.toString().trim());
    }

    /** Runs one selection; returns {@code null} on success or a short reason string. */
    @SuppressWarnings("unchecked")
    private String runOne(SelectionRow sel, String format, LocalDate s, LocalDate t, Window owner) {
        int vertFormatNo = sel.vertFormatNo();
        if (vertFormatNo <= 0) return "no vert_format_no";

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

        Map<String, Object> data;
        try {
            data = glRw.runMatrix(session, params);
        } catch (Exception ex) {
            return "engine error: " + ex.getMessage();
        }
        if (data.get("warning") != null) return (String) data.get("warning");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        if (rows == null || rows.isEmpty()) return "no rows produced";

        Map<String, Object> jp = new HashMap<>((Map<String, Object>) data.get("params"));
        String reportPath = "excel".equals(format) ? EXCEL_PATH : PDF_PATH;
        try {
            hub.runJasperReportWithDataSource(reportPath, jp,
                mapDataSource(rows), format, owner);
        } catch (Exception ex) {
            return "jasper error: " + ex.getMessage();
        }
        return null;
    }

    private static String safeTitle(SelectionRow sel) {
        if (sel.rptTitle() != null && !sel.rptTitle().isBlank()) return sel.rptTitle();
        if (sel.desc1()    != null && !sel.desc1().isBlank())    return sel.desc1();
        return "Selection " + sel.selectionNo();
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
