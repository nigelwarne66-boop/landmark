# Landmark — JavaFX + Spring Boot Desktop ERP

## What this is

COBOL-to-Java conversion of Landmark ERP (Australian accounting/payroll).
JavaFX desktop app with embedded Tomcat on port 8090.

**Package root: `com.landmarksoftware`** (payroll lives in
`com.landmarksoftware.payroll.{model,service,ui}`).

## Non-negotiable rules

- **ZERO `jdbc.*` calls in any controller** — all SQL goes in the paired `@Service` class.
- All monetary/rate values: `BigDecimal` (never `double`).
- All hours from PA tables: stored as **minutes** in DB, divide by 60 for display.
- All write operations: `@Transactional` on the service method.
- `company_no` in every WHERE clause — from `appSession.getCompanyNo()`.
- Controllers contain only JavaFX. Services contain only JDBC.
- **TFN is never printed/logged in full** — `Employee.maskTfn(String)` (static, shared by UI Show/Hide toggle and the PDF print service) returns `***-***-NNN`.
- **New tables follow the work-file rule.** If the table name contains `wk` (e.g. `paemwk1`, `pasuwk3`, `patmwkk`) it's a work file — create it directly in Java via `CREATE TABLE IF NOT EXISTS` in a `@PostConstruct` on the owning service. **Every other new table** must have the four extract-pipeline files at `C:\landmark_extract\sql\` **before** any Java depends on it: `create/<table>_create.sql`, `insert/<table>_insert.sql`, `insert/<table>_select.sql`, `insert/<table>_values.sql` — plus the name appended to `list/module/pafiles.txt`. Reason: that pipeline is how COBOL `.dat` data flows from ACU into MySQL; a production table without those four files can't be populated from the source. Existing exceptions (`tax_brackets`, `pa_audit`) pre-date the rule and have no ACU origin — not a template for new tables.

## Recent learnings (always check these before assuming COBOL behaviour)

Captured iteratively during Wave 3 — small COBOL details that bit us once and shouldn't bite again.

- **Two COBOL date conventions coexist in the data — verify per field, never assume:**
  - **Landmark Julian** — `INT` count of days since **1899-12-31**. `pagroup.paid_thru_to_X`, `pagroup.payrun_date_X` and similar.   Helper: `landmarkDayToDate(int)` in `TimesheetEntryController`. Verified via live `pagroup.csv` 2026-05-16: 44773 → 2022-07-31.
  - **YYMMDD-packed** — 6-digit `INT` like `260531` = 2026-05-31. Year pivot `yy < 40 → 20yy`, else `19yy`. Used by some other COBOL date fields. Helper: `MainMenuController.dayNoToDate`.
  - **Native DATE** — most extract-pipeline tables (`parunhd`, `paehist`, `patimhd`, `paleave`) just use MySQL `DATE`. Sentinel for "no date" is `1899-12-31`.
- **COBOL program names lie sometimes — read the proc, not the menu:**
  - **PAPA14** is *not* leave processing despite its old menu label. Per `papa14.pl`, it's the **CM/GL payment-batch posting** interface (entry to PAPA15+).
  - **CREATE-PAYRUN** on PATM01 P2 does *not* create a new parunhd. Per `patm01.pl:1027`, it validates the current payrun + transitions to PATM02 (the all-paygroups timesheet builder = our P3).
- **Leave model — `paleave` is exceptional events only, NOT a per-pay-period log:**
  - `accrued_taken_ind='A'` rows only from **PAEM01** (manual edits) + **PASU18** (opening-balance migration).
  - `accrued_taken_ind='T'` rows only from **PAPP28** posting (one per leave-taken patimes line).
  - Regular per-period accrual (**PAPP03** `ACCRUE-LEAVE`) updates `pastaff` running balances directly — does *not* write paleave.
  - On un-post, PAPP28 reverses both: deletes 'T' rows + adds the minutes back to pastaff.
- **Paygroup auto-attach algorithm** (`SET-PAY-THRU-DATES`, `patm01.pl:1383`) — per frequency, compute next pay-thru only when:
  1. `pagroup.payrun_active_X != 'Y'` (not already in another payrun), AND
  2. `pagroup.paid_thru_to_X > 0` (has history). Then `next = paid_thru_to_X + period` (1mth / 28d / 15d / 14d / 7d).
