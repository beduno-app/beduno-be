package com.beduno.user;

import com.beduno.IntegrationTestBase;
import com.beduno.common.model.PageResponse;
import com.beduno.user.dto.CreateUserRequest;
import com.beduno.user.dto.UpdateUserRequest;
import com.beduno.user.dto.UserResponse;
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

class UserIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        ensureAgencyExists(OTHER_AGENCY_ID);
    }

    @Nested
    class Create {

        @Test
        void shouldCreateUser_whenAgencyAdmin() {
            var request = createRequest(uniqueEmail(), Role.FRONT_DESK);
            var headers = authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID);

            var response = restTemplate.exchange(
                    "/api/v1/users", HttpMethod.POST,
                    new HttpEntity<>(request, headers), UserResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().email()).isEqualTo(request.email());
            assertThat(response.getBody().role()).isEqualTo(Role.FRONT_DESK);
            assertThat(response.getBody().status()).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        void shouldRejectCreate_whenNotAgencyAdmin() {
            var request = createRequest(uniqueEmail(), Role.FRONT_DESK);
            var headers = authHeaders(Role.AGENCY_PLANNER, DEFAULT_AGENCY_ID);

            var response = restTemplate.exchange(
                    "/api/v1/users", HttpMethod.POST,
                    new HttpEntity<>(request, headers), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void shouldRejectDuplicateEmail() {
            var email = uniqueEmail();
            createUser(DEFAULT_AGENCY_ID, email, Role.FRONT_DESK);

            var headers = authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID);
            var response = restTemplate.exchange(
                    "/api/v1/users", HttpMethod.POST,
                    new HttpEntity<>(createRequest(email, Role.FRONT_DESK), headers), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).contains("error.user.email_exists");
        }
    }

    @Nested
    class Read {

        @Test
        void shouldReturnUserById() {
            var created = createUser(DEFAULT_AGENCY_ID, uniqueEmail(), Role.FRONT_DESK);
            var headers = authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID);

            var response = restTemplate.exchange(
                    "/api/v1/users/" + created.id(), HttpMethod.GET,
                    new HttpEntity<>(headers), UserResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().id()).isEqualTo(created.id());
        }

        @Test
        void shouldReturnPagedList() {
            createUser(DEFAULT_AGENCY_ID, uniqueEmail(), Role.FRONT_DESK);
            var headers = authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID);

            var response = restTemplate.exchange(
                    "/api/v1/users?page=0&size=50", HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<PageResponse<UserResponse>>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().content()).isNotEmpty();
        }

        @Test
        void shouldRejectList_whenNotAgencyAdmin() {
            var headers = authHeaders(Role.FRONT_DESK, DEFAULT_AGENCY_ID);

            var response = restTemplate.exchange(
                    "/api/v1/users", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void shouldNotAccessUserFromOtherAgency() {
            var created = createUser(DEFAULT_AGENCY_ID, uniqueEmail(), Role.FRONT_DESK);

            var headers = authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID);
            var response = restTemplate.exchange(
                    "/api/v1/users/" + created.id(), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class Update {

        @Test
        void shouldUpdateUser() {
            var created = createUser(DEFAULT_AGENCY_ID, uniqueEmail(), Role.FRONT_DESK);
            var headers = authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID);

            var updateRequest = new UpdateUserRequest(
                    created.email(), "Updated", "Name", Role.PROPERTY_ADMIN, "EN", List.of(), UserStatus.ACTIVE
            );
            var response = restTemplate.exchange(
                    "/api/v1/users/" + created.id(), HttpMethod.PUT,
                    new HttpEntity<>(updateRequest, headers), UserResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().firstName()).isEqualTo("Updated");
            assertThat(response.getBody().role()).isEqualTo(Role.PROPERTY_ADMIN);
        }

        @Test
        void shouldRejectSelfDeactivation_whenStatusSetToInactiveViaUpdate() {
            // deactivate() refuses to deactivate the caller, but update() accepted
            // status=INACTIVE on the caller's own record -- a way around the guard that locks
            // the admin out of their own agency the moment SEC-01 makes status count.
            var agencyId = UUID.randomUUID();
            ensureAgencyExists(agencyId);
            var self = createUser(agencyId, uniqueEmail(), Role.AGENCY_ADMIN);
            // A second active admin, so a failure here cannot be the last-admin guard instead.
            createUser(agencyId, uniqueEmail(), Role.AGENCY_ADMIN);

            var request = new UpdateUserRequest(
                    self.email(), self.firstName(), self.lastName(), Role.AGENCY_ADMIN,
                    "EN", List.of(), UserStatus.INACTIVE
            );
            var response = restTemplate.exchange(
                    "/api/v1/users/" + self.id(), HttpMethod.PUT,
                    new HttpEntity<>(request, authHeadersForAgencyUser(Role.AGENCY_ADMIN, agencyId, self.id())),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).contains("error.user.cannot_deactivate_self");
        }

        @Test
        void shouldDeactivateOtherUser_whenStatusSetToInactiveViaUpdate() {
            var agencyId = UUID.randomUUID();
            ensureAgencyExists(agencyId);
            var self = createUser(agencyId, uniqueEmail(), Role.AGENCY_ADMIN);
            var target = createUser(agencyId, uniqueEmail(), Role.FRONT_DESK);

            var request = new UpdateUserRequest(
                    target.email(), target.firstName(), target.lastName(), Role.FRONT_DESK,
                    "EN", List.of(), UserStatus.INACTIVE
            );
            var response = restTemplate.exchange(
                    "/api/v1/users/" + target.id(), HttpMethod.PUT,
                    new HttpEntity<>(request, authHeadersForAgencyUser(Role.AGENCY_ADMIN, agencyId, self.id())),
                    UserResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().status()).isEqualTo(UserStatus.INACTIVE);
        }

        @Test
        void shouldRejectDuplicateEmailOnUpdate() {
            var emailA = uniqueEmail();
            var emailB = uniqueEmail();
            createUser(DEFAULT_AGENCY_ID, emailA, Role.FRONT_DESK);
            var userB = createUser(DEFAULT_AGENCY_ID, emailB, Role.FRONT_DESK);

            var headers = authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID);
            var updateRequest = new UpdateUserRequest(
                    emailA, userB.firstName(), userB.lastName(), userB.role(), "EN", List.of(), UserStatus.ACTIVE
            );
            var response = restTemplate.exchange(
                    "/api/v1/users/" + userB.id(), HttpMethod.PUT,
                    new HttpEntity<>(updateRequest, headers), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    class Deactivate {

        @Test
        void shouldDeactivateUser_whenNotLastAdmin() {
            var agencyId = UUID.randomUUID();
            ensureAgencyExists(agencyId);
            createUser(agencyId, uniqueEmail(), Role.AGENCY_ADMIN);
            var target = createUser(agencyId, uniqueEmail(), Role.AGENCY_ADMIN);

            var headers = authHeaders(Role.AGENCY_ADMIN, agencyId);
            var response = restTemplate.exchange(
                    "/api/v1/users/" + target.id(), HttpMethod.DELETE,
                    new HttpEntity<>(headers), Void.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            var getResponse = restTemplate.exchange(
                    "/api/v1/users/" + target.id(), HttpMethod.GET,
                    new HttpEntity<>(headers), UserResponse.class
            );
            assertThat(getResponse.getBody().status()).isEqualTo(UserStatus.INACTIVE);
        }

        @Test
        void shouldRejectDeactivatingLastActiveAdmin() {
            var agencyId = UUID.randomUUID();
            ensureAgencyExists(agencyId);
            var onlyAdmin = createUser(agencyId, uniqueEmail(), Role.AGENCY_ADMIN);

            var headers = authHeaders(Role.AGENCY_ADMIN, agencyId);
            var response = restTemplate.exchange(
                    "/api/v1/users/" + onlyAdmin.id(), HttpMethod.DELETE,
                    new HttpEntity<>(headers), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).contains("error.user.last_admin");
        }

        @Test
        void shouldRejectSelfDeactivation() {
            var agencyId = UUID.randomUUID();
            ensureAgencyExists(agencyId);
            var admin1 = createUser(agencyId, uniqueEmail(), Role.AGENCY_ADMIN);
            createUser(agencyId, uniqueEmail(), Role.AGENCY_ADMIN);

            var headers = authHeadersForAgencyUser(Role.AGENCY_ADMIN, agencyId, admin1.id());
            var response = restTemplate.exchange(
                    "/api/v1/users/" + admin1.id(), HttpMethod.DELETE,
                    new HttpEntity<>(headers), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).contains("error.user.cannot_deactivate_self");
        }

        @Test
        void shouldDeactivateNonAdmin_regardlessOfAdminCount() {
            var target = createUser(DEFAULT_AGENCY_ID, uniqueEmail(), Role.FRONT_DESK);
            var headers = authHeaders(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID);

            var response = restTemplate.exchange(
                    "/api/v1/users/" + target.id(), HttpMethod.DELETE,
                    new HttpEntity<>(headers), Void.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }
    }

    private CreateUserRequest createRequest(String email, Role role) {
        return new CreateUserRequest(email, "supersecretpassword123", "Test", "User", role, "EN", List.of());
    }

    private UserResponse createUser(UUID agencyId, String email, Role role) {
        var headers = authHeaders(Role.AGENCY_ADMIN, agencyId);
        var response = restTemplate.exchange(
                "/api/v1/users", HttpMethod.POST,
                new HttpEntity<>(createRequest(email, role), headers), UserResponse.class
        );
        return response.getBody();
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    /** A token whose subject is an actual persisted user, needed to trigger the self-action guard. */
    private org.springframework.http.HttpHeaders authHeadersForAgencyUser(Role role, UUID agencyId, UUID userId) {
        var token = jwtTokenProvider.generateAccessToken(testUser(role, agencyId, new UUID[0], userId));
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
