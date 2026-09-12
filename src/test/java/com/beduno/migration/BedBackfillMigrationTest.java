package com.beduno.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V12__backfill_beds.sql's real backfill logic against populated data, using the real
 * V10-V14 migration sequence via MigrationTestSupport -- something IntegrationTestBase's shared,
 * already-fully-migrated container cannot do, since every test there only ever sees an empty
 * schema before any fixture is inserted.
 *
 * <p>Runs against its own dedicated Testcontainers Postgres instance, independent of
 * IntegrationTestBase, and boots no Spring context at all -- Flyway is driven directly.
 */
@Testcontainers
class BedBackfillMigrationTest {

    private static final String[] OCCUPYING_STATUSES = {"PLANNED", "EXPECTED_TODAY", "CHECKED_IN"};

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private MigrationTestSupport migrations;

    @BeforeEach
    void setUp() throws Exception {
        migrations = new MigrationTestSupport(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        // Each test gets a schema wiped clean, including flyway_schema_history, so migrateTo()
        // always replays the full sequence from V1 rather than seeing a partially-migrated DB
        // left over from a previous test method.
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    @Test
    void shouldBackfillCleanly_whenHistoricalStaysDoNotOverlap() throws Exception {
        migrations.migrateTo("11");

        var agencyId = UUID.randomUUID();
        var workerId = UUID.randomUUID();
        var propertyId = UUID.randomUUID();
        var roomId = UUID.randomUUID();

        try (var connection = connect()) {
            seedAgency(connection, agencyId);
            seedWorker(connection, agencyId, workerId);
            seedProperty(connection, agencyId, propertyId);
            seedRoom(connection, agencyId, propertyId, roomId, 1); // -> 1 bed after backfill

            // Three PLANNED stays in the same 1-bed room, one after another -- never concurrent.
            seedStay(connection, agencyId, workerId, propertyId, roomId,
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10), "PLANNED", 0);
            seedStay(connection, agencyId, workerId, propertyId, roomId,
                    LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 20), "PLANNED", 1);
            seedStay(connection, agencyId, workerId, propertyId, roomId,
                    LocalDate.of(2026, 1, 20), LocalDate.of(2026, 1, 30), "PLANNED", 2);
        }

        migrations.migrateToLatest();

        try (var connection = connect()) {
            assertThat(countStaysWithoutBed(connection)).isZero();
            assertThat(countStaysAssignedToBedInAnotherRoom(connection)).isZero();
            assertThat(countOverlappingBedViolations(connection))
                    .as("harness sanity: non-overlapping historical stays sharing one bed must never violate BedOccupancyConstraint")
                    .isZero();
        }
    }

