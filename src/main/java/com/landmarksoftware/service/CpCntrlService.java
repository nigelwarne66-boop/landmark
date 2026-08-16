package com.landmarksoftware.service;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import static com.landmarksoftware.db.tables.Cpcntrl.CPCNTRL;

/**
 * Read-only access to CPCNTRL — per-company control row.
 * Currently used for {@code local_pc_dir}, the directory where generated
 * report files (PDF / Excel) are saved by the Reports Hub.
 */
@Service
public class CpCntrlService {

    private final DSLContext dsl;

    public CpCntrlService(DSLContext dsl) { this.dsl = dsl; }

    /**
     * Returns the configured local PC output dir, or empty string on miss.
     * cpcntrl is a single global config row, not per-company — companyNo is
     * accepted for API compatibility but ignored.
     */
    public String getLocalPcDir(int companyNo) {
        try {
            String v = dsl.select(CPCNTRL.LOCAL_PC_DIR).from(CPCNTRL).limit(1).fetchOne(CPCNTRL.LOCAL_PC_DIR);
            String dir = v == null ? "" : v.trim();
            System.out.println("CpCntrl.local_pc_dir = [" + dir + "]");
            return dir;
        } catch (Exception e) {
            System.out.println("CpCntrl.local_pc_dir lookup failed: " + e.getMessage());
            return "";
        }
    }
}
