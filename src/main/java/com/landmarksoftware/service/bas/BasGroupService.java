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

import com.landmarksoftware.model.bas.BasGroup;
import com.landmarksoftware.model.bas.BasGroupMember;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static com.landmarksoftware.db.tables.Cpbasco.CPBASCO;
import static com.landmarksoftware.db.tables.Cpbasgr.CPBASGR;
import static com.landmarksoftware.db.tables.Cpbashd.CPBASHD;
import static com.landmarksoftware.db.tables.Cpcoyco.CPCOYCO;
import static com.landmarksoftware.db.tables.Cpgstgr.CPGSTGR;
import static com.landmarksoftware.db.tables.Cpsubcy.CPSUBCY;

/**
 * CPBA01 — BAS Report Group Maintenance service.
 *
 * <p>All jOOQ read/write for {@code cpbasgr} (group header), {@code cpbasco}
 * (member cross-reference), {@code cpsubcy} (BAS-relevant GL accounts, both
 * for the group's own owner and for each member company), plus read-only
 * lookups against {@code cpgstgr} (GST consolidation), {@code cpbashd} (BAS
 * statement existence — delete guard), {@code cpcoyco} (company master).
 *
 * <p>Multi-table writes (group Add/Edit/Delete touch cpbasgr + cpbasco +
 * cpsubcy together) are each a single {@code @Transactional} method so they
 * commit or roll back as one unit.
 *
 * <p>The date sentinel for "no value" on {@code cpbasgr.last_start_date} /
 * {@code last_end_date} is 1899-12-31 per COBOL convention (CLAUDE.md).
 */
@Service
public class BasGroupService {

    private static final LocalDate SENTINEL_DATE = LocalDate.of(1899, 12, 31);

    private final DSLContext dsl;

