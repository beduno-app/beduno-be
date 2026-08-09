package com.beduno.property;

import com.beduno.IntegrationTestBase;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.room.GenderRule;
import com.beduno.room.dto.CreateRoomRequest;
import com.beduno.room.dto.RoomResponse;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rooms.property_id and stays.property_id / stays.room_id are RESTRICT foreign
 * keys, so deleting a referenced row used to surface as an opaque 500 from the
 * database. These cover the 409 guards that replaced that.
 */
class DeletionGuardIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
    }

    @Nested
    class PropertyDeletion {

        @Test
        void shouldReturnConflict_whenPropertyStillHasRooms() {
            var property = createProperty();
            createRoom(property.id());

            var response = deleteProperty(property.id());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).contains("error.property.has_rooms");
        }

        @Test
        void shouldDeleteProperty_whenNothingReferencesIt() {
            var property = createProperty();

            var response = deleteProperty(property.id());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }
    }

    @Nested
    class RoomDeletion {

        @Test
        void shouldReturnConflict_whenStaysReferenceRoom() {
            var property = createProperty();
            var room = createRoom(property.id());
            createStay(property.id(), room.id());

            var response = deleteRoom(property.id(), room.id());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).contains("error.room.has_stays");
        }

        @Test
        void shouldReturnConflict_whenOnlyTerminalStaysReferenceRoom() {
            var property = createProperty();
            var room = createRoom(property.id());
            var stay = createStay(property.id(), room.id());
            // A cancelled stay still holds the foreign key, so it must still block.
            jdbcTemplate.update("UPDATE stays SET status = 'CANCELLED' WHERE id = ?", stay.id());

            var response = deleteRoom(property.id(), room.id());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).contains("error.room.has_stays");
        }

        @Test
        void shouldDeleteRoom_whenNoStaysReferenceIt() {
            var property = createProperty();
            var room = createRoom(property.id());

            var response = deleteRoom(property.id(), room.id());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }
    }

    private org.springframework.http.ResponseEntity<String> deleteProperty(UUID propertyId) {
        return restTemplate.exchange(
                "/api/v1/properties/" + propertyId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);
    }

    private org.springframework.http.ResponseEntity<String> deleteRoom(UUID propertyId, UUID roomId) {
        return restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms/" + roomId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);
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
                null, 4, 0, GenderRule.ANY, null);
        return restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                RoomResponse.class).getBody();
    }

    private StayResponse createStay(UUID propertyId, UUID roomId) {
        var worker = createWorker();
        var request = new CreateStayRequest(
                worker.id(), propertyId, roomId,
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(5), null, null);
        return restTemplate.exchange(
                "/api/v1/stays", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                StayResponse.class).getBody();
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
