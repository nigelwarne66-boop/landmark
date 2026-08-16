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

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Configuration handed to {@link AppShell#buildHeader(ShellContext)} and
 * {@link AppShell#buildRail(ShellContext)} — DESIGN_SYSTEM.md §5.
 *
 * <p>A small fluent config object rather than a builder-with-build(): both
 * callers (MainMenuController, ReportsHubController) construct one of these
 * per shell build/rebuild from their own session state, so there is no
 * intermediate "builder vs built product" distinction worth the ceremony.
 * All fields have safe no-op defaults so a caller only needs to set what it
 * actually uses (e.g. MainMenuController never sets the favourites
 * callback — see {@link AppShell} javadoc for why that's still correct).
 */
public final class ShellContext {

    String companyName = "";
    String financialYearLabel = "";
    Runnable onContextChipClick = () -> { };

    String userDisplayName = "";
    /** Secondary identity line under the name in the user menu (§7.2) — an
     *  email address if one exists, otherwise the login/username, or
     *  {@code null} to omit the line entirely. Never fabricated. */
    String userSecondaryLine;

    Runnable onSwitchCompany = () -> { };
    Runnable onSwitchFinancialYear = () -> { };
    Runnable onPreferences = () -> { };
    /** Wave 7 (DESIGN_SYSTEM.md §10) — "Open in Reports" user-menu item,
     *  sibling of {@link #onPreferences}. {@code () -> { }} no-op default so
     *  a caller that hasn't wired the handoff (e.g. the reporting build
     *  itself, which has no "open in reports" to offer) doesn't need to set
     *  it. See {@code AppShell} javadoc for the menu placement. */
    Runnable onOpenInReports = () -> { };
    Runnable onSignOut = () -> { };

    /** Currently active module for the ledger-spine indicator, or
     *  {@code null} if a non-module view (e.g. Favourites) is active. */
    Module activeModule;
    Predicate<Module> moduleVisible = m -> true;
    Consumer<Module> onModuleSelected = m -> { };

    /** {@code null} (the default) keeps the Favourites row a static,
     *  non-interactive placeholder — see {@link AppShell}. */
    Runnable onFavouritesClick;
    Supplier<Integer> favouritesCount = () -> 0;

    public ShellContext companyName(String v) { this.companyName = v == null ? "" : v; return this; }
    public ShellContext financialYearLabel(String v) { this.financialYearLabel = v == null ? "" : v; return this; }
    public ShellContext onContextChipClick(Runnable v) { this.onContextChipClick = v; return this; }

    public ShellContext userDisplayName(String v) { this.userDisplayName = v == null ? "" : v; return this; }
    public ShellContext userSecondaryLine(String v) { this.userSecondaryLine = v; return this; }

    public ShellContext onSwitchCompany(Runnable v) { this.onSwitchCompany = v; return this; }
    public ShellContext onSwitchFinancialYear(Runnable v) { this.onSwitchFinancialYear = v; return this; }
    public ShellContext onPreferences(Runnable v) { this.onPreferences = v; return this; }
    public ShellContext onOpenInReports(Runnable v) { this.onOpenInReports = v; return this; }
    public ShellContext onSignOut(Runnable v) { this.onSignOut = v; return this; }

    public ShellContext activeModule(Module v) { this.activeModule = v; return this; }
    public ShellContext moduleVisible(Predicate<Module> v) { this.moduleVisible = v; return this; }
    public ShellContext onModuleSelected(Consumer<Module> v) { this.onModuleSelected = v; return this; }

    public ShellContext onFavouritesClick(Runnable v) { this.onFavouritesClick = v; return this; }
    public ShellContext favouritesCount(Supplier<Integer> v) { this.favouritesCount = v; return this; }

    /** "Company · FY 2025–26" — the header context chip text (§5.1). */
    String contextChipText() {
        return chipText(companyName, financialYearLabel);
    }

    /** Shared formatter so a caller refreshing the chip after a MENU23
     *  switch (outside a full rebuild) produces byte-identical text. */
    public static String chipText(String companyName, String financialYearLabel) {
        boolean hasCompany = companyName != null && !companyName.isBlank();
        boolean hasYear = financialYearLabel != null && !financialYearLabel.isBlank();
        if (hasCompany && hasYear) return companyName + " · " + financialYearLabel;
        if (hasCompany) return companyName;
        if (hasYear) return financialYearLabel;
        return "Select company";
    }
}
