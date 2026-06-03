/*
 * Copyright (c) 2026 Landmark Software Pty Ltd.
 * All rights reserved.
 */
package com.landmarksoftware.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;

/**
 * Reusable card component matching the DESIGN_SYSTEM.md ModuleCard spec.
 *
 * Layout:
 *   ┌─────────────────────────────────────────┐
 *   │ [icon chip]  Title                       │  ← card header
 *   │              Subtitle                    │
 *   ├─────────────────────────────────────────┤
 *   │  Link row                            ›  │  ← repeated per entry
 *   │  Link row (dim = coming soon)           │
 *   └─────────────────────────────────────────┘
 *
 * Usage:
 *   ModuleCard card = new ModuleCard("#0F6E56", "fth-user", "Payroll Setup",
 *       "Pay codes, employees and groups");
 *   card.addRow("Pay Code Maintenance", () -> openPayCodes(), true);
 *   card.addRow("Single Touch Payroll", null, false);  // soon
 *   card.setOpenAction(() -> openReports());  // optional "Open ›" header button
 */
public class ModuleCard extends VBox {

    private final String accentHex;
    private final VBox   rowContainer;
    private Runnable     openAction;

    public ModuleCard(String accentHex, String iconLiteral,
                      String title, String subtitle) {
        this.accentHex = accentHex;
        super.setStyle(
            "-fx-background-color: -lm-bg-surface;" +
            "-fx-background-radius: 10;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 8, 0, 0, 2);");

        // ── Icon chip ─────────────────────────────────────────────────
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(14);
        icon.setStyle("-fx-icon-color: " + accentHex + ";");

        Rectangle chip = new Rectangle(28, 28);
        chip.setArcWidth(8); chip.setArcHeight(8);
        chip.setStyle("-fx-fill: " + accentHex + "22;");

        javafx.scene.layout.StackPane iconChip = new javafx.scene.layout.StackPane(chip, icon);
        iconChip.setPrefSize(28, 28);
        iconChip.setMaxSize(28, 28);

        // ── Title + subtitle ──────────────────────────────────────────
        Label lTitle = new Label(title);
        lTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: -lm-text-primary;");
        Label lSub = new Label(subtitle);
        lSub.setStyle("-fx-font-size: 12px; -fx-text-fill: -lm-text-tertiary;");
        VBox textBox = new VBox(2, lTitle, lSub);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        // ── Open button (optional) ────────────────────────────────────
        Label openBtn = new Label("Open ›");
        openBtn.setStyle(
            "-fx-font-size: 11px; -fx-text-fill: -lm-accent; -fx-cursor: hand;");
        openBtn.setVisible(false);
        openBtn.setManaged(false);
        openBtn.setOnMouseClicked(e -> { if (openAction != null) openAction.run(); });

        HBox header = new HBox(10, iconChip, textBox, openBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 16, 14, 16));
        header.setStyle(
            "-fx-border-color: transparent transparent -lm-border transparent;" +
            "-fx-border-width: 0 0 0.5 0;");

        // Store openBtn reference so setOpenAction can reveal it
        header.setUserData(openBtn);

        rowContainer = new VBox(0);

        getChildren().addAll(header, rowContainer);
    }

    /** Adds a clickable link row. Pass action=null and available=false for "coming soon". */
    public void addRow(String label, Runnable action, boolean available) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 13px; -fx-text-fill: " +
                     (available ? "-lm-text-secondary;" : "-lm-text-tertiary;"));
        HBox.setHgrow(lbl, Priority.ALWAYS);

        HBox row = new HBox(lbl);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7, 16, 7, 16));

        if (!available) {
            Label soon = new Label("soon");
            soon.setStyle(
                "-fx-font-size: 10px; -fx-text-fill: -lm-text-tertiary;" +
                "-fx-background-color: -lm-border;" +
                "-fx-background-radius: 4; -fx-padding: 1 5 1 5;");
            row.getChildren().add(soon);
        } else if (action != null) {
            row.setStyle("-fx-cursor: hand;");
            row.setOnMouseEntered(e ->
                row.setStyle("-fx-background-color: -lm-bg-selected; -fx-cursor: hand;"));
            row.setOnMouseExited(e ->
                row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;"));
            row.setOnMouseClicked(e -> action.run());
        }

        // Divider on all but the last row (patched by getChildren observer would be fragile;
        // instead set border on all and let the last row's border be clipped by the card radius)
        row.setStyle(row.getStyle() +
            "-fx-border-color: transparent transparent -lm-border transparent;" +
            "-fx-border-width: 0 0 0.5 0;");

        rowContainer.getChildren().add(row);
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
        getChildren().stream()
            .filter(n -> n instanceof HBox && ((HBox) n).getUserData() instanceof Label)
            .findFirst()
            .ifPresent(n -> {
                Label btn = (Label) ((HBox) n).getUserData();
                btn.setVisible(true);
                btn.setManaged(true);
            });
    }
}
