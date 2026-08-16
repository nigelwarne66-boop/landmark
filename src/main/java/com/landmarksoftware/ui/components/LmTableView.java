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
package com.landmarksoftware.ui.components;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Thin wrapper/factory around {@link TableView} implementing
 * DESIGN_SYSTEM.md §7.7 — "the most important component in the product".
 *
 * <p>Wires the {@code .lm-table} style class (header, row, hover, selection
 * spine, totals styling already fully speced in
 * {@code landmark-theme.css}'s "TableView" section) and adds four things
 * plain {@code TableView} doesn't give you for free:
 * <ul>
 *   <li>Typed column factories ({@link #numColumn}, {@link #codeColumn},
 *       {@link #dateColumn}, {@link #textColumn}) that apply the right
 *       column-type style class (§7.7: amount columns right-aligned Plex
 *       Mono, dates dd/mm/yyyy Plex Mono left-aligned, codes Plex Mono) to
 *       both the header and every rendered cell.</li>
 *   <li>{@link #setCompact(boolean)} — the 32px comfortable / 28px compact
 *       row-height density toggle (persisted by the caller, not this
 *       class), backed by the {@code .compact} modifier already in the
 *       CSS.</li>
 *   <li>{@link #setEmptyState} — the centred module-icon / message /
 *       primary-action empty state (§7.7), via {@link TableView#setPlaceholder}.</li>
 *   <li>{@link #buildTotalsRow} — a sticky-look totals line rendered as a
 *       separate {@link HBox} beneath the table (per the CSS's own comment:
 *       a native {@code TableView} footer doesn't support this well), with
 *       each cell width bound live to its matching column so it lines up
 *       even after a user resizes columns.</li>
 * </ul>
 *
 * <p>Drill-down affordance (§7.7 "any cell that drills shows brand-700 text
 * on row hover and a pointer cursor") is applied per-column via
 * {@link #markDrillable(TableColumn)} — it only adds the {@code .drill}
 * style class to rendered cells, the caller still owns the click handler
 * that performs the actual drill.
 */
public class LmTableView<T> extends TableView<T> {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public LmTableView() {
        super();
        init();
    }

    public LmTableView(ObservableList<T> items) {
        super(items);
        init();
    }

    private void init() {
        getStyleClass().add("lm-table");
        setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    }

    /** 32px comfortable (default) vs 28px compact row height — §7.7 user toggle. */
    public void setCompact(boolean compact) {
        if (compact) {
            if (!getStyleClass().contains("compact")) {
                getStyleClass().add("compact");
            }
        } else {
            getStyleClass().remove("compact");
        }
    }

    // ── Empty state — §7.7 ──────────────────────────────────────────────────

    /**
     * Centred 32px module icon at 40% opacity + one line of what's missing +
     * one primary action, per §7.7. {@code iconLiteral} is an Ikonli Feather
     * literal (e.g. {@code "fth-inbox"}); pass {@code null} to omit the icon.
     * Pass {@code actionText}/{@code onAction} as {@code null} to omit the
     * action button (some empty states are informational only).
     */
    public void setEmptyState(String iconLiteral, String message, String actionText, Runnable onAction) {
        VBox box = new VBox();
        box.getStyleClass().add("lm-empty");
        if (iconLiteral != null) {
            FontIcon icon = new FontIcon(iconLiteral);
            icon.setIconSize(32);
            icon.getStyleClass().add("lm-empty-icon");
            box.getChildren().add(icon);
        }
        Label text = new Label(message == null ? "" : message);
        text.getStyleClass().add("lm-empty-text");
        text.setWrapText(true);
        text.setMaxWidth(320);
        box.getChildren().add(text);
        if (actionText != null && onAction != null) {
            box.getChildren().add(LmButton.primary(actionText, onAction));
        }
        setPlaceholder(box);
    }

    // ── Typed column factories — §7.7 column-type conventions ──────────────

    /** Plain left-aligned text column — no type styling. */
    public static <S> TableColumn<S, String> textColumn(String header, Function<S, String> extractor, double width) {
        TableColumn<S, String> col = new TableColumn<>(header);
        col.setPrefWidth(width);
        col.setCellValueFactory(p -> new SimpleStringProperty(safe(extractor.apply(p.getValue()))));
        return col;
    }

    /** Amount column — right-aligned, Plex Mono, {@code .col-num} (§7.7). */
    public static <S> TableColumn<S, String> numColumn(String header, Function<S, String> extractor, double width) {
        TableColumn<S, String> col = textColumn(header, extractor, width);
        styleColumn(col, "col-num");
        return col;
    }

    /** Code column — Plex Mono, left-aligned, {@code .col-code} (§7.7). */
    public static <S> TableColumn<S, String> codeColumn(String header, Function<S, String> extractor, double width) {
        TableColumn<S, String> col = textColumn(header, extractor, width);
        styleColumn(col, "col-code");
        return col;
    }

    /** Date column — formatted dd/mm/yyyy, Plex Mono, left-aligned, {@code .col-date} (§7.7). */
    public static <S> TableColumn<S, String> dateColumn(String header, Function<S, LocalDate> extractor, double width) {
        TableColumn<S, String> col = textColumn(header, s -> {
            LocalDate d = extractor.apply(s);
            return d == null ? "" : d.format(DATE_FMT);
        }, width);
        styleColumn(col, "col-date");
        return col;
    }

    /**
     * Marks an existing column as drillable (§7.7 "drill-down contract"):
     * every rendered, non-empty cell gets the {@code .drill} style class so
     * {@code landmark-theme.css}'s {@code .drill:hover} rule shows
     * brand-700 underlined text + pointer cursor on row hover. Wraps
     * whatever cell factory the column already has (or the default
     * string-rendering one) rather than replacing its content logic.
     */
    public static <S, V> void markDrillable(TableColumn<S, V> column) {
        Callback<TableColumn<S, V>, TableCell<S, V>> existing = column.getCellFactory();
        column.setCellFactory(c -> {
            TableCell<S, V> cell = existing != null ? existing.call(c) : defaultCell();
            cell.itemProperty().addListener((obs, ov, nv) -> {
                if (nv != null && !cell.isEmpty() && !cell.getStyleClass().contains("drill")) {
                    cell.getStyleClass().add("drill");
                } else if ((nv == null || cell.isEmpty())) {
                    cell.getStyleClass().remove("drill");
                }
            });
            return cell;
        });
    }

    // ── Totals row — §7.7 ────────────────────────────────────────────────────

    /**
     * Builds the totals row as a standalone {@link HBox} styled
     * {@code .lm-table-totals} — sticky-look bottom bar, 2px top border,
     * bold Plex Mono — meant to be placed directly beneath this table (e.g.
     * in the same {@code VBox}), NOT inside it. Each entry's label width is
     * bound to its column's live {@code widthProperty()} so figures stay
     * aligned under their columns even after a {@code CONSTRAINED_RESIZE_POLICY}
     * resize.
     *
     * @param values ordered column → totals-text map (use a
     *               {@link LinkedHashMap} matching the table's column
     *               order); pass an empty string for columns that don't
     *               carry a total, e.g. a leading "Total" label column.
     */
    public HBox buildTotalsRow(Map<TableColumn<T, ?>, String> values) {
        HBox row = new HBox();
        row.getStyleClass().add("lm-table-totals");
        for (Map.Entry<TableColumn<T, ?>, String> e : values.entrySet()) {
            TableColumn<T, ?> col = e.getKey();
            Label lbl = new Label(e.getValue() == null ? "" : e.getValue());
            lbl.prefWidthProperty().bind(col.widthProperty());
            lbl.minWidthProperty().bind(col.widthProperty());
            lbl.maxWidthProperty().bind(col.widthProperty());
            if (col.getStyleClass().contains("col-num")) {
                lbl.setAlignment(Pos.CENTER_RIGHT);
                lbl.getStyleClass().add("col-num");
            } else if (col.getStyleClass().contains("col-code")) {
                lbl.getStyleClass().add("col-code");
            } else if (col.getStyleClass().contains("col-date")) {
                lbl.getStyleClass().add("col-date");
            }
            row.getChildren().add(lbl);
        }
        return row;
    }

    // ── Internals ────────────────────────────────────────────────────────

    private static <S, V> void styleColumn(TableColumn<S, V> column, String styleClass) {
        column.getStyleClass().add(styleClass);
        Callback<TableColumn<S, V>, TableCell<S, V>> base = column.getCellFactory();
        column.setCellFactory(c -> {
            TableCell<S, V> cell = base != null ? base.call(c) : defaultCell();
            cell.getStyleClass().add(styleClass);
            return cell;
        });
    }

    private static <S, V> TableCell<S, V> defaultCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(V item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        };
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
