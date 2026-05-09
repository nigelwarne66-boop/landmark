package com.example.fixedassets.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Reusable live-search lookup dialog.
 *
 * Opens as a modal window. User types in a search box; results filter
 * as they type. Double-click or Enter selects a row and populates the
 * calling field.
 *
 * Supports five lookup types, each querying a different table:
 *   ASSET      — FAASSET   (asset_no, desc_1, loc_code, grp_code)
 *   LOCATION   — FACODLO   (loc_code, description)
 *   GROUP      — FACODGR   (grp_code, description)
 *   SUBGROUP   — FACODSG   (subgrp_code, description)
 *   DEPARTMENT — FACODDT   (dept_code, description)
 */
public class LookupDialog {

    // ── Lookup types ──────────────────────────────────────────────────
    public enum LookupType {
        ASSET("Asset", "asset_no"),
        LOCATION("Location", "loc_code"),
        GROUP("Group", "grp_code"),
        SUBGROUP("Sub-Group", "subgrp_code"),
        DEPARTMENT("Department", "dept_code"),
        STOCKTAKE_SITE("Stocktake Site", "stake_site_code"),
        INSURANCE_TYPE("Insurance Type", "ins_type_code"),
        DEPN_CODE("Depreciation Code", "depn_code");

        final String label;
        final String codeColumn;

        LookupType(String label, String codeColumn) {
            this.label = label;
            this.codeColumn = codeColumn;
        }
    }

    // ── Column definition for the results table ───────────────────────
    record Column(String header, String key) {}

    // ── State ─────────────────────────────────────────────────────────
    private final JdbcTemplate          jdbc;
    private final LookupType            type;
    private final int                   companyNo;
    private final Consumer<String>      onSelect;   // called with the selected code

