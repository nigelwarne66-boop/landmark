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

import com.landmarksoftware.model.AssetRow;
import com.landmarksoftware.model.TransactionListRequest;
import com.landmarksoftware.model.TransactionRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/**
 * FATL02 — Fixed Assets Transaction List service.
 *
 * Replicates the COBOL logic:
 *   1. Select assets from FAASSET matching the parameter screen filters
 *   2. For each asset, read all FATRANS rows and apply type/date filters
 *   3. Return grouped results (asset → list of transactions)
 *
 * Control break (per asset) and grand totals are computed here so
 * both PDF and Excel export services can use the same data.
 */
@Service
public class TransactionListService {

    private final JdbcTemplate jdbc;

    public TransactionListService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Result container ──────────────────────────────────────────────

    public record AssetGroup(AssetRow asset, List<TransactionRow> transactions) {}

    public record TransactionListOutput(
        TransactionListRequest request,
        List<AssetGroup>       groups,
        int                    totalAssets,
        int                    totalTransactions
    ) {}

    // ── Entry point ───────────────────────────────────────────────────

    public TransactionListOutput run(TransactionListRequest req) {
        List<AssetRow> assets = selectAssets(req);

        List<AssetGroup> groups    = new ArrayList<>();
        int              totalTrxs = 0;

        for (AssetRow asset : assets) {
            List<TransactionRow> trxs = selectTransactions(req, asset);
            if (!trxs.isEmpty()) {
                groups.add(new AssetGroup(asset, trxs));
                totalTrxs += trxs.size();
            }
        }

        return new TransactionListOutput(req, groups, groups.size(), totalTrxs);
    }

    // ── Asset selection ───────────────────────────────────────────────

    /**
     * Mirrors COBOL TEST-RECORD-SELECTION-03.
     * Reads FAASSET sequentially from startAssetNo, stopping at endAssetNo.
     * All filters are applied in SQL for performance.
     */
    private List<AssetRow> selectAssets(TransactionListRequest req) {
        StringBuilder sql = new StringBuilder(FaSql.SELECT_ASSETS_FOR_TRX_LIST_PREFIX);

        List<Object> params = new ArrayList<>();
        params.add(req.getCompanyNo());
        params.add(req.getStartAssetNo().isBlank() ? "" : req.getStartAssetNo());
        params.add(req.effectiveEndAssetNo());

        // Location filter
        if (!req.getLocCode().isBlank()) {
            sql.append("  AND loc_code = ?\n");
            params.add(req.getLocCode());
        }

        // Group filter
        if (!req.getGrpCode().isBlank()) {
            sql.append("  AND grp_code = ?\n");
            params.add(req.getGrpCode());
        }

        // Sub-group filter
        if (!req.getSubgrpCode().isBlank()) {
            sql.append("  AND subgrp_code = ?\n");
            params.add(req.getSubgrpCode());
        }

        // Department filter
        if (!req.getDeptCode().isBlank()) {
            sql.append("  AND dept_code = ?\n");
            params.add(req.getDeptCode());
        }

        // Leased asset filter (N = exclude leased, Y = include all)
        if (!req.isIncludeLeased()) {
            sql.append("  AND (leased_asset_flag = 'N' OR leased_asset_flag IS NULL)\n");
        }

        // Pooled only filter
        if (req.isPooledOnly()) {
            sql.append("  AND asset_pool_flag = 'Y'\n");
        }

        sql.append("ORDER BY asset_no");

        return jdbc.query(sql.toString(), new AssetRowMapper(), params.toArray());
    }

    // ── Transaction selection ─────────────────────────────────────────

