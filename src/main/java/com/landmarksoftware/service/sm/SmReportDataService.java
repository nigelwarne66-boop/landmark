package com.landmarksoftware.service.sm;

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

import static com.landmarksoftware.db.tables.Arcusts.ARCUSTS;
import static com.landmarksoftware.db.tables.Apsupps.APSUPPS;
import static com.landmarksoftware.db.tables.Smcodpt.SMCODPT;
import static com.landmarksoftware.db.tables.Smlocat.SMLOCAT;
import static com.landmarksoftware.db.tables.Smsteff.SMSTEFF;
import static com.landmarksoftware.db.tables.Smsthed.SMSTHED;
import static com.landmarksoftware.db.tables.Smstloc.SMSTLOC;
import static com.landmarksoftware.db.tables.Smtrans.SMTRANS;

/**
 * Sales / Inventory Management (SM) <b>report</b> data service — one query method per
 * SM report card in the JavaFX Reports Hub ({@code -Preporting} build).
 *
 * <p>All DB access lives here via jOOQ DSLContext; controllers stay pure JavaFX.
 * Ported from {@code C:\landmark\cobol\sm2\smtl*}, columns verified against the
 * live {@code lmextract} schema.
 *
 * <p><b>Data reality in the current extract</b> (verified 2026-05-28): only
 * {@code smtrans} is populated (company 999, 433k rows). The item master
 * ({@code smsthed}), per-location stock master ({@code smstloc}), price-effective
 * ({@code smsteff}), {@code smsales}/{@code smpurch} histories, {@code smlocat} and
 * {@code smcodpt} are all empty; serial ({@code smserno}/{@code smsetrx}) and the OP
 * order tables ({@code opordhd}/{@code opordln}) are not extracted at all. So the five
 * transaction-driven reports (Movements Detail/Summary, Sales History, Transactions by
 * Customer, Purchase Analysis) return data today; the master-dependent reports run
 * cleanly but render empty until those tables load.
 *
 * <p><b>smtrans transaction taxonomy</b> — {@code system_id} is the source module
 * ({@code AR} sales/credits, {@code AP} purchase receipts, {@code SM} adjustments +
 * transfers); {@code move_ind} = {@code S}ale / {@code R}eceipt / {@code A}djustment /
 * {@code C}redit / {@code T}ransfer; {@code in_out_direct_ind} = {@code I}n / {@code O}ut
 * / {@code D}irect / {@code B}oth.
 */
@Service
public class SmReportDataService {

    private static final Logger log = LoggerFactory.getLogger(SmReportDataService.class);
    private final DSLContext dsl;

    public SmReportDataService(DSLContext dsl) { this.dsl = dsl; }

    /** A selectable code with a display label; {@code toString()} drives ComboBox rendering. */
    public record CodeName(String code, String label) {
        @Override public String toString() { return label; }
    }

    /** Sort sequence for the two consolidated SM reports. */
    public enum SmSequence { ITEM, LOCATION, CUSTOMER }

    // ── Lookups (the COBOL F5 lists) ──────────────────────────────────────────

