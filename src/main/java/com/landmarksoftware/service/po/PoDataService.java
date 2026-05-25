package com.landmarksoftware.service.po;

import com.landmarksoftware.model.AppSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/**
 * Purchase Orders — popohed (PO header), popolin (PO lines), apsupps (supplier)
 * popohed PK: (company_no, loc_no, po_no)
 * po_status: O=open, C=closed, X=cancelled
 * po_value=total order value, recvd_value=goods received, inv_value=invoiced
 */
@Service
public class PoDataService {
    private static final Logger log = LoggerFactory.getLogger(PoDataService.class);
    private final JdbcTemplate jdbc;
    public PoDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int getOpenPoCount(AppSession s) {
        try { Integer v = jdbc.queryForObject("SELECT COUNT(*) FROM popohed WHERE company_no=? AND po_status='O'", Integer.class, s.getCompanyNo()); return v!=null?v:0; } catch (Exception e) { return 0; }
    }
    public BigDecimal getOpenPoValue(AppSession s) {
        try { BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(po_value),0) FROM popohed WHERE company_no=? AND po_status='O'", BigDecimal.class, s.getCompanyNo()); return v!=null?v:BigDecimal.ZERO; } catch (Exception e) { return BigDecimal.ZERO; }
    }
    public BigDecimal getReceivedYtd(AppSession s) {
        try { BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(recvd_value),0) FROM popohed WHERE company_no=? AND YEAR(po_date)=?", BigDecimal.class, s.getCompanyNo(), s.getYearNo()); return v!=null?v:BigDecimal.ZERO; } catch (Exception e) { return BigDecimal.ZERO; }
    }
    public BigDecimal getUnreceivedValue(AppSession s) {
        try { BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(po_value - recvd_value),0) FROM popohed WHERE company_no=? AND po_status='O'", BigDecimal.class, s.getCompanyNo()); return v!=null?v:BigDecimal.ZERO; } catch (Exception e) { return BigDecimal.ZERO; }
    }

    public Map<String, Object> getPoByMonth(AppSession s) {
        List<String> months = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        try {
            jdbc.query("SELECT DATE_FORMAT(po_date,'%b %Y') AS month_label, YEAR(po_date) AS yr, MONTH(po_date) AS mo, COALESCE(SUM(po_value),0) AS total FROM popohed WHERE company_no=? AND YEAR(po_date)=? GROUP BY yr, mo, month_label ORDER BY yr, mo",
                rs -> { months.add(rs.getString("month_label")); values.add(z(rs.getBigDecimal("total"))); }, s.getCompanyNo(), s.getYearNo());
        } catch (Exception e) { log.error("Query failed in {}: {}", getClass().getSimpleName(), e.getMessage()); }
        Map<String,Object> r=new LinkedHashMap<>(); r.put("months",months); r.put("values",values); return r;
    }

    public List<Map<String, Object>> getPoBySupplier(AppSession s) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            jdbc.query("SELECT COALESCE(h.supplier_name_1, h.supplier_no) AS supp_name, COALESCE(SUM(h.po_value),0) AS total FROM popohed h WHERE h.company_no=? AND h.po_status='O' GROUP BY supp_name ORDER BY total DESC LIMIT 10",
                rs -> { Map<String,Object> item=new LinkedHashMap<>(); item.put("name",rs.getString("supp_name")); item.put("value",z(rs.getBigDecimal("total"))); result.add(item); }, s.getCompanyNo());
        } catch (Exception e) { log.error("Query failed in {}: {}", getClass().getSimpleName(), e.getMessage()); }
        return result;
    }

    public Map<String, Object> getOpenPOListData(AppSession s) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            jdbc.query("SELECT h.po_no, h.loc_no, h.po_date, h.supplier_no, h.supplier_name_1, h.po_status, h.po_value, h.recvd_value, h.inv_value, (h.po_value - h.recvd_value) AS outstanding FROM popohed h WHERE h.company_no=? AND h.po_status='O' ORDER BY h.po_date DESC",
                rs -> {
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("poNo",rs.getInt("po_no")); row.put("location",rs.getString("loc_no"));
                    row.put("date",rs.getDate("po_date")!=null?rs.getDate("po_date").toString():"");
                    row.put("supplier",rs.getString("supplier_name_1")); row.put("status",rs.getString("po_status"));
                    row.put("poValue",rs.getBigDecimal("po_value")); row.put("received",rs.getBigDecimal("recvd_value"));
                    row.put("invoiced",rs.getBigDecimal("inv_value")); row.put("outstanding",rs.getBigDecimal("outstanding"));
                    rows.add(row);
                }, s.getCompanyNo());
        } catch (Exception e) { return err(e.getMessage()); }
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("columns", List.of(col("PO No","poNo","number"),col("Location","location","text"),col("Date","date","date"),col("Supplier","supplier","text"),col("Status","status","text"),col("PO Value","poValue","currency"),col("Received","received","currency"),col("Invoiced","invoiced","currency"),col("Outstanding","outstanding","currency")));
        r.put("rows",rows); r.put("title","Open Purchase Orders"); return r;
    }

    private BigDecimal z(BigDecimal v){return v!=null?v:BigDecimal.ZERO;}
    private Map<String,Object> col(String l,String f,String t){return Map.of("label",l,"field",f,"type",t);}
    private Map<String,Object> err(String m){return Map.of("error",m,"columns",List.of(),"rows",List.of(),"title","Error");}
}
