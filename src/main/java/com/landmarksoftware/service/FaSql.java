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
package com.landmarksoftware.service;

/**
 * Centralised SQL constants for the Fixed Assets module.
 *
 * Each public constant is a complete (or static-prefix of a)
 * parameterised SQL statement that an FA service passes to JdbcTemplate.
 *
 * Naming follows the project-wide VERB_TARGET[_QUALIFIER] convention.
 * Constants ending in _PREFIX are intentionally incomplete — services
 * append conditional WHERE/ORDER fragments at runtime. The Javadoc on
 * each prefix lists what the caller must append.
 *
 * NOT extracted (and intentionally left inline in the services):
 *   - Template queries with runtime table or column substitution
 *     (e.g. AcquisitionService.lookupDesc / validateCode / lookupDepnRate).
 *     These can't be expressed as a single static String.
 *
 * Tables covered: faasset, fatrxin, fatrans, cmbatch, cpcoyco,
 *                 facodlo, facodgr, facodsg, facoddt, facoddn, apsupps.
 */
public final class FaSql {

    private FaSql() {}

    // ════════════════════════════════════════════════════════════════════
    // FATRANS — Posted asset transactions
    // ════════════════════════════════════════════════════════════════════

    /**
     * Static prefix for FATL02 transaction-list query.
     * Caller appends a parameterised IN-list for trx_type, optional
     * date filters, and an ORDER BY.
     * Mandatory params for the prefix: companyNo, assetNo.
     */
    public static final String SELECT_FATRANS_FOR_ASSET_PREFIX = """
            SELECT t.company_no, t.asset_no, t.trx_type, t.trx_date, t.batch_no,
                   t.ref, t.trx_status,
                   t.acqn_actual_cost, t.acqn_book_depn_cost, t.acqn_tax_depn_cost,
                   t.acqn_loc, t.acqn_dept, t.acqn_grp, t.acqn_subgrp,
                   t.depn_method, t.depn_code, t.depn_freq, t.depn_rate,
                   t.depn_amt, t.depn_thru_to_date,
                   t.depn_loc, t.depn_dept, t.depn_grp, t.depn_subgrp,
                   t.depn_adj_amt, t.depn_adj_loc, t.depn_adj_dept,
                   t.depn_adj_grp, t.depn_adj_subgrp,
                   t.reval_val, t.reval_adj_amt, t.reval_accum_depn,
                   t.reval_loc, t.reval_dept, t.reval_grp, t.reval_subgrp,
                   t.tfr_from_loc, t.tfr_from_dept, t.tfr_from_grp, t.tfr_from_subgrp,
                   t.tfr_to_loc, t.tfr_to_dept, t.tfr_to_grp, t.tfr_to_subgrp,
                   t.tfr_book_depn_cost, t.tfr_net_reval_amt, t.tfr_prov_for_depn_amt,
                   t.retmt_bk_profit_amt, t.retmt_tx_profit_amt, t.retmt_proceeds_amt,
                   t.retmt_loc, t.retmt_dept, t.retmt_grp, t.retmt_subgrp,
                   t.audit_user_id, t.audit_date,
                   t.audit_time_hr, t.audit_time_min, t.audit_time_sec
            FROM FATRANS t
            WHERE t.company_no = ?
              AND t.asset_no   = ?
              AND t.trx_status = ''
              AND t.trx_type  IN (""";

    // ════════════════════════════════════════════════════════════════════
    // FAASSET — Asset master
    // ════════════════════════════════════════════════════════════════════

    /**
     * Static prefix for FATL02 asset selection.
     * Caller appends conditional filters (loc/grp/subgrp/dept/leased/pooled)
     * and ORDER BY.
     * Mandatory params for the prefix: companyNo, startAssetNo, endAssetNo.
     */
    public static final String SELECT_ASSETS_FOR_TRX_LIST_PREFIX = """
            SELECT asset_no, desc_1, loc_code, dept_code, grp_code, subgrp_code,
                   asset_status, acqn_date, leased_asset_flag, asset_pool_flag,
                   actual_cost, book_depn_cost, tax_depn_cost
            FROM FAASSET
            WHERE company_no = ?
              AND asset_no  >= ?
              AND asset_no  <= ?
            """;

    /**
     * Static prefix for FATL03 (Acquired & Retired) asset selection.
     * Caller appends conditional filters (loc/grp/subgrp/dept/leased/pooled),
     * the A/R-mode date-range subclause, and ORDER BY.
     * Mandatory params for the prefix: companyNo, startAssetNo, endAssetNo.
     */
    public static final String SELECT_ACQUIRED_RETIRED_ASSETS_PREFIX = """
            SELECT asset_no, desc_1, desc_2, loc_code, dept_code, grp_code, subgrp_code,
                   asset_status, asset_pool_flag, leased_asset_flag,
                   acqn_date, retmt_date,
                   book_depn_cost, last_reval_val,
                   accum_book_depn, accum_book_depn_adj, last_book_depn_date,
                   tax_depn_cost, last_tax_reval_val,
                   accum_tax_depn, accum_tax_depn_adj, last_tax_depn_date,
                   retmt_proceeds_val
            FROM FAASSET
            WHERE company_no = ?
              AND asset_no >= ?
              AND asset_no <= ?
            """;

    /**
     * Static prefix for FATL03 pooled-asset accumulation read.
     * Caller appends optional date-range filter and ORDER BY.
     * Mandatory params for the prefix: companyNo, assetNo.
     */
    public static final String SELECT_FATRANS_POOLED_ACCUM_PREFIX = """
            SELECT trx_type, trx_date,
                   acqn_book_depn_cost, acqn_tax_depn_cost,
                   depn_amt,
                   retmt_proceeds_amt, retmt_bk_profit_amt, retmt_tx_profit_amt
            FROM FATRANS
            WHERE company_no = ?
              AND asset_no   = ?
              AND trx_status = ''
              AND trx_date > '1900-01-01'
            """;

