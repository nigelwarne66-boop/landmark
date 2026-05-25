package com.landmarksoftware.service.sm;

import com.landmarksoftware.model.AppSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/**
 * Stock Management — smsthed (stock master), smstloc (stock by location),
 *                   smtrans (transactions), smsumry (period summary), smlocat (locations)
 * smstloc.qty_on_hand, value_on_hand, qty_on_order, qty_allocated per stock+location
 * smsumry: sales_qty/value, recpts_qty/value by period_end_date
 * smtrans: in_out_direct_ind (I/O/D), cost_value, sales_or_recpt_value
 */
@Service
public class SmDataService {
    private static final Logger log = LoggerFactory.getLogger(SmDataService.class);
    private final JdbcTemplate jdbc;
    public SmDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int getStockItemCount(AppSession s) {
        try { Integer v = jdbc.queryForObject("SELECT COUNT(DISTINCT stock_code) FROM smstloc WHERE company_no=? AND item_status='A'", Integer.class, s.getCompanyNo()); return v!=null?v:0; } catch (Exception e) { return 0; }
    }
    public BigDecimal getTotalStockValue(AppSession s) {
        try { BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(value_on_hand),0) FROM smstloc WHERE company_no=?", BigDecimal.class, s.getCompanyNo()); return v!=null?v:BigDecimal.ZERO; } catch (Exception e) { return BigDecimal.ZERO; }
    }
    public BigDecimal getSalesValueYtd(AppSession s) {
        try { BigDecimal v = jdbc.queryForObject(
            "SELECT COALESCE(SUM(sales_value),0) FROM smsumry WHERE company_no=? AND period_end_date BETWEEN ? AND ?",
            BigDecimal.class, s.getCompanyNo(), s.getYrStartDate(), s.getYrEndDate()); return v!=null?v:BigDecimal.ZERO;
        } catch (Exception e) { return BigDecimal.ZERO; }
    }
    public int getLowStockCount(AppSession s) {
        try { Integer v = jdbc.queryForObject("SELECT COUNT(*) FROM smstloc WHERE company_no=? AND item_status='A' AND qty_on_hand <= min_qty_level AND min_qty_level > 0", Integer.class, s.getCompanyNo()); return v!=null?v:0; } catch (Exception e) { return 0; }
    }

    public List<Map<String, Object>> getStockValueByLocation(AppSession s) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            jdbc.query("SELECT COALESCE(l.name1, sl.loc_no) AS loc_name, SUM(sl.value_on_hand) AS total_value FROM smstloc sl LEFT JOIN smlocat l ON l.company_no=sl.company_no AND l.loc_no=sl.loc_no WHERE sl.company_no=? GROUP BY sl.loc_no, l.name1 ORDER BY total_value DESC LIMIT 10",
                rs -> { Map<String,Object> item=new LinkedHashMap<>(); item.put("name",rs.getString("loc_name")); item.put("value",z(rs.getBigDecimal("total_value"))); result.add(item); }, s.getCompanyNo());
        } catch (Exception e) { log.error("Query failed in {}: {}", getClass().getSimpleName(), e.getMessage()); }
        return result;
    }

    public Map<String, Object> getSalesByMonth(AppSession s) {
        List<String> months = new ArrayList<>();
        List<BigDecimal> salesQty = new ArrayList<>();
        List<BigDecimal> salesVal = new ArrayList<>();
        try {
            jdbc.query("SELECT DATE_FORMAT(period_end_date,'%b %Y') AS month_label, YEAR(period_end_date) AS yr, MONTH(period_end_date) AS mo, COALESCE(SUM(sales_qty),0) AS qty, COALESCE(SUM(sales_value),0) AS val FROM smsumry WHERE company_no=? AND period_end_date BETWEEN ? AND ? GROUP BY yr, mo, month_label ORDER BY yr, mo",
                rs -> { months.add(rs.getString("month_label")); salesQty.add(z(rs.getBigDecimal("qty"))); salesVal.add(z(rs.getBigDecimal("val"))); },
                s.getCompanyNo(), s.getYrStartDate(), s.getYrEndDate());
        } catch (Exception e) { log.error("Query failed in {}: {}", getClass().getSimpleName(), e.getMessage()); }
        Map<String,Object> r=new LinkedHashMap<>(); r.put("months",months); r.put("salesQty",salesQty); r.put("salesValue",salesVal); return r;
    }

    public Map<String, Object> getStockOnHandData(AppSession s) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            jdbc.query("SELECT h.stock_code, h.desc_1, h.stock_unit, COALESCE(SUM(sl.qty_on_hand),0) AS qty_on_hand, COALESCE(SUM(sl.value_on_hand),0) AS value_on_hand, COALESCE(SUM(sl.qty_on_order),0) AS qty_on_order, COALESCE(SUM(sl.qty_allocated),0) AS qty_allocated, COALESCE(SUM(sl.qty_on_hand - sl.qty_allocated),0) AS qty_available FROM smsthed h JOIN smstloc sl ON sl.company_no=h.company_no AND sl.stock_code=h.stock_code WHERE h.company_no=? AND sl.item_status='A' GROUP BY h.stock_code, h.desc_1, h.stock_unit ORDER BY h.stock_code",
                rs -> {
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("stockCode",rs.getString("stock_code")); row.put("description",rs.getString("desc_1")); row.put("unit",rs.getString("stock_unit"));
                    row.put("onHand",rs.getBigDecimal("qty_on_hand")); row.put("value",rs.getBigDecimal("value_on_hand"));
                    row.put("onOrder",rs.getBigDecimal("qty_on_order")); row.put("allocated",rs.getBigDecimal("qty_allocated")); row.put("available",rs.getBigDecimal("qty_available"));
                    rows.add(row);
                }, s.getCompanyNo());
        } catch (Exception e) { return err(e.getMessage()); }
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("columns", List.of(col("Stock Code","stockCode","text"),col("Description","description","text"),col("Unit","unit","text"),col("On Hand","onHand","number"),col("Value","value","currency"),col("On Order","onOrder","number"),col("Allocated","allocated","number"),col("Available","available","number")));
        r.put("rows",rows); r.put("title","Stock On Hand"); return r;
    }

    private BigDecimal z(BigDecimal v){return v!=null?v:BigDecimal.ZERO;}
    private Map<String,Object> col(String l,String f,String t){return Map.of("label",l,"field",f,"type",t);}
    private Map<String,Object> err(String m){return Map.of("error",m,"columns",List.of(),"rows",List.of(),"title","Error");}
}
