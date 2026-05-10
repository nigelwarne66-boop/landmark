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

import com.landmarksoftware.model.AcquisitionRecord;
import com.landmarksoftware.model.AssetFullRecord;
import com.landmarksoftware.model.BatchInfo;
import com.landmarksoftware.model.AppSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FAAQ01 — Asset Acquisition Service.
 *
 * All JDBC operations for the acquisition entry workflow, extracted
 * from AcquisitionEntryController. No JavaFX dependencies.
 *
 * Tables: CPCOYCO, CMBATCH, FAASSET, FATRXIN, FATRANS,
 *         FACODLO, FACODDT, FACODGR, FACODSG, FACODSS, FACODDN, APSUPPS
 */
@Service
public class AcquisitionService {

    private static final java.sql.Date ZERO_DATE =
        java.sql.Date.valueOf("1899-12-31");

    private final JdbcTemplate jdbc;
    private final AppSession   appSession;

    public AcquisitionService(JdbcTemplate jdbc, AppSession appSession) {
        this.jdbc       = jdbc;
        this.appSession = appSession;
    }

    // ── Batch number allocation ────────────────────────────────────────────

    /**
     * Allocates and reserves the next FA acquisition batch number.
     *
     * Priority:
     *   1. Read+increment CPCOYCO.fa_last_batch_no (primary — mirrors COBOL)
     *   2. MAX(CMBATCH.batch_no)+1 (fallback if CPCOYCO unavailable)
     *   3. MAX(FATRXIN.batch_no)+1 (second fallback)
     *   4. 1 (absolute fallback)
     */
    public int nextBatchNo(int companyNo) {
        try {
            Integer last = jdbc.queryForObject(
                FaSql.FIND_FA_LAST_BATCH_NO, Integer.class, companyNo);
            int next = (last != null ? last : 0) + 1;
            jdbc.update(FaSql.UPDATE_FA_LAST_BATCH_NO, next, companyNo);
            return next;
        } catch (Exception e) {
            logWarn("nextBatchNo: CPCOYCO read failed, falling back — " + e.getMessage());
        }
        try {
            Integer m = jdbc.queryForObject(
                FaSql.FIND_NEXT_CMBATCH_NO, Integer.class, companyNo);
            if (m != null && m > 1) return m;
        } catch (Exception ignored) {}
        try {
            Integer m = jdbc.queryForObject(
                FaSql.FIND_NEXT_FATRXIN_BATCH_NO, Integer.class, companyNo);
            if (m != null) return m;
        } catch (Exception ignored) {}
        return 1;
    }

    // ── Batch header queries ───────────────────────────────────────────────

