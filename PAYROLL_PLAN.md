# Landmark Payroll — Development Plan
*Created: 2026-05-09*

---

## Status overview

| Wave | Focus | Status |
|------|-------|--------|
| 0 — Foundation | Module scaffold, AppSession, PACD01 | ✅ Complete |
| 1 — Setup CRUD | PAEM01 ✅, PAPG01 ✅, PASU04 ✅, PAAW01 ✅ | ✅ Complete |
| 2 — Batch operations | PAEM11, PAPC01, PAEM60, PASU55, PASU11/14/15 | 🔲 Pending Wave 1 |
| 3 — Pay processing | PATM01, PAPP01, PAPA14/30, PABK02, PAPP28 | 🔲 Pending Wave 1 + test data |
| 4 — Compliance | PAST10 (STP), PAPS26, PADE01 | 🔲 Pending Wave 3 + ATO sandbox |
| 5 — Reports | PATL series | 🔲 Ongoing |

---

## Wave 0 — Foundation ✅

### What was built

**Package structure:** `com.example.fixedassets.payroll.{model,service,ui}`

**AppSession additions:**
- `selectedPayrunNo / Date / Desc` — active payrun for PATM01/PAPP01
- `hasPayrun()`, `clearPayrun()` — payrun state helpers
- `payrollFilesDir` — ABA output dir (CPCOYCO fallback → application.properties)
- `paInstalFlag`, `isPayrollInstalled()`

**`PayCodeService`** — 11 JDBC calls for full pacodes CRUD  
**`PayCodeMaintenanceController`** (PACD01) — zero JDBC, all JavaFX. Type filter bar, isInUse() check before delete, soft-delete (deactivate) when code is in paehist  
**`PayrollMenuController`** — four-card module hub (Setup, Processing, Reports, Year End)  
**`MainMenuController`** — Payroll tab in top nav, sidebar section, PACD01 MenuEntry live

### Before running PACD01 for the first time
```sql
-- Confirm actual pacodes columns on your installation:
SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'pacodes' AND TABLE_SCHEMA = DATABASE()
ORDER BY ORDINAL_POSITION;
```
If `active_flag`, `std_rate`, `std_amount`, `gl_code`, or `notes` are missing, `PayCodeService` falls back safely (strDefault helper). Add the columns with an ALTER TABLE if they should exist.

---

## Wave 1 — Setup CRUD

*Prerequisite: Wave 0 complete.*  
*DB confirmation needed before coding PAAW01 — see queries at bottom.*

### PAEM01 — Employee Maintenance

**COBOL equivalent:** PAEM01.pl  
**Table:** `pastaff` — PK: `(company_no, employee_no INT)`

Key columns to expose in the UI:
```
employee_no, name1, name2, dept, address fields,
employee_status (A/I/T), employment_type,
date_started, date_terminated,
std_rate_per_hr, pay_frequency,
award, job_class,
tax_file_no, tax_scale, tax_variation_flag, tax_variation_amt,
bank_bsb, bank_account_no, bank_account_name,
al_hrs_accrued (÷60 for display), sl_hrs_accrued (÷60),
email_address, phone
```

**Screen structure (mirrors COBOL P1 → S1 → S1A → S2 pattern):**
- P1: TableView — employee_no, name1, dept, status, pay_frequency
- S1 modal: personal details + employment + bank tabs
- S2 modal: leave balances (display only — accrual is calculated)
- Toolbar: Add | Edit | Terminate | Leave Balances | Print

**Service:** `EmployeeService` — findAll, findOne, search, insert, update, terminate, loadLeaveBalances  
**Controller:** `EmployeeMaintenanceController` — wire into `PayrollMenuController.openEmployeeMaintenance()`

**Gotchas:**
- `pastaff.dept` not `dept_code` (differs from FA tables)
- `std_rate_per_hr` is per-hour rate for COBOL hourly employees
- `al_hrs_accrued`, `sl_hrs_accrued` etc. stored as minutes — display ÷ 60
- `tax_file_no` — do not log or print in full; mask last 5 digits in UI

---

### PAPG01 — Pay Group Maintenance

**Table:** `pagroup` — PK: `(company_no, pay_group VARCHAR)`

Pay groups define pay frequency and GL posting codes for a set of employees. Simpler CRUD than PAEM01 — single-tab dialog is sufficient.

Columns to confirm before coding:
```sql
SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'pagroup' AND TABLE_SCHEMA = DATABASE();
```

Expected: `pay_group, desc1, pay_frequency (W/F/M), gl_wages_code, gl_super_code, active_flag`

---

### PASU04 — Tax Scale Maintenance

