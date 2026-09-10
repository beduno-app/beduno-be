package com.beduno.stay;

import com.beduno.IntegrationTestBase;
import com.beduno.bed.BedStatus;
import com.beduno.bed.dto.BedResponse;
import com.beduno.bed.dto.BulkGenerateBedsRequest;
import com.beduno.bed.dto.UpdateBedRequest;
import com.beduno.common.exception.ErrorResponse;
import com.beduno.common.model.PageResponse;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.room.GenderRule;
import com.beduno.room.dto.CreateRoomRequest;
import com.beduno.room.dto.RoomResponse;
import com.beduno.stay.dto.CreateStayRequest;
import com.beduno.stay.dto.StayResponse;
import com.beduno.stay.dto.UpdateStayRequest;
import com.beduno.user.Role;
import com.beduno.worker.Gender;
import com.beduno.worker.dto.CreateWorkerRequest;
import com.beduno.worker.dto.WorkerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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

class StayIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        ensureAgencyExists(OTHER_AGENCY_ID);
    }

    @Nested
    class Create {

        @Test
        void shouldCreateStay_whenValidRequest() {
            var worker = createWorker(DEFAULT_AGENCY_ID, Gender.MALE);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 4, 0, GenderRule.MIXED);

            var request = new CreateStayRequest(
                    worker.id(), property.id(), room.id(),
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(8),
                    null, null
            );
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                    StayResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(StayStatus.PLANNED);
            assertThat(response.getBody().workerId()).isEqualTo(worker.id());
            assertThat(response.getBody().roomId()).isEqualTo(room.id());
        }

        @Test
        void shouldRejectCreate_whenFrontDesk() {
            var worker = createWorker(DEFAULT_AGENCY_ID, Gender.MALE);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 4, 0, GenderRule.MIXED);

            var request = new CreateStayRequest(
                    worker.id(), property.id(), room.id(),
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(8),
                    null, null
            );
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.FRONT_DESK)),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Disabled("Room-level capacity is retired; BedOccupancyConstraint is a "
                + "no-op until phase 4 wires bed resolution into StayService.create -- see the "
                + "named-beds plan's Critical Implementation Details. Re-enable as a bed-occupied "
                + "equivalent in phase 4.")
        @Test
        void shouldReturnHardViolation_whenRoomAtCapacity() {
            var worker1 = createWorker(DEFAULT_AGENCY_ID, Gender.MALE);
            var worker2 = createWorker(DEFAULT_AGENCY_ID, Gender.MALE);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 1, 0, GenderRule.MIXED);

            var dateFrom = LocalDate.now().plusDays(10);
            var dateTo = LocalDate.now().plusDays(17);

            createStay(worker1.id(), property.id(), room.id(), dateFrom, dateTo, null);

            var request = new CreateStayRequest(
                    worker2.id(), property.id(), room.id(), dateFrom, dateTo, null, null
            );
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                    ErrorResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().error()).isEqualTo("CONSTRAINT_VIOLATION");
            assertThat(response.getBody().details()).isNotEmpty();
        }

        @Test
        void shouldReturnHardViolation_whenWorkerAlreadyBooked() {
            var worker = createWorker(DEFAULT_AGENCY_ID, Gender.MALE);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room1 = createRoom(DEFAULT_AGENCY_ID, property.id(), 4, 0, GenderRule.MIXED);
            var room2 = createRoom(DEFAULT_AGENCY_ID, property.id(), 4, 0, GenderRule.MIXED);

            var dateFrom = LocalDate.now().plusDays(20);
            var dateTo = LocalDate.now().plusDays(27);

            createStay(worker.id(), property.id(), room1.id(), dateFrom, dateTo, null);

            var request = new CreateStayRequest(
                    worker.id(), property.id(), room2.id(), dateFrom, dateTo, null, null
            );
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                    ErrorResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().error()).isEqualTo("CONSTRAINT_VIOLATION");
        }

        @Test
        void shouldReturnSoftViolation_whenGenderMismatch() {
            var femaleWorker = createWorker(DEFAULT_AGENCY_ID, Gender.FEMALE);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var maleRoom = createRoom(DEFAULT_AGENCY_ID, property.id(), 4, 0, GenderRule.MALE_ONLY);

            var request = new CreateStayRequest(
                    femaleWorker.id(), property.id(), maleRoom.id(),
                    LocalDate.now().plusDays(30), LocalDate.now().plusDays(37),
                    null, null
            );
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                    ErrorResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().error()).isEqualTo("CONSTRAINT_VIOLATION");
            assertThat(response.getBody().message()).isEqualTo("error.constraint.soft_violations");
        }

        @Test
        void shouldSucceed_whenGenderMismatchOverridden() {
            var femaleWorker = createWorker(DEFAULT_AGENCY_ID, Gender.FEMALE);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var maleRoom = createRoom(DEFAULT_AGENCY_ID, property.id(), 4, 0, GenderRule.MALE_ONLY);

            var request = new CreateStayRequest(
                    femaleWorker.id(), property.id(), maleRoom.id(),
                    LocalDate.now().plusDays(40), LocalDate.now().plusDays(47),
                    "Emergency placement approved by manager", null
            );
            var response = restTemplate.exchange(
                    "/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                    StayResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().status()).isEqualTo(StayStatus.PLANNED);
        }
    }

    @Nested
    class Read {

        @Test
        void shouldReturnStayById() {
            var worker = createWorker(DEFAULT_AGENCY_ID, Gender.MALE);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 4, 0, GenderRule.MIXED);
            var dateFrom = LocalDate.now().plusDays(50);
            var stay = createStay(worker.id(), property.id(), room.id(), dateFrom, dateFrom.plusDays(7), null);

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.FRONT_DESK)),
                    StayResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().id()).isEqualTo(stay.id());
        }

        @Test
        void shouldReturnPagedList_whenFilteredByWorkerId() {
            var worker = createWorker(DEFAULT_AGENCY_ID, Gender.MALE);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 4, 0, GenderRule.MIXED);
            var dateFrom = LocalDate.now().plusDays(60);
            createStay(worker.id(), property.id(), room.id(), dateFrom, dateFrom.plusDays(7), null);

            var response = restTemplate.exchange(
                    "/api/v1/stays?workerId=" + worker.id() + "&page=0&size=10",
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.AGENCY_PLANNER)),
                    new ParameterizedTypeReference<PageResponse<StayResponse>>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().content())
                    .extracting(StayResponse::workerId)
                    .containsOnly(worker.id());
        }
    }

    @Nested
    class Update {

        @Test
        void shouldUpdateStayDates() {
            var worker = createWorker(DEFAULT_AGENCY_ID, Gender.MALE);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 4, 0, GenderRule.MIXED);
            var dateFrom = LocalDate.now().plusDays(70);
            var stay = createStay(worker.id(), property.id(), room.id(), dateFrom, dateFrom.plusDays(7), null);

            var newDateFrom = dateFrom.plusDays(1);
            var updateRequest = new UpdateStayRequest(
                    room.id(), newDateFrom, newDateFrom.plusDays(7), null, "Updated notes"
            );
            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id(), HttpMethod.PUT,
                    new HttpEntity<>(updateRequest, authHeaders(Role.AGENCY_ADMIN)),
                    StayResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().dateFrom()).isEqualTo(newDateFrom);
        }
    }

    @Nested
    class Cancel {

        @Test
        void shouldCancelStay() {
            var worker = createWorker(DEFAULT_AGENCY_ID, Gender.MALE);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 4, 0, GenderRule.MIXED);
            var dateFrom = LocalDate.now().plusDays(80);
            var stay = createStay(worker.id(), property.id(), room.id(), dateFrom, dateFrom.plusDays(7), null);

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id(), HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)),
                    Void.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            var getResponse = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)),
                    StayResponse.class
            );
            assertThat(getResponse.getBody().status()).isEqualTo(StayStatus.CANCELLED);
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void shouldNotAccessStayFromOtherAgency() {
            var worker = createWorker(DEFAULT_AGENCY_ID, Gender.MALE);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 4, 0, GenderRule.MIXED);
            var dateFrom = LocalDate.now().plusDays(90);
            var stay = createStay(worker.id(), property.id(), room.id(), dateFrom, dateFrom.plusDays(7), null);

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID)),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void shouldNotCancelStayFromOtherAgency() {
            var worker = createWorker(DEFAULT_AGENCY_ID, Gender.MALE);
            var property = createProperty(DEFAULT_AGENCY_ID);
            var room = createRoom(DEFAULT_AGENCY_ID, property.id(), 4, 0, GenderRule.MIXED);
            var dateFrom = LocalDate.now().plusDays(100);
            var stay = createStay(worker.id(), property.id(), room.id(), dateFrom, dateFrom.plusDays(7), null);

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id(), HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID)),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private WorkerResponse createWorker(UUID agencyId, Gender gender) {
        var request = new CreateWorkerRequest(
                "W-" + UUID.randomUUID().toString().substring(0, 8),
                "Test", "Worker", gender,
                null, null, null, null, null, null
        );
        var response = restTemplate.exchange(
                "/api/v1/workers", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, agencyId)),
                WorkerResponse.class
        );
        return response.getBody();
    }

    private PropertyResponse createProperty(UUID agencyId) {
        var request = new CreatePropertyRequest("Property-" + UUID.randomUUID().toString().substring(0, 8), null, null, null);
        var response = restTemplate.exchange(
                "/api/v1/properties", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, agencyId)),
                PropertyResponse.class
        );
        return response.getBody();
    }

    private RoomResponse createRoom(UUID agencyId, UUID propertyId, int bedCount, int blockedBedCount, GenderRule genderRule) {
        var request = new CreateRoomRequest(
                "Room-" + UUID.randomUUID().toString().substring(0, 8),
                null, genderRule, null
        );
        var room = restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, agencyId)),
                RoomResponse.class
        ).getBody();
        if (bedCount > 0) {
            var bedsUrl = "/api/v1/properties/" + propertyId + "/rooms/" + room.id() + "/beds";
            var beds = restTemplate.exchange(bedsUrl + "/bulk-generate", HttpMethod.POST,
                    new HttpEntity<>(new BulkGenerateBedsRequest(bedCount), authHeaders(Role.AGENCY_ADMIN, agencyId)),
                    new ParameterizedTypeReference<List<BedResponse>>() {}
            ).getBody();
            for (int i = 0; i < blockedBedCount && i < beds.size(); i++) {
                var bed = beds.get(i);
                restTemplate.exchange(bedsUrl + "/" + bed.id(), HttpMethod.PUT,
                        new HttpEntity<>(new UpdateBedRequest(bed.label(), BedStatus.BLOCKED), authHeaders(Role.AGENCY_ADMIN, agencyId)),
                        BedResponse.class);
            }
        }
        return room;
    }

    private StayResponse createStay(UUID workerId, UUID propertyId, UUID roomId,
                                    LocalDate dateFrom, LocalDate dateTo, String overrideReason) {
        var request = new CreateStayRequest(workerId, propertyId, roomId, dateFrom, dateTo, overrideReason, null);
        var response = restTemplate.exchange(
                "/api/v1/stays", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                StayResponse.class
        );
        return response.getBody();
    }
}
