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

import com.landmarksoftware.db.tables.records.CpbasgrRecord;
import com.landmarksoftware.db.tables.records.CpbashdRecord;
import com.landmarksoftware.db.tables.records.CpbastxRecord;
import com.landmarksoftware.db.tables.records.CpcoycoRecord;
import com.landmarksoftware.db.tables.records.CpsubcyRecord;
import com.landmarksoftware.model.bas.BasPullPreview;
import com.landmarksoftware.model.bas.BasRun;
import com.landmarksoftware.model.bas.CreateBasParams;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.landmarksoftware.db.tables.Cpbasco.CPBASCO;
import static com.landmarksoftware.db.tables.Cpbasgr.CPBASGR;
import static com.landmarksoftware.db.tables.Cpbashd.CPBASHD;
import static com.landmarksoftware.db.tables.Cpbastx.CPBASTX;
import static com.landmarksoftware.db.tables.Cpcoyco.CPCOYCO;
import static com.landmarksoftware.db.tables.Cpgstgr.CPGSTGR;
import static com.landmarksoftware.db.tables.Cpsubcy.CPSUBCY;
import static com.landmarksoftware.db.tables.Paehist.PAEHIST;

/**
 * CPBA10 — BAS run creation, editing, and deletion. All JDBC/jOOQ for
 * {@code cpbashd}/{@code cpbastx}/{@code cpbasgr} — controllers are SQL-free.
 *
 * <p>The ATO-label math itself lives in {@link BasCalculationEngine} (a pure
 * function library, no DB access); this class is purely DB orchestration —
 * fetch, guard, mutate via the engine, persist, all inside one
 * {@code @Transactional} method per operation, per CLAUDE.md's rule that
 * every write path is one all-or-nothing transaction.
 *
 * <p>Field access on {@code cpbashd}/{@code cpbastx} records throughout uses
 * the generic {@code record.get(FIELD)}/{@code record.set(FIELD, value)}
 * idiom rather than jOOQ's generated per-column getters — see {@link
 * BasCalculationEngine}'s class Javadoc for why.
 */
@Service
public class BasProcessingService {

    /** COBOL date-zero sentinel (CLAUDE.md convention) — used for unused date columns. */
    private static final LocalDate SENTINEL_DATE = LocalDate.of(1899, 12, 31);

    private final DSLContext dsl;
    private final BasReasonCodeService reasonCodeService;

    public BasProcessingService(DSLContext dsl, BasReasonCodeService reasonCodeService) {
        this.dsl = dsl;
        this.reasonCodeService = reasonCodeService;
    }

    // ── Lookups for the UI ──────────────────────────────────────────────

    /** A BAS group code + display label + its own owning company; {@code toString()} drives ComboBox rendering. */
    public record GroupOption(String code, String label, int companyNo) {
        @Override public String toString() { return label; }
    }

    /**
     * All BAS groups — NOT restricted to the session company. A BAS group's
     * whole purpose is consolidating potentially several companies (same
     * rationale as CPBA01's group list), so this is company-agnostic.
     */
    public List<GroupOption> getBasGroups() {
        return dsl.select(CPBASGR.BAS_GROUP, CPBASGR.BAS_GROUP_NAME, CPBASGR.COMPANY_NO)
            .from(CPBASGR)
            .orderBy(CPBASGR.BAS_GROUP)
            .fetch(r -> new GroupOption(trim(r.get(CPBASGR.BAS_GROUP)),
                trim(r.get(CPBASGR.BAS_GROUP)) + " — " + trim(r.get(CPBASGR.BAS_GROUP_NAME)),
                r.get(CPBASGR.COMPANY_NO)));
    }

    /** {@code bas_group} is cpbasgr's whole primary key (globally unique) — no company filter needed or correct here. */
    public CpbasgrRecord getGroup(String basGroup) {
        return dsl.selectFrom(CPBASGR)
            .where(CPBASGR.BAS_GROUP.eq(basGroup))
            .fetchOne();
    }

    /**
     * P1 list — NOT restricted to the session company (same rationale as the
     * group list), and shows draft runs only ({@code bas_status = ''}):
     * once committed or cancelled a run is done/historical, not part of the
     * active working list. Optionally filtered to one BAS group.
     */
    public List<BasRun> findRuns(String basGroupFilter) {
        Condition cond = CPBASHD.BAS_STATUS.eq("");
        if (basGroupFilter != null && !basGroupFilter.isBlank()) {
            cond = cond.and(CPBASHD.BAS_GROUP.eq(basGroupFilter));
        }
        return dsl.selectFrom(CPBASHD)
            .where(cond)
            .orderBy(CPBASHD.BAS_GROUP, CPBASHD.BAS_NO.desc())
            .fetch(r -> new BasRun(
                trim(r.get(CPBASHD.BAS_GROUP)), r.get(CPBASHD.BAS_NO),
                statusLabel(trim(r.get(CPBASHD.BAS_STATUS))), trim(r.get(CPBASHD.BAS_STATUS)),
                r.get(CPBASHD.A3_FROM_DATE), r.get(CPBASHD.A4_TO_DATE),
                nz(r.get(CPBASHD.BAS_9_NET_TAX_AMT)), r.get(CPBASHD.A5_DUE_DATE)));
    }

    public CpbashdRecord getHeader(String basGroup, int basNo) {
        return dsl.selectFrom(CPBASHD)
            .where(CPBASHD.BAS_GROUP.eq(basGroup)).and(CPBASHD.BAS_NO.eq(basNo))
            .fetchOne();
    }

