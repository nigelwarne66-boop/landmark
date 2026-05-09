package com.example.fixedassets.service;

import com.example.fixedassets.model.SessionData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

/**
 * Company/year session loading for Landmark.
 *
 * Extracts the JDBC from MainMenuController.pushToAppSession() and
 * loadYearForSession() so that:
 *   - AppSession population is independently testable.
 *   - A future REST endpoint can call loadSessionData() directly
 *     without instantiating any JavaFX controller.
 *
 * Tables: CPCOYCO, GLDATES
 */
@Service
public class SessionService {

    private final JdbcTemplate jdbc;

    public SessionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Session data ──────────────────────────────────────────────────────

    /**
     * Load the three CPCOYCO/GLDATES values that AppSession needs for a
     * given company+year combination.
     *
     * Mirrors MainMenuController.pushToAppSession() DB section.
     * All reads are non-fatal — sensible defaults are used on failure.
     *
     * @param companyNo  the selected company
     * @param yearNo     4-digit year (e.g. 2025) from GLDATES.year_no
     */
    public SessionData loadSessionData(int companyNo, int yearNo) {
        int       faTaxYrEndMth  = loadFaTaxYrEndMth(companyNo);
        LocalDate yrStartDate    = null;
        LocalDate yrEndDate      = null;
        String    batchCtrlFlag  = loadBatchControlFlag(companyNo);

        try {
            Map<String, Object> yr = jdbc.queryForMap(
                "SELECT yr_start_date, yr_end_date FROM GLDATES " +
                "WHERE company_no=? AND year_no=?",
                companyNo, yearNo);
            Object sd = yr.get("yr_start_date");
            Object ed = yr.get("yr_end_date");
            if (sd instanceof java.sql.Date d) yrStartDate = d.toLocalDate();
            if (ed instanceof java.sql.Date d) yrEndDate   = d.toLocalDate();
        } catch (Exception e) {
            log("GLDATES date lookup failed (non-fatal): " + e.getMessage());
        }

        return new SessionData(faTaxYrEndMth, yrStartDate, yrEndDate, batchCtrlFlag);
    }

    private int loadFaTaxYrEndMth(int companyNo) {
        try {
            Integer v = jdbc.queryForObject(
                "SELECT fa_tax_yr_end_mth FROM CPCOYCO WHERE company_no=?",
                Integer.class, companyNo);
            return v != null ? v : 6;
        } catch (Exception e) {
            return 6; // default: June
        }
    }

    private String loadBatchControlFlag(int companyNo) {
        try {
            String v = jdbc.queryForObject(
                "SELECT fa_batch_control_flag FROM CPCOYCO WHERE company_no=?",
                String.class, companyNo);
            return v != null ? v : "Y";
        } catch (Exception e) {
            return "Y"; // default: batch control on
        }
    }

    // ── Year discovery ────────────────────────────────────────────────────

    /**
     * Find the most recent year_no for a company from GLDATES.
     *
     * Mirrors MainMenuController.loadYearForSession() including the
     * fallback to a company-less query for installations where GLDATES
     * has no company_no column.
     *
     * @param companyNo  the selected company
     * @return 4-digit year_no (e.g. 2025), or 0 if GLDATES is empty
     */
    public int findCurrentYear(int companyNo) {
        // Primary: query with company_no
        try {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT year_no FROM GLDATES " +
                "WHERE company_no=? ORDER BY year_no DESC LIMIT 1",
                companyNo);
            return ((Number) row.get("year_no")).intValue();
        } catch (Exception e) {
            log("findCurrentYear: company-scoped query failed — " + e.getMessage());
        }
        // Fallback: installations where GLDATES has no company_no column
        try {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT year_no FROM GLDATES ORDER BY year_no DESC LIMIT 1");
            return ((Number) row.get("year_no")).intValue();
        } catch (Exception e) {
            log("findCurrentYear: fallback also failed — " + e.getMessage());
            return 0;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static void log(String m) { System.out.println("SessionService: " + m); }
}
