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

import javafx.scene.control.Label;

/**
 * Small neutral marker for metadata — DESIGN_SYSTEM.md §7.9. Used for
 * "soon" tags, counts, record types. A badge must never change the styling
 * of the thing it labels — it renders alongside it, at normal weight and
 * colour (fixes defect D4: unbuilt items keep full-contrast text, the badge
 * alone carries the "not built yet" state).
 */
public final class Badge {

    private Badge() { }

    public static Label of(String text) {
        Label badge = new Label(text);
        badge.getStyleClass().add("lm-badge");
        return badge;
    }
}