- **Bootstrap path for a brand-new company / paygroup with no history:** validate=Y in the Select Paygroups dialog now prompts to attach with `payrun.end_date` defaults for all 5 frequencies. validate=N still skips (COBOL-accurate).
- **PAEM01 read-only state columns** — `paid_thru_to_date`, `timesheets_to_date`, `last_payrun_no`, `current_payrun_no`, retainer/commission running totals on pastaff are **owned by PAPP01 / PAPP28**. PAEM01 must NOT include them in UPDATE — round-trip them in memory only. (The 2026-05-22 backfill respects this.)
- **ABA employer-side fields come from `cmbanks`**, not session.companyName. Pick the row where `eft_pa_flag='Y'` and not inactive. `user_no` (6-digit APCA), `eft_bank_code` (3-char abbreviation), `eft_name` (26-char), `branch_no`+`bank_acct_no` (trace), `pay_serv_remitter_name` (16-char). See `CmBanksService.findPayrollBank`.

## Working style

- Never ask for confirmation mid-task. Complete the full scope, then report results.
- When a compile check passes, move immediately to the next item without stopping.
- Only stop and ask if a genuine blocker hits — missing file, ambiguous design decision not covered by `CONVENTIONS.md`, or an unresolvable compile error.
- At the end of a task, give a summary of what was done. Do not ask if you should continue.

## Key files to read first

- `CONVENTIONS.md` — confirmed table names, column corrections, architecture rules.
- `PAYROLL_PLAN.md` — wave-by-wave payroll development plan (status tracked there).
- `TAX_SCALE_LOAD.md` — annual ATO tax-table refresh process.
- `src/main/java/com/landmarksoftware/payroll/ui/PayCodeMaintenanceController.java` — the pattern every new screen follows.
- `src/main/java/com/landmarksoftware/payroll/service/PayCodeService.java` — the pattern every new service follows.

## DB

MySQL, schema `lmextract`, port 3306. Main payroll table: `pastaff` (PK: `company_no, employee_no INT`).

