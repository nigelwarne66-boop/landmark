package com.landmarksoftware.repository;

import com.landmarksoftware.model.AssetRow;
import com.landmarksoftware.service.FaSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Replaces ACUCOBOL Vision file access for FAASSET.
 *
 * COBOL access patterns used in FATL12:
 *
 *   Primary key read:    READ FAASSET  (by FAASSET-ASSET-NO)
 *   Sequential by AK1:  START FAASSET KEY >= AK1-LOC+GRP+SUBGRP+ASSET-NO
 *                        READ NEXT FAASSET
 *   Sequential by AK2:  START ... KEY >= AK2-DEPT+GRP+SUBGRP+ASSET-NO
 *   Sequential by AK3:  START ... KEY >= AK3-GRP+SUBGRP+ASSET-NO
 *   Primary sequential: START ... KEY >= ASSET-NO
 *
 * Java replaces the sequential scan+filter loop with a single parameterised
 * SQL query. The 5-range filter that FATL12 applies record-by-record in COBOL
 * becomes WHERE clause predicates here.
 *
 * SQL table: fa_asset  (see schema in project docs)
 */
@Repository
public class FaAssetRepository {

    private final JdbcTemplate jdbc;

    public FaAssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ────────────────────────────────────────────────────────────────────
    // Primary query — used by DepreciationProjectionService
    // Replaces: FATL12 asset selection loop (LOOP-THRU-ASSET-FILE)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns all active assets matching the 5 range filter criteria.
     *
     * Filter logic mirrors FATL12.pl:
     *   1. Asset number in [startAssetNo .. endAssetNo]
     *   2. Location in [startLoc .. endLoc]
     *   3. Group in [startGrp .. endGrp]
     *   4. Sub-group in [startSubgrp .. endSubgrp]
     *   5. Department in [startDept .. endDept]
     *   6. Status = 'A' (active) — FATL12 skips disposed/inactive assets
     *   7. Actual cost > 0 (FATL12 skips zero-cost assets)
     *
     * @param startAssetNo  inclusive lower bound on asset_no
     * @param endAssetNo    inclusive upper bound on asset_no
     * @param startLoc      inclusive lower bound on loc_code
     * @param endLoc        inclusive upper bound on loc_code
     * @param startGrp      inclusive lower bound on grp_code
     * @param endGrp        inclusive upper bound on grp_code
     * @param startSubgrp   inclusive lower bound on subgrp_code
     * @param endSubgrp     inclusive upper bound on subgrp_code
     * @param startDept     inclusive lower bound on dept_code
     * @param endDept       inclusive upper bound on dept_code
     * @return list of matching AssetRow objects, ordered by asset_no
     */
    public List<AssetRow> findByRanges(
            int    companyNo,
            String startAssetNo, String endAssetNo,
            String startLoc,     String endLoc,
            String startGrp,     String endGrp,
            String startSubgrp,  String endSubgrp,
            String startDept,    String endDept) {

        return jdbc.query(FaSql.FIND_ASSETS_BY_RANGES, new AssetRowMapper(),
                companyNo,
                startAssetNo, endAssetNo,
                startLoc,     endLoc,
                startGrp,     endGrp,
                startSubgrp,  endSubgrp,
                startDept,    endDept);
    }

    // ────────────────────────────────────────────────────────────────────
    // Single-asset lookup (used for validation / re-reads)
    // Replaces: READ FAASSET
    // ────────────────────────────────────────────────────────────────────

