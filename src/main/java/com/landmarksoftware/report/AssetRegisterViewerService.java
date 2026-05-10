/*
 * Copyright (c) 2026 Landmark Software Pty Ltd.
 * All rights reserved.
 *
 * This software is proprietary and confidential.
 * Unauthorised copying, modification, distribution or use
 * of this software, via any medium, is strictly prohibited.
 * Decompilation and reverse engineering are expressly forbidden.
 *
 * Licenced under the terms of the Landmark Software Licence Agreement.
 */
package com.landmarksoftware.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fixed Asset Register interactive viewer.
 *
 * Runs as a plain HTTP servlet on port 8090 at /fa/asset-register.
 * Queries faasset via JdbcTemplate and returns a fully interactive HTML report with:
 *   - Column show/hide
 *   - Sort by any column (click header)
 *   - Live filter/search
 *   - CSV export
 *   - Browser print-to-PDF
 *
 * URL: http://localhost:8090/fa/asset-register?companyNo=1&bookTaxInd=B
 *
 * Replaces the former report engine dependency — pure JdbcTemplate + HTML servlet.
 */
@Service
public class AssetRegisterViewerService {

    public static final int    VIEWER_PORT = 8090;
    public static final String VIEWER_PATH = "/fa/asset-register";

    private final JdbcTemplate jdbc;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;
    @Value("${spring.datasource.username}")
    private String jdbcUser;
    @Value("${spring.datasource.password:}")
    private String jdbcPassword;

    public AssetRegisterViewerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Build the viewer URL for the given parameter map.
     * Called by AssetRegisterParamsController when the user clicks Run Report.
     */
    public String buildViewerUrl(Map<String, String> params) {
        StringBuilder url = new StringBuilder("http://localhost:" + VIEWER_PORT + VIEWER_PATH);
        if (params != null && !params.isEmpty()) {
            url.append("?");
            params.forEach((k, v) -> {
                if (v != null && !v.isEmpty())
                    url.append(k).append("=")
                       .append(URLEncoder.encode(v, StandardCharsets.UTF_8))
                       .append("&");
            });
        }
        return url.toString();
    }

    @Bean
    public ServletRegistrationBean<AssetRegisterServlet> assetRegisterServlet() {
        AssetRegisterServlet servlet = new AssetRegisterServlet(jdbc);
        ServletRegistrationBean<AssetRegisterServlet> reg =
            new ServletRegistrationBean<>(servlet, VIEWER_PATH);
        reg.setName("AssetRegisterServlet");
        reg.setLoadOnStartup(1);
        return reg;
    }

    // ── Servlet ──────────────────────────────────────────────────────────────

    public static class AssetRegisterServlet extends HttpServlet {

        private final JdbcTemplate jdbc;

