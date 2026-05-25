/*
 * Copyright (c) 2026 Landmark Software Pty Ltd.
 * All rights reserved.
 *
 * Reporting-only entry point.  Run with:  mvn javafx:run -Preporting
 *
 * Boots the same Spring Boot context + same login screen as
 * {@link FixedAssetsApplication}, but flips {@link AppMode#current} to
 * REPORTING in main() so the post-login navigation in
 * FixedAssetsApplication.start() branches to the Reports Hub instead of
 * the full main menu.
 */
package com.landmarksoftware.desktop;

import javafx.application.Application;

public class ReportingApplication extends FixedAssetsApplication {

    public static void main(String[] args) {
        AppMode.current = AppMode.Mode.REPORTING;
        Application.launch(ReportingApplication.class, args);
    }
}
