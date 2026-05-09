package com.example.fixedassets.repository;

import com.example.fixedassets.model.FaTransactionRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Replaces ACUCOBOL Vision file access for FATRANS.
 *
 * COBOL access patterns used in FATL12:
 *
 *   START FATRANS KEY >= WS-FATRANS-ASSET-NO + TRX-TYPE + TRX-DATE
 *   READ NEXT FATRANS until asset-no changes or type changes
 *
 * Used for two distinct purposes:
 *
 *   1. Opening-balance reconstruction (CALC-BASE = 'O'):
 *      Read all BD/TD (and BA/TA) records for the asset, sum depn amounts
 *      up to the projection start date. Subtract from cost to get opening WDV.
 *
 *   2. Revaluation adjustment (post-period RV records):
 *      Read RV records after the current period start; subtract REVAL-ADJ-AMT
 *      from the depreciable base for that period.
 */
@Repository
public class FaTransactionRepository {

    private final JdbcTemplate jdbc;

    public FaTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ────────────────────────────────────────────────────────────────────
    // Opening-balance reconstruction
    // Replaces: PERFORM CALC-OPEN-BAL-FOR-STREAM (fatl12.pl)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns all depreciation and depreciation-adjustment transactions for
     * an asset up to (and including) a given date, for the specified stream.
     *
     * @param assetNo    asset identifier
     * @param stream     'B' (book) or 'T' (tax)
     * @param upToDate   include transactions on or before this date
     * @return list ordered by trx_date ascending
     */
    public List<FaTransactionRow> findDepnTransactions(
            int companyNo, String assetNo, char stream, LocalDate upToDate) {

        String type1 = stream == 'T' ? "TD" : "BD";
        String type2 = stream == 'T' ? "TA" : "BA";

        String sql = """
            SELECT asset_no, trx_type, trx_date, batch_no,
                   trx_status, op_bal_flag,
                   depn_method, depn_code, depn_freq, depn_rate,
                   depn_amt, depn_thru_to_date, depn_calc_ind, depn_calc_base,
                   acqn_actual_cost, acqn_book_depn_cost, acqn_tax_depn_cost,
                   reval_val, reval_adj_amt, reval_accum_depn
            FROM FATRANS
            WHERE company_no = ?
              AND asset_no   = ?
              AND trx_type   IN (?, ?)
              AND trx_date  <= ?
            ORDER BY trx_date, batch_no
            """;

        return jdbc.query(sql, new TrxRowMapper(),
                companyNo, assetNo, type1, type2,
                java.sql.Date.valueOf(upToDate));
    }

    // ────────────────────────────────────────────────────────────────────
    // Revaluation adjustment scan
    // Replaces: PERFORM CALC-REVAL-FOR-PERIOD (fatl12.pl)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns RV (revaluation) transactions for an asset that fall strictly
     * after a given period start date. Used to subtract post-period revaluations
     * from the depreciable base for the current projection column.
     *
     * @param assetNo      asset identifier
     * @param afterDate    transactions must be dated strictly after this date
     * @return list ordered by trx_date ascending
     */
    public List<FaTransactionRow> findRevalTransactionsAfter(
            int companyNo, String assetNo, LocalDate afterDate) {

        String sql = """
            SELECT asset_no, trx_type, trx_date, batch_no,
                   trx_status, op_bal_flag,
                   acqn_actual_cost, acqn_book_depn_cost, acqn_tax_depn_cost,
                   depn_method, depn_code, depn_freq, depn_rate,
                   depn_amt, depn_thru_to_date, depn_calc_ind, depn_calc_base,
                   reval_val, reval_adj_amt, reval_accum_depn
            FROM FATRANS
            WHERE company_no = ?
              AND asset_no   = ?
              AND trx_type   = 'RV'
              AND trx_date   > ?
            ORDER BY trx_date, batch_no
            """;

        return jdbc.query(sql, new TrxRowMapper(),
                companyNo, assetNo,
                java.sql.Date.valueOf(afterDate));
    }

    // ────────────────────────────────────────────────────────────────────
    // Acquisition cost lookup (used when reconstructing cost base)
    // Replaces: READ FATRANS with TRX-TYPE = 'AQ'
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns the acquisition record(s) for an asset.
     * Most assets have a single AQ record; partial disposals can create multiples.
     */
    public List<FaTransactionRow> findAcquisitions(int companyNo, String assetNo) {
        String sql = """
            SELECT asset_no, trx_type, trx_date, batch_no,
                   trx_status, op_bal_flag,
                   acqn_actual_cost, acqn_book_depn_cost, acqn_tax_depn_cost,
                   depn_method, depn_code, depn_freq, depn_rate,
                   depn_amt, depn_thru_to_date, depn_calc_ind, depn_calc_base,
                   reval_val, reval_adj_amt, reval_accum_depn
            FROM FATRANS
            WHERE company_no = ?
              AND asset_no   = ?
              AND trx_type   = 'AQ'
            ORDER BY trx_date, batch_no
            """;
        return jdbc.query(sql, new TrxRowMapper(), companyNo, assetNo);
    }

    // ────────────────────────────────────────────────────────────────────
    // RowMapper — FATRANS Vision record → FaTransactionRow
    // ────────────────────────────────────────────────────────────────────

    private static class TrxRowMapper implements RowMapper<FaTransactionRow> {

        @Override
        public FaTransactionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            FaTransactionRow t = new FaTransactionRow();

            t.setAssetNo(rs.getString("asset_no"));
            t.setTrxType(rs.getString("trx_type"));
            t.setTrxDate(toLocalDate(rs, "trx_date"));
            t.setBatchNo(rs.getInt("batch_no"));
            t.setTrxStatus(rs.getString("trx_status"));
            t.setOpBalFlag(rs.getString("op_bal_flag"));

            // Acquisition sub-record
            t.setAcqnActualCost(rs.getBigDecimal("acqn_actual_cost"));
            t.setAcqnBookDepnCost(rs.getBigDecimal("acqn_book_depn_cost"));
            t.setAcqnTaxDepnCost(rs.getBigDecimal("acqn_tax_depn_cost"));

            // Depreciation sub-record
            t.setDepnMethod(rs.getString("depn_method"));
            t.setDepnCode(rs.getString("depn_code"));
            t.setDepnFreq(rs.getInt("depn_freq"));
            t.setDepnRate(rs.getBigDecimal("depn_rate"));
            t.setDepnAmt(rs.getBigDecimal("depn_amt"));
            t.setDepnThruToDate(toLocalDate(rs, "depn_thru_to_date"));
            t.setDepnCalcInd(rs.getString("depn_calc_ind"));
            t.setDepnCalcBase(rs.getString("depn_calc_base"));

            // Revaluation sub-record
            t.setRevalVal(rs.getBigDecimal("reval_val"));
            t.setRevalAdjAmt(rs.getBigDecimal("reval_adj_amt"));
            t.setRevalAccumDepn(rs.getBigDecimal("reval_accum_depn"));

            return t;
        }

        private LocalDate toLocalDate(ResultSet rs, String col) throws SQLException {
            java.sql.Date d = rs.getDate(col);
            if (d == null) return null;
            LocalDate ld = d.toLocalDate();
            if (ld.getYear() < 1900) return null;
            return ld;
        }
    }
}
