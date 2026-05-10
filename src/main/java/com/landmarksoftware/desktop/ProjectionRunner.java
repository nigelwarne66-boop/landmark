package com.landmarksoftware.desktop;

import com.landmarksoftware.model.ProjectionRequest;
import com.landmarksoftware.repository.CompanyRepository;
import com.landmarksoftware.repository.GlSessionRepository;
import com.landmarksoftware.service.DepreciationProjectionService;
import com.landmarksoftware.export.DepreciationExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Drives a single FATL12→FATL13 projection run from command-line arguments.
 *
 * Usage:
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--company=1 --year=2025 --projDate=2025-06-30"
 *
 * Or with java -jar:
 *   java -jar fixed-assets.jar --company=1 --year=2025 --projDate=2025-06-30
 *
 * All arguments have defaults (see @Value fields below) so you can run
 * with no arguments for a quick smoke test.
 *
 * Optional arguments:
 *   --company=N          Company number (default: 1)
 *   --year=YYYY          4-digit fiscal year (default: current year)
 *   --batch=N            Batch number (default: 1)
 *   --projDate=YYYY-MM-DD  Projection-to date — must be a GL period-end (default: today)
 *   --stream=B|T         Book or Tax depreciation (default: B)
 *   --rate=NN.NN         Override projected rate, 0 = use master file (default: 0)
 *   --startAsset=X       Start asset number (default: first)
 *   --endAsset=X         End asset number (default: last)
 *   --outDir=path        Output directory for .xlsx file (default: ./output)
 */
@org.springframework.context.annotation.Profile("cli")
@Component
public class ProjectionRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProjectionRunner.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    // ── Injected dependencies ─────────────────────────────────────────
    private final DepreciationProjectionService projectionService;
    private final DepreciationExportService     exportService;
    private final CompanyRepository             companyRepo;

    // ── Parameters (Spring binds --argName=value from command line) ───
    @Value("${company:1}")
    private int companyNo;

    @Value("${year:#{T(java.time.LocalDate).now().getYear()}}")
    private int yearNo;

    @Value("${batch:1}")
    private int batchNo;

    @Value("${projDate:#{T(java.time.LocalDate).now().toString()}}")
    private String projDateStr;

    @Value("${stream:B}")
    private String stream;

    @Value("${rate:0}")
    private String rateStr;

    @Value("${startAsset: }")
    private String startAsset;

    @Value("${endAsset:~~~~~~~~~~~~~~~~~~~~}")
    private String endAsset;

    @Value("${outDir:./output}")
    private String outDir;

    // ── Constructor injection ─────────────────────────────────────────
    public ProjectionRunner(
            DepreciationProjectionService projectionService,
            DepreciationExportService     exportService,
            CompanyRepository             companyRepo) {
        this.projectionService = projectionService;
        this.exportService     = exportService;
        this.companyRepo       = companyRepo;
    }

    // ────────────────────────────────────────────────────────────────────
    // Run
    // ────────────────────────────────────────────────────────────────────

    @Override
    public void run(String... args) throws Exception {

        LocalDate projDate;
        try {
            projDate = LocalDate.parse(projDateStr, ISO);
        } catch (Exception e) {
            log.error("Invalid projDate '{}' — expected YYYY-MM-DD", projDateStr);
            return;
        }

        BigDecimal rate;
        try {
            rate = new BigDecimal(rateStr.trim());
        } catch (Exception e) {
            log.error("Invalid rate '{}'", rateStr);
            return;
        }

        char streamChar = stream.toUpperCase().charAt(0);
        if (streamChar != 'B' && streamChar != 'T') {
            log.error("Invalid stream '{}' — must be B or T", stream);
            return;
        }

        // ── Resolve tax year end month from company master ────────────
        int taxYrEndMth = companyRepo.findByCompanyNo(companyNo)
                .map(c -> c.getFaTaxYrEndMth())
                .orElseGet(() -> {
                    log.warn("Company {} not found in cp_company — defaulting tax year end to June (6)", companyNo);
                    return 6;
                });

        // ── Build projection request ──────────────────────────────────
        ProjectionRequest req = new ProjectionRequest();
        req.setCompanyNo(companyNo);
        req.setYearNo(yearNo);
        req.setBatchNo(batchNo);
        req.setProjectedToDate(projDate);
        req.setTaxOrBook(streamChar);
        req.setProjectedRate(rate);
        req.setStartAssetNo(startAsset.trim().isEmpty() ? " " : startAsset);
        req.setEndAssetNo(endAsset);

        log.info("=======================================================");
        log.info("FA Depreciation Projection");
        log.info("  Company    : {}", companyNo);
        log.info("  Year       : {}", yearNo);
        log.info("  Stream     : {} ({})", streamChar, streamChar == 'B' ? "Book" : "Tax");
        log.info("  Proj. date : {}", projDate);
        log.info("  Proj. rate : {} (0 = use master file)", rate);
        log.info("  Asset range: {} → {}", req.getStartAssetNo().trim(), req.getEndAssetNo().trim());
        log.info("  Tax yr end : month {}", taxYrEndMth);
        log.info("=======================================================");

        // ── Run projection ────────────────────────────────────────────
        long t0 = System.currentTimeMillis();
        DepreciationProjectionService.ProjectionOutput output;
        try {
            output = projectionService.project(req);
        } catch (IllegalArgumentException e) {
            log.error("Projection failed — invalid parameters: {}", e.getMessage());
            log.error("Hint: projDate must match a GL period-end date in gl_year.period_end_N");
            return;
        }

        int assetCount = output.results().size();
        int colCount   = output.header().columnCount();
        log.info("Projection complete: {} assets × {} period columns  ({} ms)",
                assetCount, colCount, System.currentTimeMillis() - t0);

        if (assetCount == 0) {
            log.warn("No assets selected — check your asset range and that fa_asset has status='A' records with cost > 0");
            return;
        }

        // ── Export to Excel ───────────────────────────────────────────
        Path dir = Paths.get(outDir);
        Files.createDirectories(dir);

        String filename = String.format("FA_DepnProjection_%s_%s_%s.xlsx",
                companyNo, streamChar, projDate.toString().replace("-", ""));
        Path outFile = dir.resolve(filename);

        exportService.export(output, outFile);
        log.info("Excel written: {}", outFile.toAbsolutePath());
        log.info("=======================================================");
    }
}
