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

import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Lays out up to four {@link CueTile}s in an evenly spaced row — the
 * "row of up to four cue tiles" a module landing page opens with, per
 * DESIGN_SYSTEM.md §7.10. The desktop CSS doesn't carry an exact
 * {@code .lm-cues} grid class (that's a web-tokens-file concept), so this
 * is a plain {@link HBox} with the 16px gap used elsewhere in
 * {@code landmark-theme.css} for card rows, each tile growing to share the
 * available width equally.
 */
public final class CueGrid {

    private CueGrid() { }

    public static HBox of(CueTile... tiles) {
        HBox row = new HBox(16);
        row.setFillHeight(true);
        for (CueTile tile : tiles) {
            HBox.setHgrow(tile, Priority.ALWAYS);
            tile.setMaxWidth(Double.MAX_VALUE);
            row.getChildren().add(tile);
        }
        return row;
    }

    /** Same layout with a caller-chosen gap, e.g. 24px for a looser dashboard. */
    public static HBox of(double gap, CueTile... tiles) {
        HBox row = of(tiles);
        row.setSpacing(gap);
        return row;
    }

    // Keep a spacer available for callers who want to right-align a subset
    // of tiles rather than stretch every tile to fill the row.
    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }
}
