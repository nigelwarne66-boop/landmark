# Landmark — Developer Conventions
*Updated: 2026-05-16*

---

## Projects

| Project | Artifact | Port | Run |
|---------|----------|------|-----|
| Desktop app (JavaFX + Spring Boot) | `Landmark` | 8090 (Tomcat, FA viewer) | `mvn javafx:run` |
| Reports server (Spring Boot web) | `landmark-reports` | 8091 | `mvn spring-boot:run` |

Both projects share the same `lmextract` database schema.  
Package root: `com.example.fixedassets` (historical naming — covers all modules).

---

## Architecture — Desktop App

### Startup
`FixedAssetsApplication extends Application` → JVM entry (JavaFX requirement).  
`SpringConfig @SpringBootApplication` → separate class (JavaFX/Spring classpath conflict).  
Embedded Tomcat starts on port 8090; serves the FA Asset Register HTML viewer at `/fa/asset-register`.

### Layer rules
```
Controller (ui/ or payroll/ui/)   — JavaFX only. ZERO jdbc.* calls.
Service    (service/ or payroll/service/) — All JDBC. @Transactional on writes.
Model      (model/ or payroll/model/)     — Plain Java types. No JavaFX, no SQL types.
```

No `JdbcTemplate` field on any controller. The Spring IoC container wires everything — never `new` a service.

### Screen pattern (all modules)
Every screen controller exposes `buildScene(Stage stage)` returning a `Scene`.  
`MainMenuController` opens screens in new `Stage` instances via `open*()` methods.  
Background DB work: `ExecutorService` (single thread, daemon), results via `Platform.runLater()`.

### Writing a new CRUD screen — checklist
1. Model: `payroll/model/FooRecord.java` — plain fields, no SQL types, no JavaFX
2. Service: `payroll/service/FooService.java` — `@Service`, all JDBC here
3. Controller: `payroll/ui/FooController.java` — `@Component`, zero jdbc
4. Wire into `PayrollMenuController` — change `false, null` → `true, () -> openFoo(stage)`
5. Add `MenuEntry` to `MainMenuController.buildEntries()` if it needs a top-level menu spot

### Money and dates
- All monetary/rate values: `BigDecimal` (never `double` or `float`)
- All hours in DB: **stored as minutes** (pastaff, paehist, paawjob, etc.) — divide by 60 for display
- All dates: `LocalDate` in Java; `java.sql.Date.valueOf(localDate)` for JDBC params
- Sentinel date for NOT NULL date columns with no value: `1899-12-31` (COBOL date-zero)
- Date guard before arithmetic: `LandmarkDateUtils.isValidLandmarkDate(ld)` (year > 1900)
- Corrupt date guard: `7431-02-14` exists in production data — always guard before display

---

## AppSession

Singleton `@Component` — the Java equivalent of COBOL GLPASS.  
Populated by `MainMenuController` (company/year selection) and `LoginController` (user/terminal).  
Injected into every controller and service that needs session context.

### Key fields

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `companyNo` | int | MENU23 dialog | Always in WHERE clauses |
| `yearNo` | int | MENU23 dialog | 4-digit e.g. 2025 |
| `yrNo` | int | derived | 2-digit e.g. 25 |
| `yrStartDate` | LocalDate | GLDATES | Set by SessionService |
| `yrEndDate` | LocalDate | GLDATES | Set by SessionService |
| `userId` | String | LoginController | Set on successful login |
| `terminalNo` | int | MEPASS | Allocated by UserService |
| `batchControlFlag` | String | CPCOYCO | Y=batch control on |
| `faTaxYrEndMth` | int | CPCOYCO | Month 1-12, default 6 |
| `selectedPayrunNo` | int | payroll screens | 0 = no payrun selected |
| `payrollFilesDir` | String | CPCOYCO / application.properties | ABA output directory |
| `paInstalFlag` | String | CPCOYCO | blank/Y = payroll active |

### Payroll helpers
```java
appSession.hasPayrun()      // selectedPayrunNo > 0
appSession.clearPayrun()    // reset payrun state
appSession.isPayrollInstalled()  // !N
```

---

## Services — what exists

### Auth / session
| Service | Responsibility | JDBC calls |
|---------|---------------|------------|
| `UserService` | meusers CRUD: find, lock, last-access, MEPASS terminal, temp password | 9 |
| `PasswordService` | BCrypt verify + plain-text migration. **No JDBC.** | 0 |
| `SessionService` | CPCOYCO/GLDATES session data + year discovery | 5 |

