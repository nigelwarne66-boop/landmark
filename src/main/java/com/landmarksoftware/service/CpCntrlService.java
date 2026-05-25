package com.landmarksoftware.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only access to CPCNTRL — per-company control row.
 * Currently used for {@code local_pc_dir}, the directory where generated
 * report files (PDF / Excel) are saved by the Reports Hub.
 */
@Service
public class CpCntrlService {

    private final JdbcTemplate jdbc;

    public CpCntrlService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the configured local PC output dir, or empty string on miss. */
    public String getLocalPcDir(int companyNo) {
        try {
            String v = jdbc.queryForObject(
                "SELECT local_pc_dir FROM cpcntrl WHERE company_no = ?",
                String.class, companyNo);
            return v == null ? "" : v.trim();
        } catch (Exception e) {
            return "";
        }
    }
}