    /**
     * Static prefix for FATL03 pooled-asset detail read (single trx_type).
     * Caller appends optional date-range filter and ORDER BY.
     * Mandatory params for the prefix: companyNo, assetNo, trxType.
     */
    public static final String SELECT_FATRANS_POOLED_DETAIL_PREFIX = """
            SELECT trx_type, trx_date,
                   acqn_book_depn_cost, acqn_tax_depn_cost,
                   retmt_proceeds_amt, retmt_bk_profit_amt, retmt_tx_profit_amt
            FROM FATRANS
            WHERE company_no = ?
              AND asset_no   = ?
              AND trx_type   = ?
              AND trx_status = ''
              AND trx_date > '1900-01-01'
            """;

    /**
     * Read all FATRANS for an asset (FATL10 calcAccumDepn).
     * Params: companyNo, assetNo.
     */
    public static final String FIND_FATRANS_FOR_ASSET = """
            SELECT trx_type, trx_date, trx_status,
                   depn_amt, depn_adj_amt, depn_thru_to_date,
                   retmt_proceeds_amt, retmt_bk_profit_amt, retmt_tx_profit_amt
            FROM FATRANS
            WHERE company_no = ?
              AND asset_no   = ?
            ORDER BY trx_date, trx_type
            """;

    /**
     * Read depreciation method/code/freq/rate columns for one asset
     * (FATL10 applyDepnDetails). Params: companyNo, assetNo.
     */
    public static final String FIND_DEPN_DETAILS_BY_ASSET = """
            SELECT book_depn_method, book_depn_code, book_depn_freq,
                   book_rate_1, book_rate_2, start_depn_date,
                   tax_depn_method, tax_depn_code, tax_depn_freq,
                   tax_rate_1, tax_rate_2, start_tax_depn_date
            FROM FAASSET
            WHERE company_no = ? AND asset_no = ?
            """;

    /**
     * Static prefix for FATL10 (Asset Register) load.
     * Caller appends conditional filters (asset_no range, loc/grp/subgrp/dept)
     * and ORDER BY.
     * Mandatory params for the prefix: companyNo.
     */
    public static final String SELECT_ASSET_REGISTER_PREFIX = """
            SELECT
                a.asset_no, a.desc_1, a.desc_2,
                a.loc_code, a.dept_code, a.grp_code, a.subgrp_code,
                a.acqn_date, a.asset_status, a.leased_asset_flag,
                a.actual_cost, a.book_depn_cost, a.tax_depn_cost,
                a.book_depn_method, a.book_depn_code, a.book_depn_freq,
                a.book_rate_1, a.book_rate_2,
                a.tax_depn_method, a.tax_depn_code, a.tax_depn_freq,
                a.tax_rate_1, a.tax_rate_2,
                a.start_depn_date, a.start_tax_depn_date,
                a.last_reval_val, a.last_reval_date,
                a.last_tax_reval_val, a.last_tax_reval_date,
                a.retmt_date
            FROM FAASSET a
            WHERE a.company_no = ?
            """;

    // ════════════════════════════════════════════════════════════════════
    // FACOD* — code tables (validators + desc lookups)
    // ════════════════════════════════════════════════════════════════════

    /** Verify a location code exists. Params: companyNo, locCode. */
    public static final String COUNT_LOCATION_BY_PK =
        "SELECT COUNT(*) FROM FACODLO WHERE company_no=? AND loc_code=?";

    /** Verify a group code exists. Params: companyNo, grpCode. */
    public static final String COUNT_GROUP_BY_PK =
        "SELECT COUNT(*) FROM FACODGR WHERE company_no=? AND grp_code=?";

    /** Verify a sub-group code exists. Params: companyNo, subgrpCode. */
    public static final String COUNT_SUBGROUP_BY_PK =
        "SELECT COUNT(*) FROM FACODSG WHERE company_no=? AND subgrp_code=?";

    /** Verify a department code exists. Params: companyNo, deptCode. */
    public static final String COUNT_DEPARTMENT_BY_PK =
        "SELECT COUNT(*) FROM FACODDT WHERE company_no=? AND dept_code=?";

    /** Look up a location description. Params: companyNo, locCode. */
    public static final String FIND_LOCATION_DESC_BY_PK =
        "SELECT desc1 FROM FACODLO WHERE company_no=? AND loc_code=?";

    /** Look up a group description. Params: companyNo, grpCode. */
    public static final String FIND_GROUP_DESC_BY_PK =
        "SELECT desc1 FROM FACODGR WHERE company_no=? AND grp_code=?";

    /** Look up a sub-group description. Params: companyNo, subgrpCode. */
    public static final String FIND_SUBGROUP_DESC_BY_PK =
        "SELECT desc1 FROM FACODSG WHERE company_no=? AND subgrp_code=?";

    /** Look up a department description. Params: companyNo, deptCode. */
    public static final String FIND_DEPARTMENT_DESC_BY_PK =
        "SELECT desc1 FROM FACODDT WHERE company_no=? AND dept_code=?";

    // ════════════════════════════════════════════════════════════════════
    // CPCOYCO — Company control / batch counter
    // ════════════════════════════════════════════════════════════════════

    /** Read the FA last-batch-no counter. Params: companyNo. */
    public static final String FIND_FA_LAST_BATCH_NO =
        "SELECT fa_last_batch_no FROM CPCOYCO WHERE company_no=?";

    /** Reserve a new FA batch number. Params: nextBatchNo, companyNo. */
    public static final String UPDATE_FA_LAST_BATCH_NO =
        "UPDATE CPCOYCO SET fa_last_batch_no=? WHERE company_no=?";

    // ════════════════════════════════════════════════════════════════════
    // CMBATCH — Batch headers
    // ════════════════════════════════════════════════════════════════════

    /** Fallback: max batch_no + 1 from CMBATCH. Params: companyNo. */
    public static final String FIND_NEXT_CMBATCH_NO =
        "SELECT COALESCE(MAX(batch_no),0)+1 FROM CMBATCH " +
        "WHERE system_id='FA' AND company_no=?";

    /** Load one CMBATCH row (FA system) by PK. Params: companyNo, batchNo. */
    public static final String FIND_BATCH_BY_PK =
        "SELECT batch_no, batch_date, entered_by, ref, batch_status " +
        "FROM CMBATCH WHERE system_id='FA' AND company_no=? AND batch_no=?";

