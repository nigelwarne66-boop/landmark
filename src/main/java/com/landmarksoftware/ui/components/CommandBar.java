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
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;

/**
 * The 44px command bar directly under the page header on every list/record
 * screen — DESIGN_SYSTEM.md §7.4. Left-aligned action buttons in fixed
 * order (primary, then secondary, then ghost), a spacer, then a right-hand
 * slot for whatever view controls the caller has built (filter field,
 * density toggle, export button, …) — this component only reserves the
 * slot, it doesn't invent those features.
 *
 * <p>Buttons in a command bar render at the dense 28px size per §7.4; this
 * builder applies the {@code .sm} modifier automatically so callers just
 * pass an {@link LmButton} result.
 *
 * <p>An overflow "dots" menu for actions beyond the available width is
 * called out in the design doc as a nice-to-have, not required this wave,
 * and is not implemented here.
 *
 * <p>Not yet wired into any screen — Wave 5 adds it to Pay Code Maintenance
 * (PACD01) as the reference implementation.
 */
public final class CommandBar {

    private final List<Button> primaryActions = new ArrayList<>();
    private final List<Button> secondaryActions = new ArrayList<>();
    private final List<Button> ghostActions = new ArrayList<>();
    private Node rightSlot;

    private CommandBar() { }

    public static CommandBar builder() {
        return new CommandBar();
    }

    public CommandBar primary(Button button) {
        primaryActions.add(dense(button));
        return this;
    }

    public CommandBar secondary(Button button) {
        secondaryActions.add(dense(button));
        return this;
    }

    public CommandBar ghost(Button button) {
        ghostActions.add(dense(button));
        return this;
    }

    /** Right-aligned slot for view controls — filter field, density toggle, export, … */
    public CommandBar rightSlot(Node node) {
        this.rightSlot = node;
        return this;
    }

    private static Button dense(Button button) {
        button.getStyleClass().add("sm");
        return button;
    }

    public HBox build() {
        HBox bar = new HBox(8);
        bar.getStyleClass().add("lm-commandbar");
        bar.setAlignment(Pos.CENTER_LEFT);

        bar.getChildren().addAll(primaryActions);
        bar.getChildren().addAll(secondaryActions);
        bar.getChildren().addAll(ghostActions);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bar.getChildren().add(spacer);

        if (rightSlot != null) {
            bar.getChildren().add(rightSlot);
        }

        return bar;
    }
}
