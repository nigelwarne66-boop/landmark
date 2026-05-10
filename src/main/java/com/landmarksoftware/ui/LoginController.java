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
package com.landmarksoftware.ui;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.model.UserRecord;
import com.landmarksoftware.service.PasswordService;
import com.landmarksoftware.service.UserService;
import javafx.animation.*;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * MENU00 — Login Screen (Xero-style: centred single panel).
 *
 * Authentication mirrors COBOL CHECK-USER-ID + CHECK-PASSWORD (menu00.pl):
 *   - Looks up user_id in MEUSERS
 *   - Checks user_status: H=suspended, L=locked, T=terminated
 *   - Compares plain-text password
 *   - Locks account after MAX_ATTEMPTS failures (user_status='L')
 *   - Checks passwd_expiry_date (MySQL DATE column)
 *
 * "Forgot User ID?" — user enters email address, matching user IDs are displayed.
 * "Reset Password?" — user enters user ID, temporary password generated + shown.
 *
 * MEUSERS confirmed columns: user_id, name1, password, user_status,
 *   supervisor_flag, passwd_expiry_date, last_access_date, email_address
 */
@Component
public class LoginController {

    private static final int MAX_ATTEMPTS = 3;

    private final UserService     userService;
    private final PasswordService passwordService;
    private final AppSession      appSession;

