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

import com.landmarksoftware.model.AssetBarCode;
import com.landmarksoftware.model.AssetListRow;
import com.landmarksoftware.model.AssetMaintenanceRecord;
import com.landmarksoftware.service.FaSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * FAAS01 data access — FAASSET (I-O), FAASAUD (write), FAASBAR (I-O), FATRXIN (read).
 *
 * Key design decisions matching COBOL behaviour:
 * - findAll()    = sequential scan for the P1 listbox (NEXT-FAASSET)
 * - findByKey()  = keyed read for the edit/status screens (READ-FAASSET)
 * - save()       = REWRITE-FAASSET-RECORD (existing) or WRITE-FAASSET-RECORD (new)
 * - hasUnpostedTransactions() = checks FATRXIN for any record with this asset_no
 * - writeAuditRecord()  = WRITE-FAASAUD-RECORD (before/after snapshot)
 */
@Repository
public class AssetMaintenanceRepository {

    private final JdbcTemplate jdbc;

    public AssetMaintenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Asset list (P1 listbox) ────────────────────────────────────────

    /**
     * Load all assets for a company, ordered by asset_no.
     * Used to populate the P1 listbox.
     */
    public List<AssetListRow> findAllForList(int companyNo) {
        return jdbc.query(FaSql.FIND_ASSETS_FOR_LIST,
            (rs, i) -> new AssetListRow(
                rs.getString("asset_no"),
                rs.getString("desc_1"),
                rs.getString("loc_code"),
                rs.getString("dept_code"),
                rs.getString("grp_code"),
                rs.getString("subgrp_code"),
                rs.getString("asset_status"),
                "Y".equals(rs.getString("asset_pool_flag"))
            ),
            companyNo);
    }

    // ── Single asset read ──────────────────────────────────────────────