    private TextField                   txtSearch;
    private TableView<Map<String, String>> table;
    private Label                       lblStatus;
    private final ObservableList<Map<String, String>> rows =
            FXCollections.observableArrayList();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lookup-search");
        t.setDaemon(true);
        return t;
    });
    private Future<?> pendingSearch;

    // ── Constructor ───────────────────────────────────────────────────

    public LookupDialog(JdbcTemplate jdbc, LookupType type,
                        int companyNo, Consumer<String> onSelect) {
        this.jdbc      = jdbc;
        this.type      = type;
        this.companyNo = companyNo;
        this.onSelect  = onSelect;
    }

    // ── Show ──────────────────────────────────────────────────────────

    public void show(Window owner) {
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setTitle(type.label + " Lookup");
        stage.setMinWidth(560);
        stage.setMinHeight(440);

        BorderPane root = new BorderPane();
        root.setTop(buildSearchBar());
        root.setCenter(buildTable());
        root.setBottom(buildFooter(stage));

        Scene scene = new Scene(root, 580, 460);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());

        // Keyboard: Escape = close, Enter = select
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.close();
            if (e.getCode() == KeyCode.ENTER)  selectAndClose(stage);
        });

        stage.setScene(scene);
        stage.show();

        // Load all records on open, focus search box
        Platform.runLater(() -> {
            txtSearch.requestFocus();
            search("");
        });
    }

    // ── Search bar ────────────────────────────────────────────────────

    private VBox buildSearchBar() {
        txtSearch = new TextField();
        txtSearch.setPromptText("Type to filter " + type.label.toLowerCase() + "s…");
        txtSearch.getStyleClass().add("text-field");

        // Live search — fire on every keystroke with a short debounce
        txtSearch.textProperty().addListener((obs, oldVal, newVal) -> {
            if (pendingSearch != null) pendingSearch.cancel(false);
            pendingSearch = executor.submit(() -> {
                try { Thread.sleep(180); } catch (InterruptedException ignored) {}
                Platform.runLater(() -> search(newVal.trim()));
            });
        });

        lblStatus = new Label("Loading…");
        lblStatus.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");

        VBox bar = new VBox(6, new Label("Search:"), txtSearch, lblStatus);
        bar.setPadding(new Insets(14, 14, 8, 14));
        return bar;
    }

    // ── Results table ─────────────────────────────────────────────────

    private TableView<Map<String, String>> buildTable() {
        table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No results"));

        for (Column col : columns()) {
            TableColumn<Map<String, String>, String> tc = new TableColumn<>(col.header());
            tc.setCellValueFactory(p ->
                new SimpleStringProperty(p.getValue().getOrDefault(col.key(), "")));
            table.getColumns().add(tc);
        }

        // Double-click to select
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) {
                selectAndClose((Stage) table.getScene().getWindow());
            }
        });

        VBox wrap = new VBox(table);
        VBox.setVgrow(table, Priority.ALWAYS);
        wrap.setPadding(new Insets(0, 14, 0, 14));
        VBox.setVgrow(wrap, Priority.ALWAYS);
        return table;
    }

    // ── Footer ────────────────────────────────────────────────────────

    private HBox buildFooter(Stage stage) {
        Button btnSelect = new Button("Select");
        btnSelect.getStyleClass().add("btn-primary");
        btnSelect.setOnAction(e -> selectAndClose(stage));

        Button btnCancel = new Button("Cancel");
        btnCancel.getStyleClass().add("btn-secondary");
        btnCancel.setOnAction(e -> stage.close());

        HBox footer = new HBox(10, btnSelect, btnCancel);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(10, 14, 12, 14));
        footer.setStyle("-fx-border-color: #D0DDE8; -fx-border-width: 1 0 0 0;");
        return footer;
    }

    // ── Search execution ──────────────────────────────────────────────

    private void search(String filter) {
        lblStatus.setText("Searching…");
        executor.submit(() -> {
            try {
                List<Map<String, String>> results = fetchResults(filter);
                Platform.runLater(() -> {
                    rows.setAll(results);
                    lblStatus.setText(results.size() + " record" +
                        (results.size() == 1 ? "" : "s") + " found");
                    if (!results.isEmpty())
                        table.getSelectionModel().selectFirst();
                });
            } catch (Exception ex) {
                Platform.runLater(() ->
                    lblStatus.setText("Error: " + ex.getMessage()));
            }
        });
    }

    private List<Map<String, String>> fetchResults(String filter) {
        String like = "%" + filter + "%";
        String sql;
        Object[] params;

        switch (type) {
            case ASSET -> {
                sql = """
                    SELECT asset_no, desc_1, loc_code, grp_code
                    FROM FAASSET
                    WHERE company_no = ?
                      AND asset_status NOT IN ('R', 'U')
                      AND (asset_no LIKE ? OR desc_1 LIKE ?
                           OR loc_code LIKE ? OR grp_code LIKE ?)
                    ORDER BY asset_no
                    LIMIT 200
                    """;
                params = new Object[]{companyNo, like, like, like, like};
            }
            case LOCATION -> {
                sql = """
                    SELECT loc_code, desc1
                    FROM FACODLO
                    WHERE company_no = ?
                      AND (loc_code LIKE ? OR desc1 LIKE ?)
                    ORDER BY loc_code
                    LIMIT 200
                    """;
                params = new Object[]{companyNo, like, like};
            }
            case GROUP -> {
                sql = """
                    SELECT grp_code, desc1
                    FROM FACODGR
                    WHERE company_no = ?
                      AND (grp_code LIKE ? OR desc1 LIKE ?)
                    ORDER BY grp_code
                    LIMIT 200
                    """;
                params = new Object[]{companyNo, like, like};
            }
            case SUBGROUP -> {
                sql = """
                    SELECT subgrp_code, desc1
                    FROM FACODSG
                    WHERE company_no = ?
                      AND (subgrp_code LIKE ? OR desc1 LIKE ?)
                    ORDER BY subgrp_code
                    LIMIT 200
                    """;
                params = new Object[]{companyNo, like, like};
            }
            case STOCKTAKE_SITE -> {
                sql = """
                    SELECT stake_site_code, desc1 FROM FACODSS
                    WHERE company_no = ?
                      AND (stake_site_code LIKE ? OR desc1 LIKE ?)
                    ORDER BY stake_site_code
                    LIMIT 200
                    """;
                params = new Object[]{companyNo, like, like};
            }
            case INSURANCE_TYPE -> {
                sql = """
                    SELECT ins_type_code, desc1 FROM FACODIN
                    WHERE company_no = ?
                      AND (ins_type_code LIKE ? OR desc1 LIKE ?)
                    ORDER BY ins_type_code
                    LIMIT 200
                    """;
                params = new Object[]{companyNo, like, like};
            }
            case DEPN_CODE -> {
                sql = """
                    SELECT depn_code, desc1 FROM FACODDN
                    WHERE company_no = ?
                      AND (depn_code LIKE ? OR desc1 LIKE ?)
                    ORDER BY depn_code
                    LIMIT 200
                    """;
                params = new Object[]{companyNo, like, like};
            }
            case DEPARTMENT -> {
                sql = """
                    SELECT dept_code, desc1
                    FROM FACODDT
                    WHERE company_no = ?
                      AND (dept_code LIKE ? OR desc1 LIKE ?)
                    ORDER BY dept_code
                    LIMIT 200
                    """;
                params = new Object[]{companyNo, like, like};
            }
            default -> throw new IllegalStateException("Unknown lookup type: " + type);
        }

        return jdbc.queryForList(sql, params).stream()
            .map(row -> {
                Map<String, String> m = new java.util.LinkedHashMap<>();
                row.forEach((k, v) -> m.put(k, v == null ? "" : v.toString()));
                return m;
            })
            .toList();
    }

    // ── Select and close ──────────────────────────────────────────────

    private void selectAndClose(Stage stage) {
        Map<String, String> selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        String code = selected.getOrDefault(type.codeColumn, "");
        if (!code.isBlank()) {
            onSelect.accept(code);
            stage.close();
        }
    }

    // ── Column definitions per lookup type ────────────────────────────

    private List<Column> columns() {
        return switch (type) {
            case ASSET      -> List.of(
                new Column("Asset No",    "asset_no"),
                new Column("Description", "desc_1"),
                new Column("Location",    "loc_code"),
                new Column("Group",       "grp_code"),
                new Column("Status",      "asset_status"));
            case LOCATION   -> List.of(
                new Column("Location",    "loc_code"),
                new Column("Description", "desc1"));
            case GROUP      -> List.of(
                new Column("Group",       "grp_code"),
                new Column("Description", "desc1"));
            case SUBGROUP   -> List.of(
                new Column("Sub-Group",   "subgrp_code"),
                new Column("Description", "desc1"));
            case STOCKTAKE_SITE -> List.of(
                    new Column("Code", "stake_site_code"),
                    new Column("Description", "desc1"));
            case INSURANCE_TYPE -> List.of(
                    new Column("Code", "ins_type_code"),
                    new Column("Description", "desc1"));
            case DEPN_CODE -> List.of(
                    new Column("Code", "depn_code"),
                    new Column("Description", "desc1"));
            case DEPARTMENT -> List.of(
                new Column("Department",  "dept_code"),
                new Column("Description", "desc1"));
        };
    }
}
