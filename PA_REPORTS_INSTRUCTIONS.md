# Claude Code Instructions — Payroll Reports (PA Module)

## Context and conventions

Follow the JavaFX Reports Hub pattern exactly — NOT a web/Spring-MVC approach.
Use `PayReportDataService` (new service, `service/pa/`) + Jasper jrxml pair per report
+ `ui/reports/Pa<Name>Controller.java` (`@Component @Scope("prototype")`).
Register each in `ReportsHubController.buildModuleRegistry()` under the `pa` module.

All monetary/rate values: `BigDecimal`. Hours stored as **minutes** in DB — divide by 60 for display.
TFN is **never printed in full** — `Employee.maskTfn(String)` where needed (PATL26 shows TFN).

---

## DB tables — confirmed column names and data state

### `paehist` — posted payroll history lines (PK: company_no, employee_no, payrun_date, pay_type, pay_code, payrun_no, line_no)
Core join table for detail/history reports. Key columns:
- `paygroup`, `dept` — for filtering/grouping
- `pay_type` (INT), `pay_code` (VARCHAR 6) — line classification
- `ext_amt` — **the amount field**: `gross_amt` and `tax_amt` are 0 in current data; all amounts live in `ext_amt`
- `hrs` — **minutes** (divide by 60)
- `gl_acct_no_main`, `gl_acct_no_sub` — GL cost centre (only payroll-tax clearing code 991 is populated in company 60; GL distribution reports will be sparse until better costing is done)
- `payroll_taxable_flag` — for payroll tax filtering
- `income_taxable_flag` — for PAYG filtering
- `payrun_date` — the payrun's date (join to `parunhd` on company_no + payrun_no for period dates)

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

### `paytd` — year-to-date accumulators (PK: company_no, year_no, employee_no, pay_type, pay_code)
Columns: `amt` (BigDecimal), `hrs` (minutes), `income_taxable_flag`.
Company 60 has 11 rows, year_no=2026 only. **YTD reports will only show current year unless historical payruns are reprocessed.**
Join: `pastaff` on company_no + employee_no; `pacodes` on company_no + pay_code.

### `pacodes` — pay code master (PK: company_no, pay_code)
Key columns: `type` (INT — matches pay_type in paehist), `desc1`, `payslip_desc`, `abbrev_desc`.
Super fields: `fund_name`, `fund_abn`, `fund_usi`, `fund_esa`, `bank_bsb`, `bank_acct_no`,
`super_employee_perc`, `super_before_after_tax`, `dedn_used_for_super`, `contrib_used_for_super`.
Super guarantee flag: `super_guarantee_flag`.
Company 60 codes: PAY, BONUS, PAYPH (type=1), LSL (type=4), ETPE (type=19),
FBTYTD/SACRIF/SACAUS/SACHOS/SACRES (type=21), PAYTAX (type=22), ONCOST (type=24).

### `pafunds` — super fund master (PK: company_no, apra_smsf_fund_ind, fund_id, smsf_abn)
Key columns: `fund_name`, `fund_abn`, `fund_usi`, `product_name`, `bank_bsb`, `bank_acct_no`,
`apra_smsf_fund_ind` ('A'=APRA, 'S'=SMSF).
Company 60 has 4 funds: AustralianSuper, HOSTPLUS, Rest, Warne Family SF (SMSF).

### `pacosts` — period cost distribution (PK: company_no, period_end_date, paygroup, dept, cost_type, pay_type, pay_code)
Columns: `amt` (BigDecimal), `hrs` (minutes).
Company 60 has 242 rows dating from 2007. Selection must match a period-end date.
Join: `pagroup` on paygroup for description; `padepts` on dept for description.

### `pastaff` — employee master
Key columns: `employee_no`, `surname`, `first_name`, `paygroup`, `dept`, `employee_status`,
`pay_freq`, `annual_salary`, `tax_file_no` (mask!), `date_started`, `date_terminated`.

### `parunhd` — payrun header (PK: company_no, payrun_no)
Key columns: `payrun_date`, `start_date`, `end_date`, `yr_no`, `payrun_status`
('C'=Created, 'P'=Posted, 'F'=Completed, 'D'=Deleted), `payrun_type`.
Company 60: 20 payruns; `start_date`/`end_date` are 1899-12-30 sentinel for older payruns.

