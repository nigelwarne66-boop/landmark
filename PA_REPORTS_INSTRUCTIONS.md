# Claude Code Instructions â€” Payroll Reports (PA Module)

## Context and conventions

Follow the JavaFX Reports Hub pattern exactly â€” NOT a web/Spring-MVC approach.
Use `PayReportDataService` (new service, `service/pa/`) + Jasper jrxml pair per report
+ `ui/reports/Pa<Name>Controller.java` (`@Component @Scope("prototype")`).
Register each in `ReportsHubController.buildModuleRegistry()` under the `pa` module.

All monetary/rate values: `BigDecimal`. Hours stored as **minutes** in DB â€” divide by 60 for display.
TFN is **never printed in full** â€” `Employee.maskTfn(String)` where needed (PATL26 shows TFN).

---

## DB tables â€” confirmed column names and data state

### `paehist` â€” posted payroll history lines (PK: company_no, employee_no, payrun_date, pay_type, pay_code, payrun_no, line_no)
Core join table for detail/history reports. Key columns:
- `paygroup`, `dept` â€” for filtering/grouping
- `pay_type` (INT), `pay_code` (VARCHAR 6) â€” line classification
- `ext_amt` â€” **the amount field**: `gross_amt` and `tax_amt` are 0 in current data; all amounts live in `ext_amt`
- `hrs` â€” **minutes** (divide by 60)
- `gl_acct_no_main`, `gl_acct_no_sub` â€” GL cost centre (only payroll-tax clearing code 991 is populated in company 60; GL distribution reports will be sparse until better costing is done)
- `payroll_taxable_flag` â€” for payroll tax filtering
- `income_taxable_flag` â€” for PAYG filtering
- `payrun_date` â€” the payrun's date (join to `parunhd` on company_no + payrun_no for period dates)

**Pay type mapping in paehist (as written by Java PostingService):**

| pay_type | Meaning |
|----------|---------|
| 1 | Ordinary/normal pay |
| 2 | Overtime |
| 3 | Other pay |
| 4 | LSL |
| 5 | Annual Leave |
| 7 | Sick Leave |
| 8 | RDO/Other leave |
| 18 | PAYG income tax withheld |
| 19 | Before-tax deduction |
| 20 | After-tax deduction / employer super guarantee |
| 21 | Salary sacrifice / employee super contribution |
| 22 | Payroll tax (state) |

Company 60 has 50 rows across 9 payruns (2024-06-07 to 2026-05-01), 7 employees.

### `paytd` â€” year-to-date accumulators (PK: company_no, year_no, employee_no, pay_type, pay_code)
Columns: `amt` (BigDecimal), `hrs` (minutes), `income_taxable_flag`.
Company 60 has 11 rows, year_no=2026 only. **YTD reports will only show current year unless historical payruns are reprocessed.**
Join: `pastaff` on company_no + employee_no; `pacodes` on company_no + pay_code.

### `pacodes` â€” pay code master (PK: company_no, pay_code)
Key columns: `type` (INT â€” matches pay_type in paehist), `desc1`, `payslip_desc`, `abbrev_desc`.
Super fields: `fund_name`, `fund_abn`, `fund_usi`, `fund_esa`, `bank_bsb`, `bank_acct_no`,
`super_employee_perc`, `super_before_after_tax`, `dedn_used_for_super`, `contrib_used_for_super`.
Super guarantee flag: `super_guarantee_flag`.
Company 60 codes: PAY, BONUS, PAYPH (type=1), LSL (type=4), ETPE (type=19),
FBTYTD/SACRIF/SACAUS/SACHOS/SACRES (type=21), PAYTAX (type=22), ONCOST (type=24).

### `pafunds` â€” super fund master (PK: company_no, apra_smsf_fund_ind, fund_id, smsf_abn)
Key columns: `fund_name`, `fund_abn`, `fund_usi`, `product_name`, `bank_bsb`, `bank_acct_no`,
`apra_smsf_fund_ind` ('A'=APRA, 'S'=SMSF).
Company 60 has 4 funds: AustralianSuper, HOSTPLUS, Rest, Warne Family SF (SMSF).

### `pacosts` â€” period cost distribution (PK: company_no, period_end_date, paygroup, dept, cost_type, pay_type, pay_code)
Columns: `amt` (BigDecimal), `hrs` (minutes).
Company 60 has 242 rows dating from 2007. Selection must match a period-end date.
Join: `pagroup` on paygroup for description; `padepts` on dept for description.

