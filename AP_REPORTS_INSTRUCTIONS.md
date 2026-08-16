# Claude Code Instructions — Accounts Payable Reports (AP Module)

## Context and conventions

Read `CONVENTIONS.md` fully before starting. Key reminders:
- Module name: **ap** (lowercase throughout)
- URL pattern for reports: `GET /reports/ap/{name}?format=pdf|excel&[params]`
- URL pattern for param form: `GET /reports/ap/{name}/params?format=`
- jrxml files go in `src/main/resources/reports/ap/`
- Thymeleaf param templates go in `templates/ap/reports.html` (cards) and the param template case in `ReportController.paramTemplate()`
- Standard params always injected: `COMPANY_NO`, `YEAR_NO`, `COMPANY_NAME`, `YEAR_DESC`, `YR_START_DATE`, `YR_END_DATE`, `USER_ID`
- DB tables (confirmed): `apsupps`, `aptrans`, `appurch`, `apsumry` — see CONVENTIONS.md for columns
- Also used: `glchart` (acct_main_no, desc1, pl_bs_ind, dr_cr_ind), `gldates` (yr_no, year_no, period_end_1..13), `cpcoyco`
- For AP distributions: table `apdisum` or `apdistn` (distribution lines on aptrans)
- For AP reconciliations: table `aprecon` (recon header), `aptrans` has `recon_no`, `recon_seq_no`
- For AP sub-ledgers: table `apledgr` (sub_ledger PK, name)
- For foreign currency: `cpfccod` (for_curr_code PK)
- **PDF output must replicate the COBOL report layout exactly** (same columns, same totalling breaks, same heading text).
- **Excel output should include all PDF columns PLUS any additional useful fields** (e.g. supplier name where PDF only shows number, full dates, extra reference fields).
- Delete `${java.io.tmpdir}/landmark-reports-cache/` after changing any jrxml.

---

## Report cards to add to `templates/ap/reports.html`

Add one card per report to the AP reports list page. Use the existing GL/FA/PY cards as style templates.

---

## Reports to implement

---

| Program | Title | Primary tables | Groups by | Key notes |
|---|---|---|---|---|
| APTL01 | Cash Requirements | `apsupps`, `aptrans` | Supplier | D/S toggle (detail vs summary); 5 allocation flags A–E (OR logic); at least one flag must be Y; `trx_status='O'` filter; warn if all flags N |
| APTL05 | GL Distributions Summary | `apdisum`, `glchart` | GL account | `acct_type='C'` = control vs expense split; filter by `period_end_date` and optional sub_ledger |
| APTL06 | Period Summary | `apsumry`, `apledgr`, `gldates` | Sub ledger | Opening balance = `apsumry.open_bal` (period_end_date=0) + cumulative prior-period movements in same year |
| APTL09 | Supplier Purchase History | `appurch`, `apsupps`, `gldates` | Sub ledger, supplier type | `appurch.purch_01..13` — map to period via gldates; YTD + last year total + variance; suppressZero option |
| APTL10 | Supplier Analysis | `aptrans`, `apsupps` | Supplier | **Excel-only** (COBOL forces this); top-N ranking by AN/AV/TN/TV; N month-end buckets in columns; COBOL work files → sort in memory |
| APRC03 | Account Reconciliation | `aprecon`, `aptrans`, `apsupps` | Supplier, recon_no | U/B/A balance filter; local vs FC toggle; `paymtDocNoInd` shows payment doc detail; exclude archived transactions |
| APRC04 | Unbalanced Reconciliation | `aprecon`, `apsupps` | Supplier | Local and FC balance filters applied independently (AND); date filter on last transaction date |
| APRC05 | Transaction Listing | `aptrans`, `apsupps`, `apdistn` | Supplier | Most flexible filter: doc_type multi-select, date/post date, batch, posting status, on-hold sub-flags; optional distribution + PO sub-lines; exclude archived |
| APRC09 | Detailed Transaction Listing | `apdistn`, `aptrans`, `apsupps`, `glchart` | Supplier | Distribution-level detail with tax breakdown by code (T/E/C/N/D/J/Z); doc or post date filter |
| APRC11 | FC Revaluation | `aptrans`, `apsupps` | Supplier | **Guard:** `cpcoyco.ap_for_curr_flag='Y'` required; revaluation date range filter; recon/unrecon filter; shows FC balance + reval gain/loss |

Full SQL specs were removed 2026-05-31 as they are derivable from the table descriptions above and CONVENTIONS.md. The Common implementation notes section below covers all non-obvious patterns.

---

## Implementation order (suggested)

1. **APRC05** (Transaction Listing) — most commonly used, good baseline for aptrans queries
2. **APRC09** (Detailed Transaction Listing) — similar to APRC05, adds distributions
3. **APTL06** (Period Summary) — straightforward summary from apsumry
4. **APTL05** (GL Distributions) — uses apdisum, parallel to GL work
5. **APTL09** (Purchase History) — uses appurch periods table
6. **APRC04** (Unbalanced Reconciliation) — uses aprecon
7. **APRC03** (Account Reconciliation) — full reconciliation detail
8. **APTL01** (Cash Requirements) — complex allocation logic, test thoroughly
9. **APTL10** (Supplier Analysis) — Excel-only, complex aggregation
10. **APRC11** (Foreign Currency Revaluation) — requires foreign currency installation

---

## Common implementation notes

### DB column name verification

**Before writing any SQL**, run a quick schema check on the live DB to confirm actual column names on `aptrans`, `aprecon`, `apdisum`, `apdistn`, `apledgr`, `appurch`. The CONVENTIONS.md only lists the key columns — there are many more. Use:

```sql
SHOW COLUMNS FROM aptrans;
SHOW COLUMNS FROM aprecon;
-- etc.
```

Key unknowns to verify: `posting_date` vs `post_date`, `batch_no` vs `jnl_no`, `sub_ledger` column on aptrans, `disc_date`, `prompt_pay_flag`, `must_pay_flag`, `on_hold_flag`, `reval_date`, `reval_batch_no`, `for_curr_code`, `alpha_key`, `acct_status`.

### Date handling

All COBOL dates are stored as `YYMMDD` or `YYYYMMDD` integer sequences ("day numbers"). In the Java/SQL layer, use the DB's actual date column types — these should already be proper DATE columns in the SQL DB. Pass `java.time.LocalDate` parameters from the controller.

### Sub ledger

`apledgr` stores sub ledger codes and names. When a sub ledger filter is blank, include all sub ledgers. Show the sub ledger name alongside the code in all reports.

### Session check

Always call `session.hasCompany()` before querying. Redirect to `/select-company` if false.

### JasperReports parameters

Declare all filter values as JasperReports parameters. Pass them to both the SQL query (via `$P{...}` in the WHERE clause) and to the report header/legend band so each printout is self-documenting.

### Excel column extras rule

For every report: wherever the PDF shows only a code (supplier no, GL account no, sub ledger code, doc type code), the Excel version should add the corresponding name/description column immediately adjacent to the code column.