    private int     attempts    = 0;
    private Label   lblMessage;
    private Label   lblAttempts;
    private boolean loginSuccess = false;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "login-thread"); t.setDaemon(true); return t;
    });

    public LoginController(UserService userService,
                            PasswordService passwordService,
                            AppSession appSession) {
        this.userService     = userService;
        this.passwordService = passwordService;
        this.appSession      = appSession;
    }

    public boolean showAndWait(Stage stage) {
        stage.setScene(buildScene(stage));
        stage.setTitle("Landmark Software — Sign In");
        stage.setResizable(false);
        stage.showAndWait();
        return loginSuccess;
    }

    // ══════════════════════════════════════════════════════════════
    // Scene — centred white card on grey background
    // ══════════════════════════════════════════════════════════════

    private Scene buildScene(Stage stage) {
        StackPane root = new StackPane(buildCard(stage));
        root.setStyle("-fx-background-color: #F4F4F2;");
        Scene scene = new Scene(root, 460, 560);
        scene.getStylesheets().add(
            getClass().getResource("/css/fixedassets.css").toExternalForm());
        return scene;
    }

    private VBox buildCard(Stage stage) {
        VBox card = new VBox();
        card.setAlignment(Pos.TOP_CENTER);
        card.setPadding(new Insets(44, 48, 36, 48));
        card.setSpacing(0);
        card.setMaxWidth(360);
        card.setStyle(
            "-fx-background-color: white;" +
            "-fx-background-radius: 14;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.10), 28, 0, 0, 4);");

        // Logo
        javafx.scene.Node pin = LandmarkLogo.iconMark(110);
        HBox logoRow = new HBox(pin);
        logoRow.setAlignment(Pos.CENTER);
        VBox.setMargin(logoRow, new Insets(0, 0, 18, 0));

        // Title
        Label title = new Label("Landmark");
        title.setStyle("-fx-font-size:21px; -fx-font-weight:bold; -fx-text-fill:#1A1A2E;");
        title.setAlignment(Pos.CENTER);
        title.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(title, new Insets(0, 0, 26, 0));

        // Fields
        TextField fUserId = field("User ID");
        PasswordField fPassword = passwordField("Password");
        VBox.setMargin(fUserId,   new Insets(0, 0, 10, 0));
        VBox.setMargin(fPassword, new Insets(0, 0, 8, 0));

        // Status labels
        lblAttempts = new Label("");
        lblAttempts.setStyle("-fx-font-size:11px; -fx-text-fill:#9CA3AF;");
        lblAttempts.setMaxWidth(Double.MAX_VALUE);

        lblMessage = new Label("");
        lblMessage.setStyle("-fx-font-size:12px; -fx-text-fill:#DC2626; -fx-wrap-text:true;");
        lblMessage.setMaxWidth(Double.MAX_VALUE);
        lblMessage.setMinHeight(16);
        VBox.setMargin(lblMessage, new Insets(0, 0, 8, 0));

        // Log in button
        Button btnLogin = new Button("Log in");
        btnLogin.setDefaultButton(true);
        btnLogin.setMaxWidth(Double.MAX_VALUE);
        btnLogin.setStyle(primaryBtn());
        btnLogin.setOnMouseEntered(e -> btnLogin.setStyle(primaryBtnHover()));
        btnLogin.setOnMouseExited(e  -> btnLogin.setStyle(primaryBtn()));
        VBox.setMargin(btnLogin, new Insets(4, 0, 0, 0));

        // Forgot links
        Hyperlink lnkForgotUser = link("Forgot User ID?");
        Hyperlink lnkResetPwd   = link("Reset Password?");
        HBox links = new HBox(24, lnkForgotUser, lnkResetPwd);
        links.setAlignment(Pos.CENTER);
        VBox.setMargin(links, new Insets(18, 0, 0, 0));

        // Footer
        Label footer = new Label(
            "© " + LocalDate.now().getYear() + " Landmark Business Software");
        footer.setStyle("-fx-font-size:10px; -fx-text-fill:#C4C4B4;");
        footer.setAlignment(Pos.CENTER);
        footer.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(footer, new Insets(22, 0, 0, 0));

        // Wire
        Runnable doLogin = () -> doLogin(stage, fUserId, fPassword, btnLogin);
        btnLogin.setOnAction(e -> doLogin.run());
        fPassword.setOnAction(e -> doLogin.run());
        fUserId.setOnAction(e   -> fPassword.requestFocus());
        lnkForgotUser.setOnAction(e -> showForgotUserDialog(card.getScene().getWindow()));
        lnkResetPwd.setOnAction(e   -> showResetPasswordDialog(card.getScene().getWindow()));

        card.getChildren().addAll(
            logoRow, title,
            fUserId, fPassword,
            lblAttempts, lblMessage,
            btnLogin, links, footer);

        // Fade in
        card.setOpacity(0);
        FadeTransition fi = new FadeTransition(Duration.millis(400), card);
        fi.setDelay(Duration.millis(60));
        fi.setFromValue(0); fi.setToValue(1);
        fi.setInterpolator(Interpolator.EASE_OUT);
        fi.setOnFinished(e -> fUserId.requestFocus());
        fi.play();
        return card;
    }

    // ── Login action ──────────────────────────────────────────────

    /** Crisp vector Landmark pin logo for the login screen. */
    private static javafx.scene.layout.StackPane buildLoginLogo(double size) {
        javafx.scene.shape.Circle outer = new javafx.scene.shape.Circle(size * 0.42);
        outer.setFill(javafx.scene.paint.Color.web("#1C2333"));

        javafx.scene.shape.Circle inner = new javafx.scene.shape.Circle(size * 0.30);
        inner.setFill(javafx.scene.paint.Color.web("#1A6EF5"));

        javafx.scene.shape.Polygon plane = new javafx.scene.shape.Polygon(
            size * 0.20,  0.0,
           -size * 0.14, -size * 0.14,
           -size * 0.06,  0.0,
           -size * 0.14,  size * 0.14
        );
        plane.setFill(javafx.scene.paint.Color.WHITE);
        plane.setRotate(-30);

        javafx.scene.shape.Polygon tail = new javafx.scene.shape.Polygon(
            -size * 0.14, size * 0.28,
             size * 0.14, size * 0.28,
             0.0,          size * 0.50
        );
        tail.setFill(javafx.scene.paint.Color.web("#1C2333"));

        javafx.scene.layout.StackPane pin = new javafx.scene.layout.StackPane(
            tail, outer, inner, plane);
        pin.setPrefSize(size, size * 1.2);
        pin.setMaxSize(size, size * 1.2);
        return pin;
    }

    private void doLogin(Stage stage, TextField fUserId,
                          PasswordField fPassword, Button btnLogin) {
        String uid = fUserId.getText().trim().toUpperCase();
        String pwd = fPassword.getText();
        if (uid.isEmpty()) { showMessage("Please enter your User ID.", false);
            fUserId.requestFocus(); return; }
        if (pwd.isEmpty()) { showMessage("Please enter your password.", false);
            fPassword.requestFocus(); return; }

        btnLogin.setDisable(true);
        lblMessage.setText("");
        exec.submit(() -> {
            String result = authenticate(uid, pwd);
            javafx.application.Platform.runLater(() -> {
                btnLogin.setDisable(false);
                if (result == null) {
                    loginSuccess = true;
                    appSession.setUserId(uid);
                    FadeTransition fade = new FadeTransition(
                        Duration.millis(220), stage.getScene().getRoot());
                    fade.setFromValue(1.0); fade.setToValue(0.0);
                    fade.setOnFinished(ev -> stage.close());
                    fade.play();
                } else {
                    showMessage(result, true);
                    fPassword.clear();
                    if (result.contains("locked") || result.contains("terminated")
                            || result.contains("suspended")) {
                        fUserId.setDisable(true);
                        fPassword.setDisable(true);
                        btnLogin.setDisable(true);
                    } else {
                        fPassword.requestFocus();
                    }
                }
            });
        });
    }

    // ══════════════════════════════════════════════════════════════
    // Forgot User ID dialog
    // ══════════════════════════════════════════════════════════════

    private void showForgotUserDialog(Window owner) {
        Stage dlg = dialogStage(owner, "Forgot User ID");
        VBox card = dialogCard(310);

        Label title = dialogTitle("Forgot User ID?");
        Label sub   = dialogSub(
            "Enter the email address on your account.\n" +
            "Matching User IDs will be displayed below.");

        TextField fEmail = field("Email address");
        VBox.setMargin(fEmail, new Insets(0, 0, 12, 0));

        Label lblResult = new Label("");
        lblResult.setStyle(
            "-fx-font-size:15px; -fx-font-weight:bold; -fx-text-fill:#1A1A2E; " +
            "-fx-wrap-text:true;");
        lblResult.setMaxWidth(Double.MAX_VALUE);

        Label lblStatus = new Label("");
        lblStatus.setStyle("-fx-font-size:12px; -fx-text-fill:#6B7280; -fx-wrap-text:true;");
        lblStatus.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(lblStatus, new Insets(0, 0, 8, 0));

        Button btnFind   = new Button("Find User ID");
        Button btnCancel = new Button("Close");
        styleDialogButtons(btnFind, btnCancel, dlg);

        btnFind.setOnAction(e -> {
            String email = fEmail.getText().trim();
            if (email.isEmpty()) { lblStatus.setText("Please enter an email address."); return; }
            btnFind.setDisable(true);
            lblResult.setText(""); lblStatus.setText("Searching…");
            exec.submit(() -> {
                String res = findUserIdByEmail(email);
                javafx.application.Platform.runLater(() -> {
                    btnFind.setDisable(false);
                    if (res.startsWith("ERROR:")) {
                        lblStatus.setText(res.substring(6).trim());
                        lblResult.setText("");
                    } else {
                        lblResult.setText(res);
                        lblStatus.setText("User ID(s) matching this email:");
                    }
                });
            });
        });

        card.getChildren().addAll(
            title, sub, fEmail, lblStatus, lblResult, btnFind, btnCancel);
        showDialog(dlg, card, 360, 320);
    }

    private String findUserIdByEmail(String email) {
        try {
            List<String> ids = userService.findUserIdsByEmail(email);
            if (ids.isEmpty())
                return "ERROR: No active account found for that email address.";
            return String.join(",  ", ids);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Reset Password dialog
    // ══════════════════════════════════════════════════════════════

    private void showResetPasswordDialog(Window owner) {
        Stage dlg = dialogStage(owner, "Reset Password");
        VBox card = dialogCard(310);

        Label title = dialogTitle("Reset Password?");
        Label sub   = dialogSub(
            "Enter your User ID. A temporary password will be generated.\n" +
            "You will be required to change it on your next login.");

        TextField fUid = field("User ID");
        VBox.setMargin(fUid, new Insets(0, 0, 12, 0));

        // Result panel — hidden until success
        VBox resultBox = new VBox(6);
        resultBox.setStyle(
            "-fx-background-color:#F0FFF4; -fx-background-radius:8;" +
            "-fx-border-color:#10B981; -fx-border-radius:8; -fx-border-width:1.5;" +
            "-fx-padding:14;");
        resultBox.setVisible(false); resultBox.setManaged(false);
        Label lblResultHdr = new Label("Your temporary password:");
        lblResultHdr.setStyle("-fx-font-size:11px; -fx-text-fill:#6B7280;");
        Label lblTempPwd = new Label("");
        lblTempPwd.setStyle(
            "-fx-font-size:26px; -fx-font-weight:bold; -fx-text-fill:#1A6EF5;" +
            "-fx-letter-spacing:3;");
        Label lblResultNote = new Label("Log in with this password then change it immediately.");
        lblResultNote.setStyle("-fx-font-size:10px; -fx-text-fill:#9CA3AF; -fx-wrap-text:true;");
        resultBox.getChildren().addAll(lblResultHdr, lblTempPwd, lblResultNote);
        VBox.setMargin(resultBox, new Insets(0, 0, 10, 0));

        Label lblStatus = new Label("");
        lblStatus.setStyle("-fx-font-size:12px; -fx-text-fill:#DC2626; -fx-wrap-text:true;");
        lblStatus.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(lblStatus, new Insets(0, 0, 8, 0));

        Button btnReset  = new Button("Generate Temporary Password");
        Button btnCancel = new Button("Close");
        styleDialogButtons(btnReset, btnCancel, dlg);

        btnReset.setOnAction(e -> {
            String uid = fUid.getText().trim().toUpperCase();
            if (uid.isEmpty()) { lblStatus.setText("Please enter your User ID."); return; }
            btnReset.setDisable(true);
            lblStatus.setText(""); resultBox.setVisible(false); resultBox.setManaged(false);
            exec.submit(() -> {
                String[] out = resetPassword(uid);
                javafx.application.Platform.runLater(() -> {
                    if (out[0] != null) {
                        lblTempPwd.setText(out[0]);
                        resultBox.setVisible(true); resultBox.setManaged(true);
                        lblStatus.setText("");
                        btnReset.setDisable(true);
                        fUid.setDisable(true);
                    } else {
                        lblStatus.setText(out[1]);
                        btnReset.setDisable(false);
                    }
                });
            });
        });

        card.getChildren().addAll(
            title, sub, fUid, resultBox, lblStatus, btnReset, btnCancel);
        showDialog(dlg, card, 360, 370);
    }

    private String[] resetPassword(String userId) {
        UserService.TempPasswordResult result = userService.generateTempPassword(userId);
        return result.success()
            ? new String[]{result.tempPassword(), null}
            : new String[]{null, result.errorMessage()};
    }

    // ══════════════════════════════════════════════════════════════
    // Authentication
    // ══════════════════════════════════════════════════════════════

    private String authenticate(String userId, String password) {
        attempts++;

        Optional<UserRecord> found = userService.findUser(userId);
        if (found.isEmpty()) return "User ID not on file.";
        UserRecord user = found.get();

        System.out.println("MEUSERS: " + user.userId() + " status=[" + user.userStatus() + "]");

        if ("H".equals(user.userStatus())) return "Access temporarily suspended.\nSee your supervisor.";
        if ("L".equals(user.userStatus())) return "Access locked.\nSee your supervisor or use Reset Password.";
        if ("T".equals(user.userStatus())) return "Access has been terminated.\nSee your supervisor.";

        // Verify password; migrate plain-text to BCrypt on first successful use
        String hashToWrite = passwordService.verifyAndMigrate(user.storedPassword(), password);
        if (hashToWrite == null) {
            if (attempts >= MAX_ATTEMPTS) {
                userService.lockUser(userId);
                return "Maximum attempts exceeded.\nAccount locked — see your supervisor.";
            }
            int rem = MAX_ATTEMPTS - attempts;
            updateAttemptLabel(rem);
            return "Incorrect password — " + rem + " attempt" + (rem == 1 ? "" : "s") + " left.";
        }

        // Password correct — check expiry
        if (user.passwdExpiry() != null && user.passwdExpiry().isBefore(LocalDate.now()))
            return "Password has expired.\nUse 'Reset Password?' to get a new one.";

        // Migrate plain-text password to BCrypt if needed (non-fatal)
        if (!hashToWrite.isEmpty())
            userService.writePasswordHash(userId, hashToWrite);

        // Record successful login (last-access date + MEPASS terminal)
        userService.recordSuccessfulLogin(userId, appSession.getCompanyNo(), appSession.getCompanyName());
        appSession.setSupervisorFlag("Y".equals(user.supervisorFlag()) ? "Y" : "N");
        appSession.setUserName(user.name1());
        return null;
    }

    // ══════════════════════════════════════════════════════════════
    // UI helpers
    // ══════════════════════════════════════════════════════════════

    private void updateAttemptLabel(int rem) {
        javafx.application.Platform.runLater(() ->
            lblAttempts.setText(rem + " attempt" + (rem == 1 ? "" : "s") + " remaining"));
    }

    private void showMessage(String msg, boolean error) {
        lblMessage.setText(msg);
        lblMessage.setStyle("-fx-font-size:12px; -fx-wrap-text:true; -fx-text-fill:"
            + (error ? "#DC2626" : "#059669") + ";");
        if (error) {
            TranslateTransition sh = new TranslateTransition(Duration.millis(55), lblMessage);
            sh.setFromX(0); sh.setToX(5); sh.setCycleCount(6); sh.setAutoReverse(true);
            sh.play();
        }
    }

    private TextField field(String prompt) {
        TextField tf = new TextField();
        tf.setPromptText(prompt); tf.setStyle(fieldStyle());
        tf.setPrefHeight(44); tf.setMaxWidth(Double.MAX_VALUE);
        return tf;
    }

    private PasswordField passwordField(String prompt) {
        PasswordField pf = new PasswordField();
        pf.setPromptText(prompt); pf.setStyle(fieldStyle());
        pf.setPrefHeight(44); pf.setMaxWidth(Double.MAX_VALUE);
        return pf;
    }

    private String fieldStyle() {
        return "-fx-background-color:white; -fx-border-color:#D1D5DB;" +
               "-fx-border-radius:8; -fx-background-radius:8;" +
               "-fx-font-size:13px; -fx-padding:0 12; -fx-border-width:1.5;";
    }

    private String primaryBtn() {
        return "-fx-background-color:#1A6EF5; -fx-text-fill:white;" +
               "-fx-font-size:14px; -fx-font-weight:bold;" +
               "-fx-background-radius:8; -fx-padding:12 0; -fx-cursor:hand;";
    }

    private String primaryBtnHover() { return primaryBtn().replace("#1A6EF5","#155EC7"); }

    private Hyperlink link(String text) {
        Hyperlink h = new Hyperlink(text);
        h.setStyle("-fx-text-fill:#1A6EF5; -fx-font-size:12px;" +
                   "-fx-border-color:transparent; -fx-underline:false;");
        return h;
    }

    private Stage dialogStage(Window owner, String title) {
        Stage s = new Stage();
        s.initOwner(owner); s.initModality(Modality.WINDOW_MODAL);
        s.setTitle(title); s.setResizable(false);
        return s;
    }

    private VBox dialogCard(double maxWidth) {
        VBox c = new VBox();
        c.setAlignment(Pos.TOP_LEFT);
        c.setPadding(new Insets(32, 32, 24, 32));
        c.setSpacing(0); c.setMaxWidth(maxWidth);
        c.setStyle("-fx-background-color:white; -fx-background-radius:12;" +
                   "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.09),16,0,0,3);");
        return c;
    }

    private Label dialogTitle(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:16px; -fx-font-weight:bold; -fx-text-fill:#1A1A2E;");
        l.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(l, new Insets(0,0,6,0));
        return l;
    }

    private Label dialogSub(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:12px; -fx-text-fill:#6B7280; -fx-wrap-text:true;");
        l.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(l, new Insets(0,0,18,0));
        return l;
    }

    private void styleDialogButtons(Button primary, Button cancel, Stage dlg) {
        primary.setDefaultButton(true);
        primary.setMaxWidth(Double.MAX_VALUE);
        primary.setStyle(primaryBtn());
        primary.setOnMouseEntered(e -> primary.setStyle(primaryBtnHover()));
        primary.setOnMouseExited(e  -> primary.setStyle(primaryBtn()));
        cancel.setStyle("-fx-background-color:transparent; -fx-text-fill:#6B7280;" +
                        "-fx-font-size:13px; -fx-background-radius:8; -fx-padding:8 0; -fx-cursor:hand;");
        cancel.setMaxWidth(Double.MAX_VALUE);
        cancel.setOnAction(e -> dlg.close());
        VBox.setMargin(primary, new Insets(4,0,0,0));
        VBox.setMargin(cancel,  new Insets(8,0,0,0));
    }

    private void showDialog(Stage dlg, VBox card, double w, double h) {
        StackPane root = new StackPane(card);
        root.setStyle("-fx-background-color:#F4F4F2;");
        dlg.setScene(new Scene(root, w, h));
        dlg.show();
    }

    private static String str(Object v) { return v == null ? "" : v.toString().trim(); }
}
