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

import com.landmarksoftware.model.GlJournalHeader;
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
 * GLGN01 — General / Standing journal entry against {@code glgnhed} / {@code glgnlin}.
 *
 * <p>Entry only — journals are captured as drafts ({@code jnl_status=' '},
 * {@code posted_flag='N'}); a separate commit (posting) step later writes
 * them to {@code gltrx}/{@code glbal}. The debit=credit rule is <b>not</b>
 * enforced here (mirrors COBOL) — the balance is surfaced to the UI and the
 * commit step rejects unbalanced journals.
 */
@Service
public class GlJournalService {

    /** COBOL date-zero sentinel for NOT NULL DATE columns with no value. */
    private static final LocalDate DATE_ZERO = LocalDate.of(1899, 12, 31);

    private final JdbcTemplate jdbc;

    public GlJournalService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ── Read ─────────────────────────────────────────────────────────────

    /** Journal headers (without lines) for the given type/year. */
    public List<GlJournalHeader> findHeaders(int companyNo, int yrNo, String jnlType) {
        return jdbc.query(GlJournalSql.SELECT_HEADERS, (rs, i) -> {
            GlJournalHeader h = new GlJournalHeader();
            h.jnlType         = rs.getString("jnl_type");
            h.yrNo            = rs.getInt("yr_no");
            h.jnlNo           = rs.getInt("jnl_no");
            h.jnlDate         = toLocal(rs.getDate("jnl_date"));
            h.autoReverseFlag = rs.getString("auto_reverse_flag");
            h.note1           = rs.getString("note_1");
            h.note2           = rs.getString("note_2");
            h.jnlStatus       = rs.getString("jnl_status");
            h.totalDr         = nz(rs.getBigDecimal("total_dr"));
            h.totalCr         = nz(rs.getBigDecimal("total_cr"));
            h.postFreq        = rs.getString("post_freq");
            h.expiryDate      = toLocal(rs.getDate("expiry_date"));
            return h;
        }, companyNo, jnlType, yrNo);
    }

    /** Load the lines for one journal. */
    public List<GlJournalLine> loadLines(int companyNo, String jnlType, int yrNo, int jnlNo) {
        return jdbc.query(GlJournalSql.SELECT_LINES, (rs, i) -> {
            GlJournalLine l = new GlJournalLine();
            l.lineNo      = rs.getInt("line_no");
            l.acctMainNo  = rs.getInt("acct_main_no");
            l.acctSubNo   = rs.getInt("acct_sub_no");
            l.drAmt       = nz(rs.getBigDecimal("dr_amt"));
            l.crAmt       = nz(rs.getBigDecimal("cr_amt"));
            l.ref         = rs.getString("ref");
            l.zeroAmtFlag = rs.getString("zero_amt_flag");
            l.acctDesc    = rs.getString("desc1") == null ? "" : rs.getString("desc1");
            return l;
        }, companyNo, jnlType, yrNo, jnlNo);
    }

    // ── Inquiry (GLGN02 + GLGN08 selection criteria, combined) ────────────

