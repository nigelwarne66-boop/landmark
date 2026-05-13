# ATO Tax Scale — Annual Load Process

The `tax_brackets` table holds the coefficient rows the payroll engine uses
to compute PAYG withholding (NAT_1004) and STSL/HELP withholding (NAT_3539).
It is **not** sourced from the COBOL extract — the ATO publishes these tables
each financial year as Excel workbooks, and we load them via a Java CLI.

## How the two files are used

Landmark uses the ATO's **combined withholding formula** — one bracket
lookup per employee, no add-on math:

```
if employee.has_stsl_debt:
    weekly_tax = a₃₅₃₉ · x − b₃₅₃₉    ← NAT_3539 (PAYG + STSL combined)
else:
    weekly_tax = a₁₀₀₄ · x − b₁₀₀₄    ← NAT_1004 (PAYG only)
```

This matches the ATO Tax Calculator output exactly. Cross-check (scale 2,
$1,500 weekly, with STSL): `0.47 × 1500 − 369.85 = $335.15` → $336 when
rounded, the value the ATO calc reports.

Both files publish brackets the **same way**, on sheet index 1
("Statement of Formula - CSV"):

| File | Coefficients carry | Used when |
|------|---------------------|-----------|
| NAT_1004 | PAYG only | employee has no STSL debt |
| NAT_3539 | PAYG + STSL **combined** | employee has STSL debt |

Layout in both: `scale_no | upper_earnings | a | b`. The loader is a single
parser that just stamps `source_file` from the CLI arg.

> **Why not load NAT_3539 sheet 5 (Component Rates)?** Sheet 5 has a
> separate component-only formula in `y = a·x − b` form, but the values
> don't reconcile with the ATO calculator's STSL component (it gives
> ~$31.73 STSL at $1,500 scale 2; the calculator implies ~$75). The
> combined formula on sheet 2 IS reproducible, so we use that.

## When to run

- **Every 1 July** — load the new NAT_1004 once the ATO publishes the new
  financial year's "Statement of Formulas for Calculating Amounts to be
  Withheld" workbook.
- **Mid-year** if the ATO releases a corrected NAT_3539 (STSL coefficients
  change when HELP/SFSS rates are tweaked). NAT_1004 and NAT_3539 are
  versioned independently — the `(source_file, effective_from)` key keeps
  them separate.

## What to download

| File | Where | What it covers |
|------|-------|----------------|
| `NAT_1004.xlsx` | ato.gov.au → tax tables → "Statement of formulas" | PAYG weekly withholding coefficients |
| `NAT_3539.xlsx` | ato.gov.au → tax tables → "Study and training support loans" | STSL/HELP additional withholding |

Note the **effective-from date** printed on the workbook's cover page
(usually 1 July of the new financial year). The spreadsheet itself does not
encode this date.

## How to run

The loader is a standalone Spring Boot CLI — no JavaFX, no Tomcat — so it
exits cleanly when the load is finished.

```powershell
# NAT_1004 (PAYG)
mvn -q exec:java `
  -Dexec.mainClass=com.landmarksoftware.payroll.tools.TaxBracketLoaderMain `
  "-Dexec.args=1 NAT_1004 2025-07-01 C:\ATO\NAT_1004.xlsx"

# NAT_3539 (STSL)
mvn -q exec:java `
  -Dexec.mainClass=com.landmarksoftware.payroll.tools.TaxBracketLoaderMain `
  "-Dexec.args=1 NAT_3539 2025-07-01 C:\ATO\NAT_3539.xlsx"
```

Arguments: `<companyNo> <NAT_1004|NAT_3539> <effective-from yyyy-MM-dd> <xlsx-path>`

The loader:
1. Reads sheet index 1 ("Statement of Formula - CSV") — same layout in
   both workbooks: four columns `(scale_no, x, a, b)`.
2. Re-numbers brackets within each `scale_no` group (scales 1, 2, 3, 5, 6).
3. Deletes any existing brackets for the same
   `(company_no, source_file, effective_from)` tuple.
4. Inserts the parsed brackets in one transaction, stamping `source_file`
   with the CLI argument.
5. Prints `Loaded N brackets …` on success.

Safe to re-run with a corrected file — step 3 wipes the prior load for that
publication before re-inserting.

## Verifying the load

```sql
SELECT scale_no, COUNT(*) AS brackets,
       MIN(upper_earnings) AS first_band,
       MAX(upper_earnings) AS terminator
FROM   tax_brackets
WHERE  company_no     = 1
  AND  source_file    = 'NAT_1004'
  AND  effective_from = '2025-07-01'
GROUP  BY scale_no
ORDER  BY scale_no;
```

The last bracket of each scale should have `upper_earnings = 999999.00` —
that's the ATO terminator row for "no cap, use coefficients to infinity".
If it's missing, the parse stopped early — re-check the workbook.

## Schema

`TaxBracketService` auto-creates the table on Spring startup via
`@PostConstruct`; you should never need to run DDL by hand.

```
tax_brackets
  company_no      INT
  source_file     VARCHAR(20)   -- 'NAT_1004' or 'NAT_3539'
  effective_from  DATE          -- as printed on the ATO cover page
  scale_no        VARCHAR(2)
  bracket_no      INT           -- 1-based within (source, scale, eff_from)
  upper_earnings  DECIMAL(10,2) -- "x less than" threshold; 999999 = uncapped
  coeff_a         DECIMAL(10,6) -- tax rate
  coeff_b         DECIMAL(10,4) -- subtraction value
  audit_loaded_at TIMESTAMP
  audit_loaded_by VARCHAR(64)
  PRIMARY KEY (company_no, source_file, effective_from, scale_no, bracket_no)
```

`tax = ROUND(coeff_a × earnings − coeff_b, 0)` for the first bracket whose
`upper_earnings ≥ employee's weekly earnings`.
