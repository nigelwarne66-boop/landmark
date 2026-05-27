package com.landmarksoftware.service.po;

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
 * Purchasing (PO) <b>report</b> data service — one query method per PO report card
 * in the JavaFX Reports Hub ({@code -Preporting} build).
 *
 * <p>All JDBC lives here. Ported from {@code C:\landmark\cobol\po2\potl*}, columns
 * verified against the live {@code lmextract} schema. Header in {@code popohed},
 * lines in {@code popolin}; supplier names from {@code apsupps}.
 */
@Service
public class PoReportDataService {

    private static final Logger log = LoggerFactory.getLogger(PoReportDataService.class);
    private final JdbcTemplate jdbc;

    public PoReportDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
            jdbc.query("SELECT DISTINCT loc_no FROM smlocat WHERE company_no=? ORDER BY loc_no",
                rs -> { String l = trim(rs.getString("loc_no")); if (!l.isEmpty()) list.add(new CodeName(l, l)); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getLocations: {}", e.getMessage()); }
        return list;
    }

    /** Suppliers, "(All)" first, "code — name" from apsupps. */
    public List<CodeName> getSuppliers(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All suppliers)"));
        try {
            jdbc.query("SELECT supplier_no, name_1 FROM apsupps WHERE company_no=? ORDER BY supplier_no",
                rs -> { list.add(new CodeName(trim(rs.getString("supplier_no")),
                                              trim(rs.getString("supplier_no")) + " — " + trim(rs.getString("name_1")))); },
                s.getCompanyNo());
        } catch (Exception e) { log.warn("getSuppliers: {}", e.getMessage()); }
        return list;
    }

