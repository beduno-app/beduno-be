package com.beduno.stay;

import com.beduno.IntegrationTestBase;
import com.beduno.bed.BedStatus;
import com.beduno.bed.dto.BedResponse;
import com.beduno.bed.dto.BulkGenerateBedsRequest;
import com.beduno.bed.dto.UpdateBedRequest;
import com.beduno.common.exception.ErrorResponse;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BedAssignmentIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        ensureAgencyExists(OTHER_AGENCY_ID);
    }

    @Nested
    class AutoAssign {

        @Test
        void shouldPickLowestLabelFreeBed() {
            var worker = createWorker(DEFAULT_AGENCY_ID);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 3);
            var lowest = listBeds(DEFAULT_AGENCY_ID, property.id(), room.id()).stream()
                    .min(Comparator.comparing(BedResponse::label)).orElseThrow();

            var stay = createStay(DEFAULT_AGENCY_ID, worker.id(), property.id(), room.id(), null,
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(8));

            assertThat(stay.bedId()).isEqualTo(lowest.id());
            assertThat(stay.bedAutoAssigned()).isTrue();
        }

        @Test
        void shouldSkipOccupiedAndBlockedBeds() {
            var worker1 = createWorker(DEFAULT_AGENCY_ID);
            var worker2 = createWorker(DEFAULT_AGENCY_ID);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 3);
            var beds = listBeds(DEFAULT_AGENCY_ID, property.id(), room.id()).stream()
                    .sorted(Comparator.comparing(BedResponse::label)).toList();

            var dateFrom = LocalDate.now().plusDays(1);
            var dateTo = LocalDate.now().plusDays(8);

            createStay(DEFAULT_AGENCY_ID, worker1.id(), property.id(), room.id(), beds.get(0).id(), dateFrom, dateTo);
            blockBed(DEFAULT_AGENCY_ID, property.id(), room.id(), beds.get(1).id());

            var stay = createStay(DEFAULT_AGENCY_ID, worker2.id(), property.id(), room.id(), null, dateFrom, dateTo);

            assertThat(stay.bedId()).isEqualTo(beds.get(2).id());
            assertThat(stay.bedAutoAssigned()).isTrue();
        }
    }

    @Nested
    class ExplicitAssign {

        @Test
        void shouldSetAutoAssignedFalse_whenBedExplicit() {
            var worker = createWorker(DEFAULT_AGENCY_ID);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 2);
            var chosen = listBeds(DEFAULT_AGENCY_ID, property.id(), room.id()).get(0);

            var stay = createStay(DEFAULT_AGENCY_ID, worker.id(), property.id(), room.id(), chosen.id(),
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(8));

            assertThat(stay.bedId()).isEqualTo(chosen.id());
            assertThat(stay.bedAutoAssigned()).isFalse();
        }

        @Test
        void shouldRejectBlockedBed() {
            var worker = createWorker(DEFAULT_AGENCY_ID);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 1);
            var bed = listBeds(DEFAULT_AGENCY_ID, property.id(), room.id()).get(0);
            blockBed(DEFAULT_AGENCY_ID, property.id(), room.id(), bed.id());

            var request = new CreateStayRequest(worker.id(), property.id(), room.id(), bed.id(),
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(8), null, null);
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID)),
                    ErrorResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().error()).isEqualTo("CONSTRAINT_VIOLATION");
        }

        @Test
        void shouldRejectOccupiedBed() {
            var worker1 = createWorker(DEFAULT_AGENCY_ID);
            var worker2 = createWorker(DEFAULT_AGENCY_ID);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 1);
            var bed = listBeds(DEFAULT_AGENCY_ID, property.id(), room.id()).get(0);

            var dateFrom = LocalDate.now().plusDays(1);
            var dateTo = LocalDate.now().plusDays(8);
            createStay(DEFAULT_AGENCY_ID, worker1.id(), property.id(), room.id(), bed.id(), dateFrom, dateTo);

            var request = new CreateStayRequest(worker2.id(), property.id(), room.id(), bed.id(),
                    dateFrom, dateTo, null, null);
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID)),
                    ErrorResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().error()).isEqualTo("CONSTRAINT_VIOLATION");
        }
    }

    @Nested
    class NoFreeBed {

        @Test
        void shouldFailHard_whenRoomHasNoBeds() {
            var worker = createWorker(DEFAULT_AGENCY_ID);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 0);

            var request = new CreateStayRequest(worker.id(), property.id(), room.id(), null,
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(8), null, null);
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID)),
                    ErrorResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().error()).isEqualTo("CONSTRAINT_VIOLATION");
        }

        @Test
        void shouldFailHard_whenAllBedsOccupied() {
            var worker1 = createWorker(DEFAULT_AGENCY_ID);
            var worker2 = createWorker(DEFAULT_AGENCY_ID);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 1);

            var dateFrom = LocalDate.now().plusDays(1);
            var dateTo = LocalDate.now().plusDays(8);
            createStay(DEFAULT_AGENCY_ID, worker1.id(), property.id(), room.id(), null, dateFrom, dateTo);

            var request = new CreateStayRequest(worker2.id(), property.id(), room.id(), null,
                    dateFrom, dateTo, null, null);
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID)),
                    ErrorResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().error()).isEqualTo("CONSTRAINT_VIOLATION");
        }
    }

    @Nested
    class BoundaryConditions {

        @Test
        void shouldAllowNewStay_whenCheckoutDateEqualsCheckinDate() {
            var worker1 = createWorker(DEFAULT_AGENCY_ID);
            var worker2 = createWorker(DEFAULT_AGENCY_ID);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 1);
            var bed = listBeds(DEFAULT_AGENCY_ID, property.id(), room.id()).get(0);

            var dateFrom = LocalDate.now().plusDays(1);
            var dateTo = dateFrom.plusDays(7);
            createStay(DEFAULT_AGENCY_ID, worker1.id(), property.id(), room.id(), bed.id(), dateFrom, dateTo);

            var request = new CreateStayRequest(worker2.id(), property.id(), room.id(), bed.id(),
                    dateTo, dateTo.plusDays(7), null, null);
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID)),
                    StayResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        void shouldRejectNewStay_whenDatesTrulyOverlap() {
            var worker1 = createWorker(DEFAULT_AGENCY_ID);
            var worker2 = createWorker(DEFAULT_AGENCY_ID);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 1);
            var bed = listBeds(DEFAULT_AGENCY_ID, property.id(), room.id()).get(0);

            var dateFrom = LocalDate.now().plusDays(1);
            var dateTo = dateFrom.plusDays(7);
            createStay(DEFAULT_AGENCY_ID, worker1.id(), property.id(), room.id(), bed.id(), dateFrom, dateTo);

            var request = new CreateStayRequest(worker2.id(), property.id(), room.id(), bed.id(),
                    dateTo.minusDays(1), dateTo.plusDays(6), null, null);
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID)),
                    ErrorResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().error()).isEqualTo("CONSTRAINT_VIOLATION");
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void shouldRejectBedFromOtherAgencyRoom() {
            var worker = createWorker(DEFAULT_AGENCY_ID);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 1);

            var otherProperty = createProperty(OTHER_AGENCY_ID);
            var otherRoom = createRoom(OTHER_AGENCY_ID, otherProperty.id(), 1);
            var otherBed = listBeds(OTHER_AGENCY_ID, otherProperty.id(), otherRoom.id()).get(0);

            var request = new CreateStayRequest(worker.id(), property.id(), room.id(), otherBed.id(),
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(8), null, null);
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID)),
                    ErrorResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().error()).isEqualTo("VALIDATION_ERROR");
        }

        @Test
        void shouldRejectAutoAssign_whenRoomBelongsToOtherAgency() {
            var worker = createWorker(OTHER_AGENCY_ID);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 1);

            var request = new CreateStayRequest(worker.id(), property.id(), room.id(), null,
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(8), null, null);
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID)),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private WorkerResponse createWorker(UUID agencyId) {
        var request = new CreateWorkerRequest(
                "W-" + UUID.randomUUID().toString().substring(0, 8),
                "First", "Last", Gender.MALE, null, null, null, null, null, null
        );
        return restTemplate.exchange(
                "/api/v1/workers", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, agencyId)),
                WorkerResponse.class
        ).getBody();
    }

    private PropertyResponse createProperty(UUID agencyId) {
        var request = new CreatePropertyRequest("Property-" + UUID.randomUUID().toString().substring(0, 8), null, null, null);
        return restTemplate.exchange(
                "/api/v1/properties", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, agencyId)),
                PropertyResponse.class
        ).getBody();
    }

    private RoomResponse createRoom(UUID agencyId, UUID propertyId, int bedCount) {
        var request = new CreateRoomRequest(
                "Room-" + UUID.randomUUID().toString().substring(0, 8),
                null, GenderRule.MIXED, null
        );
        var room = restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, agencyId)),
                RoomResponse.class
        ).getBody();
        if (bedCount > 0) {
            restTemplate.exchange(
                    "/api/v1/properties/" + propertyId + "/rooms/" + room.id() + "/beds/bulk-generate",
                    HttpMethod.POST,
                    new HttpEntity<>(new BulkGenerateBedsRequest(bedCount), authHeaders(Role.AGENCY_ADMIN, agencyId)),
                    new ParameterizedTypeReference<List<BedResponse>>() {}
            );
        }
        return room;
    }

    private List<BedResponse> listBeds(UUID agencyId, UUID propertyId, UUID roomId) {
        return restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms/" + roomId + "/beds", HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN, agencyId)),
                new ParameterizedTypeReference<List<BedResponse>>() {}
        ).getBody();
    }

    private void blockBed(UUID agencyId, UUID propertyId, UUID roomId, UUID bedId) {
        var beds = listBeds(agencyId, propertyId, roomId);
        var label = beds.stream().filter(b -> b.id().equals(bedId)).findFirst().orElseThrow().label();
        restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms/" + roomId + "/beds/" + bedId,
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateBedRequest(label, BedStatus.BLOCKED), authHeaders(Role.AGENCY_ADMIN, agencyId)),
                BedResponse.class
        );
    }

    private StayResponse createStay(UUID agencyId, UUID workerId, UUID propertyId, UUID roomId, UUID bedId,
                                     LocalDate dateFrom, LocalDate dateTo) {
        var request = new CreateStayRequest(workerId, propertyId, roomId, bedId, dateFrom, dateTo, null, null);
        return restTemplate.exchange(
                "/api/v1/stays", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, agencyId)),
                StayResponse.class
        ).getBody();
    }
}
