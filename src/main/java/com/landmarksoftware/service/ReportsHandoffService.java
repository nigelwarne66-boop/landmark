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

import com.landmarksoftware.model.AppSession;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Wave 7 — desktop-to-reports session handoff (DESIGN_SYSTEM.md §10):
 * "Session handoff from desktop to reporting suite carrying user, company
 * and financial year, opening on the matching module."
 *
 * <p>{@code cp_handoff_token} is a Java-managed work table — ephemeral (a
 * row is only ever valid for 60 seconds, see the reporting webapp's
 * {@code HandoffTokenService}), with no COBOL/ACU origin. Per
 * {@code .claude/CLAUDE.md}'s work-file rule this is created directly via
 * {@code CREATE TABLE IF NOT EXISTS} in a {@code @PostConstruct} rather than
 * the extract pipeline — the same precedent already established by
 * {@code tax_brackets} ({@link com.landmarksoftware.payroll.service.TaxBracketService})
 * and {@code pa_audit} ({@link com.landmarksoftware.payroll.service.BatchAuditService}),
 * neither of which has an ACU/COBOL source either. Both this app and the
 * LandmarkReporting webapp create the table defensively — either process
 * could start first, and {@code CREATE TABLE IF NOT EXISTS} is a no-op on
 * the second creator.
 *
 * <h2>Token generation</h2>
 * 128+ bits of {@link SecureRandom}-backed entropy, not a bare
 * {@code UUID.randomUUID()} call — the JDK does not guarantee
 * {@code UUID.randomUUID()} is backed by a CSPRNG on every platform/JVM
 * combination, and this token is a bearer credential for a real login, so
 * the stronger guarantee is worth the few extra lines.
 */
@Service
public class ReportsHandoffService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AppSession appSession;
    private final String reportsBaseUrl;

    public ReportsHandoffService(JdbcTemplate jdbc, AppSession appSession,
                                  @Value("${landmark.reports.base-url:http://localhost:8091}") String reportsBaseUrl) {
        this.jdbc = jdbc;
        this.appSession = appSession;
        this.reportsBaseUrl = reportsBaseUrl;
    }

    @PostConstruct
    void ensureTable() {
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS cp_handoff_token (" +
            "    token        VARCHAR(64) PRIMARY KEY," +
            "    user_id      VARCHAR(20) NOT NULL," +
            "    company_no   INT NOT NULL," +
            "    year_no      INT NOT NULL," +
            "    module_route VARCHAR(20) NOT NULL," +
            "    created_at   DATETIME NOT NULL," +
            "    consumed_at  DATETIME NULL" +
            ")");
    }

    /**
     * Generates a single-use handoff token for the given identity and
     * inserts it with {@code consumed_at = NULL}. The reporting webapp's
     * {@code HandoffTokenService.claim(token)} is the only code that ever
     * reads this row back — it enforces single-use + a 60-second expiry.
     *
     * @return the 64-hex-char token to embed in the {@code /handoff} URL
     */
    public String createToken(String userId, int companyNo, int yearNo, String moduleRoute) {
        String token = generateToken();
        jdbc.update(
            "INSERT INTO cp_handoff_token (token, user_id, company_no, year_no, module_route, created_at, consumed_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, NULL)",
            token, userId, companyNo, yearNo, moduleRoute, Timestamp.valueOf(LocalDateTime.now()));
        return token;
    }

    /** 64 hex chars (256 bits) — {@link SecureRandom#nextBytes(byte[])} over
     *  32 bytes, hex-encoded. Well above the "≥128 bits of entropy" floor,
     *  and avoids depending on {@code UUID.randomUUID()}'s CSPRNG guarantee
     *  (see class javadoc). */
    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Generates a token for the CURRENT session (user/company/year) and
     * opens it in the system browser via the exact same
     * {@code cmd /c start "" "<url>"} convention {@code ReportsHubController}
     * already uses for opening files — see that class's
     * {@code openWithOsViewer} javadoc. Deliberately not
     * {@link java.awt.Desktop}: it silently no-ops in this JavaFX-only build
     * because AWT is never initialised (DESIGN_SYSTEM.md / CLAUDE.md).
     *
     * <p>This is the single method a shell call site needs — see
     * {@code ShellContext#onOpenInReports}.
     *
     * @param moduleRoute lowercase module route id (e.g. "gl") — matches
     *                     {@code com.landmarksoftware.ui.nav.Module#getRouteId()}
     *                     and the reporting webapp's {@code nav.Module} enum names
     */
    public void openInReports(String moduleRoute) {
        String token = createToken(appSession.getUserId(), appSession.getCompanyNo(),
            appSession.getYearNo(), moduleRoute);
        String url = reportsBaseUrl + "/handoff?token=" + token;
        openWithOsViewer(url);
    }

    /**
     * Windows: {@code cmd /c start "" "<url>"} — the empty title arg is what
     * {@code start} expects when the target itself is quoted. Mirrors
     * {@code ReportsHubController.openWithOsViewer} exactly (that method
     * takes a {@code File}; this one takes a URL string, same command shape
     * otherwise) rather than reusing it directly — it's private on a
     * controller this wave is not allowed to touch.
     */
    private void openWithOsViewer(String url) {
        String os = System.getProperty("os.name", "").toLowerCase();
        java.util.List<String> cmd;
        if (os.contains("win")) {
            cmd = java.util.List.of("cmd", "/c", "start", "", url);
        } else if (os.contains("mac") || os.contains("darwin")) {
            cmd = java.util.List.of("open", url);
        } else {
            cmd = java.util.List.of("xdg-open", url);
        }
        try {
            new ProcessBuilder(cmd).inheritIO().start();
        } catch (Exception ex) {
            System.err.println("Could not open " + url + " via " + cmd.get(0) + ": " + ex);
        }
    }
}
