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
import java.util.ArrayList;
import java.util.List;

/**
 * Vector reproduction of the Landmark Software logo, built from JavaFX
 * shape nodes — no raster images, no third-party SVG renderer.
 *
 * <p>Canonical source files live at {@code C:\landmark\JavaDev\}:
 * {@code landmark-logo-icon.svg} (pin mark only, viewBox 264×310) and
 * {@code landmark-logo.svg} (pin mark + LANDMARK / SOFTWARE wordmark,
 * viewBox 680×400). The path / circle / polygon / text coordinates here
 * are copied verbatim from those SVGs so the rendered output matches
 * the design files pixel-for-pixel at every scale. If the SVGs are
 * revised, re-sync this class.
 *
 * <p>Both factory methods return a {@link Group} with a single {@link Scale}
 * transform applied so the on-screen height matches the {@code height}
 * argument; width scales proportionally with the source viewBox.
 *
 * Usage:
 *   pane.getChildren().add(LandmarkLogo.iconMark(56));
 *   pane.getChildren().add(LandmarkLogo.fullLogo(120));
 */
public final class LandmarkLogo {

    /** Brand colours — matched verbatim to landmark-logo.svg / landmark-logo-icon.svg. */
    public static final Color NAVY      = Color.web("#1C2B4A");
    public static final Color BLUE      = Color.web("#4D90D6");
    public static final Color BLUE_DARK = Color.web("#3574BF");

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

    /**
     * From landmark-logo-icon.svg (viewBox 0 0 264 310).
     * Pin: straight tangent lines from (66,102)→(132,254)→(198,102), arc back over.
     */
    private static Group buildIconShapes() {
        SVGPath pin = new SVGPath();
        pin.setContent("M 198,102 L 132,254 L 66,102 A 72,72 0 1,1 198,102 Z");
        pin.setFill(NAVY);

        Circle aperture = new Circle(132, 72, 52, Color.WHITE);

        Polygon arrowR = new Polygon(154, 46, 124, 114, 111, 85);
        arrowR.setFill(BLUE);
        Polygon arrowL = new Polygon(154, 46, 111, 85, 88, 74);
        arrowL.setFill(BLUE_DARK);

        return new Group(pin, aperture, arrowR, arrowL);
    }

    /**
     * From landmark-logo.svg (viewBox 0 0 680 400). Same pin geometry as
     * the icon, translated to the wordmark's centre line (cx = 340), with
     * LANDMARK + SOFTWARE wordmarks beneath.
     */
    private static Group buildFullLogoShapes() {
        SVGPath pin = new SVGPath();
        pin.setContent("M 406,120 L 340,272 L 274,120 A 72,72 0 1,1 406,120 Z");
        pin.setFill(NAVY);

        Circle aperture = new Circle(340, 90, 52, Color.WHITE);

        Polygon arrowR = new Polygon(362, 64, 332, 132, 319, 103);
        arrowR.setFill(BLUE);
        Polygon arrowL = new Polygon(362, 64, 319, 103, 296, 92);
        arrowL.setFill(BLUE_DARK);

        // SVG: font-size 46, font-weight 300, letter-spacing 12, text-anchor middle, y=316
        Node wordmark = spacedWordmark("LANDMARK", 340, 316, 46, 12, FontWeight.LIGHT);
        // SVG: font-size 19, font-weight 300, letter-spacing 9, text-anchor middle, y=358
        Node submark  = spacedWordmark("SOFTWARE", 340, 358, 19,  9, FontWeight.LIGHT);

        return new Group(pin, aperture, arrowR, arrowL, wordmark, submark);
    }

    /**
     * SVG-style letter-spaced wordmark, centred on {@code cx} and baseline-anchored at
     * {@code y}. JavaFX {@link Text} has no native letter-spacing property, so we lay
     * each glyph out individually at incremental x positions — that matches SVG
     * {@code letter-spacing} semantics (extra space inserted between adjacent letters).
     */
    private static Group spacedWordmark(String content, double cx, double y,
                                        double fontSize, double letterSpacing,
                                        FontWeight weight) {
        Font font = Font.font("Helvetica Neue", weight, fontSize);
        List<Text> glyphs = new ArrayList<>(content.length());
        double total = 0;
        for (int i = 0; i < content.length(); i++) {
            Text t = new Text(String.valueOf(content.charAt(i)));
            t.setFont(font);
            t.setFill(NAVY);
            t.setTextOrigin(VPos.BASELINE);
            // applyCss() primes font metrics so getLayoutBounds is meaningful
            // before the node enters a scene — required to position each glyph.
            t.applyCss();
            glyphs.add(t);
            total += t.getLayoutBounds().getWidth();
        }
        total += letterSpacing * (content.length() - 1);

        double x = cx - total / 2;
        for (Text t : glyphs) {
            t.setX(x);
            t.setY(y);
            x += t.getLayoutBounds().getWidth() + letterSpacing;
        }
        return new Group(glyphs.toArray(new Node[0]));
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
