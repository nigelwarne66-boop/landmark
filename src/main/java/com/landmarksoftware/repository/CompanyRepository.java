package com.landmarksoftware.repository;

import com.landmarksoftware.model.CompanyRow;
import com.landmarksoftware.service.FaSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Replaces ACUCOBOL Vision file access for CPCOYCO.
 *
 * COBOL access pattern in FATL12:
 *   READ CPCOYCO (by CPCOYCO-COMPANY-NO from GLPASS)
 *   Reads CPCOYCO-FA-TAX-YR-END-MTH to determine tax year boundary.
 *
 * Only the FA-relevant field is projected — the full CPCOYCO record is
 * enormous (AR/AP/GL/CM/PY module config) and irrelevant here.
 *
 * SQL table: cp_company
 * Key column: company_no  (INTEGER, maps to CPCOYCO-COMPANY-NO PIC 9(3))
 */
@Repository
public class CompanyRepository {

    private final JdbcTemplate jdbc;

    public CompanyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the FA tax year end month for the given company.
     *
     * Replaces: READ CPCOYCO using CPCOYCO-COMPANY-NO
     *           → access CPCOYCO-FA-TAX-YR-END-MTH
     *
     * @param companyNo  3-digit company number from GLPASS
     * @return Optional<CompanyRow> — empty if company not found
     */
    public Optional<CompanyRow> findByCompanyNo(int companyNo) {
        List<CompanyRow> rows = jdbc.query(FaSql.FIND_COMPANY_BY_PK, (rs, n) -> {
            CompanyRow c = new CompanyRow();
            c.setCompanyNo(rs.getInt("company_no"));
            c.setFaTaxYrEndMth(rs.getInt("fa_tax_yr_end_mth"));
            return c;
        }, companyNo);

        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
    /**
     * Returns all companies ordered by company_no.
     * Used to populate the company selector in the JavaFX UI.
     */
    public List<CompanyRow> findAll() {
        return jdbc.query(FaSql.FIND_ALL_COMPANIES, (rs, n) -> {
            CompanyRow c = new CompanyRow();
            c.setCompanyNo(rs.getInt("company_no"));
            c.setFaTaxYrEndMth(rs.getInt("fa_tax_yr_end_mth"));
            c.setName(rs.getString("name1"));
            return c;
        });
    }

}