# Migration: landmark-reports → JavaFX Reporting Hub
# Paste this entire file into Claude Code

## What we have

The old `landmark-reports` Spring/web project (landmark-reports_1.zip) contains:

### Completed .jrxml report files (copy these directly, no changes needed)
```
src/main/resources/reports/fa/asset-register.jrxml
src/main/resources/reports/fa/depreciation.jrxml
src/main/resources/reports/fa/acquisitions.jrxml
src/main/resources/reports/gl/trial-balance.jrxml
src/main/resources/reports/gl/profit-loss.jrxml
src/main/resources/reports/gl/balance-sheet.jrxml
src/main/resources/reports/gl/general-journal.jrxml
src/main/resources/reports/gl/account-transactions.jrxml
src/main/resources/reports/py/employee-list.jrxml
src/main/resources/reports/py/payroll-summary.jrxml
```
→ Copy all to: `src/main/resources/reports/` (same structure, same paths)

### Completed data services (migrate with session adapter — see Wave 1)
- `GlDataService`   — full GL queries, confirmed table/column names
- `FaDataService`   — FA queries, KPIs, asset register data
- `PyDataService`   — payroll queries, paehist, pacosts
- `ArDataService`   — AR ageing, arcusts, artrans
- `ApDataService`   — AP ageing, apsupps, aptrans
- `CmDataService`   — CM cashbook, cmtrans
- `SmDataService`   — SM stock, smstloc
- `PoDataService`   — PO open orders

### Completed JasperReportService
- Compiles .jrxml on first use, caches .jasper in temp dir
- `exportPdf(reportPath, params)` → `byte[]`
- `exportExcel(reportPath, params)` → `byte[]`
- `reportPath` format: `"gl/trial-balance"` (no extension)

### Standard Jasper params (always injected)
```java
COMPANY_NO, YEAR_NO, COMPANY_NAME, YEAR_DESC,
YR_START_DATE, YR_END_DATE, USER_ID,
FROM_PERIOD (int), TO_PERIOD (int), AS_AT_PERIOD (int), ACCT_NO (String)
```

### Session difference
Old project uses `ReportSession` (Spring @SessionScope).
New project uses `AppSession` (@Component singleton in Spring context).
AppSession already has: companyNo, companyName, yearNo, yrNo, yearDesc,
yrStartDate, yrEndDate, userId.
The field names are identical — just the class name changes.

---

## Wave 1 — Foundation (do this first, everything else depends on it)

### 1a. Copy .jrxml files
Copy all 10 .jrxml files listed above into
`src/main/resources/reports/{fa,gl,py}/`
Create the directory structure if it doesn't exist.

### 1b. Migrate JasperReportService
Copy `JasperReportService.java` from the old project into
`src/main/java/com/landmarksoftware/report/JasperReportService.java`

Change:
- Package: `com.landmark.reports.service` → `com.landmarksoftware.report`
- Remove `@Value("${landmark.reports.compile-dir}")` — replace with:
  ```java
  private final String compileDir = System.getProperty("java.io.tmpdir")
      + File.separator + "landmark-reports-cache";
  ```
- Keep everything else exactly as-is

Add to `pom.xml` if not already present:
```xml
<dependency>
    <groupId>net.sf.jasperreports</groupId>
    <artifactId>jasperreports</artifactId>
    <version>6.21.0</version>
</dependency>
<dependency>
    <groupId>net.sf.jasperreports</groupId>
    <artifactId>jasperreports-fonts</artifactId>
    <version>6.21.0</version>
</dependency>
```

### 1c. Create JasperService adapter in ReportsHubController
Add `@Autowired private JasperReportService jasper;`

Add this helper method to ReportsHubController — every selection screen
controller calls this instead of calling services directly:

