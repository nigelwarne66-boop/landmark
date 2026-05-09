package com.example.fixedassets;

import com.example.fixedassets.ui.LoginController;
import com.example.fixedassets.ui.MainMenuController;
import javafx.application.Application;
import javafx.application.Platform;
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

    private ConfigurableApplicationContext springContext;

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

        // Login succeeded — open main menu (MENU01)
        MainMenuController menu = springContext.getBean(MainMenuController.class);
        primaryStage.setScene(menu.buildScene());
        primaryStage.setTitle("Landmark");
        primaryStage.setMinWidth(740);
        primaryStage.setMinHeight(580);
        primaryStage.setOnCloseRequest(e -> Platform.exit());
        primaryStage.show();
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
