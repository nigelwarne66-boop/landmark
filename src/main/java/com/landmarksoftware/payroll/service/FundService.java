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

import com.landmarksoftware.payroll.model.Fund;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * pafunds lookup — drives the USI / ESA dropdowns on PACD01 S2E (SUPER) and
 * S2H (CONTRIB). Selecting a fund auto-populates the pacodes fund_* fields.
 */
@Service
public class FundService {

    private final JdbcTemplate jdbc;

    public FundService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Search pafunds by type indicator and free-text filter.
     * @param fundTypeInd "A" for APRA (USI search) or "S" for SMSF (ESA search)
     * @param filter      partial match on fund_id, fund_name, or fund_abn (blank = list all)
     */
    public List<Fund> searchByType(int companyNo, String fundTypeInd, String filter) {
        String like = "%" + (filter == null ? "" : filter.trim()) + "%";
        return jdbc.query(
            PayrollSql.SEARCH_FUNDS,
            (rs, i) -> map(rs),
            companyNo, fundTypeInd, like, like, like);
    }

    private static Fund map(ResultSet rs) throws SQLException {
        Fund f = new Fund();
        f.apraSmsfFundInd = s(rs, "apra_smsf_fund_ind");
        f.fundId          = s(rs, "fund_id");
        f.smsfAbn         = s(rs, "smsf_abn");
        f.fundAbn         = s(rs, "fund_abn");
        f.fundName        = s(rs, "fund_name");
        f.fundName2       = s(rs, "fund_name_2");
        f.productName     = s(rs, "product_name");
        f.fundAddr1       = s(rs, "fund_addr_1");
        f.fundAddr2       = s(rs, "fund_addr_2");
        f.fundAddr3       = s(rs, "fund_addr_3");
        f.contactName     = s(rs, "contact_name");
        f.contactPhone    = s(rs, "contact_phone");
        f.contactEmail    = s(rs, "contact_email");
        f.bankCode        = s(rs, "bank_code");
        f.bankBsb         = s(rs, "bank_bsb");
        f.bankAcctNo      = s(rs, "bank_acct_no");
        f.acctName        = s(rs, "acct_name");
        f.employerNo      = s(rs, "employer_no");
        return f;
    }

    private static String s(ResultSet rs, String col) {
        try {
            String v = rs.getString(col);
            return v == null ? "" : v.trim();
        } catch (Exception e) { return ""; }
    }
}