### `pagroup` — paygroup master
Key columns: `paygroup` (PK component), `description`.

### `padepts` — department master
Key columns: `dept` (PK component), `description`.

### NOT VIABLE — missing data
- **`papatax`** — not in extract; PATL12 (Payroll Tax Detail) deferred.

---

## Combined reports (single screen with sort option)

| Reports | Combined Title | Sort Options |
|---------|---------------|-------------|
| PATL17 + PATL30 + PATL55 | Employee History Summary | Employee / Paygroup / Payrun |
| PATL05 + PATL09 | Deductions & Superannuation | Pay Code / Fund Name |
| PATL32 + PATL06 | GL Distribution | GL Account / Employee |

---

## Reports to implement (11 viable, 2 deferred)

---

### PATL02 — Employee YTD Payments

**Source:** `paytd` + `pastaff` + `pacodes`
**Purpose:** Shows each employee's year-to-date payment amounts by pay code for a chosen tax year.
Excludes pay_types 20–24 (deductions/super/tax) from the main payment section per COBOL logic
— or optionally show all types with clear labelling.

**Selection fields:**

| Field | Type | Label |
|---|---|---|
| `startPaygroup` | text(4) | Starting paygroup |
| `endPaygroup` | text(4) | Ending paygroup |
| `startDept` | text(4) | Starting dept |
| `endDept` | text(4) | Ending dept |
| `startEmployee` | number | Starting employee no |
| `endEmployee` | number | Ending employee no |
| `yearNo` | ComboBox | Tax year (from distinct year_no in paytd) |

**SQL:**
```sql
SELECT t.employee_no, s.surname, s.first_name, s.paygroup, s.dept,
       t.pay_type, t.pay_code, c.desc1,
       t.amt, t.hrs
FROM paytd t
JOIN pastaff s ON s.company_no=t.company_no AND s.employee_no=t.employee_no
LEFT JOIN pacodes c ON c.company_no=t.company_no AND c.pay_code=t.pay_code
WHERE t.company_no=? AND t.year_no=?
  AND s.paygroup BETWEEN ? AND ?
  AND s.dept BETWEEN ? AND ?
  AND t.employee_no BETWEEN ? AND ?
ORDER BY s.surname, s.first_name, t.employee_no, t.pay_type, t.pay_code
```

**PDF layout:** Group by employee. Header: Emp No | Name | Dept | Paygroup.
Per pay code line: Pay Code | Description | Hours (if hrs>0, divide by 60) | Amount.
Employee total. Grand total.

**Excel extras:** Add pay_type, year_no, employee_status.

---

### PATL14 — Employee History Detail

**Source:** `paehist` + `pastaff` + `parunhd`
**Purpose:** Full detail of every posted payroll line for an employee over a date/payrun range.

**Selection fields:**

| Field | Type | Label |
|---|---|---|
| `startEmployee` | number | Starting employee no |
| `endEmployee` | number | Ending employee no |
| `startDate` | DatePicker | Start payrun date |
| `endDate` | DatePicker | End payrun date |
| `startPayrun` | number | Starting payrun no (0=all) |
| `endPayrun` | number | Ending payrun no |

**SQL:**
```sql
SELECT h.employee_no, s.surname, s.first_name, s.paygroup, s.dept,
       h.payrun_no, h.payrun_date, h.pay_type, h.pay_code, c.desc1,
       h.hrs, h.qty, h.rate_perc, h.ext_amt, h.ref
FROM paehist h
JOIN pastaff s ON s.company_no=h.company_no AND s.employee_no=h.employee_no
LEFT JOIN pacodes c ON c.company_no=h.company_no AND c.pay_code=h.pay_code
WHERE h.company_no=?
  AND h.employee_no BETWEEN ? AND ?
  AND h.payrun_date BETWEEN ? AND ?
  AND h.payrun_no BETWEEN ? AND ?
ORDER BY h.employee_no, h.payrun_date, h.payrun_no, h.pay_type, h.pay_code, h.line_no
```

**PDF layout:** Group by employee, then payrun. Header per payrun: Payrun No | Date.
Per line: Pay Code | Description | Hours | Qty | Rate | Amount.
Payrun subtotal. Employee total. Grand total.

