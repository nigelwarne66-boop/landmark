package com.landmarksoftware.service.cm;

import com.landmarksoftware.model.AppSession;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.landmarksoftware.db.tables.Cmbanks.CMBANKS;
import static com.landmarksoftware.db.tables.Cmcbdis.CMCBDIS;
import static com.landmarksoftware.db.tables.Cmcbtrx.CMCBTRX;
import static com.landmarksoftware.db.tables.Cmrched.CMRCHED;
import static com.landmarksoftware.db.tables.Cmtrans.CMTRANS;
import static com.landmarksoftware.db.tables.Cpgstcd.CPGSTCD;
import static com.landmarksoftware.db.tables.Glchart.GLCHART;

/**
 * Cash Management (cashbook) <b>report</b> data service — one query method per CM
 * report card in the JavaFX Reports Hub ({@code -Preporting} build).
 *
 * <p>All DB access lives here via jOOQ DSLContext; controllers stay pure JavaFX.
 * Every method is a port of the matching COBOL/Perl in {@code C:\landmark\cobol\cm2},
 * with column names verified against the live {@code lmextract} schema.
 *
 * <p>CM doc_type convention: 1 = deposit, 2 = bank credit, 3 = cheque, 4 = bank
 * debit. Receipts (1/2) are positive to the cashbook; payments (3/4) negative.
 */
@Service
public class CmReportDataService {

    private static final Logger log = LoggerFactory.getLogger(CmReportDataService.class);
    private final DSLContext dsl;

    public CmReportDataService(DSLContext dsl) { this.dsl = dsl; }

    // ── Picker lookups ────────────────────────────────────────────────────────

    /** A selectable code with a display label; {@code toString()} drives ComboBox rendering. */
    public record CodeName(String code, String label) {
        @Override public String toString() { return label; }
    }

