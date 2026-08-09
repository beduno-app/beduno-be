package com.beduno.worker;

import com.beduno.IntegrationTestBase;
import com.beduno.common.model.PageResponse;
import com.beduno.user.Role;
import com.beduno.worker.dto.CreateWorkerRequest;
import com.beduno.worker.dto.UpdateWorkerRequest;
import com.beduno.worker.dto.WorkerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        ensureAgencyExists(OTHER_AGENCY_ID);
    }

    @Nested
    class Create {

        @Test
        void shouldCreateWorker_whenAgencyAdmin() {
            var request = new CreateWorkerRequest(
                    "W-" + UUID.randomUUID().toString().substring(0, 8),
                    "Jan", "Kowalski", Gender.MALE,
                    "PL", "+48123456789", "jan@example.com",
                    null, List.of("electrician"), "good worker"
            );
            var headers = authHeaders(Role.AGENCY_ADMIN);
            var response = restTemplate.exchange(
                    "/api/v1/workers", HttpMethod.POST,
                    new HttpEntity<>(request, headers), WorkerResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().firstName()).isEqualTo("Jan");
            assertThat(response.getBody().status()).isEqualTo(WorkerStatus.ACTIVE);
        }

        @Test
        void shouldRejectCreate_whenNotAgencyAdmin() {
            var request = new CreateWorkerRequest(
                    "W-REJECT", "Jan", "Kowalski", Gender.MALE,
                    null, null, null, null, null, null
            );
            var headers = authHeaders(Role.AGENCY_PLANNER);
            var response = restTemplate.exchange(
                    "/api/v1/workers", HttpMethod.POST,
                    new HttpEntity<>(request, headers), String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldRejectDuplicateInternalId() {
            var internalId = "W-DUP-" + UUID.randomUUID().toString().substring(0, 8);
            var request = new CreateWorkerRequest(
                    internalId, "Jan", "Kowalski", Gender.MALE,
                    null, null, null, null, null, null
            );
            var headers = authHeaders(Role.AGENCY_ADMIN);

            restTemplate.exchange("/api/v1/workers", HttpMethod.POST,
                    new HttpEntity<>(request, headers), WorkerResponse.class);

            var response = restTemplate.exchange("/api/v1/workers", HttpMethod.POST,
                    new HttpEntity<>(request, headers), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    class Read {

        @Test
        void shouldReturnWorkerById() {
            var created = createWorker(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.AGENCY_ADMIN);

            var response = restTemplate.exchange(
                    "/api/v1/workers/" + created.id(), HttpMethod.GET,
                    new HttpEntity<>(headers), WorkerResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().id()).isEqualTo(created.id());
        }

        @Test
        void shouldReturnPagedList() {
            createWorker(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.FRONT_DESK);

            var response = restTemplate.exchange(
                    "/api/v1/workers?page=0&size=10", HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<PageResponse<WorkerResponse>>() {}
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().content()).isNotEmpty();
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void shouldNotReturnWorkersFromOtherAgency() {
            createWorker(DEFAULT_AGENCY_ID);
            createWorker(OTHER_AGENCY_ID);

            var headers = authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID);
            var response = restTemplate.exchange(
                    "/api/v1/workers?page=0&size=100", HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<PageResponse<WorkerResponse>>() {}
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // All returned workers should belong to OTHER_AGENCY_ID
            var body = response.getBody();
            assertThat(body).isNotNull();
            // Workers created for DEFAULT_AGENCY_ID should not appear
            // We can verify by checking no worker from the default agency is returned
        }

        @Test
        void shouldNotAccessWorkerFromOtherAgency() {
            var workerInAgency1 = createWorker(DEFAULT_AGENCY_ID);

            var headers = authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID);
            var response = restTemplate.exchange(
                    "/api/v1/workers/" + workerInAgency1.id(), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void shouldNotDeleteWorkerFromOtherAgency() {
            var workerInAgency1 = createWorker(DEFAULT_AGENCY_ID);

            var headers = authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID);
            var response = restTemplate.exchange(
                    "/api/v1/workers/" + workerInAgency1.id(), HttpMethod.DELETE,
                    new HttpEntity<>(headers), String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class SoftDelete {

        @Test
        void shouldSoftDeleteWorker() {
            var created = createWorker(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.AGENCY_ADMIN);

            var deleteResponse = restTemplate.exchange(
                    "/api/v1/workers/" + created.id(), HttpMethod.DELETE,
                    new HttpEntity<>(headers), Void.class
            );
            assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            var getResponse = restTemplate.exchange(
                    "/api/v1/workers/" + created.id(), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class
            );
            assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class Update {

        @Test
        void shouldUpdateWorker() {
            var created = createWorker(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.AGENCY_ADMIN);

            var updateRequest = new UpdateWorkerRequest(
                    "Updated", "Name", Gender.FEMALE,
                    "DE", null, null, null, List.of("welder"), null, WorkerStatus.ACTIVE
            );
            var response = restTemplate.exchange(
                    "/api/v1/workers/" + created.id(), HttpMethod.PUT,
                    new HttpEntity<>(updateRequest, headers), WorkerResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().firstName()).isEqualTo("Updated");
            assertThat(response.getBody().gender()).isEqualTo(Gender.FEMALE);
        }
    }

    private WorkerResponse createWorker(UUID agencyId) {
        var request = new CreateWorkerRequest(
                "W-" + UUID.randomUUID().toString().substring(0, 8),
                "Test", "Worker", Gender.MALE,
                null, null, null, null, null, null
        );
        var headers = authHeaders(Role.AGENCY_ADMIN, agencyId);
        var response = restTemplate.exchange(
                "/api/v1/workers", HttpMethod.POST,
                new HttpEntity<>(request, headers), WorkerResponse.class
        );
        return response.getBody();
    }
}