The MySQL schema is generated from the COBOL extract pipeline at `C:\landmark_extract\` — `sql/create/<table>_create.sql`. **Extract is intentionally lossy in two places:**
- `pataxfl` drops the OCCURS bracket columns (engine cap on OCCURS extraction).
- `pasumry` is permanently dropped (engine produces garbage from nested COMP-3 OCCURS).

The COBOL canonical source for `.fd`/`.ws` is `C:\landmark\compile\` (with rare exceptions falling back to `C:\landmark\cobol\pa2\`).

---

## Current state (as of 2026-05-26)

### Wave 0 — Foundation ✅
- Module scaffolding, `AppSession` (mirrors GLPASS), PACD01 (Pay Code Maintenance).

### Wave 1 — Setup CRUD ✅ Complete

| Program | Tables | Pattern |
|---------|--------|---------|
| **PAEM01** Employee Maintenance | pastaff + paempay (bank splits) | P1 list + 5-tab dialog (Personal / Employment / Pay & Tax / Bank-EFT / Super) + Leave Balances modal + MVR check + Print PDF |
| **PAPG01** Pay Group Maintenance | pagroup + padepts | P1 list + 7-tab dialog including Departments drill (P2→S2) |
| **PASU04** Tax Scale Maintenance | pataxfl (config) + `tax_brackets` (read-only) | P1 list + 2-tab dialog (Scale Config / Brackets) |
| **PAAW01** Award Maintenance | paawhed + paawjob (~80 cols, 7 sub-tabs) + paawwcp | P1 list + 3-tab dialog (General / Job Classes / WC) |
| **PATX01** Load ATO Tax Scales | `tax_brackets` (Java-managed) | Year End → File picker dialog, runs `TaxBracketLoader` |

### Wave 1.5 — Audit back-fill ✅ Complete
Per-change audit trail wired alongside the existing per-row `audit_user_id/date/time*` columns. See "Audit framework" section below for what landed and what's deferred.

### Wave 2 — Batch operations ✅ Complete (with two CRUD-style screens trimmed)
Foundation + 5 full batch programs + 2 thin maintenance screens. See "Wave 2 detail" section below.

### Wave 3 — Pay run processing ✅ End-to-end runnable
- **`PaygTaxCalculator`** ✅ — single-lookup against `tax_brackets` (NAT_1004 / NAT_3539). Verified scale 2, $1500 STSL → $336. Weekly base + ATO conversion for F/M. As-of-date lookup. Wired into `PayrollCalcService`.
- **Extract pipeline schemas** ✅ for `patimhd`, `patimes`, `parungr` in `C:\landmark_extract\sql\` + `pafiles.txt`. SQL applied 2026-05-14.
- **PATM01** ✅ — S0 / P1 / P2 / P3 / S3 / S3B all wired:
  - S0: date range + include-fully-posted / cancelled / type filter.
  - P1: Add (chains into Options) / Options ▸ / Cancel / Refresh / Filter.
  - **Options dialog**: 1 Edit · 2 Default (cost type **I/G/L**, calc tax/super, skip-paygroup, retainer/splits/RDO) · 3 Select · 4 Create · 5 Import. Transitions use `Platform.runLater` to avoid nested-modal input lockup.
  - **Select Paygroups picker**: start/end combos sourced from `pagroup` master, rendered as `code — description`. Validate-before-selection checkbox. OK auto-attaches the range to the payrun.
  - P2: Back · Select ▸ Timesheets · Add · Edit · Delete · Create Payrun · Status · Range… · Refresh. Create Payrun matches COBOL CREATE-PAYRUN (validates parungr non-empty, hands off to P3).
  - **P3** (employee/timesheet list): Surname | First Name | Employee No | Paygroup | Total Hours | Gross | Net. Toolbar: Back · Add · Edit · Delete · Pay Method · Print · Super · **Standing Lines** · Refresh. Delete cascades patimes; Pay Method / Print / Super still stubbed.
  - **S3** (Add/Edit timesheet header): prompts for employee, validates pastaff, opens header dialog (pay-thru dates, status, default/costed/calc-tax flags). On OK offers "Seed from paecode" → inserts patimes from the standing rows, then drills into S3B.
  - **S3B** (per-line patimes editor): modal listbox of timesheet lines with Add / Edit / Delete; per-line dialog covers pay type/code, paygroup/dept/award/job class, hours/qty/rate/ext amt, date, ref.
  - **paecode CRUD** ✅ — Standing Lines button on P3 opens a modal listbox of paecode rows for the selected employee. Add/Edit/Delete all flow through `MasterFileAuditService.auditPaeCode` → activates the Wave 1.5 `papcaud` audit hook.
- **PAPP01 — Pay Run Processing** ✅
  - `PayrollCalcService.recalcPayrun` reads patimes for every patimhd on the payrun, categorises by pay_type (24-code map, see service Javadoc), updates patimhd running totals, then calls `PaygTaxCalculator` for PAYG withholding using the employee's tax_scale_no + STSL flag + pay_freq. On full success flips `parunhd.calcs_completed_flag` to `Y`.
  - `PayRunProcessingController` lists open payruns with a "Calculate Tax + Totals" button. Wired into both menus.
- **PAPP28 — Payroll Posting** ✅ — `PayrollPostingService.postPayrun` moves every patimes line into paehist, writes a synthetic tax line per employee at pay_type=22 carrying PAPP01's total_tax, flips patimhd statuses to P and parunhd to P. All-or-nothing transaction. `unpostPayrun` reverses (deletes paehist + flips statuses back to O). Wired into PayRunProcessingController as **Post ▸ paehist** + **Un-post** buttons next to Calculate.
- **PABK02 — ABA Payment File** ✅ — `AbaFileService` derives net pay from paehist (`sum(gross_amt) − sum(tax_amt) − sum(ext_amt)` for pay_type 19/20/21), applies paempay split rules (A→P→B), writes APCA fixed-width 120-char records (header / detail / trailer) to `appSession.payrollFilesDir` as `PAYROLL_YYYYMMDD_NNN.aba`. Employer bank details — APCA user number, EFT bank abbreviation, trace BSB / account, remitter name — sourced from `cmbanks` where `eft_pa_flag = 'Y'` via `CmBanksService.findPayrollBank`. Refuses generation if no payroll bank is configured. BSB validated 6-digit non-zero; invalid splits warn-and-skip. UI in `AbaPaymentController`.
- **Leave processing — corrected 2026-05-16** — original port mis-labelled the leave service as PAPA14. Verified against COBOL: PAPA14 is the CM/GL payment-posting interface (entry to PAPA15+ chain). Actual leave behaviour split across:
  - **PAPP03 — `PayrollLeaveService.accruePayrun`** — accrues AL / SL / AL-loading onto `pastaff` per employee. Uses `paawjob.al_hrs / sick_hrs_1 / all_hrs` per job class; falls back to flat 4/52 (AL) and 2/52 (SL) when no award rate. Branches on `pastaff.accrue_al_by_hrs_flag` and `employee_type`: F (full-time) + non-hourly → full period entitlement; P (part-time) or F+by-hours → pro-rata (entitlement × hoursWorkedMin / 2280). Casuals skip AL/SL. Termination payruns skip accrual. **Does NOT write paleave** — only updates pastaff (matches COBOL). Wired as "Process Leave (PAPP03)" button on the PAPP01 screen.
  - **PAPP28 — `PayrollPostingService` leave hooks** — when posting patimes lines with pay_type ∈ {4 LSL, 5 AL, 6 AL-load, 7 Sick}, writes/updates a paleave row with `accrued_taken_ind='T'` and decrements the matching pastaff balance column (`lsl_hrs_aft_78` / `al_hrs_accrued` / `accrued_al_loading` / `accrued_sick_leave`). Un-post reverses both.
  - **`paleave` ledger** — Per COBOL, paleave rows are *exceptional* events: 'A' (accrued) only from PAEM01 manual edits or PASU18 opening-balance migration; 'T' (taken) only from PAPP28 posting. Regular per-period accrual never touches paleave.
- **Award-rate AL/SL overrides** (paawjob.al_accrual_rate per job class), **LSL** accrual (years-of-service tracking), **PAPA30** leave payout, **GL journal posting**, and **PAPP01 Pay Method / Print / Super** P3 buttons are deferred — clearly labelled stubs.

### Pay-type → patimhd column mapping (PayrollCalcService)
The mapping is best-guess based on the 24-code pacodes set; refine as gaps surface. Pay-types not listed fall into `total_other_pay`.
| Pay type | Bucket |
|----------|--------|
| 1 | Normal pay + normal minutes |
| 2 | Overtime + otime minutes |
| 3 | Annual leave + AL minutes |
| 4 | AL loading (no minutes) |
| 5 | Sick leave + sick minutes |
| 6 | LSL + LSL minutes |
| 7 | Other leave + other-leave minutes |
| 8 | Other pay + other minutes |
| 11 | Taxable allowance |
| 12 | Non-taxable allowance |
| 13–18 | Term A / B / C / D / E / W |
| 19 | Before-tax deduction |
| 20 | After-tax deduction |
| 21 | Super (employee contribution) |
| 23 | Backpay |

### Cross-cutting — Session persistence
`LastSessionStore` (`~/.fixedassets/session.properties`) ✅ persists the user's MENU23 pick (`lastCompanyNo` + `lastYearNo`). `loadDefaultSession` honours it on next login when the company still exists; otherwise falls back to first-company + latest-year.

### Wave 4, 5
Per `PAYROLL_PLAN.md`.

---

## Key decisions made during Wave 1

### Tax scale architecture
- **`pataxfl` carries scale-level config only** (FBT, leave loading, term tax %, rounding ind, HECS flag, descriptions). Brackets are *not* in the MySQL extract — engine OCCURS cap.
- **`tax_brackets` carries all bracket coefficients**, loaded from ATO Excel via PATX01.
- **PK**: `(company_no, source_file, effective_from, scale_no, bracket_no)`. Loader uses DELETE-then-INSERT keyed by the first three columns — safe to rerun.
- **Single-lookup calculation** (not PAYG + STSL add-on):
  - No STSL → look up `(NAT_1004, scaleN)`
  - Has STSL → look up `(NAT_3539, scaleN)` — NAT_3539 rows carry the **combined** PAYG+STSL coefficients
  - Reproduces the ATO Tax Calculator exactly (verified: scale 2, $1,500 → $336)
  - NAT_3539 sheet 5 "STSL Component Rates" doesn't reconcile with the ATO calc — we don't use it.
- **Same scale_no convention for both files** (1..6) — no T/N codes, no "1S" companion suffix.

### Drill-down dialog pattern
Used in PAEM01 (bank splits), PAPG01 (departments), PAAW01 (job classes + WC rows).
- Child rows live in their own table — saves happen **immediately on Add/Edit/Delete inside the drill modal**, independent of the outer parent Save/Cancel.
- Drill tab is disabled when adding a new parent (`isAdd=true`) — save the parent first, then drill in.

### Cascading deletes
- **PAAW01**: deleting an award wipes paawjob + paawwcp + paawhed in one transaction. Deleting a job class wipes paawwcp + paawjob.
- All other entities use **delete guards** (mirror COBOL): block when active employees reference the row.
- Scale `"H"` (legacy HECS marker) deletion is blocked.

### PAPG01 S2 strictness
The `padepts` table has 18 GL account columns. The COBOL S2 maintenance screen only exposes **9** — the rest are populated by other COBOL paths (CPCOYCO defaults, wage-accrual processing, leave-provision processing). Java S2 matches COBOL: **shows 9 fields**, but the Save logic carries the remaining 9 forward unchanged on update so the other-process data isn't blanked.

### Audit columns + date sentinels
- Every `pa*` table has `audit_user_id`, `audit_date`, `audit_time_hr/min/sec/hun`. NOT NULL.
- Services populate these from `user_name` + server clock on every insert/update.
- COBOL date-zero sentinel is `1899-12-31` — use this for NOT NULL dates with no real value.

### Misc
- `MainMenuController` has its **own** payroll quick-launch grid separate from `PayrollMenuController`. When wiring a new program, add a `MenuEntry` to both lists. (Learned the hard way with PATX01 — first wave only wired the module menu.)
- ATO Excel reads via Apache POI 5.2.5 (already in pom). Sheet index 1 is "Statement of Formula - CSV" in both NAT_1004 and NAT_3539.
- Spring component scan: `@SpringBootApplication(scanBasePackages = "com.landmarksoftware")` — every `@Component` / `@Service` is auto-wired.

---

## Audit framework — Wave 1.5 state

COBOL has a two-flavour audit on master files. Our Java port populates the per-row `audit_user_id/date/time*` stamp on every write **and** writes the per-change audit tables in the same transaction as the data write.

### Audit table coverage

| Table | Captures | Pattern | In extract? | Java writes? |
|-------|----------|---------|-------------|--------------|
| `paemaud` | Employee changes — JSON before/after + maint-type | Heavyweight | ✅ | ✅ via `EmployeeService` |
| `pafuaud` | Super fund changes — JSON before/after | Heavyweight | ✅ | ⏳ infrastructure ready, awaiting Super Fund Maintenance CRUD |
| `papcaud` | Per-employee `paecode` row changes | Per-row tracker | ✅ | ⏳ awaiting Wave 3 paecode CRUD (PATM01/PAPP01) |
| `pacdchg` | Pay code rate-before / amt-before; drives downstream recalc | Lightweight | ✅ | ✅ via `PayCodeService` on rate/amount change |
| `paawchg` | Award / job class last change date; drives recalc | Lightweight | ✅ | ✅ via `AwardService` + `AwardJobClassService` |
| `glchaud` / `apspaud` / `arcuaud` / `faasaud` / `fadraud` | Other modules' equivalents | Mixed | ✅ | n/a in payroll scope |

### What landed

- Extract pipeline schemas in `C:\landmark_extract\sql\create\` + matching `insert/select/values` triples. Heavyweight tables use `LONGTEXT` (JSON snapshot) instead of COBOL's fixed 1500-byte blob.
- `pafiles.txt` updated with `paemaud`, `pafuaud`, `paawchg`, `pacdchg`.
- `MasterFileAuditService` in `com.landmarksoftware.payroll.service` — single entry point per audit table:
  - `auditEmployee(...)` writes paemaud
  - `auditFund(...)` writes pafuaud
  - `auditPayCodeRateChange(...)` UPSERTs pacdchg
  - `auditAwardChange(...)` UPSERTs paawchg
- JSON serialisation via Spring's Jackson with TFN masking applied to every employee snapshot (CLAUDE.md TFN rule).
- `EmployeeService.update/terminate` now take `userId` so the audit row carries an attribution.

### What's deferred

- `pafuaud` writes — no pafunds CRUD today (FundService is read-only). When Super Fund Maintenance lands, every write must call `MasterFileAuditService.auditFund` in the same `@Transactional` block. Note: PAEM01 super-tab edits go to `paemaud` (they change pastaff, not pafunds).
- `papcaud` writes — paecode CRUD doesn't exist until Wave 3 (PATM01/PAPP01).
- pastaff's own `audit_user_id/date/time*` columns are still left stale by `EmployeeService.update` — only the audit ROW is correctly attributed. The per-row stamp on update of pastaff is a pre-existing gap, separate from the audit-trail work.

### Wave 2 fits on top

The `pa_audit` table (option B from the chat — batch-level metadata only) sits alongside the Wave 1.5 per-change tables. PASU55's recalc-YTD batch writes one `pa_audit` row + many `paemaud` rows for each employee touched. Together: pa_audit gives you batch grouping, paemaud gives you per-employee before/after.

---

## Build + run

- JDK 25 (compile), JRE 1.8.0_421 (legacy Hibernate at runtime).
- `mvn -q compile` to build, `mvn javafx:run` to launch.
- **Reporting-only build**: `mvn javafx:run -Preporting` — same Spring context + login, swaps MENU01 for the Reports Hub (see "Reporting build" section below).
- Maven picks up `JAVA_HOME` from env — set to `C:\Program Files\Java\latest\jdk-25` before running.
- ProGuard runs at `package` phase (rules in `src/main/proguard/rules.pro`) — not in normal dev cycle.

---

## Reporting build (-Preporting)

Standalone JavaFX entry that shares the full app's Spring context, login, and DB but renders a Reports Hub instead of MENU01. Run with `mvn javafx:run -Preporting`. 5 modules / 12 Jasper reports, all wired end-to-end.

### Architecture
- `AppMode.current` (set to REPORTING in `ReportingApplication.main()`) directs `FixedAssetsApplication.start()` to load `/fxml/reports-hub.fxml` instead of `MainMenuController.buildScene()`.
- `MainMenuController.loadDefaultSession()` still runs to populate AppSession with company + year; the menu UI is never built.
- Reports save to `cpcntrl.local_pc_dir` (single global row — not per-company despite the `company_no` PK) and open via `cmd /c start "" "<path>"`. **Don't use `java.awt.Desktop`** — it silently no-ops in JavaFX-only builds because AWT isn't initialised.
- "Switch company" link spawns the same MENU23 dialog as the full app via `MainMenuController.showCompanyYearDialog(Window)` (made public + null-guarded for the sidebar-free reporting mode).

### Modules + reports
| Module | Reports | Notes |
|---|---|---|
| Fixed Assets | Asset Register, Depreciation, Acquired & Retired, Transaction List | No selection fields |
| Payroll | Payroll Summary, Employee List | Gated on `MEUSERS.print_pa_from_pass = 'Y'` — module absent for users without it |
| General Ledger | Trial Balance, Profit & Loss, Balance Sheet, General Journal, Account Transactions | Period-range selector (or single asAtPeriod for Balance Sheet) |
| Accounts Receivable | Debtors Ageing — summary + detail | |
| Accounts Payable | Creditors Ageing — summary + detail | |

### Selection screen pattern
Card click → `/fxml/reports/<module>/<report-name>.fxml` + `com.landmarksoftware.ui.reports.<Module><Name>Controller` (`@Component @Scope("prototype")`). Controllers call:
- `hub.runJasperReport(reportPath, extraParams, format, ownerWindow)` — for reports with static .jrxml SQL
- `hub.runJasperReportWithDataSource(reportPath, extraParams, JRDataSource, format, owner)` — for AR/AP ageing where SQL is too dynamic for a static block; rows pre-fetched via `Ar/ApDataService.get*ListingData(...)` and wrapped in a `JRBeanCollectionDataSource`

GL share `GlPeriodSelector.fxml` via `<fx:include>`; included controller's fields are bound by the `fx:id + "Controller"` naming convention.

### Jasper gotchas (each bit us at least once)
- **Excel column header repeats on every page** unless filled with `IS_IGNORE_PAGINATION=true` — paginated fill = N pages = N column headers. `JasperReportService.excelParams()` injects this; PDF still uses normal pagination.
- **White-text column headers invisible in Excel**: each `<staticText>` needs `mode="Opaque" backcolor="..."` because band-level `<rectangle>` backgrounds don't translate to Excel cell fills (PDF renders both).
- **Band order in `.jrxml`** must be `title → columnHeader → detail → pageFooter → summary` per the Jasper XSD. Putting `summary` before `pageFooter` errors with "Invalid content: pageFooter, expected noData".
- **Group variables with `resetType="Group"`** also need `resetGroup="<groupName>"` — missing this gives "Unknown reset group 'null'" at compile.
- **Feather, not Tabler** for Ikonli icons — Tabler isn't bundled with Ikonli. Prefix is `fth-` (e.g. `fth-package`, `fth-users`, `fth-bar-chart-2`).
- **GLDATES periods are 13 separate `period_end_01..period_end_13` columns** (COBOL OCCURS-style), not normalised rows. Unpivot in Java — see `GlPeriodService.loadPeriods()`. Use **`year_no` (4-digit calendar)** not `yr_no` (sequence PK) for matching.
- **CPCNTRL is a single global row** even though the PK is `company_no` — query with `LIMIT 1`, ignore the PK.

### Per-report .jrxml lookup
`ReportsHubController.openSelectionScreen(report, moduleId)` tries to load `/fxml/reports/<moduleId>/<report.getName()>.fxml`. If the FXML doesn't exist (e.g. a future report card you haven't built yet), it falls back to the "Coming soon" alert — so adding a new report just means adding the FXML + controller, no hub registry edits beyond a `ReportDef.withParams(...)`.

### Deferred
- `GlReportWriterService` migration (custom report-builder feature — cc-migration.md note #2)
- Excel report-time filter wires on the .jrxml are now complete for all 12 reports (filter UI matches what the SQL actually uses).

## Wave 2 detail — Batch operations

### Foundation (shared by every Wave 2 program)
- `pa_audit` table (option B — batch metadata only). Auto-created on startup by `BatchAuditService.ensureTable()` (same pattern as `tax_brackets`). Schema: `(audit_id PK, company_no, run_timestamp, user_id, program_code, description, rows_affected, status, notes)`.
- `BatchAuditService` — `start(...) → auditId`, `complete`, `fail`, `updateRowsAffected`, `appendNote`. Statuses: `R` running, `C` complete, `F` failed, `P` preview.
- `BatchPreviewDialog<T>` — reusable modal that shows what will change before any DB write. Takes a list of preview rows + column specs; returns `true` on Apply, `false` on Cancel/Esc.
- `BatchProgressDialog` — reusable progress bar + Cancel button bound to a `Task<R>`. Daemon executor; auto-closes when the task finishes.
- 6 new extract-pipeline schemas added: `paemwk1`, `pasuwk3` (work files), `pasphde`, `pasphdg`, `paspgre`, `paspgrg` (production tables for pay-phase splits). Each has create/insert/select/values plus a `pafiles.txt` entry. SQL was applied manually by the user on 2026-05-13.

### Programs

| Program | Tables touched | Pattern | Status |
|---------|----------------|---------|--------|
| **PASU14** Set Super Percentage | paecode, papcaud, pa_audit | Full preview + progress | ✅ |
| **PASU11** Update Award Rate Changes | paawchg → pastaff, paecode, paemaud, papcaud, pa_audit | Full preview + progress; clears paawchg rows after processing | ✅ |
| **PASU15** Global Employee Award Update | pastaff, paecode, paemaud, papcaud, pa_audit | Full preview + progress; 4 behaviour switches (rate/salary/timesheet/super) | ✅ |
| **PAEM60** Change Employee Pay Rates | pastaff, paecode, paemaud, papcaud, pa_audit | Full preview + progress; %-change semantics; 4 pay-type sections | ✅ |
| **PAEM11** Duplicate Default Timesheets | paecode (source → targets) + paemwk1 snapshot | Full preview + progress; REPLACE target paecode | ✅ |
| **PASU55** Leave Accrual Reversal | paleave, pasuwk3, paemaud, pa_audit | Thin: list-by-employee + bulk-reverse. Per-row Add/Edit dialogs **deferred** | ⏳ |
| **PAPC01** Timesheet Splits | pasphde, paspgre, pa_audit | Thin: master/detail viewer + delete-employee-split. Add/Edit dialogs + by-paygroup variant (`pasphdg`/`paspgrg`) **deferred** | ⏳ |

### What's wired into the audit trail

Every batch program writes:
1. **One pa_audit row** per run with the parameter summary and final row-count.
2. **paemaud** snapshots (employee before/after) on every pastaff change.
3. **papcaud** snapshots on every paecode change, including a "marker" row (`pay_code_type=1`, blank `pay_code`) when pastaff std rate/hrs/gross moves — mirrors COBOL `WRITE-PAY-CODE-AUDIT`.

### What's deferred (PASU55 + PAPC01)

These two were CRUD-style screens in the COBOL, not batch utilities. They don't fit the
`BatchPreviewDialog`/`BatchProgressDialog` pattern and need design treatment closer to
PAEM01 (multi-tab dialog with per-field validation). What's there now:
- **PASU55** — read-only paleave listing per employee + bulk-reverse-all button. Service has the right shape (`pa_audit` + `pasuwk3` + `paemaud` all wired). Missing: per-row Add/Edit dialogs.
- **PAPC01** — pasphde/paspgre master-detail viewer + delete-employee-split. Missing: per-row Add/Edit; entire by-paygroup variant (pasphdg/paspgrg).

Both are wired into both menus (PayrollMenuController + MainMenuController) and the Spring beans + services are in place — the next iteration just adds the dialogs.

### MenuEntry note (carried from Wave 1)
`MainMenuController` has its own quick-launch grid separate from `PayrollMenuController`. When wiring a new program, add a `MenuEntry` to both lists. Wave 2 added a third grid row to PayrollMenuController ("Mass Update" + "Batch Operations" cards). MainMenuController gained the same two cards at row 2.

## Things deferred / open

### From earlier waves
- **PASU55 + PAPC01 per-row dialogs** — see Wave 2 detail above.
- **PAPC01 by-paygroup variant** (pasphdg/paspgrg) — schema present, UI not yet built.
- **PASU04 manual bracket editing** — currently read-only; if users need to hand-tweak coefficients (e.g. custom scale "H"), needs Add/Edit/Delete UI on the Brackets tab.
- **Print everywhere** — PAEM01 has a Print button + `EmployeePdfService`. PAPG01 / PASU04 / PAAW01 don't (low priority).

### From Wave 3
- **PATM01 P3 toolbar stubs**: Pay Method (P3A patmpay editor) · Print (payslip) · Super (CREATE-SUPER-FOR-ALL).
- **PAPP28 GL journal** — only paehist is written today; the GL journal entries (creditors → expense accounts) are deferred until the patimes→paehist mapping is confirmed correct.
- **PAPP03 idempotency** — `PayrollLeaveService.accruePayrun` doesn't track per-payrun processed state; re-running double-accrues. Needs `parunhd.leave_processed_flag` or `pa_accrual_log`.
- **PAPP03 anniversary math** (COBOL `CALC-AL-SL-ANNIVERSARY`) — `al_hrs_curr_yr` resets on anniversary; currently always increments. Same for AL loading + sick year-totals.
- **PAPP03 lump vs incremental** — `paawjob.al_inc_lump_ind = 'L'` should only accrue on anniversary; currently always treats as incremental.
- **PAPP03 LSL** — not handled, needs years-of-service tracking + pre-78/aft-78/since-Aug-93 split.
- **PAPA14 (real, CM/GL payment posting)** — not built. Menu stub explains the mislabel; entry to the PAPA15+ chain.
- **PAPA30 leave payout** — not built.
- **PABK02 multi-bank support** — currently picks the first cmbanks row with `eft_pa_flag='Y'`. If a company runs payroll across multiple bank accounts, the controller will need a bank picker.
- **pafuaud writes** — still no Super Fund Maintenance CRUD; FundService remains read-only.
- **pastaff per-row audit on update** — pre-existing gap (separate from the audit-trail work). `EmployeeService.update` writes paemaud but doesn't refresh pastaff's own `audit_user_id/date/time*` columns.

### Wave 4+ blocked by external prerequisites
- **PAST10 (STP Phase 2)** — needs ATO developer sandbox account (developer.ato.gov.au) + SBR2 credentials. See `PAYROLL_PLAN.md`.
- **PAPS26 (Payment Summaries)** — annual; needs `paytd` column confirmation.
- **PADE01 (Year End)** — wraps PASU11/14/15 carry-forwards + license gate.