    /** PO numbers, "(All)" first, from popohed (optionally scoped to a location). */
    public List<CodeName> getPoNumbers(AppSession s, String locNo) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All purchase orders)"));
        try {
            StringBuilder sql = new StringBuilder("SELECT DISTINCT po_no FROM popohed WHERE company_no=? ");
            List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
            if (notBlank(locNo)) { sql.append(" AND loc_no=? "); args.add(locNo); }
            sql.append(" ORDER BY po_no");
            jdbc.query(sql.toString(), rs -> { int n = rs.getInt("po_no"); list.add(new CodeName(String.valueOf(n), String.valueOf(n))); }, args.toArray());
        } catch (Exception e) { log.warn("getPoNumbers: {}", e.getMessage()); }
        return list;
    }

    /** Financial GL accounts, "(All)" first, "main-sub — desc" from glchart. */
    public List<CodeName> getGlAccounts(AppSession s) {
        List<CodeName> list = new ArrayList<>();
        list.add(new CodeName("", "(All accounts)"));
        try {
            jdbc.query("SELECT acct_main_no, acct_sub_no, desc1 FROM glchart WHERE company_no=? ORDER BY acct_main_no, acct_sub_no",
                rs -> { String code = rs.getInt("acct_main_no") + "-" + rs.getInt("acct_sub_no");
                        list.add(new CodeName(code, code + " — " + trim(rs.getString("desc1")))); },
                s.getCompanyNo());
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

    private static String orderByFor(PoSequence seq) {
        return switch (seq) {
            case SUPPLIER      -> "h.supplier_no, l.po_no, l.line_no";
            case ITEM          -> "l.stock_code, l.deliv_loc_no, l.po_no, l.line_no";
            case DELIVERY_DATE -> "l.next_deliv_date, l.po_no, l.line_no";
            case ORDER_DATE    -> "h.po_date, l.po_no, l.line_no";
            case GL_ACCOUNT    -> "l.gl_acct_main, l.gl_acct_sub, l.po_no, l.line_no";
            case COST_LEDGER   -> "l.ledger_type, l.ledger_code, l.po_no, l.line_no";
            case BA_LEDGER     -> "l.ba_ledger_id, l.ba_primary_code_1, l.po_no, l.line_no";
            default            -> "l.po_no, l.line_no";
        };
    }

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

        StringBuilder sql = new StringBuilder(
            "SELECT l.po_no, l.line_no, h.po_date, h.supplier_no, COALESCE(h.supplier_name_1,'') AS supplier_name, " +
            "       h.po_status, l.stock_code, l.desc_1, l.deliv_loc_no, l.next_deliv_date, l.line_type, " +
            "       l.gl_acct_main, l.gl_acct_sub, l.ledger_type, l.ledger_code, l.ba_ledger_id, " +
            "       l." + pfx + "_qty_ordered AS qty_ordered, l." + pfx + "_unit_cost AS unit_cost, " +
            "       l." + pfx + "_ext_amt AS ext_amt, l." + pfx + "_tax_amt AS tax_amt, " +
            "       l.recvd_qty, l.inv_qty " +
            "FROM popolin l " +
            "JOIN popohed h ON h.company_no=l.company_no AND h.loc_no=l.loc_no AND h.po_no=l.po_no " +
            "WHERE l.company_no=? AND l.line_type <> 'C' ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        if (notBlank(p.locNo()))      { sql.append(" AND l.loc_no=? "); args.add(p.locNo()); }
        if (p.startPoNo() > 0)        { int e = p.endPoNo() > 0 ? p.endPoNo() : 999999;
            sql.append(" AND l.po_no BETWEEN ? AND ? "); args.add(p.startPoNo()); args.add(e); }
        if (notBlank(p.startSupplier())) { String e = notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz";
            sql.append(" AND h.supplier_no BETWEEN ? AND ? "); args.add(p.startSupplier()); args.add(e); }
        if (p.startDate() != null)    { LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999,12,31);
            sql.append(" AND h.po_date BETWEEN ? AND ? "); args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(e)); }
        if (p.outstandingOnly() && !orig) sql.append(" AND (l.curr_qty_ordered - l.recvd_qty) > 0 ");
        sql.append(" ORDER BY ").append(orderByFor(seq));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal ext = z(rs.getBigDecimal("ext_amt")), tax = z(rs.getBigDecimal("tax_amt"));
                tot[0] = tot[0].add(ext); tot[1] = tot[1].add(tax);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("poNo", rs.getInt("po_no"));
                r.put("lineNo", rs.getInt("line_no"));
                r.put("poDate", sqlDate(rs.getDate("po_date")));
                r.put("supplierNo", trim(rs.getString("supplier_no")));
                r.put("supplierName", trim(rs.getString("supplier_name")));
                r.put("poStatus", poStatus(rs.getString("po_status")));
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("description", rs.getString("desc_1"));
                r.put("delivLoc", trim(rs.getString("deliv_loc_no")));
                r.put("delivDate", sqlDate(rs.getDate("next_deliv_date")));
                r.put("lineType", trim(rs.getString("line_type")));
                r.put("glAcct", rs.getInt("gl_acct_main") + "-" + rs.getInt("gl_acct_sub"));
                r.put("ledger", (trim(rs.getString("ledger_type")) + " " + trim(rs.getString("ledger_code"))).trim());
                r.put("baLedger", trim(rs.getString("ba_ledger_id")));
                r.put("qtyOrdered", z(rs.getBigDecimal("qty_ordered")));
                r.put("unitCost", z(rs.getBigDecimal("unit_cost")));
                r.put("extAmt", ext);
                r.put("taxAmt", tax);
                r.put("recvdQty", z(rs.getBigDecimal("recvd_qty")));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) {
            log.error("getPurchaseOrdersInSequence: {}", e.getMessage(), e);
            return warn("Query failed: " + e.getMessage());
        }
        if (rows.isEmpty()) return warn("No purchase order lines matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SEQUENCE_DESC", seqDesc(seq));
        params.put("LOC_DESC", notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("PO_RANGE", p.startPoNo() > 0 ? p.startPoNo() + " to " + (p.endPoNo() > 0 ? p.endPoNo() : "end") : "All purchase orders");
        params.put("SUPP_RANGE", notBlank(p.startSupplier()) ? p.startSupplier() + " to " + (notBlank(p.endSupplier()) ? p.endSupplier() : "end") : "All suppliers");
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : "All order dates");
        params.put("AMT_BASIS", orig ? "Original order" : "Current order");
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
        StringBuilder sql = new StringBuilder(
            "SELECT po_no, po_date, supplier_no, COALESCE(supplier_name_1,'') AS supplier_name, po_status, " +
            "       po_value, po_tax_value, inv_value, inv_tax_value, recvd_value, recvd_tax_value " +
            "FROM popohed WHERE company_no=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        appendHeaderFilters(sql, args, p.locNo(), p.startPoNo(), p.endPoNo(), p.startSupplier(), p.endSupplier(),
                            p.startDate(), p.endDate(), p.poStatus());
        sql.append("S".equalsIgnoreCase(p.printSeq()) ? " ORDER BY supplier_no, po_no " : " ORDER BY po_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal ordered = z(rs.getBigDecimal("po_value")).add(z(rs.getBigDecimal("po_tax_value")));
                BigDecimal invoiced = z(rs.getBigDecimal("inv_value")).add(z(rs.getBigDecimal("inv_tax_value")));
                BigDecimal delivered = z(rs.getBigDecimal("recvd_value")).add(z(rs.getBigDecimal("recvd_tax_value")));
                BigDecimal outstanding = ordered.subtract(delivered);
                if (p.outstandingOnly() && outstanding.signum() == 0) return;
                tot[0]=tot[0].add(ordered); tot[1]=tot[1].add(invoiced); tot[2]=tot[2].add(delivered); tot[3]=tot[3].add(outstanding);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("poNo", rs.getInt("po_no")); r.put("poDate", sqlDate(rs.getDate("po_date")));
                r.put("supplierNo", trim(rs.getString("supplier_no"))); r.put("supplierName", trim(rs.getString("supplier_name")));
                r.put("poStatus", poStatus(rs.getString("po_status")));
                r.put("ordered", ordered); r.put("invoiced", invoiced); r.put("delivered", delivered); r.put("outstanding", outstanding);
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getPurchaseOrderSummary: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = headerParams(p.locNo(), p.startPoNo(), p.endPoNo(), p.startSupplier(), p.endSupplier(), p.startDate(), p.endDate(), p.poStatus());
        params.put("SEQ_DESC", "S".equalsIgnoreCase(p.printSeq()) ? "Supplier" : "Order number");
        params.put("SUM_ORDERED", tot[0]); params.put("SUM_INVOICED", tot[1]); params.put("SUM_DELIVERED", tot[2]); params.put("SUM_OUTSTANDING", tot[3]);
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
        StringBuilder sql = new StringBuilder(
            "SELECT l.po_no, l.line_no, h.po_date, h.supplier_no, COALESCE(h.supplier_name_1,'') AS supplier_name, " +
            "       h.po_status, l.stock_code, l.desc_1, l.line_type, l.next_deliv_date, " +
            "       l.curr_qty_ordered, l.recvd_qty, l.inv_qty, l.curr_unit_cost, l.curr_ext_amt, l.curr_tax_amt " +
            "FROM popolin l JOIN popohed h ON h.company_no=l.company_no AND h.loc_no=l.loc_no AND h.po_no=l.po_no " +
            "WHERE l.company_no=? AND l.line_type <> 'C' ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        appendLineFilters(sql, args, p.locNo(), p.startPoNo(), p.endPoNo(), p.startSupplier(), p.endSupplier(),
                          p.startDate(), p.endDate(), p.poStatus());
        if (p.outstandingOnly()) sql.append(" AND (l.curr_qty_ordered - l.recvd_qty) > 0 ");
        sql.append("S".equalsIgnoreCase(p.printSeq()) ? " ORDER BY h.supplier_no, l.po_no, l.line_no " : " ORDER BY l.po_no, l.line_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal ext = z(rs.getBigDecimal("curr_ext_amt")), tax = z(rs.getBigDecimal("curr_tax_amt"));
                tot[0]=tot[0].add(ext); tot[1]=tot[1].add(tax);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("poNo", rs.getInt("po_no")); r.put("lineNo", rs.getInt("line_no"));
                r.put("poDate", sqlDate(rs.getDate("po_date")));
                r.put("supplierNo", trim(rs.getString("supplier_no"))); r.put("supplierName", trim(rs.getString("supplier_name")));
                r.put("poStatus", poStatus(rs.getString("po_status")));
                r.put("stockCode", trim(rs.getString("stock_code"))); r.put("description", rs.getString("desc_1"));
                r.put("lineType", trim(rs.getString("line_type"))); r.put("delivDate", sqlDate(rs.getDate("next_deliv_date")));
                r.put("qtyOrdered", z(rs.getBigDecimal("curr_qty_ordered"))); r.put("recvdQty", z(rs.getBigDecimal("recvd_qty")));
                r.put("invQty", z(rs.getBigDecimal("inv_qty"))); r.put("unitCost", z(rs.getBigDecimal("curr_unit_cost")));
                r.put("extAmt", ext); r.put("taxAmt", tax);
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getPurchaseOrderDetail: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        Map<String, Object> params = headerParams(p.locNo(), p.startPoNo(), p.endPoNo(), p.startSupplier(), p.endSupplier(), p.startDate(), p.endDate(), p.poStatus());
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
        StringBuilder sql = new StringBuilder(
            "SELECT l.po_no, l.line_no, h.supplier_no, COALESCE(h.supplier_name_1,'') AS supplier_name, " +
            "       l.stock_code, l.desc_1, l.curr_ext_amt, l.recvd_ext_amt, l.inv_ext_amt, " +
            "       (SELECT COUNT(*) FROM apdocno a WHERE a.company_no=l.company_no AND a.po_loc_no=l.loc_no AND a.po_no=l.po_no AND a.po_line_no=l.line_no) AS doc_count, " +
            "       (SELECT COALESCE(SUM(a.po_value_matched),0) FROM apdocno a WHERE a.company_no=l.company_no AND a.po_loc_no=l.loc_no AND a.po_no=l.po_no AND a.po_line_no=l.line_no) AS matched_value " +
            "FROM popolin l JOIN popohed h ON h.company_no=l.company_no AND h.loc_no=l.loc_no AND h.po_no=l.po_no " +
            "WHERE l.company_no=? AND l.line_type <> 'C' ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        appendLineFilters(sql, args, p.locNo(), p.startPoNo(), p.endPoNo(), p.startSupplier(), p.endSupplier(), p.startDate(), p.endDate(), null);
        sql.append(" ORDER BY h.supplier_no, l.po_no, l.line_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal ordered = z(rs.getBigDecimal("curr_ext_amt")), delivered = z(rs.getBigDecimal("recvd_ext_amt")), invoiced = z(rs.getBigDecimal("inv_ext_amt"));
                BigDecimal outstanding = ordered.subtract(invoiced);
                if (p.exceptionsOnly() && delivered.compareTo(invoiced) == 0) return;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("poNo", rs.getInt("po_no")); r.put("lineNo", rs.getInt("line_no"));
                r.put("supplierNo", trim(rs.getString("supplier_no"))); r.put("supplierName", trim(rs.getString("supplier_name")));
                r.put("stockCode", trim(rs.getString("stock_code"))); r.put("description", rs.getString("desc_1"));
                r.put("ordered", ordered); r.put("delivered", delivered); r.put("invoiced", invoiced); r.put("outstanding", outstanding);
                r.put("docCount", rs.getInt("doc_count")); r.put("matchedValue", z(rs.getBigDecimal("matched_value")));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getPurchaseIndex: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No purchase order lines matched the selection.");
        Map<String, Object> params = headerParams(p.locNo(), p.startPoNo(), p.endPoNo(), p.startSupplier(), p.endSupplier(), p.startDate(), p.endDate(), null);
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
        StringBuilder sql = new StringBuilder(
            "SELECT l.po_no, l.line_no, h.supplier_no, COALESCE(h.supplier_name_1,'') AS supplier_name, l.stock_code, " +
            "       l.recvd_qty, l.inv_qty, l.recvd_ext_amt, l.recvd_tax_amt, l.inv_ext_amt, l.inv_tax_amt " +
            "FROM popolin l JOIN popohed h ON h.company_no=l.company_no AND h.loc_no=l.loc_no AND h.po_no=l.po_no " +
            "WHERE l.company_no=? AND l.line_type <> 'C' ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        appendLineFilters(sql, args, p.locNo(), p.startPoNo(), p.endPoNo(), p.startSupplier(), p.endSupplier(), null, null, null);
        sql.append("P".equalsIgnoreCase(p.printSeq()) ? " ORDER BY l.po_no, l.line_no " : " ORDER BY h.supplier_no, l.po_no, l.line_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal delivered = z(rs.getBigDecimal("recvd_ext_amt")).add(z(rs.getBigDecimal("recvd_tax_amt")));
                BigDecimal invoiced = z(rs.getBigDecimal("inv_ext_amt")).add(z(rs.getBigDecimal("inv_tax_amt")));
                BigDecimal variance = delivered.subtract(invoiced);
                if (p.excludeCompleted() && z(rs.getBigDecimal("recvd_qty")).compareTo(z(rs.getBigDecimal("inv_qty"))) == 0 && variance.signum() == 0) return;
                tot[0]=tot[0].add(delivered); tot[1]=tot[1].add(invoiced); tot[2]=tot[2].add(variance);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("poNo", rs.getInt("po_no")); r.put("lineNo", rs.getInt("line_no"));
                r.put("supplierNo", trim(rs.getString("supplier_no"))); r.put("supplierName", trim(rs.getString("supplier_name")));
                r.put("stockCode", trim(rs.getString("stock_code")));
                r.put("delivered", delivered); r.put("invoiced", invoiced); r.put("variance", variance);
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getDeliveryInvoiceVariance: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No purchase order lines matched the selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC", notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("SUPP_RANGE", notBlank(p.startSupplier()) ? p.startSupplier() + " to " + (notBlank(p.endSupplier()) ? p.endSupplier() : "end") : "All suppliers");
        params.put("SEQ_DESC", "P".equalsIgnoreCase(p.printSeq()) ? "Order number" : "Supplier");
        params.put("SUM_DELIVERED", tot[0]); params.put("SUM_INVOICED", tot[1]); params.put("SUM_VARIANCE", tot[2]); params.put("ROW_COUNT", rows.size());
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
        StringBuilder sql = new StringBuilder(
            "SELECT l.po_no, l.line_no, h.supplier_no, COALESCE(h.supplier_name_1,'') AS supplier_name, l.stock_code, l.desc_1, " +
            "       l.line_type, l.recvd_ext_amt, l.inv_ext_amt " +
            "FROM popolin l JOIN popohed h ON h.company_no=l.company_no AND h.loc_no=l.loc_no AND h.po_no=l.po_no " +
            "WHERE l.company_no=? AND l.line_type <> 'C' ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        if (p.sundriesOnly()) sql.append(" AND l.line_type <> 'I' ");   // sundry / non-inventory
        else                  sql.append(" AND l.line_type = 'I' ");    // stock goods
        appendLineFilters(sql, args, p.locNo(), p.startPoNo(), p.endPoNo(), p.startSupplier(), p.endSupplier(), null, p.asAtDate(), null);
        sql.append("P".equalsIgnoreCase(p.printSeq()) ? " ORDER BY l.po_no, l.line_no " : " ORDER BY h.supplier_no, l.po_no, l.line_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal delivered = z(rs.getBigDecimal("recvd_ext_amt")), invoiced = z(rs.getBigDecimal("inv_ext_amt"));
                BigDecimal outstanding = delivered.subtract(invoiced);
                if (outstanding.signum() == 0) return;   // only uninvoiced/over-invoiced lines
                tot[0]=tot[0].add(delivered); tot[1]=tot[1].add(invoiced); tot[2]=tot[2].add(outstanding);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("poNo", rs.getInt("po_no")); r.put("lineNo", rs.getInt("line_no"));
                r.put("supplierNo", trim(rs.getString("supplier_no"))); r.put("supplierName", trim(rs.getString("supplier_name")));
                r.put("stockCode", trim(rs.getString("stock_code"))); r.put("description", rs.getString("desc_1"));
                r.put("lineType", trim(rs.getString("line_type")));
                r.put("delivered", delivered); r.put("invoiced", invoiced); r.put("outstanding", outstanding);
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getUninvoicedGoods: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No uninvoiced " + (p.sundriesOnly() ? "sundry" : "goods") + " lines for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC", notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("AS_AT", p.asAtDate() != null ? p.asAtDate().toString() : "");
        params.put("SUPP_RANGE", notBlank(p.startSupplier()) ? p.startSupplier() + " to " + (notBlank(p.endSupplier()) ? p.endSupplier() : "end") : "All suppliers");
        params.put("KIND_DESC", p.sundriesOnly() ? "Sundries" : "Goods");
        params.put("SUM_DELIVERED", tot[0]); params.put("SUM_INVOICED", tot[1]); params.put("SUM_OUTSTANDING", tot[2]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // POTL39 — Sundries Reconcile  (AP documents, apdocno)
    // ════════════════════════════════════════════════════════════════════════

    public record SundriesReconParams(LocalDate asAtDate, String startSupplier, String endSupplier) {}

    /** POTL39 — AP purchase documents (apdocno): matched value + adjustments by supplier/document. */
    public Map<String, Object> getSundriesReconcile(AppSession s, SundriesReconParams p) {
        StringBuilder sql = new StringBuilder(
            "SELECT a.supplier_no, a.doc_no, a.doc_type, a.doc_date, a.posting_date, a.po_no, " +
            "       a.po_value_matched, a.adjust_value, a.doc_status " +
            "FROM apdocno a WHERE a.company_no=? ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        if (notBlank(p.startSupplier())) { String e = notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz";
            sql.append(" AND a.supplier_no BETWEEN ? AND ? "); args.add(p.startSupplier()); args.add(e); }
        if (p.asAtDate() != null) { sql.append(" AND a.doc_date <= ? "); args.add(Date.valueOf(p.asAtDate())); }
        sql.append(" ORDER BY a.supplier_no, a.doc_date, a.doc_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] tot = { BigDecimal.ZERO, BigDecimal.ZERO };
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal matched = z(rs.getBigDecimal("po_value_matched")), adjust = z(rs.getBigDecimal("adjust_value"));
                tot[0]=tot[0].add(matched); tot[1]=tot[1].add(adjust);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("supplierNo", trim(rs.getString("supplier_no")));
                r.put("docNo", trim(rs.getString("doc_no"))); r.put("docType", trim(rs.getString("doc_type")));
                r.put("docDate", sqlDate(rs.getDate("doc_date"))); r.put("postingDate", sqlDate(rs.getDate("posting_date")));
                r.put("poNo", rs.getInt("po_no"));
                r.put("matchedValue", matched); r.put("adjustValue", adjust);
                r.put("status", "U".equalsIgnoreCase(trim(rs.getString("doc_status"))) ? "Unposted" : "");
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getSundriesReconcile: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No purchase documents for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("AS_AT", p.asAtDate() != null ? p.asAtDate().toString() : "");
        params.put("SUPP_RANGE", notBlank(p.startSupplier()) ? p.startSupplier() + " to " + (notBlank(p.endSupplier()) ? p.endSupplier() : "end") : "All suppliers");
        params.put("SUM_MATCHED", tot[0]); params.put("SUM_ADJUST", tot[1]); params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ════════════════════════════════════════════════════════════════════════
    // POTL30 — Expedite Action  (overdue undelivered PO lines)
    // ════════════════════════════════════════════════════════════════════════

    public record ExpediteParams(String locNo, LocalDate startDate, LocalDate endDate, String startSupplier, String endSupplier) {}

    /** POTL30 — PO lines due in the window that are not yet fully received (expedite candidates). */
    public Map<String, Object> getExpediteAction(AppSession s, ExpediteParams p) {
        StringBuilder sql = new StringBuilder(
            "SELECT l.po_no, l.line_no, h.supplier_no, COALESCE(h.supplier_name_1,'') AS supplier_name, l.stock_code, l.desc_1, " +
            "       l.next_deliv_date, l.curr_qty_ordered, l.recvd_qty " +
            "FROM popolin l JOIN popohed h ON h.company_no=l.company_no AND h.loc_no=l.loc_no AND h.po_no=l.po_no " +
            "WHERE l.company_no=? AND l.line_type <> 'C' AND l.recvd_qty < l.curr_qty_ordered ");
        List<Object> args = new ArrayList<>(); args.add(s.getCompanyNo());
        if (notBlank(p.locNo())) { sql.append(" AND l.loc_no=? "); args.add(p.locNo()); }
        if (notBlank(p.startSupplier())) { String e = notBlank(p.endSupplier()) ? p.endSupplier() : "zzzzzzzzzz";
            sql.append(" AND h.supplier_no BETWEEN ? AND ? "); args.add(p.startSupplier()); args.add(e); }
        if (p.startDate() != null) { LocalDate e = p.endDate() != null ? p.endDate() : LocalDate.of(9999,12,31);
            sql.append(" AND l.next_deliv_date BETWEEN ? AND ? "); args.add(Date.valueOf(p.startDate())); args.add(Date.valueOf(e)); }
        else if (p.endDate() != null) { sql.append(" AND l.next_deliv_date <= ? "); args.add(Date.valueOf(p.endDate())); }
        sql.append(" ORDER BY l.next_deliv_date, h.supplier_no, l.po_no, l.line_no ");

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            jdbc.query(sql.toString(), rs -> {
                BigDecimal ordered = z(rs.getBigDecimal("curr_qty_ordered")), recvd = z(rs.getBigDecimal("recvd_qty"));
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("poNo", rs.getInt("po_no")); r.put("lineNo", rs.getInt("line_no"));
                r.put("supplierNo", trim(rs.getString("supplier_no"))); r.put("supplierName", trim(rs.getString("supplier_name")));
                r.put("stockCode", trim(rs.getString("stock_code"))); r.put("description", rs.getString("desc_1"));
                r.put("delivDate", sqlDate(rs.getDate("next_deliv_date")));
                r.put("qtyOrdered", ordered); r.put("recvdQty", recvd); r.put("qtyOutstanding", ordered.subtract(recvd));
                rows.add(r);
            }, args.toArray());
        } catch (Exception e) { log.error("getExpediteAction: {}", e.getMessage(), e); return warn("Query failed: " + e.getMessage()); }
        if (rows.isEmpty()) return warn("No overdue / undelivered purchase order lines for this selection.");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC", notBlank(p.locNo()) ? p.locNo() : "All locations");
        params.put("SUPP_RANGE", notBlank(p.startSupplier()) ? p.startSupplier() + " to " + (notBlank(p.endSupplier()) ? p.endSupplier() : "end") : "All suppliers");
        params.put("DATE_RANGE", p.startDate() != null ? p.startDate() + " to " + (p.endDate() != null ? p.endDate() : "…") : (p.endDate() != null ? "up to " + p.endDate() : "All dates"));
        params.put("ROW_COUNT", rows.size());
        return result(rows, params);
    }

    // ── shared filter builders ────────────────────────────────────────────────

    private void appendHeaderFilters(StringBuilder sql, List<Object> args, String locNo, int startPo, int endPo,
                                     String startSup, String endSup, LocalDate startDate, LocalDate endDate, String poStatus) {
        if (notBlank(locNo)) { sql.append(" AND loc_no=? "); args.add(locNo); }
        if (startPo > 0) { int e = endPo > 0 ? endPo : 999999; sql.append(" AND po_no BETWEEN ? AND ? "); args.add(startPo); args.add(e); }
        if (notBlank(startSup)) { String e = notBlank(endSup) ? endSup : "zzzzzzzzzz"; sql.append(" AND supplier_no BETWEEN ? AND ? "); args.add(startSup); args.add(e); }
        if (startDate != null) { LocalDate e = endDate != null ? endDate : LocalDate.of(9999,12,31); sql.append(" AND po_date BETWEEN ? AND ? "); args.add(Date.valueOf(startDate)); args.add(Date.valueOf(e)); }
        appendStatus(sql, "", poStatus);
    }

    private void appendLineFilters(StringBuilder sql, List<Object> args, String locNo, int startPo, int endPo,
                                   String startSup, String endSup, LocalDate startDate, LocalDate endDate, String poStatus) {
        if (notBlank(locNo)) { sql.append(" AND l.loc_no=? "); args.add(locNo); }
        if (startPo > 0) { int e = endPo > 0 ? endPo : 999999; sql.append(" AND l.po_no BETWEEN ? AND ? "); args.add(startPo); args.add(e); }
        if (notBlank(startSup)) { String e = notBlank(endSup) ? endSup : "zzzzzzzzzz"; sql.append(" AND h.supplier_no BETWEEN ? AND ? "); args.add(startSup); args.add(e); }
        if (startDate != null) { LocalDate e = endDate != null ? endDate : LocalDate.of(9999,12,31); sql.append(" AND h.po_date BETWEEN ? AND ? "); args.add(Date.valueOf(startDate)); args.add(Date.valueOf(e)); }
        else if (endDate != null) { sql.append(" AND h.po_date <= ? "); args.add(Date.valueOf(endDate)); }
        appendStatus(sql, "h.", poStatus);
    }

    /** PO status filter: blank = active (exclude U/C); U/C = that status; A/null = all. */
    private void appendStatus(StringBuilder sql, String pfx, String poStatus) {
        if (poStatus == null || "A".equalsIgnoreCase(poStatus.trim())) return;
        String st = poStatus.trim();
        if (st.isEmpty())        sql.append(" AND ").append(pfx).append("po_status NOT IN ('U','C') ");
        else                     sql.append(" AND ").append(pfx).append("po_status = '").append(st.replace("'", "")).append("' ");
    }

    private Map<String, Object> headerParams(String locNo, int startPo, int endPo, String startSup, String endSup,
                                             LocalDate startDate, LocalDate endDate, String poStatus) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("LOC_DESC", notBlank(locNo) ? locNo : "All locations");
        params.put("PO_RANGE", startPo > 0 ? startPo + " to " + (endPo > 0 ? endPo : "end") : "All purchase orders");
        params.put("SUPP_RANGE", notBlank(startSup) ? startSup + " to " + (notBlank(endSup) ? endSup : "end") : "All suppliers");
        params.put("DATE_RANGE", startDate != null ? startDate + " to " + (endDate != null ? endDate : "…") : "All order dates");
        params.put("STATUS_DESC", poStatus == null || poStatus.isBlank() ? "Active" : "A".equalsIgnoreCase(poStatus) ? "All" : "U".equalsIgnoreCase(poStatus) ? "Unconfirmed" : "C".equalsIgnoreCase(poStatus) ? "Cancelled" : poStatus);
        return params;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static String poStatus(String s) {
        return switch (trim(s)) {
            case "U" -> "Unconfirmed"; case "C" -> "Cancelled"; case "F" -> "Completed"; default -> "";
        };
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