**Excel extras:** Add paygroup, dept, ref, income_taxable_flag.

---

### PATL17/30/55 — Employee History Summary

**Source:** `paehist` + `pastaff`
**Purpose:** Summarised payroll history (rolled up per employee + pay code) for a date range.
PATL30 = same sorted by paygroup; PATL55 = same sorted by payrun.

**Selection fields:**

| Field | Type | Label |
|---|---|---|
| `startEmployee` | number | Starting employee no |
| `endEmployee` | number | Ending employee no |
| `startPaygroup` | text(4) | Starting paygroup |
| `endPaygroup` | text(4) | Ending paygroup |
| `startDept` | text(4) | Starting dept |
| `endDept` | text(4) | Ending dept |
| `startDate` | DatePicker | Start payrun date |
| `endDate` | DatePicker | End payrun date |
| `sortBy` | RadioButton | Sort by: Employee / Paygroup / Payrun |

**SQL (Employee sort):**
```sql
SELECT h.employee_no, s.surname, s.first_name, s.paygroup, s.dept,
       h.pay_type, h.pay_code, c.desc1,
       SUM(h.hrs) total_hrs, SUM(h.ext_amt) total_amt
FROM paehist h
JOIN pastaff s ON s.company_no=h.company_no AND s.employee_no=h.employee_no
LEFT JOIN pacodes c ON c.company_no=h.company_no AND c.pay_code=h.pay_code
WHERE h.company_no=?
  AND h.employee_no BETWEEN ? AND ?
  AND s.paygroup BETWEEN ? AND ?
  AND s.dept BETWEEN ? AND ?
  AND h.payrun_date BETWEEN ? AND ?
GROUP BY h.employee_no, s.surname, s.first_name, s.paygroup, s.dept,
         h.pay_type, h.pay_code, c.desc1
ORDER BY -- employee: s.surname, s.first_name, h.employee_no, h.pay_type
         -- paygroup: s.paygroup, s.surname, h.employee_no, h.pay_type
         -- payrun: h.payrun_date, h.employee_no, h.pay_type
```

**PDF layout:** Group by employee (or paygroup, or payrun per sort option).
Per pay code: Pay Code | Description | Hours (÷60) | Amount.
Group subtotals. Grand total.

---

### PATL05/09 — Deductions & Superannuation

**Source:** `paytd` + `pastaff` + `pacodes` (where pay_type IN (19,20,21) — deductions and super)
PATL09 = same sorted by fund name instead of pay code.

**Selection fields:**

| Field | Type | Label |
|---|---|---|
| `startCode` | text(6) | Starting pay code |
| `endCode` | text(6) | Ending pay code |
| `startEmployee` | number | Starting employee no |
| `endEmployee` | number | Ending employee no |
| `yearNo` | ComboBox | Tax year |
| `sortBy` | RadioButton | Sort by: Pay Code / Fund Name |

**SQL:**
```sql
SELECT t.pay_code, t.pay_type, c.desc1, c.fund_name,
       t.employee_no, s.surname, s.first_name,
       t.amt, t.hrs
FROM paytd t
JOIN pastaff s ON s.company_no=t.company_no AND s.employee_no=t.employee_no
LEFT JOIN pacodes c ON c.company_no=t.company_no AND c.pay_code=t.pay_code
WHERE t.company_no=? AND t.year_no=?
  AND t.pay_type IN (19, 20, 21)
  AND t.pay_code BETWEEN ? AND ?
  AND t.employee_no BETWEEN ? AND ?
ORDER BY -- pay code sort: t.pay_code, s.surname, t.employee_no
         -- fund name sort: c.fund_name, t.pay_code, s.surname
```

**PDF layout:** Group by pay code (or fund name). Header: Code | Description | Fund.
Per employee: Emp No | Name | Amount.
Group total. Grand total.

---

### PATL16 — Department Expenses

**Source:** `pacosts` + `pagroup` + `padepts` + `pacodes`
**Purpose:** Period expense summary by department and pay code for a period-end date.
Note: Selection date must match an existing `period_end_date` in pacosts.

**Selection fields:**

