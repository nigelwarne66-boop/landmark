package com.example.fixedassets.service;

import com.example.fixedassets.model.AcquiredRetiredRequest;
import com.example.fixedassets.model.AcquiredRetiredRow;
import com.example.fixedassets.model.AcquiredRetiredRow.PooledTrxLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * FATL03 — Assets Acquired and Retired service.
 *
 * Replicates the COBOL logic exactly:
 *
 * NON-POOLED assets (pool_flag != 'Y'):
 *   Selection is by acqn_date (A mode) or retmt_date (R mode) in range.
 *   Per asset: compute book/tax WDV and disposal profit/loss.
 *   Accumulators: A32 (book cost), A30 (accum book depn), A31 (book WDV),
 *                 A60 (proceeds), A61 (book P/L),
 *                 A42 (tax cost), A40 (accum tax depn), A41 (tax WDV), A62 (tax P/L).
 *
 * POOLED assets (pool_flag = 'Y'):
 *   Always selected when include-pooled = Y.
 *   ACCUM-NEXT-TRX: accumulates AQ/BD/TD/RT totals from FATRANS in date range.
 *   PRINT-NEXT-TRX: reads individual AQ (A mode) or RT (R mode) transactions
 *                   for DETAIL-LINE-16/17.
 *
 * Totals output:
 *   BOOK line: A10 (assets), A32 (cost), A30 (accum depn), A31 (WDV), A60 (proceeds), A61 (P/L)
 *   TAX  line:               A42 (cost), A40 (accum depn), A41 (WDV),                 A62 (P/L)
 */
@Service
public class AcquiredRetiredService {

    private final JdbcTemplate jdbc;

    public AcquiredRetiredService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Output container ──────────────────────────────────────────────

    public record ReportTotals(
        int        assetCount,
        // BOOK accumulators
        BigDecimal totalBookCost,        // A32
        BigDecimal totalAccumBookDepn,   // A30
        BigDecimal totalBookWdv,         // A31
        BigDecimal totalProceeds,        // A60
        BigDecimal totalBookProfit,      // A61
        // TAX accumulators
        BigDecimal totalTaxCost,         // A42
        BigDecimal totalAccumTaxDepn,    // A40
        BigDecimal totalTaxWdv,          // A41
        BigDecimal totalTaxProfit        // A62
    ) {}

    public record AcquiredRetiredOutput(
        AcquiredRetiredRequest request,
        List<AcquiredRetiredRow> rows,
        ReportTotals totals
    ) {}

    // ── Entry point ───────────────────────────────────────────────────

    public AcquiredRetiredOutput run(AcquiredRetiredRequest req) {
        List<AcquiredRetiredRow> rows = selectAssets(req);

        // Accumulate grand totals
        BigDecimal a32 = BigDecimal.ZERO, a30 = BigDecimal.ZERO,
                   a31 = BigDecimal.ZERO, a60 = BigDecimal.ZERO, a61 = BigDecimal.ZERO;
        BigDecimal a42 = BigDecimal.ZERO, a40 = BigDecimal.ZERO,
                   a41 = BigDecimal.ZERO, a62 = BigDecimal.ZERO;
        int        a10 = 0;

        for (AcquiredRetiredRow r : rows) {
            a10++;
            if (r.isPooledAsset()) {
                // Pooled uses WS-ASSET-DATA totals — mirrors DETAIL-LINE-10/11 accumulators
                a32 = a32.add(r.getTotalBookDepnCost());
                a30 = a30.add(r.getTotalAccumBookDepn());
                a31 = a31.add(r.getBookDepnCost());       // FAASSET-BOOK-DEPN-COST used as WDV col
                a60 = a60.add(r.getTotalRetmtProceeds());
                a61 = a61.add(r.getBookDisposalProfitLoss());
                a42 = a42.add(r.getTotalTaxDepnCost());
                a40 = a40.add(r.getTotalAccumTaxDepn());
                a41 = a41.add(r.getTaxDepnCost());        // FAASSET-TAX-DEPN-COST
                a62 = a62.add(r.getTaxDisposalProfitLoss());
            } else {
                a32 = a32.add(r.getBookDepnCost());
                a30 = a30.add(r.totalAccumBookDepnForDisplay());
                a31 = a31.add(r.getBookWrittenDownVal());
                a60 = a60.add(r.getRetmtProceedsVal());
                a61 = a61.add(r.getBookDisposalProfitLoss());
                a42 = a42.add(r.getTaxDepnCost());
                a40 = a40.add(r.getAccumTaxDepn().add(r.getAccumTaxDepnAdj()));
                a41 = a41.add(r.getTaxWrittenDownVal());
                a62 = a62.add(r.getTaxDisposalProfitLoss());
            }
        }

        ReportTotals totals = new ReportTotals(a10, a32, a30, a31, a60, a61, a42, a40, a41, a62);
        return new AcquiredRetiredOutput(req, rows, totals);
    }

