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
package com.landmarksoftware.payroll.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landmarksoftware.payroll.model.Employee;
import com.landmarksoftware.payroll.model.PayCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * MVR (Member Verification Request) — ATO SuperStream API client.
 *
 * <p>Mirrors COBOL {@code PAEM01 SCREEN-S1C-MVR-LOGIC} → {@code PAMVR01}
 * pipeline. Replaces the legacy Node script {@code sendmvr.js}: builds the
 * JSON request, authenticates against OZEDI B2B, POSTs the body to the SS2
 * upload endpoint, extracts the {@code messageUuid} (success) or
 * {@code detail} (error) from the response.
 *
 * <p>Source-field mapping is documented in {@code MVR_Development_Notes.md}.
 *
 * <p>The JSON body is built with Jackson (compact, no whitespace — matches
 * COBOL output style). Address suburb/state/postcode come from pastaff.
 */
@Service
public class MvrService {

    private static final DateTimeFormatter LOG_TS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate     jdbc;
    private final OzediClient      ozedi;
    private final OzediProperties  config;
    private final ObjectMapper     json = new ObjectMapper();

    public MvrService(JdbcTemplate jdbc, OzediClient ozedi, OzediProperties config) {
        this.jdbc   = jdbc;
        this.ozedi  = ozedi;
        this.config = config;
    }

    /** Result of an MVR request. */
    public record Result(boolean ok, String status, String message) {
        public static Result error(String msg)    { return new Result(false, "ERROR",        msg); }
        public static Result submitted(String u)  { return new Result(true,  "SUBMITTED",
            "MVR submitted. UUID: " + u); }
        public static Result rejected(String msg) { return new Result(false, "NOT_VERIFIED", msg); }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Validate, build JSON, POST to OZEDI SS2, and return the parsed result.
     * Mirrors COBOL {@code PAMVR01 SEND-MVR-DATA}.
     */
    public Result verify(int companyNo, int yearNo,
                         Employee emp, PayCode superFund) {
        // 1. Pre-condition validation (mirrors COBOL DO-MVR-CHECK)
        if (emp.superCode == null || emp.superCode.isBlank()) {
            return Result.error("Super code required for MVR check.");
        }
        if ((emp.superMemberNo == null || emp.superMemberNo.isBlank())
          && (emp.taxFileNo == null || emp.taxFileNo.isBlank())) {
            return Result.error("Member no or TFN required for MVR.");
        }
        if (superFund == null) {
            return Result.error("Super fund pacodes row not found for code '"
                + emp.superCode + "'.");
        }
        Map<String, String> employer = readEmployerDetails(companyNo, yearNo);
        if (employer == null) {
            return Result.error("Cannot find employer details — no papsdat row for company "
                + companyNo + " year " + yearNo + " and no cpcoyco fallback.");
        }

        log("=== MVR start: emp=" + emp.employeeNo + " " + emp.fullName()
            + " superCode=" + emp.superCode);

        // 2. Build JSON body (mirrors COBOL BUILD-MVR-JSON)
        String body;
        try {
            body = buildMvrJson(emp, superFund, employer);
        } catch (Exception ex) {
            log("BUILD-JSON failed: " + ex.getMessage());
            return Result.error("Could not build MVR JSON: " + ex.getMessage());
        }
        log("JSON body (first 190 chars): "
            + body.substring(0, Math.min(190, body.length())));

        // 3. Authenticate + post (replaces sendmvr.js)
        String responseBody;
        try {
            String jwt = ozedi.authenticate();
            log("Auth ok, JWT length=" + jwt.length());
            String employerAbn = employer.getOrDefault("employer_abn", "");
            responseBody = ozedi.postMvrPayload(jwt, employerAbn, body);
            log("API response (first 190 chars): "
                + responseBody.substring(0, Math.min(190, responseBody.length())));
        } catch (OzediClient.OzediException oe) {
            log("OZEDI call failed: " + oe.getMessage());
            return Result.error(oe.getMessage());
        }

        // 4. Parse response (mirrors COBOL CHECK-JSON-FILE-RETURN-CODE)
        return parseResponse(responseBody);
    }

    // ── JSON builder ─────────────────────────────────────────────────────