`PasswordService.verifyAndMigrate(stored, plaintext)` returns `null` (wrong), `""` (correct+already BCrypt), or the new hash to write (correct+needs migration). The caller writes back via `UserService.writePasswordHash()`.

### Fixed Assets
| Service | Responsibility |
|---------|---------------|
| `AcquisitionService` | All 35 JDBC for FAAQ01: batch ops, FAASSET insert/update, FATRXIN, FATRANS post |
| `AssetMaintenanceService` | FAAS01 asset CRUD |
| `AssetRegisterService` | FATL10 asset register query |
| `AcquiredRetiredService` | FATL03 |
| `TransactionListService` | FATL02 |
| `DepreciationProjectionService` | FATL12 |
| `DepreciationCalculator` | Pure calculation — no JDBC |

### Payroll
| Service | Responsibility |
|---------|---------------|
| `PayCodeService` | pacodes CRUD (PACD01) — writes pacdchg audit on rate / amount changes |
| `EmployeeService` | pastaff CRUD (PAEM01) — writes paemaud JSON before/after on insert/update/terminate |
| `EmployeePayService` | paempay (bank splits, S2) |
| `PayGroupService` | pagroup CRUD (PAPG01) |
| `DepartmentService` | padepts (drill from PAPG01) |
| `AwardService` / `AwardJobClassService` / `AwardWcompService` | paawhed / paawjob / paawwcp (PAAW01) — writes paawchg |
| `TaxScaleService` | pataxfl (PASU04 scale-level config) |
| `TaxBracketService` | tax_brackets — Java-managed, loaded annually from ATO Excel via `TaxBracketLoader`. `findApplicableBracket` + `findEffectiveFromOnOrBefore` |
| `FundService` | pafunds read-only (no maintenance screen yet) |
| `MvrService` | Member Verification Request (super fund) |
| `MasterFileAuditService` | One method per audit table — `auditEmployee`, `auditFund`, `auditPayCodeRateChange`, `auditAwardChange`, `auditPaeCode`. Called from every write service |
| `BatchAuditService` | `pa_audit` batch-level rows for Wave 2 mass-update programs |
| **Wave 2 batch** | `SetSuperPercentageService` / `UpdateAwardRateChangesService` / `GlobalEmployeeAwardUpdateService` / `ChangeEmployeePayRatesService` / `DuplicateTimesheetsService` / `LeaveAccrualReversalService` / `TimesheetSplitsService` — all use `BatchPreviewDialog<T>` + `BatchProgressDialog` |
| **Wave 3 services** | `PaygTaxCalculator`, `PayrunService` (parunhd), `PayrunGroupService` (parungr), `TimesheetHeaderService` (patimhd), `TimesheetLineService` (patimes), `PaecodeService` (paecode + papcaud), `PayrollCalcService` (PAPP01), `PayrollPostingService` (PAPP28), `PayHistService` (paehist), `AbaFileService` (PABK02), `LeaveAccrualService` (PAPA14) |

---

## COBOL source-of-truth

