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

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Reusable page header — DESIGN_SYSTEM.md §5.3. Title (24/600) + subtitle
 * (14, secondary) + an optional right-aligned action + a rule beneath.
 *
 * <p>Module dashboards pass their module's accent hex so the rule's first
 * 48px render as the ledger-spine colour before the hairline continues —
 * built as an {@link HBox} of two {@link Region}s rather than a gradient,
 * per the landmark-theme.css {@code .lm-page-rule-accent} comment.
 */
public final class PageHeader {

    private PageHeader() { }

    public static VBox build(String title, String subtitle) {
        return build(title, subtitle, null, null);
    }

    public static VBox build(String title, String subtitle, Node action) {
        return build(title, subtitle, action, null);
    }

    /**
     * @param moduleAccentHex non-null on a module dashboard landing page —
     *                        renders the first 48px of the rule in that
     *                        module's accent colour (the "ledger spine",
     *                        horizontal). {@code null} for a plain hairline.
     */
    public static VBox build(String title, String subtitle, Node action, String moduleAccentHex) {
        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("lm-page-title");

        Label subtitleLbl = new Label(subtitle == null ? "" : subtitle);
        subtitleLbl.getStyleClass().add("lm-page-subtitle");

        VBox text = new VBox(4, titleLbl, subtitleLbl);

        HBox top = new HBox(text);
        top.setAlignment(Pos.CENTER_LEFT);
        if (action != null) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            top.getChildren().addAll(spacer, action);
        }

        VBox header = new VBox(20, top, buildRule(moduleAccentHex));
        return header;
    }

    private static Node buildRule(String moduleAccentHex) {
        if (moduleAccentHex == null || moduleAccentHex.isBlank()) {
            Region hairline = new Region();
            hairline.getStyleClass().add("lm-page-rule");
            hairline.setMaxWidth(Double.MAX_VALUE);
            return hairline;
        }

        Region accent = new Region();
        accent.getStyleClass().add("lm-page-rule-accent");
        accent.setStyle("-fx-background-color: " + moduleAccentHex + ";");

        Region hairline = new Region();
        hairline.getStyleClass().add("lm-page-rule");
        HBox.setHgrow(hairline, Priority.ALWAYS);

        HBox rule = new HBox(accent, hairline);
        rule.setMaxWidth(Double.MAX_VALUE);
        return rule;
    }
}
