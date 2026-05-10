package com.landmarksoftware.service;

import com.landmarksoftware.model.AssetBarCode;
import com.landmarksoftware.model.AssetListRow;
import com.landmarksoftware.model.AssetMaintenanceRecord;
import com.landmarksoftware.repository.AssetMaintenanceRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * FAAS01 — Asset Maintenance service layer.
 *
 * Encapsulates all business logic from faas01.pl:
 *
 *  GET-S1-DISPLAYS   → loadDisplayDescriptions()
 *  CHECK-ASSET-NO    → validateForEdit()
 *  WRITE-CHANGE-AUDIT-RECORD  → saveWithAudit()
 *  WRITE-STATUS-AUDIT-RECORD  → saveStatusChange()
 *  CHECK-ATTACH-TO-ASSET-NO   → validateAttachToAsset()
 *
 * The service is deliberately thin on depn-change logic (S3 → calls FAAS04)
 * since that is a separate batch program; we capture parameters and flag for
 * a stub/out-of-scope implementation note.
 */
@Service
public class AssetMaintenanceService {

    private final AssetMaintenanceRepository repo;

    public AssetMaintenanceService(AssetMaintenanceRepository repo) {
        this.repo = repo;
    }

    // ── List screen ───────────────────────────────────────────────────

    public List<AssetListRow> getAssetList(int companyNo) {
        return repo.findAllForList(companyNo);
    }

    // ── Load single asset with display descriptions ────────────────────

    /**
     * Mirrors COBOL GET-S1-DISPLAYS.
     * Reads FAASSET and populates all the derived display fields:
     * loc_desc, dept_desc, grp_desc, subgrp_desc, stake_site_desc,
     * ins_type_desc, tax/book depn method/code descriptions.
     */
    public Optional<AssetMaintenanceRecord> loadAsset(int companyNo, String assetNo) {
        return repo.findByAssetNo(companyNo, assetNo)
            .map(r -> {
                loadDescriptions(companyNo, r);
                r.setHasUnpostedTrx(repo.hasUnpostedTransactions(companyNo, assetNo));
                return r;
            });
    }

    private void loadDescriptions(int companyNo, AssetMaintenanceRecord r) {
        r.setLocDesc(lookupDesc("FACODLO", "loc_code",        "desc1", companyNo, r.getLocCode()));
        r.setDeptDesc(lookupDesc("FACODDT", "dept_code",       "desc1", companyNo, r.getDeptCode()));
        r.setGrpDesc(lookupDesc("FACODGR", "grp_code",        "desc1", companyNo, r.getGrpCode()));
        r.setSubgrpDesc(lookupDesc("FACODSG", "subgrp_code",   "desc1", companyNo, r.getSubgrpCode()));
        r.setStakeSiteDesc(lookupDesc("FACODSS", "stake_site_code", "desc1", companyNo, r.getStakeSite()));
        if (!r.getInsType().isBlank())
            r.setInsTypeDesc(lookupDesc("FACODIN", "ins_type_code", "desc1", companyNo, r.getInsType()));
        r.setTaxDepnMethodDesc(methodDesc(r.getTaxDepnMethod()));
        r.setBookDepnMethodDesc(methodDesc(r.getBookDepnMethod()));
        if (!"Y".equals(r.getLeasedAssetFlag()) && !r.getTaxDepnCode().isBlank())
            r.setTaxDepnCodeDesc(lookupDesc("FACODDN", "depn_code", "desc1", companyNo, r.getTaxDepnCode()));
        if (!"Y".equals(r.getLeasedAssetFlag()) && !r.getBookDepnCode().isBlank())
            r.setBookDepnCodeDesc(lookupDesc("FACODDN", "depn_code", "desc1", companyNo, r.getBookDepnCode()));
        r.setStatusDesc(r.statusDisplayDesc());
    }

    private String lookupDesc(String table, String codeCol, String descCol,
                               int companyNo, String value) {
        if (value == null || value.isBlank()) return "";
        return repo.lookupDesc(table, codeCol, descCol, companyNo, value)
            .orElse("** NOT ON FILE **");
    }

    private String methodDesc(String method) {
        if (method == null || method.isBlank()) return "";
        return switch (method) {
            case "S" -> "STRAIGHT-LINE";
            case "D" -> "DIMINISHING";
            default  -> method;
        };
    }

    // ── Validation ────────────────────────────────────────────────────

    /**
     * Mirrors COBOL CHECK-ASSET-NO.
     * Returns a validation message (non-null = error), or null = OK.
     * Also sets whether the screen should switch to inquire mode.
     */
    public record ValidationResult(String message, boolean inquireOnly) {}

    public ValidationResult validateForEdit(int companyNo, String assetNo) {
        var asset = repo.findByAssetNo(companyNo, assetNo);
        if (asset.isEmpty()) return new ValidationResult("NOT ON FILE", false);

        var r = asset.get();
        if ("U".equals(r.getAssetStatus()) && !"Y".equals(r.getAssetPoolFlag()))
            return new ValidationResult("ASSET HAS NOT BEEN POSTED", false);
        if ("U".equals(r.getAssetStatus()) && "Y".equals(r.getAssetPoolFlag())
            && !"Y".equals(r.getPoolAcqnPostedFlag()))
            return new ValidationResult("ASSET HAS NOT BEEN POSTED", false);
        if ("R".equals(r.getAssetStatus()))
            return new ValidationResult("ASSET HAS BEEN RETIRED", false);

        if (repo.hasUnpostedTransactions(companyNo, assetNo))
            return new ValidationResult("ASSET HAS UNPOSTED TRANSACTIONS\nNO CHANGES ALLOWED", true);

        return null; // OK
    }

