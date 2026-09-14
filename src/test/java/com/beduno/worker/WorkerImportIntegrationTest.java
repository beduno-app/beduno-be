package com.beduno.worker;

import com.beduno.IntegrationTestBase;
import com.beduno.user.Role;
import com.beduno.worker.dto.WorkerImportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CSV path enforced none of the length limits the JSON path gets from Bean Validation, and
 * entities use GenerationType.UUID -- so save() did not reach the database and the INSERT ran on
 * the next row's duplicate check. An over-long cell in row 12 was therefore reported as a failure
 * of row 13, marked the transaction rollback-only, and the request ended in 500 with no workers
 * created and no error details delivered.
 */
class WorkerImportIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
    }

    @Test
    void shouldCreateWorkers_whenAllRowsAreValid() {
        var csv = header()
                + row("A", "Jan", "Kowalski")
                + row("B", "Anna", "Nowak");

        var result = importCsv(csv);

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.errors()).isZero();
    }

    @Test
    void shouldRejectOnlyTheOffendingRow_whenAFieldIsTooLong() {
        var tooLong = "x".repeat(101);
        var csv = header()
                + row("A", "Jan", "Kowalski")
                + row("B", tooLong, "Nowak")
                + row("C", "Anna", "Nowak");

        var result = importCsv(csv);

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.errors()).isEqualTo(1);
        var error = result.errorDetails().get(0);
        // Row 3 of the file: the header is row 1. Previously the flush deferred the failure and
        // row 4 was blamed for it.
        assertThat(error.row()).isEqualTo(3);
        assertThat(error.reason()).isEqualTo("error.worker.import.field_too_long");
    }

    @Test
    void shouldRejectOnlyTheOffendingRow_whenInternalIdIsTooLong() {
        var csv = header()
                + row("x".repeat(101), "Jan", "Kowalski")
                + row("B", "Anna", "Nowak");

        var result = importCsv(csv);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.errorDetails().get(0).reason()).isEqualTo("error.worker.import.field_too_long");
    }

    @Test
    void shouldReportRowFailure_whenDateOfBirthIsUnparseable() {
        var csv = header()
                + "A" + suffix + ",Jan,Kowalski,MALE,,,,not-a-date,,\n"
                + row("B", "Anna", "Nowak");

        var result = importCsv(csv);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.errorDetails().get(0).reason()).isEqualTo("error.worker.import.row_failed");
    }

    private final String suffix = "-" + UUID.randomUUID().toString().substring(0, 8);

    private String header() {
        return "internalId,firstName,lastName,gender,nationality,phone,email,dateOfBirth,tags,notes\n";
    }

    private String row(String internalId, String firstName, String lastName) {
        return internalId + suffix + "," + firstName + "," + lastName + ",MALE,,,,,,\n";
    }

    private WorkerImportResult importCsv(String csv) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "workers.csv";
            }
        });

        var headers = new HttpHeaders();
        headers.addAll(authHeaders(Role.AGENCY_ADMIN));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        var response = restTemplate.exchange(
                "/api/v1/workers/import", HttpMethod.POST,
                new HttpEntity<>(body, headers), WorkerImportResult.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
