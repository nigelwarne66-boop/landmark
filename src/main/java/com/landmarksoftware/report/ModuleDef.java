package com.landmarksoftware.report;

import java.util.List;

/**
 * One row in the Reports Hub left panel.
 * id must match the module slug used in service/export class naming conventions.
 */
public class ModuleDef {

    private final String          id;      // e.g. "fa", "gl", "py"
    private final String          label;   // e.g. "Fixed Assets"
    private final String          badge;   // optional — "new" or null
    private final List<ReportDef> reports;

    public ModuleDef(String id, String label, String badge, List<ReportDef> reports) {
        this.id      = id;
        this.label   = label;
        this.badge   = badge;
        this.reports = reports;
    }

    public ModuleDef(String id, String label, List<ReportDef> reports) {
        this(id, label, null, reports);
    }

    public String          getId()          { return id; }
    public String          getLabel()       { return label; }
    public String          getBadge()       { return badge; }
    public boolean         hasBadge()       { return badge != null && !badge.isBlank(); }
    public List<ReportDef> getReports()     { return reports; }
    public int             getReportCount() { return reports.size(); }
}
