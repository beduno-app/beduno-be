package com.beduno.bed;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.beduno.IntegrationTestBase;
import com.beduno.bed.dto.BedResponse;
import com.beduno.bed.dto.BulkGenerateBedsRequest;
import com.beduno.bed.dto.CreateBedRequest;
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

import static org.assertj.core.api.Assertions.assertThat;

class BedIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        ensureAgencyExists(OTHER_AGENCY_ID);
    }

    @Nested
    class Create {

        @Test
        void shouldCreateBed_whenAgencyAdmin() {
            var room = createRoom(DEFAULT_AGENCY_ID);
            var request = new CreateBedRequest("A1");
            var headers = authHeaders(Role.AGENCY_ADMIN);
            var response = restTemplate.exchange(
                    bedsUrl(room), HttpMethod.POST, new HttpEntity<>(request, headers), BedResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().label()).isEqualTo("A1");
            assertThat(response.getBody().status()).isEqualTo(BedStatus.ACTIVE);
        }

        @Test
        void shouldRejectDuplicateLabelInSameRoom() {
            var room = createRoom(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.AGENCY_ADMIN);
            createBed(room, "A1");

            var response = restTemplate.exchange(bedsUrl(room), HttpMethod.POST,
                    new HttpEntity<>(new CreateBedRequest("A1"), headers), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    class BulkGenerate {

        @Test
        void shouldGenerateSequentialLabels_onFreshRoom() {
            var room = createRoom(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.AGENCY_ADMIN);

            var response = restTemplate.exchange(bedsUrl(room) + "/bulk-generate", HttpMethod.POST,
                    new HttpEntity<>(new BulkGenerateBedsRequest(3), headers),
                    new ParameterizedTypeReference<List<BedResponse>>() {});

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).extracting(BedResponse::label)
                    .containsExactly("1", "2", "3");
        }

        @Test
        void shouldNotCollideWithRenamedLabel_onNextBulkGenerate() {
            var room = createRoom(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.AGENCY_ADMIN);

            var generated = restTemplate.exchange(bedsUrl(room) + "/bulk-generate", HttpMethod.POST,
                    new HttpEntity<>(new BulkGenerateBedsRequest(2), headers),
                    new ParameterizedTypeReference<List<BedResponse>>() {}).getBody();

            var toRename = generated.get(1);
            restTemplate.exchange(bedsUrl(room) + "/" + toRename.id(), HttpMethod.PUT,
                    new HttpEntity<>(new UpdateBedRequest("top bunk", BedStatus.ACTIVE), headers),
                    BedResponse.class);

            var more = restTemplate.exchange(bedsUrl(room) + "/bulk-generate", HttpMethod.POST,
                    new HttpEntity<>(new BulkGenerateBedsRequest(1), headers),
                    new ParameterizedTypeReference<List<BedResponse>>() {});

            // "2" was renamed to "top bunk", so the highest numeric label is now "1" again --
            // the next bulk-generate reuses "2" and, since "top bunk" isn't numeric, never collides.
            assertThat(more.getBody()).extracting(BedResponse::label).containsExactly("2");
        }
    }

    @Nested
    class BlockAndRead {

        @Test
        void shouldBlockBed_andReflectStatusOnRead() {
            var room = createRoom(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.AGENCY_ADMIN);
            var bed = createBed(room, "A1");

            var updateResponse = restTemplate.exchange(bedsUrl(room) + "/" + bed.id(), HttpMethod.PUT,
                    new HttpEntity<>(new UpdateBedRequest(bed.label(), BedStatus.BLOCKED), headers),
                    BedResponse.class);
            assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(updateResponse.getBody().status()).isEqualTo(BedStatus.BLOCKED);

            var readResponse = restTemplate.exchange(bedsUrl(room) + "/" + bed.id(), HttpMethod.GET,
                    new HttpEntity<>(headers), BedResponse.class);
            assertThat(readResponse.getBody().status()).isEqualTo(BedStatus.BLOCKED);
        }
    }

    @Nested
    class Delete {

        @Test
        void shouldDeleteBed() {
            var room = createRoom(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.AGENCY_ADMIN);
            var bed = createBed(room, "A1");

            var response = restTemplate.exchange(bedsUrl(room) + "/" + bed.id(), HttpMethod.DELETE,
                    new HttpEntity<>(headers), Void.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            var readResponse = restTemplate.exchange(bedsUrl(room) + "/" + bed.id(), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void shouldRejectDelete_whenStayReferencesBed() {
            var room = createRoom(DEFAULT_AGENCY_ID);
            var bed = createBed(room, "A1");
            createStay(DEFAULT_AGENCY_ID, room, bed);

            var response = restTemplate.exchange(bedsUrl(room) + "/" + bed.id(), HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), ErrorResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().error()).isEqualTo("CONFLICT");
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void shouldNotAccessBedFromOtherAgency() {
            var room = createRoom(DEFAULT_AGENCY_ID);
            var bed = createBed(room, "A1");

            var headers = authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID);
            var response = restTemplate.exchange(bedsUrl(room) + "/" + bed.id(), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void shouldNotListBedsFromOtherAgencyRoom() {
            var room = createRoom(DEFAULT_AGENCY_ID);
            createBed(room, "A1");

            var headers = authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID);
            var response = restTemplate.exchange(bedsUrl(room), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private String bedsUrl(RoomResponse room) {
        return "/api/v1/properties/" + room.propertyId() + "/rooms/" + room.id() + "/beds";
    }

    private BedResponse createBed(RoomResponse room, String label) {
        var headers = authHeaders(Role.AGENCY_ADMIN);
        return restTemplate.exchange(bedsUrl(room), HttpMethod.POST,
                new HttpEntity<>(new CreateBedRequest(label), headers), BedResponse.class).getBody();
    }

    private RoomResponse createRoom(UUID agencyId) {
        var property = createProperty(agencyId);
        var request = new CreateRoomRequest("Room " + UUID.randomUUID().toString().substring(0, 8), 1, GenderRule.MIXED, null);
        var headers = authHeaders(Role.AGENCY_ADMIN, agencyId);
        return restTemplate.exchange(
                "/api/v1/properties/" + property.id() + "/rooms", HttpMethod.POST,
                new HttpEntity<>(request, headers), RoomResponse.class
        ).getBody();
    }

    private PropertyResponse createProperty(UUID agencyId) {
        var request = new CreatePropertyRequest("Prop " + UUID.randomUUID().toString().substring(0, 8), "Addr", "City", null);
        var headers = authHeaders(Role.AGENCY_ADMIN, agencyId);
        return restTemplate.exchange(
                "/api/v1/properties", HttpMethod.POST,
                new HttpEntity<>(request, headers), PropertyResponse.class
        ).getBody();
    }

    private StayResponse createStay(UUID agencyId, RoomResponse room, BedResponse bed) {
        var worker = createWorker(agencyId);
        var request = new CreateStayRequest(worker.id(), room.propertyId(), room.id(), bed.id(),
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(8), null, null);
        return restTemplate.exchange(
                "/api/v1/stays", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, agencyId)), StayResponse.class
        ).getBody();
    }

    private WorkerResponse createWorker(UUID agencyId) {
        var request = new CreateWorkerRequest(
                "W-" + UUID.randomUUID().toString().substring(0, 8),
                "First", "Last", Gender.MALE, null, null, null, null, null, null
        );
        return restTemplate.exchange(
                "/api/v1/workers", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN, agencyId)), WorkerResponse.class
        ).getBody();
    }
}