    /**
     * Load an existing batch from CMBATCH. Returns empty if not found.
     * Caller should check BatchInfo.isOpen() before allowing resume.
     */
    public Optional<BatchInfo> loadBatch(int companyNo, int batchNo) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                FaSql.FIND_BATCH_BY_PK, companyNo, batchNo);
            return Optional.of(mapBatchInfo(row));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * List all unposted FA batches for this company, newest first.
     */
    public List<BatchInfo> loadUnpostedBatches(int companyNo) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                FaSql.FIND_UNPOSTED_BATCHES, companyNo);
            List<BatchInfo> result = new ArrayList<>();
            for (Map<String, Object> row : rows) result.add(mapBatchInfo(row));
            return result;
        } catch (Exception e) {
            logWarn("loadUnpostedBatches failed: " + e.getMessage());
            return List.of();
        }
    }

    private BatchInfo mapBatchInfo(Map<String, Object> row) {
        BatchInfo b = new BatchInfo();
        b.batchNo = ((Number) row.get("batch_no")).intValue();
        Object bd = row.get("batch_date");
        if (bd instanceof java.sql.Date d) b.batchDate = d.toLocalDate();
        else if (bd instanceof LocalDate ld) b.batchDate = ld;
        b.enteredBy = str(row, "entered_by");
        b.ref       = str(row, "ref");
        b.status    = str(row, "batch_status");
        return b;
    }

    // ── Transaction list ───────────────────────────────────────────────────

    /**
     * Load all AQ transactions for a batch, joined to FAASSET for display columns.
     * Returns lightweight rows suitable for the P1 TableView.
     */
    public List<Map<String, Object>> loadBatchTransactions(int companyNo, int batchNo) {
        return jdbc.queryForList(FaSql.FIND_BATCH_TRANSACTIONS, companyNo, batchNo);
    }

    /** Count AQ transactions in a batch — used for post confirmation dialog. */
    public int countBatchTransactions(int companyNo, int batchNo) {
        Integer n = jdbc.queryForObject(
            FaSql.COUNT_BATCH_TRANSACTIONS, Integer.class, companyNo, batchNo);
        return n != null ? n : 0;
    }

    // ── Validation ────────────────────────────────────────────────────────

    /**
     * Validate all code fields for an acquisition.
     * Returns a non-empty error message if any code is invalid, null if all valid.
     * Mirrors COBOL CHECK-xxx-CODE validation in FAAQ01.
     */
    public String validateCodes(int companyNo, AcquisitionRecord rec) {
        for (String[] chk : new String[][]{
            {rec.locCode,    "FACODLO", "loc_code",        "Location"},
            {rec.deptCode,   "FACODDT", "dept_code",       "Department"},
            {rec.grpCode,    "FACODGR", "grp_code",        "Group"},
            {rec.subgrpCode, "FACODSG", "subgrp_code",     "Sub-group"},
            {rec.site,       "FACODSS", "stake_site_code", "Stocktake site"},
            {rec.taxCode,    "FACODDN", "depn_code",       "Tax depn code"},
            {rec.bookCode,   "FACODDN", "depn_code",       "Book depn code"},
        }) {
            if (!chk[0].isBlank()) {
                Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + chk[1] +
                    " WHERE company_no=? AND " + chk[2] + "=?",
                    Integer.class, companyNo, chk[0]);
                if (cnt == null || cnt == 0)
                    return chk[3] + " '" + chk[0] + "' not on file.";
            }
        }
        if (!rec.attachTo.isBlank()) {
            Integer cnt = jdbc.queryForObject(
                FaSql.COUNT_FAASSET_BY_PK, Integer.class, companyNo, rec.attachTo);
            if (cnt == null || cnt == 0)
                return "Attached-to asset '" + rec.attachTo + "' not on file.";
        }
        return null; // valid
    }

    /**
     * Check whether an asset number already exists on file (non-U status).
     * Returns error message if duplicate, null if clear.
     */
    public String checkDuplicate(int companyNo, String assetNo) {
        Integer n = jdbc.queryForObject(
            FaSql.COUNT_ASSET_BY_PK_NOT_UNPOSTED, Integer.class, companyNo, assetNo);
        return (n != null && n > 0)
            ? "Asset No '" + assetNo + "' already on file."
            : null;
    }

    /**
     * Check whether an asset is already in the batch.
     * Returns error message if present, null if clear.
     */
    public String checkAlreadyInBatch(int companyNo, int batchNo, String assetNo) {
        Integer n = jdbc.queryForObject(
            FaSql.COUNT_ASSET_IN_BATCH, Integer.class, companyNo, batchNo, assetNo);
        return (n != null && n > 0)
            ? "Asset No '" + assetNo + "' is already in this batch."
            : null;
    }

    // ── Save (Add / Edit) ─────────────────────────────────────────────────

    /**
     * Validate codes, then insert or update FAASSET + FATRXIN.
     *
     * @return null on success, or an error message string on failure.
     *         Caller displays the message and does NOT close the dialog.
     */
    @Transactional
    public String save(int companyNo, int batchNo, boolean isAdd,
                       AcquisitionRecord rec, String userId) {

        // DB-side code validation (runs for both Add and Edit)
        String codeErr = validateCodes(companyNo, rec);
        if (codeErr != null) return codeErr;

        if (isAdd) {
            String dupErr = checkDuplicate(companyNo, rec.assetNo);
            if (dupErr != null) return dupErr;
            String batchErr = checkAlreadyInBatch(companyNo, batchNo, rec.assetNo);
            if (batchErr != null) return batchErr;
            insertAsset(companyNo, batchNo, rec, userId);
            insertBatchLine(companyNo, batchNo, rec, userId);
        } else {
            updateAsset(companyNo, rec, userId);
            updateBatchLine(companyNo, batchNo, rec.assetNo, rec.acqnDate, userId);
        }
        return null; // success
    }

    private void insertAsset(int companyNo, int batchNo,
                              AcquisitionRecord r, String userId) {
        java.sql.Date today     = today();
        java.sql.Date acqnDate  = sqlDate(r.acqnDate);
        java.sql.Date startTax  = sqlDate(r.startTax);
        java.sql.Date startBook = sqlDate(r.startBook);
        java.sql.Date writeDown = sqlDate(r.writeDown);
        java.sql.Date replAsAt  = sqlDate(r.replAsAt);
        java.sql.Date lseExpiry = sqlDate(r.lseExpiry);

        jdbc.update(
            FaSql.INSERT_ASSET_FULL,
            companyNo, r.assetNo, r.alpha,
            r.locCode, r.grpCode, r.subgrpCode, r.assetNo,
            r.deptCode, r.grpCode, r.subgrpCode, r.assetNo,
            r.grpCode, r.subgrpCode, r.assetNo,
            r.attachTo, r.attachTo,
            r.desc1, r.desc2, r.locCode, r.deptCode, r.grpCode, r.subgrpCode,
            "U", "",
            r.site, acqnDate, r.acqnType, r.qty, r.intOrder,
            r.suppNo, r.suppNo, r.invoiceNo,
            r.leased ? "Y" : "N", r.contractNo, r.payAmt, r.payFreq,
            lseExpiry, r.residual, r.contractVal,
            r.currIns, r.replNew, replAsAt, r.insType,
            BigDecimal.ZERO, r.actualCost, r.taxDepnCost, r.bookDepnCost, BigDecimal.ZERO,
            writeDown,
            r.taxMethod, r.taxCode, r.taxFreq,
            r.taxRate1, r.taxRate2, r.taxCalcInd, r.taxCalcBase,
            startTax, BigDecimal.ZERO, BigDecimal.ZERO,
            ZERO_DATE, ZERO_DATE,
            r.bookMethod, r.bookCode, r.bookFreq,
            r.bookRate1, r.bookRate2, r.bookCalcInd, r.bookCalcBase,
            startBook, BigDecimal.ZERO, BigDecimal.ZERO,
            ZERO_DATE, ZERO_DATE,
            ZERO_DATE, BigDecimal.ZERO, BigDecimal.ZERO,
            ZERO_DATE, BigDecimal.ZERO,
            ZERO_DATE, ZERO_DATE,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, "", "",
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            r.pooled ? "Y" : "N", BigDecimal.ZERO, ZERO_DATE,
            BigDecimal.ZERO, ZERO_DATE, "N",
            "C".equals(r.postTo) ? "Y" : "N",
            "B".equals(r.postTo) ? "Y" : "N",
            "", r.ledgerCode,
            "", "",
            0L,
            userId, today, 0, 0, 0, 0);
    }

    private void insertBatchLine(int companyNo, int batchNo,
                                  AcquisitionRecord r, String userId) {
        java.sql.Date today    = today();
        java.sql.Date acqnDate = sqlDate(r.acqnDate);
        jdbc.update(
            FaSql.INSERT_BATCH_LINE_AQ,
            companyNo, batchNo, r.assetNo, r.assetNo,
            acqnDate, batchNo, batchNo, today, 0,
            BigDecimal.ZERO, 0, "", "",
            0, ZERO_DATE, userId,
            "N", "N", "N", "N",
            userId, today, 0, 0, 0, 0);
    }

    private void updateAsset(int companyNo, AcquisitionRecord r, String userId) {
        java.sql.Date today     = today();
        java.sql.Date acqnDate  = sqlDate(r.acqnDate);
        java.sql.Date lseExpiry = sqlDate(r.lseExpiry);
        java.sql.Date replAsAt  = sqlDate(r.replAsAt);
        java.sql.Date writeDown = sqlDate(r.writeDown);
        java.sql.Date startTax  = sqlDate(r.startTax);
        java.sql.Date startBook = sqlDate(r.startBook);

        jdbc.update(
            FaSql.UPDATE_ASSET_FULL,
            r.desc1, r.desc2, r.alpha,
            r.locCode, r.grpCode, r.subgrpCode, r.assetNo,
            r.deptCode, r.grpCode, r.subgrpCode, r.assetNo,
            r.grpCode, r.subgrpCode, r.assetNo,
            r.attachTo, r.attachTo,
            r.locCode, r.deptCode, r.grpCode, r.subgrpCode,
            r.site, r.pooled ? "Y" : "N", r.qty, acqnDate, r.acqnType,
            r.suppNo, r.suppNo, r.invoiceNo,
            r.leased ? "Y" : "N", lseExpiry, r.contractNo,
            r.payAmt, r.payFreq,
            r.currIns, r.replNew, replAsAt, r.insType,
            r.actualCost, r.taxDepnCost, r.bookDepnCost,
            writeDown, r.taxMethod, r.taxCode,
            r.taxFreq, r.taxRate1, r.taxRate2,
            startTax, startBook,
            r.bookMethod, r.bookCode,
            r.bookFreq, r.bookRate1, r.bookRate2,
            "C".equals(r.postTo) ? "Y" : "N",
            "B".equals(r.postTo) ? "Y" : "N",
            "", r.ledgerCode,
            "", "",
            userId, today, 0, 0, 0, 0,
            companyNo, r.assetNo);
    }

    private void updateBatchLine(int companyNo, int batchNo,
                                  String assetNo, LocalDate acqnDate, String userId) {
        jdbc.update(
            FaSql.UPDATE_BATCH_LINE_DATE,
            sqlDate(acqnDate), companyNo, batchNo, assetNo);
    }

    // ── Delete ────────────────────────────────────────────────────────────

    /**
     * Delete a transaction from the batch (FATRXIN only).
     * FAASSET is left with status 'U' — the asset itself is not deleted here
     * as it may have been pre-existing. Mirrors COBOL DELETE-THIS-ASSET.
     */
    @Transactional
    public void deleteBatchLine(int companyNo, int batchNo, String assetNo) {
        jdbc.update(FaSql.DELETE_BATCH_LINE, companyNo, batchNo, assetNo);
    }

    // ── Excel export ──────────────────────────────────────────────────────

    /**
     * Load all assets in the batch for Excel export.
     * Returns raw column map — the export service formats the workbook.
     */
    public List<Map<String, Object>> loadBatchAssetsForExport(int companyNo, int batchNo) {
        return jdbc.queryForList(FaSql.FIND_BATCH_ASSETS_FOR_EXPORT, companyNo, batchNo);
    }

    // ── Excel import ──────────────────────────────────────────────────────

    /**
     * Result of a single-asset import operation.
     */
    public enum ImportOutcome { INSERTED, UPDATED, SKIPPED_DUPLICATE, SKIPPED_ERROR }

    public record ImportResult(ImportOutcome outcome, String message) {}

    /**
     * Import a single asset from Excel. Checks duplicates, then inserts or updates.
     * acqn_excel_load_flag is set to 'Y' for imported rows (differs from manual entry).
     */
    @Transactional
    public ImportResult importAsset(int companyNo, int batchNo,
                                     AcquisitionRecord rec, String userId) {
        // Check existing (non-unposted) asset with same no
        Integer existsPosted = jdbc.queryForObject(
            FaSql.COUNT_ASSET_BY_PK_NOT_UNPOSTED, Integer.class, companyNo, rec.assetNo);
        if (existsPosted != null && existsPosted > 0)
            return new ImportResult(ImportOutcome.SKIPPED_DUPLICATE,
                "Asset '" + rec.assetNo + "' already on file (skipped)");

        // Check if already in this batch — UPDATE instead of INSERT
        Integer inBatch = jdbc.queryForObject(
            FaSql.COUNT_ASSET_IN_BATCH, Integer.class, companyNo, batchNo, rec.assetNo);

        if (inBatch != null && inBatch > 0) {
            updateAssetForImport(companyNo, rec, userId);
            return new ImportResult(ImportOutcome.UPDATED, "Updated: " + rec.assetNo);
        } else {
            insertAssetForImport(companyNo, batchNo, rec, userId);
            insertBatchLineExcelFlag(companyNo, batchNo, rec, userId);
            return new ImportResult(ImportOutcome.INSERTED, "Inserted: " + rec.assetNo);
        }
    }

    private void updateAssetForImport(int companyNo, AcquisitionRecord r, String userId) {
        java.sql.Date today    = today();
        java.sql.Date acqnDate = sqlDate(r.acqnDate);
        jdbc.update(
            FaSql.UPDATE_ASSET_FROM_IMPORT,
            r.desc1, r.desc2, r.alpha,
            r.locCode, r.grpCode, r.subgrpCode, r.assetNo,
            r.deptCode, r.grpCode, r.subgrpCode, r.assetNo,
            r.grpCode, r.subgrpCode, r.assetNo,
            r.locCode, r.deptCode, r.grpCode, r.subgrpCode,
            r.site, r.actualCost, acqnDate, r.acqnType,
            r.suppNo, r.suppNo, r.invoiceNo,
            r.taxMethod, r.taxCode, r.taxRate1, r.taxRate2,
            r.bookMethod, r.bookCode, r.bookRate1, r.bookRate2,
            "C".equals(r.postTo) ? "Y" : "N", r.ledgerCode,
            userId, today,
            companyNo, r.assetNo);
    }

    private void insertAssetForImport(int companyNo, int batchNo,
                                       AcquisitionRecord r, String userId) {
        java.sql.Date today    = today();
        java.sql.Date acqnDate = sqlDate(r.acqnDate);
        jdbc.update(
            FaSql.INSERT_ASSET_FROM_IMPORT,
            companyNo, r.assetNo, r.alpha,
            r.locCode, r.grpCode, r.subgrpCode, r.assetNo,
            r.deptCode, r.grpCode, r.subgrpCode, r.assetNo,
            r.grpCode, r.subgrpCode, r.assetNo,
            r.attachTo, r.attachTo,
            r.desc1, r.desc2, r.locCode, r.deptCode, r.grpCode, r.subgrpCode,
            "U", "", r.site,
            acqnDate, r.acqnType, r.qty, r.intOrder,
            r.suppNo, r.suppNo, r.invoiceNo,
            "N", "", BigDecimal.ZERO, "",
            ZERO_DATE, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, ZERO_DATE, "",
            BigDecimal.ZERO, r.actualCost, r.taxDepnCost, r.bookDepnCost, BigDecimal.ZERO,
            ZERO_DATE, r.taxMethod, r.taxCode, 0, r.taxRate1, r.taxRate2, "", "",
            ZERO_DATE, BigDecimal.ZERO, BigDecimal.ZERO, ZERO_DATE, ZERO_DATE,
            r.bookMethod, r.bookCode, 0, r.bookRate1, r.bookRate2, "", "",
            ZERO_DATE, BigDecimal.ZERO, BigDecimal.ZERO, ZERO_DATE, ZERO_DATE,
            ZERO_DATE, BigDecimal.ZERO, BigDecimal.ZERO,
            ZERO_DATE, BigDecimal.ZERO, ZERO_DATE, ZERO_DATE,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, "", "",
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            "N".equals(r.pooled ? "N" : "Y") ? "N" : "Y",
            BigDecimal.ZERO, ZERO_DATE, BigDecimal.ZERO, ZERO_DATE, "N",
            "C".equals(r.postTo) ? "Y" : "N",
            "B".equals(r.postTo) ? "Y" : "N",
            "", r.ledgerCode, "", "", 0L,
            userId, today, 0, 0, 0, 0);
    }

    private void insertBatchLineExcelFlag(int companyNo, int batchNo,
                                           AcquisitionRecord r, String userId) {
        java.sql.Date today    = today();
        java.sql.Date acqnDate = sqlDate(r.acqnDate);
        jdbc.update(
            FaSql.INSERT_BATCH_LINE_AQ_EXCEL,
            companyNo, batchNo, r.assetNo, r.assetNo,
            acqnDate, batchNo, batchNo, today, 0,
            BigDecimal.ZERO, 0, "", "",
            0, ZERO_DATE, userId,
            "N", "N", "N", "Y",  // acqn_excel_load_flag = Y for imported rows
            userId, today, 0, 0, 0, 0);
    }

    // ── Post batch ────────────────────────────────────────────────────────

    public record PostResult(int posted, String error) {
        public boolean success() { return error == null; }
    }

    /**
     * Post all unposted acquisitions in the batch.
     *
     * For each asset:
     *   1. Write FATRANS AQ record
     *   2. Activate FAASSET: status 'U' → ''
     * Then:
     *   3. Mark CMBATCH as completed (INSERT or UPDATE)
     *
     * @Transactional — all-or-nothing. If any asset fails, the whole batch rolls back.
     */
    @Transactional
    public PostResult postBatch(int companyNo, int batchNo, String userId) {
        java.sql.Date today = today();

        List<Map<String, Object>> assets = jdbc.queryForList(
            FaSql.FIND_BATCH_ASSETS_FOR_POST, companyNo, batchNo);

        if (assets.isEmpty())
            return new PostResult(0, "No unposted assets found in batch " + batchNo +
                ". Batch may already have been posted.");

        int posted = 0;
        for (Map<String, Object> a : assets) {
            String assetNo     = a.get("asset_no").toString();
            BigDecimal cost    = dec(a, "actual_cost");
            BigDecimal taxCost = dec(a, "tax_depn_cost");
            BigDecimal bkCost  = dec(a, "book_depn_cost");
            String locCode     = str(a, "loc_code");
            String deptCode    = str(a, "dept_code");
            String grpCode     = str(a, "grp_code");
            String subgrp      = str(a, "subgrp_code");
            java.sql.Date acqnDate = a.get("acqn_date") instanceof java.sql.Date d ? d : today;
            String dateAssetNo = new java.text.SimpleDateFormat("yyyyMMdd").format(acqnDate) + assetNo;

            // Write FATRANS AQ record — all 78 columns
            jdbc.update(
                FaSql.INSERT_FATRANS_AQ,
                companyNo, dateAssetNo, acqnDate, assetNo,
                "AQ", acqnDate, batchNo, "", "", "N",
                cost, bkCost,
                locCode, deptCode, grpCode, subgrp,
                BigDecimal.ZERO, BigDecimal.ZERO, taxCost,
                str(a,"tax_depn_method"), str(a,"tax_depn_code"),
                toInt(a,"tax_depn_freq"), dec(a,"tax_depn_rate_1"),
                BigDecimal.ZERO,
                ZERO_DATE, locCode, deptCode, grpCode, subgrp,
                str(a,"tax_depn_calc_ind"), str(a,"tax_depn_calc_base"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                "", "", "", "",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "", "", "", "",
                "", "", "", "",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "", "", "", "",
                BigDecimal.ZERO,
                "", "", "", "",
                BigDecimal.ZERO, BigDecimal.ZERO,
                0L, userId, today,
                0, 0, 0, 0,
                "Acquisition");

            // Activate asset: 'U' → ''
            jdbc.update(
                FaSql.UPDATE_ASSET_ACTIVATE,
                userId, today, companyNo, assetNo);

            posted++;
        }

        // Mark CMBATCH completed
        Integer batchExists = jdbc.queryForObject(
            FaSql.COUNT_CMBATCH_BY_PK, Integer.class, companyNo, batchNo);
        if (batchExists != null && batchExists > 0) {
            jdbc.update(FaSql.UPDATE_CMBATCH_COMPLETED, companyNo, batchNo);
        } else {
            jdbc.update(
                FaSql.INSERT_CMBATCH_COMPLETED,
                companyNo, batchNo, today,
                userId.substring(0, Math.min(3, userId.length())));
        }

        return new PostResult(posted, null);
    }

    // ── Asset full load ───────────────────────────────────────────────────

    /**
     * Load the full FAASSET record for edit pre-fill.
     * Returns empty if not found.
     */
    public Optional<AssetFullRecord> loadFullAsset(int companyNo, String assetNo) {
        try {
            Map<String, Object> a = jdbc.queryForMap(
                FaSql.FIND_FAASSET_FULL_BY_PK, companyNo, assetNo);
            AssetFullRecord r = new AssetFullRecord();
            r.assetNo      = assetNo;
            r.desc1        = str(a, "desc_1");
            r.desc2        = str(a, "desc_2");
            r.alpha        = str(a, "alpha_code");
            r.locCode      = str(a, "loc_code");
            r.deptCode     = str(a, "dept_code");
            r.grpCode      = str(a, "grp_code");
            r.subgrpCode   = str(a, "subgrp_code");
            r.site         = str(a, "stake_site");
            r.attachTo     = str(a, "attach_to_asset_no");
            r.acqnType     = str(a, "acqn_type");
            r.intOrder     = str(a, "internal_order_no");
            r.suppName     = str(a, "supp_name");
            r.suppNo       = str(a, "supp_no");
            r.suppInv      = str(a, "supp_inv_no");
            r.pooled       = "Y".equals(str(a, "asset_pool_flag"));
            r.leased       = "Y".equals(str(a, "leased_asset_flag"));
            r.contractNo   = str(a, "lse_contract_no");
            r.payFreq      = str(a, "lse_paymt_freq");
            r.insType      = str(a, "ins_type");
            r.taxDepnCost  = dec(a, "tax_depn_cost");
            r.bookDepnCost = dec(a, "book_depn_cost");
            r.taxMethod    = str(a, "tax_depn_method");
            r.taxCode      = str(a, "tax_depn_code");
            r.taxCalcInd   = str(a, "tax_depn_calc_ind");
            r.taxCalcBase  = str(a, "tax_depn_calc_base");
            r.taxFreq      = str(a, "tax_depn_freq");
            r.bookMethod   = str(a, "book_depn_method");
            r.bookCode     = str(a, "book_depn_code");
            r.bookCalcInd  = str(a, "book_depn_calc_ind");
            r.bookCalcBase = str(a, "book_depn_calc_base");
            r.bookFreq     = str(a, "book_depn_freq");
            r.postTo       = "Y".equals(str(a,"post_depn_to_cl")) ? "C"
                           : "Y".equals(str(a,"post_depn_to_ba")) ? "B" : "N";
            r.ledgerCode   = str(a, "ledger_code");
            r.payAmt       = dec(a, "lse_paymt_amt");
            r.residual     = dec(a, "lse_resid_val");
            r.contractVal  = dec(a, "lse_tot_contr_val");
            r.currIns      = dec(a, "curr_ins_val");
            r.replNew      = dec(a, "repl_new_val");
            r.disposalVal  = dec(a, "disposal_val");
            r.taxRate1     = dec(a, "tax_depn_rate_1");
            r.taxRate2     = dec(a, "tax_depn_rate_2");
            r.bookRate1    = dec(a, "book_depn_rate_1");
            r.bookRate2    = dec(a, "book_depn_rate_2");
            r.accumTaxDepn    = dec(a, "accum_tax_depn");
            r.accumTaxAdj     = dec(a, "accum_tax_depn_adj");
            r.lastTaxRevalVal = dec(a, "last_tax_reval_val");
            r.accumBookDepn   = dec(a, "accum_book_depn");
            r.accumBookAdj    = dec(a, "accum_book_depn_adj");
            r.lastBookRevalVal= dec(a, "last_reval_val");
            r.poolTaxBal      = dec(a, "pool_tax_bal");
            r.poolBookBal     = dec(a, "pool_book_bal");
            Object qtyObj = a.get("qty");
            r.qty = qtyObj instanceof Number n ? n.intValue() : 1;
            r.acqnDate     = toLocalDate(a, "acqn_date");
            r.replAsAt     = toLocalDate(a, "repl_val_as_at_date");
            r.lseExpiry    = toLocalDate(a, "lse_expiry_date");
            r.writeDown    = toLocalDate(a, "write_down_date");
            r.startTax     = toLocalDate(a, "start_tax_depn_date");
            r.startBook    = toLocalDate(a, "start_depn_date");
            r.lastTaxDepn  = toLocalDate(a, "last_tax_depn_date");
            r.lastTaxReval = toLocalDate(a, "last_tax_reval_date");
            r.lastBookDepn = toLocalDate(a, "last_book_depn_date");
            r.lastBookReval= toLocalDate(a, "last_reval_date");
            return Optional.of(r);
        } catch (Exception e) {
            logWarn("loadFullAsset failed for " + assetNo + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    // ── Lookup helpers ────────────────────────────────────────────────────

    /**
     * Look up desc1 for a code in a codes table.
     * Returns empty string if not found or blank code.
     */
    public String lookupDesc(int companyNo, String table, String keyCol, String code) {
        if (code == null || code.isBlank()) return "";
        try {
            return jdbc.queryForObject(
                "SELECT desc1 FROM " + table +
                " WHERE " + keyCol + "=? AND company_no=?",
                String.class, code, companyNo);
        } catch (Exception e) { return ""; }
    }

    /**
     * Look up supplier name from APSUPPS.
     * Returns empty string if not found.
     */
    public String lookupSupplier(int companyNo, String suppNo) {
        if (suppNo == null || suppNo.isBlank()) return "";
        try {
            return jdbc.queryForObject(
                FaSql.FIND_SUPPLIER_NAME_BY_PK, String.class, suppNo, companyNo);
        } catch (Exception e) { return ""; }
    }

    /**
     * Validate a single code field against a codes table.
     * Returns error message if invalid, null if blank (optional field) or valid.
     * Used for focus-out validation in the controller.
     */
    public String validateCode(int companyNo, String code,
                                String table, String keyCol, String fieldName) {
        if (code == null || code.isBlank()) return null;
        try {
            Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table +
                " WHERE company_no=? AND " + keyCol + "=?",
                Integer.class, companyNo, code);
            return (cnt != null && cnt > 0) ? null
                : fieldName + " '" + code + "' not on file.";
        } catch (Exception e) {
            logWarn("validateCode failed for " + fieldName + ": " + e.getMessage());
            return null; // non-fatal — DB error doesn't block entry
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private static java.sql.Date today() {
        return java.sql.Date.valueOf(LocalDate.now());
    }

    private static java.sql.Date sqlDate(LocalDate ld) {
        return ld != null ? java.sql.Date.valueOf(ld) : ZERO_DATE;
    }

    private static BigDecimal dec(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        try { return new BigDecimal(v.toString()); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString().trim();
    }

    private static int toInt(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    private static LocalDate toLocalDate(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        LocalDate ld = (v instanceof java.sql.Date d) ? d.toLocalDate()
            : LocalDate.parse(v.toString());
        return (ld.getYear() <= 1900) ? null : ld;
    }

    private static void logWarn(String msg) {
        System.out.println("AcquisitionService WARN: " + msg);
    }
}
