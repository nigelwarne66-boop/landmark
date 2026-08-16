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
package com.landmarksoftware.service.bas;

import com.landmarksoftware.model.bas.BasTransaction;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.landmarksoftware.db.tables.Cpbasco.CPBASCO;
import static com.landmarksoftware.db.tables.Cpbasgr.CPBASGR;
import static com.landmarksoftware.db.tables.Cpbastx.CPBASTX;
import static com.landmarksoftware.db.tables.Cpcoyco.CPCOYCO;

/**
 * CPBA07 — BAS Transaction (Tax Transaction) Maintenance service. All
 * JDBC/jOOQ for {@code cpbastx} (+ the {@code cpbasgr}/{@code cpbasco}/
 * {@code cpcoyco} cross-checks) lives here — the UI controller is SQL-free
 * apart from the shared {@code GlAccountLookupDialog} it delegates GL account
 * picking/validation to directly (that component owns its own querying).
 *
 * <p>{@code cpbastx} PK is {@code (trx_date, date_added, time_added)} — the
 * latter two are system-assigned at insert. {@code time_added} is packed as
 * {@code HHMMSShh} (hundredths resolution); on the rare collision within the
 * same hundredth, {@link #insert} retries with a freshly-read clock, mirroring
 * the COBOL's spin-retry behaviour.
 *
 * <p>Most {@code cpbastx} rows are written by other modules' posting
 * (AR/AP/CM/Payroll) — those carry a non-blank {@code source}. Columns this
 * screen doesn't expose (bas_no, gst_code, tax_rate, reason_code, the
 * customer- and supplier-detail fields beyond the three summary fields,
 * employee detail, fbt amounts, trx_status, abn, perc_claimable,
 * cmcbdis_seq_no, bas_gross_amt, note_no) are left at blank/zero/sentinel
 * defaults on INSERT and are never touched by UPDATE — CPBA07 in the COBOL
 * doesn't reference them either; {@code bas_no} in particular stays 0 until
 * a separate BAS-run process consolidates unassigned transactions onto a
 * statement (that's CPBA10's job, not this screen's).
 */
@Service
public class BasTransactionService {

    private static final Logger log = LoggerFactory.getLogger(BasTransactionService.class);

    /** The literal CPBA07 BAS-code whitelist — not a pattern, the exact list. */
    public static final List<String> VALID_BAS_CODES = List.of(
        "G1", "G2", "G3", "G4", "G7", "G10", "G11", "G13", "G14", "G15", "G18",
        "W1", "W2", "W3", "W4", "1C", "1D", "1E", "1F", "1G", "7");

    private static final LocalDate SENTINEL_DATE = LocalDate.of(1899, 12, 31);
    private static final int MAX_INSERT_ATTEMPTS = 8;

    private final DSLContext dsl;

    public BasTransactionService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Filter for the CPBA07 list — every field optional (null/blank = no filter on it). */
    public record Filter(
        String basGroup,
        String basCode,
        LocalDate trxDateFrom,
        LocalDate trxDateTo,
        Integer companyNo,
        BigDecimal grossFrom,
        BigDecimal grossTo
    ) {
        public static Filter none() {
            return new Filter(null, null, null, null, null, null, null);
        }
    }

    /** Result of the cpbasco company→BAS-group cross-check. */
    public record CompanyGroupLink(boolean found, String basGroup) {}

    /** A selectable BAS group; {@code toString()} drives ComboBox rendering. */
    public record GroupOption(String code, String label) {
        @Override public String toString() { return label; }
    }

    // ── List ─────────────────────────────────────────────────────────────

