package com.landmarksoftware.report;

import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import net.sf.jasperreports.engine.export.*;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.export.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.nio.file.*;
import java.sql.Connection;
import java.util.Map;

/**
 * Central JasperReports service.
 *
 * Report files (.jrxml) live in src/main/resources/reports/{module}/.
 * They are compiled on first use and cached in a temp directory.
 *
 * Usage:
 *   byte[] pdf   = jasper.exportPdf("gl/trial-balance", params);
 *   byte[] xlsx  = jasper.exportExcel("gl/trial-balance", params);
 *   String html  = jasper.exportHtml("gl/trial-balance", params);
 */
@Service
public class JasperReportService {

    private static final Logger log = LoggerFactory.getLogger(JasperReportService.class);

    private final DataSource dataSource;

    // Migrated from @Value("${landmark.reports.compile-dir}") — JavaFX desktop
    // has no Spring config property for this, so use the system temp dir.
    private final String compileDir = System.getProperty("java.io.tmpdir")
        + File.separator + "landmark-reports-cache";

    public JasperReportService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Export report as PDF bytes. */
    public byte[] exportPdf(String reportPath, Map<String, Object> params) throws Exception {
        JasperPrint print = fill(reportPath, params);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JasperExportManager.exportReportToPdfStream(print, out);
        return out.toByteArray();
    }

    /** Export report as Excel (.xlsx) bytes. */
    public byte[] exportExcel(String reportPath, Map<String, Object> params) throws Exception {
        JasperPrint print = fill(reportPath, excelParams(params));
        configureExcelExclusions(print);
        return runXlsxExport(print);
    }

    // ── Data-source variants ───────────────────────────────────────────────
    // Used by reports whose query is too dynamic for a static .jrxml SQL
    // block (e.g. AR/AP ageing with 4 selection flags rewriting WHERE).
    // The caller pre-fetches rows and wraps them in a JRBeanCollectionDataSource.

    /** Export PDF using an in-memory list of beans/maps instead of JDBC. */
    public byte[] exportPdfFromDataSource(String reportPath,
                                          Map<String, Object> params,
                                          JRDataSource dataSource) throws Exception {
        JasperPrint print = fillFromDataSource(reportPath, params, dataSource);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JasperExportManager.exportReportToPdfStream(print, out);
        return out.toByteArray();
    }

    /** Export Excel using an in-memory list of beans/maps instead of JDBC. */
    public byte[] exportExcelFromDataSource(String reportPath,
                                            Map<String, Object> params,
                                            JRDataSource dataSource) throws Exception {
        JasperPrint print = fillFromDataSource(reportPath, excelParams(params), dataSource);
        configureExcelExclusions(print);
        return runXlsxExport(print);
    }

    /**
     * Wrap the caller's params with IS_IGNORE_PAGINATION=true so the fill
     * produces a single continuous "page" instead of N paginated ones.
     * Without this, the Excel exporter repeats the column header (and any
     * page header/footer) at every page boundary because each page is
     * independently formed during fill.
     */
    private Map<String, Object> excelParams(Map<String, Object> caller) {
        Map<String, Object> p = new java.util.HashMap<>(caller);
        p.put(JRParameter.IS_IGNORE_PAGINATION, Boolean.TRUE);
        return p;
    }

    /**
     * Set Excel-exclude properties on the JasperPrint so the exporter
     * trims out per-page repeats.
     *
     * Each Excel jrxml already declares band.1=pageFooter and band.2=title via
     * its own &lt;property&gt; elements. We use band indices 3+ here so we do NOT
     * override those jrxml settings (overwriting band.1/band.2 from Java would
     * silently drop the title exclusion and show the title in every Excel output).
     * band.3=pageHeader is a safety net for the rare report that has a page header.
     */
    private void configureExcelExclusions(JasperPrint print) {
        // Keep column header only on the first page (matches every Excel jrxml's keep.first.band.1).
        print.setProperty(
            "net.sf.jasperreports.export.xls.exclude.origin.keep.first.band.1",
            "columnHeader");
        // Exclude pageHeader via a higher band index — jrxml owns band.1 (pageFooter) and band.2 (title).
        print.setProperty(
            "net.sf.jasperreports.export.xls.exclude.origin.band.3",
            "pageHeader");
    }

