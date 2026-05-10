package com.landmarksoftware.service;

/**
 * Centralised SQL constants for the live-search Lookup queries used by
 * the reusable LookupDialog UI component.
 *
 * Each constant is a parameterised SELECT against an FA code table or
 * the asset master, returning at most 200 rows for incremental search.
 *
 * Tables: faasset, facodlo, facodgr, facodsg, facoddt,
 *         facodss, facodin, facoddn.
 *
 * Naming follows the project-wide VERB_TARGET[_QUALIFIER] convention.
 */
public final class LookupSql {

    private LookupSql() {}

    // ─── faasset ────────────────────────────────────────────────────────

    /**
     * Search assets by asset_no / desc_1 / loc_code / grp_code (LIKE),
     * excluding retired ('R') and unposted ('U').
     * Params: companyNo, like, like, like, like.
     */
    public static final String SEARCH_ASSETS =
        "SELECT asset_no, desc_1, loc_code, grp_code FROM FAASSET " +
        "WHERE company_no=? AND asset_status NOT IN ('R','U') " +
        "AND (asset_no LIKE ? OR desc_1 LIKE ? OR loc_code LIKE ? OR grp_code LIKE ?) " +
        "ORDER BY asset_no LIMIT 200";

    // ─── facodlo (Locations) ────────────────────────────────────────────

    /** Search locations by code or description. Params: companyNo, like, like. */
    public static final String SEARCH_LOCATIONS =
        "SELECT loc_code, desc1 FROM FACODLO " +
        "WHERE company_no=? AND (loc_code LIKE ? OR desc1 LIKE ?) " +
        "ORDER BY loc_code LIMIT 200";

    // ─── facodgr (Groups) ───────────────────────────────────────────────

    /** Search groups by code or description. Params: companyNo, like, like. */
    public static final String SEARCH_GROUPS =
        "SELECT grp_code, desc1 FROM FACODGR " +
        "WHERE company_no=? AND (grp_code LIKE ? OR desc1 LIKE ?) " +
        "ORDER BY grp_code LIMIT 200";

    // ─── facodsg (Sub-groups) ───────────────────────────────────────────

    /** Search sub-groups by code or description. Params: companyNo, like, like. */
    public static final String SEARCH_SUBGROUPS =
        "SELECT subgrp_code, desc1 FROM FACODSG " +
        "WHERE company_no=? AND (subgrp_code LIKE ? OR desc1 LIKE ?) " +
        "ORDER BY subgrp_code LIMIT 200";

    // ─── facoddt (Departments) ──────────────────────────────────────────

    /** Search departments by code or description. Params: companyNo, like, like. */
    public static final String SEARCH_DEPARTMENTS =
        "SELECT dept_code, desc1 FROM FACODDT " +
        "WHERE company_no=? AND (dept_code LIKE ? OR desc1 LIKE ?) " +
        "ORDER BY dept_code LIMIT 200";

    // ─── facodss (Stocktake Sites) ──────────────────────────────────────

    /** Search stocktake sites by code or description. Params: companyNo, like, like. */
    public static final String SEARCH_STOCKTAKE_SITES =
        "SELECT stake_site_code, desc1 FROM FACODSS " +
        "WHERE company_no=? AND (stake_site_code LIKE ? OR desc1 LIKE ?) " +
        "ORDER BY stake_site_code LIMIT 200";

    // ─── facodin (Insurance Types) ──────────────────────────────────────

    /** Search insurance types by code or description. Params: companyNo, like, like. */
    public static final String SEARCH_INSURANCE_TYPES =
        "SELECT ins_type_code, desc1 FROM FACODIN " +
        "WHERE company_no=? AND (ins_type_code LIKE ? OR desc1 LIKE ?) " +
        "ORDER BY ins_type_code LIMIT 200";

    // ─── facoddn (Depreciation Codes) ───────────────────────────────────

    /** Search depreciation codes by code or description. Params: companyNo, like, like. */
    public static final String SEARCH_DEPN_CODES =
        "SELECT depn_code, desc1 FROM FACODDN " +
        "WHERE company_no=? AND (depn_code LIKE ? OR desc1 LIKE ?) " +
        "ORDER BY depn_code LIMIT 200";
}
