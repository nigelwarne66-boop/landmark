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

    /**
     * Returns the configured local PC output dir, or empty string on miss.
     * cpcntrl is a single global config row, not per-company — companyNo is
     * accepted for API compatibility but ignored.
     */
    public String getLocalPcDir(int companyNo) {
        try {
            String v = jdbc.queryForObject(
                "SELECT local_pc_dir FROM cpcntrl LIMIT 1",
                String.class);
            String dir = v == null ? "" : v.trim();
            System.out.println("CpCntrl.local_pc_dir = [" + dir + "]");
            return dir;
        } catch (Exception e) {
            System.out.println("CpCntrl.local_pc_dir lookup failed: " + e.getMessage());
            return "";
        }
    }
}
