package com.beduno.room;

import com.beduno.IntegrationTestBase;
import com.beduno.common.model.PageResponse;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.room.dto.CreateRoomRequest;
import com.beduno.room.dto.RoomResponse;
import com.beduno.stay.StayStatus;
import com.beduno.stay.dto.CheckInRequest;
import com.beduno.stay.dto.CreateStayRequest;
import com.beduno.stay.dto.StayResponse;
import com.beduno.user.Role;
import com.beduno.worker.Gender;
import com.beduno.worker.dto.CreateWorkerRequest;
import com.beduno.worker.dto.WorkerResponse;
import org.junit.jupiter.api.BeforeEach;
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
 * currentOccupancy and occupants[] are what the room card renders, and the SPA has been typing and
 * rendering them while the API returned neither.
 */
class RoomOccupantsIntegrationTest extends IntegrationTestBase {

    private PropertyResponse property;
    private RoomResponse room;

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        // check-in stamps confirmed_by_user_id, which is a real foreign key
        ensureUserExists(DEFAULT_USER_ID, DEFAULT_AGENCY_ID);
        property = createProperty();
        room = createRoom("101");
    }

    @Test
    void shouldReportNoOccupants_whenNobodyIsCheckedIn() {
        var fetched = getRoom();

        assertThat(fetched.currentOccupancy()).isZero();
        assertThat(fetched.occupants()).isEmpty();
    }

    @Test
    void shouldReportTheCheckedInWorker_whenAStayIsActive() {
        var worker = createWorker("Jan", "Kowalski");
        checkIn(worker.id());

        var fetched = getRoom();

        assertThat(fetched.currentOccupancy()).isEqualTo(1);
        assertThat(fetched.occupants()).hasSize(1);
        var occupant = fetched.occupants().get(0);
        assertThat(occupant.worker().firstName()).isEqualTo("Jan");
        assertThat(occupant.worker().lastName()).isEqualTo("Kowalski");
        assertThat(occupant.worker().internalId()).isEqualTo(worker.internalId());
        assertThat(occupant.status()).isEqualTo(StayStatus.CHECKED_IN);
        assertThat(occupant.dateFrom()).isEqualTo(LocalDate.now());
    }

    /**
     * A planned stay has not taken the bed, so counting it would misreport the one number the room
     * card exists to convey.
     */
    @Test
    void shouldNotCountPlannedStays_whenTheWorkerHasNotCheckedIn() {
        var worker = createWorker("Anna", "Nowak");
        createStay(worker.id());

        var fetched = getRoom();

        assertThat(fetched.currentOccupancy()).isZero();
    }

    @Test
    void shouldPopulateOccupancy_onTheListEndpointToo() {
        var worker = createWorker("Piotr", "Zieliński");
        checkIn(worker.id());

        var response = restTemplate.exchange(
                "/api/v1/properties/" + property.id() + "/rooms", HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)),
                new ParameterizedTypeReference<PageResponse<RoomResponse>>() { });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().content()).singleElement()
                .satisfies(r -> {
                    assertThat(r.currentOccupancy()).isEqualTo(1);
                    assertThat(r.occupants()).extracting(o -> o.worker().lastName())
                            .containsExactly("Zieliński");
                });
    }

    private RoomResponse getRoom() {
        return restTemplate.exchange(
                "/api/v1/properties/" + property.id() + "/rooms/" + room.id(), HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), RoomResponse.class).getBody();
    }

    private void checkIn(UUID workerId) {
        var stay = createStay(workerId);
        jdbcTemplate.update("UPDATE stays SET status = 'EXPECTED_TODAY' WHERE id = ?", stay.id());
        var response = restTemplate.exchange("/api/v1/stays/" + stay.id() + "/check-in",
                HttpMethod.POST, new HttpEntity<>(new CheckInRequest(null, null),
                        authHeaders(Role.FRONT_DESK)), StayResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private StayResponse createStay(UUID workerId) {
        var request = new CreateStayRequest(workerId, property.id(), room.id(),
                LocalDate.now(), LocalDate.now().plusDays(7), null, null);
        return restTemplate.exchange("/api/v1/stays", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                StayResponse.class).getBody();
    }

    private WorkerResponse createWorker(String firstName, String lastName) {
        var request = new CreateWorkerRequest(
                "W-" + UUID.randomUUID().toString().substring(0, 8),
                firstName, lastName, Gender.MALE, "PL", null, null, null, List.of(), null);
        return restTemplate.exchange("/api/v1/workers", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                WorkerResponse.class).getBody();
    }

    private PropertyResponse createProperty() {
        var request = new CreatePropertyRequest(
                "Prop " + UUID.randomUUID().toString().substring(0, 8), "Addr", "City", null);
        return restTemplate.exchange("/api/v1/properties", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                PropertyResponse.class).getBody();
    }

    private RoomResponse createRoom(String roomNumber) {
        var request = new CreateRoomRequest(roomNumber, 1, 4, 0, GenderRule.MIXED, null);
        return restTemplate.exchange(
                "/api/v1/properties/" + property.id() + "/rooms", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                RoomResponse.class).getBody();
    }
}
