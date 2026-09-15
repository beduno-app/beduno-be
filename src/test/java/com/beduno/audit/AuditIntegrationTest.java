package com.beduno.audit;

import com.beduno.IntegrationTestBase;
import com.beduno.audit.dto.AuditEventResponse;
import com.beduno.bed.dto.BedResponse;
import com.beduno.bed.dto.BulkGenerateBedsRequest;
import com.beduno.common.model.PageResponse;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.room.GenderRule;
import com.beduno.room.dto.CreateRoomRequest;
import com.beduno.room.dto.RoomResponse;
import com.beduno.stay.dto.CheckInRequest;
import com.beduno.stay.dto.CreateStayRequest;
import com.beduno.stay.dto.StayResponse;
import com.beduno.user.Role;
import com.beduno.user.dto.CreateUserRequest;
import com.beduno.user.dto.UserResponse;
import com.beduno.worker.Gender;
import com.beduno.worker.dto.CreateWorkerRequest;
import com.beduno.worker.dto.WorkerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit trail is the only record of who moved whom and when, and nothing asserted its content:
 * the existing tests only checked that {@code GET /api/v1/audit} returns 200 and that seeding
 * writes some rows. A dropped {@code auditService.log} call in a new write path, or a snapshot
 * taken at the wrong moment, shipped silently.
 */
class AuditIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        ensureAgencyExists(OTHER_AGENCY_ID);
        ensureUserExists(DEFAULT_USER_ID, DEFAULT_AGENCY_ID);
    }

    @Nested
    class Content {

        @Test
        void shouldRecordActorAndPreviousStatus_whenStayIsCheckedIn() {
            var property = createProperty();
            var room = createRoom(property.id(), 4);
            var stay = plannedStay(property.id(), room.id());
            forceExpectedToday(stay.id());

            restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/check-in", HttpMethod.POST,
                    new HttpEntity<>(new CheckInRequest(null, null, null),
                            authHeadersAt(Role.FRONT_DESK, property.id())),
                    StayResponse.class
            );

            var events = auditFor(AuditEntityType.STAY, stay.id());
            var checkIn = events.stream()
                    .filter(e -> e.action() == AuditAction.CHECKED_IN).findFirst().orElseThrow();

            assertThat(checkIn.actorUserId()).isEqualTo(DEFAULT_USER_ID);
            assertThat(checkIn.previousState()).containsEntry("status", "EXPECTED_TODAY");
            assertThat(checkIn.newState()).containsEntry("status", "CHECKED_IN");
        }

        /**
         * The "previous" snapshot used to be taken after the room and bed had already been
         * written, so a check-in that redirected the worker recorded the new room as the old one
         * and the planned room was unrecoverable from the trail.
         */
        @Test
        void shouldRecordPlannedRoom_whenCheckInOverridesTheRoom() {
            var property = createProperty();
            var room = createRoom(property.id(), 4);
            var altRoom = createRoom(property.id(), 4);
            var stay = plannedStay(property.id(), room.id());
            forceExpectedToday(stay.id());

            restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/check-in", HttpMethod.POST,
                    new HttpEntity<>(new CheckInRequest(altRoom.id(), null, null),
                            authHeadersAt(Role.PROPERTY_ADMIN, property.id())),
                    StayResponse.class
            );

            var checkIn = auditFor(AuditEntityType.STAY, stay.id()).stream()
                    .filter(e -> e.action() == AuditAction.CHECKED_IN).findFirst().orElseThrow();

            assertThat(checkIn.previousState()).containsEntry("roomId", room.id().toString());
            assertThat(checkIn.newState()).containsEntry("roomId", altRoom.id().toString());
        }

        @Test
        void shouldFilterByEntityId_whenTwoStaysExist() {
            var property = createProperty();
            var room = createRoom(property.id(), 4);
            var first = plannedStay(property.id(), room.id());
            var second = plannedStay(property.id(), room.id());

            var events = auditFor(AuditEntityType.STAY, first.id());

            assertThat(events).isNotEmpty();
            assertThat(events).extracting(AuditEventResponse::entityId).containsOnly(first.id());
            assertThat(events).extracting(AuditEventResponse::entityId).doesNotContain(second.id());
        }

        @Test
        void shouldReturnEvent_whenFetchedById() {
            var property = createProperty();
            var room = createRoom(property.id(), 4);
            var stay = plannedStay(property.id(), room.id());
            var checkIn = auditFor(AuditEntityType.STAY, stay.id()).stream()
                    .filter(e -> e.action() == AuditAction.CREATED).findFirst().orElseThrow();

            var response = restTemplate.exchange(
                    "/api/v1/audit/" + checkIn.id(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), AuditEventResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().id()).isEqualTo(checkIn.id());
            assertThat(response.getBody().entityId()).isEqualTo(stay.id());
        }

        @Test
        void shouldReturnNotFound_whenEventDoesNotExist() {
            var response = restTemplate.exchange(
                    "/api/v1/audit/" + UUID.randomUUID(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class Access {

        @Test
        void shouldReturnForbidden_whenCallerIsFrontDesk() {
            var response = restTemplate.exchange(
                    "/api/v1/audit", HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.FRONT_DESK)), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldReturnForbidden_whenFetchingByIdAsFrontDesk() {
            var property = createProperty();
            var room = createRoom(property.id(), 4);
            var stay = plannedStay(property.id(), room.id());
            var event = auditFor(AuditEntityType.STAY, stay.id()).getFirst();

            var response = restTemplate.exchange(
                    "/api/v1/audit/" + event.id(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.FRONT_DESK)), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        /**
         * Same back-door concern as the list endpoint, but for the by-id lookup: a planner who
         * knows (or guesses) a USER event's id must not be able to read it directly.
         */
        @Test
        void shouldReturnNotFound_whenFetchingUserEventByIdAsPlanner() {
            var created = createUser();
            var event = audit("?entityType=USER&entityId=" + created.id(), Role.AGENCY_ADMIN).getFirst();

            var response = restTemplate.exchange(
                    "/api/v1/audit/" + event.id(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.AGENCY_PLANNER)), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void shouldReturnNotFound_whenFetchingEventFromOtherAgency() {
            var property = createProperty();
            var room = createRoom(property.id(), 4);
            var stay = plannedStay(property.id(), room.id());
            var event = auditFor(AuditEntityType.STAY, stay.id()).getFirst();

            var response = restTemplate.exchange(
                    "/api/v1/audit/" + event.id(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID)), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        /**
         * /api/v1/users is restricted to AGENCY_ADMIN because it exposes every account's email and
         * role. USER audit events carry the same fields in their snapshots, and this endpoint is
         * open to AGENCY_PLANNER -- so the roster was readable through the audit trail.
         */
        @Test
        void shouldNotReturnUserEvents_whenCallerIsPlanner() {
            var created = createUser();

            var plannerView = audit("?entityType=USER&size=200", Role.AGENCY_PLANNER);
            assertThat(plannerView).isEmpty();

            var unfilteredPlannerView = audit("?size=200", Role.AGENCY_PLANNER);
            assertThat(unfilteredPlannerView)
                    .extracting(AuditEventResponse::entityType)
                    .doesNotContain(AuditEntityType.USER);

            var adminView = audit("?entityType=USER&entityId=" + created.id(), Role.AGENCY_ADMIN);
            assertThat(adminView).isNotEmpty();
        }

        @Test
        void shouldNotReturnEventsFromOtherAgency() {
            var property = createProperty();
            var room = createRoom(property.id(), 4);
            var stay = plannedStay(property.id(), room.id());

            var otherAgencyView = audit("?entityType=STAY&size=200", Role.AGENCY_ADMIN, OTHER_AGENCY_ID);

            assertThat(otherAgencyView).extracting(AuditEventResponse::entityId).doesNotContain(stay.id());
        }

        @Test
        void shouldReturnRecentEvents_whenEntityHasSomeInThisAgency() {
            var property = createProperty();
            var room = createRoom(property.id(), 4);
            var stay = plannedStay(property.id(), room.id());

            var response = restTemplate.exchange(
                    "/api/v1/audit/recent?entityId=" + stay.id(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)),
                    new ParameterizedTypeReference<List<AuditEventResponse>>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).extracting(AuditEventResponse::entityId).containsOnly(stay.id());
        }

        @Test
        void shouldNotReturnRecentEvents_whenEntityBelongsToOtherAgency() {
            var property = createProperty();
            var room = createRoom(property.id(), 4);
            var stay = plannedStay(property.id(), room.id());

            var response = restTemplate.exchange(
                    "/api/v1/audit/recent?entityId=" + stay.id(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID)),
                    new ParameterizedTypeReference<List<AuditEventResponse>>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEmpty();
        }

        @Test
        void shouldNotReturnRecentUserEvents_whenCallerIsPlanner() {
            var created = createUser();

            var response = restTemplate.exchange(
                    "/api/v1/audit/recent?entityId=" + created.id(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.AGENCY_PLANNER)),
                    new ParameterizedTypeReference<List<AuditEventResponse>>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEmpty();
        }
    }

    private List<AuditEventResponse> auditFor(AuditEntityType entityType, UUID entityId) {
        return audit("?entityType=" + entityType + "&entityId=" + entityId, Role.AGENCY_ADMIN);
    }

    private List<AuditEventResponse> audit(String query, Role role) {
        return audit(query, role, DEFAULT_AGENCY_ID);
    }

    private List<AuditEventResponse> audit(String query, Role role, UUID agencyId) {
        var response = restTemplate.exchange(
                "/api/v1/audit" + query, HttpMethod.GET,
                new HttpEntity<>(authHeaders(role, agencyId)),
                new ParameterizedTypeReference<PageResponse<AuditEventResponse>>() {}
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().content();
    }

    private UserResponse createUser() {
        var request = new CreateUserRequest("audit-" + UUID.randomUUID() + "@agency.pl",
                "supersecretpassword123", "Test", "User", Role.FRONT_DESK, "EN", List.of());
        return restTemplate.exchange(
                "/api/v1/users", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)), UserResponse.class
        ).getBody();
    }

    private PropertyResponse createProperty() {
        var request = new CreatePropertyRequest("Property " + UUID.randomUUID(),
                "Street 1", "Warsaw", null);
        return restTemplate.exchange(
                "/api/v1/properties", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)), PropertyResponse.class
        ).getBody();
    }

    private RoomResponse createRoom(UUID propertyId, int bedCount) {
        var request = new CreateRoomRequest("R-" + UUID.randomUUID().toString().substring(0, 8),
                null, GenderRule.MIXED, null);
        var room = restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)), RoomResponse.class
        ).getBody();
        restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms/" + room.id() + "/beds/bulk-generate",
                HttpMethod.POST,
                new HttpEntity<>(new BulkGenerateBedsRequest(bedCount), authHeaders(Role.AGENCY_ADMIN)),
                new ParameterizedTypeReference<List<BedResponse>>() {}
        );
        return room;
    }

    private WorkerResponse createWorker() {
        var request = new CreateWorkerRequest("W-" + UUID.randomUUID().toString().substring(0, 8),
                "First", "Last", Gender.MALE, null, null, null, null, null, null);
        return restTemplate.exchange(
                "/api/v1/workers", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)), WorkerResponse.class
        ).getBody();
    }

    private StayResponse plannedStay(UUID propertyId, UUID roomId) {
        var worker = createWorker();
        var request = new CreateStayRequest(worker.id(), propertyId, roomId, null,
                LocalDate.now(), LocalDate.now().plusDays(7), null, null);
        return restTemplate.exchange(
                "/api/v1/stays", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)), StayResponse.class
        ).getBody();
    }

    private void forceExpectedToday(UUID stayId) {
        jdbcTemplate.update("UPDATE stays SET status = 'EXPECTED_TODAY' WHERE id = ?", stayId);
    }
}
