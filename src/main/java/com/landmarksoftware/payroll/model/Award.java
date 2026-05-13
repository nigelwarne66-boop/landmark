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

/**
 * PAAW01 — Award header (paawhed table).
 *
 * <p>PK: {@code (company_no, award_code)}. Awards own many job classes
 * ({@link AwardJobClass}) and many WC premium rows ({@link AwardWcomp}).
 *
 * <p>FK naming note: {@code paawhed/paawjob/paawwcp} use
 * {@code award_code} and {@code job_class_code}, while {@code pastaff /
 * paehist / paecode} use {@code award} and {@code job_class} (no
 * {@code _code} suffix). Be careful when joining.
 */
public class Award {

    public int    companyNo  = 0;
    public String awardCode  = "";    // VARCHAR(3)
    public String desc1      = "";
    public long   noteNo     = 0;
}