    /** Returns the compact JSON the SS2 endpoint expects. */
    String buildMvrJson(Employee emp, PayCode fund, Map<String, String> employer) {
        ObjectNode root = json.createObjectNode();
        root.put("Comment-1", "OZEDI MVR JSON v2.");

        // messageHeader
        root.putObject("messageHeader").put("messageType", "MemberVerificationRequest");

        // sender (from papsdat employer)
        ObjectNode sender = root.putObject("sender");
        sender.put("abn",                trim(employer.get("employer_abn")));
        sender.put("emailAddress",       trim(employer.get("employer_email")));
        // Split contact name into given/family (last whitespace-separated token = family)
        String[] gf = splitContactName(employer.get("employer_contact_name"));
        sender.put("familyName",         gf[1]);
        sender.put("givenName",          gf[0]);
        sender.put("organisationalName", trim(employer.get("employer_name_1")));
        ObjectNode phone = sender.putObject("phone");
        phone.put("phoneServiceLine", "02");
        phone.put("phone",            trim(employer.get("employer_contact_phone")));

        // receiver (super fund — A=APRA uses USI, S=SMSF uses ESA)
        ObjectNode receiver = root.putObject("receiver");
        if ("A".equalsIgnoreCase(fund.apraSmsfFundInd)) {
            receiver.put("uniqueSuperannuationIdentifier", trim(fund.fundUsi));
            receiver.put("targetElectronicServiceAddress", "");
        } else {
            receiver.put("targetElectronicServiceAddress", trim(fund.fundEsa));
        }
        receiver.put("abn",                trim(fund.fundAbn));
        receiver.put("organisationalName", trim(fund.fundName));

        // employers[0]
        ObjectNode emp0 = root.putArray("employers").addObject();
        emp0.put("abn",                trim(employer.get("employer_abn")));
        emp0.put("organisationalName", trim(employer.get("employer_name_1")));

        // employers[0].members[0]
        ObjectNode m0 = emp0.putArray("members").addObject();
        ObjectNode name = m0.putObject("name");
        name.put("givenName", trim(emp.firstName));
        if (emp.secondName != null && !emp.secondName.trim().isEmpty()) {
            name.put("otherGivenName", trim(emp.secondName));
        }
        name.put("familyName", trim(emp.surname));

        m0.put("birthDate", emp.dateOfBirth == null ? "" : emp.dateOfBirth.toString());

        // TFN preferred over member number (matches COBOL — if TFN present
        // use it and set taxFileNumberNotProvided=N; else use member no with Y)
        boolean haveTfn = emp.taxFileNo != null && !emp.taxFileNo.isBlank()
                         && !"0".equals(emp.taxFileNo.trim());
        m0.put("superannuationFundMember",
            haveTfn ? emp.taxFileNo.trim() : trim(emp.superMemberNo));
        m0.put("taxFileNumberNotProvided", haveTfn ? "N" : "Y");
        m0.put("sex", emp.sex == null ? "" : emp.sex);

        // address (pastaff residential)
        ObjectNode addr = m0.putObject("address");
        addr.put("addressDetailsUsage", "RES");
        addr.put("addressLine1", trim(emp.addr1));
        addr.put("country",      "au");
        // postcode first 4 chars only (matches COBOL PASTAFF-POSTCODE(1:4))
        String pc = emp.postcode == null ? "" : emp.postcode.trim();
        addr.put("postCode", pc.length() > 4 ? pc.substring(0, 4) : pc);
        addr.put("state",  trim(emp.state));
        addr.put("suburb", trim(emp.city));

        try {
            return json.writeValueAsString(root);
        } catch (Exception ex) {
            throw new RuntimeException("JSON serialization failed: " + ex.getMessage(), ex);
        }
    }

    // ── Response parser ──────────────────────────────────────────────────

    Result parseResponse(String body) {
        if (body == null || body.isBlank()) {
            return Result.error("Empty response from OZEDI.");
        }
        try {
            JsonNode root = json.readTree(body);
            // Error path — COBOL looks for "error" substring; we check both
            // explicit "error":true and the presence of a "detail" field.
            JsonNode errNode = root.get("error");
            JsonNode detail  = root.get("detail");
            if ((errNode != null && errNode.asBoolean(false))
              || (detail != null && !detail.asText().isBlank())) {
                String msg = (detail != null ? detail.asText() : "Unknown error");
                log("MVR rejected: " + msg);
                return Result.rejected(msg);
            }
            JsonNode uuidNode = root.get("messageUuid");
            if (uuidNode != null && !uuidNode.asText().isBlank()) {
                String uuid = uuidNode.asText();
                log("MVR submitted ok, UUID=" + uuid);
                return Result.submitted(uuid);
            }
            // No error, no UUID — surface the raw body
            log("MVR unrecognized response: " + body);
            return Result.error("OZEDI returned no messageUuid: " + body);
        } catch (Exception ex) {
            log("Response parse failed: " + ex.getMessage());
            return Result.error("Could not parse OZEDI response: " + ex.getMessage());
        }
    }

