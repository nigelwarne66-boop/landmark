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

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.payroll.model.CmBank;
import com.landmarksoftware.payroll.model.EmployeePay;
import com.landmarksoftware.payroll.model.PayHistEntry;
import com.landmarksoftware.payroll.model.Payrun;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * PABK02 — ABA Payment File generator.
 *
 * <p>Reads net pay from {@code paehist}, employee bank routing from
 * {@code paempay}, and writes an APCA ABA flat file to
 * {@link AppSession#getPayrollFilesDir()}.
 *
 * <p>File format — Australian Banking Association direct-entry, fixed
 * width 120 chars per record + LF:
 * <ul>
 *   <li><b>Record 0</b> — file header (one only).</li>
 *   <li><b>Record 1</b> — detail (one per employee × pay-method split).</li>
 *   <li><b>Record 7</b> — trailer (totals + count).</li>
 * </ul>
 *
 * <p>Net pay derivation per employee:
 * <pre>
 *   sum(gross_amt)          // every patimes line that hit paehist
 *   − sum(tax_amt)          // synthetic tax line, pay_type=22
 *   − sum(ext_amt) where pay_type ∈ {19 before-tax, 20 after-tax, 21 super}
 * </pre>
 *
 * <p>Pay-method split distribution mirrors paempay rules:
 * <ul>
 *   <li>{@code A} — fixed dollar amount.</li>
 *   <li>{@code P} — percentage (0–100).</li>
 *   <li>{@code B} — balance (whatever's left after A + P splits).</li>
 * </ul>
 *
 * <p>Output filename: {@code PAYROLL_YYYYMMDD_NNN.aba} (NNN = payrun_no).
 */
@Service
public class AbaFileService {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ABA_DATE  = DateTimeFormatter.ofPattern("ddMMyy");

    private final PayHistService     paehist;
    private final EmployeePayService empPayments;
    private final EmployeeService    employees;
    private final PayrunService      payruns;
    private final CmBanksService     cmBanks;

    public AbaFileService(PayHistService paehist,
                           EmployeePayService empPayments,
                           EmployeeService employees,
                           PayrunService payruns,
                           CmBanksService cmBanks) {
        this.paehist     = paehist;
        this.empPayments = empPayments;
        this.employees   = employees;
        this.payruns     = payruns;
        this.cmBanks     = cmBanks;
    }

    public record Result(Path file, int detailCount, BigDecimal totalCredit,
                          List<String> warnings) {}

    /**
     * Generate the ABA file for a posted payrun. The payrun must be in
     * status {@code "P"} (Posted) or {@code "F"}; otherwise refuse.
     */
    public Result generate(int companyNo, int payrunNo, String userId,
                            AppSession session) throws IOException {
        Payrun pr = payruns.findOne(companyNo, payrunNo).orElseThrow(
            () -> new IllegalArgumentException("Payrun " + payrunNo + " not found."));
        if (!"P".equalsIgnoreCase(pr.payrunStatus)
                && !"F".equalsIgnoreCase(pr.payrunStatus)) {
            throw new IllegalStateException(
                "Payrun " + payrunNo + " is not posted (status = "
                    + pr.statusDisplay() + "). Run PAPP28 first.");
        }

        // Employer bank — header + trace fields come from cmbanks.
        CmBank bank = cmBanks.findPayrollBank(companyNo).orElseThrow(
            () -> new IllegalStateException(
                "No payroll bank configured in cmbanks (eft_pa_flag='Y'). "
                + "Set up at least one bank for payroll EFT before generating ABA."));

        // Net pay per employee, ordered by employee_no for a stable file.
        Map<Integer, BigDecimal> netByEmployee = computeNetByEmployee(companyNo, payrunNo);

        List<DetailLine> details = new ArrayList<>();
        List<String> warnings    = new ArrayList<>();
        BigDecimal totalCredit   = BigDecimal.ZERO;

        for (var entry : netByEmployee.entrySet()) {
            int employeeNo  = entry.getKey();
            BigDecimal net  = entry.getValue();
            if (net.signum() <= 0) {
                warnings.add("Employee " + employeeNo + ": net pay = " + net
                    + ", skipped.");
                continue;
            }
            List<EmployeePay> splits = empPayments.findByEmployee(companyNo, employeeNo);
            List<DetailLine> lines   = applySplits(employeeNo, net, splits, warnings);
            for (DetailLine d : lines) {
                if (isValidBsb(d.bsb) && !d.account.isBlank()) {
                    details.add(d);
                    totalCredit = totalCredit.add(d.amount);
                } else {
                    warnings.add("Employee " + employeeNo + ": invalid BSB ("
                        + d.bsb + ") or empty account — split not exported.");
                }
            }
        }

        // Build the file body.
        List<String> records = new ArrayList<>();
        records.add(formatHeader(pr, bank));
        for (DetailLine d : details) records.add(formatDetail(d, bank));
        records.add(formatTrailer(totalCredit, details.size()));

        // Write to disk.
        Path dir = resolveOutputDir(session);
        Files.createDirectories(dir);
        String fileName = "PAYROLL_" + FILE_DATE.format(
            pr.paymtDate == null || pr.paymtDate.getYear() < 1900
                ? pr.payrunDate : pr.paymtDate)
            + "_" + String.format("%03d", payrunNo) + ".aba";
        Path out = dir.resolve(fileName);
        Files.write(out, String.join("\n", records).getBytes("US-ASCII"));

        return new Result(out, details.size(), totalCredit, warnings);
    }

    // ── Net-pay derivation ────────────────────────────────────────────────

    private Map<Integer, BigDecimal> computeNetByEmployee(int companyNo, int payrunNo) {
        Map<Integer, BigDecimal> map = new TreeMap<>();
        for (PayHistEntry e : paehist.findByPayrun(companyNo, payrunNo)) {
            BigDecimal contribution = BigDecimal.ZERO;
            // Credit pay-types (gross income)
            if (isCredit(e.payType)) {
                contribution = nz(e.extAmt);
            }
            // Debits (deductions / super / tax)
            if (e.payType == 19 || e.payType == 20 || e.payType == 21) {
                contribution = contribution.subtract(nz(e.extAmt));
            }
            // Synthetic tax line — pay_type=22, taxAmt carries the value.
            if (e.payType == 22) {
                contribution = contribution.subtract(nz(e.taxAmt));
            }
            map.merge(e.employeeNo, contribution, BigDecimal::add);
        }
        return map;
    }

    private static boolean isCredit(int payType) {
        return (payType >= 1 && payType <= 8)         // earnings
            || payType == 11 || payType == 12         // allowances
            || (payType >= 13 && payType <= 18)       // termination components
            || payType == 23;                         // backpay
    }

    // ── Split distribution ───────────────────────────────────────────────

    /**
     * Apply paempay split rules. "A" amounts first, then "P" percentages on
     * the residual, then "B" balance picks up whatever's left.
     */
    private List<DetailLine> applySplits(int employeeNo, BigDecimal net,
                                          List<EmployeePay> splits,
                                          List<String> warnings) {
        List<DetailLine> out = new ArrayList<>();
        if (splits == null || splits.isEmpty()) {
            warnings.add("Employee " + employeeNo + ": no paempay split defined "
                + "— net pay not exported.");
            return out;
        }
        BigDecimal remaining = net;
        EmployeePay balance  = null;

        // Pass 1: "A" amounts.
        for (EmployeePay s : splits) {
            if ("A".equalsIgnoreCase(s.payCalcMethod)) {
                BigDecimal amt = capAt(nz(s.payAmtPerc), remaining);
                if (amt.signum() > 0) {
                    out.add(detail(s, amt));
                    remaining = remaining.subtract(amt);
                }
            }
        }
        // Pass 2: "P" percentages on the original net.
        for (EmployeePay s : splits) {
            if ("P".equalsIgnoreCase(s.payCalcMethod)) {
                BigDecimal pct = nz(s.payAmtPerc).divide(new BigDecimal("100"),
                    8, RoundingMode.HALF_UP);
                BigDecimal amt = capAt(net.multiply(pct).setScale(2, RoundingMode.HALF_UP),
                    remaining);
                if (amt.signum() > 0) {
                    out.add(detail(s, amt));
                    remaining = remaining.subtract(amt);
                }
            }
        }
        // Pass 3: "B" balance — first balance split wins; others ignored.
        for (EmployeePay s : splits) {
            if ("B".equalsIgnoreCase(s.payCalcMethod)) { balance = s; break; }
        }
        if (balance != null && remaining.signum() > 0) {
            out.add(detail(balance, remaining));
            remaining = BigDecimal.ZERO;
        }
        if (remaining.signum() > 0) {
            warnings.add("Employee " + employeeNo + ": $" + remaining
                + " unallocated after splits — no balance row defined.");
        }
        return out;
    }

    private static DetailLine detail(EmployeePay s, BigDecimal amount) {
        DetailLine d = new DetailLine();
        d.bsb       = normaliseBsb(s.tfrToBankNo);
        d.account   = s.tfrToBankAcctNo == null ? "" : s.tfrToBankAcctNo.trim();
        d.title     = s.payeeName == null ? "" : s.payeeName;
        d.amount    = amount;
        return d;
    }

    private static BigDecimal capAt(BigDecimal value, BigDecimal max) {
        if (value == null) return BigDecimal.ZERO;
        return value.compareTo(max) > 0 ? max : value;
    }

    // ── ABA record formatting ────────────────────────────────────────────

    private static String formatHeader(Payrun pr, CmBank bank) {
        StringBuilder sb = new StringBuilder(120);
        sb.append('0');
        pad(sb, "", 17);                                       // 2-18 reel info (blank)
        sb.append("01");                                       // 19-20 reel seq
        pad(sb, bankAbbreviation(bank), 3);                    // 21-23 bank abbreviation
        pad(sb, "", 7);                                        // 24-30 blank
        pad(sb, userName(bank), 26);                           // 31-56 user name
        pad(sb, bank.formattedUserNo(), 6);                    // 57-62 APCA user number
        pad(sb, "PAYROLL", 12);                                // 63-74 description
        sb.append(ABA_DATE.format(pr.paymtDate != null && pr.paymtDate.getYear() >= 1900
            ? pr.paymtDate : pr.payrunDate));                  // 75-80 ddmmyy
        pad(sb, "", 40);                                       // 81-120 blank
        return sb.toString();
    }

    private static String formatDetail(DetailLine d, CmBank bank) {
        StringBuilder sb = new StringBuilder(120);
        sb.append('1');
        pad(sb, formatBsb(d.bsb), 7);                         // 2-8 BSB NNN-NNN
        padRight(sb, d.account, 9);                            // 9-17 account
        sb.append(' ');                                        // 18 indicator
        sb.append("50");                                       // 19-20 trans code (credit)
        padCents(sb, d.amount, 10);                            // 21-30 amount cents
        pad(sb, d.title, 32);                                  // 31-62 title
        pad(sb, "PAYROLL", 18);                                // 63-80 lodgement reference
        pad(sb, formatBsb(bank.branchNo), 7);                  // 81-87 trace BSB (employer)
        padRight(sb, digitsOnly(bank.bankAcctNo), 9);          // 88-96 trace account (employer)
        pad(sb, remitterName(bank), 16);                       // 97-112 remitter
        padCents(sb, BigDecimal.ZERO, 8);                      // 113-120 withholding tax
        return sb.toString();
    }

    private static String bankAbbreviation(CmBank bank) {
        return bank.eftBankCode == null || bank.eftBankCode.isBlank()
            ? "ANZ" : bank.eftBankCode;
    }

    private static String userName(CmBank bank) {
        return bank.eftName == null || bank.eftName.isBlank()
            ? bank.name : bank.eftName;
    }

    private static String remitterName(CmBank bank) {
        if (bank.paySrvRemitterName != null && !bank.paySrvRemitterName.isBlank()) {
            return bank.paySrvRemitterName;
        }
        return bank.eftName == null || bank.eftName.isBlank()
            ? bank.name : bank.eftName;
    }

    private static String digitsOnly(String s) {
        if (s == null) return "";
        return s.replaceAll("[^0-9]", "");
    }

    private static String formatTrailer(BigDecimal totalCredit, int count) {
        StringBuilder sb = new StringBuilder(120);
        sb.append('7');
        sb.append("999-999");                                  // 2-8 reserved BSB
        pad(sb, "", 12);                                       // 9-20 blank
        padCents(sb, BigDecimal.ZERO, 10);                     // 21-30 net total
        padCents(sb, totalCredit, 10);                         // 31-40 credit total
        padCents(sb, BigDecimal.ZERO, 10);                     // 41-50 debit total
        pad(sb, "", 24);                                       // 51-74 blank
        padRight(sb, String.valueOf(count), 6);                // 75-80 count
        pad(sb, "", 40);                                       // 81-120 blank
        return sb.toString();
    }

    private static void pad(StringBuilder sb, String s, int width) {
        if (s == null) s = "";
        if (s.length() > width) s = s.substring(0, width);
        sb.append(s);
        for (int i = s.length(); i < width; i++) sb.append(' ');
    }
    private static void padRight(StringBuilder sb, String s, int width) {
        if (s == null) s = "";
        if (s.length() > width) s = s.substring(s.length() - width);
        for (int i = s.length(); i < width; i++) sb.append(' ');
        sb.append(s);
    }
    private static void padCents(StringBuilder sb, BigDecimal amount, int width) {
        long cents = amount == null ? 0
            : amount.multiply(new BigDecimal("100"))
                  .setScale(0, RoundingMode.HALF_UP).longValue();
        String s = String.valueOf(Math.abs(cents));
        for (int i = s.length(); i < width; i++) sb.append('0');
        sb.append(s);
    }

    // ── BSB / account helpers ────────────────────────────────────────────

    private static String normaliseBsb(String raw) {
        if (raw == null) return "";
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() == 6 ? digits : raw.trim();
    }

    private static String formatBsb(String bsb) {
        if (bsb == null) return "000-000";
        String d = bsb.replaceAll("[^0-9]", "");
        if (d.length() == 6) return d.substring(0, 3) + "-" + d.substring(3);
        return "000-000";
    }

    private static boolean isValidBsb(String bsb) {
        if (bsb == null) return false;
        String d = bsb.replaceAll("[^0-9]", "");
        return d.length() == 6 && !d.equals("000000");
    }

    private Path resolveOutputDir(AppSession session) {
        String dir = session.getPayrollFilesDir();
        if (dir == null || dir.isBlank()) {
            // Fallback to user's home payroll directory.
            return Paths.get(System.getProperty("user.home"), "landmark-payroll");
        }
        return Paths.get(dir);
    }

    private static BigDecimal nz(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }

    /** Internal collector for one ABA detail row. */
    private static class DetailLine {
        String bsb     = "";
        String account = "";
        String title   = "";
        BigDecimal amount = BigDecimal.ZERO;
    }
}