    // ── Asset selection ───────────────────────────────────────────────

    /**
     * Mirrors COBOL TEST-RECORD-SELECTION-03.
     *
     * The A/R logic is complex:
     *
     * Acquisitions (A):
     *   status != 'U'
     *   AND ((pool_flag != 'Y' AND acqn_date IN range) OR pool_flag = 'Y')
     *
     * Retirements (R):
     *   (retmt_date > 0 AND pool_flag != 'Y' AND retmt_date IN range)
     *   OR pool_flag = 'Y'
     */
    private List<AcquiredRetiredRow> selectAssets(AcquiredRetiredRequest req) {
        StringBuilder sql = new StringBuilder("""
            SELECT asset_no, desc_1, desc_2, loc_code, dept_code, grp_code, subgrp_code,
                   asset_status, asset_pool_flag, leased_asset_flag,
                   acqn_date, retmt_date,
                   book_depn_cost, last_reval_val,
                   accum_book_depn, accum_book_depn_adj, last_book_depn_date,
                   tax_depn_cost, last_tax_reval_val,
                   accum_tax_depn, accum_tax_depn_adj, last_tax_depn_date,
                   retmt_proceeds_val
            FROM FAASSET
            WHERE company_no = ?
              AND asset_no >= ?
              AND asset_no <= ?
            """);

        List<Object> params = new ArrayList<>();
        params.add(req.getCompanyNo());
        params.add(req.getStartAssetNo().isBlank() ? "" : req.getStartAssetNo());
        params.add(req.effectiveEndAssetNo());

        // Location filter
        if (!req.getLocCode().isBlank()) {
            sql.append("  AND loc_code = ?\n");
            params.add(req.getLocCode());
        }
        // Group filter
        if (!req.getGrpCode().isBlank()) {
            sql.append("  AND grp_code = ?\n");
            params.add(req.getGrpCode());
        }
        // Sub-group filter
        if (!req.getSubgrpCode().isBlank()) {
            sql.append("  AND subgrp_code = ?\n");
            params.add(req.getSubgrpCode());
        }
        // Department filter
        if (!req.getDeptCode().isBlank()) {
            sql.append("  AND dept_code = ?\n");
            params.add(req.getDeptCode());
        }

        // Leased filter: COBOL = leased_flag='N' OR (flag='Y' AND include='Y')
        if (!req.isIncludeLeased()) {
            sql.append("  AND (leased_asset_flag = 'N' OR leased_asset_flag IS NULL)\n");
        }

        // Pooled filter: (pool='Y' AND include-pooled='Y') OR pool!='Y'
        if (!req.isIncludePooled()) {
            sql.append("  AND (asset_pool_flag IS NULL OR asset_pool_flag != 'Y')\n");
        }

        // A/R mode selection — the most complex part
        if (req.isAcquisitions()) {
            // status != 'U' AND (non-pool with acqn_date in range OR pooled)
            sql.append("  AND asset_status != 'U'\n");
            sql.append("  AND (\n");
            sql.append("    (asset_pool_flag != 'Y'\n");
            if (req.getStartDate() != null) {
                sql.append("      AND acqn_date >= ?\n");
                params.add(java.sql.Date.valueOf(req.getStartDate()));
            }
            if (req.getEndDate() != null) {
                sql.append("      AND acqn_date <= ?\n");
                params.add(java.sql.Date.valueOf(req.getEndDate()));
            }
            sql.append("      AND acqn_date > '1900-01-01')\n");
            if (req.isIncludePooled()) {
                sql.append("    OR asset_pool_flag = 'Y'\n");
            }
            sql.append("  )\n");
        } else {
            // Retirements: retmt_date in range OR pooled
            sql.append("  AND (\n");
            sql.append("    (asset_pool_flag != 'Y'\n");
            sql.append("      AND retmt_date IS NOT NULL\n");
            sql.append("      AND retmt_date > '1900-01-01'\n");
            if (req.getStartDate() != null) {
                sql.append("      AND retmt_date >= ?\n");
                params.add(java.sql.Date.valueOf(req.getStartDate()));
            }
            if (req.getEndDate() != null) {
                sql.append("      AND retmt_date <= ?\n");
                params.add(java.sql.Date.valueOf(req.getEndDate()));
            }
            sql.append("    )\n");
            if (req.isIncludePooled()) {
                sql.append("    OR asset_pool_flag = 'Y'\n");
            }
            sql.append("  )\n");
        }

        sql.append("ORDER BY asset_no");

        List<AcquiredRetiredRow> rows = jdbc.query(sql.toString(),
            new AssetRowMapper(), params.toArray());

        // For each asset, compute WDV / P&L and handle pooled accumulation
        for (AcquiredRetiredRow r : rows) {
            if (r.isPooledAsset()) {
                accumPooledTrxs(req, r);
            } else {
                computeNonPooled(r);
            }
        }

        return rows;
    }

