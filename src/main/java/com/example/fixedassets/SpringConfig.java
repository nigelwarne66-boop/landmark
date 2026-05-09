package com.example.fixedassets;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Spring Boot configuration class — kept separate from FixedAssetsApplication
 * because JavaFX requires the entry point to extend javafx.application.Application,
 * while Spring Boot's @SpringBootApplication must not extend it (classpath conflict).
 *
 * FixedAssetsApplication.init() calls SpringApplication.run(SpringConfig.class, ...)
 * which boots this configuration, scanning all @Component / @Service / @Repository
 * classes in the com.example.fixedassets package tree.
 */
@SpringBootApplication
public class SpringConfig {
    // No content needed — @SpringBootApplication triggers component scan,
    // auto-configuration (DataSource, JdbcTemplate), and property binding.
}
