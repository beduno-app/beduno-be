package com.beduno.stay;

import com.beduno.IntegrationTestBase;
import com.beduno.audit.AuditEntityType;
import com.beduno.audit.dto.AuditEventResponse;
import com.beduno.common.model.PageResponse;
import com.beduno.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PLANNED -> EXPECTED_TODAY sweep is the only way a stay ever becomes checkable in, and it was
 * the one piece of the system with no test at all: every test that needed an EXPECTED_TODAY stay
 * wrote the status with raw SQL and skipped the transition entirely. It is also the single
 * sanctioned cross-tenant query, so a well-meaning "filter this by TenantContext for consistency"
 * would have thrown on the scheduler thread -- which has no tenant -- and silently stopped every
 * check-in in production, with the suite still green.
 *
 * <p>Rows are seeded with JDBC rather than through the API because the API now promotes a due stay
 * on creation; this has to start from stays that are genuinely still PLANNED.
 */
class StaySchedulerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private StayService stayService;

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        ensureAgencyExists(OTHER_AGENCY_ID);
    }

    @Test
    void shouldPromoteDueStaysAcrossAllAgencies_whenSweepRuns() {
        var today = LocalDate.now();
        var dueToday = seedPlannedStay(DEFAULT_AGENCY_ID, today);
        var dueInOtherAgency = seedPlannedStay(OTHER_AGENCY_ID, today);
        var notDueYet = seedPlannedStay(DEFAULT_AGENCY_ID, today.plusDays(3));

        var promoted = stayService.transitionPlannedToExpectedToday(today);

        assertThat(promoted).isGreaterThanOrEqualTo(2);
        assertThat(statusOf(dueToday)).isEqualTo("EXPECTED_TODAY");
        assertThat(statusOf(dueInOtherAgency)).isEqualTo("EXPECTED_TODAY");
        assertThat(statusOf(notDueYet)).isEqualTo("PLANNED");
    }

    /**
     * The sweep used to match {@code dateFrom = today} exactly. The deployment is a single
     * instance that gets stopped when the API is idle, so a missed 06:00 run left the stay PLANNED
     * forever -- and PLANNED has no transition to CHECKED_IN, so check-in returned 409 with no way
     * out but a database update.
     */
    @Test
    void shouldCatchUpOnOverdueStays_whenASweepWasMissed() {
        var today = LocalDate.now();
        var overdue = seedPlannedStay(DEFAULT_AGENCY_ID, today.minusDays(4));

        stayService.transitionPlannedToExpectedToday(today);

        assertThat(statusOf(overdue)).isEqualTo("EXPECTED_TODAY");
    }

    @Test
    void shouldLeaveTerminalStaysAlone_whenSweepRuns() {
        var today = LocalDate.now();
        var cancelled = seedPlannedStay(DEFAULT_AGENCY_ID, today);
        jdbcTemplate.update("UPDATE stays SET status = 'CANCELLED' WHERE id = ?", cancelled);

        stayService.transitionPlannedToExpectedToday(today);

        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
    }

    @Test
    void shouldBeIdempotent_whenSweepRunsTwice() {
        var today = LocalDate.now();
        seedPlannedStay(DEFAULT_AGENCY_ID, today);

        stayService.transitionPlannedToExpectedToday(today);
        var secondRun = stayService.transitionPlannedToExpectedToday(today);

        assertThat(secondRun).isZero();
    }

    /** Every other status change on a stay is audited; this one was not. */
    @Test
    void shouldRecordAnAuditEvent_whenStayIsPromoted() {
        var today = LocalDate.now();
        var stay = seedPlannedStay(DEFAULT_AGENCY_ID, today);

        stayService.transitionPlannedToExpectedToday(today);

        var events = restTemplate.exchange(
                "/api/v1/audit?entityType=STAY&entityId=" + stay, HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID)),
                new ParameterizedTypeReference<PageResponse<AuditEventResponse>>() {}
        ).getBody().content();

        assertThat(events).isNotEmpty();
        var promotion = events.stream()
                .filter(e -> "scheduler".equals(e.reason())).findFirst().orElseThrow();
        assertThat(promotion.entityType()).isEqualTo(AuditEntityType.STAY);
        assertThat(promotion.actorUserId()).isNull();
        assertThat(promotion.previousState()).containsEntry("status", "PLANNED");
        assertThat(promotion.newState()).containsEntry("status", "EXPECTED_TODAY");
    }

    /**
     * Inserts a stay directly, bypassing the constraint engine. Each call gets its own property,
     * room, bed and worker so that concurrent rows never contend for a bed.
     */
    private UUID seedPlannedStay(UUID agencyId, LocalDate dateFrom) {
        var propertyId = UUID.randomUUID();
        var roomId = UUID.randomUUID();
        var bedId = UUID.randomUUID();
        var workerId = UUID.randomUUID();
        var stayId = UUID.randomUUID();
        var suffix = stayId.toString().substring(0, 8);

        jdbcTemplate.update("INSERT INTO properties (id, agency_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                propertyId, agencyId, "Sweep property " + suffix);
        jdbcTemplate.update(
                "INSERT INTO rooms (id, agency_id, property_id, room_number, gender_rule, status) "
                        + "VALUES (?, ?, ?, ?, 'MIXED', 'ACTIVE')",
                roomId, agencyId, propertyId, "R-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO beds (id, agency_id, room_id, label, status) VALUES (?, ?, ?, '1', 'ACTIVE')",
                bedId, agencyId, roomId);
        jdbcTemplate.update(
                "INSERT INTO workers (id, agency_id, internal_id, first_name, last_name, gender, status) "
                        + "VALUES (?, ?, ?, 'Sweep', 'Worker', 'MALE', 'ACTIVE')",
                workerId, agencyId, "W-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO stays (id, agency_id, worker_id, property_id, room_id, bed_id, "
                        + "bed_auto_assigned, date_from, date_to, status, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, true, ?, ?, 'PLANNED', 0)",
                stayId, agencyId, workerId, propertyId, roomId, bedId, dateFrom, dateFrom.plusDays(30));
        return stayId;
    }

    private String statusOf(UUID stayId) {
        return jdbcTemplate.queryForObject("SELECT status FROM stays WHERE id = ?", String.class, stayId);
    }
}
