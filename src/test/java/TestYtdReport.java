import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.Iterator;
import java.util.*;

/**
 * Standalone diagnostic: renders PATL02 PDF with hardcoded test rows,
 * once via JRBeanCollectionDataSource and once via direct map.get DataSource.
 * Run: mvn -q test-compile exec:java -Dexec.mainClass=TestYtdReport -Dexec.classpathScope=test
 */
public class TestYtdReport {

    public static void main(String[] args) throws Exception {
        // Hardcoded rows mirroring what PayReportDataService produces
        List<Map<String, Object>> rows = buildRows();
        System.out.println("Row count: " + rows.size());
        for (Map<String, Object> r : rows) {
            System.out.printf("  rowKind=%-8s payAmt=%-12s codeDesc=%s%n",
                r.get("rowKind"), r.get("payAmt"), r.get("codeDesc"));
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("COMPANY_NAME", "Test Company");
        params.put("YEAR_DESC",    "FY 2025-26");
        params.put("ACCT_RANGE",   "");
        params.put("GRAND_TOTAL",  new BigDecimal("108714.00"));
        params.put("ROW_COUNT",    rows.size());

        String jrxmlPath = "src/main/resources/reports/py/employee-ytd-payments.jrxml";

        // Test 1: JRBeanCollectionDataSource (existing approach)
        renderAndSave(jrxmlPath, params, new JRBeanCollectionDataSource(rows),
            "C:/temp/test-ytd-BEAN.pdf", "JRBeanCollectionDataSource");

        // Test 2: direct map.get DataSource (new approach)
        renderAndSave(jrxmlPath, params, mapDataSource(rows),
            "C:/temp/test-ytd-DIRECT.pdf", "Direct mapDataSource");

        System.out.println("\nrows.size() before test 3 = " + rows.size());

        // Test 3: minimal inline jrxml — no printWhenExpression, payAmt unconditional
        String minJrxml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<jasperReport xmlns=\"http://jasperreports.sourceforge.net/jasperreports\"" +
            " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"" +
            " xsi:schemaLocation=\"http://jasperreports.sourceforge.net/jasperreports" +
            " http://jasperreports.sourceforge.net/xsd/jasperreport.xsd\"" +
            " name=\"min\" pageWidth=\"500\" pageHeight=\"400\" columnWidth=\"460\"" +
            " leftMargin=\"20\" rightMargin=\"20\" topMargin=\"20\" bottomMargin=\"20\"" +
            " whenNoDataType=\"AllSectionsNoDetail\">" +
            "<style name=\"base\" isDefault=\"true\" isBlankWhenNull=\"true\"/>" +
            "<field name=\"rowKind\" class=\"java.lang.String\"/>" +
            "<field name=\"codeDesc\" class=\"java.lang.String\"/>" +
            "<field name=\"payAmt\" class=\"java.math.BigDecimal\"/>" +
            "<detail><band height=\"14\">" +
            // Unconditional — shows payAmt as BigDecimal with pattern on every row
            "<textField pattern=\"#,##0.00;(#,##0.00)\"><reportElement x=\"300\" y=\"2\" width=\"150\" height=\"10\"/>" +
            "<textElement textAlignment=\"Right\"/>" +
            "<textFieldExpression><![CDATA[$F{payAmt}]]></textFieldExpression></textField>" +
            // Also show rowKind as String to confirm field access works
            "<textField><reportElement x=\"0\" y=\"2\" width=\"100\" height=\"10\"/>" +
            "<textFieldExpression><![CDATA[$F{rowKind}]]></textFieldExpression></textField>" +
            // And codeDesc
            "<textField><reportElement x=\"104\" y=\"2\" width=\"190\" height=\"10\"/>" +
            "<textFieldExpression><![CDATA[$F{codeDesc}]]></textFieldExpression></textField>" +
            "</band></detail>" +
            "<summary><band height=\"14\">" +
            "<staticText><reportElement x=\"0\" y=\"2\" width=\"300\" height=\"10\"/><text><![CDATA[END]]></text></staticText>" +
            "</band></summary>" +
            "</jasperReport>";

        renderAndSave(jrxmlPath, params, mapDataSource(buildRows()),
            "C:/temp/test-ytd-MIN.pdf", "Minimal jrxml (fresh rows)");

        // Test 4: full jrxml but payAmt expression patched to String.valueOf() — bypasses BigDecimal rendering
        String fullJrxml;
        try (InputStream in = new FileInputStream(jrxmlPath)) {
            fullJrxml = new String(in.readAllBytes(), "UTF-8");
        }
        // Replace the BigDecimal pattern textField expression with String.valueOf for detail rows
        // Patch: add isStretchWithOverflow="true" to payAmt — only structural diff vs codeDesc
        String patched = fullJrxml
            .replace(
                "<textField pattern=\"#,##0.00;(#,##0.00)\"><reportElement x=\"530\" y=\"2\" width=\"140\" height=\"10\"><printWhenExpression><![CDATA[\"detail\".equals($F{rowKind})]]>",
                "<textField isStretchWithOverflow=\"true\" pattern=\"#,##0.00;(#,##0.00)\"><reportElement x=\"530\" y=\"2\" width=\"140\" height=\"10\"><printWhenExpression><![CDATA[\"detail\".equals($F{rowKind})]]>");
        System.out.println("\nAdded isStretchWithOverflow to payAmt detail: " + (patched.contains("isStretchWithOverflow=\"true\" pattern") ? "YES" : "NO"));
        try (InputStream in = new ByteArrayInputStream(patched.getBytes("UTF-8"))) {
            JasperReport compiled = JasperCompileManager.compileReport(in);
            JasperPrint print = JasperFillManager.fillReport(compiled, params, mapDataSource(buildRows()));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            net.sf.jasperreports.engine.JasperExportManager.exportReportToPdfStream(print, baos);
            Files.write(Path.of("C:/temp/test-ytd-STRVAL.pdf"), baos.toByteArray());
            System.out.println("\n--- Test 4: String.valueOf($F{payAmt}) ---");
            System.out.println("Saved " + baos.size() + " bytes → C:/temp/test-ytd-STRVAL.pdf");
        }
    }

    static void renderAndSave(String jrxmlPath, Map<String, Object> params,
                              JRDataSource ds, String outPath, String label) throws Exception {
        System.out.println("\n--- " + label + " ---");
        try (InputStream in = new FileInputStream(jrxmlPath)) {
            JasperReport compiled = JasperCompileManager.compileReport(in);
            JasperPrint  print    = JasperFillManager.fillReport(compiled, params, ds);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            net.sf.jasperreports.engine.JasperExportManager.exportReportToPdfStream(print, baos);
            byte[] pdf = baos.toByteArray();
            Files.write(Path.of(outPath), pdf);
            System.out.println("Saved " + pdf.length + " bytes → " + outPath);
        } catch (Exception e) {
            System.err.println("FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static List<Map<String, Object>> buildRows() {
        List<Map<String, Object>> out = new ArrayList<>();
        // Employee 1: Sara Warne — PAY 10000, TAX 1750, SUPER 1764, SACRIF 22700, PAYTAX 0
        out.add(header(1, "WARNE", "SARA", "1", "1"));
        out.add(detail("PAY",    "ORDINARY PAY",                  10000.00));
        out.add(detail("TAX",    "Tax",                            1750.00));
        out.add(detail("SUPER",  "SUPERANNUATION GUARANTEE",       1764.00));
        out.add(detail("SACRIF", "SALARY SACRIFICE",              22700.00));
        out.add(detail("PAYTAX", "Payroll Tax",                       0.00));
        out.add(total(1, 36214.00));
        return out;
    }

    static Map<String, Object> header(int empNo, String surname, String firstName,
                                       String dept, String paygroup) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rowKind",   "header");
        r.put("empNo",     empNo);
        r.put("surname",   surname);
        r.put("firstName", firstName);
        r.put("dept",      dept);
        r.put("paygroup",  paygroup);
        r.put("payType",   0);
        r.put("payCode",   "");
        r.put("codeDesc",  empNo + "  —  " + surname + ", " + firstName
                         + "   Dept: " + dept + "   Paygroup: " + paygroup);
        r.put("hours",     null);
        r.put("payAmt",    BigDecimal.ZERO);
        return r;
    }

    static Map<String, Object> detail(String code, String desc, double amt) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rowKind",   "detail");
        r.put("empNo",     1);
        r.put("surname",   "WARNE");
        r.put("firstName", "SARA");
        r.put("dept",      "1");
        r.put("paygroup",  "1");
        r.put("payType",   1);
        r.put("payCode",   code);
        r.put("codeDesc",  desc);
        r.put("hours",     null);
        r.put("payAmt",    BigDecimal.valueOf(amt));
        return r;
    }

    static Map<String, Object> total(int empNo, double amt) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rowKind",   "total");
        r.put("empNo",     empNo);
        r.put("surname",   "");
        r.put("firstName", "");
        r.put("dept",      "");
        r.put("paygroup",  "");
        r.put("payType",   0);
        r.put("payCode",   "");
        r.put("codeDesc",  "Employee Total");
        r.put("hours",     null);
        r.put("payAmt",    BigDecimal.valueOf(amt));
        return r;
    }

    static JRDataSource mapDataSource(List<Map<String, Object>> rows) {
        return new JRDataSource() {
            private final Iterator<Map<String, Object>> it = rows.iterator();
            private Map<String, Object> current;
            @Override public boolean next() {
                if (!it.hasNext()) return false;
                current = it.next(); return true;
            }
            @Override public Object getFieldValue(JRField f) { return current.get(f.getName()); }
        };
    }
}
