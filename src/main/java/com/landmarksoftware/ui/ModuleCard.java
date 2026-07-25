/*
 * Copyright (c) 2026 Landmark Software Pty Ltd.
 * All rights reserved.
 */
package com.landmarksoftware.ui;

import com.landmarksoftware.ui.components.ListRow;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;

/**
 * Reusable card component matching the DESIGN_SYSTEM.md §7.3 ModuleCard
 * spec. Migrated onto the {@code landmark-theme.css} token classes
 * ({@code .lm-card}/{@code .lm-card-header}/{@code .lm-tile}/
 * {@code .lm-card-title}/{@code .lm-card-desc}/{@code .lm-card-link}) and
 * the shared {@link ListRow} component for its body rows — Wave 3 of the
 * design-system rollout.
 *
 * <p><b>Accent-hex-to-tile mapping.</b> §7.3's icon tile wants a tint
 * background plus an accent-coloured icon (two colours), but this class's
 * public constructor only ever received one hex (the accent). Rather than
 * changing the constructor signature — which would break the ~50
 * {@code MainMenuController} call sites this wave must not touch — the tile
 * fill is derived from the accent hex itself by appending alpha (`"22"` ≈
 * 13%), the same technique the pre-design-system version of this class used
 * for its icon chip. This also sidesteps a mismatch: many of
 * {@code MainMenuController}'s existing hexes (e.g. {@code #BA7517},
 * {@code #0F6E56}, {@code #D97706}) are legacy per-section accents that
 * predate {@link com.landmarksoftware.ui.nav.Module} and don't map 1:1 onto
 * a module's registry tint, so a {@code Module.getTintHex()} lookup would
 * either fail or silently pick the wrong module for several call sites.
 *
 * <p>Layout:
 * <pre>
 *   ┌─────────────────────────────────────────┐
 *   │ [icon tile]  Title                       │  ← .lm-card-header
 *   │              Subtitle                    │
 *   ├─────────────────────────────────────────┤
 *   │  ListRow                             ›  │
 *   │  ListRow (pending = "soon" badge)        │
 *   └─────────────────────────────────────────┘
 * </pre>
 *
 * Usage:
 * <pre>
 *   ModuleCard card = new ModuleCard("#0F6E56", "fth-user", "Payroll Setup",
 *       "Pay codes, employees and groups");
 *   card.addRow("Pay Code Maintenance", () -> openPayCodes(), true);
 *   card.addRow("Single Touch Payroll", null, false);  // soon
 *   card.setOpenAction(() -> openReports());  // optional "Open ›" header link
 * </pre>
 */
public class ModuleCard extends VBox {

    private final VBox  rowContainer;
    private final Label openLink;
    private Runnable    openAction;

    public ModuleCard(String accentHex, String iconLiteral,
                      String title, String subtitle) {
        getStyleClass().add("lm-card");

        // ── Icon tile ─────────────────────────────────────────────────
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(16);
        icon.setStyle("-fx-icon-color: " + accentHex + ";");

        StackPane tile = new StackPane(icon);
        tile.getStyleClass().add("lm-tile");
        tile.setStyle("-fx-background-color: " + accentHex + "22;");

        // ── Title + subtitle ──────────────────────────────────────────
        Label lTitle = new Label(title);
        lTitle.getStyleClass().add("lm-card-title");
        Label lSub = new Label(subtitle);
        lSub.getStyleClass().add("lm-card-desc");
        VBox textBox = new VBox(2, lTitle, lSub);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        // ── Open link (optional) ────────────────────────────────────
        openLink = new Label("Open ›");
        openLink.getStyleClass().add("lm-card-link");
        openLink.setVisible(false);
        openLink.setManaged(false);
        openLink.setOnMouseClicked(e -> { if (openAction != null) openAction.run(); });

        HBox header = new HBox(10, tile, textBox, openLink);
        header.getStyleClass().add("lm-card-header");
        header.setAlignment(Pos.CENTER_LEFT);

        rowContainer = new VBox(0);

        getChildren().addAll(header, rowContainer);
    }

    /** Adds a clickable row. Pass action=null and available=false for "coming soon". */
    public void addRow(String label, Runnable action, boolean available) {
        ListRow row = ListRow.builder().title(label);
        if (!available) {
            row.badge("soon").pending();
        } else if (action != null) {
            row.chevron().onClick(action);
        }
        rowContainer.getChildren().add(row.build());
    }

    /** Adds multiple rows from a list of MenuEntry objects. */
    public void addRows(List<com.landmarksoftware.ui.MenuEntry> entries) {
        for (MenuEntry e : entries) {
            addRow(e.getTitle(), e.isAvailable() ? e.getAction() : null, e.isAvailable());
        }
    }

    /**
     * Wires the optional "Open ›" affordance in the card header.
     * Per D5, this is a TODO hook — the actual cross-app launch is Phase S1.
     */
    public void setOpenAction(Runnable action) {
        this.openAction = action;
        openLink.setVisible(true);
        openLink.setManaged(true);
    }
}
