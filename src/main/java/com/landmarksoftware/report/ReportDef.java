package com.landmarksoftware.report;

import java.util.function.Consumer;

/**
 * Describes one report in the hub.
 * formats are no longer stored here — the selection screen owns that choice.
 */
public class ReportDef {

    private final String       name;
    private final String       label;
    private final String       description;
    private final String       iconLiteral;   // Tabler icon, e.g. "ti-building-warehouse"
    private Consumer<String>   runner;        // Consumer<"pdf"|"excel"> — wired in controller

    private ReportDef(String name, String label, String description, String iconLiteral) {
        this.name        = name;
        this.label       = label;
        this.description = description;
        this.iconLiteral = iconLiteral;
    }

    /** All reports now go through a params screen, so withParams is the only factory. */
    public static ReportDef withParams(String name, String label,
                                       String description, String iconLiteral) {
        return new ReportDef(name, label, description, iconLiteral);
    }

    public String          getName()        { return name; }
    public String          getLabel()       { return label; }
    public String          getDescription() { return description; }
    public String          getIconLiteral() { return iconLiteral; }
    public Consumer<String> getRunner()     { return runner; }
    public void setRunner(Consumer<String> runner) { this.runner = runner; }
}