    /** All unposted FA batches for a company, newest first. Params: companyNo. */
    public static final String FIND_UNPOSTED_BATCHES =
        "SELECT batch_no, batch_date, entered_by, ref, batch_status " +
        "FROM CMBATCH WHERE system_id='FA' AND company_no=? " +
        "AND (batch_status='' OR batch_status='U') ORDER BY batch_no DESC";

    /** Verify a CMBATCH FA batch row exists. Params: companyNo, batchNo. */
    public static final String COUNT_CMBATCH_BY_PK =
        "SELECT COUNT(*) FROM CMBATCH " +
        "WHERE system_id='FA' AND company_no=? AND batch_no=?";

    /** Mark an existing CMBATCH FA batch as completed. Params: companyNo, batchNo. */
    public static final String UPDATE_CMBATCH_COMPLETED =
        "UPDATE CMBATCH SET batch_status='C' " +
        "WHERE system_id='FA' AND company_no=? AND batch_no=?";

    /**
     * Insert a new completed CMBATCH FA row.
     * Params: companyNo, batchNo, today, enteredBy.
     */
    public static final String INSERT_CMBATCH_COMPLETED =
        "INSERT INTO CMBATCH " +
        "(system_id, company_no, batch_no, batch_date, entered_by, ref, batch_status) " +
        "VALUES ('FA',?,?,?,?,'','C')";

    // ════════════════════════════════════════════════════════════════════
    // FATRXIN — Batch transactions (acquisition entries pending post)
    // ════════════════════════════════════════════════════════════════════

    /** Fallback: max batch_no + 1 from FATRXIN. Params: companyNo. */
    public static final String FIND_NEXT_FATRXIN_BATCH_NO =
        "SELECT COALESCE(MAX(batch_no),0)+1 FROM FATRXIN WHERE company_no=?";

    /**
     * P1 list — load all AQ transactions in a batch joined to FAASSET.
     * Params: companyNo, batchNo.
     */
    public static final String FIND_BATCH_TRANSACTIONS =
        "SELECT t.asset_no, t.batch_no, t.trans_trx_date, a.actual_cost, " +
        "a.desc_1, a.loc_code, a.dept_code, a.grp_code, a.subgrp_code, a.asset_status " +
        "FROM FATRXIN t " +
        "LEFT JOIN FAASSET a ON a.asset_no=t.asset_no AND a.company_no=t.company_no " +
        "WHERE t.company_no=? AND t.batch_no=? AND t.trans_trx_type='AQ' " +
        "ORDER BY t.asset_no";

    /** Count AQ rows in a batch (for the post-confirmation dialog). Params: companyNo, batchNo. */
    public static final String COUNT_BATCH_TRANSACTIONS =
        "SELECT COUNT(*) FROM FATRXIN " +
        "WHERE company_no=? AND batch_no=? AND trans_trx_type='AQ'";

    /** Whether a specific asset is in a batch. Params: companyNo, batchNo, assetNo. */
    public static final String COUNT_ASSET_IN_BATCH =
        "SELECT COUNT(*) FROM FATRXIN " +
        "WHERE company_no=? AND batch_no=? AND asset_no=?";

    /**
     * Insert an AQ batch line for manually-entered acquisitions.
     * 26 params — see AcquisitionService.insertBatchLine() for the order.
     * Uses ON DUPLICATE KEY UPDATE on (trans_trx_date, entry_date).
     */
    public static final String INSERT_BATCH_LINE_AQ =
        "INSERT INTO FATRXIN (" +
        "company_no, batch_no, asset_no, trans_asset_no, trans_trx_type," +
        "trans_trx_date, trans_batch_no, entry_batch_no, entry_date, entry_time," +
        "parent_for_curr_rate, parent_for_curr_day_no, parent_fc_maths_ind, parent_rates_key," +
        "period_no, period_end_date, user_id," +
        "data_integ_error_flag, open_bal_flag, asset_split_flag, acqn_excel_load_flag," +
        "audit_user_id, audit_date, audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun" +
        ") VALUES (?,?,?,?,'AQ',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)" +
        " ON DUPLICATE KEY UPDATE" +
        "  trans_trx_date=VALUES(trans_trx_date)," +
        "  entry_date=VALUES(entry_date)";

    /**
     * Insert an AQ batch line for Excel-imported acquisitions.
     * Same shape as INSERT_BATCH_LINE_AQ but acqn_excel_load_flag='Y'
     * and no ON DUPLICATE KEY UPDATE clause.
     */
    public static final String INSERT_BATCH_LINE_AQ_EXCEL =
        "INSERT INTO FATRXIN (" +
        "company_no, batch_no, asset_no, trans_asset_no, trans_trx_type," +
        "trans_trx_date, trans_batch_no, entry_batch_no, entry_date, entry_time," +
        "parent_for_curr_rate, parent_for_curr_day_no, parent_fc_maths_ind, parent_rates_key," +
        "period_no, period_end_date, user_id," +
        "data_integ_error_flag, open_bal_flag, asset_split_flag, acqn_excel_load_flag," +
        "audit_user_id, audit_date, audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun" +
        ") VALUES (?,?,?,?,'AQ',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    /** Update a batch-line transaction date. Params: acqnDate, companyNo, batchNo, assetNo. */
    public static final String UPDATE_BATCH_LINE_DATE =
        "UPDATE FATRXIN SET trans_trx_date=? " +
        "WHERE company_no=? AND batch_no=? AND asset_no=?";

    /** Hard-delete a batch line. Params: companyNo, batchNo, assetNo. */
    public static final String DELETE_BATCH_LINE =
        "DELETE FROM FATRXIN " +
        "WHERE company_no=? AND batch_no=? AND asset_no=?";

    // ════════════════════════════════════════════════════════════════════
    // FAASSET — full record reads/writes (asset master)
    // ════════════════════════════════════════════════════════════════════

    /** Verify any non-unposted asset with this PK exists. Params: companyNo, assetNo. */
    public static final String COUNT_ASSET_BY_PK_NOT_UNPOSTED =
        "SELECT COUNT(*) FROM FAASSET " +
        "WHERE company_no=? AND asset_no=? AND asset_status<>'U'";

    /** Verify a FAASSET row by PK exists. Params: companyNo, assetNo. */
    public static final String COUNT_FAASSET_BY_PK =
        "SELECT COUNT(*) FROM FAASSET WHERE company_no=? AND asset_no=?";

