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
package com.landmarksoftware.repository;

import com.landmarksoftware.model.GlYear;
import com.landmarksoftware.service.FaSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Replaces ACUCOBOL Vision file access for GLDATES.
 *
 * Actual column names confirmed from DESCRIBE GLDATES:
 *   period_end_01 .. period_end_13   (zero-padded, not period_end_1)
 *   period_start_01 .. period_start_13
 *   period_status_01 .. period_status_13
 *   unit_01 .. unit_13
 *   Key: company_no + yr_no (composite primary key)
 */
@Repository
public class GlDateRepository {

    private final JdbcTemplate jdbc;

    public GlDateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Find by company + 4-digit year ───────────────────────────────

    public Optional<GlYear> findByCompanyAndYearNo(int companyNo, int yearNo) {
        List<GlYear> rows = jdbc.query(
            FaSql.FIND_GL_YEAR_BY_PK, new GlYearMapper(), companyNo, yearNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ── Find all years for a company ──────────────────────────────────

    public List<GlYear> findAllByCompany(int companyNo) {
        return jdbc.query(
            FaSql.FIND_ALL_GL_YEARS_BY_COMPANY, new GlYearMapper(), companyNo);
    }

    // ── Validate projection date against period-end dates ─────────────

    /**
     * Returns true if the given date matches any period_end_01..13
     * for the given company. Used to validate the user-entered projection date.
     */
    public boolean isPeriodEndDate(int companyNo, LocalDate date) {
        Object[] params = new Object[14];
        params[0] = companyNo;
        java.sql.Date sqlDate = java.sql.Date.valueOf(date);
        for (int i = 1; i <= 13; i++) params[i] = sqlDate;

        Integer count = jdbc.queryForObject(
            FaSql.COUNT_GLDATES_PERIOD_END_MATCH_BY_COMPANY, Integer.class, params);
        return count != null && count > 0;
    }

    // ── Overload without companyNo for backward compatibility ─────────

    public boolean isPeriodEndDate(LocalDate date) {
        Object[] params = new Object[13];
        java.sql.Date sqlDate = java.sql.Date.valueOf(date);
        for (int i = 0; i < 13; i++) params[i] = sqlDate;

        Integer count = jdbc.queryForObject(
            FaSql.COUNT_GLDATES_PERIOD_END_MATCH, Integer.class, params);
        return count != null && count > 0;
    }

    // ── RowMapper ─────────────────────────────────────────────────────

    private static class GlYearMapper implements RowMapper<GlYear> {

        @Override
        public GlYear mapRow(ResultSet rs, int rowNum) throws SQLException {
            GlYear g = new GlYear();

            g.setYearNo(rs.getInt("year_no"));
            g.setYrStartDate(toLocalDate(rs, "yr_start_date"));
            g.setYrEndDate(toLocalDate(rs, "yr_end_date"));
            g.setYrEndStatus(rs.getInt("yr_end_status"));

            // period_end_01 .. period_end_13  (zero-padded column names)
            for (int p = 1; p <= 13; p++) {
                String col = String.format("period_end_%02d", p);
                LocalDate pe = toLocalDate(rs, col);
                if (pe != null) g.setPeriodEnd(p, pe);

                String startCol = String.format("period_start_%02d", p);
                LocalDate ps = toLocalDate(rs, startCol);
                if (ps != null) g.setPeriodStart(p, ps);

                String unitCol = String.format("unit_%02d", p);
                double u = rs.getDouble(unitCol);
                if (!rs.wasNull()) g.setUnit(p, u);

                String statusCol = String.format("period_status_%02d", p);
                g.setPeriodStatus(p, rs.getString(statusCol));
            }

            return g;
        }

        private LocalDate toLocalDate(ResultSet rs, String col) throws SQLException {
            java.sql.Date d = rs.getDate(col);
            return d == null ? null : d.toLocalDate();
        }
    }
}
