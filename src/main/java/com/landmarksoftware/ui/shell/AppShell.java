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

import com.landmarksoftware.ui.LandmarkLogo;
import com.landmarksoftware.ui.nav.Module;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared shell builder — DESIGN_SYSTEM.md §5. Builds the 56px header (§5.1)
 * and the 240px/64px navigation rail (§5.2) from a {@link ShellContext} so
 * {@code MainMenuController} (full app) and {@code ReportsHubController}
 * ({@code -Preporting} build) render pixel-identical chrome above and left
 * of the content region — Wave 2's done-criterion.
 *
 * <p>This class only knows how to build/refresh the chrome Nodes; it owns no
 * session state itself. Each caller supplies its own session values and
 * callbacks via {@link ShellContext} and keeps the returned {@link ShellHeader}
 * / {@link ShellRail} handles for later refreshes (company switch, etc).
 *
 * <h2>Favourites row</h2>
 * DESIGN_SYSTEM.md §5.2/§7 puts a Favourites section above MODULES in both
 * apps. Neither app has a favourites feature wired into its *navigation*
 * today (ReportsHubController has a working per-report favourites store, but
 * it currently lives inside the module content area, not the rail). Rather
 * than invent rail-level favourites behaviour for MainMenuController (which
 * has no favourites concept at all), the row defaults to a visually-present,
 * functionally inert placeholder: {@link ShellContext#onFavouritesClick} is
 * {@code null} unless a caller opts in, in which case the row becomes
 * clickable and its count badge is driven by {@link ShellContext#favouritesCount}.
 */
@Component
public class AppShell {

    private final RailStateStore railState;

    public AppShell(RailStateStore railState) {
        this.railState = railState;
    }

    // ── Header — §5.1 ────────────────────────────────────────────────

    public ShellHeader buildHeader(ShellContext ctx) {
        Node brand = LandmarkLogo.iconMark(24);

        Label wordmark = new Label("Landmark");
        wordmark.getStyleClass().add("lm-header-wordmark");

        Label contextChip = new Label(ctx.contextChipText());
        contextChip.getStyleClass().add("lm-context-chip");
        contextChip.setOnMouseClicked(e -> ctx.onContextChipClick.run());

        HBox left = new HBox(12, brand, wordmark, contextChip);
        left.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        TextField search = new TextField();
        search.setPromptText("Search…");
        search.getStyleClass().add("lm-search");

        Label avatar = new Label(deriveInitials(ctx.userDisplayName));
        avatar.getStyleClass().add("lm-avatar");

        Label userNameLabel = new Label(ctx.userDisplayName);

        FontIcon chevron = new FontIcon("fth-chevron-down");
        chevron.setIconSize(14);

        HBox userButton = new HBox(8, avatar, userNameLabel, chevron);
        userButton.getStyleClass().add("lm-user-button");
        userButton.setAlignment(Pos.CENTER_LEFT);

        ContextMenu userMenu = buildUserMenu(ctx);
        userButton.setOnMouseClicked(e -> userMenu.show(userButton, Side.BOTTOM, 0, 4));

        HBox right = new HBox(8, search, userButton);
        right.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(left, spacer, right);
        header.getStyleClass().add("lm-header");
        header.setAlignment(Pos.CENTER_LEFT);

        return new ShellHeader(header, contextChip, userNameLabel, avatar, search);
    }

    /** User menu popover — DESIGN_SYSTEM.md §7.2. */
    private ContextMenu buildUserMenu(ShellContext ctx) {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("lm-popover");

        // ContextMenu has no native non-selectable header slot — a
        // CustomMenuItem with hideOnClick=false stands in for the
        // name/email block at the top of the §7.2 popover.
        Label nameLbl = new Label(ctx.userDisplayName);
        nameLbl.setStyle("-fx-font-weight: 600; -fx-font-size: 13px; -fx-text-fill: -lm-text-primary;");
        CustomMenuItem nameItem = new CustomMenuItem(nameLbl, false);
        nameItem.setHideOnClick(false);
        menu.getItems().add(nameItem);

        if (ctx.userSecondaryLine != null && !ctx.userSecondaryLine.isBlank()) {
            Label secondaryLbl = new Label(ctx.userSecondaryLine);
            secondaryLbl.getStyleClass().add("lm-caption");
            CustomMenuItem secondaryItem = new CustomMenuItem(secondaryLbl, false);
            secondaryItem.setHideOnClick(false);
            menu.getItems().add(secondaryItem);
        }

        menu.getItems().add(new SeparatorMenuItem());

        MenuItem switchCompany = new MenuItem("Switch company");
        switchCompany.setOnAction(e -> ctx.onSwitchCompany.run());
        MenuItem switchYear = new MenuItem("Switch financial year");
        switchYear.setOnAction(e -> ctx.onSwitchFinancialYear.run());
        MenuItem preferences = new MenuItem("Preferences");
        preferences.setOnAction(e -> ctx.onPreferences.run());
        // Wave 7 (DESIGN_SYSTEM.md §10) — sibling of Preferences. No-op by
        // default (ShellContext.onOpenInReports); still shown so the item is
        // discoverable, but harmless for a caller that never wires it.
        FontIcon openInReportsIcon = new FontIcon("fth-external-link");
        openInReportsIcon.setIconSize(14);
        MenuItem openInReports = new MenuItem("Open in Reports", openInReportsIcon);
        openInReports.setOnAction(e -> ctx.onOpenInReports.run());
        menu.getItems().addAll(switchCompany, switchYear, preferences, openInReports);

        menu.getItems().add(new SeparatorMenuItem());

        // Sign out is a normal menu item with a logout icon — not red, not a
        // button (DESIGN_SYSTEM.md §7.2, fixes defect D3).
        FontIcon logoutIcon = new FontIcon("fth-log-out");
        logoutIcon.setIconSize(14);
        MenuItem signOut = new MenuItem("Sign out", logoutIcon);
        signOut.setOnAction(e -> ctx.onSignOut.run());
        menu.getItems().add(signOut);

        return menu;
    }

    // ── Navigation rail — §5.2 ──────────────────────────────────────

    public ShellRail buildRail(ShellContext ctx) {
        VBox rail = new VBox();
        rail.getStyleClass().add("lm-rail");
        if (railState.isCollapsed()) rail.getStyleClass().add("collapsed");

        Label favouritesCount = new Label(String.valueOf(ctx.favouritesCount.get()));
        HBox favouritesRow = buildFavouritesRow(ctx, favouritesCount);
        rail.getChildren().add(favouritesRow);

        Label modulesLabel = new Label("MODULES");
        modulesLabel.getStyleClass().add("lm-rail-label");
        rail.getChildren().add(modulesLabel);

        Map<Module, HBox> rows = new LinkedHashMap<>();
        for (Module m : Module.values()) {
            HBox row = buildModuleRow(m, ctx);
            boolean show = ctx.moduleVisible.test(m);
            row.setVisible(show);
            row.setManaged(show);
            row.pseudoClassStateChanged(ShellRail.ACTIVE, m == ctx.activeModule);
            rows.put(m, row);
            rail.getChildren().add(row);
        }

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        rail.getChildren().add(grow);

        rail.getChildren().add(buildCollapseToggle(rail));

        return new ShellRail(rail, rows, favouritesRow, favouritesCount);
    }

    private HBox buildFavouritesRow(ShellContext ctx, Label countLabel) {
        FontIcon star = new FontIcon("fth-star");
        star.setIconSize(18);

        Label lbl = new Label("Favourites");
        HBox.setHgrow(lbl, Priority.ALWAYS);

        countLabel.getStyleClass().add("lm-badge");

        HBox row = new HBox(12, star, lbl, countLabel);
        row.getStyleClass().add("lm-nav-item");
        row.setAlignment(Pos.CENTER_LEFT);

        if (ctx.onFavouritesClick != null) {
            row.setOnMouseClicked(e -> ctx.onFavouritesClick.run());
        } else {
            // No favourites feature wired into navigation — present but inert.
            row.setStyle("-fx-cursor: default;");
        }
        return row;
    }

    private HBox buildModuleRow(Module m, ShellContext ctx) {
        FontIcon icon = new FontIcon(m.getFeatherIcon());
        icon.setIconSize(18);

        Label lbl = new Label(m.getDisplayName());
        HBox.setHgrow(lbl, Priority.ALWAYS);

        HBox row = new HBox(12, icon, lbl);
        row.getStyleClass().add("lm-nav-item");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setUserData(m);
        row.setOnMouseClicked(e -> ctx.onModuleSelected.accept(m));
        return row;
    }

    private HBox buildCollapseToggle(VBox rail) {
        boolean collapsedInit = rail.getStyleClass().contains("collapsed");

        FontIcon icon = new FontIcon(collapsedInit ? "fth-chevrons-right" : "fth-chevrons-left");
        icon.setIconSize(16);

        Label lbl = new Label("Collapse");
        HBox.setHgrow(lbl, Priority.ALWAYS);
        lbl.setVisible(!collapsedInit);
        lbl.setManaged(!collapsedInit);

        HBox toggle = new HBox(12, icon, lbl);
        toggle.getStyleClass().add("lm-nav-item");
        toggle.setAlignment(Pos.CENTER_LEFT);

        Tooltip tip = new Tooltip(collapsedInit ? "Expand navigation" : "Collapse navigation");
        Tooltip.install(toggle, tip);

        toggle.setOnMouseClicked(e -> {
            boolean collapseNow = !rail.getStyleClass().contains("collapsed");
            if (collapseNow) rail.getStyleClass().add("collapsed");
            else rail.getStyleClass().remove("collapsed");
            railState.setCollapsed(collapseNow);

            icon.setIconLiteral(collapseNow ? "fth-chevrons-right" : "fth-chevrons-left");
            lbl.setVisible(!collapseNow);
            lbl.setManaged(!collapseNow);
            tip.setText(collapseNow ? "Expand navigation" : "Collapse navigation");
        });

        return toggle;
    }

    static String deriveInitials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }
}