    /**
     * Load full FAASSET record for edit pre-fill. Returns all columns.
     * Params: companyNo, assetNo.
     */
    public static final String FIND_FAASSET_FULL_BY_PK =
        "SELECT * FROM FAASSET WHERE company_no=? AND asset_no=?";

    /**
     * Insert a new FAASSET row (FAAQ01 manual acquisition).
     * 110 params — see AcquisitionService.insertAsset() for the order.
     * Inserts with asset_status='U' (unposted) — postBatch promotes to ''.
     */
    public static final String INSERT_ASSET_FULL =
        "INSERT INTO FAASSET (" +
        "company_no, asset_no, alpha_code," +
        "ak1_loc, ak1_grp, ak1_subgrp, ak1_asset_no," +
        "ak2_dept, ak2_grp, ak2_subgrp, ak2_asset_no," +
        "ak3_grp, ak3_subgrp, ak3_asset_no," +
        "attach_to_asset_no, attach_asset_no," +
        "desc_1, desc_2, loc_code, dept_code, grp_code, subgrp_code," +
        "asset_status, asset_status_ref," +
        "stake_site, acqn_date, acqn_type, qty, internal_order_no," +
        "supp_name, supp_no, supp_inv_no," +
        "leased_asset_flag, lse_contract_no, lse_paymt_amt, lse_paymt_freq," +
        "lse_expiry_date, lse_resid_val, lse_tot_contr_val," +
        "curr_ins_val, repl_new_val, repl_val_as_at_date, ins_type," +
        "disposal_val, actual_cost, tax_depn_cost, book_depn_cost, interest_component," +
        "write_down_date," +
        "tax_depn_method, tax_depn_code, tax_depn_freq," +
        "tax_depn_rate_1, tax_depn_rate_2, tax_depn_calc_ind, tax_depn_calc_base," +
        "start_tax_depn_date, accum_tax_depn, accum_tax_depn_adj," +
        "last_tax_depn_date, last_tx_dep_adj_date," +
        "book_depn_method, book_depn_code, book_depn_freq," +
        "book_depn_rate_1, book_depn_rate_2, book_depn_calc_ind, book_depn_calc_base," +
        "start_depn_date, accum_book_depn, accum_book_depn_adj," +
        "last_book_depn_date, last_bk_depn_adj_date," +
        "last_reval_date, last_reval_val, reval_reserve_bal," +
        "last_tax_reval_date, last_tax_reval_val," +
        "last_tfr_date, retmt_date," +
        "retmt_proceeds_val, retmt_bk_profit_val, retmt_tx_profit_val," +
        "parent_rate_orig, parent_rate_curr, parent_fc_maths_ind, parent_rate_ind," +
        "parent_book_depn_cost, parent_accum_book_depn, parent_last_reval_val," +
        "asset_pool_flag, pool_book_bal, pool_book_bal_date," +
        "pool_tax_bal, pool_tax_bal_date, pool_acqn_posted_flag," +
        "post_depn_to_cl, post_depn_to_ba, ledger_type, ledger_code," +
        "ba_ledger_id, ba_primary_codes," +
        "note_no," +
        "audit_user_id, audit_date, audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun" +
        ") VALUES (" +
        "?,?,?,"     +
        "?,?,?,?,"   +
        "?,?,?,?,"   +
        "?,?,?,"     +
        "?,?,"       +
        "?,?,?,?,?,?,"+
        "?,?,"       +
        "?,?,?,?,?," +
        "?,?,?,"     +
        "?,?,?,?,"   +
        "?,?,?,"     +
        "?,?,?,?,"   +
        "?,?,?,?,?,?,?"+
        "?,?,?,"     +
        "?,?,?,?,"   +
        "?,?,?,"     +
        "?,?,"       +
        "?,?,?,"     +
        "?,?,?,?,"   +
        "?,?,?,"     +
        "?,?,"       +
        "?,?,?,"     +
        "?,?,"       +
        "?,?,"       +
        "?,?,?,"     +
        "?,?,?,?,"   +
        "?,?,?,"     +
        "?,?,?,"     +
        "?,?,?,"     +
        "?,?,?,?,"   +
        "?,?,"       +
        "?,"         +
        "?,?,?,?,?,?"   +
        ")";

    /**
     * Update editable FAASSET columns (FAAQ01 manual edit).
     * Params end with companyNo, assetNo. See AcquisitionService.updateAsset().
     */
    public static final String UPDATE_ASSET_FULL =
        "UPDATE FAASSET SET" +
        " desc_1=?, desc_2=?, alpha_code=?," +
        " ak1_loc=?, ak1_grp=?, ak1_subgrp=?, ak1_asset_no=?," +
        " ak2_dept=?, ak2_grp=?, ak2_subgrp=?, ak2_asset_no=?," +
        " ak3_grp=?, ak3_subgrp=?, ak3_asset_no=?," +
        " attach_to_asset_no=?, attach_asset_no=?," +
        " loc_code=?, dept_code=?, grp_code=?, subgrp_code=?," +
        " stake_site=?, asset_pool_flag=?, qty=?, acqn_date=?, acqn_type=?," +
        " supp_name=?, supp_no=?, supp_inv_no=?," +
        " leased_asset_flag=?, lse_expiry_date=?, lse_contract_no=?," +
        " lse_paymt_amt=?, lse_paymt_freq=?," +
        " curr_ins_val=?, repl_new_val=?, repl_val_as_at_date=?, ins_type=?," +
        " actual_cost=?, tax_depn_cost=?, book_depn_cost=?," +
        " write_down_date=?, tax_depn_method=?, tax_depn_code=?," +
        " tax_depn_freq=?, tax_depn_rate_1=?, tax_depn_rate_2=?," +
        " start_tax_depn_date=?, start_depn_date=?," +
        " book_depn_method=?, book_depn_code=?," +
        " book_depn_freq=?, book_depn_rate_1=?, book_depn_rate_2=?," +
        " post_depn_to_cl=?, post_depn_to_ba=?, ledger_type=?, ledger_code=?," +
        " ba_ledger_id=?, ba_primary_codes=?," +
        " audit_user_id=?, audit_date=?, audit_time_hr=?, audit_time_min=?, audit_time_sec=?, audit_time_hun=?" +
        " WHERE company_no=? AND asset_no=?";