    // ── Non-pooled computations ───────────────────────────────────────

    /**
     * CALC-BOOK-WRITTEN-DOWN-VAL:
     *   if last_reval_val > 0 → use last_reval_val, else book_depn_cost
     *   subtract (accum_book_depn + accum_book_depn_adj)
     *
     * CALC-TAX-WRITTEN-DOWN-VAL:
     *   if last_tax_reval_val > 0 → use last_tax_reval_val, else tax_depn_cost
     *   subtract accum_tax_depn + accum_tax_depn_adj
     *
     * SET-BOOK/TAX-DISPOSAL-PROFIT-LOSS:
     *   proceeds - WDV
     */
    private void computeNonPooled(AcquiredRetiredRow r) {
        // Book WDV
        BigDecimal bookBase = r.getLastRevalVal().compareTo(BigDecimal.ZERO) > 0
            ? r.getLastRevalVal()
            : r.getBookDepnCost();
        BigDecimal bookWdv = bookBase
            .subtract(r.getAccumBookDepn())
            .subtract(r.getAccumBookDepnAdj());
        r.setBookWrittenDownVal(bookWdv);

        // Tax WDV
        BigDecimal taxBase = r.getLastTaxRevalVal().compareTo(BigDecimal.ZERO) > 0
            ? r.getLastTaxRevalVal()
            : r.getTaxDepnCost();
        BigDecimal taxWdv = taxBase
            .subtract(r.getAccumTaxDepn())
            .subtract(r.getAccumTaxDepnAdj());
        r.setTaxWrittenDownVal(taxWdv);

        // Disposal profit / loss
        r.setBookDisposalProfitLoss(r.getRetmtProceedsVal().subtract(bookWdv));
        r.setTaxDisposalProfitLoss(r.getRetmtProceedsVal().subtract(taxWdv));
    }

    // ── Pooled asset FATRANS accumulation ────────────────────────────

