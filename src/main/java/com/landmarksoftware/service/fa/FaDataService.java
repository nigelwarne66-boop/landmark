package com.landmarksoftware.service.fa;

import com.landmarksoftware.model.AppSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class FaDataService {
    private static final Logger log = LoggerFactory.getLogger(FaDataService.class);

    private final JdbcTemplate jdbc;

    public FaDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ── KPI tiles ──────────────────────────────────────────────
    public int getTotalAssets(AppSession s) {
        try { Integer v = jdbc.queryForObject("SELECT COUNT(*) FROM FAASSET WHERE company_no=? AND asset_status=''", Integer.class, s.getCompanyNo()); return v != null ? v : 0; } catch (Exception e) { return 0; }
    }
    public BigDecimal getTotalCost(AppSession s) {
        try { BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(actual_cost),0) FROM FAASSET WHERE company_no=? AND asset_status=''", BigDecimal.class, s.getCompanyNo()); return v != null ? v : BigDecimal.ZERO; } catch (Exception e) { return BigDecimal.ZERO; }
    }
    public BigDecimal getTotalAccumDepn(AppSession s) {
        try { BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(accum_book_depn),0) FROM FAASSET WHERE company_no=? AND asset_status=''", BigDecimal.class, s.getCompanyNo()); return v != null ? v : BigDecimal.ZERO; } catch (Exception e) { return BigDecimal.ZERO; }
    }
    public BigDecimal getNetBookValue(AppSession s) { return getTotalCost(s).subtract(getTotalAccumDepn(s)); }
    public int getUnpostedCount(AppSession s) {
        try { Integer v = jdbc.queryForObject("SELECT COUNT(*) FROM FAASSET WHERE company_no=? AND asset_status='U'", Integer.class, s.getCompanyNo()); return v != null ? v : 0; } catch (Exception e) { return 0; }
    }

    // ── ECharts data ───────────────────────────────────────────
    public List<Map<String, Object>> getCostByGroup(AppSession s) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT COALESCE(g.desc1, a.grp_code) as grp_name, SUM(a.actual_cost) as total_cost " +
                "FROM FAASSET a LEFT JOIN FACODGR g ON g.company_no=a.company_no AND g.grp_code=a.grp_code " +
                "WHERE a.company_no=? AND a.asset_status='' GROUP BY a.grp_code, g.desc1 ORDER BY total_cost DESC LIMIT 10",
                rs -> { Map<String, Object> item = new LinkedHashMap<>(); item.put("name", rs.getString("grp_name")); item.put("value", rs.getBigDecimal("total_cost")); result.add(item); },
                s.getCompanyNo());
        } catch (Exception e) { log.error("Query failed in {}: {}", getClass().getSimpleName(), e.getMessage()); }
        return result;
    }

    public Map<String, Object> getCountByLocation(AppSession s) {
        List<String> locations = new ArrayList<>(); List<Integer> counts = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT COALESCE(l.desc1, a.loc_code) as loc_name, COUNT(*) as cnt " +
                "FROM FAASSET a LEFT JOIN FACODLO l ON l.company_no=a.company_no AND l.loc_code=a.loc_code " +
                "WHERE a.company_no=? AND a.asset_status='' GROUP BY a.loc_code, l.desc1 ORDER BY cnt DESC LIMIT 12",
                rs -> { locations.add(rs.getString("loc_name")); counts.add(rs.getInt("cnt")); },
                s.getCompanyNo());
        } catch (Exception e) { log.error("Query failed in {}: {}", getClass().getSimpleName(), e.getMessage()); }
        Map<String, Object> r = new LinkedHashMap<>(); r.put("locations", locations); r.put("counts", counts); return r;
    }

    public Map<String, Object> getAcquisitionsByMonth(AppSession s) {
        List<String> months = new ArrayList<>(); List<BigDecimal> costs = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT DATE_FORMAT(acqn_date,'%b %Y') as month_label, YEAR(acqn_date) as yr, MONTH(acqn_date) as mo, SUM(actual_cost) as total " +
                "FROM FAASSET WHERE company_no=? AND asset_status IN ('','U') AND acqn_date BETWEEN ? AND ? " +
                "GROUP BY yr, mo, month_label ORDER BY yr, mo",
                rs -> { months.add(rs.getString("month_label")); BigDecimal v = rs.getBigDecimal("total"); costs.add(v != null ? v : BigDecimal.ZERO); },
                s.getCompanyNo(), s.getYrStartDate(), s.getYrEndDate());
        } catch (Exception e) { log.error("Query failed in {}: {}", getClass().getSimpleName(), e.getMessage()); }
        Map<String, Object> r = new LinkedHashMap<>(); r.put("months", months); r.put("costs", costs); return r;
    }

    // ── Interactive report data ────────────────────────────────

    /** Asset Register data for interactive preview. */
    public Map<String, Object> getAssetRegisterData(AppSession s) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT a.asset_no, a.desc_1, " +
                "  COALESCE(g.desc1, a.grp_code) AS grp_desc, " +
                "  COALESCE(l.desc1, a.loc_code) AS loc_desc, " +
                "  a.acqn_date, a.actual_cost, a.accum_book_depn, " +
                "  a.actual_cost - a.accum_book_depn AS net_book_value " +
                "FROM FAASSET a " +
                "LEFT JOIN FACODGR g ON g.company_no=a.company_no AND g.grp_code=a.grp_code " +
                "LEFT JOIN FACODLO l ON l.company_no=a.company_no AND l.loc_code=a.loc_code " +
                "WHERE a.company_no=? AND a.asset_status='' ORDER BY a.grp_code, a.asset_no",
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("assetNo",    rs.getString("asset_no"));
                    row.put("description",rs.getString("desc_1"));
                    row.put("group",      rs.getString("grp_desc"));
                    row.put("location",   rs.getString("loc_desc"));
                    row.put("acqnDate",   rs.getDate("acqn_date") != null ? rs.getDate("acqn_date").toString() : "");
                    row.put("cost",       rs.getBigDecimal("actual_cost"));
                    row.put("accumDepn",  rs.getBigDecimal("accum_book_depn"));
                    row.put("netBook",    rs.getBigDecimal("net_book_value"));
                    rows.add(row);
                }, s.getCompanyNo());
        } catch (Exception e) { return errorResult(e.getMessage()); }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", List.of(
            col("Asset No",    "assetNo",    "text"),
            col("Description", "description","text"),
            col("Group",       "group",      "text"),
            col("Location",    "location",   "text"),
            col("Acqn Date",   "acqnDate",   "date"),
            col("Cost",        "cost",       "currency"),
            col("Accum Depn",  "accumDepn",  "currency"),
            col("Net Book Val","netBook",    "currency")
        ));
        result.put("rows", rows);
        result.put("title", "Asset Register — Active Assets");
        return result;
    }

    /** Acquisitions data for interactive preview. */
    public Map<String, Object> getAcquisitionsData(AppSession s) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT a.asset_no, a.desc_1, a.acqn_date, a.acqn_type, " +
                "  COALESCE(g.desc1, a.grp_code) AS grp_desc, " +
                "  COALESCE(l.desc1, a.loc_code) AS loc_desc, " +
                "  a.actual_cost, a.supp_name, a.supp_inv_no, a.asset_status " +
                "FROM FAASSET a " +
                "LEFT JOIN FACODGR g ON g.company_no=a.company_no AND g.grp_code=a.grp_code " +
                "LEFT JOIN FACODLO l ON l.company_no=a.company_no AND l.loc_code=a.loc_code " +
                "WHERE a.company_no=? AND a.acqn_date BETWEEN ? AND ? AND a.asset_status IN ('','U') " +
                "ORDER BY a.acqn_date, a.asset_no",
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("assetNo",    rs.getString("asset_no"));
                    row.put("description",rs.getString("desc_1"));
                    row.put("acqnDate",   rs.getDate("acqn_date") != null ? rs.getDate("acqn_date").toString() : "");
                    row.put("type",       rs.getString("acqn_type"));
                    row.put("group",      rs.getString("grp_desc"));
                    row.put("location",   rs.getString("loc_desc"));
                    row.put("cost",       rs.getBigDecimal("actual_cost"));
                    row.put("supplier",   rs.getString("supp_name"));
                    row.put("invoice",    rs.getString("supp_inv_no"));
                    row.put("status",     "U".equals(rs.getString("asset_status")) ? "Unposted" : "Active");
                    rows.add(row);
                }, s.getCompanyNo(), s.getYrStartDate(), s.getYrEndDate());
        } catch (Exception e) { return errorResult(e.getMessage()); }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", List.of(
            col("Asset No",    "assetNo",    "text"),
            col("Description", "description","text"),
            col("Acqn Date",   "acqnDate",   "date"),
            col("Type",        "type",       "text"),
            col("Group",       "group",      "text"),
            col("Location",    "location",   "text"),
            col("Cost",        "cost",       "currency"),
            col("Supplier",    "supplier",   "text"),
            col("Invoice",     "invoice",    "text"),
            col("Status",      "status",     "text")
        ));
        result.put("rows", rows);
        result.put("title", "Acquisitions — " + s.getYearDesc());
        return result;
    }

    private Map<String, Object> col(String label, String field, String type) {
        return Map.of("label", label, "field", field, "type", type);
    }
    private Map<String, Object> errorResult(String msg) {
        return Map.of("error", msg, "columns", List.of(), "rows", List.of());
    }
}
