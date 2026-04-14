package com.bedok.worker.dto;

import com.bedok.worker.Gender;
import com.bedok.worker.WorkerStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record WorkerResponse(
        UUID id,
        String internalId,
        String firstName,
        String lastName,
        Gender gender,
        String nationality,
        String phone,
        String email,
        LocalDate dateOfBirth,
        List<String> tags,
        String notes,
        WorkerStatus status,
        Instant createdAt,
        Instant updatedAt
) {}