    /**
     * Update FAASSET from Excel-import row (smaller column set than UPDATE_ASSET_FULL).
     * Params end with companyNo, assetNo. See AcquisitionService.updateAssetForImport().
     */
    public static final String UPDATE_ASSET_FROM_IMPORT =
        "UPDATE FAASSET SET desc_1=?, desc_2=?, alpha_code=?," +
        " ak1_loc=?, ak1_grp=?, ak1_subgrp=?, ak1_asset_no=?," +
        " ak2_dept=?, ak2_grp=?, ak2_subgrp=?, ak2_asset_no=?," +
        " ak3_grp=?, ak3_subgrp=?, ak3_asset_no=?," +
        " loc_code=?, dept_code=?, grp_code=?, subgrp_code=?," +
        " stake_site=?, actual_cost=?, acqn_date=?, acqn_type=?," +
        " supp_name=?, supp_no=?, supp_inv_no=?," +
        " tax_depn_method=?, tax_depn_code=?, tax_depn_rate_1=?, tax_depn_rate_2=?," +
        " book_depn_method=?, book_depn_code=?, book_depn_rate_1=?, book_depn_rate_2=?," +
        " post_depn_to_cl=?, ledger_code=?, audit_user_id=?, audit_date=?" +
        " WHERE company_no=? AND asset_no=?";

    /**
     * Insert FAASSET from Excel-import row. Same column set as INSERT_ASSET_FULL
     * but a flatter VALUES list (no spacing comments).
     */
    public static final String INSERT_ASSET_FROM_IMPORT =
        "INSERT INTO FAASSET (" +
        "company_no, asset_no, alpha_code," +
        "ak1_loc, ak1_grp, ak1_subgrp, ak1_asset_no," +
        "ak2_dept, ak2_grp, ak2_subgrp, ak2_asset_no," +
        "ak3_grp, ak3_subgrp, ak3_asset_no," +
        "attach_to_asset_no, attach_asset_no," +
        "desc_1, desc_2, loc_code, dept_code, grp_code, subgrp_code," +
        "asset_status, asset_status_ref, stake_site," +
        "acqn_date, acqn_type, qty, internal_order_no," +
        "supp_name, supp_no, supp_inv_no," +
        "leased_asset_flag, lse_contract_no, lse_paymt_amt, lse_paymt_freq," +
        "lse_expiry_date, lse_resid_val, lse_tot_contr_val," +
        "curr_ins_val, repl_new_val, repl_val_as_at_date, ins_type," +
        "disposal_val, actual_cost, tax_depn_cost, book_depn_cost, interest_component," +
        "write_down_date, tax_depn_method, tax_depn_code, tax_depn_freq," +
        "tax_depn_rate_1, tax_depn_rate_2, tax_depn_calc_ind, tax_depn_calc_base," +
        "start_tax_depn_date, accum_tax_depn, accum_tax_depn_adj," +
        "last_tax_depn_date, last_tx_dep_adj_date," +
        "book_depn_method, book_depn_code, book_depn_freq," +
        "book_depn_rate_1, book_depn_rate_2, book_depn_calc_ind, book_depn_calc_base," +
        "start_depn_date, accum_book_depn, accum_book_depn_adj," +
        "last_book_depn_date, last_bk_depn_adj_date," +
        "last_reval_date, last_reval_val, reval_reserve_bal," +
        "last_tax_reval_date, last_tax_reval_val, last_tfr_date, retmt_date," +
        "retmt_proceeds_val, retmt_bk_profit_val, retmt_tx_profit_val," +
        "parent_rate_orig, parent_rate_curr, parent_fc_maths_ind, parent_rate_ind," +
        "parent_book_depn_cost, parent_accum_book_depn, parent_last_reval_val," +
        "asset_pool_flag, pool_book_bal, pool_book_bal_date," +
        "pool_tax_bal, pool_tax_bal_date, pool_acqn_posted_flag," +
        "post_depn_to_cl, post_depn_to_ba, ledger_type, ledger_code," +
        "ba_ledger_id, ba_primary_codes, note_no," +
        "audit_user_id, audit_date, audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun" +
        ") VALUES (" +
        "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?," +
        "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?," +
        "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?," +
        "?,?,?,?,?,?,?)";

    /**
     * Activate an asset on batch post (status 'U' → '').
     * Params: userId, today, companyNo, assetNo.
     */
    public static final String UPDATE_ASSET_ACTIVATE =
        "UPDATE FAASSET SET asset_status=''," +
        " audit_user_id=?, audit_date=?" +
        " WHERE company_no=? AND asset_no=?";

    /**
     * Read joined FATRXIN×FAASSET data for export (Excel).
     * Params: companyNo, batchNo.
     */
    public static final String FIND_BATCH_ASSETS_FOR_EXPORT =
        "SELECT a.asset_no, a.desc_1, a.desc_2, a.alpha_code," +
        "  a.loc_code, a.dept_code, a.grp_code, a.subgrp_code," +
        "  a.stake_site, a.attach_to_asset_no, a.asset_pool_flag, a.qty," +
        "  a.acqn_date, a.acqn_type, a.internal_order_no," +
        "  a.supp_name, a.supp_no, a.supp_inv_no," +
        "  a.actual_cost, a.tax_depn_cost, a.book_depn_cost," +
        "  a.tax_depn_method, a.tax_depn_code, a.tax_depn_rate_1, a.tax_depn_rate_2," +
        "  a.book_depn_method, a.book_depn_code, a.book_depn_rate_1, a.book_depn_rate_2," +
        "  a.post_depn_to_cl, a.ledger_code" +
        " FROM FATRXIN t" +
        " JOIN FAASSET a ON a.company_no=t.company_no AND a.asset_no=t.asset_no" +
        " WHERE t.company_no=? AND t.batch_no=? AND t.trans_trx_type='AQ'" +
        " ORDER BY a.asset_no";

