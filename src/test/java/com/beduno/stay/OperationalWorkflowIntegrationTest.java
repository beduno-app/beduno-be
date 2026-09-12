package com.beduno.stay;

import com.beduno.IntegrationTestBase;
import com.beduno.bed.BedStatus;
import com.beduno.bed.dto.BedResponse;
import com.beduno.bed.dto.BulkGenerateBedsRequest;
import com.beduno.bed.dto.UpdateBedRequest;
import com.beduno.common.exception.ErrorResponse;
import com.beduno.occupancy.dto.InspectionDiscrepancyResponse;
import com.beduno.occupancy.dto.InspectionReportRequest;
import com.beduno.occupancy.dto.InspectionRoomEntry;
import com.beduno.occupancy.dto.OccupancyExceptionResponse;
import com.beduno.occupancy.dto.RoomActualOccupancy;
import com.beduno.occupancy.dto.RoomOccupancyResponse;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.room.GenderRule;
import com.beduno.room.dto.CreateRoomRequest;
import com.beduno.room.dto.RoomResponse;
import com.beduno.stay.dto.CheckInRequest;
import com.beduno.stay.dto.CheckOutRequest;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalWorkflowIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        ensureUserExists(DEFAULT_USER_ID, DEFAULT_AGENCY_ID);
    }

    @Nested
    class CheckInWorkflow {

        @Test
        void shouldCheckIn_whenStayIsExpectedToday() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);

            var stay = createPlannedStay(worker.id(), property.id(), room.id(), LocalDate.now(), LocalDate.now().plusDays(7));
            forceExpectedToday(stay.id());

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/check-in",
                    HttpMethod.POST,
                    new HttpEntity<>(new CheckInRequest(null, null, null), authHeaders(Role.FRONT_DESK)),
                    StayResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().status()).isEqualTo(StayStatus.CHECKED_IN);
        }

        @Test
        void shouldCheckIn_withRoomOverride() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            var altRoom = createRoom(property.id(), 4, 0);

            var stay = createPlannedStay(worker.id(), property.id(), room.id(), LocalDate.now(), LocalDate.now().plusDays(7));
            forceExpectedToday(stay.id());

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/check-in",
                    HttpMethod.POST,
                    new HttpEntity<>(new CheckInRequest(altRoom.id(), null, null), authHeaders(Role.PROPERTY_ADMIN)),
                    StayResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().roomId()).isEqualTo(altRoom.id());
            assertThat(response.getBody().status()).isEqualTo(StayStatus.CHECKED_IN);
        }

        @Test
        void shouldRejectCheckIn_whenFrontDeskTriesOnPlannedStay() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);

            var stay = createPlannedStay(worker.id(), property.id(), room.id(),
                    LocalDate.now().plusDays(5), LocalDate.now().plusDays(12));

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/check-in",
                    HttpMethod.POST,
                    new HttpEntity<>(new CheckInRequest(null, null, null), authHeaders(Role.FRONT_DESK)),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void shouldMarkNoShow_whenStayIsExpectedToday() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);

            var stay = createPlannedStay(worker.id(), property.id(), room.id(), LocalDate.now(), LocalDate.now().plusDays(7));
            forceExpectedToday(stay.id());

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/no-show",
                    HttpMethod.POST,
                    new HttpEntity<>(new NoShowRequest("NO_TRANSPORT"), authHeaders(Role.FRONT_DESK)),
                    StayResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().status()).isEqualTo(StayStatus.NO_SHOW);
        }

        @Test
        void shouldRejectNoShow_whenAgencyPlannerCalls() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);

            var stay = createPlannedStay(worker.id(), property.id(), room.id(), LocalDate.now(), LocalDate.now().plusDays(7));
            forceExpectedToday(stay.id());

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/no-show",
                    HttpMethod.POST,
                    new HttpEntity<>(new NoShowRequest("NO_TRANSPORT"), authHeaders(Role.AGENCY_PLANNER)),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldRejectCheckIn_whenTargetBedOccupied() {
            var worker1 = createWorker();
            var worker2 = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 2, 0);

            var stay = createPlannedStay(worker1.id(), property.id(), room.id(), LocalDate.now(), LocalDate.now().plusDays(7));
            forceExpectedToday(stay.id());

            var beds = listBeds(property.id(), room.id());
            var otherBed = beds.stream().filter(b -> !b.id().equals(stay.bedId())).findFirst().orElseThrow();

            var occupyRequest = new CreateStayRequest(worker2.id(), property.id(), room.id(), otherBed.id(),
                    LocalDate.now(), LocalDate.now().plusDays(7), null, null);
            restTemplate.exchange("/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(occupyRequest, authHeaders(Role.AGENCY_ADMIN)), StayResponse.class);

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/check-in",
                    HttpMethod.POST,
                    new HttpEntity<>(new CheckInRequest(null, otherBed.id(), null), authHeaders(Role.FRONT_DESK)),
                    ErrorResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().error()).isEqualTo("CONSTRAINT_VIOLATION");
        }

        @Test
        void shouldRejectCheckIn_whenTargetBedBlocked() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 2, 1);

            var stay = createPlannedStay(worker.id(), property.id(), room.id(), LocalDate.now(), LocalDate.now().plusDays(7));
            forceExpectedToday(stay.id());

            var beds = listBeds(property.id(), room.id());
            var blockedBed = beds.stream().filter(b -> b.status() == BedStatus.BLOCKED).findFirst().orElseThrow();

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/check-in",
                    HttpMethod.POST,
                    new HttpEntity<>(new CheckInRequest(null, blockedBed.id(), null), authHeaders(Role.FRONT_DESK)),
                    ErrorResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().error()).isEqualTo("CONSTRAINT_VIOLATION");
        }
    }

    @Nested
    class CheckOutWorkflow {

        @Test
        void shouldCheckOut_whenStayIsCheckedIn() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            var stay = checkedInStay(worker.id(), property.id(), room.id());

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/check-out",
                    HttpMethod.POST,
                    new HttpEntity<>(new CheckOutRequest(null), authHeaders(Role.FRONT_DESK)),
                    StayResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().status()).isEqualTo(StayStatus.CHECKED_OUT);
        }

        @Test
        void shouldCheckOut_withActualDateOverride() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            var stay = checkedInStay(worker.id(), property.id(), room.id());

            var actualDate = LocalDate.now().plusDays(3);
            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/check-out",
                    HttpMethod.POST,
                    new HttpEntity<>(new CheckOutRequest(actualDate), authHeaders(Role.PROPERTY_ADMIN)),
                    StayResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().dateTo()).isEqualTo(actualDate);
        }
    }

    @Nested
    class RoomMoveWorkflow {

        @Test
        void shouldMoveToTargetRoom_whenCheckedIn() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            var targetRoom = createRoom(property.id(), 4, 0);
            var stay = checkedInStay(worker.id(), property.id(), room.id());

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/move",
                    HttpMethod.POST,
                    new HttpEntity<>(new MoveRequest(targetRoom.id(), null, null), authHeaders(Role.PROPERTY_ADMIN)),
                    StayResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().roomId()).isEqualTo(targetRoom.id());
            assertThat(response.getBody().status()).isEqualTo(StayStatus.CHECKED_IN);

            var oldStayResponse = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.PROPERTY_ADMIN)),
                    StayResponse.class
            );
            assertThat(oldStayResponse.getBody().status()).isEqualTo(StayStatus.CHECKED_OUT);
        }

        @Test
        void shouldRejectMove_whenSameBed() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            var stay = checkedInStay(worker.id(), property.id(), room.id());

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/move",
                    HttpMethod.POST,
                    new HttpEntity<>(new MoveRequest(room.id(), stay.bedId(), null), authHeaders(Role.PROPERTY_ADMIN)),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void shouldMoveToNewBed_whenSameRoomDifferentBed() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            var stay = checkedInStay(worker.id(), property.id(), room.id());
            var beds = listBeds(property.id(), room.id());
            var otherBed = beds.stream().filter(b -> !b.id().equals(stay.bedId())).findFirst().orElseThrow();

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/move",
                    HttpMethod.POST,
                    new HttpEntity<>(new MoveRequest(room.id(), otherBed.id(), null), authHeaders(Role.PROPERTY_ADMIN)),
                    StayResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().roomId()).isEqualTo(room.id());
            assertThat(response.getBody().bedId()).isEqualTo(otherBed.id());
        }

        @Test
        void shouldRejectMove_whenTargetBedOccupied() {
            var worker = createWorker();
            var otherWorker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            var targetRoom = createRoom(property.id(), 2, 0);
            var stay = checkedInStay(worker.id(), property.id(), room.id());

            var targetBeds = listBeds(property.id(), targetRoom.id());
            var occupiedBed = targetBeds.get(0);
            var occupyRequest = new CreateStayRequest(otherWorker.id(), property.id(), targetRoom.id(), occupiedBed.id(),
                    LocalDate.now(), LocalDate.now().plusDays(7), null, null);
            restTemplate.exchange("/api/v1/stays", HttpMethod.POST,
                    new HttpEntity<>(occupyRequest, authHeaders(Role.AGENCY_ADMIN)), StayResponse.class);

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/move",
                    HttpMethod.POST,
                    new HttpEntity<>(new MoveRequest(targetRoom.id(), occupiedBed.id(), null), authHeaders(Role.PROPERTY_ADMIN)),
                    ErrorResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().error()).isEqualTo("CONSTRAINT_VIOLATION");
        }

        @Test
        void shouldRejectMove_whenTargetBedBlocked() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            var targetRoom = createRoom(property.id(), 1, 1);
            var stay = checkedInStay(worker.id(), property.id(), room.id());

            var targetBeds = listBeds(property.id(), targetRoom.id());
            var blockedBed = targetBeds.get(0);

            var response = restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/move",
                    HttpMethod.POST,
                    new HttpEntity<>(new MoveRequest(targetRoom.id(), blockedBed.id(), null), authHeaders(Role.PROPERTY_ADMIN)),
                    ErrorResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().error()).isEqualTo("CONSTRAINT_VIOLATION");
        }
    }

    @Nested
    class ArrivalsEndpoint {

        @Test
        void shouldReturnArrivals_forPropertyAndDate() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);

            var stay = createPlannedStay(worker.id(), property.id(), room.id(), LocalDate.now(), LocalDate.now().plusDays(7));
            forceExpectedToday(stay.id());

            var response = restTemplate.exchange(
                    "/api/v1/stays/arrivals?propertyId=" + property.id() + "&date=" + LocalDate.now(),
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.FRONT_DESK)),
                    new ParameterizedTypeReference<List<StayResponse>>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .extracting(StayResponse::id)
                    .contains(stay.id());
        }
    }

    @Nested
    class OccupancyEndpoints {

        @Test
        void shouldReturnOccupancy_withCheckedInWorkers() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            checkedInStay(worker.id(), property.id(), room.id());

            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/occupancy?date=" + LocalDate.now(),
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.PROPERTY_ADMIN)),
                    new ParameterizedTypeReference<List<RoomOccupancyResponse>>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var roomEntry = response.getBody().stream()
                    .filter(r -> r.roomId().equals(room.id()))
                    .findFirst();
            assertThat(roomEntry).isPresent();
            assertThat(roomEntry.get().occupiedSpots()).isEqualTo(1);
            assertThat(roomEntry.get().occupants()).hasSize(1);
            assertThat(roomEntry.get().occupants().get(0).workerId()).isEqualTo(worker.id());
        }

        @Test
        void shouldReturnExceptions_whenRoomOverCapacity() {
            var property = createProperty();
            var room = createRoom(property.id(), 2, 0);

            var worker1 = createWorker();
            var worker2 = createWorker();
            checkedInStay(worker1.id(), property.id(), room.id());
            checkedInStay(worker2.id(), property.id(), room.id());
            // Block one of the room's two beds so ACTIVE bed count (1) drops below the two
            // checked-in workers -- the bed-derived equivalent of reducing capacity below occupancy.
            var beds = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/rooms/" + room.id() + "/beds",
                    HttpMethod.GET, new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)),
                    new ParameterizedTypeReference<List<BedResponse>>() {}
            ).getBody();
            var bedToBlock = beds.get(0);
            restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/rooms/" + room.id() + "/beds/" + bedToBlock.id(),
                    HttpMethod.PUT,
                    new HttpEntity<>(new UpdateBedRequest(bedToBlock.label(), BedStatus.BLOCKED), authHeaders(Role.AGENCY_ADMIN)),
                    BedResponse.class);

            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/exceptions?date=" + LocalDate.now(),
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.PROPERTY_ADMIN)),
                    new ParameterizedTypeReference<List<OccupancyExceptionResponse>>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var exception = response.getBody().stream()
                    .filter(e -> e.roomId().equals(room.id()))
                    .findFirst();
            assertThat(exception).isPresent();
            assertThat(exception.get().exceptionType()).isEqualTo("OVER_CAPACITY");
        }
    }

    @Nested
    class InspectionEndpoints {

        @Test
        void shouldReturnInspectionRoster_withExpectedAndCheckedIn() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            checkedInStay(worker.id(), property.id(), room.id());

            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/inspection?date=" + LocalDate.now(),
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.PROPERTY_ADMIN)),
                    new ParameterizedTypeReference<List<InspectionRoomEntry>>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var entry = response.getBody().stream()
                    .filter(r -> r.roomId().equals(room.id()))
                    .findFirst();
            assertThat(entry).isPresent();
            assertThat(entry.get().checkedInOccupants()).hasSize(1);
        }

        @Test
        void shouldRejectInspection_whenFrontDesk() {
            var property = createProperty();

            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/inspection?date=" + LocalDate.now(),
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders(Role.FRONT_DESK)),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldDetectDiscrepancy_whenWorkerExpectedButAbsent() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            checkedInStay(worker.id(), property.id(), room.id());

            var report = new InspectionReportRequest(
                    List.of(new RoomActualOccupancy(room.id(), List.of()))
            );
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/inspection?date=" + LocalDate.now(),
                    HttpMethod.POST,
                    new HttpEntity<>(report, authHeaders(Role.PROPERTY_ADMIN)),
                    InspectionDiscrepancyResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().hasDiscrepancies()).isTrue();
            var roomDiscrepancy = response.getBody().discrepancies().stream()
                    .filter(d -> d.roomId().equals(room.id()))
                    .findFirst();
            assertThat(roomDiscrepancy).isPresent();
            assertThat(roomDiscrepancy.get().items())
                    .extracting(d -> d.discrepancyType())
                    .contains("EXPECTED_NOT_PRESENT");
        }

        @Test
        void shouldReturnNoDiscrepancies_whenAllMatch() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            checkedInStay(worker.id(), property.id(), room.id());

            var report = new InspectionReportRequest(
                    List.of(new RoomActualOccupancy(room.id(), List.of(worker.id())))
            );
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + property.id() + "/inspection?date=" + LocalDate.now(),
                    HttpMethod.POST,
                    new HttpEntity<>(report, authHeaders(Role.PROPERTY_ADMIN)),
                    InspectionDiscrepancyResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().hasDiscrepancies()).isFalse();
        }
    }

    @Nested
    class FullLifecycle {

        @Test
        void shouldCompleteFullStayLifecycle() {
            var worker = createWorker();
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            var altRoom = createRoom(property.id(), 4, 0);

            // 1. Create planned stay
            var stay = createPlannedStay(worker.id(), property.id(), room.id(),
                    LocalDate.now(), LocalDate.now().plusDays(7));
            assertThat(stay.status()).isEqualTo(StayStatus.PLANNED);

            // 2. Scheduler transition (manual trigger)
            forceExpectedToday(stay.id());
            var afterTransition = getStay(stay.id());
            assertThat(afterTransition.status()).isEqualTo(StayStatus.EXPECTED_TODAY);

            // 3. Check arrivals
            var arrivals = getArrivals(property.id(), LocalDate.now());
            assertThat(arrivals).extracting(StayResponse::id).contains(stay.id());

            // 4. Check in
            restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/check-in",
                    HttpMethod.POST,
                    new HttpEntity<>(new CheckInRequest(null, null, null), authHeaders(Role.FRONT_DESK)),
                    StayResponse.class
            );
            assertThat(getStay(stay.id()).status()).isEqualTo(StayStatus.CHECKED_IN);

            // 5. Verify occupancy
            var occupancy = getOccupancy(property.id(), LocalDate.now());
            var roomEntry = occupancy.stream().filter(r -> r.roomId().equals(room.id())).findFirst().orElseThrow();
            assertThat(roomEntry.occupiedSpots()).isEqualTo(1);

            // 6. Move to another room
            restTemplate.exchange(
                    "/api/v1/stays/" + stay.id() + "/move",
                    HttpMethod.POST,
                    new HttpEntity<>(new MoveRequest(altRoom.id(), null, null), authHeaders(Role.PROPERTY_ADMIN)),
                    StayResponse.class
            );

            // 7. Inspect
            var roster = getInspectionRoster(property.id(), LocalDate.now());
            var altRoomEntry = roster.stream().filter(r -> r.roomId().equals(altRoom.id())).findFirst().orElseThrow();
            assertThat(altRoomEntry.checkedInOccupants()).hasSize(1);

            // 8. Check out
            restTemplate.exchange(
                    "/api/v1/stays/" + getLatestStayForWorker(worker.id()).id() + "/check-out",
                    HttpMethod.POST,
                    new HttpEntity<>(new CheckOutRequest(null), authHeaders(Role.FRONT_DESK)),
                    StayResponse.class
            );

            var finalOccupancy = getOccupancy(property.id(), LocalDate.now());
            finalOccupancy.forEach(r -> assertThat(r.occupiedSpots()).isEqualTo(0));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private WorkerResponse createWorker() {
        var request = new CreateWorkerRequest(
                "W-" + UUID.randomUUID().toString().substring(0, 8),
                "Test", "Worker", Gender.MALE,
                null, null, null, null, null, null
        );
        return restTemplate.exchange(
                "/api/v1/workers", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                WorkerResponse.class
        ).getBody();
    }

    private PropertyResponse createProperty() {
        var request = new CreatePropertyRequest(
                "Prop-" + UUID.randomUUID().toString().substring(0, 8), null, null, null);
        return restTemplate.exchange(
                "/api/v1/properties", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                PropertyResponse.class
        ).getBody();
    }

    private RoomResponse createRoom(UUID propertyId, int bedCount, int blockedBedCount) {
        var request = new CreateRoomRequest(
                "Room-" + UUID.randomUUID().toString().substring(0, 8),
                null, GenderRule.MIXED, null
        );
        var room = restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                RoomResponse.class
        ).getBody();
        generateBeds(propertyId, room.id(), bedCount, blockedBedCount);
        return room;
    }

    private List<BedResponse> listBeds(UUID propertyId, UUID roomId) {
        return restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms/" + roomId + "/beds", HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)),
                new ParameterizedTypeReference<List<BedResponse>>() {}
        ).getBody();
    }

    private void generateBeds(UUID propertyId, UUID roomId, int bedCount, int blockedBedCount) {
        if (bedCount <= 0) {
            return;
        }
        var bedsUrl = "/api/v1/properties/" + propertyId + "/rooms/" + roomId + "/beds";
        var beds = restTemplate.exchange(bedsUrl + "/bulk-generate", HttpMethod.POST,
                new HttpEntity<>(new BulkGenerateBedsRequest(bedCount), authHeaders(Role.AGENCY_ADMIN)),
                new ParameterizedTypeReference<List<BedResponse>>() {}
        ).getBody();
        for (int i = 0; i < blockedBedCount && i < beds.size(); i++) {
            var bed = beds.get(i);
            restTemplate.exchange(bedsUrl + "/" + bed.id(), HttpMethod.PUT,
                    new HttpEntity<>(new UpdateBedRequest(bed.label(), BedStatus.BLOCKED), authHeaders(Role.AGENCY_ADMIN)),
                    BedResponse.class);
        }
    }

    private StayResponse createPlannedStay(UUID workerId, UUID propertyId, UUID roomId,
                                           LocalDate dateFrom, LocalDate dateTo) {
        var request = new CreateStayRequest(workerId, propertyId, roomId, null, dateFrom, dateTo, null, null);
        return restTemplate.exchange(
                "/api/v1/stays", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                StayResponse.class
        ).getBody();
    }

    private StayResponse checkedInStay(UUID workerId, UUID propertyId, UUID roomId) {
        var stay = createPlannedStay(workerId, propertyId, roomId,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(7));
        jdbcTemplate.update(
                "UPDATE stays SET status = 'CHECKED_IN', confirmed_by_user_id = ? WHERE id = ?",
                DEFAULT_USER_ID, stay.id()
        );
        return getStay(stay.id());
    }

    private void forceExpectedToday(UUID stayId) {
        jdbcTemplate.update("UPDATE stays SET status = 'EXPECTED_TODAY' WHERE id = ?", stayId);
    }

    private StayResponse getStay(UUID id) {
        return restTemplate.exchange(
                "/api/v1/stays/" + id, HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)),
                StayResponse.class
        ).getBody();
    }

    private List<StayResponse> getArrivals(UUID propertyId, LocalDate date) {
        return restTemplate.exchange(
                "/api/v1/stays/arrivals?propertyId=" + propertyId + "&date=" + date,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.FRONT_DESK)),
                new ParameterizedTypeReference<List<StayResponse>>() {}
        ).getBody();
    }

    private List<RoomOccupancyResponse> getOccupancy(UUID propertyId, LocalDate date) {
        return restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/occupancy?date=" + date,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.PROPERTY_ADMIN)),
                new ParameterizedTypeReference<List<RoomOccupancyResponse>>() {}
        ).getBody();
    }

    private List<InspectionRoomEntry> getInspectionRoster(UUID propertyId, LocalDate date) {
        return restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/inspection?date=" + date,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.PROPERTY_ADMIN)),
                new ParameterizedTypeReference<List<InspectionRoomEntry>>() {}
        ).getBody();
    }

    private StayResponse getLatestStayForWorker(UUID workerId) {
        return restTemplate.exchange(
                "/api/v1/stays?workerId=" + workerId + "&page=0&size=10",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)),
                new ParameterizedTypeReference<com.beduno.common.model.PageResponse<StayResponse>>() {}
        ).getBody().content().stream()
                .filter(s -> s.status() == StayStatus.CHECKED_IN)
                .findFirst().orElseThrow();
    }
}
