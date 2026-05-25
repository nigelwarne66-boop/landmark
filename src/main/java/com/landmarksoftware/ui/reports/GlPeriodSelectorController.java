package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.gl.GlPeriodService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.ComboBox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Controller for the shared GlPeriodSelector fragment.
 * Populates both combos from GlPeriodService and exposes the selected
 * period numbers via getFromPeriod() / getToPeriod() for the outer
 * report selection screen to read when building Jasper params.
 */
@Component
@Scope("prototype")
public class GlPeriodSelectorController implements Initializable {

    @FXML private ComboBox<PeriodOption> fromPeriod;
    @FXML private ComboBox<PeriodOption> toPeriod;

    @Autowired private AppSession      session;
    @Autowired private GlPeriodService periods;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        List<PeriodOption> opts = periods.loadPeriods(
            session.getCompanyNo(), session.getYearNo());
        fromPeriod.setItems(FXCollections.observableArrayList(opts));
        toPeriod.setItems(FXCollections.observableArrayList(opts));

        if (!opts.isEmpty()) {
            fromPeriod.getSelectionModel().selectFirst();
            // Default "to" to period 12 if present, else last available.
            opts.stream()
                .filter(p -> p.periodNo() == 12)
                .findFirst()
                .ifPresentOrElse(toPeriod.getSelectionModel()::select,
                                 () -> toPeriod.getSelectionModel().selectLast());
        }
    }

    /** Returns 0 if no period selected. */
    public int getFromPeriod() {
        PeriodOption p = fromPeriod.getSelectionModel().getSelectedItem();
        return p == null ? 0 : p.periodNo();
    }

    /** Returns 0 if no period selected. */
    public int getToPeriod() {
        PeriodOption p = toPeriod.getSelectionModel().getSelectedItem();
        return p == null ? 0 : p.periodNo();
    }
}