    /** W1 (gross wages) + W2 (PAYG withheld) totals from {@code paehist} for a period, for the Withholding screen's "Calculate from Payroll" button. */
    public record PayrollTotals(BigDecimal grossWages, BigDecimal taxWithheld) {}

    /**
     * Sums payroll history for the BAS's own company between {@code fromDate}
     * and {@code toDate} inclusive, keyed on {@code payrun_date} (the same
     * date field used to filter every other payroll-by-period report in this
     * app — see {@code PayReportDataService}):
     * <ul>
     *   <li><b>Gross wages (W1)</b> — {@code sum(ext_amt)} where {@code
     *       income_taxable_flag = 'Y'}. This flag is exactly the ATO's "was
     *       this component of pay assessable income" test as already applied
     *       by {@code PayrollPostingService.isTaxableType} when each line was
     *       posted, so it naturally excludes super, deductions, non-taxable
     *       allowances, AND the synthetic tax line itself (which is posted
     *       with the flag forced to 'N').</li>
     *   <li><b>PAYG withheld (W2)</b> — {@code sum(ext_amt)} where {@code
     *       pay_code = 'TAX'} — the synthetic per-employee tax line {@code
     *       PayrollPostingService} writes at posting time ({@code pay_type =
     *       22}), carrying the actual PAYG amount withheld.</li>
     * </ul>
     */
    public PayrollTotals getPayrollTotals(int companyNo, LocalDate fromDate, LocalDate toDate) {
        Condition period = PAEHIST.COMPANY_NO.eq(companyNo)
            .and(PAEHIST.PAYRUN_DATE.between(fromDate, toDate));
        BigDecimal gross = dsl.select(DSL.sum(PAEHIST.EXT_AMT)).from(PAEHIST)
            .where(period).and(PAEHIST.INCOME_TAXABLE_FLAG.eq("Y"))
            .fetchOptional(0, BigDecimal.class).orElse(BigDecimal.ZERO);
        BigDecimal tax = dsl.select(DSL.sum(PAEHIST.EXT_AMT)).from(PAEHIST)
            .where(period).and(PAEHIST.PAY_CODE.eq("TAX"))
            .fetchOptional(0, BigDecimal.class).orElse(BigDecimal.ZERO);
        return new PayrollTotals(gross, tax);
    }

    /** Company/subsidiary name+address+ABN, for pre-filling the Create dialog's ABN field. */
    public String lookupAbn(int companyNo, int subCoyNo) {
        if (subCoyNo > 0) {
            CpsubcyRecord sc = dsl.selectFrom(CPSUBCY)
                .where(CPSUBCY.COMPANY_NO_2.eq(companyNo)).and(CPSUBCY.SUB_COY_NO.eq(subCoyNo)).fetchOne();
            return sc != null ? trim(sc.get(CPSUBCY.ABN)) : "";
        }
        CpcoycoRecord co = dsl.selectFrom(CPCOYCO).where(CPCOYCO.COMPANY_NO.eq(companyNo)).fetchOne();
        return co != null ? trim(co.get(CPCOYCO.ABN)) : "";
    }

    // ── Preview (Create dialog) ─────────────────────────────────────────

    /**
     * Estimated pull-in for the Create dialog's preview step — count and
     * gross/tax totals of unposted {@code cpbastx} rows for this group that
     * would be claimed by a BAS with this to-date/GST-include setting.
     * Does not include rows pulled in via GST-group consolidation (see
     * {@link BasPullPreview}'s Javadoc).
     */
    public BasPullPreview previewPull(String basGroup, LocalDate toDate, String includeGstFlag) {
        CpbasgrRecord grp = getGroup(basGroup);
        String posInd = grp != null ? trim(grp.get(CPBASGR.TRX_POSTING_DATE_IND)) : "";
        boolean includeGst = "Y".equalsIgnoreCase(includeGstFlag);

        List<CpbastxRecord> candidates = dsl.selectFrom(CPBASTX)
            .where(CPBASTX.BAS_GROUP.eq(basGroup)).and(CPBASTX.BAS_NO.eq(0))
            .fetch();

        int count = 0;
        BigDecimal gross = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        for (CpbastxRecord line : candidates) {
            String code = trim(line.get(CPBASTX.BAS_CODE)).toUpperCase();
            LocalDate cmp = "P".equalsIgnoreCase(posInd) ? line.get(CPBASTX.POSTING_DATE) : line.get(CPBASTX.TRX_DATE);
            if (cmp != null && toDate != null && cmp.isAfter(toDate)) continue;
            if (code.startsWith("G") && !includeGst) continue;
            count++;
            gross = gross.add(nz(line.get(CPBASTX.TRX_GROSS_AMT)));
            tax = tax.add(nz(line.get(CPBASTX.TAX_AMT)));
        }
        return new BasPullPreview(count, gross, tax);
    }

    // ── Create ───────────────────────────────────────────────────────────

