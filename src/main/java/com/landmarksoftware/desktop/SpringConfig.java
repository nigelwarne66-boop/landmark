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
package com.landmarksoftware.desktop;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot configuration class — kept separate from FixedAssetsApplication
 * because JavaFX requires the entry point to extend javafx.application.Application,
 * while Spring Boot's @SpringBootApplication must not extend it (classpath conflict).
 *
 * FixedAssetsApplication.init() calls SpringApplication.run(SpringConfig.class, ...)
 * which boots this configuration, scanning all @Component / @Service / @Repository
 * classes under the com.landmarksoftware package tree.
 *
 * scanBasePackages is set explicitly because this class lives in the .desktop
 * sub-package, while most beans live in sibling packages (model, service, ui,
 * repository, payroll.*, etc.) — the default scan would miss them.
 */
@SpringBootApplication(scanBasePackages = "com.landmarksoftware")
public class SpringConfig {
    // No content needed — @SpringBootApplication triggers component scan,
    // auto-configuration (DataSource, JdbcTemplate), and property binding.
}
