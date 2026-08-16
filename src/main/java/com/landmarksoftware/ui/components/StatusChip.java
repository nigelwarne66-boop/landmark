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

import java.util.Locale;
import java.util.Set;

/**
 * Status pill — DESIGN_SYSTEM.md §7.8. Sentence-case text (not uppercase),
 * tint background, semantic text colour, wired onto the {@code .lm-chip}
 * base class plus one of the variant classes already defined in
 * {@code landmark-theme.css}'s "Chips and badges" section.
 *
 * <p>Vocabulary (status → variant → semantic meaning):
 * <ul>
 *   <li>Draft, Open, Outstanding → info</li>
 *   <li>Ready → warning</li>
 *   <li>Posted, Paid, Reconciled → success</li>
 *   <li>Overdue → danger</li>
 *   <li>Cancelled, Inactive → neutral</li>
 * </ul>
 */
public final class StatusChip extends Label {

    private static final Set<String> KNOWN_VARIANTS = Set.of(
        "draft", "ready", "posted", "open", "paid", "overdue",
        "outstanding", "reconciled", "cancelled", "inactive");

    private StatusChip(String status) {
        super(sentenceCase(status));
        getStyleClass().add("lm-chip");
        String key = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        // Unrecognised statuses fall back to the neutral (cancelled/inactive) look
        // rather than guessing at a semantic colour that might mislead.
        getStyleClass().add(KNOWN_VARIANTS.contains(key) ? key : "cancelled");
    }

    public static StatusChip of(String status) {
        return new StatusChip(status);
    }

    private static String sentenceCase(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String trimmed = s.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
