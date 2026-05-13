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
package com.landmarksoftware.payroll.service;

import com.landmarksoftware.payroll.model.TaxBracket;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Persistence for ATO tax-scale brackets.
 *
 * <p>The {@code tax_brackets} table is auto-created on startup if missing.
 * This is a Java-managed reference table — it is NOT part of the COBOL
 * extract pipeline; rows come from {@link TaxBracketLoader} reading the
 * annual ATO Excel files ({@code NAT_1004.xlsx}, {@code NAT_3539.xlsx}).
 *
 * <p>Update cadence: annually, when the ATO publishes new tax tables
 * (usually 1 July) or whenever HELP/STSL coefficients change mid-year
 * (which is why NAT_3539 has its own effective-from date).
 */
@Service
public class TaxBracketService {

    private final JdbcTemplate jdbc;

    public TaxBracketService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    void ensureTable() {
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS tax_brackets (" +
            "    company_no       INT           NOT NULL," +
            "    source_file      VARCHAR(20)   NOT NULL," +
            "    effective_from   DATE          NOT NULL," +
            "    scale_no         VARCHAR(2)    NOT NULL," +
            "    bracket_no       INT           NOT NULL," +
            "    upper_earnings   DECIMAL(10,2) NOT NULL," +
            "    coeff_a          DECIMAL(10,6) NOT NULL," +
            "    coeff_b          DECIMAL(10,4) NOT NULL," +
            "    audit_loaded_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "    audit_loaded_by  VARCHAR(64)   NOT NULL DEFAULT ''," +
            "    PRIMARY KEY (company_no, source_file, effective_from, scale_no, bracket_no)" +
            ")");
    }

    /**
     * Replace all brackets for (companyNo, sourceFile, effectiveFrom) — used
     * when loading a fresh ATO publication. Ensures we never have orphan rows
     * from a previous half-loaded run.
     */
    @Transactional
    public int replaceForPublication(int companyNo, String sourceFile,
                                      LocalDate effectiveFrom,
                                      List<TaxBracket> brackets,
                                      String loadedBy) {
        jdbc.update(
            "DELETE FROM tax_brackets WHERE company_no=? AND source_file=? AND effective_from=?",
            companyNo, sourceFile, java.sql.Date.valueOf(effectiveFrom));

        int n = 0;
        for (TaxBracket b : brackets) {
            jdbc.update(
                "INSERT INTO tax_brackets " +
                "(company_no, source_file, effective_from, scale_no, bracket_no, " +
                " upper_earnings, coeff_a, coeff_b, audit_loaded_by) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
                companyNo, sourceFile, java.sql.Date.valueOf(effectiveFrom),
                b.scaleNo, b.bracketNo,
                b.upperEarnings, b.coeffA, b.coeffB,
                loadedBy == null ? "" : loadedBy);
            n++;
        }
        return n;
    }

    /** Find the latest publication of a source file for the company, or null. */
    public LocalDate findLatestEffectiveFrom(int companyNo, String sourceFile) {
        return jdbc.query(
            "SELECT MAX(effective_from) FROM tax_brackets " +
            "WHERE company_no=? AND source_file=?",
            rs -> rs.next() ? rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate() : null,
            companyNo, sourceFile);
    }

    /**
     * Latest publication that was in effect on {@code asOf} — i.e. the largest
     * {@code effective_from} ≤ {@code asOf}. Returns null if no publication is
     * yet in force (e.g. payrun-date precedes any loaded ATO publication).
     *
     * <p>Used by {@link PaygTaxCalculator} so historical / back-dated pay runs
     * resolve to the bracket coefficients that applied at the time.
     */
    public LocalDate findEffectiveFromOnOrBefore(int companyNo, String sourceFile,
                                                  LocalDate asOf) {
        return jdbc.query(
            "SELECT MAX(effective_from) FROM tax_brackets " +
            "WHERE company_no=? AND source_file=? AND effective_from<=?",
            rs -> rs.next() ? rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate() : null,
            companyNo, sourceFile, java.sql.Date.valueOf(asOf));
    }

    /** Count brackets loaded for a publication (zero = not loaded yet). */
    public int countBrackets(int companyNo, String sourceFile, LocalDate effectiveFrom) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tax_brackets " +
            "WHERE company_no=? AND source_file=? AND effective_from=?",
            Integer.class,
            companyNo, sourceFile, java.sql.Date.valueOf(effectiveFrom));
        return n == null ? 0 : n;
    }

    /**
     * Look up the bracket that applies to the given weekly earnings for a
     * scale. Returns the bracket whose {@code upper_earnings} is the
     * smallest value ≥ {@code earnings}. Returns null if no bracket matches.
     */
    public TaxBracket findApplicableBracket(int companyNo, String sourceFile,
                                             LocalDate effectiveFrom,
                                             String scaleNo,
                                             BigDecimal earnings) {
        List<TaxBracket> rows = jdbc.query(
            "SELECT scale_no, bracket_no, upper_earnings, coeff_a, coeff_b " +
            "FROM tax_brackets " +
            "WHERE company_no=? AND source_file=? AND effective_from=? AND scale_no=? " +
            "AND upper_earnings >= ? " +
            "ORDER BY upper_earnings ASC LIMIT 1",
            (rs, i) -> {
                TaxBracket b = new TaxBracket();
                b.companyNo      = companyNo;
                b.sourceFile     = sourceFile;
                b.effectiveFrom  = effectiveFrom;
                b.scaleNo        = rs.getString("scale_no");
                b.bracketNo      = rs.getInt("bracket_no");
                b.upperEarnings  = rs.getBigDecimal("upper_earnings");
                b.coeffA         = rs.getBigDecimal("coeff_a");
                b.coeffB         = rs.getBigDecimal("coeff_b");
                return b;
            },
            companyNo, sourceFile, java.sql.Date.valueOf(effectiveFrom),
            scaleNo, earnings);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
