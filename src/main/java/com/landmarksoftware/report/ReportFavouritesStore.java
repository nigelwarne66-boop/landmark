/*
 * Copyright (c) 2026 Landmark Software Pty Ltd.
 * All rights reserved.
 */
package com.landmarksoftware.report;

import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Persists the user's favourite reports for the Reports Hub.
 *
 * Stored at: %USERPROFILE%\.fixedassets\report-favourites.properties
 * Key format: "{moduleId}:{reportName}" (e.g. "fa:asset-register").
 * Same loose error policy as FavouritesStore — a missing or corrupt file
 * falls back to an empty set rather than blocking the hub.
 */
@Component
public class ReportFavouritesStore {

    private final Path storePath;
    private final Set<String> favourites = new LinkedHashSet<>();

    public ReportFavouritesStore() {
        String home = System.getProperty("user.home");
        storePath = Paths.get(home, ".fixedassets", "report-favourites.properties");
        load();
    }

    public boolean isFavourite(String key) { return favourites.contains(key); }

    public int count() { return favourites.size(); }

    public void toggle(String key) {
        if (favourites.contains(key)) favourites.remove(key);
        else                          favourites.add(key);
        save();
    }

    public Set<String> getFavourites() {
        return Collections.unmodifiableSet(favourites);
    }

    private void load() {
        if (!Files.exists(storePath)) return;
        try (InputStream in = Files.newInputStream(storePath)) {
            Properties p = new Properties();
            p.load(in);
            p.forEach((k, v) -> {
                if ("true".equalsIgnoreCase(v.toString()))
                    favourites.add(k.toString());
            });
        } catch (IOException ignored) {}
    }

    private void save() {
        try {
            Files.createDirectories(storePath.getParent());
            Properties p = new Properties();
            favourites.forEach(code -> p.setProperty(code, "true"));
            try (OutputStream out = Files.newOutputStream(storePath)) {
                p.store(out, "Landmark — Report Favourites");
            }
        } catch (IOException ignored) {}
    }
}