    /**
     * Journal Inquiry search — glgnhed rows (draft, committed, cancelled or
     * expired) matching the given filters. Mirrors GLGN02S1's fields
     * (financial year, journal-no range, unposted-only) plus the date-range
     * and user-id-range fields GLGN06/08 offer when building a posting run,
     * since GLGN02 alone has no date or user filter. Any of the range/user
     * params may be {@code null} to leave that bound open; {@code status}
     * is one of "ALL" (default), "DRAFT", "COMMITTED", "CANCELLED", "EXPIRED".
     */
    public List<GlJournalHeader> searchInquiry(int companyNo, String jnlType, int yrNo,
            Integer startJnlNo, Integer endJnlNo, LocalDate startDate, LocalDate endDate,
            String startUserId, String endUserId, String status) {
        StringBuilder sql = new StringBuilder(
            "SELECT jnl_type, yr_no, jnl_no, jnl_date, auto_reverse_flag, note_1, note_2, " +
            "       jnl_status, total_dr, total_cr, post_freq, expiry_date, audit_user_id " +
            "FROM glgnhed WHERE company_no=? AND jnl_type=? AND yr_no=?");
        List<Object> params = new ArrayList<>(List.of(companyNo, jnlType, yrNo));
        if (startJnlNo != null) { sql.append(" AND jnl_no>=?"); params.add(startJnlNo); }
        if (endJnlNo != null)   { sql.append(" AND jnl_no<=?"); params.add(endJnlNo); }
        if (startDate != null)  { sql.append(" AND jnl_date>=?"); params.add(java.sql.Date.valueOf(startDate)); }
        if (endDate != null)    { sql.append(" AND jnl_date<=?"); params.add(java.sql.Date.valueOf(endDate)); }
        if (startUserId != null && !startUserId.isBlank()) { sql.append(" AND audit_user_id>=?"); params.add(startUserId.trim()); }
        if (endUserId != null && !endUserId.isBlank())     { sql.append(" AND audit_user_id<=?"); params.add(endUserId.trim()); }
        switch (status == null ? "ALL" : status.toUpperCase()) {
            case "DRAFT"     -> sql.append(" AND posted_flag<>'Y'");
            case "COMMITTED" -> sql.append(" AND posted_flag='Y' AND jnl_status='P'");
            case "CANCELLED" -> sql.append(" AND posted_flag='Y' AND jnl_status='C'");
            case "EXPIRED"   -> sql.append(" AND posted_flag='Y' AND jnl_status='X'");
            default -> { /* ALL — no extra filter */ }
        }
        sql.append(" ORDER BY jnl_no DESC");
        return jdbc.query(sql.toString(), (rs, i) -> {
            GlJournalHeader h = new GlJournalHeader();
            h.jnlType         = rs.getString("jnl_type");
            h.yrNo            = rs.getInt("yr_no");
            h.jnlNo           = rs.getInt("jnl_no");
            h.jnlDate         = toLocal(rs.getDate("jnl_date"));
            h.autoReverseFlag = rs.getString("auto_reverse_flag");
            h.note1           = rs.getString("note_1");
            h.note2           = rs.getString("note_2");
            h.jnlStatus       = rs.getString("jnl_status");
            h.totalDr         = nz(rs.getBigDecimal("total_dr"));
            h.totalCr         = nz(rs.getBigDecimal("total_cr"));
            h.postFreq        = rs.getString("post_freq");
            h.expiryDate      = toLocal(rs.getDate("expiry_date"));
            h.auditUserId     = rs.getString("audit_user_id");
            return h;
        }, params.toArray());
    }

    /**
     * Creates a new draft General journal that reverses a committed
     * journal's lines (debit and credit swapped on every line), for the
     * user to review and commit like any hand-entered journal — same
     * account, opposite side. Only committed journals ({@code jnl_status='P'})
     * can be reversed; there is no COBOL equivalent (GLGN08 is a print-only
     * "Journal Register" step in the posting pipeline, not a reversal
     * engine — see GLGN08 own-code) so this is new behaviour, not a port.
     */
    @Transactional
    public int createReversal(int companyNo, String origJnlType, int origYrNo, int origJnlNo,
                              LocalDate reversalDate, String userId, int terminalNo) {
        Map<String, Object> orig = jdbc.queryForMap(
            "SELECT jnl_status, note_1 FROM glgnhed WHERE company_no=? AND jnl_type=? AND yr_no=? AND jnl_no=?",
            companyNo, origJnlType, origYrNo, origJnlNo);
        String status = orig.get("jnl_status") == null ? "" : orig.get("jnl_status").toString().trim();
        if (!"P".equals(status))
            throw new IllegalStateException("Journal " + origJnlNo + " is not committed — only committed journals can be reversed.");

        List<GlJournalLine> origLines = loadLines(companyNo, origJnlType, origYrNo, origJnlNo);
        if (origLines.isEmpty()) throw new IllegalStateException("Journal " + origJnlNo + " has no lines to reverse.");

        String origNote = orig.get("note_1") == null ? "" : orig.get("note_1").toString().trim();
        GlJournalHeader h = new GlJournalHeader();
        h.jnlType = "G";
        h.yrNo = origYrNo;
        h.jnlDate = reversalDate;
        h.note1 = trunc("Reversal of jnl " + origJnlNo + ("S".equals(origJnlType) ? " (standing)" : "")
            + (origNote.isBlank() ? "" : " — " + origNote), 55);
        h.autoReverseFlag = "N";
        for (GlJournalLine ol : origLines) {
            GlJournalLine nl = new GlJournalLine();
            nl.acctMainNo = ol.acctMainNo;
            nl.acctSubNo  = ol.acctSubNo;
            nl.drAmt = ol.crAmt;   // swapped — a reversal is the mirror image
            nl.crAmt = ol.drAmt;
            nl.ref   = ol.ref;
            h.lines.add(nl);
        }
        return save(companyNo, h, userId, terminalNo);
    }

