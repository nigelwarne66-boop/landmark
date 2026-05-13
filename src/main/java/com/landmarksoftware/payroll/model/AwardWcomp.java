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
package com.landmarksoftware.payroll.model;

import java.math.BigDecimal;

/**
 * PAAW01 — Workers compensation & on-costs % for an
 * (award, job_class, paygroup) tuple (paawwcp table).
 *
 * <p>PK: {@code (company_no, award_code, job_class_code, paygroup)}. The
 * paygroup join means different pay groups can carry different WC rates
 * for the same job classification.
 */
public class AwardWcomp {

    public int        companyNo      = 0;
    public String     awardCode      = "";
    public String     jobClassCode   = "";
    public String     paygroup       = "";

    public BigDecimal wcompPerc      = BigDecimal.ZERO;
    public BigDecimal onCostsPerc    = BigDecimal.ZERO;
    public long       noteNo         = 0;
}
