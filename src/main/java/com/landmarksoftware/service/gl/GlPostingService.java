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
package com.landmarksoftware.service.gl;

import com.landmarksoftware.model.GlJournalLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GL journal posting (glgn06/glgn07 core) — posts balanced, draft GENERAL
 * journals into gltrx + glbal, then flips the header to committed, all in
 * one transaction. Also runs STANDING journals (glgn09 UPDATE-STANDING-JNL):
 * each run posts the template's current occurrence under a freshly-allocated
 * run number with gltrx.source='ST', then rolls the template forward (or
 * expires it).
 *
 * <p>Confirmed against COBOL + live data: gltrx.source='GN' (general) /
 * 'ST' (standing), trx_type='F', gltrx.year_no = gldates.year_no (4-digit)
 * for the journal's yr_no, seq_no = MAX+1 within the account/date key, and
 * glbal is DEBIT-positive (bal_NN += dr − cr). See GlPostingSql for the
 * deferred features.
 */
@Service
public class GlPostingService {

    private static final LocalDate DATE_ZERO = LocalDate.of(1899, 12, 31);

    private final JdbcTemplate jdbc;
    private final GlJournalService journals;

    // glbal has ~130 all-NOT-NULL columns; build the zero-row INSERT from
    // metadata once rather than hand-listing.
    private volatile String glbalInsertSql;
    private volatile List<String[]> glbalCols;   // [name, dataType]

    public GlPostingService(JdbcTemplate jdbc, GlJournalService journals) {
        this.jdbc = jdbc;
        this.journals = journals;
    }

    /** A journal that can be posted. */
    public record Postable(int jnlNo, LocalDate date, String note,
                           BigDecimal dr, BigDecimal cr, boolean balanced) {}

    /** Outcome of a posting run. */
    public record PostResult(int journalsPosted, int linesPosted, int skipped, String message) {}

    /** Balanced, unposted general journals available to post. */
    public List<Postable> findPostable(int companyNo, int yrNo) {
        return jdbc.query(GlPostingSql.SELECT_UNPOSTED, (rs, i) -> {
            BigDecimal dr = nz(rs.getBigDecimal("total_dr"));
            BigDecimal cr = nz(rs.getBigDecimal("total_cr"));
            boolean bal = dr.compareTo(cr) == 0 && dr.signum() > 0;
            return new Postable(rs.getInt("jnl_no"), toLocal(rs.getDate("jnl_date")),
                rs.getString("note_1"), dr, cr, bal);
        }, companyNo, yrNo);
    }

    /** Commit (post) one general journal. Throws if it is not committable. */
    @Transactional
    public PostResult postJournal(int companyNo, int yrNo, int jnlNo, String userId, int terminalNo) {
        Map<String, Object> h = jdbc.queryForMap(
            "SELECT jnl_date, posted_flag, jnl_status FROM glgnhed " +
            "WHERE company_no=? AND jnl_type='G' AND yr_no=? AND jnl_no=?",
            companyNo, yrNo, jnlNo);
        String posted = str(h.get("posted_flag")), status = str(h.get("jnl_status"));
        if ("Y".equals(posted)) throw new IllegalStateException("Journal " + jnlNo + " is already committed.");
        if (!status.isBlank())  throw new IllegalStateException("Journal " + jnlNo + " status '" + status + "' — cannot commit.");
        LocalDate jnlDate = ((Date) h.get("jnl_date")).toLocalDate();

        List<GlJournalLine> lines = journals.loadLines(companyNo, "G", yrNo, jnlNo);
        if (lines.isEmpty()) throw new IllegalStateException("Journal " + jnlNo + " has no lines.");
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (GlJournalLine l : lines) { dr = dr.add(nz(l.drAmt)); cr = cr.add(nz(l.crAmt)); }
        if (dr.compareTo(cr) != 0) throw new IllegalStateException("Journal " + jnlNo + " is unbalanced — cannot commit.");
        if (dr.signum() == 0)      throw new IllegalStateException("Journal " + jnlNo + " has zero value.");

        int yearNo = yearNo(companyNo, yrNo);
        int period = periodFor(companyNo, yrNo, jnlDate);

        for (GlJournalLine l : lines) {
            Integer seq = jdbc.queryForObject(GlPostingSql.NEXT_SEQ_NO, Integer.class,
                companyNo, yearNo, l.acctMainNo, l.acctSubNo, "F", Date.valueOf(jnlDate));
            jdbc.update(GlPostingSql.INSERT_GLTRX,
                gltrxParams(companyNo, yearNo, "GN", jnlNo, l, jnlDate, seq == null ? 1 : seq, userId));
            ensureGlbalRow(companyNo, yearNo, l.acctMainNo, l.acctSubNo, userId);
            jdbc.update(GlPostingSql.updateGlbalBal(period),
                nz(l.drAmt).subtract(nz(l.crAmt)), companyNo, yearNo, l.acctMainNo, l.acctSubNo);
        }

        LocalTime now = LocalTime.now();
        jdbc.update(GlPostingSql.MARK_POSTED, terminalNo, trunc(userId, 15), Date.valueOf(LocalDate.now()),
            now.getHour(), now.getMinute(), now.getSecond(), companyNo, yrNo, jnlNo);
        return new PostResult(1, lines.size(), 0, "Committed journal " + jnlNo + " (" + lines.size() + " lines).");
    }