    /**
     * Mirrors COBOL PRINT-NEXT-TRX.
     * Reads FATRANS for this asset, applies type flags and date range.
     * Returns TransactionRow list enriched with asset context.
     */
    private List<TransactionRow> selectTransactions(TransactionListRequest req, AssetRow asset) {
        // Build the type filter — only include enabled types
        List<String> enabledTypes = new ArrayList<>();
        if (req.isInclAcqn())       enabledTypes.add("AQ");
        if (req.isInclBookReval())  enabledTypes.add("RV");
        if (req.isInclTaxReval())   enabledTypes.add("TV");
        if (req.isInclTransfer())   enabledTypes.add("TR");
        if (req.isInclRetirement()) enabledTypes.add("RT");
        if (req.isInclBookDepn())   enabledTypes.add("BD");
        if (req.isInclBookDepnAdj()) enabledTypes.add("BA");
        if (req.isInclTaxDepn())    enabledTypes.add("TD");
        if (req.isInclTaxDepnAdj()) enabledTypes.add("TA");

        if (enabledTypes.isEmpty()) return Collections.emptyList();

        String inClause = String.join(",",
            Collections.nCopies(enabledTypes.size(), "?"));

        StringBuilder sql = new StringBuilder(FaSql.SELECT_FATRANS_FOR_ASSET_PREFIX);
        sql.append(inClause).append(")\n");

        List<Object> params = new ArrayList<>();
        params.add(req.getCompanyNo());
        params.add(asset.getAssetNo());
        params.addAll(enabledTypes);

        // Date range filter
        if (req.getStartDate() != null) {
            sql.append("  AND t.trx_date >= ?\n");
            params.add(java.sql.Date.valueOf(req.getStartDate()));
        }
        if (req.getEndDate() != null) {
            sql.append("  AND t.trx_date <= ?\n");
            params.add(java.sql.Date.valueOf(req.getEndDate()));
        }

        // Exclude COBOL null date 1899-12-30
        sql.append("  AND t.trx_date > '1900-01-01'\n");
        sql.append("ORDER BY t.trx_date, t.batch_no");

        List<TransactionRow> rows = jdbc.query(sql.toString(),
            new TransactionRowMapper(), params.toArray());

        // Enrich each row with asset context
        rows.forEach(r -> populateAssetContext(r, asset));
        return rows;
    }

    private void populateAssetContext(TransactionRow r, AssetRow asset) {
        r.setAssetNo(asset.getAssetNo());
        r.setDesc1(asset.getDesc1());
        r.setLocCode(asset.getLocCode());
        r.setDeptCode(asset.getDeptCode());
        r.setGrpCode(asset.getGrpCode());
        r.setSubgrpCode(asset.getSubgrpCode());
        r.setAcqnDate(asset.getAcqnDate());
        r.setAssetStatus(asset.getAssetStatus());
        r.setPooledAsset("Y".equals(asset.getAssetPoolFlag()));
    }

    // ── RowMappers ────────────────────────────────────────────────────

