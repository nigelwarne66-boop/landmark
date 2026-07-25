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

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Persists the navigation rail's collapsed/expanded state — DESIGN_SYSTEM.md
 * §5.2, "Persist the choice per user" — following the same loose-error-policy
 * pattern as {@code com.landmarksoftware.ui.LastSessionStore} /
 * {@code FavouritesStore} (missing/corrupt file → fall back to the default,
 * never block startup).
 *
 * <p>Deliberately its own file ({@code shellstate.properties}) rather than
 * reusing {@code session.properties} or {@code favourites.properties} — both
 * of those stores do a full overwrite-on-save with only their own key(s), so
 * sharing a file would silently drop the other store's data on every save.
 */
@Component
public class RailStateStore {

    private static final String KEY_COLLAPSED = "railCollapsed";

    private final Path storePath;
    private boolean collapsed = false;

    public RailStateStore() {
        String home = System.getProperty("user.home");
        storePath = Paths.get(home, ".fixedassets", "shellstate.properties");
        load();
    }

    public boolean isCollapsed() { return collapsed; }

    /** Record a fresh collapse-toggle choice. Silently no-ops on disk failure. */
    public void setCollapsed(boolean value) {
        this.collapsed = value;
        try {
            Files.createDirectories(storePath.getParent());
            Properties p = new Properties();
            p.setProperty(KEY_COLLAPSED, Boolean.toString(value));
            try (OutputStream out = Files.newOutputStream(storePath)) {
                p.store(out, "Landmark — shell UI state");
            }
        } catch (IOException ignored) {
            // Persistence is a convenience — never block the UI.
        }
    }

    private void load() {
        if (!Files.exists(storePath)) return;
        try (InputStream in = Files.newInputStream(storePath)) {
            Properties p = new Properties();
            p.load(in);
            collapsed = Boolean.parseBoolean(p.getProperty(KEY_COLLAPSED, "false"));
        } catch (IOException ignored) {
            collapsed = false;
        }
    }
}
