package com.landmarksoftware.service;

/**
 * Centralised SQL constants for company/year session loading.
 *
 * Used by SessionService to populate AppSession from CPCOYCO and GLDATES
 * after the user picks a company and fiscal year.
 *
 * Tables: CPCOYCO, GLDATES.
 */
public final class SessionSql {

    private SessionSql() {}

    // ─── CPCOYCO (Company control) ──────────────────────────────────────

    /** FA tax-year-end month (1-12). Params: companyNo. */
    public static final String FIND_FA_TAX_YR_END_MTH =
        "SELECT fa_tax_yr_end_mth FROM CPCOYCO WHERE company_no=?";

    /** FA batch control flag ('Y'/'N'). Params: companyNo. */
    public static final String FIND_FA_BATCH_CONTROL_FLAG =
        "SELECT fa_batch_control_flag FROM CPCOYCO WHERE company_no=?";

    // ─── GLDATES (Fiscal year boundaries) ───────────────────────────────

    /** Year start/end dates for a (company, year). Params: companyNo, yearNo. */
    public static final String FIND_GLDATES_BY_COMPANY_AND_YEAR =
        "SELECT yr_start_date, yr_end_date FROM GLDATES " +
        "WHERE company_no=? AND year_no=?";

    /**
     * Most recent year_no for a company. Primary path used by
     * findCurrentYear(). Params: companyNo.
     */
    public static final String FIND_LATEST_YEAR_BY_COMPANY =
        "SELECT year_no FROM GLDATES " +
        "WHERE company_no=? ORDER BY year_no DESC LIMIT 1";

    /**
     * Most recent year_no, ignoring company. Fallback for installations
     * where GLDATES has no company_no column. No params.
     */
    public static final String FIND_LATEST_YEAR_NO_COMPANY =
        "SELECT year_no FROM GLDATES ORDER BY year_no DESC LIMIT 1";
}
