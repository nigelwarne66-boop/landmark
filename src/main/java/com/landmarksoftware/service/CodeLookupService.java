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
package com.landmarksoftware.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live-search lookup queries for the reusable LookupDialog UI component.
 *
 * Extracted from LookupDialog so that controllers never need to pass
 * a JdbcTemplate directly to a UI widget.
 *
 * Each method returns rows as List&lt;Map&lt;String,String&gt;&gt; — column name to
 * display value — exactly what LookupDialog's TableView needs.
 * All queries are LIMIT 200 and company-scoped.
 *
 * Tables: FAASSET, FACODLO, FACODGR, FACODSG, FACODDT,
 *         FACODSS, FACODIN, FACODDN
 */
@Service
public class CodeLookupService {

    private final JdbcTemplate jdbc;

    public CodeLookupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Asset ──────────────────────────────────────────────────────────

    public List<Map<String, String>> searchAssets(int companyNo, String filter) {
        String like = like(filter);
        return query(LookupSql.SEARCH_ASSETS, companyNo, like, like, like, like);
    }

    // ── FA codes ──────────────────────────────────────────────────────

    public List<Map<String, String>> searchLocations(int companyNo, String filter) {
        String like = like(filter);
        return query(LookupSql.SEARCH_LOCATIONS, companyNo, like, like);
    }

    public List<Map<String, String>> searchGroups(int companyNo, String filter) {
        String like = like(filter);
        return query(LookupSql.SEARCH_GROUPS, companyNo, like, like);
    }

    public List<Map<String, String>> searchSubgroups(int companyNo, String filter) {
        String like = like(filter);
        return query(LookupSql.SEARCH_SUBGROUPS, companyNo, like, like);
    }

    public List<Map<String, String>> searchDepartments(int companyNo, String filter) {
        String like = like(filter);
        return query(LookupSql.SEARCH_DEPARTMENTS, companyNo, like, like);
    }

    public List<Map<String, String>> searchStocktakeSites(int companyNo, String filter) {
        String like = like(filter);
        return query(LookupSql.SEARCH_STOCKTAKE_SITES, companyNo, like, like);
    }

    public List<Map<String, String>> searchInsuranceTypes(int companyNo, String filter) {
        String like = like(filter);
        return query(LookupSql.SEARCH_INSURANCE_TYPES, companyNo, like, like);
    }

    public List<Map<String, String>> searchDepnCodes(int companyNo, String filter) {
        String like = like(filter);
        return query(LookupSql.SEARCH_DEPN_CODES, companyNo, like, like);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static String like(String filter) {
        return "%" + (filter == null ? "" : filter) + "%";
    }

    private List<Map<String, String>> query(String sql, Object... params) {
        return jdbc.queryForList(sql, params).stream()
            .map(row -> {
                Map<String, String> m = new LinkedHashMap<>();
                row.forEach((k, v) -> m.put(k, v == null ? "" : v.toString()));
                return m;
            })
            .toList();
    }
}