    /**
     * Read unposted-asset detail for batch posting.
     * Params: companyNo, batchNo.
     */
    public static final String FIND_BATCH_ASSETS_FOR_POST =
        "SELECT a.asset_no, a.actual_cost, a.tax_depn_cost, a.book_depn_cost," +
        "       a.acqn_date, a.loc_code, a.dept_code, a.grp_code, a.subgrp_code," +
        "       a.tax_depn_method, a.book_depn_method," +
        "       a.tax_depn_code, a.book_depn_code," +
        "       a.tax_depn_freq, a.book_depn_freq," +
        "       a.tax_depn_rate_1, a.book_depn_rate_1," +
        "       a.tax_depn_calc_ind, a.book_depn_calc_ind," +
        "       a.tax_depn_calc_base, a.book_depn_calc_base" +
        " FROM FATRXIN t" +
        " JOIN FAASSET a ON a.company_no=t.company_no AND a.asset_no=t.asset_no" +
        " WHERE t.company_no=? AND t.batch_no=? AND t.trans_trx_type='AQ'" +
        " AND a.asset_status='U'";

    // ════════════════════════════════════════════════════════════════════
    // FATRANS — AQ insert (batch post)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Insert an AQ FATRANS row on batch post. ~78 params.
     * Uses ON DUPLICATE KEY UPDATE to overwrite earlier failed posts.
     * See AcquisitionService.postBatch() for the parameter order.
     */
    public static final String INSERT_FATRANS_AQ =
        "INSERT INTO FATRANS (" +
        "company_no, date_asset_no, date_trx_date, asset_no," +
        "trx_type, trx_date, batch_no, ref, trx_status, op_bal_flag," +
        "acqn_actual_cost, acqn_book_depn_cost," +
        "acqn_loc, acqn_dept, acqn_grp, acqn_subgrp," +
        "acqn_parent_book_cost, acqn_parent_rate, acqn_tax_depn_cost," +
        "depn_method, depn_code, depn_freq, depn_rate, depn_amt," +
        "depn_thru_to_date, depn_loc, depn_dept, depn_grp, depn_subgrp," +
        "depn_calc_ind, depn_calc_base, depn_parent_amt, depn_parent_rate," +
        "reval_val, reval_adj_amt," +
        "reval_loc, reval_dept, reval_grp, reval_subgrp," +
        "reval_parent_val, reval_parent_rate, reval_accum_depn," +
        "tfr_from_loc, tfr_from_dept, tfr_from_grp, tfr_from_subgrp," +
        "tfr_to_loc, tfr_to_dept, tfr_to_grp, tfr_to_subgrp," +
        "tfr_book_depn_cost, tfr_net_reval_amt, tfr_prov_for_depn_amt," +
        "retmt_bk_profit_amt, retmt_tx_profit_amt, retmt_proceeds_amt," +
        "retmt_parent_profit, retmt_parent_proceeds, retmt_parent_rate," +
        "retmt_loc, retmt_dept, retmt_grp, retmt_subgrp," +
        "depn_adj_amt," +
        "depn_adj_loc, depn_adj_dept, depn_adj_grp, depn_adj_subgrp," +
        "depn_adj_parent_amt, depn_adj_parent_rate," +
        "note_no, audit_user_id, audit_date," +
        "audit_time_hr, audit_time_min, audit_time_sec, audit_time_hun," +
        "trx_type_description" +
        ") VALUES (" +
        "?,?,?,?," +
        "?,?,?,?,?,?," +
        "?,?,?,?,?,?,?,?,?," +
        "?,?,?,?,?," +
        "?,?,?,?,?," +
        "?,?,?,?," +
        "?,?," +
        "?,?,?,?,?,?,?," +
        "?,?,?,?,?,?,?,?," +
        "?,?,?," +
        "?,?,?,?,?,?," +
        "?,?,?,?," +
        "?," +
        "?,?,?,?,?,?," +
        "?,?,?,?,?,?,?,?" +
        ")" +
        " ON DUPLICATE KEY UPDATE" +
        "  acqn_actual_cost=VALUES(acqn_actual_cost)," +
        "  trx_status=VALUES(trx_status)";

    // ════════════════════════════════════════════════════════════════════
    // APSUPPS — Supplier lookup
    // ════════════════════════════════════════════════════════════════════

    /** Look up a supplier name by PK. Params: suppNo, companyNo. */
    public static final String FIND_SUPPLIER_NAME_BY_PK =
        "SELECT supp_name FROM APSUPPS WHERE supp_no=? AND company_no=?";

    // ════════════════════════════════════════════════════════════════════
    // Repository extractions
    //
    // Below are constants used by the JdbcTemplate-based repositories
    // (AssetMaintenanceRepository, FaAssetRepository, FaTransactionRepository,
    // CompanyRepository, GlDateRepository, GlSessionRepository).
    //
    // Where a repo query duplicates one already declared above
    // (e.g. SELECT * FROM FAASSET WHERE pk), the repo references the
    // existing constant rather than redeclaring.
    // ════════════════════════════════════════════════════════════════════

    // ─── FAASSET (additional reads/writes) ──────────────────────────────

    /**
     * P1 listbox source for FAAS01 — minimal column set per asset.
     * Params: companyNo.
     */
    public static final String FIND_ASSETS_FOR_LIST = """
            SELECT asset_no, desc_1, loc_code, dept_code,
                   grp_code, subgrp_code, asset_status, asset_pool_flag
            FROM FAASSET
            WHERE company_no = ?
            ORDER BY asset_no
            """;