```java
public void runJasperReport(String reportPath, Map<String, Object> extraParams,
                              String format, javafx.stage.Window owner) {
    Map<String, Object> params = buildStandardParams();
    params.putAll(extraParams);

    new Thread(() -> {
        try {
            byte[] data;
            String ext, mime;
            if ("pdf".equals(format)) {
                data = jasper.exportPdf(reportPath, params);
                ext = ".pdf"; mime = "application/pdf";
            } else {
                data = jasper.exportExcel(reportPath, params);
                ext = ".xlsx";
                mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            }
            final byte[] finalData = data;
            final String finalExt = ext;
            javafx.application.Platform.runLater(() ->
                saveOrOpen(finalData, finalExt, owner));
        } catch (Exception ex) {
            javafx.application.Platform.runLater(() ->
                showError("Report failed", ex.getMessage()));
        }
    }, "jasper-" + reportPath).start();
}

private Map<String, Object> buildStandardParams() {
    Map<String, Object> p = new java.util.HashMap<>();
    p.put("COMPANY_NO",    session.getCompanyNo());
    p.put("YEAR_NO",       session.getYearNo());
    p.put("COMPANY_NAME",  session.getCompanyName());
    p.put("YEAR_DESC",     session.getYearDesc());
    p.put("YR_START_DATE", session.getYrStartDate() != null
        ? java.sql.Date.valueOf(session.getYrStartDate()) : null);
    p.put("YR_END_DATE",   session.getYrEndDate() != null
        ? java.sql.Date.valueOf(session.getYrEndDate()) : null);
    p.put("USER_ID",       session.getUserId());
    return p;
}

private void saveOrOpen(byte[] data, String ext, javafx.stage.Window owner) {
    javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
    fc.setTitle("Save Report");
    fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
        ext.equals(".pdf") ? "PDF" : "Excel", "*" + ext));
    java.io.File file = fc.showSaveDialog(owner);
    if (file != null) {
        try {
            java.nio.file.Files.write(file.toPath(), data);
            // Open the saved file with the system default viewer
            java.awt.Desktop.getDesktop().open(file);
        } catch (Exception ex) {
            showError("Could not save file", ex.getMessage());
        }
    }
}
```

### 1d. Migrate data services
Copy each data service from old project, changing only:
- Package declaration (e.g. `com.landmark.reports.service.gl` → `com.landmarksoftware.service.gl`)
- Import `com.landmarksoftware.model.AppSession` instead of `ReportSession`
- Replace every `ReportSession` type reference with `AppSession`
- All SQL queries and logic: copy exactly, zero changes

Services to migrate:
```
GlDataService   → src/main/java/com/landmarksoftware/service/gl/GlDataService.java
FaDataService   → src/main/java/com/landmarksoftware/service/fa/FaDataService.java
PyDataService   → src/main/java/com/landmarksoftware/service/py/PyDataService.java
ArDataService   → src/main/java/com/landmarksoftware/service/ar/ArDataService.java
ApDataService   → src/main/java/com/landmarksoftware/service/ap/ApDataService.java
CmDataService   → src/main/java/com/landmarksoftware/service/cm/CmDataService.java
SmDataService   → src/main/java/com/landmarksoftware/service/sm/SmDataService.java
PoDataService   → src/main/java/com/landmarksoftware/service/po/PoDataService.java
```

Run `mvn javafx:run -Preporting` — app should launch cleanly (no report calls yet).

---

## Wave 2 — Fixed Assets (3 reports, services already wired)

For each report, create:
- FXML: `src/main/resources/fxml/reports/fa/{report-name}.fxml`
- Controller: `src/main/java/com/landmarksoftware/ui/reports/Fa{Name}Controller.java`

Use `SELECTION-SCREEN-PATTERN.fxml` as the template for each.

### FA/1 — Asset Register
Selection fields: none required (runs for whole year)
```java
// Controller onPdf / onExcel:
hubController.runJasperReport("fa/asset-register", Map.of(), format, getWindow());
```

### FA/2 — Depreciation
Selection fields: none required (year from session)
```java
hubController.runJasperReport("fa/depreciation", Map.of(), format, getWindow());
```

### FA/3 — Acquisitions & Retirements
Selection fields: none required
```java
hubController.runJasperReport("fa/acquisitions", Map.of(), format, getWindow());
```