    public BasGroupService(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ═══════════════════════════════════════════════════════════════════
    // P1 — group list / lookups
    // ═══════════════════════════════════════════════════════════════════

    /**
     * All BAS groups (P1 list) — NOT restricted to the session company. A BAS
     * group's whole purpose is grouping/consolidating potentially several
     * companies together, so the group list is company-agnostic, unlike most
     * other master-file lists in this app.
     */
    public List<BasGroup> findAll() {
        return dsl.selectFrom(CPBASGR)
            .orderBy(CPBASGR.BAS_GROUP)
            .fetch()
            .map(this::mapGroup);
    }

    public boolean groupExists(String basGroup) {
        return dsl.fetchExists(dsl.selectFrom(CPBASGR).where(CPBASGR.BAS_GROUP.eq(basGroup)));
    }

    /** Full record for S1 Edit — includes the owner's cpsubcy GL accounts (not present on the plain list row). */
    public Optional<BasGroup> findForEdit(String basGroup) {
        Record r = dsl.selectFrom(CPBASGR).where(CPBASGR.BAS_GROUP.eq(basGroup)).fetchOne();
        if (r == null) return Optional.empty();
        BasGroup g = mapGroup(r);
        dsl.selectFrom(CPSUBCY)
           .where(CPSUBCY.COMPANY_NO_2.eq(g.companyNo).and(CPSUBCY.SUB_COY_NO.eq(g.subCoyNo)))
           .fetchOptional()
           .ifPresent(s -> {
               g.taxPaidAcctMain  = s.get(CPSUBCY.TAX_PAID_ACCT_MAIN);
               g.taxPaidAcctSub   = s.get(CPSUBCY.TAX_PAID_ACCT_SUB);
               g.varianceAcctMain = s.get(CPSUBCY.VARIANCE_ACCT_MAIN);
               g.varianceAcctSub  = s.get(CPSUBCY.VARIANCE_ACCT_SUB);
           });
        return Optional.of(g);
    }

    private BasGroup mapGroup(Record r) {
        BasGroup g = new BasGroup();
        g.basGroup          = trim(r.get(CPBASGR.BAS_GROUP));
        g.companyNo          = r.get(CPBASGR.COMPANY_NO);
        g.subCoyNo            = r.get(CPBASGR.SUB_COY_NO);
        g.basFreq              = trim(r.get(CPBASGR.BAS_FREQ));
        g.lastBasNoUsed       = r.get(CPBASGR.LAST_BAS_NO_USED);
        g.lastBasNo           = r.get(CPBASGR.LAST_BAS_NO);
        g.currentBasNo        = r.get(CPBASGR.CURRENT_BAS_NO);
        g.lastStartDate       = ld(r.get(CPBASGR.LAST_START_DATE));
        g.lastEndDate         = ld(r.get(CPBASGR.LAST_END_DATE));
        g.gstGroupConsolInd   = trim(r.get(CPBASGR.GST_GROUP_CONSOL_IND));
        g.gstFreq             = trim(r.get(CPBASGR.GST_FREQ));
        g.basGroupName        = trim(r.get(CPBASGR.BAS_GROUP_NAME));
        g.gstMethod           = trim(r.get(CPBASGR.GST_METHOD));
        g.trxPostingDateInd   = trim(r.get(CPBASGR.TRX_POSTING_DATE_IND));
        g.noteNo              = r.get(CPBASGR.NOTE_NO);
        return g;
    }

    // ── Cross-cutting lookups used by both S1 and S2 ────────────────────

    public boolean companyExists(int companyNo) {
        return dsl.fetchExists(dsl.selectFrom(CPCOYCO).where(CPCOYCO.COMPANY_NO.eq(companyNo)));
    }

    public String companyName(int companyNo) {
        return dsl.select(CPCOYCO.NAME1).from(CPCOYCO)
            .where(CPCOYCO.COMPANY_NO.eq(companyNo))
            .fetchOptional(CPCOYCO.NAME1).map(BasGroupService::trim).orElse("");
    }

    /** Sub-coy 0 always "exists" (means "none"); >0 must have a cpsubcy row for that company. */
    public boolean subCoyExists(int companyNo, int subCoyNo) {
        if (subCoyNo <= 0) return true;
        return dsl.fetchExists(dsl.selectFrom(CPSUBCY)
            .where(CPSUBCY.COMPANY_NO_2.eq(companyNo).and(CPSUBCY.SUB_COY_NO.eq(subCoyNo))));
    }

    public String subCoyName(int companyNo, int subCoyNo) {
        return dsl.select(CPSUBCY.COMPANY_NAME).from(CPSUBCY)
            .where(CPSUBCY.COMPANY_NO_2.eq(companyNo).and(CPSUBCY.SUB_COY_NO.eq(subCoyNo)))
            .fetchOptional(CPSUBCY.COMPANY_NAME).map(BasGroupService::trim).orElse("");
    }

    /**
     * Returns the bas_group code that already owns this company/sub-coy
     * combination via cpbasco's alt keys, if any — excluding {@code excludeGroup}
     * (pass {@code null} on Add, the group's own code on Edit).
     */
    public Optional<String> findConflictingOwner(int companyNo, int subCoyNo, String excludeGroup) {
        var cond = CPBASCO.ALT_COMPANY_NO.eq(companyNo).and(CPBASCO.ALT_SUB_COY_NO.eq(subCoyNo));
        if (excludeGroup != null && !excludeGroup.isBlank()) {
            cond = cond.and(CPBASCO.BAS_GROUP.ne(excludeGroup));
        }
        return dsl.select(CPBASCO.BAS_GROUP).from(CPBASCO)
            .where(cond)
            .fetchOptional(CPBASCO.BAS_GROUP).map(BasGroupService::trim);
    }

    public boolean existsConsolInto(String basGroup) {
        return dsl.fetchExists(dsl.selectFrom(CPGSTGR).where(CPGSTGR.CONSOL_INTO_BAS_GROUP.eq(basGroup)));
    }

    public boolean existsConsolFrom(String basGroup) {
        return dsl.fetchExists(dsl.selectFrom(CPGSTGR).where(CPGSTGR.CONSOL_FROM_BAS_GROUP.eq(basGroup)));
    }

    /** Delete guard — any BAS statement ever created for this group. bas_group is globally unique (PK). */
    public boolean hasStatements(String basGroup) {
        return dsl.fetchExists(dsl.selectFrom(CPBASHD).where(CPBASHD.BAS_GROUP.eq(basGroup)));
    }

    // ═══════════════════════════════════════════════════════════════════
    // S1 — group Add / Edit / Delete
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Insert a new BAS group + its auto-created owner cpbasco row + the
     * owner's cpsubcy GL-account row (created if missing). One transaction.
     */
    @Transactional
    public void insert(BasGroup g, String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        dsl.insertInto(CPBASGR)
           .set(CPBASGR.BAS_GROUP, g.basGroup)
           .set(CPBASGR.COMPANY_NO, g.companyNo)
           .set(CPBASGR.SUB_COY_NO, g.subCoyNo)
           .set(CPBASGR.BAS_FREQ, g.basFreq)
           .set(CPBASGR.LAST_BAS_NO_USED, 0)
           .set(CPBASGR.LAST_BAS_NO, 0)
           .set(CPBASGR.LAST_START_DATE, SENTINEL_DATE)
           .set(CPBASGR.LAST_END_DATE, SENTINEL_DATE)
           .set(CPBASGR.CURRENT_BAS_NO, 0)
           .set(CPBASGR.GST_GROUP_CONSOL_IND, g.gstGroupConsolInd)
           .set(CPBASGR.GST_FREQ, g.gstFreq)
           .set(CPBASGR.BAS_GROUP_NAME, g.basGroupName)
           .set(CPBASGR.GST_METHOD, g.gstMethod)
           .set(CPBASGR.TRX_POSTING_DATE_IND, g.trxPostingDateInd)
           .set(CPBASGR.NOTE_NO, 0L)
           .set(CPBASGR.AUDIT_USER_ID, userId)
           .set(CPBASGR.AUDIT_DATE, today)
           .set(CPBASGR.AUDIT_TIME_HR, now.getHour())
           .set(CPBASGR.AUDIT_TIME_MIN, now.getMinute())
           .set(CPBASGR.AUDIT_TIME_SEC, now.getSecond())
           .set(CPBASGR.AUDIT_TIME_HUN, 0)
           .execute();

        insertOwnerCpbasco(g.basGroup, g.companyNo, g.subCoyNo, userId);
        upsertCpsubcyGlOnly(g.companyNo, g.subCoyNo,
            g.taxPaidAcctMain, g.taxPaidAcctSub, g.varianceAcctMain, g.varianceAcctSub, userId);
    }

    /**
     * Update an existing BAS group. When the owning company/sub-coy changed,
     * re-keys the owner cpbasco row and cleans up the old owner's cpsubcy GL
     * accounts (zero if the row still has other data, delete if it was a
     * bare sub_coy_no=0 owner-only row), then writes the new owner's row.
     */
    @Transactional
    public void update(BasGroup g, int oldCompanyNo, int oldSubCoyNo, String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        dsl.update(CPBASGR)
           .set(CPBASGR.COMPANY_NO, g.companyNo)
           .set(CPBASGR.SUB_COY_NO, g.subCoyNo)
           .set(CPBASGR.BAS_FREQ, g.basFreq)
           .set(CPBASGR.GST_GROUP_CONSOL_IND, g.gstGroupConsolInd)
           .set(CPBASGR.GST_FREQ, g.gstFreq)
           .set(CPBASGR.BAS_GROUP_NAME, g.basGroupName)
           .set(CPBASGR.GST_METHOD, g.gstMethod)
           .set(CPBASGR.TRX_POSTING_DATE_IND, g.trxPostingDateInd)
           .set(CPBASGR.AUDIT_USER_ID, userId)
           .set(CPBASGR.AUDIT_DATE, today)
           .set(CPBASGR.AUDIT_TIME_HR, now.getHour())
           .set(CPBASGR.AUDIT_TIME_MIN, now.getMinute())
           .set(CPBASGR.AUDIT_TIME_SEC, now.getSecond())
           .set(CPBASGR.AUDIT_TIME_HUN, 0)
           .where(CPBASGR.BAS_GROUP.eq(g.basGroup))
           .execute();

        boolean ownerChanged = (oldCompanyNo != g.companyNo) || (oldSubCoyNo != g.subCoyNo);
        if (ownerChanged) {
            dsl.deleteFrom(CPBASCO)
               .where(CPBASCO.BAS_GROUP.eq(g.basGroup)
                  .and(CPBASCO.COMPANY_NO.eq(oldCompanyNo))
                  .and(CPBASCO.SUB_COY_NO.eq(oldSubCoyNo)))
               .execute();
            clearOrDeleteCpsubcyGl(oldCompanyNo, oldSubCoyNo);
            insertOwnerCpbasco(g.basGroup, g.companyNo, g.subCoyNo, userId);
        }
        upsertCpsubcyGlOnly(g.companyNo, g.subCoyNo,
            g.taxPaidAcctMain, g.taxPaidAcctSub, g.varianceAcctMain, g.varianceAcctSub, userId);
    }

    /**
     * Delete a BAS group. Caller must have already confirmed via
     * {@link #hasStatements(String)} that this is safe. Cleans up the
     * owner's cpsubcy GL accounts (zero-or-delete, same rule as an
     * owner-change update), cascades every cpbasco row (member + owner),
     * then removes the cpbasgr row itself.
     */
    @Transactional
    public void delete(BasGroup g) {
        clearOrDeleteCpsubcyGl(g.companyNo, g.subCoyNo);
        dsl.deleteFrom(CPBASCO).where(CPBASCO.BAS_GROUP.eq(g.basGroup)).execute();
        dsl.deleteFrom(CPBASGR).where(CPBASGR.BAS_GROUP.eq(g.basGroup)).execute();
    }

    private void clearOrDeleteCpsubcyGl(int companyNo, int subCoyNo) {
        if (subCoyNo == 0) {
            dsl.deleteFrom(CPSUBCY)
               .where(CPSUBCY.COMPANY_NO_2.eq(companyNo).and(CPSUBCY.SUB_COY_NO.eq(0)))
               .execute();
        } else {
            dsl.update(CPSUBCY)
               .set(CPSUBCY.TAX_PAID_ACCT_MAIN, 0)
               .set(CPSUBCY.TAX_PAID_ACCT_SUB, 0)
               .set(CPSUBCY.VARIANCE_ACCT_MAIN, 0)
               .set(CPSUBCY.VARIANCE_ACCT_SUB, 0)
               .where(CPSUBCY.COMPANY_NO_2.eq(companyNo).and(CPSUBCY.SUB_COY_NO.eq(subCoyNo)))
               .execute();
        }
    }

    private void insertOwnerCpbasco(String basGroup, int companyNo, int subCoyNo, String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        dsl.insertInto(CPBASCO)
           .set(CPBASCO.BAS_GROUP, basGroup)
           .set(CPBASCO.COMPANY_NO, companyNo)
           .set(CPBASCO.SUB_COY_NO, subCoyNo)
           .set(CPBASCO.ALT_COMPANY_NO, companyNo)
           .set(CPBASCO.ALT_SUB_COY_NO, subCoyNo)
           .set(CPBASCO.NOTE_NO, 0L)
           .set(CPBASCO.AUDIT_USER_ID, userId)
           .set(CPBASCO.AUDIT_DATE, today)
           .set(CPBASCO.AUDIT_TIME_HR, now.getHour())
           .set(CPBASCO.AUDIT_TIME_MIN, now.getMinute())
           .set(CPBASCO.AUDIT_TIME_SEC, now.getSecond())
           .set(CPBASCO.AUDIT_TIME_HUN, 0)
           .execute();
    }

    /** Create-or-update ONLY the tax-paid/variance GL pair on a cpsubcy row — every other column is left as-is (new row: zero/blank). */
    private void upsertCpsubcyGlOnly(int companyNo, int subCoyNo,
                                      int taxMain, int taxSub, int varMain, int varSub, String userId) {
        boolean exists = dsl.fetchExists(dsl.selectFrom(CPSUBCY)
            .where(CPSUBCY.COMPANY_NO_2.eq(companyNo).and(CPSUBCY.SUB_COY_NO.eq(subCoyNo))));
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        if (exists) {
            dsl.update(CPSUBCY)
               .set(CPSUBCY.TAX_PAID_ACCT_MAIN, taxMain)
               .set(CPSUBCY.TAX_PAID_ACCT_SUB, taxSub)
               .set(CPSUBCY.VARIANCE_ACCT_MAIN, varMain)
               .set(CPSUBCY.VARIANCE_ACCT_SUB, varSub)
               .set(CPSUBCY.AUDIT_USER_ID, userId)
               .set(CPSUBCY.AUDIT_DATE, today)
               .set(CPSUBCY.AUDIT_TIME_HR, now.getHour())
               .set(CPSUBCY.AUDIT_TIME_MIN, now.getMinute())
               .set(CPSUBCY.AUDIT_TIME_SEC, now.getSecond())
               .set(CPSUBCY.AUDIT_TIME_HUN, 0)
               .where(CPSUBCY.COMPANY_NO_2.eq(companyNo).and(CPSUBCY.SUB_COY_NO.eq(subCoyNo)))
               .execute();
        } else {
            dsl.insertInto(CPSUBCY)
               .set(CPSUBCY.COMPANY_NO, companyNo)
               .set(CPSUBCY.COMPANY_NO_2, companyNo)
               .set(CPSUBCY.SUB_COY_NO, subCoyNo)
               .set(CPSUBCY.COMPANY_NAME, "")
               .set(CPSUBCY.COMPANY_ADDR_1, "")
               .set(CPSUBCY.COMPANY_ADDR_2, "")
               .set(CPSUBCY.COMPANY_ADDR_3, "")
               .set(CPSUBCY.ABN, "")
               .set(CPSUBCY.BAS_HO_LOAN_ACCT_MAIN, 0)
               .set(CPSUBCY.BAS_HO_LOAN_ACCT_SUB, 0)
               .set(CPSUBCY.BAS_SUB_LOAN_ACCT_MAIN, 0)
               .set(CPSUBCY.BAS_SUB_LOAN_ACCT_SUB, 0)
               .set(CPSUBCY.TAX_PAID_ACCT_MAIN, taxMain)
               .set(CPSUBCY.TAX_PAID_ACCT_SUB, taxSub)
               .set(CPSUBCY.NON_LAND_ACCT_MAIN, 0)
               .set(CPSUBCY.NON_LAND_ACCT_SUB, 0)
               .set(CPSUBCY.VARIANCE_ACCT_MAIN, varMain)
               .set(CPSUBCY.VARIANCE_ACCT_SUB, varSub)
               .set(CPSUBCY.INTER_COY_ACCT_MAIN, 0)
               .set(CPSUBCY.INTER_COY_ACCT_SUB, 0)
               .set(CPSUBCY.BAS_1C_1D_ACCT_MAIN, 0)
               .set(CPSUBCY.BAS_1C_1D_ACCT_SUB, 0)
               .set(CPSUBCY.BAS_1E_1F_ACCT_MAIN, 0)
               .set(CPSUBCY.BAS_1E_1F_ACCT_SUB, 0)
               .set(CPSUBCY.BAS_1G_ACCT_MAIN, 0)
               .set(CPSUBCY.BAS_1G_ACCT_SUB, 0)
               .set(CPSUBCY.BAS_4_ACCT_MAIN, 0)
               .set(CPSUBCY.BAS_4_ACCT_SUB, 0)
               .set(CPSUBCY.BAS_5A_5B_ACCT_MAIN, 0)
               .set(CPSUBCY.BAS_5A_5B_ACCT_SUB, 0)
               .set(CPSUBCY.BAS_6A_6B_ACCT_MAIN, 0)
               .set(CPSUBCY.BAS_6A_6B_ACCT_SUB, 0)
               .set(CPSUBCY.BAS_7_ACCT_MAIN, 0)
               .set(CPSUBCY.BAS_7_ACCT_SUB, 0)
               .set(CPSUBCY.BAS_7A_ACCT_MAIN, 0)
               .set(CPSUBCY.BAS_7A_ACCT_SUB, 0)
               .set(CPSUBCY.BAS_7C_7D_ACCT_MAIN, 0)
               .set(CPSUBCY.BAS_7C_7D_ACCT_SUB, 0)
               .set(CPSUBCY.NOTE_NO, 0L)
               .set(CPSUBCY.AUDIT_USER_ID, userId)
               .set(CPSUBCY.AUDIT_DATE, today)
               .set(CPSUBCY.AUDIT_TIME_HR, now.getHour())
               .set(CPSUBCY.AUDIT_TIME_MIN, now.getMinute())
               .set(CPSUBCY.AUDIT_TIME_SEC, now.getSecond())
               .set(CPSUBCY.AUDIT_TIME_HUN, 0)
               .execute();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // P2/S2 — member companies
    // ═══════════════════════════════════════════════════════════════════

    /** Member list for the group (P2) — company name resolved from cpsubcy, falling back to cpcoyco. */
    public List<BasGroupMember> findMembers(String basGroup) {
        return dsl.selectFrom(CPBASCO)
            .where(CPBASCO.BAS_GROUP.eq(basGroup))
            .orderBy(CPBASCO.COMPANY_NO, CPBASCO.SUB_COY_NO)
            .fetch()
            .map(r -> {
                BasGroupMember m = new BasGroupMember();
                m.basGroup   = trim(r.get(CPBASCO.BAS_GROUP));
                m.companyNo  = r.get(CPBASCO.COMPANY_NO);
                m.subCoyNo   = r.get(CPBASCO.SUB_COY_NO);
                m.noteNo     = r.get(CPBASCO.NOTE_NO);
                m.companyName = resolveCompanyName(m.companyNo, m.subCoyNo);
                return m;
            });
    }

    private String resolveCompanyName(int companyNo, int subCoyNo) {
        String n = subCoyName(companyNo, subCoyNo);
        return n.isEmpty() ? companyName(companyNo) : n;
    }

    /** Full member record for S2 Edit — includes all 12 GL account pairs from cpsubcy (NOT tax-paid/variance — those are S1-only). */
    public Optional<BasGroupMember> findMemberForEdit(String basGroup, int companyNo, int subCoyNo) {
        boolean exists = dsl.fetchExists(dsl.selectFrom(CPBASCO)
            .where(CPBASCO.BAS_GROUP.eq(basGroup)
                .and(CPBASCO.COMPANY_NO.eq(companyNo))
                .and(CPBASCO.SUB_COY_NO.eq(subCoyNo))));
        if (!exists) return Optional.empty();

        BasGroupMember m = new BasGroupMember();
        m.basGroup = basGroup;
        m.companyNo = companyNo;
        m.subCoyNo = subCoyNo;

        Record s = dsl.selectFrom(CPSUBCY)
            .where(CPSUBCY.COMPANY_NO_2.eq(companyNo).and(CPSUBCY.SUB_COY_NO.eq(subCoyNo)))
            .fetchOne();
        if (s != null) {
            m.companyName        = trim(s.get(CPSUBCY.COMPANY_NAME));
            m.basHoLoanAcctMain  = s.get(CPSUBCY.BAS_HO_LOAN_ACCT_MAIN);
            m.basHoLoanAcctSub   = s.get(CPSUBCY.BAS_HO_LOAN_ACCT_SUB);
            m.basSubLoanAcctMain = s.get(CPSUBCY.BAS_SUB_LOAN_ACCT_MAIN);
            m.basSubLoanAcctSub  = s.get(CPSUBCY.BAS_SUB_LOAN_ACCT_SUB);
            m.interCoyAcctMain   = s.get(CPSUBCY.INTER_COY_ACCT_MAIN);
            m.interCoyAcctSub    = s.get(CPSUBCY.INTER_COY_ACCT_SUB);
            m.bas1c1dAcctMain    = s.get(CPSUBCY.BAS_1C_1D_ACCT_MAIN);
            m.bas1c1dAcctSub     = s.get(CPSUBCY.BAS_1C_1D_ACCT_SUB);
            m.bas1e1fAcctMain    = s.get(CPSUBCY.BAS_1E_1F_ACCT_MAIN);
            m.bas1e1fAcctSub     = s.get(CPSUBCY.BAS_1E_1F_ACCT_SUB);
            m.bas1gAcctMain      = s.get(CPSUBCY.BAS_1G_ACCT_MAIN);
            m.bas1gAcctSub       = s.get(CPSUBCY.BAS_1G_ACCT_SUB);
            m.bas4AcctMain       = s.get(CPSUBCY.BAS_4_ACCT_MAIN);
            m.bas4AcctSub        = s.get(CPSUBCY.BAS_4_ACCT_SUB);
            m.bas5a5bAcctMain    = s.get(CPSUBCY.BAS_5A_5B_ACCT_MAIN);
            m.bas5a5bAcctSub     = s.get(CPSUBCY.BAS_5A_5B_ACCT_SUB);
            m.bas6a6bAcctMain    = s.get(CPSUBCY.BAS_6A_6B_ACCT_MAIN);
            m.bas6a6bAcctSub     = s.get(CPSUBCY.BAS_6A_6B_ACCT_SUB);
            m.bas7AcctMain       = s.get(CPSUBCY.BAS_7_ACCT_MAIN);
            m.bas7AcctSub        = s.get(CPSUBCY.BAS_7_ACCT_SUB);
            m.bas7aAcctMain      = s.get(CPSUBCY.BAS_7A_ACCT_MAIN);
            m.bas7aAcctSub       = s.get(CPSUBCY.BAS_7A_ACCT_SUB);
            m.bas7c7dAcctMain    = s.get(CPSUBCY.BAS_7C_7D_ACCT_MAIN);
            m.bas7c7dAcctSub     = s.get(CPSUBCY.BAS_7C_7D_ACCT_SUB);
        } else {
            m.companyName = companyName(companyNo);
        }
        return Optional.of(m);
    }

    /** Add a member: insert the cpbasco row, then write/create its cpsubcy GL-account row. */
    @Transactional
    public void insertMember(BasGroupMember m, String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        dsl.insertInto(CPBASCO)
           .set(CPBASCO.BAS_GROUP, m.basGroup)
           .set(CPBASCO.COMPANY_NO, m.companyNo)
           .set(CPBASCO.SUB_COY_NO, m.subCoyNo)
           .set(CPBASCO.ALT_COMPANY_NO, m.companyNo)
           .set(CPBASCO.ALT_SUB_COY_NO, m.subCoyNo)
           .set(CPBASCO.NOTE_NO, 0L)
           .set(CPBASCO.AUDIT_USER_ID, userId)
           .set(CPBASCO.AUDIT_DATE, today)
           .set(CPBASCO.AUDIT_TIME_HR, now.getHour())
           .set(CPBASCO.AUDIT_TIME_MIN, now.getMinute())
           .set(CPBASCO.AUDIT_TIME_SEC, now.getSecond())
           .set(CPBASCO.AUDIT_TIME_HUN, 0)
           .execute();
        upsertCpsubcyFull(m, userId);
    }

    /** Edit a member — company/sub-coy are read-only in the UI, only the GL account fields are rewritten. */
    @Transactional
    public void updateMember(BasGroupMember m, String userId) {
        upsertCpsubcyFull(m, userId);
    }

    /** Delete a member's cpbasco row only — cpsubcy is shared/reusable company data and is never removed here. */
    @Transactional
    public void deleteMember(String basGroup, int companyNo, int subCoyNo) {
        dsl.deleteFrom(CPBASCO)
           .where(CPBASCO.BAS_GROUP.eq(basGroup)
               .and(CPBASCO.COMPANY_NO.eq(companyNo))
               .and(CPBASCO.SUB_COY_NO.eq(subCoyNo)))
           .execute();
    }

    /**
     * Create-or-update the 12 S2 GL account pairs on a cpsubcy row.
     * {@code tax_paid_acct}/{@code variance_acct} are S1-only (the group
     * owner's fields) and are deliberately NEVER touched here — see {@link
     * BasGroupMember}'s class javadoc. company name/addr/ABN/non_land_acct
     * are also untouched (new row: blank/zero).
     */
    private void upsertCpsubcyFull(BasGroupMember m, String userId) {
        boolean exists = dsl.fetchExists(dsl.selectFrom(CPSUBCY)
            .where(CPSUBCY.COMPANY_NO_2.eq(m.companyNo).and(CPSUBCY.SUB_COY_NO.eq(m.subCoyNo))));
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        if (exists) {
            dsl.update(CPSUBCY)
               .set(CPSUBCY.BAS_HO_LOAN_ACCT_MAIN, m.basHoLoanAcctMain)
               .set(CPSUBCY.BAS_HO_LOAN_ACCT_SUB, m.basHoLoanAcctSub)
               .set(CPSUBCY.BAS_SUB_LOAN_ACCT_MAIN, m.basSubLoanAcctMain)
               .set(CPSUBCY.BAS_SUB_LOAN_ACCT_SUB, m.basSubLoanAcctSub)
               .set(CPSUBCY.INTER_COY_ACCT_MAIN, m.interCoyAcctMain)
               .set(CPSUBCY.INTER_COY_ACCT_SUB, m.interCoyAcctSub)
               .set(CPSUBCY.BAS_1C_1D_ACCT_MAIN, m.bas1c1dAcctMain)
               .set(CPSUBCY.BAS_1C_1D_ACCT_SUB, m.bas1c1dAcctSub)
               .set(CPSUBCY.BAS_1E_1F_ACCT_MAIN, m.bas1e1fAcctMain)
               .set(CPSUBCY.BAS_1E_1F_ACCT_SUB, m.bas1e1fAcctSub)
               .set(CPSUBCY.BAS_1G_ACCT_MAIN, m.bas1gAcctMain)
               .set(CPSUBCY.BAS_1G_ACCT_SUB, m.bas1gAcctSub)
               .set(CPSUBCY.BAS_4_ACCT_MAIN, m.bas4AcctMain)
               .set(CPSUBCY.BAS_4_ACCT_SUB, m.bas4AcctSub)
               .set(CPSUBCY.BAS_5A_5B_ACCT_MAIN, m.bas5a5bAcctMain)
               .set(CPSUBCY.BAS_5A_5B_ACCT_SUB, m.bas5a5bAcctSub)
               .set(CPSUBCY.BAS_6A_6B_ACCT_MAIN, m.bas6a6bAcctMain)
               .set(CPSUBCY.BAS_6A_6B_ACCT_SUB, m.bas6a6bAcctSub)
               .set(CPSUBCY.BAS_7_ACCT_MAIN, m.bas7AcctMain)
               .set(CPSUBCY.BAS_7_ACCT_SUB, m.bas7AcctSub)
               .set(CPSUBCY.BAS_7A_ACCT_MAIN, m.bas7aAcctMain)
               .set(CPSUBCY.BAS_7A_ACCT_SUB, m.bas7aAcctSub)
               .set(CPSUBCY.BAS_7C_7D_ACCT_MAIN, m.bas7c7dAcctMain)
               .set(CPSUBCY.BAS_7C_7D_ACCT_SUB, m.bas7c7dAcctSub)
               .set(CPSUBCY.AUDIT_USER_ID, userId)
               .set(CPSUBCY.AUDIT_DATE, today)
               .set(CPSUBCY.AUDIT_TIME_HR, now.getHour())
               .set(CPSUBCY.AUDIT_TIME_MIN, now.getMinute())
               .set(CPSUBCY.AUDIT_TIME_SEC, now.getSecond())
               .set(CPSUBCY.AUDIT_TIME_HUN, 0)
               .where(CPSUBCY.COMPANY_NO_2.eq(m.companyNo).and(CPSUBCY.SUB_COY_NO.eq(m.subCoyNo)))
               .execute();
        } else {
            dsl.insertInto(CPSUBCY)
               .set(CPSUBCY.COMPANY_NO, m.companyNo)
               .set(CPSUBCY.COMPANY_NO_2, m.companyNo)
               .set(CPSUBCY.SUB_COY_NO, m.subCoyNo)
               .set(CPSUBCY.COMPANY_NAME, "")
               .set(CPSUBCY.COMPANY_ADDR_1, "")
               .set(CPSUBCY.COMPANY_ADDR_2, "")
               .set(CPSUBCY.COMPANY_ADDR_3, "")
               .set(CPSUBCY.ABN, "")
               .set(CPSUBCY.BAS_HO_LOAN_ACCT_MAIN, m.basHoLoanAcctMain)
               .set(CPSUBCY.BAS_HO_LOAN_ACCT_SUB, m.basHoLoanAcctSub)
               .set(CPSUBCY.BAS_SUB_LOAN_ACCT_MAIN, m.basSubLoanAcctMain)
               .set(CPSUBCY.BAS_SUB_LOAN_ACCT_SUB, m.basSubLoanAcctSub)
               .set(CPSUBCY.TAX_PAID_ACCT_MAIN, 0)
               .set(CPSUBCY.TAX_PAID_ACCT_SUB, 0)
               .set(CPSUBCY.NON_LAND_ACCT_MAIN, 0)
               .set(CPSUBCY.NON_LAND_ACCT_SUB, 0)
               .set(CPSUBCY.VARIANCE_ACCT_MAIN, 0)
               .set(CPSUBCY.VARIANCE_ACCT_SUB, 0)
               .set(CPSUBCY.INTER_COY_ACCT_MAIN, m.interCoyAcctMain)
               .set(CPSUBCY.INTER_COY_ACCT_SUB, m.interCoyAcctSub)
               .set(CPSUBCY.BAS_1C_1D_ACCT_MAIN, m.bas1c1dAcctMain)
               .set(CPSUBCY.BAS_1C_1D_ACCT_SUB, m.bas1c1dAcctSub)
               .set(CPSUBCY.BAS_1E_1F_ACCT_MAIN, m.bas1e1fAcctMain)
               .set(CPSUBCY.BAS_1E_1F_ACCT_SUB, m.bas1e1fAcctSub)
               .set(CPSUBCY.BAS_1G_ACCT_MAIN, m.bas1gAcctMain)
               .set(CPSUBCY.BAS_1G_ACCT_SUB, m.bas1gAcctSub)
               .set(CPSUBCY.BAS_4_ACCT_MAIN, m.bas4AcctMain)
               .set(CPSUBCY.BAS_4_ACCT_SUB, m.bas4AcctSub)
               .set(CPSUBCY.BAS_5A_5B_ACCT_MAIN, m.bas5a5bAcctMain)
               .set(CPSUBCY.BAS_5A_5B_ACCT_SUB, m.bas5a5bAcctSub)
               .set(CPSUBCY.BAS_6A_6B_ACCT_MAIN, m.bas6a6bAcctMain)
               .set(CPSUBCY.BAS_6A_6B_ACCT_SUB, m.bas6a6bAcctSub)
               .set(CPSUBCY.BAS_7_ACCT_MAIN, m.bas7AcctMain)
               .set(CPSUBCY.BAS_7_ACCT_SUB, m.bas7AcctSub)
               .set(CPSUBCY.BAS_7A_ACCT_MAIN, m.bas7aAcctMain)
               .set(CPSUBCY.BAS_7A_ACCT_SUB, m.bas7aAcctSub)
               .set(CPSUBCY.BAS_7C_7D_ACCT_MAIN, m.bas7c7dAcctMain)
               .set(CPSUBCY.BAS_7C_7D_ACCT_SUB, m.bas7c7dAcctSub)
               .set(CPSUBCY.NOTE_NO, 0L)
               .set(CPSUBCY.AUDIT_USER_ID, userId)
               .set(CPSUBCY.AUDIT_DATE, today)
               .set(CPSUBCY.AUDIT_TIME_HR, now.getHour())
               .set(CPSUBCY.AUDIT_TIME_MIN, now.getMinute())
               .set(CPSUBCY.AUDIT_TIME_SEC, now.getSecond())
               .set(CPSUBCY.AUDIT_TIME_HUN, 0)
               .execute();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Sentinel filter — pre-1900 (COBOL "no date") collapses to null. */
    private static LocalDate ld(LocalDate d) {
        return (d != null && d.isAfter(LocalDate.of(1900, 1, 1))) ? d : null;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