### `pastaff` â€” employee master
Key columns: `employee_no`, `surname`, `first_name`, `paygroup`, `dept`, `employee_status`,
`pay_freq`, `annual_salary`, `tax_file_no` (mask!), `date_started`, `date_terminated`.

### `parunhd` â€” payrun header (PK: company_no, payrun_no)
Key columns: `payrun_date`, `start_date`, `end_date`, `yr_no`, `payrun_status`
('C'=Created, 'P'=Posted, 'F'=Completed, 'D'=Deleted), `payrun_type`.
Company 60: 20 payruns; `start_date`/`end_date` are 1899-12-30 sentinel for older payruns.

### `pagroup` â€” paygroup master
Key columns: `paygroup` (PK component), `description`.

### `padepts` â€” department master
Key columns: `dept` (PK component), `description`.

### NOT VIABLE â€” missing data
- **`papatax`** â€” not in extract; PATL12 (Payroll Tax Detail) deferred.

---

## Combined reports (single screen with sort option)

| Reports | Combined Title | Sort Options |
|---------|---------------|-------------|
| PATL17 + PATL30 + PATL55 | Employee History Summary | Employee / Paygroup / Payrun |
| PATL05 + PATL09 | Deductions & Superannuation | Pay Code / Fund Name |
| PATL32 + PATL06 | GL Distribution | GL Account / Employee |

---

## Report inventory

Full SQL specs removed 2026-05-31 â€” derivable from the table descriptions above and CONVENTIONS.md. See **Implementation learnings** section for all non-obvious patterns.

| Program(s) | Title | Source tables | PDF group key | Key notes |
|---|---|---|---|---|
| PATL02 | Employee YTD Payments | paytd, pastaff, pacodes | employee | Amounts in `amt`; paytd only has year_no=2026 for co.60 |
| PATL14 | Employee History Detail | paehist, pastaff, pacodes, parunhd | employee | Amounts in `ext_amt`; `start_date`/`end_date` are 1899 sentinel for most payruns |
| PATL17/30/55 | Employee History Summary | paehist, pastaff, pacodes | employee | Aggregated; 3 sort options: employee / paygroup / payrun |
| PATL05/09 | Deductions & Superannuation | paytd, pastaff, pacodes | pay_code | Filter: `pay_type IN (19,20,21)`; 2 sort options: code / fund name |
| PATL16 | Department Expenses | pacosts, pagroup, padepts, pacodes | paygroupâ†’dept | Selection date must match an existing `period_end_date` in pacosts |
| PATL07 | Period Summary | paehist, parunhd | paygroup | Each row = one payrun aggregate (normal_pay, super, tax, total) |
| PATL32/06 | GL Distribution | paehist, glchart | GL account | Only acct 991 (payroll-tax clearing) is coded in co.60 |
| PATL60 | Payrun GL Detail | paehist, pastaff, pacodes, glchart | employee | Single payrun only; status F or P required |
| PATL28 | Timesheet History | paehist, pastaff, parunhd, pacodes | paygroupâ†’employee | Includes `hrs`, `qty`, `rate_perc` columns |
| PATL40 | Super/Deductions Status | paehist, pastaff, pacodes | pay_code | Filter: `pay_type IN (19,20,21)`; includes `paid_flag` |
| PASP10 | Superannuation by Fund | paytd, pastaff, pacodes | fund_name | Filter: `pay_type IN (20,21)`; fund from pacodes.fund_name |
| PATL26 | Extended Superannuation | paehist, pastaff, pacodes | pay_codeâ†’employee | TFN masked via `Employee.maskTfn()`; includes `super_before_after_tax` |

---

## Deferred â€” missing data

| Program | Reason |
|---------|--------|
| PATL12 â€” Payroll Tax Detail | `papatax` not in extract pipeline |

---

## Common implementation notes

### Amount convention
All amounts in `paehist` are in `ext_amt`. The `gross_amt` and `tax_amt` columns are 0 in current data.
In `paytd` the amount is in `amt`.

### Hours convention
`hrs` in both `paehist` and `paytd` = **minutes**. Display: `hrs / 60.0` for decimal hours,
or format as HH:MM. Offer both options where COBOL shows `DECIMAL/HRS MIN` switch.

### Tax year (yearNo)
`paytd.year_no` is the 2-digit year (26 = FY2025-26). Populate the year combo from
`SELECT DISTINCT year_no FROM paytd WHERE company_no=?`. Display as "FY 20XX".