    /**
     * Creates a new BAS run: bumps {@code cpbasgr}'s sequence, pulls in every
     * eligible unposted {@code cpbastx} row (plus any GST-consolidated rows
     * from other groups), runs the full calculation roll-up, and writes the
     * four optional manual figures (F1/7A/7C/7D) as synthetic transaction
     * lines. One transaction, all-or-nothing.
     *
     * @return the new BAS number
     */
    @Transactional
    public int createBas(CreateBasParams p, String userId) {
        CpbasgrRecord grp = dsl.selectFrom(CPBASGR)
            .where(CPBASGR.BAS_GROUP.eq(p.basGroup()))
            .forUpdate().fetchOne();
        if (grp == null) throw new IllegalStateException("BAS group not found.");
        if (nz(grp.get(CPBASGR.CURRENT_BAS_NO)) != 0) {
            throw new IllegalStateException("Current BAS not committed");
        }

        int newBasNo = nz(grp.get(CPBASGR.LAST_BAS_NO_USED)) + 1;
        grp.set(CPBASGR.LAST_BAS_NO_USED, newBasNo);
        grp.set(CPBASGR.CURRENT_BAS_NO, newBasNo);
        stampGr(grp, userId);
        grp.store();

        int companyNo = nz(grp.get(CPBASGR.COMPANY_NO));
        int subCoyNo = nz(grp.get(CPBASGR.SUB_COY_NO));
        String companyName, addr1, addr2, addr3;
        if (subCoyNo > 0) {
            CpsubcyRecord sc = dsl.selectFrom(CPSUBCY)
                .where(CPSUBCY.COMPANY_NO_2.eq(companyNo)).and(CPSUBCY.SUB_COY_NO.eq(subCoyNo)).fetchOne();
            companyName = sc != null ? trim(sc.get(CPSUBCY.COMPANY_NAME)) : "";
            addr1 = sc != null ? trim(sc.get(CPSUBCY.COMPANY_ADDR_1)) : "";
            addr2 = sc != null ? trim(sc.get(CPSUBCY.COMPANY_ADDR_2)) : "";
            addr3 = sc != null ? trim(sc.get(CPSUBCY.COMPANY_ADDR_3)) : "";
        } else {
            CpcoycoRecord co = dsl.selectFrom(CPCOYCO).where(CPCOYCO.COMPANY_NO.eq(companyNo)).fetchOne();
            companyName = co != null ? trim(co.get(CPCOYCO.NAME1)) : "";
            addr1 = co != null ? trim(co.get(CPCOYCO.ADDR_1)) : "";
            addr2 = co != null ? trim(co.get(CPCOYCO.ADDR_2)) : "";
            addr3 = co != null ? trim(co.get(CPCOYCO.ADDR_3)) : "";
        }

        CpbashdRecord h = dsl.newRecord(CPBASHD);
        zeroDefaults(h, CPBASHD);
        h.set(CPBASHD.BAS_DATE, LocalDate.now());
        h.set(CPBASHD.BAS_GROUP, p.basGroup());
        h.set(CPBASHD.BAS_NO, newBasNo);
        h.set(CPBASHD.COMPANY_NO, companyNo);
        h.set(CPBASHD.SUB_COY_NO, subCoyNo);
        h.set(CPBASHD.COMPANY_NAME, companyName);
        h.set(CPBASHD.COMPANY_ADDR_1, addr1);
        h.set(CPBASHD.COMPANY_ADDR_2, addr2);
        h.set(CPBASHD.COMPANY_ADDR_3, addr3);
        h.set(CPBASHD.A1_BAS_ID_NO, trim(p.basIdNo()));
        h.set(CPBASHD.A2_ABN, trim(p.abn()));
        h.set(CPBASHD.A3_FROM_DATE, p.fromDate());
        h.set(CPBASHD.A4_TO_DATE, p.toDate());
        h.set(CPBASHD.A5_DUE_DATE, p.dueDate() != null ? p.dueDate() : SENTINEL_DATE);
        h.set(CPBASHD.A6_PAY_DATE, p.payDate() != null ? p.payDate() : SENTINEL_DATE);
        String includeGst = "Y".equalsIgnoreCase(p.includeGstFlag()) ? "Y" : "N";
        h.set(CPBASHD.INCLUDE_GST_FLAG, includeGst);
        h.set(CPBASHD.GST_METHOD, trim(grp.get(CPBASGR.GST_METHOD)));
        h.set(CPBASHD.BAS_STATUS, "");   // draft

        // ── Pull unposted transactions (steps 3-4) ──────────────────────
        String posInd = trim(grp.get(CPBASGR.TRX_POSTING_DATE_IND));
        pullLines(h, p.basGroup(), newBasNo, p.toDate(), includeGst, posInd);

        if ("Y".equals(includeGst)) {
            List<String> fromGroups = dsl.select(CPGSTGR.CONSOL_FROM_BAS_GROUP)
                .from(CPGSTGR)
                .where(CPGSTGR.CONSOL_INTO_BAS_GROUP.eq(p.basGroup()))
                .fetch(CPGSTGR.CONSOL_FROM_BAS_GROUP);
            for (String fromGroup : fromGroups) {
                pullLinesConsolidated(h, fromGroup, p.basGroup(), newBasNo, p.toDate(), posInd);
            }
        }

        // ── Manual figures (step 6) — write synthetic cpbastx rows + store keys ──
        LocalDate today = LocalDate.now();
        int baseTime = nowHms();
        int timeCounter = 0;
        if (nz(p.fbtAmt()).signum() != 0) {
            String key = insertSyntheticLine(p.basGroup(), newBasNo, "F", p.toDate(), today,
                baseTime + (timeCounter++), p.fbtAmt(), companyNo, userId);
            h.set(CPBASHD.F1_FBT_AMT, p.fbtAmt());
            h.set(CPBASHD.F_CPBASTX_KEY, key);
        }
        if (nz(p.deferredImportTax()).signum() != 0) {
            String key = insertSyntheticLine(p.basGroup(), newBasNo, "7A", p.toDate(), today,
                baseTime + (timeCounter++), p.deferredImportTax(), companyNo, userId);
            h.set(CPBASHD.BAS_7A_DEFERRED_IMPORT_TAX, p.deferredImportTax());
            h.set(CPBASHD.BAS_7A_CPBASTX_KEY, key);
        }
        if (nz(p.fuelTaxCreditOverclaim()).signum() != 0) {
            String key = insertSyntheticLine(p.basGroup(), newBasNo, "7C", p.toDate(), today,
                baseTime + (timeCounter++), p.fuelTaxCreditOverclaim(), companyNo, userId);
            h.set(CPBASHD.BAS_7C_FUEL_TAX_CRED_CLAIM, p.fuelTaxCreditOverclaim());
            h.set(CPBASHD.BAS_7C_CPBASTX_KEY, key);
        }
        if (nz(p.fuelTaxCredit()).signum() != 0) {
            String key = insertSyntheticLine(p.basGroup(), newBasNo, "7D", p.toDate(), today,
                baseTime + (timeCounter++), p.fuelTaxCredit(), companyNo, userId);
            h.set(CPBASHD.BAS_7D_FUEL_TAX_CREDIT, p.fuelTaxCredit());
            h.set(CPBASHD.BAS_7D_CPBASTX_KEY, key);
        }
        // F4 reason code is blank at creation time (no UI for it yet) — calcFbt()
        // therefore copies F1 straight into 6A, matching COBOL's "no variation" path.
        BasCalculationEngine.calcFbt(h);

        // ── Roll-up (step 5 — run once, after accumulators + manual figures
        //    are both final; recalculate() is a pure/idempotent function of
        //    the header's field values so the result is identical either way) ──
        BasCalculationEngine.recalculate(h);

        stampHd(h, userId);
        h.store();
        return newBasNo;
    }