    /**
     * Validates attach-to asset no (COBOL CHECK-ATTACH-TO-ASSET-NO).
     * Returns null if OK, or an error message string.
     */
    public String validateAttachTo(int companyNo, AssetMaintenanceRecord current) {
        String attachNo = current.getAttachToAssetNo();
        if (attachNo == null || attachNo.isBlank()) return null; // no attachment = OK

        var target = repo.findByAssetNo(companyNo, attachNo);
        if (target.isEmpty()) return "ATTACH TO ASSET NO NOT ON FILE";

        var t = target.get();
        if (!t.getAttachToAssetNo().isBlank()) return "THIS ASSET ALREADY ATTACHED TO ANOTHER";
        if (!t.getLocCode().equals(current.getLocCode())
            || !t.getGrpCode().equals(current.getGrpCode())
            || !t.getSubgrpCode().equals(current.getSubgrpCode())
            || !t.getDeptCode().equals(current.getDeptCode()))
            return "NOT THE SAME LOC/GRP ETC AS ATTACHED ASSET";

        return null;
    }

    // ── Save with audit ────────────────────────────────────────────────

    /**
     * WRITE-CHANGE-AUDIT-RECORD + CHANGE-FAASSET.
     * Saves the asset and writes a type-C audit record.
     * beforeData is the serialised before-record snapshot from the UI.
     */
    public void saveWithAudit(AssetMaintenanceRecord record, String beforeSnapshot) {
        String afterSnapshot = snapshot(record);
        if (!afterSnapshot.equals(beforeSnapshot)) {
            writeAudit(record.getCompanyNo(), record.getAssetNo(),
                "C", beforeSnapshot, afterSnapshot);
        }
        repo.update(record);
    }

    /**
     * WRITE-STATUS-AUDIT-RECORD + CHANGE-FAASSET.
     * Saves a status-only change with type-S audit.
     */
    public void saveStatusChange(AssetMaintenanceRecord record,
                                  String newStatus, String ref,
                                  String beforeSnapshot) {
        record.setAssetStatus(newStatus.equals("A") ? "" : newStatus);
        record.setAssetStatusRef(ref);
        String afterSnapshot = snapshot(record);
        if (!afterSnapshot.equals(beforeSnapshot)) {
            writeAudit(record.getCompanyNo(), record.getAssetNo(),
                "S", beforeSnapshot, afterSnapshot);
        }
        repo.updateStatus(record.getCompanyNo(), record.getAssetNo(),
            record.getAssetStatus(), ref);
    }

    private void writeAudit(int companyNo, String assetNo, String type,
                              String before, String after) {
        LocalTime now = LocalTime.now();
        int timeInt = now.getHour() * 10000 + now.getMinute() * 100 + now.getSecond();
        repo.writeAuditRecord(companyNo, assetNo, LocalDate.now(), timeInt, type, before, after);
    }

    /** Lightweight serialisation of the asset record for audit comparison. */
    public String snapshot(AssetMaintenanceRecord r) {
        return r.getAssetNo() + "|" + r.getDesc1() + "|" + r.getDesc2() + "|"
            + r.getAlphaCode() + "|" + r.getLocCode() + "|" + r.getDeptCode() + "|"
            + r.getGrpCode() + "|" + r.getSubgrpCode() + "|" + r.getStakeSite() + "|"
            + r.getAttachToAssetNo() + "|" + r.getAcqnType() + "|" + r.getInternalOrderNo() + "|"
            + r.getParentRateInd() + "|" + r.getParentRateCurr() + "|" + r.getParentFcMathsInd() + "|"
            + r.getSupplierName() + "|" + r.getSupplierNo() + "|" + r.getInvoiceNo() + "|"
            + r.getLeasedAssetFlag() + "|" + r.getLseExpiryDate() + "|" + r.getLseContractNo() + "|"
            + r.getLsePaymentAmt() + "|" + r.getLsePaymentFreq() + "|"
            + r.getCurrentInsValue() + "|" + r.getReplNewVal() + "|" + r.getReplValAsAtDate() + "|"
            + r.getInsType() + "|"
            + r.getActualCost() + "|" + r.getTaxDepnCost() + "|" + r.getBookDepnCost() + "|"
            + r.getInterestComponent() + "|" + r.getWriteDownDate() + "|"
            + r.getStartDepnDate() + "|" + r.getStartTaxDepnDate() + "|"
            + r.getTaxDepnMethod() + "|" + r.getTaxDepnCode() + "|"
            + r.getTaxRate1() + "|" + r.getTaxRate2() + "|"
            + r.getBookDepnMethod() + "|" + r.getBookDepnCode() + "|"
            + r.getBookRate1() + "|" + r.getBookRate2() + "|"
            + r.getBookDepnFreq() + "|" + r.getTaxDepnFreq() + "|"
            + r.getPostDepnToClBa() + "|" + r.getLedgerType() + "|" + r.getLedgerCode() + "|"
            + r.getBaLedgerId() + "|" + r.getBaPrimaryCodes() + "|"
            + r.getAssetStatus() + "|" + r.getAssetStatusRef();
    }

    // ── Bar codes ─────────────────────────────────────────────────────

    public List<AssetBarCode> getBarCodes(int companyNo, String assetNo) {
        return repo.findBarCodes(companyNo, assetNo);
    }

    /** Returns null if OK, error message if duplicate. */
    public String addBarCode(int companyNo, String assetNo, String barCode) {
        if (repo.barCodeExists(companyNo, assetNo, barCode))
            return "BAR CODE ALREADY ON FILE";
        repo.addBarCode(companyNo, assetNo, barCode);
        return null;
    }

    public void deleteBarCode(int companyNo, String assetNo, String barCode) {
        repo.deleteBarCode(companyNo, assetNo, barCode);
    }
}