**COBOL equivalent:** PASU04 (`cobol/pa2/pasu04.{cbl,pl,ws}` + S1–S4 sub-screens)
**Table:** `pataxfl` — PK: `(company_no, scale_no)`

**Sub-screens (mirrors COBOL pattern):**
- **S1** — Scale header: scale_no, desc_1, desc_2, leave_loading_limit,
  term_tax_perc, includes_hecs_flag, rounding_ind, fbt_perc_rate, fbt_taxable_amt
- **S2** — Brackets, levels 1–14: `(weekly_earnings, coeff_a, coeff_b)` each
- **S3** — Extra brackets, levels 15–26
- **S4** — STSL rate table: `(weekly_amt, rate)` per level

**Data overlap with the new `tax_brackets` table (PATX01 bulk-loader):**
`pataxfl` carries scale-level config (FBT, leave loading) **and** brackets;
`tax_brackets` carries only ATO-loaded bracket coefficients. Decide before
coding which is authoritative going forward — either:
  - Keep both: PASU04 edits `pataxfl` for config + manual brackets,
    PATX01 loads ATO publications into `tax_brackets`; calculator reads
    whichever per scale.
  - Consolidate: PATX01 writes to `pataxfl` (re-shapes to OCCURS layout
    at load), PASU04 becomes the single editor.

**Special scales:**
- `"H"` — HECS marker; PASU04 blocks deletion
- `"1S"`, `"2S"`, etc. — auto-created when an STSL rate table is entered for the matching base scale

---

### PAAW01 — Award Maintenance

**Most complex Wave 1 program — code last.**

**Tables:** `paawhed`, `paawjob`, `paawwcp`

```
paawhed  PK: (company_no, award_code)
paawjob  PK: (company_no, award_code, job_class_code) — 80+ columns
paawwcp  PK: (company_no, award_code, wc_premium_code)
```

**FK naming mismatch — critical:**  
`paawhed/paawjob/paawwcp` use `award_code` / `job_class_code`  
`pastaff/paehist/paecode` use `award` / `job_class` (no `_code` suffix)

**Before coding, run these confirmation queries:**
```sql
-- Confirm paawhed columns
SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'paawhed' AND TABLE_SCHEMA = DATABASE();

-- Confirm paawjob columns (80+)
SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'paawjob' AND TABLE_SCHEMA = DATABASE()
ORDER BY ORDINAL_POSITION;

-- Confirm paawwcp FK name
SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'paawwcp' AND TABLE_SCHEMA = DATABASE();

-- Confirm paawchg uses award or award_code
SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'paawchg' AND TABLE_SCHEMA = DATABASE();

-- Confirm pagroup columns
SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'pagroup' AND TABLE_SCHEMA = DATABASE();

-- Confirm super_commence_date is proper DATE (already verified - it is)
SELECT super_commence_date FROM paawjob LIMIT 3;
```

**`paawjob` leave structure (80+ columns):**
The COBOL award stores AL (annual leave), LSL (long service leave), sick leave (3 tiers), RDO (rostered days off), and super band tables all in a single wide row. The Java model should flatten these into named groups:

```java
class AwardJobClass {
    // AL
    int    alAccrualRate;      // minutes per period
    int    alMaxAccrual;       // minutes cap
    // LSL
    int    lslQualPeriod;      // months to qualify
    BigDecimal lslRate;
    // Sick — 3 tiers
    int    sickTier1Hrs;       // minutes
    int    sickTier2Hrs;
    int    sickTier3Hrs;
    // Super bands (array or separate rows)
    BigDecimal superRate;
    LocalDate  superCommenceDate;
    // ... etc
}
```

Plan for 3+ Claude Code sessions for PAAW01 alone.

---

## Wave 2 — Batch Updates

*Prerequisite: Wave 1 complete.*

| Program | What it does | Notes |
|---------|-------------|-------|
| PAEM11 | Mass employee update (dept, pay group, rate) | Confirmation dialog essential — mass write |
| PAPC01 | Pay code maintenance (batch add/update to paecode standing lines) | Loops over employees |
| PAEM60 | Employee archiving / de-activation | Cascades to paecode, checks paehist |
| PASU55 | Recalculate YTD totals from paehist | Dangerous — add dry-run mode |
| PASU11 | Carry forward annual leave to new year | Run after PADE01 |
| PASU14 | Carry forward LSL | Run after PADE01 |
| PASU15 | Carry forward sick leave | Run after PADE01 |

All Wave 2 programs write to multiple tables in a single transaction. Each needs:
- Preview mode (show what will change before committing)
- Audit log write to a `pa_audit` table (to be confirmed/created)
- Progress indicator for large employee counts

