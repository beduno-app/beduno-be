package com.beduno.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

/**
 * Drives this project's Flyway migrations directly against a caller-supplied JDBC URL, bypassing
 * Spring Boot's FlywayAutoConfiguration entirely. This is what lets a test seed rows between an
 * intermediate schema version and the latest one -- something IntegrationTestBase's shared,
 * already-fully-migrated container (started once, shared across the whole test JVM run) cannot
 * do without breaking every other integration test.
 */
public class MigrationTestSupport {

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MigrationTestSupport(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /** Migrates up to and including the given Flyway version, e.g. {@code "11"}. */
    public void migrateTo(String version) {
        flyway(MigrationVersion.fromVersion(version)).migrate();
    }

    /**
     * Migrates every remaining pending migration. Reuses Flyway's own {@code flyway_schema_history}
     * table, so this resumes from wherever a prior {@link #migrateTo(String)} call left off rather
     * than re-running anything.
     */
    public void migrateToLatest() {
        flyway(MigrationVersion.LATEST).migrate();
    }

    private Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
