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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the OZEDI SuperStream / MVR integration.
 * Bound from {@code application.properties} (prefix {@code ozedi.}).
 *
 * <p>Per {@code MVR_Development_Notes.md}, the SS2 account and client IDs are
 * Landmark-level constants — one eBusiness account is used across customer
 * sites — so they live in this app's config, not per-company in cpcoyco.
 *
 * <p>The username/password should be a dedicated API service account
 * supplied via environment variables {@code OZEDI_USERNAME} /
 * {@code OZEDI_PASSWORD} — never commit personal credentials to source.
 */
@Component
@ConfigurationProperties(prefix = "ozedi")
public class OzediProperties {

    /** e.g. "api-ebusiness.ozedi.com.au" — authentication host. */
    private String b2bHost = "api-ebusiness.ozedi.com.au";
    /** e.g. "api-superstream.ozedi.com.au" — payload upload host. */
    private String ss2Host = "api-superstream.ozedi.com.au";
    /** eBusiness account number (Landmark site-wide constant). */
    private String accountNo = "";
    /** SS2 client ID (Landmark site-wide constant). */
    private String clientId = "";
    /** OZEDI service account username (env var OZEDI_USERNAME). */
    private String username = "";
    /** OZEDI service account password (env var OZEDI_PASSWORD). */
    private String password = "";
    /** Directory for mvr_debug.log; empty → user-home/landmark-mvr. */
    private String logDir = "";
    /** Sender contact email (e.g. payroll officer) — papsdat replacement. */
    private String senderEmail = "";
    /** Sender contact name "Given Family" — papsdat replacement. */
    private String senderContactName = "";

    public String getSenderEmail()        { return senderEmail; }
    public void   setSenderEmail(String v){ this.senderEmail = v; }
    public String getSenderContactName()        { return senderContactName; }
    public void   setSenderContactName(String v){ this.senderContactName = v; }

    public String getB2bHost()    { return b2bHost; }
    public void   setB2bHost(String v)    { this.b2bHost = v; }
    public String getSs2Host()    { return ss2Host; }
    public void   setSs2Host(String v)    { this.ss2Host = v; }
    public String getAccountNo()  { return accountNo; }
    public void   setAccountNo(String v)  { this.accountNo = v; }
    public String getClientId()   { return clientId; }
    public void   setClientId(String v)   { this.clientId = v; }
    public String getUsername()   { return username; }
    public void   setUsername(String v)   { this.username = v; }
    public String getPassword()   { return password; }
    public void   setPassword(String v)   { this.password = v; }
    public String getLogDir()     { return logDir; }
    public void   setLogDir(String v)     { this.logDir = v; }

    /** Quick configured-check — false if username or password is blank. */
    public boolean isConfigured() {
        return username != null && !username.isBlank()
            && password != null && !password.isBlank();
    }
}