    public boolean journalExists(int companyNo, String jnlType, int yrNo, int jnlNo) {
        Integer n = jdbc.queryForObject(GlJournalSql.COUNT_HEADER, Integer.class,
            companyNo, jnlType, yrNo, jnlNo);
        return n != null && n > 0;
    }

    // ── Validation ───────────────────────────────────────────────────────

    /** Result of an account lookup: valid + description, or invalid + reason. */
    public record AccountResult(boolean ok, String message, String desc) {}

    /** A postable account for the picker. */
    public record AccountRow(int mainNo, int subNo, String desc) {
        public String display() { return subNo == 0 ? String.valueOf(mainNo) : mainNo + "." + subNo; }
    }

    /** All postable financial accounts for the company (account picker). */
    public List<AccountRow> listPostableAccounts(int companyNo) {
        return jdbc.query(GlJournalSql.LIST_POSTABLE_ACCOUNTS, (rs, i) ->
            new AccountRow(rs.getInt("acct_main_no"), rs.getInt("acct_sub_no"),
                rs.getString("desc1") == null ? "" : rs.getString("desc1")),
            companyNo);
    }

    /**
     * Validate an account against glchart (CHECK-GL-ACCT). Returns the
     * description when postable, or a user-facing reason when not.
     */
    public AccountResult checkAccount(int companyNo, int mainNo, int subNo) {
        List<AccountResult> r = jdbc.query(GlJournalSql.SELECT_ACCOUNT, (rs, i) -> {
            String desc     = rs.getString("desc1");
            String finFlag  = rs.getString("fin_acct_flag");
            String posting  = rs.getString("posting_flag");
            String nonPost  = rs.getString("all_non_posting_flag");
            if ("Y".equals(nonPost))
                return new AccountResult(false, "Account " + acct(mainNo, subNo) + " is a header/group account (not for posting).", desc);
            if ("N".equals(posting) || "S".equals(posting))
                return new AccountResult(false, "Account " + acct(mainNo, subNo) + " is not open for posting.", desc);
            if (!"Y".equals(finFlag))
                return new AccountResult(false, "Account " + acct(mainNo, subNo) + " is not a financial account.", desc);
            return new AccountResult(true, "", desc == null ? "" : desc);
        }, companyNo, mainNo, subNo);
        if (r.isEmpty())
            return new AccountResult(false, "Account " + acct(mainNo, subNo) + " is not on file.", "");
        return r.get(0);
    }

