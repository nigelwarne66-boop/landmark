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

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * The single row pattern for report lists, record lists and card bodies —
 * DESIGN_SYSTEM.md §7.5. Layout:
 * <pre>
 *   [32px icon tile] [title 14/500 + description 12 muted] … [badge] [trailing]
 * </pre>
 * A small builder, since the icon tile, trailing control and click handler
 * all vary per caller and JavaFX has no named/optional constructor
 * parameters.
 *
 * <p>Trailing control is the favourite star ({@link #favourite}, filled
 * {@code --lm-warning} when set, outlined {@code --lm-text-muted} when not)
 * or a {@code chevron-right} ({@link #chevron()}) — never both, per §7.5.
 *
 * <p>{@link #pending()} renders the §7.9/D4 "not built yet" state: the row
 * stays at full-contrast text (badge alone carries the state), is
 * non-interactive ({@code cursor: default}, no hover fill, no click
 * handler) — the {@code .lm-row.pending} rule in landmark-theme.css.
 */
public final class ListRow {

    // Ikonli's FontIcon.iconCode is itself a StyleableObjectProperty — calling
    // Node.setStyle(...) on a FontIcon triggers a full CSS re-application pass
    // that resets iconCode back to its unset/default value (rendering a
    // fallback glyph instead of the requested icon), even though the icon
    // literal was set correctly via the constructor. Fix: colour icons via
    // the proper setIconColor(Paint) Java API, never setStyle("-fx-icon-
    // color: ..."). Verified 2026-08-13 via a snapshot diagnostic (see
    // com.landmarksoftware.util.DiagnoseModuleCardIcons). These two literals
    // mirror -lm-text-muted / -lm-warning, identical in both theme CSS files
    // today — if a theme ever diverges on these tokens this needs revisiting.
    private static final Color TEXT_MUTED = Color.web("#868C87");
    private static final Color WARNING    = Color.web("#A8630A");

    private String title;
    private String description;
    private String iconLiteral;
    private String tileAccentHex;
    private String tileTintHex;
    private String badgeText;
    private Runnable onClick;
    private boolean showChevron;
    private boolean favouriteSet;
    private boolean favourite;
    private Runnable onFavouriteToggle;
    private boolean pending;

    private ListRow() { }

    public static ListRow builder() {
        return new ListRow();
    }

    public ListRow title(String title) {
        this.title = title;
        return this;
    }

    public ListRow description(String description) {
        this.description = description;
        return this;
    }

    /** 32px icon tile, module tint background + accent icon (§4: accent is icon-only, tint is the tile fill). */
    public ListRow icon(String iconLiteral, String accentHex, String tintHex) {
        this.iconLiteral = iconLiteral;
        this.tileAccentHex = accentHex;
        this.tileTintHex = tintHex;
        return this;
    }

    /** A small metadata badge (e.g. "soon") rendered before the trailing control. */
    public ListRow badge(String text) {
        this.badgeText = text;
        return this;
    }

    public ListRow onClick(Runnable onClick) {
        this.onClick = onClick;
        return this;
    }

    /** Trailing chevron-right — the default drill affordance for a clickable row. */
    public ListRow chevron() {
        this.showChevron = true;
        return this;
    }

    /** Trailing favourite star, mutually exclusive with {@link #chevron()}. */
    public ListRow favourite(boolean favourite, Runnable onToggle) {
        this.favouriteSet = true;
        this.favourite = favourite;
        this.onFavouriteToggle = onToggle;
        return this;
    }

    /** Marks the row as not-yet-built (§7.9/D4): normal text weight/colour, non-interactive, no chevron. */
    public ListRow pending() {
        this.pending = true;
        return this;
    }

    public HBox build() {
        HBox row = new HBox();
        row.getStyleClass().add("lm-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setSpacing(12);

        if (iconLiteral != null) {
            row.getChildren().add(buildTile());
        }

        Label titleLbl = new Label(title == null ? "" : title);
        titleLbl.getStyleClass().add("lm-row-title");

        VBox text;
        if (description != null && !description.isBlank()) {
            Label descLbl = new Label(description);
            descLbl.getStyleClass().add("lm-row-desc");
            text = new VBox(2, titleLbl, descLbl);
        } else {
            text = new VBox(titleLbl);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        row.getChildren().add(text);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().add(spacer);

        if (badgeText != null) {
            row.getChildren().add(Badge.of(badgeText));
        }

        if (favouriteSet) {
            row.getChildren().add(buildFavouriteStar());
        } else if (showChevron && !pending) {
            FontIcon chevron = new FontIcon("fth-chevron-right");
            chevron.setIconSize(14);
            chevron.setIconColor(TEXT_MUTED);
            row.getChildren().add(chevron);
        }

        if (pending) {
            row.getStyleClass().add("pending");
        } else if (onClick != null) {
            row.setCursor(Cursor.HAND);
            row.setOnMouseClicked(e -> onClick.run());
        }

        return row;
    }

    private StackPane buildTile() {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(14);
        if (tileAccentHex != null) {
            icon.setIconColor(Color.web(tileAccentHex));
        }
        StackPane tile = new StackPane(icon);
        tile.getStyleClass().addAll("lm-tile", "sm");
        if (tileTintHex != null) {
            tile.setStyle("-fx-background-color: " + tileTintHex + ";");
        }
        return tile;
    }

    private FontIcon buildFavouriteStar() {
        // Feather ("fth-") is outline-only by design — no filled glyph
        // variant — so the selected/favourited state switches to Material2's
        // solid star ("mdmz-star") instead of just recolouring the same
        // outline glyph, matching how a favourite toggle is conventionally
        // drawn (solid when on, outline when off).
        FontIcon star = new FontIcon(favourite ? "mdmz-star" : "fth-star");
        star.setIconSize(14);
        star.setIconColor(favourite ? WARNING : TEXT_MUTED);
        star.setCursor(Cursor.HAND);
        if (onFavouriteToggle != null) {
            star.setOnMouseClicked(e -> {
                e.consume();
                onFavouriteToggle.run();
            });
        }
        return star;
    }
}
