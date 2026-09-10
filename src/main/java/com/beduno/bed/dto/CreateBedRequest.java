package com.beduno.bed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateBedRequest(
        @NotBlank @Size(max = 50) String label
) {}
