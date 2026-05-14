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

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Persists the user's last-selected company + year (MENU23 picks) to
 * {@code %USERPROFILE%\.fixedassets\session.properties} so the next login
 * resumes where they left off.
 *
 * <p>Stored alongside {@link FavouritesStore}'s {@code favourites.properties}.
 * Same loose error policy: a missing or corrupt file falls back to defaults
 * rather than blocking startup.
 *
 * <p>Keys:
 * <ul>
 *   <li>{@code lastCompanyNo} — integer company_no, or 0/absent if unset.</li>
 *   <li>{@code lastYearNo}    — 4-digit year_no (e.g. 2025), or 0/absent.</li>
 * </ul>
 */
@Component
public class LastSessionStore {

    private static final String KEY_COMPANY = "lastCompanyNo";
    private static final String KEY_YEAR    = "lastYearNo";

    private final Path storePath;
    private int lastCompanyNo = 0;
    private int lastYearNo    = 0;

    public LastSessionStore() {
        String home = System.getProperty("user.home");
        storePath = Paths.get(home, ".fixedassets", "session.properties");
        load();
    }

    public int getLastCompanyNo() { return lastCompanyNo; }
    public int getLastYearNo()    { return lastYearNo;    }
    public boolean hasLast()      { return lastCompanyNo > 0; }

    /** Record a fresh MENU23 selection. Silently no-ops on disk failure. */
    public void save(int companyNo, int yearNo) {
        this.lastCompanyNo = companyNo;
        this.lastYearNo    = yearNo;
        try {
            Files.createDirectories(storePath.getParent());
            Properties p = new Properties();
            p.setProperty(KEY_COMPANY, Integer.toString(companyNo));
            p.setProperty(KEY_YEAR,    Integer.toString(yearNo));
            try (OutputStream out = Files.newOutputStream(storePath)) {
                p.store(out, "Landmark — last selected company/year");
            }
        } catch (IOException ignored) {
            // Persistence is a convenience — never block the session change.
        }
    }

    private void load() {
        if (!Files.exists(storePath)) return;
        try (InputStream in = Files.newInputStream(storePath)) {
            Properties p = new Properties();
            p.load(in);
            lastCompanyNo = parseInt(p.getProperty(KEY_COMPANY));
            lastYearNo    = parseInt(p.getProperty(KEY_YEAR));
        } catch (IOException ignored) {
            lastCompanyNo = 0;
            lastYearNo    = 0;
        }
    }

    private static int parseInt(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }
}