---

## Wave 3 — Pay Processing

*Prerequisite: Wave 1 + representative test data (at least 10 employees across 2 pay groups).*

### PATM01 — Timesheet Entry
**Most complex program — plan 6 Claude Code sessions.**

**Tables read/written:** pastaff, parunhd, paehist, paecode, paawjob  
**AppSession:** must call `appSession.setSelectedPayrunNo()` on payrun selection — all other screens in the pay cycle read from it

**Screen flow:**
```
S1: Select payrun (parunhd list for current yr_no)
    OR create new payrun header
P1: Employee list for the payrun
    [Enter Hours] → S2: timesheet entry grid
S2: Hours grid — standard hours pre-filled from paecode
    Override: actual hours worked, leave types
    [Calculate] → preview gross
    [Save] → write paehist rows
```

**Hours calculation rules (from COBOL):**
- Base hours from `pastaff.std_rate_per_hr × hours_worked`
- Overtime rates from `paawjob` bands
- Leave loading: `al_loading_rate` from paawjob when AL is taken
- Super: calculate on ordinary time earnings (OTE) — check `super_flag` on each pay code

**Key paehist PK — must match exactly:**  
`(company_no, employee_no, payrun_date, pay_type, pay_code, payrun_no, line_no)`  
`line_no` is sequential within the payrun for each employee — manage in `TimesheetService`.

---

### PAPP01 — Pay Run Processing

Reads paehist, applies tax scales, calculates super, produces:
- Net pay per employee (to paehist deduction rows)
- Super guarantee amounts (to paehist super rows)  
- Employer super (to pacosts)
- GL posting summary (ready for PAPP28)

**Tax scale application:** ATO tax tables stored in — confirm table name:
```sql
SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE '%tax%' OR TABLE_NAME LIKE '%ato%';
```

---

### PABK02 — ABA Payment File

Generates an ABA (Australian Banking Association) formatted file for direct credit to employee bank accounts.

**ABA format — fixed width, not CSV:**
```
Record 0: File header    (120 chars)
Record 1: Detail (1 per employee) (120 chars)
Record 7: File trailer   (120 chars)
```

Output directory: `appSession.getPayrollFilesDir()` → fallback to `landmark.payroll.output-dir` in `application.properties`.

Filename convention: `PAYROLL_YYYYMMDD_NNN.aba` where NNN is payrun_no.

**Validation before generating:**
- All employees have BSB + account number in paempay
- BSB is 6 digits (format: NNN-NNN)
- Net pay amounts are positive
- Total credits = sum of all detail records

---

### PAPA14 — Leave Processing
### PAPA30 — Leave Payout  
### PAPP28 — Payroll GL Posting

These three coordinate leave recording, leave payouts, and the final GL journal write from the completed pay run. Plan after PAPP01 is working.

---

## Wave 4 — Compliance

*Prerequisite: Wave 3 complete + ATO developer sandbox account registered.*

### PAST10 — Single Touch Payroll (STP Phase 2)

ATO SBR2 submission — the most regulated program in the system.

**Register before coding:**  
ATO Developer Portal: `https://developer.ato.gov.au/`  
Register for SBR2 sandbox access. Get software ID and test DSP credentials.

**What STP Phase 2 submits (per pay event):**
- Employee income types (salary, allowances, lump sums)
- Tax withheld
- Super guarantee (not just amounts — fund details)
- FBT reportable amounts
- PAYG withholding summary

**Implementation approach:**
1. `StpPayloadBuilder` — assemble the JSON payload from paehist + paytd
2. `StpService` — sign and POST to ATO via SBR2 gateway
3. `StpSubmissionRecord` — store submission response (receipt ID, timestamp) in a `pa_stp_log` table
4. `StpController` (PAST10) — UI to review, submit, and track STP events

**Do not start until the ATO sandbox is available.** The payload format changes frequently — code against the live ATO test environment, not from documentation alone.

---

### PAPS26 — Payment Summaries (Annual)

End-of-year PAYG payment summaries per employee. Reads `paytd` (year_no = calendar year, NOT yr_no).

Confirm `paytd` columns:
```sql
SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'paytd' AND TABLE_SCHEMA = DATABASE();
```

---

### PADE01 — Payroll Year End

The year-end roll-forward program. Sequence:
1. Validate all payruns for the year are posted (parunhd.payrun_status = 'P')
2. Clear YTD accumulators in paytd for the new year
3. Create new year record in gldates (confirm this is the right table — it may be separate)
4. Set new yr_no in parunhd sequence start
5. Trigger PASU11/14/15 (carry-forward leave)