    /** Commit all balanced, draft general journals for the company/year. */
    @Transactional
    public PostResult postAll(int companyNo, int yrNo, String userId, int terminalNo) {
        int j = 0, lines = 0, skipped = 0;
        for (Postable p : findPostable(companyNo, yrNo)) {
            if (!p.balanced()) { skipped++; continue; }
            PostResult r = postJournal(companyNo, yrNo, p.jnlNo(), userId, terminalNo);
            j += r.journalsPosted(); lines += r.linesPosted();
        }
        return new PostResult(j, lines, skipped,
            "Committed " + j + " journal(s), " + lines + " line(s)"
            + (skipped > 0 ? "; skipped " + skipped + " unbalanced." : "."));
    }

    /** Commit a specific set of balanced, draft general journals (individual/multi-select from the entry screen). */
    @Transactional
    public PostResult postSelected(int companyNo, int yrNo, List<Integer> jnlNos, String userId, int terminalNo) {
        int j = 0, lines = 0, skipped = 0;
        for (int jnlNo : jnlNos) {
            try {
                PostResult r = postJournal(companyNo, yrNo, jnlNo, userId, terminalNo);
                j += r.journalsPosted(); lines += r.linesPosted();
            } catch (IllegalStateException ex) {
                skipped++;
            }
        }
        return new PostResult(j, lines, skipped,
            "Committed " + j + " journal(s), " + lines + " line(s)"
            + (skipped > 0 ? "; skipped " + skipped + "." : "."));
    }

    // ── Standing journals — "Run" (glgn06/07 core + glgn09 UPDATE-STANDING-JNL) ──
    //
    // Running a standing journal posts its current occurrence to the ledger
    // under a freshly-allocated run number (gldates.last_aud_no_std_jnl —
    // distinct from the template's own jnl_no, which never changes) with
    // gltrx.source='ST' (glgn07 SET-JNL-SOURCE), then rolls the template's
    // jnl_date forward per post_freq, or expires it (jnl_status='X',
    // posted_flag='Y') when there's no next occurrence.

    /** Balanced, active standing journals available to run. */
    public List<Postable> findRunnableStanding(int companyNo, int yrNo) {
        return jdbc.query(GlPostingSql.SELECT_RUNNABLE_STANDING, (rs, i) -> {
            BigDecimal dr = nz(rs.getBigDecimal("total_dr"));
            BigDecimal cr = nz(rs.getBigDecimal("total_cr"));
            boolean bal = dr.compareTo(cr) == 0 && dr.signum() > 0;
            return new Postable(rs.getInt("jnl_no"), toLocal(rs.getDate("jnl_date")),
                rs.getString("note_1"), dr, cr, bal);
        }, companyNo, yrNo);
    }