### Period-end date validation
For reports that require a period-end date (PATL16, PATL32): validate against `gldates` period-end columns.

### Payrun access guard
Payroll reports are gated by `MEUSERS.print_pa_from_pass = 'Y'`. Already handled
in the Reporting Hub payroll module visibility check.

### jrxml styling
Use same navy/blue palette as GL reports:
- Column header: `#1e2d45` dark navy, white text
- Group headers: `#506680` steel blue, white text  
- Group footers / subtotals: `#1e2d45` dark navy, white text
- Excel: `ignore.page.margins=true`, margins=0

### Excel extras rule
Wherever PDF shows only a code, Excel adds the description column adjacent.

---

## Implementation learnings (2026-05-30 build)

These were discovered during the initial build and should not be repeated.

### Resource paths â€” module ID is `"py"`, not `"pa"`
All payroll report resources must live under `py/` (the hub module ID), not `pa/`:
- FXML: `fxml/reports/py/<name>.fxml`
- jrxml: `reports/py/<name>.jrxml` and `<name>-excel.jrxml`
- Controller jrxml paths: `"py/<name>"` and `"py/<name>-excel"`

### Hub registration â€” never call `openSelectionScreen()` directly
In `buildModuleRegistry()`, every report card already auto-wires click â†’ `openSelectionScreen()`.
Set `setRunner(fmt -> comingSoon(title))` as a fallback only. Calling `openSelectionScreen()` directly
in `buildModuleRegistry()` fires at startup before a scene is built â†’ NPE on `getScene().getWindow()`.

### `isStretchWithOverflow` â€” apply to ALL textFields in the band
Jasper 6.21 bug: in a band with ANY `<textField isStretchWithOverflow="true">`, all subsequent
textFields WITHOUT that flag are silently skipped. Every `<textField>` in every detail/group band must
carry `isStretchWithOverflow="true"`. Does not affect `<staticText>`.

### `mapDataSource()` â€” do not use `JRBeanCollectionDataSource`
`JRBeanCollectionDataSource` uses Apache BeanUtils which silently returns null for BigDecimal fields
on `Map<String,Object>` rows in this class-loader context (String fields work fine). Use the direct
`mapDataSource()` anonymous helper in each controller instead â€” it calls `map.get(field.getName())`
directly. Copy the helper from `PaEmployeeYtdPaymentsController` to every new controller.

### jOOQ row access â€” use string-based, not Field-reference
`row.get(aliasedTable.field(SCHEMA.COL))` creates a new Field object at call time; when that object
doesn't match the selected field by identity, jOOQ silently returns null for the last-positioned
fields in the SELECT. **Always use** `row.get("column_name", Type.class)` in PA service methods.
`PayReportDataService` is fully jOOQ-based (DSLContext, not JdbcTemplate).

### Jasper variable ordering â€” variables before groups
`<variable>` elements must appear BEFORE `<group>` elements in the jrxml (XSD order:
parameter â†’ field â†’ variable â†’ group). Variables declared after their group cause a
SAXParseException "invalid content" at the variable element. A PowerShell script
(`fix-variable-ordering.ps1` style) can batch-fix this across multiple files.

### Pre-compute group totals in service, don't rely on Jasper Sum variables
`calculation="Sum"` group variables are unreliable with `JRBeanCollectionDataSource` + Map rows.
Pattern that works (GLRP40 style): service emits header/detail/total rows with a `rowKind` field
(`"header"`, `"detail"`, `"total"`); the total row carries the pre-computed sum as `$F{payAmt}`;
`printWhenExpression` controls which textField variant renders per row kind. All textField variants
must have `isStretchWithOverflow="true"` (see above).

### Conditional style forecolor bleeds between records
If a conditional style sets `forecolor="#ffffff"` for one row kind but provides no explicit
forecolor for the "detail" row kind, the PDF renderer keeps white as the active colour â†’
amounts appear blank on white background. Add an explicit conditional for EVERY row kind
(including the default data rows) setting `forecolor="#000000"`.

### `JasperReportService` cache â€” timestamp-based since 2026-05-30
`compile()` now compares `resource.lastModified()` vs the cached `.jasper` mtime. Any edit to a
jrxml in `target/classes` will trigger automatic recompilation on next use. The previous version
used any existing `.jasper` unconditionally â€” jrxml edits had no effect while the app was running.

### `paytd.year_no` is 4-digit (corrected from instructions above)
The paytd table uses 4-digit years (2026 = FY 2025-26), not 2-digit as initially documented.