    private void pullLines(CpbashdRecord h, String basGroup, int basNo, LocalDate toDate,
                            String includeGstFlag, String posInd) {
        boolean includeGst = "Y".equals(includeGstFlag);
        List<CpbastxRecord> candidates = dsl.selectFrom(CPBASTX)
            .where(CPBASTX.BAS_GROUP.eq(basGroup)).and(CPBASTX.BAS_NO.eq(0))
            .fetch();
        for (CpbastxRecord line : candidates) {
            String code = trim(line.get(CPBASTX.BAS_CODE)).toUpperCase();
            LocalDate cmp = "P".equalsIgnoreCase(posInd) ? line.get(CPBASTX.POSTING_DATE) : line.get(CPBASTX.TRX_DATE);
            if (cmp != null && toDate != null && cmp.isAfter(toDate)) continue;
            if (code.startsWith("G") && !includeGst) continue;
            BasCalculationEngine.classify(h, line, +1);
            line.set(CPBASTX.BAS_NO, basNo);
            line.store();
        }
    }

    private void pullLinesConsolidated(CpbashdRecord h, String fromGroup, String intoGroup, int basNo,
                                        LocalDate toDate, String posIndOfIntoGroup) {
        List<CpbastxRecord> candidates = dsl.selectFrom(CPBASTX)
            .where(CPBASTX.BAS_GROUP.eq(fromGroup)).and(CPBASTX.BAS_NO.eq(0))
            .fetch();
        for (CpbastxRecord line : candidates) {
            String code = trim(line.get(CPBASTX.BAS_CODE)).toUpperCase();
            if (!code.startsWith("G")) continue;
            LocalDate cmp = "P".equalsIgnoreCase(posIndOfIntoGroup) ? line.get(CPBASTX.POSTING_DATE) : line.get(CPBASTX.TRX_DATE);
            if (cmp != null && toDate != null && cmp.isAfter(toDate)) continue;
            BasCalculationEngine.classify(h, line, +1);
            line.set(CPBASTX.BAS_GROUP, intoGroup);
            line.set(CPBASTX.BAS_NO, basNo);
            line.store();
        }
    }

    private String insertSyntheticLine(String basGroup, int basNo, String basCode, LocalDate trxDate,
                                        LocalDate dateAdded, int timeAdded, BigDecimal amount,
                                        int companyNo, String userId) {
        CpbastxRecord line = dsl.newRecord(CPBASTX);
        zeroDefaults(line, CPBASTX);
        line.set(CPBASTX.BAS_GROUP, basGroup);
        line.set(CPBASTX.BAS_NO, basNo);
        line.set(CPBASTX.BAS_CODE, basCode);
        line.set(CPBASTX.TRX_DATE, trxDate);
        line.set(CPBASTX.DATE_ADDED, dateAdded);
        line.set(CPBASTX.TIME_ADDED, timeAdded);
        line.set(CPBASTX.COMPANY_NO, companyNo);
        line.set(CPBASTX.POSTING_DATE, trxDate);
        line.set(CPBASTX.TAX_AMT, amount);
        line.set(CPBASTX.TRX_STATUS, "U");
        stampTx(line, userId);
        line.insert();
        return encodeKey(trxDate, dateAdded, timeAdded);
    }

    // ── Delete / Cancel ──────────────────────────────────────────────────

