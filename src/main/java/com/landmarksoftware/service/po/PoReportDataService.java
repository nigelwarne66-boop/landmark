package com.landmarksoftware.service.po;

import com.landmarksoftware.model.AppSession;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.landmarksoftware.db.tables.Apdocno.APDOCNO;
import static com.landmarksoftware.db.tables.Apsupps.APSUPPS;
import static com.landmarksoftware.db.tables.Glchart.GLCHART;
import static com.landmarksoftware.db.tables.Popohed.POPOHED;
import static com.landmarksoftware.db.tables.Popolin.POPOLIN;
import static com.landmarksoftware.db.tables.Smlocat.SMLOCAT;

/**
 * Purchasing (PO) <b>report</b> data service — one query method per PO report card
 * in the JavaFX Reports Hub ({@code -Preporting} build).
 *
 * <p>All JDBC lives here. Ported from {@code C:\landmark\cobol\po2\potl*}, columns
 * verified against the live {@code lmextract} schema. Header in {@code popohed},
 * lines in {@code popolin}; supplier names from {@code apsupps}.
 *
 * <p>Migrated from JdbcTemplate to jOOQ DSLContext.
 */
@Service
public class PoReportDataService {

    private static final Logger log = LoggerFactory.getLogger(PoReportDataService.class);
    private final DSLContext dsl;

    public PoReportDataService(DSLContext dsl) { this.dsl = dsl; }

    /** A selectable code with a display label; {@code toString()} drives ComboBox rendering. */
    public record CodeName(String code, String label) {
        @Override public String toString() { return label; }
    }

    // ── Lookups (the COBOL F5 lists) ──────────────────────────────────────────

    /** Purchasing locations, "(All)" first, from smlocat. */
    public List<CodeName> getLocations(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All locations)"));
        try {
            dsl.selectDistinct(SMLOCAT.LOC_NO)
               .from(SMLOCAT)
               .where(SMLOCAT.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(SMLOCAT.LOC_NO)
               .fetch()
               .forEach(r -> {
                   String l = trim(r.get(SMLOCAT.LOC_NO));
                   if (!l.isEmpty()) list.add(new CodeName(l, l));
               });
        } catch (Exception e) { log.warn("getLocations: {}", e.getMessage()); }
        return list;
    }

    /** Suppliers, "(All)" first, "code — name" from apsupps. */
    public List<CodeName> getSuppliers(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All suppliers)"));
        try {
            dsl.select(APSUPPS.SUPPLIER_NO, APSUPPS.NAME_1)
               .from(APSUPPS)
               .where(APSUPPS.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(APSUPPS.SUPPLIER_NO)
               .fetch()
               .forEach(r -> {
                   String no = trim(r.get(APSUPPS.SUPPLIER_NO));
                   list.add(new CodeName(no, no + " — " + trim(r.get(APSUPPS.NAME_1))));
               });
        } catch (Exception e) { log.warn("getSuppliers: {}", e.getMessage()); }
        return list;
    }

