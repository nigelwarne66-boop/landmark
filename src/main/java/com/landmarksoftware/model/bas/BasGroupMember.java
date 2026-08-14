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
package com.landmarksoftware.model.bas;

/**
 * CPBA01 P2/S2 — a member company attached to a BAS Report Group
 * ({@code cpbasco} cross-reference row + the matching {@code cpsubcy}
 * GL-account row).
 *
 * <p>PK of the cross-reference: {@code (bas_group, company_no, sub_coy_no)}.
 * One row per group is the "owner" (matches {@link BasGroup#companyNo}/
 * {@link BasGroup#subCoyNo}) — auto-managed by the S1 group dialog, not
 * directly deletable from this P2/S2 screen.
 *
 * <p>{@link #companyName} is display-only, resolved from {@code cpsubcy} (or
 * {@code cpcoyco} when no {@code cpsubcy} row exists yet, i.e. sub_coy_no=0
 * with no prior BAS activity for that company).
 *
 * <p>The 12 GL account pairs mirror the {@code cpsubcy} columns exposed on
 * the COBOL S2 screen ({@code cpba01s2.sd}), in the screen's real field
 * order: 1C-1D, 1E-1F, 1G, 4, 5A-5B, 6A-6B, 7, 7A, 7C-7D, Inter-Co, then
 * (subsidiary section) HO Loan, Sub Loan. {@code bas_ho_loan_acct} /
 * {@code bas_sub_loan_acct} are only relevant when {@link #subCoyNo} &gt; 0.
 *
 * <p><b>Tax Paid / Variance are NOT on S2</b> — those two GL accounts belong
 * exclusively to S1 (the BAS group's own owner-level fields, see {@link
 * BasGroup#taxPaidAcctMain}), verified against {@code cpba01s2.sd}. S2 must
 * never read or write {@code cpsubcy.tax_paid_acct_*}/{@code variance_acct_*}
 * — a member company's row may separately be the *owner* row of some other
 * BAS group, and S2 editing it as a mere member must not clobber that.
 * {@code non_land_acct} and company name/address/ABN exist on {@code cpsubcy}
 * but are also NOT edited here — left untouched on writes.
 */
public class BasGroupMember {

    public String basGroup   = "";
    public int    companyNo  = 0;
    public int    subCoyNo   = 0;
    public String companyName = "";
    public long   noteNo = 0L;

    // ── GL account pairs (main + sub) — cpsubcy columns exposed on S2,
    // in the COBOL screen's real order ────────────────────────────────
    public int bas1c1dAcctMain = 0,    bas1c1dAcctSub = 0;
    public int bas1e1fAcctMain = 0,    bas1e1fAcctSub = 0;
    public int bas1gAcctMain = 0,      bas1gAcctSub = 0;
    public int bas4AcctMain = 0,       bas4AcctSub = 0;
    public int bas5a5bAcctMain = 0,    bas5a5bAcctSub = 0;
    public int bas6a6bAcctMain = 0,    bas6a6bAcctSub = 0;
    public int bas7AcctMain = 0,       bas7AcctSub = 0;
    public int bas7aAcctMain = 0,      bas7aAcctSub = 0;
    public int bas7c7dAcctMain = 0,    bas7c7dAcctSub = 0;
    public int interCoyAcctMain = 0,   interCoyAcctSub = 0;
    public int basHoLoanAcctMain = 0,  basHoLoanAcctSub = 0;   // sub-coy only
    public int basSubLoanAcctMain = 0, basSubLoanAcctSub = 0;  // sub-coy only

    /** Whether this member row is the group's own owner (matches g.companyNo/g.subCoyNo). */
    public boolean isOwnerOf(BasGroup g) {
        return g != null && companyNo == g.companyNo && subCoyNo == g.subCoyNo;
    }
}