    @Test
    void shouldDocumentKnownBedOccupancyViolation_whenHistoricalStaysInterleaveAcrossRanks() throws Exception {
        migrations.migrateTo("11");

        var agencyId = UUID.randomUUID();
        var workerId = UUID.randomUUID();
        var propertyId = UUID.randomUUID();
        var roomId = UUID.randomUUID();

        try (var connection = connect()) {
            seedAgency(connection, agencyId);
            seedWorker(connection, agencyId, workerId);
            seedProperty(connection, agencyId, propertyId);
            seedRoom(connection, agencyId, propertyId, roomId, 2); // -> 2 beds after backfill

            // All three are valid under the old room-capacity(2) model -- at most 2 stays are
            // concurrently active at any point in time:
            //   Stay A (rank 0): days 1-30.
            //   Stay B (rank 1): days 5-10, concurrent with A only (2 <= capacity 2).
            //   Stay C (rank 2): days 15-20, concurrent with A only (B already ended).
            seedStay(connection, agencyId, workerId, propertyId, roomId,
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 30), "PLANNED", 0);
            seedStay(connection, agencyId, workerId, propertyId, roomId,
                    LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 10), "PLANNED", 1);
            seedStay(connection, agencyId, workerId, propertyId, roomId,
                    LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 20), "PLANNED", 2);
        }

        migrations.migrateToLatest();

        try (var connection = connect()) {
            // V12's bed assignment is row_number() OVER (PARTITION BY room_id ORDER BY
            // created_at, id) % bed_count -- creation-order rank modulo bed count, with zero
            // awareness of date-range overlap. Rank 0 (stay A) and rank 2 (stay C) both land on
            // bed (rn % 2 == 0), even though A and C genuinely overlap (days 15-20). This is a
            // real, already-shipped gap in V12__backfill_beds.sql: this test proves it exists,
            // it does not fix it. Never modify an existing migration, and a forward-fix migration
            // to repair real data is out of this test-only rollout's scope -- see
            // context/changes/testing-data-integrity-guardrails/research.md (Open Question 3)
            // and plan.md's Current State Analysis for the full rationale.
            assertThat(countOverlappingBedViolations(connection))
                    .as("documents the known, intentionally-unfixed V12 backfill gap -- see class Javadoc")
                    .isGreaterThan(0);
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void seedAgency(Connection connection, UUID agencyId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO agencies (id, name) VALUES (?, ?)")) {
            statement.setObject(1, agencyId);
            statement.setString(2, "Agency " + agencyId);
            statement.executeUpdate();
        }
    }

    private void seedWorker(Connection connection, UUID agencyId, UUID workerId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO workers (id, agency_id, internal_id, first_name, last_name, gender) "
                        + "VALUES (?, ?, ?, 'Test', 'Worker', 'MALE')")) {
            statement.setObject(1, workerId);
            statement.setObject(2, agencyId);
            statement.setString(3, "W-" + workerId);
            statement.executeUpdate();
        }
    }

    private void seedProperty(Connection connection, UUID agencyId, UUID propertyId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO properties (id, agency_id, name) VALUES (?, ?, ?)")) {
            statement.setObject(1, propertyId);
            statement.setObject(2, agencyId);
            statement.setString(3, "Property " + propertyId);
            statement.executeUpdate();
        }
    }

    /** {@code capacity} drives exactly how many beds V12 later generates for this room. */
    private void seedRoom(Connection connection, UUID agencyId, UUID propertyId, UUID roomId, int capacity)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO rooms (id, agency_id, property_id, room_number, capacity) VALUES (?, ?, ?, ?, ?)")) {
            statement.setObject(1, roomId);
            statement.setObject(2, agencyId);
            statement.setObject(3, propertyId);
            statement.setString(4, "Room " + roomId);
            statement.setInt(5, capacity);
            statement.executeUpdate();
        }
    }

    /**
     * {@code creationOrder} controls V12's ranking directly: {@code created_at} is set to a
     * distinctly-spaced timestamp per row so {@code ORDER BY created_at, id} is deterministic
     * regardless of insertion order or clock resolution.
     */
    private void seedStay(Connection connection, UUID agencyId, UUID workerId, UUID propertyId, UUID roomId,
            LocalDate dateFrom, LocalDate dateTo, String status, int creationOrder) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO stays (id, agency_id, worker_id, property_id, room_id, date_from, date_to, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, agencyId);
            statement.setObject(3, workerId);
            statement.setObject(4, propertyId);
            statement.setObject(5, roomId);
            statement.setDate(6, Date.valueOf(dateFrom));
            statement.setDate(7, Date.valueOf(dateTo));
            statement.setString(8, status);
            var createdAt = OffsetDateTime.parse("2025-01-01T00:00:00Z").plusMinutes(creationOrder);
            statement.setTimestamp(9, Timestamp.from(createdAt.toInstant()));
            statement.executeUpdate();
        }
    }

    private long countStaysWithoutBed(Connection connection) throws Exception {
        return scalarCount(connection, "SELECT count(*) FROM stays WHERE bed_id IS NULL");
    }

    private long countStaysAssignedToBedInAnotherRoom(Connection connection) throws Exception {
        return scalarCount(connection,
                "SELECT count(*) FROM stays s JOIN beds b ON b.id = s.bed_id WHERE b.room_id <> s.room_id");
    }

    /**
     * Mirrors BedOccupancyConstraint's own semantics (com.beduno.stay.constraint.impl) as a raw
     * SQL self-join, since that class needs a full ConstraintContext this dedicated-container
     * test does not boot: two distinct stays sharing a bed, both in an occupying status, whose
     * [date_from, date_to) ranges overlap (an open date_to is treated as unbounded).
     */
    private long countOverlappingBedViolations(Connection connection) throws Exception {
        var occupying = "'" + String.join("','", OCCUPYING_STATUSES) + "'";
        return scalarCount(connection, """
                SELECT count(*)
                FROM stays s1
                JOIN stays s2 ON s1.bed_id = s2.bed_id AND s1.id < s2.id
                WHERE s1.status IN (%s) AND s2.status IN (%s)
                  AND s1.date_from < COALESCE(s2.date_to, 'infinity'::date)
                  AND s2.date_from < COALESCE(s1.date_to, 'infinity'::date)
                """.formatted(occupying, occupying));
    }

    private long scalarCount(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
