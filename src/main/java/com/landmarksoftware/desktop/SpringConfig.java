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
import org.springframework.context.annotation.ComponentScan;

/**
 * Spring Boot configuration class — kept separate from FixedAssetsApplication
 * because JavaFX requires the entry point to extend javafx.application.Application,
 * while Spring Boot's @SpringBootApplication must not extend it (classpath conflict).
 *
 * FixedAssetsApplication.init() calls SpringApplication.run(SpringConfig.class, ...)
 * which boots this configuration, scanning all @Component / @Service / @Repository
 * classes in the com.landmarksoftware.desktop package tree.
 */
@SpringBootApplication
public class SpringConfig {
    // No content needed — @SpringBootApplication triggers component scan,
    // auto-configuration (DataSource, JdbcTemplate), and property binding.
}
