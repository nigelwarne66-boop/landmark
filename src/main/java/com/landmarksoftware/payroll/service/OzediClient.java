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
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTPS client for the OZEDI SuperStream B2B + SS2 endpoints.
 * <p>Replaces the COBOL {@code sendmvr.js} Node script. Two operations:
 * <ol>
 *   <li>{@link #authenticate()} — POST username/password to
 *       {@code /v2/authenticate}, return the JWT {@code id_token}.</li>
 *   <li>{@link #postMvrPayload(String, String, String)} — POST the MVR JSON
 *       body to {@code /api/uploads/process/{account}/{client}/{abn}
 *       ?autoSendWhenReady=true} with {@code Authorization: Bearer JWT}.</li>
 * </ol>
 * <p>Uses the JDK 11+ built-in HttpClient; no external HTTP dependency.
 */
@Service
public class OzediClient {

    private final OzediProperties config;
    private final ObjectMapper    json = new ObjectMapper();
    private final HttpClient      http = HttpClient.newBuilder()
                                            .connectTimeout(Duration.ofSeconds(15))
                                            .build();

    public OzediClient(OzediProperties config) { this.config = config; }

    /**
     * Authenticate against the eBusiness API and return the JWT id_token.
     * @throws OzediException on HTTP error or missing token in response.
     */
    public String authenticate() throws OzediException {
        if (!config.isConfigured()) {
            throw new OzediException("OZEDI credentials are not configured "
                + "(set OZEDI_USERNAME / OZEDI_PASSWORD or ozedi.username / ozedi.password).");
        }
        String body = "{\"username\":\"" + esc(config.getUsername())
                    + "\",\"password\":\"" + esc(config.getPassword())
                    + "\",\"rememberMe\":\"false\"}";

        URI uri = URI.create("https://" + config.getB2bHost() + "/v2/authenticate");
        HttpRequest req = HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = send(req, "authenticate");
        if (resp.statusCode() / 100 != 2) {
            throw new OzediException("Auth failed: HTTP " + resp.statusCode() + " — " + resp.body());
        }
        try {
            JsonNode root = json.readTree(resp.body());
            JsonNode tok = root.get("id_token");
            if (tok == null || tok.asText().isBlank()) {
                throw new OzediException("Auth response missing id_token: " + resp.body());
            }
            return tok.asText();
        } catch (OzediException oe) { throw oe; }
        catch (Exception ex) {
            throw new OzediException("Auth response not parseable: " + ex.getMessage());
        }
    }

    /**
     * POST the MVR JSON body to the SS2 uploads endpoint.
     * @param jwtToken from {@link #authenticate()}
     * @param employerAbn the employer ABN — last segment of the upload path
     * @param mvrJson serialized MVR request body
     * @return raw response body (caller parses for {@code messageUuid} or {@code detail})
     */
    public String postMvrPayload(String jwtToken, String employerAbn, String mvrJson)
            throws OzediException {
        URI uri = URI.create("https://" + config.getSs2Host()
            + "/api/uploads/process/"
            + config.getAccountNo()
            + "/" + config.getClientId()
            + "/" + (employerAbn == null ? "" : employerAbn.trim())
            + "?autoSendWhenReady=true");
        HttpRequest req = HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer " + jwtToken)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(mvrJson))
            .build();
        HttpResponse<String> resp = send(req, "upload");
        // Don't throw on non-2xx — caller wants the body either way to extract
        // an "error":true / "detail":"..." payload for the user message.
        return resp.body();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private HttpResponse<String> send(HttpRequest req, String label) throws OzediException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new OzediException("OZEDI " + label + " call failed: " + ex.getMessage());
        }
    }

    /** Minimal JSON-string escape — username/password are simple ASCII fields. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Wrapper exception so callers don't see java.net details. */
    public static class OzediException extends Exception {
        public OzediException(String msg) { super(msg); }
    }
}
