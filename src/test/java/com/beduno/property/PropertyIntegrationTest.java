package com.beduno.property;

import com.beduno.IntegrationTestBase;
import com.beduno.common.model.PageResponse;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.property.dto.UpdatePropertyRequest;
import com.beduno.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        ensureAgencyExists(OTHER_AGENCY_ID);
    }

    @Nested
    class Create {

        @Test
        void shouldCreateProperty_whenAgencyAdmin() {
            var request = new CreatePropertyRequest("Test Property", "123 Main St", "Warsaw", null);
            var headers = authHeaders(Role.AGENCY_ADMIN);
            var response = restTemplate.exchange(
                    "/api/v1/properties", HttpMethod.POST,
                    new HttpEntity<>(request, headers), PropertyResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().name()).isEqualTo("Test Property");
            assertThat(response.getBody().status()).isEqualTo(PropertyStatus.ACTIVE);
        }

        @Test
        void shouldRejectCreate_whenNotAgencyAdmin() {
            var request = new CreatePropertyRequest("Test", "addr", "city", null);
            var headers = authHeaders(Role.PROPERTY_ADMIN);
            var response = restTemplate.exchange(
                    "/api/v1/properties", HttpMethod.POST,
                    new HttpEntity<>(request, headers), String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    class Read {

        @Test
        void shouldReturnPropertyById() {
            var created = createProperty(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.AGENCY_ADMIN);
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + created.id(), HttpMethod.GET,
                    new HttpEntity<>(headers), PropertyResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().id()).isEqualTo(created.id());
        }

        @Test
        void shouldReturnPagedList() {
            createProperty(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.FRONT_DESK);
            var response = restTemplate.exchange(
                    "/api/v1/properties?page=0&size=10", HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<PageResponse<PropertyResponse>>() {}
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().content()).isNotEmpty();
        }
    }

    @Nested
    class TenantIsolation {

        /**
         * Asserting only that the caller's own property is present cannot fail if the agency
         * filter is dropped -- the leak is the other agency's row appearing, so that is what has
         * to be asserted against.
         */
        @Test
        void shouldNotReturnPropertiesFromOtherAgency() {
            var defaultAgencyProperty = createProperty(DEFAULT_AGENCY_ID);
            var otherProperty = createProperty(OTHER_AGENCY_ID);

            var headers = authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID);
            var response = restTemplate.exchange(
                    "/api/v1/properties?page=0&size=100", HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<PageResponse<PropertyResponse>>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().content())
                    .extracting(PropertyResponse::id)
                    .contains(otherProperty.id())
                    .doesNotContain(defaultAgencyProperty.id());
        }

        @Test
        void shouldNotAccessPropertyFromOtherAgency() {
            var propertyInAgency1 = createProperty(DEFAULT_AGENCY_ID);

            var headers = authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID);
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + propertyInAgency1.id(), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void shouldNotDeletePropertyFromOtherAgency() {
            var propertyInAgency1 = createProperty(DEFAULT_AGENCY_ID);

            var headers = authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID);
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + propertyInAgency1.id(), HttpMethod.DELETE,
                    new HttpEntity<>(headers), String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class Update {

        @Test
        void shouldUpdateProperty() {
            var created = createProperty(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.AGENCY_ADMIN);
            var updateRequest = new UpdatePropertyRequest("Updated Name", "New Address", "Gdansk", null, PropertyStatus.INACTIVE);
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + created.id(), HttpMethod.PUT,
                    new HttpEntity<>(updateRequest, headers), PropertyResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().name()).isEqualTo("Updated Name");
            assertThat(response.getBody().status()).isEqualTo(PropertyStatus.INACTIVE);
        }

        @Test
        void shouldAllowPropertyAdminToUpdateOwnProperty() {
            var created = createProperty(DEFAULT_AGENCY_ID);
            var headers = authHeaders(Role.PROPERTY_ADMIN, DEFAULT_AGENCY_ID, new UUID[]{created.id()});
            var updateRequest = new UpdatePropertyRequest("Admin Updated", null, null, null, PropertyStatus.ACTIVE);
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + created.id(), HttpMethod.PUT,
                    new HttpEntity<>(updateRequest, headers), PropertyResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void shouldRejectPropertyAdminUpdatingOtherProperty() {
            var created = createProperty(DEFAULT_AGENCY_ID);
            var otherPropertyId = UUID.randomUUID();
            var headers = authHeaders(Role.PROPERTY_ADMIN, DEFAULT_AGENCY_ID, new UUID[]{otherPropertyId});
            var updateRequest = new UpdatePropertyRequest("Nope", null, null, null, PropertyStatus.ACTIVE);
            var response = restTemplate.exchange(
                    "/api/v1/properties/" + created.id(), HttpMethod.PUT,
                    new HttpEntity<>(updateRequest, headers), String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    protected PropertyResponse createProperty(UUID agencyId) {
        var request = new CreatePropertyRequest("Property " + UUID.randomUUID().toString().substring(0, 8), "Address", "City", null);
        var headers = authHeaders(Role.AGENCY_ADMIN, agencyId);
        var response = restTemplate.exchange(
                "/api/v1/properties", HttpMethod.POST,
                new HttpEntity<>(request, headers), PropertyResponse.class
        );
        return response.getBody();
    }
}
