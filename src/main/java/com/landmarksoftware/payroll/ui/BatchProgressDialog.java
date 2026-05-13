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
package com.landmarksoftware.payroll.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Wave 2 reusable progress dialog — shows a progress bar + status label
 * for batch operations, with a Cancel button that signals the underlying
 * {@link Task}. Runs the {@code Task<R>} on a daemon executor.
 *
 * <p>Usage:
 * <pre>
 *   Task&lt;Integer&gt; t = new Task&lt;&gt;() {
 *       protected Integer call() {
 *           int n = items.size();
 *           for (int i = 0; i &lt; n; i++) {
 *               if (isCancelled()) break;
 *               // …work…
 *               updateProgress(i + 1, n);
 *               updateMessage("Processing " + items.get(i));
 *           }
 *           return n;
 *       }
 *   };
 *   BatchProgressDialog.runWithProgress(window, "Setting super %", t,
 *       rows -&gt; status("Done: " + rows + " rows updated", false),
 *       err  -&gt; status("Failed: " + err.getMessage(), true));
 * </pre>
 *
 * <p>The {@code onDone} callback fires on the JavaFX thread with the
 * task's return value; {@code onError} fires with the exception. Neither
 * fires on cancel — caller can detect cancellation via the task's
 * {@link Task#isCancelled()}.
 */
public final class BatchProgressDialog {

    private BatchProgressDialog() {}

    private static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "batch-progress");
        t.setDaemon(true);
        return t;
    });

    /**
     * Display the dialog and run {@code task} on a background thread.
     * The dialog closes automatically when the task finishes (success,
     * failure, or cancel). All callbacks fire on the JavaFX thread.
     */
    public static <R> void runWithProgress(Window owner, String title, Task<R> task,
                                            Consumer<R> onDone,
                                            Consumer<Throwable> onError) {
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setTitle(title);
        stage.setMinWidth(440);
        stage.setMinHeight(170);
        stage.setOnCloseRequest(e -> {
            // disable window-close (×) — user must use Cancel button so the
            // task gets a chance to react.
            if (task.isRunning()) e.consume();
        });

        Label lblTitle = new Label(title);
        lblTitle.setStyle("-fx-font-weight:bold;");

        ProgressBar bar = new ProgressBar();
        bar.setPrefWidth(420);
        bar.progressProperty().bind(task.progressProperty());

        Label lblStatus = new Label("Starting…");
        lblStatus.textProperty().bind(task.messageProperty());

        Button btnCancel = new Button("Cancel");
        btnCancel.setOnAction(e -> task.cancel());

        HBox btnBar = new HBox(btnCancel);
        btnBar.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(10, lblTitle, bar, lblStatus, btnBar);
        box.setPadding(new Insets(16));

        Scene scene = new Scene(box);
        scene.getStylesheets().add(
            BatchProgressDialog.class.getResource("/css/fixedassets.css").toExternalForm());
        stage.setScene(scene);

        task.setOnSucceeded(e -> {
            stage.close();
            if (onDone != null) onDone.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            stage.close();
            if (onError != null) onError.accept(task.getException());
        });
        task.setOnCancelled(e -> stage.close());

        EXEC.submit(task);
        stage.show();
    }

    /**
     * Convenience overload — fire-and-forget progress with logging only.
     */
    public static <R> void runWithProgress(Window owner, String title, Task<R> task) {
        runWithProgress(owner, title, task,
            r   -> {},
            err -> Platform.runLater(() -> {
                if (err != null) err.printStackTrace();
            }));
    }
}