**Live program source:** `C:\landmark\Cobol\pa2\pacd01*` (and the corresponding `Cobol\pa2\`, `Cobol\fa2\`, `Cobol\gl2\`, etc folders for each module's programs).

**Definitive record layouts (`.fd` / `.ws`):** `C:\landmark\compile\` — the authoritative copy used by the compile pipeline. `Cobol\fl2\` mirrors these byte-for-byte; either path is fine but treat `compile/` as canonical.

**Do NOT use** `C:\landmark\Cobol\hist\` snapshots — they're archived versions whose mtimes can be misleadingly later than the live source but whose field set is older. Specifically, the `pacd01_v020` archive is missing the SuperStream / Fund-Type fields (`apra_smsf_fund_ind`, `superstream_category`) and the deduction "Salary sacrifice" flag.

For screen field analysis:
- `.sd` files give the user-visible labels
- `.lgw` files give the `datafield=` binding (the actual pacodes/pastaff/etc column)
- `.fd` files give the canonical PIC clauses for column sizes/types

### OCCURS reference convention in `_select.sql` files

The Landmark Query engine uses **two different** subscript conventions depending on where the OCCURS clause sits in the FD:

- **Leaf-level OCCURS** (the field itself has `OCCURS n TIMES`, no children) — e.g. `pasumpc-allow-dedn-amt`, `pataxfl-coeff-a`, `pataxfl-wkly-earnings`. Reference as **lowercase `-NN` for every occurrence including 01**: `pasumpc-allow-dedn-amt-01`, `pasumpc-allow-dedn-amt-02`, ..., `pasumpc-allow-dedn-amt-13`.
- **Nested-group OCCURS** (a level-5 group has `OCCURS n TIMES` containing level-7/level-9 single fields) — e.g. `pasumry-normal-pay` inside `pasumry-header-data OCCURS 13`. Reference as **lowercase bare for occurrence 1, UPPERCASE `-NN` for 02..N**: `pasumry-normal-pay`, `pasumry-NORMAL-PAY-02`, ..., `pasumry-NORMAL-PAY-13`.

Use the wrong convention and the engine silently returns fewer columns than asked (you'll see `No value specified for parameter N` from JDBC when the framework tries to bind). The dictionary's `LEVEL_NO` + `OCCURS_TIMES` columns tell you which case you're in: if the OCCURS is on the same row as a leaf-PIC, use the leaf convention.

### Engine caps on OCCURS extraction — known limits

The Landmark Query engine has **hard limits** on how much OCCURS data it returns in a single query:

1. **Per-OCCURS-array cap of ~13-19 elements**, depending on which subscript convention you use. `pasumpc` (OCCURS 13) works fully; `pataxfl` (OCCURS 20) bails out partway: lowercase `-NN` gets 13 elements, mixed convention gets 19.
2. **Won't transition between adjacent OCCURS field-groups in a single query** — e.g. `pataxfl-wkly-earnings` (OCCURS 20) followed by `pataxfl-coeff-a` (also OCCURS 20). The engine stops mid-first-array and never reaches the second, regardless of subscript convention.
3. **Approximate total-column cap of 250–330** for deep-nested OCCURS (`pasumry` style) before it bails — non-deterministic; varies with select content.

**Practical rule:** if a table's OCCURS arrays push past these limits, omit the OCCURS columns from the extract schema and source that data from elsewhere (manual seed for static reference data, or a different ETL path). `pataxfl` does this — the 78 ATO tax-coefficient columns are deliberately omitted; the table holds only `scale_no` + `desc_1/_2` + singletons + audit. The coefficients are static ATO data that doesn't change per company anyway.

---

## DB Tables — Confirmed Column Names

**Rule: never guess column names from COBOL source. Check this section first.**

### System
| Table | Purpose | Key columns |
|-------|---------|-------------|
| MEUSERS | User accounts | user_id, name1, password, user_status (blank/H/L/T), supervisor_flag, passwd_expiry_date, last_access_date, email_address |
| MEPASS | Terminal sessions | terminal_no PK (1–998), user_id, company_no, terminal_inactive, log_date, log_hr, log_min |
| CPCOYCO | Company master | company_no, name1, name_2, fa_last_batch_no, fa_tax_yr_end_mth, fa_batch_control_flag, payroll_files_dir (blank→use application.properties), pa_instal_flag (blank→treat as Y) |
| GLDATES | Fiscal years | **TWO keys**: year_no (calendar e.g. 2024) and yr_no (sequence PK). yr_start_date, yr_end_date are proper DATE columns |

### GL
| Our code used | Actual table | Key column differences |
|---|---|---|
| GLTRANS | **gltrx** | acct_no→acct_main_no, debit_amt→dr_amt, credit_amt→cr_amt, batch_no→jnl_no, trx_date→jnl_date |
| GLACCNT | **glchart** | acct_no→acct_main_no, acct_desc→desc1; use pl_bs_ind (P/B) + dr_cr_ind (D/C) not acct_type |
| — | **glbal** | Pre-aggregated balances bal_01..bal_13 per period per account |
| — | **glgnhed** | Journal headers. yr_no (not year_no) joins gldates.yr_no |
| — | **glgnlin** | Journal lines |

`glchart.pl_bs_ind`: P=P&L, B=Balance Sheet  
`glchart.dr_cr_ind`: C=credit-normal (income/liabilities), D=debit-normal (expenses/assets)  
`gltrx` has **no period_no** — derive from jnl_date vs gldates.period_start_NN/period_end_NN

### FA (all column names confirmed correct)
`faasset`, `facodgr`, `facodlo`, `facoddt`, `facodsg`, `facodss`, `facoddn`, `facodin` — all columns correct as implemented.  
`CMBATCH`: system_id='FA', batch_status: blank/U=open, C=completed  
`FATRXIN`: trans_trx_type='AQ' for acquisitions  
`FATRANS`: date_asset_no = YYYYMMDD + asset_no (sort key)

### PY (Payroll)
| Our code used | Actual table | Key column differences |
|---|---|---|
| PYEMPLO | **pastaff** | emp_no→employee_no, dept_code→dept, emp_status→employee_status, emp_email→email_address, pay_rate→std_rate_per_hr, start_date→date_started |
| PYPAYRUN | **parunhd** | NO pay amount columns. yr_no (not year_no). payrun_date, paymt_date, no_of_employees_paid |
| — | **paehist** | Pay history lines. PK: (company_no, employee_no, payrun_date, pay_type, pay_code, payrun_no, line_no). ext_amt = money. pay_type: 1=income 2=allowance 3=deduction 4=tax 5=super |
| — | **paecode** | Employee standing pay lines (template for payrun) |
| — | **pacodes** | Paycode master. PK: (company_no, pay_code). type INT (**1-24** — sourced from PACD01.cbl WS-PAYCODE-TYPE-LIT-TABLE; earlier "1-5" was wrong). 122 columns total, mostly NOT NULL. type-driven field groups: 1-3 pay\_\*, 4-9 leave\_\*, 10-14 allow\_\*, 15-16 dedn\_\*, 17/20 super\_\*+fund\_\*+bank\_\*, 18 tax\_\*, 19 term_e_flag, 21 contrib\_\*, 22-24 header-only. super_flag locked 'N' for types 17, 20. wcomp_flag locked 'N' for types > 14 except 19/20/21. |
| — | **paempay** | Employee bank accounts |
| — | **pacosts** | Period cost summary by period_end_date/dept/pay_type — use for charts/KPIs |
| — | **paytd** | YTD totals per employee/pay_code. Uses year_no (calendar year, NOT yr_no) |
| — | **parunhd** | Pay run header. payrun_no PK, payrun_status O/P/R, yr_no FK→gldates.yr_no |
| — | **paawhed** | Award header. award_code |
| — | **paawjob** | Award job classifications. PK: (company_no, award_code, job_class_code). 80+ columns for leave/super |
| — | **paawwcp** | Award WC premiums |
| — | **paawchg** | Award change history |

**Critical payroll gotchas:**
- All "hours" fields in PA tables are stored in **minutes** (pastaff.al_hrs_accrued, paehist.hrs, paawjob.std_hrs). Divide by 60 for display.
- **`pagroup.paid_thru_to_X` is YYMMDD-packed INT, NOT a DATE**. e.g. `260531` = 2026-05-31. Year pivot: `yy < 40 → 20yy`, else `19yy`. Same convention as COBOL day-numbers elsewhere. Helper: `ymmddToDate(int)` in `TimesheetEntryController`.
- **parungr pay-thru dates** must derive from `pagroup.paid_thru_to_X + period` (per COBOL `SET-PAY-THRU-DATES`, patm01.pl:1383) — NEVER hard-default to the payrun's end date. Per frequency: leave blank when `payrun_active_X='Y'` (already in another payrun) OR `paid_thru_to_X=0` (no history); else `paid_thru_to_X + N` where N = 1mth / 28d / 15d / 14d / 7d for mth / 4wk / bimth / fort / week.
- **COBOL CREATE-PAYRUN** on the P2 toolbar (patm01.pl:1027) is misleadingly named. It does NOT insert a new parunhd — it validates the current payrun + transitions to the timesheet builder (PATM02 ≈ our P3). The "create a new parunhd" action is on P1 Add.
- **`pasumry` is permanently dropped from the extract pipeline** — do NOT load it, query it, build screens against it, or wire it into the Java app. The Landmark Query engine misreads its `S9(8)V99 COMP-3` level-9 fields inside the OCCURS 13 group at level 7, fabricating ~$500M-scale garbage values that don't exist in the source `.dat` file (audit fields extract fine; periodic amounts/mins don't). Confirmed 2026-05-11 by hex-dumping `pasumry060.dat` and comparing against the `patl07` PDF report — the file content is mostly zero bytes for periodic data, yet SQL showed `544,947,455.22`. Removed from `landmark_extract/list/module/pafiles.txt`. **For period totals**, recompute on demand from `paehist` with `GROUP BY pay_type, payrun_date` — that's the source of truth for what the PDF reports show.
- Award FK naming: `paawhed`/`paawjob`/`paawwcp` use `award_code`/`job_class_code`; `pastaff`/`paehist`/`paecode` use `award`/`job_class`. Join carefully.
- `parunhd.yr_no` (not year_no) FK → `gldates.yr_no`
- `paytd.year_no` = calendar year (not sequence)
- `desc1` not `desc` across all tables (avoids SQL reserved word)

### AR (Accounts Receivable)
| Table | Purpose | Key columns |
|-------|---------|-------------|
| arcusts | Customer master | cust_no VARCHAR(10), name1, cust_status='A', curr_period..curr_period_minus_4 (ageing) |
| artrans | Transactions | cust_no, doc_date, doc_type='I'=invoice, amt, amt_paid, trx_status O=open P=paid, due_date |
| arsales | Sales by period | sales_01..13 per year_no |
| arsumry | Period summary | period_end_date, open_bal, ar_inv_value, ar_recpt_value |

### AP (Accounts Payable)
| Table | Purpose | Key columns |
|-------|---------|-------------|
| apsupps | Supplier master | supplier_no VARCHAR(10), name_1, acct_status='A', curr_period..curr_period_minus_4 |
| aptrans | Transactions | supplier_no, doc_date, doc_type='I'=invoice, amt, amt_paid, trx_status O/P, due_date |
| appurch | Purchases by period | purch_01..13 per year_no |
| apsumry | Period summary | period_end_date, sub_ledger, open_bal, ap_inv_value, ap_chq_value |

### CM (Cash Management)
| Table | Purpose | Key columns |
|-------|---------|-------------|
| cmbanks | Bank accounts | bank_code VARCHAR(2), name1, gl_acct_main_no (links to glchart), inactive_flag |
| cmtrans | Cashbook transactions | bank_code, doc_date, amt, cashbook_recpt_paymt R=receipt P=payment, trx_status O=outstanding R=reconciled |
| cmbatch | Batch header | batch_no, yr_no, bank_code, batch_status |

### SM / PO
| Table | Purpose | Key columns |
|-------|---------|-------------|
| smsthed | Stock master | stock_code VARCHAR(25), desc_1, stock_unit, item_type |
| smstloc | Stock by location | stock_code, loc_no, qty_on_hand, value_on_hand, qty_on_order, qty_allocated, min_qty_level, item_status='A' |
| popohed | PO header | po_no INT, supplier_no, po_date, po_status O/C/X, po_value, recvd_value, inv_value |
| popolin | PO lines | po_no, line_no, stock_code, orig_qty_ordered, orig_unit_cost |

---

## Auth flow (desktop)

```
LoginController.doLogin()
  → UserService.findUser()           — load meusers row
  → status check (H/L/T)
  → PasswordService.verifyAndMigrate() — BCrypt or plain-text
  → expiry check
  → UserService.writePasswordHash()  — migrate if plain-text (non-fatal)
  → UserService.recordSuccessfulLogin() — last_access + MEPASS terminal
  → AppSession.setUserId / setUserName / setSupervisorFlag
  → MainMenuController.buildScene()
```

Password migration: plain-text passwords are upgraded to BCrypt (strength 12) on first successful login. The old value is never re-written if BCrypt verification fails — avoids corrupt migration.

MEPASS concurrent user count: `terminal_inactive='N'` rows vs `license.maxUsers`. Staleness: rows older than today or stale hour need a cleanup sweep — `clearStaleTerminals()` in `UserService` (to be added before license enforcement ships).

---

## FA Asset Register viewer

Embedded Tomcat servlet at `http://localhost:8090/fa/asset-register`.  
Implemented in `report/AssetRegisterViewerService.java` — pure JdbcTemplate + HTML servlet. No BIRT, no template engine.  
Features: column show/hide, sort, live search, CSV export, print-to-PDF via browser.

---

## BIRT — removed 2026-05-09

BIRT engine and all related classes (`BirtReportService`, `BirtOutputFormat`, `BirtViewerService`, `AssetListingScriptedDataAdapter`, `BirtReportDialog`, `fatl10_asset_register.rptdesign`) have been deleted. Do not re-add. The FA Register is served by `AssetRegisterViewerService`.

---

## Reports server (landmark-reports, port 8091)

Auth: `/login` → LandmarkAuthProvider (MEUSERS+MEPASS) → `/select-company` → `/select-year` → `/dashboard`  
`ReportSession` is `@SessionScope` — always check `session.hasCompany()` before querying.

URL patterns:
```
GET /reports/{module}                               → report list
GET /reports/{module}/{name}/params?format=         → parameter form
GET /reports/{module}/{name}?format=pdf|excel|html  → run report
GET /api/{module}/{endpoint}                        → JSON for ECharts
GET /{module}/dashboard                             → module dashboard
```

Standard params always available: `COMPANY_NO`, `YEAR_NO`, `COMPANY_NAME`, `YEAR_DESC`, `YR_START_DATE`, `YR_END_DATE`, `USER_ID`

Reports implemented: GL: trial-balance, profit-loss, balance-sheet, general-journal, account-transactions | FA: asset-register, depreciation, acquisitions | PY: payroll-summary, employee-list

---

## Adding a program — quick reference

### New CRUD screen (payroll pattern)
```
payroll/model/FooRecord.java          plain fields only
payroll/service/FooService.java       @Service, all jdbc here
payroll/ui/FooController.java         @Component, zero jdbc, buildScene(Stage)
PayrollMenuController                 change false,null → true, () -> openFoo(stage)
```

### New FA report
```
service/FooService.java               query returns List<FooRow>
ui/FooScreenController.java           buildScene() + background exec pattern
MainMenuController.buildEntries()     new MenuEntry("FA","FATL99","Title",...,this::openFoo)
MainMenuController.openFoo()          new Stage + fooScreen.buildScene(s) + s.show()
```

### DB rules for every query
- Always include `company_no=?` in WHERE
- Use `appSession.getCompanyNo()` — never hardcode
- `parunhd.yr_no` (not year_no) for payroll year joins
- `paytd.year_no` = calendar year (not sequence yr_no)
- Hours from PA tables ÷ 60 before display

---

## Audit framework

Two flavours, both wired through `MasterFileAuditService`:

**Heavyweight (JSON before/after snapshots)** — `paemaud`, `pafuaud`. One row per
change, captured in the same `@Transactional` as the data write. Snapshots run
through Jackson with TFN masking (CLAUDE.md rule — `***-***-NNN`).

**Per-row tracker** — `pacdchg` (pay-code rate change), `paawchg` (award change),
`papcaud` (per-employee pay code change). Lightweight UPSERT or single-row insert
with primary-key + before/after values.

**Activated 2026-05-16**: `papcaud` writes via `PaecodeService` (Add/Edit/Delete
all flow through `MasterFileAuditService.auditPaeCode` with `A` / `M` / `B`-before
/ `D`-snapshot maint types — COBOL convention).

**Still deferred**: `pafuaud` writes (no pafunds CRUD yet), and `pastaff`'s own
per-row `audit_user_id/date/time*` columns on update (pre-existing gap).

Wave 2 batch programs also write **`pa_audit`** (batch metadata: program code,
description, rows_affected, status R/C/F/P) alongside per-row writes.

---

## Persistence — user preferences

`FavouritesStore` (`~/.fixedassets/favourites.properties`) and `LastSessionStore`
(`~/.fixedassets/session.properties`) — same directory, plain properties files.

`LastSessionStore` persists `lastCompanyNo` + `lastYearNo` from the MENU23 picker
so logins resume on the last-used company/year (falls back to first-company +
latest-year if the company is gone).

---

## Wave 3 pay-cycle reference

End-to-end runnable as of 2026-05-16:

```
PATM01  Create payrun + attach paygroups (parungr) + add timesheets (patimhd / patimes)
   │       Options → Select drives auto-attach with SET-PAY-THRU-DATES dates.
   │       paecode CRUD lives inside PATM01 — Standing Lines button on P3.
   ▼
PAPP01  Calculate Tax + Totals — PayrollCalcService.recalcPayrun
   │       Buckets patimes by pay_type into patimhd totals, runs PaygTaxCalculator
   │       for PAYG withholding (NAT_1004 / NAT_3539 by STSL flag), flips
   │       calcs_completed_flag=Y.
   ▼
PAPP28  Post — PayrollPostingService.postPayrun
   │       Inserts paehist (one row per patimes line) + synthetic tax line at
   │       pay_type=22. Flips parunhd.status=P, patimhd.status=P. Un-post reverses.
   ▼
PABK02  ABA File — AbaFileService.generate
   │       Net pay from paehist, paempay split rules (A→P→B), APCA fixed-width
   │       120-char file to appSession.payrollFilesDir.
   ▼
PAPA14  Leave accrual — LeaveAccrualService.accruePayrun
           AL += hoursWorked × 4/52, SL += hoursWorked × 2/52 (MVP factors).
```

---
*Last updated: 2026-05-16 — Wave 3 end-to-end pay cycle, papcaud audit hook activated, LastSessionStore persistence*
