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

    /**
     * Product name shown in UI chrome (window titles, header wordmark, login
     * screen). FULL (the main app) rebranded to Compas ERP; REPORTING keeps
     * the Landmark name — it ships to existing clients unchanged.
     */
    public static String brandName() {
        return current == Mode.REPORTING ? "Landmark" : "Compas ERP";
    }

    /**
     * Classpath resource path of the design-system theme stylesheet to load
     * on the primary Scene. REPORTING keeps landmark-theme.css (teal/green)
     * unchanged; FULL loads compas-theme.css (navy/compass-blue brand tokens,
     * same structure — see compas-theme.css header comment).
     */
    public static String themeCssPath() {
        return current == Mode.REPORTING
            ? "/com/landmarksoftware/ui/css/landmark-theme.css"
            : "/com/landmarksoftware/ui/css/compas-theme.css";
    }

    private AppMode() {}
}