    private static class AssetRowMapper implements RowMapper<AssetRow> {
        @Override
        public AssetRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            AssetRow a = new AssetRow();
            a.setAssetNo(rs.getString("asset_no"));
            a.setDesc1(rs.getString("desc_1"));
            a.setLocCode(rs.getString("loc_code"));
            a.setDeptCode(rs.getString("dept_code"));
            a.setGrpCode(rs.getString("grp_code"));
            a.setSubgrpCode(rs.getString("subgrp_code"));
            a.setAssetStatus(rs.getString("asset_status"));
            a.setAcqnDate(toDate(rs, "acqn_date"));
            a.setLeasedAssetFlag(rs.getString("leased_asset_flag"));
            a.setAssetPoolFlag(rs.getString("asset_pool_flag"));
            a.setActualCost(rs.getBigDecimal("actual_cost"));
            a.setBookDepnCost(rs.getBigDecimal("book_depn_cost"));
            a.setTaxDepnCost(rs.getBigDecimal("tax_depn_cost"));
            return a;
        }
    }

    private static class TransactionRowMapper implements RowMapper<TransactionRow> {
        @Override
        public TransactionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            TransactionRow t = new TransactionRow();
            t.setTrxType(rs.getString("trx_type"));
            t.setTrxDate(toDate(rs, "trx_date"));
            t.setBatchNo(rs.getInt("batch_no"));
            t.setRef(rs.getString("ref"));
            t.setTrxStatus(rs.getString("trx_status"));

            // Acquisition
            t.setAcqnActualCost(rs.getBigDecimal("acqn_actual_cost"));
            t.setAcqnBookDepnCost(rs.getBigDecimal("acqn_book_depn_cost"));
            t.setAcqnTaxDepnCost(rs.getBigDecimal("acqn_tax_depn_cost"));
            t.setAcqnLoc(rs.getString("acqn_loc"));
            t.setAcqnDept(rs.getString("acqn_dept"));
            t.setAcqnGrp(rs.getString("acqn_grp"));
            t.setAcqnSubgrp(rs.getString("acqn_subgrp"));

            // Depreciation
            t.setDepnMethod(rs.getString("depn_method"));
            t.setDepnCode(rs.getString("depn_code"));
            t.setDepnFreq(rs.getInt("depn_freq"));
            t.setDepnRate(rs.getBigDecimal("depn_rate"));
            t.setDepnAmt(rs.getBigDecimal("depn_amt"));
            t.setDepnThruToDate(toDate(rs, "depn_thru_to_date"));
            t.setDepnLoc(rs.getString("depn_loc"));
            t.setDepnDept(rs.getString("depn_dept"));
            t.setDepnGrp(rs.getString("depn_grp"));
            t.setDepnSubgrp(rs.getString("depn_subgrp"));

            // Depreciation adjustment
            t.setDepnAdjAmt(rs.getBigDecimal("depn_adj_amt"));
            t.setDepnAdjLoc(rs.getString("depn_adj_loc"));
            t.setDepnAdjDept(rs.getString("depn_adj_dept"));
            t.setDepnAdjGrp(rs.getString("depn_adj_grp"));
            t.setDepnAdjSubgrp(rs.getString("depn_adj_subgrp"));

            // Revaluation
            t.setRevalVal(rs.getBigDecimal("reval_val"));
            t.setRevalAdjAmt(rs.getBigDecimal("reval_adj_amt"));
            t.setRevalAccumDepn(rs.getBigDecimal("reval_accum_depn"));
            t.setRevalLoc(rs.getString("reval_loc"));
            t.setRevalDept(rs.getString("reval_dept"));
            t.setRevalGrp(rs.getString("reval_grp"));
            t.setRevalSubgrp(rs.getString("reval_subgrp"));

            // Transfer
            t.setTfrFromLoc(rs.getString("tfr_from_loc"));
            t.setTfrFromDept(rs.getString("tfr_from_dept"));
            t.setTfrFromGrp(rs.getString("tfr_from_grp"));
            t.setTfrFromSubgrp(rs.getString("tfr_from_subgrp"));
            t.setTfrToLoc(rs.getString("tfr_to_loc"));
            t.setTfrToDept(rs.getString("tfr_to_dept"));
            t.setTfrToGrp(rs.getString("tfr_to_grp"));
            t.setTfrToSubgrp(rs.getString("tfr_to_subgrp"));
            t.setTfrBookDepnCost(rs.getBigDecimal("tfr_book_depn_cost"));
            t.setTfrNetRevalAmt(rs.getBigDecimal("tfr_net_reval_amt"));
            t.setTfrProvDepnAmt(rs.getBigDecimal("tfr_prov_for_depn_amt"));

            // Retirement
            t.setRetmtBkProfitAmt(rs.getBigDecimal("retmt_bk_profit_amt"));
            t.setRetmtTxProfitAmt(rs.getBigDecimal("retmt_tx_profit_amt"));
            t.setRetmtProceedsAmt(rs.getBigDecimal("retmt_proceeds_amt"));

            // Audit
            t.setAuditUserId(rs.getString("audit_user_id"));
            t.setAuditDate(toDate(rs, "audit_date"));
            int hr  = rs.getInt("audit_time_hr");
            int min = rs.getInt("audit_time_min");
            int sec = rs.getInt("audit_time_sec");
            t.setAuditTime(String.format("%02d:%02d:%02d", hr, min, sec));

            return t;
        }
    }

    private static LocalDate toDate(ResultSet rs, String col) throws SQLException {
        java.sql.Date d = rs.getDate(col);
        if (d == null) return null;
        LocalDate ld = d.toLocalDate();
        return ld.getYear() < 1900 ? null : ld;
    }
}