    /** PO numbers, "(All)" first, from popohed (optionally scoped to a location). */
    public List<CodeName> getPoNumbers(AppSession s, String locNo) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All purchase orders)"));
        try {
            Condition where = POPOHED.COMPANY_NO.eq(s.getCompanyNo());
            if (notBlank(locNo)) where = where.and(POPOHED.LOC_NO.eq(locNo));
            dsl.selectDistinct(POPOHED.PO_NO)
               .from(POPOHED)
               .where(where)
               .orderBy(POPOHED.PO_NO)
               .fetch()
               .forEach(r -> {
                   int n = r.get(POPOHED.PO_NO);
                   list.add(new CodeName(String.valueOf(n), String.valueOf(n)));
               });
        } catch (Exception e) { log.warn("getPoNumbers: {}", e.getMessage()); }
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

    // ════════════════════════════════════════════════════════════════════════
    // POTL22 (+23/24/25/26/27/29/31/42) — Purchase Orders in Sequence
    // ════════════════════════════════════════════════════════════════════════

    /** Sort sequence for the consolidated PO list — drives the ORDER BY. */
    public enum PoSequence {
        ORDER_NO, SUPPLIER, ITEM, DELIVERY_DATE, ORDER_DATE, GL_ACCOUNT, COST_LEDGER, BA_LEDGER
    }

    /** Selection — the single screen shared by POTL22/23/24/25/26/27/29/31/42. */
    public record PoInSequenceParams(
            String locNo,
            int startPoNo, int endPoNo,
            String startSupplier, String endSupplier,
            LocalDate startDate, LocalDate endDate,     // order date
            String sequence,                            // PoSequence name
            String origCurr,                            // "O" original | "C" current
            boolean outstandingOnly
    ) {}

    /**
     * POTL22 family — purchase-order lines for the chosen sort sequence. One screen,
     * a Sequence chooser sets the ORDER BY (Order No / Supplier / Item / Delivery Date /
     * Order Date / GL Account / Cost Ledger / BA Ledger). Original or current order
     * values per {@code origCurr}; optional outstanding-only filter.
     */
    public Map<String, Object> getPurchaseOrdersInSequence(AppSession s, PoInSequenceParams p) {
        PoSequence seq;
        try { seq = PoSequence.valueOf(p.sequence()); }
        catch (Exception e) { seq = PoSequence.ORDER_NO; }
        boolean orig = "O".equalsIgnoreCase(p.origCurr());
        String pfx = orig ? "orig" : "curr";

        // Aliased tables for the join
        var l = POPOLIN.as("l");
        var h = POPOHED.as("h");

        // Dynamic column references based on origCurr (orig_ vs curr_ prefix)
        Field<BigDecimal> colQtyOrdered = DSL.field(DSL.name("l", pfx + "_qty_ordered"), BigDecimal.class);
        Field<BigDecimal> colUnitCost   = DSL.field(DSL.name("l", pfx + "_unit_cost"), BigDecimal.class);
        Field<BigDecimal> colExtAmt     = DSL.field(DSL.name("l", pfx + "_ext_amt"), BigDecimal.class);
        Field<BigDecimal> colTaxAmt     = DSL.field(DSL.name("l", pfx + "_tax_amt"), BigDecimal.class);

        // Base where
        Condition where = l.COMPANY_NO.eq(s.getCompanyNo()).and(l.LINE_TYPE.ne("C"));
        if (notBlank(p.locNo()))          where = where.and(l.LOC_NO.eq(p.locNo()));
        if (p.startPoNo() > 0) {
            int end = p.endPoNo() > 0 ? p.endPoNo() : 999999;
            where = where.and(l.PO_NO.between(p.startPoNo(), end));
        }
        if (notBlank(p.startSupplier())) {
            String end = notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz";
            where = where.and(h.SUPPLIER_NO.between(p.startSupplier(), end));
        }
        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            where = where.and(h.PO_DATE.between(p.startDate(), end));
        }
        if (p.outstandingOnly() && !orig) {
            where = where.and(DSL.field(DSL.name("l", "curr_qty_ordered"), BigDecimal.class)
                .subtract(l.RECVD_QTY).gt(BigDecimal.ZERO));
        }

        // ORDER BY
        var orderBy = switch (seq) {
            case SUPPLIER      -> new org.jooq.SortField<?>[]{ h.SUPPLIER_NO.asc(), l.PO_NO.asc(), l.LINE_NO.asc() };
            case ITEM          -> new org.jooq.SortField<?>[]{ l.STOCK_CODE.asc(), l.DELIV_LOC_NO.asc(), l.PO_NO.asc(), l.LINE_NO.asc() };
            case DELIVERY_DATE -> new org.jooq.SortField<?>[]{ l.NEXT_DELIV_DATE.asc(), l.PO_NO.asc(), l.LINE_NO.asc() };
            case ORDER_DATE    -> new org.jooq.SortField<?>[]{ h.PO_DATE.asc(), l.PO_NO.asc(), l.LINE_NO.asc() };
            case GL_ACCOUNT    -> new org.jooq.SortField<?>[]{ l.GL_ACCT_MAIN.asc(), l.GL_ACCT_SUB.asc(), l.PO_NO.asc(), l.LINE_NO.asc() };
            case COST_LEDGER   -> new org.jooq.SortField<?>[]{ l.LEDGER_TYPE.asc(), l.LEDGER_CODE.asc(), l.PO_NO.asc(), l.LINE_NO.asc() };
            case BA_LEDGER     -> new org.jooq.SortField<?>[]{ l.BA_LEDGER_ID.asc(), l.BA_PRIMARY_CODE_1.asc(), l.PO_NO.asc(), l.LINE_NO.asc() };
            default            -> new org.jooq.SortField<?>[]{ l.PO_NO.asc(), l.LINE_NO.asc() };
        };

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(
                    l.PO_NO, l.LINE_NO, h.PO_DATE, h.SUPPLIER_NO,
                    DSL.coalesce(h.SUPPLIER_NAME_1, "").as("supplier_name"),
                    h.PO_STATUS, l.STOCK_CODE, l.DESC_1, l.DELIV_LOC_NO,
                    l.NEXT_DELIV_DATE, l.LINE_TYPE,
                    l.GL_ACCT_MAIN, l.GL_ACCT_SUB,
                    l.LEDGER_TYPE, l.LEDGER_CODE, l.BA_LEDGER_ID,
                    colQtyOrdered.as("qty_ordered"),
                    colUnitCost.as("unit_cost"),
                    colExtAmt.as("ext_amt"),
                    colTaxAmt.as("tax_amt"),
                    l.RECVD_QTY
               )
               .from(l)
               .join(h).on(h.COMPANY_NO.eq(l.COMPANY_NO)
                   .and(h.LOC_NO.eq(l.LOC_NO))
                   .and(h.PO_NO.eq(l.PO_NO)))
               .where(where)
               .orderBy(orderBy)
               .fetch()
               .forEach(r -> {
                   BigDecimal ext = z(r.get("ext_amt", BigDecimal.class));
                   BigDecimal tax = z(r.get("tax_amt", BigDecimal.class));
                   tot[0] = tot[0].add(ext); tot[1] = tot[1].add(tax);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("poNo",         r.get(l.PO_NO));
                   row.put("lineNo",       r.get(l.LINE_NO));
                   row.put("poDate",       localDate(r.get(h.PO_DATE)));
                   row.put("supplierNo",   trim(r.get(h.SUPPLIER_NO)));
                   row.put("supplierName", trim(r.get("supplier_name", String.class)));
                   row.put("poStatus",     poStatus(r.get(h.PO_STATUS)));
                   row.put("stockCode",    trim(r.get(l.STOCK_CODE)));
                   row.put("description",  r.get(l.DESC_1));
                   row.put("delivLoc",     trim(r.get(l.DELIV_LOC_NO)));
                   row.put("delivDate",    localDate(r.get(l.NEXT_DELIV_DATE)));
                   row.put("lineType",     trim(r.get(l.LINE_TYPE)));
                   row.put("glAcct",       r.get(l.GL_ACCT_MAIN) + "-" + r.get(l.GL_ACCT_SUB));
                   row.put("ledger",       (trim(r.get(l.LEDGER_TYPE)) + " " + trim(r.get(l.LEDGER_CODE))).trim());
                   row.put("baLedger",     trim(r.get(l.BA_LEDGER_ID)));
                   row.put("qtyOrdered",   z(r.get("qty_ordered", BigDecimal.class)));
                   row.put("unitCost",     z(r.get("unit_cost", BigDecimal.class)));
                   row.put("extAmt",       ext);
                   row.put("taxAmt",       tax);
                   row.put("recvdQty",     z(r.get(l.RECVD_QTY)));
                   rows.add(row);
               });
        } catch (Exception e) {
            log.error("getPurchaseOrdersInSequence: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No purchase order lines matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SEQUENCE_DESC", seqDesc(seq));
        params.put("LOC_DESC",      notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("PO_RANGE",      p.startPoNo() > 0 ? p.startPoNo() + " to " + (p.endPoNo() > 0 ? p.endPoNo() : "end") : "All purchase orders");
        params.put("SUPP_RANGE",    notBlank(p.startSupplier()) ? p.startSupplier() + " to " + (notBlank(p.endSupplier()) ? p.endSupplier() : "end") : "All suppliers");
        params.put("DATE_RANGE",    p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All order dates");
        params.put("AMT_BASIS",     orig ? "Original order" : "Current order");
        params.put("SUM_EXT", tot[0]); params.put("SUM_TAX", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    private static String seqDesc(PoSequence seq) {
        return switch (seq) {
            case SUPPLIER -> "Supplier"; case ITEM -> "Item / Stock Code"; case DELIVERY_DATE -> "Delivery Date";
            case ORDER_DATE -> "Order Date"; case GL_ACCOUNT -> "GL Account"; case COST_LEDGER -> "Cost Ledger";
            case BA_LEDGER -> "BA Ledger"; default -> "Order Number";
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    // POTL20 — Purchase Order Summary  (header level)
    // ════════════════════════════════════════════════════════════════════════

    public record PoSummaryParams(
            String locNo, int startPoNo, int endPoNo, String startSupplier, String endSupplier,
            LocalDate startDate, LocalDate endDate, String poStatus, boolean outstandingOnly, String printSeq) {}

    /** POTL20 — one row per PO: ordered / invoiced / delivered / outstanding values. */
    public Map<String, Object> getPurchaseOrderSummary(AppSession s, PoSummaryParams p) {
        Condition where = POPOHED.COMPANY_NO.eq(s.getCompanyNo());
        where = applyHeaderFilters(where, POPOHED, p.locNo(), p.startPoNo(), p.endPoNo(),
                                   p.startSupplier(), p.endSupplier(), p.startDate(), p.endDate(), p.poStatus());

        var orderBy = "S".equalsIgnoreCase(p.printSeq())
            ? new org.jooq.SortField<?>[]{ POPOHED.SUPPLIER_NO.asc(), POPOHED.PO_NO.asc() }
            : new org.jooq.SortField<?>[]{ POPOHED.PO_NO.asc() };

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(POPOHED.PO_NO, POPOHED.PO_DATE, POPOHED.SUPPLIER_NO,
                       DSL.coalesce(POPOHED.SUPPLIER_NAME_1, "").as("supplier_name"),
                       POPOHED.PO_STATUS,
                       POPOHED.PO_VALUE, POPOHED.PO_TAX_VALUE,
                       POPOHED.INV_VALUE, POPOHED.INV_TAX_VALUE,
                       POPOHED.RECVD_VALUE, POPOHED.RECVD_TAX_VALUE)
               .from(POPOHED)
               .where(where)
               .orderBy(orderBy)
               .fetch()
               .forEach(r -> {
                   BigDecimal ordered   = z(r.get(POPOHED.PO_VALUE)).add(z(r.get(POPOHED.PO_TAX_VALUE)));
                   BigDecimal invoiced  = z(r.get(POPOHED.INV_VALUE)).add(z(r.get(POPOHED.INV_TAX_VALUE)));
                   BigDecimal delivered = z(r.get(POPOHED.RECVD_VALUE)).add(z(r.get(POPOHED.RECVD_TAX_VALUE)));
                   BigDecimal outstanding = ordered.subtract(delivered);
                   if (p.outstandingOnly() && outstanding.signum() == 0) return;
                   tot[0]=tot[0].add(ordered); tot[1]=tot[1].add(invoiced);
                   tot[2]=tot[2].add(delivered); tot[3]=tot[3].add(outstanding);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("poNo",         r.get(POPOHED.PO_NO));
                   row.put("poDate",       localDate(r.get(POPOHED.PO_DATE)));
                   row.put("supplierNo",   trim(r.get(POPOHED.SUPPLIER_NO)));
                   row.put("supplierName", trim(r.get("supplier_name", String.class)));
                   row.put("poStatus",     poStatus(r.get(POPOHED.PO_STATUS)));
                   row.put("ordered", ordered); row.put("invoiced", invoiced);
                   row.put("delivered", delivered); row.put("outstanding", outstanding);
                   rows.add(row);
               });
        } catch (Exception e) {
            log.error("getPurchaseOrderSummary: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        Map<String, Object> params = headerParams(p.locNo(), p.startPoNo(), p.endPoNo(),
                                                  p.startSupplier(), p.endSupplier(),
                                                  p.startDate(), p.endDate(), p.poStatus());
        params.put("SEQ_DESC",       "S".equalsIgnoreCase(p.printSeq()) ? "Supplier" : "Order number");
        params.put("SUM_ORDERED",    tot[0]); params.put("SUM_INVOICED",     tot[1]);
        params.put("SUM_DELIVERED",  tot[2]); params.put("SUM_OUTSTANDING", tot[3]);
        params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // POTL21 — Purchase Order Detail  (line level)
    // ════════════════════════════════════════════════════════════════════════

    public record PoDetailParams(
            String locNo, int startPoNo, int endPoNo, String startSupplier, String endSupplier,
            LocalDate startDate, LocalDate endDate, String poStatus, boolean outstandingOnly, String printSeq) {}

    /** POTL21 — one row per PO line: ordered / received / invoiced quantities + values. */
    public Map<String, Object> getPurchaseOrderDetail(AppSession s, PoDetailParams p) {
        var l = POPOLIN.as("l");
        var h = POPOHED.as("h");

        Condition where = l.COMPANY_NO.eq(s.getCompanyNo()).and(l.LINE_TYPE.ne("C"));
        where = applyLineFilters(where, l, h, p.locNo(), p.startPoNo(), p.endPoNo(),
                                 p.startSupplier(), p.endSupplier(), p.startDate(), p.endDate(), p.poStatus());
        if (p.outstandingOnly()) where = where.and(l.CURR_QTY_ORDERED.subtract(l.RECVD_QTY).gt(BigDecimal.ZERO));

        var orderBy = "S".equalsIgnoreCase(p.printSeq())
            ? new org.jooq.SortField<?>[]{ h.SUPPLIER_NO.asc(), l.PO_NO.asc(), l.LINE_NO.asc() }
            : new org.jooq.SortField<?>[]{ l.PO_NO.asc(), l.LINE_NO.asc() };

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(l.PO_NO, l.LINE_NO, h.PO_DATE, h.SUPPLIER_NO,
                       DSL.coalesce(h.SUPPLIER_NAME_1, "").as("supplier_name"),
                       h.PO_STATUS, l.STOCK_CODE, l.DESC_1, l.LINE_TYPE,
                       l.NEXT_DELIV_DATE, l.CURR_QTY_ORDERED, l.RECVD_QTY, l.INV_QTY,
                       l.CURR_UNIT_COST, l.CURR_EXT_AMT, l.CURR_TAX_AMT)
               .from(l)
               .join(h).on(h.COMPANY_NO.eq(l.COMPANY_NO)
                   .and(h.LOC_NO.eq(l.LOC_NO))
                   .and(h.PO_NO.eq(l.PO_NO)))
               .where(where)
               .orderBy(orderBy)
               .fetch()
               .forEach(r -> {
                   BigDecimal ext = z(r.get(l.CURR_EXT_AMT)), tax = z(r.get(l.CURR_TAX_AMT));
                   tot[0]=tot[0].add(ext); tot[1]=tot[1].add(tax);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("poNo",         r.get(l.PO_NO));
                   row.put("lineNo",       r.get(l.LINE_NO));
                   row.put("poDate",       localDate(r.get(h.PO_DATE)));
                   row.put("supplierNo",   trim(r.get(h.SUPPLIER_NO)));
                   row.put("supplierName", trim(r.get("supplier_name", String.class)));
                   row.put("poStatus",     poStatus(r.get(h.PO_STATUS)));
                   row.put("stockCode",    trim(r.get(l.STOCK_CODE)));
                   row.put("description",  r.get(l.DESC_1));
                   row.put("lineType",     trim(r.get(l.LINE_TYPE)));
                   row.put("delivDate",    localDate(r.get(l.NEXT_DELIV_DATE)));
                   row.put("qtyOrdered",   z(r.get(l.CURR_QTY_ORDERED)));
                   row.put("recvdQty",     z(r.get(l.RECVD_QTY)));
                   row.put("invQty",       z(r.get(l.INV_QTY)));
                   row.put("unitCost",     z(r.get(l.CURR_UNIT_COST)));
                   row.put("extAmt",       ext); row.put("taxAmt", tax);
                   rows.add(row);
               });
        } catch (Exception e) {
            log.error("getPurchaseOrderDetail: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        Map<String, Object> params = headerParams(p.locNo(), p.startPoNo(), p.endPoNo(),
                                                  p.startSupplier(), p.endSupplier(),
                                                  p.startDate(), p.endDate(), p.poStatus());
        params.put("SEQ_DESC", "S".equalsIgnoreCase(p.printSeq()) ? "Supplier" : "Order number");
        params.put("SUM_EXT", tot[0]); params.put("SUM_TAX", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // POTL33 — Purchase Index  (PO lines matched to AP documents)
    // ════════════════════════════════════════════════════════════════════════

    public record PurchaseIndexParams(
            String locNo, int startPoNo, int endPoNo, String startSupplier, String endSupplier,
            LocalDate startDate, LocalDate endDate, boolean exceptionsOnly) {}

    /** POTL33 — per PO line: ordered / delivered / invoiced value + matched AP documents (apdocno). */
    public Map<String, Object> getPurchaseIndex(AppSession s, PurchaseIndexParams p) {
        var l = POPOLIN.as("l");
        var h = POPOHED.as("h");
        var a = APDOCNO.as("a");

        Condition where = l.COMPANY_NO.eq(s.getCompanyNo()).and(l.LINE_TYPE.ne("C"));
        where = applyLineFilters(where, l, h, p.locNo(), p.startPoNo(), p.endPoNo(),
                                 p.startSupplier(), p.endSupplier(), p.startDate(), p.endDate(), null);

        // Correlated subqueries for doc_count and matched_value
        Field<Integer> docCount = DSL.select(DSL.count())
            .from(a)
            .where(a.COMPANY_NO.eq(l.COMPANY_NO)
                .and(a.PO_LOC_NO.eq(l.LOC_NO))
                .and(a.PO_NO.eq(l.PO_NO))
                .and(a.PO_LINE_NO.eq(l.LINE_NO)))
            .asField("doc_count");

        Field<BigDecimal> matchedValue = DSL.select(DSL.coalesce(DSL.sum(a.PO_VALUE_MATCHED), BigDecimal.ZERO))
            .from(a)
            .where(a.COMPANY_NO.eq(l.COMPANY_NO)
                .and(a.PO_LOC_NO.eq(l.LOC_NO))
                .and(a.PO_NO.eq(l.PO_NO))
                .and(a.PO_LINE_NO.eq(l.LINE_NO)))
            .asField("matched_value");

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            dsl.select(l.PO_NO, l.LINE_NO, h.SUPPLIER_NO,
                       DSL.coalesce(h.SUPPLIER_NAME_1, "").as("supplier_name"),
                       l.STOCK_CODE, l.DESC_1,
                       l.CURR_EXT_AMT, l.RECVD_EXT_AMT, l.INV_EXT_AMT,
                       docCount, matchedValue)
               .from(l)
               .join(h).on(h.COMPANY_NO.eq(l.COMPANY_NO)
                   .and(h.LOC_NO.eq(l.LOC_NO))
                   .and(h.PO_NO.eq(l.PO_NO)))
               .where(where)
               .orderBy(h.SUPPLIER_NO, l.PO_NO, l.LINE_NO)
               .fetch()
               .forEach(r -> {
                   BigDecimal ordered     = z(r.get(l.CURR_EXT_AMT));
                   BigDecimal delivered   = z(r.get(l.RECVD_EXT_AMT));
                   BigDecimal invoiced    = z(r.get(l.INV_EXT_AMT));
                   BigDecimal outstanding = ordered.subtract(invoiced);
                   if (p.exceptionsOnly() && delivered.compareTo(invoiced) == 0) return;
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("poNo",         r.get(l.PO_NO));
                   row.put("lineNo",       r.get(l.LINE_NO));
                   row.put("supplierNo",   trim(r.get(h.SUPPLIER_NO)));
                   row.put("supplierName", trim(r.get("supplier_name", String.class)));
                   row.put("stockCode",    trim(r.get(l.STOCK_CODE)));
                   row.put("description",  r.get(l.DESC_1));
                   row.put("ordered", ordered); row.put("delivered", delivered);
                   row.put("invoiced", invoiced); row.put("outstanding", outstanding);
                   row.put("docCount",     r.get("doc_count", Integer.class));
                   row.put("matchedValue", z(r.get("matched_value", BigDecimal.class)));
                   rows.add(row);
               });
        } catch (Exception e) {
            log.error("getPurchaseIndex: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No purchase order lines matched the selection.");
        Map<String, Object> params = headerParams(p.locNo(), p.startPoNo(), p.endPoNo(),
                                                  p.startSupplier(), p.endSupplier(),
                                                  p.startDate(), p.endDate(), null);
        params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // POTL28 — Order Delivery / Invoice Variance
    // ════════════════════════════════════════════════════════════════════════

    public record VarianceParams(
            String locNo, String startSupplier, String endSupplier, int startPoNo, int endPoNo,
            String printSeq, boolean excludeCompleted) {}

    /** POTL28 — per PO line: delivered value vs invoiced value, and the variance. */
    public Map<String, Object> getDeliveryInvoiceVariance(AppSession s, VarianceParams p) {
        var l = POPOLIN.as("l");
        var h = POPOHED.as("h");

        Condition where = l.COMPANY_NO.eq(s.getCompanyNo()).and(l.LINE_TYPE.ne("C"));
        where = applyLineFilters(where, l, h, p.locNo(), p.startPoNo(), p.endPoNo(),
                                 p.startSupplier(), p.endSupplier(), null, null, null);

        var orderBy = "P".equalsIgnoreCase(p.printSeq())
            ? new org.jooq.SortField<?>[]{ l.PO_NO.asc(), l.LINE_NO.asc() }
            : new org.jooq.SortField<?>[]{ h.SUPPLIER_NO.asc(), l.PO_NO.asc(), l.LINE_NO.asc() };

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(l.PO_NO, l.LINE_NO, h.SUPPLIER_NO,
                       DSL.coalesce(h.SUPPLIER_NAME_1, "").as("supplier_name"),
                       l.STOCK_CODE, l.RECVD_QTY, l.INV_QTY,
                       l.RECVD_EXT_AMT, l.RECVD_TAX_AMT, l.INV_EXT_AMT, l.INV_TAX_AMT)
               .from(l)
               .join(h).on(h.COMPANY_NO.eq(l.COMPANY_NO)
                   .and(h.LOC_NO.eq(l.LOC_NO))
                   .and(h.PO_NO.eq(l.PO_NO)))
               .where(where)
               .orderBy(orderBy)
               .fetch()
               .forEach(r -> {
                   BigDecimal delivered = z(r.get(l.RECVD_EXT_AMT)).add(z(r.get(l.RECVD_TAX_AMT)));
                   BigDecimal invoiced  = z(r.get(l.INV_EXT_AMT)).add(z(r.get(l.INV_TAX_AMT)));
                   BigDecimal variance  = delivered.subtract(invoiced);
                   if (p.excludeCompleted()
                           && z(r.get(l.RECVD_QTY)).compareTo(z(r.get(l.INV_QTY))) == 0
                           && variance.signum() == 0) return;
                   tot[0]=tot[0].add(delivered); tot[1]=tot[1].add(invoiced); tot[2]=tot[2].add(variance);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("poNo",         r.get(l.PO_NO));
                   row.put("lineNo",       r.get(l.LINE_NO));
                   row.put("supplierNo",   trim(r.get(h.SUPPLIER_NO)));
                   row.put("supplierName", trim(r.get("supplier_name", String.class)));
                   row.put("stockCode",    trim(r.get(l.STOCK_CODE)));
                   row.put("delivered", delivered); row.put("invoiced", invoiced); row.put("variance", variance);
                   rows.add(row);
               });
        } catch (Exception e) {
            log.error("getDeliveryInvoiceVariance: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No purchase order lines matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC",      notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("SUPP_RANGE",    notBlank(p.startSupplier()) ? p.startSupplier() + " to " + (notBlank(p.endSupplier()) ? p.endSupplier() : "end") : "All suppliers");
        params.put("SEQ_DESC",      "P".equalsIgnoreCase(p.printSeq()) ? "Order number" : "Supplier");
        params.put("SUM_DELIVERED", tot[0]); params.put("SUM_INVOICED", tot[1]); params.put("SUM_VARIANCE", tot[2]);
        params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // POTL36 / POTL37 — Uninvoiced Goods Reconcile (goods / sundries)
    // ════════════════════════════════════════════════════════════════════════

    public record UninvoicedParams(
            String locNo, LocalDate asAtDate, int startPoNo, int endPoNo,
            String startSupplier, String endSupplier, String printSeq, boolean sundriesOnly) {}

    /** POTL36/POTL37 — received-not-invoiced value per PO line (goods, or sundry lines only). */
    public Map<String, Object> getUninvoicedGoods(AppSession s, UninvoicedParams p) {
        var l = POPOLIN.as("l");
        var h = POPOHED.as("h");

        Condition where = l.COMPANY_NO.eq(s.getCompanyNo()).and(l.LINE_TYPE.ne("C"));
        if (p.sundriesOnly()) where = where.and(l.LINE_TYPE.ne("I")); // sundry / non-inventory
        else                  where = where.and(l.LINE_TYPE.eq("I")); // stock goods

        // applyLineFilters for uninvoiced uses asAtDate as endDate (upper-bound on po_date)
        where = applyLineFilters(where, l, h, p.locNo(), p.startPoNo(), p.endPoNo(),
                                 p.startSupplier(), p.endSupplier(), null, p.asAtDate(), null);

        var orderBy = "P".equalsIgnoreCase(p.printSeq())
            ? new org.jooq.SortField<?>[]{ l.PO_NO.asc(), l.LINE_NO.asc() }
            : new org.jooq.SortField<?>[]{ h.SUPPLIER_NO.asc(), l.PO_NO.asc(), l.LINE_NO.asc() };

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(l.PO_NO, l.LINE_NO, h.SUPPLIER_NO,
                       DSL.coalesce(h.SUPPLIER_NAME_1, "").as("supplier_name"),
                       l.STOCK_CODE, l.DESC_1, l.LINE_TYPE,
                       l.RECVD_EXT_AMT, l.INV_EXT_AMT)
               .from(l)
               .join(h).on(h.COMPANY_NO.eq(l.COMPANY_NO)
                   .and(h.LOC_NO.eq(l.LOC_NO))
                   .and(h.PO_NO.eq(l.PO_NO)))
               .where(where)
               .orderBy(orderBy)
               .fetch()
               .forEach(r -> {
                   BigDecimal delivered   = z(r.get(l.RECVD_EXT_AMT));
                   BigDecimal invoiced    = z(r.get(l.INV_EXT_AMT));
                   BigDecimal outstanding = delivered.subtract(invoiced);
                   if (outstanding.signum() == 0) return; // only uninvoiced/over-invoiced lines
                   tot[0]=tot[0].add(delivered); tot[1]=tot[1].add(invoiced); tot[2]=tot[2].add(outstanding);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("poNo",         r.get(l.PO_NO));
                   row.put("lineNo",       r.get(l.LINE_NO));
                   row.put("supplierNo",   trim(r.get(h.SUPPLIER_NO)));
                   row.put("supplierName", trim(r.get("supplier_name", String.class)));
                   row.put("stockCode",    trim(r.get(l.STOCK_CODE)));
                   row.put("description",  r.get(l.DESC_1));
                   row.put("lineType",     trim(r.get(l.LINE_TYPE)));
                   row.put("delivered", delivered); row.put("invoiced", invoiced); row.put("outstanding", outstanding);
                   rows.add(row);
               });
        } catch (Exception e) {
            log.error("getUninvoicedGoods: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No uninvoiced " + (p.sundriesOnly() ? "sundry" : "goods") + " lines for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC",      notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("AS_AT",         dmy(p.asAtDate()));
        params.put("SUPP_RANGE",    notBlank(p.startSupplier()) ? p.startSupplier() + " to " + (notBlank(p.endSupplier()) ? p.endSupplier() : "end") : "All suppliers");
        params.put("KIND_DESC",     p.sundriesOnly() ? "Sundries" : "Goods");
        params.put("SUM_DELIVERED", tot[0]); params.put("SUM_INVOICED", tot[1]); params.put("SUM_OUTSTANDING", tot[2]);
        params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // POTL39 — Sundries Reconcile  (AP documents, apdocno)
    // ════════════════════════════════════════════════════════════════════════

    public record SundriesReconParams(LocalDate asAtDate, String startSupplier, String endSupplier) {}

    /** POTL39 — AP purchase documents (apdocno): matched value + adjustments by supplier/document. */
    public Map<String, Object> getSundriesReconcile(AppSession s, SundriesReconParams p) {
        Condition where = APDOCNO.COMPANY_NO.eq(s.getCompanyNo());
        if (notBlank(p.startSupplier())) {
            String end = notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz";
            where = where.and(APDOCNO.SUPPLIER_NO.between(p.startSupplier(), end));
        }
        if (p.asAtDate() != null) where = where.and(APDOCNO.DOC_DATE.le(p.asAtDate()));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(APDOCNO.SUPPLIER_NO, APDOCNO.DOC_NO, APDOCNO.DOC_TYPE,
                       APDOCNO.DOC_DATE, APDOCNO.POSTING_DATE, APDOCNO.PO_NO,
                       APDOCNO.PO_VALUE_MATCHED, APDOCNO.ADJUST_VALUE, APDOCNO.DOC_STATUS)
               .from(APDOCNO)
               .where(where)
               .orderBy(APDOCNO.SUPPLIER_NO, APDOCNO.DOC_DATE, APDOCNO.DOC_NO)
               .fetch()
               .forEach(r -> {
                   BigDecimal matched = z(r.get(APDOCNO.PO_VALUE_MATCHED));
                   BigDecimal adjust  = z(r.get(APDOCNO.ADJUST_VALUE));
                   tot[0]=tot[0].add(matched); tot[1]=tot[1].add(adjust);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("supplierNo",   trim(r.get(APDOCNO.SUPPLIER_NO)));
                   row.put("docNo",        trim(r.get(APDOCNO.DOC_NO)));
                   row.put("docType",      trim(r.get(APDOCNO.DOC_TYPE)));
                   row.put("docDate",      localDate(r.get(APDOCNO.DOC_DATE)));
                   row.put("postingDate",  localDate(r.get(APDOCNO.POSTING_DATE)));
                   row.put("poNo",         r.get(APDOCNO.PO_NO));
                   row.put("matchedValue", matched);
                   row.put("adjustValue",  adjust);
                   row.put("status", "U".equalsIgnoreCase(trim(r.get(APDOCNO.DOC_STATUS))) ? "Unposted" : "");
                   rows.add(row);
               });
        } catch (Exception e) {
            log.error("getSundriesReconcile: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No purchase documents for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("AS_AT",       dmy(p.asAtDate()));
        params.put("SUPP_RANGE",  notBlank(p.startSupplier()) ? p.startSupplier() + " to " + (notBlank(p.endSupplier()) ? p.endSupplier() : "end") : "All suppliers");
        params.put("SUM_MATCHED", tot[0]); params.put("SUM_ADJUST", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // POTL30 — Expedite Action  (overdue undelivered PO lines)
    // ════════════════════════════════════════════════════════════════════════

    public record ExpediteParams(String locNo, LocalDate startDate, LocalDate endDate, String startSupplier, String endSupplier) {}

    /** POTL30 — PO lines due in the window that are not yet fully received (expedite candidates). */
    public Map<String, Object> getExpediteAction(AppSession s, ExpediteParams p) {
        var l = POPOLIN.as("l");
        var h = POPOHED.as("h");

        Condition where = l.COMPANY_NO.eq(s.getCompanyNo())
            .and(l.LINE_TYPE.ne("C"))
            .and(l.RECVD_QTY.lt(l.CURR_QTY_ORDERED));
        if (notBlank(p.locNo())) where = where.and(l.LOC_NO.eq(p.locNo()));
        if (notBlank(p.startSupplier())) {
            String end = notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz";
            where = where.and(h.SUPPLIER_NO.between(p.startSupplier(), end));
        }
        if (p.startDate() != null) {
            LocalDate end = p.endDate() != null ? p.endDate() : LocalDate.of(9999, 12, 31);
            where = where.and(l.NEXT_DELIV_DATE.between(p.startDate(), end));
        } else if (p.endDate() != null) {
            where = where.and(l.NEXT_DELIV_DATE.le(p.endDate()));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            dsl.select(l.PO_NO, l.LINE_NO, h.SUPPLIER_NO,
                       DSL.coalesce(h.SUPPLIER_NAME_1, "").as("supplier_name"),
                       l.STOCK_CODE, l.DESC_1, l.NEXT_DELIV_DATE,
                       l.CURR_QTY_ORDERED, l.RECVD_QTY)
               .from(l)
               .join(h).on(h.COMPANY_NO.eq(l.COMPANY_NO)
                   .and(h.LOC_NO.eq(l.LOC_NO))
                   .and(h.PO_NO.eq(l.PO_NO)))
               .where(where)
               .orderBy(l.NEXT_DELIV_DATE, h.SUPPLIER_NO, l.PO_NO, l.LINE_NO)
               .fetch()
               .forEach(r -> {
                   BigDecimal ordered = z(r.get(l.CURR_QTY_ORDERED));
                   BigDecimal recvd   = z(r.get(l.RECVD_QTY));
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("poNo",           r.get(l.PO_NO));
                   row.put("lineNo",         r.get(l.LINE_NO));
                   row.put("supplierNo",     trim(r.get(h.SUPPLIER_NO)));
                   row.put("supplierName",   trim(r.get("supplier_name", String.class)));
                   row.put("stockCode",      trim(r.get(l.STOCK_CODE)));
                   row.put("description",    r.get(l.DESC_1));
                   row.put("delivDate",      localDate(r.get(l.NEXT_DELIV_DATE)));
                   row.put("qtyOrdered",     ordered);
                   row.put("recvdQty",       recvd);
                   row.put("qtyOutstanding", ordered.subtract(recvd));
                   rows.add(row);
               });
        } catch (Exception e) {
            log.error("getExpediteAction: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No overdue / undelivered purchase order lines for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC",   notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("SUPP_RANGE", notBlank(p.startSupplier()) ? p.startSupplier() + " to " + (notBlank(p.endSupplier()) ? p.endSupplier() : "end") : "All suppliers");
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…")
                                 : (p.endDate() != null ? "up to " + p.endDate() : "All dates"));
        params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ── shared condition builders ─────────────────────────────────────────────

    /**
     * Apply standard header-level filters to a {@code popohed} condition chain.
     * All column references are unaliased (direct POPOHED table).
     */
    private Condition applyHeaderFilters(Condition where,
                                         com.landmarksoftware.db.tables.Popohed tbl,
                                         String locNo, int startPo, int endPo,
                                         String startSup, String endSup,
                                         LocalDate startDate, LocalDate endDate, String poStatus) {
        if (notBlank(locNo))    where = where.and(tbl.LOC_NO.eq(locNo));
        if (startPo > 0) {
            int e = endPo > 0 ? endPo : 999999;
            where = where.and(tbl.PO_NO.between(startPo, e));
        }
        if (notBlank(startSup)) {
            String e = notBlank(endSup) ? endSup : "zzzzzzzzzz";
            where = where.and(tbl.SUPPLIER_NO.between(startSup, e));
        }
        if (startDate != null) {
            LocalDate e = endDate != null ? endDate : LocalDate.of(9999, 12, 31);
            where = where.and(tbl.PO_DATE.between(startDate, e));
        }
        return applyStatusCondition(where, tbl.PO_STATUS, poStatus);
    }

    /**
     * Apply standard line-level filters to a condition joining aliased {@code l} (popolin)
     * and {@code h} (popohed).
     */
    private Condition applyLineFilters(Condition where,
                                       com.landmarksoftware.db.tables.Popolin l,
                                       com.landmarksoftware.db.tables.Popohed h,
                                       String locNo, int startPo, int endPo,
                                       String startSup, String endSup,
                                       LocalDate startDate, LocalDate endDate, String poStatus) {
        if (notBlank(locNo))    where = where.and(l.LOC_NO.eq(locNo));
        if (startPo > 0) {
            int e = endPo > 0 ? endPo : 999999;
            where = where.and(l.PO_NO.between(startPo, e));
        }
        if (notBlank(startSup)) {
            String e = notBlank(endSup) ? endSup : "zzzzzzzzzz";
            where = where.and(h.SUPPLIER_NO.between(startSup, e));
        }
        if (startDate != null) {
            LocalDate e = endDate != null ? endDate : LocalDate.of(9999, 12, 31);
            where = where.and(h.PO_DATE.between(startDate, e));
        } else if (endDate != null) {
            where = where.and(h.PO_DATE.le(endDate));
        }
        return applyStatusCondition(where, h.PO_STATUS, poStatus);
    }

    /**
     * PO status filter: blank = active (exclude U/C); U/C/F = that status; A/null = all.
     */
    private Condition applyStatusCondition(Condition where, Field<String> statusField, String poStatus) {
        if (poStatus == null || "A".equalsIgnoreCase(poStatus.trim())) return where;
        String st = poStatus.trim();
        if (st.isEmpty()) return where.and(statusField.notIn("U", "C"));
        return where.and(statusField.eq(st));
    }

    private Map<String, Object> headerParams(String locNo, int startPo, int endPo, String startSup, String endSup,
                                             LocalDate startDate, LocalDate endDate, String poStatus) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC",   notBlank(locNo) ? locNo : "All locations");
        params.put("PO_RANGE",   startPo > 0 ? startPo + " to " + (endPo > 0 ? endPo : "end") : "All purchase orders");
        params.put("SUPP_RANGE", notBlank(startSup) ? startSup + " to " + (notBlank(endSup) ? endSup : "end") : "All suppliers");
        params.put("DATE_RANGE", startDate != null ? startDate + " to " + (endDate != null ? endDate : "…") : "All order dates");
        params.put("STATUS_DESC", poStatus == null || poStatus.isBlank() ? "Active"
                : "A".equalsIgnoreCase(poStatus) ? "All"
                : "U".equalsIgnoreCase(poStatus) ? "Unconfirmed"
                : "C".equalsIgnoreCase(poStatus) ? "Cancelled" : poStatus);
        return params;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static String poStatus(String s) {
        return switch (trim(s)) {
            case "U" -> "Unconfirmed"; case "C" -> "Cancelled"; case "F" -> "Completed"; default -> "";
        };
    }

    /**
     * Convert a jOOQ-returned {@link LocalDate} to itself (pass-through), guarding
     * against the COBOL sentinel date 1900-01-01 or earlier — return null for those.
     * Mirrors the old {@code sqlDate(java.sql.Date)} helper.
     */
    static LocalDate localDate(LocalDate d) {
        if (d == null) return null;
        return d.isAfter(LocalDate.of(1900, 1, 1)) ? d : null;
    }

    private Map<String, Object> result(List<Map<String, Object>> rows, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows); m.put("params", params); m.put("rowCount", rows.size());
        return m;
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
