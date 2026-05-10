# Landmark — Developer Conventions
*Updated: 2026-05-09*

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
| Service | Responsibility | JDBC calls |
|---------|---------------|------------|
| `PayCodeService` | pacodes CRUD (PACD01) | 11 |
| *(PAEM01 next)* | pastaff CRUD | — |

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
- `pasumry` is **unusable** — period columns `_01.._13` are VARCHAR(144) containing raw COBOL COMP-3 binary. Use `pacosts` (DECIMAL) or `paytd` instead.
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
*Last updated: 2026-05-09 — payroll scaffold (PACD01), service extractions, BIRT removal, auth refactor*