    public Optional<AssetMaintenanceRecord> findByAssetNo(int companyNo, String assetNo) {
        var list = jdbc.query(FaSql.FIND_FAASSET_FULL_BY_PK,
            new AssetRecordMapper(), companyNo, assetNo.trim());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    // ── Save (insert or update) ────────────────────────────────────────

    /**
     * Insert a new asset record (ADD-FAASSET).
     */
    public void insert(AssetMaintenanceRecord r) {
        jdbc.update(FaSql.INSERT_ASSET_FOR_MAINT,
            r.getCompanyNo(), r.getAssetNo(), r.getDesc1(), r.getDesc2(), r.getAlphaCode(),
            r.getLocCode(), r.getDeptCode(), r.getGrpCode(), r.getSubgrpCode(),
            r.getStakeSite(), r.getAttachToAssetNo(),
            r.getAssetPoolFlag(), r.getQty(), toDate(r.getAcqnDate()), r.getAcqnType(), r.getInternalOrderNo(),
            r.getParentRateInd(), r.getParentRateCurr(), r.getParentFcMathsInd(),
            r.getSupplierName(), r.getSupplierNo(), r.getInvoiceNo(),
            r.getLeasedAssetFlag(), toDate(r.getLseExpiryDate()), r.getLseContractNo(),
            r.getLsePaymentAmt(), r.getLsePaymentFreq(),
            r.getCurrentInsValue(), r.getReplNewVal(), toDate(r.getReplValAsAtDate()), r.getInsType(),
            r.getActualCost(), r.getTaxDepnCost(), r.getBookDepnCost(), r.getInterestComponent(),
            toDate(r.getWriteDownDate()), toDate(r.getStartDepnDate()), toDate(r.getStartTaxDepnDate()),
            r.getTaxDepnMethod(), r.getTaxDepnCode(), r.getTaxRate1(), r.getTaxRate2(),
            r.getBookDepnMethod(), r.getBookDepnCode(), r.getBookRate1(), r.getBookRate2(),
            r.getBookDepnFreq(), r.getTaxDepnFreq(),
            r.getPostDepnToClBa(), r.getLedgerType(), r.getLedgerCode(),
            r.getBaLedgerId(), r.getBaPrimaryCodes(),
            r.getAssetStatus(), r.getAssetStatusRef(), r.getNoteNo()
        );
    }

    /**
     * Update existing asset record (REWRITE-FAASSET-RECORD / CHANGE-FAASSET).
     */
    public void update(AssetMaintenanceRecord r) {
        jdbc.update(FaSql.UPDATE_ASSET_FOR_MAINT,
            r.getDesc1(), r.getDesc2(), r.getAlphaCode(),
            r.getStakeSite(), r.getAttachToAssetNo(),
            r.getAcqnType(), r.getInternalOrderNo(),
            r.getParentRateInd(), r.getParentRateCurr(), r.getParentFcMathsInd(),
            r.getSupplierName(), r.getSupplierNo(), r.getInvoiceNo(),
            r.getLeasedAssetFlag(), toDate(r.getLseExpiryDate()), r.getLseContractNo(),
            r.getLsePaymentAmt(), r.getLsePaymentFreq(),
            r.getCurrentInsValue(), r.getReplNewVal(), toDate(r.getReplValAsAtDate()), r.getInsType(),
            r.getActualCost(), r.getTaxDepnCost(), r.getBookDepnCost(), r.getInterestComponent(),
            toDate(r.getWriteDownDate()), toDate(r.getStartDepnDate()), toDate(r.getStartTaxDepnDate()),
            r.getTaxDepnMethod(), r.getTaxDepnCode(), r.getTaxRate1(), r.getTaxRate2(),
            r.getBookDepnMethod(), r.getBookDepnCode(), r.getBookRate1(), r.getBookRate2(),
            r.getBookDepnFreq(), r.getTaxDepnFreq(),
            r.getPostDepnToClBa(), r.getLedgerType(), r.getLedgerCode(),
            r.getBaLedgerId(), r.getBaPrimaryCodes(),
            r.getAssetStatus(), r.getAssetStatusRef(), r.getNoteNo(),
            r.getCompanyNo(), r.getAssetNo()
        );
    }

    // ── Status update (S2 — simpler targeted update) ───────────────────

    public void updateStatus(int companyNo, String assetNo, String newStatus, String ref) {
        jdbc.update(FaSql.UPDATE_ASSET_STATUS, newStatus, ref, companyNo, assetNo);
    }

    // ── Unposted transaction check ─────────────────────────────────────

    /**
     * Mirrors COBOL: MOVE FAASSET-ASSET-NO TO FATRXIN-ASSET-NO / PERFORM READ-FATRXIN-RECORD
     * Returns true if any FATRXIN record exists for this asset (= has unposted transactions).
     */
    public boolean hasUnpostedTransactions(int companyNo, String assetNo) {
        Integer count = jdbc.queryForObject(
            FaSql.COUNT_FATRXIN_BY_ASSET, Integer.class, companyNo, assetNo.trim());
        return count != null && count > 0;
    }

    // ── Audit trail ───────────────────────────────────────────────────

    /**
     * Writes a FAASAUD record (WRITE-FAASAUD-RECORD).
     * maintType: C=change, S=status, D=depreciation
     */
    public void writeAuditRecord(int companyNo, String assetNo,
                                  LocalDate dateChanged, int timeChanged,
                                  String maintType,
                                  String beforeData, String afterData) {
        jdbc.update(FaSql.INSERT_FAASAUD,
            companyNo, assetNo.trim(),
            toDate(dateChanged), timeChanged,
            maintType, beforeData, afterData);
    }

    // ── Bar codes (P5) ────────────────────────────────────────────────

    public List<AssetBarCode> findBarCodes(int companyNo, String assetNo) {
        return jdbc.query(FaSql.FIND_BARCODES_FOR_ASSET,
            (rs, i) -> new AssetBarCode(
                rs.getInt("company_no"),
                rs.getString("asset_no"),
                rs.getString("bar_code")),
            companyNo, assetNo.trim());
    }

    public void addBarCode(int companyNo, String assetNo, String barCode) {
        jdbc.update(FaSql.INSERT_BARCODE, companyNo, assetNo.trim(), barCode.trim());
    }

    public void deleteBarCode(int companyNo, String assetNo, String barCode) {
        jdbc.update(FaSql.DELETE_BARCODE, companyNo, assetNo.trim(), barCode.trim());
    }

    public boolean barCodeExists(int companyNo, String assetNo, String barCode) {
        Integer c = jdbc.queryForObject(
            FaSql.COUNT_BARCODE_BY_PK, Integer.class,
            companyNo, assetNo.trim(), barCode.trim());
        return c != null && c > 0;
    }

    // ── Code lookups ──────────────────────────────────────────────────

    public Optional<String> lookupDesc(String table, String codeCol, String descCol,
                                        int companyNo, String codeValue) {
        try {
            String desc = jdbc.queryForObject(
                "SELECT " + descCol + " FROM " + table +
                " WHERE company_no = ? AND " + codeCol + " = ?",
                String.class, companyNo, codeValue.trim());
            return Optional.ofNullable(desc);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    // ── RowMapper ──────────────────────────────────────────────────────

    private static class AssetRecordMapper implements RowMapper<AssetMaintenanceRecord> {
        @Override
        public AssetMaintenanceRecord mapRow(ResultSet rs, int i) throws SQLException {
            var r = new AssetMaintenanceRecord();
            // Core fields — these must exist (they're in FAASSET for reporting too)
            r.setCompanyNo(rs.getInt("company_no"));
            r.setAssetNo(rs.getString("asset_no"));
            r.setDesc1(rs.getString("desc_1"));
            r.setDesc2(safeStr(rs, "desc_2"));
            r.setAlphaCode(safeStr(rs, "alpha_code"));
            r.setLocCode(rs.getString("loc_code"));
            r.setDeptCode(rs.getString("dept_code"));
            r.setGrpCode(rs.getString("grp_code"));
            r.setSubgrpCode(rs.getString("subgrp_code"));
            r.setAssetStatus(safeStr(rs, "asset_status"));
            r.setAssetPoolFlag(safeStr(rs, "asset_pool_flag"));
            r.setAcqnDate(toLocal(rs, "acqn_date"));
            r.setBookDepnCost(safeDec(rs, "book_depn_cost"));
            r.setTaxDepnCost(safeDec(rs, "tax_depn_cost"));
            r.setLeasedAssetFlag(safeStr(rs, "leased_asset_flag"));
            // Extended fields — may not exist in all schema versions, read defensively
            r.setStakeSite(safeStr(rs, "stake_site"));
            r.setAttachToAssetNo(safeStr(rs, "attach_to_asset_no"));
            r.setQty(safeDec(rs, "qty"));
            r.setAcqnType(safeStr(rs, "acqn_type"));
            r.setInternalOrderNo(safeStr(rs, "internal_order_no"));
            r.setParentRateInd(safeStr(rs, "parent_rate_ind"));
            r.setParentRateCurr(safeDec(rs, "parent_rate_curr"));
            r.setParentFcMathsInd(safeStr(rs, "parent_fc_maths_ind"));
            r.setSupplierName(safeStr(rs, "supp_name"));
            r.setSupplierNo(safeStr(rs, "supp_no"));
            r.setInvoiceNo(safeStr(rs, "supp_inv_no"));
            r.setLseExpiryDate(toLocal(rs, "lse_expiry_date"));
            r.setLseContractNo(safeStr(rs, "lse_contract_no"));
            r.setLsePaymentAmt(safeDec(rs, "lse_paymt_amt"));
            r.setLsePaymentFreq(safeStr(rs, "lse_paymt_freq"));
            r.setCurrentInsValue(safeDec(rs, "curr_ins_val"));
            r.setReplNewVal(safeDec(rs, "repl_new_val"));
            r.setReplValAsAtDate(toLocal(rs, "repl_val_as_at_date"));
            r.setInsType(safeStr(rs, "ins_type"));
            r.setActualCost(safeDec(rs, "actual_cost"));
            r.setInterestComponent(safeDec(rs, "interest_component"));
            r.setWriteDownDate(toLocal(rs, "write_down_date"));
            r.setStartDepnDate(toLocal(rs, "start_depn_date"));
            r.setStartTaxDepnDate(toLocal(rs, "start_tax_depn_date"));
            r.setTaxDepnMethod(safeStr(rs, "tax_depn_method"));
            r.setTaxDepnCode(safeStr(rs, "tax_depn_code"));
            r.setTaxRate1(safeDec(rs, "tax_depn_rate_1"));
            r.setTaxRate2(safeDec(rs, "tax_depn_rate_2"));
            r.setBookDepnMethod(safeStr(rs, "book_depn_method"));
            r.setBookDepnCode(safeStr(rs, "book_depn_code"));
            r.setBookRate1(safeDec(rs, "book_depn_rate_1"));
            r.setBookRate2(safeDec(rs, "book_depn_rate_2"));
            r.setBookDepnFreq(safeInt(rs, "book_depn_freq"));
            r.setTaxDepnFreq(safeInt(rs, "tax_depn_freq"));
            r.setPoolBookBal(safeDec(rs, "pool_book_bal"));
            r.setPoolBookBalDate(toLocal(rs, "pool_book_bal_date"));
            r.setPoolTaxBal(safeDec(rs, "pool_tax_bal"));
            r.setPoolTaxBalDate(toLocal(rs, "pool_tax_bal_date"));
            r.setPoolAcqnPostedFlag(safeStr(rs, "pool_acqn_posted_flag"));
            r.setPostDepnToClBa(safeStr(rs, "post_depn_to_cl"));
            r.setLedgerType(safeStr(rs, "ledger_type"));
            r.setLedgerCode(safeStr(rs, "ledger_code"));
            r.setBaLedgerId(safeStr(rs, "ba_ledger_id"));
            r.setBaPrimaryCodes(safeStr(rs, "ba_primary_codes"));
            r.setAssetStatusRef(safeStr(rs, "asset_status_ref"));
            r.setRetmtDate(toLocal(rs, "retmt_date"));
            r.setRetmtProceedsVal(safeDec(rs, "retmt_proceeds_val"));
            r.setNoteNo(safeLong(rs, "note_no"));
            return r;
        }
    }

    // ── Safe column readers — return null/zero if column doesn't exist ─

    private static String safeStr(ResultSet rs, String col) {
        try { return rs.getString(col); } catch (SQLException e) { return ""; }
    }

    private static BigDecimal safeDec(ResultSet rs, String col) {
        try {
            BigDecimal v = rs.getBigDecimal(col);
            return v == null ? BigDecimal.ZERO : v;
        } catch (SQLException e) { return BigDecimal.ZERO; }
    }

    private static int safeInt(ResultSet rs, String col) {
        try { return rs.getInt(col); } catch (SQLException e) { return 0; }
    }

    private static long safeLong(ResultSet rs, String col) {
        try { return rs.getLong(col); } catch (SQLException e) { return 0L; }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static java.sql.Date toDate(LocalDate d) {
        return d == null ? null : java.sql.Date.valueOf(d);
    }

    // Landmark base date / COBOL date-zero sentinel
    private static final LocalDate LANDMARK_ZERO_DATE = LocalDate.of(1899, 12, 31);

    private static LocalDate toLocal(ResultSet rs, String col) throws SQLException {
        java.sql.Date d = rs.getDate(col);
        if (d == null) return null;
        LocalDate ld = d.toLocalDate();
        // 1899-12-31 is Landmark's COBOL epoch (date zero = 000000 packed) — treat as null
        return (ld.equals(LANDMARK_ZERO_DATE) || ld.getYear() < 1899) ? null : ld;
    }
}
