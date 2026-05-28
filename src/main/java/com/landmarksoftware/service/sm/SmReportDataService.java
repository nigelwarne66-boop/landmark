package com.landmarksoftware.service.sm;

import com.landmarksoftware.model.AppSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * Sales / Inventory Management (SM) <b>report</b> data service — one query method per
 * SM report card in the JavaFX Reports Hub ({@code -Preporting} build).
 *
 * <p>All JDBC lives here. Ported from {@code C:\landmark\cobol\sm2\smtl*}, columns
 * verified against the live {@code lmextract} schema.
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
    private final JdbcTemplate jdbc;

    public SmReportDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
            jdbc.query(
                "SELECT t.loc_no, MAX(l.name1) AS name1 FROM smtrans t " +
                "LEFT JOIN smlocat l ON l.company_no=t.company_no AND l.loc_no=t.loc_no " +
                "WHERE t.company_no=? AND t.loc_no IS NOT NULL AND t.loc_no<>'' " +
                "GROUP BY t.loc_no ORDER BY t.loc_no",
                rs -> {
                    String l = trim(rs.getString("loc_no"));
                    String n = trim(rs.getString("name1"));
                    list.add(new CodeName(l, n.isEmpty() ? l : l + " — " + n));
                }, s.getCompanyNo());
        } catch (Exception e) { log.warn("getLocations: {}", e.getMessage()); }
        return list;
    }

    /** Product types from smcodpt. (Empty in the current extract.) */
    public List<CodeName> getProductTypes(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All product types)"));
        try {
            jdbc.query("SELECT product_type_code, desc1 FROM smcodpt WHERE company_no=? ORDER BY product_type_code",
                rs -> { String c = trim(rs.getString("product_type_code"));
                        list.add(new CodeName(c, c + " — " + trim(rs.getString("desc1")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getProductTypes: {}", e.getMessage()); }
        return list;
    }

    /** Items — prefer the item master (smsthed); fall back to distinct codes in smtrans. */
    public List<CodeName> getItems(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All items)"));
        try {
            List<CodeName> master = new ArrayList<>();
            jdbc.query("SELECT stock_code, desc_1 FROM smsthed WHERE company_no=? ORDER BY stock_code",
                rs -> { String c = trim(rs.getString("stock_code"));
                        master.add(new CodeName(c, c + " — " + trim(rs.getString("desc_1")))); },
                s.getCompanyNo());
            if (master.isEmpty()) {
                jdbc.query("SELECT stock_code, MAX(desc_1) AS desc_1 FROM smtrans WHERE company_no=? " +
                           "AND stock_code IS NOT NULL AND stock_code<>'' GROUP BY stock_code ORDER BY stock_code",
                    rs -> { String c = trim(rs.getString("stock_code"));
                            String d = trim(rs.getString("desc_1"));
                            list.add(new CodeName(c, d.isEmpty() ? c : c + " — " + d)); },
                    s.getCompanyNo());
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
            jdbc.query("SELECT cust_no, name_1 FROM arcusts WHERE company_no=? ORDER BY cust_no",
                rs -> { String c = trim(rs.getString("cust_no"));
                        list.add(new CodeName(c, c + " — " + trim(rs.getString("name_1")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getCustomers: {}", e.getMessage()); }
        return list;
    }

    /** Suppliers from apsupps ("(All)" first, "code — name"). */
    public List<CodeName> getSuppliers(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All suppliers)"));
        try {
            jdbc.query("SELECT supplier_no, name_1 FROM apsupps WHERE company_no=? ORDER BY supplier_no",
                rs -> { String c = trim(rs.getString("supplier_no"));
                        list.add(new CodeName(c, c + " — " + trim(rs.getString("name_1")))); },
                s.getCompanyNo());
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
        StringBuilder sql = new StringBuilder(
            "SELECT t.loc_no, t.stock_code, t.desc_1, t.move_date, t.move_ind, t.in_out_direct_ind, " +
            "       t.system_id, t.doc_type, t.doc_no, t.ref, t.cust_supplier_no, " +
            "       t.qty, t.trx_unit_cost, t.cost_value, t.unit_price, t.sales_or_recpt_value " +
            "FROM smtrans t WHERE t.company_no=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        appendTransFilters(sql, args, p.locNo(), p.startItem(), p.endItem(), p.startDate(), p.endDate(), p.kind());
        sql.append(" ORDER BY t.loc_no, t.stock_code, t.move_date, t.move_time ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal cost = z(rs.getBigDecimal("cost_value")), sale = z(rs.getBigDecimal("sales_or_recpt_value"));
                tot[0] = tot[0].add(cost); tot[1] = tot[1].add(sale);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("locNo", trim(rs.getString("loc_no")));
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("moveDate", sqlDate(rs.getDate("move_date")));
                r.put("kind", kindLabel(rs.getString("system_id"), rs.getString("move_ind")));
                r.put("inOut", trim(rs.getString("in_out_direct_ind")));
                r.put("docType", trim(rs.getString("doc_type")));
                r.put("docNo", trim(rs.getString("doc_no")));
                r.put("ref", trim(rs.getString("ref")));
                r.put("partyNo", trim(rs.getString("cust_supplier_no")));
                r.put("qty", z(rs.getBigDecimal("qty")));
                r.put("unitCost", z(rs.getBigDecimal("trx_unit_cost")));
                r.put("costValue", cost);
                r.put("unitPrice", z(rs.getBigDecimal("unit_price")));
                r.put("saleValue", sale);
                rows.add(r);
            }, args.toArray());
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
        StringBuilder sql = new StringBuilder(
            "SELECT t.loc_no, t.stock_code, MAX(t.desc_1) AS desc_1, COUNT(*) AS move_count, " +
            "       SUM(CASE WHEN t.in_out_direct_ind='I' THEN t.qty ELSE 0 END) AS qty_in, " +
            "       SUM(CASE WHEN t.in_out_direct_ind='O' THEN t.qty ELSE 0 END) AS qty_out, " +
            "       SUM(t.cost_value) AS cost_value, SUM(t.sales_or_recpt_value) AS sale_value " +
            "FROM smtrans t WHERE t.company_no=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        appendTransFilters(sql, args, p.locNo(), p.startItem(), p.endItem(), p.startDate(), p.endDate(), p.kind());
        sql.append(" GROUP BY t.loc_no, t.stock_code ORDER BY t.loc_no, t.stock_code ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal in = z(rs.getBigDecimal("qty_in")), out = z(rs.getBigDecimal("qty_out"));
                BigDecimal cost = z(rs.getBigDecimal("cost_value")), sale = z(rs.getBigDecimal("sale_value"));
                tot[0] = tot[0].add(cost); tot[1] = tot[1].add(sale);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("locNo", trim(rs.getString("loc_no")));
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("moveCount", rs.getInt("move_count"));
                r.put("qtyIn", in); r.put("qtyOut", out); r.put("qtyNet", in.subtract(out));
                r.put("costValue", cost); r.put("saleValue", sale);
                rows.add(r);
            }, args.toArray());
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
        StringBuilder sql = new StringBuilder(
            "SELECT t.stock_code, MAX(t.desc_1) AS desc_1, t.cust_supplier_no, MAX(c.name_1) AS cust_name, " +
            "       SUM(-t.qty) AS qty, SUM(-t.sales_or_recpt_value) AS sales_value, SUM(-t.cost_value) AS cost_value, " +
            "       COUNT(*) AS line_count " +
            "FROM smtrans t LEFT JOIN arcusts c ON c.company_no=t.company_no AND c.cust_no=t.cust_supplier_no " +
            "WHERE t.company_no=? AND t.system_id='AR' AND t.move_ind IN ('S','C') ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        if (notBlank(p.locNo()))   { sql.append(" AND t.loc_no=? "); args.add(p.locNo()); }
        appendRange(sql, args, "t.stock_code", p.startItem(), p.endItem());
        appendRange(sql, args, "t.cust_supplier_no", p.startCustomer(), p.endCustomer());
        appendDate(sql, args, "t.move_date", p.startDate(), p.endDate());
        sql.append(" GROUP BY t.stock_code, t.cust_supplier_no ORDER BY t.stock_code, t.cust_supplier_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal sales = z(rs.getBigDecimal("sales_value")), cost = z(rs.getBigDecimal("cost_value"));
                tot[0] = tot[0].add(sales); tot[1] = tot[1].add(cost);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("customerNo", trim(rs.getString("cust_supplier_no")));
                r.put("customerName", trim(rs.getString("cust_name")));
                r.put("qty", z(rs.getBigDecimal("qty")));
                r.put("salesValue", sales);
                r.put("costValue", cost);
                r.put("margin", sales.subtract(cost));   // values already negated to positive; gross margin = sales − cost
                r.put("lineCount", rs.getInt("line_count"));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getSalesHistory: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No sales history matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC", notBlank(p.locNo()) ? p.locNo() : "All locations");
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
        StringBuilder sql = new StringBuilder(
            "SELECT t.cust_supplier_no, MAX(c.name_1) AS cust_name, t.stock_code, MAX(t.desc_1) AS desc_1, " +
            "       t.loc_no, t.move_date, t.system_id, t.move_ind, t.doc_type, t.doc_no, " +
            "       t.qty, t.sales_or_recpt_value, t.cost_value " +
            "FROM smtrans t LEFT JOIN arcusts c ON c.company_no=t.company_no AND c.cust_no=t.cust_supplier_no " +
            "WHERE t.company_no=? AND t.cust_supplier_no IS NOT NULL AND t.cust_supplier_no<>'' ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        if (notBlank(p.locNo())) { sql.append(" AND t.loc_no=? "); args.add(p.locNo()); }
        appendRange(sql, args, "t.cust_supplier_no", p.startCustomer(), p.endCustomer());
        appendRange(sql, args, "t.stock_code", p.startItem(), p.endItem());
        appendDate(sql, args, "t.move_date", p.startDate(), p.endDate());
        appendKind(sql, args, p.kind());
        sql.append(" GROUP BY t.cust_supplier_no, t.stock_code, t.loc_no, t.move_date, t.system_id, t.move_ind, t.doc_type, t.doc_no, " +
                   "t.qty, t.sales_or_recpt_value, t.cost_value " +
                   " ORDER BY t.cust_supplier_no, t.move_date, t.stock_code ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal sale = z(rs.getBigDecimal("sales_or_recpt_value")), cost = z(rs.getBigDecimal("cost_value"));
                tot[0] = tot[0].add(sale); tot[1] = tot[1].add(cost);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("customerNo", trim(rs.getString("cust_supplier_no")));
                r.put("customerName", trim(rs.getString("cust_name")));
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("locNo", trim(rs.getString("loc_no")));
                r.put("moveDate", sqlDate(rs.getDate("move_date")));
                r.put("kind", kindLabel(rs.getString("system_id"), rs.getString("move_ind")));
                r.put("docType", trim(rs.getString("doc_type")));
                r.put("docNo", trim(rs.getString("doc_no")));
                r.put("qty", z(rs.getBigDecimal("qty")));
                r.put("saleValue", sale);
                r.put("costValue", cost);
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getTransactionsByCustomer: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No customer transactions matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC", notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("CUST_RANGE", rangeDesc(p.startCustomer(), p.endCustomer(), "customers"));
        params.put("ITEM_RANGE", rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("DATE_RANGE", dateDesc(p.startDate(), p.endDate()));
        params.put("KIND_DESC", kindDesc(p.kind()));
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
        StringBuilder sql = new StringBuilder(
            "SELECT t.stock_code, MAX(t.desc_1) AS desc_1, t.cust_supplier_no, MAX(v.name_1) AS supp_name, " +
            "       t.loc_no, SUM(t.qty) AS qty, SUM(t.sales_or_recpt_value) AS recpt_value, " +
            "       SUM(t.cost_value) AS cost_value, COUNT(*) AS line_count " +
            "FROM smtrans t LEFT JOIN apsupps v ON v.company_no=t.company_no AND v.supplier_no=t.cust_supplier_no " +
            "WHERE t.company_no=? AND t.system_id='AP' AND t.move_ind='R' ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        if (notBlank(p.locNo())) { sql.append(" AND t.loc_no=? "); args.add(p.locNo()); }
        appendRange(sql, args, "t.stock_code", p.startItem(), p.endItem());
        appendRange(sql, args, "t.cust_supplier_no", p.startSupplier(), p.endSupplier());
        appendDate(sql, args, "t.move_date", p.startDate(), p.endDate());
        sql.append(" GROUP BY t.stock_code, t.cust_supplier_no, t.loc_no ORDER BY t.stock_code, t.cust_supplier_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal recpt = z(rs.getBigDecimal("recpt_value")), cost = z(rs.getBigDecimal("cost_value"));
                tot[0] = tot[0].add(recpt); tot[1] = tot[1].add(cost);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("supplierNo", trim(rs.getString("cust_supplier_no")));
                r.put("supplierName", trim(rs.getString("supp_name")));
                r.put("locNo", trim(rs.getString("loc_no")));
                r.put("qty", z(rs.getBigDecimal("qty")));
                r.put("recptValue", recpt);
                r.put("costValue", cost);
                r.put("lineCount", rs.getInt("line_count"));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getPurchaseAnalysis: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No purchase receipts matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC", notBlank(p.locNo()) ? p.locNo() : "All locations");
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
        StringBuilder sql = new StringBuilder(
            "SELECT sl.loc_no, sl.stock_code, h.desc_1, h.product_type, " +
            "       sl.qty_on_hand, sl.value_on_hand, sl.std_cost, sl.last_po_cost " +
            "FROM smstloc sl LEFT JOIN smsthed h ON h.company_no=sl.company_no AND h.stock_code=sl.stock_code " +
            "WHERE sl.company_no=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        appendStockFilters(sql, args, p.locNo(), p.startItem(), p.endItem(), p.startProdType(), p.endProdType());
        if (p.excludeZeroQty()) sql.append(" AND sl.qty_on_hand <> 0 ");
        sql.append(" ORDER BY sl.loc_no, sl.stock_code ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal val = z(rs.getBigDecimal("value_on_hand"));
                tot[0] = tot[0].add(val);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("locNo", trim(rs.getString("loc_no")));
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("productType", trim(rs.getString("product_type")));
                r.put("qtyOnHand", z(rs.getBigDecimal("qty_on_hand")));
                r.put("stdCost", z(rs.getBigDecimal("std_cost")));
                r.put("lastCost", z(rs.getBigDecimal("last_po_cost")));
                r.put("valueOnHand", val);
                rows.add(r);
            }, args.toArray());
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
        StringBuilder sql = new StringBuilder(
            "SELECT sl.loc_no, sl.stock_code, h.desc_1, " +
            "       sl.qty_on_hand, sl.qty_allocated, sl.qty_reserved, sl.qty_on_order, sl.qty_on_po, sl.qty_on_backord " +
            "FROM smstloc sl LEFT JOIN smsthed h ON h.company_no=sl.company_no AND h.stock_code=sl.stock_code " +
            "WHERE sl.company_no=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        appendStockFilters(sql, args, p.locNo(), p.startItem(), p.endItem(), p.startProdType(), p.endProdType());
        if (p.excludeZeroQty()) sql.append(" AND (sl.qty_on_hand <> 0 OR sl.qty_on_order <> 0 OR sl.qty_allocated <> 0) ");
        sql.append(" ORDER BY sl.stock_code, sl.loc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal onHand = z(rs.getBigDecimal("qty_on_hand")), alloc = z(rs.getBigDecimal("qty_allocated"));
                BigDecimal resv = z(rs.getBigDecimal("qty_reserved"));
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("locNo", trim(rs.getString("loc_no")));
                r.put("qtyOnHand", onHand);
                r.put("qtyAllocated", alloc);
                r.put("qtyReserved", resv);
                r.put("qtyOnOrder", z(rs.getBigDecimal("qty_on_order")));
                r.put("qtyOnPo", z(rs.getBigDecimal("qty_on_po")));
                r.put("qtyOnBackord", z(rs.getBigDecimal("qty_on_backord")));
                r.put("qtyAvailable", onHand.subtract(alloc).subtract(resv));
                rows.add(r);
            }, args.toArray());
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
        StringBuilder sql = new StringBuilder(
            "SELECT sl.loc_no, sl.stock_code, h.desc_1, sl.usual_supplier_no, sl.bin_no, " +
            "       sl.qty_on_hand, sl.qty_on_order, sl.qty_on_po, sl.qty_allocated, " +
            "       sl.min_qty_level, sl.max_qty_level, sl.last_po_cost " +
            "FROM smstloc sl LEFT JOIN smsthed h ON h.company_no=sl.company_no AND h.stock_code=sl.stock_code " +
            "WHERE sl.company_no=? " +
            "  AND (sl.qty_on_hand + sl.qty_on_order + sl.qty_on_po - sl.qty_allocated) < sl.min_qty_level ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        appendStockFilters(sql, args, p.locNo(), p.startItem(), p.endItem(), p.startProdType(), p.endProdType());
        sql.append(" ORDER BY ").append(switch (notBlank(p.sequence()) ? p.sequence() : "ITEM") {
            case "SUPPLIER" -> "sl.usual_supplier_no, sl.stock_code";
            case "BIN" -> "sl.bin_no, sl.stock_code";
            default -> "sl.stock_code, sl.loc_no";
        });

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal onHand = z(rs.getBigDecimal("qty_on_hand")), onOrder = z(rs.getBigDecimal("qty_on_order"));
                BigDecimal onPo = z(rs.getBigDecimal("qty_on_po")), alloc = z(rs.getBigDecimal("qty_allocated"));
                BigDecimal max = z(rs.getBigDecimal("max_qty_level"));
                BigDecimal avail = onHand.add(onOrder).add(onPo).subtract(alloc);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("locNo", trim(rs.getString("loc_no")));
                r.put("supplierNo", trim(rs.getString("usual_supplier_no")));
                r.put("binNo", trim(rs.getString("bin_no")));
                r.put("qtyOnHand", onHand);
                r.put("qtyOnOrder", onOrder.add(onPo));
                r.put("qtyAllocated", alloc);
                r.put("minLevel", z(rs.getBigDecimal("min_qty_level")));
                r.put("maxLevel", max);
                r.put("reorderQty", max.subtract(avail));
                r.put("lastCost", z(rs.getBigDecimal("last_po_cost")));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getReorderRequisitions: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No items below minimum — the stock master (smstloc) is not yet loaded in this extract.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC", notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE", rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("PTYPE_RANGE", rangeDesc(p.startProdType(), p.endProdType(), "product types"));
        params.put("SEQ_DESC", switch (notBlank(p.sequence()) ? p.sequence() : "ITEM") {
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
        String dateCol = p.useSaleDate() ? "sl.last_sale_date" : "sl.last_move_date";
        StringBuilder sql = new StringBuilder(
            "SELECT sl.loc_no, sl.stock_code, h.desc_1, sl.qty_on_hand, sl.value_on_hand, " +
            "       sl.last_move_date, sl.last_sale_date " +
            "FROM smstloc sl LEFT JOIN smsthed h ON h.company_no=sl.company_no AND h.stock_code=sl.stock_code " +
            "WHERE sl.company_no=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        appendStockFilters(sql, args, p.locNo(), p.startItem(), p.endItem(), p.startProdType(), p.endProdType());
        if (p.inactiveSince() != null) { sql.append(" AND (").append(dateCol).append(" IS NULL OR ").append(dateCol).append(" < ?) "); args.add(Date.valueOf(p.inactiveSince())); }
        sql.append(" ORDER BY sl.loc_no, sl.stock_code ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal val = z(rs.getBigDecimal("value_on_hand"));
                tot[0] = tot[0].add(val);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("locNo", trim(rs.getString("loc_no")));
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("qtyOnHand", z(rs.getBigDecimal("qty_on_hand")));
                r.put("valueOnHand", val);
                r.put("lastMoveDate", sqlDate(rs.getDate("last_move_date")));
                r.put("lastSaleDate", sqlDate(rs.getDate("last_sale_date")));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getInactiveInventory: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No inactive items — the stock master (smstloc) is not yet loaded in this extract.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC", notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE", rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("PTYPE_RANGE", rangeDesc(p.startProdType(), p.endProdType(), "product types"));
        params.put("SINCE_DESC", p.inactiveSince() != null ? "Inactive since " + p.inactiveSince() : "All items");
        params.put("BASIS_DESC", p.useSaleDate() ? "Last sale date" : "Last movement date");
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
        StringBuilder sql = new StringBuilder(
            "SELECT sl.loc_no, sl.stock_code, h.desc_1, h.product_type, sl.item_status, " +
            "       sl.qty_on_hand, sl.value_on_hand, sl.qty_allocated, sl.qty_on_order, " +
            "       sl.std_cost, sl.last_sale_date, sl.last_move_date " +
            "FROM smstloc sl LEFT JOIN smsthed h ON h.company_no=sl.company_no AND h.stock_code=sl.stock_code " +
            "WHERE sl.company_no=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        appendStockFilters(sql, args, p.locNo(), p.startItem(), p.endItem(), p.startProdType(), p.endProdType());
        sql.append(" ORDER BY ").append(seq == SmSequence.LOCATION
            ? "sl.loc_no, h.product_type, sl.stock_code"
            : "h.product_type, sl.stock_code, sl.loc_no");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal val = z(rs.getBigDecimal("value_on_hand"));
                tot[0] = tot[0].add(val);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("locNo", trim(rs.getString("loc_no")));
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("productType", trim(rs.getString("product_type")));
                r.put("itemStatus", trim(rs.getString("item_status")));
                r.put("qtyOnHand", z(rs.getBigDecimal("qty_on_hand")));
                r.put("qtyAllocated", z(rs.getBigDecimal("qty_allocated")));
                r.put("qtyOnOrder", z(rs.getBigDecimal("qty_on_order")));
                r.put("stdCost", z(rs.getBigDecimal("std_cost")));
                r.put("valueOnHand", val);
                r.put("lastSaleDate", sqlDate(rs.getDate("last_sale_date")));
                r.put("lastMoveDate", sqlDate(rs.getDate("last_move_date")));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getItemStatus: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No item-status rows — the stock master (smstloc) is not yet loaded in this extract.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SEQUENCE_DESC", seq == SmSequence.LOCATION ? "Location" : "Item");
        params.put("LOC_DESC", notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE", rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("PTYPE_RANGE", rangeDesc(p.startProdType(), p.endProdType(), "product types"));
        params.put("REPORT_DATE", p.reportDate() != null ? p.reportDate().toString() : LocalDate.now().toString());
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
        StringBuilder sql = new StringBuilder(
            "SELECT sl.loc_no, sl.stock_code, h.desc_1, sl.qty_on_consign, sl.value_on_hand, sl.std_cost " +
            "FROM smstloc sl LEFT JOIN smsthed h ON h.company_no=sl.company_no AND h.stock_code=sl.stock_code " +
            "WHERE sl.company_no=? AND (sl.loc_consignment_flag='Y' OR sl.qty_on_consign <> 0) ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        appendStockFilters(sql, args, p.locNo(), p.startItem(), p.endItem(), p.startProdType(), p.endProdType());
        sql.append(" ORDER BY sl.stock_code, sl.loc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal qty = z(rs.getBigDecimal("qty_on_consign")), cost = z(rs.getBigDecimal("std_cost"));
                BigDecimal val = qty.multiply(cost);
                tot[0] = tot[0].add(val);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("locNo", trim(rs.getString("loc_no")));
                r.put("qtyOnConsign", qty);
                r.put("stdCost", cost);
                r.put("consignValue", val);
                rows.add(r);
            }, args.toArray());
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
        StringBuilder sql = new StringBuilder(
            "SELECT t.cust_supplier_no, MAX(c.name_1) AS cust_name, t.stock_code, MAX(t.desc_1) AS desc_1, " +
            "       t.loc_no, SUM(t.qty) AS qty, SUM(t.cost_value) AS cost_value " +
            "FROM smtrans t LEFT JOIN arcusts c ON c.company_no=t.company_no AND c.cust_no=t.cust_supplier_no " +
            "WHERE t.company_no=? AND t.consignment_flag='Y' ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        appendRange(sql, args, "t.cust_supplier_no", p.startCustomer(), p.endCustomer());
        appendRange(sql, args, "t.stock_code", p.startItem(), p.endItem());
        sql.append(" GROUP BY t.cust_supplier_no, t.stock_code, t.loc_no ");
        sql.append(" ORDER BY ").append(seq == SmSequence.ITEM
            ? "t.stock_code, t.cust_supplier_no"
            : "t.cust_supplier_no, t.stock_code");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal cost = z(rs.getBigDecimal("cost_value"));
                tot[0] = tot[0].add(cost);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("customerNo", trim(rs.getString("cust_supplier_no")));
                r.put("customerName", trim(rs.getString("cust_name")));
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("locNo", trim(rs.getString("loc_no")));
                r.put("qty", z(rs.getBigDecimal("qty")));
                r.put("costValue", cost);
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getConsignmentStock: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No consignment movements — the consignment order tables (opordhd/opordln) are not in the extract and smtrans holds no consignment-flagged rows.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SEQUENCE_DESC", seq == SmSequence.ITEM ? "Item" : "Customer");
        params.put("CUST_RANGE", rangeDesc(p.startCustomer(), p.endCustomer(), "customers"));
        params.put("ITEM_RANGE", rangeDesc(p.startItem(), p.endItem(), "items"));
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
        StringBuilder sql = new StringBuilder(
            "SELECT e.loc_no, e.stock_code, h.desc_1, h.product_type, " +
            "       e.recommended_price, e.wholesale_price, e.effective_date, e.for_curr_code " +
            "FROM smsteff e LEFT JOIN smsthed h ON h.company_no=e.company_no AND h.stock_code=e.stock_code " +
            "WHERE e.company_no=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        if (notBlank(p.locNo())) { sql.append(" AND e.loc_no=? "); args.add(p.locNo()); }
        appendRange(sql, args, "e.stock_code", p.startItem(), p.endItem());
        appendRange(sql, args, "h.product_type", p.startProdType(), p.endProdType());
        if (p.effectiveDate() != null) { sql.append(" AND e.effective_date <= ? "); args.add(Date.valueOf(p.effectiveDate())); }
        sql.append(" ORDER BY e.stock_code, e.loc_no, e.effective_date ");

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            jdbc.query(sql.toString(), rs -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("productType", trim(rs.getString("product_type")));
                r.put("locNo", trim(rs.getString("loc_no")));
                r.put("currency", trim(rs.getString("for_curr_code")));
                r.put("effectiveDate", sqlDate(rs.getDate("effective_date")));
                r.put("recommendedPrice", z(rs.getBigDecimal("recommended_price")));
                r.put("wholesalePrice", z(rs.getBigDecimal("wholesale_price")));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getPriceList: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No prices — the price-effective table (smsteff) is not yet loaded in this extract.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC", notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE", rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("PTYPE_RANGE", rangeDesc(p.startProdType(), p.endProdType(), "product types"));
        params.put("EFF_DESC", p.effectiveDate() != null ? "As at " + p.effectiveDate() : "Latest");
        params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ── shared filter builders ────────────────────────────────────────────────

    /** smtrans filters: location, item range, date range, transaction kind. */
    private void appendTransFilters(StringBuilder sql, List<Object> args, String locNo,
                                    String startItem, String endItem, LocalDate startDate, LocalDate endDate, String kind) {
        if (notBlank(locNo)) { sql.append(" AND t.loc_no=? "); args.add(locNo); }
        appendRange(sql, args, "t.stock_code", startItem, endItem);
        appendDate(sql, args, "t.move_date", startDate, endDate);
        appendKind(sql, args, kind);
    }

    /** smstloc filters: location, item range, product-type range (prod-type via smsthed join). */
    private void appendStockFilters(StringBuilder sql, List<Object> args, String locNo,
                                    String startItem, String endItem, String startProdType, String endProdType) {
        if (notBlank(locNo)) { sql.append(" AND sl.loc_no=? "); args.add(locNo); }
        appendRange(sql, args, "sl.stock_code", startItem, endItem);
        appendRange(sql, args, "h.product_type", startProdType, endProdType);
    }

    private void appendRange(StringBuilder sql, List<Object> args, String col, String start, String end) {
        if (notBlank(start)) { String e = notBlank(end) ? end : "zzzzzzzzzzzzzzzzzzzzzzzzz";
            sql.append(" AND ").append(col).append(" BETWEEN ? AND ? "); args.add(start); args.add(e); }
        else if (notBlank(end)) { sql.append(" AND ").append(col).append(" <= ? "); args.add(end); }
    }

    private void appendDate(StringBuilder sql, List<Object> args, String col, LocalDate start, LocalDate end) {
        if (start != null) { LocalDate e = end != null ? end : LocalDate.of(9999,12,31);
            sql.append(" AND ").append(col).append(" BETWEEN ? AND ? "); args.add(Date.valueOf(start)); args.add(Date.valueOf(e)); }
        else if (end != null) { sql.append(" AND ").append(col).append(" <= ? "); args.add(Date.valueOf(end)); }
    }

    /** Transaction-kind filter on system_id + move_ind. */
    private void appendKind(StringBuilder sql, List<Object> args, String kind) {
        if (kind == null || kind.isBlank()) return;
        switch (kind) {
            case "SALES"   -> sql.append(" AND t.system_id='AR' AND t.move_ind='S' ");
            case "CREDITS" -> sql.append(" AND t.system_id='AR' AND t.move_ind='C' ");
            case "AR"      -> sql.append(" AND t.system_id='AR' ");
            case "PURCH"   -> sql.append(" AND t.system_id='AP' AND t.move_ind='R' ");
            case "ADJ"     -> sql.append(" AND t.system_id='SM' AND t.move_ind='A' ");
            case "TFR"     -> sql.append(" AND t.system_id='SM' AND t.move_ind='T' ");
            default -> { /* unknown → no filter */ }
        }
    }

    private Map<String, Object> movementParams(MovementParams p) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC", notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE", rangeDesc(p.startItem(), p.endItem(), "items"));
        params.put("PTYPE_RANGE", rangeDesc(p.startProdType(), p.endProdType(), "product types"));
        params.put("DATE_RANGE", dateDesc(p.startDate(), p.endDate()));
        params.put("KIND_DESC", kindDesc(p.kind()));
        return params;
    }

    private Map<String, Object> stockParams(StockParams p) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC", notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("ITEM_RANGE", rangeDesc(p.startItem(), p.endItem(), "items"));
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

    static java.sql.Date sqlDate(java.sql.Date d) {
        if (d == null) return null;
        return d.toLocalDate().isAfter(LocalDate.of(1900, 1, 1)) ? d : null;
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