    private byte[] runXlsxExport(JasperPrint print) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        JRXlsxExporter exporter = new JRXlsxExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));

        SimpleXlsxReportConfiguration cfg = new SimpleXlsxReportConfiguration();
        cfg.setOnePagePerSheet(false);
        cfg.setDetectCellType(true);
        cfg.setCollapseRowSpan(false);
        cfg.setRemoveEmptySpaceBetweenRows(true);
        cfg.setIgnorePageMargins(true);
        cfg.setWhitePageBackground(false);
        exporter.setConfiguration(cfg);
        exporter.exportReport();

        return out.toByteArray();
    }

    private JasperPrint fillFromDataSource(String reportPath,
                                           Map<String, Object> params,
                                           JRDataSource dataSource) throws Exception {
        JasperReport compiled = compile(reportPath);
        return JasperFillManager.fillReport(compiled, params, dataSource);
    }

    /** Export report as HTML string (for on-screen preview). */
    public String exportHtml(String reportPath, Map<String, Object> params) throws Exception {
        JasperPrint print = fill(reportPath, params);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        HtmlExporter exporter = new HtmlExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleHtmlExporterOutput(out));

        SimpleHtmlExporterConfiguration cfg = new SimpleHtmlExporterConfiguration();
        cfg.setBetweenPagesHtml("<hr style='border:1px dashed #ccc; margin:20px 0;'>");
        exporter.setConfiguration(cfg);
        exporter.exportReport();

        return out.toString("UTF-8");
    }

    // ── Internal ───────────────────────────────────────────────────────────

    /**
     * Fill a report: compile if needed, connect to DB, run.
     * reportPath is relative, e.g. "gl/trial-balance" → reports/gl/trial-balance.jrxml
     */
    private JasperPrint fill(String reportPath, Map<String, Object> params) throws Exception {
        JasperReport compiled = compile(reportPath);
        try (Connection conn = dataSource.getConnection()) {
            return JasperFillManager.fillReport(compiled, params, conn);
        }
    }

    /**
     * Compile .jrxml → .jasper, caching compiled file in temp dir.
     * Recompiles automatically when the source .jrxml is newer than the cache.
     */
    private JasperReport compile(String reportPath) throws Exception {
        String jrxmlClasspath = "reports/" + reportPath + ".jrxml";
        String cacheKey       = reportPath.replace("/", "_");
        Path   cachedFile     = Path.of(compileDir, cacheKey + ".jasper");

        ClassPathResource resource = new ClassPathResource(jrxmlClasspath);
        if (!resource.exists()) {
            throw new FileNotFoundException("Report not found on classpath: " + jrxmlClasspath);
        }

        // Use cache only if it is at least as new as the source .jrxml.
        if (Files.exists(cachedFile)) {
            long jrxmlMtime  = resource.lastModified();
            long jasperMtime = Files.getLastModifiedTime(cachedFile).toMillis();
            if (jrxmlMtime > 0 && jrxmlMtime <= jasperMtime) {
                log.debug("Using cached compiled report: {}", cachedFile);
                try (ObjectInputStream ois = new ObjectInputStream(
                        Files.newInputStream(cachedFile))) {
                    return (JasperReport) ois.readObject();
                }
            }
            log.info("Source newer than cache — recompiling: {}", jrxmlClasspath);
        }

        // Compile from classpath .jrxml
        log.info("Compiling report: {}", jrxmlClasspath);
        Files.createDirectories(Path.of(compileDir));

        JasperReport compiled;
        try (InputStream in = resource.getInputStream()) {
            compiled = JasperCompileManager.compileReport(in);
        }

        // Write to cache (overwrite if exists)
        try (ObjectOutputStream oos = new ObjectOutputStream(
                Files.newOutputStream(cachedFile,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING))) {
            oos.writeObject(compiled);
        }
        log.info("Compiled and cached: {}", cachedFile);

        return compiled;
    }
}