    /** Run one standing journal's current occurrence. Throws if it is not runnable. */
    @Transactional
    public PostResult runStanding(int companyNo, int yrNo, int jnlNo, String userId, int terminalNo) {
        Map<String, Object> h = jdbc.queryForMap(
            "SELECT jnl_date, posted_flag, jnl_status, post_freq, expiry_date FROM glgnhed " +
            "WHERE company_no=? AND jnl_type='S' AND yr_no=? AND jnl_no=?",
            companyNo, yrNo, jnlNo);
        String posted = str(h.get("posted_flag")), status = str(h.get("jnl_status"));
        if ("Y".equals(posted)) throw new IllegalStateException("Standing journal " + jnlNo + " is already expired or cancelled.");
        if (!status.isBlank())  throw new IllegalStateException("Standing journal " + jnlNo + " status '" + status + "' — cannot run.");
        LocalDate jnlDate = ((Date) h.get("jnl_date")).toLocalDate();

        List<GlJournalLine> lines = journals.loadLines(companyNo, "S", yrNo, jnlNo);
        if (lines.isEmpty()) throw new IllegalStateException("Standing journal " + jnlNo + " has no lines.");
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (GlJournalLine l : lines) { dr = dr.add(nz(l.drAmt)); cr = cr.add(nz(l.crAmt)); }
        if (dr.compareTo(cr) != 0) throw new IllegalStateException("Standing journal " + jnlNo + " is unbalanced — cannot run.");
        if (dr.signum() == 0)      throw new IllegalStateException("Standing journal " + jnlNo + " has zero value.");

        int yearNo = yearNo(companyNo, yrNo);
        int period = periodFor(companyNo, yrNo, jnlDate);

        jdbc.update(GlPostingSql.BUMP_STD_RUN_NO, companyNo, yrNo);
        Integer runNo = jdbc.queryForObject(GlPostingSql.READ_STD_RUN_NO, Integer.class, companyNo, yrNo);
        int genJnlNo = runNo == null ? 1 : runNo;

        for (GlJournalLine l : lines) {
            Integer seq = jdbc.queryForObject(GlPostingSql.NEXT_SEQ_NO, Integer.class,
                companyNo, yearNo, l.acctMainNo, l.acctSubNo, "F", Date.valueOf(jnlDate));
            jdbc.update(GlPostingSql.INSERT_GLTRX,
                gltrxParams(companyNo, yearNo, "ST", genJnlNo, l, jnlDate, seq == null ? 1 : seq, userId));
            ensureGlbalRow(companyNo, yearNo, l.acctMainNo, l.acctSubNo, userId);
            jdbc.update(GlPostingSql.updateGlbalBal(period),
                nz(l.drAmt).subtract(nz(l.crAmt)), companyNo, yearNo, l.acctMainNo, l.acctSubNo);
        }

        String rollMsg = rollStandingForward(companyNo, yrNo, jnlNo, jnlDate,
            str(h.get("post_freq")), toLocal((Date) h.get("expiry_date")));
        return new PostResult(1, lines.size(), 0, "Ran standing journal " + jnlNo + " (" + lines.size() + " lines)" + rollMsg);
    }

    /** Run all balanced, active standing journals for the company/year. */
    @Transactional
    public PostResult runAllStanding(int companyNo, int yrNo, String userId, int terminalNo) {
        int j = 0, lines = 0, skipped = 0;
        for (Postable p : findRunnableStanding(companyNo, yrNo)) {
            if (!p.balanced()) { skipped++; continue; }
            PostResult r = runStanding(companyNo, yrNo, p.jnlNo(), userId, terminalNo);
            j += r.journalsPosted(); lines += r.linesPosted();
        }
        return new PostResult(j, lines, skipped,
            "Ran " + j + " standing journal(s), " + lines + " line(s)"
            + (skipped > 0 ? "; skipped " + skipped + " unbalanced." : "."));
    }

