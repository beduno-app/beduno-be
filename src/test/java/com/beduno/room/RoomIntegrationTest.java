package com.beduno.room;

import com.beduno.IntegrationTestBase;
import com.beduno.common.model.PageResponse;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.room.dto.CreateRoomRequest;
import com.beduno.room.dto.RoomResponse;
import com.beduno.room.dto.UpdateRoomRequest;
import com.beduno.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoomIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        ensureAgencyExists(OTHER_AGENCY_ID);
    }

    @Nested
    class Create {

        @Test
        void shouldCreateRoom_whenAgencyAdmin() {
            var property = createProperty(DEFAULT_AGENCY_ID);
            var request = new CreateRoomRequest("Room 101", 1, 4, 0, GenderRule.MIXED, null);
            var headers = authHeaders(Role.AGENCY_ADMIN);
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/rooms", HttpMethod.POST,
                    new HttpEntity<>(request, headers), RoomResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().roomNumber()).isEqualTo("Room 101");
            assertThat(response.getBody().capacity()).isEqualTo(4);
            assertThat(response.getBody().availableSpots()).isEqualTo(4);
        }

        @Test
        void shouldCreateRoom_whenPropertyAdminWithAccess() {
            var property = createProperty(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.PROPERTY_ADMIN, DEFAULT_AGENCY_ID, new UUID[]{property.id()});
            var request = new CreateRoomRequest("Room PA", 1, 2, 0, GenderRule.FEMALE_ONLY, null);
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/rooms", HttpMethod.POST,
                    new HttpEntity<>(request, headers), RoomResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        void shouldRejectCreate_whenPropertyAdminWithoutAccess() {
            var property = createProperty(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.PROPERTY_ADMIN, DEFAULT_AGENCY_ID, new UUID[]{UUID.randomUUID()});
            var request = new CreateRoomRequest("Room Nope", 1, 2, 0, null, null);
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/rooms", HttpMethod.POST,
                    new HttpEntity<>(request, headers), String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldRejectDuplicateRoomName() {
            var property = createProperty(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.AGENCY_ADMIN);
            var request = new CreateRoomRequest("Duplicate", 1, 2, 0, GenderRule.MIXED, null);

            restTemplate.exchange("/api/v1/properties/" + property.id() + "/rooms",
                    HttpMethod.POST, new HttpEntity<>(request, headers), RoomResponse.class);

            var response = restTemplate.exchange("/api/v1/properties/" + property.id() + "/rooms",
                    HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void shouldRejectBlockedSpotsExceedingCapacity() {
            var property = createProperty(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.AGENCY_ADMIN);
            var request = new CreateRoomRequest("Bad Room", 1, 2, 5, GenderRule.MIXED, null);
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/rooms", HttpMethod.POST,
                    new HttpEntity<>(request, headers), String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    class Read {

        @Test
        void shouldReturnRoomById() {
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id());
            var headers = authHeaders(Role.FRONT_DESK);

            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/rooms/" + room.id(),
                    HttpMethod.GET, new HttpEntity<>(headers), RoomResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().id()).isEqualTo(room.id());
        }

        @Test
        void shouldReturnPagedRoomList() {
            var property = createProperty(DEFAULT_AGENCY_ID);
            createRoom(DEFAULT_AGENCY_ID, property.id());
            var headers = authHeaders(Role.AGENCY_PLANNER);

            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/rooms?page=0&size=10",
                    HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<PageResponse<RoomResponse>>() {}
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().content()).isNotEmpty();
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void shouldNotAccessRoomFromOtherAgency() {
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id());

            var headers = authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID);
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/rooms/" + room.id(),
                    HttpMethod.GET, new HttpEntity<>(headers), String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void shouldNotListRoomsFromOtherAgencyProperty() {
            var property = createProperty(DEFAULT_AGENCY_ID);
            createRoom(DEFAULT_AGENCY_ID, property.id());

            var headers = authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID);
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/rooms",
                    HttpMethod.GET, new HttpEntity<>(headers), String.class
            );
            // Property not found for other agency
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class Update {

        @Test
        void shouldUpdateRoom() {
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id());
            var headers = authHeaders(Role.AGENCY_ADMIN);
            var updateRequest = new UpdateRoomRequest(
                    room.roomNumber(), 2, 6, 1, GenderRule.MALE_ONLY, RoomStatus.ACTIVE, "updated"
            );
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/rooms/" + room.id(),
                    HttpMethod.PUT, new HttpEntity<>(updateRequest, headers), RoomResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().capacity()).isEqualTo(6);
            assertThat(response.getBody().blockedSpots()).isEqualTo(1);
            assertThat(response.getBody().availableSpots()).isEqualTo(5);
            assertThat(response.getBody().genderRule()).isEqualTo(GenderRule.MALE_ONLY);
        }
    }

    private PropertyResponse createProperty(UUID agencyId) {
        var request = new CreatePropertyRequest("Prop " + UUID.randomUUID().toString().substring(0, 8), "Addr", "City", null);
        var headers = authHeaders(Role.AGENCY_ADMIN, agencyId);
        return restTemplate.exchange(
                "/api/v1/properties", HttpMethod.POST,
                new HttpEntity<>(request, headers), PropertyResponse.class
        ).getBody();
    }

    private RoomResponse createRoom(UUID agencyId, UUID propertyId) {
        var request = new CreateRoomRequest("Room " + UUID.randomUUID().toString().substring(0, 8), 1, 4, 0, GenderRule.MIXED, null);
        var headers = authHeaders(Role.AGENCY_ADMIN, agencyId);
        return restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms", HttpMethod.POST,
                new HttpEntity<>(request, headers), RoomResponse.class
        ).getBody();
    }
}
