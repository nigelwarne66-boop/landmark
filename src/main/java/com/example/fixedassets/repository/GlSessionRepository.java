package com.example.fixedassets.repository;

import com.example.fixedassets.model.GlSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Replaces ACUCOBOL Vision file access for GLPASS.
 *
 * COBOL access pattern in FATL12/FATL13:
 *   MOVE TERMINAL-NUMBER-GRP TO GLPASS-KEY
 *   READ GLPASS
 *   → use GLPASS-COMPANY-NO, GLPASS-YR-NO, GLPASS-YEAR-NO,
 *         GLPASS-BATCH-NO, GLPASS-OPEN-BAL-DATE,
 *         GLPASS-FA-TAX-YR-END-MTH
 *
 * In a web/service architecture, GLPASS is replaced by the application's
 * authentication and session context. Two strategies are supported:
 *
 *   A) Database-backed (legacy parity):
 *      GLPASS records are migrated to gl_session table and looked up by
 *      session/terminal ID. Use findByTerminalNo().
 *
 *   B) Runtime-constructed (recommended for new architecture):
 *      Build GlSession from Spring Security principal + CompanyRepository.
 *      Use GlSessionFactory (see below) instead of this repository directly.
 *
 * SQL table: gl_session
 * Key column: terminal_no  (INTEGER, maps to GLPASS-TERMINAL-NO PIC 9(3))
 */
@Repository
public class GlSessionRepository {

    private final JdbcTemplate jdbc;

    public GlSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ────────────────────────────────────────────────────────────────────
    // Strategy A: database-backed lookup (GLPASS Vision → gl_session table)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Reads the session record for a terminal/session ID.
     *
     * Replaces: READ GLPASS using GLPASS-TERMINAL-NO
     *
     * @param terminalNo  numeric session/terminal identifier
     * @return Optional<GlSession> — empty if no active session found
     */
    public Optional<GlSession> findByTerminalNo(int terminalNo) {
        String sql = """
            SELECT terminal_no, company_no, yr_no, year_no, batch_no,
                   open_bal_date, fa_tax_yr_end_mth,
                   fa_det_summ_depn_ind, book_or_tax_ind
            FROM GLPASS
            WHERE terminal_no = ?
            """;

        List<GlSession> rows = jdbc.query(sql, (rs, n) -> {
            GlSession s = new GlSession();
            s.setTerminalNo(rs.getInt("terminal_no"));
            s.setCompanyNo(rs.getInt("company_no"));
            s.setYrNo(rs.getInt("yr_no"));
            s.setYearNo(rs.getInt("year_no"));
            s.setBatchNo(rs.getInt("batch_no"));
            Date d = rs.getDate("open_bal_date");
            s.setOpenBalDate(d == null ? null : d.toLocalDate());
            s.setFaTaxYrEndMth(rs.getInt("fa_tax_yr_end_mth"));
            s.setFaDetSummDepnInd(rs.getString("fa_det_summ_depn_ind"));
            s.setBookOrTaxInd(rs.getString("book_or_tax_ind"));
            return s;
        }, terminalNo);

        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ────────────────────────────────────────────────────────────────────
    // Strategy B: factory helper (recommended for new architecture)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Constructs a GlSession from explicit parameters, bypassing the
     * gl_session table entirely.
     *
     * Use this when the session context comes from Spring Security,
     * a JWT claim, or a web request parameter rather than the old
     * GLPASS Vision file.
     *
     * @param companyNo       company number from authenticated context
     * @param yearNo          4-digit fiscal year
     * @param batchNo         current batch number
     * @param openBalDate     opening balance date (may be null)
     * @param faTaxYrEndMth   from CompanyRepository.findByCompanyNo()
     * @return fully populated GlSession
     */
    public static GlSession buildSession(
            int companyNo, int yearNo, int batchNo,
            LocalDate openBalDate, int faTaxYrEndMth) {

        GlSession s = new GlSession();
        s.setCompanyNo(companyNo);
        s.setYearNo(yearNo);
        s.setYrNo(yearNo % 100);
        s.setBatchNo(batchNo);
        s.setOpenBalDate(openBalDate);
        s.setFaTaxYrEndMth(faTaxYrEndMth);
        return s;
    }
}
