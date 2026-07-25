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
package com.landmarksoftware.ui.shell;

import com.landmarksoftware.ui.nav.Module;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Map;
import java.util.function.Predicate;

/**
 * The 240px/64px-collapsible navigation rail built by
 * {@link AppShell#buildRail(ShellContext)} — DESIGN_SYSTEM.md §5.2. Wraps
 * the root {@link VBox} plus mutators for the pieces that change after the
 * rail is built without a full rebuild: which module carries the ledger
 * spine, which rows are visible (install-flag gating), and the Favourites
 * count badge.
 */
public final class ShellRail {

    static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    private final VBox root;
    private final Map<Module, HBox> moduleRows;
    private final HBox favouritesRow;
    private final Label favouritesCountLabel;

    ShellRail(VBox root, Map<Module, HBox> moduleRows, HBox favouritesRow, Label favouritesCountLabel) {
        this.root = root;
        this.moduleRows = moduleRows;
        this.favouritesRow = favouritesRow;
        this.favouritesCountLabel = favouritesCountLabel;
    }

    public VBox getNode() { return root; }

    /** Move the ledger-spine active indicator (§5.2) to the given module.
     *  Pass {@code null} to clear it (e.g. when Favourites is active). */
    public void setActive(Module active) {
        moduleRows.forEach((mod, row) -> row.pseudoClassStateChanged(ACTIVE, mod == active));
    }

    /** Toggle the ledger spine on the Favourites row — only meaningful for a
     *  caller that wired {@link ShellContext#onFavouritesClick(Runnable)}. */
    public void setFavouritesActive(boolean active) {
        favouritesRow.pseudoClassStateChanged(ACTIVE, active);
    }

    /** Re-apply a visibility predicate — call after AppSession's module
     *  install flags change (e.g. after a company switch). */
    public void refreshVisibility(Predicate<Module> visible) {
        moduleRows.forEach((mod, row) -> {
            boolean show = visible.test(mod);
            row.setVisible(show);
            row.setManaged(show);
        });
    }

    /** Refresh the Favourites row's count badge. No-op for callers that
     *  never wired a favourites feature (the badge stays at its build-time
     *  value, normally "0"). */
    public void refreshFavouritesCount(int count) {
        favouritesCountLabel.setText(String.valueOf(count));
    }
}
