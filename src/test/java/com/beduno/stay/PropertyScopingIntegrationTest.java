package com.beduno.stay;

import com.beduno.IntegrationTestBase;
import com.beduno.bed.dto.BulkGenerateBedsRequest;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.room.GenderRule;
import com.beduno.room.dto.CreateRoomRequest;
import com.beduno.room.dto.RoomResponse;
import com.beduno.stay.dto.BulkAssignRequest;
import com.beduno.stay.dto.BulkAssignRequest.Assignment;
import com.beduno.stay.dto.CheckOutRequest;
import com.beduno.stay.dto.CreateStayRequest;
import com.beduno.stay.dto.StayResponse;
import com.beduno.user.Role;
import com.beduno.worker.Gender;
import com.beduno.worker.dto.CreateWorkerRequest;
import com.beduno.worker.dto.WorkerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROPERTY_ADMIN and FRONT_DESK are scoped to their {@code assignedPropertyIds}. That scoping was
 * enforced in exactly three controllers -- property, room and bed -- while every stay operation,
 * every occupancy view, the inspection endpoints and all three CSV exports were agency-wide. A
 * front-desk token for one property could check a worker in at another, read its occupants, and
 * export its roster.
 *
 * <p>These assert the rule across the paths that were open, and the paired positive case for each,
 * since a scoping bug that denies everything is as broken as one that allows everything.
 */
class PropertyScopingIntegrationTest extends IntegrationTestBase {

    private PropertyResponse assigned;
    private PropertyResponse foreign;
    private RoomResponse foreignRoom;

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        ensureUserExists(DEFAULT_USER_ID, DEFAULT_AGENCY_ID);
        assigned = createProperty();
        foreign = createProperty();
        foreignRoom = createRoom(foreign.id());
    }

    @Nested
    class StayOperations {

        @Test
        void shouldReturnForbidden_whenReadingAStayAtAnotherProperty() {
            var stay = plannedStay(foreign.id(), foreignRoom.id());

            var response = get("/api/v1/stays/" + stay.id(), Role.FRONT_DESK);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).contains("error.property.access_denied");
        }

        @Test
        void shouldReturnForbidden_whenCheckingOutAStayAtAnotherProperty() {
            var stay = plannedStay(foreign.id(), foreignRoom.id());
            jdbcTemplate.update("UPDATE stays SET status = 'CHECKED_IN' WHERE id = ?", stay.id());

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/check-out", HttpMethod.POST,
                    new HttpEntity<>(new CheckOutRequest(null), authHeadersAt(Role.FRONT_DESK, assigned.id())),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldReturnForbidden_whenCreatingAStayAtAnotherProperty() {
            var worker = createWorker();
            var request = new CreateStayRequest(worker.id(), foreign.id(), foreignRoom.id(), null,
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(8), null, null);

            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeadersAt(Role.PROPERTY_ADMIN, assigned.id())),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldReportPerItemError_whenBulkAssigningToAnotherProperty() {
            // Bulk assign reports business failures per item rather than failing the request, so
            // the scope denial has to show up there rather than as a 403 for the whole batch.
            var worker = createWorker();
            var request = new BulkAssignRequest(List.of(new Assignment(
                    worker.id(), foreign.id(), foreignRoom.id(), null,
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(8), null)));

            var response = restTemplate.exchange(
                    "/api/v1/stays/bulk-assign", HttpMethod.POST,
                    new HttpEntity<>(request, authHeadersAt(Role.PROPERTY_ADMIN, assigned.id())),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("error.property.access_denied");
            assertThat(response.getBody()).contains("\"created\":0");
        }

        @Test
        void shouldAllow_whenTheStayIsAtTheAssignedProperty() {
            var room = createRoom(assigned.id());
            var stay = plannedStay(assigned.id(), room.id());

            var response = get("/api/v1/stays/" + stay.id(), Role.FRONT_DESK);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void shouldAllow_whenCallerIsAgencyWide() {
            // AGENCY_ADMIN and AGENCY_PLANNER are agency-wide by definition; scoping must not
            // narrow them, or an administrator loses access to their own agency.
            var stay = plannedStay(foreign.id(), foreignRoom.id());

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    class OccupancyAndExports {

        @Test
        void shouldReturnForbidden_whenReadingAnotherPropertysOccupancy() {
            assertThat(get("/api/v1/properties/" + foreign.id() + "/occupancy", Role.FRONT_DESK)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldReturnForbidden_whenReadingAnotherPropertysExceptions() {
            assertThat(get("/api/v1/properties/" + foreign.id() + "/exceptions", Role.PROPERTY_ADMIN)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldReturnForbidden_whenReadingAnotherPropertysInspectionRoster() {
            assertThat(get("/api/v1/properties/" + foreign.id() + "/inspection", Role.PROPERTY_ADMIN)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldReturnForbidden_whenExportingAnotherPropertysOccupancy() {
            assertThat(get("/api/v1/properties/" + foreign.id() + "/occupancy/export", Role.PROPERTY_ADMIN)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldReturnForbidden_whenExportingAnotherPropertysArrivals() {
            assertThat(get("/api/v1/properties/" + foreign.id() + "/arrivals/export", Role.FRONT_DESK)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldReturnForbidden_whenReadingAnotherPropertysArrivals() {
            assertThat(get("/api/v1/stays/arrivals?propertyId=" + foreign.id(), Role.FRONT_DESK)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldAllow_whenReadingTheAssignedPropertysOccupancy() {
            assertThat(get("/api/v1/properties/" + assigned.id() + "/occupancy", Role.FRONT_DESK)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    private org.springframework.http.ResponseEntity<String> get(String url, Role role) {
        return restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(authHeadersAt(role, assigned.id())), String.class);
    }

    private PropertyResponse createProperty() {
        var request = new CreatePropertyRequest(
                "Property-" + UUID.randomUUID().toString().substring(0, 8), null, null, null);
        return restTemplate.exchange(
                "/api/v1/properties", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                PropertyResponse.class).getBody();
    }

    private RoomResponse createRoom(UUID propertyId) {
        var request = new CreateRoomRequest(
                "Room-" + UUID.randomUUID().toString().substring(0, 8), null, GenderRule.MIXED, null);
        var room = restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                RoomResponse.class).getBody();
        restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms/" + room.id() + "/beds/bulk-generate",
                HttpMethod.POST,
                new HttpEntity<>(new BulkGenerateBedsRequest(4), authHeaders(Role.AGENCY_ADMIN)),
                String.class);
        return room;
    }

    private WorkerResponse createWorker() {
        var request = new CreateWorkerRequest("W-" + UUID.randomUUID().toString().substring(0, 8),
                "Test", "Worker", Gender.MALE, null, null, null, null, null, null);
        return restTemplate.exchange(
                "/api/v1/workers", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                WorkerResponse.class).getBody();
    }

    private StayResponse plannedStay(UUID propertyId, UUID roomId) {
        var worker = createWorker();
        var request = new CreateStayRequest(worker.id(), propertyId, roomId, null,
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(8), null, null);
        return restTemplate.exchange(
                "/api/v1/stays", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                StayResponse.class).getBody();
    }
}