    // ── Employer details lookup ──────────────────────────────────────────

    /**
     * Build the employer map for the MVR JSON. Resolution order:
     * <ol>
     *   <li><b>papsdat</b> (company_no + year_no) — primary, matches the
     *       COBOL design. Has all five fields (ABN, name, email, contact
     *       name, contact phone).</li>
     *   <li><b>cpcoyco</b> (company_no) fallback for the structural fields
     *       (ABN, name, phone) when papsdat has no row for this year.</li>
     *   <li><b>{@link OzediProperties}</b> fallback for contact email +
     *       contact name when neither papsdat nor cpcoyco has them.</li>
     * </ol>
     * <p>papsdat isn't in the Landmark Query dictionary so the extract
     * engine won't auto-populate it — operator must INSERT one row per
     * (company_no, year_no) manually until a maintenance screen is built.
     */
    private Map<String, String> readEmployerDetails(int companyNo, int yearNo) {
        // 1. Try papsdat first
        Map<String, String> m = readPapsdat(companyNo, yearNo);
        if (m != null && !isBlank(m.get("employer_abn"))) {
            log("Employer details from papsdat (year " + yearNo + ")");
            return fillContactDefaults(m);
        }
        // 2. Fall back to cpcoyco
        m = readCpcoyco(companyNo);
        if (m != null) {
            log("Employer details from cpcoyco fallback (no papsdat row for year "
                + yearNo + ")");
            return fillContactDefaults(m);
        }
        return null;
    }

    private Map<String, String> readPapsdat(int companyNo, int yearNo) {
        try {
            return jdbc.queryForObject(
                PayrollSql.FIND_PAPSDAT_EMPLOYER,
                (rs, i) -> {
                    Map<String, String> r = new java.util.HashMap<>();
                    r.put("employer_abn",           rs.getString("employer_abn"));
                    r.put("employer_name_1",        rs.getString("employer_name_1"));
                    r.put("employer_email",         rs.getString("employer_email"));
                    r.put("employer_contact_phone", rs.getString("employer_contact_phone"));
                    r.put("employer_contact_name",  rs.getString("employer_contact_name"));
                    return r;
                },
                companyNo, yearNo);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, String> readCpcoyco(int companyNo) {
        try {
            return jdbc.queryForObject(
                PayrollSql.FIND_CPCOYCO_EMPLOYER,
                (rs, i) -> {
                    Map<String, String> r = new java.util.HashMap<>();
                    r.put("employer_abn",           rs.getString("abn"));
                    r.put("employer_name_1",        rs.getString("name1"));
                    r.put("employer_contact_phone", rs.getString("phone"));
                    return r;
                },
                companyNo);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Use OzediProperties for any contact fields still blank after DB lookup. */
    private Map<String, String> fillContactDefaults(Map<String, String> m) {
        if (isBlank(m.get("employer_email")))
            m.put("employer_email", config.getSenderEmail());
        if (isBlank(m.get("employer_contact_name")))
            m.put("employer_contact_name", config.getSenderContactName());
        return m;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Split "Given Family" into [given, family] — mirrors COBOL
     * SPLIT-SENDER-CONTACT-NAME. If only one token, it goes to family.
     */
    static String[] splitContactName(String contact) {
        String s = contact == null ? "" : contact.trim();
        int sp = s.indexOf(' ');
        if (sp <= 0) return new String[]{ "", s };
        return new String[]{ s.substring(0, sp).trim(), s.substring(sp + 1).trim() };
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    /**
     * Append a line to the MVR debug log. Same pattern as COBOL
     * {@code OPEN EXTEND CPMVRLOG}. Best-effort — never throws.
     */
    private void log(String msg) {
        try {
            Path dir = logDir();
            Files.createDirectories(dir);
            Path file = dir.resolve("mvr_debug.log");
            String line = "[" + LOG_TS.format(LocalDateTime.now()) + "] " + msg + "\n";
            Files.writeString(file, line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging is best-effort; don't fail the MVR call over a log error
        }
    }

    private Path logDir() {
        String d = config.getLogDir();
        if (d != null && !d.isBlank()) return Path.of(d);
        return Path.of(System.getProperty("user.home"), "landmark-mvr");
    }
}