    /**
     * Range-bounded asset selection for FATL12 projection.
     * Filters: 5 BETWEEN ranges, status NOT IN ('R','U'), actual_cost > 0.
     * Params: companyNo, startAssetNo, endAssetNo,
     *         startLoc, endLoc, startGrp, endGrp,
     *         startSubgrp, endSubgrp, startDept, endDept.
     */
    public static final String FIND_ASSETS_BY_RANGES = """
            SELECT
                asset_no, desc_1, loc_code, grp_code, subgrp_code, dept_code,
                asset_status,
                acqn_date, actual_cost, tax_depn_cost, book_depn_cost,
                book_depn_method, book_depn_code, book_depn_freq,
                book_depn_rate_1, book_depn_rate_2,
                book_depn_calc_ind, book_depn_calc_base,
                start_depn_date, accum_book_depn, accum_book_depn_adj,
                last_book_depn_date,
                tax_depn_method, tax_depn_code, tax_depn_freq,
                tax_depn_rate_1, tax_depn_rate_2,
                tax_depn_calc_ind, tax_depn_calc_base,
                start_tax_depn_date, accum_tax_depn, accum_tax_depn_adj,
                last_tax_depn_date,
                last_reval_date, last_reval_val, last_tax_reval_date,
                write_down_date,
                asset_pool_flag, pool_book_bal, pool_book_bal_date,
                pool_tax_bal, pool_tax_bal_date
            FROM FAASSET
            WHERE company_no = ?
              AND asset_no   BETWEEN ? AND ?
              AND loc_code   BETWEEN ? AND ?
              AND grp_code   BETWEEN ? AND ?
              AND subgrp_code BETWEEN ? AND ?
              AND dept_code  BETWEEN ? AND ?
              AND asset_status NOT IN ('R', 'U')
              AND actual_cost > 0
            ORDER BY asset_no
            """;