Wire `openSelectionScreen()` in ReportsHubController for each FA report.
Run `mvn javafx:run -Preporting` — FA PDF and Excel buttons should produce output.

---

## Wave 3 — General Ledger (5 reports, period selection needed)

Create a shared period-selection component `GlPeriodSelector.fxml` (included as
an fx:include in each GL selection screen) with:
- ComboBox `fromPeriod` (Period 1–13, default 1)
- ComboBox `toPeriod` (Period 1–13, default 12)

Populate both combos by querying:
```sql
SELECT period_no, period_end_date 
FROM gldates 
WHERE company_no = ? AND yr_no = ?
ORDER BY period_no
```
Label each option as "Period N — MMM YYYY" using period_end_date.

### GL/1 — Trial Balance
Extra params: `FROM_PERIOD`, `TO_PERIOD`
```java
Map.of("FROM_PERIOD", fromPeriod, "TO_PERIOD", toPeriod)
```

### GL/2 — Profit & Loss
Extra params: `FROM_PERIOD`, `TO_PERIOD`

### GL/3 — Balance Sheet
Selection field: single `asAtPeriod` ComboBox (label "As at end of period")
Extra params: `AS_AT_PERIOD`

### GL/4 — General Journal
Extra params: `FROM_PERIOD`, `TO_PERIOD`

### GL/5 — Account Transactions
Extra fields: period from/to + TextField `ACCT_NO` (default "%", hint "e.g. 1100 or % for all")
Extra params: `FROM_PERIOD`, `TO_PERIOD`, `ACCT_NO`

---

## Wave 4 — Payroll (2 reports)

### PY/1 — Payroll Summary
Selection fields:
- ComboBox `payrunFrom` — pay run date range start
  Query: `SELECT DISTINCT payrun_date FROM parunhd WHERE company_no=? AND yr_no=? ORDER BY payrun_date`
- ComboBox `payrunTo` — pay run date range end (same query, default last)
Extra params: `FROM_DATE` (java.sql.Date), `TO_DATE` (java.sql.Date)

### PY/2 — Employee List
Selection fields: 
- ComboBox `department` (optional filter, "All departments" = null)
  Query: `SELECT DISTINCT dept, dept FROM pastaff WHERE company_no=? ORDER BY dept`
- ComboBox `status` — Active only / All / Terminated
  Options: A=Active (default), blank=All, T=Terminated
Extra params: `DEPT` (String, "%" for all), `EMP_STATUS` (String)

---

## Wave 5 — AR / AP ageing (when ready)

### AR — Debtors Ageing
Selection fields (from old web params):
- ComboBox `detailSummary`: Summary (S) / Detail (D)
- ComboBox `dateInd`: Transaction Date (T) / Due Date (D) / Posting Date (P)  
- ComboBox `grossNet`: Net Outstanding (N) / Gross Original (G)
- DatePicker `asAtDate`: optional, defaults to today

No .jrxml exists yet — create `reports/ar/debtors-ageing.jrxml`
using ArDataService.getAgeingData() as the data source.

### AP — Creditors Ageing
Same selection fields as AR (same four controls, same options).
No .jrxml exists yet — create `reports/ap/creditors-ageing.jrxml`.

---

## Key notes for CC

1. The old `FaViewController`, `GlViewController` etc. are web controllers —
   ignore them completely. The JavaFX equivalents are the selection screen
   controllers above.

2. `GlReportWriterService` and `GlReportWriterController` from the old project
   are a custom report-builder feature — skip for now, add to backlog.

3. The `application.properties` in the old project has DB connection details —
   the JavaFX project already has its own `application.properties`, don't merge.

4. `ReportSession.payrollAccess` (from old project) maps to a future
   `AppSession.payrollAccess` field — add it to AppSession and set it from
   MEUSERS during login (column: print_pa_from_pass = 'Y').

5. After Wave 2 succeeds with real PDF output, commit before starting Wave 3.