    public List<BasTransaction> find(Filter f) {
        Condition where = DSL.trueCondition();
        if (notBlank(f.basGroup()))    where = where.and(CPBASTX.BAS_GROUP.eq(f.basGroup().trim()));
        if (notBlank(f.basCode()))     where = where.and(CPBASTX.BAS_CODE.eq(f.basCode().trim()));
        if (f.trxDateFrom() != null)   where = where.and(CPBASTX.TRX_DATE.ge(f.trxDateFrom()));
        if (f.trxDateTo() != null)     where = where.and(CPBASTX.TRX_DATE.le(f.trxDateTo()));
        if (f.companyNo() != null)     where = where.and(CPBASTX.COMPANY_NO.eq(f.companyNo()));
        if (f.grossFrom() != null)     where = where.and(CPBASTX.TAX_GROSS_AMT.ge(f.grossFrom()));
        if (f.grossTo() != null)       where = where.and(CPBASTX.TAX_GROSS_AMT.le(f.grossTo()));

        List<BasTransaction> rows = new ArrayList<>();
        dsl.selectFrom(CPBASTX)
           .where(where)
           .orderBy(CPBASTX.TRX_DATE.desc(), CPBASTX.DATE_ADDED.desc(), CPBASTX.TIME_ADDED.desc())
           .fetch()
           .forEach(r -> rows.add(map(r)));
        return rows;
    }

    // ── Lookups / cross-checks ───────────────────────────────────────────

    public boolean basGroupExists(String basGroup) {
        if (!notBlank(basGroup)) return false;
        return dsl.fetchExists(dsl.selectFrom(CPBASGR).where(CPBASGR.BAS_GROUP.eq(basGroup.trim())));
    }

    /** Display name for a BAS group, "" if not found. */
    public String basGroupName(String basGroup) {
        if (!notBlank(basGroup)) return "";
        return dsl.select(CPBASGR.BAS_GROUP_NAME)
                  .from(CPBASGR)
                  .where(CPBASGR.BAS_GROUP.eq(basGroup.trim()))
                  .limit(1)
                  .fetchOptional(CPBASGR.BAS_GROUP_NAME)
                  .map(BasTransactionService::trim)
                  .orElse("");
    }

    /** All BAS groups, "code — name", for the filter dialog's group dropdown. */
    public List<GroupOption> basGroupOptions() {
        List<GroupOption> list = new ArrayList<>();
        dsl.selectDistinct(CPBASGR.BAS_GROUP, CPBASGR.BAS_GROUP_NAME)
           .from(CPBASGR)
           .orderBy(CPBASGR.BAS_GROUP)
           .fetch()
           .forEach(r -> {
               String code = trim(r.get(CPBASGR.BAS_GROUP));
               if (!code.isEmpty()) list.add(new GroupOption(code, code + " — " + trim(r.get(CPBASGR.BAS_GROUP_NAME))));
           });
        return list;
    }

    public boolean companyExists(int companyNo) {
        if (companyNo <= 0) return false;
        return dsl.fetchExists(dsl.selectFrom(CPCOYCO).where(CPCOYCO.COMPANY_NO.eq(companyNo)));
    }

    /**
     * cpbasco lookup for {@code alt_company_no = companyNo AND alt_sub_coy_no = 0}
     * (sub-company is always 0 for this cross-check, per the COBOL). Drives
     * the "Company does not belong to a BAS group" / "BAS Group different —
     * reset group?" flow on CPBA07's Company No field.
     */
    public CompanyGroupLink companyGroupLink(int companyNo) {
        Optional<String> group = dsl.select(CPBASCO.BAS_GROUP)
            .from(CPBASCO)
            .where(CPBASCO.ALT_COMPANY_NO.eq(companyNo).and(CPBASCO.ALT_SUB_COY_NO.eq(0)))
            .limit(1)
            .fetchOptional(CPBASCO.BAS_GROUP);
        return group.map(g -> new CompanyGroupLink(true, trim(g)))
                     .orElse(new CompanyGroupLink(false, ""));
    }

    // ── Write operations ─────────────────────────────────────────────────