    /**
     * FAAS01 manual-maintenance INSERT — narrower column set than
     * INSERT_ASSET_FULL (no AK*, no parent rates, no super-fund detail).
     * 56 params — see AssetMaintenanceRepository.insert() for order.
     */
    public static final String INSERT_ASSET_FOR_MAINT = """
            INSERT INTO FAASSET (
                company_no, asset_no, desc_1, desc_2, alpha_code,
                loc_code, dept_code, grp_code, subgrp_code,
                stake_site, attach_to_asset_no,
                asset_pool_flag, qty, acqn_date, acqn_type, internal_order_no,
                parent_rate_ind, parent_rate_curr, parent_fc_maths_ind,
                supp_name, supp_no, supp_inv_no,
                leased_asset_flag, lse_expiry_date, lse_contract_no,
                lse_paymt_amt, lse_paymt_freq,
                curr_ins_val, repl_new_val, repl_val_as_at_date, ins_type,
                actual_cost, tax_depn_cost, book_depn_cost, interest_component,
                write_down_date, start_depn_date, start_tax_depn_date,
                tax_depn_method, tax_depn_code, tax_depn_rate_1, tax_depn_rate_2,
                book_depn_method, book_depn_code, book_depn_rate_1, book_depn_rate_2,
                book_depn_freq, tax_depn_freq,
                post_depn_to_cl, ledger_type, ledger_code,
                ba_ledger_id, ba_primary_codes,
                asset_status, asset_status_ref, note_no
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    /**
     * FAAS01 manual-maintenance UPDATE — touches editable maintenance fields,
     * leaves audit/depn-state columns alone.
     * Params end with companyNo, assetNo. See AssetMaintenanceRepository.update().
     */
    public static final String UPDATE_ASSET_FOR_MAINT = """
            UPDATE FAASSET SET
                desc_1=?, desc_2=?, alpha_code=?,
                stake_site=?, attach_to_asset_no=?,
                acqn_type=?, internal_order_no=?,
                parent_rate_ind=?, parent_rate_curr=?, parent_fc_maths_ind=?,
                supp_name=?, supp_no=?, supp_inv_no=?,
                leased_asset_flag=?, lse_expiry_date=?, lse_contract_no=?,
                lse_paymt_amt=?, lse_paymt_freq=?,
                curr_ins_val=?, repl_new_val=?, repl_val_as_at_date=?, ins_type=?,
                actual_cost=?, tax_depn_cost=?, book_depn_cost=?, interest_component=?,
                write_down_date=?, start_depn_date=?, start_tax_depn_date=?,
                tax_depn_method=?, tax_depn_code=?, tax_depn_rate_1=?, tax_depn_rate_2=?,
                book_depn_method=?, book_depn_code=?, book_depn_rate_1=?, book_depn_rate_2=?,
                book_depn_freq=?, tax_depn_freq=?,
                post_depn_to_cl=?, ledger_type=?, ledger_code=?,
                ba_ledger_id=?, ba_primary_codes=?,
                asset_status=?, asset_status_ref=?, note_no=?
            WHERE company_no=? AND asset_no=?
            """;

    /**
     * Targeted asset-status update (FAAS01 S2 status change).
     * Params: newStatus, statusRef, companyNo, assetNo.
     */
    public static final String UPDATE_ASSET_STATUS = """
            UPDATE FAASSET SET asset_status = ?, asset_status_ref = ?
            WHERE company_no = ? AND asset_no = ?
            """;

    // ─── FATRXIN (additional) ───────────────────────────────────────────

    /** Count any FATRXIN rows for an asset (unposted-trx check). Params: companyNo, assetNo. */
    public static final String COUNT_FATRXIN_BY_ASSET = """
            SELECT COUNT(*) FROM FATRXIN
            WHERE company_no = ? AND asset_no = ?
            """;

    // ─── FATRANS (additional, used by FaTransactionRepository) ──────────

    /**
     * Read all depreciation+adj transactions for an asset up to a date,
     * for a single stream (book = 'BD'/'BA' or tax = 'TD'/'TA').
     * Params: companyNo, assetNo, type1, type2, upToDate.
     */
    public static final String FIND_DEPN_TRX_FOR_ASSET = """
            SELECT asset_no, trx_type, trx_date, batch_no,
                   trx_status, op_bal_flag,
                   depn_method, depn_code, depn_freq, depn_rate,
                   depn_amt, depn_thru_to_date, depn_calc_ind, depn_calc_base,
                   acqn_actual_cost, acqn_book_depn_cost, acqn_tax_depn_cost,
                   reval_val, reval_adj_amt, reval_accum_depn
            FROM FATRANS
            WHERE company_no = ?
              AND asset_no   = ?
              AND trx_type   IN (?, ?)
              AND trx_date  <= ?
            ORDER BY trx_date, batch_no
            """;

    /**
     * Read RV (revaluation) transactions for an asset strictly after a date.
     * Params: companyNo, assetNo, afterDate.
     */
    public static final String FIND_REVAL_TRX_AFTER_DATE = """
            SELECT asset_no, trx_type, trx_date, batch_no,
                   trx_status, op_bal_flag,
                   acqn_actual_cost, acqn_book_depn_cost, acqn_tax_depn_cost,
                   depn_method, depn_code, depn_freq, depn_rate,
                   depn_amt, depn_thru_to_date, depn_calc_ind, depn_calc_base,
                   reval_val, reval_adj_amt, reval_accum_depn
            FROM FATRANS
            WHERE company_no = ?
              AND asset_no   = ?
              AND trx_type   = 'RV'
              AND trx_date   > ?
            ORDER BY trx_date, batch_no
            """;

    /**
     * Read all AQ (acquisition) transactions for an asset.
     * Most assets have one; partial disposals can produce multiples.
     * Params: companyNo, assetNo.
     */
    public static final String FIND_ACQUISITIONS_FOR_ASSET = """
            SELECT asset_no, trx_type, trx_date, batch_no,
                   trx_status, op_bal_flag,
                   acqn_actual_cost, acqn_book_depn_cost, acqn_tax_depn_cost,
                   depn_method, depn_code, depn_freq, depn_rate,
                   depn_amt, depn_thru_to_date, depn_calc_ind, depn_calc_base,
                   reval_val, reval_adj_amt, reval_accum_depn
            FROM FATRANS
            WHERE company_no = ?
              AND asset_no   = ?
              AND trx_type   = 'AQ'
            ORDER BY trx_date, batch_no
            """;

    // ─── FAASAUD (Asset audit log) ──────────────────────────────────────

    /**
     * Write an audit row before/after an asset change (FAAS01).
     * maintType: C=change, S=status, D=depreciation.
     * Params: companyNo, assetNo, dateChanged, timeChanged,
     *         maintType, beforeData, afterData.
     */
    public static final String INSERT_FAASAUD = """
            INSERT INTO FAASAUD (
                company_no, asset_no, date_changed, time_changed,
                maint_type, before_data, after_data
            ) VALUES (?,?,?,?,?,?,?)
            """;

    // ─── FAASBAR (Asset bar codes) ──────────────────────────────────────

    /** All bar codes for an asset, ordered by bar_code. Params: companyNo, assetNo. */
    public static final String FIND_BARCODES_FOR_ASSET = """
            SELECT company_no, asset_no, bar_code FROM FAASBAR
            WHERE company_no = ? AND asset_no = ?
            ORDER BY bar_code
            """;

    /** Insert a bar code. Params: companyNo, assetNo, barCode. */
    public static final String INSERT_BARCODE =
        "INSERT INTO FAASBAR (company_no, asset_no, bar_code) VALUES (?,?,?)";

    /** Delete one bar code. Params: companyNo, assetNo, barCode. */
    public static final String DELETE_BARCODE =
        "DELETE FROM FAASBAR WHERE company_no = ? AND asset_no = ? AND bar_code = ?";

    /** Whether a specific bar code exists. Params: companyNo, assetNo, barCode. */
    public static final String COUNT_BARCODE_BY_PK = """
            SELECT COUNT(*) FROM FAASBAR
            WHERE company_no = ? AND asset_no = ? AND bar_code = ?
            """;

    // ─── CPCOYCO (additional, used by CompanyRepository) ────────────────

    /** Load minimal company record (no, name, FA tax y/e mth). Params: companyNo. */
    public static final String FIND_COMPANY_BY_PK = """
            SELECT company_no, name1, fa_tax_yr_end_mth
            FROM CPCOYCO
            WHERE company_no = ?
            """;

    /** All companies, ordered by company_no. No params. */
    public static final String FIND_ALL_COMPANIES =
        "SELECT company_no, name1, fa_tax_yr_end_mth FROM CPCOYCO ORDER BY company_no";

    // ─── GLDATES (used by GlDateRepository) ─────────────────────────────

    /** Load full GLDATES row by PK. Params: companyNo, yearNo. */
    public static final String FIND_GL_YEAR_BY_PK =
        "SELECT * FROM GLDATES WHERE company_no = ? AND year_no = ?";

    /** All GLDATES rows for a company, ordered by year. Params: companyNo. */
    public static final String FIND_ALL_GL_YEARS_BY_COMPANY =
        "SELECT * FROM GLDATES WHERE company_no = ? ORDER BY year_no";

    /**
     * Validate a date matches any period_end_NN (NN=01..13) for a company.
     * Params: companyNo, then the same date 13 times (one per period_end_NN).
     */
    public static final String COUNT_GLDATES_PERIOD_END_MATCH_BY_COMPANY =
        "SELECT COUNT(*) FROM GLDATES WHERE company_no = ? AND (" +
        "period_end_01 = ? OR period_end_02 = ? OR period_end_03 = ? OR " +
        "period_end_04 = ? OR period_end_05 = ? OR period_end_06 = ? OR " +
        "period_end_07 = ? OR period_end_08 = ? OR period_end_09 = ? OR " +
        "period_end_10 = ? OR period_end_11 = ? OR period_end_12 = ? OR " +
        "period_end_13 = ?)";

    /**
     * Validate a date matches any period_end_NN, ignoring company.
     * Params: the same date 13 times (one per period_end_NN).
     */
    public static final String COUNT_GLDATES_PERIOD_END_MATCH =
        "SELECT COUNT(*) FROM GLDATES WHERE " +
        "period_end_01 = ? OR period_end_02 = ? OR period_end_03 = ? OR " +
        "period_end_04 = ? OR period_end_05 = ? OR period_end_06 = ? OR " +
        "period_end_07 = ? OR period_end_08 = ? OR period_end_09 = ? OR " +
        "period_end_10 = ? OR period_end_11 = ? OR period_end_12 = ? OR " +
        "period_end_13 = ?";

    // ─── GLPASS (used by GlSessionRepository) ───────────────────────────

    /**
     * Read the legacy GLPASS session row by terminal number.
     * (Strategy A — most installs now use the runtime-built session via
     * GlSessionRepository.buildSession() instead.)
     * Params: terminalNo.
     */
    public static final String FIND_GL_SESSION_BY_TERMINAL = """
            SELECT terminal_no, company_no, yr_no, year_no, batch_no,
                   open_bal_date, fa_tax_yr_end_mth,
                   fa_det_summ_depn_ind, book_or_tax_ind
            FROM GLPASS
            WHERE terminal_no = ?
            """;
}
