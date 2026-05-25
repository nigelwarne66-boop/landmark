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
package com.landmarksoftware.desktop;

import com.landmarksoftware.ui.LoginController;
import com.landmarksoftware.ui.MainMenuController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Landmark — JavaFX entry point wired to Spring Boot.
 *
 * Integration pattern:
 *   1. JavaFX Application.launch() starts the FX toolkit on the FX thread.
 *   2. init() boots the Spring context on a background thread (DB connect, wiring).
 *   3. start() opens the login screen then the main menu.
 *   4. stop() closes Spring cleanly when the window is closed.
 *
 * Run:  mvn exec:java
 */
public class FixedAssetsApplication extends Application {

    protected ConfigurableApplicationContext springContext;

    @Override
    public void init() {
        springContext = SpringApplication.run(
            SpringConfig.class,
            getParameters().getRaw().toArray(new String[0]));
    }

    @Override
    public void start(Stage primaryStage) {
        // Show login screen first (MENU00)
        LoginController login = springContext.getBean(LoginController.class);
        Stage loginStage = new Stage();
        loginStage.setOnCloseRequest(e -> Platform.exit());

        boolean authenticated = login.showAndWait(loginStage);
        if (!authenticated) {
            Platform.exit();
            return;
        }

        // Post-login navigation — branches on AppMode.current.
        // FULL      → MainMenuController (the existing full menu)
        // REPORTING → ReportsHub (set by ReportingApplication.main())
        if (AppMode.current == AppMode.Mode.REPORTING) {
            showReportsHub(primaryStage);
        } else {
            showMainMenu(primaryStage);
        }
    }

    private void showMainMenu(Stage primaryStage) {
        MainMenuController menu = springContext.getBean(MainMenuController.class);
        primaryStage.setScene(menu.buildScene());
        primaryStage.setTitle("Landmark");
        primaryStage.setMinWidth(740);
        primaryStage.setMinHeight(580);
        primaryStage.setOnCloseRequest(e -> Platform.exit());
        primaryStage.show();
    }

    /**
     * Reporting-only post-login flow. Populates AppSession with the same
     * company/year defaults MainMenuController would have set, then loads
     * the Reports Hub FXML.
     */
    private void showReportsHub(Stage primaryStage) {
        try {
            // Populate AppSession.companyNo/companyName/yearDesc using the
            // existing logic in MainMenuController (default-or-last-pick),
            // without building the full menu scene.
            MainMenuController menu = springContext.getBean(MainMenuController.class);
            menu.loadDefaultSession();

            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/fxml/reports-hub.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            Scene scene = new Scene(root, 1000, 680);
            scene.getStylesheets().add(
                getClass().getResource("/css/fixedassets.css").toExternalForm());
            scene.getStylesheets().add(
                getClass().getResource("/css/reporting.css").toExternalForm());

            primaryStage.setTitle("Landmark Reports");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(900);
            primaryStage.setMinHeight(600);
            primaryStage.setOnCloseRequest(e -> Platform.exit());
            primaryStage.show();
        } catch (Exception ex) {
            // Explicit print so the trace lands in the launch log — JavaFX's
            // launcher otherwise only prints "Exception in Application start method".
            System.err.println("=== Reports Hub load failed ===");
            ex.printStackTrace();
            throw new RuntimeException("Failed to load Reports Hub", ex);
        }
    }

    @Override
    public void stop() {
        // Close Spring context — this shuts down HikariCP and background threads cleanly
        if (springContext != null) {
            springContext.close();
        }
        // Force JVM exit to terminate any lingering daemon threads
        // (MySQL connection cleanup thread, JavaFX threads) without waiting
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
