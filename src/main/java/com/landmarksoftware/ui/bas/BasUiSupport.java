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
package com.landmarksoftware.ui.bas;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * CPBA10 — small shared JavaFX form-building/validation helpers, in the
 * spirit of the private helpers at the bottom of {@code
 * PayCodeMaintenanceController} (which are class-private and can't be
 * reused across files). Kept here once so the ~9 BAS screens don't each
 * reimplement the same field/label/validation boilerplate.
 */
final class BasUiSupport {

    private BasUiSupport() { }

    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static TextField tf(String initial, int prefColumns) {
        TextField f = new TextField(initial == null ? "" : initial);
        f.setPrefColumnCount(prefColumns);
        return f;
    }

    static Label sectionHeader(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight:bold;-fx-font-size:12px;-fx-text-fill:#1A1A2E;-fx-padding:10 0 2 0;");
        return l;
    }

    static HBox twoColRow(String label, javafx.scene.Node control) {
        Label l = new Label(label);
        l.setPrefWidth(190);
        l.setStyle("-fx-text-fill:#374151;");
        HBox row = new HBox(10, l, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** A label/value row where the value is drillable (clickable, brand-coloured) when {@code enabled}. */
    static HBox amountRow(String label, BigDecimal value, boolean enabled, Runnable onDrill) {
        Label l = new Label(label);
        l.setPrefWidth(190);
        l.setStyle("-fx-text-fill:#374151;");
        Label v = new Label(moneyStr(value));
        v.setPrefWidth(120);
        v.setAlignment(Pos.CENTER_RIGHT);
        v.setStyle("-fx-font-family:'JetBrains Mono','Consolas',monospace;-fx-font-size:12px;" +
            (enabled ? "-fx-text-fill:#1A6EF5;-fx-underline:true;-fx-cursor:hand;" : "-fx-text-fill:#1A1A2E;"));
        if (enabled && onDrill != null) {
            v.setOnMouseClicked(e -> onDrill.run());
        }
        HBox row = new HBox(10, l, v);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    static String moneyStr(BigDecimal v) {
        BigDecimal n = v == null ? BigDecimal.ZERO : v;
        return n.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, javafx.scene.layout.Priority.ALWAYS);
        return r;
    }

    static void markError(TextField fld, String msg) {
        fld.setStyle("-fx-border-color:#DC2626;-fx-border-width:2;");
        fld.requestFocus();
        fld.selectAll();
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setTitle("Validation");
        a.setHeaderText(null);
        a.showAndWait();
    }

    static void clearError(TextField fld) {
        fld.setStyle("-fx-border-color:#D1D5DB;-fx-border-width:1.5;");
    }

    static void showInfo(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.showAndWait();
    }

    static void showError(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.showAndWait();
    }

    static boolean confirm(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.YES, ButtonType.NO);
        a.setTitle(title);
        a.setHeaderText(null);
        return a.showAndWait().filter(b -> b == ButtonType.YES).isPresent();
    }

    /** Parses a required BigDecimal field. Empty text counts as zero. {@code null} + markError on bad input. */
    static BigDecimal parseDec(TextField fld, String label) {
        String s = fld.getText() == null ? "" : fld.getText().trim();
        if (s.isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException ex) {
            markError(fld, label + " must be a number.");
            return null;
        }
    }

    /** Parses an optional int field (blank → {@code null}). {@code Integer.MIN_VALUE} sentinel + markError on bad input. */
    static Integer parseIntOrNull(TextField fld, String label) {
        String s = fld.getText() == null ? "" : fld.getText().trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            markError(fld, label + " must be a whole number.");
            return Integer.MIN_VALUE;
        }
    }

    static Button btnPrimary(String text) {
        Button b = new Button(text);
        b.getStyleClass().addAll("lm-btn", "lm-btn-primary");
        return b;
    }

    static Button btnSecondary(String text) {
        Button b = new Button(text);
        b.getStyleClass().addAll("lm-btn", "lm-btn-secondary");
        return b;
    }

    static HBox buttonBar(javafx.scene.Node... nodes) {
        HBox bar = new HBox(10, nodes);
        bar.setPadding(new Insets(10, 20, 16, 20));
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setStyle(
            "-fx-background-color:#F2F1EC;" +
            "-fx-border-color:rgba(0,0,0,.10) transparent transparent transparent;" +
            "-fx-border-width:0.5 0 0 0;");
        return bar;
    }

    static String up(String s) { return s == null ? "" : s.trim().toUpperCase(); }
    static String trim(String s) { return s == null ? "" : s.trim(); }

    /** COBOL date-zero sentinel (CLAUDE.md convention). */
    static final LocalDate SENTINEL_DATE = LocalDate.of(1899, 12, 31);

    static boolean isRealDate(LocalDate d) {
        return d != null && !d.equals(SENTINEL_DATE);
    }
}