    public AssetRow findByAssetNo(int companyNo, String assetNo) {
        List<AssetRow> rows = jdbc.query(
            FaSql.FIND_FAASSET_FULL_BY_PK, new AssetRowMapper(), companyNo, assetNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ────────────────────────────────────────────────────────────────────
    // RowMapper — FAASSET Vision record → AssetRow
    // ────────────────────────────────────────────────────────────────────

    private static class AssetRowMapper implements RowMapper<AssetRow> {

        @Override
        public AssetRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            AssetRow a = new AssetRow();

            a.setAssetNo(rs.getString("asset_no"));
            a.setDesc1(rs.getString("desc_1"));
            a.setLocCode(rs.getString("loc_code"));
            a.setGrpCode(rs.getString("grp_code"));
            a.setSubgrpCode(rs.getString("subgrp_code"));
            a.setDeptCode(rs.getString("dept_code"));
            a.setAssetStatus(rs.getString("asset_status"));

            a.setAcqnDate(toLocalDate(rs, "acqn_date"));
            a.setActualCost(rs.getBigDecimal("actual_cost"));
            a.setTaxDepnCost(rs.getBigDecimal("tax_depn_cost"));
            a.setBookDepnCost(rs.getBigDecimal("book_depn_cost"));

            // ── Book depreciation ──
            a.setBookDepnMethod(rs.getString("book_depn_method"));
            a.setBookDepnCode(rs.getString("book_depn_code"));
            a.setBookDepnFreq(rs.getInt("book_depn_freq"));
            a.setBookDepnRate1(rs.getBigDecimal("book_depn_rate_1"));
            a.setBookDepnRate2(rs.getBigDecimal("book_depn_rate_2"));
            a.setBookDepnCalcInd(rs.getString("book_depn_calc_ind"));
            a.setBookDepnCalcBase(rs.getString("book_depn_calc_base"));
            a.setStartDepnDate(toLocalDate(rs, "start_depn_date"));
            a.setAccumBookDepn(rs.getBigDecimal("accum_book_depn"));
            a.setAccumBookDepnAdj(rs.getBigDecimal("accum_book_depn_adj"));
            a.setLastBookDepnDate(toLocalDate(rs, "last_book_depn_date"));

            // ── Tax depreciation ──
            a.setTaxDepnMethod(rs.getString("tax_depn_method"));
            a.setTaxDepnCode(rs.getString("tax_depn_code"));
            a.setTaxDepnFreq(rs.getInt("tax_depn_freq"));
            a.setTaxDepnRate1(rs.getBigDecimal("tax_depn_rate_1"));
            a.setTaxDepnRate2(rs.getBigDecimal("tax_depn_rate_2"));
            a.setTaxDepnCalcInd(rs.getString("tax_depn_calc_ind"));
            a.setTaxDepnCalcBase(rs.getString("tax_depn_calc_base"));
            a.setStartTaxDepnDate(toLocalDate(rs, "start_tax_depn_date"));
            a.setAccumTaxDepn(rs.getBigDecimal("accum_tax_depn"));
            a.setAccumTaxDepnAdj(rs.getBigDecimal("accum_tax_depn_adj"));
            a.setLastTaxDepnDate(toLocalDate(rs, "last_tax_depn_date"));

            // ── Revaluation ──
            a.setLastRevalDate(toLocalDate(rs, "last_reval_date"));
            a.setLastRevalVal(rs.getBigDecimal("last_reval_val"));
            a.setLastTaxRevalDate(toLocalDate(rs, "last_tax_reval_date"));

            // ── Write-down & pool ──
            a.setWriteDownDate(toLocalDate(rs, "write_down_date"));
            a.setAssetPoolFlag(rs.getString("asset_pool_flag"));
            a.setPoolBookBal(rs.getBigDecimal("pool_book_bal"));
            a.setPoolBookBalDate(toLocalDate(rs, "pool_book_bal_date"));
            a.setPoolTaxBal(rs.getBigDecimal("pool_tax_bal"));
            a.setPoolTaxBalDate(toLocalDate(rs, "pool_tax_bal_date"));

            return a;
        }

        /**
         * Null-safe LocalDate extraction — also treats 1899-12-30 as null.
         * 1899-12-30 is the Landmark COBOL system's null date sentinel value.
         */
        private LocalDate toLocalDate(ResultSet rs, String col) throws SQLException {
            java.sql.Date d = rs.getDate(col);
            if (d == null) return null;
            LocalDate ld = d.toLocalDate();
            // 1899-12-30 is COBOL null date — treat as null
            if (ld.getYear() < 1900) return null;
            return ld;
        }
    }
}