**Gate with license check** — this is the program that should block if the license is expired. See license plan in session notes.

---

## Wave 5 — Reports

| Program | Report | Data source |
|---------|--------|-------------|
| PATL10 | Payroll Summary (by employee, by period) | paehist GROUP BY |
| PATL12 | Employee Listing | pastaff |
| PATL14 | Pay Code Analysis | paehist × pacodes |
| PATL20 | Leave Balances | pastaff leave columns |
| PATL22 | Super Contributions | paehist where pay_type=5 |
| PATL30 | Department Cost Summary | pacosts |

All PY reports: export PDF (pdfbox) + Excel (Apache POI). Use existing export service pattern from FA.

---

## Payroll service architecture — target state

```
payroll/
  model/
    PayCode.java              ✅ done
    Employee.java             Wave 1
    PayGroup.java             Wave 1
    AwardHeader.java          Wave 1
    AwardJobClass.java        Wave 1
    TimesheetLine.java        Wave 3
    PayrunHeader.java         Wave 3
    StpPayload.java           Wave 4

  service/
    PayCodeService.java       ✅ done
    EmployeeService.java      Wave 1
    PayGroupService.java      Wave 1
    AwardService.java         Wave 1
    TimesheetService.java     Wave 3
    PayRunService.java        Wave 3
    AbaFileService.java       Wave 3
    GlPostingService.java     Wave 3
    StpService.java           Wave 4
    YearEndService.java       Wave 4

  ui/
    PayrollMenuController.java       ✅ done
    PayCodeMaintenanceController.java ✅ done (PACD01)
    EmployeeMaintenanceController.java Wave 1 (PAEM01)
    PayGroupController.java           Wave 1 (PAPG01)
    SupervisorSetupController.java    Wave 1 (PASU04)
    AwardMaintenanceController.java   Wave 1 (PAAW01)
    TimesheetController.java          Wave 3 (PATM01)
    PayRunController.java             Wave 3 (PAPP01)
    AbaController.java                Wave 3 (PABK02)
    LeaveController.java              Wave 3 (PAPA14/30)
    GlPostingController.java          Wave 3 (PAPP28)
    StpController.java                Wave 4 (PAST10)
    PaymentSummaryController.java     Wave 4 (PAPS26)
    YearEndController.java            Wave 4 (PADE01)
```

---

## Rules that apply to every payroll program

1. **No JDBC in any controller** — all SQL in the paired `*Service`
2. **All monetary values: `BigDecimal`** — never `double`
3. **All hours: divide DB value by 60** before display
4. **`company_no` in every WHERE clause** — from `appSession.getCompanyNo()`
5. **`parunhd.yr_no`** (not year_no) FK → `gldates.yr_no`
6. **`paytd.year_no`** = calendar year (4-digit, not yr_no sequence)
7. **Date guard:** `LandmarkDateUtils.isValidLandmarkDate(ld)` before arithmetic (year > 1900)
8. **Write operations:** `@Transactional` on all service methods that touch multiple rows
9. **Payrun selection:** set `appSession.selectedPayrunNo` when user selects a payrun — don't pass it as a parameter through 4 layers
10. **Tax file numbers:** never log in full — mask as `XXX-XXX-XXX` in any log output

---

## DB queries to run before Wave 1 coding

```sql
-- 1. All payroll tables
SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'pa%'
ORDER BY TABLE_NAME;

-- 2. pastaff columns
SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'pastaff' AND TABLE_SCHEMA = DATABASE()
ORDER BY ORDINAL_POSITION;

-- 3. parunhd columns
SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'parunhd' AND TABLE_SCHEMA = DATABASE()
ORDER BY ORDINAL_POSITION;

-- 4. paawhed + paawjob FK column names
SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME IN ('paawhed','paawjob','paawwcp','paawchg')
AND TABLE_SCHEMA = DATABASE()
ORDER BY TABLE_NAME, ORDINAL_POSITION;

-- 5. pagroup columns
SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'pagroup' AND TABLE_SCHEMA = DATABASE();

-- 6. payhist sample to confirm ext_amt column name
SELECT * FROM paehist LIMIT 2;

-- 7. Verify hours-as-minutes pattern
SELECT employee_no, al_hrs_accrued,
       al_hrs_accrued / 60.0 AS al_hours_display
FROM pastaff WHERE company_no = 1 LIMIT 5;

-- 8. Tax table location
SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA = DATABASE()
AND (TABLE_NAME LIKE '%tax%' OR TABLE_NAME LIKE '%ato%' OR TABLE_NAME LIKE '%withhold%');
```

---
*Landmark Payroll Development Plan — v1.0 — 2026-05-09*
