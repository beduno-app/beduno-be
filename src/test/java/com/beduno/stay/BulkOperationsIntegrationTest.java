package com.beduno.stay;

import com.beduno.IntegrationTestBase;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.room.GenderRule;
import com.beduno.room.dto.CreateRoomRequest;
import com.beduno.room.dto.RoomResponse;
import com.beduno.stay.dto.BulkAssignRequest;
import com.beduno.stay.dto.BulkAssignRequest.Assignment;
import com.beduno.stay.dto.BulkAssignResult;
import com.beduno.stay.dto.BulkCheckoutRequest;
import com.beduno.stay.dto.BulkCheckoutResult;
import com.beduno.stay.dto.CreateStayRequest;
import com.beduno.stay.dto.StayResponse;
import com.beduno.user.Role;
import com.beduno.worker.Gender;
import com.beduno.worker.dto.CreateWorkerRequest;
import com.beduno.worker.dto.WorkerImportResult;
import com.beduno.worker.dto.WorkerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BulkOperationsIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        ensureUserExists(DEFAULT_USER_ID, DEFAULT_AGENCY_ID);
    }

    @Nested
    class WorkerCsvImport {

        @Test
        void shouldImportWorkers_whenValidCsv() {
            var id1 = "IMP-" + UUID.randomUUID().toString().substring(0, 8);
            var id2 = "IMP-" + UUID.randomUUID().toString().substring(0, 8);
            var csvContent = "internalId,firstName,lastName,gender\n"
                    + id1 + ",Anna,Kowalska,FEMALE\n"
                    + id2 + ",Jan,Nowak,MALE\n";

            var result = postCsvImport(csvContent);

            assertThat(result).isNotNull();
            assertThat(result.created()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(0);
            assertThat(result.errors()).isEqualTo(0);
        }

        @Test
        void shouldSkipDuplicates_whenInternalIdAlreadyExists() {
            var existingId = "IMP-" + UUID.randomUUID().toString().substring(0, 8);
            var csvFirst = "internalId,firstName,lastName,gender\n"
                    + existingId + ",Anna,Kowalska,FEMALE\n";
            postCsvImport(csvFirst);

            var csvSecond = "internalId,firstName,lastName,gender\n"
                    + existingId + ",Anna,Kowalska,FEMALE\n";
            var result = postCsvImport(csvSecond);

            assertThat(result.created()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        void shouldReturnErrors_whenRowsAreInvalid() {
            var id1 = "IMP-" + UUID.randomUUID().toString().substring(0, 8);
            var csvContent = "internalId,firstName,lastName,gender\n"
                    + id1 + ",ValidFirst,ValidLast,MALE\n"
                    + ",Missing,FirstField,MALE\n"
                    + "ID-3,Name,Last,INVALID_GENDER\n";

            var result = postCsvImport(csvContent);

            assertThat(result.created()).isEqualTo(1);
            assertThat(result.errors()).isEqualTo(2);
        }

        @Test
        void shouldRejectImport_whenNotAgencyAdmin() {
            var csvContent = "internalId,firstName,lastName,gender\nW1,First,Last,MALE\n";
            var multipart = buildMultipart(csvContent);
            var headers = authHeaders(Role.AGENCY_PLANNER);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            var response = restTemplate.exchange(
                    "/api/v1/workers/import", HttpMethod.POST,
                    new HttpEntity<>(multipart, headers),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        private WorkerImportResult postCsvImport(String csvContent) {
            var multipart = buildMultipart(csvContent);
            var headers = authHeaders(Role.AGENCY_ADMIN);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            return restTemplate.exchange(
                    "/api/v1/workers/import", HttpMethod.POST,
                    new HttpEntity<>(multipart, headers),
                    WorkerImportResult.class
            ).getBody();
        }

        private LinkedMultiValueMap<String, Object> buildMultipart(String csvContent) {
            var bytes = csvContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return "workers.csv";
                }
            };
            var map = new LinkedMultiValueMap<String, Object>();
            map.add("file", resource);
            return map;
        }
    }

    @Nested
    class BulkAssign {

        @Test
        void shouldAssignAllWorkers_whenAllValid() {
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            var worker1 = createWorker();
            var worker2 = createWorker();

            var request = new BulkAssignRequest(List.of(
                    new Assignment(worker1.id(), property.id(), room.id(),
                            LocalDate.now().plusDays(1), LocalDate.now().plusDays(7), null),
                    new Assignment(worker2.id(), property.id(), room.id(),
                            LocalDate.now().plusDays(1), LocalDate.now().plusDays(7), null)
            ));

            var response = restTemplate.exchange(
                    "/api/v1/stays/bulk-assign", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                    BulkAssignResult.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var result = response.getBody();
            assertThat(result).isNotNull();
            assertThat(result.created()).isEqualTo(2);
            assertThat(result.errors()).isEqualTo(0);
            assertThat(result.results()).allMatch(r -> "created".equals(r.status()));
        }

        @Test
        void shouldReturnPartialSuccess_whenSomeAssignmentsFail() {
            var property = createProperty();
            var room = createRoom(property.id(), 1, 0);
            var worker1 = createWorker();
            var worker2 = createWorker();

            // First worker gets the only available spot
            var checkInDate = LocalDate.now().minusDays(1);
            createAndCheckInStay(worker1.id(), property.id(), room.id(), checkInDate);

            var request = new BulkAssignRequest(List.of(
                    new Assignment(worker2.id(), property.id(), room.id(),
                            checkInDate, LocalDate.now().plusDays(5), null),
                    new Assignment(worker1.id(), property.id(), room.id(),
                            LocalDate.now().plusDays(10), LocalDate.now().plusDays(15), null)
            ));

            var response = restTemplate.exchange(
                    "/api/v1/stays/bulk-assign", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                    BulkAssignResult.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var result = response.getBody();
            assertThat(result).isNotNull();
            assertThat(result.created()).isEqualTo(1);
            assertThat(result.errors()).isEqualTo(1);
        }
    }

    @Nested
    class BulkCheckout {

        @Test
        void shouldCheckOutAllStays_whenAllCheckedIn() {
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            var worker1 = createWorker();
            var worker2 = createWorker();

            var stay1 = createAndCheckInStay(worker1.id(), property.id(), room.id(), LocalDate.now().minusDays(3));
            var stay2 = createAndCheckInStay(worker2.id(), property.id(), room.id(), LocalDate.now().minusDays(2));

            var request = new BulkCheckoutRequest(List.of(stay1.id(), stay2.id()));
            var response = restTemplate.exchange(
                    "/api/v1/stays/bulk-checkout", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                    BulkCheckoutResult.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var result = response.getBody();
            assertThat(result).isNotNull();
            assertThat(result.checkedOut()).isEqualTo(2);
            assertThat(result.errors()).isEqualTo(0);
        }

        @Test
        void shouldReturnPartialSuccess_whenSomeStaysCannotCheckOut() {
            var property = createProperty();
            var room = createRoom(property.id(), 4, 0);
            var worker1 = createWorker();
            var worker2 = createWorker();

            var checkedInStay = createAndCheckInStay(worker1.id(), property.id(), room.id(), LocalDate.now().minusDays(1));
            var plannedStay = createPlannedStay(worker2.id(), property.id(), room.id(),
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(7));

            var request = new BulkCheckoutRequest(List.of(checkedInStay.id(), plannedStay.id()));
            var response = restTemplate.exchange(
                    "/api/v1/stays/bulk-checkout", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                    BulkCheckoutResult.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var result = response.getBody();
            assertThat(result).isNotNull();
            assertThat(result.checkedOut()).isEqualTo(1);
            assertThat(result.errors()).isEqualTo(1);
        }

        @Test
        void shouldRejectBulkCheckout_whenUnknownStayId() {
            var request = new BulkCheckoutRequest(List.of(UUID.randomUUID()));
            var response = restTemplate.exchange(
                    "/api/v1/stays/bulk-checkout", HttpMethod.POST,
                    new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                    BulkCheckoutResult.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var result = response.getBody();
            assertThat(result).isNotNull();
            assertThat(result.errors()).isEqualTo(1);
        }
    }

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
        var request = new CreatePropertyRequest("Prop-" + UUID.randomUUID().toString().substring(0, 8), null, null, null);
        return restTemplate.exchange(
                "/api/v1/properties", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                PropertyResponse.class
        ).getBody();
    }

    private RoomResponse createRoom(UUID propertyId, int capacity, int blockedSpots) {
        var request = new CreateRoomRequest(
                "Room-" + UUID.randomUUID().toString().substring(0, 8),
                null, capacity, blockedSpots, GenderRule.ANY, null
        );
        return restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/rooms", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                RoomResponse.class
        ).getBody();
    }

    private StayResponse createPlannedStay(UUID workerId, UUID propertyId, UUID roomId,
                                           LocalDate dateFrom, LocalDate dateTo) {
        var request = new CreateStayRequest(workerId, propertyId, roomId, dateFrom, dateTo, null, null);
        return restTemplate.exchange(
                "/api/v1/stays", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)),
                StayResponse.class
        ).getBody();
    }

    private StayResponse createAndCheckInStay(UUID workerId, UUID propertyId, UUID roomId, LocalDate dateFrom) {
        var stay = createPlannedStay(workerId, propertyId, roomId, dateFrom, dateFrom.plusDays(7));
        jdbcTemplate.update(
                "UPDATE stays SET status = 'CHECKED_IN', confirmed_by_user_id = ? WHERE id = ?",
                DEFAULT_USER_ID, stay.id()
        );
        return restTemplate.exchange(
                "/api/v1/stays/" + stay.id(), HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)),
                StayResponse.class
        ).getBody();
    }
}