        public AssetRegisterServlet(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws IOException {

            int    companyNo  = intParam(req, "companyNo",  1);
            String bookTaxInd = strParam(req, "bookTaxInd", "B");
            String locn       = strParam(req, "printLocn",  "");
            String dept       = strParam(req, "printDept",  "");
            String grp        = strParam(req, "printGroup", "");
            String sgrp       = strParam(req, "printSubGroup", "");

            boolean isBook    = "B".equalsIgnoreCase(bookTaxInd);
            String depnMethod = isBook ? "a.book_depn_method" : "a.tax_depn_method";
            String depnCode   = isBook ? "a.book_depn_code"   : "a.tax_depn_code";
            String depnRate   = isBook ? "a.book_depn_rate_1"  : "a.tax_depn_rate_1";
            String depnFreq   = isBook ? "a.book_depn_freq"   : "a.tax_depn_freq";
            String startDepn  = isBook
                ? "DATE_FORMAT(a.start_depn_date,'%d/%m/%Y')"
                : "DATE_FORMAT(a.start_tax_depn_date,'%d/%m/%Y')";

            StringBuilder sql = new StringBuilder(
                "SELECT a.asset_no, a.desc_1, a.desc_2, a.loc_code, a.dept_code, " +
                "a.grp_code, a.subgrp_code, " +
                "DATE_FORMAT(a.acqn_date,'%d/%m/%Y') AS acqn_date, " +
                "CASE a.asset_status WHEN '' THEN 'Active' WHEN 'U' THEN 'Unposted' " +
                "WHEN 'H' THEN 'On Hold' WHEN 'N' THEN 'Inactive' " +
                "WHEN 'R' THEN 'Retired' ELSE a.asset_status END AS asset_status, " +
                "a.actual_cost, " +
                depnMethod + " AS depn_method, " +
                depnCode   + " AS depn_code, " +
                depnRate   + " AS depn_rate, " +
                depnFreq   + " AS depn_freq, " +
                startDepn  + " AS start_depn " +
                "FROM faasset a WHERE a.company_no = " + companyNo);

            String startAsset = strParam(req, "startAssetNo", "");
            String endAsset   = strParam(req, "endAssetNo",   "");
            String statusU    = strParam(req, "statusU", "");
            String statusA    = strParam(req, "statusA", "Y");
            String statusH    = strParam(req, "statusH", "Y");
            String statusN    = strParam(req, "statusN", "");
            String statusR    = strParam(req, "statusR", "");
            String leasedInd  = strParam(req, "leasedInd", "A");
            String sortOrder  = strParam(req, "sortOrder", "loc");

            if (!startAsset.isEmpty())
                sql.append(" AND a.asset_no >= '").append(esc(startAsset)).append("'");
            if (!endAsset.isEmpty())
                sql.append(" AND a.asset_no <= '").append(esc(endAsset)).append("'");
            if (!locn.isEmpty()) sql.append(" AND a.loc_code='").append(esc(locn)).append("'");
            if (!dept.isEmpty()) sql.append(" AND a.dept_code='").append(esc(dept)).append("'");
            if (!grp.isEmpty())  sql.append(" AND a.grp_code='").append(esc(grp)).append("'");
            if (!sgrp.isEmpty()) sql.append(" AND a.subgrp_code='").append(esc(sgrp)).append("'");

            List<String> statusList = new ArrayList<>();
            if ("Y".equals(statusU)) statusList.add("'U'");
            if ("Y".equals(statusA)) statusList.add("''");
            if ("Y".equals(statusH)) statusList.add("'H'");
            if ("Y".equals(statusN)) statusList.add("'N'");
            if ("Y".equals(statusR)) statusList.add("'R'");
            if (!statusList.isEmpty())
                sql.append(" AND a.asset_status IN (").append(String.join(",", statusList)).append(")");

            if ("L".equals(leasedInd)) sql.append(" AND a.leased_asset_flag='Y'");
            else if ("N".equals(leasedInd))
                sql.append(" AND (a.leased_asset_flag IS NULL OR a.leased_asset_flag<>'Y')");

            String orderBy = switch (sortOrder) {
                case "dept"  -> "a.dept_code, a.grp_code, a.subgrp_code, a.asset_no";
                case "grp"   -> "a.grp_code, a.subgrp_code, a.asset_no";
                case "asset" -> "a.asset_no";
                default      -> "a.loc_code, a.grp_code, a.subgrp_code, a.asset_no";
            };
            sql.append(" ORDER BY ").append(orderBy);

            if ("csv".equals(strParam(req, "export", ""))) {
                exportCsv(resp, sql.toString());
                return;
            }

            StringBuilder rows = new StringBuilder();
            int[] count = {0};
            try {
                jdbc.query(sql.toString(), rs -> {
                    count[0]++;
                    rows.append("<tr>")
                        .append(td(rs.getString("asset_no")))
                        .append(td(rs.getString("desc_1")))
                        .append(td(rs.getString("desc_2")))
                        .append(td(rs.getString("loc_code")))
                        .append(td(rs.getString("dept_code")))
                        .append(td(rs.getString("grp_code")))
                        .append(td(rs.getString("subgrp_code")))
                        .append(td(rs.getString("acqn_date")))
                        .append(tdR(fmt(rs.getBigDecimal("actual_cost"))))
                        .append(td(rs.getString("depn_method")))
                        .append(td(rs.getString("depn_code")))
                        .append(tdR(fmt(rs.getBigDecimal("depn_rate"))))
                        .append(td(rs.getString("depn_freq") == null ? "" : rs.getString("depn_freq")))
                        .append(td(rs.getString("start_depn")))
                        .append(td(rs.getString("asset_status")))
                        .append("</tr>\n");
                });
            } catch (Exception ex) {
                resp.setContentType("text/html;charset=UTF-8");
                resp.getWriter().write("<h2>Query error</h2><pre>" + h(ex.getMessage()) + "</pre>");
                return;
            }

            String baseUrl = req.getRequestURL() + "?" +
                "companyNo=" + companyNo + "&bookTaxInd=" + bookTaxInd +
                (locn.isEmpty() ? "" : "&printLocn=" + locn) +
                (dept.isEmpty() ? "" : "&printDept=" + dept) +
                (grp.isEmpty()  ? "" : "&printGroup=" + grp) +
                (sgrp.isEmpty() ? "" : "&printSubGroup=" + sgrp);

            resp.setContentType("text/html;charset=UTF-8");
            resp.getWriter().write(buildHtml(rows.toString(), count[0],
                companyNo, bookTaxInd, baseUrl, req));
        }

        private void exportCsv(HttpServletResponse resp, String sql) throws IOException {
            resp.setContentType("text/csv;charset=UTF-8");
            resp.setHeader("Content-Disposition", "attachment; filename=asset_register.csv");
            PrintWriter w = resp.getWriter();
            w.println("Asset No,Description,Desc 2,Loc,Dept,Grp,Sub-Grp,Acqn Date,Cost," +
                      "Depn Method,Depn Code,Rate,Freq,Start Depn,Status");
            jdbc.query(sql, rs -> {
                w.println(
                    csv(rs.getString("asset_no"))    + "," +
                    csv(rs.getString("desc_1"))       + "," +
                    csv(rs.getString("desc_2"))       + "," +
                    csv(rs.getString("loc_code"))     + "," +
                    csv(rs.getString("dept_code"))    + "," +
                    csv(rs.getString("grp_code"))     + "," +
                    csv(rs.getString("subgrp_code"))  + "," +
                    csv(rs.getString("acqn_date"))    + "," +
                    fmt(rs.getBigDecimal("actual_cost")) + "," +
                    csv(rs.getString("depn_method"))  + "," +
                    csv(rs.getString("depn_code"))    + "," +
                    fmt(rs.getBigDecimal("depn_rate")) + "," +
                    csv(rs.getString("depn_freq"))    + "," +
                    csv(rs.getString("start_depn"))   + "," +
                    csv(rs.getString("asset_status"))
                );
            });
        }

        private String buildHtml(String dataRows, int count, int companyNo,
                                  String bookTaxInd, String baseUrl,
                                  HttpServletRequest req) {
            String filterForm =
                "<form method='get' style='display:inline'>" +
                "<input type='hidden' name='companyNo' value='" + companyNo + "'>" +
                "<label>Book/Tax: <select name='bookTaxInd' onchange='this.form.submit()'>" +
                "<option value='B'" + ("B".equals(bookTaxInd) ? " selected" : "") + ">Book</option>" +
                "<option value='T'" + ("T".equals(bookTaxInd) ? " selected" : "") + ">Tax</option>" +
                "</select></label>&nbsp;" +
                "<label>Loc: <input name='printLocn' value='" + strParam(req,"printLocn","") + "' size='5'></label>&nbsp;" +
                "<label>Grp: <input name='printGroup' value='" + strParam(req,"printGroup","") + "' size='5'></label>&nbsp;" +
                "<button type='submit'>&#128269; Filter</button>" +
                "</form>";

            return "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                "<title>Landmark \u2014 Fixed Asset Register</title>" +
                "<style>" +
                "body{font-family:-apple-system,'Segoe UI',sans-serif;background:#F2F1EC;margin:0}" +
                "#toolbar{background:#fff;border-bottom:1px solid #E0E0D8;padding:10px 16px;" +
                "display:flex;align-items:center;gap:12px;flex-wrap:wrap;" +
                "position:sticky;top:0;z-index:999;box-shadow:0 1px 4px rgba(0,0,0,.08)}" +
                ".lm-title{font-size:14px;font-weight:600;color:#1A1A1A;white-space:nowrap}" +
                "#search{padding:5px 10px;border:1px solid #D0CFC8;border-radius:6px;font-size:12px;width:180px}" +
                ".btn{padding:5px 12px;font-size:11px;font-weight:500;border-radius:6px;" +
                "cursor:pointer;border:1px solid #D0CFC8;background:#fff;color:#333;text-decoration:none}" +
                ".btn:hover{background:#F0EFE8}" +
                ".btn-print{background:#059669;color:#fff;border-color:#059669}" +
                ".btn-print:hover{background:#047857}" +
                ".sep{width:1px;height:20px;background:#E0E0D8;flex-shrink:0}" +
                "#col-panel{display:none;background:#fff;border:1px solid #D0CFC8;border-radius:8px;" +
                "padding:12px;position:absolute;top:48px;left:16px;z-index:1000;" +
                "box-shadow:0 4px 16px rgba(0,0,0,.12);min-width:180px;max-height:70vh;overflow-y:auto}" +
                "#col-panel label{display:block;font-size:12px;padding:3px 0;cursor:pointer}" +
                ".wrap{padding:12px 16px}" +
                ".count{font-size:11px;color:#888;padding:4px 0 8px}" +
                "table{border-collapse:collapse;width:100%;background:#fff;font-size:12px}" +
                "th{background:#F8F8F6;font-size:10px;font-weight:600;color:#555;" +
                "padding:8px;cursor:pointer;white-space:nowrap;border-bottom:2px solid #E0E0D8;text-align:left}" +
                "th:hover{background:#EEEEE8}" +
                "th.sa::after{content:' \u2191'}th.sd::after{content:' \u2193'}" +
                "td{padding:6px 8px;border-bottom:1px solid #F0EFE8;vertical-align:top}" +
                "tr:hover td{background:#F8F8F6}" +
                ".r{text-align:right}" +
                "@media print{#toolbar{display:none}}" +
                "</style></head><body>" +

                "<div id='toolbar'>" +
                "<span class='lm-title'>&#9649; Fixed Asset Register</span>" +
                "<div class='sep'></div>" +
                filterForm +
                "<div class='sep'></div>" +
                "<input id='search' type='text' placeholder='Search...' oninput='lmFilter(this.value)'>" +
                "<div class='sep'></div>" +
                "<button class='btn' onclick='toggleColPanel()'>&#9776; Columns</button>" +
                "<button class='btn btn-print' onclick='window.print()'>&#128438; Print / PDF</button>" +
                "<a class='btn' href='" + baseUrl + "&export=csv'>&#8681; CSV</a>" +
                "<div id='col-panel'></div>" +
                "</div>" +

                "<div class='wrap'>" +
                "<div class='count'>" + count + " assets</div>" +
                "<table id='tbl'><thead><tr>" +
                "<th onclick='sortBy(0)'>Asset No</th>" +
                "<th onclick='sortBy(1)'>Description</th>" +
                "<th onclick='sortBy(2)'>Desc 2</th>" +
                "<th onclick='sortBy(3)'>Loc</th>" +
                "<th onclick='sortBy(4)'>Dept</th>" +
                "<th onclick='sortBy(5)'>Grp</th>" +
                "<th onclick='sortBy(6)'>SbGp</th>" +
                "<th onclick='sortBy(7)'>Acqn Date</th>" +
                "<th class='r' onclick='sortBy(8)'>Cost</th>" +
                "<th onclick='sortBy(9)'>M</th>" +
                "<th onclick='sortBy(10)'>Code</th>" +
                "<th class='r' onclick='sortBy(11)'>Rate</th>" +
                "<th onclick='sortBy(12)'>Frq</th>" +
                "<th onclick='sortBy(13)'>Start Depn</th>" +
                "<th onclick='sortBy(14)'>Status</th>" +
                "</tr></thead><tbody id='tbody'>" +
                dataRows +
                "</tbody></table></div>" +

                "<script>" +
                "window.onload=function(){" +
                "var ths=document.querySelectorAll('th');" +
                "var panel=document.getElementById('col-panel');" +
                "panel.innerHTML='<b style=\"font-size:12px\">Show/hide columns</b><hr style=\"margin:5px 0\">';" +
                "ths.forEach(function(th,i){" +
                "var l=document.createElement('label');" +
                "var cb=document.createElement('input');" +
                "cb.type='checkbox';cb.checked=true;cb.dataset.i=i;" +
                "cb.onchange=function(){toggleCol(i,this.checked);};" +
                "l.appendChild(cb);l.appendChild(document.createTextNode(' '+th.innerText.replace(/[\\u2191\\u2193]/g,'').trim()));" +
                "panel.appendChild(l);});};" +
                "function toggleCol(i,show){" +
                "document.querySelectorAll('tr').forEach(function(r){" +
                "var c=r.querySelectorAll('th,td');if(c[i])c[i].style.display=show?'':'none';});}" +
                "function toggleColPanel(){var p=document.getElementById('col-panel');" +
                "p.style.display=p.style.display==='block'?'none':'block';}" +
                "document.addEventListener('click',function(e){" +
                "var p=document.getElementById('col-panel');" +
                "if(!e.target.closest('#col-panel')&&!e.target.closest('[onclick=\"toggleColPanel()\"]'))" +
                "p.style.display='none';});" +
                "function lmFilter(q){q=q.toLowerCase();" +
                "document.querySelectorAll('#tbody tr').forEach(function(r){" +
                "r.style.display=(q===''||r.innerText.toLowerCase().includes(q))?'':'none';});}" +
                "var sortCol=-1,sortAsc=true;" +
                "function sortBy(col){" +
                "if(sortCol===col)sortAsc=!sortAsc;else{sortCol=col;sortAsc=true;}" +
                "var tb=document.getElementById('tbody');" +
                "var rows=Array.from(tb.rows);" +
                "rows.sort(function(a,b){" +
                "var av=a.cells[col]?a.cells[col].innerText.trim():'';" +
                "var bv=b.cells[col]?b.cells[col].innerText.trim():'';" +
                "var an=parseFloat(av.replace(/[^0-9.-]/g,'')),bn=parseFloat(bv.replace(/[^0-9.-]/g,''));" +
                "if(!isNaN(an)&&!isNaN(bn))return sortAsc?an-bn:bn-an;" +
                "return sortAsc?av.localeCompare(bv):bv.localeCompare(av);});" +
                "rows.forEach(function(r){tb.appendChild(r);});" +
                "document.querySelectorAll('th').forEach(function(th,i){" +
                "th.classList.remove('sa','sd');" +
                "if(i===sortCol)th.classList.add(sortAsc?'sa':'sd');});}" +
                "</script></body></html>";
        }

        // ── Helpers ──────────────────────────────────────────────────────
        private static String td(String v)  { return "<td>" + h(v) + "</td>"; }
        private static String tdR(String v) { return "<td class='r'>" + h(v) + "</td>"; }
        private static String h(String v)   {
            return v == null ? "" : v.replace("&","&amp;").replace("<","&lt;");
        }
        private static String csv(String v) {
            if (v == null) return "";
            if (v.contains(",") || v.contains("\"") || v.contains("\n"))
                return "\"" + v.replace("\"","\"\"") + "\"";
            return v;
        }
        private static String fmt(BigDecimal v) {
            if (v == null) return "";
            return v.stripTrailingZeros().toPlainString();
        }
        private static String esc(String v)  { return v == null ? "" : v.replace("'","''"); }
        private static int    intParam(HttpServletRequest r, String k, int def) {
            try { return Integer.parseInt(r.getParameter(k)); } catch (Exception e) { return def; }
        }
        private static String strParam(HttpServletRequest r, String k, String def) {
            String v = r.getParameter(k); return v != null ? v : def;
        }
    }
}
