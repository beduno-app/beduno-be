package com.beduno.stay;

import com.beduno.IntegrationTestBase;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.room.GenderRule;
import com.beduno.room.dto.CreateRoomRequest;
import com.beduno.room.dto.RoomResponse;
import com.beduno.stay.dto.CreateStayRequest;
import com.beduno.stay.dto.MoveRequest;
import com.beduno.stay.dto.NoShowRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StayGuardIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        ensureUserExists(DEFAULT_USER_ID, DEFAULT_AGENCY_ID);
    }

    @Nested
    class MoveOnFinalDay {

        @Test
        void shouldReturnConflict_whenStayEndsToday() {
            var property = createProperty();
            var room = createRoom(property.id());
            var targetRoom = createRoom(property.id());
            // The replacement stay would be [today, today), which chk_stays_dates rejects.
            var stay = checkedInStay(property.id(), room.id(),
                    LocalDate.now().minusDays(3), LocalDate.now());

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/move", HttpMethod.POST,
                    new HttpEntity<>(new MoveRequest(targetRoom.id(), null),
                            authHeaders(Role.PROPERTY_ADMIN)),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).contains("error.stay.cannot_move_on_last_day");
        }

        @Test
        void shouldMove_whenStayStillHasNightsRemaining() {
            var property = createProperty();
            var room = createRoom(property.id());
            var targetRoom = createRoom(property.id());
            var stay = checkedInStay(property.id(), room.id(),
                    LocalDate.now().minusDays(1), LocalDate.now().plusDays(3));

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/move", HttpMethod.POST,
                    new HttpEntity<>(new MoveRequest(targetRoom.id(), null),
                            authHeaders(Role.PROPERTY_ADMIN)),
                    StayResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().roomId()).isEqualTo(targetRoom.id());
        }
    }

    @Nested
    class NoShowReason {

        @Test
        void shouldPreserveNotes_whenMarkedNoShow() {
            var property = createProperty();
            var room = createRoom(property.id());
            var worker = createWorker();
            var request = new CreateStayRequest(
                    worker.id(), property.id(), room.id(),
                    LocalDate.now(), LocalDate.now().plusDays(4),
                    null, "Arrives by night bus");
            var stay = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                    StayResponse.class).getBody();
            jdbcTemplate.update("UPDATE stays SET status = 'EXPECTED_TODAY' WHERE id = ?", stay.id());

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/no-show", HttpMethod.POST,
                    new HttpEntity<>(new NoShowRequest("TRANSPORT_DELAY"),
                            authHeaders(Role.FRONT_DESK)),
                    StayResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().status()).isEqualTo(StayStatus.NO_SHOW);
            assertThat(response.getBody().noShowReason()).isEqualTo("TRANSPORT_DELAY");
            assertThat(response.getBody().notes()).isEqualTo("Arrives by night bus");
        }

        @Test
        void shouldReturnBadRequest_whenReasonTagExceedsColumnLength() {
            var property = createProperty();
            var room = createRoom(property.id());
            var stay = plannedStay(property.id(), room.id());
            jdbcTemplate.update("UPDATE stays SET status = 'EXPECTED_TODAY' WHERE id = ?", stay.id());

            // stays.no_show_reason is VARCHAR(100); without @Size this was a 500.
            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/no-show", HttpMethod.POST,
                    new HttpEntity<>(new NoShowRequest("X".repeat(101)),
                            authHeaders(Role.FRONT_DESK)),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    private StayResponse plannedStay(UUID propertyId, UUID roomId) {
        var worker = createWorker();
        var request = new CreateStayRequest(
                worker.id(), propertyId, roomId,
                LocalDate.now(), LocalDate.now().plusDays(4), null, null);
        return restTemplate.exchange(
                "/api/v1/stays", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                StayResponse.class).getBody();
    }

    private StayResponse checkedInStay(UUID propertyId, UUID roomId,
                                       LocalDate dateFrom, LocalDate dateTo) {
        var worker = createWorker();
        var request = new CreateStayRequest(
                worker.id(), propertyId, roomId, dateFrom, dateTo, null, null);
        var stay = restTemplate.exchange(
                "/api/v1/stays", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                StayResponse.class).getBody();
        jdbcTemplate.update(
                "UPDATE stays SET status = 'CHECKED_IN', confirmed_by_user_id = ? WHERE id = ?",
                DEFAULT_USER_ID, stay.id());
        return restTemplate.exchange(
                "/api/v1/stays/" + stay.id(), HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)),
                StayResponse.class).getBody();
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
                "Room-" + UUID.randomUUID().toString().substring(0, 8),
                null, GenderRule.MIXED, null);
        return restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                RoomResponse.class).getBody();
    }

    private WorkerResponse createWorker() {
        var request = new CreateWorkerRequest(
                "W-" + UUID.randomUUID().toString().substring(0, 8),
                "Test", "Worker", Gender.MALE,
                null, null, null, null, null, null);
        return restTemplate.exchange(
                "/api/v1/workers", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                WorkerResponse.class).getBody();
    }
}
