package com.beduno.worker.dto;

import com.beduno.worker.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CreateWorkerRequest(
        // workers.internal_id is VARCHAR(100); without the bound a longer value reached the
        // column and came back as a 500 instead of a field error.
        @NotBlank @Size(max = 100) String internalId,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotNull Gender gender,
        @Size(max = 100) String nationality,
        @Size(max = 50) String phone,
        @Size(max = 255) String email,
        LocalDate dateOfBirth,
        List<String> tags,
        String notes
) {}
