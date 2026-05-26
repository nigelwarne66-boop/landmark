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
        JasperPrint print = fill(reportPath, params);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        JRXlsxExporter exporter = new JRXlsxExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));

        SimpleXlsxReportConfiguration cfg = new SimpleXlsxReportConfiguration();
        cfg.setOnePagePerSheet(false);
        cfg.setDetectCellType(true);
        cfg.setCollapseRowSpan(false);
        exporter.setConfiguration(cfg);
        exporter.exportReport();

        return out.toByteArray();
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
        JasperPrint print = fillFromDataSource(reportPath, params, dataSource);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        JRXlsxExporter exporter = new JRXlsxExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));

        SimpleXlsxReportConfiguration cfg = new SimpleXlsxReportConfiguration();
        cfg.setOnePagePerSheet(false);
        cfg.setDetectCellType(true);
        cfg.setCollapseRowSpan(false);
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
     * Thread-safe: synchronized on the report path.
     */
    private JasperReport compile(String reportPath) throws Exception {
        String jrxmlClasspath = "reports/" + reportPath + ".jrxml";
        String cacheKey       = reportPath.replace("/", "_");
        Path   cachedFile     = Path.of(compileDir, cacheKey + ".jasper");

        // Check cache first
        if (Files.exists(cachedFile)) {
            log.debug("Using cached compiled report: {}", cachedFile);
            try (ObjectInputStream ois = new ObjectInputStream(
                    Files.newInputStream(cachedFile))) {
                return (JasperReport) ois.readObject();
            }
        }

        // Compile from classpath .jrxml
        log.info("Compiling report: {}", jrxmlClasspath);
        ClassPathResource resource = new ClassPathResource(jrxmlClasspath);
        if (!resource.exists()) {
            throw new FileNotFoundException("Report not found on classpath: " + jrxmlClasspath);
        }

        Files.createDirectories(Path.of(compileDir));

        JasperReport compiled;
        try (InputStream in = resource.getInputStream()) {
            compiled = JasperCompileManager.compileReport(in);
        }

        // Write to cache
        try (ObjectOutputStream oos = new ObjectOutputStream(
                Files.newOutputStream(cachedFile))) {
            oos.writeObject(compiled);
        }
        log.info("Compiled and cached: {}", cachedFile);

        return compiled;
    }
}
