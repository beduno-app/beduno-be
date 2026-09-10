package com.beduno.bed.dto;

import com.beduno.bed.BedStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateBedRequest(
        @NotBlank @Size(max = 50) String label,
        @NotNull BedStatus status
) {}
