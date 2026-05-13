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

## Current state (as of 2026-05-13)

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

### Wave 1.5 — Audit back-fill 🔲 **DO THIS BEFORE WAVE 2**
The Wave 1 maintenance screens already populate the per-row `audit_user_id/date/time*` columns, but the COBOL has a heavier per-change audit trail we haven't wired. See "Audit framework" section below for the full plan.

### Wave 2 — Batch operations 🔲 After audit back-fill
PAEM11, PAPC01, PAEM60, PASU55, PASU11/14/15. Each needs **preview mode**, batch-level audit row in `pa_audit` (option B — batch metadata only, no per-row detail; the per-row trail is the Wave 1.5 audit tables), and progress indicator for large employee counts.

### Wave 3, 4, 5
Per `PAYROLL_PLAN.md`. The `PaygTaxCalculator` will be built **as part of PATM01 / PAPP01 in Wave 3** (user's call — deferred from Wave 1).

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

## Audit framework — Wave 1.5 plan

COBOL has a two-flavour audit on master files. Our Java port populates the per-row `audit_user_id/date/time*` stamp on every write, but does **not** yet write to the per-change audit tables. **Close this gap before Wave 2.**

### Existing COBOL pattern

| Table | Captures | Pattern | In extract? | Java writes? |
|-------|----------|---------|-------------|--------------|
| `paemaud` | Employee changes — full 1500-byte before/after snapshot + maint-type | Heavyweight | ❌ | ❌ |
| `pafuaud` | Super fund changes — 1500-byte before/after | Heavyweight | ❌ | ❌ |
| `papcaud` | Per-employee pay-code row changes | Per-row tracker | ✅ | ❌ |
| `pacdchg` | Pay code rate-before / amt-before; drives downstream recalc | Lightweight | ❌ | ❌ |
| `paawchg` | Award / job class last change date; drives recalc | Lightweight | ❌ | ❌ |
| `glchaud` / `apspaud` / `arcuaud` / `faasaud` / `fadraud` | Other modules' equivalents | Mixed | ✅ | n/a in payroll scope |

### Wave 1.5 task list

1. **Add missing tables to the extract pipeline** (`C:\landmark_extract\sql\create/insert` + `pafiles.txt`):
   - `paemaud`, `pafuaud`, `paawchg`, `pacdchg`.
   - The COBOL FDs at `C:\landmark\compile\` are the canonical source.
2. **Decide the heavyweight before/after column format.** Recommended: store as `LONGTEXT` containing a **JSON snapshot** of the record (one row per change). Cleaner than COBOL's 1500-byte fixed blob, queryable via `JSON_EXTRACT`, and a future "show me employee 1234's change history" view works the same way COBOL does.
3. **Wire writes in existing Wave 1 services** — every update/insert/delete on master data writes one audit row in the same transaction:
   - `EmployeeService` → `paemaud` (full before/after JSON).
   - `PayCodeService` → `pacdchg` when rate/amount changes; existing `papcaud` (already in extract) on paecode row changes.
   - `AwardService` + `AwardJobClassService` → `paawchg` (last-change-date pattern).
   - PAEM01 super tab edits + a future Super Fund Maintenance → `pafuaud`.
4. **Audit service abstraction.** Each `*Service` shouldn't hand-roll JSON serialisation — add a `MasterFileAuditService` that takes `(table_name, pk_map, before_object, after_object, user, maint_type)` and writes the appropriate audit row. Keep transaction scope on the parent service method.

### Wave 2 fits on top

The `pa_audit` table (option B from the chat — batch-level metadata only) sits alongside the Wave 1.5 per-change tables. PASU55's recalc-YTD batch writes one `pa_audit` row + many `paemaud` rows for each employee touched. Together: pa_audit gives you batch grouping, paemaud gives you per-employee before/after.

---

## Build + run

- JDK 25 (compile), JRE 1.8.0_421 (legacy Hibernate at runtime).
- `mvn -q compile` to build, `mvn javafx:run` to launch.
- Maven picks up `JAVA_HOME` from env — set to `C:\Program Files\Java\latest\jdk-25` before running.
- ProGuard runs at `package` phase (rules in `src/main/proguard/rules.pro`) — not in normal dev cycle.

## Things deferred / open

- **Wave 1.5 — Audit back-fill** — see "Audit framework" section above. **Next concrete task.**
- **`pa_audit` batch metadata table** — option B agreed: `(audit_id PK, company_no, run_timestamp, user_id, program_code, description, rows_affected, status, notes)`. Built as part of Wave 2 alongside the heavier per-change tables.
- **PaygTaxCalculator service** — bracket lookup against `tax_brackets`. Wave 3 / PATM01.
- **PASU04 manual bracket editing** — currently read-only; if users need to hand-tweak coefficients (e.g. custom scale "H"), needs Add/Edit/Delete UI on the Brackets tab.
- **Print everywhere** — PAEM01 has a Print button + `EmployeePdfService`. PAPG01 / PASU04 / PAAW01 don't (low priority).
