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

import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

/**
 * Vector reproduction of the Landmark Software logo, built from JavaFX
 * shape nodes — no raster images, no third-party SVG renderer.
 *
 * Source SVGs live alongside the project (landmark-logo.svg /
 * landmark-logo-icon.svg) as design reference; the shape coordinates here
 * mirror the path / circle / polygon / text data from those files exactly.
 *
 * Both factory methods return a {@link Group} with a single {@link Scale}
 * transform applied so the on-screen height matches the {@code height}
 * argument; width scales proportionally with the source viewBox.
 *
 * Usage:
 *   pane.getChildren().add(LandmarkLogo.iconMark(56));
 *   pane.getChildren().add(LandmarkLogo.fullLogo(120));
 */
public final class LandmarkLogo {

    /** Brand colours — matched to landmark-logo.svg. */
    public static final Color NAVY      = Color.web("#1A2744");
    public static final Color BLUE      = Color.web("#4D90D6");
    public static final Color BLUE_DARK = Color.web("#3878C0");

    private LandmarkLogo() {}

    // ── Public factories ─────────────────────────────────────────────────

    /** Pin mark only, scaled so its on-screen height matches {@code height}. */
    public static Node iconMark(double height) {
        return wrapAndScale(buildIconShapes(), height);
    }

    /**
     * Pin mark above the LANDMARK / SOFTWARE wordmark, scaled so the
     * combined on-screen height matches {@code height}.
     */
    public static Node fullLogo(double height) {
        return wrapAndScale(buildFullLogoShapes(), height);
    }

    // ── Shape builders (coordinates copied verbatim from source SVGs) ────

    private static Group buildIconShapes() {
        SVGPath pin = new SVGPath();
        pin.setContent(
            "M200 50 " +
            "A130 130 0 1 1 135 293 " +
            "Q167 355 200 400 " +
            "Q233 355 265 293 " +
            "A130 130 0 0 0 200 50 Z");
        pin.setFill(NAVY);

        Circle aperture = new Circle(200, 180, 90, Color.WHITE);

        Polygon arrowR = new Polygon(249, 131, 194, 256, 173, 207);
        arrowR.setFill(BLUE);
        Polygon arrowL = new Polygon(249, 131, 173, 207, 124, 186);
        arrowL.setFill(BLUE_DARK);

        return new Group(pin, aperture, arrowR, arrowL);
    }

    private static Group buildFullLogoShapes() {
        SVGPath pin = new SVGPath();
        pin.setContent(
            "M340 30 " +
            "A70 70 0 1 1 305 161 " +
            "Q322 195 340 215 " +
            "Q358 195 375 161 " +
            "A70 70 0 0 0 340 30 Z");
        pin.setFill(NAVY);

        Circle aperture = new Circle(340, 100, 48, Color.WHITE);

        Polygon arrowR = new Polygon(366, 74, 337, 140, 326, 114);
        arrowR.setFill(BLUE);
        Polygon arrowL = new Polygon(366, 74, 326, 114, 300, 103);
        arrowL.setFill(BLUE_DARK);

        Text wordmark = wordmarkText("LANDMARK", 340, 272, 44);
        Text submark  = wordmarkText("SOFTWARE", 340, 314, 19);

        return new Group(pin, aperture, arrowR, arrowL, wordmark, submark);
    }

    /**
     * Centre-aligned wordmark at SVG-coordinate (cx, y), baseline-anchored.
     * SVG letter-spacing is approximated by a single space between letters
     * (JavaFX Text has no native letter-spacing property).
     */
    private static Text wordmarkText(String content, double cx, double y, double fontSize) {
        String spaced = String.join(" ", content.split(""));
        Text t = new Text(spaced);
        t.setFont(Font.font("Helvetica Neue", FontWeight.NORMAL, fontSize));
        t.setFill(NAVY);
        t.setTextOrigin(VPos.BASELINE);
        t.setY(y);
        // applyCss() primes font metrics so getLayoutBounds is meaningful
        // before the node enters a scene — required to centre on cx.
        t.applyCss();
        double w = t.getLayoutBounds().getWidth();
        t.setX(cx - w / 2);
        return t;
    }

    /**
     * Translates the inner shapes so their bounding box top-left sits at
     * (0, 0), then scales to the requested height, and wraps the result in
     * an outer Group. The outer Group's layoutBounds reflects the SCALED
     * size (because Group bounds = union of children's boundsInParent,
     * which includes child transforms) — so HBox/VBox lay it out correctly
     * instead of allocating the unscaled SVG-coord size.
     */
    private static Node wrapAndScale(Group inner, double targetHeight) {
        Bounds bb = inner.getLayoutBounds();
        double scale = targetHeight / bb.getHeight();
        // Order in JavaFX transforms list is applied highest-index first.
        // We want: translate FIRST (move to origin), then scale.
        // So Scale at index 0, Translate at index 1.
        inner.getTransforms().add(new Scale(scale, scale));
        inner.getTransforms().add(new Translate(-bb.getMinX(), -bb.getMinY()));
        return new Group(inner);
    }
}