| Field | Type | Label |
|---|---|---|
| `periodEndDate` | DatePicker | Period end date |
| `startPaygroup` | text(4) | Starting paygroup |
| `endPaygroup` | text(4) | Ending paygroup |
| `startDept` | text(4) | Starting dept |
| `endDept` | text(4) | Ending dept |

**SQL:**
```sql
SELECT k.paygroup, g.description paygroup_desc,
       k.dept, d.description dept_desc,
       k.pay_type, k.pay_code, c.desc1,
       k.amt, k.hrs
FROM pacosts k
LEFT JOIN pagroup g ON g.company_no=k.company_no AND g.paygroup=k.paygroup
LEFT JOIN padepts d ON d.company_no=k.company_no AND d.dept=k.dept
LEFT JOIN pacodes c ON c.company_no=k.company_no AND c.pay_code=k.pay_code
WHERE k.company_no=? AND k.period_end_date=?
  AND k.paygroup BETWEEN ? AND ?
  AND k.dept BETWEEN ? AND ?
ORDER BY k.paygroup, k.dept, k.pay_type, k.pay_code
```

**Validation:** Warn if no pacosts rows exist for the selected date.

**PDF layout:** Group by paygroup → dept. Per pay code: Code | Description | Hours (÷60) | Amount.
Dept subtotal. Paygroup subtotal. Grand total.

---

### PATL32/06 — GL Distribution

**Source:** `paehist` + `glchart` + `pastaff`
**Purpose:** Distribution of payroll amounts to GL accounts for a period. Selects paehist rows
where payrun_date falls within a GL period. Note: in current data gl_acct_no_main is mostly 0;
only payroll-tax clearing (991) is populated — this report will be sparse until GL costing is wired.
PATL06 = same sorted by GL account then employee.

**Selection fields:**

| Field | Type | Label |
|---|---|---|
| `periodEndDate` | DatePicker | Period end date (must be a gldates period end) |
| `startAcct` | number | Starting GL account |
| `endAcct` | number | Ending GL account |
| `startPaygroup` | text(4) | Starting paygroup |
| `endPaygroup` | text(4) | Ending paygroup |
| `startDept` | text(4) | Starting dept |
| `endDept` | text(4) | Ending dept |
| `startPayrun` | number | Starting payrun no |
| `endPayrun` | number | Ending payrun no |
| `sortBy` | RadioButton | Sort by: Payrun / GL Account+Employee |

**SQL:**
```sql
SELECT h.gl_acct_no_main, h.gl_acct_no_sub,
       c.desc1 acct_desc,
       h.employee_no, s.surname, s.first_name,
       h.payrun_no, h.payrun_date,
       h.pay_type, h.pay_code, h.ext_amt
FROM paehist h
LEFT JOIN glchart c ON c.company_no=h.company_no
     AND c.acct_main_no=h.gl_acct_no_main AND c.acct_sub_no=h.gl_acct_no_sub
JOIN pastaff s ON s.company_no=h.company_no AND s.employee_no=h.employee_no
WHERE h.company_no=?
  AND h.payrun_date = ?               -- period end date filter
  AND h.gl_acct_no_main BETWEEN ? AND ?
  AND h.paygroup BETWEEN ? AND ?
  AND h.dept BETWEEN ? AND ?
  AND h.payrun_no BETWEEN ? AND ?
  AND h.gl_acct_no_main > 0          -- exclude uncoded lines
ORDER BY -- payrun: h.payrun_no, h.gl_acct_no_main, h.employee_no
         -- GL+emp: h.gl_acct_no_main, h.employee_no, h.payrun_no
```

**Note:** Period end date drives the filter (paehist.payrun_date = period end date, matching COBOL convention). Validate date against gldates.

**PDF layout:** Group by GL account (or payrun per sort). Per employee line: Emp | Name | Pay Code | Amount.
Account subtotal. Grand total.

---

### PATL60 — Payrun GL Detail

**Source:** `paehist` + `parunhd` + `pastaff` + `glchart`
**Purpose:** Full GL line detail for a single posted payrun.

**Selection fields:**

| Field | Type | Label |
|---|---|---|
| `payrunNo` | number | Payrun no |

