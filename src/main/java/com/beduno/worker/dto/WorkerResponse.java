package com.beduno.worker.dto;

import com.beduno.worker.Gender;
import com.beduno.worker.WorkerStatus;

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