    /**
     * Insert a new row, stamping the PK's system-assigned {@code date_added}/
     * {@code time_added} and the per-row audit columns. Retries on a
     * timestamp-collision (rare, same-hundredth) up to {@value #MAX_INSERT_ATTEMPTS}
     * times, re-reading the clock each attempt — mirrors COBOL's spin-retry.
     *
     * @return the same instance with {@code dateAdded}/{@code timeAdded} populated.
     */
    @Transactional
    public BasTransaction insert(BasTransaction t, String userId) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < MAX_INSERT_ATTEMPTS; attempt++) {
            LocalDate today = LocalDate.now();
            LocalTime now = LocalTime.now();
            int timeAdded = packTime(now);
            LocalDate auditDate = today;
            LocalTime auditTime = now;

            try {
                dsl.insertInto(CPBASTX)
                   .set(CPBASTX.BAS_GROUP, up(t.basGroup, 3))
                   .set(CPBASTX.BAS_NO, 0)
                   .set(CPBASTX.BAS_CODE, up(t.basCode, 3))
                   .set(CPBASTX.TRX_DATE, t.trxDate)
                   .set(CPBASTX.DATE_ADDED, today)
                   .set(CPBASTX.TIME_ADDED, timeAdded)
                   .set(CPBASTX.COMPANY_NO, t.companyNo)
                   .set(CPBASTX.SUB_COY_NO, 0)
                   // No separate "whole document" context on a hand-keyed line —
                   // trx_gross_amt mirrors tax_gross_amt for manually entered rows.
                   .set(CPBASTX.TRX_GROSS_AMT, z(t.taxGrossAmt))
                   .set(CPBASTX.TAX_GROSS_AMT, z(t.taxGrossAmt))
                   .set(CPBASTX.TAX_AMT, z(t.taxAmt))
                   .set(CPBASTX.GST_CODE, "")
                   .set(CPBASTX.TAX_RATE, BigDecimal.ZERO)
                   .set(CPBASTX.SOURCE, "")            // hand-keyed via CPBA07 ⇒ blank source
                   .set(CPBASTX.BATCH_NO, 0)
                   .set(CPBASTX.TAX_CLEARING_MAIN, t.companyNo > 0 ? z(t.taxClearingMain) : 0)
                   .set(CPBASTX.TAX_CLEARING_SUB,  t.companyNo > 0 ? z(t.taxClearingSub)  : 0)
                   .set(CPBASTX.REASON_CODE, "")
                   .set(CPBASTX.CUST_NO, "")
                   .set(CPBASTX.CUST_DOC_DATE, SENTINEL_DATE)
                   .set(CPBASTX.CUST_DOC_TYPE, "")
                   .set(CPBASTX.CUST_RETENT_FLAG, "")
                   .set(CPBASTX.CUST_DOC_NO, "")
                   .set(CPBASTX.CUST_NAME, "")
                   .set(CPBASTX.CUST_ADDR_1, "")
                   .set(CPBASTX.CUST_ADDR_2, "")
                   .set(CPBASTX.CUST_ADDR_3, "")
                   .set(CPBASTX.SUPPLIER_NO, "")
                   .set(CPBASTX.SUPPLIER_DOC_DATE, SENTINEL_DATE)
                   .set(CPBASTX.SUPPLIER_DOC_TYPE, "")
                   .set(CPBASTX.SUPPLIER_RETENT_FLAG, "")
                   .set(CPBASTX.SUPPLIER_DOC_NO, "")
                   .set(CPBASTX.SUPPLIER_NAME, "")
                   .set(CPBASTX.SUPPLIER_ADDR_1, "")
                   .set(CPBASTX.SUPPLIER_ADDR_2, "")
                   .set(CPBASTX.SUPPLIER_ADDR_3, "")
                   .set(CPBASTX.EMPLOYEE_NO, 0)
                   .set(CPBASTX.EMP_PAYRUN_DATE, SENTINEL_DATE)
                   .set(CPBASTX.EMP_PAY_TYPE, 0)
                   .set(CPBASTX.EMP_PAY_CODE, "")
                   .set(CPBASTX.EMP_PAYRUN_NO, 0)
                   .set(CPBASTX.EMP_LINE_NO, 0)
                   .set(CPBASTX.EMPLOYEE_NAME, "")
                   .set(CPBASTX.FBT_ANNUAL_AMT, BigDecimal.ZERO)
                   .set(CPBASTX.FBT_QUARTERLY_AMT, BigDecimal.ZERO)
                   .set(CPBASTX.TRX_STATUS, "")
                   .set(CPBASTX.REF_1, trim(t.ref1, 40))
                   .set(CPBASTX.REF_2, trim(t.ref2, 40))
                   .set(CPBASTX.ABN, "")
                   .set(CPBASTX.PERC_CLAIMABLE, BigDecimal.ZERO)
                   .set(CPBASTX.CMTRANS_BANK_CODE, "")
                   .set(CPBASTX.CMTRANS_DOC_TYPE, "")
                   .set(CPBASTX.CMTRANS_DOC_NO, 0)
                   .set(CPBASTX.CMCBDIS_SEQ_NO, 0)
                   .set(CPBASTX.POSTING_DATE, t.postingDate)
                   .set(CPBASTX.BAS_GROSS_AMT, BigDecimal.ZERO)
                   .set(CPBASTX.NOTE_NO, 0L)
                   .set(CPBASTX.AUDIT_USER_ID, trim(userId, 15))
                   .set(CPBASTX.AUDIT_DATE, auditDate)
                   .set(CPBASTX.AUDIT_TIME_HR, auditTime.getHour())
                   .set(CPBASTX.AUDIT_TIME_MIN, auditTime.getMinute())
                   .set(CPBASTX.AUDIT_TIME_SEC, auditTime.getSecond())
                   .set(CPBASTX.AUDIT_TIME_HUN, auditTime.getNano() / 10_000_000)
                   .execute();

                t.dateAdded = today;
                t.timeAdded = timeAdded;
                return t;
            } catch (RuntimeException ex) {
                if (isDuplicateKey(ex)) {
                    lastFailure = ex;
                    log.debug("cpbastx insert PK collision on attempt {} — retrying", attempt + 1);
                    continue;
                }
                throw ex;
            }
        }
        throw lastFailure != null ? lastFailure
            : new IllegalStateException("Could not allocate a unique cpbastx timestamp key.");
    }

    /**
     * Update an existing row, keyed by its (unchanged) original PK. Only the
     * CPBA07-editable columns are touched — every other column (including the
     * system-lineage fields) is left exactly as it was.
     */
    @Transactional
    public void update(LocalDate keyTrxDate, LocalDate keyDateAdded, int keyTimeAdded,
                        BasTransaction t, String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        dsl.update(CPBASTX)
           .set(CPBASTX.TRX_DATE, t.trxDate)
           .set(CPBASTX.POSTING_DATE, t.postingDate)
           .set(CPBASTX.TAX_GROSS_AMT, z(t.taxGrossAmt))
           .set(CPBASTX.TAX_AMT, z(t.taxAmt))
           .set(CPBASTX.REF_1, trim(t.ref1, 40))
           .set(CPBASTX.REF_2, trim(t.ref2, 40))
           .set(CPBASTX.BAS_GROUP, up(t.basGroup, 3))
           .set(CPBASTX.BAS_CODE, up(t.basCode, 3))
           .set(CPBASTX.COMPANY_NO, t.companyNo)
           .set(CPBASTX.TAX_CLEARING_MAIN, t.companyNo > 0 ? z(t.taxClearingMain) : 0)
           .set(CPBASTX.TAX_CLEARING_SUB,  t.companyNo > 0 ? z(t.taxClearingSub)  : 0)
           .set(CPBASTX.AUDIT_USER_ID, trim(userId, 15))
           .set(CPBASTX.AUDIT_DATE, today)
           .set(CPBASTX.AUDIT_TIME_HR, now.getHour())
           .set(CPBASTX.AUDIT_TIME_MIN, now.getMinute())
           .set(CPBASTX.AUDIT_TIME_SEC, now.getSecond())
           .set(CPBASTX.AUDIT_TIME_HUN, now.getNano() / 10_000_000)
           .where(CPBASTX.TRX_DATE.eq(keyTrxDate))
           .and(CPBASTX.DATE_ADDED.eq(keyDateAdded))
           .and(CPBASTX.TIME_ADDED.eq(keyTimeAdded))
           .execute();
    }

    /**
     * Physical delete, keyed by PK. Callers must apply the CPBA07 delete
     * guard themselves ({@code taxAmt != 0 && source non-blank} ⇒ blocked) —
     * this method performs no guard so it can also be used once the caller
     * has already confirmed the delete is allowed.
     */
    @Transactional
    public void delete(LocalDate trxDate, LocalDate dateAdded, int timeAdded) {
        dsl.deleteFrom(CPBASTX)
           .where(CPBASTX.TRX_DATE.eq(trxDate))
           .and(CPBASTX.DATE_ADDED.eq(dateAdded))
           .and(CPBASTX.TIME_ADDED.eq(timeAdded))
           .execute();
    }

    /**
     * The CPBA07 2-tier delete guard (bas_no "already on a statement" gating
     * doesn't apply to this screen — see class Javadoc):
     * blocked when {@code taxAmt != 0} on a system-sourced (non-blank source) row.
     */
    public static boolean canDelete(BasTransaction t) {
        boolean nonZeroTax = t.taxAmt != null && t.taxAmt.compareTo(BigDecimal.ZERO) != 0;
        boolean systemSourced = t.source != null && !t.source.trim().isEmpty();
        return !(nonZeroTax && systemSourced);
    }

    // ── Mapping ──────────────────────────────────────────────────────────

    private static BasTransaction map(Record r) {
        BasTransaction t = new BasTransaction();
        t.trxDate         = r.get(CPBASTX.TRX_DATE);
        t.dateAdded        = r.get(CPBASTX.DATE_ADDED);
        t.timeAdded        = r.get(CPBASTX.TIME_ADDED);
        t.postingDate      = r.get(CPBASTX.POSTING_DATE);
        t.taxGrossAmt      = z(r.get(CPBASTX.TAX_GROSS_AMT));
        t.taxAmt           = z(r.get(CPBASTX.TAX_AMT));
        t.ref1             = trim(r.get(CPBASTX.REF_1));
        t.ref2             = trim(r.get(CPBASTX.REF_2));
        t.basGroup         = trim(r.get(CPBASTX.BAS_GROUP));
        t.basCode          = trim(r.get(CPBASTX.BAS_CODE));
        t.companyNo        = r.get(CPBASTX.COMPANY_NO) == null ? 0 : r.get(CPBASTX.COMPANY_NO);
        t.taxClearingMain  = r.get(CPBASTX.TAX_CLEARING_MAIN);
        t.taxClearingSub   = r.get(CPBASTX.TAX_CLEARING_SUB);
        t.subCoyNo         = r.get(CPBASTX.SUB_COY_NO) == null ? 0 : r.get(CPBASTX.SUB_COY_NO);
        t.source           = trim(r.get(CPBASTX.SOURCE));
        t.batchNo          = r.get(CPBASTX.BATCH_NO) == null ? 0 : r.get(CPBASTX.BATCH_NO);
        t.trxGrossAmt      = z(r.get(CPBASTX.TRX_GROSS_AMT));
        t.custNo           = trim(r.get(CPBASTX.CUST_NO));
        t.custDocType      = trim(r.get(CPBASTX.CUST_DOC_TYPE));
        t.custDocNo        = trim(r.get(CPBASTX.CUST_DOC_NO));
        t.supplierNo       = trim(r.get(CPBASTX.SUPPLIER_NO));
        t.supplierDocType  = trim(r.get(CPBASTX.SUPPLIER_DOC_TYPE));
        t.supplierDocNo    = trim(r.get(CPBASTX.SUPPLIER_DOC_NO));
        t.cmtransBankCode  = trim(r.get(CPBASTX.CMTRANS_BANK_CODE));
        t.cmtransDocType   = trim(r.get(CPBASTX.CMTRANS_DOC_TYPE));
        t.cmtransDocNo     = r.get(CPBASTX.CMTRANS_DOC_NO) == null ? 0 : r.get(CPBASTX.CMTRANS_DOC_NO);
        return t;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Packs a clock reading as {@code HHMMSShh} (hundredths resolution). */
    private static int packTime(LocalTime t) {
        int hundredths = t.getNano() / 10_000_000;
        return t.getHour() * 1_000_000 + t.getMinute() * 10_000 + t.getSecond() * 100 + hundredths;
    }

    private static boolean isDuplicateKey(RuntimeException ex) {
        if (ex instanceof org.springframework.dao.DuplicateKeyException) return true;
        String msg = ex.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("duplicate") || lower.contains("primary");
    }

    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static String trim(String s, int max) {
        String v = trim(s);
        return v.length() > max ? v.substring(0, max) : v;
    }
    private static String up(String s, int max) { return trim(s, max).toUpperCase(); }
    private static BigDecimal z(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static Integer z(Integer v) { return v != null ? v : 0; }
}
