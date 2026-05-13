/*
 * Copyright (c) 2026 Landmark Software Pty Ltd.
 * All rights reserved.
 *
 * This software is proprietary and confidential.
 * Unauthorised copying, modification, distribution or use
 * of this software, via any medium, is strictly prohibited.
 * Decompilation and reverse engineering are expressly forbidden.
 *
 * Licenced under the terms of the Landmark Software Licence Agreement.
 */
package com.landmarksoftware.payroll.ui;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.payroll.model.TaxBracket;
import com.landmarksoftware.payroll.service.TaxBracketLoader;
import com.landmarksoftware.payroll.service.TaxBracketService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Year-end utility — load the annual ATO tax-scale workbooks into the
 * {@code tax_brackets} table.
 *
 * <p>Mirrors the {@code TaxBracketLoaderMain} CLI but inside the JavaFX
 * shell so the payroll administrator can run it from the Year End card.
 * Each load is one transaction; rerunning the same publication safely
 * replaces it (DELETE-then-INSERT inside the service).
 */
@Component
public class TaxScaleLoadController {

    private final TaxBracketLoader  loader;
    private final TaxBracketService service;
    private final AppSession        appSession;

    public TaxScaleLoadController(TaxBracketLoader loader,
                                   TaxBracketService service,
                                   AppSession appSession) {
        this.loader     = loader;
        this.service    = service;
        this.appSession = appSession;
    }

    public Scene buildScene(Stage stage) {
        Label hdr = new Label("Load ATO Tax Scales");
        hdr.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label sub = new Label("Annual update from NAT_1004 (PAYG) and NAT_3539 (STSL) workbooks");
        sub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        VBox hdrBox = new VBox(2, hdr, sub);
        hdrBox.setPadding(new Insets(16, 20, 12, 20));

        ComboBox<String> sourceCombo = new ComboBox<>();
        sourceCombo.getItems().addAll("NAT_1004", "NAT_3539");
        sourceCombo.setValue("NAT_1004");

        DatePicker effDate = new DatePicker(defaultEffectiveFrom());
        effDate.setShowWeekNumbers(false);
        effDate.setPrefWidth(160);

        TextField filePath = new TextField();
        filePath.setPromptText("Select .xlsx workbook…");
        filePath.setEditable(false);
        filePath.setPrefWidth(360);

        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select ATO tax-scale workbook");
            fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Excel workbook (*.xlsx)", "*.xlsx"));
            String current = filePath.getText();
            if (current != null && !current.isBlank()) {
                File f = new File(current);
                if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                    fc.setInitialDirectory(f.getParentFile());
                }
            }
            File picked = fc.showOpenDialog(stage);
            if (picked != null) filePath.setText(picked.getAbsolutePath());
        });

        HBox fileRow = new HBox(8, filePath, browse);
        fileRow.setAlignment(Pos.CENTER_LEFT);

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        form.setPadding(new Insets(0, 20, 0, 20));
        form.add(new Label("Source file:"),    0, 0);
        form.add(sourceCombo,                  1, 0);
        form.add(new Label("Effective from:"), 0, 1);
        form.add(effDate,                      1, 1);
        form.add(new Label("Workbook:"),       0, 2);
        form.add(fileRow,                      1, 2);

        Label status = new Label(" ");
        status.setStyle("-fx-text-fill:#1A1A2E;");
        status.setWrapText(true);
        status.setMaxWidth(480);

        Button loadBtn = new Button("Load");
        loadBtn.setDefaultButton(true);
        Button closeBtn = new Button("Close");
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> stage.close());
        HBox buttons = new HBox(8, loadBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(16, 20, 16, 20));

        loadBtn.setOnAction(e -> {
            String source = sourceCombo.getValue();
            LocalDate eff = effDate.getValue();
            String pathStr = filePath.getText();
            if (source == null || eff == null || pathStr == null || pathStr.isBlank()) {
                status.setText("Choose a source, effective date and workbook before loading.");
                status.setStyle("-fx-text-fill:#A33;");
                return;
            }
            Path xlsx = Path.of(pathStr);
            if (!xlsx.toFile().isFile()) {
                status.setText("File not found: " + xlsx);
                status.setStyle("-fx-text-fill:#A33;");
                return;
            }
            loadBtn.setDisable(true);
            status.setStyle("-fx-text-fill:#555;");
            status.setText("Loading…");

            int companyNo = appSession.getCompanyNo();
            String user   = appSession.getUserId() == null ? "" : appSession.getUserId();

            Task<Integer> task = new Task<>() {
                @Override protected Integer call() throws Exception {
                    List<TaxBracket> rows = loader.parse(xlsx, companyNo, source, eff);
                    if (rows.isEmpty()) {
                        throw new IllegalStateException(
                            "Parsed zero brackets — check the workbook layout " +
                            "(expected 'Statement of Formula - CSV' tab at sheet index 1).");
                    }
                    return service.replaceForPublication(companyNo, source, eff, rows, user);
                }
            };
            task.setOnSucceeded(ev -> Platform.runLater(() -> {
                status.setStyle("-fx-text-fill:#185A1A;");
                status.setText("Loaded " + task.getValue() + " brackets for " + source
                    + " effective " + eff + " (company " + companyNo + ").");
                loadBtn.setDisable(false);
            }));
            task.setOnFailed(ev -> Platform.runLater(() -> {
                Throwable t = task.getException();
                status.setStyle("-fx-text-fill:#A33;");
                status.setText("Load failed: " + (t == null ? "unknown error" : t.getMessage()));
                loadBtn.setDisable(false);
            }));
            Thread th = new Thread(task, "tax-bracket-load");
            th.setDaemon(true);
            th.start();
        });

        VBox center = new VBox(14, form, status);
        center.setPadding(new Insets(8, 0, 0, 0));

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#F2F1EC;");
        root.setTop(hdrBox);
        root.setCenter(center);
        root.setBottom(buttons);
        return new Scene(root, 600, 320);
    }

    /**
     * Default effective-from = 1 July of the current calendar year if today
     * is after 1 July; otherwise 1 July of the previous year. Just a sensible
     * starting point — the operator should confirm against the workbook cover.
     */
    private static LocalDate defaultEffectiveFrom() {
        LocalDate today = LocalDate.now();
        LocalDate julyThisYear = LocalDate.of(today.getYear(), 7, 1);
        return today.isBefore(julyThisYear)
            ? LocalDate.of(today.getYear() - 1, 7, 1)
            : julyThisYear;
    }
}