    @Transactional
    public void deleteBas(String basGroup, int basNo, String userId) {
        CpbashdRecord h = fetchHeaderForUpdate(basGroup, basNo);
        String status = trim(h.get(CPBASHD.BAS_STATUS));
        if ("C".equals(status)) throw new IllegalStateException("Statement committed — no changes");
        if ("D".equals(status)) throw new IllegalStateException("Statement cancelled — no changes");

        deleteSyntheticIfPresent(trim(h.get(CPBASHD.BAS_7A_CPBASTX_KEY)));
        deleteSyntheticIfPresent(trim(h.get(CPBASHD.BAS_7C_CPBASTX_KEY)));
        deleteSyntheticIfPresent(trim(h.get(CPBASHD.BAS_7D_CPBASTX_KEY)));
        deleteSyntheticIfPresent(trim(h.get(CPBASHD.F_CPBASTX_KEY)));
        deleteSyntheticIfPresent(trim(h.get(CPBASHD.T_CPBASTX_KEY)));

        // Safety net: any stray program-created ('U') rows still tagged to this
        // BAS get physically deleted; everything else is unlinked (bas_no -> 0).
        dsl.deleteFrom(CPBASTX)
           .where(CPBASTX.BAS_GROUP.eq(basGroup)).and(CPBASTX.BAS_NO.eq(basNo)).and(CPBASTX.TRX_STATUS.eq("U"))
           .execute();
        dsl.update(CPBASTX)
           .set(CPBASTX.BAS_NO, 0)
           .where(CPBASTX.BAS_GROUP.eq(basGroup)).and(CPBASTX.BAS_NO.eq(basNo))
           .execute();

        CpbasgrRecord grp = dsl.selectFrom(CPBASGR)
            .where(CPBASGR.BAS_GROUP.eq(basGroup)).and(CPBASGR.COMPANY_NO.eq(h.get(CPBASHD.COMPANY_NO)))
            .forUpdate().fetchOne();
        if (grp != null) {
            grp.set(CPBASGR.CURRENT_BAS_NO, 0);
            stampGr(grp, userId);
            grp.store();
        }

        h.set(CPBASHD.BAS_STATUS, "D");
        stampHd(h, userId);
        h.store();
    }

    private void deleteSyntheticIfPresent(String key) {
        if (key == null || key.isBlank()) return;
        DecodedKey dk = decodeKey(key);
        if (dk == null) return;
        dsl.deleteFrom(CPBASTX)
           .where(CPBASTX.TRX_DATE.eq(dk.trxDate()))
           .and(CPBASTX.DATE_ADDED.eq(dk.dateAdded()))
           .and(CPBASTX.TIME_ADDED.eq(dk.timeAdded()))
           .execute();
    }

    // ── Transaction lines (P4/S5) ────────────────────────────────────────

    public record LineFilter(LocalDate dateFrom, LocalDate dateTo, String source, Integer batchNo,
                              BigDecimal amtFrom, BigDecimal amtTo) { }

    public List<CpbastxRecord> getLines(String basGroup, int basNo, String basCode, LineFilter filter) {
        Condition cond = CPBASTX.BAS_GROUP.eq(basGroup).and(CPBASTX.BAS_NO.eq(basNo));
        if (basCode != null && !basCode.isBlank()) cond = cond.and(CPBASTX.BAS_CODE.eq(basCode));
        if (filter != null) {
            if (filter.dateFrom() != null) cond = cond.and(CPBASTX.TRX_DATE.ge(filter.dateFrom()));
            if (filter.dateTo() != null) cond = cond.and(CPBASTX.TRX_DATE.le(filter.dateTo()));
            if (filter.source() != null && !filter.source().isBlank()) cond = cond.and(CPBASTX.SOURCE.eq(filter.source()));
            if (filter.batchNo() != null) cond = cond.and(CPBASTX.BATCH_NO.eq(filter.batchNo()));
            if (filter.amtFrom() != null) cond = cond.and(CPBASTX.TAX_AMT.ge(filter.amtFrom()));
            if (filter.amtTo() != null) cond = cond.and(CPBASTX.TAX_AMT.le(filter.amtTo()));
        }
        return dsl.selectFrom(CPBASTX).where(cond).orderBy(CPBASTX.TRX_DATE.desc()).fetch();
    }

    @Transactional
    public void addLine(String basGroup, int basNo, String basCode, LocalDate trxDate, int companyNo, int subCoyNo,
                         BigDecimal grossAmt, BigDecimal taxAmt, Integer clearMain, Integer clearSub,
                         String ref1, String ref2, String userId) {
        CpbashdRecord h = fetchHeaderForUpdate(basGroup, basNo);
        guardEditable(h);
        validateCompanySub(companyNo, subCoyNo, basGroup);

        CpbastxRecord line = dsl.newRecord(CPBASTX);
        zeroDefaults(line, CPBASTX);
        LocalDate today = LocalDate.now();
        int timeAdded = nowHms();
        line.set(CPBASTX.BAS_GROUP, basGroup);
        line.set(CPBASTX.BAS_NO, basNo);
        line.set(CPBASTX.BAS_CODE, basCode);
        line.set(CPBASTX.TRX_DATE, trxDate);
        line.set(CPBASTX.DATE_ADDED, today);
        line.set(CPBASTX.TIME_ADDED, timeAdded);
        line.set(CPBASTX.COMPANY_NO, companyNo);
        line.set(CPBASTX.SUB_COY_NO, subCoyNo);
        line.set(CPBASTX.TRX_GROSS_AMT, nz(grossAmt));
        line.set(CPBASTX.TAX_GROSS_AMT, nz(grossAmt));
        line.set(CPBASTX.BAS_GROSS_AMT, nz(grossAmt));
        line.set(CPBASTX.TAX_AMT, nz(taxAmt));
        line.set(CPBASTX.TAX_CLEARING_MAIN, companyNo != 0 && clearMain != null ? clearMain : 0);
        line.set(CPBASTX.TAX_CLEARING_SUB, companyNo != 0 && clearSub != null ? clearSub : 0);
        line.set(CPBASTX.REF_1, ref1 == null ? "" : ref1);
        line.set(CPBASTX.REF_2, ref2 == null ? "" : ref2);
        line.set(CPBASTX.POSTING_DATE, trxDate);
        stampTx(line, userId);
        line.insert();

        BasCalculationEngine.classify(h, line, +1);
        BasCalculationEngine.recalculate(h);
        stampHd(h, userId);
        h.store();
    }