    /** Run a specific set of balanced, active standing journals (individual/multi-select from the entry screen). */
    @Transactional
    public PostResult runSelectedStanding(int companyNo, int yrNo, List<Integer> jnlNos, String userId, int terminalNo) {
        int j = 0, lines = 0, skipped = 0;
        for (int jnlNo : jnlNos) {
            try {
                PostResult r = runStanding(companyNo, yrNo, jnlNo, userId, terminalNo);
                j += r.journalsPosted(); lines += r.linesPosted();
            } catch (IllegalStateException ex) {
                skipped++;
            }
        }
        return new PostResult(j, lines, skipped,
            "Ran " + j + " standing journal(s), " + lines + " line(s)"
            + (skipped > 0 ? "; skipped " + skipped + "." : "."));
    }

    /**
     * Advance the template to its next occurrence, or expire it
     * ({@code jnl_status='X'}, {@code posted_flag='Y'}) when there isn't one —
     * past its expiry date, no recurrence (post_freq blank/"O", one-off), or
     * the next date would cross into a new fiscal year (cross-year template
     * roll-forward isn't automated yet; the standing journal must be
     * recreated for the next year instead).
     */
    private String rollStandingForward(int companyNo, int yrNo, int jnlNo, LocalDate current,
                                       String postFreq, LocalDate expiry) {
        LocalDate next = nextStandingDate(current, postFreq);
        if (next == null || (expiry != null && next.isAfter(expiry))) {
            jdbc.update(GlPostingSql.EXPIRE_STANDING, companyNo, yrNo, jnlNo);
            return " — no further occurrences; expired.";
        }
        Map<String, Object> yr = jdbc.queryForMap(
            "SELECT yr_end_date FROM gldates WHERE company_no=? AND yr_no=?", companyNo, yrNo);
        LocalDate yrEnd = toLocal((Date) yr.get("yr_end_date"));
        if (yrEnd != null && next.isAfter(yrEnd)) {
            jdbc.update(GlPostingSql.EXPIRE_STANDING, companyNo, yrNo, jnlNo);
            return " — next occurrence (" + next + ") crosses into next fiscal year; expired "
                + "(recreate it for next year — cross-year roll-forward isn't automated yet).";
        }
        jdbc.update(GlPostingSql.UPDATE_STANDING_DATE, Date.valueOf(next), companyNo, yrNo, jnlNo);
        return " — next occurrence " + next + ".";
    }

