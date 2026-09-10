package com.beduno.room;

import com.beduno.IntegrationTestBase;
import com.beduno.common.model.PageResponse;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.room.dto.CreateRoomRequest;
import com.beduno.room.dto.RoomResponse;
import com.beduno.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rooms and audit events come from derived and JPQL queries rather than native SQL, so an unknown
 * sort field fails as a PropertyReferenceException instead of unknown-column SQL. Different cause,
 * identical 500 for the caller, and it deserves the same 400 the native endpoints now give.
 */
class RoomSortIntegrationTest extends IntegrationTestBase {

    private PropertyResponse property;

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        property = createProperty();
        createRoom("Room 102");
        createRoom("Room 101");
    }

    private PropertyResponse createProperty() {
        var request = new CreatePropertyRequest(
                "Prop " + UUID.randomUUID().toString().substring(0, 8), "Addr", "City", null);
        return restTemplate.exchange("/api/v1/properties", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                PropertyResponse.class).getBody();
    }

    private void createRoom(String name) {
        var request = new CreateRoomRequest(name, 1, GenderRule.MIXED, null);
        var response = restTemplate.exchange(
                "/api/v1/properties/" + property.id() + "/rooms", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)), RoomResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void shouldSortRoomsByRoomNumber_whenClientSortsByAFieldTheResponseCarries() {
        var response = restTemplate.exchange(
                "/api/v1/properties/" + property.id() + "/rooms?sort=roomNumber,asc", HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)),
                new ParameterizedTypeReference<PageResponse<RoomResponse>>() { });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().content()).extracting(RoomResponse::roomNumber)
                .containsExactly("Room 101", "Room 102");
    }

    /**
     * The inverse of what this asserted before the contract moved: roomNumber is now the field,
     * and "name" -- what the backend used to call it -- is the one that no longer exists.
     */
    @Test
    void shouldReturnBadRequest_whenSortingRoomsByTheOldFieldName() {
        var response = restTemplate.exchange(
                "/api/v1/properties/" + property.id() + "/rooms?sort=name,asc",
                HttpMethod.GET, new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("error.sort.unsupported_field");
    }

    @Test
    void shouldSortAuditEvents_whenSortFieldIsSupported() {
        var response = restTemplate.exchange("/api/v1/audit?sort=createdAt,desc", HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The audit query carried its own ORDER BY, and a Pageable's sort is appended as a second one.
     * Every request to this endpoint was a 500 -- no sort parameter needed, since the controller
     * default supplies one. No test had ever called it.
     */
    @Test
    void shouldListAuditEvents_whenNoSortParameterIsGiven() {
        var response = restTemplate.exchange("/api/v1/audit", HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The casts that give Postgres a type for the null checks must not break the path where the
     * filters are actually supplied.
     */
    @Test
    void shouldListAuditEvents_whenOptionalFiltersAreSupplied() {
        var response = restTemplate.exchange(
                "/api/v1/audit?entityType=WORKER&dateFrom=2020-01-01T00:00:00Z"
                        + "&dateTo=2030-01-01T00:00:00Z&sort=createdAt,desc",
                HttpMethod.GET, new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldReturnBadRequest_whenAuditSortFieldIsNotSupported() {
        var response = restTemplate.exchange("/api/v1/audit?sort=bogusField,desc", HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("error.sort.unsupported_field");
    }
}
