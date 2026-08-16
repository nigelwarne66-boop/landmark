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
package com.landmarksoftware.ui.nav;

/**
 * Single source of truth for the Landmark module registry — DESIGN_SYSTEM.md
 * §4. Nav order and names are locked; do not reorder, rename or abbreviate.
 *
 * <p>This is a Wave 1 (design-system rollout) addition. It is deliberately
 * <b>not yet wired</b> into {@code MainMenuController.buildSidebar()}'s
 * {@code mods} array or {@code ReportsHubController.MODULE_ICON} — those
 * hard-coded lists stay in place until Wave 2 rebuilds the rail itself. See
 * DESIGN_SYSTEM.md §10 (implementation plan) for the wave breakdown.
 *
 * <h2>Icon fields</h2>
 * Two icon literals are carried per module:
 * <ul>
 *   <li>{@link #tablerIcon} — the canonical Tabler icon name from
 *       DESIGN_SYSTEM.md §4 (e.g. {@code book-2}). Documentation only for
 *       now — {@code ikonli-tabler-pack} is not yet on Maven Central, so
 *       nothing renders this today.</li>
 *   <li>{@link #featherIcon} — the {@code fth-}-prefixed Ikonli Feather
 *       literal actually used for rendering today, carried forward from
 *       {@code MainMenuController.buildSidebar()}'s {@code mods} array
 *       (the rail this enum will eventually drive in Wave 2).</li>
 * </ul>
 *
 * <h2>Route / content-id</h2>
 * {@link #routeId} matches the lowercase module id convention already used
 * by {@code MainMenuController.selectSidebarModule(String)} /
 * {@code appSession.isModuleInstalled(String)} and
 * {@code ReportsHubController}'s module ids ("gl", "ar", "ap", "cm", "po",
 * "sm", "fa", "py", "bas", "sys") — reused verbatim so a future wire-up is a
 * drop-in replacement, not a rename.
 *
 * <h2>Accent / tint</h2>
 * {@link #accentHex} and {@link #tintHex} are the DESIGN_SYSTEM.md §4
 * values — these differ from the ad hoc accent hexes currently inline in
 * the {@code mods} array, which predate the design system and will be
 * retired when the rail is rebuilt on this enum.
 */
public enum Module {

    GENERAL_LEDGER("GL", "General Ledger", "book-2", "fth-bar-chart-2",
        "#3D5A98", "#E8EBF3", "gl"),

    ACCOUNTS_RECEIVABLE("AR", "Accounts Receivable", "file-invoice", "fth-users",
        "#2E7D57", "#E6EFEB", "ar"),

    ACCOUNTS_PAYABLE("AP", "Accounts Payable", "receipt-2", "fth-file-text",
        "#B26B12", "#F6EDE3", "ap"),

    CASH_MANAGEMENT("CM", "Cash Management", "wallet", "fth-dollar-sign",
        "#0E7263", "#E2EEEC", "cm"),

    PURCHASING("PO", "Purchasing", "shopping-cart", "fth-shopping-cart",
        "#6B4FA8", "#EDEAF5", "po"),

    INVENTORY("SM", "Inventory", "packages", "fth-package",
        "#2F6F8F", "#E4EEF2", "sm"),

    FIXED_ASSETS("FA", "Fixed Assets", "building-warehouse", "fth-home",
        "#8A5A3C", "#F1EBE8", "fa"),

    PAYROLL("PY", "Payroll", "users", "fth-user",
        "#A63D6B", "#F4E8ED", "py"),

    BAS_TAX("BAS", "BAS / Tax", "percentage", "fth-percent",
        "#5F7A2E", "#ECEFE6", "bas"),

    SYSTEM("SY", "System", "settings", "fth-settings",
        "#5A5F5B", "#EBECEB", "sys");

    /** Two/three-letter module code from DESIGN_SYSTEM.md §4 (GL, AR, AP, ...). */
    private final String code;

    /** Full display name — identical in both apps per DESIGN_SYSTEM.md §4. */
    private final String displayName;

    /**
     * Canonical Tabler icon literal from DESIGN_SYSTEM.md §4 — future use
     * once {@code ikonli-tabler-pack} lands on Maven Central. Not rendered
     * today.
     */
    private final String tablerIcon;

    /**
     * Ikonli Feather literal ({@code fth-} prefix) currently rendered by
     * the desktop rail, carried forward from {@code MainMenuController}'s
     * {@code mods} array.
     */
    private final String featherIcon;

    /** Accent hex — DESIGN_SYSTEM.md §4. Used only in icon tiles, module
     *  dashboard header rule and chart series defaults; never rail/header/
     *  button/text colour (§4 note). */
    private final String accentHex;

    /** Tile tint hex — DESIGN_SYSTEM.md §4. */
    private final String tintHex;

    /**
     * Lowercase route / content-id, matching the convention already used by
     * {@code MainMenuController.selectSidebarModule(String)} and
     * {@code ReportsHubController}'s module ids.
     */
    private final String routeId;

    Module(String code, String displayName, String tablerIcon, String featherIcon,
           String accentHex, String tintHex, String routeId) {
        this.code = code;
        this.displayName = displayName;
        this.tablerIcon = tablerIcon;
        this.featherIcon = featherIcon;
        this.accentHex = accentHex;
        this.tintHex = tintHex;
        this.routeId = routeId;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getTablerIcon() {
        return tablerIcon;
    }

    public String getFeatherIcon() {
        return featherIcon;
    }

    public String getAccentHex() {
        return accentHex;
    }

    public String getTintHex() {
        return tintHex;
    }

    public String getRouteId() {
        return routeId;
    }

    /** Looks up a Module by its lowercase route id (e.g. "gl", "sys"). Returns null if not found. */
    public static Module byRouteId(String routeId) {
        for (Module m : values()) {
            if (m.routeId.equals(routeId)) {
                return m;
            }
        }
        return null;
    }
}