    /** Banks for the company (bank code is mandatory on every CM report). */
    public List<CodeName> getBanks(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        try {
            dsl.select(CMBANKS.BANK_CODE, CMBANKS.NAME1)
               .from(CMBANKS)
               .where(CMBANKS.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(CMBANKS.BANK_CODE)
               .fetch()
               .forEach(r -> {
                   String code = trim(r.get(CMBANKS.BANK_CODE));
                   list.add(new CodeName(code, code + " — " + trim(r.get(CMBANKS.NAME1))));
               });
        } catch (Exception e) { log.warn("getBanks: {}", e.getMessage()); }
        return list;
    }

    /** Reconciliation numbers for a bank, "(All)" first, newest first. */
    public List<CodeName> getReconNumbers(AppSession s, String bankCode) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All reconciliations)"));
        if (notBlank(bankCode)) {
            try {
                dsl.select(CMRCHED.RECON_NO, CMRCHED.STMNT_CLOSE_DATE)
                   .from(CMRCHED)
                   .where(CMRCHED.COMPANY_NO.eq(s.getCompanyNo()).and(CMRCHED.BANK_CODE.eq(bankCode)))
                   .orderBy(CMRCHED.RECON_NO.desc())
                   .fetch()
                   .forEach(r -> {
                       LocalDate d = ld(r.get(CMRCHED.STMNT_CLOSE_DATE));
                       int recon = r.get(CMRCHED.RECON_NO);
                       list.add(new CodeName(String.valueOf(recon),
                               recon + (d != null ? " — " + d : "")));
                   });
            } catch (Exception e) { log.warn("getReconNumbers: {}", e.getMessage()); }
        }
        return list;
    }

    /** Financial GL accounts, "(All)" first, "main-sub — desc" from glchart. */
    public List<CodeName> getGlAccounts(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All accounts)"));
        try {
            dsl.select(GLCHART.ACCT_MAIN_NO, GLCHART.ACCT_SUB_NO, GLCHART.DESC1)
               .from(GLCHART)
               .where(GLCHART.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(GLCHART.ACCT_MAIN_NO, GLCHART.ACCT_SUB_NO)
               .fetch()
               .forEach(r -> {
                   String code = r.get(GLCHART.ACCT_MAIN_NO) + "-" + r.get(GLCHART.ACCT_SUB_NO);
                   list.add(new CodeName(code, code + " — " + trim(r.get(GLCHART.DESC1))));
               });
        } catch (Exception e) { log.warn("getGlAccounts: {}", e.getMessage()); }
        return list;
    }

    /** Distinct GL main account numbers, "(All)" first (for the CMTL14 main-account range). */
    public List<CodeName> getGlMainAccounts(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All GL mains)"));
        try {
            dsl.selectDistinct(GLCHART.ACCT_MAIN_NO)
               .from(GLCHART)
               .where(GLCHART.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(GLCHART.ACCT_MAIN_NO)
               .fetch()
               .forEach(r -> {
                   int m = r.get(GLCHART.ACCT_MAIN_NO);
                   list.add(new CodeName(String.valueOf(m), String.valueOf(m)));
               });
        } catch (Exception e) { log.warn("getGlMainAccounts: {}", e.getMessage()); }
        return list;
    }

    /** GST/tax codes, "(All)" first, "code — desc" from cpgstcd. */
    public List<CodeName> getTaxCodes(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All tax codes)"));
        try {
            dsl.select(CPGSTCD.GST_CODE, CPGSTCD.GST_DESC)
               .from(CPGSTCD)
               .where(CPGSTCD.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(CPGSTCD.GST_CODE)
               .fetch()
               .forEach(r -> {
                   String c = trim(r.get(CPGSTCD.GST_CODE));
                   if (!c.isEmpty()) list.add(new CodeName(c, c + " — " + trim(r.get(CPGSTCD.GST_DESC))));
               });
        } catch (Exception e) { log.warn("getTaxCodes: {}", e.getMessage()); }
        return list;
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL10 — Cashbook Transactions  (cobol/cm2/cmtl10.pl)
    // ════════════════════════════════════════════════════════════════════════

    /** Selection — CMTL10S0. */
    public record CashbookTxnParams(
            String bankCode,            // mandatory
            LocalDate startDate, LocalDate endDate,
            boolean inclDeposits,       // doc_type 1
            boolean inclBankCredits,    // doc_type 2
            boolean inclCheques,        // doc_type 3
            boolean inclBankDebits,     // doc_type 4
            int startDocNo, int endDocNo,
            boolean unreconciledOnly,   // recon_no = 0 only
            String reconNo,             // blank = all; else exact recon_no
            String printSeq,            // "D" date | "N" doc number
            String detailSummary        // "D" detail | "S" summary
    ) {}

    /**
     * CMTL10 — cashbook transaction listing for a bank. doc_type drives both the
     * include filters and the sign (1/2 receipts +, 3/4 payments −). Optional
     * reconciliation filter and date/doc-no ranges; print-by date or doc number.
     */
    public Map<String, Object> getCashbookTransactions(AppSession s, CashbookTxnParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");

        List<String> types = new ArrayList<>();
        if (p.inclDeposits())    types.add("1");
        if (p.inclBankCredits()) types.add("2");
        if (p.inclCheques())     types.add("3");
        if (p.inclBankDebits())  types.add("4");
        if (types.isEmpty()) return warn("Select at least one transaction type (deposits / credits / cheques / debits).");

        Condition where = CMTRANS.COMPANY_NO.eq(s.getCompanyNo())
                .and(CMTRANS.BANK_CODE.eq(p.bankCode()))
                .and(CMTRANS.DOC_TYPE.in(types));

        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            where = where.and(CMTRANS.DOC_DATE.between(p.startDate(), end));
        }
        if (p.startDocNo() > 0) {
            int end = p.endDocNo() > 0 ? p.endDocNo() : 99999999;
            where = where.and(CMTRANS.DOC_NO.between(p.startDocNo(), end));
        }
        if (p.unreconciledOnly())
            where = where.and(CMTRANS.RECON_NO.eq(0));
        else if (notBlank(p.reconNo()))
            where = where.and(CMTRANS.RECON_NO.eq(Integer.parseInt(p.reconNo())));

        var orderBy = "N".equalsIgnoreCase(p.printSeq())
                ? new org.jooq.SortField<?>[]{ CMTRANS.DOC_TYPE.asc(), CMTRANS.DOC_NO.asc(), CMTRANS.DOC_DATE.asc() }
                : new org.jooq.SortField<?>[]{ CMTRANS.DOC_DATE.asc(), CMTRANS.DOC_TYPE.asc(), CMTRANS.DOC_NO.asc() };

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };   // receipts, payments
        int[] count = { 0 };
        try {
            dsl.select(CMTRANS.DOC_DATE, CMTRANS.DOC_TYPE, CMTRANS.DOC_NO, CMTRANS.AMT,
                       CMTRANS.TRX_STATUS, CMTRANS.RECON_NO, CMTRANS.SYSTEM_ID,
                       CMTRANS.REF, CMTRANS.PAYEE_1, CMTRANS.PAYEE_2)
               .from(CMTRANS)
               .where(where)
               .orderBy(orderBy)
               .fetch()
               .forEach(r -> {
                   String dt = trim(r.get(CMTRANS.DOC_TYPE));
                   BigDecimal amt = z(r.get(CMTRANS.AMT));
                   boolean receipt = "1".equals(dt) || "2".equals(dt);
                   BigDecimal signed = receipt ? amt : amt.negate();
                   if (receipt) tot[0] = tot[0].add(amt); else tot[1] = tot[1].add(amt);
                   count[0]++;

                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("docDate",   ldToSqlDate(r.get(CMTRANS.DOC_DATE)));
                   row.put("docType",   cmDocType(dt));
                   row.put("docNo",     r.get(CMTRANS.DOC_NO));
                   row.put("payee",     payee(r.get(CMTRANS.PAYEE_1), r.get(CMTRANS.PAYEE_2)));
                   row.put("reference", r.get(CMTRANS.REF));
                   row.put("systemId",  trim(r.get(CMTRANS.SYSTEM_ID)));
                   row.put("amount",    signed);
                   row.put("reconNo",   r.get(CMTRANS.RECON_NO));
                   row.put("status",    trxStatus(r.get(CMTRANS.TRX_STATUS)));
                   rows.add(row);
               });
        } catch (Exception e) {
            log.error("getCashbookTransactions: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", p.startDate() != null
            ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SEQ_DESC", "N".equalsIgnoreCase(p.printSeq()) ? "Document number" : "Date");
        params.put("SUMMARY_MODE", "S".equalsIgnoreCase(p.detailSummary()));
        params.put("SUM_RECEIPTS", tot[0]);
        params.put("SUM_PAYMENTS", tot[1]);
        params.put("SUM_NET", tot[0].subtract(tot[1]));
        params.put("ROW_COUNT", count[0]);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("params", params); result.put("rowCount", count[0]);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL08 — Receipt Listing  (cobol/cm2/cmtl08.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record ReceiptListingParams(
            String bankCode, int startDocNo, int endDocNo,
            LocalDate startDate, LocalDate endDate, boolean includeCancelled) {}

    /** CMTL08 — receipts (cmcbtrx trx_type='R') for a bank, with receipt-type and status. */
    public Map<String, Object> getReceiptListing(AppSession s, ReceiptListingParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");

        Condition where = CMCBTRX.COMPANY_NO.eq(s.getCompanyNo())
                .and(CMCBTRX.BANK_CODE.eq(p.bankCode()))
                .and(CMCBTRX.TRX_TYPE.eq("R"));

        if (p.startDocNo() > 0) {
            int e = p.endDocNo() > 0 ? p.endDocNo() : 99999999;
            where = where.and(CMCBTRX.DOC_NO.between(p.startDocNo(), e));
        }
        if (p.startDate() != null) {
            LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            where = where.and(CMCBTRX.DOC_DATE.between(p.startDate(), e));
        }
        if (!p.includeCancelled()) where = where.and(CMCBTRX.TRX_STATUS.ne("C"));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            dsl.select(CMCBTRX.DOC_NO, CMCBTRX.DOC_DATE, CMCBTRX.RECPT_TYPE,
                       CMCBTRX.AMT, CMCBTRX.TRX_STATUS, CMCBTRX.REF_1, CMCBTRX.RECVD_FROM)
               .from(CMCBTRX)
               .where(where)
               .orderBy(CMCBTRX.DOC_DATE, CMCBTRX.DOC_NO)
               .fetch()
               .forEach(r -> {
                   BigDecimal amt = z(r.get(CMCBTRX.AMT)); tot[0] = tot[0].add(amt);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("docNo",       r.get(CMCBTRX.DOC_NO));
                   row.put("docDate",     ldToSqlDate(r.get(CMCBTRX.DOC_DATE)));
                   row.put("recptType",   recptType(r.get(CMCBTRX.RECPT_TYPE)));
                   row.put("receivedFrom", trim(r.get(CMCBTRX.RECVD_FROM)));
                   row.put("reference",   r.get(CMCBTRX.REF_1));
                   row.put("amount",      amt);
                   row.put("status",      trxStatus(r.get(CMCBTRX.TRX_STATUS)));
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getReceiptListing: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_AMT", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL35 — Cashbook by Type  (cobol/cm2/cmtl35.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record CashbookByTypeParams(String bankCode, LocalDate startDate, LocalDate endDate) {}

    /** CMTL35 — cashbook transactions grouped by document type, with per-type subtotals. */
    public Map<String, Object> getCashbookByType(AppSession s, CashbookByTypeParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");

        Condition where = CMTRANS.COMPANY_NO.eq(s.getCompanyNo())
                .and(CMTRANS.BANK_CODE.eq(p.bankCode()))
                .and(CMTRANS.DOC_TYPE.in("1", "2", "3", "4"));

        if (p.startDate() != null) {
            LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            where = where.and(CMTRANS.DOC_DATE.between(p.startDate(), e));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            dsl.select(CMTRANS.DOC_TYPE, CMTRANS.DOC_DATE, CMTRANS.DOC_NO,
                       CMTRANS.PAYEE_1, CMTRANS.PAYEE_2, CMTRANS.REF, CMTRANS.AMT)
               .from(CMTRANS)
               .where(where)
               .orderBy(CMTRANS.DOC_TYPE, CMTRANS.DOC_DATE, CMTRANS.DOC_NO)
               .fetch()
               .forEach(r -> {
                   String dt = trim(r.get(CMTRANS.DOC_TYPE));
                   BigDecimal amt = z(r.get(CMTRANS.AMT));
                   BigDecimal signed = ("1".equals(dt) || "2".equals(dt)) ? amt : amt.negate();
                   tot[0] = tot[0].add(signed);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("docTypeCode", dt);
                   row.put("docType",     cmDocType(dt));
                   row.put("docDate",     ldToSqlDate(r.get(CMTRANS.DOC_DATE)));
                   row.put("docNo",       r.get(CMTRANS.DOC_NO));
                   row.put("payee",       payee(r.get(CMTRANS.PAYEE_1), r.get(CMTRANS.PAYEE_2)));
                   row.put("reference",   r.get(CMTRANS.REF));
                   row.put("amount",      signed);
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getCashbookByType: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_NET", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMCB02 — Cashbook Listing  (cobol/cm2/cmcb02.pl) — document-level
    // ════════════════════════════════════════════════════════════════════════

    public record CashbookListingParams(String bankCode, LocalDate startDate, LocalDate endDate, String trxType) {}

    /** CMCB02 — cashbook documents (cmcbdis aggregated by document), receipts/payments/both. */
    public Map<String, Object> getCashbookListing(AppSession s, CashbookListingParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");

        Condition where = CMCBDIS.COMPANY_NO.eq(s.getCompanyNo())
                .and(CMCBDIS.BANK_CODE.eq(p.bankCode()));

        if (p.startDate() != null) {
            LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            where = where.and(CMCBDIS.DOC_DATE.between(p.startDate(), e));
        }
        // doc_type < '3' = receipts; doc_type >= '3' = payments (string comparison on VARCHAR(1))
        if ("R".equalsIgnoreCase(p.trxType()))      where = where.and(CMCBDIS.DOC_TYPE.lt("3"));
        else if ("P".equalsIgnoreCase(p.trxType())) where = where.and(CMCBDIS.DOC_TYPE.ge("3"));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            dsl.select(CMCBDIS.DOC_TYPE, CMCBDIS.DOC_NO, CMCBDIS.DOC_DATE,
                       DSL.max(CMCBDIS.PAYEE_NAME_1).as("payee"),
                       DSL.sum(CMCBDIS.AMT).as("doc_amt"),
                       DSL.count().as("lines"))
               .from(CMCBDIS)
               .where(where)
               .groupBy(CMCBDIS.DOC_TYPE, CMCBDIS.DOC_NO, CMCBDIS.DOC_DATE)
               .orderBy(CMCBDIS.DOC_TYPE, CMCBDIS.DOC_DATE, CMCBDIS.DOC_NO)
               .fetch()
               .forEach(r -> {
                   String dt = trim(r.get(CMCBDIS.DOC_TYPE));
                   BigDecimal amt = z(r.get("doc_amt", BigDecimal.class));
                   BigDecimal signed = ("1".equals(dt) || "2".equals(dt)) ? amt : amt.negate();
                   tot[0] = tot[0].add(signed);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("docType",  cmDocType(dt));
                   row.put("docNo",    r.get(CMCBDIS.DOC_NO));
                   row.put("docDate",  ldToSqlDate(r.get(CMCBDIS.DOC_DATE)));
                   row.put("payee",    trim(r.get("payee", String.class)));
                   row.put("lines",    r.get("lines", Integer.class));
                   row.put("amount",   signed);
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getCashbookListing: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("TRX_TYPE_DESC", "R".equalsIgnoreCase(p.trxType()) ? "Receipts" : "P".equalsIgnoreCase(p.trxType()) ? "Payments" : "Receipts & payments");
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_NET", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL14 — Cashbook Distributions  (cobol/cm2/cmtl14.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record CashbookDistParams(
            String bankCode, LocalDate startDate, LocalDate endDate,
            boolean inclDeposits, boolean inclBankCredits, boolean inclCheques, boolean inclBankDebits,
            String startGlMain, String endGlMain, String taxCode, String printSeq) {}

    /** CMTL14 — cashbook distribution lines with GL account + tax, GL-main and tax-code filters. */
    public Map<String, Object> getCashbookDistributions(AppSession s, CashbookDistParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");
        List<String> types = new ArrayList<>();
        if (p.inclDeposits()) types.add("1");
        if (p.inclBankCredits()) types.add("2");
        if (p.inclCheques()) types.add("3");
        if (p.inclBankDebits()) types.add("4");
        if (types.isEmpty()) return warn("Select at least one transaction type.");

        // Alias glchart to avoid ambiguous column names in the join
        var g = GLCHART.as("g");

        Condition where = CMCBDIS.COMPANY_NO.eq(s.getCompanyNo())
                .and(CMCBDIS.BANK_CODE.eq(p.bankCode()))
                .and(CMCBDIS.DOC_TYPE.in(types));

        if (p.startDate() != null) {
            LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            where = where.and(CMCBDIS.DOC_DATE.between(p.startDate(), e));
        }
        if (notBlank(p.startGlMain())) {
            int startMain = Integer.parseInt(p.startGlMain());
            int endMain   = notBlank(p.endGlMain()) ? Integer.parseInt(p.endGlMain()) : 999999;
            where = where.and(CMCBDIS.GL_ACCT_MAIN.between(startMain, endMain));
        }
        if (notBlank(p.taxCode())) where = where.and(CMCBDIS.TAX_CODE.eq(p.taxCode()));

        var orderBy = "N".equalsIgnoreCase(p.printSeq())
                ? new org.jooq.SortField<?>[]{ CMCBDIS.DOC_TYPE.asc(), CMCBDIS.DOC_NO.asc(), CMCBDIS.SEQ_NO.asc() }
                : new org.jooq.SortField<?>[]{ CMCBDIS.DOC_DATE.asc(), CMCBDIS.DOC_TYPE.asc(), CMCBDIS.DOC_NO.asc(), CMCBDIS.SEQ_NO.asc() };

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(CMCBDIS.DOC_DATE, CMCBDIS.DOC_TYPE, CMCBDIS.DOC_NO, CMCBDIS.SEQ_NO,
                       CMCBDIS.GL_ACCT_MAIN, CMCBDIS.GL_ACCT_SUB,
                       DSL.coalesce(g.field(GLCHART.DESC1), DSL.val("")).as("gl_desc"),
                       CMCBDIS.PAYEE_NAME_1, CMCBDIS.REF_1, CMCBDIS.AMT,
                       CMCBDIS.TAX_CODE, CMCBDIS.TAX_AMT, CMCBDIS.SYSTEM_ID,
                       CMCBDIS.BATCH_NO, CMCBDIS.BAS_GROUP)
               .from(CMCBDIS)
               .leftJoin(g).on(g.field(GLCHART.COMPANY_NO).eq(CMCBDIS.COMPANY_NO)
                       .and(g.field(GLCHART.ACCT_MAIN_NO).eq(CMCBDIS.GL_ACCT_MAIN))
                       .and(g.field(GLCHART.ACCT_SUB_NO).eq(CMCBDIS.GL_ACCT_SUB)))
               .where(where)
               .orderBy(orderBy)
               .fetch()
               .forEach(r -> {
                   BigDecimal amt = z(r.get(CMCBDIS.AMT));
                   BigDecimal tax = z(r.get(CMCBDIS.TAX_AMT));
                   if ("T".equalsIgnoreCase(trim(r.get(CMCBDIS.TAX_CODE))) && tax.signum() == 0)
                       tax = amt.divide(BigDecimal.valueOf(10), 2, java.math.RoundingMode.HALF_UP);
                   tot[0] = tot[0].add(amt); tot[1] = tot[1].add(tax);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("docDate",   ldToSqlDate(r.get(CMCBDIS.DOC_DATE)));
                   row.put("docType",   cmDocType(trim(r.get(CMCBDIS.DOC_TYPE))));
                   row.put("docNo",     r.get(CMCBDIS.DOC_NO));
                   row.put("seqNo",     r.get(CMCBDIS.SEQ_NO));
                   row.put("glAcct",    r.get(CMCBDIS.GL_ACCT_MAIN) + "-" + r.get(CMCBDIS.GL_ACCT_SUB));
                   row.put("glDesc",    r.get("gl_desc", String.class));
                   row.put("payee",     trim(r.get(CMCBDIS.PAYEE_NAME_1)));
                   row.put("reference", r.get(CMCBDIS.REF_1));
                   row.put("amount",    amt);
                   row.put("taxCode",   trim(r.get(CMCBDIS.TAX_CODE)));
                   row.put("taxAmt",    tax);
                   row.put("systemId",  trim(r.get(CMCBDIS.SYSTEM_ID)));
                   row.put("batchNo",   r.get(CMCBDIS.BATCH_NO));
                   row.put("basGroup",  trim(r.get(CMCBDIS.BAS_GROUP)));
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getCashbookDistributions: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_AMT", tot[0]); params.put("SUM_TAX", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL05 — Cashbook Ledger  (cobol/cm2/cmtl05.pl) — running balance
    // ════════════════════════════════════════════════════════════════════════

    public record CashbookLedgerParams(String bankCode, LocalDate startDate, LocalDate endDate) {}

    /** CMTL05 — cashbook ledger with opening balance + running balance per distribution. */
    public Map<String, Object> getCashbookLedger(AppSession s, CashbookLedgerParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");
        BigDecimal openBal;
        try {
            var rec = dsl.select(CMBANKS.OPEN_STMNT_BAL)
                         .from(CMBANKS)
                         .where(CMBANKS.COMPANY_NO.eq(s.getCompanyNo()).and(CMBANKS.BANK_CODE.eq(p.bankCode())))
                         .fetchOne();
            openBal = rec != null ? z(rec.get(CMBANKS.OPEN_STMNT_BAL)) : BigDecimal.ZERO;
        } catch (Exception e) { openBal = BigDecimal.ZERO; }

        LocalDate endDate = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] running = { openBal };
        LocalDate startDate = p.startDate();
        try {
            // Fetch all rows up to endDate; accumulate running balance across all, but only
            // emit rows within [startDate, endDate] into the result list (pre-range rows
            // still update the running balance — matching COBOL behaviour).
            dsl.select(CMCBDIS.DOC_DATE, CMCBDIS.DOC_TYPE, CMCBDIS.DOC_NO,
                       CMCBDIS.SEQ_NO, CMCBDIS.AMT)
               .from(CMCBDIS)
               .where(CMCBDIS.COMPANY_NO.eq(s.getCompanyNo())
                       .and(CMCBDIS.BANK_CODE.eq(p.bankCode()))
                       .and(CMCBDIS.DOC_DATE.le(endDate)))
               .orderBy(CMCBDIS.DOC_DATE, CMCBDIS.DOC_TYPE, CMCBDIS.DOC_NO, CMCBDIS.SEQ_NO)
               .fetch()
               .forEach(r -> {
                   String dt = trim(r.get(CMCBDIS.DOC_TYPE));
                   BigDecimal amt = z(r.get(CMCBDIS.AMT));
                   BigDecimal signed = ("1".equals(dt) || "2".equals(dt)) ? amt : amt.negate();
                   running[0] = running[0].add(signed);
                   LocalDate d = r.get(CMCBDIS.DOC_DATE);
                   if (startDate != null && d != null && d.isBefore(startDate)) return;  // pre-range: balance only
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("docDate",  ldToSqlDate(d));
                   row.put("docType",  cmDocType(dt));
                   row.put("docNo",    r.get(CMCBDIS.DOC_NO));
                   row.put("seqNo",    r.get(CMCBDIS.SEQ_NO));
                   row.put("amount",   signed);
                   row.put("balance",  running[0]);
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getCashbookLedger: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", startDate != null ? startDate + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("OPENING_BAL", openBal); params.put("CLOSING_BAL", running[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL18 — Document Listing  (cobol/cm2/cmtl18.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record DocumentListingParams(
            String bankCode, LocalDate startDate, LocalDate endDate,
            boolean inclDeposits, boolean inclBankCredits, boolean inclCheques, boolean inclBankDebits,
            int startDocNo, int endDocNo) {}

    /** CMTL18 — cashbook document listing (cmtrans) with amount paid + outstanding. */
    public Map<String, Object> getDocumentListing(AppSession s, DocumentListingParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");
        List<String> types = new ArrayList<>();
        if (p.inclDeposits()) types.add("1");
        if (p.inclBankCredits()) types.add("2");
        if (p.inclCheques()) types.add("3");
        if (p.inclBankDebits()) types.add("4");
        if (types.isEmpty()) return warn("Select at least one transaction type.");

        Condition where = CMTRANS.COMPANY_NO.eq(s.getCompanyNo())
                .and(CMTRANS.BANK_CODE.eq(p.bankCode()))
                .and(CMTRANS.DOC_TYPE.in(types));

        if (p.startDate() != null) {
            LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            where = where.and(CMTRANS.DOC_DATE.between(p.startDate(), e));
        }
        if (p.startDocNo() > 0) {
            int e = p.endDocNo() > 0 ? p.endDocNo() : 99999999;
            where = where.and(CMTRANS.DOC_NO.between(p.startDocNo(), e));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(CMTRANS.DOC_DATE, CMTRANS.DOC_TYPE, CMTRANS.DOC_NO,
                       CMTRANS.PAYEE_1, CMTRANS.PAYEE_2, CMTRANS.REF,
                       CMTRANS.AMT, CMTRANS.AMT_PAID, CMTRANS.TRX_STATUS, CMTRANS.SYSTEM_ID)
               .from(CMTRANS)
               .where(where)
               .orderBy(CMTRANS.DOC_DATE, CMTRANS.DOC_TYPE, CMTRANS.DOC_NO)
               .fetch()
               .forEach(r -> {
                   String dt = trim(r.get(CMTRANS.DOC_TYPE));
                   BigDecimal amt = z(r.get(CMTRANS.AMT)), paid = z(r.get(CMTRANS.AMT_PAID));
                   BigDecimal outstanding = amt.subtract(paid);
                   tot[0] = tot[0].add(amt); tot[1] = tot[1].add(paid); tot[2] = tot[2].add(outstanding);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("docDate",     ldToSqlDate(r.get(CMTRANS.DOC_DATE)));
                   row.put("docType",     cmDocType(dt));
                   row.put("docNo",       r.get(CMTRANS.DOC_NO));
                   row.put("payee",       payee(r.get(CMTRANS.PAYEE_1), r.get(CMTRANS.PAYEE_2)));
                   row.put("reference",   r.get(CMTRANS.REF));
                   row.put("systemId",    trim(r.get(CMTRANS.SYSTEM_ID)));
                   row.put("amount",      amt);
                   row.put("amtPaid",     paid);
                   row.put("outstanding", outstanding);
                   row.put("status",      trxStatus(r.get(CMTRANS.TRX_STATUS)));
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getDocumentListing: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_AMT", tot[0]); params.put("SUM_PAID", tot[1]); params.put("SUM_OUTSTANDING", tot[2]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL02 — Bank Reconciliation Statement  (cobol/cm2/cmtl02.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record BankReconParams(String bankCode, String reconNo) {}

    /**
     * CMTL02 — reconciliation statement: cmrched header balances + the transactions
     * reconciled under that recon number. The recon detail tables (cmrcdis/cmrecln)
     * are empty in the extract, so the detail is reconstructed from cmtrans.
     */
    public Map<String, Object> getBankReconStatement(AppSession s, BankReconParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");
        if (!notBlank(p.reconNo())) return warn("Choose a reconciliation number.");
        int recon = Integer.parseInt(p.reconNo());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("RECON_NO", recon);
        try {
            var rec = dsl.select(CMRCHED.STMNT_OPEN_DATE, CMRCHED.STMNT_CLOSE_DATE,
                                 CMRCHED.CSHBK_OPEN_BAL, CMRCHED.STMNT_OPEN_BAL,
                                 CMRCHED.STMNT_CLOSE_BAL, CMRCHED.DRS_ENTERED, CMRCHED.CRS_ENTERED)
                         .from(CMRCHED)
                         .where(CMRCHED.COMPANY_NO.eq(s.getCompanyNo())
                                 .and(CMRCHED.BANK_CODE.eq(p.bankCode()))
                                 .and(CMRCHED.RECON_NO.eq(recon)))
                         .fetchOne();
            if (rec != null) {
                LocalDate od = ld(rec.get(CMRCHED.STMNT_OPEN_DATE)), cd = ld(rec.get(CMRCHED.STMNT_CLOSE_DATE));
                params.put("STMNT_OPEN_DATE",  dmy(od));
                params.put("STMNT_CLOSE_DATE", dmy(cd));
                params.put("CSHBK_OPEN_BAL",   z(rec.get(CMRCHED.CSHBK_OPEN_BAL)));
                params.put("STMNT_OPEN_BAL",   z(rec.get(CMRCHED.STMNT_OPEN_BAL)));
                params.put("STMNT_CLOSE_BAL",  z(rec.get(CMRCHED.STMNT_CLOSE_BAL)));
                params.put("DRS_ENTERED",       z(rec.get(CMRCHED.DRS_ENTERED)));
                params.put("CRS_ENTERED",       z(rec.get(CMRCHED.CRS_ENTERED)));
            }
        } catch (Exception e) { log.warn("CMTL02 header: {}", e.getMessage()); }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            dsl.select(CMTRANS.DOC_DATE, CMTRANS.DOC_TYPE, CMTRANS.DOC_NO,
                       CMTRANS.PAYEE_1, CMTRANS.PAYEE_2, CMTRANS.AMT, CMTRANS.TRX_STATUS)
               .from(CMTRANS)
               .where(CMTRANS.COMPANY_NO.eq(s.getCompanyNo())
                       .and(CMTRANS.BANK_CODE.eq(p.bankCode()))
                       .and(CMTRANS.RECON_NO.eq(recon)))
               .orderBy(CMTRANS.DOC_TYPE, CMTRANS.DOC_DATE, CMTRANS.DOC_NO)
               .fetch()
               .forEach(r -> {
                   String dt = trim(r.get(CMTRANS.DOC_TYPE));
                   BigDecimal amt = z(r.get(CMTRANS.AMT));
                   BigDecimal signed = ("1".equals(dt) || "2".equals(dt)) ? amt : amt.negate();
                   tot[0] = tot[0].add(signed);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("docDate", ldToSqlDate(r.get(CMTRANS.DOC_DATE)));
                   row.put("docType", cmDocType(dt));
                   row.put("docNo",   r.get(CMTRANS.DOC_NO));
                   row.put("payee",   payee(r.get(CMTRANS.PAYEE_1), r.get(CMTRANS.PAYEE_2)));
                   row.put("amount",  signed);
                   row.put("status",  trxStatus(r.get(CMTRANS.TRX_STATUS)));
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getBankReconStatement: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        params.put("SUM_RECONCILED", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CMTL30 — Cashbook Foreign Currency Match  (cobol/cm2/cmtl30.pl)
    // ════════════════════════════════════════════════════════════════════════

    public record FcMatchParams(String bankCode, LocalDate startDate, LocalDate endDate, int startDocNo, int endDocNo) {}

    /** CMTL30 — foreign-currency cashbook transactions (cmtrans with an FC rate) and amounts. */
    public Map<String, Object> getForeignCurrencyMatch(AppSession s, FcMatchParams p) {
        if (!notBlank(p.bankCode())) return warn("Choose a bank.");

        Condition where = CMTRANS.COMPANY_NO.eq(s.getCompanyNo())
                .and(CMTRANS.BANK_CODE.eq(p.bankCode()))
                .and(CMTRANS.ORIG_EXCHANGE_RATE.ne(BigDecimal.ZERO));

        if (p.startDate() != null) {
            LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            where = where.and(CMTRANS.DOC_DATE.between(p.startDate(), e));
        }
        if (p.startDocNo() > 0) {
            int e = p.endDocNo() > 0 ? p.endDocNo() : 99999999;
            where = where.and(CMTRANS.DOC_NO.between(p.startDocNo(), e));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            dsl.select(CMTRANS.DOC_DATE, CMTRANS.DOC_TYPE, CMTRANS.DOC_NO, CMTRANS.AMT,
                       CMTRANS.ORIG_LOCAL_AMT, CMTRANS.ORIG_PARENT_AMT, CMTRANS.CURR_LOCAL_AMT,
                       CMTRANS.ORIG_EXCHANGE_RATE, CMTRANS.LAST_EXCHANGE_RATE,
                       CMTRANS.LAST_REVAL_DATE, CMTRANS.TRX_STATUS)
               .from(CMTRANS)
               .where(where)
               .orderBy(CMTRANS.DOC_DATE, CMTRANS.DOC_TYPE, CMTRANS.DOC_NO)
               .fetch()
               .forEach(r -> {
                   String dt = trim(r.get(CMTRANS.DOC_TYPE));
                   BigDecimal local = z(r.get(CMTRANS.AMT));
                   tot[0] = tot[0].add(local);
                   LocalDate rv = ld(r.get(CMTRANS.LAST_REVAL_DATE));
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("docDate",      ldToSqlDate(r.get(CMTRANS.DOC_DATE)));
                   row.put("docType",      cmDocType(dt));
                   row.put("docNo",        r.get(CMTRANS.DOC_NO));
                   row.put("localAmt",     local);
                   row.put("origLocalAmt", z(r.get(CMTRANS.ORIG_LOCAL_AMT)));
                   row.put("parentAmt",    z(r.get(CMTRANS.ORIG_PARENT_AMT)));
                   row.put("exchangeRate", z(r.get(CMTRANS.ORIG_EXCHANGE_RATE)));
                   row.put("lastRate",     z(r.get(CMTRANS.LAST_EXCHANGE_RATE)));
                   row.put("revalDate",    rv != null ? java.sql.Date.valueOf(rv) : null);
                   row.put("status",       trxStatus(r.get(CMTRANS.TRX_STATUS)));
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getForeignCurrencyMatch: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BANK_DESC", bankLabel(s, p.bankCode()));
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All dates");
        params.put("SUM_LOCAL", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    private Map<String, Object> result(List<Map<String, Object>> rows, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows); m.put("params", params); m.put("rowCount", rows.size());
        return m;
    }

    static String recptType(String t) {
        return switch (trim(t)) {
            case "C" -> "Cash"; case "Q" -> "Cheque"; case "R" -> "Credit Card"; case "E" -> "EFT";
            default -> trim(t);
        };
    }

    // ── shared helpers ─────────────────────────────────────────────────────────

    String bankLabel(AppSession s, String bankCode) {
        try {
            var rec = dsl.select(CMBANKS.NAME1)
                         .from(CMBANKS)
                         .where(CMBANKS.COMPANY_NO.eq(s.getCompanyNo()).and(CMBANKS.BANK_CODE.eq(bankCode)))
                         .fetchOne();
            return rec != null ? bankCode + " — " + trim(rec.get(CMBANKS.NAME1)) : bankCode;
        } catch (Exception e) { return bankCode; }
    }

    static String cmDocType(String t) {
        return switch (trim(t)) {
            case "1" -> "Deposit"; case "2" -> "Bank Cr"; case "3" -> "Cheque"; case "4" -> "Bank Dr";
            default -> trim(t);
        };
    }

    static String trxStatus(String s) {
        return switch (trim(s)) {
            case "C" -> "Cancelled"; case "A" -> "Adjustment"; case "D" -> "Deleted";
            case "S" -> "Selected"; default -> "";
        };
    }

    static String payee(String p1, String p2) {
        String a = trim(p1), b = trim(p2);
        return b.isEmpty() ? a : (a + " " + b).trim();
    }

    /** Convert a jOOQ-returned LocalDate to java.sql.Date for Jasper, suppressing sentinel dates. */
    static java.sql.Date ldToSqlDate(LocalDate d) {
        if (d == null) return null;
        return d.isAfter(LocalDate.of(1900, 1, 1)) ? java.sql.Date.valueOf(d) : null;
    }

    /** Guard against COBOL date sentinels (1899-12-31) — return null for those. */
    static LocalDate ld(LocalDate d) {
        if (d == null) return null;
        return d.isAfter(LocalDate.of(1900, 1, 1)) ? d : null;
    }

    static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    static String trim(String s) { return s == null ? "" : s.trim(); }
    static BigDecimal z(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    Map<String, Object> warn(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", new ArrayList<>()); m.put("params", new LinkedHashMap<>());
        m.put("rowCount", 0); m.put("warning", msg);
        return m;
    }

    /** Format a date as dd-MM-yyyy for report display (blank when null). */
    private static String dmy(java.time.LocalDate d) {
        return d == null ? "" : d.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"));
    }
}
