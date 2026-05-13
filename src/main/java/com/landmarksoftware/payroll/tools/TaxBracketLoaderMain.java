/*
 * Copyright (c) 2026 Landmark Software Pty Ltd.
 * All rights reserved.
 *
 * This software is proprietary and confidential.
 * Unauthorised copying, modification, distribution or use
 * of this software, via any medium, is strictly prohibited.
 * Decompilation and reverse engineering are expressly forbidden.
 *
 * Licenced under the terms of the Landmark Software Licence Agreement.
 */
package com.landmarksoftware.payroll.tools;

import com.landmarksoftware.payroll.model.TaxBracket;
import com.landmarksoftware.payroll.service.TaxBracketLoader;
import com.landmarksoftware.payroll.service.TaxBracketService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * CLI for the annual ATO tax-table refresh.
 *
 * <p>Each July the ATO publishes new NAT_1004 (PAYG) and/or NAT_3539 (STSL)
 * spreadsheets. The payroll administrator downloads them, then runs this
 * tool to load each file into the {@code tax_brackets} table. The tool
 * deletes any previously loaded brackets for the same
 * (companyNo, sourceFile, effectiveFrom) tuple before re-inserting — safe
 * to re-run with a corrected file.
 *
 * <p>Usage:
 * <pre>
 *   mvn -q exec:java \
 *     -Dexec.mainClass=com.landmarksoftware.payroll.tools.TaxBracketLoaderMain \
 *     -Dexec.args="1 NAT_1004 2025-07-01 C:\\ATO\\NAT_1004.xlsx"
 *
 *   mvn -q exec:java \
 *     -Dexec.mainClass=com.landmarksoftware.payroll.tools.TaxBracketLoaderMain \
 *     -Dexec.args="1 NAT_3539 2025-07-01 C:\\ATO\\NAT_3539.xlsx"
 * </pre>
 *
 * <p>Args: {@code <companyNo> <sourceFile> <effectiveFrom yyyy-MM-dd> <xlsxPath>}
 *
 * <p>Runs as a Spring Boot non-web app — no JavaFX, no Tomcat — so the
 * process exits cleanly when the load is finished.
 */
@SpringBootApplication(scanBasePackages = "com.landmarksoftware.payroll")
public class TaxBracketLoaderMain {

    public static void main(String[] args) {
        if (args.length != 4) {
            usage();
            System.exit(2);
        }
        int companyNo;
        try { companyNo = Integer.parseInt(args[0]); }
        catch (NumberFormatException ex) {
            System.err.println("Bad companyNo: " + args[0]);
            usage();
            System.exit(2);
            return;
        }
        String sourceFile = args[1];
        if (!"NAT_1004".equals(sourceFile) && !"NAT_3539".equals(sourceFile)) {
            System.err.println("sourceFile must be NAT_1004 or NAT_3539 (was: " + sourceFile + ")");
            System.exit(2);
        }
        LocalDate effectiveFrom;
        try { effectiveFrom = LocalDate.parse(args[2]); }
        catch (DateTimeParseException ex) {
            System.err.println("Bad effectiveFrom (expected yyyy-MM-dd): " + args[2]);
            usage();
            System.exit(2);
            return;
        }
        Path xlsx = Path.of(args[3]);
        if (!Files.isRegularFile(xlsx)) {
            System.err.println("xlsx not found: " + xlsx);
            System.exit(2);
        }

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TaxBracketLoaderMain.class)
            .web(org.springframework.boot.WebApplicationType.NONE)
            .headless(true)
            .run(args);

        int exitCode = 0;
        try {
            TaxBracketLoader  loader  = ctx.getBean(TaxBracketLoader.class);
            TaxBracketService service = ctx.getBean(TaxBracketService.class);

            List<TaxBracket> parsed = loader.parse(xlsx, companyNo, sourceFile, effectiveFrom);
            if (parsed.isEmpty()) {
                System.err.println("Parsed zero brackets from " + xlsx +
                    " — check the sheet layout (expected 'Statement of Formula - CSV' at sheet index 1)");
                exitCode = 1;
            } else {
                String user = System.getProperty("user.name", "");
                int n = service.replaceForPublication(companyNo, sourceFile, effectiveFrom, parsed, user);
                System.out.println("Loaded " + n + " brackets for "
                    + sourceFile + " effective " + effectiveFrom
                    + " into company " + companyNo + " (loaded_by=" + user + ")");
            }
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            exitCode = 1;
        } finally {
            SpringApplication.exit(ctx, () -> 0);
            ctx.close();
        }
        System.exit(exitCode);
    }

    private static void usage() {
        System.err.println(
            "Usage:\n" +
            "  TaxBracketLoaderMain <companyNo> <NAT_1004|NAT_3539> <yyyy-MM-dd> <xlsxPath>\n" +
            "Example:\n" +
            "  TaxBracketLoaderMain 1 NAT_3539 2025-07-01 C:\\ATO\\NAT_3539.xlsx");
    }
}
