package com.landmarksoftware.service.gl;

import com.landmarksoftware.ui.reports.PeriodOption;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads the list of fiscal periods for a (company, year) combination —
 * used to populate period combos on GL report selection screens.
 *
 * <p>gldates stores period end dates as 13 separate COBOL-OCCURS-style
 * columns (period_end_01..period_end_13). We fetch them in one row and
 * unpivot into one PeriodOption per non-sentinel date.
 *
 * <p>year_no (4-digit, e.g. 2026) is the column other GL queries use —
 * yr_no exists too but is the COBOL sequence PK and isn't 1:1 with
 * the calendar year. Pass session.getYearNo() here.
 */
@Service
public class GlPeriodService {

    private static final LocalDate NO_DATE_SENTINEL = LocalDate.of(1899, 12, 31);

    private final JdbcTemplate jdbc;

    public GlPeriodService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns periods in period_no order (1..13), skipping any column whose
     * date is the 1899-12-31 sentinel ("period not set up").
     * Empty list on DB error — callers should disable Run.
     */
    public List<PeriodOption> loadPeriods(int companyNo, int yearNo) {
        List<PeriodOption> out = new ArrayList<>();
        try {
            StringBuilder cols = new StringBuilder();
            for (int i = 1; i <= 13; i++) {
                if (i > 1) cols.append(", ");
                cols.append(String.format("period_end_%02d", i));
            }
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT " + cols + " FROM gldates WHERE company_no = ? AND year_no = ?",
                companyNo, yearNo);
            for (int i = 1; i <= 13; i++) {
                Object v = row.get(String.format("period_end_%02d", i));
                if (v instanceof Date d) {
                    LocalDate ld = d.toLocalDate();
                    if (!NO_DATE_SENTINEL.equals(ld)) {
                        out.add(new PeriodOption(i, ld));
                    }
                }
            }
        } catch (Exception ignored) {
            // Empty list signals "no periods" — selection screens disable Run.
        }
        return out;
    }
}
