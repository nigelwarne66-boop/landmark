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

import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

/**
 * The 56px header built by {@link AppShell#buildHeader(ShellContext)} —
 * DESIGN_SYSTEM.md §5.1. Wraps the root {@link HBox} plus mutators for the
 * pieces that need to change after the shell is built (a MENU23 company/year
 * switch, a login identity refresh) without a full rebuild.
 */
public final class ShellHeader {

    private final HBox root;
    private final Label contextChip;
    private final Label userNameLabel;
    private final Label avatarLabel;
    private final TextField searchField;

    ShellHeader(HBox root, Label contextChip, Label userNameLabel, Label avatarLabel, TextField searchField) {
        this.root = root;
        this.contextChip = contextChip;
        this.userNameLabel = userNameLabel;
        this.avatarLabel = avatarLabel;
        this.searchField = searchField;
    }

    public HBox getNode() { return root; }

    /** The global search field (§5.1) — not yet wired to a search feature in
     *  either app; exposed so a future wave can attach a listener. */
    public TextField getSearchField() { return searchField; }

    /** Refresh the "Company · FY 20XX–YY" chip after a MENU23 switch. Pass
     *  {@link ShellContext#chipText(String, String)} for a byte-identical
     *  format to the one used when the header was first built. */
    public void updateContext(String text) {
        contextChip.setText(text);
    }

    /** Refresh the user button's name and avatar initials — e.g. after login
     *  populates the identity that was unknown when the shell was built. */
    public void updateUserName(String displayName) {
        userNameLabel.setText(displayName);
        avatarLabel.setText(AppShell.deriveInitials(displayName));
    }
}