    /** Locations — sourced from smtrans (where the data is), name from smlocat if loaded. */
    public List<CodeName> getLocations(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All locations)"));
        try {
            // Aggregate MAX(name1) because loc_no may appear many times in smtrans;
            // smlocat may be empty so we LEFT JOIN and accept null name.
            Field<String> name1 = DSL.max(SMLOCAT.NAME1).as("name1");
            dsl.select(SMTRANS.LOC_NO, name1)
               .from(SMTRANS)
               .leftJoin(SMLOCAT)
                   .on(SMLOCAT.COMPANY_NO.eq(SMTRANS.COMPANY_NO)
                       .and(SMLOCAT.LOC_NO.eq(SMTRANS.LOC_NO)))
               .where(SMTRANS.COMPANY_NO.eq(s.getCompanyNo())
                   .and(SMTRANS.LOC_NO.isNotNull())
                   .and(SMTRANS.LOC_NO.ne("")))
               .groupBy(SMTRANS.LOC_NO)
               .orderBy(SMTRANS.LOC_NO)
               .fetch()
               .forEach(r -> {
                   String l = trim(r.get(SMTRANS.LOC_NO));
                   String n = trim(r.get(name1));
                   list.add(new CodeName(l, n.isEmpty() ? l : l + " — " + n));
               });
        } catch (Exception e) { log.warn("getLocations: {}", e.getMessage()); }
        return list;
    }

    /** Product types from smcodpt. (Empty in the current extract.) */
    public List<CodeName> getProductTypes(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All product types)"));
        try {
            dsl.select(SMCODPT.PRODUCT_TYPE_CODE, SMCODPT.DESC1)
               .from(SMCODPT)
               .where(SMCODPT.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(SMCODPT.PRODUCT_TYPE_CODE)
               .fetch()
               .forEach(r -> {
                   String c = trim(r.get(SMCODPT.PRODUCT_TYPE_CODE));
                   list.add(new CodeName(c, c + " — " + trim(r.get(SMCODPT.DESC1))));
               });
        } catch (Exception e) { log.warn("getProductTypes: {}", e.getMessage()); }
        return list;
    }

    /** Items — prefer the item master (smsthed); fall back to distinct codes in smtrans. */
    public List<CodeName> getItems(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All items)"));
        try {
            List<CodeName> master = new ArrayList<>();
            dsl.select(SMSTHED.STOCK_CODE, SMSTHED.DESC_1)
               .from(SMSTHED)
               .where(SMSTHED.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(SMSTHED.STOCK_CODE)
               .fetch()
               .forEach(r -> {
                   String c = trim(r.get(SMSTHED.STOCK_CODE));
                   master.add(new CodeName(c, c + " — " + trim(r.get(SMSTHED.DESC_1))));
               });
            if (master.isEmpty()) {
                Field<String> desc1 = DSL.max(SMTRANS.DESC_1).as("desc_1");
                dsl.select(SMTRANS.STOCK_CODE, desc1)
                   .from(SMTRANS)
                   .where(SMTRANS.COMPANY_NO.eq(s.getCompanyNo())
                       .and(SMTRANS.STOCK_CODE.isNotNull())
                       .and(SMTRANS.STOCK_CODE.ne("")))
                   .groupBy(SMTRANS.STOCK_CODE)
                   .orderBy(SMTRANS.STOCK_CODE)
                   .fetch()
                   .forEach(r -> {
                       String c = trim(r.get(SMTRANS.STOCK_CODE));
                       String d = trim(r.get(desc1));
                       list.add(new CodeName(c, d.isEmpty() ? c : c + " — " + d));
                   });
            } else {
                list.addAll(master);
            }
        } catch (Exception e) { log.warn("getItems: {}", e.getMessage()); }
        return list;
    }

    /** Customers from arcusts ("(All)" first, "code — name"). */
    public List<CodeName> getCustomers(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All customers)"));
        try {
            dsl.select(ARCUSTS.CUST_NO, ARCUSTS.NAME_1)
               .from(ARCUSTS)
               .where(ARCUSTS.COMPANY_NO.eq(s.getCompanyNo()))
               .orderBy(ARCUSTS.CUST_NO)
               .fetch()
               .forEach(r -> {
                   String c = trim(r.get(ARCUSTS.CUST_NO));
                   list.add(new CodeName(c, c + " — " + trim(r.get(ARCUSTS.NAME_1))));
               });
        } catch (Exception e) { log.warn("getCustomers: {}", e.getMessage()); }
        return list;
    }

    /** Suppliers from apsupps ("(All)" first, "code — name"). */
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
                   String c = trim(r.get(APSUPPS.SUPPLIER_NO));
                   list.add(new CodeName(c, c + " — " + trim(r.get(APSUPPS.NAME_1))));
               });
        } catch (Exception e) { log.warn("getSuppliers: {}", e.getMessage()); }
        return list;
    }

    /** Transaction-kind filter list for the movement reports. */
    public List<CodeName> getTransactionKinds() {
        return List.of(
            new CodeName("", "(All movements)"),
            new CodeName("SALES", "Sales"),
            new CodeName("CREDITS", "Credit notes"),
            new CodeName("AR", "Sales + credits"),
            new CodeName("PURCH", "Purchase receipts"),
            new CodeName("ADJ", "Inventory adjustments"),
            new CodeName("TFR", "Transfers"));
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL01 — Inventory Movements Detail   (smtrans, one row per movement)
    // ════════════════════════════════════════════════════════════════════════

    public record MovementParams(
            String locNo, String startItem, String endItem, String startProdType, String endProdType,
            LocalDate startDate, LocalDate endDate, String kind) {}

    public Map<String, Object> getInventoryMovementsDetail(AppSession s, MovementParams p) {
        Condition where = SMTRANS.COMPANY_NO.eq(s.getCompanyNo());
        where = applyTransFilters(where, p.locNo(), p.startItem(), p.endItem(),
                                  p.startDate(), p.endDate(), p.kind());

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(SMTRANS.LOC_NO, SMTRANS.STOCK_CODE, SMTRANS.DESC_1,
                       SMTRANS.MOVE_DATE, SMTRANS.MOVE_IND, SMTRANS.IN_OUT_DIRECT_IND,
                       SMTRANS.SYSTEM_ID, SMTRANS.DOC_TYPE, SMTRANS.DOC_NO, SMTRANS.REF,
                       SMTRANS.CUST_SUPPLIER_NO, SMTRANS.QTY, SMTRANS.TRX_UNIT_COST,
                       SMTRANS.COST_VALUE, SMTRANS.UNIT_PRICE, SMTRANS.SALES_OR_RECPT_VALUE)
               .from(SMTRANS)
               .where(where)
               .orderBy(SMTRANS.LOC_NO, SMTRANS.STOCK_CODE, SMTRANS.MOVE_DATE, SMTRANS.MOVE_TIME)
               .fetch()
               .forEach(r -> {
                   BigDecimal cost = z(r.get(SMTRANS.COST_VALUE));
                   BigDecimal sale = z(r.get(SMTRANS.SALES_OR_RECPT_VALUE));
                   tot[0] = tot[0].add(cost); tot[1] = tot[1].add(sale);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("locNo",       trim(r.get(SMTRANS.LOC_NO)));
                   row.put("stockCode",   trim(r.get(SMTRANS.STOCK_CODE)));
                   row.put("description", r.get(SMTRANS.DESC_1));
                   row.put("moveDate",    ldToSqlDate(r.get(SMTRANS.MOVE_DATE)));
                   row.put("kind",        kindLabel(r.get(SMTRANS.SYSTEM_ID), r.get(SMTRANS.MOVE_IND)));
                   row.put("inOut",       trim(r.get(SMTRANS.IN_OUT_DIRECT_IND)));
                   row.put("docType",     trim(r.get(SMTRANS.DOC_TYPE)));
                   row.put("docNo",       trim(r.get(SMTRANS.DOC_NO)));
                   row.put("ref",         trim(r.get(SMTRANS.REF)));
                   row.put("partyNo",     trim(r.get(SMTRANS.CUST_SUPPLIER_NO)));
                   row.put("qty",         z(r.get(SMTRANS.QTY)));
                   row.put("unitCost",    z(r.get(SMTRANS.TRX_UNIT_COST)));
                   row.put("costValue",   cost);
                   row.put("unitPrice",   z(r.get(SMTRANS.UNIT_PRICE)));
                   row.put("saleValue",   sale);
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getInventoryMovementsDetail: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No inventory movements matched the selection.");
        Map<String, Object> params = movementParams(p);
        params.put("SUM_COST", tot[0]); params.put("SUM_SALE", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL02 — Inventory Movements Summary   (smtrans, aggregated by item)
    // ════════════════════════════════════════════════════════════════════════

    public Map<String, Object> getInventoryMovementsSummary(AppSession s, MovementParams p) {
        Condition where = SMTRANS.COMPANY_NO.eq(s.getCompanyNo());
        where = applyTransFilters(where, p.locNo(), p.startItem(), p.endItem(),
                                  p.startDate(), p.endDate(), p.kind());

        // Aggregate fields
        Field<String>     desc1    = DSL.max(SMTRANS.DESC_1).as("desc_1");
        Field<Integer>    moveCnt  = DSL.count().as("move_count");
        Field<BigDecimal> qtyIn    = DSL.sum(
            DSL.when(SMTRANS.IN_OUT_DIRECT_IND.eq("I"), SMTRANS.QTY).otherwise(BigDecimal.ZERO)
        ).as("qty_in");
        Field<BigDecimal> qtyOut   = DSL.sum(
            DSL.when(SMTRANS.IN_OUT_DIRECT_IND.eq("O"), SMTRANS.QTY).otherwise(BigDecimal.ZERO)
        ).as("qty_out");
        Field<BigDecimal> costVal  = DSL.sum(SMTRANS.COST_VALUE).as("cost_value");
        Field<BigDecimal> saleVal  = DSL.sum(SMTRANS.SALES_OR_RECPT_VALUE).as("sale_value");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(SMTRANS.LOC_NO, SMTRANS.STOCK_CODE, desc1, moveCnt, qtyIn, qtyOut, costVal, saleVal)
               .from(SMTRANS)
               .where(where)
               .groupBy(SMTRANS.LOC_NO, SMTRANS.STOCK_CODE)
               .orderBy(SMTRANS.LOC_NO, SMTRANS.STOCK_CODE)
               .fetch()
               .forEach(r -> {
                   BigDecimal in   = z(r.get(qtyIn));
                   BigDecimal out  = z(r.get(qtyOut));
                   BigDecimal cost = z(r.get(costVal));
                   BigDecimal sale = z(r.get(saleVal));
                   tot[0] = tot[0].add(cost); tot[1] = tot[1].add(sale);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("locNo",       trim(r.get(SMTRANS.LOC_NO)));
                   row.put("stockCode",   trim(r.get(SMTRANS.STOCK_CODE)));
                   row.put("description", r.get(desc1));
                   row.put("moveCount",   r.get(moveCnt));
                   row.put("qtyIn",    in); row.put("qtyOut", out); row.put("qtyNet", in.subtract(out));
                   row.put("costValue", cost); row.put("saleValue", sale);
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getInventoryMovementsSummary: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No inventory movements matched the selection.");
        Map<String, Object> params = movementParams(p);
        params.put("SUM_COST", tot[0]); params.put("SUM_SALE", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL06 — Sales History   (smtrans AR sales + credits, by item / customer)
    // ════════════════════════════════════════════════════════════════════════

    public record SalesHistoryParams(
            String locNo, String startItem, String endItem, String startCustomer, String endCustomer,
            LocalDate startDate, LocalDate endDate) {}

    public Map<String, Object> getSalesHistory(AppSession s, SalesHistoryParams p) {
        // AR sales/credits store qty + sale value + cost as negative (stock-outflow
        // convention). Negate so the report reads as natural positive sales figures;
        // gross margin is then sales − cost.
        Condition where = SMTRANS.COMPANY_NO.eq(s.getCompanyNo())
                .and(SMTRANS.SYSTEM_ID.eq("AR"))
                .and(SMTRANS.MOVE_IND.in("S", "C"));
        if (notBlank(p.locNo())) where = where.and(SMTRANS.LOC_NO.eq(p.locNo()));
        where = applyRange(where, SMTRANS.STOCK_CODE, p.startItem(), p.endItem());
        where = applyRange(where, SMTRANS.CUST_SUPPLIER_NO, p.startCustomer(), p.endCustomer());
        where = applyDate(where, SMTRANS.MOVE_DATE, p.startDate(), p.endDate());

        Field<String>     desc1    = DSL.max(SMTRANS.DESC_1).as("desc_1");
        Field<String>     custName = DSL.max(ARCUSTS.NAME_1).as("cust_name");
        Field<BigDecimal> qty      = DSL.sum(SMTRANS.QTY.neg()).as("qty");
        Field<BigDecimal> salesVal = DSL.sum(SMTRANS.SALES_OR_RECPT_VALUE.neg()).as("sales_value");
        Field<BigDecimal> costVal  = DSL.sum(SMTRANS.COST_VALUE.neg()).as("cost_value");
        Field<Integer>    lineCnt  = DSL.count().as("line_count");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(SMTRANS.STOCK_CODE, desc1, SMTRANS.CUST_SUPPLIER_NO, custName,
                       qty, salesVal, costVal, lineCnt)
               .from(SMTRANS)
               .leftJoin(ARCUSTS)
                   .on(ARCUSTS.COMPANY_NO.eq(SMTRANS.COMPANY_NO)
                       .and(ARCUSTS.CUST_NO.eq(SMTRANS.CUST_SUPPLIER_NO)))
               .where(where)
               .groupBy(SMTRANS.STOCK_CODE, SMTRANS.CUST_SUPPLIER_NO)
               .orderBy(SMTRANS.STOCK_CODE, SMTRANS.CUST_SUPPLIER_NO)
               .fetch()
               .forEach(r -> {
                   BigDecimal sales = z(r.get(salesVal));
                   BigDecimal cost  = z(r.get(costVal));
                   tot[0] = tot[0].add(sales); tot[1] = tot[1].add(cost);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("stockCode",    trim(r.get(SMTRANS.STOCK_CODE)));
                   row.put("description",  r.get(desc1));
                   row.put("customerNo",   trim(r.get(SMTRANS.CUST_SUPPLIER_NO)));
                   row.put("customerName", trim(r.get(custName)));
                   row.put("qty",          z(r.get(qty)));
                   row.put("salesValue",   sales);
                   row.put("costValue",    cost);
                   row.put("margin",       sales.subtract(cost));   // values already negated to positive; gross margin = sales − cost
                   row.put("lineCount",    r.get(lineCnt));
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getSalesHistory: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No sales history matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC",   notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE", rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("CUST_RANGE", rangeDesc(p.startCustomer(), p.endCustomer(), "customers"));
        params.put("DATE_RANGE", dateDesc(p.startDate(), p.endDate()));
        params.put("SUM_SALES", tot[0]); params.put("SUM_COST", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL16 — Transactions by Customer   (smtrans, by customer)
    // ════════════════════════════════════════════════════════════════════════

    public record CustomerTxnParams(
            String locNo, String startCustomer, String endCustomer, String startItem, String endItem,
            LocalDate startDate, LocalDate endDate, String kind) {}

    public Map<String, Object> getTransactionsByCustomer(AppSession s, CustomerTxnParams p) {
        Condition where = SMTRANS.COMPANY_NO.eq(s.getCompanyNo())
                .and(SMTRANS.CUST_SUPPLIER_NO.isNotNull())
                .and(SMTRANS.CUST_SUPPLIER_NO.ne(""));
        if (notBlank(p.locNo())) where = where.and(SMTRANS.LOC_NO.eq(p.locNo()));
        where = applyRange(where, SMTRANS.CUST_SUPPLIER_NO, p.startCustomer(), p.endCustomer());
        where = applyRange(where, SMTRANS.STOCK_CODE, p.startItem(), p.endItem());
        where = applyDate(where, SMTRANS.MOVE_DATE, p.startDate(), p.endDate());
        where = applyKind(where, p.kind());

        Field<String> custName = DSL.max(ARCUSTS.NAME_1).as("cust_name");
        Field<String> desc1    = DSL.max(SMTRANS.DESC_1).as("desc_1");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(SMTRANS.CUST_SUPPLIER_NO, custName, SMTRANS.STOCK_CODE, desc1,
                       SMTRANS.LOC_NO, SMTRANS.MOVE_DATE, SMTRANS.SYSTEM_ID, SMTRANS.MOVE_IND,
                       SMTRANS.DOC_TYPE, SMTRANS.DOC_NO,
                       SMTRANS.QTY, SMTRANS.SALES_OR_RECPT_VALUE, SMTRANS.COST_VALUE)
               .from(SMTRANS)
               .leftJoin(ARCUSTS)
                   .on(ARCUSTS.COMPANY_NO.eq(SMTRANS.COMPANY_NO)
                       .and(ARCUSTS.CUST_NO.eq(SMTRANS.CUST_SUPPLIER_NO)))
               .where(where)
               .groupBy(SMTRANS.CUST_SUPPLIER_NO, SMTRANS.STOCK_CODE, SMTRANS.LOC_NO,
                        SMTRANS.MOVE_DATE, SMTRANS.SYSTEM_ID, SMTRANS.MOVE_IND,
                        SMTRANS.DOC_TYPE, SMTRANS.DOC_NO,
                        SMTRANS.QTY, SMTRANS.SALES_OR_RECPT_VALUE, SMTRANS.COST_VALUE)
               .orderBy(SMTRANS.CUST_SUPPLIER_NO, SMTRANS.MOVE_DATE, SMTRANS.STOCK_CODE)
               .fetch()
               .forEach(r -> {
                   BigDecimal sale = z(r.get(SMTRANS.SALES_OR_RECPT_VALUE));
                   BigDecimal cost = z(r.get(SMTRANS.COST_VALUE));
                   tot[0] = tot[0].add(sale); tot[1] = tot[1].add(cost);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("customerNo",   trim(r.get(SMTRANS.CUST_SUPPLIER_NO)));
                   row.put("customerName", trim(r.get(custName)));
                   row.put("stockCode",    trim(r.get(SMTRANS.STOCK_CODE)));
                   row.put("description",  r.get(desc1));
                   row.put("locNo",        trim(r.get(SMTRANS.LOC_NO)));
                   row.put("moveDate",     ldToSqlDate(r.get(SMTRANS.MOVE_DATE)));
                   row.put("kind",         kindLabel(r.get(SMTRANS.SYSTEM_ID), r.get(SMTRANS.MOVE_IND)));
                   row.put("docType",      trim(r.get(SMTRANS.DOC_TYPE)));
                   row.put("docNo",        trim(r.get(SMTRANS.DOC_NO)));
                   row.put("qty",          z(r.get(SMTRANS.QTY)));
                   row.put("saleValue",    sale);
                   row.put("costValue",    cost);
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getTransactionsByCustomer: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No customer transactions matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC",   notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("CUST_RANGE", rangeDesc(p.startCustomer(), p.endCustomer(), "customers"));
        params.put("ITEM_RANGE", rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("DATE_RANGE", dateDesc(p.startDate(), p.endDate()));
        params.put("KIND_DESC",  kindDesc(p.kind()));
        params.put("SUM_SALE", tot[0]); params.put("SUM_COST", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL12 — Purchase Analysis   (smtrans AP receipts, by item / supplier)
    // ════════════════════════════════════════════════════════════════════════

    public record PurchaseParams(
            String locNo, String startItem, String endItem, String startSupplier, String endSupplier,
            LocalDate startDate, LocalDate endDate) {}

    public Map<String, Object> getPurchaseAnalysis(AppSession s, PurchaseParams p) {
        Condition where = SMTRANS.COMPANY_NO.eq(s.getCompanyNo())
                .and(SMTRANS.SYSTEM_ID.eq("AP"))
                .and(SMTRANS.MOVE_IND.eq("R"));
        if (notBlank(p.locNo())) where = where.and(SMTRANS.LOC_NO.eq(p.locNo()));
        where = applyRange(where, SMTRANS.STOCK_CODE, p.startItem(), p.endItem());
        where = applyRange(where, SMTRANS.CUST_SUPPLIER_NO, p.startSupplier(), p.endSupplier());
        where = applyDate(where, SMTRANS.MOVE_DATE, p.startDate(), p.endDate());

        Field<String>     desc1    = DSL.max(SMTRANS.DESC_1).as("desc_1");
        Field<String>     suppName = DSL.max(APSUPPS.NAME_1).as("supp_name");
        Field<BigDecimal> qty      = DSL.sum(SMTRANS.QTY).as("qty");
        Field<BigDecimal> recptVal = DSL.sum(SMTRANS.SALES_OR_RECPT_VALUE).as("recpt_value");
        Field<BigDecimal> costVal  = DSL.sum(SMTRANS.COST_VALUE).as("cost_value");
        Field<Integer>    lineCnt  = DSL.count().as("line_count");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            dsl.select(SMTRANS.STOCK_CODE, desc1, SMTRANS.CUST_SUPPLIER_NO, suppName,
                       SMTRANS.LOC_NO, qty, recptVal, costVal, lineCnt)
               .from(SMTRANS)
               .leftJoin(APSUPPS)
                   .on(APSUPPS.COMPANY_NO.eq(SMTRANS.COMPANY_NO)
                       .and(APSUPPS.SUPPLIER_NO.eq(SMTRANS.CUST_SUPPLIER_NO)))
               .where(where)
               .groupBy(SMTRANS.STOCK_CODE, SMTRANS.CUST_SUPPLIER_NO, SMTRANS.LOC_NO)
               .orderBy(SMTRANS.STOCK_CODE, SMTRANS.CUST_SUPPLIER_NO)
               .fetch()
               .forEach(r -> {
                   BigDecimal recpt = z(r.get(recptVal));
                   BigDecimal cost  = z(r.get(costVal));
                   tot[0] = tot[0].add(recpt); tot[1] = tot[1].add(cost);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("stockCode",    trim(r.get(SMTRANS.STOCK_CODE)));
                   row.put("description",  r.get(desc1));
                   row.put("supplierNo",   trim(r.get(SMTRANS.CUST_SUPPLIER_NO)));
                   row.put("supplierName", trim(r.get(suppName)));
                   row.put("locNo",        trim(r.get(SMTRANS.LOC_NO)));
                   row.put("qty",          z(r.get(qty)));
                   row.put("recptValue",   recpt);
                   row.put("costValue",    cost);
                   row.put("lineCount",    r.get(lineCnt));
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getPurchaseAnalysis: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No purchase receipts matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC",   notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE", rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("SUPP_RANGE", rangeDesc(p.startSupplier(), p.endSupplier(), "suppliers"));
        params.put("DATE_RANGE", dateDesc(p.startDate(), p.endDate()));
        params.put("SUM_RECPT", tot[0]); params.put("SUM_COST", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL07 — Inventory Valuation   (smstloc × smsthed — empty until masters load)
    // ════════════════════════════════════════════════════════════════════════

    public record StockParams(
            String locNo, String startItem, String endItem, String startProdType, String endProdType,
            boolean excludeZeroQty) {}

    public Map<String, Object> getInventoryValuation(AppSession s, StockParams p) {
        Condition where = SMSTLOC.COMPANY_NO.eq(s.getCompanyNo());
        where = applyStockFilters(where, p.locNo(), p.startItem(), p.endItem(),
                                  p.startProdType(), p.endProdType());
        if (p.excludeZeroQty()) where = where.and(SMSTLOC.QTY_ON_HAND.ne(BigDecimal.ZERO));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            dsl.select(SMSTLOC.LOC_NO, SMSTLOC.STOCK_CODE, SMSTHED.DESC_1, SMSTHED.PRODUCT_TYPE,
                       SMSTLOC.QTY_ON_HAND, SMSTLOC.VALUE_ON_HAND, SMSTLOC.STD_COST, SMSTLOC.LAST_PO_COST)
               .from(SMSTLOC)
               .leftJoin(SMSTHED)
                   .on(SMSTHED.COMPANY_NO.eq(SMSTLOC.COMPANY_NO)
                       .and(SMSTHED.STOCK_CODE.eq(SMSTLOC.STOCK_CODE)))
               .where(where)
               .orderBy(SMSTLOC.LOC_NO, SMSTLOC.STOCK_CODE)
               .fetch()
               .forEach(r -> {
                   BigDecimal val = z(r.get(SMSTLOC.VALUE_ON_HAND));
                   tot[0] = tot[0].add(val);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("locNo",        trim(r.get(SMSTLOC.LOC_NO)));
                   row.put("stockCode",    trim(r.get(SMSTLOC.STOCK_CODE)));
                   row.put("description",  r.get(SMSTHED.DESC_1));
                   row.put("productType",  trim(r.get(SMSTHED.PRODUCT_TYPE)));
                   row.put("qtyOnHand",    z(r.get(SMSTLOC.QTY_ON_HAND)));
                   row.put("stdCost",      z(r.get(SMSTLOC.STD_COST)));
                   row.put("lastCost",     z(r.get(SMSTLOC.LAST_PO_COST)));
                   row.put("valueOnHand",  val);
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getInventoryValuation: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No stock-on-hand rows — the stock master (smstloc/smsthed) is not yet loaded in this extract.");
        Map<String, Object> params = stockParams(p);
        params.put("SUM_VALUE", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL26 — Item Availability   (smstloc — empty until masters load)
    // ════════════════════════════════════════════════════════════════════════

    public Map<String, Object> getItemAvailability(AppSession s, StockParams p) {
        Condition where = SMSTLOC.COMPANY_NO.eq(s.getCompanyNo());
        where = applyStockFilters(where, p.locNo(), p.startItem(), p.endItem(),
                                  p.startProdType(), p.endProdType());
        if (p.excludeZeroQty()) where = where.and(
            SMSTLOC.QTY_ON_HAND.ne(BigDecimal.ZERO)
                .or(SMSTLOC.QTY_ON_ORDER.ne(BigDecimal.ZERO))
                .or(SMSTLOC.QTY_ALLOCATED.ne(BigDecimal.ZERO)));

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            dsl.select(SMSTLOC.LOC_NO, SMSTLOC.STOCK_CODE, SMSTHED.DESC_1,
                       SMSTLOC.QTY_ON_HAND, SMSTLOC.QTY_ALLOCATED, SMSTLOC.QTY_RESERVED,
                       SMSTLOC.QTY_ON_ORDER, SMSTLOC.QTY_ON_PO, SMSTLOC.QTY_ON_BACKORD)
               .from(SMSTLOC)
               .leftJoin(SMSTHED)
                   .on(SMSTHED.COMPANY_NO.eq(SMSTLOC.COMPANY_NO)
                       .and(SMSTHED.STOCK_CODE.eq(SMSTLOC.STOCK_CODE)))
               .where(where)
               .orderBy(SMSTLOC.STOCK_CODE, SMSTLOC.LOC_NO)
               .fetch()
               .forEach(r -> {
                   BigDecimal onHand = z(r.get(SMSTLOC.QTY_ON_HAND));
                   BigDecimal alloc  = z(r.get(SMSTLOC.QTY_ALLOCATED));
                   BigDecimal resv   = z(r.get(SMSTLOC.QTY_RESERVED));
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("stockCode",    trim(r.get(SMSTLOC.STOCK_CODE)));
                   row.put("description",  r.get(SMSTHED.DESC_1));
                   row.put("locNo",        trim(r.get(SMSTLOC.LOC_NO)));
                   row.put("qtyOnHand",    onHand);
                   row.put("qtyAllocated", alloc);
                   row.put("qtyReserved",  resv);
                   row.put("qtyOnOrder",   z(r.get(SMSTLOC.QTY_ON_ORDER)));
                   row.put("qtyOnPo",      z(r.get(SMSTLOC.QTY_ON_PO)));
                   row.put("qtyOnBackord", z(r.get(SMSTLOC.QTY_ON_BACKORD)));
                   row.put("qtyAvailable", onHand.subtract(alloc).subtract(resv));
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getItemAvailability: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No availability rows — the stock master (smstloc) is not yet loaded in this extract.");
        Map<String, Object> params = stockParams(p);
        params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL10 — Reorder and PO Requisitions   (smstloc below min — empty until loaded)
    // ════════════════════════════════════════════════════════════════════════

    public record ReorderParams(
            String locNo, String startItem, String endItem, String startProdType, String endProdType,
            String sequence) {}

    public Map<String, Object> getReorderRequisitions(AppSession s, ReorderParams p) {
        // The reorder condition — available qty < min — is a runtime-computed expression
        // across four columns; expressed as plain SQL for readability.
        Condition where = SMSTLOC.COMPANY_NO.eq(s.getCompanyNo())
                .and(DSL.condition(
                    "(smstloc.qty_on_hand + smstloc.qty_on_order + smstloc.qty_on_po - smstloc.qty_allocated) < smstloc.min_qty_level"));
        where = applyStockFilters(where, p.locNo(), p.startItem(), p.endItem(),
                                  p.startProdType(), p.endProdType());

        String seqKey = notBlank(p.sequence()) ? p.sequence() : "ITEM";
        var orderBy = switch (seqKey) {
            case "SUPPLIER" -> new org.jooq.SortField<?>[]{ SMSTLOC.USUAL_SUPPLIER_NO.asc(), SMSTLOC.STOCK_CODE.asc() };
            case "BIN"      -> new org.jooq.SortField<?>[]{ SMSTLOC.BIN_NO.asc(), SMSTLOC.STOCK_CODE.asc() };
            default         -> new org.jooq.SortField<?>[]{ SMSTLOC.STOCK_CODE.asc(), SMSTLOC.LOC_NO.asc() };
        };

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            dsl.select(SMSTLOC.LOC_NO, SMSTLOC.STOCK_CODE, SMSTHED.DESC_1,
                       SMSTLOC.USUAL_SUPPLIER_NO, SMSTLOC.BIN_NO,
                       SMSTLOC.QTY_ON_HAND, SMSTLOC.QTY_ON_ORDER, SMSTLOC.QTY_ON_PO,
                       SMSTLOC.QTY_ALLOCATED, SMSTLOC.MIN_QTY_LEVEL, SMSTLOC.MAX_QTY_LEVEL,
                       SMSTLOC.LAST_PO_COST)
               .from(SMSTLOC)
               .leftJoin(SMSTHED)
                   .on(SMSTHED.COMPANY_NO.eq(SMSTLOC.COMPANY_NO)
                       .and(SMSTHED.STOCK_CODE.eq(SMSTLOC.STOCK_CODE)))
               .where(where)
               .orderBy(orderBy)
               .fetch()
               .forEach(r -> {
                   BigDecimal onHand  = z(r.get(SMSTLOC.QTY_ON_HAND));
                   BigDecimal onOrder = z(r.get(SMSTLOC.QTY_ON_ORDER));
                   BigDecimal onPo    = z(r.get(SMSTLOC.QTY_ON_PO));
                   BigDecimal alloc   = z(r.get(SMSTLOC.QTY_ALLOCATED));
                   BigDecimal max     = z(r.get(SMSTLOC.MAX_QTY_LEVEL));
                   BigDecimal avail   = onHand.add(onOrder).add(onPo).subtract(alloc);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("stockCode",   trim(r.get(SMSTLOC.STOCK_CODE)));
                   row.put("description", r.get(SMSTHED.DESC_1));
                   row.put("locNo",       trim(r.get(SMSTLOC.LOC_NO)));
                   row.put("supplierNo",  trim(r.get(SMSTLOC.USUAL_SUPPLIER_NO)));
                   row.put("binNo",       trim(r.get(SMSTLOC.BIN_NO)));
                   row.put("qtyOnHand",   onHand);
                   row.put("qtyOnOrder",  onOrder.add(onPo));
                   row.put("qtyAllocated", alloc);
                   row.put("minLevel",    z(r.get(SMSTLOC.MIN_QTY_LEVEL)));
                   row.put("maxLevel",    max);
                   row.put("reorderQty",  max.subtract(avail));
                   row.put("lastCost",    z(r.get(SMSTLOC.LAST_PO_COST)));
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getReorderRequisitions: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No items below minimum — the stock master (smstloc) is not yet loaded in this extract.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC",   notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE", rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("PTYPE_RANGE", rangeDesc(p.startProdType(), p.endProdType(), "product types"));
        params.put("SEQ_DESC", switch (seqKey) {
            case "SUPPLIER" -> "Supplier"; case "BIN" -> "Bin"; default -> "Item"; });
        params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL20 — Inactive Inventory   (smstloc last-move before a date — empty until loaded)
    // ════════════════════════════════════════════════════════════════════════

    public record InactiveParams(
            String locNo, String startItem, String endItem, String startProdType, String endProdType,
            LocalDate inactiveSince, boolean useSaleDate) {}

    public Map<String, Object> getInactiveInventory(AppSession s, InactiveParams p) {
        Condition where = SMSTLOC.COMPANY_NO.eq(s.getCompanyNo());
        where = applyStockFilters(where, p.locNo(), p.startItem(), p.endItem(),
                                  p.startProdType(), p.endProdType());
        if (p.inactiveSince() != null) {
            // useSaleDate switches between last_sale_date and last_move_date;
            // expressed as plain SQL because jOOQ typed field references can't be
            // selected dynamically without losing type safety here.
            String dateCol = p.useSaleDate() ? "smstloc.last_sale_date" : "smstloc.last_move_date";
            where = where.and(DSL.condition(
                "(" + dateCol + " IS NULL OR " + dateCol + " < {0})", p.inactiveSince()));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            dsl.select(SMSTLOC.LOC_NO, SMSTLOC.STOCK_CODE, SMSTHED.DESC_1,
                       SMSTLOC.QTY_ON_HAND, SMSTLOC.VALUE_ON_HAND,
                       SMSTLOC.LAST_MOVE_DATE, SMSTLOC.LAST_SALE_DATE)
               .from(SMSTLOC)
               .leftJoin(SMSTHED)
                   .on(SMSTHED.COMPANY_NO.eq(SMSTLOC.COMPANY_NO)
                       .and(SMSTHED.STOCK_CODE.eq(SMSTLOC.STOCK_CODE)))
               .where(where)
               .orderBy(SMSTLOC.LOC_NO, SMSTLOC.STOCK_CODE)
               .fetch()
               .forEach(r -> {
                   BigDecimal val = z(r.get(SMSTLOC.VALUE_ON_HAND));
                   tot[0] = tot[0].add(val);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("locNo",        trim(r.get(SMSTLOC.LOC_NO)));
                   row.put("stockCode",    trim(r.get(SMSTLOC.STOCK_CODE)));
                   row.put("description",  r.get(SMSTHED.DESC_1));
                   row.put("qtyOnHand",    z(r.get(SMSTLOC.QTY_ON_HAND)));
                   row.put("valueOnHand",  val);
                   row.put("lastMoveDate", ldToSqlDate(r.get(SMSTLOC.LAST_MOVE_DATE)));
                   row.put("lastSaleDate", ldToSqlDate(r.get(SMSTLOC.LAST_SALE_DATE)));
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getInactiveInventory: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No inactive items — the stock master (smstloc) is not yet loaded in this extract.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC",    notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE",  rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("PTYPE_RANGE", rangeDesc(p.startProdType(), p.endProdType(), "product types"));
        params.put("SINCE_DESC",  p.inactiveSince() != null ? "Inactive since " + p.inactiveSince() : "All items");
        params.put("BASIS_DESC",  p.useSaleDate() ? "Last sale date" : "Last movement date");
        params.put("SUM_VALUE", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL15 (+24) — Item Status   (consolidated; sequence = ITEM | LOCATION)
    // ════════════════════════════════════════════════════════════════════════

    public record ItemStatusParams(
            String locNo, String startItem, String endItem, String startProdType, String endProdType,
            LocalDate reportDate, String sequence) {}

    public Map<String, Object> getItemStatus(AppSession s, ItemStatusParams p) {
        SmSequence seq = parseSeq(p.sequence(), SmSequence.ITEM);
        Condition where = SMSTLOC.COMPANY_NO.eq(s.getCompanyNo());
        where = applyStockFilters(where, p.locNo(), p.startItem(), p.endItem(),
                                  p.startProdType(), p.endProdType());

        var orderBy = seq == SmSequence.LOCATION
            ? new org.jooq.SortField<?>[]{ SMSTLOC.LOC_NO.asc(), SMSTHED.PRODUCT_TYPE.asc(), SMSTLOC.STOCK_CODE.asc() }
            : new org.jooq.SortField<?>[]{ SMSTHED.PRODUCT_TYPE.asc(), SMSTLOC.STOCK_CODE.asc(), SMSTLOC.LOC_NO.asc() };

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            dsl.select(SMSTLOC.LOC_NO, SMSTLOC.STOCK_CODE, SMSTHED.DESC_1, SMSTHED.PRODUCT_TYPE,
                       SMSTLOC.ITEM_STATUS, SMSTLOC.QTY_ON_HAND, SMSTLOC.VALUE_ON_HAND,
                       SMSTLOC.QTY_ALLOCATED, SMSTLOC.QTY_ON_ORDER,
                       SMSTLOC.STD_COST, SMSTLOC.LAST_SALE_DATE, SMSTLOC.LAST_MOVE_DATE)
               .from(SMSTLOC)
               .leftJoin(SMSTHED)
                   .on(SMSTHED.COMPANY_NO.eq(SMSTLOC.COMPANY_NO)
                       .and(SMSTHED.STOCK_CODE.eq(SMSTLOC.STOCK_CODE)))
               .where(where)
               .orderBy(orderBy)
               .fetch()
               .forEach(r -> {
                   BigDecimal val = z(r.get(SMSTLOC.VALUE_ON_HAND));
                   tot[0] = tot[0].add(val);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("locNo",        trim(r.get(SMSTLOC.LOC_NO)));
                   row.put("stockCode",    trim(r.get(SMSTLOC.STOCK_CODE)));
                   row.put("description",  r.get(SMSTHED.DESC_1));
                   row.put("productType",  trim(r.get(SMSTHED.PRODUCT_TYPE)));
                   row.put("itemStatus",   trim(r.get(SMSTLOC.ITEM_STATUS)));
                   row.put("qtyOnHand",    z(r.get(SMSTLOC.QTY_ON_HAND)));
                   row.put("qtyAllocated", z(r.get(SMSTLOC.QTY_ALLOCATED)));
                   row.put("qtyOnOrder",   z(r.get(SMSTLOC.QTY_ON_ORDER)));
                   row.put("stdCost",      z(r.get(SMSTLOC.STD_COST)));
                   row.put("valueOnHand",  val);
                   row.put("lastSaleDate", ldToSqlDate(r.get(SMSTLOC.LAST_SALE_DATE)));
                   row.put("lastMoveDate", ldToSqlDate(r.get(SMSTLOC.LAST_MOVE_DATE)));
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getItemStatus: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No item-status rows — the stock master (smstloc) is not yet loaded in this extract.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SEQUENCE_DESC", seq == SmSequence.LOCATION ? "Location" : "Item");
        params.put("LOC_DESC",      notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE",    rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("PTYPE_RANGE",   rangeDesc(p.startProdType(), p.endProdType(), "product types"));
        params.put("REPORT_DATE",   p.reportDate() != null ? p.reportDate().toString() : LocalDate.now().toString());
        params.put("SUM_VALUE", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL27 — Serial/Batch History   (smserno/smsetrx NOT in extract)
    // ════════════════════════════════════════════════════════════════════════

    public record SerialParams(
            String locNo, String startItem, String endItem, String startSerial, String endSerial,
            LocalDate startDate, LocalDate endDate) {}

    public Map<String, Object> getSerialBatchHistory(AppSession s, SerialParams p) {
        // The serial/batch detail tables (smserno, smsetrx) are not present in the
        // current extract, so there is no source to query. Return a clear warning.
        return warn("Serial/batch history is unavailable — the serial tables (smserno/smsetrx) are not in the current extract.");
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL36 — Consignment Stock GL Reconcile   (smstloc consignment — empty until loaded)
    // ════════════════════════════════════════════════════════════════════════

    public Map<String, Object> getConsignmentGlReconcile(AppSession s, StockParams p) {
        Condition where = SMSTLOC.COMPANY_NO.eq(s.getCompanyNo())
                .and(SMSTLOC.LOC_CONSIGNMENT_FLAG.eq("Y").or(SMSTLOC.QTY_ON_CONSIGN.ne(BigDecimal.ZERO)));
        where = applyStockFilters(where, p.locNo(), p.startItem(), p.endItem(),
                                  p.startProdType(), p.endProdType());

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            dsl.select(SMSTLOC.LOC_NO, SMSTLOC.STOCK_CODE, SMSTHED.DESC_1,
                       SMSTLOC.QTY_ON_CONSIGN, SMSTLOC.VALUE_ON_HAND, SMSTLOC.STD_COST)
               .from(SMSTLOC)
               .leftJoin(SMSTHED)
                   .on(SMSTHED.COMPANY_NO.eq(SMSTLOC.COMPANY_NO)
                       .and(SMSTHED.STOCK_CODE.eq(SMSTLOC.STOCK_CODE)))
               .where(where)
               .orderBy(SMSTLOC.STOCK_CODE, SMSTLOC.LOC_NO)
               .fetch()
               .forEach(r -> {
                   BigDecimal qty  = z(r.get(SMSTLOC.QTY_ON_CONSIGN));
                   BigDecimal cost = z(r.get(SMSTLOC.STD_COST));
                   BigDecimal val  = qty.multiply(cost);
                   tot[0] = tot[0].add(val);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("stockCode",     trim(r.get(SMSTLOC.STOCK_CODE)));
                   row.put("description",   r.get(SMSTHED.DESC_1));
                   row.put("locNo",         trim(r.get(SMSTLOC.LOC_NO)));
                   row.put("qtyOnConsign",  qty);
                   row.put("stdCost",       cost);
                   row.put("consignValue",  val);
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getConsignmentGlReconcile: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No consignment stock — the stock master (smstloc) is not yet loaded in this extract.");
        Map<String, Object> params = stockParams(p);
        params.put("SUM_VALUE", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL53 (+56) — Consignment Stock   (consolidated; sequence = CUSTOMER | ITEM)
    // ════════════════════════════════════════════════════════════════════════

    public record ConsignmentParams(
            String startCustomer, String endCustomer, String startItem, String endItem, String sequence) {}

    public Map<String, Object> getConsignmentStock(AppSession s, ConsignmentParams p) {
        // COBOL reads opordhd/opordln (consignment orders) — not extracted. Fall back to
        // smtrans consignment movements so the report still runs against real data.
        SmSequence seq = parseSeq(p.sequence(), SmSequence.CUSTOMER);
        Condition where = SMTRANS.COMPANY_NO.eq(s.getCompanyNo())
                .and(SMTRANS.CONSIGNMENT_FLAG.eq("Y"));
        where = applyRange(where, SMTRANS.CUST_SUPPLIER_NO, p.startCustomer(), p.endCustomer());
        where = applyRange(where, SMTRANS.STOCK_CODE, p.startItem(), p.endItem());

        Field<String>     custName = DSL.max(ARCUSTS.NAME_1).as("cust_name");
        Field<String>     desc1    = DSL.max(SMTRANS.DESC_1).as("desc_1");
        Field<BigDecimal> qty      = DSL.sum(SMTRANS.QTY).as("qty");
        Field<BigDecimal> costVal  = DSL.sum(SMTRANS.COST_VALUE).as("cost_value");

        var orderBy = seq == SmSequence.ITEM
            ? new org.jooq.SortField<?>[]{ SMTRANS.STOCK_CODE.asc(), SMTRANS.CUST_SUPPLIER_NO.asc() }
            : new org.jooq.SortField<?>[]{ SMTRANS.CUST_SUPPLIER_NO.asc(), SMTRANS.STOCK_CODE.asc() };

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            dsl.select(SMTRANS.CUST_SUPPLIER_NO, custName, SMTRANS.STOCK_CODE, desc1,
                       SMTRANS.LOC_NO, qty, costVal)
               .from(SMTRANS)
               .leftJoin(ARCUSTS)
                   .on(ARCUSTS.COMPANY_NO.eq(SMTRANS.COMPANY_NO)
                       .and(ARCUSTS.CUST_NO.eq(SMTRANS.CUST_SUPPLIER_NO)))
               .where(where)
               .groupBy(SMTRANS.CUST_SUPPLIER_NO, SMTRANS.STOCK_CODE, SMTRANS.LOC_NO)
               .orderBy(orderBy)
               .fetch()
               .forEach(r -> {
                   BigDecimal cost = z(r.get(costVal));
                   tot[0] = tot[0].add(cost);
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("customerNo",   trim(r.get(SMTRANS.CUST_SUPPLIER_NO)));
                   row.put("customerName", trim(r.get(custName)));
                   row.put("stockCode",    trim(r.get(SMTRANS.STOCK_CODE)));
                   row.put("description",  r.get(desc1));
                   row.put("locNo",        trim(r.get(SMTRANS.LOC_NO)));
                   row.put("qty",          z(r.get(qty)));
                   row.put("costValue",    cost);
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getConsignmentStock: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No consignment movements — the consignment order tables (opordhd/opordln) are not in the extract and smtrans holds no consignment-flagged rows.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SEQUENCE_DESC", seq == SmSequence.ITEM ? "Item" : "Customer");
        params.put("CUST_RANGE",    rangeDesc(p.startCustomer(), p.endCustomer(), "customers"));
        params.put("ITEM_RANGE",    rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("SUM_VALUE", tot[0]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMTL08 — Price List   (smsteff × smsthed — empty until masters load)
    // ════════════════════════════════════════════════════════════════════════

    public record PriceListParams(
            String locNo, String startItem, String endItem, String startProdType, String endProdType,
            LocalDate effectiveDate) {}

    public Map<String, Object> getPriceList(AppSession s, PriceListParams p) {
        Condition where = SMSTEFF.COMPANY_NO.eq(s.getCompanyNo());
        if (notBlank(p.locNo())) where = where.and(SMSTEFF.LOC_NO.eq(p.locNo()));
        where = applyRange(where, SMSTEFF.STOCK_CODE, p.startItem(), p.endItem());
        where = applyRange(where, SMSTHED.PRODUCT_TYPE, p.startProdType(), p.endProdType());
        if (p.effectiveDate() != null) where = where.and(SMSTEFF.EFFECTIVE_DATE.le(p.effectiveDate()));

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            dsl.select(SMSTEFF.LOC_NO, SMSTEFF.STOCK_CODE, SMSTHED.DESC_1, SMSTHED.PRODUCT_TYPE,
                       SMSTEFF.RECOMMENDED_PRICE, SMSTEFF.WHOLESALE_PRICE,
                       SMSTEFF.EFFECTIVE_DATE, SMSTEFF.FOR_CURR_CODE)
               .from(SMSTEFF)
               .leftJoin(SMSTHED)
                   .on(SMSTHED.COMPANY_NO.eq(SMSTEFF.COMPANY_NO)
                       .and(SMSTHED.STOCK_CODE.eq(SMSTEFF.STOCK_CODE)))
               .where(where)
               .orderBy(SMSTEFF.STOCK_CODE, SMSTEFF.LOC_NO, SMSTEFF.EFFECTIVE_DATE)
               .fetch()
               .forEach(r -> {
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("stockCode",         trim(r.get(SMSTEFF.STOCK_CODE)));
                   row.put("description",        r.get(SMSTHED.DESC_1));
                   row.put("productType",        trim(r.get(SMSTHED.PRODUCT_TYPE)));
                   row.put("locNo",              trim(r.get(SMSTEFF.LOC_NO)));
                   row.put("currency",           trim(r.get(SMSTEFF.FOR_CURR_CODE)));
                   row.put("effectiveDate",      ldToSqlDate(r.get(SMSTEFF.EFFECTIVE_DATE)));
                   row.put("recommendedPrice",   z(r.get(SMSTEFF.RECOMMENDED_PRICE)));
                   row.put("wholesalePrice",     z(r.get(SMSTEFF.WHOLESALE_PRICE)));
                   rows.add(row);
               });
        } catch (Exception e) { log.error("getPriceList: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No prices — the price-effective table (smsteff) is not yet loaded in this extract.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC",   notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE", rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("PTYPE_RANGE", rangeDesc(p.startProdType(), p.endProdType(), "product types"));
        params.put("EFF_DESC",   p.effectiveDate() != null ? "As at " + p.effectiveDate() : "Latest");
        params.put("ROW_COUNT",  rows.size());
        return result(rows, params);
    }

    // ── shared Condition builders ─────────────────────────────────────────────

    /** smtrans filters: location, item range, date range, transaction kind. */
    private Condition applyTransFilters(Condition where, String locNo,
                                        String startItem, String endItem,
                                        LocalDate startDate, LocalDate endDate, String kind) {
        if (notBlank(locNo)) where = where.and(SMTRANS.LOC_NO.eq(locNo));
        where = applyRange(where, SMTRANS.STOCK_CODE, startItem, endItem);
        where = applyDate(where, SMTRANS.MOVE_DATE, startDate, endDate);
        where = applyKind(where, kind);
        return where;
    }

    /** smstloc filters: location, item range, product-type range (prod-type via smsthed join). */
    private Condition applyStockFilters(Condition where, String locNo,
                                        String startItem, String endItem,
                                        String startProdType, String endProdType) {
        if (notBlank(locNo)) where = where.and(SMSTLOC.LOC_NO.eq(locNo));
        where = applyRange(where, SMSTLOC.STOCK_CODE, startItem, endItem);
        where = applyRange(where, SMSTHED.PRODUCT_TYPE, startProdType, endProdType);
        return where;
    }

    private <T extends Comparable<T>> Condition applyRange(
            Condition where, Field<String> col, String start, String end) {
        if (notBlank(start)) {
            String e = notBlank(end) ? end : "zzzzzzzzzzzzzzzzzzzzzzzzz";
            where = where.and(col.between(start, e));
        } else if (notBlank(end)) {
            where = where.and(col.le(end));
        }
        return where;
    }

    private Condition applyDate(Condition where, Field<LocalDate> col, LocalDate start, LocalDate end) {
        if (start != null) {
            LocalDate e = end != null ? end : LocalDate.of(9999, 12, 31);
            where = where.and(col.between(start, e));
        } else if (end != null) {
            where = where.and(col.le(end));
        }
        return where;
    }

    /** Transaction-kind filter on system_id + move_ind. */
    private Condition applyKind(Condition where, String kind) {
        if (kind == null || kind.isBlank()) return where;
        return switch (kind) {
            case "SALES"   -> where.and(SMTRANS.SYSTEM_ID.eq("AR").and(SMTRANS.MOVE_IND.eq("S")));
            case "CREDITS" -> where.and(SMTRANS.SYSTEM_ID.eq("AR").and(SMTRANS.MOVE_IND.eq("C")));
            case "AR"      -> where.and(SMTRANS.SYSTEM_ID.eq("AR"));
            case "PURCH"   -> where.and(SMTRANS.SYSTEM_ID.eq("AP").and(SMTRANS.MOVE_IND.eq("R")));
            case "ADJ"     -> where.and(SMTRANS.SYSTEM_ID.eq("SM").and(SMTRANS.MOVE_IND.eq("A")));
            case "TFR"     -> where.and(SMTRANS.SYSTEM_ID.eq("SM").and(SMTRANS.MOVE_IND.eq("T")));
            default        -> where;   // unknown kind → no additional filter
        };
    }

    private Map<String, Object> movementParams(MovementParams p) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC",    notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE",  rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("PTYPE_RANGE", rangeDesc(p.startProdType(), p.endProdType(), "product types"));
        params.put("DATE_RANGE",  dateDesc(p.startDate(), p.endDate()));
        params.put("KIND_DESC",   kindDesc(p.kind()));
        return params;
    }

    private Map<String, Object> stockParams(StockParams p) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC",    notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE",  rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("PTYPE_RANGE", rangeDesc(p.startProdType(), p.endProdType(), "product types"));
        return params;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static SmSequence parseSeq(String s, SmSequence dflt) {
        if (s == null || s.isBlank()) return dflt;
        try { return SmSequence.valueOf(s.trim().toUpperCase()); } catch (Exception e) { return dflt; }
    }

    /** Human label for a smtrans (system_id, move_ind) pair. */
    static String kindLabel(String systemId, String moveInd) {
        String sys = trim(systemId), mv = trim(moveInd);
        return switch (sys + "/" + mv) {
            case "AR/S" -> "Sale"; case "AR/C" -> "Credit"; case "AP/R" -> "Receipt"; case "AP/C" -> "Purch credit";
            case "SM/A" -> "Adjustment"; case "SM/T" -> "Transfer";
            default -> (sys + " " + mv).trim();
        };
    }

    static String kindDesc(String kind) {
        if (kind == null || kind.isBlank()) return "All movements";
        return switch (kind) {
            case "SALES" -> "Sales"; case "CREDITS" -> "Credit notes"; case "AR" -> "Sales + credits";
            case "PURCH" -> "Purchase receipts"; case "ADJ" -> "Inventory adjustments"; case "TFR" -> "Transfers";
            default -> kind;
        };
    }

    private static String rangeDesc(String start, String end, String noun) {
        if (notBlank(start)) return start + " to " + (notBlank(end) ? end : "end");
        if (notBlank(end)) return "up to " + end;
        return "All " + noun;
    }

    private static String dateDesc(LocalDate start, LocalDate end) {
        if (start != null) return start + " to " + (end != null ? end : "…");
        if (end != null) return "up to " + end;
        return "All dates";
    }

    private Map<String, Object> result(List<Map<String, Object>> rows, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows); m.put("params", params); m.put("rowCount", rows.size());
        return m;
    }

    /**
     * Converts a jOOQ-returned {@link LocalDate} to a {@link java.sql.Date} for Jasper,
     * returning {@code null} for sentinel dates on or before 1900-01-01.
     */
    static java.sql.Date ldToSqlDate(LocalDate d) {
        if (d == null) return null;
        return d.isAfter(LocalDate.of(1900, 1, 1)) ? java.sql.Date.valueOf(d) : null;
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
}