    /**
     * Mirrors COBOL PRINT-ASSET-POOL-TRXS / ACCUM-NEXT-TRX / ADD-TO-TOTALS.
     *
     * Phase 1 — ACCUM-NEXT-TRX:
     *   Read ALL FATRANS for this asset in date range.
     *   AQ → add to totalBookDepnCost / totalTaxDepnCost; if A-mode count it
     *   BD → add depn_amt to totalAccumBookDepn
     *   TD → add depn_amt to totalAccumTaxDepn
     *   RT → add proceeds/profits; if R-mode count it
     *
     * Phase 2 — PRINT-NEXT-TRX:
     *   Re-read FATRANS starting at AQ (A-mode) or RT (R-mode),
     *   collect individual lines for DETAIL-16/17.
     *
     * Computed after accumulation:
     *   bookDisposalProfitLoss = accumulated from RT transactions
     *   taxDisposalProfitLoss  = accumulated from RT transactions
     */
    private void accumPooledTrxs(AcquiredRetiredRequest req, AcquiredRetiredRow r) {
        // Phase 1 — accumulate totals
        String accumSql = """
            SELECT trx_type, trx_date,
                   acqn_book_depn_cost, acqn_tax_depn_cost,
                   depn_amt,
                   retmt_proceeds_amt, retmt_bk_profit_amt, retmt_tx_profit_amt
            FROM FATRANS
            WHERE company_no = ?
              AND asset_no   = ?
              AND trx_status = ''
              AND trx_date > '1900-01-01'
            """;

        List<Object> ap = new ArrayList<>();
        ap.add(req.getCompanyNo());
        ap.add(r.getAssetNo());

        StringBuilder accumWhere = new StringBuilder(accumSql);
        if (req.getStartDate() != null) {
            accumWhere.append("  AND trx_date >= ?\n");
            ap.add(java.sql.Date.valueOf(req.getStartDate()));
        }
        if (req.getEndDate() != null) {
            accumWhere.append("  AND trx_date <= ?\n");
            ap.add(java.sql.Date.valueOf(req.getEndDate()));
        }
        accumWhere.append("ORDER BY trx_date, batch_no");

        BigDecimal totBookCost = BigDecimal.ZERO, totTaxCost = BigDecimal.ZERO;
        BigDecimal totAccumBook = BigDecimal.ZERO, totAccumTax = BigDecimal.ZERO;
        BigDecimal totProceeds  = BigDecimal.ZERO;
        BigDecimal totBookProfit = BigDecimal.ZERO, totTaxProfit = BigDecimal.ZERO;
        int        trxCount = 0;

        List<Object[]> accumRows = jdbc.query(accumWhere.toString(), (rs, i) -> new Object[]{
            rs.getString("trx_type"),
            rs.getBigDecimal("acqn_book_depn_cost"),
            rs.getBigDecimal("acqn_tax_depn_cost"),
            rs.getBigDecimal("depn_amt"),
            rs.getBigDecimal("retmt_proceeds_amt"),
            rs.getBigDecimal("retmt_bk_profit_amt"),
            rs.getBigDecimal("retmt_tx_profit_amt")
        }, ap.toArray());

        for (Object[] row : accumRows) {
            String type = (String) row[0];
            switch (type) {
                case "AQ" -> {
                    totBookCost  = totBookCost.add(bd(row[1]));
                    totTaxCost   = totTaxCost.add(bd(row[2]));
                    if (req.isAcquisitions()) trxCount++;
                }
                case "BD" -> totAccumBook = totAccumBook.add(bd(row[3]));
                case "TD" -> totAccumTax  = totAccumTax.add(bd(row[3]));
                case "RT" -> {
                    totProceeds  = totProceeds.add(bd(row[4]));
                    totBookProfit = totBookProfit.add(bd(row[5]));
                    totTaxProfit  = totTaxProfit.add(bd(row[6]));
                    if (req.isRetirements()) trxCount++;
                }
            }
        }

        r.setTotalBookDepnCost(totBookCost);
        r.setTotalTaxDepnCost(totTaxCost);
        r.setTotalAccumBookDepn(totAccumBook);
        r.setTotalAccumTaxDepn(totAccumTax);
        r.setTotalRetmtProceeds(totProceeds);
        r.setBookDisposalProfitLoss(totBookProfit);
        r.setTaxDisposalProfitLoss(totTaxProfit);

        // Phase 2 — individual transaction lines (only if trxCount > 0)
        if (trxCount == 0) return;

        String targetType = req.isAcquisitions() ? "AQ" : "RT";
        String printSql = """
            SELECT trx_type, trx_date,
                   acqn_book_depn_cost, acqn_tax_depn_cost,
                   retmt_proceeds_amt, retmt_bk_profit_amt, retmt_tx_profit_amt
            FROM FATRANS
            WHERE company_no = ?
              AND asset_no   = ?
              AND trx_type   = ?
              AND trx_status = ''
              AND trx_date > '1900-01-01'
            """;

        List<Object> pp = new ArrayList<>();
        pp.add(req.getCompanyNo());
        pp.add(r.getAssetNo());
        pp.add(targetType);

        StringBuilder printWhere = new StringBuilder(printSql);
        if (req.getStartDate() != null) {
            printWhere.append("  AND trx_date >= ?\n");
            pp.add(java.sql.Date.valueOf(req.getStartDate()));
        }
        if (req.getEndDate() != null) {
            printWhere.append("  AND trx_date <= ?\n");
            pp.add(java.sql.Date.valueOf(req.getEndDate()));
        }
        printWhere.append("ORDER BY trx_date, batch_no");

        List<PooledTrxLine> lines = jdbc.query(printWhere.toString(), (rs, i) -> {
            LocalDate td = toDate(rs, "trx_date");
            if ("AQ".equals(targetType)) {
                return new PooledTrxLine(
                    "AQ", td,
                    bd(rs.getBigDecimal("acqn_book_depn_cost")),
                    bd(rs.getBigDecimal("acqn_tax_depn_cost")),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            } else {
                return new PooledTrxLine(
                    "RT", td,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    bd(rs.getBigDecimal("retmt_proceeds_amt")),
                    bd(rs.getBigDecimal("retmt_bk_profit_amt")),
                    bd(rs.getBigDecimal("retmt_tx_profit_amt")));
            }
        }, pp.toArray());

        r.setPooledTrxs(lines);
    }

    // ── RowMapper ─────────────────────────────────────────────────────

    private static class AssetRowMapper implements RowMapper<AcquiredRetiredRow> {
        @Override
        public AcquiredRetiredRow mapRow(ResultSet rs, int rn) throws SQLException {
            AcquiredRetiredRow r = new AcquiredRetiredRow();
            r.setAssetNo(rs.getString("asset_no"));
            r.setDesc1(rs.getString("desc_1"));
            r.setDesc2(rs.getString("desc_2"));
            r.setLocCode(rs.getString("loc_code"));
            r.setDeptCode(rs.getString("dept_code"));
            r.setGrpCode(rs.getString("grp_code"));
            r.setSubgrpCode(rs.getString("subgrp_code"));
            r.setAssetStatus(rs.getString("asset_status"));
            r.setPooledAsset("Y".equals(rs.getString("asset_pool_flag")));
            r.setAcqnDate(toDate(rs, "acqn_date"));
            r.setRetmtDate(toDate(rs, "retmt_date"));
            r.setBookDepnCost(rs.getBigDecimal("book_depn_cost"));
            r.setLastRevalVal(rs.getBigDecimal("last_reval_val"));
            r.setAccumBookDepn(rs.getBigDecimal("accum_book_depn"));
            r.setAccumBookDepnAdj(rs.getBigDecimal("accum_book_depn_adj"));
            r.setLastBookDepnDate(toDate(rs, "last_book_depn_date"));
            r.setLastTaxRevalVal(rs.getBigDecimal("last_tax_reval_val"));
            r.setTaxDepnCost(rs.getBigDecimal("tax_depn_cost"));
            r.setAccumTaxDepn(rs.getBigDecimal("accum_tax_depn"));
            r.setAccumTaxDepnAdj(rs.getBigDecimal("accum_tax_depn_adj"));
            r.setLastTaxDepnDate(toDate(rs, "last_tax_depn_date"));
            r.setRetmtProceedsVal(rs.getBigDecimal("retmt_proceeds_val"));
            return r;
        }
    }

    private static LocalDate toDate(ResultSet rs, String col) throws SQLException {
        java.sql.Date d = rs.getDate(col);
        if (d == null) return null;
        LocalDate ld = d.toLocalDate();
        return ld.getYear() < 1900 ? null : ld;
    }

    private static BigDecimal bd(Object v) {
        if (v instanceof BigDecimal b) return b;
        return BigDecimal.ZERO;
    }
}
