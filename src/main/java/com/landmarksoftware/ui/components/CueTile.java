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

import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Module dashboard cue tile — DESIGN_SYSTEM.md §7.10. A clickable card
 * showing a large Plex Mono figure ({@code .lm-cue-value}, 28px/600), a
 * label beneath ({@code .lm-cue-label}), and an optional trend/ageing line,
 * on the {@code .lm-cue} card background (already fully speced in
 * {@code landmark-theme.css}). Each tile drills into the list it
 * summarises.
 *
 * <p>Extends {@link VBox} (same shape as {@link StatusChip} extending
 * {@link Label}) rather than using a builder, so a caller that populates
 * the figure asynchronously (the normal case — cue data comes from
 * pre-aggregated tables via a background thread, never the JavaFX thread)
 * can hold the instance and call {@link #setValue} when the query returns.
 */
public final class CueTile extends VBox {

    private final Label valueLabel;
    private final Label labelLabel;
    private Label trendLabel;

    private CueTile(String value, String label) {
        super(4);
        getStyleClass().add("lm-cue");
        valueLabel = new Label(value == null ? "" : value);
        valueLabel.getStyleClass().add("lm-cue-value");
        labelLabel = new Label(label == null ? "" : label);
        labelLabel.getStyleClass().add("lm-cue-label");
        getChildren().addAll(valueLabel, labelLabel);
    }

    /** A tile with no drill — informational only. */
    public static CueTile of(String value, String label) {
        return new CueTile(value, label);
    }

    /** A tile that drills into the list it summarises (the normal case per §7.10). */
    public static CueTile of(String value, String label, Runnable onClick) {
        CueTile tile = new CueTile(value, label);
        tile.onClick(onClick);
        return tile;
    }

    public CueTile onClick(Runnable action) {
        setCursor(Cursor.HAND);
        setOnMouseClicked(e -> action.run());
        return this;
    }

    /**
     * Optional trend/ageing line beneath the label, e.g. "+3 this month" or
     * an overdue-count callout. {@code semanticColorToken} is a
     * {@code landmark-theme.css} colour token such as {@code "-lm-danger"}
     * or {@code "-lm-success"} (pass {@code null} for muted/neutral).
     */
    public CueTile trend(String text, String semanticColorToken) {
        if (trendLabel == null) {
            trendLabel = new Label();
            trendLabel.setStyle("-fx-font-size:11px;");
            getChildren().add(trendLabel);
        }
        trendLabel.setText(text == null ? "" : text);
        trendLabel.setStyle("-fx-font-size:11px;-fx-text-fill:" +
            (semanticColorToken != null ? semanticColorToken : "-lm-text-muted") + ";");
        return this;
    }

    /** Updates the headline figure — the normal path once a background load completes. */
    public void setValue(String value) {
        valueLabel.setText(value == null ? "" : value);
    }

    public void setLabel(String label) {
        labelLabel.setText(label == null ? "" : label);
    }
}