    /** Next occurrence date per post_freq (M/B/Q/H/A), preserving "last day of month"; null = one-off, no recurrence. */
    private static LocalDate nextStandingDate(LocalDate current, String postFreq) {
        int months = switch (postFreq == null ? "" : postFreq.trim().toUpperCase()) {
            case "M" -> 1;
            case "B" -> 2;
            case "Q" -> 3;
            case "H" -> 6;
            case "A" -> 12;
            default  -> 0;   // "O" one-off, or unset
        };
        if (months == 0) return null;
        boolean lastDayOfMonth = current.getDayOfMonth() == current.lengthOfMonth();
        LocalDate next = current.plusMonths(months);
        return lastDayOfMonth ? next.withDayOfMonth(next.lengthOfMonth()) : next;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private int yearNo(int companyNo, int yrNo) {
        Integer y = jdbc.queryForObject(GlPostingSql.SELECT_YEAR_NO, Integer.class, companyNo, yrNo);
        if (y == null) throw new IllegalStateException("No gldates row for year " + yrNo + ".");
        return y;
    }

    /** Period (1-13) whose start..end contains the date; else first end ≥ date; else 1. */
    private int periodFor(int companyNo, int yrNo, LocalDate date) {
        Map<String, Object> row = jdbc.queryForMap(GlPostingSql.SELECT_PERIODS, companyNo, yrNo);
        int fallback = 0;
        for (int p = 1; p <= 13; p++) {
            LocalDate st = toLocal((Date) row.get(String.format("period_start_%02d", p)));
            LocalDate en = toLocal((Date) row.get(String.format("period_end_%02d", p)));
            if (st != null && en != null && !date.isBefore(st) && !date.isAfter(en)) return p;
            if (fallback == 0 && en != null && !en.isBefore(date)) fallback = p;
        }
        return fallback == 0 ? 1 : fallback;
    }

    /** Create a zero glbal row for the account if one doesn't exist. */
    private void ensureGlbalRow(int companyNo, int yearNo, int main, int sub, String userId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM glbal WHERE company_no=? AND year_no=? AND acct_main_no=? AND acct_sub_no=?",
            Integer.class, companyNo, yearNo, main, sub);
        if (n != null && n > 0) return;
        ensureGlbalMeta();
        LocalTime now = LocalTime.now();
        Object[] params = new Object[glbalCols.size()];
        for (int i = 0; i < glbalCols.size(); i++) {
            String name = glbalCols.get(i)[0], type = glbalCols.get(i)[1];
            params[i] = switch (name) {
                case "company_no"   -> companyNo;
                case "year_no"      -> yearNo;
                case "acct_main_no" -> main;
                case "acct_sub_no"  -> sub;
                case "audit_user_id"-> trunc(userId, 15);
                case "audit_date"   -> Date.valueOf(LocalDate.now());
                case "audit_time_hr"-> now.getHour();
                case "audit_time_min"-> now.getMinute();
                case "audit_time_sec"-> now.getSecond();
                default             -> typeDefault(type);
            };
        }
        jdbc.update(glbalInsertSql, params);
    }

    private void ensureGlbalMeta() {
        if (glbalInsertSql != null) return;
        synchronized (this) {
            if (glbalInsertSql != null) return;
            List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = 'glbal' ORDER BY ordinal_position");
            List<String[]> meta = new ArrayList<>();
            StringBuilder names = new StringBuilder(), qs = new StringBuilder();
            for (Map<String, Object> c : cols) {
                String name = str(c.get("column_name")), type = str(c.get("data_type")).toLowerCase();
                meta.add(new String[]{name, type});
                if (names.length() > 0) { names.append(','); qs.append(','); }
                names.append(name); qs.append('?');
            }
            this.glbalCols = meta;
            this.glbalInsertSql = "INSERT INTO glbal (" + names + ") VALUES (" + qs + ")";
        }
    }

    private static Object typeDefault(String dataType) {
        return switch (dataType) {
            case "decimal", "numeric" -> BigDecimal.ZERO;
            case "date", "datetime", "timestamp" -> Date.valueOf(DATE_ZERO);
            case "int", "integer", "smallint", "tinyint", "mediumint", "bigint" -> 0;
            default -> "";   // char/varchar/text
        };
    }

    private Object[] gltrxParams(int companyNo, int yearNo, String source, int jnlNo, GlJournalLine l,
                                 LocalDate jnlDate, int seq, String userId) {
        LocalTime now = LocalTime.now();
        return new Object[] {
            companyNo, yearNo, source, jnlNo, " ", l.acctMainNo, l.acctSubNo,
            "F", Date.valueOf(jnlDate), seq, nz(l.drAmt), nz(l.crAmt), BigDecimal.ZERO, BigDecimal.ZERO,
            trunc(l.ref, 40), 0L, "", Date.valueOf(DATE_ZERO), "", "", "", 0, "",
            "", 0, 0, "", 0, "", "", "N", 0, 0, 0, "", "", "", 0, 0, "", "",
            trunc(userId, 15), Date.valueOf(LocalDate.now()), now.getHour(), now.getMinute(), now.getSecond(), 0
        };
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static LocalDate toLocal(Date d) {
        if (d == null) return null;
        LocalDate ld = d.toLocalDate();
        return ld.equals(DATE_ZERO) ? null : ld;
    }
    private static String str(Object o) { return o == null ? "" : o.toString().trim(); }
    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
