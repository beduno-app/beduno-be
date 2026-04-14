package com.bedok.worker.dto;

import com.bedok.worker.Gender;
import com.bedok.worker.WorkerStatus;

import java.util.UUID;

public record WorkerSummary(
        UUID id,
        String internalId,
        String firstName,
        String lastName,
        Gender gender,
        WorkerStatus status
) {}
