package com.beduno.worker.dto;

import com.beduno.worker.Gender;
import com.beduno.worker.WorkerStatus;

import java.util.UUID;

public record WorkerSummary(
        UUID id,
        String internalId,
        String firstName,
        String lastName,
        Gender gender,
        WorkerStatus status
) {}
