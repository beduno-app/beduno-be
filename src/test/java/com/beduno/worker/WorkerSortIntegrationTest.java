package com.beduno.worker;

import com.beduno.IntegrationTestBase;
import com.beduno.common.model.PageResponse;
import com.beduno.user.Role;
import com.beduno.worker.dto.CreateWorkerRequest;
import com.beduno.worker.dto.WorkerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sorting on the list endpoints goes into native SQL, so the name a client sends has to resolve to
 * a column. Clients sort by the names they see in the JSON; sending {@code lastName} used to reach
 * Postgres verbatim, fold to {@code lastname}, and fail the whole request with a 500.
 */
class WorkerSortIntegrationTest extends IntegrationTestBase {

    private String marker;

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        marker = "S" + UUID.randomUUID().toString().substring(0, 8);
        createWorker("Ana", marker + "Gamma");
        createWorker("Bob", marker + "Alpha");
        createWorker("Cid", marker + "Beta");
    }

    private void createWorker(String firstName, String lastName) {
        var request = new CreateWorkerRequest(
                "W-" + UUID.randomUUID().toString().substring(0, 8),
                firstName, lastName, Gender.MALE,
                "PL", null, null, null, List.of(), null);
        var response = restTemplate.exchange("/api/v1/workers", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(Role.AGENCY_ADMIN)), WorkerResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private PageResponse<WorkerResponse> list(String query) {
        var response = restTemplate.exchange(
                "/api/v1/workers?search=" + marker + query, HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)),
                new ParameterizedTypeReference<PageResponse<WorkerResponse>>() { });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    void shouldSortByApiFieldName_whenClientSortsByLastName() {
        var page = list("&sort=lastName,asc");

        assertThat(page.content()).extracting(WorkerResponse::lastName)
                .containsExactly(marker + "Alpha", marker + "Beta", marker + "Gamma");
    }

    @Test
    void shouldReverseOrder_whenDirectionIsDescending() {
        var page = list("&sort=lastName,desc");

        assertThat(page.content()).extracting(WorkerResponse::lastName)
                .containsExactly(marker + "Gamma", marker + "Beta", marker + "Alpha");
    }

    @Test
    void shouldSortByFirstName_whenClientSortsByAnotherCamelCaseField() {
        var page = list("&sort=firstName,asc");

        assertThat(page.content()).extracting(WorkerResponse::firstName)
                .containsExactly("Ana", "Bob", "Cid");
    }

    @Test
    void shouldApplyDefaultSort_whenNoSortIsGiven() {
        var page = list("");

        assertThat(page.content()).extracting(WorkerResponse::lastName)
                .containsExactly(marker + "Alpha", marker + "Beta", marker + "Gamma");
    }

    @Test
    void shouldReturnBadRequest_whenSortFieldIsNotSupported() {
        var response = restTemplate.exchange("/api/v1/workers?sort=bogusField,asc", HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("error.sort.unsupported_field");
    }

    /**
     * The column name is deliberately no longer accepted. It only ever worked because the endpoint
     * defaulted to it, which leaked the database's naming into the public contract.
     */
    @Test
    void shouldReturnBadRequest_whenSortFieldIsAColumnName() {
        var response = restTemplate.exchange("/api/v1/workers?sort=last_name,asc", HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldAcceptCamelCaseSort_onStaysAndProperties() {
        var stays = restTemplate.exchange("/api/v1/stays?sort=dateFrom,desc", HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);
        var properties = restTemplate.exchange("/api/v1/properties?sort=createdAt,desc",
                HttpMethod.GET, new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);

        assertThat(stays.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(properties.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
