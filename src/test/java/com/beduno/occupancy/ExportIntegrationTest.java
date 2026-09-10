package com.beduno.occupancy;

import com.beduno.IntegrationTestBase;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.room.GenderRule;
import com.beduno.room.dto.CreateRoomRequest;
import com.beduno.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The export keys used to exist only in the EN and PL bundles while
 * {@code ExportService.toLocale} accepted DE, RU and UA. Because {@code msg()}
 * falls back to the key itself, those languages produced a CSV whose header row
 * was the literal string {@code export.occupancy.header}. These assert every
 * supported language now resolves.
 */
class ExportIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
    }

    @Nested
    class OccupancyExport {

        @Test
        void shouldReturnEnglishHeader_whenLanguageIsOmitted() {
            var property = createPropertyWithRoom();

            var body = export(property, null);

            assertThat(body).startsWith("Room,Floor,BedCount,AvailableBedCount,Occupied,Bed,WorkerId,FirstName,LastName");
        }

        @Test
        void shouldReturnGermanHeader_whenLanguageIsDe() {
            var property = createPropertyWithRoom();

            var body = export(property, "DE");

            assertThat(body).startsWith("Zimmer,Etage,Bettenzahl,VerfuegbareBetten,Belegt,Bett,MitarbeiterId,Vorname,Nachname");
            assertThat(body).doesNotContain("export.occupancy.header");
        }

        @Test
        void shouldReturnUkrainianHeader_whenLanguageIsUa() {
            // The API takes "UA" but Ukrainian's ISO 639-1 code is "uk", so the bundle
            // must be messages_uk.properties or this silently falls back to English.
            var property = createPropertyWithRoom();

            var body = export(property, "UA");

            assertThat(body).startsWith("Кімната,Поверх,КількістьЛіжок,ВільніЛіжка,Зайнято,Ліжко,ІдПрацівника,Ім'я,Прізвище");
        }

        @Test
        void shouldReturnADistinctHeader_forEverySupportedLanguage() {
            var property = createPropertyWithRoom();
            var seen = new java.util.HashMap<String, String>();

            for (var language : new String[] {"EN", "PL", "DE", "RU", "UA"}) {
                var header = export(property, language).lines().findFirst().orElseThrow();

                assertThat(header)
                        .as("header for %s must be translated, not a raw message key", language)
                        .doesNotContain("export.occupancy.header");
                assertThat(seen)
                        .as("header for %s must not silently fall back to another language", language)
                        .doesNotContainValue(header);
                seen.put(language, header);
            }
        }
    }

    private String export(UUID propertyId, String language) {
        var url = "/api/v1/properties/" + propertyId + "/occupancy/export"
                + (language != null ? "?language=" + language : "");
        var response = restTemplate.exchange(
                url, HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private UUID createPropertyWithRoom() {
        var property = restTemplate.exchange(
                "/api/v1/properties", HttpMethod.POST,
                new HttpEntity<>(new CreatePropertyRequest(
                        "Property-" + UUID.randomUUID().toString().substring(0, 8), null, null, null),
                        authHeaders(Role.AGENCY_ADMIN)),
                PropertyResponse.class).getBody();

        restTemplate.exchange(
                "/api/v1/properties/" + property.id() + "/rooms", HttpMethod.POST,
                new HttpEntity<>(new CreateRoomRequest(
                        "Room-" + UUID.randomUUID().toString().substring(0, 8),
                        1, GenderRule.MIXED, null),
                        authHeaders(Role.AGENCY_ADMIN)),
                String.class);

        return property.id();
    }
}
