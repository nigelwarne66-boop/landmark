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
package com.landmarksoftware.service.bas;

import com.landmarksoftware.model.bas.BasReasonCode;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.landmarksoftware.db.tables.Cpbascd.CPBASCD;

/**
 * CPBA02 — BAS Reason Codes Maintenance service. All JDBC/jOOQ for
 * {@code cpbascd} CRUD — the UI controller is SQL-free.
 *
 * <p>Table: {@code cpbascd}  PK: {@code (bas_code, reason_code)}. No
 * {@code company_no} column — this table is global, not company-scoped
 * (confirmed from the COBOL {@code .fd}).
 *
 * <p>{@code cpbascd} also holds {@code 7A}/{@code 7C}/{@code 7D} rows used
 * by CPBA10 — every write method here is only ever called by the CPBA02 UI
 * with {@code basCode} restricted to {@code T4} or {@code F4} by the
 * controller's category selector. This service does not itself enforce that
 * restriction (mirrors COBOL — CPBA02 simply never offers the other
 * categories on its selector).
 */
@Service
public class BasReasonCodeService {

    private final DSLContext dsl;

    public BasReasonCodeService(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ── List ──────────────────────────────────────────────────────────────

    /** All reason codes for a category (T4 or F4), ordered by reason_code. */
    public List<BasReasonCode> findByCategory(String basCode) {
        return dsl.selectFrom(CPBASCD)
            .where(CPBASCD.BAS_CODE.eq(basCode))
            .orderBy(CPBASCD.REASON_CODE)
            .fetch(r -> new BasReasonCode(
                trim(r.get(CPBASCD.BAS_CODE)),
                trim(r.get(CPBASCD.REASON_CODE)),
                trim(r.get(CPBASCD.DESC_1)),
                trim(r.get(CPBASCD.DESC_2))));
    }

    // ── Validation ────────────────────────────────────────────────────────

    public boolean exists(String basCode, String reasonCode) {
        return dsl.fetchExists(
            dsl.selectFrom(CPBASCD)
               .where(CPBASCD.BAS_CODE.eq(basCode))
               .and(CPBASCD.REASON_CODE.eq(reasonCode)));
    }

    // ── Write operations ─────────────────────────────────────────────────

    /** Insert a new reason code. Caller must verify no duplicate exists first. */
    @Transactional
    public void insert(String basCode, String reasonCode, String desc1, String desc2, String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        dsl.insertInto(CPBASCD)
           .set(CPBASCD.BAS_CODE, trimUp(basCode, 2))
           .set(CPBASCD.REASON_CODE, trimUp(reasonCode, 2))
           .set(CPBASCD.DESC_1, trim(desc1, 35))
           .set(CPBASCD.DESC_2, trim(desc2, 35))
           .set(CPBASCD.NOTE_NO, 0L)
           .set(CPBASCD.AUDIT_USER_ID, trim(userId, 15))
           .set(CPBASCD.AUDIT_DATE, today)
           .set(CPBASCD.AUDIT_TIME_HR, now.getHour())
           .set(CPBASCD.AUDIT_TIME_MIN, now.getMinute())
           .set(CPBASCD.AUDIT_TIME_SEC, now.getSecond())
           .set(CPBASCD.AUDIT_TIME_HUN, 0)
           .execute();
    }

    /**
     * Update the description of an existing reason code. bas_code and
     * reason_code (the PK) are never changed by an update — the dialog's
     * key field is read-only, matching COBOL.
     */
    @Transactional
    public void update(String basCode, String reasonCode, String desc1, String desc2, String userId) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        dsl.update(CPBASCD)
           .set(CPBASCD.DESC_1, trim(desc1, 35))
           .set(CPBASCD.DESC_2, trim(desc2, 35))
           .set(CPBASCD.AUDIT_USER_ID, trim(userId, 15))
           .set(CPBASCD.AUDIT_DATE, today)
           .set(CPBASCD.AUDIT_TIME_HR, now.getHour())
           .set(CPBASCD.AUDIT_TIME_MIN, now.getMinute())
           .set(CPBASCD.AUDIT_TIME_SEC, now.getSecond())
           .set(CPBASCD.AUDIT_TIME_HUN, 0)
           .where(CPBASCD.BAS_CODE.eq(basCode))
           .and(CPBASCD.REASON_CODE.eq(reasonCode))
           .execute();
    }

    /**
     * Physical delete — no guard against usage elsewhere. Mirrors COBOL
     * CPBA02, which does not check the BAS header/transaction tables before
     * deleting a reason code.
     */
    @Transactional
    public void delete(String basCode, String reasonCode) {
        dsl.deleteFrom(CPBASCD)
           .where(CPBASCD.BAS_CODE.eq(basCode))
           .and(CPBASCD.REASON_CODE.eq(reasonCode))
           .execute();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String trim(String s, int max) {
        String t = trim(s);
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String trimUp(String s, int max) {
        return trim(s, max).toUpperCase();
    }
}