    @Transactional
    public void editLine(String basGroup, int basNo, LocalDate origTrxDate, LocalDate origDateAdded, int origTimeAdded,
                          LocalDate newTrxDate, int companyNo, int subCoyNo, BigDecimal grossAmt, BigDecimal taxAmt,
                          Integer clearMain, Integer clearSub, String ref1, String ref2, String userId) {
        CpbashdRecord h = fetchHeaderForUpdate(basGroup, basNo);
        guardEditable(h);
        validateCompanySub(companyNo, subCoyNo, basGroup);

        CpbastxRecord line = dsl.selectFrom(CPBASTX)
            .where(CPBASTX.TRX_DATE.eq(origTrxDate))
            .and(CPBASTX.DATE_ADDED.eq(origDateAdded))
            .and(CPBASTX.TIME_ADDED.eq(origTimeAdded))
            .forUpdate().fetchOne();
        if (line == null) throw new IllegalStateException("Transaction line not found.");

        BasCalculationEngine.classify(h, line, -1);   // reverse the old contribution

        line.set(CPBASTX.TRX_DATE, newTrxDate);
        line.set(CPBASTX.COMPANY_NO, companyNo);
        line.set(CPBASTX.SUB_COY_NO, subCoyNo);
        line.set(CPBASTX.TRX_GROSS_AMT, nz(grossAmt));
        line.set(CPBASTX.TAX_GROSS_AMT, nz(grossAmt));
        line.set(CPBASTX.BAS_GROSS_AMT, nz(grossAmt));
        line.set(CPBASTX.TAX_AMT, nz(taxAmt));
        line.set(CPBASTX.TAX_CLEARING_MAIN, companyNo != 0 && clearMain != null ? clearMain : 0);
        line.set(CPBASTX.TAX_CLEARING_SUB, companyNo != 0 && clearSub != null ? clearSub : 0);
        line.set(CPBASTX.REF_1, ref1 == null ? "" : ref1);
        line.set(CPBASTX.REF_2, ref2 == null ? "" : ref2);
        line.set(CPBASTX.POSTING_DATE, newTrxDate);
        stampTx(line, userId);
        line.store();   // UPDATE using the record's original (fetched) PK values

        BasCalculationEngine.classify(h, line, +1);   // apply the new contribution
        BasCalculationEngine.recalculate(h);
        stampHd(h, userId);
        h.store();
    }

    /**
     * Removes a line from a BAS. Per the real COBOL S5 "Delete/Remove
     * Transactions" behaviour: an ordinary transaction (posted by AR/AP/CM/
     * Payroll, {@code trx_status <> 'U'}) is only ever UNLINKED — {@code
     * bas_no} reset to 0 — never physically deleted, so it's picked up again
     * by the next BAS run rather than being lost. Only a synthetic line
     * ({@code trx_status = 'U'}, i.e. one this program itself created — a
     * manually-added P4/S5 line, or a header manual-figure line) may be
     * physically deleted, and only when the caller explicitly asks for that
     * via {@code deleteEntirely} — otherwise even a synthetic line is just
     * unlinked, matching the COBOL default.
     */
    @Transactional
    public void deleteLine(String basGroup, int basNo, LocalDate trxDate, LocalDate dateAdded, int timeAdded,
                            boolean deleteEntirely, String userId) {
        CpbashdRecord h = fetchHeaderForUpdate(basGroup, basNo);
        guardEditable(h);

        CpbastxRecord line = dsl.selectFrom(CPBASTX)
            .where(CPBASTX.TRX_DATE.eq(trxDate))
            .and(CPBASTX.DATE_ADDED.eq(dateAdded))
            .and(CPBASTX.TIME_ADDED.eq(timeAdded))
            .forUpdate().fetchOne();
        if (line == null) return;

        BasCalculationEngine.classify(h, line, -1);
        boolean isSynthetic = "U".equals(trim(line.get(CPBASTX.TRX_STATUS)));
        if (isSynthetic && deleteEntirely) {
            line.delete();
        } else {
            line.set(CPBASTX.BAS_NO, 0);
            stampTx(line, userId);
            line.store();
        }
        BasCalculationEngine.recalculate(h);
        stampHd(h, userId);
        h.store();
    }

    private void validateCompanySub(int companyNo, int subCoyNo, String basGroup) {
        if (companyNo == 0) return;   // 0 = "none" — allowed, no clearing account either
        boolean coExists = dsl.fetchExists(dsl.selectFrom(CPCOYCO).where(CPCOYCO.COMPANY_NO.eq(companyNo)));
        if (!coExists) throw new IllegalArgumentException("Company does not exist.");
        boolean belongs = dsl.fetchExists(dsl.selectFrom(CPBASCO)
            .where(CPBASCO.COMPANY_NO.eq(companyNo))
            .and(CPBASCO.SUB_COY_NO.eq(subCoyNo))
            .and(CPBASCO.BAS_GROUP.eq(basGroup)));
        if (!belongs) throw new IllegalArgumentException("Company and subsidiary company does not belong to this BAS group");
    }