Validate: payrun must exist, not deleted, not in 'C' (created) status — must be 'P' (posted) or 'F' (completed).

**SQL:**
```sql
SELECT h.employee_no, s.surname, s.first_name,
       h.pay_type, h.pay_code, c_pc.desc1 code_desc,
       h.gl_acct_no_main, h.gl_acct_no_sub, c_gl.desc1 acct_desc,
       h.ext_amt
FROM paehist h
JOIN parunhd r ON r.company_no=h.company_no AND r.payrun_no=h.payrun_no
JOIN pastaff s ON s.company_no=h.company_no AND s.employee_no=h.employee_no
LEFT JOIN pacodes c_pc ON c_pc.company_no=h.company_no AND c_pc.pay_code=h.pay_code
LEFT JOIN glchart c_gl ON c_gl.company_no=h.company_no
     AND c_gl.acct_main_no=h.gl_acct_no_main AND c_gl.acct_sub_no=h.gl_acct_no_sub
WHERE h.company_no=? AND h.payrun_no=?
ORDER BY h.employee_no, h.gl_acct_no_main, h.pay_type
```

**PDF layout:** Report header shows payrun details (no/date/status).
Group by employee. Per line: Pay Code | GL Account | Description | Amount.
Employee total. Grand total debit / credit.

---

### PATL28 — Timesheet History

**Source:** `paehist` + `pastaff` + `parunhd`
**Purpose:** Payroll history filtered by paygroup, employee, and date range — similar to PATL14 but
focused on timesheet (hours-based) lines and grouped by payrun.

**Selection fields:**

| Field | Type | Label |
|---|---|---|
| `startPaygroup` | text(4) | Starting paygroup |
| `endPaygroup` | text(4) | Ending paygroup |
| `startEmployee` | number | Starting employee no |
| `endEmployee` | number | Ending employee no |
| `startDate` | DatePicker | Start date |
| `endDate` | DatePicker | End date |

**SQL:**
```sql
SELECT h.employee_no, s.surname, s.first_name, s.paygroup, s.dept,
       h.payrun_no, h.payrun_date,
       h.pay_type, h.pay_code, c.desc1,
       h.hrs, h.qty, h.rate_perc, h.ext_amt, h.ref,
       h.this_pay_start_date, h.this_pay_to_date
FROM paehist h
JOIN pastaff s ON s.company_no=h.company_no AND s.employee_no=h.employee_no
LEFT JOIN pacodes c ON c.company_no=h.company_no AND c.pay_code=h.pay_code
WHERE h.company_no=?
  AND h.paygroup BETWEEN ? AND ?
  AND h.employee_no BETWEEN ? AND ?
  AND h.payrun_date BETWEEN ? AND ?
ORDER BY s.paygroup, h.employee_no, h.payrun_date, h.pay_type, h.line_no
```

**PDF layout:** Group by paygroup → employee → payrun.
Per line: Pay Code | Description | Period dates | Hours (÷60) | Qty | Rate | Amount.
Payrun subtotal. Employee total. Grand total.

---

### PATL40 — Super/Deductions Paid Status

**Source:** `paehist` + `pacodes` + `pastaff`
**Purpose:** Shows deduction and super amounts paid (or unpaid) for each employee,
with filtering by pay code type. pay_type 19/20 = deductions; 20/21 = super.

**Selection fields:**

| Field | Type | Label |
|---|---|---|
| `startCode` | text(6) | Starting pay code |
| `endCode` | text(6) | Ending pay code |
| `startEmployee` | number | Starting employee no |
| `endEmployee` | number | Ending employee no |
| `startDate` | DatePicker | Start payrun date |
| `endDate` | DatePicker | End payrun date |
| `includeDeductions` | CheckBox | Include before-tax deductions |
| `includeAfterTaxDedns` | CheckBox | Include after-tax deductions |
| `includeEmployeeSuper` | CheckBox | Include employee super |
| `includeEmployerSuper` | CheckBox | Include employer super |

