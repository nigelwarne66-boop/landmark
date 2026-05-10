package com.landmarksoftware.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/**
 * Represents a single report/function entry in the navigation menu.
 */
public class MenuEntry {

    private final String   moduleCode;   // e.g. "FA", "GL", "AR"
    private final String   programCode;  // e.g. "FATL12"
    private final String   title;        // e.g. "Projected Depreciation"
    private final String   subtitle;     // e.g. "Extract + Export to Excel"
    private final boolean  available;    // false = placeholder (greyed out)
    private final Runnable action;       // what to do on click

    private final BooleanProperty favourite = new SimpleBooleanProperty(false);

    public MenuEntry(String moduleCode, String programCode,
                     String title, String subtitle,
                     boolean available, Runnable action) {
        this.moduleCode  = moduleCode;
        this.programCode = programCode;
        this.title       = title;
        this.subtitle    = subtitle;
        this.available   = available;
        this.action      = action;
    }

    // ── Convenience factory for placeholder entries ───────────────────
    public static MenuEntry placeholder(String moduleCode, String programCode,
                                        String title, String subtitle) {
        return new MenuEntry(moduleCode, programCode, title, subtitle, false, null);
    }

    // ── Getters ───────────────────────────────────────────────────────
    public String   getModuleCode()  { return moduleCode; }
    public String   getProgramCode() { return programCode; }
    public String   getTitle()       { return title; }
    public String   getSubtitle()    { return subtitle; }
    public boolean  isAvailable()    { return available; }
    public Runnable getAction()      { return action; }

    public BooleanProperty favouriteProperty() { return favourite; }
    public boolean isFavourite()               { return favourite.get(); }
    public void    setFavourite(boolean v)     { favourite.set(v); }

    public void toggleFavourite() { favourite.set(!favourite.get()); }
}