    // ── Withholding (W1-W4) / Income Tax (T1-T4) / FBT (F1-F4) ────────────
    // The real COBOL "BAS CALCULATION SHEET - WITHHOLDING TAXES" screen shows
    // all three groups together in one window (reached by clicking 4, 5A, OR
    // 6A on the Other Taxes summary) — verified against screenshots
    // 2026-08-13. W1-W4 are plain directly-editable header fields (no
    // synthetic-line backing — cpbashd has no w*_cpbastx_key columns, unlike
    // 7A/7C/7D/F/T), so a manual entry here simply overwrites whatever a
    // transaction pull-in previously accumulated into them.

    @Transactional
    public void updateWithholdingAndInstalments(String basGroup, int basNo,
            BigDecimal w1, BigDecimal w2, BigDecimal w3, BigDecimal w4,
            BigDecimal t1, BigDecimal t3, String t4,
            BigDecimal f1, BigDecimal f2, BigDecimal f3, String f4,
            String userId) {
        String t4Code = t4 == null ? "" : t4.trim().toUpperCase();
        if (!t4Code.isEmpty() && !reasonCodeService.exists("T4", t4Code)) {
            throw new IllegalArgumentException("T4 reason code not on file");
        }
        String f4Code = f4 == null ? "" : f4.trim().toUpperCase();
        if (!f4Code.isEmpty() && !reasonCodeService.exists("F4", f4Code)) {
            throw new IllegalArgumentException("F4 reason code not on file");
        }

        CpbashdRecord h = fetchHeaderForUpdate(basGroup, basNo);
        guardEditable(h);
        h.set(CPBASHD.W1_TOTAL_WAGES, nz(w1));
        h.set(CPBASHD.W2_WAGES_WITHHELD, nz(w2));
        h.set(CPBASHD.W3_INVESTMENT_WITHHELD, nz(w3));
        h.set(CPBASHD.W4_PAYMENTS_WITHHELD, nz(w4));
        h.set(CPBASHD.T1_TAXABLE_INCOME, nz(t1));
        h.set(CPBASHD.T3_VARIED_TAX_RATE, nz(t3));
        h.set(CPBASHD.T4_REASON_CODE, t4Code);
        h.set(CPBASHD.F1_FBT_AMT, nz(f1));
        h.set(CPBASHD.F2_EST_FBT_PAYABLE, nz(f2));   // informational only — not used in any formula
        h.set(CPBASHD.F3_VARIED_FBT_AMT, nz(f3));
        h.set(CPBASHD.F4_REASON_CODE, f4Code);
        BasCalculationEngine.calcIncomeTax(h);
        BasCalculationEngine.calcFbt(h);
        BasCalculationEngine.recalculate(h);
        stampHd(h, userId);
        h.store();
    }

    // ── 7A / 7C / 7D single-figure fields ────────────────────────────────

    public enum ManualFigureKind { SEVEN_A, SEVEN_C, SEVEN_D }

    @Transactional
    public void updateManualFigure(String basGroup, int basNo, ManualFigureKind kind, BigDecimal amount, String userId) {
        CpbashdRecord h = fetchHeaderForUpdate(basGroup, basNo);
        guardEditable(h);

        String basCode = switch (kind) { case SEVEN_A -> "7A"; case SEVEN_C -> "7C"; case SEVEN_D -> "7D"; };
        String existingKey = switch (kind) {
            case SEVEN_A -> trim(h.get(CPBASHD.BAS_7A_CPBASTX_KEY));
            case SEVEN_C -> trim(h.get(CPBASHD.BAS_7C_CPBASTX_KEY));
            case SEVEN_D -> trim(h.get(CPBASHD.BAS_7D_CPBASTX_KEY));
        };

        BigDecimal amt = nz(amount);
        if (existingKey.isEmpty()) {
            LocalDate trxDate = h.get(CPBASHD.A4_TO_DATE);
            LocalDate today = LocalDate.now();
            int timeAdded = nowHms();
            String newKey = insertSyntheticLine(basGroup, basNo, basCode, trxDate, today, timeAdded,
                amt, h.get(CPBASHD.COMPANY_NO), userId);
            switch (kind) {
                case SEVEN_A -> h.set(CPBASHD.BAS_7A_CPBASTX_KEY, newKey);
                case SEVEN_C -> h.set(CPBASHD.BAS_7C_CPBASTX_KEY, newKey);
                case SEVEN_D -> h.set(CPBASHD.BAS_7D_CPBASTX_KEY, newKey);
            }
        } else {
            DecodedKey dk = decodeKey(existingKey);
            if (dk != null) {
                dsl.update(CPBASTX)
                   .set(CPBASTX.TAX_AMT, amt)
                   .set(CPBASTX.AUDIT_USER_ID, trim(userId))
                   .set(CPBASTX.AUDIT_DATE, LocalDate.now())
                   .where(CPBASTX.TRX_DATE.eq(dk.trxDate()))
                   .and(CPBASTX.DATE_ADDED.eq(dk.dateAdded()))
                   .and(CPBASTX.TIME_ADDED.eq(dk.timeAdded()))
                   .execute();
            }
        }

        switch (kind) {
            case SEVEN_A -> h.set(CPBASHD.BAS_7A_DEFERRED_IMPORT_TAX, amt);
            case SEVEN_C -> h.set(CPBASHD.BAS_7C_FUEL_TAX_CRED_CLAIM, amt);
            case SEVEN_D -> h.set(CPBASHD.BAS_7D_FUEL_TAX_CREDIT, amt);
        }
        BasCalculationEngine.recalculate(h);
        stampHd(h, userId);
        h.store();
    }

