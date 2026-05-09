package com.example.fixedassets.ui;

import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Persists the user's favourite menu selections to a local file.
 *
 * Stores favourites as a simple properties file:
 *   %USERPROFILE%\.fixedassets\favourites.properties
 *
 * Format: programCode=true  (only entries set to true are written)
 */
@Component
public class FavouritesStore {

    private final Path storePath;
    private final Set<String> favourites = new LinkedHashSet<>();

    public FavouritesStore() {
        String home = System.getProperty("user.home");
        storePath = Paths.get(home, ".fixedassets", "favourites.properties");
        load();
    }

    public boolean isFavourite(String programCode) {
        return favourites.contains(programCode);
    }

    public void setFavourite(String programCode, boolean value) {
        if (value) favourites.add(programCode);
        else       favourites.remove(programCode);
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
                p.store(out, "Landmark — User Favourites");
            }
        } catch (IOException ignored) {}
    }
}
