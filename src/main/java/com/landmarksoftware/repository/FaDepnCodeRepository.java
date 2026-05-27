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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * FACODDN depreciation-code lookup (READ-FACODDN in fatl12.pl).
 *
 * When an asset carries a depreciation code (FAASSET-BOOK/TAX-DEPN-CODE not
 * spaces), FATL12 reads FACODDN to obtain the straight-line and diminishing
 * rates plus the calculation indicator / base for the chosen stream. The
 * method ('S' vs 'D') then selects which rate applies.
 *
 * <p>Note: in some extracts FACODDN is unpopulated — callers must tolerate an
 * empty Optional (COBOL leaves the rate zero when the code is not found).
 */
@Repository
public class FaDepnCodeRepository {

    private static final String FIND_BY_CODE = """
            SELECT book_str_line_rate, book_dimin_rate, book_calc_ind, book_calc_base,
                   tax_str_line_rate,  tax_dimin_rate,  tax_calc_ind,  tax_calc_base
            FROM facoddn
            WHERE company_no = ? AND depn_code = ?
            """;

    /** Straight-line + diminishing rate, calc indicator and base for one stream. */
    public record DepnCodeRates(
            BigDecimal strLineRate, BigDecimal diminRate, String calcInd, String calcBase) {}

    private final JdbcTemplate jdbc;

    public FaDepnCodeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Looks up the rates/indicators for a depreciation code and stream.
     *
     * @param stream 'T' for tax columns, otherwise the book columns
     * @return empty when the code is not on FACODDN
     */
    public Optional<DepnCodeRates> find(int companyNo, String depnCode, char stream) {
        if (depnCode == null || depnCode.isBlank()) return Optional.empty();
        try {
            return jdbc.query(FIND_BY_CODE, rs -> {
                if (!rs.next()) return Optional.<DepnCodeRates>empty();
                boolean tax = stream == 'T';
                return Optional.of(new DepnCodeRates(
                        rs.getBigDecimal(tax ? "tax_str_line_rate" : "book_str_line_rate"),
                        rs.getBigDecimal(tax ? "tax_dimin_rate"    : "book_dimin_rate"),
                        rs.getString(tax ? "tax_calc_ind"  : "book_calc_ind"),
                        rs.getString(tax ? "tax_calc_base" : "book_calc_base")));
            }, companyNo, depnCode.trim());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