    // ── Shared guards / fetch ────────────────────────────────────────────

    private CpbashdRecord fetchHeaderForUpdate(String basGroup, int basNo) {
        CpbashdRecord h = dsl.selectFrom(CPBASHD)
            .where(CPBASHD.BAS_GROUP.eq(basGroup)).and(CPBASHD.BAS_NO.eq(basNo))
            .forUpdate().fetchOne();
        if (h == null) throw new IllegalStateException("BAS run not found.");
        return h;
    }

    private void guardEditable(CpbashdRecord h) {
        String status = trim(h.get(CPBASHD.BAS_STATUS));
        if ("C".equals(status)) throw new IllegalStateException("Statement committed — no changes");
        if ("D".equals(status)) throw new IllegalStateException("Statement cancelled — no changes");
    }

    private static String statusLabel(String status) {
        return switch (status == null ? "" : status.trim()) {
            case "C" -> "Committed";
            case "D" -> "Cancelled";
            case "P" -> "Posting";
            default -> "Draft";
        };
    }

    // ── cpbastx composite-key encode/decode for the *_cpbastx_key columns ──
    // Format: 5-digit trx_date epoch-day + 5-digit date_added epoch-day +
    // 6-digit time_added (HHMMSS) = 16 chars exactly, fits VARCHAR(16).

    private record DecodedKey(LocalDate trxDate, LocalDate dateAdded, int timeAdded) { }

    private static String encodeKey(LocalDate trxDate, LocalDate dateAdded, int timeAdded) {
        return String.format("%05d%05d%06d", trxDate.toEpochDay(), dateAdded.toEpochDay(), timeAdded);
    }

    private static DecodedKey decodeKey(String key) {
        if (key == null || key.length() != 16) return null;
        try {
            long trxEpoch = Long.parseLong(key.substring(0, 5));
            long addedEpoch = Long.parseLong(key.substring(5, 10));
            int timeAdded = Integer.parseInt(key.substring(10, 16));
            return new DecodedKey(LocalDate.ofEpochDay(trxEpoch), LocalDate.ofEpochDay(addedEpoch), timeAdded);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int nowHms() {
        LocalTime t = LocalTime.now();
        return t.getHour() * 10000 + t.getMinute() * 100 + t.getSecond();
    }

    // ── Audit stamping (per-table — field constants differ by table) ────

    private static void stampHd(CpbashdRecord h, String userId) {
        LocalTime now = LocalTime.now();
        h.set(CPBASHD.AUDIT_USER_ID, trim(userId));
        h.set(CPBASHD.AUDIT_DATE, LocalDate.now());
        h.set(CPBASHD.AUDIT_TIME_HR, now.getHour());
        h.set(CPBASHD.AUDIT_TIME_MIN, now.getMinute());
        h.set(CPBASHD.AUDIT_TIME_SEC, now.getSecond());
        h.set(CPBASHD.AUDIT_TIME_HUN, 0);
    }

    private static void stampTx(CpbastxRecord line, String userId) {
        LocalTime now = LocalTime.now();
        line.set(CPBASTX.AUDIT_USER_ID, trim(userId));
        line.set(CPBASTX.AUDIT_DATE, LocalDate.now());
        line.set(CPBASTX.AUDIT_TIME_HR, now.getHour());
        line.set(CPBASTX.AUDIT_TIME_MIN, now.getMinute());
        line.set(CPBASTX.AUDIT_TIME_SEC, now.getSecond());
        line.set(CPBASTX.AUDIT_TIME_HUN, 0);
    }

    private static void stampGr(CpbasgrRecord grp, String userId) {
        LocalTime now = LocalTime.now();
        grp.set(CPBASGR.AUDIT_USER_ID, trim(userId));
        grp.set(CPBASGR.AUDIT_DATE, LocalDate.now());
        grp.set(CPBASGR.AUDIT_TIME_HR, now.getHour());
        grp.set(CPBASGR.AUDIT_TIME_MIN, now.getMinute());
        grp.set(CPBASGR.AUDIT_TIME_SEC, now.getSecond());
        grp.set(CPBASGR.AUDIT_TIME_HUN, 0);
    }

    // ── Generic NOT-NULL defaulting for newly-constructed records ───────
    // cpbashd has ~115 columns, cpbastx ~63 — rather than enumerate every
    // one, default every column by its jOOQ Java type before the caller
    // overwrites the handful that matter. Avoids "no value for parameter N"
    // on insert for the columns this program never touches.

    @SuppressWarnings("unchecked")
    private static void zeroDefaults(Record rec, Table<?> table) {
        for (Field<?> f : table.fields()) {
            Class<?> type = f.getType();
            Object v;
            if (type == BigDecimal.class) v = BigDecimal.ZERO;
            else if (type == Long.class) v = 0L;
            else if (type == Integer.class) v = 0;
            else if (type == LocalDate.class) v = SENTINEL_DATE;
            else if (type == String.class) v = "";
            else v = null;
            rec.set((Field<Object>) f, v);
        }
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static int nz(Integer v) { return v == null ? 0 : v; }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
