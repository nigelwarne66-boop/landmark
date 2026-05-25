package com.landmarksoftware.desktop;

/**
 * Set before Application.launch() to control post-login navigation.
 *
 * FULL       → normal flow → MainMenuController
 * REPORTING  → reporting-only flow → ReportsHubController
 *
 * LoginController and CompanySelectionController check this
 * to decide which scene to load next (see TODO comments in each).
 */
public class AppMode {

    public enum Mode { FULL, REPORTING }

    /** Default is FULL so existing behaviour is completely unchanged. */
    public static Mode current = Mode.FULL;

    private AppMode() {}
}
