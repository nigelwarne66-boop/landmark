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

import javafx.scene.control.Button;

/**
 * Static factories for the four button variants — DESIGN_SYSTEM.md §7.1.
 * Every button returned already carries {@code .lm-btn} plus its variant
 * class from {@code landmark-theme.css}; callers never need to know the raw
 * class names.
 *
 * <p>Heights: 32px default (nothing extra to apply), 28px in dense toolbars
 * ({@link #small(Button)} — the {@code .sm} modifier), 36px on forms
 * ({@link #large(Button)} — the {@code .lg} modifier).
 *
 * <p>Per §7.1, danger is never used for navigation or sign-out — that's a
 * caller discipline, not something this class can enforce.
 */
public final class LmButton {

    private LmButton() { }

    /** One per screen — the main action. */
    public static Button primary(String text, Runnable onAction) {
        return build(text, onAction, "lm-btn-primary");
    }

    /** Everything else. */
    public static Button secondary(String text, Runnable onAction) {
        return build(text, onAction, "lm-btn-secondary");
    }

    /** Toolbar and row actions. */
    public static Button ghost(String text, Runnable onAction) {
        return build(text, onAction, "lm-btn-ghost");
    }

    /** Delete, reverse, cancel a posted document — never navigation or sign-out. */
    public static Button danger(String text, Runnable onAction) {
        return build(text, onAction, "lm-btn-danger");
    }

    /** Applies the 28px dense-toolbar size to an existing {@code .lm-btn}. */
    public static Button small(Button button) {
        button.getStyleClass().add("sm");
        return button;
    }

    /** Applies the 36px forms size to an existing {@code .lm-btn}. */
    public static Button large(Button button) {
        button.getStyleClass().add("lg");
        return button;
    }

    private static Button build(String text, Runnable onAction, String variantClass) {
        Button btn = new Button(text);
        btn.getStyleClass().addAll("lm-btn", variantClass);
        if (onAction != null) {
            btn.setOnAction(e -> onAction.run());
        }
        return btn;
    }
}
