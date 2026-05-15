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

import com.landmarksoftware.payroll.model.CmBank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * cmbanks service — payroll-side reader. Only the ABA fields we need.
 * Cash Management has its own service for full CRUD.
 */
@Service
public class CmBanksService {

    private final JdbcTemplate jdbc;

    public CmBanksService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Find the bank account configured for payroll ABA — first cmbanks row
     * where {@code eft_pa_flag = 'Y'} and not inactive. Returns empty if no
     * payroll bank is set up.
     */
    public Optional<CmBank> findPayrollBank(int companyNo) {
        try {
            CmBank b = jdbc.queryForObject(
                "SELECT * FROM cmbanks " +
                "WHERE company_no=? AND eft_pa_flag='Y' " +
                "AND (inactive_flag IS NULL OR inactive_flag IN ('','N')) " +
                "ORDER BY bank_code LIMIT 1",
                (rs, i) -> map(rs),
                companyNo);
            return Optional.ofNullable(b);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Look up a specific bank by code — for when payrun pins to a bank. */
    public Optional<CmBank> findOne(int companyNo, String bankCode) {
        try {
            CmBank b = jdbc.queryForObject(
                "SELECT * FROM cmbanks WHERE company_no=? AND bank_code=?",
                (rs, i) -> map(rs),
                companyNo, bankCode);
            return Optional.ofNullable(b);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static CmBank map(ResultSet rs) throws SQLException {
        CmBank b = new CmBank();
        b.companyNo            = rs.getInt("company_no");
        b.bankCode             = nz(rs.getString("bank_code"));
        b.name                 = strSafe(rs, "name");
        b.branchNo             = strSafe(rs, "branch_no");
        b.bankAcctNo           = strSafe(rs, "bank_acct_no");
        b.inactiveFlag         = strSafe(rs, "inactive_flag");
        b.eftFlag              = strSafe(rs, "eft_flag");
        b.eftBankCode          = strSafe(rs, "eft_bank_code");
        b.eftName              = strSafe(rs, "eft_name");
        b.userNo               = safeInt(rs, "user_no");
        b.eftPaFlag            = strSafe(rs, "eft_pa_flag");
        b.eftPaCashbookEntry   = strSafe(rs, "eft_pa_cashbook_entry");
        b.eftPaTfrFilename     = strSafe(rs, "eft_pa_tfr_filename");
        b.paySrvBankInd        = strSafe(rs, "pay_serv_bank_ind");
        b.paySrvRemitterName   = strSafe(rs, "pay_serv_remitter_name");
        return b;
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String strSafe(ResultSet rs, String col) {
        try { return nz(rs.getString(col)); }
        catch (SQLException e) { return ""; }
    }
    private static int safeInt(ResultSet rs, String col) {
        try { return rs.getInt(col); }
        catch (SQLException e) { return 0; }
    }
}