**SQL:**
```sql
SELECT h.employee_no, s.surname, s.first_name,
       h.pay_code, c.desc1, c.fund_name,
       h.payrun_date, h.payrun_no,
       h.ext_amt, h.paid_flag
FROM paehist h
JOIN pastaff s ON s.company_no=h.company_no AND s.employee_no=h.employee_no
LEFT JOIN pacodes c ON c.company_no=h.company_no AND c.pay_code=h.pay_code
WHERE h.company_no=?
  AND h.pay_code BETWEEN ? AND ?
  AND h.employee_no BETWEEN ? AND ?
  AND h.payrun_date BETWEEN ? AND ?
  AND h.pay_type IN (/* selected types based on checkboxes: 19,20,21 */)
ORDER BY h.pay_code, h.employee_no, h.payrun_date
```

**PDF layout:** Group by pay code. Per employee/payrun line: Emp | Name | Payrun Date | Amount | Paid flag.
Code total. Grand total.

---

### PASP10 — Superannuation by Fund

**Source:** `paytd` + `pacodes` + `pafunds` + `pastaff`
**Purpose:** YTD super contributions grouped by fund, showing employee breakdown.

**Selection fields:**

| Field | Type | Label |
|---|---|---|
| `startCode` | text(6) | Starting super code |
| `endCode` | text(6) | Ending super code |
| `startEmployee` | number | Starting employee no |
| `endEmployee` | number | Ending employee no |
| `yearNo` | ComboBox | Tax year |

**SQL:**
```sql
SELECT c.fund_name, c.fund_abn, c.fund_usi,
       t.pay_code, c.desc1 code_desc,
       t.employee_no, s.surname, s.first_name,
       t.amt
FROM paytd t
JOIN pastaff s ON s.company_no=t.company_no AND s.employee_no=t.employee_no
LEFT JOIN pacodes c ON c.company_no=t.company_no AND c.pay_code=t.pay_code
WHERE t.company_no=? AND t.year_no=?
  AND t.pay_type IN (20, 21)          -- employer SGC + salary sacrifice
  AND t.pay_code BETWEEN ? AND ?
  AND t.employee_no BETWEEN ? AND ?
ORDER BY c.fund_name, t.pay_code, s.surname, t.employee_no
```

**PDF layout:** Group by fund. Fund header: Fund Name | ABN | USI.
Per employee: Emp No | Name | Code | Amount.
Fund subtotal (employer SGC / salary sacrifice split). Grand total.

---

### PATL26 — Extended Superannuation

**Source:** `paehist` + `pacodes` + `pafunds` + `pastaff`
**Purpose:** Detailed super history by date range showing fund details, amounts, and employee TFN
(masked per CLAUDE.md rule: `Employee.maskTfn(String)`).

**Selection fields:**

| Field | Type | Label |
|---|---|---|
| `startCode` | text(6) | Starting super code |
| `endCode` | text(6) | Ending super code |
| `startEmployee` | number | Starting employee no |
| `endEmployee` | number | Ending employee no |
| `startDate` | DatePicker | Start payrun date |
| `endDate` | DatePicker | End payrun date |

**SQL:**
```sql
SELECT h.employee_no, s.surname, s.first_name,
       Employee.maskTfn(CAST(s.tax_file_no AS CHAR)) masked_tfn,
       h.payrun_date, h.pay_code, c.desc1 code_desc,
       c.fund_name, c.fund_abn, c.fund_usi,
       h.ext_amt, c.super_before_after_tax
FROM paehist h
JOIN pastaff s ON s.company_no=h.company_no AND s.employee_no=h.employee_no
LEFT JOIN pacodes c ON c.company_no=h.company_no AND c.pay_code=h.pay_code
WHERE h.company_no=?
  AND h.pay_type IN (20, 21)
  AND h.pay_code BETWEEN ? AND ?
  AND h.employee_no BETWEEN ? AND ?
  AND h.payrun_date BETWEEN ? AND ?
ORDER BY h.pay_code, h.employee_no, h.payrun_date
```

**Note:** TFN masking must happen in Java service (not SQL) — `Employee.maskTfn(String.valueOf(rs.getLong("tax_file_no")))`.

**PDF layout:** Group by pay code → employee. Fund header per employee.
Per payrun line: Date | Amount | Before/After tax indicator.
Employee total. Grand total.

---

## Deferred — missing data

| Program | Reason |
|---------|--------|
| PATL12 — Payroll Tax Detail | `papatax` not in extract pipeline |

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