    /**
     * Validate the journal date against gldates (CHECK-YR-NO + CHECK-JNL-DATE):
     * year must exist, not be year-ended, and the date must fall in the year.
     * Returns {@code null} when OK, else a user-facing message.
     */
    public String checkJournalDate(int companyNo, int yrNo, LocalDate date) {
        if (date == null) return "Journal date is required.";
        List<String> msgs = jdbc.query(GlJournalSql.SELECT_GLDATES, (rs, i) -> {
            int yrEnd       = rs.getInt("yr_end_status");
            LocalDate start = toLocal(rs.getDate("yr_start_date"));
            LocalDate end   = toLocal(rs.getDate("yr_end_date"));
            if (yrEnd != 0)
                return "Year end has been run for year " + yrNo + " — no more journals.";
            if (start != null && date.isBefore(start))
                return "Date is before the start of the fiscal year (" + start + ").";
            if (end != null && date.isAfter(end))
                return "Date is after the end of the fiscal year (" + end + ").";
            return null;
        }, companyNo, yrNo);
        if (msgs.isEmpty()) return "No fiscal year set up for year " + yrNo + ".";
        return msgs.get(0);   // null = OK
    }

    // ── Journal-number allocation (serialized on gldates within the tx) ──

    @Transactional
    public int allocateNextJournalNo(int companyNo, int yrNo, String jnlType) {
        Integer n;
        if ("S".equals(jnlType)) {
            // Standing: company-level counter on cpcoyco (cross-year).
            jdbc.update(GlJournalSql.BUMP_STD_JNL_NO, companyNo);
            n = jdbc.queryForObject(GlJournalSql.READ_STD_JNL_NO, Integer.class, companyNo);
        } else {
            // General: per-company-and-year counter on gldates.
            jdbc.update(GlJournalSql.BUMP_GEN_JNL_NO, companyNo, yrNo);
            n = jdbc.queryForObject(GlJournalSql.READ_GEN_JNL_NO, Integer.class, companyNo, yrNo);
        }
        return n == null ? 0 : n;
    }

    // ── Write ────────────────────────────────────────────────────────────

    /**
     * Save a journal (insert new or replace existing) with its lines in one
     * transaction. Allocates a journal number when {@code header.jnlNo == 0}.
     * Lines are renumbered 1..N. Returns the (possibly newly allocated) jnl_no.
     */
    @Transactional
    public int save(int companyNo, GlJournalHeader h, String userId, int terminalNo) {
        boolean isNew = h.jnlNo <= 0;
        if (isNew) {
            h.jnlNo = allocateNextJournalNo(companyNo, h.yrNo, h.jnlType);
        } else {
            // Replace lines + header in place.
            jdbc.update(GlJournalSql.DELETE_LINES, companyNo, h.jnlType, h.yrNo, h.jnlNo);
            jdbc.update(GlJournalSql.DELETE_HEADER, companyNo, h.jnlType, h.yrNo, h.jnlNo);
        }
        h.recomputeTotals();
        jdbc.update(GlJournalSql.INSERT_HEADER, headerParams(companyNo, h, userId, terminalNo));

        int lineNo = 0;
        for (GlJournalLine l : h.lines) {
            l.lineNo = ++lineNo;
            jdbc.update(GlJournalSql.INSERT_LINE, lineParams(companyNo, h, l, userId));
        }
        return h.jnlNo;
    }

    /** Cancel a journal — delete its lines, mark header status "C" (CANCEL-THIS-JOURNAL). */
    @Transactional
    public void cancel(int companyNo, String jnlType, int yrNo, int jnlNo) {
        jdbc.update(GlJournalSql.DELETE_LINES, companyNo, jnlType, yrNo, jnlNo);
        jdbc.update(GlJournalSql.UPDATE_STATUS, "C", "Y", companyNo, jnlType, yrNo, jnlNo);
    }

    // ── Param builders (order MUST match GlJournalSql column lists) ──────

    private Object[] headerParams(int companyNo, GlJournalHeader h, String userId, int terminalNo) {
        LocalTime now = LocalTime.now();
        LocalDate today = LocalDate.now();
        return new Object[] {
            companyNo,                          // company_no
            h.jnlType,                          // posted_jnl_type
            "N",                                // posted_flag (unposted)
            h.jnlType,                          // jnl_type
            h.yrNo,                             // yr_no
            h.jnlNo,                            // jnl_no
            sqlDate(h.jnlDate),                 // jnl_date
            nvl(h.autoReverseFlag, "N"),        // auto_reverse_flag
            nz(h.totalDr),                      // total_dr
            nz(h.totalCr),                      // total_cr
            nvl(h.jnlStatus, " "),              // jnl_status
            "",                                 // for_curr_code
            BigDecimal.ZERO,                    // for_curr_rate
            "",                                 // for_curr_maths_ind
            "",                                 // local_parent_ind
            "N",                                // sub_coy_jnl_flag
            "N",                                // cash_acct_jnl_flag
            0,                                  // import_batch_no
            0,                                  // import_company_no
            trunc(h.note1, 55),                 // note_1
            trunc(h.note2, 55),                 // note_2
            "N",                                // gst_flag
            "",                                 // default_tax_code
            "",                                 // tax_pricing_ind
            terminalNo,                         // post_terminal_no
            h.isStanding() ? nvl(h.postFreq, "") : "",   // post_freq
            "",                                 // last_aud_source
            0,                                  // last_aud_jnl_no
            "",                                 // last_aud_reversal_ind
            sqlDate(h.isStanding() ? h.expiryDate : null), // expiry_date
            BigDecimal.ZERO,                    // total_fc_dr
            BigDecimal.ZERO,                    // total_fc_cr
            BigDecimal.ZERO,                    // total_profit
            BigDecimal.ZERO,                    // total_fc_profit
            trunc(userId, 15),                  // audit_user_id
            Date.valueOf(today),                // audit_date
            now.getHour(),                      // audit_time_hr
            now.getMinute(),                    // audit_time_min
            now.getSecond(),                    // audit_time_sec
            0                                   // audit_time_hun
        };
    }

    private Object[] lineParams(int companyNo, GlJournalHeader h, GlJournalLine l, String userId) {
        LocalTime now = LocalTime.now();
        LocalDate today = LocalDate.now();
        return new Object[] {
            companyNo,                          // company_no
            h.jnlType,                          // jnl_type
            h.yrNo,                             // yr_no
            h.jnlNo,                            // jnl_no
            l.lineNo,                           // line_no
            l.acctMainNo,                       // acct_main_no
            l.acctSubNo,                        // acct_sub_no
            nz(l.drAmt),                        // dr_amt
            nz(l.crAmt),                        // cr_amt
            "",                                 // trx_type
            trunc(l.ref, 40),                   // ref
            "",                                 // tax_code
            BigDecimal.ZERO,                    // tax_amt
            BigDecimal.ZERO,                    // gst_gross_amt
            0,                                  // tax_clear_acct_main
            0,                                  // tax_clear_acct_sub
            "",                                 // sales_purch_ind
            "N",                                // gl_recon_acct_flag
            "",                                 // gl_recon_id
            0L,                                 // note_no
            h.isStanding() ? nvl(l.zeroAmtFlag, "N") : "N", // zero_amt_flag
            trunc(userId, 15),                  // audit_user_id
            Date.valueOf(today),                // audit_date
            now.getHour(),                      // audit_time_hr
            now.getMinute(),                    // audit_time_min
            now.getSecond(),                    // audit_time_sec
            0                                   // audit_time_hun
        };
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static Date sqlDate(LocalDate d) {
        return Date.valueOf(d == null ? DATE_ZERO : d);
    }
    private static LocalDate toLocal(Date d) {
        if (d == null) return null;
        LocalDate ld = d.toLocalDate();
        return ld.equals(DATE_ZERO) ? null : ld;
    }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String nvl(String s, String dflt) { return (s == null || s.isEmpty()) ? dflt : s; }
    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
    private static String acct(int main, int sub) {
        return sub == 0 ? String.valueOf(main) : main + "." + sub;
    }
}
